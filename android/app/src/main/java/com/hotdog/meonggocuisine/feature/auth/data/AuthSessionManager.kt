package com.hotdog.meonggocuisine.feature.auth.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 로그인 세션입니다.
 *
 * [member]는 로그인 응답에만 있습니다. 저장된 갱신 토큰으로만 세션을 되살린 경우에는 회원 정보가
 * 없으므로 null입니다.
 */
data class AuthSession(
    val accessToken: String,
    val accessTokenExpiresAt: String,
    val member: AuthMember? = null,
)

@Singleton
class AuthSessionManager
    @Inject
    constructor(
        private val refreshTokenStore: RefreshTokenStore,
    ) {
        private val mutableSession = MutableStateFlow<AuthSession?>(null)
        val session: StateFlow<AuthSession?> = mutableSession.asStateFlow()

        fun establish(loginResponse: LoginResponse) {
            refreshTokenStore.save(loginResponse.refreshToken)
            mutableSession.value =
                AuthSession(
                    accessToken = loginResponse.accessToken,
                    accessTokenExpiresAt = loginResponse.accessTokenExpiresAt,
                    member = loginResponse.member,
                )
        }

        /**
         * 갱신한 토큰으로 세션을 교체합니다.
         *
         * 새 갱신 토큰을 먼저 저장한 뒤에 세션을 공개해, 대기 중인 요청이 저장되지 않은 토큰을
         * 사용하지 않게 합니다.
         */
        fun renew(refreshResponse: TokenRefreshResponse) {
            refreshTokenStore.save(refreshResponse.refreshToken)
            mutableSession.value =
                AuthSession(
                    accessToken = refreshResponse.accessToken,
                    accessTokenExpiresAt = refreshResponse.accessTokenExpiresAt,
                    member = mutableSession.value?.member,
                )
        }

        /** 프로필 조회·닉네임 변경 뒤 세션의 회원 정보를 맞춘다. 토큰은 건드리지 않는다. */
        fun updateMember(member: AuthMember) {
            val current = mutableSession.value ?: return
            mutableSession.value = current.copy(member = member)
        }

        fun readRefreshToken(): String? = refreshTokenStore.read()

        fun clear() {
            mutableSession.value = null
            refreshTokenStore.clear()
        }
    }
