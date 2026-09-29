package com.hotdog.meonggocuisine.feature.community.ui

import com.hotdog.meonggocuisine.feature.community.data.LostPostSummary
import com.hotdog.meonggocuisine.feature.community.data.PostListFilter
import com.hotdog.meonggocuisine.feature.community.data.PostListSort
import com.hotdog.meonggocuisine.feature.community.data.SelectedRegion
import com.hotdog.meonggocuisine.feature.community.data.ShelteringPostListResult
import com.hotdog.meonggocuisine.feature.community.data.ShelteringPostRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShelteringPostListViewModelTest {
    @Test
    fun `전국을 고르면 지역 선택을 요구하지 않고 서버에는 지역 코드를 생략한다`() =
        runTest(dispatcher) {
            val repository = FakeShelteringPostRepository(results = mutableListOf(successResult()))
            val regionStore = FakeRegionStore(SelectedRegion(code = "00", name = "전국"))
            val viewModel = ShelteringPostListViewModel(repository, regionStore)

            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.needsRegionSelection)
            assertEquals("전국", viewModel.uiState.value.selectedRegionName)
            assertEquals(listOf<String?>(null), repository.regionCodes)
            assertEquals(listOf(PostListSort.LATEST), repository.filters.map { it.sort })
        }

    @Test
    fun `오래된순으로 바꾸면 검색과 지역을 유지하고 재시도에도 정렬을 유지한다`() =
        runTest(dispatcher) {
            val repository =
                FakeShelteringPostRepository(
                    results =
                        mutableListOf(
                            successResult(),
                            ShelteringPostListResult.Success(emptyList(), false, null),
                            successResult(),
                            successResult(),
                        ),
                )
            val regionStore = FakeRegionStore(SelectedRegion(code = "11440", name = "서울특별시 마포구"))
            val viewModel = ShelteringPostListViewModel(repository, regionStore)
            advanceUntilIdle()

            viewModel.onSearchQueryChange("  푸들  ")
            viewModel.search()
            advanceUntilIdle()
            viewModel.onFilterApply(PostListFilter(sort = PostListSort.OLDEST))

            assertEquals(PostListSort.OLDEST, viewModel.uiState.value.filter.sort)
            assertEquals("푸들", viewModel.uiState.value.appliedSearchQuery)
            assertEquals("11440", viewModel.uiState.value.selectedRegionCode)
            assertTrue(viewModel.uiState.value.posts.isEmpty())
            assertTrue(viewModel.uiState.value.isLoading)

            advanceUntilIdle()
            val requestCount = repository.filters.size
            viewModel.onFilterApply(PostListFilter(sort = PostListSort.OLDEST))
            advanceUntilIdle()
            assertEquals(requestCount, repository.filters.size)

            viewModel.retry()
            advanceUntilIdle()

            assertEquals(
                listOf(
                    PostListSort.LATEST,
                    PostListSort.LATEST,
                    PostListSort.OLDEST,
                    PostListSort.OLDEST,
                ),
                repository.filters.map { it.sort },
            )
            assertEquals(listOf(null, null, null, null), repository.cursors)
            assertEquals(listOf("11440", "11440", "11440", "11440"), repository.regionCodes)
            assertEquals(listOf(null, "푸들", "푸들", "푸들"), repository.queries)
        }

    @Test
    fun `이전 지역 요청은 새 지역의 오래된순 결과를 덮어쓰지 못한다`() =
        runTest(dispatcher) {
            val firstRegion = CompletableDeferred<ShelteringPostListResult>()
            val secondRegionLatest = CompletableDeferred<ShelteringPostListResult>()
            val secondRegionOldest = CompletableDeferred<ShelteringPostListResult>()
            val repository =
                DeferredShelteringPostRepository(
                    mutableListOf(firstRegion, secondRegionLatest, secondRegionOldest),
                )
            val regionStore = FakeRegionStore(SelectedRegion(code = "11440", name = "서울특별시 마포구"))
            val viewModel = ShelteringPostListViewModel(repository, regionStore)
            advanceUntilIdle()

            regionStore.select(SelectedRegion(code = "41170", name = "경기도 안양시"))
            advanceUntilIdle()
            viewModel.onFilterApply(PostListFilter(sort = PostListSort.OLDEST))
            advanceUntilIdle()

            secondRegionOldest.complete(
                ShelteringPostListResult.Success(
                    posts = listOf(shelteringPost(30, "SHELTER", "새 지역 동물")),
                    hasNext = false,
                    nextCursor = null,
                ),
            )
            advanceUntilIdle()
            firstRegion.complete(
                ShelteringPostListResult.Success(
                    posts = listOf(shelteringPost(10, "SHELTER", "이전 지역 동물")),
                    hasNext = false,
                    nextCursor = null,
                ),
            )
            advanceUntilIdle()

            assertEquals("경기도 안양시", viewModel.uiState.value.selectedRegionName)
            assertEquals(PostListSort.OLDEST, viewModel.uiState.value.filter.sort)
            assertEquals(listOf(30L), viewModel.uiState.value.posts.map { it.postId })
            assertEquals(
                listOf(
                    Triple("11440", PostListSort.LATEST, null),
                    Triple("41170", PostListSort.LATEST, null),
                    Triple("41170", PostListSort.OLDEST, null),
                ),
                repository.requests,
            )
        }

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `지역 선택 전에는 전국으로 보호 목록을 불러온다`() =
        runTest(dispatcher) {
            val repository = FakeShelteringPostRepository(results = mutableListOf(successResult()))
            val regionStore = FakeRegionStore()
            val viewModel = ShelteringPostListViewModel(repository, regionStore)

            advanceUntilIdle()

            // 전국은 서버에 코드를 보내지 않는다 — 목록은 바로 보이고 좁히는 건 사용자가 정한다.
            assertFalse(viewModel.uiState.value.needsRegionSelection)
            assertEquals(listOf<String?>(null), repository.regionCodes)
        }

    @Test
    fun `지역을 선택하면 선택한 지역 코드로 보호 목록을 불러온다`() =
        runTest(dispatcher) {
            val repository =
                FakeShelteringPostRepository(
                    results = mutableListOf(successResult(), successResult()),
                )
            val regionStore = FakeRegionStore()
            val viewModel = ShelteringPostListViewModel(repository, regionStore)
            advanceUntilIdle()

            regionStore.select(SelectedRegion(code = "11440", name = "서울특별시 마포구"))
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.needsRegionSelection)
            assertEquals("서울특별시 마포구", viewModel.uiState.value.selectedRegionName)
            // 첫 요청은 기본값인 전국(코드 생략), 두 번째가 고른 지역이다.
            assertEquals(listOf(null, "11440"), repository.regionCodes)
            assertEquals(2, viewModel.uiState.value.posts.size)
            assertFalse(viewModel.uiState.value.isLoading)
        }

    @Test
    fun `검색하면 품종 검색어와 선택 지역을 함께 적용한다`() =
        runTest(dispatcher) {
            val repository =
                FakeShelteringPostRepository(
                    results =
                        mutableListOf(
                            successResult(),
                            ShelteringPostListResult.Success(emptyList(), false, null),
                        ),
                )
            val regionStore = FakeRegionStore(SelectedRegion(code = "11440", name = "서울특별시 마포구"))
            val viewModel = ShelteringPostListViewModel(repository, regionStore)
            advanceUntilIdle()

            viewModel.onSearchQueryChange("  푸들  ")
            viewModel.search()
            advanceUntilIdle()

            assertEquals("푸들", repository.queries.last())
            assertEquals("11440", repository.regionCodes.last())
            assertTrue(viewModel.uiState.value.isSearchResult)
            assertTrue(viewModel.uiState.value.posts.isEmpty())
        }

    @Test
    fun `사용자 게시물과 보호소 동물을 같은 목록 상태로 유지한다`() =
        runTest(dispatcher) {
            val repository =
                FakeShelteringPostRepository(
                    results = mutableListOf(successResult()),
                )
            val regionStore = FakeRegionStore(SelectedRegion(code = "11440", name = "서울특별시 마포구"))
            val viewModel = ShelteringPostListViewModel(repository, regionStore)
            advanceUntilIdle()

            assertEquals(listOf("USER_POST", "SHELTER"), viewModel.uiState.value.posts.map { it.source })
        }

    @Test
    fun `조회 실패는 빈 결과와 다른 오류 상태로 표시한다`() =
        runTest(dispatcher) {
            val repository =
                FakeShelteringPostRepository(
                    results = mutableListOf(ShelteringPostListResult.Failure("보호 목록 조회 실패")),
                )
            val regionStore = FakeRegionStore(SelectedRegion(code = "11440", name = "서울특별시 마포구"))
            val viewModel = ShelteringPostListViewModel(repository, regionStore)

            advanceUntilIdle()

            assertEquals("보호 목록 조회 실패", viewModel.uiState.value.errorMessage)
            assertFalse(viewModel.uiState.value.isLoading)
        }

    private fun successResult() =
        ShelteringPostListResult.Success(
            posts =
                listOf(
                    shelteringPost(1, "USER_POST", "콩이"),
                    shelteringPost(2, "SHELTER", "몽이"),
                ),
            hasNext = false,
            nextCursor = null,
        )

    private fun shelteringPost(
        id: Long,
        source: String,
        name: String,
    ) = LostPostSummary(
        postId = id,
        type = "SHELTERING",
        source = source,
        name = name,
        species = "DOG",
        breedName = "푸들",
        sex = "FEMALE",
        color = "갈색",
        eventDate = "2026-09-08",
        listedAt = "2026-09-08T12:00:00Z",
        publicLocation = "서울특별시 마포구",
    )

    private class FakeShelteringPostRepository(
        private val results: MutableList<ShelteringPostListResult> = mutableListOf(),
    ) : ShelteringPostRepository {
        val queries = mutableListOf<String?>()
        val regionCodes = mutableListOf<String?>()
        val filters = mutableListOf<PostListFilter>()
        val cursors = mutableListOf<String?>()

        override suspend fun getShelteringPosts(
            breedName: String?,
            regionCode: String?,
            filter: PostListFilter,
            cursor: String?,
        ): ShelteringPostListResult {
            queries += breedName
            regionCodes += regionCode
            filters += filter
            cursors += cursor
            return results.removeAt(0)
        }
    }

    private class DeferredShelteringPostRepository(
        private val responses: MutableList<CompletableDeferred<ShelteringPostListResult>>,
    ) : ShelteringPostRepository {
        val requests = mutableListOf<Triple<String?, PostListSort, String?>>()

        override suspend fun getShelteringPosts(
            breedName: String?,
            regionCode: String?,
            filter: PostListFilter,
            cursor: String?,
        ): ShelteringPostListResult {
            requests += Triple(regionCode, filter.sort, cursor)
            return responses.removeAt(0).await()
        }
    }
}
