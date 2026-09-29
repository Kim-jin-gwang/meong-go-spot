package com.hotdog.meonggocuisine.feature.community.data

sealed interface ShelteringPostListResult {
    data class Success(
        val posts: List<LostPostSummary>,
        val hasNext: Boolean,
        val nextCursor: String?,
    ) : ShelteringPostListResult

    data class Failure(val message: String) : ShelteringPostListResult
}

interface ShelteringPostRepository {
    /** [regionCode] 는 5자리 시·군·구, 2자리 시·도 전체, null 전국 — 서버 P1 규칙 그대로. */
    suspend fun getShelteringPosts(
        breedName: String?,
        regionCode: String?,
        filter: PostListFilter = PostListFilter(),
        cursor: String? = null,
    ): ShelteringPostListResult
}
