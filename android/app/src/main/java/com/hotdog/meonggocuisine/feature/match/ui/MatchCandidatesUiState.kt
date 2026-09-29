package com.hotdog.meonggocuisine.feature.match.ui

import com.hotdog.meonggocuisine.feature.match.data.MatchBaseSummary
import com.hotdog.meonggocuisine.feature.match.data.MatchCandidate

/** 후보 화면이 표시하는 단계입니다. */
enum class MatchCandidatesPhase {
    /** 결과를 조회하는 중입니다. */
    LOADING,

    /** 임계값을 통과한 후보가 있습니다. */
    CANDIDATES,

    /** 정상적으로 분석했지만 후보가 없습니다. 처리 실패와 다릅니다. */
    EMPTY,

    /** 처리에 실패했습니다. 후보 없음과 구분해 표시합니다. */
    FAILED,

    /** 작성자가 아니거나 종료된 게시물입니다. 결과를 표시하지 않습니다. */
    BLOCKED,
}

data class MatchCandidatesUiState(
    val phase: MatchCandidatesPhase = MatchCandidatesPhase.LOADING,
    /** 기준 게시물의 동물 이름입니다. 없으면 이름 없는 문구를 사용합니다. */
    val animalName: String? = null,
    /** 화면 상단 요약입니다. 못 불러오면 null이고 후보 표시는 계속합니다. */
    val baseSummary: MatchBaseSummary? = null,
    val candidates: List<MatchCandidate> = emptyList(),
    /** 접힌 상태에서 보여 줄 후보 수를 넘겼는지입니다. */
    val isExpanded: Boolean = false,
    /** 최신 실행이 실패·처리 중이라 이전 성공 결과를 보여 주는 중입니다. */
    val usingPreviousResult: Boolean = false,
    /** 게시물이 바뀌어 이 결과가 낡았습니다. 자동으로 다시 분석하지 않습니다. */
    val isStale: Boolean = false,
    val isRequesting: Boolean = false,
    val errorMessage: String? = null,
) {
    val canRequestAnalysis: Boolean
        get() = !isRequesting && phase != MatchCandidatesPhase.BLOCKED && phase != MatchCandidatesPhase.LOADING

    /** 접힌 상태에서 실제로 표시하는 후보입니다. */
    val visibleCandidates: List<MatchCandidate>
        get() = if (isExpanded) candidates else candidates.take(COLLAPSED_CANDIDATE_COUNT)

    val hasMoreCandidates: Boolean
        get() = !isExpanded && candidates.size > COLLAPSED_CANDIDATE_COUNT

    companion object {
        /** 디자인의 한 줄 3칸 기준으로 두 줄까지 먼저 보여 준다. */
        const val COLLAPSED_CANDIDATE_COUNT = 6
    }
}
