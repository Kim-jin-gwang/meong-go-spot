package com.hotdog.meonggocuisine.feature.match.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hotdog.meonggocuisine.feature.match.data.MatchComparisonResult
import com.hotdog.meonggocuisine.feature.match.data.MatchRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MatchComparisonViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val repository: MatchRepository,
    ) : ViewModel() {
        // toRoute()는 Android Bundle이 필요해 JVM 단위 테스트에서 실행할 수 없어 키로 직접 읽는다.
        val postId: Long = checkNotNull(savedStateHandle[POST_ID_ARG])
        val candidatePostId: Long = checkNotNull(savedStateHandle[CANDIDATE_POST_ID_ARG])

        private val mutableUiState = MutableStateFlow(MatchComparisonUiState())
        val uiState: StateFlow<MatchComparisonUiState> = mutableUiState.asStateFlow()

        init {
            load()
        }

        fun retry() = load()

        private fun load() {
            mutableUiState.value = MatchComparisonUiState(isLoading = true)
            viewModelScope.launch {
                mutableUiState.value =
                    when (val result = repository.getComparison(postId, candidatePostId)) {
                        is MatchComparisonResult.Success ->
                            MatchComparisonUiState(isLoading = false, comparison = result.comparison)

                        is MatchComparisonResult.Unauthorized ->
                            MatchComparisonUiState(isLoading = false, errorMessage = result.message)

                        is MatchComparisonResult.PostNotFound ->
                            MatchComparisonUiState(isLoading = false, errorMessage = result.message)

                        is MatchComparisonResult.Failure ->
                            MatchComparisonUiState(isLoading = false, errorMessage = result.message)
                    }
            }
        }

        private companion object {
            const val POST_ID_ARG = "postId"
            const val CANDIDATE_POST_ID_ARG = "candidatePostId"
        }
    }
