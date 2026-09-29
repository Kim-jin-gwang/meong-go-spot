package com.hotdog.meonggocuisine.feature.chat.data

import kotlinx.serialization.Serializable

@Serializable
data class ChatMemberDto(
    val memberId: Long,
    val nickname: String,
)

@Serializable
data class ChatRoomStartResponse(
    val chatRoomId: Long,
    val postId: Long,
    val otherMember: ChatMemberDto,
    val lastMessageAt: String? = null,
    val createdAt: String,
)

@Serializable
data class ChatRoomListResponse(
    val items: List<ChatRoomSummaryDto>,
    val page: ChatPageDto,
)

@Serializable
data class ChatRoomSummaryDto(
    val chatRoomId: Long,
    val post: ChatRoomPostDto,
    val otherMember: ChatMemberDto,
    val lastMessage: ChatLastMessageDto? = null,
    val unreadCount: Int = 0,
    val hasUnread: Boolean = false,
    val readOnly: Boolean = false,
)

@Serializable
data class ChatRoomPostDto(
    val postId: Long,
    val type: String,
    val status: String,
    // 이름과 품종은 등록할 때 비워 둘 수 있다. 서버가 아직 모르는 판이면 아예 오지 않는다.
    val name: String? = null,
    val species: String? = null,
    val breedName: String? = null,
    val sex: String? = null,
    val thumbnailUrl: String? = null,
)

@Serializable
data class ChatLastMessageDto(
    val messageId: Long,
    val content: String,
    val createdAt: String,
)

@Serializable
data class ChatPageDto(
    val size: Int,
    val hasNext: Boolean,
    val nextCursor: String? = null,
    val nextAfterMessageId: Long? = null,
)

@Serializable
data class ChatMessagesResponse(
    val chatRoomId: Long,
    // 어느 게시물의 대화이고 상대가 누구인지. 서버가 아직 이 값을 모르는 판이면 오지 않는다.
    val post: ChatRoomPostDto? = null,
    val otherMember: ChatMemberDto? = null,
    val readOnly: Boolean = false,
    val myLastReadMessageId: Long? = null,
    val otherLastReadMessageId: Long? = null,
    val items: List<ChatMessageDto>,
    val page: ChatPageDto,
    val pollAfterMs: Long = 3_000L,
)

@Serializable
data class ChatMessageDto(
    val messageId: Long,
    val sender: ChatMemberDto,
    val content: String,
    val createdAt: String,
)

@Serializable
data class ChatMessageSendRequest(
    val clientMessageId: String,
    val content: String,
)

@Serializable
data class ChatReadUpdateRequest(
    val lastReadMessageId: Long,
)

@Serializable
data class ChatReadUpdateResponse(
    val chatRoomId: Long,
    val lastReadMessageId: Long,
)
