package com.hotdog.meonggocuisine.feature.match.ui

import com.hotdog.meonggocuisine.feature.match.data.MatchComparison

data class MatchComparisonUiState(
    val isLoading: Boolean = true,
    val comparison: MatchComparison? = null,
    val errorMessage: String? = null,
) {
    /**
     * 후보 확인을 위한 정성적 근거입니다.
     *
     * 유사도 점수와 정확한 거리는 M1이 응답하지 않으며 표시하지 않습니다. 대신 임계값을 통과한
     * 후보라는 사실, 사건 시점 관계, 공개 지역만으로 사용자가 직접 확인하도록 안내합니다
     * (`docs/product/wireframes/README.md` 08).
     */
    val recommendationReasons: List<String>
        get() {
            val comparison = comparison ?: return emptyList()
            val mine = comparison.mine
            val candidate = comparison.candidate
            return buildList {
                add("외형이 닮은 아이로 찾았어요")
                if (candidate.eventDate > mine.eventDate) {
                    add("실종 이후인 ${candidate.eventDate.toDisplayDate()}에 발견되었어요")
                }
                add("${candidate.publicLocation}에서 발견되었어요")
            }
        }
}

internal fun String.toDisplayDate(): String = replace("-", ".")
