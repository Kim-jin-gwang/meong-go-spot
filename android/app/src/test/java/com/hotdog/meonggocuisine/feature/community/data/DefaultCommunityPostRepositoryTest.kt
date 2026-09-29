package com.hotdog.meonggocuisine.feature.community.data

import com.hotdog.meonggocuisine.core.network.ApiResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.Response

class DefaultCommunityPostRepositoryTest {
    @Test
    fun `요청 취소는 목록 실패로 변환하지 않고 다시 던진다`() =
        runTest {
            val api = CancellingCommunityApi()

            assertCancellation {
                DefaultLostPostRepository(api).getLostPosts(null, null, PostListFilter(sort = PostListSort.LATEST))
            }
            assertCancellation {
                DefaultShelteringPostRepository(api).getShelteringPosts(null, "11440", PostListFilter(sort = PostListSort.LATEST))
            }
        }

    @Test
    fun `두 공개 목록 저장소는 선택한 오래된순을 API 값으로 전달한다`() =
        runTest {
            val api = RecordingCommunityApi()

            DefaultLostPostRepository(api).getLostPosts(null, null, PostListFilter(sort = PostListSort.OLDEST))
            DefaultShelteringPostRepository(api).getShelteringPosts(null, "11440", PostListFilter(sort = PostListSort.OLDEST))

            assertEquals(listOf("LOST:OLDEST", "SHELTERING:OLDEST"), api.requests)
        }

    @Test
    fun `최신순은 서버 기본값이라 sort 를 보내지 않는다`() =
        runTest {
            // sort 를 모르는 옛 서버(v1.3.0)가 400 을 주므로 기본값은 생략한다 (2026-09-22)
            val api = RecordingCommunityApi()

            DefaultLostPostRepository(api).getLostPosts(null, null, PostListFilter(sort = PostListSort.LATEST))
            DefaultShelteringPostRepository(api).getShelteringPosts(null, "11440", PostListFilter(sort = PostListSort.LATEST))

            assertEquals(listOf("LOST:-", "SHELTERING:-"), api.requests)
        }

    @Test
    fun `축종과 성별 조건을 API 값으로 전달한다`() =
        runTest {
            val api = RecordingCommunityApi()
            val filter = PostListFilter(species = SpeciesFilter.DOG, sex = SexFilter.FEMALE)

            DefaultLostPostRepository(api).getLostPosts(null, null, filter)
            DefaultShelteringPostRepository(api).getShelteringPosts(null, "11440", filter)

            assertEquals(listOf("DOG/FEMALE", "DOG/FEMALE"), api.animalConditions)
        }

    @Test
    fun `전체는 파라미터를 보내지 않는다`() =
        runTest {
            // 서버는 값이 오면 그 값으로 거른다. 전체를 뜻하는 값이 따로 없어 생략해야 전체가 된다.
            val api = RecordingCommunityApi()

            DefaultLostPostRepository(api).getLostPosts(null, null, PostListFilter())
            DefaultShelteringPostRepository(api).getShelteringPosts(null, "11440", PostListFilter())

            assertEquals(listOf("-/-", "-/-"), api.animalConditions)
        }

    private class RecordingCommunityApi : CommunityApi {
        val requests = mutableListOf<String>()
        val animalConditions = mutableListOf<String>()

        override suspend fun getPosts(
            type: String,
            species: String?,
            sex: String?,
            breedName: String?,
            regionCode: String?,
            color: String?,
            source: String?,
            sort: String?,
            cursor: String?,
        ): Response<ApiResponse<PostListResponse>> {
            requests += "$type:${sort ?: "-"}"
            animalConditions += "${species ?: "-"}/${sex ?: "-"}"
            return Response.success(
                ApiResponse(
                    code = "SUCCESS",
                    message = "목록을 조회했습니다.",
                    data = PostListResponse(emptyList(), PostListPage(10, false)),
                ),
            )
        }
    }

    private class CancellingCommunityApi : CommunityApi {
        override suspend fun getPosts(
            type: String,
            species: String?,
            sex: String?,
            breedName: String?,
            regionCode: String?,
            color: String?,
            source: String?,
            sort: String?,
            cursor: String?,
        ): Response<ApiResponse<PostListResponse>> = throw CancellationException("request cancelled")
    }

    private suspend fun assertCancellation(block: suspend () -> Unit) {
        try {
            block()
            fail("CancellationException이 다시 발생해야 합니다.")
        } catch (_: CancellationException) {
            // 요청 취소는 정상적인 coroutine 제어 흐름이다.
        }
    }
}
