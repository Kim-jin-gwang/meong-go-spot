package com.hotdog.meonggocuisine.feature.auth.ui.signup

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoButton
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoTextButton
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoTextField
import com.hotdog.meonggocuisine.core.designsystem.theme.MeonggoBanjeomTheme
import com.hotdog.meonggocuisine.core.designsystem.token.MeonggoSpacing

data class SignUpActions(
    val onLoginIdChange: (String) -> Unit,
    val onPasswordChange: (String) -> Unit,
    val onPasswordConfirmChange: (String) -> Unit,
    val onNicknameChange: (String) -> Unit,
    val onPhoneNumberChange: (String) -> Unit,
    val onVerificationCodeChange: (String) -> Unit,
    val onPrivacyAgreementChange: (Boolean) -> Unit,
    val onCheckLoginId: () -> Unit,
    val onFieldFocusChanged: (SignUpField, Boolean) -> Unit,
    val onPrivacyPolicyClick: () -> Unit,
    val onRequestPhoneCode: () -> Unit,
    val onConfirmPhoneCode: () -> Unit,
    val onSignUpClick: () -> Unit,
    val onLoginClick: () -> Unit,
)

@Composable
fun SignUpRouteScreen(
    onSignUpSuccess: () -> Unit,
    onLoginClick: () -> Unit,
    viewModel: SignUpViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            if (event == SignUpEvent.SignUpSucceeded) onSignUpSuccess()
        }
    }

    SignUpScreen(
        uiState = uiState,
        actions =
            SignUpActions(
                onLoginIdChange = viewModel::onLoginIdChange,
                onPasswordChange = viewModel::onPasswordChange,
                onPasswordConfirmChange = viewModel::onPasswordConfirmChange,
                onNicknameChange = viewModel::onNicknameChange,
                onPhoneNumberChange = viewModel::onPhoneNumberChange,
                onVerificationCodeChange = viewModel::onVerificationCodeChange,
                onPrivacyAgreementChange = viewModel::onPrivacyCollectionAgreementChange,
                onPrivacyPolicyClick = { uriHandler.openUri(PRIVACY_POLICY_URL) },
                onFieldFocusChanged = viewModel::onFieldFocusChanged,
                onCheckLoginId = viewModel::checkLoginIdAvailability,
                onRequestPhoneCode = viewModel::requestPhoneCode,
                onConfirmPhoneCode = viewModel::confirmPhoneCode,
                onSignUpClick = viewModel::signup,
                onLoginClick = onLoginClick,
            ),
    )
}

@Composable
fun SignUpScreen(
    uiState: SignUpUiState,
    actions: SignUpActions,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MeonggoSpacing.large, vertical = MeonggoSpacing.extraLarge),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = SignUpCardMaxWidth),
            shape = RoundedCornerShape(SignUpCardRadius),
            // 바탕(`surface`)과 같은 색이면 카드가 바탕에 묻힌다. 로그인 화면과 같이 순백으로 올린다.
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
            elevation = CardDefaults.cardElevation(defaultElevation = SignUpCardElevation),
        ) {
            SignUpContent(
                uiState = uiState,
                actions = actions,
            )
        }
    }
}

@Composable
private fun SignUpContent(
    uiState: SignUpUiState,
    actions: SignUpActions,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = SignUpCardHorizontalPadding, vertical = SignUpCardVerticalPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SignUpFieldSpacing),
    ) {
        // 여기는 서비스 첫 화면이 아니라 하위 화면이라 브랜드 로고 대신 무엇을 하는 화면인지 적는다.
        Text(
            text = "회원가입",
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(MeonggoSpacing.medium))
        LoginIdRow(
            uiState = uiState,
            onLoginIdChange = actions.onLoginIdChange,
            onCheckLoginId = actions.onCheckLoginId,
            onFieldFocusChanged = actions.onFieldFocusChanged,
        )
        SignUpPasswordField(
            value = uiState.password,
            onValueChange = actions.onPasswordChange,
            // 규칙은 placeholder 에 적는다. 안내를 늘 띄워 두면 칸마다 글이 한 줄씩 붙어
            // 폼이 길어지고, 정작 어겼을 때의 경고가 그 안내와 섞여 눈에 덜 들어온다.
            placeholder = "비밀번호 입력 (8자 이상, 공백 없이)",
            errorMessage = uiState.passwordError,
            enabled = !uiState.isBusy,
            modifier = Modifier.signUpFieldFocus(SignUpField.PASSWORD, actions.onFieldFocusChanged),
        )
        SignUpPasswordField(
            value = uiState.passwordConfirm,
            onValueChange = actions.onPasswordConfirmChange,
            placeholder = "비밀번호 재입력",
            errorMessage = uiState.passwordConfirmError,
            enabled = !uiState.isBusy,
            modifier = Modifier.signUpFieldFocus(SignUpField.PASSWORD_CONFIRM, actions.onFieldFocusChanged),
        )
        MeonggoTextField(
            value = uiState.nickname,
            onValueChange = actions.onNicknameChange,
            modifier = Modifier.fillMaxWidth().signUpFieldFocus(SignUpField.NICKNAME, actions.onFieldFocusChanged),
            placeholder = "닉네임 입력 (30자 이내)",
            errorMessage = uiState.nicknameError,
            enabled = !uiState.isBusy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
        )
        PhoneNumberRow(
            uiState = uiState,
            onPhoneNumberChange = actions.onPhoneNumberChange,
            onRequestPhoneCode = actions.onRequestPhoneCode,
            onFieldFocusChanged = actions.onFieldFocusChanged,
        )
        if (uiState.phoneVerificationState != PhoneVerificationState.IDLE) {
            PhoneCodeRow(
                uiState = uiState,
                onVerificationCodeChange = actions.onVerificationCodeChange,
                onConfirmPhoneCode = actions.onConfirmPhoneCode,
            )
        }
        PrivacyAgreement(
            checked = uiState.privacyCollectionAgreed,
            enabled = !uiState.isBusy,
            errorMessage = uiState.privacyCollectionError,
            onCheckedChange = actions.onPrivacyAgreementChange,
            onPolicyClick = actions.onPrivacyPolicyClick,
        )
        uiState.requestError?.let {
            Text(
                text =
                    if (uiState.signupRetryAfterSeconds > 0) {
                        "$it (${uiState.signupRetryAfterSeconds}초 후 재시도)"
                    } else {
                        it
                    },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(modifier = Modifier.height(MeonggoSpacing.extraSmall))
        MeonggoButton(
            text = "회원가입",
            onClick = actions.onSignUpClick,
            modifier = Modifier.fillMaxWidth(),
            enabled = uiState.canSubmit,
            isLoading = uiState.isSubmitting,
            loadingStateDescription = "회원가입 요청 중",
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "이미 계정이 있으신가요?",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MeonggoTextButton(
                text = "로그인",
                onClick = actions.onLoginClick,
                enabled = !uiState.isBusy,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            )
        }
    }
}

private val SignUpCardMaxWidth = 356.dp
private val SignUpCardRadius = 18.dp
private val SignUpCardElevation = 6.dp
private val SignUpCardHorizontalPadding = 24.dp
private val SignUpCardVerticalPadding = 32.dp
private val SignUpFieldSpacing = 6.dp
private const val PRIVACY_POLICY_URL = "https://api.meonggo.shop/privacy"

@Preview(showBackground = true, backgroundColor = 0xFFFFFFFF, heightDp = 900)
@Composable
private fun SignUpScreenPreview() {
    MeonggoBanjeomTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            SignUpScreen(
                uiState = SignUpUiState(),
                actions = previewSignUpActions(),
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFFFFFF, heightDp = 900)
@Composable
private fun SignUpVerifiedPreview() {
    MeonggoBanjeomTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            SignUpScreen(
                uiState =
                    SignUpUiState(
                        loginId = "mango206",
                        password = "valid-password-123",
                        passwordConfirm = "valid-password-123",
                        nickname = "망고보호자",
                        phoneNumber = "01012345678",
                        verificationCode = "123456",
                        privacyCollectionAgreed = true,
                        phoneVerificationState = PhoneVerificationState.VERIFIED,
                    ),
                actions = previewSignUpActions(),
            )
        }
    }
}

private fun previewSignUpActions(): SignUpActions =
    SignUpActions(
        onLoginIdChange = {},
        onPasswordChange = {},
        onPasswordConfirmChange = {},
        onNicknameChange = {},
        onPhoneNumberChange = {},
        onVerificationCodeChange = {},
        onPrivacyAgreementChange = {},
        onPrivacyPolicyClick = {},
        onFieldFocusChanged = { _, _ -> },
        onCheckLoginId = {},
        onRequestPhoneCode = {},
        onConfirmPhoneCode = {},
        onSignUpClick = {},
        onLoginClick = {},
    )
