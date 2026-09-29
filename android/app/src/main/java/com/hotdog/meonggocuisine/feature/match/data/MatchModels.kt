package com.hotdog.meonggocuisine.feature.match.data

import kotlinx.serialization.Serializable

/** M1 매칭 상태·후보 응답입니다. */
@Serializable
data class MatchCandidatesResponse(
    val postId: Long,
    val analysisStatus: String,
    val latestRun: MatchRunSummaryDto? = null,
    val resultRunId: Long? = null,
    val recommendedPollAfterMs: Long? = null,
    val usingPreviousResult: Boolean = false,
    val candidates: List<MatchCandidateDto> = emptyList(),
)

@Serializable
data class MatchRunSummaryDto(
    val matchRunId: Long,
    val status: String,
    val candidateCount: Int? = null,
    val errorCode: String? = null,
    val startedAt: String? = null,
    val completedAt: String? = null,
)

@Serializable
data class MatchCandidateDto(
    val rank: Int,
    val post: MatchCandidatePostDto,
)

@Serializable
data class MatchCandidatePostDto(
    val postId: Long,
    val type: String,
    val source: String,
    val status: String,
    val name: String? = null,
    val species: String,
    val breedName: String? = null,
    val sex: String,
    val color: String? = null,
    val eventDate: String,
    val publicLocation: String,
    val thumbnailUrl: String? = null,
    val author: MatchCandidateAuthorDto? = null,
    val shelter: MatchCandidateShelterDto? = null,
)

@Serializable
data class MatchCandidateAuthorDto(
    // M1 후보 응답의 author 는 nickname 만 담는다(api-spec §M1 — 회원 ID는 표시용으로 쓰지 않는다).
    // P2 상세는 memberId 를 주므로 옵셔널로 받는다.
    val memberId: Long? = null,
    val nickname: String,
)

@Serializable
data class MatchCandidateShelterDto(
    val name: String,
    val phone: String? = null,
)

/** M2 유사도 분석 실행 접수 응답입니다. */
@Serializable
data class MatchRunResponse(
    val postId: Long,
    val matchRunId: Long,
    val status: String,
    val createdAt: String,
)

/**
 * 이 기능이 보는 게시물입니다.
 *
 * M1은 `postId`와 후보 요약만 내려주므로, 기준 게시물 요약과 비교 상세에 필요한 필드를 게시물
 * 상세에서 읽습니다. 게시물 기능을 직접 참조하지 않기 위해 필요한 필드만 정의합니다.
 */
@Serializable
data class MatchBasePostResponse(
    val postId: Long,
    val source: String? = null,
    val status: String? = null,
    val name: String? = null,
    val species: String,
    val breedName: String? = null,
    val sex: String,
    val color: String? = null,
    val eventDate: String,
    val eventTime: String? = null,
    val featureText: String? = null,
    val eventLocation: MatchBaseLocationResponse,
    val currentLocation: MatchBaseLocationResponse? = null,
    val photos: List<MatchBasePhotoResponse> = emptyList(),
    val author: MatchCandidateAuthorDto? = null,
    val shelter: MatchShelterDto? = null,
    val chat: MatchChatDto? = null,
)

/** 공공 보호동물의 보호센터 정보입니다. */
@Serializable
data class MatchShelterDto(
    val name: String,
    val phone: String? = null,
    val address: String? = null,
    val noticeNo: String? = null,
    val processState: String? = null,
)

@Serializable
data class MatchChatDto(
    val available: Boolean,
    val reason: String? = null,
)

@Serializable
data class MatchBaseLocationResponse(
    val publicLocation: String,
)

@Serializable
data class MatchBasePhotoResponse(
    val url: String,
    val sortOrder: Int,
)
