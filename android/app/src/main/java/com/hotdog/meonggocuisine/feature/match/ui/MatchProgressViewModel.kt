package com.hotdog.meonggocuisine.feature.match.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hotdog.meonggocuisine.feature.match.data.AnalysisStatus
import com.hotdog.meonggocuisine.feature.match.data.MatchRepository
import com.hotdog.meonggocuisine.feature.match.data.MatchRunRequestResult
import com.hotdog.meonggocuisine.feature.match.data.MatchStatusResult
import com.hotdog.meonggocuisine.feature.match.data.MatchStatusSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MatchProgressViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val repository: MatchRepository,
    ) : ViewModel() {
        // toRoute()는 Android Bundle이 필요해 JVM 단위 테스트에서 실행할 수 없어 키로 직접 읽는다.
        val postId: Long = checkNotNull(savedStateHandle[POST_ID_ARG])
        private val animalName: String? = savedStateHandle[ANIMAL_NAME_ARG]

        private val mutableUiState = MutableStateFlow(MatchProgressUiState(animalName = animalName))
        val uiState: StateFlow<MatchProgressUiState> = mutableUiState.asStateFlow()

        private var pollingJob: Job? = null

        /** 화면이 보이는 동안에만 상태를 조회합니다. */
        fun startPolling() {
            if (pollingJob?.isActive == true) return
            pollingJob =
                viewModelScope.launch {
                    loadStatus()
                    while (isActive && mutableUiState.value.phase == MatchProgressPhase.IN_PROGRESS) {
                        delay(mutableUiState.value.pollAfterMs)
                        loadStatus()
                    }
                }
        }

        fun stopPolling() {
            pollingJob?.cancel()
            pollingJob = null
        }

        /** 작성자가 분석을 시작하거나 다시 시도합니다. */
        fun requestAnalysis() {
            if (!mutableUiState.value.canRequestAnalysis) return
            mutableUiState.value = mutableUiState.value.copy(isRequesting = true, errorMessage = null)
            viewModelScope.launch {
                when (val result = repository.requestAnalysis(postId)) {
                    is MatchRunRequestResult.Accepted -> {
                        mutableUiState.value =
                            mutableUiState.value.copy(
                                phase = MatchProgressPhase.IN_PROGRESS,
                                isRequesting = false,
                                errorMessage = null,
                            )
                        startPolling()
                    }

                    is MatchRunRequestResult.Unauthorized -> block(result.message)
                    is MatchRunRequestResult.PostNotFound -> block(result.message)
                    is MatchRunRequestResult.NotAllowed -> block(result.message)
                    is MatchRunRequestResult.Failure ->
                        mutableUiState.value =
                            mutableUiState.value.copy(
                                phase = MatchProgressPhase.FAILED,
                                isRequesting = false,
                                errorMessage = result.message,
                            )
                }
            }
        }

        private suspend fun loadStatus() {
            when (val result = repository.getStatus(postId)) {
                is MatchStatusResult.Success -> show(result.snapshot)
                is MatchStatusResult.Unauthorized -> block(result.message)
                is MatchStatusResult.PostNotFound -> block(result.message)
                is MatchStatusResult.NotAllowed -> block(result.message)
                is MatchStatusResult.Failure -> showStatusFailure(result.message)
            }
        }

        private fun show(snapshot: MatchStatusSnapshot) {
            mutableUiState.value =
                mutableUiState.value.copy(
                    phase = snapshot.toPhase(),
                    isRequesting = false,
                    candidateCount = snapshot.candidateCount,
                    usingPreviousResult = snapshot.usingPreviousResult,
                    errorMessage = if (snapshot.latestRunStatus == AnalysisStatus.FAILED) snapshot.failureMessage() else null,
                    pollAfterMs = snapshot.recommendedPollAfterMs ?: MatchProgressUiState.DEFAULT_POLL_AFTER_MS,
                )
        }

        /**
         * 조회 자체가 실패한 경우입니다.
         *
         * 처리 중이던 실행은 서버에 남아 있으므로 분석 실패로 단정하지 않고, 이미 알고 있는 단계를
         * 유지한 채 오류만 알립니다.
         */
        private fun showStatusFailure(message: String) {
            val current = mutableUiState.value
            mutableUiState.value =
                current.copy(
                    phase = if (current.phase == MatchProgressPhase.LOADING) MatchProgressPhase.FAILED else current.phase,
                    isRequesting = false,
                    errorMessage = message,
                )
        }

        /** 권한·상태 거부입니다. 폴링을 멈추고 기존 결과 표시를 제거합니다. */
        private fun block(message: String) {
            stopPolling()
            mutableUiState.value =
                MatchProgressUiState(
                    phase = MatchProgressPhase.BLOCKED,
                    animalName = animalName,
                    errorMessage = message,
                )
        }

        private companion object {
            const val POST_ID_ARG = "postId"
            const val ANIMAL_NAME_ARG = "animalName"
        }
    }

private fun MatchStatusSnapshot.toPhase(): MatchProgressPhase =
    when {
        latestRunStatus?.isInProgress == true -> MatchProgressPhase.IN_PROGRESS
        analysisStatus == AnalysisStatus.STALE -> MatchProgressPhase.STALE
        analysisStatus == AnalysisStatus.SUCCEEDED -> MatchProgressPhase.COMPLETED
        analysisStatus == AnalysisStatus.FAILED -> MatchProgressPhase.FAILED
        analysisStatus.isInProgress -> MatchProgressPhase.IN_PROGRESS
        else -> MatchProgressPhase.NOT_REQUESTED
    }

private fun MatchStatusSnapshot.failureMessage(): String =
    when (errorCode) {
        "MATCH_TIMEOUT" -> "분석 시간이 초과되었습니다. 다시 시도해 주세요."
        else -> "분석을 완료하지 못했습니다. 다시 시도해 주세요."
    }
