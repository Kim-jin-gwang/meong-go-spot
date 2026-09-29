package com.hotdog.meonggocuisine.feature.auth.data

import com.hotdog.meonggocuisine.core.network.ApiErrorResponse
import com.hotdog.meonggocuisine.feature.push.data.PushDeviceLifecycle
import kotlinx.serialization.json.Json
import java.io.IOException
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject

class DefaultAuthRepository
    @Inject
    constructor(
        private val authApi: AuthApi,
        private val authSessionManager: AuthSessionManager,
        private val pushDeviceLifecycle: PushDeviceLifecycle,
        private val json: Json,
    ) : AuthRepository {
        override suspend fun login(
            loginId: String,
            password: String,
        ): LoginResult =
            try {
                val response =
                    authApi.login(
                        LoginRequest(
                            loginId = canonicalizeLoginId(loginId),
                            password = Normalizer.normalize(password, Normalizer.Form.NFC),
                        ),
                    )
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    authSessionManager.establish(body.data)
                    pushDeviceLifecycle.onSessionAvailable()
                    LoginResult.Success
                } else {
                    val error = response.errorBody()?.string()?.let(::parseError)
                    mapError(
                        code = error?.code,
                        message = error?.message,
                        retryAfter = response.headers()["Retry-After"]?.toIntOrNull(),
                    )
                }
            } catch (_: IOException) {
                LoginResult.ConnectionFailure("서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.")
            } catch (_: Exception) {
                LoginResult.ServerFailure("로그인 처리 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.")
            }

        override suspend fun refreshTokens(): TokenRefreshResult {
            val refreshToken = authSessionManager.readRefreshToken() ?: return expireSession()
            val response =
                try {
                    authApi.refreshTokens(TokenRefreshRequest(refreshToken))
                } catch (_: Exception) {
                    // 서버가 토큰을 회전했는지 알 수 없으므로 같은 토큰으로 재시도하지 않는다.
                    return expireSession()
                }
            val body = response.body()
            if (!response.isSuccessful || body == null) return expireSession()
            authSessionManager.renew(body.data)
            pushDeviceLifecycle.onSessionAvailable()
            return TokenRefreshResult.Refreshed
        }

        override suspend fun loadCurrentMember() {
            val response =
                try {
                    authApi.currentMember()
                } catch (_: Exception) {
                    return
                }
            val body = response.body()
            if (response.isSuccessful && body != null) {
                authSessionManager.updateMember(body.data)
            }
        }

        private fun expireSession(): TokenRefreshResult {
            authSessionManager.clear()
            pushDeviceLifecycle.onSessionCleared()
            return TokenRefreshResult.SessionExpired
        }

        private fun parseError(value: String): ApiErrorResponse? =
            runCatching { json.decodeFromString<ApiErrorResponse>(value) }.getOrNull()

        private fun mapError(
            code: String?,
            message: String?,
            retryAfter: Int?,
        ): LoginResult =
            when (code) {
                "COMMON-001" -> LoginResult.InvalidInput(message ?: "입력값을 확인해 주세요.")
                "AUTH-001" ->
                    LoginResult.AuthenticationFailed("아이디 또는 비밀번호가 올바르지 않습니다.")
                "AUTH-005" -> LoginResult.ServerFailure("사용할 수 없는 계정입니다.")
                "AUTH-004" ->
                    LoginResult.TemporarilyBlocked(
                        message = "로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요.",
                        retryAfterSeconds = retryAfter ?: 1,
                    )
                "AUTH-006" ->
                    LoginResult.TemporarilyBlocked(
                        message = "인증 요청이 많습니다. 잠시 후 다시 시도해 주세요.",
                        retryAfterSeconds = retryAfter ?: 1,
                    )
                else -> LoginResult.ServerFailure(message ?: "서버 오류가 발생했습니다.")
            }

        private fun canonicalizeLoginId(loginId: String): String =
            Normalizer.normalize(
                Normalizer.normalize(loginId, Normalizer.Form.NFC).lowercase(Locale.ROOT),
                Normalizer.Form.NFC,
            )
    }
