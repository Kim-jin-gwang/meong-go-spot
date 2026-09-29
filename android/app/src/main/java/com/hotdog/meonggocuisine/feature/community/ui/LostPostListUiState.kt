package com.hotdog.meonggocuisine.feature.community.ui

import com.hotdog.meonggocuisine.feature.community.data.LostPostSummary
import com.hotdog.meonggocuisine.feature.community.data.PostListFilter

data class LostPostListUiState(
    val searchQuery: String = "",
    val appliedSearchQuery: String = "",
    val filter: PostListFilter = PostListFilter(),
    /** 보호 목록과 같은 선택 지역. null 이면 전국 — 잃어버렸어요는 지역을 고르기 전에도 볼 수 있다. */
    val selectedRegionCode: String? = null,
    val selectedRegionName: String? = null,
    val posts: List<LostPostSummary> = emptyList(),
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val hasNext: Boolean = false,
    val nextCursor: String? = null,
    val errorMessage: String? = null,
) {
    val isSearchResult: Boolean get() = appliedSearchQuery.isNotBlank()
}
