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
import com.hotdog.meonggocuisine.core.designsystem.theme.MeonggoBanjeomTheme

/**
 * 등록 버튼의 더하기 표시입니다.
 *
 * 글자 `+` 로 그리면 기기 글꼴마다 굵기와 광학 중심이 달라 버튼 안에서 살짝 떠 보인다.
 * 직접 그려 어느 기기에서도 같은 굵기로 정중앙에 온다.
 */
@Composable
fun PlusIcon(
    color: Color,
    modifier: Modifier = Modifier,
    iconSize: Dp = 22.dp,
    strokeWidth: Float = 2.4f,
) {
    Canvas(modifier.size(iconSize)) {
        val stroke = strokeWidth.dp.toPx()
        val inset = size.width * 0.2f
        drawLine(
            color = color,
            start = Offset(size.width / 2f, inset),
            end = Offset(size.width / 2f, size.height - inset),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(inset, size.height / 2f),
            end = Offset(size.width - inset, size.height / 2f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PlusIconPreview() {
    MeonggoBanjeomTheme {
        PlusIcon(color = Color.Black)
    }
}
