package com.hotdog.meonggocuisine.feature.match.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hotdog.meonggocuisine.feature.match.data.AnalysisStatus
import com.hotdog.meonggocuisine.feature.match.data.MatchBaseSummaryResult
import com.hotdog.meonggocuisine.feature.match.data.MatchRepository
import com.hotdog.meonggocuisine.feature.match.data.MatchRunRequestResult
import com.hotdog.meonggocuisine.feature.match.data.MatchStatusResult
import com.hotdog.meonggocuisine.feature.match.data.MatchStatusSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MatchCandidatesViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val repository: MatchRepository,
    ) : ViewModel() {
        // toRoute()는 Android Bundle이 필요해 JVM 단위 테스트에서 실행할 수 없어 키로 직접 읽는다.
        val postId: Long = checkNotNull(savedStateHandle[POST_ID_ARG])
        private val animalName: String? = savedStateHandle[ANIMAL_NAME_ARG]

        private val mutableUiState = MutableStateFlow(MatchCandidatesUiState(animalName = animalName))
        val uiState: StateFlow<MatchCandidatesUiState> = mutableUiState.asStateFlow()

        /**
         * 다시 분석을 접수해 진행 화면으로 돌아가야 하는지 알립니다.
         *
         * 이 화면은 완료된 결과만 보여 주므로 처리 중 상태는 진행 화면이 담당합니다.
         */
        private val mutableAnalysisRestarted = MutableStateFlow(false)
        val analysisRestarted: StateFlow<Boolean> = mutableAnalysisRestarted.asStateFlow()

        /** 진행 화면으로 이동한 뒤 부른다. 되돌리지 않으면 리컴포즈마다 다시 이동해 백스택이 꼬인다. */
        fun onAnalysisRestartedHandled() {
            mutableAnalysisRestarted.value = false
        }

        init {
            loadBaseSummary()
            load()
        }

        fun retry() = load()

        /** 접어 둔 나머지 후보를 모두 표시합니다. */
        fun expandCandidates() {
            mutableUiState.value = mutableUiState.value.copy(isExpanded = true)
        }

        /**
         * 상단 요약을 조회합니다.
         *
         * 요약은 보조 정보이므로 실패해도 후보 표시를 막지 않습니다.
         */
        private fun loadBaseSummary() {
            viewModelScope.launch {
                when (val result = repository.getBaseSummary(postId)) {
                    is MatchBaseSummaryResult.Success ->
                        mutableUiState.value = mutableUiState.value.copy(baseSummary = result.summary)

                    MatchBaseSummaryResult.Unavailable -> Unit
                }
            }
        }

        /** 후보가 없거나 실패했을 때 작성자가 다시 분석합니다. */
        fun requestAnalysis() {
            if (!mutableUiState.value.canRequestAnalysis) return
            mutableUiState.value = mutableUiState.value.copy(isRequesting = true, errorMessage = null)
            viewModelScope.launch {
                when (val result = repository.requestAnalysis(postId)) {
                    is MatchRunRequestResult.Accepted -> {
                        mutableUiState.value = mutableUiState.value.copy(isRequesting = false)
                        mutableAnalysisRestarted.value = true
                    }

                    is MatchRunRequestResult.Unauthorized -> block(result.message)
                    is MatchRunRequestResult.PostNotFound -> block(result.message)
                    is MatchRunRequestResult.NotAllowed -> block(result.message)
                    is MatchRunRequestResult.Failure ->
                        mutableUiState.value =
                            mutableUiState.value.copy(
                                isRequesting = false,
                                errorMessage = result.message,
                            )
                }
            }
        }

        private fun load() {
            mutableUiState.value =
                mutableUiState.value.copy(phase = MatchCandidatesPhase.LOADING, errorMessage = null)
            viewModelScope.launch {
                when (val result = repository.getStatus(postId)) {
                    is MatchStatusResult.Success -> show(result.snapshot)
                    is MatchStatusResult.Unauthorized -> block(result.message)
                    is MatchStatusResult.PostNotFound -> block(result.message)
                    is MatchStatusResult.NotAllowed -> block(result.message)
                    is MatchStatusResult.Failure ->
                        mutableUiState.value =
                            mutableUiState.value.copy(
                                phase = MatchCandidatesPhase.FAILED,
                                errorMessage = result.message,
                            )
                }
            }
        }

        private fun show(snapshot: MatchStatusSnapshot) {
            if (snapshot.latestRunStatus?.isInProgress == true || snapshot.analysisStatus.isInProgress) {
                mutableAnalysisRestarted.value = true
                return
            }
            mutableUiState.value =
                mutableUiState.value.copy(
                    phase = snapshot.toCandidatesPhase(),
                    candidates = snapshot.candidates,
                    usingPreviousResult = snapshot.usingPreviousResult,
                    isStale = snapshot.analysisStatus == AnalysisStatus.STALE,
                    isRequesting = false,
                    errorMessage =
                        if (snapshot.latestRunStatus == AnalysisStatus.FAILED) {
                            snapshot.failureText()
                        } else {
                            null
                        },
                )
        }

        /** 권한·상태 거부입니다. 기존 후보 표시를 제거합니다. */
        private fun block(message: String) {
            mutableUiState.value =
                MatchCandidatesUiState(
                    phase = MatchCandidatesPhase.BLOCKED,
                    animalName = animalName,
                    errorMessage = message,
                )
        }

        private companion object {
            const val POST_ID_ARG = "postId"
            const val ANIMAL_NAME_ARG = "animalName"
        }
    }

/**
 * 후보 유무를 화면 단계로 옮깁니다.
 *
 * 최신 실행이 실패했어도 보존된 이전 성공 결과가 있으면 후보를 보여 주고, 실패는 안내로만 알립니다.
 */
private fun MatchStatusSnapshot.toCandidatesPhase(): MatchCandidatesPhase =
    when {
        candidates.isNotEmpty() -> MatchCandidatesPhase.CANDIDATES
        latestRunStatus == AnalysisStatus.FAILED -> MatchCandidatesPhase.FAILED
        else -> MatchCandidatesPhase.EMPTY
    }

private fun MatchStatusSnapshot.failureText(): String =
    when (errorCode) {
        "MATCH_TIMEOUT" -> "분석 시간이 초과되었습니다. 다시 시도해 주세요."
        else -> "분석을 완료하지 못했습니다. 다시 시도해 주세요."
    }
