package com.hotdog.meonggocuisine.feature.post.data

sealed interface MyPostListResult {
    data class Success(
        val posts: List<MyPostSummary>,
        val hasNext: Boolean,
        val nextCursor: String?,
    ) : MyPostListResult

    data class Unauthorized(val message: String) : MyPostListResult

    data class Failure(val message: String) : MyPostListResult
}

interface MyPostRepository {
    suspend fun getMyPosts(
        status: String? = null,
        cursor: String? = null,
    ): MyPostListResult
}
