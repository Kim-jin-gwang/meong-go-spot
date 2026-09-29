package com.hotdog.meonggocuisine.feature.community.data

/**
 * 목록 화면의 조회 조건입니다.
 *
 * 필터 시트가 세 값을 함께 고쳐 `적용하기` 한 번으로 넘기므로 하나로 묶어 둔다. 따로 두면 적용
 * 한 번에 상태가 세 번 바뀌어 목록을 세 번 다시 부른다.
 *
 * 사건 날짜 범위는 여기 없다. 서버가 받는 목록 파라미터에 날짜가 없기 때문이다
 * (`PostListInputPolicy` 화이트리스트: type·species·sex·breedName·regionCode·color·source·sort).
 * 응답에는 `eventDate` 가 오지만 그건 받아 온 페이지 안에서만 걸러낼 수 있어 커서 페이지네이션과
 * 맞지 않는다 — 첫 페이지가 전부 기간 밖이면 뒤 페이지에 있어도 빈 화면이 된다. 서버가 걸러
 * 주게 된 뒤에 넣는다.
 */
data class PostListFilter(
    val species: SpeciesFilter = SpeciesFilter.ALL,
    val sex: SexFilter = SexFilter.ALL,
    val sort: PostListSort = PostListSort.LATEST,
) {
    /**
     * 기본값보다 좁혀 둔 조건이 있는지입니다.
     *
     * 목록 범위를 줄이는 건 축종과 성별뿐이라 둘만 센다. 정렬은 늘 둘 중 하나여서 켜고 끄는
     * 개념이 없다. 검색줄의 필터 버튼에 켜짐 표시를 띄울지 이 값으로 정한다.
     */
    val isNarrowed: Boolean
        get() = species != SpeciesFilter.ALL || sex != SexFilter.ALL
}

/** 축종 조건. [apiValue] 가 null 이면 파라미터를 보내지 않아 전체가 된다. */
enum class SpeciesFilter(val apiValue: String?) {
    ALL(null),
    DOG("DOG"),
    CAT("CAT"),
}

/**
 * 성별 조건. [apiValue] 가 null 이면 파라미터를 보내지 않아 전체가 된다.
 *
 * `UNKNOWN` 은 고르는 값으로 두지 않는다. 성별 미상은 보호소 데이터에 흔한데, 그것만 따로 보려는
 * 사람은 없기 때문이다. 다만 수컷이나 암컷을 고르면 미상인 게시물은 목록에서 빠진다.
 */
enum class SexFilter(val apiValue: String?) {
    ALL(null),
    MALE("MALE"),
    FEMALE("FEMALE"),
}
