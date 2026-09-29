package com.hotdog.meonggocuisine.feature.auth.data

import kotlinx.serialization.Serializable

/** A9-1 요청. 가입 여부는 응답으로 알 수 없다 — 서버가 미가입 번호에도 202 를 준다. */
@Serializable
data class AccountRecoveryPhoneRequest(
    val phoneNumber: String,
)

@Serializable
data class AccountRecoveryConfirmationRequest(
    val phoneNumber: String,
    val verificationCode: String,
)

/** A9-2 응답 — 번호 소유를 증명했으므로 아이디는 가려지지 않는다. 복구 증명은 10분·1회용. */
@Serializable
data class AccountRecoveryConfirmationResponse(
    val loginId: String,
    val recoveryToken: String,
    val expiresAt: String,
)

@Serializable
data class PasswordResetRequest(
    val loginId: String,
    val recoveryToken: String,
    val newPassword: String,
)

sealed interface RecoveryCodeRequestResult {
    data object Success : RecoveryCodeRequestResult

    data class InvalidPhone(val message: String) : RecoveryCodeRequestResult

    data class TemporarilyUnavailable(
        val message: String,
        val retryAfterSeconds: Int,
    ) : RecoveryCodeRequestResult

    data class Failure(val message: String) : RecoveryCodeRequestResult
}

sealed interface RecoveryConfirmationResult {
    data class Success(
        val loginId: String,
        val recoveryToken: String,
    ) : RecoveryConfirmationResult

    /** 코드가 틀렸거나 만료됐거나 그 번호의 회원이 없다 — 서버는 셋을 구분해 주지 않는다. */
    data class InvalidCode(val message: String) : RecoveryConfirmationResult

    data class Failure(val message: String) : RecoveryConfirmationResult
}

sealed interface PasswordResetResult {
    data object Success : PasswordResetResult

    /** 새 비밀번호가 규칙에 어긋난다 (`fieldErrors[].field=newPassword`). */
    data class InvalidPassword(val message: String) : PasswordResetResult

    /** 잊었다던 비밀번호를 그대로 넣었다 (`MEMBER-003`). */
    data class Unchanged(val message: String) : PasswordResetResult

    /** 복구 증명이 만료·소비됐거나 아이디와 맞지 않는다 (`PHONE-002`). 인증부터 다시 한다. */
    data class ProofExpired(val message: String) : PasswordResetResult

    data class Failure(val message: String) : PasswordResetResult
}

/** 계정 찾기(A9) — 휴대전화 인증으로 아이디를 확인하고 비밀번호를 재설정한다. 전부 비로그인 호출. */
interface AccountRecoveryRepository {
    suspend fun requestCode(phoneNumber: String): RecoveryCodeRequestResult

    suspend fun confirmCode(
        phoneNumber: String,
        verificationCode: String,
    ): RecoveryConfirmationResult

    suspend fun resetPassword(
        loginId: String,
        recoveryToken: String,
        newPassword: String,
    ): PasswordResetResult
}
