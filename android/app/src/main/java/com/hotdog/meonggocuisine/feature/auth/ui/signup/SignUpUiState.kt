package com.hotdog.meonggocuisine.feature.auth.ui.signup

/**
 * 가입 폼의 입력칸입니다.
 *
 * 어떤 칸에 손을 댔고 어떤 칸을 보고 있는지 기억해 두면, 아직 쓰지도 않은 칸에 빨간 글씨를
 * 띄우지 않으면서도 칸을 떠나는 순간 바로 알려 줄 수 있다.
 */
enum class SignUpField {
    LOGIN_ID,
    PASSWORD,
    PASSWORD_CONFIRM,
    NICKNAME,
    PHONE_NUMBER,
}

/** 아이디 중복 확인 상태. */
enum class LoginIdCheckState {
    IDLE,
    CHECKING,
    AVAILABLE,
    TAKEN,
}

enum class PhoneVerificationState {
    IDLE,
    CODE_SENT,
    VERIFIED,
}

data class SignUpUiState(
    val loginId: String = "",
    val password: String = "",
    val passwordConfirm: String = "",
    val nickname: String = "",
    val phoneNumber: String = "",
    val verificationCode: String = "",
    val privacyCollectionAgreed: Boolean = false,
    val loginIdError: String? = null,
    val passwordError: String? = null,
    val passwordConfirmError: String? = null,
    val nicknameError: String? = null,
    val phoneNumberError: String? = null,
    val verificationCodeError: String? = null,
    val privacyCollectionError: String? = null,
    val requestError: String? = null,
    val phoneVerificationState: PhoneVerificationState = PhoneVerificationState.IDLE,
    val resendAfterSeconds: Int = 0,
    val signupRetryAfterSeconds: Int = 0,
    val isRequestingCode: Boolean = false,
    val isConfirmingCode: Boolean = false,
    val isSubmitting: Boolean = false,
    val loginIdCheck: LoginIdCheckState = LoginIdCheckState.IDLE,
    /** 중복 확인 결과 안내. 구버전 서버가 이 기능을 모를 때의 안내도 여기로 온다. */
    val loginIdCheckMessage: String? = null,
) {
    val canCheckLoginId: Boolean
        get() = loginId.isNotBlank() && !isBusy && loginIdCheck != LoginIdCheckState.CHECKING

    val isBusy: Boolean
        get() = isRequestingCode || isConfirmingCode || isSubmitting || loginIdCheck == LoginIdCheckState.CHECKING

    val canRequestCode: Boolean
        get() = privacyCollectionAgreed && !isBusy && resendAfterSeconds == 0 && phoneVerificationState != PhoneVerificationState.VERIFIED

    val canConfirmCode: Boolean
        get() = privacyCollectionAgreed && !isBusy && phoneVerificationState == PhoneVerificationState.CODE_SENT

    val canSubmit: Boolean
        get() =
            !isBusy &&
                signupRetryAfterSeconds == 0 &&
                phoneVerificationState == PhoneVerificationState.VERIFIED &&
                privacyCollectionAgreed
}
