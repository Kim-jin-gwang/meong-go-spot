package com.hotdog.meonggocuisine.feature.adoption.ui

import com.hotdog.meonggocuisine.feature.adoption.data.AdoptionAnimal
import com.hotdog.meonggocuisine.feature.adoption.data.AdoptionFavoriteResult
import com.hotdog.meonggocuisine.feature.adoption.data.AdoptionListResult
import com.hotdog.meonggocuisine.feature.adoption.data.AdoptionRepository
import com.hotdog.meonggocuisine.feature.adoption.data.AdoptionSwipeListResult
import com.hotdog.meonggocuisine.feature.adoption.data.AdoptionSwipeRecord
import com.hotdog.meonggocuisine.feature.adoption.data.Sex
import com.hotdog.meonggocuisine.feature.adoption.data.SexFilter
import com.hotdog.meonggocuisine.feature.adoption.data.Species
import com.hotdog.meonggocuisine.feature.adoption.data.SpeciesFilter
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
class AdoptionViewModelTest {
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
    fun `축종으로 목록을 불러온다`() =
        runTest(dispatcher) {
            val repository = FakeAdoptionRepository(pages = listOf(page(animal(1L), animal(2L))))
            val viewModel = AdoptionViewModel(repository)

            advanceUntilIdle()

            assertEquals(listOf(1L, 2L), viewModel.uiState.value.animals.map { it.postId })
            assertNull(repository.lastRegionCode)
            assertEquals(SpeciesFilter.ALL, repository.lastFilter)
            assertNull(repository.lastCursor)
        }

    @Test
    fun `화면이 보일 때 다시 불러도 두 번 부르지 않는다`() =
        runTest(dispatcher) {
            val repository = FakeAdoptionRepository(pages = listOf(page(animal(1L))))
            val viewModel = AdoptionViewModel(repository)

            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(1, repository.listCallCount)
            assertNull(viewModel.uiState.value.errorMessage)
            assertEquals(listOf(1L), viewModel.uiState.value.animals.map { it.postId })
        }

    @Test
    fun `카드를 넘기면 다음 동물이 현재가 된다`() =
        runTest(dispatcher) {
            val repository = FakeAdoptionRepository(pages = listOf(page(animal(1L), animal(2L))))
            val viewModel = AdoptionViewModel(repository)
            advanceUntilIdle()

            viewModel.onNext()

            assertEquals(2L, viewModel.uiState.value.current?.postId)
            assertEquals(1, viewModel.uiState.value.currentIndex)
        }

    @Test
    fun `남은 카드가 적어지면 다음 페이지를 미리 받는다`() =
        runTest(dispatcher) {
            val repository =
                FakeAdoptionRepository(
                    pages =
                        listOf(
                            page(animal(1L), animal(2L), animal(3L), animal(4L), hasNext = true, cursor = "c1"),
                            page(animal(5L)),
                        ),
                )
            val viewModel = AdoptionViewModel(repository)
            advanceUntilIdle()
            assertEquals(1, repository.listCallCount)

            viewModel.onNext()
            advanceUntilIdle()

            assertEquals(2, repository.listCallCount)
            assertEquals("c1", repository.lastCursor)
            assertEquals(listOf(1L, 2L, 3L, 4L, 5L), viewModel.uiState.value.animals.map { it.postId })
        }

    @Test
    fun `커서가 만료되면 첫 페이지부터 다시 부른다`() =
        runTest(dispatcher) {
            val repository =
                FakeAdoptionRepository(
                    pages = listOf(page(animal(1L), animal(2L), hasNext = true, cursor = "stale")),
                    cursorExpires = true,
                )
            val viewModel = AdoptionViewModel(repository)
            advanceUntilIdle()

            viewModel.onNext()
            advanceUntilIdle()

            assertNull(repository.lastCursor)
            // 첫 페이지를 다시 받되 방금 넘긴 1번은 빼고 올린다.
            assertEquals(listOf(2L), viewModel.uiState.value.animals.map { it.postId })
        }

    @Test
    fun `넘긴 아이는 다시 받아 와도 카드로 올리지 않는다`() =
        runTest(dispatcher) {
            val repository = FakeAdoptionRepository(pages = listOf(page(animal(1L), animal(2L), animal(3L))))
            val viewModel = AdoptionViewModel(repository)
            advanceUntilIdle()

            viewModel.onNext()
            viewModel.onNext()
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(listOf(3L), viewModel.uiState.value.animals.map { it.postId })
            assertEquals(3L, viewModel.uiState.value.current?.postId)
        }

    @Test
    fun `카드를 넘기면 서버에 넘김을 적는다`() =
        runTest(dispatcher) {
            val repository = FakeAdoptionRepository(pages = listOf(page(animal(1L), animal(2L))))
            val viewModel = AdoptionViewModel(repository)
            advanceUntilIdle()

            viewModel.onNext()
            viewModel.onNext()
            advanceUntilIdle()

            assertEquals(listOf(1L, 2L), repository.recordedSwipes)
        }

    @Test
    fun `히스토리의 하트는 서버가 아는 찜 상태다`() =
        runTest(dispatcher) {
            val repository = FakeAdoptionRepository(pages = listOf(page(animal(1L))))
            repository.swipeRecords =
                listOf(
                    record(animal(2L, favorited = true), favorited = true),
                    record(animal(3L), favorited = false),
                )
            val viewModel = AdoptionViewModel(repository)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(listOf(2L, 3L), state.passed.map { it.animal.postId })
            assertEquals(listOf(2L), state.passedFavorites.map { it.animal.postId })
        }

    @Test
    fun `걸러 낸 페이지가 비면 다음 페이지를 이어 받는다`() =
        runTest(dispatcher) {
            val repository =
                FakeAdoptionRepository(
                    pages =
                        listOf(
                            page(animal(1L), animal(2L), animal(3L), animal(4L), animal(5L), hasNext = true, cursor = "c1"),
                            page(animal(1L), animal(2L), hasNext = true, cursor = "c2"),
                            page(animal(6L)),
                        ),
                )
            val viewModel = AdoptionViewModel(repository)
            advanceUntilIdle()
            viewModel.onNext()
            viewModel.onNext()
            advanceUntilIdle()

            // 두 번째 쪽은 이미 넘긴 1·2번뿐이라 통째로 걸러진다. 거기서 멈추지 않고 6번까지 간다.
            assertEquals(3, repository.listCallCount)
            assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), viewModel.uiState.value.animals.map { it.postId })
            assertFalse(viewModel.uiState.value.isExhausted)
        }

    @Test
    fun `더 받을 페이지가 없을 때만 모두 확인으로 본다`() =
        runTest(dispatcher) {
            val repository = FakeAdoptionRepository(pages = listOf(page(animal(1L))))
            val viewModel = AdoptionViewModel(repository)
            advanceUntilIdle()

            viewModel.onNext()

            assertTrue(viewModel.uiState.value.isExhausted)
            assertNull(viewModel.uiState.value.current)
        }

    @Test
    fun `필터를 바꾸면 커서 없이 처음부터 다시 부른다`() =
        runTest(dispatcher) {
            val repository = FakeAdoptionRepository(pages = listOf(page(animal(1L), animal(2L))))
            val viewModel = AdoptionViewModel(repository)
            advanceUntilIdle()
            viewModel.onNext()

            viewModel.onFilterChange(SpeciesFilter.CAT, SexFilter.FEMALE)
            advanceUntilIdle()

            assertEquals(SpeciesFilter.CAT, repository.lastFilter)
            assertEquals(SexFilter.FEMALE, repository.lastSex)
            assertNull(repository.lastCursor)
            assertEquals(0, viewModel.uiState.value.currentIndex)
        }

    @Test
    fun `같은 조건을 다시 적용하면 부르지 않는다`() =
        runTest(dispatcher) {
            val repository = FakeAdoptionRepository(pages = listOf(page(animal(1L))))
            val viewModel = AdoptionViewModel(repository)
            advanceUntilIdle()
            assertEquals(1, repository.listCallCount)

            viewModel.onFilterChange(SpeciesFilter.ALL, SexFilter.ALL)
            advanceUntilIdle()

            assertEquals(1, repository.listCallCount)
        }

    @Test
    fun `관심을 누르면 화면을 먼저 바꾸고 서버에 보낸다`() =
        runTest(dispatcher) {
            val repository = FakeAdoptionRepository(pages = listOf(page(animal(1L))))
            val viewModel = AdoptionViewModel(repository)
            advanceUntilIdle()

            viewModel.onFavoriteToggle()
            assertTrue(viewModel.uiState.value.current!!.favorited)

            advanceUntilIdle()
            assertEquals(listOf(1L), repository.addedFavorites)
        }

    @Test
    fun `이미 관심인 동물을 누르면 해제를 보낸다`() =
        runTest(dispatcher) {
            val repository = FakeAdoptionRepository(pages = listOf(page(animal(1L, favorited = true))))
            val viewModel = AdoptionViewModel(repository)
            advanceUntilIdle()

            viewModel.onFavoriteToggle()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.current!!.favorited)
            assertEquals(listOf(1L), repository.removedFavorites)
        }

    @Test
    fun `관심 저장이 실패하면 표시를 되돌리고 알린다`() =
        runTest(dispatcher) {
            val repository =
                FakeAdoptionRepository(
                    pages = listOf(page(animal(1L))),
                    favoriteResult = AdoptionFavoriteResult.Failure("저장하지 못했습니다."),
                )
            val viewModel = AdoptionViewModel(repository)
            advanceUntilIdle()

            viewModel.onFavoriteToggle()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.current!!.favorited)
            assertEquals("저장하지 못했습니다.", viewModel.uiState.value.favoriteErrorMessage)
        }

    @Test
    fun `후보 자격을 잃은 동물은 카드에서 내린다`() =
        runTest(dispatcher) {
            val repository =
                FakeAdoptionRepository(
                    pages = listOf(page(animal(1L), animal(2L))),
                    favoriteResult = AdoptionFavoriteResult.NoLongerAvailable,
                )
            val viewModel = AdoptionViewModel(repository)
            advanceUntilIdle()

            viewModel.onFavoriteToggle()
            advanceUntilIdle()

            assertEquals(listOf(2L), viewModel.uiState.value.animals.map { it.postId })
            assertEquals(2L, viewModel.uiState.value.current?.postId)
        }

    @Test
    fun `조회에 실패하면 오류를 보여 준다`() =
        runTest(dispatcher) {
            val repository = FakeAdoptionRepository(listFailure = "목록을 불러오지 못했습니다.")
            val viewModel = AdoptionViewModel(repository)

            advanceUntilIdle()

            assertEquals("목록을 불러오지 못했습니다.", viewModel.uiState.value.errorMessage)
            assertFalse(viewModel.uiState.value.isLoading)
        }

    @Test
    fun `지역 코드를 보내지 않아 언제나 전국을 부른다`() =
        runTest(dispatcher) {
            val repository = FakeAdoptionRepository(listOf(page(animal(1L), hasNext = true, cursor = "c1"), page(animal(2L))))
            val viewModel = AdoptionViewModel(repository)
            advanceUntilIdle()

            viewModel.onNext()
            advanceUntilIdle()

            assertEquals(2, repository.listCallCount)
            assertNull(repository.lastRegionCode)
        }

    private fun page(
        vararg animals: AdoptionAnimal,
        hasNext: Boolean = false,
        cursor: String? = null,
    ) = AdoptionListResult.Success(animals.toList(), hasNext, cursor)

    private fun record(
        animal: AdoptionAnimal,
        favorited: Boolean,
    ) = AdoptionSwipeRecord(
        animal = animal,
        swipedAt = "2026-09-25T03:00:00Z",
        favorited = favorited,
        available = true,
    )

    private fun animal(
        id: Long,
        days: Long = 100L,
        favorited: Boolean = false,
    ) = AdoptionAnimal(
        postId = id,
        species = Species.DOG,
        breedName = "믹스견",
        sex = Sex.UNKNOWN,
        color = "갈색",
        publicLocation = "서울특별시 강북구",
        thumbnailUrl = null,
        noticeEndDate = "2026-06-09",
        daysSinceNoticeEnd = days,
        lastSyncedAt = "2026-09-17T01:30:00Z",
        favorited = favorited,
    )
}

/** 페이지를 순서대로 돌려준다. 서버가 정렬해 준 순서를 그대로 쓰는지 보려고 다시 정렬하지 않는다. */
private class FakeAdoptionRepository(
    private val pages: List<AdoptionListResult.Success> = emptyList(),
    private val listFailure: String? = null,
    private val cursorExpires: Boolean = false,
    private val favoriteResult: AdoptionFavoriteResult = AdoptionFavoriteResult.Success,
) : AdoptionRepository {
    var listCallCount = 0
        private set
    var lastRegionCode: String? = null
        private set
    var lastFilter: SpeciesFilter? = null
        private set
    var lastCursor: String? = null
        private set
    val addedFavorites = mutableListOf<Long>()
    val removedFavorites = mutableListOf<Long>()

    var lastSex: SexFilter? = null
        private set
    val recordedSwipes = mutableListOf<Long>()
    var swipeRecords: List<AdoptionSwipeRecord> = emptyList()

    override suspend fun getWaitingAnimals(
        regionCode: String?,
        species: SpeciesFilter,
        sex: SexFilter,
        cursor: String?,
    ): AdoptionListResult {
        lastRegionCode = regionCode
        lastFilter = species
        lastSex = sex
        lastCursor = cursor
        listFailure?.let { return AdoptionListResult.Failure(it) }
        if (cursor != null && cursorExpires) return AdoptionListResult.CursorExpired
        val index = if (cursor == null) 0 else listCallCount
        listCallCount++
        val page =
            pages.getOrElse(index) { AdoptionListResult.Success(emptyList(), hasNext = false, nextCursor = null) }
        // 서버가 하는 일을 그대로 흉내 낸다 — 이미 넘긴 동물은 빼고 준다 (AD1, 2026-09-25).
        return page.copy(animals = page.animals.filterNot { it.postId in recordedSwipes })
    }

    /** 서버가 넘긴 동물을 빼 주듯, 기록한 postId 를 다음 쪽에서 제외한다. */
    override suspend fun recordSwipe(postId: Long) {
        recordedSwipes += postId
    }

    override suspend fun getSwipes(cursor: String?): AdoptionSwipeListResult =
        AdoptionSwipeListResult.Success(swipeRecords, hasNext = false, nextCursor = null)

    override suspend fun addFavorite(postId: Long): AdoptionFavoriteResult {
        addedFavorites += postId
        return favoriteResult
    }

    override suspend fun removeFavorite(postId: Long): AdoptionFavoriteResult {
        removedFavorites += postId
        return favoriteResult
    }
}
