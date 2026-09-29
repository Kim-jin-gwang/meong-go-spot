package com.hotdog.meonggocuisine.core.navigation

import kotlinx.serialization.Serializable

/**
 * 공고가 끝나 입양을 기다리는 동물을 카드로 넘겨 보는 화면입니다.
 *
 * 로그인이 필요하다(2026-09-25). 넘긴 동물을 기억해 다시 보여 주지 않으려면 서버가 누가 넘겼는지
 * 알아야 하고, AD1 자체가 인증을 요구한다. 그 전에는 보기만 하는 화면이라 열려 있었다.
 */
@Serializable
data object AdoptionRoute : AuthRequiredRoute

/**
 * 소개팅에서 카드를 눌렀을 때 그 위에 뜨는 상세입니다.
 *
 * 목적지가 [PostDetailRoute] 와 같은 화면이지만 route 를 따로 둔다 — 같은 route 를 화면 목적지와
 * 대화상자 목적지 양쪽에 등록할 수 없고, 소개팅에서는 카드가 뒤에 남아 있어야 "보던 자리로
 * 돌아온다" 가 성립한다. 카드 더미를 덮고 화면이 통째로 바뀌면 넘기던 흐름이 끊긴다.
 */
@Serializable
data class AdoptionAnimalDetailRoute(
    val postId: Long,
    /**
     * 열 때의 찜 상태. 팝업의 하트가 처음부터 맞게 보이도록 들고 간다.
     *
     * 팝업이 따로 조회하지 않는 건 카드·히스토리가 이미 아는 값이기 때문이다 — 한 번 더 물으면
     * 창이 뜨고 나서 하트가 뒤늦게 바뀐다. 이 뒤의 변화는 팝업이 스스로 들고 있다(2026-09-25).
     */
    val favorited: Boolean = false,
) : PublicRoute
