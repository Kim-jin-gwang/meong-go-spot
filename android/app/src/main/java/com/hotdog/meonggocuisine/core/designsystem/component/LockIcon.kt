package com.hotdog.meonggocuisine.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 잠긴 자물쇠입니다. 더 손댈 수 없는 자리에 둡니다 — 종료된 게시물의 대화가 씁니다.
 *
 * 넘어가는 자리에 두던 꺾쇠를 대신한다. 꺾쇠는 "여기서 더 할 일이 있다"고 말하는데, 끝난
 * 대화에서는 그 말이 틀리다.
 */
@Composable
fun LockIcon(
    color: Color,
    modifier: Modifier = Modifier,
    iconSize: Dp = 16.dp,
    strokeWidth: Dp = 1.8.dp,
) {
    Canvas(modifier.size(iconSize)) {
        val stroke = strokeWidth.toPx()
        val bodyTop = size.height * 0.46f
        // 고리 — 몸통 위로 반원만 올린다.
        val shackle =
            Path().apply {
                addArc(
                    oval =
                        Rect(
                            left = size.width * 0.26f,
                            top = size.height * 0.10f,
                            right = size.width * 0.74f,
                            bottom = bodyTop + size.height * 0.12f,
                        ),
                    startAngleDegrees = 180f,
                    sweepAngleDegrees = 180f,
                )
            }
        drawPath(shackle, color = color, style = Stroke(width = stroke, cap = StrokeCap.Round))
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width * 0.16f, bodyTop),
            size = Size(size.width * 0.68f, size.height * 0.44f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(stroke * 1.2f),
            style = Stroke(width = stroke),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun LockIconPreview() {
    LockIcon(color = Color(0xFF2B2320), iconSize = 44.dp, strokeWidth = 4.dp)
}
