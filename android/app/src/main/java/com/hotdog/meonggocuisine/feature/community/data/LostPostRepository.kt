package com.hotdog.meonggocuisine.feature.community.data

sealed interface LostPostListResult {
    data class Success(
        val posts: List<LostPostSummary>,
        val hasNext: Boolean,
        val nextCursor: String?,
    ) : LostPostListResult

    data class Failure(val message: String) : LostPostListResult
}

interface LostPostRepository {
    /** [regionCode] 가 null 이면 전국 목록이다 — P1 은 LOST 에서 regionCode 를 선택 필터로 둔다. */
    suspend fun getLostPosts(
        breedName: String?,
        regionCode: String?,
        filter: PostListFilter = PostListFilter(),
        cursor: String? = null,
    ): LostPostListResult
}
