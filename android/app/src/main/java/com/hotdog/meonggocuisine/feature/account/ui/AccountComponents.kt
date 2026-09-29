package com.hotdog.meonggocuisine.feature.account.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoScreenHeader
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoSurfaces
import com.hotdog.meonggocuisine.core.designsystem.token.MeonggoSpacing

/**
 * 마이페이지 계열 화면의 공통 뼈대 — 뒤로가기 + 제목 머리줄, 스크롤되는 본문.
 *
 * 머리줄은 게시물 상세·등록과 같은 [MeonggoScreenHeader] 다. 예전에는 `‹` 글리프와 왼쪽에
 * 붙인 제목으로 직접 그렸는데, 같은 앱 안에서 마이페이지만 머리줄이 달라 보였다.
 * 좌우 여백도 24dp 에서 [MeonggoSurfaces.gutter] 로 맞춰 다른 화면과 같은 폭에서 시작한다.
 */
@Composable
internal fun AccountScaffold(
    title: String,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { MeonggoScreenHeader(title = title, onBackClick = onBackClick) },
    ) { contentPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        PaddingValues(
                            horizontal = MeonggoSurfaces.gutter,
                            vertical = MeonggoSpacing.medium,
                        ),
                    ),
        ) {
            content()
        }
    }
}

/**
 * 사람 아이콘 — 머리(원)와 어깨(반원). 프로젝트의 다른 아이콘처럼 폰트 글리프 대신 직접 그려 어느 기기에서도 같은 모양이다.
 * 헤더 버튼과 마이페이지 프로필 카드가 함께 쓴다.
 */
@Composable
fun PersonIcon(
    color: Color,
    modifier: Modifier = Modifier,
    iconSize: Dp = 22.dp,
    strokeWidth: Float = 1.8f,
) {
    // 크기는 [iconSize] 로 받는다. modifier 로 넘기면 여기서 다시 덮어써 호출부 값이 먹지 않는다.
    Canvas(modifier.size(iconSize)) {
        val stroke = Stroke(width = strokeWidth.dp.toPx())
        val headRadius = size.width * 0.19f
        drawCircle(
            color = color,
            radius = headRadius,
            center = Offset(size.width / 2f, size.height * 0.30f),
            style = stroke,
        )
        val shoulders =
            Path().apply {
                addArc(
                    oval =
                        Rect(
                            left = size.width * 0.12f,
                            top = size.height * 0.56f,
                            right = size.width * 0.88f,
                            bottom = size.height * 1.32f,
                        ),
                    startAngleDegrees = 180f,
                    sweepAngleDegrees = 180f,
                )
            }
        drawPath(shoulders, color = color, style = stroke)
        drawLine(
            color = color,
            start = Offset(size.width * 0.12f, size.height * 0.94f),
            end = Offset(size.width * 0.88f, size.height * 0.94f),
            strokeWidth = stroke.width,
        )
    }
}
