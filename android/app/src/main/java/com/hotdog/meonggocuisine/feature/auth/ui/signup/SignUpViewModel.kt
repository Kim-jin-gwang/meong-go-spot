package com.hotdog.meonggocuisine.feature.auth.ui.signup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hotdog.meonggocuisine.feature.auth.data.LoginIdAvailabilityResult
import com.hotdog.meonggocuisine.feature.auth.data.PhoneCodeRequestResult
import com.hotdog.meonggocuisine.feature.auth.data.PhoneConfirmationResult
import com.hotdog.meonggocuisine.feature.auth.data.SignupRepository
import com.hotdog.meonggocuisine.feature.auth.data.SignupResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface SignUpEvent {
    data object SignUpSucceeded : SignUpEvent
}

@HiltViewModel
class SignUpViewModel
    @Inject
    constructor(
        private val signupRepository: SignupRepository,
    ) : ViewModel() {
        private val mutableUiState = MutableStateFlow(SignUpUiState())
        val uiState: StateFlow<SignUpUiState> = mutableUiState.asStateFlow()

        private val eventChannel = Channel<SignUpEvent>(Channel.BUFFERED)
        val events = eventChannel.receiveAsFlow()

        private var phoneVerificationToken: String? = null
        private var loginIdCheckJob: Job? = null
        private var phoneRequestJob: Job? = null
        private var phoneConfirmationJob: Job? = null
        private var signupJob: Job? = null
        private var resendCountdownJob: Job? = null
        private var signupRetryJob: Job? = null

        fun onLoginIdChange(value: String) =
            update {
                // 아이디가 바뀌면 앞서 확인한 결과는 다른 값에 대한 답이라 지운다.
                copy(
                    loginId = value,
                    loginIdError = null,
                    loginIdCheck = LoginIdCheckState.IDLE,
                    loginIdCheckMessage = null,
                    requestError = null,
                )
            }

        /**
         * 아이디를 쓸 수 있는지 서버에 물어봅니다.
         *
         * 구버전 서버에 이 창구가 없으면(404) 준비 중이라고만 알리고 가입은 막지 않는다. 중복은
         * 가입 시점에 `MEMBER-001` 로도 잡히므로, 이 확인은 미리 알려 주는 편의일 뿐이다.
         */
        fun checkLoginIdAvailability() {
            val state = mutableUiState.value
            if (loginIdCheckJob?.isActive == true || !state.canCheckLoginId) return
            val formatError = SignUpInputValidator.validateLoginId(state.loginId)
            if (formatError != null) {
                update { copy(loginIdError = formatError) }
                return
            }
            loginIdCheckJob =
                viewModelScope.launch {
                    update { copy(loginIdCheck = LoginIdCheckState.CHECKING, loginIdCheckMessage = null, loginIdError = null) }
                    when (val result = signupRepository.checkLoginIdAvailability(state.loginId)) {
                        LoginIdAvailabilityResult.Available ->
                            update {
                                copy(loginIdCheck = LoginIdCheckState.AVAILABLE, loginIdCheckMessage = "사용할 수 있는 아이디입니다.")
                            }

                        LoginIdAvailabilityResult.Taken ->
                            update {
                                copy(
                                    loginIdCheck = LoginIdCheckState.TAKEN,
                                    loginIdCheckMessage = null,
                                    loginIdError = "이미 사용 중인 아이디입니다. 다른 아이디를 입력해 주세요.",
                                )
                            }

                        LoginIdAvailabilityResult.NotSupportedYet ->
                            update {
                                copy(
                                    loginIdCheck = LoginIdCheckState.IDLE,
                                    loginIdCheckMessage = "중복 확인은 아직 준비 중입니다. 가입할 때 확인됩니다.",
                                )
                            }

                        is LoginIdAvailabilityResult.Failure ->
                            update { copy(loginIdCheck = LoginIdCheckState.IDLE, loginIdCheckMessage = result.message) }
                    }
                }
        }

        fun onPasswordChange(value: String) =
            update {
                copy(
                    password = value,
                    // 공백·제어 문자는 친 순간 보인다. 길이는 칸을 떠날 때(onFieldFocusChanged) 본다.
                    passwordError = SignUpInputValidator.immediatePasswordError(value),
                    passwordConfirmError = null,
                    requestError = null,
                )
            }

        fun onPasswordConfirmChange(value: String) =
            update {
                copy(
                    passwordConfirm = value,
                    passwordConfirmError = SignUpInputValidator.immediatePasswordError(value),
                    requestError = null,
                )
            }

        fun onNicknameChange(value: String) = update { copy(nickname = value, nicknameError = null, requestError = null) }

        fun onPhoneNumberChange(value: String) {
            phoneRequestJob?.cancel()
            phoneConfirmationJob?.cancel()
            resendCountdownJob?.cancel()
            phoneVerificationToken = null
            update {
                copy(
                    phoneNumber = value,
                    verificationCode = "",
                    phoneNumberError = null,
                    verificationCodeError = null,
                    requestError = null,
                    phoneVerificationState = PhoneVerificationState.IDLE,
                    resendAfterSeconds = 0,
                    isRequestingCode = false,
                    isConfirmingCode = false,
                )
            }
        }

        fun onVerificationCodeChange(value: String) =
            update {
                copy(
                    verificationCode =
                        value
                            .filter(Char::isDigit)
                            .take(SignUpInputValidator.VERIFICATION_CODE_LENGTH),
                    verificationCodeError = null,
                    requestError = null,
                )
            }

        /**
         * 입력칸을 떠날 때 그 칸만 검사합니다.
         *
         * 글자를 칠 때마다 검사하면 "아이디를 입력해 주세요"가 첫 글자를 치기도 전에 뜬다.
         * 칸을 떠날 때 보는 대신, 비어 있는 칸은 넘어간다 — 아직 안 쓴 것과 잘못 쓴 것은 다르고,
         * 빈 칸은 가입 버튼을 누를 때 [SignUpInputValidator.validateForm] 이 잡는다.
         */
        fun onFieldFocusChanged(
            field: SignUpField,
            focused: Boolean,
        ) = update {
            // 커서가 들어올 때는 아무것도 하지 않는다. 칸을 떠날 때만 본다.
            if (focused) return@update this
            val blurred = this
            when (field) {
                SignUpField.LOGIN_ID ->
                    blurred.copy(loginIdError = loginId.errorIfFilled(SignUpInputValidator::validateLoginId))

                SignUpField.PASSWORD ->
                    blurred.copy(passwordError = password.errorIfFilled(SignUpInputValidator::validatePassword))

                SignUpField.PASSWORD_CONFIRM ->
                    blurred.copy(
                        passwordConfirmError =
                            passwordConfirm.errorIfFilled {
                                SignUpInputValidator.validatePasswordConfirm(password, it)
                            },
                    )

                SignUpField.NICKNAME ->
                    blurred.copy(nicknameError = nickname.errorIfFilled(SignUpInputValidator::validateNickname))

                SignUpField.PHONE_NUMBER ->
                    blurred.copy(phoneNumberError = phoneNumber.errorIfFilled(SignUpInputValidator::validatePhoneNumber))
            }
        }

        fun onPrivacyCollectionAgreementChange(value: Boolean) =
            update { copy(privacyCollectionAgreed = value, privacyCollectionError = null, requestError = null) }

        fun requestPhoneCode() {
            if (phoneRequestJob?.isActive == true || !mutableUiState.value.canRequestCode) return
            val phoneNumberError = SignUpInputValidator.validatePhoneNumber(mutableUiState.value.phoneNumber)
            if (phoneNumberError != null) {
                update { copy(phoneNumberError = phoneNumberError) }
                return
            }

            phoneRequestJob =
                viewModelScope.launch {
                    update { copy(isRequestingCode = true, requestError = null) }
                    when (
                        val result =
                            signupRepository.requestPhoneCode(
                                phoneNumber = mutableUiState.value.phoneNumber,
                                privacyCollectionAgreed = mutableUiState.value.privacyCollectionAgreed,
                            )
                    ) {
                        PhoneCodeRequestResult.Success -> {
                            update {
                                copy(
                                    isRequestingCode = false,
                                    phoneVerificationState = PhoneVerificationState.CODE_SENT,
                                    verificationCode = "",
                                    resendAfterSeconds = SignUpInputValidator.RESEND_WAIT_SECONDS,
                                )
                            }
                            startResendCountdown()
                        }
                        is PhoneCodeRequestResult.InvalidPhone ->
                            update { copy(isRequestingCode = false, phoneNumberError = result.message) }
                        is PhoneCodeRequestResult.TemporarilyUnavailable -> {
                            update {
                                copy(
                                    isRequestingCode = false,
                                    requestError = result.message,
                                    resendAfterSeconds = result.retryAfterSeconds.coerceAtLeast(1),
                                )
                            }
                            startResendCountdown()
                        }
                        is PhoneCodeRequestResult.Failure ->
                            update { copy(isRequestingCode = false, requestError = result.message) }
                    }
                }
        }

        fun confirmPhoneCode() {
            if (phoneConfirmationJob?.isActive == true || !mutableUiState.value.canConfirmCode) return
            val state = mutableUiState.value
            val codeError =
                if (state.verificationCode.length == SignUpInputValidator.VERIFICATION_CODE_LENGTH) {
                    null
                } else {
                    "인증번호 숫자 6자리를 입력해 주세요."
                }
            if (codeError != null) {
                update { copy(verificationCodeError = codeError) }
                return
            }

            phoneConfirmationJob =
                viewModelScope.launch {
                    update { copy(isConfirmingCode = true, requestError = null) }
                    when (
                        val result =
                            signupRepository.confirmPhoneCode(
                                phoneNumber = mutableUiState.value.phoneNumber,
                                verificationCode = mutableUiState.value.verificationCode,
                                privacyCollectionAgreed = mutableUiState.value.privacyCollectionAgreed,
                            )
                    ) {
                        is PhoneConfirmationResult.Success -> {
                            phoneVerificationToken = result.verificationToken
                            update {
                                copy(
                                    isConfirmingCode = false,
                                    phoneVerificationState = PhoneVerificationState.VERIFIED,
                                    verificationCodeError = null,
                                )
                            }
                        }
                        is PhoneConfirmationResult.InvalidCode ->
                            update { copy(isConfirmingCode = false, verificationCodeError = result.message) }
                        is PhoneConfirmationResult.PhoneInUse ->
                            update { copy(isConfirmingCode = false, phoneNumberError = result.message) }
                        is PhoneConfirmationResult.Failure ->
                            update { copy(isConfirmingCode = false, requestError = result.message) }
                    }
                }
        }

        fun signup() {
            if (signupJob?.isActive == true || mutableUiState.value.isBusy || mutableUiState.value.signupRetryAfterSeconds > 0) {
                return
            }
            if (!validateSignup()) return
            val verificationToken = phoneVerificationToken ?: return

            signupJob =
                viewModelScope.launch {
                    update { copy(isSubmitting = true, requestError = null) }
                    val state = mutableUiState.value
                    when (
                        val result =
                            signupRepository.signup(
                                loginId = state.loginId,
                                password = state.password,
                                nickname = state.nickname,
                                phoneNumber = state.phoneNumber,
                                phoneVerificationToken = verificationToken,
                                privacyCollectionAgreed = state.privacyCollectionAgreed,
                            )
                    ) {
                        SignupResult.Success -> handleSignupSuccess()
                        is SignupResult.InvalidInput -> showFieldErrors(result)
                        is SignupResult.LoginIdInUse ->
                            update { copy(isSubmitting = false, loginIdError = result.message) }
                        is SignupResult.ConsentRequired ->
                            update { copy(isSubmitting = false, privacyCollectionError = result.message) }
                        is SignupResult.PhoneInvalid -> invalidatePhoneNumberVerification(result.message)
                        is SignupResult.PhoneInUse -> invalidatePhoneNumberVerification(result.message)
                        is SignupResult.TemporarilyUnavailable -> startSignupRetryCountdown(result)
                        is SignupResult.Failure ->
                            update { copy(isSubmitting = false, requestError = result.message) }
                    }
                }
        }

        private fun validateSignup(): Boolean {
            val state = mutableUiState.value
            val errors = SignUpInputValidator.validateForm(state, phoneVerificationToken != null)

            update {
                copy(
                    loginIdError = errors.loginId,
                    passwordError = errors.password,
                    passwordConfirmError = errors.passwordConfirm,
                    nicknameError = errors.nickname,
                    phoneNumberError = errors.phoneNumber,
                    privacyCollectionError = errors.privacyCollection,
                    requestError = null,
                )
            }
            return errors.isValid
        }

        private fun handleSignupSuccess() {
            phoneVerificationToken = null
            mutableUiState.value = SignUpUiState()
            eventChannel.trySend(SignUpEvent.SignUpSucceeded)
        }

        private fun showFieldErrors(result: SignupResult.InvalidInput) {
            val knownFields =
                setOf(
                    "loginId",
                    "password",
                    "nickname",
                    "phoneNumber",
                    "phoneVerificationToken",
                    "privacyCollectionAgreed",
                    "privacyCollectionPolicyVersion",
                )
            val invalidPhoneVerification =
                result.fieldErrors.containsKey("phoneNumber") ||
                    result.fieldErrors.containsKey("phoneVerificationToken")
            if (invalidPhoneVerification) {
                phoneVerificationToken = null
            }
            update {
                copy(
                    isSubmitting = false,
                    loginIdError = result.fieldErrors["loginId"],
                    passwordError = result.fieldErrors["password"],
                    nicknameError = result.fieldErrors["nickname"],
                    phoneNumberError =
                        result.fieldErrors["phoneNumber"] ?: result.fieldErrors["phoneVerificationToken"],
                    phoneVerificationState =
                        if (invalidPhoneVerification) PhoneVerificationState.IDLE else phoneVerificationState,
                    verificationCode = if (invalidPhoneVerification) "" else verificationCode,
                    privacyCollectionError =
                        result.fieldErrors["privacyCollectionAgreed"]
                            ?: result.fieldErrors["privacyCollectionPolicyVersion"],
                    requestError =
                        if (result.fieldErrors.isEmpty() || result.fieldErrors.keys.any { it !in knownFields }) {
                            result.message
                        } else {
                            null
                        },
                )
            }
        }

        private fun invalidatePhoneNumberVerification(message: String) {
            phoneVerificationToken = null
            update {
                copy(
                    isSubmitting = false,
                    phoneNumberError = message,
                    phoneVerificationState = PhoneVerificationState.IDLE,
                    verificationCode = "",
                )
            }
        }

        private fun startResendCountdown() {
            resendCountdownJob?.cancel()
            resendCountdownJob =
                viewModelScope.launch {
                    while (mutableUiState.value.resendAfterSeconds > 0) {
                        delay(1_000)
                        update { copy(resendAfterSeconds = resendAfterSeconds - 1) }
                    }
                }
        }

        private fun startSignupRetryCountdown(result: SignupResult.TemporarilyUnavailable) {
            signupRetryJob?.cancel()
            update {
                copy(
                    isSubmitting = false,
                    requestError = result.message,
                    signupRetryAfterSeconds = result.retryAfterSeconds.coerceAtLeast(1),
                )
            }
            signupRetryJob =
                viewModelScope.launch {
                    while (mutableUiState.value.signupRetryAfterSeconds > 0) {
                        delay(1_000)
                        update { copy(signupRetryAfterSeconds = signupRetryAfterSeconds - 1) }
                    }
                }
        }

        override fun onCleared() {
            phoneVerificationToken = null
            mutableUiState.value = SignUpUiState()
            super.onCleared()
        }

        /** 값이 비어 있으면 검사하지 않는다 — 아직 안 쓴 칸은 틀린 칸이 아니다. */
        private inline fun String.errorIfFilled(validate: (String) -> String?): String? = if (isEmpty()) null else validate(this)

        private fun update(transform: SignUpUiState.() -> SignUpUiState) {
            mutableUiState.value = mutableUiState.value.transform()
        }
    }
