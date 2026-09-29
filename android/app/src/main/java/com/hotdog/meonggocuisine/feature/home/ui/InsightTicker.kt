package com.hotdog.meonggocuisine.feature.home.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hotdog.meonggocuisine.core.designsystem.token.MeonggoSpacing
import com.hotdog.meonggocuisine.feature.home.data.HomeInsights
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 홈 상단의 한 줄 소식 띠. 카드 5장의 숫자를 한 문장씩 10초마다 바꿔 보여 주고, 누르면 소식 화면(카드 5장 전체)으로 간다.
 *
 * 홈은 스크롤 없는 한 화면(헤더·세 갈래 카드·탭)이라 카드 5장을 그대로 걸 자리가 없다(2026-09-22 결정). 그래서 홈에는
 * "숫자가 있다"는 것만 알리는 띠 하나를 두고, 전체는 소식 화면에 둔다.
 *
 * **되돌리기**: 홈에서 이 띠를 빼려면 [com.hotdog.meonggocuisine.feature.home.ui.HomeScreen] 의 `InsightTicker(...)` 호출 한 곳을
 * 원래의 안내 문구 `Text("어떤 도움이 필요하신가요?")` 로 바꾸면 된다. 소식 화면과 경로는 남겨 두어도 해가 없다.
 */
@Composable
internal fun InsightTicker(
    insights: HomeInsights?,
    regionName: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val messages = remember(insights, regionName) { insightTickerMessages(insights, regionName) }
    var index by remember(messages) { mutableIntStateOf(0) }
    LaunchedEffect(messages) {
        if (messages.size < 2) return@LaunchedEffect
        while (true) {
            delay(INSIGHT_AUTO_ADVANCE_MILLIS)
            index = (index + 1) % messages.size
        }
    }
    Surface(
        modifier = modifier.fillMaxWidth().height(TICKER_HEIGHT).clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "소식",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(MeonggoSpacing.medium))
            Crossfade(targetState = messages[index], modifier = Modifier.weight(1f), label = "insight-ticker") { message ->
                Text(
                    message,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(MeonggoSpacing.small))
            Text("›", color = MaterialTheme.colorScheme.primary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * 띠에 돌려 보일 문장들. 카드 순서(일일 입소 → 마감 임박 → 주간 입소 → 결과 통계 → 실종 신고)를 따르고, 조각이 없거나
 * 알릴 것이 없는 카드는 건너뛴다. 하나도 없으면 "준비 중" 한 문장.
 */
internal fun insightTickerMessages(
    insights: HomeInsights?,
    regionName: String?,
): List<String> {
    if (insights == null) return listOf(PREPARING_MESSAGE)
    val messages = mutableListOf<String>()
    insights.dailyIntake?.let { messages += "${it.animalCount}마리가 새로 보호소에 들어왔어요 · 보호소 ${it.shelterCount}곳" }
    insights.noticeClosing?.let {
        if (regionName != null && it.animalCount > 0) {
            messages += "$regionName 공고 마감 임박 ${it.animalCount}마리 · ${it.withinDays}일 안에 끝나요"
        }
    }
    insights.weeklyIntake?.let {
        if (regionName != null) messages += "$regionName 최근 7일 ${it.total}마리 입소"
    }
    insights.shelterOutcomes?.let {
        messages += "보호소 동물 ${(it.returnRate * 100).roundToInt()}%는 주인에게 돌아갔어요 · 입양 ${(it.adoptionRate * 100).roundToInt()}%"
    }
    insights.lostReports?.let {
        val region = it.regionLast30DaysCount?.takeIf { regionName != null }?.let { count -> " · $regionName 30일 ${count}건" } ?: ""
        messages += "어제 전국 실종 신고 ${it.yesterdayCount}건$region"
    }
    return messages.ifEmpty { listOf(PREPARING_MESSAGE) }
}

internal const val PREPARING_MESSAGE = "보호소 소식을 준비하고 있어요"
private val TICKER_HEIGHT = 52.dp
