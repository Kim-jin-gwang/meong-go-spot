package com.hotdog.meonggocuisine.feature.auth.data

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(
    val loginId: String,
    val password: String,
)

@Serializable
data class LoginResponse(
    val tokenType: String,
    val accessToken: String,
    val refreshToken: String,
    val accessTokenExpiresAt: String,
    val refreshTokenExpiresAt: String,
    val member: AuthMember,
)

@Serializable
data class AuthMember(
    val memberId: Long,
    val nickname: String,
)

@Serializable
data class TokenRefreshRequest(
    val refreshToken: String,
)

/** A3 응답에는 회원 정보가 없습니다. 갱신은 토큰만 교체합니다. */
@Serializable
data class TokenRefreshResponse(
    val tokenType: String,
    val accessToken: String,
    val refreshToken: String,
    val accessTokenExpiresAt: String,
    val refreshTokenExpiresAt: String,
)
