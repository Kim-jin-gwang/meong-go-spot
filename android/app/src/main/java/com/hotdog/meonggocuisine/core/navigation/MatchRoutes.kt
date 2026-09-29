package com.hotdog.meonggocuisine.core.navigation

import kotlinx.serialization.Serializable

/**
 * 유사도 분석 진행 경로입니다.
 *
 * [animalName]은 화면 문구에만 쓰는 표시값입니다. M1 응답에 기준 게시물의 이름이 없어
 * 상세 화면에서 전달하며, 없으면 이름 없는 문구를 사용합니다.
 */
@Serializable
data class MatchProgressRoute(
    val postId: Long,
    val animalName: String? = null,
) : AuthRequiredRoute

/** 유사 후보 목록 경로입니다. [animalName]은 [MatchProgressRoute]와 같은 표시값입니다. */
@Serializable
data class MatchCandidatesRoute(
    val postId: Long,
    val animalName: String? = null,
) : AuthRequiredRoute

@Serializable
data class MatchComparisonRoute(
    val postId: Long,
    val candidatePostId: Long,
) : AuthRequiredRoute
