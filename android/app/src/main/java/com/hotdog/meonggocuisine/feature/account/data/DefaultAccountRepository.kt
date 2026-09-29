package com.hotdog.meonggocuisine.feature.account.data

import com.hotdog.meonggocuisine.core.network.ApiErrorResponse
import com.hotdog.meonggocuisine.feature.auth.data.AuthMember
import com.hotdog.meonggocuisine.feature.auth.data.AuthSessionManager
import com.hotdog.meonggocuisine.feature.push.data.PushDeviceLifecycle
import kotlinx.serialization.json.Json
import retrofit2.Response
import java.io.IOException
import java.text.Normalizer
import javax.inject.Inject

class DefaultAccountRepository
    @Inject
    constructor(
        private val accountApi: AccountApi,
        private val authSessionManager: AuthSessionManager,
        private val pushDeviceLifecycle: PushDeviceLifecycle,
        private val json: Json,
    ) : AccountRepository {
        override suspend fun loadProfile(): ProfileResult =
            try {
                val response = accountApi.profile()
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    authSessionManager.updateMember(AuthMember(body.data.memberId, body.data.nickname))
                    ProfileResult.Loaded(body.data.nickname)
                } else {
                    when (val failure = reject(response)) {
                        AccountResult.SessionExpired -> ProfileResult.SessionExpired
                        is AccountResult.Rejected -> ProfileResult.Failed(failure.message)
                        AccountResult.Success -> ProfileResult.Failed(GENERIC_ERROR)
                    }
                }
            } catch (_: IOException) {
                ProfileResult.Failed(CONNECTION_ERROR)
            } catch (_: Exception) {
                ProfileResult.Failed(GENERIC_ERROR)
            }

        override suspend fun changeNickname(nickname: String): AccountResult =
            request {
                val response = accountApi.changeNickname(NicknameUpdateRequest(nfc(nickname).trim()))
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    authSessionManager.updateMember(AuthMember(body.data.memberId, body.data.nickname))
                    AccountResult.Success
                } else {
                    reject(response)
                }
            }

        override suspend fun changePassword(
            currentPassword: String,
            newPassword: String,
        ): AccountResult =
            request {
                val response =
                    accountApi.changePassword(
                        PasswordChangeRequest(currentPassword = nfc(currentPassword), newPassword = nfc(newPassword)),
                    )
                if (response.isSuccessful) AccountResult.Success else reject(response)
            }

        override suspend fun withdraw(currentPassword: String): AccountResult =
            request {
                val response = accountApi.withdraw(WithdrawalRequest(nfc(currentPassword)))
                if (response.isSuccessful) {
                    pushDeviceLifecycle.onSessionCleared()
                    authSessionManager.clear()
                    AccountResult.Success
                } else {
                    reject(response)
                }
            }

        override suspend fun logout() {
            pushDeviceLifecycle.unregisterBeforeLogout()
            val refreshToken = authSessionManager.readRefreshToken()
            try {
                if (refreshToken != null) accountApi.logout(LogoutRequest(refreshToken))
            } catch (_: Exception) {
                // 서버에 닿지 못해도 로컬 세션은 지운다. 남은 서버 세션은 30일 만료로 정리된다.
            } finally {
                pushDeviceLifecycle.onSessionCleared()
                authSessionManager.clear()
            }
        }

        private inline fun request(block: () -> AccountResult): AccountResult =
            try {
                block()
            } catch (_: IOException) {
                AccountResult.Rejected(CONNECTION_ERROR)
            } catch (_: Exception) {
                AccountResult.Rejected(GENERIC_ERROR)
            }

        private fun reject(response: Response<*>): AccountResult {
            val error = response.errorBody()?.string()?.let(::parseError)
            return when (error?.code) {
                "AUTH-002", "AUTH-003" -> {
                    pushDeviceLifecycle.onSessionCleared()
                    authSessionManager.clear()
                    AccountResult.SessionExpired
                }
                "AUTH-001" -> AccountResult.Rejected("현재 비밀번호가 올바르지 않습니다.", AccountField.CURRENT_PASSWORD)
                "MEMBER-003" -> AccountResult.Rejected("새 비밀번호는 현재 비밀번호와 달라야 합니다.", AccountField.NEW_PASSWORD)
                "COMMON-001" -> {
                    val fieldError = error.data?.fieldErrors?.firstOrNull()
                    AccountResult.Rejected(
                        message = fieldError?.reason ?: error.message,
                        field =
                            when (fieldError?.field) {
                                "nickname" -> AccountField.NICKNAME
                                "currentPassword" -> AccountField.CURRENT_PASSWORD
                                "newPassword" -> AccountField.NEW_PASSWORD
                                else -> null
                            },
                    )
                }
                "AUTH-004", "AUTH-006" -> {
                    val retryAfter = response.headers()["Retry-After"]?.toIntOrNull()
                    AccountResult.Rejected(
                        if (retryAfter != null) "${error.message} (${retryAfter}초 후 재시도)" else error.message,
                    )
                }
                "AUTH-005" -> AccountResult.Rejected("사용할 수 없는 계정입니다.")
                else -> AccountResult.Rejected(error?.message ?: GENERIC_ERROR)
            }
        }

        private fun parseError(value: String): ApiErrorResponse? =
            runCatching { json.decodeFromString<ApiErrorResponse>(value) }.getOrNull()

        private fun nfc(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)

        private companion object {
            const val CONNECTION_ERROR = "서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요."
            const val GENERIC_ERROR = "처리 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."
        }
    }
