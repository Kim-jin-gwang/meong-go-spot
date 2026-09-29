package com.hotdog.meonggocuisine.feature.adoption.data

import com.hotdog.meonggocuisine.core.network.ApiResponse
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 입양 탐색 API — docs/api-spec.md AD1·AD3·AD4·AD5·AD6.
 *
 * 1.6 앱은 로그인 뒤 사용한다. AD1은 1.5 앱의 서버 선배포 호환성을 위해 공개 조회도 유지하지만,
 * AD3~AD6은 로그인이 필요하다.
 */
interface AdoptionApi {
    @GET("adoptions")
    suspend fun candidates(
        @Query("regionCode") regionCode: String?,
        @Query("species") species: String? = null,
        @Query("sex") sex: String? = null,
        @Query("cursor") cursor: String? = null,
    ): Response<ApiResponse<AdoptionListResponse>>

    /**
     * 넘긴 동물을 기록합니다 (AD5).
     *
     * 어느 쪽으로 넘겼는지는 보내지 않는다 — 찜 여부는 AD3·AD4 가 정하고 이 기록은 "이미 봤다" 만
     * 뜻한다. 같은 동물을 다시 보내도 204다 (멱등).
     */
    @PUT("members/me/adoption-swipes/{postId}")
    suspend fun recordSwipe(
        @Path("postId") postId: Long,
    ): Response<Unit>

    /** 내가 넘긴 동물을 최신순으로 (AD6). 각 항목에 현재 찜 여부가 함께 온다. */
    @GET("members/me/adoption-swipes")
    suspend fun swipes(
        @Query("cursor") cursor: String? = null,
    ): Response<ApiResponse<AdoptionSwipeListResponse>>

    /** 이미 찜한 항목을 다시 보내도 204다 (멱등). */
    @PUT("members/me/adoption-favorites/{postId}")
    suspend fun addFavorite(
        @Path("postId") postId: Long,
    ): Response<Unit>

    /** 찜이 없거나 반복 요청이어도 204다 (멱등). */
    @DELETE("members/me/adoption-favorites/{postId}")
    suspend fun removeFavorite(
        @Path("postId") postId: Long,
    ): Response<Unit>
}

@Serializable
data class AdoptionListResponse(
    val asOfDate: String,
    val items: List<AdoptionAnimal>,
    val page: AdoptionPage,
)

/** `nextCursor`는 마지막 페이지에서 생략된다 (`@JsonInclude(NON_NULL)`). */
@Serializable
data class AdoptionPage(
    val size: Int,
    val hasNext: Boolean,
    val nextCursor: String? = null,
)

@Serializable
data class AdoptionSwipeListResponse(
    val asOfDate: String,
    val items: List<AdoptionSwipeItem>,
    val page: AdoptionPage,
)

/**
 * 넘긴 동물 한 건 (AD6).
 *
 * 카드와 같은 값에 [swipedAt] 과 [availability] 가 더 붙는다. 자격을 잃은 동물도 목록에 남는다 —
 * 히스토리에서 아이가 말없이 사라지면 사용자가 자기 기록을 믿지 못한다.
 */
@Serializable
data class AdoptionSwipeItem(
    val postId: Long,
    val species: Species,
    val breedName: String? = null,
    val sex: Sex,
    val color: String? = null,
    val publicLocation: String,
    val thumbnailUrl: String? = null,
    val noticeEndDate: String? = null,
    val daysSinceNoticeEnd: Long? = null,
    val lastSyncedAt: String? = null,
    val swipedAt: String,
    val favorited: Boolean = false,
    val availability: AdoptionAvailability = AdoptionAvailability.AVAILABLE,
)

@Serializable
enum class AdoptionAvailability {
    AVAILABLE,
    UNAVAILABLE,
}
