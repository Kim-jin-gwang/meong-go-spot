package com.hotdog.meonggocuisine.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hotdog.meonggocuisine.core.designsystem.theme.MeonggoBanjeomTheme

/**
 * 날짜 줄 앞의 달력 표시입니다.
 *
 * 몸통 테두리와 위쪽 고리 둘, 머리 칸을 나누는 가로줄로 그린다. 프로젝트의 다른 아이콘처럼
 * 폰트 글리프를 쓰지 않아 기기 글꼴과 무관하게 같은 모양이다.
 */
@Composable
fun CalendarIcon(
    color: Color,
    modifier: Modifier = Modifier,
    iconSize: Dp = 14.dp,
) {
    Canvas(modifier.size(iconSize)) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.11f
        listOf(w * 0.3f, w * 0.7f).forEach { x ->
            drawLine(
                color = color,
                start = Offset(x, 0f),
                end = Offset(x, h * 0.22f),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
        drawRoundRect(
            color = color,
            topLeft = Offset(stroke / 2f, h * 0.14f),
            size = Size(w - stroke, h * 0.86f - stroke / 2f),
            cornerRadius = CornerRadius(w * 0.16f),
            style = Stroke(width = stroke),
        )
        drawLine(
            color = color,
            start = Offset(stroke / 2f, h * 0.42f),
            end = Offset(w - stroke / 2f, h * 0.42f),
            strokeWidth = stroke,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun CalendarIconPreview() {
    MeonggoBanjeomTheme {
        CalendarIcon(color = Color.Black, iconSize = 28.dp)
    }
}
