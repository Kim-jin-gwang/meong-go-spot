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
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InsightsViewModelTest {
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
    fun `저장된 지역으로 카드 묶음을 불러오고 지역이 바뀌면 다시 부른다`() =
        runTest(dispatcher) {
            val repository = FakeHomeRepository(HomeInsights(dailyIntake = DailySummary(1, "2026-09-21", 366, 127, "t")))
            val store = FakeRegionStore(SelectedRegion("11440", "서울특별시 마포구"))
            val viewModel = InsightsViewModel(repository, store)
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isLoading)
            assertEquals("서울특별시 마포구", viewModel.uiState.value.regionName)
            assertEquals(366, viewModel.uiState.value.insights?.dailyIntake?.animalCount)
            assertEquals(listOf("11440"), repository.insightRegionCodes)

            // 전국(00) 은 서버에 코드를 보내지 않는다
            store.select(SelectedRegion("00", "전국"))
            advanceUntilIdle()

            assertEquals(listOf("11440", null), repository.insightRegionCodes)
            assertEquals("전국", viewModel.uiState.value.regionName)
        }

    private class FakeHomeRepository(private val insights: HomeInsights?) : HomeRepository {
        val insightRegionCodes = mutableListOf<String?>()

        override suspend fun getInsights(regionCode: String?): HomeInsights? {
            insightRegionCodes += regionCode
            return insights
        }

        override suspend fun getShelteringPreview(regionCode: String?): List<LostPostSummary> = emptyList()

        override suspend fun getLostPreview(): List<LostPostSummary> = emptyList()
    }

    private class FakeRegionStore(initial: SelectedRegion?) : RegionStore {
        private val mutableSelectedRegion = MutableStateFlow(initial)
        override val selectedRegion: StateFlow<SelectedRegion?> = mutableSelectedRegion

        override fun select(region: SelectedRegion) {
            mutableSelectedRegion.value = region
        }

        override fun clear() {
            mutableSelectedRegion.value = null
        }
    }
}
