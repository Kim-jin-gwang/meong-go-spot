package com.hotdog.meonggocuisine.feature.adoption.data

import kotlinx.serialization.Serializable

/**
 * 공고가 끝나 입양을 기다리는 보호 동물입니다.
 *
 * 동물보호법 제34조제1항제1호에 따라 공고한 날부터 10일이 지나면 지자체가 소유권을 취득하고,
 * 그때부터 입양이 가능해진다. 공고 중인 동물은 아직 원 소유자의 것이라 서버가 후보에서 뺀다.
 *
 * 보호소 이름·전화번호·주소는 담지 않는다. 비로그인도 보는 목록이라 계약이 금지하며, 연락처는
 * 로그인 뒤 게시물 상세에서만 확인한다 (docs/product/adoption-discovery-mvp.md §4).
 */
@Serializable
data class AdoptionAnimal(
    val postId: Long,
    val species: Species,
    val breedName: String? = null,
    val sex: Sex,
    val color: String? = null,
    val publicLocation: String,
    val thumbnailUrl: String? = null,
    val noticeEndDate: String,
    /**
     * 공고 종료일로부터 조회일까지의 일수입니다.
     *
     * 실제 입소일부터 센 총 보호 기간이 아니므로 화면에 `보호소에서 N일째`로 바꾸어 쓰지 않는다
     * (계약 §4).
     */
    val daysSinceNoticeEnd: Long,
    val lastSyncedAt: String,
    val favorited: Boolean = false,
)

@Serializable
enum class Species(val label: String) {
    DOG("개"),
    CAT("고양이"),
}

@Serializable
enum class Sex(val label: String) {
    MALE("수컷"),
    FEMALE("암컷"),
    UNKNOWN("성별 미상"),
}

/**
 * 축종 필터. 개가 전체의 약 85%라 필터가 없으면 고양이를 찾는 사용자가 한참 넘겨야 한다.
 *
 * [ALL]은 서버에 `species`를 보내지 않는 것과 같다 (api-spec AD1).
 */
enum class SpeciesFilter(val label: String, val query: String?) {
    ALL("전체", null),
    DOG("개", "DOG"),
    CAT("고양이", "CAT"),
}

/**
 * 성별 필터 (api-spec AD1, 2026-09-25 추가).
 *
 * 고르면 원천 성별이 `UNKNOWN` 인 동물은 빠진다 — 고른 성별이 맞는지 확인할 수 없는 건을 섞으면
 * 필터가 뜻을 잃는다. 그래서 [Sex.UNKNOWN] 에 대응하는 선택지를 두지 않는다.
 */
enum class SexFilter(val label: String, val query: String?) {
    ALL("전체", null),
    MALE("수컷", "MALE"),
    FEMALE("암컷", "FEMALE"),
}
