package com.hotdog.meonggocuisine.feature.community.ui

import androidx.lifecycle.ViewModel
import com.hotdog.meonggocuisine.feature.community.data.NATIONWIDE_REGION_CODE
import com.hotdog.meonggocuisine.feature.community.data.RegionStore
import com.hotdog.meonggocuisine.feature.community.data.SelectedRegion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * @param name 드롭다운에 보이는 시·군·구 이름.
 * @param display 서버가 만드는 공개 표시값. null 이면 "시도 + name" 과 같다는 뜻이고,
 *   세종특별자치시처럼 시·군·구 부분이 없는 곳만 값을 갖는다.
 */
data class RegionDistrict(val code: String, val name: String, val display: String? = null)

/**
 * 선택 화면에 보이는 항목 — 생성된 [RegionCatalog] 위에 "전국" 과 시·도별 "전체" 를 얹는다.
 *
 * 카탈로그 파일은 공식 자료에서 생성하므로 손대지 않고, 전체 항목은 여기서 만든다. 전체 항목의 코드는 서버 규칙과
 * 같다: 전국 [NATIONWIDE_REGION_CODE], 시·도 전체는 그 시·도 코드 2자리(하위 시·군·구 코드의 접두).
 */
object RegionOptions {
    const val NATIONWIDE_PROVINCE = "전국"
    const val ALL_DISTRICTS = "전체"

    val provinces: List<String> = listOf(NATIONWIDE_PROVINCE) + RegionCatalog.districts.keys

    fun districts(province: String): List<RegionDistrict> {
        if (province == NATIONWIDE_PROVINCE) {
            return listOf(RegionDistrict(NATIONWIDE_REGION_CODE, ALL_DISTRICTS, NATIONWIDE_PROVINCE))
        }
        val catalog = RegionCatalog.districts.getValue(province)
        val prefix = catalog.first().code.take(2)
        return listOf(RegionDistrict(prefix, ALL_DISTRICTS, "$province $ALL_DISTRICTS")) + catalog
    }

    /** 저장된 코드(전국·시도·시군구 어느 것이든)를 화면의 (시·도, 항목) 으로 되돌린다. 모르는 코드는 null. */
    fun find(code: String): Pair<String, RegionDistrict>? =
        provinces.firstNotNullOfOrNull { province ->
            districts(province).firstOrNull { it.code == code }?.let { province to it }
        }
}

data class RegionSelectionUiState(
    val selectedProvince: String = "서울특별시",
    val selectedDistrict: RegionDistrict = RegionCatalog.districts.getValue("서울특별시").first { it.code == "11440" },
) {
    val districts: List<RegionDistrict> get() = RegionOptions.districts(selectedProvince)
}

@HiltViewModel
class RegionSelectionViewModel
    @Inject
    constructor(
        private val regionStore: RegionStore,
    ) : ViewModel() {
        private val mutableUiState = MutableStateFlow(initialState())
        val uiState: StateFlow<RegionSelectionUiState> = mutableUiState.asStateFlow()

        fun selectProvince(province: String) {
            // 시·도를 바꾸면 그 시·도 "전체" 가 먼저 잡힌다 — 구를 따지지 않고 보려는 사람이 한 번 더 고를 필요가 없다.
            mutableUiState.value = RegionSelectionUiState(province, RegionOptions.districts(province).first())
        }

        fun selectDistrict(district: RegionDistrict) {
            mutableUiState.value = mutableUiState.value.copy(selectedDistrict = district)
        }

        fun saveSelection() {
            val state = mutableUiState.value
            regionStore.select(
                SelectedRegion(
                    code = state.selectedDistrict.code,
                    name =
                        state.selectedDistrict.display
                            ?: "${state.selectedProvince} ${state.selectedDistrict.name}",
                ),
            )
        }

        private fun initialState(): RegionSelectionUiState {
            val selected = regionStore.selectedRegion.value ?: return RegionSelectionUiState()
            val (province, district) = RegionOptions.find(selected.code) ?: return RegionSelectionUiState()
            return RegionSelectionUiState(province, district)
        }
    }
