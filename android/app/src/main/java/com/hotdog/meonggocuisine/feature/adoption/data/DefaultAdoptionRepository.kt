package com.hotdog.meonggocuisine.feature.adoption.data

import com.hotdog.meonggocuisine.core.network.ApiErrorResponse
import kotlinx.serialization.json.Json
import retrofit2.Response
import java.io.IOException
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

class DefaultAdoptionRepository
    @Inject
    constructor(
        private val adoptionApi: AdoptionApi,
        private val json: Json,
    ) : AdoptionRepository {
        override suspend fun getWaitingAnimals(
            regionCode: String?,
            species: SpeciesFilter,
            sex: SexFilter,
            cursor: String?,
        ): AdoptionListResult =
            try {
                val response = adoptionApi.candidates(regionCode, species.query, sex.query, cursor)
                val body = response.body()
                when {
                    response.isSuccessful && body != null ->
                        AdoptionListResult.Success(
                            animals = body.data.items,
                            hasNext = body.data.page.hasNext,
                            nextCursor = body.data.page.nextCursor,
                        )

                    response.errorCode() == CURSOR_EXPIRED -> AdoptionListResult.CursorExpired
                    else -> AdoptionListResult.Failure(LIST_FAILURE)
                }
            } catch (e: CancellationException) {
                // 취소는 실패가 아니다. 삼키면 취소된 조회가 뒤늦게 오류 화면을 덮어쓴다.
                throw e
            } catch (_: IOException) {
                AdoptionListResult.Failure(CONNECTION_FAILURE)
            } catch (_: Exception) {
                AdoptionListResult.Failure(LIST_FAILURE)
            }

        /**
         * 넘김 기록은 보내고 잊습니다 (AD5).
         *
         * 실패를 화면에 알리지 않는다 — 사용자가 한 일은 카드를 넘긴 것이고 그건 이미 됐다. 기록이
         * 빠지면 그 동물이 다음 조회에 한 번 더 올라올 뿐이다 (계약 §5).
         */
        override suspend fun recordSwipe(postId: Long) {
            try {
                adoptionApi.recordSwipe(postId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 삼킨다 — 위 주석 참고.
            }
        }

        override suspend fun getSwipes(cursor: String?): AdoptionSwipeListResult =
            try {
                val response = adoptionApi.swipes(cursor)
                val body = response.body()
                when {
                    response.isSuccessful && body != null ->
                        AdoptionSwipeListResult.Success(
                            records = body.data.items.map(AdoptionSwipeItem::toRecord),
                            hasNext = body.data.page.hasNext,
                            nextCursor = body.data.page.nextCursor,
                        )

                    response.errorCode() == CURSOR_EXPIRED -> AdoptionSwipeListResult.CursorExpired
                    else -> AdoptionSwipeListResult.Failure(SWIPE_LIST_FAILURE)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                AdoptionSwipeListResult.Failure(CONNECTION_FAILURE)
            } catch (_: Exception) {
                AdoptionSwipeListResult.Failure(SWIPE_LIST_FAILURE)
            }

        override suspend fun addFavorite(postId: Long): AdoptionFavoriteResult =
            favorite(FAVORITE_ADD_FAILURE) { adoptionApi.addFavorite(postId) }

        override suspend fun removeFavorite(postId: Long): AdoptionFavoriteResult =
            favorite(FAVORITE_REMOVE_FAILURE) { adoptionApi.removeFavorite(postId) }

        /**
         * 찜 추가·해제는 성공이 204라 본문이 없습니다.
         *
         * 두 요청 모두 멱등이라 같은 값을 다시 보내도 성공으로 온다. 실패는 로그인이 필요한
         * 경우와 후보 자격을 잃은 경우만 구분하고 나머지는 한 문구로 묶는다.
         */
        private suspend fun favorite(
            failureMessage: String,
            request: suspend () -> Response<Unit>,
        ): AdoptionFavoriteResult =
            try {
                val response = request()
                when {
                    response.isSuccessful -> AdoptionFavoriteResult.Success
                    response.code() == HTTP_UNAUTHORIZED -> AdoptionFavoriteResult.Unauthorized
                    response.errorCode() == NO_LONGER_AVAILABLE -> AdoptionFavoriteResult.NoLongerAvailable
                    else -> AdoptionFavoriteResult.Failure(failureMessage)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                AdoptionFavoriteResult.Failure(CONNECTION_FAILURE)
            } catch (_: Exception) {
                AdoptionFavoriteResult.Failure(failureMessage)
            }

        private fun Response<*>.errorCode(): String? =
            runCatching {
                errorBody()?.string()?.let { json.decodeFromString<ApiErrorResponse>(it).code }
            }.getOrNull()

        private companion object {
            const val HTTP_UNAUTHORIZED = 401
            const val CURSOR_EXPIRED = "CURSOR-001"
            const val NO_LONGER_AVAILABLE = "ADOPTION-001"
            const val CONNECTION_FAILURE = "서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요."
            const val LIST_FAILURE = "기다리는 아이를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요."
            const val FAVORITE_ADD_FAILURE = "관심 표시를 저장하지 못했습니다. 잠시 후 다시 시도해 주세요."
            const val FAVORITE_REMOVE_FAILURE = "관심 표시를 해제하지 못했습니다. 잠시 후 다시 시도해 주세요."
            const val SWIPE_LIST_FAILURE = "지나온 아이를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요."
        }
    }

/**
 * 서버가 준 넘김 한 건을 화면이 쓰는 모양으로 옮깁니다.
 *
 * 자격을 잃은 동물은 `noticeEndDate`·`daysSinceNoticeEnd` 가 비어 올 수 있어 기본값을 채운다 —
 * 히스토리는 그런 아이도 칸을 지키고 `available=false` 로만 알린다 (AD6).
 */
private fun AdoptionSwipeItem.toRecord(): AdoptionSwipeRecord =
    AdoptionSwipeRecord(
        animal =
            AdoptionAnimal(
                postId = postId,
                species = species,
                breedName = breedName,
                sex = sex,
                color = color,
                publicLocation = publicLocation,
                thumbnailUrl = thumbnailUrl,
                noticeEndDate = noticeEndDate.orEmpty(),
                daysSinceNoticeEnd = daysSinceNoticeEnd ?: 0L,
                lastSyncedAt = lastSyncedAt.orEmpty(),
                favorited = favorited,
            ),
        swipedAt = swipedAt,
        favorited = favorited,
        available = availability == AdoptionAvailability.AVAILABLE,
    )
