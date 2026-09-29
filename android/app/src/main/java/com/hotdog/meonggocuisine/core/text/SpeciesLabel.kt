package com.hotdog.meonggocuisine.core.text

/**
 * 축종을 사람이 부르는 말로 옮깁니다.
 *
 * 화면마다 따로 적던 때는 같은 게시물이 목록에서는 "강아지", 유사도 분석 결과에서는 "개" 로
 * 불렸다(2026-09-25). 등록 화면이 이미 "강아지 / 고양이" 로 고르게 하므로 그쪽에 맞춘다.
 *
 * 이름과 품종이 비어 있을 때 이 말이 대신 나온다. 개·고양이만 싣는 서비스라 그 밖은 나올
 * 일이 거의 없지만, 원천이 새 값을 주면 "동물" 로 받는다.
 */
fun speciesLabel(species: String?): String =
    when (species) {
        "DOG" -> "강아지"
        "CAT" -> "고양이"
        else -> "동물"
    }
