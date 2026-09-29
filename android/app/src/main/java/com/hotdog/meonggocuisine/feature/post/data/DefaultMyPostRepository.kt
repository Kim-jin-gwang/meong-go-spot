package com.hotdog.meonggocuisine.feature.post.data

import com.hotdog.meonggocuisine.core.network.ApiErrorResponse
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject

class DefaultMyPostRepository
    @Inject
    constructor(
        private val postApi: PostApi,
        private val json: Json,
    ) : MyPostRepository {
        override suspend fun getMyPosts(
            status: String?,
            cursor: String?,
        ): MyPostListResult =
            try {
                val response = postApi.getMyPosts(status = status, cursor = cursor)
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    MyPostListResult.Success(
                        posts = body.data.items,
                        hasNext = body.data.page.hasNext,
                        nextCursor = body.data.page.nextCursor,
                    )
                } else if (response.code() == 401) {
                    // AUTH-002(토큰 무효)와 AUTH-003(세션 만료·폐기) 모두 재로그인 대상이다.
                    MyPostListResult.Unauthorized("로그인이 필요합니다. 다시 로그인해 주세요.")
                } else {
                    val error = response.errorBody()?.string()?.let(::parseError)
                    MyPostListResult.Failure(error?.message ?: LIST_FAILURE)
                }
            } catch (_: IOException) {
                MyPostListResult.Failure("서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.")
            } catch (_: Exception) {
                MyPostListResult.Failure(LIST_FAILURE)
            }

        private fun parseError(value: String): ApiErrorResponse? =
            runCatching { json.decodeFromString<ApiErrorResponse>(value) }.getOrNull()

        private companion object {
            const val LIST_FAILURE = "내 게시물을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요."
        }
    }
