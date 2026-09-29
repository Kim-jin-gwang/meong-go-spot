package com.hotdog.meonggocuisine.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage

/**
 * 목록에 올리는 동물 사진 한 칸입니다.
 *
 * 사진이 없을 때와 원본이 지워졌을 때 모두 [MeonggoPhotoPlaceholder] 로 떨어진다. 공공 사진의
 * 약 5% 는 원본이 404 라 빈 칸이 그대로 남으면 목록에 구멍이 뚫린 것처럼 보인다.
 *
 * 모양은 밖에서 받는다. 안에서 제 모서리를 따로 깎으면 자리를 덜 둥글게 바꿔도 사진만 그대로라
 * 네 귀퉁이에 바탕색이 비친다(2026-09-24).
 *
 * 게시물 목록·홈이 쓰던 것을 채팅과 유사도 분석도 함께 쓰도록 옮겨 왔다. 화면마다 따로 그리던
 * 때는 사진이 없는 칸에 "🐾" 글자가 나와서 같은 앱 안에서 빈 사진이 두 가지로 보였다.
 */
@Composable
fun MeonggoPhotoThumbnail(
    imageUrl: String?,
    contentDescription: String = "동물 사진",
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
) {
    Box(
        modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (imageUrl == null) {
            // 실제 동물 사진을 자리표시자로 쓰지 않는다 — 고양이 칸에 개 사진이 나가면 "다른 동물"로 읽힌다.
            MeonggoPhotoPlaceholder(contentDescription = contentDescription)
        } else {
            SubcomposeAsyncImage(
                model = imageUrl,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                error = { MeonggoPhotoPlaceholder(contentDescription = contentDescription) },
            )
        }
    }
}
