package com.hotdog.meonggocuisine.feature.post.data

import com.hotdog.meonggocuisine.core.network.ApiResponse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class DefaultMyPostRepositoryTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `AUTH-002 401 응답은 로그인 필요 결과로 매핑한다`() =
        runTest {
            val repository = repositoryReturning(errorResponse(401, "AUTH-002", "인증이 필요합니다."))

            assertTrue(repository.getMyPosts() is MyPostListResult.Unauthorized)
        }

    @Test
    fun `AUTH-003 세션 만료 401 응답도 로그인 필요 결과로 매핑한다`() =
        runTest {
            val repository = repositoryReturning(errorResponse(401, "AUTH-003", "세션이 만료되었습니다."))

            assertTrue(repository.getMyPosts() is MyPostListResult.Unauthorized)
        }

    @Test
    fun `본문을 해석할 수 없는 401 응답도 로그인 필요 결과로 매핑한다`() =
        runTest {
            val body = "not-json".toResponseBody("text/plain".toMediaType())
            val repository = repositoryReturning(Response.error(401, body))

            assertTrue(repository.getMyPosts() is MyPostListResult.Unauthorized)
        }

    @Test
    fun `커서 오류 400 응답은 서버 메시지를 담은 실패로 매핑한다`() =
        runTest {
            val repository = repositoryReturning(errorResponse(400, "CURSOR-001", "cursor가 유효하지 않습니다."))

            val result = repository.getMyPosts()

            assertTrue(result is MyPostListResult.Failure)
            assertEquals("cursor가 유효하지 않습니다.", (result as MyPostListResult.Failure).message)
        }

    @Test
    fun `성공 응답은 목록과 페이지 정보로 매핑한다`() =
        runTest {
            val response =
                ApiResponse(
                    code = "SUCCESS",
                    message = "내 게시물을 조회했습니다.",
                    data =
                        MyPostListResponse(
                            items = listOf(myPost(1)),
                            page = MyPostListPage(size = 10, hasNext = true, nextCursor = "cursor-1"),
                        ),
                )
            val repository = repositoryReturning(Response.success(response))

            val result = repository.getMyPosts()

            assertTrue(result is MyPostListResult.Success)
            val success = result as MyPostListResult.Success
            assertEquals(listOf(1L), success.posts.map(MyPostSummary::postId))
            assertTrue(success.hasNext)
            assertEquals("cursor-1", success.nextCursor)
        }

    private fun repositoryReturning(response: Response<ApiResponse<MyPostListResponse>>) =
        DefaultMyPostRepository(
            postApi =
                object : PostApi {
                    override suspend fun getPostDetail(postId: Long): Response<ApiResponse<PostDetailResponse>> =
                        throw UnsupportedOperationException()

                    override suspend fun getMyPosts(
                        type: String?,
                        status: String?,
                        cursor: String?,
                    ): Response<ApiResponse<MyPostListResponse>> = response

                    override suspend fun updatePost(
                        postId: Long,
                        request: JsonObject,
                    ): Response<ApiResponse<UpdatePostResponse>> = throw UnsupportedOperationException()

                    override suspend fun replacePhotos(
                        postId: Long,
                        payload: RequestBody,
                        photos: List<MultipartBody.Part>,
                    ): Response<ApiResponse<ReplacePhotosResponse>> = throw UnsupportedOperationException()

                    override suspend fun closePost(
                        postId: Long,
                        request: ClosePostRequest,
                    ): Response<ApiResponse<ClosePostResponse>> = throw UnsupportedOperationException()
                },
            json = json,
        )

    private fun errorResponse(
        httpCode: Int,
        code: String,
        message: String,
    ): Response<ApiResponse<MyPostListResponse>> {
        val body = """{"code":"$code","message":"$message"}""".toResponseBody("application/json".toMediaType())
        return Response.error(httpCode, body)
    }

    private fun myPost(id: Long) =
        MyPostSummary(
            postId = id,
            type = "LOST",
            source = "USER_POST",
            status = "ACTIVE",
            version = 1,
            name = "망고",
            species = "DOG",
            eventDate = "2026-08-25",
            listedAt = "2026-08-25T11:00:00Z",
            publicLocation = "서울특별시 강남구",
            updatedAt = "2026-08-27T05:40:00Z",
        )
}
