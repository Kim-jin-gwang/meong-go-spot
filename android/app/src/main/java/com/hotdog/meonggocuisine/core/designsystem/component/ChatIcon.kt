package com.hotdog.meonggocuisine.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hotdog.meonggocuisine.core.designsystem.theme.MeonggoBanjeomTheme

/**
 * 말풍선 아이콘 — 둥근 사각형과 왼쪽 아래 꼬리.
 *
 * 프로젝트의 다른 아이콘처럼 폰트 글리프 대신 직접 그린다. 글리프는 기기에 따라 빠져 있거나
 * 모양이 달라서, 탭 아이콘이 기기마다 다르게 보이는 문제를 이미 겪었다.
 */
@Composable
fun ChatIcon(
    color: Color,
    modifier: Modifier = Modifier,
    iconSize: Dp = 22.dp,
    strokeWidth: Float = 1.8f,
) {
    // 크기는 [iconSize] 로 받는다. modifier 로 넘기면 여기서 다시 덮어써 호출부 값이 먹지 않는다.
    Canvas(modifier.size(iconSize)) {
        val stroke = Stroke(width = strokeWidth.dp.toPx())
        val bubble =
            Path().apply {
                addRoundRect(
                    RoundRect(
                        rect =
                            Rect(
                                left = size.width * 0.12f,
                                top = size.height * 0.18f,
                                right = size.width * 0.88f,
                                bottom = size.height * 0.68f,
                            ),
                        cornerRadius = CornerRadius(size.width * 0.16f),
                    ),
                )
                // 꼬리는 말풍선 아래 모서리에서 갈라져 나온다.
                moveTo(size.width * 0.30f, size.height * 0.68f)
                lineTo(size.width * 0.30f, size.height * 0.86f)
                lineTo(size.width * 0.50f, size.height * 0.68f)
            }
        drawPath(bubble, color = color, style = stroke)
        val dotRadius = size.width * 0.045f
        listOf(0.32f, 0.50f, 0.68f).forEach { x ->
            drawCircle(
                color = color,
                radius = dotRadius,
                center = Offset(size.width * x, size.height * 0.43f),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ChatIconPreview() {
    MeonggoBanjeomTheme {
        ChatIcon(color = Color.Black)
    }
}
