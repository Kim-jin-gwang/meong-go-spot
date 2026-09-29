package com.hotdog.meonggocuisine.feature.home.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoBackButton
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoLoadingIndicator
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoTopBar
import com.hotdog.meonggocuisine.core.designsystem.theme.MeonggoBanjeomTheme
import com.hotdog.meonggocuisine.core.designsystem.token.MeonggoCardColors
import com.hotdog.meonggocuisine.core.designsystem.token.MeonggoSpacing
import com.hotdog.meonggocuisine.feature.community.ui.CurrentRegion
import com.hotdog.meonggocuisine.feature.community.ui.floatingCardShadow
import com.hotdog.meonggocuisine.feature.home.data.DailyIntakeCount
import com.hotdog.meonggocuisine.feature.home.data.DailySummary
import com.hotdog.meonggocuisine.feature.home.data.HomeInsights
import com.hotdog.meonggocuisine.feature.home.data.LostReports
import com.hotdog.meonggocuisine.feature.home.data.NoticeClosing
import com.hotdog.meonggocuisine.feature.home.data.ShelterOutcomes
import com.hotdog.meonggocuisine.feature.home.data.WeeklyIntake
import kotlin.math.roundToInt

/**
 * 소식 화면 — 홈의 한 줄 띠를 눌러 들어온다.
 *
 * 처음엔 홈 카드 5장을 그대로 세로로 쌓았는데 같은 베이지 카드가 다섯 번 반복되어 읽을 곳이 없었다(QA 2026-09-22 "짜친다").
 * 지금은 숫자 하나가 주인공인 구조다: 오늘 입소 수를 크게 한 장, 나머지 넷은 숫자 타일 2×2, 결과 통계는 비율 막대 한 줄.
 * 지역 표시는 목록 화면과 같은 [CurrentRegion] 을 쓴다. 데이터는 그대로 D3 한 번이다.
 */
@Composable
fun InsightsRouteScreen(
    onBackClick: () -> Unit,
    onRegionChangeClick: () -> Unit,
    onShelteringListClick: () -> Unit,
    onLostListClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: InsightsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    InsightsScreen(
        uiState = uiState,
        onBackClick = onBackClick,
        onRegionChangeClick = onRegionChangeClick,
        onShelteringListClick = onShelteringListClick,
        onLostListClick = onLostListClick,
        modifier = modifier,
    )
}

@Composable
fun InsightsScreen(
    uiState: InsightsUiState,
    onBackClick: () -> Unit,
    onRegionChangeClick: () -> Unit,
    onShelteringListClick: () -> Unit,
    onLostListClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            MeonggoTopBar(
                title = "보호소 소식",
                navigationIcon = {
                    MeonggoBackButton(onBackClick)
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .verticalScroll(rememberScrollState()),
        ) {
            CurrentRegion(regionName = uiState.regionName ?: "전국", onRegionChangeClick = onRegionChangeClick)
            if (uiState.isLoading && uiState.insights == null) {
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp), contentAlignment = Alignment.Center) {
                    MeonggoLoadingIndicator(contentDescription = "보호소 소식을 불러오는 중")
                }
            } else {
                InsightsDashboard(
                    insights = uiState.insights,
                    regionName = uiState.regionName,
                    onRegionChangeClick = onRegionChangeClick,
                    onShelteringListClick = onShelteringListClick,
                    onLostListClick = onLostListClick,
                )
            }
        }
    }
}

@Composable
private fun InsightsDashboard(
    insights: HomeInsights?,
    regionName: String?,
    onRegionChangeClick: () -> Unit,
    onShelteringListClick: () -> Unit,
    onLostListClick: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = GUTTER),
        verticalArrangement = Arrangement.spacedBy(MeonggoSpacing.medium),
    ) {
        Spacer(Modifier.height(MeonggoSpacing.extraSmall))
        HeroCard(insights?.dailyIntake)
        Row(horizontalArrangement = Arrangement.spacedBy(MeonggoSpacing.medium)) {
            NoticeClosingTile(insights?.noticeClosing, regionName, onRegionChangeClick, onShelteringListClick, Modifier.weight(1f))
            WeeklyIntakeTile(insights?.weeklyIntake, regionName, onRegionChangeClick, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MeonggoSpacing.medium)) {
            LostReportsTile(insights?.lostReports, regionName, onLostListClick, Modifier.weight(1f))
            ReturnRateTile(insights?.shelterOutcomes, Modifier.weight(1f))
        }
        OutcomesBarCard(insights?.shelterOutcomes)
        Text(
            "매일 밤 전국 보호소·실종 신고 데이터를 받아 와 집계해요. 결과 통계는 매주 월요일에 갱신돼요.",
            modifier = Modifier.padding(vertical = MeonggoSpacing.small),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(MeonggoSpacing.large))
    }
}

/** 오늘 입소 수 — 이 화면의 주인공. 크림색 면에 숫자를 크게. */
@Composable
private fun HeroCard(summary: DailySummary?) {
    Surface(
        modifier = Modifier.fillMaxWidth().floatingCardShadow(CARD_SHAPE),
        shape = CARD_SHAPE,
        color = MeonggoCardColors.adoptionSurface,
    ) {
        Column(Modifier.padding(horizontal = 22.dp, vertical = 20.dp)) {
            TileLabel("오늘 새로 들어온 아이")
            Spacer(Modifier.height(MeonggoSpacing.small))
            if (summary == null) {
                Text("소식을 준비하고 있어요", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                TileCaption("매일 밤 전국 보호소 데이터를 받아 옵니다")
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        formatCount(summary.animalCount.toLong()),
                        fontSize = 44.sp,
                        lineHeight = 48.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "마리",
                        modifier = Modifier.padding(start = 6.dp, bottom = 7.dp),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                TileCaption("${summary.summaryDate} · 전국 보호소 ${summary.shelterCount}곳")
            }
        }
    }
}

@Composable
private fun NoticeClosingTile(
    notice: NoticeClosing?,
    regionName: String?,
    onRegionChangeClick: () -> Unit,
    onShelteringListClick: () -> Unit,
    modifier: Modifier,
) {
    when {
        regionName == null -> StatTile("공고 마감 임박", null, "", "지역을 고르면 보여 드려요", modifier, onClick = onRegionChangeClick)
        notice == null -> StatTile("공고 마감 임박", null, "", "준비하고 있어요", modifier)
        else ->
            StatTile(
                label = "공고 마감 임박",
                value = formatCount(notice.animalCount.toLong()),
                unit = "마리",
                caption = if (notice.animalCount == 0) "${notice.withinDays}일 안에 끝나는 공고가 없어요" else "${notice.withinDays}일 안에 끝나요 · 보기 ›",
                modifier = modifier,
                highlight = notice.animalCount > 0,
                onClick = onShelteringListClick,
            )
    }
}

@Composable
private fun WeeklyIntakeTile(
    weekly: WeeklyIntake?,
    regionName: String?,
    onRegionChangeClick: () -> Unit,
    modifier: Modifier,
) {
    when {
        regionName == null -> StatTile("이번 주 입소", null, "", "지역을 고르면 보여 드려요", modifier, onClick = onRegionChangeClick)
        weekly == null -> StatTile("이번 주 입소", null, "", "준비하고 있어요", modifier)
        else -> {
            val dogs = weekly.days.sumOf { it.dogCount }
            val cats = weekly.days.sumOf { it.catCount }
            StatTile("이번 주 입소", formatCount(weekly.total.toLong()), "마리", "개 $dogs · 고양이 $cats", modifier)
        }
    }
}

@Composable
private fun LostReportsTile(
    lost: LostReports?,
    regionName: String?,
    onLostListClick: () -> Unit,
    modifier: Modifier,
) {
    if (lost == null) {
        StatTile("어제 실종 신고", null, "", "준비하고 있어요", modifier)
        return
    }
    val regionCount = lost.regionLast30DaysCount
    StatTile(
        label = "어제 실종 신고",
        value = formatCount(lost.yesterdayCount.toLong()),
        unit = "건",
        caption =
            if (regionName != null && regionCount != null) {
                "$regionName 30일 ${regionCount}건 · 보기 ›"
            } else {
                "전국 · 잃어버렸어요 보기 ›"
            },
        modifier = modifier,
        highlight = true,
        onClick = onLostListClick,
    )
}

@Composable
private fun ReturnRateTile(
    outcomes: ShelterOutcomes?,
    modifier: Modifier,
) {
    if (outcomes == null) {
        StatTile("주인 품으로", null, "", "준비하고 있어요", modifier)
        return
    }
    StatTile("주인 품으로", "${percent(outcomes.returnRate)}", "%", "입양 ${percent(outcomes.adoptionRate)}% · 최근 3년", modifier)
}

/** 숫자 타일 한 장 — 라벨, 큰 숫자 + 단위, 한 줄 설명. [value] 가 null 이면 준비 중. */
@Composable
private fun StatTile(
    label: String,
    value: String?,
    unit: String,
    caption: String,
    modifier: Modifier,
    highlight: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        modifier =
            modifier
                .height(TILE_HEIGHT)
                .floatingCardShadow(CARD_SHAPE)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = CARD_SHAPE,
        color = MeonggoCardColors.shelteringSurface,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            TileLabel(label)
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.Bottom) {
                if (value == null) {
                    Text("–", fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.outline)
                } else {
                    Text(
                        value,
                        fontSize = 28.sp,
                        lineHeight = 32.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        unit,
                        modifier = Modifier.padding(start = 3.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            TileCaption(caption, highlight = highlight)
        }
    }
}

/** 결과 통계 — 반환·입양·그 밖의 비율을 막대 한 줄로. 숫자만 나열하는 것보다 "대부분이 돌아가지 못한다" 가 한눈에 보인다. */
@Composable
private fun OutcomesBarCard(outcomes: ShelterOutcomes?) {
    Surface(
        modifier = Modifier.fillMaxWidth().floatingCardShadow(CARD_SHAPE),
        shape = CARD_SHAPE,
        color = MeonggoCardColors.shelteringSurface,
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            TileLabel("보호소에 들어온 동물은 어떻게 됐을까요")
            Spacer(Modifier.height(MeonggoSpacing.medium))
            if (outcomes == null) {
                TileCaption("최근 3년 전국 보호소 기록을 집계하고 있어요")
                return@Column
            }
            val returned = outcomes.returnRate.toFloat().coerceIn(0f, 1f)
            val adopted = outcomes.adoptionRate.toFloat().coerceIn(0f, 1f - returned)
            val other = (1f - returned - adopted).coerceAtLeast(0f)
            Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))) {
                Box(Modifier.weight(returned.coerceAtLeast(0.01f)).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                Box(Modifier.weight(adopted.coerceAtLeast(0.01f)).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
                Box(Modifier.weight(other.coerceAtLeast(0.01f)).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant))
            }
            Spacer(Modifier.height(MeonggoSpacing.medium))
            Row(horizontalArrangement = Arrangement.spacedBy(MeonggoSpacing.large)) {
                Legend(MaterialTheme.colorScheme.primary, "주인에게 돌아감 ${percent(outcomes.returnRate)}%")
                Legend(MaterialTheme.colorScheme.outlineVariant, "새 가족 ${percent(outcomes.adoptionRate)}%")
            }
            Spacer(Modifier.height(MeonggoSpacing.small))
            val notice = outcomes.averageNoticeDays?.let { " · 공고 평균 ${it.roundToInt()}일" } ?: ""
            TileCaption("최근 3년 종결 ${formatCount(outcomes.closedCount)}건$notice")
        }
    }
}

@Composable
private fun Legend(
    color: Color,
    text: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun TileLabel(text: String) {
    Text(text, color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun TileCaption(
    text: String,
    highlight: Boolean = false,
) {
    Spacer(Modifier.height(MeonggoSpacing.extraSmall))
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = if (highlight) FontWeight.SemiBold else null,
        maxLines = 1,
    )
}

private fun percent(rate: Double): Int = (rate * 100).roundToInt()

private fun formatCount(value: Long): String = if (value >= 10_000) "${value / 10_000}만" else "%,d".format(value)

private val CARD_SHAPE = RoundedCornerShape(22.dp)
private val TILE_HEIGHT = 124.dp
private val GUTTER = 16.dp

@Preview(showBackground = true, heightDp = 1000)
@Composable
private fun InsightsScreenPreview() {
    MeonggoBanjeomTheme {
        InsightsScreen(
            uiState =
                InsightsUiState(
                    isLoading = false,
                    regionName = "전국",
                    insights =
                        HomeInsights(
                            dailyIntake = DailySummary(1, "2026-09-21", 366, 127, "2026-09-21T14:10:00Z"),
                            noticeClosing = NoticeClosing(null, 3, 173),
                            weeklyIntake =
                                WeeklyIntake(
                                    null,
                                    "2026-09-15",
                                    "2026-09-21",
                                    1135,
                                    listOf(180, 210, 160, 120, 90, 175, 200).mapIndexed {
                                            i,
                                            n,
                                        ->
                                        DailyIntakeCount("2026-09-${15 + i}", n, 0)
                                    },
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
                            lostReports = LostReports("2026-09-21", 136, null, null, null, null),
                        ),
                ),
            onBackClick = {},
            onRegionChangeClick = {},
            onShelteringListClick = {},
            onLostListClick = {},
        )
    }
}
