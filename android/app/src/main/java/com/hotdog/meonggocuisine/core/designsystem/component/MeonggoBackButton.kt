package com.hotdog.meonggocuisine.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.hotdog.meonggocuisine.core.designsystem.theme.MeonggoBanjeomTheme

/**
 * 머리줄 왼쪽 끝의 뒤로 가기입니다.
 *
 * 글꼴의 `←` 문자를 쓰던 자리다. 문자는 굵기와 크기가 글꼴을 따라가서 화면마다 다르게 보였고,
 * 상세 화면([MeonggoScreenHeader])만 [ChevronLeftIcon] 을 써서 같은 앱 안에 뒤로 가기가 두
 * 종류였다. 꺾쇠 하나로 맞춘다(2026-09-24).
 *
 * 단추 면은 깔지 않는다 — 머리줄에서 유일하게 누르는 것이라 면까지 두면 무거워진다. 대신 꺾쇠
 * 둘레로 [TouchInset] 만큼 눌리는 넓이를 남겨 손가락이 닿을 자리는 지킨다.
 */
@Composable
fun MeonggoBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Box(
        modifier =
            modifier
                .clip(CircleShape)
                .semantics { contentDescription = "뒤로 가기" }
                .clickable(onClick = onClick)
                .padding(TouchInset),
    ) {
        ChevronLeftIcon(iconSize = IconSize, strokeWidth = StrokeWidth, color = color)
    }
}

internal val IconSize = 26.dp
internal val StrokeWidth = 2.6.dp
private val TouchInset = 8.dp

@Preview(showBackground = true, backgroundColor = 0xFFFDFBF7)
@Composable
private fun MeonggoBackButtonPreview() {
    MeonggoBanjeomTheme {
        MeonggoBackButton(onClick = {})
    }
}
