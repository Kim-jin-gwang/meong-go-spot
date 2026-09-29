package com.hotdog.meonggocuisine.core.designsystem.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.hotdog.meonggocuisine.R
import com.hotdog.meonggocuisine.core.designsystem.theme.MeonggoBanjeomTheme

/**
 * 사진이 없거나 불러오지 못한 자리에 놓는 그림입니다.
 *
 * 실제 동물 사진은 쓰지 않는다 — 2026-09-14 운영에서 썸네일이 비어 있던 게시물 452건에 개 사진이 나가면서
 * 고양이·햄스터 게시물 카드에도 그 사진이 붙어 "다른 동물" 로 읽혔다. 사진은 "사진 없음" 으로 읽히지 않고
 * 그 동물의 사진으로 읽힌다.
 *
 * 그렇다고 축종마다 그림을 달리 두지도 않는다. 이 그림은 개와 고양이를 둘 다 흐리게 깔고 가운데에 가위표
 * 친 사진 표시를 두어 어느 축종도 주장하지 않는다 — 축종을 갈라야 했던 이유 자체가 사라졌다(2026-09-24).
 * 그 전에는 Canvas 로 개·고양이·발자국을 따로 그렸는데, 조각이 많아 썸네일 크기로 줄면 뭉개졌다.
 *
 * 그림의 바탕색은 `surfaceVariant` 와 같은 값이다. 칸이 정사각형이 아니어도(상세는 가로가 길다) 그림 양옆에
 * 남는 자리와 색이 이어져 한 장으로 보인다.
 */
@Composable
fun MeonggoPhotoPlaceholder(
    modifier: Modifier = Modifier,
    contentDescription: String = "사진 없음",
) {
    Image(
        painter = painterResource(R.drawable.img_photo_placeholder),
        contentDescription = contentDescription,
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
        contentScale = ContentScale.Fit,
    )
}

@Preview(showBackground = true)
@Composable
private fun MeonggoPhotoPlaceholderPreview() {
    MeonggoBanjeomTheme {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MeonggoPhotoPlaceholder(modifier = Modifier.size(96.dp))
            MeonggoPhotoPlaceholder(modifier = Modifier.width(170.dp).height(115.dp))
        }
    }
}
