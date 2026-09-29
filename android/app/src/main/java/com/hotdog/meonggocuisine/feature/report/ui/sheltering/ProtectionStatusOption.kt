package com.hotdog.meonggocuisine.feature.report.ui.sheltering

/**
 * 발견한 동물을 지금 어떻게 두고 있는지입니다.
 *
 * 서버에는 이 값을 위한 칸이 따로 없다. 특징 글 맨 앞에 `현재 보호 상태: …` 한 줄로 붙여 보내고
 * 상세 화면이 그 줄을 다시 잘라 쓴다([com.hotdog.meonggocuisine.feature.report.data.DefaultShelteringPostCreateRepository]).
 * 그래서 값은 그냥 글자이고, 목록을 정하는 건 화면의 몫이다 — 아무 글이나 받던 칸을 고르는 칸으로
 * 바꾼 이유도 그거다. 매번 다른 말로 적히면 주인이 상태를 읽어 낼 수 없다.
 *
 * 줄바꿈이 없는 짧은 말만 쓴다. 특징 글에 한 줄로 얹어 보내므로 값에 줄바꿈이 들어가면 상세에서
 * 다시 잘라 낼 때 어긋난다.
 */
enum class ProtectionStatusOption(val label: String) {
    SHELTER("보호소"),
    TEMPORARY("임시보호"),
    HOSPITAL("병원"),
    HANDOVER_PLANNED("인계예정"),

    /**
     * 못 데려와서 발견한 자리에 그대로 있는 경우.
     *
     * 앞의 넷과 성격이 다르다 — 주인 입장에서는 지금 그 자리로 가야 하는 상황이다. 이게 없으면
     * 길고양이나 대형견처럼 데려올 수 없었던 사람이 `임시보호` 를 잘못 고르게 된다.
     */
    AT_FOUND_PLACE("발견 장소"),
    OTHER("기타"),
    ;

    companion object {
        /** 저장된 글자에 맞는 선택지. 예전 게시물이나 손으로 적힌 값이면 null 이라 아무것도 안 고른 상태가 된다. */
        fun from(label: String): ProtectionStatusOption? = entries.firstOrNull { it.label == label }
    }
}
