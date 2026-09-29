package com.hotdog.meonggocuisine.feature.community.ui

import com.hotdog.meonggocuisine.feature.community.data.LostPostSummary
import com.hotdog.meonggocuisine.feature.community.data.PostListFilter

data class ShelteringPostListUiState(
    val selectedRegionCode: String? = null,
    val selectedRegionName: String? = null,
    val searchQuery: String = "",
    val appliedSearchQuery: String = "",
    val filter: PostListFilter = PostListFilter(),
    val posts: List<LostPostSummary> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasNext: Boolean = false,
    val nextCursor: String? = null,
    val errorMessage: String? = null,
) {
    val needsRegionSelection: Boolean get() = selectedRegionCode == null
    val isSearchResult: Boolean get() = appliedSearchQuery.isNotBlank()
}
