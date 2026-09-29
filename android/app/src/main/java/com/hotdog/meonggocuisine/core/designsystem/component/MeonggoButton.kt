package com.hotdog.meonggocuisine.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.hotdog.meonggocuisine.core.designsystem.theme.MeonggoBanjeomTheme
import com.hotdog.meonggocuisine.core.designsystem.token.MeonggoSpacing

@Composable
fun MeonggoButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    loadingStateDescription: String = "처리 중",
    // 옆에 다른 부품이 나란히 서는 자리(채팅 입력줄)는 같은 모서리를 써야 한 덩어리로 보인다.
    shape: Shape = ButtonDefaults.shape,
) {
    Button(
        onClick = onClick,
        modifier = modifier.loadingSemantics(isLoading, loadingStateDescription),
        enabled = enabled && !isLoading,
        shape = shape,
    ) {
        MeonggoButtonContent(text = text, isLoading = isLoading)
    }
}

/**
 * 되돌릴 수 없는 동작을 위한 주요 버튼입니다 — 계정 삭제가 씁니다.
 *
 * 색만 `error` 로 바뀌고 모양·크기·로딩 표시는 [MeonggoButton] 과 같다. 화면에서 색과 모양을
 * 직접 적으면 같은 자리의 버튼이 다른 화면과 어긋난다(계정 삭제가 실제로 그랬다).
 */
@Composable
fun MeonggoDangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    loadingStateDescription: String = "처리 중",
) {
    Button(
        onClick = onClick,
        modifier = modifier.loadingSemantics(isLoading, loadingStateDescription),
        enabled = enabled && !isLoading,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
    ) {
        MeonggoButtonContent(text = text, isLoading = isLoading)
    }
}

@Composable
fun MeonggoOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    loadingStateDescription: String = "처리 중",
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.loadingSemantics(isLoading, loadingStateDescription),
        enabled = enabled && !isLoading,
    ) {
        MeonggoButtonContent(text = text, isLoading = isLoading)
    }
}

@Composable
fun MeonggoTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    textStyle: TextStyle = MaterialTheme.typography.labelLarge,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
    ) {
        Text(text = text, style = textStyle)
    }
}

@Composable
private fun RowScope.MeonggoButtonContent(
    text: String,
    isLoading: Boolean,
) {
    if (isLoading) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            color = LocalContentColor.current,
            strokeWidth = 2.dp,
        )
    } else {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

private fun Modifier.loadingSemantics(
    isLoading: Boolean,
    description: String,
): Modifier =
    if (isLoading) {
        semantics { stateDescription = description }
    } else {
        this
    }

@Preview(showBackground = true)
@Composable
private fun MeonggoButtonPreview() {
    MeonggoBanjeomTheme {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(MeonggoSpacing.small),
        ) {
            MeonggoButton(text = "확인", onClick = {})
            MeonggoOutlinedButton(text = "취소", onClick = {})
            MeonggoTextButton(text = "회원가입", onClick = {})
            MeonggoButton(text = "저장", onClick = {}, enabled = false)
            MeonggoButton(text = "저장", onClick = {}, isLoading = true)
        }
    }
}
