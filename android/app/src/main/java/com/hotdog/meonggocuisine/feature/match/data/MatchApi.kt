package com.hotdog.meonggocuisine.feature.match.data

import com.hotdog.meonggocuisine.core.network.ApiResponse
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface MatchApi {
    @GET("posts/{postId}/candidates")
    suspend fun getCandidates(
        @Path("postId") postId: Long,
    ): Response<ApiResponse<MatchCandidatesResponse>>

    @POST("posts/{postId}/match-runs")
    suspend fun requestMatchRun(
        @Path("postId") postId: Long,
    ): Response<ApiResponse<MatchRunResponse>>

    /** 화면 상단 요약에 필요한 기준 게시물 정보입니다. M1 응답에 없어 따로 조회합니다. */
    @GET("posts/{postId}")
    suspend fun getBasePost(
        @Path("postId") postId: Long,
    ): Response<ApiResponse<MatchBasePostResponse>>
}
