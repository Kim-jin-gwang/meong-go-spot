package com.hotdog.meonggocuisine.feature.post.ui.mypost

import com.hotdog.meonggocuisine.feature.post.data.MyPostListResult
import com.hotdog.meonggocuisine.feature.post.data.MyPostRepository
import com.hotdog.meonggocuisine.feature.post.data.MyPostSummary
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MyPostListViewModelTest {
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
    fun `초기 진입 시 전체 필터로 내 게시물을 불러온다`() =
        runTest(dispatcher) {
            val repository = FakeMyPostRepository(results = mutableListOf(successResult(myPost(1))))
            val viewModel = MyPostListViewModel(repository)

            advanceUntilIdle()

            assertEquals(1, viewModel.uiState.value.posts.size)
            assertFalse(viewModel.uiState.value.isLoading)
            assertNull(repository.statuses.single())
        }

    @Test
    fun `필터를 전환하면 목록과 커서를 초기화하고 status 쿼리로 다시 조회한다`() =
        runTest(dispatcher) {
            val repository =
                FakeMyPostRepository(
                    results =
                        mutableListOf(
                            successResult(myPost(1), hasNext = true, nextCursor = "cursor-1"),
                            successResult(myPost(2, status = "CLOSED")),
                        ),
                )
            val viewModel = MyPostListViewModel(repository)
            advanceUntilIdle()

            viewModel.onFilterSelect(MyPostStatusFilter.CLOSED)
            advanceUntilIdle()

            assertEquals("CLOSED", repository.statuses.last())
            assertNull(repository.cursors.last())
            assertEquals(listOf(2L), viewModel.uiState.value.posts.map(MyPostSummary::postId))
            assertNull(viewModel.uiState.value.nextCursor)
            assertFalse(viewModel.uiState.value.hasNext)
        }

    @Test
    fun `같은 필터를 다시 선택하면 재조회하지 않는다`() =
        runTest(dispatcher) {
            val repository = FakeMyPostRepository(results = mutableListOf(successResult(myPost(1))))
            val viewModel = MyPostListViewModel(repository)
            advanceUntilIdle()

            viewModel.onFilterSelect(MyPostStatusFilter.ALL)
            advanceUntilIdle()

            assertEquals(1, repository.statuses.size)
        }

    @Test
    fun `커서 페이징으로 다음 페이지를 기존 목록 뒤에 병합한다`() =
        runTest(dispatcher) {
            val repository =
                FakeMyPostRepository(
                    results =
                        mutableListOf(
                            successResult(myPost(1), hasNext = true, nextCursor = "cursor-1"),
                            successResult(myPost(2)),
                        ),
                )
            val viewModel = MyPostListViewModel(repository)
            advanceUntilIdle()

            viewModel.loadMore()
            advanceUntilIdle()

            assertEquals("cursor-1", repository.cursors.last())
            assertEquals(listOf(1L, 2L), viewModel.uiState.value.posts.map(MyPostSummary::postId))
            assertFalse(viewModel.uiState.value.hasNext)
        }

    @Test
    fun `다음 페이지가 없으면 loadMore가 조회하지 않는다`() =
        runTest(dispatcher) {
            val repository = FakeMyPostRepository(results = mutableListOf(successResult(myPost(1))))
            val viewModel = MyPostListViewModel(repository)
            advanceUntilIdle()

            viewModel.loadMore()
            advanceUntilIdle()

            assertEquals(1, repository.statuses.size)
        }

    @Test
    fun `빈 목록은 오류가 아닌 빈 상태로 표시한다`() =
        runTest(dispatcher) {
            val repository = FakeMyPostRepository(results = mutableListOf(successResult()))
            val viewModel = MyPostListViewModel(repository)

            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.posts.isEmpty())
            assertNull(viewModel.uiState.value.errorMessage)
            assertFalse(viewModel.uiState.value.isLoading)
        }

    @Test
    fun `조회 실패는 빈 목록과 다른 오류 상태로 표시한다`() =
        runTest(dispatcher) {
            val repository =
                FakeMyPostRepository(
                    results = mutableListOf(MyPostListResult.Failure("목록 조회 실패")),
                )
            val viewModel = MyPostListViewModel(repository)

            advanceUntilIdle()

            assertEquals("목록 조회 실패", viewModel.uiState.value.errorMessage)
            assertFalse(viewModel.uiState.value.requiresLogin)
            assertFalse(viewModel.uiState.value.isLoading)
        }

    @Test
    fun `401 응답은 로그인 필요 상태로 구분한다`() =
        runTest(dispatcher) {
            val repository =
                FakeMyPostRepository(
                    results = mutableListOf(MyPostListResult.Unauthorized("로그인이 필요합니다.")),
                )
            val viewModel = MyPostListViewModel(repository)

            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.requiresLogin)
            assertEquals("로그인이 필요합니다.", viewModel.uiState.value.errorMessage)
            assertFalse(viewModel.uiState.value.hasNext)
        }

    @Test
    fun `추가 페이지 조회 실패 시 기존 목록을 유지한다`() =
        runTest(dispatcher) {
            val repository =
                FakeMyPostRepository(
                    results =
                        mutableListOf(
                            successResult(myPost(1), hasNext = true, nextCursor = "cursor-1"),
                            MyPostListResult.Failure("추가 조회 실패"),
                        ),
                )
            val viewModel = MyPostListViewModel(repository)
            advanceUntilIdle()

            viewModel.loadMore()
            advanceUntilIdle()

            assertEquals(listOf(1L), viewModel.uiState.value.posts.map(MyPostSummary::postId))
            assertEquals("추가 조회 실패", viewModel.uiState.value.errorMessage)
            assertTrue(viewModel.uiState.value.hasNext)
        }

    private fun successResult(
        vararg posts: MyPostSummary,
        hasNext: Boolean = false,
        nextCursor: String? = null,
    ) = MyPostListResult.Success(
        posts = posts.toList(),
        hasNext = hasNext,
        nextCursor = nextCursor,
    )

    private fun myPost(
        id: Long,
        status: String = "ACTIVE",
    ) = MyPostSummary(
        postId = id,
        type = "LOST",
        source = "USER_POST",
        status = status,
        version = 1,
        name = "망고",
        species = "DOG",
        eventDate = "2026-08-25",
        listedAt = "2026-08-25T11:00:00Z",
        publicLocation = "서울특별시 강남구",
        updatedAt = "2026-08-27T05:40:00Z",
    )

    private class FakeMyPostRepository(
        private val results: MutableList<MyPostListResult>,
    ) : MyPostRepository {
        val statuses = mutableListOf<String?>()
        val cursors = mutableListOf<String?>()

        override suspend fun getMyPosts(
            status: String?,
            cursor: String?,
        ): MyPostListResult {
            statuses += status
            cursors += cursor
            return results.removeFirst()
        }
    }
}
