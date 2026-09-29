package com.hotdog.meonggocuisine.feature.auth.ui

import com.hotdog.meonggocuisine.feature.auth.data.AuthMember
import com.hotdog.meonggocuisine.feature.auth.data.AuthRepository
import com.hotdog.meonggocuisine.feature.auth.data.AuthSessionManager
import com.hotdog.meonggocuisine.feature.auth.data.LoginResponse
import com.hotdog.meonggocuisine.feature.auth.data.LoginResult
import com.hotdog.meonggocuisine.feature.auth.data.RefreshTokenStore
import com.hotdog.meonggocuisine.feature.auth.data.TokenRefreshResponse
import com.hotdog.meonggocuisine.feature.auth.data.TokenRefreshResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthGateViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `저장된 갱신 토큰으로 세션을 되살리면 로그인 상태가 된다`() =
        runTest(dispatcher) {
            val sessionManager = AuthSessionManager(FakeRefreshTokenStore(stored = "stored-refresh-token"))
            val repository = FakeAuthRepository(sessionManager, refreshSucceeds = true)
            val viewModel = AuthGateViewModel(repository, sessionManager)

            advanceUntilIdle()

            assertEquals(AuthGateState.AUTHENTICATED, viewModel.state.value)
            assertEquals(1, repository.refreshCallCount)
        }

    @Test
    fun `되살리기가 끝나기 전에는 비로그인으로 단정하지 않는다`() =
        runTest(dispatcher) {
            val sessionManager = AuthSessionManager(FakeRefreshTokenStore(stored = "stored-refresh-token"))
            val repository = FakeAuthRepository(sessionManager, refreshSucceeds = true)
            val viewModel = AuthGateViewModel(repository, sessionManager)

            assertEquals(AuthGateState.RESTORING, viewModel.state.value)
        }

    @Test
    fun `갱신 토큰이 만료됐으면 비로그인 상태가 된다`() =
        runTest(dispatcher) {
            val store = FakeRefreshTokenStore(stored = "expired-refresh-token")
            val sessionManager = AuthSessionManager(store)
            val repository = FakeAuthRepository(sessionManager, refreshSucceeds = false)
            val viewModel = AuthGateViewModel(repository, sessionManager)

            advanceUntilIdle()

            assertEquals(AuthGateState.UNAUTHENTICATED, viewModel.state.value)
            assertEquals(null, store.stored)
        }

    @Test
    fun `저장된 갱신 토큰이 없으면 A3을 호출하지 않는다`() =
        runTest(dispatcher) {
            val sessionManager = AuthSessionManager(FakeRefreshTokenStore(stored = null))
            val repository = FakeAuthRepository(sessionManager, refreshSucceeds = false)
            val viewModel = AuthGateViewModel(repository, sessionManager)

            advanceUntilIdle()

            assertEquals(AuthGateState.UNAUTHENTICATED, viewModel.state.value)
            assertEquals(0, repository.refreshCallCount)
        }

    @Test
    fun `되살린 뒤 로그아웃하면 비로그인 상태로 바뀐다`() =
        runTest(dispatcher) {
            val sessionManager = AuthSessionManager(FakeRefreshTokenStore(stored = "stored-refresh-token"))
            val repository = FakeAuthRepository(sessionManager, refreshSucceeds = true)
            val viewModel = AuthGateViewModel(repository, sessionManager)
            advanceUntilIdle()

            sessionManager.clear()
            advanceUntilIdle()

            assertEquals(AuthGateState.UNAUTHENTICATED, viewModel.state.value)
        }

    @Test
    fun `되살리기 전에 로그인하면 로그인 상태가 유지된다`() =
        runTest(dispatcher) {
            val sessionManager = AuthSessionManager(FakeRefreshTokenStore(stored = null))
            val repository = FakeAuthRepository(sessionManager, refreshSucceeds = false)
            val viewModel = AuthGateViewModel(repository, sessionManager)
            advanceUntilIdle()

            sessionManager.establish(loginResponse())
            advanceUntilIdle()

            assertEquals(AuthGateState.AUTHENTICATED, viewModel.state.value)
        }

    @Test
    fun `되살린 뒤 A6으로 회원 정보를 채운다`() =
        runTest(dispatcher) {
            val sessionManager = AuthSessionManager(FakeRefreshTokenStore(stored = "stored-refresh-token"))
            val repository = FakeAuthRepository(sessionManager, refreshSucceeds = true)
            val viewModel = AuthGateViewModel(repository, sessionManager)

            advanceUntilIdle()

            assertEquals(AuthGateState.AUTHENTICATED, viewModel.state.value)
            assertEquals(1, repository.memberCallCount)
            assertEquals(7L, sessionManager.session.value?.member?.memberId)
        }

    @Test
    fun `되살리기에 실패하면 회원 정보를 부르지 않는다`() =
        runTest(dispatcher) {
            val sessionManager = AuthSessionManager(FakeRefreshTokenStore(stored = "expired-refresh-token"))
            val repository = FakeAuthRepository(sessionManager, refreshSucceeds = false)
            val viewModel = AuthGateViewModel(repository, sessionManager)

            advanceUntilIdle()

            assertEquals(AuthGateState.UNAUTHENTICATED, viewModel.state.value)
            assertEquals(0, repository.memberCallCount)
        }

    @Test
    fun `회원 정보 조회가 실패해도 로그인 상태는 유지된다`() =
        runTest(dispatcher) {
            val sessionManager = AuthSessionManager(FakeRefreshTokenStore(stored = "stored-refresh-token"))
            val repository = FakeAuthRepository(sessionManager, refreshSucceeds = true)
            repository.memberLookupFails = true
            val viewModel = AuthGateViewModel(repository, sessionManager)

            advanceUntilIdle()

            assertEquals(AuthGateState.AUTHENTICATED, viewModel.state.value)
            assertNull(sessionManager.session.value?.member)
        }

    private fun loginResponse() =
        LoginResponse(
            tokenType = "Bearer",
            accessToken = "access-token",
            refreshToken = "refresh-token",
            accessTokenExpiresAt = "2026-09-16T00:15:00Z",
            refreshTokenExpiresAt = "2026-10-16T00:00:00Z",
            member = AuthMember(memberId = 1L, nickname = "테스터"),
        )
}

private class FakeRefreshTokenStore(
    var stored: String?,
) : RefreshTokenStore {
    override fun save(refreshToken: String) {
        stored = refreshToken
    }

    override fun read(): String? = stored

    override fun clear() {
        stored = null
    }
}

/** A3 호출 여부와 성공·실패만 재현한다. 세션 변경은 실제 [AuthSessionManager]에 맡긴다. */
private class FakeAuthRepository(
    private val sessionManager: AuthSessionManager,
    private val refreshSucceeds: Boolean,
) : AuthRepository {
    var refreshCallCount = 0
        private set
    var memberCallCount = 0
        private set
    var memberLookupFails = false

    override suspend fun login(
        loginId: String,
        password: String,
    ): LoginResult = LoginResult.Success

    override suspend fun loadCurrentMember() {
        memberCallCount++
        if (memberLookupFails) return
        sessionManager.updateMember(AuthMember(memberId = 7L, nickname = "복구된닉"))
    }

    override suspend fun refreshTokens(): TokenRefreshResult {
        refreshCallCount++
        return if (refreshSucceeds) {
            sessionManager.renew(
                TokenRefreshResponse(
                    tokenType = "Bearer",
                    accessToken = "new-access-token",
                    refreshToken = "new-refresh-token",
                    accessTokenExpiresAt = "2026-09-16T00:15:00Z",
                    refreshTokenExpiresAt = "2026-10-16T00:00:00Z",
                ),
            )
            TokenRefreshResult.Refreshed
        } else {
            sessionManager.clear()
            TokenRefreshResult.SessionExpired
        }
    }
}
