package com.hotdog.meonggocuisine.feature.community.ui

import com.hotdog.meonggocuisine.feature.community.data.RegionStore
import com.hotdog.meonggocuisine.feature.community.data.SelectedRegion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 잃어버렸어요·보호하고 있어요 목록 테스트가 함께 쓰는 메모리 지역 저장소. */
internal class FakeRegionStore(
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
