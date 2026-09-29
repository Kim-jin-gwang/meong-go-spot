package com.hotdog.meonggocuisine.feature.post.data

import com.hotdog.meonggocuisine.core.network.ApiErrorResponse
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject

class DefaultPostDetailRepository
    @Inject
    constructor(
        private val postApi: PostApi,
        private val json: Json,
    ) : PostDetailRepository {
        override suspend fun getPostDetail(postId: Long): PostDetailResult =
            try {
                val response = postApi.getPostDetail(postId)
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    PostDetailResult.Success(body.data)
                } else {
                    val error = response.errorBody()?.string()?.let(::parseError)
                    when (error?.code) {
                        "POST-001" -> PostDetailResult.NotFound(error.message)
                        else -> PostDetailResult.Failure(error?.message ?: DETAIL_FAILURE)
                    }
                }
            } catch (_: IOException) {
                PostDetailResult.Failure("서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.")
            } catch (_: Exception) {
                PostDetailResult.Failure(DETAIL_FAILURE)
            }

        private fun parseError(value: String): ApiErrorResponse? =
            runCatching { json.decodeFromString<ApiErrorResponse>(value) }.getOrNull()

        private companion object {
            const val DETAIL_FAILURE = "게시물 상세를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요."
        }
    }
