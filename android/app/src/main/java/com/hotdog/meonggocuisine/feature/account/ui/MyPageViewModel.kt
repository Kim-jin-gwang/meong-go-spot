package com.hotdog.meonggocuisine.feature.account.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hotdog.meonggocuisine.feature.account.data.AccountRepository
import com.hotdog.meonggocuisine.feature.account.data.ProfileResult
import com.hotdog.meonggocuisine.feature.auth.data.AuthSessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MyPageUiState(
    /** 세션에 있으면 즉시 보이고, 없으면(앱 재시작 뒤) A6 로 채운다. */
    val nickname: String? = null,
    val isLoading: Boolean = false,
    val loadError: String? = null,
    val isLoggingOut: Boolean = false,
    val showLogoutConfirm: Boolean = false,
)

sealed interface MyPageEvent {
    /** 로그아웃 또는 세션 만료 — 화면은 홈으로 돌아간다. */
    data object SignedOut : MyPageEvent
}

@HiltViewModel
class MyPageViewModel
    @Inject
    constructor(
        private val accountRepository: AccountRepository,
        authSessionManager: AuthSessionManager,
    ) : ViewModel() {
        private val mutableUiState =
            MutableStateFlow(MyPageUiState(nickname = authSessionManager.session.value?.member?.nickname))
        val uiState: StateFlow<MyPageUiState> = mutableUiState.asStateFlow()

        private val eventChannel = Channel<MyPageEvent>(Channel.BUFFERED)
        val events = eventChannel.receiveAsFlow()

        private var logoutJob: Job? = null

        /** 화면이 다시 보일 때마다 부른다 — 닉네임 변경 화면에서 돌아오면 새 값이 보여야 한다. */
        fun refresh() {
            viewModelScope.launch {
                mutableUiState.value = mutableUiState.value.copy(isLoading = true, loadError = null)
                when (val result = accountRepository.loadProfile()) {
                    is ProfileResult.Loaded ->
                        mutableUiState.value = mutableUiState.value.copy(nickname = result.nickname, isLoading = false)
                    is ProfileResult.Failed ->
                        mutableUiState.value = mutableUiState.value.copy(isLoading = false, loadError = result.message)
                    ProfileResult.SessionExpired -> {
                        mutableUiState.value = mutableUiState.value.copy(isLoading = false)
                        eventChannel.send(MyPageEvent.SignedOut)
                    }
                }
            }
        }

        fun requestLogout() {
            mutableUiState.value = mutableUiState.value.copy(showLogoutConfirm = true)
        }

        fun dismissLogout() {
            mutableUiState.value = mutableUiState.value.copy(showLogoutConfirm = false)
        }

        fun confirmLogout() {
            if (logoutJob?.isActive == true) return
            logoutJob =
                viewModelScope.launch {
                    mutableUiState.value = mutableUiState.value.copy(showLogoutConfirm = false, isLoggingOut = true)
                    accountRepository.logout()
                    mutableUiState.value = mutableUiState.value.copy(isLoggingOut = false)
                    eventChannel.send(MyPageEvent.SignedOut)
                }
        }
    }
