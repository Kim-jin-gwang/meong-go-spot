package com.hotdog.meonggocuisine.core.designsystem.component

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hotdog.meonggocuisine.core.designsystem.token.MeonggoCardColors

/**
 * 바탕 위에 떠 있는 면의 공통 값입니다.
 *
 * 목록 카드에서 잡은 모양(둥글기 22dp·아주 얇은 갈색 그림자)을 등록 화면과 시트도 그대로 쓴다.
 * 화면마다 따로 적으면 한쪽만 고쳐질 때 같은 앱 안에서 카드 모양이 갈린다 — 목록 두 화면이
 * 실제로 그렇게 어긋났었다.
 */
object MeonggoSurfaces {
    /** 떠 있는 면의 둥글기. */
    val cardShape: Shape = RoundedCornerShape(22.dp)

    /** 떠 있는 면의 그림자 두께. 경계만 보이면 되므로 아주 얇다. */
    val cardElevation: Dp = 1.dp

    /** 화면 좌우 여백. 목록·등록이 같은 폭으로 시작해야 페이지를 옮겨도 자리가 안 흔들린다. */
    val gutter: Dp = 16.dp
}

/**
 * 따뜻한 갈색 그림자입니다.
 *
 * `Surface(shadowElevation)` 은 그림자 색을 못 바꿔 늘 검정으로 그린다. 따뜻한 흰 바탕 위에
 * 검정을 깔면 카드 아래가 회색 띠로 보여서, 색을 지정할 수 있는 [shadow] 를 쓴다.
 * API 28 아래에서는 색 지정이 무시되고 검정으로 그려진다.
 *
 * 경계만 드러내는 용도다. 정말 떠 있어야 하는 면은 elevation 을 키워도 진해지지 않는다 —
 * 플랫폼 그림자는 진하기 상한이 낮아서 넓게 옅어질 뿐이다(소개팅 카드에서 확인, 2026-09-24).
 */
fun Modifier.meonggoFloatingShadow(
    shape: Shape = MeonggoSurfaces.cardShape,
    elevation: Dp = MeonggoSurfaces.cardElevation,
): Modifier =
    shadow(
        elevation = elevation,
        shape = shape,
        ambientColor = MeonggoCardColors.floatingShadow,
        spotColor = MeonggoCardColors.floatingShadow,
    )

/**
 * 점선 테두리입니다.
 *
 * 아직 비어 있어 채워 넣을 자리라는 뜻으로 쓴다. 실선은 이미 값이 든 칸(입력칸·선택지)에 쓰고
 * 있어서, 같은 실선으로 그리면 빈 자리와 채운 자리가 같아 보인다.
 */
fun Modifier.meonggoDashedBorder(
    color: Color,
    cornerRadius: Dp,
    strokeWidth: Dp = 1.dp,
    dash: Dp = 5.dp,
    gap: Dp = 4.dp,
): Modifier =
    drawBehind {
        val width = strokeWidth.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(width / 2f, width / 2f),
            size = Size(size.width - width, size.height - width),
            cornerRadius = CornerRadius(cornerRadius.toPx()),
            style =
                Stroke(
                    width = width,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash.toPx(), gap.toPx())),
                ),
        )
    }
