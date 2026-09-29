package com.hotdog.meonggocuisine.feature.auth.data

import com.hotdog.meonggocuisine.core.network.ApiErrorResponse
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject

class DefaultSignupRepository
    @Inject
    constructor(
        private val authApi: AuthApi,
        private val json: Json,
    ) : SignupRepository {
        override suspend fun checkLoginIdAvailability(loginId: String): LoginIdAvailabilityResult =
            try {
                val response = authApi.checkLoginIdAvailability(canonicalizeLoginId(loginId))
                val body = response.body()
                when {
                    response.isSuccessful && body != null ->
                        if (body.data.available) {
                            LoginIdAvailabilityResult.Available
                        } else {
                            LoginIdAvailabilityResult.Taken
                        }
                    // 구버전 서버는 이 엔드포인트를 모른다. 가입을 막을 일은 아니다.
                    //
                    // 401 도 여기로 묶는다. Spring Security 는 모르는 경로를 404 가 아니라 인증
                    // 필요(401)로 돌려주므로, 없는 창구와 로그인이 필요한 창구가 같은 코드로 온다.
                    // 이 확인은 가입 전에 쓰는 것이라 구현되면 비로그인 허용이 될 자리다.
                    response.code() == HTTP_NOT_FOUND || response.code() == HTTP_UNAUTHORIZED ->
                        LoginIdAvailabilityResult.NotSupportedYet
                    response.code() == HTTP_CONFLICT -> LoginIdAvailabilityResult.Taken
                    else ->
                        LoginIdAvailabilityResult.Failure(
                            response.errorBody()?.string()?.let(::parseError)?.message
                                ?: LOGIN_ID_CHECK_FAILURE,
                        )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                LoginIdAvailabilityResult.Failure(CONNECTION_FAILURE)
            } catch (_: Exception) {
                LoginIdAvailabilityResult.Failure(LOGIN_ID_CHECK_FAILURE)
            }

        override suspend fun requestPhoneCode(
            phoneNumber: String,
            privacyCollectionAgreed: Boolean,
        ): PhoneCodeRequestResult =
            try {
                val response =
                    authApi.requestPhoneVerification(
                        PhoneVerificationRequest(
                            phoneNumber = phoneNumber,
                            privacyCollectionAgreed = privacyCollectionAgreed,
                            privacyCollectionPolicyVersion = PRIVACY_COLLECTION_POLICY_VERSION,
                        ),
                    )
                if (response.isSuccessful && response.body() != null) {
                    PhoneCodeRequestResult.Success
                } else {
                    val error = response.errorBody()?.string()?.let(::parseError)
                    when (error?.code) {
                        "COMMON-001" -> PhoneCodeRequestResult.InvalidPhone(error.message)
                        "PHONE-001", "PHONE-004" ->
                            PhoneCodeRequestResult.TemporarilyUnavailable(
                                message = error.message,
                                retryAfterSeconds = response.retryAfterSeconds(),
                            )
                        else -> PhoneCodeRequestResult.Failure(error?.message ?: PHONE_REQUEST_FAILURE)
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: IOException) {
                PhoneCodeRequestResult.Failure(CONNECTION_FAILURE)
            } catch (_: Exception) {
                PhoneCodeRequestResult.Failure(PHONE_REQUEST_FAILURE)
            }

        override suspend fun confirmPhoneCode(
            phoneNumber: String,
            verificationCode: String,
            privacyCollectionAgreed: Boolean,
        ): PhoneConfirmationResult =
            try {
                val response =
                    authApi.confirmPhoneVerification(
                        PhoneConfirmationRequest(
                            phoneNumber = phoneNumber,
                            verificationCode = verificationCode,
                            privacyCollectionAgreed = privacyCollectionAgreed,
                            privacyCollectionPolicyVersion = PRIVACY_COLLECTION_POLICY_VERSION,
                        ),
                    )
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    PhoneConfirmationResult.Success(
                        verificationToken = body.data.phoneVerificationToken,
                        expiresAt = body.data.expiresAt,
                    )
                } else {
                    val error = response.errorBody()?.string()?.let(::parseError)
                    when (error?.code) {
                        "COMMON-001", "PHONE-002" ->
                            PhoneConfirmationResult.InvalidCode(
                                error.message,
                            )
                        "PHONE-003" -> PhoneConfirmationResult.PhoneInUse(error.message)
                        else -> PhoneConfirmationResult.Failure(error?.message ?: PHONE_CONFIRM_FAILURE)
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: IOException) {
                PhoneConfirmationResult.Failure(CONNECTION_FAILURE)
            } catch (_: Exception) {
                PhoneConfirmationResult.Failure(PHONE_CONFIRM_FAILURE)
            }

        override suspend fun signup(
            loginId: String,
            password: String,
            nickname: String,
            phoneNumber: String,
            phoneVerificationToken: String,
            privacyCollectionAgreed: Boolean,
        ): SignupResult =
            try {
                val response =
                    authApi.signup(
                        SignupRequest(
                            loginId = canonicalizeLoginId(loginId),
                            password = Normalizer.normalize(password, Normalizer.Form.NFC),
                            nickname = Normalizer.normalize(nickname, Normalizer.Form.NFC).trim(),
                            phoneNumber = phoneNumber,
                            phoneVerificationToken = phoneVerificationToken,
                            privacyCollectionAgreed = privacyCollectionAgreed,
                            privacyCollectionPolicyVersion = PRIVACY_COLLECTION_POLICY_VERSION,
                        ),
                    )
                if (response.isSuccessful && response.body() != null) {
                    SignupResult.Success
                } else {
                    val error = response.errorBody()?.string()?.let(::parseError)
                    when (error?.code) {
                        "COMMON-001" ->
                            SignupResult.InvalidInput(
                                message = error.message,
                                fieldErrors = error.data?.fieldErrors.orEmpty().associate { it.field to it.reason },
                            )
                        "MEMBER-001" -> SignupResult.LoginIdInUse(error.message)
                        "MEMBER-002" -> SignupResult.ConsentRequired(error.message)
                        "PHONE-002" -> SignupResult.PhoneInvalid(error.message)
                        "PHONE-003" -> SignupResult.PhoneInUse(error.message)
                        "AUTH-006" ->
                            SignupResult.TemporarilyUnavailable(
                                message = error.message,
                                retryAfterSeconds = response.retryAfterSeconds(),
                            )
                        else -> SignupResult.Failure(error?.message ?: SIGNUP_FAILURE)
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: IOException) {
                SignupResult.Failure(CONNECTION_FAILURE)
            } catch (_: Exception) {
                SignupResult.Failure(SIGNUP_FAILURE)
            }

        private fun parseError(value: String): ApiErrorResponse? =
            runCatching { json.decodeFromString<ApiErrorResponse>(value) }.getOrNull()

        private fun retrofit2.Response<*>.retryAfterSeconds(): Int = headers()["Retry-After"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1

        private fun canonicalizeLoginId(loginId: String): String =
            Normalizer.normalize(
                Normalizer.normalize(loginId, Normalizer.Form.NFC).lowercase(Locale.ROOT),
                Normalizer.Form.NFC,
            )

        private companion object {
            const val CONNECTION_FAILURE = "서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요."
            const val PHONE_REQUEST_FAILURE = "인증 코드 요청 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."
            const val PHONE_CONFIRM_FAILURE = "휴대전화 인증 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."
            const val SIGNUP_FAILURE = "회원가입 처리 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."
            const val LOGIN_ID_CHECK_FAILURE = "아이디를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요."
            const val HTTP_UNAUTHORIZED = 401
            const val HTTP_NOT_FOUND = 404
            const val HTTP_CONFLICT = 409
        }
    }
