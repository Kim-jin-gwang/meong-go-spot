package com.hotdog.meonggocuisine.feature.home.ui

import com.hotdog.meonggocuisine.feature.home.data.DailySummary
import com.hotdog.meonggocuisine.feature.home.data.HomeInsights
import com.hotdog.meonggocuisine.feature.home.data.LostReports
import com.hotdog.meonggocuisine.feature.home.data.NoticeClosing
import com.hotdog.meonggocuisine.feature.home.data.ShelterOutcomes
import com.hotdog.meonggocuisine.feature.home.data.WeeklyIntake
import org.junit.Assert.assertEquals
import org.junit.Test

class InsightTickerMessagesTest {
    @Test
    fun `조각이 없으면 준비 중 한 문장이다`() {
        assertEquals(listOf(PREPARING_MESSAGE), insightTickerMessages(null, "서울특별시 마포구"))
        assertEquals(listOf(PREPARING_MESSAGE), insightTickerMessages(HomeInsights(), null))
    }

    @Test
    fun `카드 순서대로 문장을 만들고 지역이 없는 지역 조각은 건너뛴다`() {
        val insights =
            HomeInsights(
                dailyIntake = DailySummary(1, "2026-09-21", 366, 127, "2026-09-21T14:10:00Z"),
                noticeClosing = NoticeClosing("11440", 3, 4),
                weeklyIntake = WeeklyIntake("11440", "2026-09-15", "2026-09-21", 12, emptyList()),
                shelterOutcomes = ShelterOutcomes("2023-07-17", "2026-07-17", 286134, 0.1209, 0.2921, 10.4, "2026-09-15T04:59:29Z"),
                lostReports = LostReports("2026-09-21", 136, "11440", 3, 2, 1),
            )

        assertEquals(
            listOf(
                "366마리가 새로 보호소에 들어왔어요 · 보호소 127곳",
                "서울특별시 마포구 공고 마감 임박 4마리 · 3일 안에 끝나요",
                "서울특별시 마포구 최근 7일 12마리 입소",
                "보호소 동물 12%는 주인에게 돌아갔어요 · 입양 29%",
                "어제 전국 실종 신고 136건 · 서울특별시 마포구 30일 3건",
            ),
            insightTickerMessages(insights, "서울특별시 마포구"),
        )
        // 지역이 없으면 지역 카드 두 장은 빠지고 실종 신고도 전국만 말한다
        assertEquals(
            listOf(
                "366마리가 새로 보호소에 들어왔어요 · 보호소 127곳",
                "보호소 동물 12%는 주인에게 돌아갔어요 · 입양 29%",
                "어제 전국 실종 신고 136건",
            ),
            insightTickerMessages(insights, null),
        )
    }

    @Test
    fun `마감 임박이 0마리면 그 문장은 내지 않는다`() {
        val insights = HomeInsights(noticeClosing = NoticeClosing("11440", 3, 0))

        assertEquals(listOf(PREPARING_MESSAGE), insightTickerMessages(insights, "서울특별시 마포구"))
    }
}
