package com.hotdog.meonggocuisine.feature.home.data

import com.hotdog.meonggocuisine.core.network.ApiResponse
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

interface HomeApi {
    /** D2. 인증 없이 호출하는 최신 일일 입소 요약입니다. */
    @GET("data-sources/shelter-animals/daily-summary")
    suspend fun getDailySummary(): Response<ApiResponse<DailySummaryResponse>>

    /** D3. 홈 카드 5장 — D2 요약을 포함하므로 홈은 이것만 부른다. 지역이 없으면 regionCode 를 생략한다. */
    @GET("data-sources/shelter-animals/insights")
    suspend fun getInsights(
        @Query("regionCode") regionCode: String?,
    ): Response<ApiResponse<HomeInsights>>
}
