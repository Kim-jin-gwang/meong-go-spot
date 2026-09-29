package com.hotdog.meonggocuisine.feature.push.chat

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatPushVisibilityTracker
    @Inject
    constructor() {
        @Volatile
        private var isAppForeground = false

        @Volatile
        private var visibleChatRoomId: Long? = null

        fun setAppForeground(isForeground: Boolean) {
            isAppForeground = isForeground
        }

        @Synchronized
        fun setChatRoomVisible(
            chatRoomId: Long,
            isVisible: Boolean,
        ) {
            if (isVisible) {
                visibleChatRoomId = chatRoomId
            } else if (visibleChatRoomId == chatRoomId) {
                visibleChatRoomId = null
            }
        }

        fun isVisible(chatRoomId: Long): Boolean = isAppForeground && visibleChatRoomId == chatRoomId
    }
