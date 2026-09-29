package com.hotdog.meonggocuisine.feature.home.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 전국 응답 회귀 방지. 2026-09-17 서버가 전국 조각의 regionCode 를 null 로 내려보내기 시작했는데 모델이 non-null 이어서
 * 파싱이 통째로 실패했고, 카드 5장이 전부 "준비 중" 으로 보였다.
 */
class HomeInsightsParsingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `전국 응답의 null regionCode 를 읽는다`() {
        val body =
            """
            {"dailyIntake":{"ingestionRunId":10,"summaryDate":"2026-09-16","animalCount":324,"shelterCount":106,"completedAt":"2026-09-16T14:12:00Z"},
             "noticeClosing":{"regionCode":null,"withinDays":3,"animalCount":575},
             "weeklyIntake":{"regionCode":null,"from":"2026-09-11","to":"2026-09-17","total":1193,"days":[{"date":"2026-09-11","dogCount":100,"catCount":80}]},
             "shelterOutcomes":{"windowStart":"2023-07-17","windowEnd":"2026-07-17","closedCount":286134,"returnRate":0.1209,"adoptionRate":0.2921,"averageNoticeDays":10.4,"computedAt":"2026-09-15T06:33:48Z"},
             "lostReports":{"yesterday":"2026-09-16","yesterdayCount":12,"regionCode":null,"regionLast30DaysCount":120,"regionDogCount":90,"regionCatCount":30}}
            """.trimIndent()

        val insights = json.decodeFromString<HomeInsights>(body)

        assertEquals(324, insights.dailyIntake?.animalCount)
        assertNull(insights.noticeClosing?.regionCode)
        assertEquals(575, insights.noticeClosing?.animalCount)
        assertEquals(1193, insights.weeklyIntake?.total)
        assertEquals(120, insights.lostReports?.regionLast30DaysCount)
    }

    @Test
    fun `지역 조각이 null 인 옛 응답도 그대로 읽는다`() {
        val insights =
            json.decodeFromString<HomeInsights>(
                """{"dailyIntake":null,"noticeClosing":null,"weeklyIntake":null,"shelterOutcomes":null,"lostReports":null}""",
            )
        assertNull(insights.noticeClosing)
        assertNull(insights.lostReports)
    }
}
