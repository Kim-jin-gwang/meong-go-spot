package com.hotdog.meonggocuisine.feature.match.data

import com.hotdog.meonggocuisine.core.network.ApiErrorResponse
import com.hotdog.meonggocuisine.core.text.speciesLabel
import kotlinx.serialization.json.Json
import retrofit2.Response
import java.io.IOException
import javax.inject.Inject

class DefaultMatchRepository
    @Inject
    constructor(
        private val matchApi: MatchApi,
        private val json: Json,
    ) : MatchRepository {
        override suspend fun getStatus(postId: Long): MatchStatusResult =
            try {
                val response = matchApi.getCandidates(postId)
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    MatchStatusResult.Success(body.data.toSnapshot())
                } else {
                    when (response.errorCode()) {
                        "AUTH-002", "AUTH-003" -> MatchStatusResult.Unauthorized(LOGIN_REQUIRED)
                        "POST-001" -> MatchStatusResult.PostNotFound("게시물을 찾을 수 없습니다.")
                        "POST-002" -> MatchStatusResult.NotAllowed("본인 게시물에서만 유사 후보를 볼 수 있습니다.")
                        "POST-003" -> MatchStatusResult.NotAllowed("종료된 게시물은 유사도 분석을 사용할 수 없습니다.")
                        "MATCH-001" -> MatchStatusResult.NotAllowed("잃어버렸어요 게시물에서만 후보를 조회할 수 있습니다.")
                        else -> MatchStatusResult.Failure(STATUS_FAILURE)
                    }
                }
            } catch (_: IOException) {
                MatchStatusResult.Failure(CONNECTION_FAILURE)
            } catch (_: Exception) {
                MatchStatusResult.Failure(STATUS_FAILURE)
            }

        override suspend fun requestAnalysis(postId: Long): MatchRunRequestResult =
            try {
                val response = matchApi.requestMatchRun(postId)
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    MatchRunRequestResult.Accepted(
                        matchRunId = body.data.matchRunId,
                        status = AnalysisStatus.from(body.data.status),
                    )
                } else {
                    when (response.errorCode()) {
                        "AUTH-002", "AUTH-003" -> MatchRunRequestResult.Unauthorized(LOGIN_REQUIRED)
                        "POST-001" -> MatchRunRequestResult.PostNotFound("게시물을 찾을 수 없습니다.")
                        "POST-002" -> MatchRunRequestResult.NotAllowed("본인 게시물만 분석할 수 있습니다.")
                        "POST-003" -> MatchRunRequestResult.NotAllowed("종료된 게시물은 분석할 수 없습니다.")
                        "MATCH-001" -> MatchRunRequestResult.NotAllowed("잃어버렸어요 게시물만 분석할 수 있습니다.")
                        else -> MatchRunRequestResult.Failure(REQUEST_FAILURE)
                    }
                }
            } catch (_: IOException) {
                MatchRunRequestResult.Failure(CONNECTION_FAILURE)
            } catch (_: Exception) {
                MatchRunRequestResult.Failure(REQUEST_FAILURE)
            }

        override suspend fun getBaseSummary(postId: Long): MatchBaseSummaryResult =
            try {
                val response = matchApi.getBasePost(postId)
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    MatchBaseSummaryResult.Success(body.data.toSummary())
                } else {
                    MatchBaseSummaryResult.Unavailable
                }
            } catch (_: Exception) {
                MatchBaseSummaryResult.Unavailable
            }

        override suspend fun getComparison(
            postId: Long,
            candidatePostId: Long,
        ): MatchComparisonResult =
            try {
                val mine = matchApi.getBasePost(postId)
                val candidate = matchApi.getBasePost(candidatePostId)
                val mineBody = mine.body()
                val candidateBody = candidate.body()
                if (mine.isSuccessful && candidate.isSuccessful && mineBody != null && candidateBody != null) {
                    MatchComparisonResult.Success(
                        MatchComparison(
                            mine = mineBody.data.toComparisonAnimal(),
                            candidate = candidateBody.data.toComparisonAnimal(),
                        ),
                    )
                } else {
                    val code = if (!mine.isSuccessful) mine.errorCode() else candidate.errorCode()
                    when (code) {
                        "AUTH-002", "AUTH-003" -> MatchComparisonResult.Unauthorized(LOGIN_REQUIRED)
                        "POST-001" -> MatchComparisonResult.PostNotFound("게시물을 찾을 수 없습니다.")
                        else -> MatchComparisonResult.Failure(COMPARISON_FAILURE)
                    }
                }
            } catch (_: IOException) {
                MatchComparisonResult.Failure(CONNECTION_FAILURE)
            } catch (_: Exception) {
                MatchComparisonResult.Failure(COMPARISON_FAILURE)
            }

        private fun Response<*>.errorCode(): String? =
            errorBody()?.string()?.let { value ->
                runCatching { json.decodeFromString<ApiErrorResponse>(value).code }.getOrNull()
            }

        private companion object {
            const val LOGIN_REQUIRED = "로그인이 필요합니다."
            const val STATUS_FAILURE = "분석 상태를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요."
            const val REQUEST_FAILURE = "유사도 분석을 요청하지 못했습니다. 잠시 후 다시 시도해 주세요."
            const val CONNECTION_FAILURE = "서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요."
            const val COMPARISON_FAILURE = "비교 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요."
        }
    }

internal fun MatchCandidatesResponse.toSnapshot(): MatchStatusSnapshot {
    val candidates = candidates.sortedBy { it.rank }.map(MatchCandidateDto::toCandidate)
    return MatchStatusSnapshot(
        analysisStatus = AnalysisStatus.from(analysisStatus),
        latestRunStatus = latestRun?.status?.let(AnalysisStatus::from),
        errorCode = latestRun?.errorCode,
        recommendedPollAfterMs = recommendedPollAfterMs,
        usingPreviousResult = usingPreviousResult,
        candidateCount = latestRun?.candidateCount ?: candidates.size,
        candidates = candidates,
    )
}

private fun MatchCandidateDto.toCandidate(): MatchCandidate =
    MatchCandidate(
        rank = rank,
        postId = post.postId,
        source = post.source,
        status = post.status,
        name = post.name,
        species = post.species,
        breedName = post.breedName,
        sex = post.sex,
        color = post.color,
        eventDate = post.eventDate,
        publicLocation = post.publicLocation,
        thumbnailUrl = post.thumbnailUrl,
        authorNickname = post.author?.nickname,
        shelterName = post.shelter?.name,
        shelterPhone = post.shelter?.phone,
    )

internal fun MatchBasePostResponse.toSummary(): MatchBaseSummary =
    MatchBaseSummary(
        postId = postId,
        name = name?.takeIf(String::isNotBlank),
        species = species,
        breedName = breedName?.takeIf(String::isNotBlank),
        sex = sex,
        color = color?.takeIf(String::isNotBlank),
        eventDate = eventDate,
        eventTime = eventTime,
        publicLocation = eventLocation.publicLocation,
        thumbnailUrl = photos.sortedBy { it.sortOrder }.firstOrNull()?.url,
    )

internal fun MatchBasePostResponse.toComparisonAnimal(): MatchComparisonAnimal =
    MatchComparisonAnimal(
        postId = postId,
        source = source,
        status = status,
        displayName = comparisonDisplayName(),
        attributeSummary =
            listOfNotNull(
                breedName?.takeIf(String::isNotBlank),
                sexLabel(sex),
                color?.takeIf(String::isNotBlank),
            ).joinToString(" · ").ifBlank { speciesLabel(species) },
        eventDate = eventDate,
        eventTime = eventTime,
        publicLocation = eventLocation.publicLocation,
        currentLocation = currentLocation?.publicLocation,
        featureText = featureText?.takeIf(String::isNotBlank),
        thumbnailUrl = photos.sortedBy { it.sortOrder }.firstOrNull()?.url,
        authorNickname = author?.nickname,
        shelterName = shelter?.name,
        shelterPhone = shelter?.phone,
        shelterAddress = shelter?.address,
        shelterNoticeNo = shelter?.noticeNo,
        shelterProcessState = shelter?.processState,
        chatAvailable = chat?.available == true,
    )

/** 공공 보호동물은 이름이 없으므로 공고 번호로 구분합니다. */
private fun MatchBasePostResponse.comparisonDisplayName(): String =
    name?.takeIf(String::isNotBlank)
        ?: shelter?.noticeNo?.takeIf(String::isNotBlank)?.let { "보호소 #$it" }
        ?: breedName?.takeIf(String::isNotBlank)
        ?: speciesLabel(species)

private fun sexLabel(sex: String): String =
    when (sex) {
        "MALE" -> "수컷"
        "FEMALE" -> "암컷"
        else -> "성별 모름"
    }
