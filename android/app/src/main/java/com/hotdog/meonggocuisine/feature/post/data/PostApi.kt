package com.hotdog.meonggocuisine.feature.post.data

import com.hotdog.meonggocuisine.core.network.ApiResponse
import kotlinx.serialization.json.JsonObject
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

interface PostApi {
    @GET("posts/{postId}")
    suspend fun getPostDetail(
        @Path("postId") postId: Long,
    ): Response<ApiResponse<PostDetailResponse>>

    @GET("members/me/posts")
    suspend fun getMyPosts(
        @Query("type") type: String? = null,
        @Query("status") status: String? = null,
        @Query("cursor") cursor: String? = null,
    ): Response<ApiResponse<MyPostListResponse>>

    /**
     * P4 게시물 메타데이터 수정입니다.
     *
     * 필드 생략은 유지, 명시적 null은 선택 필드 삭제다. 앱의 Json 설정은 `explicitNulls = false`라
     * 데이터 클래스의 null은 직렬화되지 않으므로 두 뜻을 구분할 수 있는 [JsonObject]로 보낸다.
     */
    @PATCH("posts/{postId}")
    suspend fun updatePost(
        @Path("postId") postId: Long,
        @Body request: JsonObject,
    ): Response<ApiResponse<UpdatePostResponse>>

    @Multipart
    @PUT("posts/{postId}/photos")
    suspend fun replacePhotos(
        @Path("postId") postId: Long,
        @Part("payload") payload: RequestBody,
        @Part photos: List<MultipartBody.Part>,
    ): Response<ApiResponse<ReplacePhotosResponse>>

    @POST("posts/{postId}/closure")
    suspend fun closePost(
        @Path("postId") postId: Long,
        @Body request: ClosePostRequest,
    ): Response<ApiResponse<ClosePostResponse>>
}
