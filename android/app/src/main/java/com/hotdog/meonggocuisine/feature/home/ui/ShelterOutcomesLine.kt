package com.hotdog.meonggocuisine.feature.home.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.hotdog.meonggocuisine.feature.home.data.ShelterOutcomes
import kotlin.math.roundToInt

/**
 * 보호소 결과 통계를 한 줄로 — "보호소 동물 12%가 주인에게 돌아갔어요 · 입양 29%".
 *
 * 입양(소개팅) 화면처럼 통계가 주인공이 아닌 곳에서 쓴다. 카드 한 장은 화면을 너무 차지했다(QA 2026-09-22). 숫자만 갈색으로
 * 강조하고 나머지는 보조 글자다.
 */
@Composable
internal fun ShelterOutcomesLine(
    outcomes: ShelterOutcomes,
    modifier: Modifier = Modifier,
) {
    val returned = (outcomes.returnRate * 100).roundToInt()
    val adopted = (outcomes.adoptionRate * 100).roundToInt()
    val emphasis = SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
    Row(modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            buildAnnotatedString {
                append("보호소 동물 ")
                withStyle(emphasis) { append("$returned%") }
                append("가 주인에게 돌아갔어요 · 입양 ")
                withStyle(emphasis) { append("$adopted%") }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
