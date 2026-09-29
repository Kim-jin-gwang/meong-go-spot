package com.hotdog.meonggocuisine.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 뒤로 가기 꺾쇠입니다.
 *
 * 글꼴의 `‹` 문자를 키워 쓰면 굵기가 글꼴을 따라가서, 옆에 붙는 제목이 굵을수록 꺾쇠만 가늘게
 * 보인다. 직접 그으면 [strokeWidth] 로 제목과 무게를 맞출 수 있다.
 */
@Composable
fun ChevronLeftIcon(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    iconSize: Dp = 24.dp,
    strokeWidth: Dp = 2.4.dp,
) {
    Canvas(modifier.size(iconSize)) {
        val stroke = strokeWidth.toPx()
        // 좌우로 조금 눌러 그려야 꺾쇠가 정사각 칸 가운데에 놓인다.
        val left = size.width * 0.34f
        val right = size.width * 0.66f
        val top = size.height * 0.2f
        val bottom = size.height * 0.8f
        val path =
            Path().apply {
                moveTo(right, top)
                lineTo(left, size.height / 2f)
                lineTo(right, bottom)
            }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ChevronLeftIconPreview() {
    ChevronLeftIcon(iconSize = 28.dp)
}
