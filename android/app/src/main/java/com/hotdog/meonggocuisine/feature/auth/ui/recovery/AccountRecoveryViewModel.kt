package com.hotdog.meonggocuisine.feature.auth.ui.recovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hotdog.meonggocuisine.feature.auth.data.AccountRecoveryRepository
import com.hotdog.meonggocuisine.feature.auth.data.PasswordResetResult
import com.hotdog.meonggocuisine.feature.auth.data.RecoveryCodeRequestResult
import com.hotdog.meonggocuisine.feature.auth.data.RecoveryConfirmationResult
import com.hotdog.meonggocuisine.feature.auth.ui.signup.SignUpInputValidator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 계정 찾기(A9). 입력 규칙은 회원가입([SignUpInputValidator])과 같다 — 같은 번호·같은 비밀번호 정책이다.
 *
 * 복구 증명은 메모리에만 두고 화면을 벗어나면 지운다. 비밀번호 두 칸도 성공·이탈 시 비운다.
 */
@HiltViewModel
class AccountRecoveryViewModel
    @Inject
    constructor(
        private val repository: AccountRecoveryRepository,
    ) : ViewModel() {
        private val mutableUiState = MutableStateFlow(AccountRecoveryUiState())
        val uiState: StateFlow<AccountRecoveryUiState> = mutableUiState.asStateFlow()

        private var recoveryToken: String? = null
        private var requestJob: Job? = null
        private var confirmJob: Job? = null
        private var resetJob: Job? = null
        private var countdownJob: Job? = null

        fun onPhoneNumberChange(value: String) {
            requestJob?.cancel()
            confirmJob?.cancel()
            countdownJob?.cancel()
            update {
                copy(
                    phoneNumber = value,
                    phoneNumberError = null,
                    isCodeSent = false,
                    verificationCode = "",
                    verificationCodeError = null,
                    isRequestingCode = false,
                    isConfirmingCode = false,
                    resendAfterSeconds = 0,
                    requestError = null,
                )
            }
        }

        fun onVerificationCodeChange(value: String) =
            update { copy(verificationCode = value.filter(Char::isDigit).take(6), verificationCodeError = null, requestError = null) }

        fun onNewPasswordChange(value: String) =
            update {
                copy(
                    newPassword = value,
                    newPasswordError = SignUpInputValidator.immediatePasswordError(value),
                    newPasswordConfirmError = null,
                    requestError = null,
                )
            }

        fun onNewPasswordConfirmChange(value: String) =
            update {
                copy(
                    newPasswordConfirm = value,
                    newPasswordConfirmError = SignUpInputValidator.immediatePasswordError(value),
                    requestError = null,
                )
            }

        fun requestCode() {
            if (requestJob?.isActive == true || !mutableUiState.value.canRequestCode) return
            val phoneError = SignUpInputValidator.validatePhoneNumber(mutableUiState.value.phoneNumber)
            if (phoneError != null) {
                update { copy(phoneNumberError = phoneError) }
                return
            }
            requestJob =
                viewModelScope.launch {
                    update { copy(isRequestingCode = true, requestError = null) }
                    when (val result = repository.requestCode(mutableUiState.value.phoneNumber)) {
                        RecoveryCodeRequestResult.Success -> {
                            update {
                                copy(
                                    isRequestingCode = false,
                                    isCodeSent = true,
                                    verificationCode = "",
                                    resendAfterSeconds = SignUpInputValidator.RESEND_WAIT_SECONDS,
                                )
                            }
                            startCountdown()
                        }
                        is RecoveryCodeRequestResult.InvalidPhone ->
                            update { copy(isRequestingCode = false, phoneNumberError = result.message) }
                        is RecoveryCodeRequestResult.TemporarilyUnavailable -> {
                            update {
                                copy(
                                    isRequestingCode = false,
                                    requestError = result.message,
                                    resendAfterSeconds = result.retryAfterSeconds.coerceAtLeast(1),
                                )
                            }
                            startCountdown()
                        }
                        is RecoveryCodeRequestResult.Failure ->
                            update { copy(isRequestingCode = false, requestError = result.message) }
                    }
                }
        }

        fun confirmCode() {
            if (confirmJob?.isActive == true || !mutableUiState.value.canConfirmCode) return
            confirmJob =
                viewModelScope.launch {
                    update { copy(isConfirmingCode = true, requestError = null) }
                    val state = mutableUiState.value
                    when (val result = repository.confirmCode(state.phoneNumber, state.verificationCode)) {
                        is RecoveryConfirmationResult.Success -> {
                            recoveryToken = result.recoveryToken
                            countdownJob?.cancel()
                            update {
                                copy(
                                    isConfirmingCode = false,
                                    step = RecoveryStep.FOUND,
                                    loginId = result.loginId,
                                    verificationCode = "",
                                    resendAfterSeconds = 0,
                                )
                            }
                        }
                        is RecoveryConfirmationResult.InvalidCode ->
                            update { copy(isConfirmingCode = false, verificationCodeError = result.message) }
                        is RecoveryConfirmationResult.Failure ->
                            update { copy(isConfirmingCode = false, requestError = result.message) }
                    }
                }
        }

        fun resetPassword() {
            if (resetJob?.isActive == true || !mutableUiState.value.canReset) return
            val state = mutableUiState.value
            val loginId = state.loginId
            val token = recoveryToken
            if (loginId == null || token == null) {
                backToPhone("인증이 만료됐어요. 휴대전화 인증을 다시 해 주세요.")
                return
            }
            val passwordError = SignUpInputValidator.validatePassword(state.newPassword)
            val confirmError = SignUpInputValidator.validatePasswordConfirm(state.newPassword, state.newPasswordConfirm)
            if (passwordError != null || confirmError != null) {
                update { copy(newPasswordError = passwordError, newPasswordConfirmError = confirmError) }
                return
            }
            resetJob =
                viewModelScope.launch {
                    update { copy(isResetting = true, requestError = null) }
                    when (val result = repository.resetPassword(loginId, token, state.newPassword)) {
                        PasswordResetResult.Success -> {
                            recoveryToken = null
                            update {
                                copy(
                                    isResetting = false,
                                    step = RecoveryStep.DONE,
                                    newPassword = "",
                                    newPasswordConfirm = "",
                                )
                            }
                        }
                        is PasswordResetResult.InvalidPassword ->
                            update { copy(isResetting = false, newPasswordError = result.message) }
                        is PasswordResetResult.Unchanged ->
                            update { copy(isResetting = false, newPasswordError = result.message) }
                        is PasswordResetResult.ProofExpired -> backToPhone(result.message)
                        is PasswordResetResult.Failure ->
                            update { copy(isResetting = false, requestError = result.message) }
                    }
                }
        }

        /** 증명이 죽었으면 처음부터 — 아이디는 남겨 두어 다시 인증하면 바로 재설정 단계로 갈 수 있다. */
        private fun backToPhone(message: String) {
            recoveryToken = null
            update {
                copy(
                    step = RecoveryStep.PHONE,
                    isResetting = false,
                    isCodeSent = false,
                    verificationCode = "",
                    newPassword = "",
                    newPasswordConfirm = "",
                    requestError = message,
                )
            }
        }

        private fun startCountdown() {
            countdownJob?.cancel()
            countdownJob =
                viewModelScope.launch {
                    while (mutableUiState.value.resendAfterSeconds > 0) {
                        delay(1_000)
                        update { copy(resendAfterSeconds = (resendAfterSeconds - 1).coerceAtLeast(0)) }
                    }
                }
        }

        override fun onCleared() {
            recoveryToken = null
            update { copy(newPassword = "", newPasswordConfirm = "", verificationCode = "") }
            super.onCleared()
        }

        private fun update(transform: AccountRecoveryUiState.() -> AccountRecoveryUiState) {
            mutableUiState.value = mutableUiState.value.transform()
        }
    }
