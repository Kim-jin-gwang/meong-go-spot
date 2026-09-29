package com.hotdog.meonggocuisine.feature.auth.data

import com.hotdog.meonggocuisine.core.network.AuthRefreshResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import javax.inject.Provider

class SessionAuthTokenProviderTest {
    @Test
    fun `현재 access token을 그대로 공급한다`() {
        val sessionManager = signedInSessionManager()
        val provider = SessionAuthTokenProvider(sessionManager, Provider { FakeAuthRepository() })

        assertEquals("access-1", provider.accessToken())
    }

    @Test
    fun `다른 요청이 이미 갱신했으면 A3을 호출하지 않고 갱신 결과를 함께 사용한다`() {
        val sessionManager = signedInSessionManager()
        val repository = FakeAuthRepository()
        val provider = SessionAuthTokenProvider(sessionManager, Provider { repository })

        // 거부된 요청이 보낸 토큰은 이미 교체된 이전 토큰이다.
        val result = provider.refresh(usedAccessToken = "stale-access-token")

        assertEquals(AuthRefreshResult.REFRESHED, result)
        assertEquals(0, repository.refreshCount)
    }

    @Test
    fun `거부된 토큰이 현재 토큰과 같으면 갱신을 한 번 요청한다`() {
        val sessionManager = signedInSessionManager()
        val repository = FakeAuthRepository(result = TokenRefreshResult.Refreshed)
        val provider = SessionAuthTokenProvider(sessionManager, Provider { repository })

        val result = provider.refresh(usedAccessToken = "access-1")

        assertEquals(AuthRefreshResult.REFRESHED, result)
        assertEquals(1, repository.refreshCount)
    }

    @Test
    fun `갱신이 실패하면 재로그인이 필요하다고 보고한다`() {
        val sessionManager = signedInSessionManager()
        val repository = FakeAuthRepository(result = TokenRefreshResult.SessionExpired)
        val provider = SessionAuthTokenProvider(sessionManager, Provider { repository })

        val result = provider.refresh(usedAccessToken = "access-1")

        assertEquals(AuthRefreshResult.SESSION_EXPIRED, result)
        assertEquals(1, repository.refreshCount)
    }

    @Test
    fun `세션을 무효화하면 access token과 저장된 갱신 토큰이 사라진다`() {
        val store = FakeRefreshTokenStore(storedToken = "refresh-1")
        val sessionManager = signedInSessionManager(store)
        val provider = SessionAuthTokenProvider(sessionManager, Provider { FakeAuthRepository() })

        provider.invalidate()

        assertNull(provider.accessToken())
        assertNull(store.read())
    }

    private fun signedInSessionManager(store: RefreshTokenStore = FakeRefreshTokenStore()): AuthSessionManager =
        AuthSessionManager(store).apply {
            establish(
                LoginResponse(
                    tokenType = "Bearer",
                    accessToken = "access-1",
                    refreshToken = "refresh-1",
                    accessTokenExpiresAt = "2026-09-10T06:00:00Z",
                    refreshTokenExpiresAt = "2026-10-10T06:00:00Z",
                    member = AuthMember(memberId = 1L, nickname = "망고보호자"),
                ),
            )
        }

    private class FakeAuthRepository(
        private val result: TokenRefreshResult = TokenRefreshResult.Refreshed,
    ) : AuthRepository {
        var refreshCount = 0

        override suspend fun login(
            loginId: String,
            password: String,
        ): LoginResult = LoginResult.Success

        override suspend fun loadCurrentMember() = Unit

        override suspend fun refreshTokens(): TokenRefreshResult {
            refreshCount += 1
            return result
        }
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
