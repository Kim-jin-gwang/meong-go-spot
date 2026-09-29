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
 * 조회 조건 버튼의 표시입니다.
 *
 * 길이가 줄어드는 가로줄 셋으로 "좁힌다"를 그린다. 깔때기 모양도 흔하지만 이 앱에는 다른 채워진
 * 도형이 없어 획으로만 그린 이쪽이 검색·등록 아이콘과 같은 결로 보인다.
 */
@Composable
fun FilterIcon(
    color: Color,
    modifier: Modifier = Modifier,
    iconSize: Dp = 22.dp,
    strokeWidth: Float = 2f,
) {
    Canvas(modifier.size(iconSize)) {
        val stroke = strokeWidth * density
        val w = size.width
        listOf(0.26f to 0.00f, 0.50f to 0.14f, 0.74f to 0.28f).forEach { (y, inset) ->
            drawLine(
                color = color,
                start = Offset(w * inset, size.height * y),
                end = Offset(w * (1f - inset), size.height * y),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun FilterIconPreview() {
    MeonggoBanjeomTheme {
        FilterIcon(color = Color.Black, iconSize = 44.dp)
    }
}
