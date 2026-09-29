package com.hotdog.meonggocuisine.feature.home.ui

import com.hotdog.meonggocuisine.feature.community.data.LostPostSummary
import com.hotdog.meonggocuisine.feature.community.data.RegionStore
import com.hotdog.meonggocuisine.feature.community.data.SelectedRegion
import com.hotdog.meonggocuisine.feature.home.data.DailySummary
import com.hotdog.meonggocuisine.feature.home.data.HomeInsights
import com.hotdog.meonggocuisine.feature.home.data.HomeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
class HomeViewModelTest {
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
    fun `인사이트 호출이 실패해도 로딩을 끝내고 카드는 비워 둔다`() =
        runTest(dispatcher) {
            val repository = FakeHomeRepository(insights = null)
            val viewModel = HomeViewModel(repository, FakeRegionStore())

            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isLoading)
            assertNull(viewModel.uiState.value.insights)
            assertNull(viewModel.uiState.value.summary)
            assertEquals(listOf<String?>(null), repository.insightRegionCodes)
        }

    @Test
    fun `선택한 지역 코드로 보호 중 미리보기를 불러온다`() =
        runTest(dispatcher) {
            val repository =
                FakeHomeRepository(
                    insights = HomeInsights(dailyIntake = summary()),
                    shelteringPosts = listOf(post(7001), post(7002)),
                    lostPosts = listOf(post(8001)),
                )
            val regionStore = FakeRegionStore(SelectedRegion(code = "11440", name = "서울특별시 마포구"))
            val viewModel = HomeViewModel(repository, regionStore)

            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state.hasRegion)
            assertEquals("서울특별시 마포구", state.regionName)
            assertEquals(listOf("11440"), repository.regionCodes)
            assertEquals(listOf("11440"), repository.insightRegionCodes)
            assertEquals(2, state.shelteringPosts.size)
            assertEquals(1, state.lostPosts.size)
            assertEquals(3, state.summary?.animalCount)
        }

    @Test
    fun `같은 지역을 불러오는 중이면 중복으로 요청하지 않는다`() =
        runTest(dispatcher) {
            val repository = FakeHomeRepository()
            val regionStore = FakeRegionStore(SelectedRegion(code = "11440", name = "서울특별시 마포구"))
            val viewModel = HomeViewModel(repository, regionStore)

            // 화면이 다시 보일 때 호출되는 refresh 다. init 요청이 아직 끝나지 않았다.
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(listOf("11440"), repository.regionCodes)
        }

    @Test
    fun `요청 중에 지역이 바뀌면 새 지역으로 다시 불러온다`() =
        runTest(dispatcher) {
            val repository = FakeHomeRepository()
            val regionStore = FakeRegionStore(SelectedRegion(code = "11440", name = "서울특별시 마포구"))
            val viewModel = HomeViewModel(repository, regionStore)

            // 지역 선택 화면에서 지역을 바꾸고 돌아온 상황이다.
            regionStore.select(SelectedRegion(code = "41830", name = "경기도 양평군"))
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(listOf("41830"), repository.regionCodes)
            assertEquals("경기도 양평군", viewModel.uiState.value.regionName)
        }

    @Test
    fun `다른 화면에서 지역을 바꾸면 홈이 다시 보이기 전에도 새 지역으로 다시 불러온다`() =
        runTest(dispatcher) {
            val repository = FakeHomeRepository()
            val regionStore = FakeRegionStore(SelectedRegion(code = "11110", name = "서울특별시 종로구"))
            val viewModel = HomeViewModel(repository, regionStore)
            advanceUntilIdle()
            assertEquals("서울특별시 종로구", viewModel.uiState.value.regionName)

            // 보호 목록 탭에서 지역을 바꿨다 — 홈 화면의 refresh() 는 부르지 않는다.
            regionStore.select(SelectedRegion(code = "11170", name = "서울특별시 용산구"))
            advanceUntilIdle()

            assertEquals(listOf("11110", "11170"), repository.regionCodes)
            assertEquals("서울특별시 용산구", viewModel.uiState.value.regionName)
        }

    @Test
    fun `전국을 고르면 미리보기와 인사이트를 지역 코드 없이 부르고, 지역이 없으면 미리보기를 부르지 않는다`() =
        runTest(dispatcher) {
            val nationwide = FakeHomeRepository()
            HomeViewModel(nationwide, FakeRegionStore(SelectedRegion(code = "00", name = "전국")))
            advanceUntilIdle()
            assertEquals(listOf<String?>(null), nationwide.regionCodes)
            assertEquals(listOf<String?>(null), nationwide.insightRegionCodes)

            val unselected = FakeHomeRepository()
            HomeViewModel(unselected, FakeRegionStore())
            advanceUntilIdle()
            assertTrue(unselected.regionCodes.isEmpty())
            assertEquals(listOf<String?>(null), unselected.insightRegionCodes)
        }

    private fun summary() =
        DailySummary(
            ingestionRunId = 42,
            summaryDate = "2026-09-14",
            animalCount = 3,
            shelterCount = 2,
            completedAt = "2026-09-14T23:20:00Z",
        )

    private fun post(id: Long) =
        LostPostSummary(
            postId = id,
            type = "SHELTERING",
            source = "SHELTER",
            species = "DOG",
            breedName = "푸들",
            sex = "FEMALE",
            color = "갈색",
            eventDate = "2026-09-13",
            listedAt = "2026-09-13T12:00:00Z",
            publicLocation = "서울특별시 마포구",
        )

    private class FakeHomeRepository(
        private val insights: HomeInsights? = null,
        private val shelteringPosts: List<LostPostSummary> = emptyList(),
        private val lostPosts: List<LostPostSummary> = emptyList(),
    ) : HomeRepository {
        val regionCodes = mutableListOf<String?>()
        val insightRegionCodes = mutableListOf<String?>()

        override suspend fun getInsights(regionCode: String?): HomeInsights? {
            insightRegionCodes += regionCode
            return insights
        }

        override suspend fun getShelteringPreview(regionCode: String?): List<LostPostSummary> {
            regionCodes += regionCode
            return shelteringPosts
        }

        override suspend fun getLostPreview(): List<LostPostSummary> = lostPosts
    }

    private class FakeRegionStore(
        initialRegion: SelectedRegion? = null,
    ) : RegionStore {
        private val mutableSelectedRegion = MutableStateFlow(initialRegion)

        override val selectedRegion: StateFlow<SelectedRegion?> = mutableSelectedRegion

        override fun select(region: SelectedRegion) {
            mutableSelectedRegion.value = region
        }

        override fun clear() {
            mutableSelectedRegion.value = null
        }
    }
}
