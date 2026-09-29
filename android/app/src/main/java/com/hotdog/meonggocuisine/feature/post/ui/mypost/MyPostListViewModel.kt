package com.hotdog.meonggocuisine.feature.post.ui.mypost

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hotdog.meonggocuisine.feature.post.data.MyPostListResult
import com.hotdog.meonggocuisine.feature.post.data.MyPostRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MyPostListViewModel
    @Inject
    constructor(
        private val repository: MyPostRepository,
    ) : ViewModel() {
        private val mutableUiState = MutableStateFlow(MyPostListUiState())
        val uiState: StateFlow<MyPostListUiState> = mutableUiState.asStateFlow()

        private var requestJob: Job? = null

        init {
            loadPosts()
        }

        fun onFilterSelect(filter: MyPostStatusFilter) {
            if (mutableUiState.value.selectedFilter == filter) return
            requestJob?.cancel()
            mutableUiState.value =
                MyPostListUiState(
                    selectedFilter = filter,
                    isLoading = true,
                )
            loadPosts()
        }

        fun retry() {
            if (requestJob?.isActive == true) return
            mutableUiState.value =
                mutableUiState.value.copy(
                    isLoading = true,
                    errorMessage = null,
                    requiresLogin = false,
                )
            loadPosts()
        }

        fun loadMore() {
            val state = mutableUiState.value
            if (requestJob?.isActive == true || state.isLoadingMore || !state.hasNext || state.nextCursor == null) return
            mutableUiState.value = state.copy(isLoadingMore = true, errorMessage = null)
            loadPosts(cursor = state.nextCursor)
        }

        private fun loadPosts(cursor: String? = null) {
            requestJob =
                viewModelScope.launch {
                    val result =
                        repository.getMyPosts(
                            status = mutableUiState.value.selectedFilter.statusQuery,
                            cursor = cursor,
                        )
                    when (result) {
                        is MyPostListResult.Success -> showPosts(result, append = cursor != null)
                        is MyPostListResult.Unauthorized -> showUnauthorized(result.message)
                        is MyPostListResult.Failure -> showError(result.message, append = cursor != null)
                    }
                }
        }

        private fun showPosts(
            result: MyPostListResult.Success,
            append: Boolean,
        ) {
            val currentPosts = if (append) mutableUiState.value.posts else emptyList()
            mutableUiState.value =
                mutableUiState.value.copy(
                    posts = currentPosts + result.posts,
                    isLoading = false,
                    isLoadingMore = false,
                    hasNext = result.hasNext,
                    nextCursor = result.nextCursor,
                    errorMessage = null,
                    requiresLogin = false,
                )
        }

        private fun showUnauthorized(message: String) {
            mutableUiState.value =
                mutableUiState.value.copy(
                    isLoading = false,
                    isLoadingMore = false,
                    errorMessage = message,
                    requiresLogin = true,
                    hasNext = false,
                    nextCursor = null,
                )
        }

        private fun showError(
            message: String,
            append: Boolean,
        ) {
            mutableUiState.value =
                mutableUiState.value.copy(
                    isLoading = false,
                    isLoadingMore = false,
                    errorMessage = message,
                    hasNext = if (append) mutableUiState.value.hasNext else false,
                )
        }
    }
