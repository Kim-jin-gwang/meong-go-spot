package com.hotdog.meonggocuisine.core.update

import com.hotdog.meonggocuisine.core.network.ApiResponse
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

interface AppVersionApi {
    @GET("app/version")
    suspend fun version(
        @Query("platform") platform: String = "android",
    ): Response<ApiResponse<AppVersionResponse>>
}
