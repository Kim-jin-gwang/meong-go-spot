package com.hotdog.meonggocuisine.feature.auth.ui.signup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hotdog.meonggocuisine.R
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoButton
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoTextButton
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoTextField
import com.hotdog.meonggocuisine.core.designsystem.token.MeonggoSpacing

/**
 * 입력칸이 커서를 얻고 잃는 것을 알립니다.
 *
 * 칸마다 `Modifier.onFocusChanged` 를 직접 쓰면 어느 칸인지 실어 보내는 코드가 다섯 번 반복된다.
 */
internal fun Modifier.signUpFieldFocus(
    field: SignUpField,
    onFieldFocusChanged: (SignUpField, Boolean) -> Unit,
): Modifier = onFocusChanged { onFieldFocusChanged(field, it.isFocused) }

@Composable
internal fun SignUpPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    errorMessage: String?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
) {
    var isVisible by remember { mutableStateOf(false) }
    MeonggoTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = placeholder,
        supportingText = supportingText,
        errorMessage = errorMessage,
        enabled = enabled,
        visualTransformation = if (isVisible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
        trailingIcon = {
            IconButton(onClick = { isVisible = !isVisible }, enabled = enabled) {
                Icon(
                    painter = painterResource(if (isVisible) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                    contentDescription = if (isVisible) "비밀번호 숨기기" else "비밀번호 보기",
                )
            }
        },
    )
}

/**
 * 아이디 칸과 중복 확인 버튼입니다.
 *
 * 가입을 눌러야 `MEMBER-001` 로 알 수 있으면 나머지를 다 채우고 휴대전화 인증까지 끝낸 뒤에야
 * 처음부터 다시 하게 된다. 여기서 미리 물어본다.
 */
@Composable
internal fun LoginIdRow(
    uiState: SignUpUiState,
    onLoginIdChange: (String) -> Unit,
    onCheckLoginId: () -> Unit,
    onFieldFocusChanged: (SignUpField, Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        MeonggoTextField(
            value = uiState.loginId,
            onValueChange = onLoginIdChange,
            modifier = Modifier.weight(1f).signUpFieldFocus(SignUpField.LOGIN_ID, onFieldFocusChanged),
            placeholder = "아이디 입력 (50자 이내)",
            supportingText = uiState.loginIdCheckMessage,
            errorMessage = uiState.loginIdError,
            enabled = !uiState.isBusy,
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next,
                ),
        )
        Spacer(modifier = Modifier.width(MeonggoSpacing.small))
        PhoneActionButton(
            text = if (uiState.loginIdCheck == LoginIdCheckState.AVAILABLE) "확인 완료" else "중복 확인",
            onClick = onCheckLoginId,
            enabled = uiState.canCheckLoginId,
            isLoading = uiState.loginIdCheck == LoginIdCheckState.CHECKING,
        )
    }
}

@Composable
internal fun PhoneNumberRow(
    uiState: SignUpUiState,
    onPhoneNumberChange: (String) -> Unit,
    onRequestPhoneCode: () -> Unit,
    onFieldFocusChanged: (SignUpField, Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        MeonggoTextField(
            value = uiState.phoneNumber,
            onValueChange = onPhoneNumberChange,
            modifier = Modifier.weight(1f).signUpFieldFocus(SignUpField.PHONE_NUMBER, onFieldFocusChanged),
            // 안내 문구는 두지 않는다. 옆의 `인증 요청` 버튼이 이미 무슨 일이 일어나는지 말한다.
            placeholder = "휴대전화 번호",
            errorMessage = uiState.phoneNumberError,
            enabled = !uiState.isBusy,
            keyboardOptions =
                KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
        )
        Spacer(modifier = Modifier.width(MeonggoSpacing.small))
        PhoneActionButton(
            text =
                when {
                    uiState.isRequestingCode -> "전송 중"
                    uiState.resendAfterSeconds > 0 -> "${uiState.resendAfterSeconds}초"
                    uiState.phoneVerificationState == PhoneVerificationState.CODE_SENT -> "재전송"
                    else -> "인증 요청"
                },
            onClick = onRequestPhoneCode,
            enabled = uiState.canRequestCode,
            isLoading = uiState.isRequestingCode,
        )
    }
}

@Composable
internal fun PhoneCodeRow(
    uiState: SignUpUiState,
    onVerificationCodeChange: (String) -> Unit,
    onConfirmPhoneCode: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            MeonggoTextField(
                value = uiState.verificationCode,
                onValueChange = onVerificationCodeChange,
                modifier = Modifier.weight(1f),
                placeholder = "인증번호 6자리",
                errorMessage = uiState.verificationCodeError,
                enabled = !uiState.isBusy && uiState.phoneVerificationState != PhoneVerificationState.VERIFIED,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            )
            Spacer(modifier = Modifier.width(MeonggoSpacing.small))
            PhoneActionButton(
                text = if (uiState.phoneVerificationState == PhoneVerificationState.VERIFIED) "인증 완료" else "확인",
                onClick = onConfirmPhoneCode,
                enabled = uiState.canConfirmCode,
                isLoading = uiState.isConfirmingCode,
            )
        }
        if (uiState.phoneVerificationState == PhoneVerificationState.VERIFIED) {
            Text(
                text = "휴대전화 인증이 완료되었습니다.",
                modifier = Modifier.padding(start = MeonggoSpacing.small, top = MeonggoSpacing.extraSmall),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
internal fun PrivacyAgreement(
    checked: Boolean,
    enabled: Boolean,
    errorMessage: String?,
    onCheckedChange: (Boolean) -> Unit,
    onPolicyClick: () -> Unit,
) {
    var isNoticeOpen by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = MeonggoSpacing.small),
        verticalArrangement = Arrangement.spacedBy(MeonggoSpacing.extraSmall),
    ) {
        // 고지문 전체를 가입 화면에 펼쳐 두면 입력칸 사이에 글 여섯 문단이 끼어 가입 흐름이 끊긴다.
        // 여기에는 동의 여부만 두고 내용은 시트로 옮긴다 (`privacy-collection-v1`, UR-ACC-001).
        //
        // 켜는 것과 끄는 것이 다르다. 꺼져 있으면 줄을 눌러도 바로 켜지지 않고 시트가 열려,
        // 동의가 고지를 본 뒤의 행동이 된다. 켜져 있으면 눌러서 바로 끈다 — 이미 읽은 사람에게
        // 시트를 다시 보여 줄 이유가 없다.
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) {
                        if (checked) onCheckedChange(false) else isNoticeOpen = true
                    }
                    .padding(start = PrivacyRowStartInset, top = MeonggoSpacing.extraSmall, bottom = MeonggoSpacing.extraSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 기본 상자는 48dp 터치 영역 안에 20dp 네모가 들어 있어 왼쪽이 입력칸보다 안쪽에서
            // 시작한다. 줄 전체가 터치 영역이므로 상자는 보이는 크기로 줄여 입력칸 왼쪽 선에 맞춘다.
            Checkbox(
                checked = checked,
                onCheckedChange = null,
                modifier = Modifier.size(PrivacyCheckboxSize),
                enabled = enabled,
            )
            Spacer(modifier = Modifier.width(PrivacyLabelSpacing))
            Text(
                text = "[필수] 개인정보 수집·이용에 동의합니다.",
                modifier = Modifier.weight(weight = 1f, fill = false),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.width(PrivacyLabelSpacing))
            Text(
                text = "보기",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        errorMessage?.let {
            Text(
                text = it,
                modifier = Modifier.padding(start = MeonggoSpacing.small),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    if (isNoticeOpen) {
        PrivacyNoticeSheet(
            onDismiss = { isNoticeOpen = false },
            onPolicyClick = onPolicyClick,
            onAgree = {
                isNoticeOpen = false
                onCheckedChange(true)
            },
        )
    }
}

/**
 * 개인정보 수집·이용 고지 전문입니다.
 *
 * 문구는 승인 대상(`privacy-collection-v1`)이라 옮기기만 하고 한 글자도 고치지 않았다.
 *
 * 동의를 켜는 곳은 여기뿐이다. 끄는 것은 가입 화면에서 체크 상자를 다시 누르면 된다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrivacyNoticeSheet(
    onDismiss: () -> Unit,
    onPolicyClick: () -> Unit,
    onAgree: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = PrivacySheetRadius, topEnd = PrivacySheetRadius),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = PrivacySheetGutter)
                    .padding(bottom = PrivacySheetGutter),
        ) {
            Text(
                text = "개인정보 수집·이용 안내 (필수)",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(MeonggoSpacing.medium))
            Column(
                modifier =
                    Modifier
                        .weight(weight = 1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MeonggoSpacing.small),
            ) {
                PrivacyNoticeParagraph(
                    "수집 항목: 가입 시 아이디, 비밀번호, 닉네임, 휴대전화 번호. " +
                        "게시물 등록 시 반려동물 사진, 실종·목격 위치(정확한 위치·선택 좌표 포함)와 일시. " +
                        "채팅 이용 시 메시지 내용. " +
                        "서비스 이용 시 접속 IP·기록.",
                )
                PrivacyNoticeParagraph(
                    "이용 목적: 회원 관리·휴대전화 인증·중복 가입 및 부정 이용 방지, " +
                        "게시물 등록·AI 유사 후보·1:1 채팅 제공.",
                )
                PrivacyNoticeParagraph(
                    "보유 기간: 회원 정보는 이용 중 및 탈퇴 후 30일, 게시물(사진·위치 포함)은 종료·삭제 후 90일" +
                        "(탈퇴 시 30일 우선), 채팅은 탈퇴 후 30일 내 삭제·익명화. 접속 기록은 목적 달성 후 파기.",
                )
                PrivacyNoticeParagraph(
                    "휴대전화 인증 문자 발송은 SOLAPI에 위탁합니다.",
                )
                PrivacyNoticeParagraph(
                    "동의를 거부할 수 있습니다. 거부해도 비회원 목록은 볼 수 있지만 " +
                        "회원가입과 회원 전용 기능은 이용할 수 없습니다.",
                )
                MeonggoTextButton(
                    text = "개인정보 처리방침 전문 보기",
                    onClick = onPolicyClick,
                )
            }
            Spacer(modifier = Modifier.height(MeonggoSpacing.medium))
            MeonggoButton(
                text = "동의합니다",
                onClick = onAgree,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PrivacyNoticeParagraph(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 입력칸 옆의 작은 외곽선 버튼 — 회원가입과 계정 찾기가 같이 쓴다. */
@Composable
internal fun PhoneActionButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    isLoading: Boolean,
) {
    val isButtonEnabled = enabled && !isLoading
    val contentColor =
        if (isButtonEnabled) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_CONTENT_ALPHA)
        }

    OutlinedButton(
        onClick = onClick,
        modifier =
            Modifier
                .width(PhoneActionButtonWidth)
                .height(PhoneActionButtonHeight)
                .semantics {
                    if (isLoading) stateDescription = "처리 중"
                },
        enabled = isButtonEnabled,
        shape = MaterialTheme.shapes.small,
        colors =
            ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.primary,
                disabledContentColor = contentColor,
            ),
        border = BorderStroke(1.dp, contentColor),
        contentPadding = PaddingValues(horizontal = MeonggoSpacing.small),
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = LocalContentColor.current,
                strokeWidth = 2.dp,
            )
        } else {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge.copy(fontSize = PhoneActionButtonFontSize),
                maxLines = 1,
            )
        }
    }
}

private val PhoneActionButtonWidth = 92.dp
private val PhoneActionButtonHeight = 56.dp
private val PhoneActionButtonFontSize = 14.sp

/** 체크 상자와 글자 사이, 글자와 `보기` 사이 간격. */
private val PrivacyLabelSpacing = 8.dp

/** 동의 줄을 입력칸 왼쪽 선보다 살짝 안으로 들인다. */
private val PrivacyRowStartInset = 4.dp

/** 체크 상자의 보이는 크기. 기본 48dp 터치 영역을 벗긴 값이다. */
private val PrivacyCheckboxSize = 20.dp

/** 고지 시트의 위 모서리. 필터 시트와 같은 값이다. */
private val PrivacySheetRadius = 28.dp

/** 고지 시트의 좌우·아래 여백. */
private val PrivacySheetGutter = 20.dp
private const val DISABLED_CONTENT_ALPHA = 0.38f
