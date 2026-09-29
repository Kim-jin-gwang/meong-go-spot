package com.hotdog.meonggocuisine.feature.account.data

import kotlinx.serialization.Serializable

/** A6·A7 응답 `data` — 로그인 응답의 `member` 와 같은 두 필드다. */
@Serializable
data class MemberProfileResponse(
    val memberId: Long,
    val nickname: String,
)

@Serializable
data class NicknameUpdateRequest(
    val nickname: String,
)

@Serializable
data class PasswordChangeRequest(
    val currentPassword: String,
    val newPassword: String,
)

@Serializable
data class WithdrawalRequest(
    val currentPassword: String,
)

@Serializable
data class LogoutRequest(
    val refreshToken: String,
)
