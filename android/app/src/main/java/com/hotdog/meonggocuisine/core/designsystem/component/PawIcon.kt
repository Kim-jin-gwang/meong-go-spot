package com.hotdog.meonggocuisine.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hotdog.meonggocuisine.core.designsystem.theme.MeonggoBanjeomTheme

/**
 * 동물 정보 줄 앞의 발바닥 표시입니다.
 *
 * 발가락 넷과 발바닥 하나로 그린다. 프로젝트의 다른 아이콘처럼 폰트 글리프를 쓰지 않아 기기
 * 글꼴과 무관하게 같은 모양이다.
 */
@Composable
fun PawIcon(
    color: Color,
    modifier: Modifier = Modifier,
    iconSize: Dp = 14.dp,
) {
    Canvas(modifier.size(iconSize)) {
        val w = size.width
        val h = size.height
        val toe = Size(w * 0.22f, h * 0.28f)
        listOf(
            Offset(w * 0.10f, h * 0.20f),
            Offset(w * 0.37f, h * 0.06f),
            Offset(w * 0.65f, h * 0.06f),
            Offset(w * 0.92f, h * 0.20f),
        ).forEach { center ->
            drawOval(
                color = color,
                topLeft = Offset(center.x - toe.width / 2f, center.y - toe.height / 2f),
                size = toe,
            )
        }
        drawOval(
            color = color,
            topLeft = Offset(w * 0.18f, h * 0.44f),
            size = Size(w * 0.64f, h * 0.50f),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PawIconPreview() {
    MeonggoBanjeomTheme {
        PawIcon(color = Color.Black, iconSize = 28.dp)
    }
}
