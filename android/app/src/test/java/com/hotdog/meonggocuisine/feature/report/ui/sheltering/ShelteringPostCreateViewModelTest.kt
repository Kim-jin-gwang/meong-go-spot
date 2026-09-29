package com.hotdog.meonggocuisine.feature.report.ui.sheltering

import com.hotdog.meonggocuisine.feature.community.data.RegionStore
import com.hotdog.meonggocuisine.feature.community.data.SelectedRegion
import com.hotdog.meonggocuisine.feature.report.data.PhotoInputInspector
import com.hotdog.meonggocuisine.feature.report.data.ShelteringPostCreateInput
import com.hotdog.meonggocuisine.feature.report.data.ShelteringPostCreateRepository
import com.hotdog.meonggocuisine.feature.report.data.ShelteringPostCreateResult
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
class ShelteringPostCreateViewModelTest {
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
    fun `등록 성공 시 보호 목록 이동 이벤트를 보낸다`() =
        runTest(dispatcher) {
            val repository = FakeShelteringPostCreateRepository(ShelteringPostCreateResult.Success(postId = 130))
            val viewModel = ShelteringPostCreateViewModel(repository, AcceptAllPhotos(), FakeRegionStore())

            fillRequiredFields(viewModel)
            viewModel.submit()
            advanceUntilIdle()

            assertEquals(ShelteringPostCreateEvent.Created, viewModel.events.first())
            assertFalse(viewModel.uiState.value.isSubmitting)
            assertEquals("14:20", repository.inputs.single().foundTime)
            assertEquals("서울특별시 마포구 자택", repository.inputs.single().currentProtectionPlace)
        }

    @Test
    fun `필수값을 다 채우기 전에는 등록 버튼이 잠기고 남은 칸을 알려 준다`() =
        runTest(dispatcher) {
            val viewModel =
                ShelteringPostCreateViewModel(
                    FakeShelteringPostCreateRepository(ShelteringPostCreateResult.Success(postId = 1)),
                    AcceptAllPhotos(),
                    FakeRegionStore(),
                )

            assertFalse(viewModel.uiState.value.canSubmit)
            assertEquals(
                listOf("사진", "발견 날짜", "발견 시간", "발견 장소", "현재 보호 상태", "현재 보호 장소"),
                viewModel.uiState.value.submitBlockers,
            )

            fillRequiredFields(viewModel)

            assertTrue(viewModel.uiState.value.canSubmit)
        }

    @Test
    fun `칸을 떠나기 전에는 비어 있어도 오류를 보이지 않고 떠나면 보인다`() =
        runTest(dispatcher) {
            val viewModel =
                ShelteringPostCreateViewModel(
                    FakeShelteringPostCreateRepository(ShelteringPostCreateResult.Success(postId = 1)),
                    AcceptAllPhotos(),
                    FakeRegionStore(),
                )

            assertNull(viewModel.uiState.value.protectionStatusError)

            viewModel.onFieldLeave(ShelteringPostField.PROTECTION_STATUS)
            assertEquals("현재 보호 상태를 입력해 주세요.", viewModel.uiState.value.protectionStatusError)

            viewModel.onProtectionStatusChange("임시 보호 중")
            assertNull(viewModel.uiState.value.protectionStatusError)
        }

    @Test
    fun `분이 59를 넘으면 칸을 떠나기 전에도 바로 오류를 보인다`() =
        runTest(dispatcher) {
            val viewModel =
                ShelteringPostCreateViewModel(
                    FakeShelteringPostCreateRepository(ShelteringPostCreateResult.Success(postId = 1)),
                    AcceptAllPhotos(),
                    FakeRegionStore(),
                )

            viewModel.onFoundHourChange("2")
            viewModel.onFoundMinuteChange("6")
            assertNull(viewModel.uiState.value.foundTimeError)

            viewModel.onFoundMinuteChange("60")
            assertEquals("분은 0~59 사이로 입력해 주세요.", viewModel.uiState.value.foundTimeError)
        }

    @Test
    fun `저장된 지역이 시·도 전체면 미리 채우지 않고 선택 전 제출을 막는다`() =
        runTest(dispatcher) {
            val repository = FakeShelteringPostCreateRepository(ShelteringPostCreateResult.Success(postId = 1))
            val store = FakeRegionStore(SelectedRegion("30", "대전광역시 전체"))
            val viewModel = ShelteringPostCreateViewModel(repository, AcceptAllPhotos(), store)

            assertEquals(null, viewModel.uiState.value.selectedRegionCode)

            fillRequiredFields(viewModel)
            viewModel.submit()
            advanceUntilIdle()

            assertEquals("발견 지역을 선택해 주세요.", viewModel.uiState.value.regionError)
            assertEquals(0, repository.inputs.size)
        }

    @Test
    fun `화면에서 고른 시·군·구 코드를 등록 요청에 보낸다`() =
        runTest(dispatcher) {
            val repository = FakeShelteringPostCreateRepository(ShelteringPostCreateResult.Success(postId = 1))
            val viewModel = ShelteringPostCreateViewModel(repository, AcceptAllPhotos(), FakeRegionStore(null))

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
                FakeShelteringPostCreateRepository(
                    ShelteringPostCreateResult.InvalidInput(
                        message = "입력값을 확인해 주세요.",
                        fieldErrors =
                            mapOf(
                                "eventTime" to "발견 시각이 지금보다 뒤입니다.",
                                "currentLocation.exactLocation" to "보호 장소가 너무 짧습니다.",
                            ),
                    ),
                )
            val viewModel = ShelteringPostCreateViewModel(repository, AcceptAllPhotos(), FakeRegionStore())

            fillRequiredFields(viewModel)
            viewModel.submit()
            advanceUntilIdle()

            assertEquals("발견 시각이 지금보다 뒤입니다.", viewModel.uiState.value.foundTimeError)
            assertEquals("보호 장소가 너무 짧습니다.", viewModel.uiState.value.currentProtectionPlaceError)
            assertEquals("입력값을 확인해 주세요.", viewModel.uiState.value.requestError)

            viewModel.onFoundMinuteChange("19")
            viewModel.onCurrentProtectionPlaceChange("서울특별시 마포구 자택 101호")
            assertNull(viewModel.uiState.value.foundTimeError)
            assertNull(viewModel.uiState.value.currentProtectionPlaceError)
        }

    @Test
    fun `요청 실패 후 입력값과 선택 사진을 유지한다`() =
        runTest(dispatcher) {
            val repository = FakeShelteringPostCreateRepository(ShelteringPostCreateResult.Failure("등록 실패"))
            val viewModel = ShelteringPostCreateViewModel(repository, AcceptAllPhotos(), FakeRegionStore())

            fillRequiredFields(viewModel)
            viewModel.onNameChange("초코")
            viewModel.submit()
            advanceUntilIdle()

            assertEquals("초코", viewModel.uiState.value.name)
            assertEquals(listOf("content://photo/1"), viewModel.uiState.value.photoUris)
            assertEquals("등록 실패", viewModel.uiState.value.requestError)
        }

    @Test
    fun `글자 칸은 서버 한도에서 잘라 넣는다`() =
        runTest(dispatcher) {
            val viewModel =
                ShelteringPostCreateViewModel(
                    FakeShelteringPostCreateRepository(ShelteringPostCreateResult.Success(postId = 1)),
                    AcceptAllPhotos(),
                    FakeRegionStore(),
                )

            viewModel.onProtectionStatusChange("가".repeat(150))
            viewModel.onFeatureTextChange("나".repeat(2500))

            assertEquals(100, viewModel.uiState.value.protectionStatus.length)
            assertEquals(1880, viewModel.uiState.value.featureText.length)
        }

    // 사진 검사는 코루틴에서 돌아간다. 이어지는 검증이 사진을 보게 하려면 여기서 돌려 준다.
    private fun TestScope.fillRequiredFields(viewModel: ShelteringPostCreateViewModel) {
        viewModel.addPhotos(listOf("content://photo/1"))
        viewModel.onFoundDateChange(LocalDate.now().toString())
        viewModel.onFoundPeriodChange(DayPeriod.PM)
        viewModel.onFoundHourChange("2")
        viewModel.onFoundMinuteChange("20")
        viewModel.onFoundPlaceChange("서울특별시 마포구 공원 인근")
        viewModel.onProtectionStatusChange("임시 보호 중")
        viewModel.onCurrentProtectionPlaceChange("서울특별시 마포구 자택")
        advanceUntilIdle()
    }

    private class FakeShelteringPostCreateRepository(
        private val result: ShelteringPostCreateResult,
    ) : ShelteringPostCreateRepository {
        val inputs = mutableListOf<ShelteringPostCreateInput>()

        override suspend fun createShelteringPost(input: ShelteringPostCreateInput): ShelteringPostCreateResult {
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

    private class AcceptAllPhotos : PhotoInputInspector {
        override suspend fun rejection(uriText: String): String? = null
    }
}
