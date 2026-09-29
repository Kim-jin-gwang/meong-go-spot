package com.hotdog.meonggocuisine.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * 다시 불러오기 — 한 바퀴 도는 화살표입니다.
 *
 * 오른쪽 위를 터 두고 그 빈자리에 화살촉을 둔다. 고리가 닫혀 있으면 어느 쪽으로 도는지 읽히지
 * 않는다. 굵기는 [ChevronLeftIcon] 과 같은 값을 받는다 — 머리줄 양 끝에 나란히 서는 두 그림이라
 * 한쪽만 가늘면 줄이 기울어 보인다.
 *
 * 화살촉은 **고리가 끝나는 점보다 조금 앞**에 찍고 두 획이 뒤로 벌어진다. 획이 고리 끝을
 * [OVERLAP] 만큼만 지나쳐 닿으므로 고리와 이어져 보이면서도 고리 위에 겹쳐 뭉개지지 않는다.
 * 촉을 고리 위에 바로 얹었을 때는 한 획이 고리를 타고 되짚어 가 화살표가 보이지 않았다.
 *
 * 그린 것 전체를 칸 한가운데에 맞춘다. 화살촉이 고리 밖으로 나가 잉크가 위쪽으로 치우치는데,
 * 고리를 칸 가운데에 두면 그림이 위로 뜬 것처럼 보여 옆 제목과 눈높이가 맞지 않는다.
 */
@Composable
fun RefreshIcon(
    color: Color,
    modifier: Modifier = Modifier,
    iconSize: Dp = 26.dp,
    strokeWidth: Dp = 2.6.dp,
) {
    Canvas(modifier.size(iconSize)) {
        val thickness = strokeWidth.toPx()

        // 반지름 1인 자리에서 먼저 잡는다. 칸에 맞추는 일은 아래에서 한 번에 한다.
        val endRadians = Math.toRadians((START_DEGREES + SWEEP_DEGREES).toDouble())
        val arcEnd = Offset(cos(endRadians).toFloat(), sin(endRadians).toFloat())
        val forward = Offset(-sin(endRadians).toFloat(), cos(endRadians).toFloat())
        val spreadRadians = Math.toRadians(BARB_SPREAD_DEGREES.toDouble())
        val tip = arcEnd + forward * (BARB_RATIO * (cos(spreadRadians).toFloat() - OVERLAP))
        val barbEnds =
            listOf(BARB_SPREAD_DEGREES, -BARB_SPREAD_DEGREES).map { spread ->
                val radians = Math.toRadians(spread.toDouble())
                val c = cos(radians).toFloat()
                val s = sin(radians).toFloat()
                tip +
                    Offset(
                        -forward.x * c + forward.y * s,
                        -forward.x * s - forward.y * c,
                    ) * BARB_RATIO
            }

        // 잉크가 차지하는 넓이 — 고리는 반지름 1 안에 있고, 촉만 그 밖으로 나간다.
        val marks = barbEnds + tip
        val left = minOf(-1f, marks.minOf { it.x })
        val right = maxOf(1f, marks.maxOf { it.x })
        val top = minOf(-1f, marks.minOf { it.y })
        val bottom = maxOf(1f, marks.maxOf { it.y })
        val scale = (size.minDimension * INK_RATIO - thickness) / max(right - left, bottom - top)
        val origin =
            Offset(
                size.width / 2f - (left + right) / 2f * scale,
                size.height / 2f - (top + bottom) / 2f * scale,
            )

        fun place(point: Offset) = origin + point * scale

        drawArc(
            color = color,
            startAngle = START_DEGREES,
            sweepAngle = SWEEP_DEGREES,
            useCenter = false,
            topLeft = origin - Offset(scale, scale),
            size = Size(scale * 2f, scale * 2f),
            style = Stroke(width = thickness, cap = StrokeCap.Round),
        )
        barbEnds.forEach { end ->
            drawLine(
                color = color,
                start = place(tip),
                end = place(end),
                strokeWidth = thickness,
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * 그림이 칸에서 차지하는 비율.
 *
 * 칸을 꽉 채우면 옆에 선 뒤로 가기 꺾쇠보다 덩치가 커 보인다 — 꺾쇠는 제 칸의 세로 60% 만
 * 쓰는데 이 고리는 칸에 내접하기 때문이다. 둘이 같은 크기로 보이는 값이다.
 */
private const val INK_RATIO = 0.70f

/** 고리가 시작하는 자리. 0도는 3시 방향이고 시계 방향이 양수다. */
private const val START_DEGREES = 25f

/** 고리가 도는 각도. 나머지 85도가 화살촉이 들어설 빈자리다. */
private const val SWEEP_DEGREES = 275f

/** 두 획이 진행 방향의 반대에서 좌우로 벌어지는 각도. */
private const val BARB_SPREAD_DEGREES = 50f

/** 화살촉 두 획의 길이 — 고리 반지름에 견준 비율. */
private const val BARB_RATIO = 0.66f

/** 두 획이 고리 끝을 지나쳐 닿는 깊이. 0이면 촉이 고리에서 떨어져 보인다. */
private const val OVERLAP = 0.18f

@Preview(showBackground = true)
@Composable
private fun RefreshIconPreview() {
    RefreshIcon(color = Color(0xFF2B2320), iconSize = 52.dp, strokeWidth = 5.2.dp)
}
