package com.hotdog.meonggocuisine.feature.account.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoButton
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoTextField
import com.hotdog.meonggocuisine.core.designsystem.theme.MeonggoBanjeomTheme
import com.hotdog.meonggocuisine.feature.account.data.AccountField
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

data class PasswordChangeUiState(
    val currentPassword: String = "",
    val newPassword: String = "",
    val newPasswordConfirm: String = "",
    val currentPasswordError: String? = null,
    val newPasswordError: String? = null,
    val newPasswordConfirmError: String? = null,
    val requestError: String? = null,
    val isLoading: Boolean = false,
)

sealed interface PasswordChangeEvent {
    data object Changed : PasswordChangeEvent

    data object SessionExpired : PasswordChangeEvent
}

@HiltViewModel
class PasswordChangeViewModel
    @Inject
    constructor(
        private val accountRepository: AccountRepository,
    ) : ViewModel() {
        private val mutableUiState = MutableStateFlow(PasswordChangeUiState())
        val uiState: StateFlow<PasswordChangeUiState> = mutableUiState.asStateFlow()

        private val eventChannel = Channel<PasswordChangeEvent>(Channel.BUFFERED)
        val events = eventChannel.receiveAsFlow()
        private var job: Job? = null

        fun onCurrentPasswordChange(value: String) {
            mutableUiState.value =
                mutableUiState.value.copy(currentPassword = value, currentPasswordError = null, requestError = null)
        }

        fun onNewPasswordChange(value: String) {
            mutableUiState.value =
                mutableUiState.value.copy(
                    newPassword = value,
                    // 공백·제어 문자는 친 순간 보인다 — 회원가입과 같은 규칙.
                    newPasswordError = AccountInputValidator.immediatePasswordError(value),
                    requestError = null,
                )
        }

        fun onNewPasswordConfirmChange(value: String) {
            mutableUiState.value =
                mutableUiState.value.copy(
                    newPasswordConfirm = value,
                    newPasswordConfirmError = AccountInputValidator.immediatePasswordError(value),
                    requestError = null,
                )
        }

        fun submit() {
            if (job?.isActive == true) return
            if (!validate()) return
            job =
                viewModelScope.launch {
                    val state = mutableUiState.value
                    mutableUiState.value = state.copy(isLoading = true, requestError = null)
                    when (val result = accountRepository.changePassword(state.currentPassword, state.newPassword)) {
                        AccountResult.Success -> {
                            mutableUiState.value = PasswordChangeUiState()
                            eventChannel.send(PasswordChangeEvent.Changed)
                        }
                        is AccountResult.Rejected ->
                            mutableUiState.value =
                                mutableUiState.value.copy(
                                    isLoading = false,
                                    currentPasswordError =
                                        result.message.takeIf { result.field == AccountField.CURRENT_PASSWORD },
                                    newPasswordError = result.message.takeIf { result.field == AccountField.NEW_PASSWORD },
                                    requestError = result.message.takeIf { result.field == null },
                                )
                        AccountResult.SessionExpired -> {
                            mutableUiState.value = PasswordChangeUiState()
                            eventChannel.send(PasswordChangeEvent.SessionExpired)
                        }
                    }
                }
        }

        private fun validate(): Boolean {
            val state = mutableUiState.value
            val currentError = AccountInputValidator.validateCurrentPassword(state.currentPassword)
            val newError = AccountInputValidator.validateNewPassword(state.newPassword)
            val confirmError = AccountInputValidator.validatePasswordConfirm(state.newPassword, state.newPasswordConfirm)
            mutableUiState.value =
                state.copy(
                    currentPasswordError = currentError,
                    newPasswordError = newError,
                    newPasswordConfirmError = confirmError,
                    requestError = null,
                )
            return currentError == null && newError == null && confirmError == null
        }

        override fun onCleared() {
            mutableUiState.value = PasswordChangeUiState()
            super.onCleared()
        }
    }

@Composable
fun PasswordChangeRouteScreen(
    onBackClick: () -> Unit,
    onChanged: () -> Unit,
    onSessionExpired: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PasswordChangeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                PasswordChangeEvent.Changed -> onChanged()
                PasswordChangeEvent.SessionExpired -> onSessionExpired()
            }
        }
    }
    PasswordChangeScreen(
        uiState = uiState,
        onCurrentPasswordChange = viewModel::onCurrentPasswordChange,
        onNewPasswordChange = viewModel::onNewPasswordChange,
        onNewPasswordConfirmChange = viewModel::onNewPasswordConfirmChange,
        onSubmitClick = viewModel::submit,
        onBackClick = onBackClick,
        modifier = modifier,
    )
}

@Composable
fun PasswordChangeScreen(
    uiState: PasswordChangeUiState,
    onCurrentPasswordChange: (String) -> Unit,
    onNewPasswordChange: (String) -> Unit,
    onNewPasswordConfirmChange: (String) -> Unit,
    onSubmitClick: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AccountScaffold(title = "비밀번호 변경", onBackClick = onBackClick, modifier = modifier) {
        Text(
            "변경하면 다른 기기의 로그인은 모두 해제되고, 이 기기는 그대로 사용할 수 있습니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        PasswordField(
            value = uiState.currentPassword,
            onValueChange = onCurrentPasswordChange,
            label = "현재 비밀번호",
            errorMessage = uiState.currentPasswordError,
            enabled = !uiState.isLoading,
            imeAction = ImeAction.Next,
        )
        Spacer(Modifier.height(12.dp))
        PasswordField(
            value = uiState.newPassword,
            onValueChange = onNewPasswordChange,
            label = "새 비밀번호",
            errorMessage = uiState.newPasswordError,
            supportingText = "8자 이상, 공백과 제어 문자(탭·줄바꿈 등)는 쓸 수 없습니다.",
            enabled = !uiState.isLoading,
            imeAction = ImeAction.Next,
        )
        Spacer(Modifier.height(12.dp))
        PasswordField(
            value = uiState.newPasswordConfirm,
            onValueChange = onNewPasswordConfirmChange,
            label = "새 비밀번호 확인",
            errorMessage = uiState.newPasswordConfirmError,
            enabled = !uiState.isLoading,
            imeAction = ImeAction.Done,
            onDone = onSubmitClick,
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
        MeonggoButton(
            text = "비밀번호 변경",
            onClick = onSubmitClick,
            modifier = Modifier.fillMaxWidth(),
            enabled = !uiState.isLoading,
            isLoading = uiState.isLoading,
            loadingStateDescription = "비밀번호 변경 중",
        )
    }
}

@Composable
internal fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    errorMessage: String?,
    enabled: Boolean,
    imeAction: ImeAction,
    supportingText: String? = null,
    onDone: () -> Unit = {},
) {
    MeonggoTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = label,
        supportingText = supportingText,
        errorMessage = errorMessage,
        enabled = enabled,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
    )
}

@Preview(showBackground = true, widthDp = 390, heightDp = 700)
@Composable
private fun PasswordChangeScreenPreview() {
    MeonggoBanjeomTheme {
        PasswordChangeScreen(PasswordChangeUiState(), {}, {}, {}, {}, {})
    }
}
