package com.hotdog.meonggocuisine.feature.post.ui.mypost

import com.hotdog.meonggocuisine.feature.post.data.MyPostSummary

enum class MyPostStatusFilter(val label: String, val statusQuery: String?) {
    ALL("전체", null),
    ACTIVE("진행 중", "ACTIVE"),
    CLOSED("종료", "CLOSED"),
}

data class MyPostListUiState(
    val selectedFilter: MyPostStatusFilter = MyPostStatusFilter.ALL,
    val posts: List<MyPostSummary> = emptyList(),
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val hasNext: Boolean = false,
    val nextCursor: String? = null,
    val errorMessage: String? = null,
    val requiresLogin: Boolean = false,
)
