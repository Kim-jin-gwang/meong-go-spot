package com.hotdog.meonggocuisine.feature.match.data

/**
 * M1이 알려주는 분석 상태입니다.
 *
 * `STALE`은 게시물이 바뀌어 이전 후보가 낡았음을 뜻하며, 이때도 이전 후보는 보존됩니다.
 */
enum class AnalysisStatus {
    NOT_REQUESTED,
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    STALE,
    ;

    val isInProgress: Boolean get() = this == PENDING || this == RUNNING

    companion object {
        fun from(value: String?): AnalysisStatus = entries.firstOrNull { it.name == value } ?: NOT_REQUESTED
    }
}

/** 후보 요약입니다. 원시 점수·백분율·정확한 거리는 서버가 내려주지 않으며 담지 않습니다. */
data class MatchCandidate(
    val rank: Int,
    val postId: Long,
    val source: String,
    val status: String,
    val name: String?,
    val species: String,
    val breedName: String?,
    val sex: String,
    val color: String?,
    val eventDate: String,
    val publicLocation: String,
    val thumbnailUrl: String?,
    val authorNickname: String?,
    val shelterName: String?,
    val shelterPhone: String?,
)

/**
 * 한 번의 M1 조회 결과입니다.
 *
 * 폴링은 [analysisStatus]가 아니라 [latestRunStatus]와 [recommendedPollAfterMs]를 따릅니다.
 * `STALE`이 새 실행의 처리 중·실패 상태와 겹칠 때 [analysisStatus]가 `STALE`로 고정되기 때문입니다.
 */
data class MatchStatusSnapshot(
    val analysisStatus: AnalysisStatus,
    val latestRunStatus: AnalysisStatus?,
    val errorCode: String?,
    val recommendedPollAfterMs: Long?,
    val usingPreviousResult: Boolean,
    val candidateCount: Int,
    val candidates: List<MatchCandidate>,
)

sealed interface MatchStatusResult {
    data class Success(val snapshot: MatchStatusSnapshot) : MatchStatusResult

    /** 로그인이 필요합니다. */
    data class Unauthorized(val message: String) : MatchStatusResult

    /** 기준 게시물이 없거나 삭제됐습니다. */
    data class PostNotFound(val message: String) : MatchStatusResult

    /**
     * 작성자가 아니거나, 종료된 게시물이거나, 후보 조회 대상이 아닌 게시물입니다.
     *
     * 이 결과를 받으면 폴링을 멈추고 기존 결과 표시를 제거합니다.
     */
    data class NotAllowed(val message: String) : MatchStatusResult

    data class Failure(val message: String) : MatchStatusResult
}

/** 화면 상단에 표시하는 기준 게시물 요약입니다. */
data class MatchBaseSummary(
    val postId: Long,
    val name: String?,
    val species: String,
    val breedName: String?,
    val sex: String,
    val color: String?,
    val eventDate: String,
    val eventTime: String?,
    val publicLocation: String,
    val thumbnailUrl: String?,
)

sealed interface MatchBaseSummaryResult {
    data class Success(val summary: MatchBaseSummary) : MatchBaseSummaryResult

    /** 요약을 못 불러와도 후보 표시는 막지 않는다. */
    data object Unavailable : MatchBaseSummaryResult
}

sealed interface MatchRunRequestResult {
    /** 새 실행을 접수했거나, 이미 처리 중인 실행을 그대로 돌려받았습니다. */
    data class Accepted(
        val matchRunId: Long,
        val status: AnalysisStatus,
    ) : MatchRunRequestResult

    data class Unauthorized(val message: String) : MatchRunRequestResult

    data class PostNotFound(val message: String) : MatchRunRequestResult

    data class NotAllowed(val message: String) : MatchRunRequestResult

    data class Failure(val message: String) : MatchRunRequestResult
}

interface MatchRepository {
    /** M1 — 분석 상태와 후보를 조회합니다. */
    suspend fun getStatus(postId: Long): MatchStatusResult

    /** M2 — 유사도 분석 실행을 접수합니다. */
    suspend fun requestAnalysis(postId: Long): MatchRunRequestResult

    /** 화면 상단 요약용 기준 게시물 정보를 조회합니다. */
    suspend fun getBaseSummary(postId: Long): MatchBaseSummaryResult

    /** 비교 상세에 필요한 내 동물과 후보 동물 정보를 함께 조회합니다. */
    suspend fun getComparison(
        postId: Long,
        candidatePostId: Long,
    ): MatchComparisonResult
}

/** 비교 상세의 한쪽 동물입니다. 내 동물과 후보 동물에 같은 모양을 사용합니다. */
data class MatchComparisonAnimal(
    val postId: Long,
    val source: String?,
    val status: String?,
    val displayName: String,
    val attributeSummary: String,
    val eventDate: String,
    val eventTime: String?,
    val publicLocation: String,
    val currentLocation: String?,
    val featureText: String?,
    val thumbnailUrl: String?,
    val authorNickname: String?,
    val shelterName: String?,
    val shelterPhone: String?,
    val shelterAddress: String?,
    val shelterNoticeNo: String?,
    val shelterProcessState: String?,
    val chatAvailable: Boolean,
) {
    val isShelter: Boolean get() = source == "SHELTER"
}

data class MatchComparison(
    val mine: MatchComparisonAnimal,
    val candidate: MatchComparisonAnimal,
)

sealed interface MatchComparisonResult {
    data class Success(val comparison: MatchComparison) : MatchComparisonResult

    data class Unauthorized(val message: String) : MatchComparisonResult

    data class PostNotFound(val message: String) : MatchComparisonResult

    data class Failure(val message: String) : MatchComparisonResult
}
