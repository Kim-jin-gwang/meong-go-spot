package com.hotdog.meonggocuisine.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.hotdog.meonggocuisine.core.designsystem.theme.MeonggoBanjeomTheme

/**
 * 하단 탭의 집 아이콘입니다.
 *
 * 프로젝트의 다른 아이콘처럼 폰트 글리프 대신 직접 그린다. 글리프(`⌂`)는 기기 글꼴에 따라
 * 굵기와 위치가 달라져 세 탭의 아이콘 크기가 어긋난다.
 */
@Composable
fun HomeIcon(
    color: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Float = 1.9f,
) {
    Canvas(modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        val house =
            Path().apply {
                moveTo(w * 0.13f, h * 0.46f)
                lineTo(w * 0.5f, h * 0.12f)
                lineTo(w * 0.87f, h * 0.46f)
                lineTo(w * 0.87f, h * 0.86f)
                lineTo(w * 0.13f, h * 0.86f)
                close()
            }
        drawPath(
            path = house,
            color = color,
            style =
                Stroke(
                    width = strokeWidth.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeIconPreview() {
    MeonggoBanjeomTheme {
        HomeIcon(color = Color.Black)
    }
}
