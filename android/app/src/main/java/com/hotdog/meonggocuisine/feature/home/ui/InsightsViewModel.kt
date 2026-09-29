package com.hotdog.meonggocuisine.feature.home.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hotdog.meonggocuisine.feature.community.data.RegionStore
import com.hotdog.meonggocuisine.feature.community.data.asApiRegionCode
import com.hotdog.meonggocuisine.feature.home.data.HomeInsights
import com.hotdog.meonggocuisine.feature.home.data.HomeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class InsightsUiState(
    val isLoading: Boolean = true,
    val regionName: String? = null,
    val insights: HomeInsights? = null,
)

/**
 * 소식 화면 — 홈 띠를 눌러 들어오는 카드 5장 전체. 홈과 같은 D3 한 번 호출이고, 지역 저장소를 구독해 지역이 바뀌면 다시 부른다.
 */
@HiltViewModel
class InsightsViewModel
    @Inject
    constructor(
        private val repository: HomeRepository,
        private val regionStore: RegionStore,
    ) : ViewModel() {
        private val mutableUiState = MutableStateFlow(InsightsUiState())
        val uiState: StateFlow<InsightsUiState> = mutableUiState.asStateFlow()

        private var requestJob: Job? = null

        init {
            viewModelScope.launch {
                regionStore.selectedRegion.collect { refresh() }
            }
        }

        fun refresh() {
            val region = regionStore.selectedRegion.value
            requestJob?.cancel()
            mutableUiState.value = mutableUiState.value.copy(isLoading = true, regionName = region?.name)
            requestJob =
                viewModelScope.launch {
                    // 전국(00)·시·도 전체(2자리)·시·군·구(5자리) 를 서버 규칙으로 — 전국은 코드 생략.
                    val insights = repository.getInsights(region?.code.asApiRegionCode())
                    mutableUiState.value = InsightsUiState(isLoading = false, regionName = region?.name, insights = insights)
                }
        }
    }
