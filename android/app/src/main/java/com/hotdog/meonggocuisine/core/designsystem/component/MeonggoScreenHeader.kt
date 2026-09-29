package com.hotdog.meonggocuisine.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hotdog.meonggocuisine.core.designsystem.theme.MeonggoBanjeomTheme

/**
 * 뒤로 가기와 화면 이름만 있는 머리줄입니다. 탭 바 없이 들어갔다 나오는 화면들이 함께 씁니다.
 *
 * 되돌아가기는 단추 면 없이 꺾쇠만 둔다. 화면에서 유일한 제목 줄이라 면까지 깔면 눌러야 할 것이
 * 두 개로 보인다. 대신 꺾쇠를 제목 크기에 맞춰 키우고, 손가락이 닿는 넓이는 그대로 남긴다.
 *
 * [actions] 는 제목 오른쪽에 두는 화면별 동작이다. 제목은 [actions] 가 무엇이든 줄 한가운데에
 * 그대로 있다 — 화면을 옮길 때마다 제목이 좌우로 흔들리지 않아야 한다.
 */
@Composable
fun MeonggoScreenHeader(
    title: String,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Box(
        modifier = modifier.fillMaxWidth().height(HeaderHeight),
        contentAlignment = Alignment.Center,
    ) {
        MeonggoBackButton(
            onClick = onBackClick,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = MeonggoSurfaces.gutter - TouchInset),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = MeonggoSurfaces.gutter - TouchInset),
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
    }
}

private val HeaderHeight = 62.dp
private val TouchInset = 8.dp

@Preview(showBackground = true, widthDp = 411, backgroundColor = 0xFFFDFBF7)
@Composable
private fun MeonggoScreenHeaderPreview() {
    MeonggoBanjeomTheme {
        MeonggoScreenHeader(title = "게시물 상세", onBackClick = {})
    }
}
