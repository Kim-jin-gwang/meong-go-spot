package com.hotdog.meonggocuisine.core.navigation

import kotlinx.serialization.Serializable

@Serializable
data object ChatListRoute : AuthRequiredRoute

@Serializable
data class ChatRoomRoute(
    val chatRoomId: Long,
    // 머리줄에 쓸 상대 이름. 목록에서 들어올 때만 안다 — C3 는 방의 상대를 내려주지 않아서,
    // 알림이나 비교 상세에서 바로 들어오면 받은 메시지에서 찾는다.
    val otherNickname: String? = null,
) : AuthRequiredRoute

@Serializable
data class PostChatStartRoute(
    val postId: Long,
) : AuthRequiredRoute
