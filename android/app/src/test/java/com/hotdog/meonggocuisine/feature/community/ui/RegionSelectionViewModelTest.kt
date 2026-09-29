package com.hotdog.meonggocuisine.feature.community.ui

import com.hotdog.meonggocuisine.feature.community.data.SelectedRegion
import org.junit.Assert.assertEquals
import org.junit.Test

class RegionSelectionViewModelTest {
    @Test
    fun `시도를 바꾸면 그 시도 전체가 먼저 잡힌다`() {
        val viewModel = RegionSelectionViewModel(FakeRegionStore())

        viewModel.selectProvince("부산광역시")

        assertEquals("부산광역시", viewModel.uiState.value.selectedProvince)
        assertEquals(RegionDistrict("26", "전체", "부산광역시 전체"), viewModel.uiState.value.selectedDistrict)
    }

    @Test
    fun `전체 항목을 저장하면 표시 이름이 시도 전체이고 코드는 2자리다`() {
        val store = FakeRegionStore()
        val viewModel = RegionSelectionViewModel(store)
        viewModel.selectProvince("서울특별시")

        viewModel.saveSelection()

        assertEquals(SelectedRegion(code = "11", name = "서울특별시 전체"), store.selectedRegion.value)
    }

    @Test
    fun `저장된 전국·시도 전체 코드로 화면을 열면 그 항목이 선택돼 있다`() {
        val nationwide = RegionSelectionViewModel(FakeRegionStore(SelectedRegion("00", "전국")))
        assertEquals("전국", nationwide.uiState.value.selectedProvince)
        assertEquals("00", nationwide.uiState.value.selectedDistrict.code)

        val province = RegionSelectionViewModel(FakeRegionStore(SelectedRegion("11", "서울특별시 전체")))
        assertEquals("서울특별시", province.uiState.value.selectedProvince)
        assertEquals("전체", province.uiState.value.selectedDistrict.name)
    }
}
