package com.hotdog.meonggocuisine.feature.adoption.data

sealed interface AdoptionListResult {
    data class Success(
        val animals: List<AdoptionAnimal>,
        val hasNext: Boolean,
        val nextCursor: String?,
    ) : AdoptionListResult

    /** 커서가 만료되거나 다른 필터의 것이면 첫 페이지부터 다시 부른다 (계약 §5). */
    data object CursorExpired : AdoptionListResult

    data class Failure(val message: String) : AdoptionListResult
}

sealed interface AdoptionFavoriteResult {
    data object Success : AdoptionFavoriteResult

    /** 로그인이 필요하거나 세션이 끊겼다. */
    data object Unauthorized : AdoptionFavoriteResult

    /** 후보 자격을 잃어 더는 찜할 수 없다 — 카드에서 내린다 (`ADOPTION-001`). */
    data object NoLongerAvailable : AdoptionFavoriteResult

    data class Failure(val message: String) : AdoptionFavoriteResult
}

/** 넘긴 동물 목록(AD6) 한 쪽. */
sealed interface AdoptionSwipeListResult {
    data class Success(
        val records: List<AdoptionSwipeRecord>,
        val hasNext: Boolean,
        val nextCursor: String?,
    ) : AdoptionSwipeListResult

    data object CursorExpired : AdoptionSwipeListResult

    data class Failure(val message: String) : AdoptionSwipeListResult
}

interface AdoptionRepository {
    /**
     * 입양을 기다리는 동물을 공고 종료일이 오래된 순으로 가져옵니다.
     *
     * 이미 넘긴 동물은 **서버가 뺀다** (AD1, 2026-09-25). 화면이 받아서 거르면 10건을 요청해 몇 건만
     * 남는 쪽이 생기고 `hasNext` 가 화면에 보이는 장수와 어긋난다.
     *
     * [regionCode] 는 서버 AD1 규칙 그대로 — 5자리 시·군·구, 2자리 시·도 전체, null 이면 전국.
     */
    suspend fun getWaitingAnimals(
        regionCode: String?,
        species: SpeciesFilter,
        sex: SexFilter = SexFilter.ALL,
        cursor: String? = null,
    ): AdoptionListResult

    /**
     * 넘긴 동물을 기록합니다 (AD5).
     *
     * 화면은 이 결과를 기다리지 않는다. 실패하면 그 동물이 한 번 더 올라올 수 있고, 다음 조회가
     * 서버 기준으로 맞춰 준다 (계약 §5).
     */
    suspend fun recordSwipe(postId: Long)

    suspend fun getSwipes(cursor: String? = null): AdoptionSwipeListResult

    suspend fun addFavorite(postId: Long): AdoptionFavoriteResult

    suspend fun removeFavorite(postId: Long): AdoptionFavoriteResult
}
