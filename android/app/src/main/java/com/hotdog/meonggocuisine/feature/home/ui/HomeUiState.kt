package com.hotdog.meonggocuisine.feature.home.ui

import com.hotdog.meonggocuisine.feature.community.data.LostPostSummary
import com.hotdog.meonggocuisine.feature.home.data.DailySummary
import com.hotdog.meonggocuisine.feature.home.data.HomeInsights

data class HomeUiState(
    val isLoading: Boolean = true,
    /** D3 카드 5장. 호출 실패면 null — 카드는 전부 "준비 중". */
    val insights: HomeInsights? = null,
    val regionName: String? = null,
    val shelteringPosts: List<LostPostSummary> = emptyList(),
    val lostPosts: List<LostPostSummary> = emptyList(),
) {
    /** 지역을 고르지 않으면 보호 중 미리보기 대신 지역 선택을 안내한다. */
    val hasRegion: Boolean get() = regionName != null

    /** 첫 카드(일일 입소 요약) — D2 와 같은 값이며 D3 응답 안에 들어온다. */
    val summary: DailySummary? get() = insights?.dailyIntake
}
