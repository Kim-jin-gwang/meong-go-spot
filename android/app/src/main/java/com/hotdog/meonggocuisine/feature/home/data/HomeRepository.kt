package com.hotdog.meonggocuisine.feature.home.data

import com.hotdog.meonggocuisine.feature.community.data.LostPostSummary

/**
 * 메인 화면이 한 번에 보여 주는 묶음입니다.
 *
 * 세 조각(요약·보호 중·실종)은 서로 독립이라 하나가 실패해도 나머지는 보여 준다. 그래서 전체를
 * 실패로 만들지 않고 조각마다 null 또는 빈 목록으로 내려보낸다.
 */
data class HomeContent(
    val insights: HomeInsights?,
    val shelteringPosts: List<LostPostSummary>,
    val lostPosts: List<LostPostSummary>,
)

interface HomeRepository {
    /** D3 카드 묶음. 호출이 실패하면 null — 화면은 다섯 장 모두 "준비 중" 으로 그린다. */
    suspend fun getInsights(regionCode: String?): HomeInsights?

    /** 지역 범위의 보호 중 동물입니다. [regionCode] 가 null 이면 전국 — 지역을 고르지 않은 경우는 호출자가 걸러 준다. */
    suspend fun getShelteringPreview(regionCode: String?): List<LostPostSummary>

    /** 최근 실종 신고입니다. 지역과 무관하게 최신순으로 가져옵니다. */
    suspend fun getLostPreview(): List<LostPostSummary>
}
