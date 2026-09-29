package com.hotdog.meonggocuisine.feature.auth.ui.recovery

/**
 * 계정 찾기는 한 화면에서 세 단계를 지난다.
 *
 * - [PHONE]: 번호 입력 → 인증 요청 → 코드 입력 → 확인
 * - [FOUND]: 아이디를 보여 주고, 원하면 바로 새 비밀번호를 정한다
 * - [DONE]: 비밀번호를 바꿨다 — 로그인으로 보낸다
 */
enum class RecoveryStep {
    PHONE,
    FOUND,
    DONE,
}

data class AccountRecoveryUiState(
    val step: RecoveryStep = RecoveryStep.PHONE,
    val phoneNumber: String = "",
    val phoneNumberError: String? = null,
    val isCodeSent: Boolean = false,
    val verificationCode: String = "",
    val verificationCodeError: String? = null,
    val isRequestingCode: Boolean = false,
    val isConfirmingCode: Boolean = false,
    /** 재전송까지 남은 초. 서버의 Retry-After 도 여기에 담는다. */
    val resendAfterSeconds: Int = 0,
    /** 인증을 통과한 뒤 서버가 돌려준 아이디. */
    val loginId: String? = null,
    val newPassword: String = "",
    val newPasswordError: String? = null,
    val newPasswordConfirm: String = "",
    val newPasswordConfirmError: String? = null,
    val isResetting: Boolean = false,
    val requestError: String? = null,
) {
    val isBusy: Boolean get() = isRequestingCode || isConfirmingCode || isResetting

    val canRequestCode: Boolean get() = !isBusy && resendAfterSeconds == 0 && phoneNumber.isNotBlank()

    val canConfirmCode: Boolean get() = !isBusy && isCodeSent && verificationCode.length == 6

    val canReset: Boolean get() = !isBusy && newPassword.isNotEmpty() && newPasswordConfirm.isNotEmpty()
}
