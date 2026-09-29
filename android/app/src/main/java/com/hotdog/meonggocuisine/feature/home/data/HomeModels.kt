package com.hotdog.meonggocuisine.feature.home.data

import kotlinx.serialization.Serializable

/**
 * D2 응답 봉투입니다.
 *
 * 대상 실행(최신 성공 `DAILY_INCREMENTAL`)이 아직 없으면 서버가 `summary: null` 과 200 을
 * 내려주므로 nullable 입니다 (docs/api-spec.md D2).
 */
@Serializable
data class DailySummaryResponse(
    val summary: DailySummary? = null,
)

@Serializable
data class DailySummary(
    val ingestionRunId: Long,
    val summaryDate: String,
    val animalCount: Int,
    val shelterCount: Int,
    val completedAt: String,
)

/**
 * D3 홈 인사이트 — 카드 5장을 한 응답으로 받는다 (docs/api-spec.md D3).
 *
 * 조각마다 독립이라 서버는 없는 조각을 `null` 로 내려보낸다(지역 없음 → 지역 조각 null, 배치 전 → shelterOutcomes null).
 * 화면은 null 조각을 "준비 중"/"지역을 선택하세요" 카드로 그린다.
 */
@Serializable
data class HomeInsights(
    val dailyIntake: DailySummary? = null,
    val noticeClosing: NoticeClosing? = null,
    val weeklyIntake: WeeklyIntake? = null,
    val shelterOutcomes: ShelterOutcomes? = null,
    val lostReports: LostReports? = null,
)

/** [regionCode] 는 요청값 그대로 — 전국이면 서버가 null 을 준다 (D3, 2026-09-17). */
@Serializable
data class NoticeClosing(
    val regionCode: String? = null,
    val withinDays: Int,
    val animalCount: Int,
)

@Serializable
data class WeeklyIntake(
    val regionCode: String? = null,
    val from: String,
    val to: String,
    val total: Int,
    val days: List<DailyIntakeCount>,
)

@Serializable
data class DailyIntakeCount(
    val date: String,
    val dogCount: Int,
    val catCount: Int,
)

@Serializable
data class ShelterOutcomes(
    val windowStart: String,
    val windowEnd: String,
    val closedCount: Long,
    val returnRate: Double,
    val adoptionRate: Double,
    val averageNoticeDays: Double? = null,
    val computedAt: String,
)

@Serializable
data class LostReports(
    val yesterday: String,
    val yesterdayCount: Int,
    val regionCode: String? = null,
    val regionLast30DaysCount: Int? = null,
    val regionDogCount: Int? = null,
    val regionCatCount: Int? = null,
)
