package com.hotdog.meonggocuisine.feature.report.data

import com.hotdog.meonggocuisine.core.network.ApiResponse
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface ReportApi {
    @Multipart
    @POST("posts")
    suspend fun createPost(
        @Part("payload") payload: RequestBody,
        @Part photos: List<MultipartBody.Part>,
    ): Response<ApiResponse<CreatePostResponse>>
}
