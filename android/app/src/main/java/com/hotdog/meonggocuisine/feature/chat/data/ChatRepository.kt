package com.hotdog.meonggocuisine.feature.chat.data

data class ChatMessage(
    val messageId: Long,
    val senderNickname: String,
    val content: String,
    val createdAt: String,
    val mine: Boolean,
)

/** 대화가 걸린 게시물. 채팅방 머리줄 밑에 어느 아이 이야기인지 밝히는 데 쓴다. */
data class ChatRoomPost(
    val postId: Long,
    val type: String,
    val status: String,
    val name: String?,
    val species: String?,
    val breedName: String?,
    val sex: String?,
    val thumbnailUrl: String?,
)

data class ChatMessagePage(
    val post: ChatRoomPost? = null,
    val otherNickname: String? = null,
    val readOnly: Boolean,
    val messages: List<ChatMessage>,
    val hasNext: Boolean,
    val nextCursor: String?,
    val pollAfterMs: Long,
    val nextAfterMessageId: Long? = null,
    val myLastReadMessageId: Long? = null,
    val otherLastReadMessageId: Long? = null,
)

data class ChatRoomSummary(
    val chatRoomId: Long,
    val postId: Long,
    val postType: String,
    val postStatus: String,
    val postName: String? = null,
    val postSpecies: String? = null,
    val postBreedName: String? = null,
    val postSex: String? = null,
    val thumbnailUrl: String?,
    val otherNickname: String,
    val lastMessageContent: String?,
    val lastMessageAt: String?,
    val readOnly: Boolean,
    val lastMessageId: Long? = null,
    val unreadCount: Int = 0,
    val hasUnread: Boolean = false,
)

sealed interface ChatRoomStartResult {
    data class Success(val chatRoomId: Long) : ChatRoomStartResult

    data class NotAllowed(val message: String) : ChatRoomStartResult

    data class Failure(val message: String) : ChatRoomStartResult
}

sealed interface ChatRoomListResult {
    data class Success(
        val rooms: List<ChatRoomSummary>,
        val hasNext: Boolean,
        val nextCursor: String?,
    ) : ChatRoomListResult

    data class InvalidCursor(val message: String) : ChatRoomListResult

    data class Failure(val message: String) : ChatRoomListResult
}

sealed interface ChatMessagesResult {
    data class Success(val page: ChatMessagePage) : ChatMessagesResult

    data class RoomNotFound(val message: String) : ChatMessagesResult

    data class InvalidCursor(val message: String) : ChatMessagesResult

    data class InvalidAfterMessage(val message: String) : ChatMessagesResult

    data class Failure(val message: String) : ChatMessagesResult
}

sealed interface ChatReadResult {
    data class Success(val lastReadMessageId: Long) : ChatReadResult

    data class RoomNotFound(val message: String) : ChatReadResult

    data class InvalidMessage(val message: String) : ChatReadResult

    data class Failure(val message: String) : ChatReadResult
}

sealed interface ChatMessageSendResult {
    data class Success(val message: ChatMessage) : ChatMessageSendResult

    data class ReadOnly(val message: String) : ChatMessageSendResult

    data class RoomNotFound(val message: String) : ChatMessageSendResult

    data class InvalidInput(val message: String) : ChatMessageSendResult

    data class Failure(val message: String) : ChatMessageSendResult
}

interface ChatRepository {
    suspend fun startChatRoom(postId: Long): ChatRoomStartResult

    suspend fun getChatRooms(cursor: String?): ChatRoomListResult

    suspend fun getMessages(
        chatRoomId: Long,
        cursor: String? = null,
        afterMessageId: Long? = null,
    ): ChatMessagesResult

    suspend fun updateLastRead(
        chatRoomId: Long,
        lastReadMessageId: Long,
    ): ChatReadResult

    suspend fun sendMessage(
        chatRoomId: Long,
        clientMessageId: String,
        content: String,
    ): ChatMessageSendResult
}
