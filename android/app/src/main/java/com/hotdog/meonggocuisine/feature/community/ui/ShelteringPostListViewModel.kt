package com.hotdog.meonggocuisine.feature.community.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hotdog.meonggocuisine.feature.community.data.NationwideRegion
import com.hotdog.meonggocuisine.feature.community.data.PostListFilter
import com.hotdog.meonggocuisine.feature.community.data.RegionStore
import com.hotdog.meonggocuisine.feature.community.data.ShelteringPostListResult
import com.hotdog.meonggocuisine.feature.community.data.ShelteringPostRepository
import com.hotdog.meonggocuisine.feature.community.data.asApiRegionCode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ShelteringPostListViewModel
    @Inject
    constructor(
        private val repository: ShelteringPostRepository,
        private val regionStore: RegionStore,
    ) : ViewModel() {
        private val mutableUiState = MutableStateFlow(ShelteringPostListUiState())
        val uiState: StateFlow<ShelteringPostListUiState> = mutableUiState.asStateFlow()

        private var requestJob: Job? = null
        private var requestGeneration = 0L
        private var loadedRegionCode: String? = null

        init {
            viewModelScope.launch {
                regionStore.selectedRegion.collect { stored ->
                    // 고른 적이 없으면 전국으로 본다. 예전에는 지역 선택 화면으로 먼저 보냈는데,
                    // 보호 목록은 어느 지역이든 볼 가치가 있어 첫 진입을 막을 이유가 없다.
                    val region = stored ?: NationwideRegion
                    val changed = region.code != loadedRegionCode
                    mutableUiState.value =
                        mutableUiState.value.copy(
                            selectedRegionCode = region.code,
                            selectedRegionName = region.name,
                        )
                    if (changed) {
                        loadedRegionCode = region.code
                        cancelActiveRequest()
                        mutableUiState.value =
                            mutableUiState.value.copy(
                                posts = emptyList(),
                                isLoading = true,
                                isLoadingMore = false,
                                hasNext = false,
                                nextCursor = null,
                                errorMessage = null,
                            )
                        loadPosts()
                    }
                }
            }
        }

        fun onSearchQueryChange(value: String) {
            if (value.length <= MAX_SEARCH_LENGTH) {
                mutableUiState.value = mutableUiState.value.copy(searchQuery = value)
            }
        }

        fun search() {
            if (requestJob?.isActive == true || mutableUiState.value.selectedRegionCode == null) return
            mutableUiState.value =
                mutableUiState.value.copy(
                    appliedSearchQuery = mutableUiState.value.searchQuery.trim(),
                    posts = emptyList(),
                    isLoading = true,
                    hasNext = false,
                    nextCursor = null,
                    errorMessage = null,
                )
            loadPosts()
        }

        fun retry() {
            if (requestJob?.isActive == true || mutableUiState.value.selectedRegionCode == null) return
            mutableUiState.value = mutableUiState.value.copy(isLoading = true, errorMessage = null)
            loadPosts()
        }

        /**
         * 필터 시트의 `적용하기` 입니다.
         *
         * 검색어와 선택 지역은 그대로 두고 목록과 커서만 비운다 — 조건이 달라지면 서버가 주는
         * 커서도 의미를 잃기 때문이다. 바뀐 게 없으면 다시 부르지 않는다.
         */
        fun onFilterApply(filter: PostListFilter) {
            val state = mutableUiState.value
            if (state.filter == filter || state.selectedRegionCode == null) return
            cancelActiveRequest()
            mutableUiState.value =
                state.copy(
                    filter = filter,
                    posts = emptyList(),
                    isLoading = true,
                    isLoadingMore = false,
                    hasNext = false,
                    nextCursor = null,
                    errorMessage = null,
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
            // 선택이 없으면(null) 지역 선택 화면으로 보내지만, "전국"(00) 은 선택이 있는 것이다 — 서버에는 코드를 생략한다.
            val state = mutableUiState.value
            val regionCode = state.selectedRegionCode ?: return
            val generation = ++requestGeneration
            requestJob =
                viewModelScope.launch {
                    val result =
                        repository.getShelteringPosts(
                            breedName = state.appliedSearchQuery.ifBlank { null },
                            regionCode = regionCode.asApiRegionCode(),
                            filter = state.filter,
                            cursor = cursor,
                        )
                    if (generation != requestGeneration) return@launch
                    when (result) {
                        is ShelteringPostListResult.Success -> showPosts(result, append = cursor != null)
                        is ShelteringPostListResult.Failure -> showError(result.message, append = cursor != null)
                    }
                }
        }

        private fun cancelActiveRequest() {
            requestGeneration += 1
            requestJob?.cancel()
            requestJob = null
        }

        private fun showPosts(
            result: ShelteringPostListResult.Success,
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

        private companion object {
            const val MAX_SEARCH_LENGTH = 100
        }
    }
