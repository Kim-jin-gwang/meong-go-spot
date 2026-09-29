package com.hotdog.meonggocuisine.core.designsystem.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 오른쪽 꺾쇠입니다. 다음 화면으로 넘어가는 줄(마이페이지 메뉴)이 씁니다.
 *
 * [ChevronLeftIcon] 을 돌려 그린다. 같은 꺾쇠를 두 벌 그리면 굵기와 비율이 갈리고, 실제로
 * 마이페이지는 여기에 `›` 글리프를 쓰고 있어서 기기 글꼴마다 굵기가 달랐다.
 */
@Composable
fun ChevronRightIcon(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    iconSize: Dp = 24.dp,
    strokeWidth: Dp = 2.4.dp,
) {
    ChevronLeftIcon(
        modifier = modifier.rotate(180f),
        color = color,
        iconSize = iconSize,
        strokeWidth = strokeWidth,
    )
}

@Preview(showBackground = true)
@Composable
private fun ChevronRightIconPreview() {
    ChevronRightIcon(iconSize = 28.dp)
}
