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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoButton
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoTextField
import com.hotdog.meonggocuisine.core.designsystem.theme.MeonggoBanjeomTheme
import com.hotdog.meonggocuisine.core.navigation.NicknameEditRoute
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

data class NicknameEditUiState(
    val nickname: String = "",
    val nicknameError: String? = null,
    val requestError: String? = null,
    val isLoading: Boolean = false,
)

sealed interface NicknameEditEvent {
    data object Saved : NicknameEditEvent

    data object SessionExpired : NicknameEditEvent
}

@HiltViewModel
class NicknameEditViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val accountRepository: AccountRepository,
    ) : ViewModel() {
        private val mutableUiState =
            MutableStateFlow(NicknameEditUiState(nickname = savedStateHandle.toRoute<NicknameEditRoute>().currentNickname))
        val uiState: StateFlow<NicknameEditUiState> = mutableUiState.asStateFlow()

        private val eventChannel = Channel<NicknameEditEvent>(Channel.BUFFERED)
        val events = eventChannel.receiveAsFlow()
        private var job: Job? = null

        fun onNicknameChange(value: String) {
            mutableUiState.value = mutableUiState.value.copy(nickname = value, nicknameError = null, requestError = null)
        }

        fun save() {
            if (job?.isActive == true) return
            val error = AccountInputValidator.validateNickname(mutableUiState.value.nickname)
            if (error != null) {
                mutableUiState.value = mutableUiState.value.copy(nicknameError = error)
                return
            }
            job =
                viewModelScope.launch {
                    mutableUiState.value = mutableUiState.value.copy(isLoading = true, requestError = null)
                    when (val result = accountRepository.changeNickname(mutableUiState.value.nickname)) {
                        AccountResult.Success -> {
                            mutableUiState.value = mutableUiState.value.copy(isLoading = false)
                            eventChannel.send(NicknameEditEvent.Saved)
                        }
                        is AccountResult.Rejected ->
                            mutableUiState.value =
                                mutableUiState.value.copy(
                                    isLoading = false,
                                    nicknameError = if (result.field != null) result.message else null,
                                    requestError = if (result.field == null) result.message else null,
                                )
                        AccountResult.SessionExpired -> {
                            mutableUiState.value = mutableUiState.value.copy(isLoading = false)
                            eventChannel.send(NicknameEditEvent.SessionExpired)
                        }
                    }
                }
        }
    }

@Composable
fun NicknameEditRouteScreen(
    onBackClick: () -> Unit,
    onSaved: () -> Unit,
    onSessionExpired: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NicknameEditViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                NicknameEditEvent.Saved -> onSaved()
                NicknameEditEvent.SessionExpired -> onSessionExpired()
            }
        }
    }
    NicknameEditScreen(uiState, viewModel::onNicknameChange, viewModel::save, onBackClick, modifier)
}

@Composable
fun NicknameEditScreen(
    uiState: NicknameEditUiState,
    onNicknameChange: (String) -> Unit,
    onSaveClick: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AccountScaffold(title = "닉네임 변경", onBackClick = onBackClick, modifier = modifier) {
        Text(
            "게시물과 채팅에 보이는 이름입니다. 1~30자, 중복 가능.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        MeonggoTextField(
            value = uiState.nickname,
            onValueChange = onNicknameChange,
            modifier = Modifier.fillMaxWidth(),
            label = "닉네임",
            errorMessage = uiState.nicknameError,
            enabled = !uiState.isLoading,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSaveClick() }),
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
            text = "저장",
            onClick = onSaveClick,
            modifier = Modifier.fillMaxWidth(),
            enabled = !uiState.isLoading,
            isLoading = uiState.isLoading,
            loadingStateDescription = "닉네임 저장 중",
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 500)
@Composable
private fun NicknameEditScreenPreview() {
    MeonggoBanjeomTheme {
        NicknameEditScreen(NicknameEditUiState(nickname = "망고보호자"), {}, {}, {})
    }
}
