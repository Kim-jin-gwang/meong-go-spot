package com.hotdog.meonggocuisine.feature.match.ui

/** 분석 진행 화면이 표시하는 단계입니다. */
enum class MatchProgressPhase {
    /** 최초 상태 조회 중입니다. */
    LOADING,

    /** 아직 분석을 요청한 적이 없습니다. */
    NOT_REQUESTED,

    /** 서버가 처리 중입니다. */
    IN_PROGRESS,

    /** 분석이 끝났습니다. 후보 화면으로 넘어갑니다. */
    COMPLETED,

    /** 처리에 실패했습니다. 후보 없음과 구분해 표시합니다. */
    FAILED,

    /** 게시물이 바뀌어 이전 후보가 낡았습니다. 자동으로 다시 분석하지 않습니다. */
    STALE,

    /** 작성자가 아니거나 종료된 게시물입니다. 폴링을 멈추고 결과를 표시하지 않습니다. */
    BLOCKED,
}

data class MatchProgressUiState(
    val phase: MatchProgressPhase = MatchProgressPhase.LOADING,
    /** 기준 게시물의 동물 이름입니다. M1이 내려주지 않으므로 경로 인자로 받고, 없으면 null입니다. */
    val animalName: String? = null,
    val isRequesting: Boolean = false,
    val candidateCount: Int = 0,
    val usingPreviousResult: Boolean = false,
    val errorMessage: String? = null,
    val pollAfterMs: Long = DEFAULT_POLL_AFTER_MS,
) {
    /** 작성자가 분석을 시작하거나 다시 시도할 수 있는 단계입니다. */
    val canRequestAnalysis: Boolean
        get() =
            !isRequesting &&
                phase in
                setOf(
                    MatchProgressPhase.NOT_REQUESTED,
                    MatchProgressPhase.FAILED,
                    MatchProgressPhase.STALE,
                )

    companion object {
        /** 서버가 권장 간격을 주지 않을 때만 사용하는 값입니다. */
        const val DEFAULT_POLL_AFTER_MS = 1_000L
    }
}
