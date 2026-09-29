package com.hotdog.meonggocuisine.feature.account.data

import com.hotdog.meonggocuisine.core.network.ApiResponse
import com.hotdog.meonggocuisine.feature.auth.data.AuthMember
import com.hotdog.meonggocuisine.feature.auth.data.AuthSessionManager
import com.hotdog.meonggocuisine.feature.auth.data.LoginResponse
import com.hotdog.meonggocuisine.feature.auth.data.RefreshTokenStore
import com.hotdog.meonggocuisine.feature.push.data.PushDeviceLifecycle
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class DefaultAccountRepositoryTest {
    @Test
    fun `닉네임 변경 성공은 세션의 회원 정보도 새 닉네임으로 바꾼다`() =
        runBlocking {
            val sessionManager = signedIn()
            val api = FakeAccountApi(nicknameResponse = Response.success(profile("새이름")))

            val result = repository(api, sessionManager).changeNickname("  새이름 ")

            assertEquals(AccountResult.Success, result)
            assertEquals("새이름", api.lastNickname)
            assertEquals("새이름", sessionManager.session.value?.member?.nickname)
        }

    @Test
    fun `서버 필드 오류는 해당 입력 칸으로 돌려준다`() =
        runBlocking {
            val api =
                FakeAccountApi(
                    passwordResponse =
                        error(
                            400,
                            """{"code":"COMMON-001","message":"요청값 검증 실패",""" +
                                """"data":{"fieldErrors":[{"field":"newPassword","reason":"더 강한 다른 비밀번호를 사용해 주세요."}]}}""",
                        ),
                )

            val result = repository(api, signedIn()).changePassword("current-pw-206!", "password")

            assertEquals(AccountResult.Rejected("더 강한 다른 비밀번호를 사용해 주세요.", AccountField.NEW_PASSWORD), result)
        }

    @Test
    fun `현재 비밀번호 불일치는 현재 비밀번호 칸의 오류다`() =
        runBlocking {
            val api = FakeAccountApi(withdrawResponse = error(401, """{"code":"AUTH-001","message":"아이디 또는 비밀번호가 올바르지 않습니다."}"""))
            val sessionManager = signedIn()

            val result = repository(api, sessionManager).withdraw("wrong")

            assertEquals(AccountField.CURRENT_PASSWORD, (result as AccountResult.Rejected).field)
            assertNotNull(sessionManager.session.value)
        }

    @Test
    fun `세션 거부 응답은 로컬 세션을 지우고 SessionExpired 를 돌려준다`() =
        runBlocking {
            val store = FakeRefreshTokenStore("refresh-1")
            val sessionManager = signedIn(store)
            val api = FakeAccountApi(passwordResponse = error(401, """{"code":"AUTH-003","message":"로그인 세션이 만료되었거나 유효하지 않습니다."}"""))

            val result = repository(api, sessionManager).changePassword("current-pw-206!", "next-pw-206!")

            assertEquals(AccountResult.SessionExpired, result)
            assertNull(sessionManager.session.value)
            assertNull(store.read())
        }

    @Test
    fun `탈퇴 성공은 세션과 저장된 갱신 토큰을 지운다`() =
        runBlocking {
            val store = FakeRefreshTokenStore("refresh-1")
            val sessionManager = signedIn(store)
            val api = FakeAccountApi(withdrawResponse = Response.success(204, Unit))

            val result = repository(api, sessionManager).withdraw("current-pw-206!")

            assertEquals(AccountResult.Success, result)
            assertNull(sessionManager.session.value)
            assertNull(store.read())
        }

    @Test
    fun `로그아웃은 서버에 닿지 못해도 로컬 세션을 지운다`() =
        runBlocking {
            val store = FakeRefreshTokenStore("refresh-1")
            val sessionManager = signedIn(store)
            val api = FakeAccountApi(logoutFailure = IOException("offline"))
            val lifecycle = FakePushDeviceLifecycle()

            repository(api, sessionManager, lifecycle).logout()

            assertEquals("refresh-1", api.lastLogoutToken)
            assertEquals(1, lifecycle.unregisterCount)
            assertEquals(1, lifecycle.sessionClearedCount)
            assertNull(sessionManager.session.value)
            assertNull(store.read())
        }

    private fun repository(
        api: AccountApi,
        sessionManager: AuthSessionManager,
        lifecycle: PushDeviceLifecycle = FakePushDeviceLifecycle(),
    ) = DefaultAccountRepository(api, sessionManager, lifecycle, Json { ignoreUnknownKeys = true })

    private fun profile(nickname: String) =
        ApiResponse(code = "SUCCESS", message = "닉네임을 변경했습니다.", data = MemberProfileResponse(memberId = 1L, nickname = nickname))

    private fun <T> error(
        status: Int,
        body: String,
    ): Response<T> = Response.error(status, body.toResponseBody("application/json".toMediaType()))

    private fun signedIn(store: RefreshTokenStore = FakeRefreshTokenStore("refresh-1")) =
        AuthSessionManager(store).apply {
            establish(
                LoginResponse(
                    tokenType = "Bearer",
                    accessToken = "access-1",
                    refreshToken = "refresh-1",
                    accessTokenExpiresAt = "2026-09-15T06:00:00Z",
                    refreshTokenExpiresAt = "2026-10-15T06:00:00Z",
                    member = AuthMember(memberId = 1L, nickname = "망고보호자"),
                ),
            )
        }

    private class FakeRefreshTokenStore(private var token: String? = null) : RefreshTokenStore {
        override fun save(refreshToken: String) {
            token = refreshToken
        }

        override fun read(): String? = token

        override fun clear() {
            token = null
        }
    }

    private class FakePushDeviceLifecycle : PushDeviceLifecycle {
        var unregisterCount = 0
        var sessionClearedCount = 0

        override fun onSessionAvailable() = Unit

        override fun onNewToken(token: String) = Unit

        override suspend fun unregisterBeforeLogout() {
            unregisterCount += 1
        }

        override fun onSessionCleared() {
            sessionClearedCount += 1
        }
    }

    private class FakeAccountApi(
        private val nicknameResponse: Response<ApiResponse<MemberProfileResponse>>? = null,
        private val passwordResponse: Response<Unit>? = null,
        private val withdrawResponse: Response<Unit>? = null,
        private val logoutFailure: IOException? = null,
    ) : AccountApi {
        var lastNickname: String? = null
        var lastLogoutToken: String? = null

        override suspend fun profile(): Response<ApiResponse<MemberProfileResponse>> = error("not used")

        override suspend fun changeNickname(request: NicknameUpdateRequest): Response<ApiResponse<MemberProfileResponse>> {
            lastNickname = request.nickname
            return nicknameResponse ?: error("not stubbed")
        }

        override suspend fun changePassword(request: PasswordChangeRequest): Response<Unit> = passwordResponse ?: error("not stubbed")

        override suspend fun withdraw(request: WithdrawalRequest): Response<Unit> = withdrawResponse ?: error("not stubbed")

        override suspend fun logout(request: LogoutRequest): Response<Unit> {
            lastLogoutToken = request.refreshToken
            logoutFailure?.let { throw it }
            return Response.success(204, Unit)
        }
    }
}
