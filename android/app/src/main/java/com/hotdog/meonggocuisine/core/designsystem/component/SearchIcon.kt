package com.hotdog.meonggocuisine.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hotdog.meonggocuisine.core.designsystem.theme.MeonggoBanjeomTheme

/**
 * 하단 탭의 돋보기 아이콘입니다.
 *
 * [HomeIcon]과 같은 이유로 글리프(`⌕`) 대신 직접 그린다.
 */
@Composable
fun SearchIcon(
    color: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Float = 1.9f,
    // 크기는 여기서 받는다. modifier 로 넘기면 아래 size 가 다시 덮어써 호출부 값이 먹지 않는다.
    iconSize: Dp = 22.dp,
) {
    Canvas(modifier.size(iconSize)) {
        val w = size.width
        val h = size.height
        val stroke = strokeWidth.dp.toPx()
        drawCircle(
            color = color,
            radius = w * 0.29f,
            center = Offset(w * 0.44f, h * 0.44f),
            style = Stroke(width = stroke),
        )
        drawLine(
            color = color,
            start = Offset(w * 0.66f, h * 0.66f),
            end = Offset(w * 0.88f, h * 0.88f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SearchIconPreview() {
    MeonggoBanjeomTheme {
        SearchIcon(color = Color.Black)
    }
}
