package com.hotdog.meonggocuisine.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.hotdog.meonggocuisine.core.designsystem.theme.MeonggoBanjeomTheme

/**
 * 위치 핀입니다. 게시물 목록·홈이 쓰던 것을 유사도 분석도 함께 쓰도록 옮겨 왔다.
 *
 * 크기는 [Modifier] 로 받는다 — 같은 핀을 목록에서는 20dp, 홈 미리보기에서는 12dp 로 쓴다.
 */
@Composable
fun LocationPinIcon(
    modifier: Modifier = Modifier,
    // 핀 가운데 구멍은 뒤 면이 비쳐 보이는 것처럼 그린다. 올려 둘 면이 다르면 그 색을 넘긴다.
    holeColor: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    val color = MaterialTheme.colorScheme.primary
    val centerColor = holeColor
    Canvas(modifier.size(width = 22.dp, height = 24.dp)) {
        val pin =
            Path().apply {
                moveTo(size.width / 2f, size.height)
                cubicTo(
                    size.width * 0.42f,
                    size.height * 0.82f,
                    size.width * 0.16f,
                    size.height * 0.57f,
                    size.width * 0.16f,
                    size.height * 0.39f,
                )
                cubicTo(
                    size.width * 0.16f,
                    size.height * 0.16f,
                    size.width * 0.31f,
                    0f,
                    size.width / 2f,
                    0f,
                )
                cubicTo(
                    size.width * 0.69f,
                    0f,
                    size.width * 0.84f,
                    size.height * 0.16f,
                    size.width * 0.84f,
                    size.height * 0.39f,
                )
                cubicTo(
                    size.width * 0.84f,
                    size.height * 0.57f,
                    size.width * 0.58f,
                    size.height * 0.82f,
                    size.width / 2f,
                    size.height,
                )
                close()
            }
        drawPath(path = pin, color = color)
        drawCircle(
            color = centerColor,
            radius = size.width * 0.12f,
            center = Offset(size.width / 2f, size.height * 0.36f),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun LocationPinIconPreview() {
    MeonggoBanjeomTheme {
        LocationPinIcon(Modifier.size(width = 44.dp, height = 48.dp))
    }
}
