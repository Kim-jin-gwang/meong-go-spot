package com.hotdog.meonggocuisine.feature.push.data

import com.hotdog.meonggocuisine.feature.auth.data.AuthMember
import com.hotdog.meonggocuisine.feature.auth.data.AuthSessionManager
import com.hotdog.meonggocuisine.feature.auth.data.LoginResponse
import com.hotdog.meonggocuisine.feature.auth.data.RefreshTokenStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PushDeviceLifecycleManagerTest {
    @Test
    fun `세션이 생기면 현재 Firebase 토큰을 N1에 등록한다`() {
        val fixture = Fixture()

        fixture.manager.onSessionAvailable()
        fixture.scope.advanceUntilIdle()

        assertEquals(listOf("installation-1" to "firebase-token-1"), fixture.api.registrations)
        assertEquals(1, fixture.tokenProvider.callCount)
    }

    @Test
    fun `onNewToken은 전달된 토큰을 사용하고 Firebase 토큰을 다시 조회하지 않는다`() {
        val fixture = Fixture()

        fixture.manager.onNewToken("firebase-token-2")
        fixture.scope.advanceUntilIdle()

        assertEquals(listOf("installation-1" to "firebase-token-2"), fixture.api.registrations)
        assertEquals(0, fixture.tokenProvider.callCount)
    }

    @Test
    fun `일시 네트워크 실패는 같은 토큰으로 다시 등록한다`() {
        val fixture = Fixture(failuresBeforeSuccess = 2)

        fixture.manager.onSessionAvailable()
        fixture.scope.advanceUntilIdle()

        assertEquals(3, fixture.api.registrations.size)
        assertEquals("firebase-token-1", fixture.api.registrations.last().second)
    }

    @Test
    fun `로그인 세션이 없으면 토큰을 조회하거나 등록하지 않는다`() {
        val fixture = Fixture(signedIn = false)

        fixture.manager.onSessionAvailable()
        fixture.scope.advanceUntilIdle()

        assertEquals(0, fixture.tokenProvider.callCount)
        assertEquals(emptyList<Pair<String, String>>(), fixture.api.registrations)
    }

    @Test
    fun `로그아웃 전에는 현재 설치를 N2로 해제한다`() {
        val fixture = Fixture()

        fixture.scope.runTest { fixture.manager.unregisterBeforeLogout() }

        assertEquals(listOf("installation-1"), fixture.api.unregistrations)
    }

    private class Fixture(
        signedIn: Boolean = true,
        failuresBeforeSuccess: Int = 0,
    ) {
        val scope = TestScope(StandardTestDispatcher())
        val api = FakePushDeviceApi(failuresBeforeSuccess)
        val tokenProvider = FakePushTokenProvider()
        private val sessionManager = AuthSessionManager(FakeRefreshTokenStore())
        val manager =
            PushDeviceLifecycleManager(
                api = api,
                installationIdStore = FakeInstallationIdStore(),
                tokenProvider = tokenProvider,
                authSessionManager = sessionManager,
                scope = scope,
            )

        init {
            if (signedIn) {
                sessionManager.establish(
                    LoginResponse(
                        tokenType = "Bearer",
                        accessToken = "access-token",
                        refreshToken = "refresh-token",
                        accessTokenExpiresAt = "2026-09-22T01:00:00Z",
                        refreshTokenExpiresAt = "2026-10-22T01:00:00Z",
                        member = AuthMember(1L, "망고보호자"),
                    ),
                )
            }
        }
    }

    private class FakePushDeviceApi(private var failuresBeforeSuccess: Int) : PushDeviceApi {
        val registrations = mutableListOf<Pair<String, String>>()
        val unregistrations = mutableListOf<String>()

        override suspend fun register(
            installationId: String,
            request: PushDeviceRegistrationRequest,
        ): Response<Unit> {
            registrations += installationId to request.token
            if (failuresBeforeSuccess-- > 0) throw IOException("offline")
            return Response.success(204, Unit)
        }

        override suspend fun unregister(installationId: String): Response<Unit> {
            unregistrations += installationId
            return Response.success(204, Unit)
        }
    }

    private class FakeInstallationIdStore : InstallationIdStore {
        override fun getOrCreate(): String = "installation-1"
    }

    private class FakePushTokenProvider : PushTokenProvider {
        var callCount = 0

        override suspend fun currentToken(): String {
            callCount += 1
            return "firebase-token-1"
        }
    }

    private class FakeRefreshTokenStore : RefreshTokenStore {
        private var token: String? = null

        override fun save(refreshToken: String) {
            token = refreshToken
        }

        override fun read(): String? = token

        override fun clear() {
            token = null
        }
    }
}
