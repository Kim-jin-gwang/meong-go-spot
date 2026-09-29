package com.hotdog.meonggocuisine.feature.home.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hotdog.meonggocuisine.core.designsystem.theme.MeonggoBanjeomTheme
import com.hotdog.meonggocuisine.core.designsystem.token.MeonggoSpacing
import com.hotdog.meonggocuisine.feature.home.data.DailyIntakeCount
import com.hotdog.meonggocuisine.feature.home.data.DailySummary
import com.hotdog.meonggocuisine.feature.home.data.HomeInsights
import com.hotdog.meonggocuisine.feature.home.data.LostReports
import com.hotdog.meonggocuisine.feature.home.data.NoticeClosing
import com.hotdog.meonggocuisine.feature.home.data.ShelterOutcomes
import com.hotdog.meonggocuisine.feature.home.data.WeeklyIntake
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

internal const val INSIGHT_PAGE_COUNT = 5
internal const val INSIGHT_AUTO_ADVANCE_MILLIS = 10_000L

/**
 * 가상 페이지 수 — 마지막 장에서 더 밀면 첫 장으로 이어지게(원형) 하려고 실제 5장을 아주 긴 목록 위에 반복한다.
 * 가운데에서 시작하므로 양쪽으로 수천 번 밀어도 끝에 닿지 않는다. 실제 카드는 `page % INSIGHT_PAGE_COUNT`.
 */
private const val VIRTUAL_PAGE_COUNT = 5_000
private const val INITIAL_VIRTUAL_PAGE = VIRTUAL_PAGE_COUNT / 2 - (VIRTUAL_PAGE_COUNT / 2) % INSIGHT_PAGE_COUNT

/**
 * 홈 상단 인사이트 카드 5장 — 가로로 밀어 넘기고, 가만히 두면 10초마다 다음 장으로 간다 (D3).
 *
 * 자동 전환은 "머문 페이지"가 바뀔 때마다 타이머를 다시 시작한다. 그래서 사용자가 손으로 넘겨도 그 장에서 다시
 * 10초를 기다리고, 손가락이 닿아 있는 동안(드래그 중)에는 넘기지 않는다. 마지막 장 다음은 첫 장이다 — 자동 전환도,
 * 손으로 미는 것도 같은 방향으로 계속 이어진다.
 */
@Composable
internal fun InsightPager(
    uiState: HomeUiState,
    onRegionChangeClick: () -> Unit,
    onShelteringListClick: () -> Unit,
    onLostListClick: () -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = INITIAL_VIRTUAL_PAGE) { VIRTUAL_PAGE_COUNT }
    val isDragged by pagerState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(pagerState.settledPage, isDragged) {
        if (isDragged) return@LaunchedEffect
        delay(INSIGHT_AUTO_ADVANCE_MILLIS)
        pagerState.animateScrollToPage(pagerState.settledPage + 1)
    }
    val insights = uiState.insights
    Column {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth(),
            pageSpacing = MeonggoSpacing.medium,
            beyondViewportPageCount = 1,
        ) { virtualPage ->
            when (virtualPage % INSIGHT_PAGE_COUNT) {
                0 -> DailyIntakeCard(insights?.dailyIntake)
                1 -> NoticeClosingCard(insights?.noticeClosing, uiState.regionName, onRegionChangeClick, onShelteringListClick)
                2 -> WeeklyIntakeCard(insights?.weeklyIntake, uiState.regionName, onRegionChangeClick)
                3 -> ShelterOutcomesCard(insights?.shelterOutcomes)
                else -> LostReportsCard(insights?.lostReports, uiState.regionName, onLostListClick)
            }
        }
        Spacer(Modifier.height(MeonggoSpacing.small))
        PageDots(count = INSIGHT_PAGE_COUNT, selected = pagerState.currentPage % INSIGHT_PAGE_COUNT)
    }
}

@Composable
private fun PageDots(
    count: Int,
    selected: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val active = index == selected
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .size(width = if (active) 16.dp else 6.dp, height = 6.dp)
                    .clip(CircleShape)
                    .background(
                        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    ),
            )
        }
    }
}

/** 카드 공통 틀 — 기존 일일 요약 카드의 색·모서리·여백을 그대로 쓰고 높이를 고정해 다섯 장이 같은 크기다. */
@Composable
private fun InsightCard(
    label: String,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(INSIGHT_CARD_HEIGHT)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Text(
                label,
                color = MaterialTheme.colorScheme.secondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(MeonggoSpacing.small))
            content()
        }
    }
}

@Composable
private fun Headline(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun Caption(
    text: String,
    highlight: Boolean = false,
) {
    Spacer(Modifier.height(MeonggoSpacing.extraSmall))
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = if (highlight) FontWeight.SemiBold else null,
        fontSize = 12.sp,
    )
}

/**
 * 1. 일일 입소 요약 — 대상 실행이 없으면 서버가 null 을 주므로(D2) 숫자 대신 준비 중 문구.
 * FCM 요약 푸시를 받고 들어온 사용자가 같은 자리를 찾기 때문에 카드를 숨기지 않는다.
 */
@Composable
private fun DailyIntakeCard(summary: DailySummary?) {
    InsightCard("오늘의 보호소") {
        if (summary == null) {
            Headline("오늘 들어온 소식을\n준비하고 있어요")
            Caption("매일 밤 전국 보호소 데이터를 받아 옵니다")
        } else {
            Headline("${summary.animalCount}마리가 새로\n보호소에 들어왔어요")
            Caption("${summary.summaryDate} 기준 · 보호소 ${summary.shelterCount}곳")
        }
    }
}

/** 2. 내 지역 공고 마감 임박 — 공고가 끝나면 소유권이 지자체로 넘어가므로 "지금 확인"의 이유가 된다. */
@Composable
private fun NoticeClosingCard(
    notice: NoticeClosing?,
    regionName: String?,
    onRegionChangeClick: () -> Unit,
    onShelteringListClick: () -> Unit,
) {
    when {
        regionName == null ->
            InsightCard("내 지역 공고 마감 임박", onClick = onRegionChangeClick) {
                Headline("지역을 선택하면\n마감 임박 동물을 알려 드려요")
                Caption("지역 선택하기 ›", highlight = true)
            }
        // 지역은 있는데 서버 조각이 없다 — D3 미배포·호출 실패. "지역을 고르라"고 하면 거짓 안내다.
        notice == null ->
            InsightCard("내 지역 공고 마감 임박") {
                Headline("마감 임박 소식을\n준비하고 있어요")
                Caption("$regionName · 공고 종료 3일 전 동물을 알려 드립니다")
            }
        notice.animalCount == 0 ->
            InsightCard("내 지역 공고 마감 임박", onClick = onShelteringListClick) {
                Headline("${notice.withinDays}일 안에 공고가 끝나는\n동물이 없어요")
                Caption("$regionName · 보호 중 동물 보기 ›", highlight = true)
            }
        else ->
            InsightCard("내 지역 공고 마감 임박", onClick = onShelteringListClick) {
                Headline("${notice.animalCount}마리의 공고가\n${notice.withinDays}일 안에 끝나요")
                Caption("$regionName · 지금 확인하기 ›", highlight = true)
            }
    }
}

/** 3. 내 지역 최근 7일 입소 — 막대 7개(발견일 기준 개+고양이)와 합계. */
@Composable
private fun WeeklyIntakeCard(
    weekly: WeeklyIntake?,
    regionName: String?,
    onRegionChangeClick: () -> Unit,
) {
    if (regionName == null) {
        InsightCard("내 지역 이번 주 입소", onClick = onRegionChangeClick) {
            Headline("지역을 선택하면\n이번 주 입소 추이를 보여 드려요")
            Caption("지역 선택하기 ›", highlight = true)
        }
        return
    }
    if (weekly == null) {
        InsightCard("내 지역 이번 주 입소") {
            Headline("이번 주 입소 추이를\n준비하고 있어요")
            Caption("$regionName · 최근 7일 발견 동물을 세어 봅니다")
        }
        return
    }
    val dogs = weekly.days.sumOf { it.dogCount }
    val cats = weekly.days.sumOf { it.catCount }
    InsightCard("내 지역 이번 주 입소") {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Headline("최근 7일 ${weekly.total}마리")
                Caption("$regionName · 개 $dogs · 고양이 $cats")
            }
            WeeklyBars(weekly.days)
        }
    }
}

@Composable
private fun WeeklyBars(days: List<DailyIntakeCount>) {
    val max = days.maxOfOrNull { it.dogCount + it.catCount }?.coerceAtLeast(1) ?: 1
    Row(
        modifier = Modifier.height(WEEKLY_BAR_MAX_HEIGHT),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        days.forEach { day ->
            val total = day.dogCount + day.catCount
            val height = if (total == 0) 2.dp else (WEEKLY_BAR_MAX_HEIGHT * total / max).coerceAtLeast(4.dp)
            Box(
                Modifier
                    .width(10.dp)
                    .height(height)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        if (total == 0) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                    ),
            )
        }
    }
}

/**
 * 4. 보호소 결과 통계 — 최근 3년 종결 건 기준. 서비스 존재 이유(빨리 찍어 보면 돌아갈 확률이 오른다)를 데이터로 말한다.
 * 안락사율은 payload 에 있어도 카드에 쓰지 않는다 (2026-09-14 결정).
 */
@Composable
private fun ShelterOutcomesCard(outcomes: ShelterOutcomes?) {
    InsightCard("보호소에 들어온 동물은") {
        if (outcomes == null) {
            Headline("통계를 준비하고 있어요")
            Caption("최근 3년 전국 보호소 기록을 집계합니다")
        } else {
            Headline("${percent(outcomes.returnRate)}%가 주인에게\n돌아갔어요")
            val notice = outcomes.averageNoticeDays?.let { " · 공고 평균 ${it.roundToInt()}일" } ?: ""
            Caption("입양 ${percent(outcomes.adoptionRate)}%$notice · 최근 3년 ${formatCount(outcomes.closedCount)}건")
        }
    }
}

/** 5. 실종 신고 현황 — 어제 전국 신규 신고와 내 지역 최근 30일. */
@Composable
private fun LostReportsCard(
    lost: LostReports?,
    regionName: String?,
    onLostListClick: () -> Unit,
) {
    InsightCard("실종 신고", onClick = onLostListClick) {
        if (lost == null) {
            Headline("실종 신고 소식을\n준비하고 있어요")
            Caption("동물보호관리시스템 신고를 매일 받아 옵니다")
        } else {
            Headline("어제 전국에서 ${lost.yesterdayCount}건의\n실종 신고가 들어왔어요")
            val regionCount = lost.regionLast30DaysCount
            Caption(
                if (regionName != null && regionCount != null) {
                    "$regionName 최근 30일 ${regionCount}건 · 개 ${lost.regionDogCount ?: 0} 고양이 ${lost.regionCatCount ?: 0} ›"
                } else {
                    "잃어버렸어요 보기 ›"
                },
                highlight = true,
            )
        }
    }
}

private fun percent(rate: Double): Int = (rate * 100).roundToInt()

private fun formatCount(value: Long): String = if (value >= 10_000) "${value / 10_000}만" else "%,d".format(value)

private val INSIGHT_CARD_HEIGHT = 164.dp
private val WEEKLY_BAR_MAX_HEIGHT = 44.dp

@Preview(showBackground = true, widthDp = 390)
@Composable
private fun InsightPagerPreview() {
    MeonggoBanjeomTheme {
        Box(Modifier.fillMaxSize().padding(20.dp)) {
            InsightPager(
                uiState =
                    HomeUiState(
                        isLoading = false,
                        regionName = "경기도 안양시",
                        insights =
                            HomeInsights(
                                dailyIntake = DailySummary(1, "2026-09-14", 248, 99, "2026-09-14T14:12:00Z"),
                                noticeClosing = NoticeClosing("41170", 3, 4),
                                weeklyIntake =
                                    WeeklyIntake(
                                        "41170",
                                        "2026-09-09",
                                        "2026-09-15",
                                        12,
                                        listOf(1, 2, 0, 3, 2, 1, 3).mapIndexed { i, n -> DailyIntakeCount("2026-09-${9 + i}", n, 0) },
                                    ),
                                shelterOutcomes =
                                    ShelterOutcomes(
                                        "2023-07-17",
                                        "2026-07-17",
                                        286134,
                                        0.1209,
                                        0.2921,
                                        10.4,
                                        "2026-09-15T04:59:29Z",
                                    ),
                                lostReports = LostReports("2026-09-14", 136, "41170", 3, 2, 1),
                            ),
                    ),
                onRegionChangeClick = {},
                onShelteringListClick = {},
                onLostListClick = {},
            )
        }
    }
}
