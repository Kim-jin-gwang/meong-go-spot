package com.hotdog.meonggocuisine.feature.auth.data

import com.hotdog.meonggocuisine.core.network.AuthRefreshResult
import com.hotdog.meonggocuisine.core.network.AuthTokenProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * 네트워크 계층에 로그인 세션의 토큰을 공급합니다.
 *
 * [AuthRepository]는 Retrofit에 의존하므로 [Provider]로 늦게 받아 객체 생성 순환을 끊습니다.
 * 갱신은 OkHttp 인터셉터 thread에서 호출되므로 [runBlocking]으로 결과를 기다립니다.
 */
@Singleton
class SessionAuthTokenProvider
    @Inject
    constructor(
        private val sessionManager: AuthSessionManager,
        private val authRepository: Provider<AuthRepository>,
    ) : AuthTokenProvider {
        private val refreshMutex = Mutex()

        override fun accessToken(): String? = sessionManager.session.value?.accessToken

        override fun refresh(usedAccessToken: String?): AuthRefreshResult = runBlocking { refreshOnce(usedAccessToken) }

        override fun invalidate() = sessionManager.clear()

        /**
         * 갱신을 한 번만 수행합니다.
         *
         * 잠금을 기다리는 동안 다른 요청이 이미 갱신했으면 access token이 바뀌므로, A3를 다시
         * 호출하지 않고 그 결과를 함께 사용합니다.
         */
        private suspend fun refreshOnce(usedAccessToken: String?): AuthRefreshResult =
            refreshMutex.withLock {
                val currentAccessToken = sessionManager.session.value?.accessToken
                if (currentAccessToken != null && currentAccessToken != usedAccessToken) {
                    return AuthRefreshResult.REFRESHED
                }
                when (authRepository.get().refreshTokens()) {
                    TokenRefreshResult.Refreshed -> AuthRefreshResult.REFRESHED
                    TokenRefreshResult.SessionExpired -> AuthRefreshResult.SESSION_EXPIRED
                }
            }
    }
