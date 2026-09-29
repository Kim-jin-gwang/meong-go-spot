package com.hotdog.meonggocuisine.feature.report.ui.lost

import com.hotdog.meonggocuisine.feature.community.data.RegionStore
import com.hotdog.meonggocuisine.feature.community.data.SelectedRegion
import com.hotdog.meonggocuisine.feature.report.data.LostPostCreateInput
import com.hotdog.meonggocuisine.feature.report.data.LostPostCreateRepository
import com.hotdog.meonggocuisine.feature.report.data.LostPostCreateResult
import com.hotdog.meonggocuisine.feature.report.data.PhotoInputInspector
import com.hotdog.meonggocuisine.feature.report.ui.DayPeriod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class LostPostCreateViewModelTest {
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
    fun `필수값이 부족하면 등록 API를 호출하지 않는다`() =
        runTest(dispatcher) {
            val repository = FakeLostPostCreateRepository()
            val viewModel = LostPostCreateViewModel(repository, AcceptAllPhotos(), FakeRegionStore())

            viewModel.submit()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.photoError != null)
            assertEquals(0, repository.inputs.size)
        }

    @Test
    fun `필수값을 다 채우기 전에는 등록 버튼이 잠기고 남은 칸을 알려 준다`() =
        runTest(dispatcher) {
            val viewModel = LostPostCreateViewModel(FakeLostPostCreateRepository(), AcceptAllPhotos(), FakeRegionStore())

            assertFalse(viewModel.uiState.value.canSubmit)
            // 지역은 저장된 시·군·구(마포구)로 미리 채워져 있어 빠진다.
            assertEquals(listOf("사진", "실종 날짜", "실종 시간", "실종 장소"), viewModel.uiState.value.submitBlockers)

            fillRequiredFields(viewModel)

            assertTrue(viewModel.uiState.value.canSubmit)
            assertTrue(viewModel.uiState.value.submitBlockers.isEmpty())
        }

    @Test
    fun `칸을 떠나기 전에는 비어 있어도 오류를 보이지 않고 떠나면 보인다`() =
        runTest(dispatcher) {
            val viewModel = LostPostCreateViewModel(FakeLostPostCreateRepository(), AcceptAllPhotos(), FakeRegionStore())

            viewModel.onEventPlaceChange("월")
            viewModel.onEventPlaceChange("")
            assertNull(viewModel.uiState.value.eventPlaceError)

            viewModel.onFieldLeave(LostPostField.EVENT_PLACE)
            assertEquals("실종 장소를 입력해 주세요.", viewModel.uiState.value.eventPlaceError)

            viewModel.onEventPlaceChange("월드컵북로")
            assertNull(viewModel.uiState.value.eventPlaceError)
        }

    @Test
    fun `날짜는 여덟 자리를 다 치면 바로 검사한다`() =
        runTest(dispatcher) {
            val viewModel = LostPostCreateViewModel(FakeLostPostCreateRepository(), AcceptAllPhotos(), FakeRegionStore())

            viewModel.onEventDateChange("2026130")
            assertNull(viewModel.uiState.value.eventDateError)

            viewModel.onEventDateChange("20261301")
            assertEquals("없는 날짜입니다. 월과 일을 확인해 주세요.", viewModel.uiState.value.eventDateError)
        }

    @Test
    fun `시가 12를 넘으면 칸을 떠나기 전에도 바로 오류를 보인다`() =
        runTest(dispatcher) {
            val viewModel = LostPostCreateViewModel(FakeLostPostCreateRepository(), AcceptAllPhotos(), FakeRegionStore())

            viewModel.onEventHourChange("6")
            assertNull(viewModel.uiState.value.eventTimeError)

            viewModel.onEventHourChange("13")
            assertEquals("시는 1~12 사이로 입력해 주세요.", viewModel.uiState.value.eventTimeError)

            viewModel.onEventHourChange("1")
            assertNull(viewModel.uiState.value.eventTimeError)
        }

    @Test
    fun `오후 6시 30분은 18시 30분으로 보낸다`() =
        runTest(dispatcher) {
            val repository = FakeLostPostCreateRepository(LostPostCreateResult.Success(postId = 129))
            val viewModel = LostPostCreateViewModel(repository, AcceptAllPhotos(), FakeRegionStore())

            fillRequiredFields(viewModel)
            viewModel.submit()
            advanceUntilIdle()

            assertEquals("18:30", repository.inputs.single().eventTime)
        }

    @Test
    fun `등록 성공 시 상세 화면 이동 이벤트를 보낸다`() =
        runTest(dispatcher) {
            val repository = FakeLostPostCreateRepository(LostPostCreateResult.Success(postId = 129))
            val viewModel = LostPostCreateViewModel(repository, AcceptAllPhotos(), FakeRegionStore())

            fillRequiredFields(viewModel)
            viewModel.submit()
            advanceUntilIdle()

            assertEquals(129, (viewModel.events.first() as LostPostCreateEvent.Created).postId)
            assertFalse(viewModel.uiState.value.isSubmitting)
            assertEquals("11440", repository.inputs.single().regionCode)
        }

    @Test
    fun `저장된 지역이 시·도 전체면 미리 채우지 않고 선택 전 제출을 막는다`() =
        runTest(dispatcher) {
            val repository = FakeLostPostCreateRepository()
            val store = FakeRegionStore(SelectedRegion("30", "대전광역시 전체"))
            val viewModel = LostPostCreateViewModel(repository, AcceptAllPhotos(), store)

            assertEquals(null, viewModel.uiState.value.selectedRegionCode)

            fillRequiredFields(viewModel)
            viewModel.submit()
            advanceUntilIdle()

            assertEquals("실종 지역을 선택해 주세요.", viewModel.uiState.value.regionError)
            assertEquals(0, repository.inputs.size)
        }

    @Test
    fun `화면에서 고른 시·군·구 코드를 등록 요청에 보낸다`() =
        runTest(dispatcher) {
            val repository = FakeLostPostCreateRepository()
            val viewModel = LostPostCreateViewModel(repository, AcceptAllPhotos(), FakeRegionStore(null))

            fillRequiredFields(viewModel)
            viewModel.onRegionSelect("30170", "대전광역시 서구")
            viewModel.submit()
            advanceUntilIdle()

            assertEquals("30170", repository.inputs.single().regionCode)
        }

    @Test
    fun `서버가 짚은 칸 오류는 그 칸 아래 보이고 고치면 지워진다`() =
        runTest(dispatcher) {
            val repository =
                FakeLostPostCreateRepository(
                    LostPostCreateResult.InvalidInput(
                        message = "입력값을 확인해 주세요.",
                        fieldErrors = mapOf("eventTime" to "실종 시각이 지금보다 뒤입니다."),
                    ),
                )
            val viewModel = LostPostCreateViewModel(repository, AcceptAllPhotos(), FakeRegionStore())

            fillRequiredFields(viewModel)
            viewModel.submit()
            advanceUntilIdle()

            assertEquals("실종 시각이 지금보다 뒤입니다.", viewModel.uiState.value.eventTimeError)
            assertEquals("입력값을 확인해 주세요.", viewModel.uiState.value.requestError)

            viewModel.onEventMinuteChange("29")
            assertNull(viewModel.uiState.value.eventTimeError)
        }

    @Test
    fun `서버가 사진을 거부하면 사진 칸과 제출 단계 둘 다에 알린다`() =
        runTest(dispatcher) {
            val repository = FakeLostPostCreateRepository(LostPostCreateResult.PhotoInvalid("처리할 수 없는 사진입니다."))
            val viewModel = LostPostCreateViewModel(repository, AcceptAllPhotos(), FakeRegionStore())

            fillRequiredFields(viewModel)
            viewModel.submit()
            advanceUntilIdle()

            assertEquals("처리할 수 없는 사진입니다.", viewModel.uiState.value.photoError)
            assertEquals(
                "처리할 수 없는 사진입니다. 사진 등록 단계로 돌아가 사진을 확인해 주세요.",
                viewModel.uiState.value.requestError,
            )
            assertFalse(viewModel.uiState.value.isSubmitting)
        }

    @Test
    fun `요청 실패 후 입력값과 선택 사진을 유지한다`() =
        runTest(dispatcher) {
            val repository = FakeLostPostCreateRepository(LostPostCreateResult.Failure("등록 실패"))
            val viewModel = LostPostCreateViewModel(repository, AcceptAllPhotos(), FakeRegionStore())

            fillRequiredFields(viewModel)
            viewModel.onNameChange("망고")
            viewModel.submit()
            advanceUntilIdle()

            assertEquals("망고", viewModel.uiState.value.name)
            assertEquals(listOf("content://photo/1"), viewModel.uiState.value.photoUris)
            assertEquals("등록 실패", viewModel.uiState.value.requestError)
        }

    @Test
    fun `글자 칸은 서버 한도에서 잘라 넣는다`() =
        runTest(dispatcher) {
            val viewModel = LostPostCreateViewModel(FakeLostPostCreateRepository(), AcceptAllPhotos(), FakeRegionStore())

            viewModel.onNameChange("가".repeat(60))
            viewModel.onFeatureTextChange("나".repeat(2500))

            assertEquals(50, viewModel.uiState.value.name.length)
            assertEquals(2000, viewModel.uiState.value.featureText.length)
        }

    // 사진 검사는 코루틴에서 돌아간다. 이어지는 검증이 사진을 보게 하려면 여기서 돌려 준다.
    private fun TestScope.fillRequiredFields(viewModel: LostPostCreateViewModel) {
        viewModel.addPhotos(listOf("content://photo/1"))
        viewModel.onEventDateChange(LocalDate.now().toString())
        viewModel.onEventPeriodChange(DayPeriod.PM)
        viewModel.onEventHourChange("6")
        viewModel.onEventMinuteChange("30")
        viewModel.onEventPlaceChange("서울특별시 마포구 월드컵북로")
        advanceUntilIdle()
    }

    private class FakeLostPostCreateRepository(
        private val result: LostPostCreateResult = LostPostCreateResult.Success(1),
    ) : LostPostCreateRepository {
        val inputs = mutableListOf<LostPostCreateInput>()

        override suspend fun createLostPost(input: LostPostCreateInput): LostPostCreateResult {
            inputs += input
            return result
        }
    }

    private class FakeRegionStore(
        initial: SelectedRegion? = SelectedRegion("11440", "서울특별시 마포구"),
    ) : RegionStore {
        private val mutableSelectedRegion = MutableStateFlow(initial)
        override val selectedRegion = mutableSelectedRegion

        override fun select(region: SelectedRegion) {
            mutableSelectedRegion.value = region
        }

        override fun clear() {
            mutableSelectedRegion.value = null
        }
    }

    @Test
    fun `넣지 못한 사진이 있으면 이유를 안내 창으로 올리고 확인하면 닫힌다`() =
        runTest(dispatcher) {
            val viewModel = LostPostCreateViewModel(FakeLostPostCreateRepository(), RejectHeic(), FakeRegionStore())

            viewModel.addPhotos(listOf("content://a.jpg", "content://b.heic", "content://c.heic"))
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(listOf("content://a.jpg"), state.photoUris)
            assertEquals(3, state.photoRejectionNotice?.pickedCount)
            assertEquals(2, state.photoRejectionNotice?.rejectedCount)
            assertEquals(listOf("JPEG 또는 PNG 사진만 등록할 수 있어요. (2장)"), state.photoRejectionNotice?.lines)
            assertEquals("JPEG 또는 PNG 사진만 등록할 수 있어요.", state.photoError)

            viewModel.dismissPhotoRejectionNotice()
            assertNull(viewModel.uiState.value.photoRejectionNotice)

            viewModel.addPhotos(listOf("content://d.jpg"))
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.photoRejectionNotice)
        }

    private class RejectHeic : PhotoInputInspector {
        override suspend fun rejection(uriText: String): String? = if (uriText.endsWith(".heic")) "JPEG 또는 PNG 사진만 등록할 수 있어요." else null
    }

    private class AcceptAllPhotos : PhotoInputInspector {
        override suspend fun rejection(uriText: String): String? = null
    }
}
