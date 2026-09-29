package com.hotdog.meonggocuisine.feature.auth.data

import com.hotdog.meonggocuisine.core.network.ApiMessageResponse
import com.hotdog.meonggocuisine.core.network.ApiResponse
import com.hotdog.meonggocuisine.feature.push.data.PushDeviceLifecycle
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class DefaultAuthRepositoryRefreshTest {
    @Test
    fun `로그인 성공은 현재 기기 등록을 예약한다`() =
        runBlocking {
            val lifecycle = FakePushDeviceLifecycle()
            val sessionManager = AuthSessionManager(FakeRefreshTokenStore())
            val api = FakeAuthApi(loginResponse = Response.success(loginBody()))

            val result = repository(api, sessionManager, lifecycle).login("mango206", "password-206!")

            assertEquals(LoginResult.Success, result)
            assertEquals(1, lifecycle.sessionAvailableCount)
        }

    @Test
    fun `갱신에 성공하면 새 토큰을 저장하고 세션을 교체한다`() =
        runBlocking {
            val store = FakeRefreshTokenStore(storedToken = "old-refresh-token")
            val sessionManager = signedInSessionManager(store)
            val api = FakeAuthApi(refreshResponse = Response.success(refreshBody()))

            val result = repository(api, sessionManager).refreshTokens()

            assertEquals(TokenRefreshResult.Refreshed, result)
            assertEquals("access-2", sessionManager.session.value?.accessToken)
            assertEquals("refresh-2", store.read())
        }

    @Test
    fun `갱신 성공은 로그인에서 받은 회원 정보를 유지한다`() =
        runBlocking {
            val sessionManager = signedInSessionManager()
            val api = FakeAuthApi(refreshResponse = Response.success(refreshBody()))

            repository(api, sessionManager).refreshTokens()

            assertEquals("망고보호자", sessionManager.session.value?.member?.nickname)
        }

    @Test
    fun `서버가 세션을 거부하면 세션과 저장된 갱신 토큰을 삭제한다`() =
        runBlocking {
            val store = FakeRefreshTokenStore(storedToken = "old-refresh-token")
            val sessionManager = signedInSessionManager(store)
            val api = FakeAuthApi(refreshResponse = unauthorized())

            val result = repository(api, sessionManager).refreshTokens()

            assertEquals(TokenRefreshResult.SessionExpired, result)
            assertNull(sessionManager.session.value)
            assertNull(store.read())
        }

    @Test
    fun `처리 여부를 알 수 없는 통신 실패도 같은 토큰으로 재시도하지 않고 세션을 삭제한다`() =
        runBlocking {
            val store = FakeRefreshTokenStore(storedToken = "old-refresh-token")
            val sessionManager = signedInSessionManager(store)
            val api = FakeAuthApi(refreshFailure = IOException("timeout"))

            val result = repository(api, sessionManager).refreshTokens()

            assertEquals(TokenRefreshResult.SessionExpired, result)
            assertEquals(1, api.refreshCount)
            assertNull(store.read())
        }

    @Test
    fun `저장된 갱신 토큰이 없으면 A3을 호출하지 않는다`() =
        runBlocking {
            val sessionManager = AuthSessionManager(FakeRefreshTokenStore())
            val api = FakeAuthApi(refreshResponse = Response.success(refreshBody()))

            val result = repository(api, sessionManager).refreshTokens()

            assertEquals(TokenRefreshResult.SessionExpired, result)
            assertEquals(0, api.refreshCount)
        }

    private fun repository(
        api: AuthApi,
        sessionManager: AuthSessionManager,
        lifecycle: PushDeviceLifecycle = FakePushDeviceLifecycle(),
    ) = DefaultAuthRepository(api, sessionManager, lifecycle, Json { ignoreUnknownKeys = true })

    private fun loginBody() =
        ApiResponse(
            code = "SUCCESS",
            message = "로그인했습니다.",
            data =
                LoginResponse(
                    tokenType = "Bearer",
                    accessToken = "access-1",
                    refreshToken = "refresh-1",
                    accessTokenExpiresAt = "2026-09-10T06:15:00Z",
                    refreshTokenExpiresAt = "2026-10-10T06:00:00Z",
                    member = AuthMember(1L, "망고보호자"),
                ),
        )

    private fun refreshBody() =
        ApiResponse(
            code = "SUCCESS",
            message = "토큰을 갱신했습니다.",
            data =
                TokenRefreshResponse(
                    tokenType = "Bearer",
                    accessToken = "access-2",
                    refreshToken = "refresh-2",
                    accessTokenExpiresAt = "2026-09-10T06:15:00Z",
                    refreshTokenExpiresAt = "2026-10-10T06:00:00Z",
                ),
        )

    private fun unauthorized(): Response<ApiResponse<TokenRefreshResponse>> =
        Response.error(
            401,
            """{"code":"AUTH-003","message":"로그인 세션이 만료되었거나 유효하지 않습니다."}"""
                .toResponseBody("application/json".toMediaType()),
        )

    private fun signedInSessionManager(store: RefreshTokenStore = FakeRefreshTokenStore(storedToken = "old-refresh-token")) =
        AuthSessionManager(store).apply {
            establish(
                LoginResponse(
                    tokenType = "Bearer",
                    accessToken = "access-1",
                    refreshToken = "old-refresh-token",
                    accessTokenExpiresAt = "2026-09-10T06:00:00Z",
                    refreshTokenExpiresAt = "2026-10-10T06:00:00Z",
                    member = AuthMember(memberId = 1L, nickname = "망고보호자"),
                ),
            )
        }

    private class FakeAuthApi(
        private val loginResponse: Response<ApiResponse<LoginResponse>>? = null,
        private val refreshResponse: Response<ApiResponse<TokenRefreshResponse>>? = null,
        private val refreshFailure: Exception? = null,
    ) : AuthApi {
        var refreshCount = 0

        override suspend fun checkLoginIdAvailability(loginId: String): Response<ApiResponse<LoginIdAvailabilityResponse>> =
            throw UnsupportedOperationException("이 테스트는 아이디 확인을 쓰지 않는다")

        override suspend fun refreshTokens(request: TokenRefreshRequest): Response<ApiResponse<TokenRefreshResponse>> {
            refreshCount += 1
            refreshFailure?.let { throw it }
            return checkNotNull(refreshResponse)
        }

        override suspend fun requestPhoneVerification(request: PhoneVerificationRequest): Response<ApiMessageResponse> =
            error("이 테스트에서 사용하지 않는다")

        override suspend fun confirmPhoneVerification(
            request: PhoneConfirmationRequest,
        ): Response<ApiResponse<PhoneVerificationResponse>> = error("이 테스트에서 사용하지 않는다")

        override suspend fun signup(request: SignupRequest): Response<ApiResponse<SignupResponse>> = error("이 테스트에서 사용하지 않는다")

        override suspend fun login(request: LoginRequest): Response<ApiResponse<LoginResponse>> = loginResponse ?: error("이 테스트에서 사용하지 않는다")

        override suspend fun currentMember(): Response<ApiResponse<AuthMember>> = error("이 테스트에서 사용하지 않는다")

        override suspend fun requestAccountRecoveryCode(request: AccountRecoveryPhoneRequest): Response<ApiMessageResponse> =
            error("이 테스트에서 사용하지 않는다")

        override suspend fun confirmAccountRecoveryCode(
            request: AccountRecoveryConfirmationRequest,
        ): Response<ApiResponse<AccountRecoveryConfirmationResponse>> = error("이 테스트에서 사용하지 않는다")

        override suspend fun resetPasswordByRecovery(request: PasswordResetRequest): Response<Unit> = error("이 테스트에서 사용하지 않는다")
    }

    private class FakePushDeviceLifecycle : PushDeviceLifecycle {
        var sessionAvailableCount = 0

        override fun onSessionAvailable() {
            sessionAvailableCount += 1
        }

        override fun onNewToken(token: String) = Unit

        override suspend fun unregisterBeforeLogout() = Unit

        override fun onSessionCleared() = Unit
    }

    private class FakeRefreshTokenStore(
        private var storedToken: String? = null,
    ) : RefreshTokenStore {
        override fun save(refreshToken: String) {
            storedToken = refreshToken
        }

        override fun read(): String? = storedToken

        override fun clear() {
            storedToken = null
        }
    }
}
