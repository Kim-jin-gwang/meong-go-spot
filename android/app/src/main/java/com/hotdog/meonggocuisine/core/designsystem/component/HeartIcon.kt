package com.hotdog.meonggocuisine.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.hotdog.meonggocuisine.core.designsystem.theme.MeonggoBanjeomTheme

/**
 * 하트 아이콘 — [filled]가 참이면 채우고 아니면 선으로 그립니다.
 *
 * 프로젝트의 다른 아이콘처럼 폰트 글리프 대신 직접 그린다. 채운 하트와 빈 하트를 같은 모양으로
 * 유지해야 눌렀을 때 형태가 바뀌지 않고 색과 채움만 바뀐다.
 */
@Composable
fun HeartIcon(
    color: Color,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    strokeWidth: Float = 1.8f,
) {
    Canvas(modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        val heart =
            Path().apply {
                moveTo(w * 0.5f, h * 0.88f)
                cubicTo(w * 0.12f, h * 0.62f, w * 0.04f, h * 0.36f, w * 0.22f, h * 0.22f)
                cubicTo(w * 0.36f, h * 0.11f, w * 0.46f, h * 0.22f, w * 0.5f, h * 0.31f)
                cubicTo(w * 0.54f, h * 0.22f, w * 0.64f, h * 0.11f, w * 0.78f, h * 0.22f)
                cubicTo(w * 0.96f, h * 0.36f, w * 0.88f, h * 0.62f, w * 0.5f, h * 0.88f)
                close()
            }
        drawPath(
            path = heart,
            color = color,
            style = if (filled) Fill else Stroke(width = strokeWidth.dp.toPx()),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HeartIconPreview() {
    MeonggoBanjeomTheme {
        HeartIcon(color = Color.Black, filled = true)
    }
}
