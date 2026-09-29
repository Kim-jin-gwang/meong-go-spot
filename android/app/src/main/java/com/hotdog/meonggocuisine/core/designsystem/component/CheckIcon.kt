package com.hotdog.meonggocuisine.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 체크 표시입니다. 추천 근거처럼 "맞다"를 줄머리에 붙일 때 씁니다.
 *
 * 다른 아이콘과 같은 이유로 직접 그린다 — `✓` 글리프는 기기 글꼴마다 굵기와 크기가 달라서,
 * 같은 줄의 한글 옆에서 어떤 기기에서는 도드라지고 어떤 기기에서는 묻힌다.
 */
@Composable
fun CheckIcon(
    color: Color,
    modifier: Modifier = Modifier,
    iconSize: Dp = 14.dp,
    strokeWidth: Float = 2f,
) {
    Canvas(modifier.size(iconSize)) {
        val stroke = strokeWidth.dp.toPx()
        val path =
            listOf(
                Offset(size.width * 0.16f, size.height * 0.54f),
                Offset(size.width * 0.40f, size.height * 0.78f),
                Offset(size.width * 0.86f, size.height * 0.24f),
            )
        path.zipWithNext { from, to ->
            drawLine(
                color = color,
                start = from,
                end = to,
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun CheckIconPreview() {
    CheckIcon(color = Color.Black, iconSize = 28.dp)
}
