package com.hotdog.meonggocuisine.core.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateGateViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val today = LocalDate.of(2026, 9, 21)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `서버 값으로 권고를 정하고 나중에를 누르면 오늘 날짜를 기억한다`() =
        runTest(dispatcher) {
            val store = FakePromptStore()
            val viewModel = viewModel(version(latest = 7, min = 5), store, current = 6)
            advanceUntilIdle()

            assertEquals(UpdatePrompt.RECOMMEND, viewModel.state.value.prompt)
            assertEquals(STORE, viewModel.state.value.storeUrl)

            viewModel.dismissRecommendation()

            assertEquals(UpdatePrompt.NONE, viewModel.state.value.prompt)
            assertEquals(today, store.dismissedOn())
        }

    @Test
    fun `오늘 이미 닫았으면 권고를 다시 보이지 않지만 강제는 보인다`() =
        runTest(dispatcher) {
            val store = FakePromptStore(dismissed = today)

            val recommended = viewModel(version(latest = 7, min = 5), store, current = 6)
            advanceUntilIdle()
            assertEquals(UpdatePrompt.NONE, recommended.state.value.prompt)

            val forced = viewModel(version(latest = 7, min = 5), store, current = 4)
            advanceUntilIdle()
            assertEquals(UpdatePrompt.FORCE, forced.state.value.prompt)
        }

    @Test
    fun `조회에 실패하면 안내 없이 들어간다`() =
        runTest(dispatcher) {
            val viewModel = viewModel(null, FakePromptStore(), current = 1)
            advanceUntilIdle()

            assertEquals(UpdatePrompt.NONE, viewModel.state.value.prompt)
            assertNull(viewModel.state.value.storeUrl)
        }

    private fun viewModel(
        response: AppVersionResponse?,
        store: UpdatePromptStore,
        current: Int,
    ) = UpdateGateViewModel(
        repository =
            object : AppVersionRepository {
                override suspend fun fetch(): AppVersionResponse? = response
            },
        promptStore = store,
        currentVersionCode = current,
        today = { today },
    )

    private fun version(
        latest: Int,
        min: Int,
    ) = AppVersionResponse("android", latest, min, STORE)

    private class FakePromptStore(private var dismissed: LocalDate? = null) : UpdatePromptStore {
        override fun dismissedOn(): LocalDate? = dismissed

        override fun markDismissed(date: LocalDate) {
            dismissed = date
        }
    }

    private companion object {
        const val STORE = "https://m.onestore.co.kr/v2/ko-kr/app/0001009297"
    }
}
