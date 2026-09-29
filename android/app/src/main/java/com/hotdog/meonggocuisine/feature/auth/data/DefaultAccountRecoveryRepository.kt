package com.hotdog.meonggocuisine.feature.auth.data

import com.hotdog.meonggocuisine.core.network.ApiErrorResponse
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject

class DefaultAccountRecoveryRepository
    @Inject
    constructor(
        private val authApi: AuthApi,
        private val json: Json,
    ) : AccountRecoveryRepository {
        override suspend fun requestCode(phoneNumber: String): RecoveryCodeRequestResult =
            try {
                val response = authApi.requestAccountRecoveryCode(AccountRecoveryPhoneRequest(phoneNumber))
                if (response.isSuccessful && response.body() != null) {
                    RecoveryCodeRequestResult.Success
                } else {
                    val error = response.errorBody()?.string()?.let(::parseError)
                    when (error?.code) {
                        "COMMON-001" -> RecoveryCodeRequestResult.InvalidPhone(error.message)
                        "PHONE-001", "PHONE-004" ->
                            RecoveryCodeRequestResult.TemporarilyUnavailable(
                                message = error.message,
                                retryAfterSeconds = response.retryAfterSeconds(),
                            )
                        else -> RecoveryCodeRequestResult.Failure(error?.message ?: REQUEST_FAILURE)
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: IOException) {
                RecoveryCodeRequestResult.Failure(CONNECTION_FAILURE)
            } catch (_: Exception) {
                RecoveryCodeRequestResult.Failure(REQUEST_FAILURE)
            }

        override suspend fun confirmCode(
            phoneNumber: String,
            verificationCode: String,
        ): RecoveryConfirmationResult =
            try {
                val response =
                    authApi.confirmAccountRecoveryCode(
                        AccountRecoveryConfirmationRequest(phoneNumber = phoneNumber, verificationCode = verificationCode),
                    )
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    RecoveryConfirmationResult.Success(loginId = body.data.loginId, recoveryToken = body.data.recoveryToken)
                } else {
                    val error = response.errorBody()?.string()?.let(::parseError)
                    when (error?.code) {
                        "COMMON-001", "PHONE-002" -> RecoveryConfirmationResult.InvalidCode(error.message)
                        else -> RecoveryConfirmationResult.Failure(error?.message ?: CONFIRM_FAILURE)
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: IOException) {
                RecoveryConfirmationResult.Failure(CONNECTION_FAILURE)
            } catch (_: Exception) {
                RecoveryConfirmationResult.Failure(CONFIRM_FAILURE)
            }

        override suspend fun resetPassword(
            loginId: String,
            recoveryToken: String,
            newPassword: String,
        ): PasswordResetResult =
            try {
                val response =
                    authApi.resetPasswordByRecovery(
                        PasswordResetRequest(loginId = loginId, recoveryToken = recoveryToken, newPassword = newPassword),
                    )
                if (response.isSuccessful) {
                    PasswordResetResult.Success
                } else {
                    val error = response.errorBody()?.string()?.let(::parseError)
                    when (error?.code) {
                        "COMMON-001" ->
                            PasswordResetResult.InvalidPassword(
                                error.data?.fieldErrors?.firstOrNull { it.field == "newPassword" }?.reason ?: error.message,
                            )
                        "MEMBER-003" -> PasswordResetResult.Unchanged(error.message)
                        "PHONE-002" -> PasswordResetResult.ProofExpired(error.message)
                        else -> PasswordResetResult.Failure(error?.message ?: RESET_FAILURE)
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: IOException) {
                PasswordResetResult.Failure(CONNECTION_FAILURE)
            } catch (_: Exception) {
                PasswordResetResult.Failure(RESET_FAILURE)
            }

        private fun parseError(value: String): ApiErrorResponse? =
            runCatching {
                json.decodeFromString<ApiErrorResponse>(
                    value,
                )
            }.getOrNull()

        private fun retrofit2.Response<*>.retryAfterSeconds(): Int = headers()["Retry-After"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1

        private companion object {
            const val CONNECTION_FAILURE = "서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요."
            const val REQUEST_FAILURE = "인증 코드 요청 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."
            const val CONFIRM_FAILURE = "휴대전화 인증 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."
            const val RESET_FAILURE = "비밀번호를 바꾸지 못했습니다. 잠시 후 다시 시도해 주세요."
        }
    }
