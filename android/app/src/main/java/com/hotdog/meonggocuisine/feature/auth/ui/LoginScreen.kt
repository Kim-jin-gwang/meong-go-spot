package com.hotdog.meonggocuisine.feature.auth.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotdog.meonggocuisine.R
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoButton
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoTextButton
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoTextField
import com.hotdog.meonggocuisine.core.designsystem.theme.MeonggoBanjeomTheme
import com.hotdog.meonggocuisine.core.designsystem.token.MeonggoSpacing

@Composable
fun LoginRouteScreen(
    onLoginSuccess: () -> Unit,
    onSignUpClick: () -> Unit,
    onFindAccountClick: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            if (event == LoginEvent.LoginSucceeded) onLoginSuccess()
        }
    }

    LoginScreen(
        uiState = uiState,
        onLoginIdChange = viewModel::onLoginIdChange,
        onPasswordChange = viewModel::onPasswordChange,
        onLoginClick = viewModel::login,
        onSignUpClick = onSignUpClick,
        onFindAccountClick = onFindAccountClick,
    )
}

@Composable
fun LoginScreen(
    uiState: LoginUiState,
    onLoginIdChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onLoginClick: () -> Unit,
    onSignUpClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFindAccountClick: () -> Unit = {},
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
                    .widthIn(max = LoginCardMaxWidth),
            shape = RoundedCornerShape(LoginCardRadius),
            // 바탕(`surface`)과 같은 색이면 카드가 바탕에 묻힌다. 목록 카드·탭 바처럼 순백으로
            // 올려야 입력하는 자리가 화면에서 떠 보인다.
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
            elevation = CardDefaults.cardElevation(defaultElevation = LoginCardElevation),
        ) {
            LoginContent(
                uiState = uiState,
                onLoginIdChange = onLoginIdChange,
                onPasswordChange = onPasswordChange,
                onLoginClick = onLoginClick,
                onSignUpClick = onSignUpClick,
                onFindAccountClick = onFindAccountClick,
            )
        }
    }
}

@Composable
private fun LoginContent(
    uiState: LoginUiState,
    onLoginIdChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onLoginClick: () -> Unit,
    onSignUpClick: () -> Unit,
    onFindAccountClick: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = LoginCardHorizontalPadding,
                    vertical = LoginCardVerticalPadding,
                ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MeonggoSpacing.small),
    ) {
        // 서비스명은 글씨 로고 에셋이다. 시스템 글꼴로 조판하면 로고의 둥근 획이 재현되지 않아
        // 헤더·시작 화면과 다른 글씨가 된다.
        //
        // 박스를 원본 비율로 잡아야 커진다. `ContentScale.Fit` 은 가로세로 배율 중 작은 쪽을
        // 따르므로 한쪽만 늘리면 반대쪽이 1 배로 남아 박스만 커지고 그림은 그대로다 (직접 재서
        // 확인함 — 높이만 40→56dp 로 올렸을 때 그려진 크기가 102×27dp 로 동일했다).
        Image(
            painter = painterResource(R.drawable.logo_wordmark),
            contentDescription = "멍고반점",
            modifier = Modifier.width(LoginWordmarkWidth).aspectRatio(WORDMARK_ASPECT_RATIO),
            contentScale = ContentScale.Fit,
        )
        Spacer(modifier = Modifier.height(MeonggoSpacing.extraLarge))
        MeonggoTextField(
            value = uiState.loginId,
            onValueChange = onLoginIdChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = "아이디",
            errorMessage = uiState.loginIdError,
            enabled = !uiState.isLoading,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
        )
        MeonggoTextField(
            value = uiState.password,
            onValueChange = onPasswordChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = "비밀번호",
            errorMessage = uiState.passwordError,
            enabled = !uiState.isLoading,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onLoginClick() }),
        )
        uiState.requestError?.let {
            Text(
                text =
                    if (uiState.retryAfterSeconds > 0) {
                        "$it (${uiState.retryAfterSeconds}초 후 재시도)"
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
            text = "로그인",
            onClick = onLoginClick,
            modifier = Modifier.fillMaxWidth(),
            enabled = uiState.canSubmit,
            isLoading = uiState.isLoading,
            loadingStateDescription = "로그인 요청 중",
        )
        // 회원가입과 계정 찾기를 한 줄에 — 둘 다 "로그인이 안 될 때" 가는 곳이다 (2026-09-23 QA "계정 찾기 없음").
        Row(
            modifier = Modifier.fillMaxWidth().offset(y = (-4).dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MeonggoTextButton(
                text = "아이디·비밀번호 찾기",
                onClick = onFindAccountClick,
                enabled = !uiState.isLoading,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                text = "|",
                color = MaterialTheme.colorScheme.outlineVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            MeonggoTextButton(
                text = "회원가입",
                onClick = onSignUpClick,
                enabled = !uiState.isLoading,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            )
        }
    }
}

/** 글씨 로고 원본 비율(306:82). 박스를 이 비율로 잡아야 그림이 박스를 꽉 채운다. */
private const val WORDMARK_ASPECT_RATIO = 306f / 82f

/** 글씨 로고 너비. 화면 가로(411dp)의 3분의 1쯤이고, 높이는 비율대로 약 35dp 가 된다. */
private val LoginWordmarkWidth = 136.dp

private val LoginCardMaxWidth = 356.dp
private val LoginCardRadius = 18.dp
private val LoginCardElevation = 6.dp
private val LoginCardHorizontalPadding = 42.dp
private val LoginCardVerticalPadding = 48.dp

@Preview(showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
private fun LoginScreenPreview() {
    MeonggoBanjeomTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            LoginScreen(
                uiState = LoginUiState(),
                onLoginIdChange = {},
                onPasswordChange = {},
                onLoginClick = {},
                onSignUpClick = {},
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
private fun LoginErrorPreview() {
    MeonggoBanjeomTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            LoginScreen(
                uiState =
                    LoginUiState(
                        loginId = "mango206",
                        password = "password",
                        requestError = "아이디 또는 비밀번호가 올바르지 않습니다.",
                    ),
                onLoginIdChange = {},
                onPasswordChange = {},
                onLoginClick = {},
                onSignUpClick = {},
            )
        }
    }
}
