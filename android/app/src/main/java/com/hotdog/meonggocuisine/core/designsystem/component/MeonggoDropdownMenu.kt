package com.hotdog.meonggocuisine.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hotdog.meonggocuisine.core.designsystem.token.MeonggoRadius

/**
 * 앱 전체가 같이 쓰는 선택 목록(드롭다운).
 *
 * Material 기본 `DropdownMenu` 는 회보라색 면·큼직한 항목이라 우리 화면(흰 면·갈색 강조·둥근 모서리)과 어긋났다(QA 2026-09-21).
 * 여기서 모양을 한 번만 정한다: 흰 면, 옅은 테두리, 12dp 모서리, 항목 사이 가는 구분선, 현재 선택 항목은 갈색 굵은 글자 + 체크.
 * 목록이 길면(시·군·구) 320dp 에서 스크롤한다.
 *
 * 열고 닫는 상태와 앵커(버튼)는 호출하는 쪽이 가진다 — 앵커 모양은 화면마다 다를 수 있다.
 */
@Composable
fun <T> MeonggoDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    options: List<T>,
    selected: T?,
    optionLabel: (T) -> String,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.widthIn(min = 168.dp).heightIn(max = 320.dp),
        shape = RoundedCornerShape(MeonggoRadius.medium),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 6.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = option == selected
            DropdownMenuItem(
                text = {
                    Text(
                        text = optionLabel(option),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                },
                trailingIcon = if (isSelected) ({ CheckMark() }) else null,
                onClick = { onSelected(option) },
                modifier =
                    Modifier.background(
                        if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
            )
            if (index < options.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                )
            }
        }
    }
}

/** 드롭다운 앵커 버튼 오른쪽에 두는 아래 화살표. 두 지역 선택 화면이 같은 모양을 쓴다. */
@Composable
fun DropdownChevron(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier.size(18.dp)) {
        val strokeWidth = 1.8.dp.toPx()
        drawLine(
            color = color,
            start = Offset(size.width * 0.28f, size.height * 0.42f),
            end = Offset(size.width * 0.5f, size.height * 0.62f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.5f, size.height * 0.62f),
            end = Offset(size.width * 0.72f, size.height * 0.42f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun CheckMark(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier.size(16.dp)) {
        val strokeWidth = 2.dp.toPx()
        drawLine(
            color = color,
            start = Offset(size.width * 0.2f, size.height * 0.55f),
            end = Offset(size.width * 0.42f, size.height * 0.76f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.42f, size.height * 0.76f),
            end = Offset(size.width * 0.82f, size.height * 0.3f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}
