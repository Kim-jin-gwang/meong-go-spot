package com.hotdog.meonggocuisine.feature.community.ui

import com.hotdog.meonggocuisine.feature.community.data.LostPostListResult
import com.hotdog.meonggocuisine.feature.community.data.LostPostRepository
import com.hotdog.meonggocuisine.feature.community.data.LostPostSummary
import com.hotdog.meonggocuisine.feature.community.data.PostListFilter
import com.hotdog.meonggocuisine.feature.community.data.PostListSort
import com.hotdog.meonggocuisine.feature.community.data.SelectedRegion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
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
class LostPostListViewModelTest {
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
    fun `지역을 고르지 않았으면 전국 목록을 불러온다`() =
        runTest(dispatcher) {
            val repository = FakeLostPostRepository(results = mutableListOf(successResult()))
            val viewModel = LostPostListViewModel(repository, FakeRegionStore())

            advanceUntilIdle()

            assertEquals(1, viewModel.uiState.value.posts.size)
            assertFalse(viewModel.uiState.value.isLoading)
            assertEquals(null, repository.queries.single())
            assertEquals(null, repository.regionCodes.single())
            assertEquals(PostListSort.LATEST, repository.filters.single().sort)
            assertEquals(null, viewModel.uiState.value.selectedRegionName)
        }

    @Test
    fun `정렬을 바꾸면 검색과 지역을 유지하고 첫 페이지부터 오래된순으로 다시 불러온다`() =
        runTest(dispatcher) {
            val repository =
                FakeLostPostRepository(
                    results =
                        mutableListOf(
                            successResult(postId = 1),
                            successResult(postId = 2),
                            successResult(postId = 3, hasNext = true, nextCursor = "oldest-next"),
                            successResult(postId = 4),
                            successResult(postId = 5),
                        ),
                )
            val regionStore = FakeRegionStore(SelectedRegion(code = "11440", name = "서울특별시 마포구"))
            val viewModel = LostPostListViewModel(repository, regionStore)
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
            assertEquals(null, viewModel.uiState.value.nextCursor)

            advanceUntilIdle()
            assertEquals(listOf(3L), viewModel.uiState.value.posts.map { it.postId })
            assertEquals(PostListSort.OLDEST, repository.filters.last().sort)
            assertEquals("푸들", repository.queries.last())
            assertEquals("11440", repository.regionCodes.last())

            val requestCount = repository.filters.size
            viewModel.onFilterApply(PostListFilter(sort = PostListSort.OLDEST))
            advanceUntilIdle()
            assertEquals(requestCount, repository.filters.size)

            viewModel.loadMore()
            advanceUntilIdle()
            viewModel.retry()
            advanceUntilIdle()

            assertEquals(
                listOf(
                    PostListSort.LATEST,
                    PostListSort.LATEST,
                    PostListSort.OLDEST,
                    PostListSort.OLDEST,
                    PostListSort.OLDEST,
                ),
                repository.filters.map { it.sort },
            )
            assertEquals(listOf(null, null, null, "oldest-next", null), repository.cursors)
        }

    @Test
    fun `더 보기 취소를 실패로 반환한 이전 요청은 새 정렬 상태를 바꾸지 못한다`() =
        runTest(dispatcher) {
            val newSortResult = CompletableDeferred<LostPostListResult>()
            var requestCount = 0
            val repository =
                object : LostPostRepository {
                    override suspend fun getLostPosts(
                        breedName: String?,
                        regionCode: String?,
                        filter: PostListFilter,
                        cursor: String?,
                    ): LostPostListResult =
                        when (requestCount++) {
                            0 -> successResult(hasNext = true, nextCursor = "latest-next")
                            1 ->
                                try {
                                    awaitCancellation()
                                } catch (_: CancellationException) {
                                    LostPostListResult.Failure("취소된 더 보기 요청")
                                }
                            else -> newSortResult.await()
                        }
                }
            val viewModel = LostPostListViewModel(repository, FakeRegionStore())
            advanceUntilIdle()

            viewModel.loadMore()
            advanceUntilIdle()
            viewModel.onFilterApply(PostListFilter(sort = PostListSort.OLDEST))
            advanceUntilIdle()

            assertEquals(PostListSort.OLDEST, viewModel.uiState.value.filter.sort)
            assertTrue(viewModel.uiState.value.isLoading)
            assertEquals(null, viewModel.uiState.value.errorMessage)
            assertTrue(viewModel.uiState.value.posts.isEmpty())

            newSortResult.complete(successResult(postId = 2))
            advanceUntilIdle()
            assertEquals(listOf(2L), viewModel.uiState.value.posts.map { it.postId })
        }

    @Test
    fun `저장된 지역이 있으면 그 지역으로 불러오고 지역을 바꾸면 다시 불러온다`() =
        runTest(dispatcher) {
            val repository = FakeLostPostRepository(results = mutableListOf(successResult(), successResult()))
            val regionStore = FakeRegionStore(SelectedRegion(code = "11440", name = "서울특별시 마포구"))
            val viewModel = LostPostListViewModel(repository, regionStore)
            advanceUntilIdle()
            assertEquals("서울특별시 마포구", viewModel.uiState.value.selectedRegionName)

            regionStore.select(SelectedRegion(code = "41170", name = "경기도 안양시"))
            advanceUntilIdle()

            assertEquals(listOf("11440", "41170"), repository.regionCodes)
            assertEquals("경기도 안양시", viewModel.uiState.value.selectedRegionName)
            assertEquals(1, viewModel.uiState.value.posts.size)
        }

    @Test
    fun `검색하면 품종 검색어를 적용하고 빈 검색 결과를 구분한다`() =
        runTest(dispatcher) {
            val repository =
                FakeLostPostRepository(
                    results =
                        mutableListOf(
                            successResult(),
                            LostPostListResult.Success(emptyList(), false, null),
                        ),
                )
            val viewModel = LostPostListViewModel(repository, FakeRegionStore())
            advanceUntilIdle()

            viewModel.onSearchQueryChange("  푸들  ")
            viewModel.search()
            advanceUntilIdle()

            assertEquals("푸들", repository.queries.last())
            assertTrue(viewModel.uiState.value.isSearchResult)
            assertTrue(viewModel.uiState.value.posts.isEmpty())
        }

    @Test
    fun `조회 실패는 빈 결과와 다른 오류 상태로 표시한다`() =
        runTest(dispatcher) {
            val repository =
                FakeLostPostRepository(
                    results = mutableListOf(LostPostListResult.Failure("목록 조회 실패")),
                )
            val viewModel = LostPostListViewModel(repository, FakeRegionStore())

            advanceUntilIdle()

            assertEquals("목록 조회 실패", viewModel.uiState.value.errorMessage)
            assertFalse(viewModel.uiState.value.isLoading)
        }

    @Test
    fun `전국을 고르면 서버에는 지역 코드를 생략하고 화면에는 전국이라고 보인다`() =
        runTest(dispatcher) {
            val repository = FakeLostPostRepository(results = mutableListOf(successResult()))
            val viewModel = LostPostListViewModel(repository, FakeRegionStore(SelectedRegion(code = "00", name = "전국")))

            advanceUntilIdle()

            assertEquals(listOf<String?>(null), repository.regionCodes)
            assertEquals("전국", viewModel.uiState.value.selectedRegionName)
        }

    private fun successResult(
        postId: Long = 1,
        hasNext: Boolean = false,
        nextCursor: String? = null,
    ) = LostPostListResult.Success(
        posts =
            listOf(
                LostPostSummary(
                    postId = postId,
                    type = "LOST",
                    source = "USER_POST",
                    species = "DOG",
                    breedName = "푸들",
                    sex = "FEMALE",
                    color = "갈색",
                    eventDate = "2026-09-08",
                    listedAt = "2026-09-08T12:00:00Z",
                    publicLocation = "서울특별시 강남구 역삼동",
                ),
            ),
        hasNext = hasNext,
        nextCursor = nextCursor,
    )

    private class FakeLostPostRepository(
        private val results: MutableList<LostPostListResult>,
    ) : LostPostRepository {
        val queries = mutableListOf<String?>()
        val regionCodes = mutableListOf<String?>()
        val filters = mutableListOf<PostListFilter>()
        val cursors = mutableListOf<String?>()

        override suspend fun getLostPosts(
            breedName: String?,
            regionCode: String?,
            filter: PostListFilter,
            cursor: String?,
        ): LostPostListResult {
            queries += breedName
            regionCodes += regionCode
            filters += filter
            cursors += cursor
            return results.removeAt(0)
        }
    }
}
