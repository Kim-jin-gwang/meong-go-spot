package com.hotdog.meonggocuisine.feature.post.data

sealed interface PostDetailResult {
    data class Success(val detail: PostDetailResponse) : PostDetailResult

    data class NotFound(val message: String) : PostDetailResult

    data class Failure(val message: String) : PostDetailResult
}

interface PostDetailRepository {
    suspend fun getPostDetail(postId: Long): PostDetailResult
}
