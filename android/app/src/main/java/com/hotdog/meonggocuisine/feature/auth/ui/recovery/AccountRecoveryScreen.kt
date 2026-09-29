package com.hotdog.meonggocuisine.feature.auth.ui.recovery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoButton
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoScreenHeader
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoTextButton
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoTextField
import com.hotdog.meonggocuisine.core.designsystem.theme.MeonggoBanjeomTheme
import com.hotdog.meonggocuisine.core.designsystem.token.MeonggoSpacing
import com.hotdog.meonggocuisine.feature.auth.ui.signup.PhoneActionButton
import com.hotdog.meonggocuisine.feature.auth.ui.signup.SignUpInputValidator
import com.hotdog.meonggocuisine.feature.auth.ui.signup.SignUpPasswordField

@Composable
fun AccountRecoveryRouteScreen(
    onBackClick: () -> Unit,
    onGoToLogin: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AccountRecoveryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    AccountRecoveryScreen(
        uiState = uiState,
        actions =
            AccountRecoveryActions(
                onBackClick = onBackClick,
                onGoToLogin = onGoToLogin,
                onPhoneNumberChange = viewModel::onPhoneNumberChange,
                onRequestCode = viewModel::requestCode,
                onVerificationCodeChange = viewModel::onVerificationCodeChange,
                onConfirmCode = viewModel::confirmCode,
                onNewPasswordChange = viewModel::onNewPasswordChange,
                onNewPasswordConfirmChange = viewModel::onNewPasswordConfirmChange,
                onResetPassword = viewModel::resetPassword,
            ),
        modifier = modifier,
    )
}

data class AccountRecoveryActions(
    val onBackClick: () -> Unit,
    val onGoToLogin: () -> Unit,
    val onPhoneNumberChange: (String) -> Unit,
    val onRequestCode: () -> Unit,
    val onVerificationCodeChange: (String) -> Unit,
    val onConfirmCode: () -> Unit,
    val onNewPasswordChange: (String) -> Unit,
    val onNewPasswordConfirmChange: (String) -> Unit,
    val onResetPassword: () -> Unit,
)

/**
 * 계정 찾기 — 로그인·회원가입과 같은 흰 카드 한 장에 세 단계를 차례로 보인다.
 *
 * 아이디 찾기와 비밀번호 재설정을 한 흐름으로 묶는다. 둘 다 같은 휴대전화 인증을 거치는데 갈래를 나누면
 * 사용자가 먼저 "무엇을 잃었는지" 골라야 한다. 인증이 끝나면 아이디를 보여 주고, 비밀번호는 원할 때만 바꾼다.
 */
@Composable
fun AccountRecoveryScreen(
    uiState: AccountRecoveryUiState,
    actions: AccountRecoveryActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .imePadding(),
    ) {
        MeonggoScreenHeader(title = "계정 찾기", onBackClick = actions.onBackClick)
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = MeonggoSpacing.large, vertical = MeonggoSpacing.large),
            contentAlignment = Alignment.TopCenter,
        ) {
            Card(
                modifier = Modifier.fillMaxWidth().widthIn(max = CardMaxWidth),
                shape = RoundedCornerShape(CardRadius),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                elevation = CardDefaults.cardElevation(defaultElevation = CardElevation),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(MeonggoSpacing.medium),
                ) {
                    when (uiState.step) {
                        RecoveryStep.PHONE -> PhoneStep(uiState, actions)
                        RecoveryStep.FOUND -> FoundStep(uiState, actions)
                        RecoveryStep.DONE -> DoneStep(uiState, actions)
                    }
                    uiState.requestError?.let {
                        Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun PhoneStep(
    uiState: AccountRecoveryUiState,
    actions: AccountRecoveryActions,
) {
    Text(
        text = "가입할 때 인증한 휴대전화 번호로\n아이디를 확인하고 비밀번호를 다시 정할 수 있어요.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        MeonggoTextField(
            value = uiState.phoneNumber,
            onValueChange = actions.onPhoneNumberChange,
            modifier = Modifier.weight(1f),
            placeholder = "휴대전화 번호",
            supportingText = if (uiState.isCodeSent) "문자로 보낸 인증번호를 입력해 주세요" else null,
            errorMessage = uiState.phoneNumberError,
            enabled = !uiState.isBusy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
        )
        Spacer(Modifier.width(MeonggoSpacing.small))
        PhoneActionButton(
            text =
                when {
                    uiState.resendAfterSeconds > 0 -> "${uiState.resendAfterSeconds}초"
                    uiState.isCodeSent -> "재전송"
                    else -> "인증 요청"
                },
            onClick = actions.onRequestCode,
            enabled = uiState.canRequestCode,
            isLoading = uiState.isRequestingCode,
        )
    }
    if (uiState.isCodeSent) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            MeonggoTextField(
                value = uiState.verificationCode,
                onValueChange = actions.onVerificationCodeChange,
                modifier = Modifier.weight(1f),
                placeholder = "인증번호 6자리",
                errorMessage = uiState.verificationCodeError,
                enabled = !uiState.isBusy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            )
            Spacer(Modifier.width(MeonggoSpacing.small))
            PhoneActionButton(
                text = "확인",
                onClick = actions.onConfirmCode,
                enabled = uiState.canConfirmCode,
                isLoading = uiState.isConfirmingCode,
            )
        }
    }
}

@Composable
private fun FoundStep(
    uiState: AccountRecoveryUiState,
    actions: AccountRecoveryActions,
) {
    Text(text = "회원님의 아이디", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = uiState.loginId.orEmpty(),
            modifier = Modifier.padding(horizontal = MeonggoSpacing.large, vertical = MeonggoSpacing.medium),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
    }
    MeonggoTextButton(
        text = "이 아이디로 로그인하기",
        onClick = actions.onGoToLogin,
        modifier = Modifier.fillMaxWidth(),
        enabled = !uiState.isBusy,
    )
    Spacer(Modifier.height(MeonggoSpacing.small))
    Text(text = "비밀번호를 잊으셨다면 새로 정해 주세요", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    SignUpPasswordField(
        value = uiState.newPassword,
        onValueChange = actions.onNewPasswordChange,
        placeholder = "새 비밀번호 (8자 이상, 공백 없이)",
        supportingText = SignUpInputValidator.PASSWORD_RULE,
        errorMessage = uiState.newPasswordError,
        enabled = !uiState.isBusy,
    )
    SignUpPasswordField(
        value = uiState.newPasswordConfirm,
        onValueChange = actions.onNewPasswordConfirmChange,
        placeholder = "새 비밀번호 재입력",
        errorMessage = uiState.newPasswordConfirmError,
        enabled = !uiState.isBusy,
    )
    MeonggoButton(
        text = "비밀번호 재설정",
        onClick = actions.onResetPassword,
        modifier = Modifier.fillMaxWidth(),
        enabled = uiState.canReset,
        isLoading = uiState.isResetting,
        loadingStateDescription = "비밀번호 재설정 중",
    )
}

@Composable
private fun DoneStep(
    uiState: AccountRecoveryUiState,
    actions: AccountRecoveryActions,
) {
    Text(
        text = "비밀번호를 바꿨어요",
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
    )
    Text(
        text = "다른 기기의 로그인은 모두 해제됐어요. 새 비밀번호로 다시 로그인해 주세요.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    uiState.loginId?.let {
        Text(text = "아이디: $it", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
    MeonggoButton(text = "로그인으로", onClick = actions.onGoToLogin, modifier = Modifier.fillMaxWidth())
}

private val CardMaxWidth = 480.dp
private val CardRadius = 24.dp
private val CardElevation = 2.dp

private val PreviewActions =
    AccountRecoveryActions({}, {}, {}, {}, {}, {}, {}, {}, {})

@Preview(showBackground = true)
@Composable
private fun AccountRecoveryPhonePreview() {
    MeonggoBanjeomTheme {
        AccountRecoveryScreen(
            uiState = AccountRecoveryUiState(phoneNumber = "01012345678", isCodeSent = true, resendAfterSeconds = 42),
            actions = PreviewActions,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AccountRecoveryFoundPreview() {
    MeonggoBanjeomTheme {
        AccountRecoveryScreen(
            uiState = AccountRecoveryUiState(step = RecoveryStep.FOUND, loginId = "mango206"),
            actions = PreviewActions,
        )
    }
}
