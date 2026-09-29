package com.hotdog.meonggocuisine.feature.adoption.ui

import com.hotdog.meonggocuisine.feature.adoption.data.AdoptionAnimal
import com.hotdog.meonggocuisine.feature.adoption.data.AdoptionSwipeRecord
import com.hotdog.meonggocuisine.feature.adoption.data.SexFilter
import com.hotdog.meonggocuisine.feature.adoption.data.SpeciesFilter

data class AdoptionUiState(
    val isLoading: Boolean = true,
    val speciesFilter: SpeciesFilter = SpeciesFilter.ALL,
    val sexFilter: SexFilter = SexFilter.ALL,
    val animals: List<AdoptionAnimal> = emptyList(),
    val currentIndex: Int = 0,
    val errorMessage: String? = null,
    /** 다음 페이지가 더 있으면 카드가 떨어지기 전에 미리 부른다. */
    val hasNext: Boolean = false,
    val favoriteErrorMessage: String? = null,
    /**
     * 넘긴 아이들, 방금 넘긴 것이 앞에 온다. 좋아요로 넘겼든 그냥 넘겼든 모두 들어온다.
     *
     * 서버가 준다(AD6). 계정에 남으므로 앱을 껐다 켜도, 기기를 바꿔도 그대로다(2026-09-25).
     */
    val passed: List<AdoptionSwipeRecord> = emptyList(),
) {
    val current: AdoptionAnimal? get() = animals.getOrNull(currentIndex)

    /** 카드 스택에서 현재 카드 뒤에 겹쳐 보이는 다음 카드다. */
    val next: AdoptionAnimal? get() = animals.getOrNull(currentIndex + 1)

    /** 히스토리의 좋아요 칸 — 지금 찜인 것만. 넘긴 순서를 그대로 쓴다. */
    val passedFavorites: List<AdoptionSwipeRecord> get() = passed.filter { it.favorited }

    private val isDeckDone: Boolean
        get() = !isLoading && errorMessage == null && !hasNext && currentIndex >= animals.size

    /**
     * 볼 수 있는 아이를 다 본 상태다.
     *
     * 넘긴 아이는 다시 올라오지 않으므로, 이번에 받은 카드가 없더라도 넘긴 기록이 있으면 "없다" 가
     * 아니라 "다 봤다" 다 — 조건을 탓하게 두면 멀쩡한 필터를 풀게 된다(2026-09-24).
     */
    val isExhausted: Boolean get() = isDeckDone && (animals.isNotEmpty() || passed.isNotEmpty())

    val isEmpty: Boolean get() = !isLoading && errorMessage == null && animals.isEmpty() && passed.isEmpty()
}
