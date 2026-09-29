package com.hotdog.meonggocuisine.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
 * 몇 개 중 하나를 고르는 버튼입니다.
 *
 * 고른 것은 테두리와 글자를 브랜드 갈색으로 올리고 면을 아주 옅게 깐다. 면만 칠해 가르면 좁은
 * 화면에서 어느 게 켜졌는지 한눈에 안 들어오고, 반대로 면을 갈색으로 꽉 채우면 고른 값이
 * 화면에서 가장 센 요소가 되어 정작 눌러야 할 등록 버튼보다 눈에 띈다.
 *
 * 글자 대비는 고른 쪽 5.7:1, 안 고른 쪽 13.7:1.
 */
@Composable
fun MeonggoChoiceOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(
        modifier = modifier,
        shape = ChoiceShape,
        color =
            if (selected) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surfaceContainerLowest
            },
        border =
            BorderStroke(
                width = if (selected) 1.5.dp else 1.dp,
                color =
                    if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
            ),
        enabled = enabled,
        onClick = onClick,
    ) {
        Box(Modifier.padding(vertical = 13.dp), contentAlignment = Alignment.Center) {
            Text(
                label,
                color =
                    if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp),
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

/**
 * 선택지 한 줄입니다. 너비를 똑같이 나눠 어느 하나가 더 중요해 보이지 않게 한다.
 */
@Composable
fun <T> MeonggoChoiceRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            MeonggoChoiceOption(
                label = label(option),
                selected = option == selected,
                onClick = { onSelect(option) },
                modifier = Modifier.weight(1f),
                enabled = enabled,
            )
        }
    }
}

/**
 * 한 줄에 다 안 들어가는 선택지를 줄로 접어 놓습니다.
 *
 * 남는 칸은 빈자리로 둬서 모든 선택지가 같은 너비를 갖는다. 마지막 줄만 넓게 늘리면 그 둘이 더
 * 중요한 답처럼 보인다.
 *
 * [selected] 가 null 이면 아직 아무것도 안 고른 상태다 — 기본값이 없는 물음에 쓴다.
 */
@Composable
fun <T> MeonggoChoiceGrid(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 3,
    enabled: Boolean = true,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.chunked(columns).forEach { rowOptions ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowOptions.forEach { option ->
                    MeonggoChoiceOption(
                        label = label(option),
                        selected = option == selected,
                        onClick = { onSelect(option) },
                        modifier = Modifier.weight(1f),
                        enabled = enabled,
                    )
                }
                repeat(columns - rowOptions.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private val ChoiceShape = RoundedCornerShape(14.dp)

@Preview(showBackground = true, widthDp = 411)
@Composable
private fun MeonggoChoiceRowPreview() {
    MeonggoBanjeomTheme {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MeonggoChoiceRow(
                options = listOf("강아지", "고양이"),
                selected = "강아지",
                label = { it },
                onSelect = {},
            )
            MeonggoChoiceRow(
                options = listOf("수컷", "암컷", "모름"),
                selected = "모름",
                label = { it },
                onSelect = {},
            )
        }
    }
}
