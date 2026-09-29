package com.hotdog.meonggocuisine.feature.community.data

import com.hotdog.meonggocuisine.core.network.ApiResponse
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

interface CommunityApi {
    @GET("posts")
    suspend fun getPosts(
        @Query("type") type: String = "LOST",
        @Query("species") species: String? = null,
        @Query("sex") sex: String? = null,
        @Query("breedName") breedName: String? = null,
        @Query("regionCode") regionCode: String? = null,
        @Query("color") color: String? = null,
        @Query("source") source: String? = null,
        @Query("sort") sort: String? = null,
        @Query("cursor") cursor: String? = null,
    ): Response<ApiResponse<PostListResponse>>
}
