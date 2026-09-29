package com.hotdog.meonggocuisine.feature.account.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoDangerButton
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoSurfaces
import com.hotdog.meonggocuisine.core.designsystem.theme.MeonggoBanjeomTheme
import com.hotdog.meonggocuisine.feature.account.data.AccountRepository
import com.hotdog.meonggocuisine.feature.account.data.AccountResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WithdrawUiState(
    val currentPassword: String = "",
    val currentPasswordError: String? = null,
    val requestError: String? = null,
    val isLoading: Boolean = false,
    val showConfirm: Boolean = false,
)

sealed interface WithdrawEvent {
    /** 탈퇴 완료 또는 세션 만료 — 두 경우 모두 로컬 세션은 이미 없다. */
    data object SignedOut : WithdrawEvent
}

@HiltViewModel
class WithdrawViewModel
    @Inject
    constructor(
        private val accountRepository: AccountRepository,
    ) : ViewModel() {
        private val mutableUiState = MutableStateFlow(WithdrawUiState())
        val uiState: StateFlow<WithdrawUiState> = mutableUiState.asStateFlow()

        private val eventChannel = Channel<WithdrawEvent>(Channel.BUFFERED)
        val events = eventChannel.receiveAsFlow()
        private var job: Job? = null

        fun onCurrentPasswordChange(value: String) {
            mutableUiState.value =
                mutableUiState.value.copy(currentPassword = value, currentPasswordError = null, requestError = null)
        }

        /** 버튼은 확인 대화상자만 연다. 비밀번호가 비어 있으면 대화상자도 열지 않는다. */
        fun requestWithdraw() {
            val error = AccountInputValidator.validateCurrentPassword(mutableUiState.value.currentPassword)
            mutableUiState.value = mutableUiState.value.copy(currentPasswordError = error, showConfirm = error == null)
        }

        fun dismissConfirm() {
            mutableUiState.value = mutableUiState.value.copy(showConfirm = false)
        }

        fun confirmWithdraw() {
            if (job?.isActive == true) return
            job =
                viewModelScope.launch {
                    mutableUiState.value = mutableUiState.value.copy(showConfirm = false, isLoading = true, requestError = null)
                    when (val result = accountRepository.withdraw(mutableUiState.value.currentPassword)) {
                        AccountResult.Success, AccountResult.SessionExpired -> {
                            mutableUiState.value = WithdrawUiState()
                            eventChannel.send(WithdrawEvent.SignedOut)
                        }
                        is AccountResult.Rejected ->
                            mutableUiState.value =
                                mutableUiState.value.copy(
                                    isLoading = false,
                                    currentPasswordError = if (result.field != null) result.message else null,
                                    requestError = if (result.field == null) result.message else null,
                                )
                    }
                }
        }

        override fun onCleared() {
            mutableUiState.value = WithdrawUiState()
            super.onCleared()
        }
    }

@Composable
fun WithdrawRouteScreen(
    onBackClick: () -> Unit,
    onSignedOut: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WithdrawViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                WithdrawEvent.SignedOut -> onSignedOut()
            }
        }
    }
    WithdrawScreen(
        uiState = uiState,
        onCurrentPasswordChange = viewModel::onCurrentPasswordChange,
        onWithdrawClick = viewModel::requestWithdraw,
        onConfirm = viewModel::confirmWithdraw,
        onConfirmDismiss = viewModel::dismissConfirm,
        onBackClick = onBackClick,
        modifier = modifier,
    )
}

@Composable
fun WithdrawScreen(
    uiState: WithdrawUiState,
    onCurrentPasswordChange: (String) -> Unit,
    onWithdrawClick: () -> Unit,
    onConfirm: () -> Unit,
    onConfirmDismiss: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AccountScaffold(title = "계정 삭제", onBackClick = onBackClick, modifier = modifier) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MeonggoSurfaces.cardShape,
            color = MaterialTheme.colorScheme.errorContainer,
        ) {
            Text(
                "계정을 삭제하면 바로 로그아웃되고 내가 올린 게시물은 즉시 비공개됩니다. " +
                    "게시물·사진·채팅과 계정 정보는 30일 안에 파기되며 되돌릴 수 없습니다. " +
                    "같은 휴대전화 번호로는 30일 뒤에 다시 가입할 수 있습니다.",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        Spacer(Modifier.height(20.dp))
        PasswordField(
            value = uiState.currentPassword,
            onValueChange = onCurrentPasswordChange,
            label = "현재 비밀번호",
            errorMessage = uiState.currentPasswordError,
            enabled = !uiState.isLoading,
            imeAction = ImeAction.Done,
            onDone = onWithdrawClick,
        )
        uiState.requestError?.let {
            Text(
                it,
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(24.dp))
        MeonggoDangerButton(
            text = "계정 삭제",
            onClick = onWithdrawClick,
            modifier = Modifier.fillMaxWidth(),
            isLoading = uiState.isLoading,
            loadingStateDescription = "삭제 처리 중",
        )
    }
    if (uiState.showConfirm) {
        AlertDialog(
            onDismissRequest = onConfirmDismiss,
            title = { Text("정말 삭제할까요?") },
            text = {
                Text("이 작업은 되돌릴 수 없습니다. 계정과 게시물이 삭제됩니다.", style = MaterialTheme.typography.bodyMedium)
            },
            confirmButton = {
                TextButton(onClick = onConfirm) {
                    Text("삭제", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = onConfirmDismiss) { Text("취소") } },
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 600)
@Composable
private fun WithdrawScreenPreview() {
    MeonggoBanjeomTheme {
        WithdrawScreen(WithdrawUiState(), {}, {}, {}, {}, {})
    }
}
