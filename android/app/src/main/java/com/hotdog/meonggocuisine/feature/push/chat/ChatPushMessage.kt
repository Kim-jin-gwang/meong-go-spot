package com.hotdog.meonggocuisine.feature.push.chat

data class ChatPushMessage(
    val chatRoomId: Long,
    val messageId: Long,
    val postId: Long,
)

class ChatPushMessageParser {
    fun parse(data: Map<String, String>): ChatPushMessage? {
        if (data[ChatPushContract.KEY_TYPE] != ChatPushContract.TYPE_CHAT_MESSAGE) return null
        val chatRoomId = data.positiveLong(ChatPushContract.KEY_CHAT_ROOM_ID) ?: return null
        val messageId = data.positiveLong(ChatPushContract.KEY_MESSAGE_ID) ?: return null
        val postId = data.positiveLong(ChatPushContract.KEY_POST_ID) ?: return null
        return ChatPushMessage(chatRoomId = chatRoomId, messageId = messageId, postId = postId)
    }

    private fun Map<String, String>.positiveLong(key: String): Long? = get(key)?.toLongOrNull()?.takeIf { it > 0L }
}

object ChatPushContract {
    const val TYPE_CHAT_MESSAGE = "CHAT_MESSAGE"
    const val KEY_TYPE = "type"
    const val KEY_CHAT_ROOM_ID = "chatRoomId"
    const val KEY_MESSAGE_ID = "messageId"
    const val KEY_POST_ID = "postId"

    const val ACTION_OPEN_CHAT = "com.hotdog.meonggocuisine.action.OPEN_CHAT"
    const val EXTRA_CHAT_ROOM_ID = "chatRoomId"
    const val EXTRA_MESSAGE_ID = "messageId"
}
