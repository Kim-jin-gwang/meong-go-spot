package com.hotdog.meonggocuisine.feature.auth.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hotdog.meonggocuisine.feature.auth.data.AuthRepository
import com.hotdog.meonggocuisine.feature.auth.data.LoginResult
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

sealed interface LoginEvent {
    data object LoginSucceeded : LoginEvent
}

@HiltViewModel
class LoginViewModel
    @Inject
    constructor(
        private val authRepository: AuthRepository,
    ) : ViewModel() {
        private val mutableUiState = MutableStateFlow(LoginUiState())
        val uiState: StateFlow<LoginUiState> = mutableUiState.asStateFlow()

        private val eventChannel = Channel<LoginEvent>(Channel.BUFFERED)
        val events = eventChannel.receiveAsFlow()

        private var loginJob: Job? = null
        private var retryJob: Job? = null

        fun onLoginIdChange(value: String) {
            mutableUiState.value =
                mutableUiState.value.copy(
                    loginId = value,
                    loginIdError = null,
                    requestError = null,
                )
        }

        fun onPasswordChange(value: String) {
            mutableUiState.value =
                mutableUiState.value.copy(
                    password = value,
                    passwordError = null,
                    requestError = null,
                )
        }

        fun login() {
            if (loginJob?.isActive == true || !mutableUiState.value.canSubmit) return
            if (!validate()) return

            loginJob =
                viewModelScope.launch {
                    mutableUiState.value = mutableUiState.value.copy(isLoading = true, requestError = null)
                    when (
                        val result =
                            authRepository.login(
                                loginId = mutableUiState.value.loginId,
                                password = mutableUiState.value.password,
                            )
                    ) {
                        LoginResult.Success -> {
                            mutableUiState.value = mutableUiState.value.copy(password = "", isLoading = false)
                            eventChannel.send(LoginEvent.LoginSucceeded)
                        }
                        is LoginResult.InvalidInput -> showRequestError(result.message)
                        is LoginResult.AuthenticationFailed -> showRequestError(result.message)
                        is LoginResult.ServerFailure -> showRequestError(result.message)
                        is LoginResult.ConnectionFailure -> showRequestError(result.message)
                        is LoginResult.TemporarilyBlocked -> startRetryCountdown(result)
                    }
                }
        }

        private fun validate(): Boolean {
            val state = mutableUiState.value
            val loginIdError =
                when {
                    state.loginId.isBlank() -> "아이디를 입력해 주세요."
                    state.loginId.codePointCount() > MAX_RAW_CODE_POINTS -> "아이디가 너무 깁니다."
                    else -> null
                }
            val passwordError =
                when {
                    state.password.isEmpty() -> "비밀번호를 입력해 주세요."
                    state.password.codePointCount() > MAX_RAW_CODE_POINTS -> "비밀번호가 너무 깁니다."
                    else -> null
                }
            mutableUiState.value =
                state.copy(
                    loginIdError = loginIdError,
                    passwordError = passwordError,
                    requestError = null,
                )
            return loginIdError == null && passwordError == null
        }

        private fun showRequestError(message: String) {
            mutableUiState.value = mutableUiState.value.copy(isLoading = false, requestError = message)
        }

        private fun startRetryCountdown(result: LoginResult.TemporarilyBlocked) {
            retryJob?.cancel()
            mutableUiState.value =
                mutableUiState.value.copy(
                    isLoading = false,
                    requestError = result.message,
                    retryAfterSeconds = result.retryAfterSeconds.coerceAtLeast(1),
                )
            retryJob =
                viewModelScope.launch {
                    while (mutableUiState.value.retryAfterSeconds > 0) {
                        delay(1_000)
                        mutableUiState.value =
                            mutableUiState.value.copy(
                                retryAfterSeconds = mutableUiState.value.retryAfterSeconds - 1,
                            )
                    }
                }
        }

        override fun onCleared() {
            mutableUiState.value = mutableUiState.value.copy(password = "")
            super.onCleared()
        }

        private fun String.codePointCount(): Int = codePointCount(0, length)

        private companion object {
            const val MAX_RAW_CODE_POINTS = 256
        }
    }
