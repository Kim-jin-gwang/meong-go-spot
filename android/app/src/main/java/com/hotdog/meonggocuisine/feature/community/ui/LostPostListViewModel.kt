package com.hotdog.meonggocuisine.feature.community.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hotdog.meonggocuisine.feature.community.data.LostPostListResult
import com.hotdog.meonggocuisine.feature.community.data.LostPostRepository
import com.hotdog.meonggocuisine.feature.community.data.PostListFilter
import com.hotdog.meonggocuisine.feature.community.data.RegionStore
import com.hotdog.meonggocuisine.feature.community.data.asApiRegionCode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LostPostListViewModel
    @Inject
    constructor(
        private val repository: LostPostRepository,
        private val regionStore: RegionStore,
    ) : ViewModel() {
        private val mutableUiState = MutableStateFlow(LostPostListUiState())
        val uiState: StateFlow<LostPostListUiState> = mutableUiState.asStateFlow()

        private var requestJob: Job? = null
        private var requestGeneration = 0L
        private var hasLoaded = false
        private var loadedRegionCode: String? = null

        init {
            // 보호 목록과 같은 저장소를 구독한다. 예전엔 "서울 마포구"가 상수로 박혀 있어 지역 변경 화면에서 다른
            // 지역을 골라도 이 목록은 그대로였다 (2026-09-15 버그). 첫 방출이 최초 조회이고, 지역이 바뀌면 다시 불러온다.
            viewModelScope.launch {
                regionStore.selectedRegion.collect { region ->
                    val changed = !hasLoaded || region?.code != loadedRegionCode
                    mutableUiState.value =
                        mutableUiState.value.copy(
                            selectedRegionCode = region?.code,
                            selectedRegionName = region?.name,
                            posts = if (changed) emptyList() else mutableUiState.value.posts,
                            errorMessage = if (changed) null else mutableUiState.value.errorMessage,
                        )
                    if (changed) {
                        hasLoaded = true
                        loadedRegionCode = region?.code
                        cancelActiveRequest()
                        mutableUiState.value =
                            mutableUiState.value.copy(
                                isLoading = true,
                                isLoadingMore = false,
                                hasNext = false,
                                nextCursor = null,
                            )
                        loadPosts()
                    }
                }
            }
        }

        fun onSearchQueryChange(value: String) {
            if (value.length > MAX_SEARCH_LENGTH) return
            mutableUiState.value = mutableUiState.value.copy(searchQuery = value)
        }

        fun search() {
            if (requestJob?.isActive == true) return
            val appliedQuery = mutableUiState.value.searchQuery.trim()
            mutableUiState.value =
                mutableUiState.value.copy(
                    appliedSearchQuery = appliedQuery,
                    posts = emptyList(),
                    isLoading = true,
                    hasNext = false,
                    nextCursor = null,
                    errorMessage = null,
                )
            loadPosts()
        }

        fun retry() {
            if (requestJob?.isActive == true) return
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
            if (state.filter == filter) return
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
            val state = mutableUiState.value
            val generation = ++requestGeneration
            requestJob =
                viewModelScope.launch {
                    val result =
                        repository.getLostPosts(
                            breedName = state.appliedSearchQuery.ifBlank { null },
                            regionCode = state.selectedRegionCode.asApiRegionCode(),
                            filter = state.filter,
                            cursor = cursor,
                        )
                    if (generation != requestGeneration) return@launch
                    when (result) {
                        is LostPostListResult.Success -> showPosts(result, append = cursor != null)
                        is LostPostListResult.Failure -> showError(result.message, append = cursor != null)
                    }
                }
        }

        private fun cancelActiveRequest() {
            requestGeneration += 1
            requestJob?.cancel()
            requestJob = null
        }

        private fun showPosts(
            result: LostPostListResult.Success,
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
