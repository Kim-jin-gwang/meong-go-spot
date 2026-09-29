package com.hotdog.meonggocuisine.feature.home.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hotdog.meonggocuisine.feature.community.data.RegionStore
import com.hotdog.meonggocuisine.feature.community.data.asApiRegionCode
import com.hotdog.meonggocuisine.feature.home.data.HomeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        private val repository: HomeRepository,
        private val regionStore: RegionStore,
    ) : ViewModel() {
        private val mutableUiState = MutableStateFlow(HomeUiState())
        val uiState: StateFlow<HomeUiState> = mutableUiState.asStateFlow()

        private var requestJob: Job? = null
        private var requestedRegionCode: String? = null

        init {
            // 지역 저장소를 구독한다 — 첫 방출이 최초 조회다. 다른 탭에서 지역을 바꾸면 홈이 다시 보일 때까지 기다리지
            // 않고 바로 새 지역으로 다시 부른다. 예전엔 화면 재진입(resume)에만 의존해 탭 전환 뒤 옛 지역이 남았다
            // (2026-09-15 버그: 보호 목록에서 용산구로 바꿔도 홈은 종로구).
            viewModelScope.launch {
                regionStore.selectedRegion.collect { refresh() }
            }
        }

        /**
         * 지역 저장소가 바뀔 때와 화면이 다시 보일 때 불러옵니다.
         *
         * 같은 지역을 이미 불러오는 중이면 중복 호출하지 않되, 지역이 바뀌었으면
         * 진행 중인 요청을 버리고 새 지역으로 다시 부릅니다.
         */
        fun refresh() {
            val region = regionStore.selectedRegion.value
            if (requestJob?.isActive == true && requestedRegionCode == region?.code) return
            requestJob?.cancel()
            requestedRegionCode = region?.code
            mutableUiState.value = mutableUiState.value.copy(isLoading = true, regionName = region?.name)
            requestJob =
                viewModelScope.launch {
                    // 전국(00)·시·도 전체(2자리)·시·군·구(5자리) 를 서버 규칙으로 바꾼다 — 전국은 코드 생략.
                    val insights = async { repository.getInsights(region?.code.asApiRegionCode()) }
                    val sheltering =
                        async { if (region == null) emptyList() else repository.getShelteringPreview(region.code.asApiRegionCode()) }
                    val lost = async { repository.getLostPreview() }
                    mutableUiState.value =
                        HomeUiState(
                            isLoading = false,
                            insights = insights.await(),
                            regionName = region?.name,
                            shelteringPosts = sheltering.await(),
                            lostPosts = lost.await(),
                        )
                }
        }
    }
