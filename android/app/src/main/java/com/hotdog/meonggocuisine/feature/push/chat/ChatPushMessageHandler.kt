package com.hotdog.meonggocuisine.feature.push.chat

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatPushMessageHandler
    @Inject
    constructor(
        private val parser: ChatPushMessageParser,
        private val deduplicator: ChatPushDeduplicator,
        private val eventPublisher: ChatPushEventPublisher,
        private val visibilityTracker: ChatPushVisibilityTracker,
        private val notificationPublisher: ChatPushNotificationPublisher,
    ) {
        fun handle(data: Map<String, String>) {
            val message = parser.parse(data) ?: return
            if (!deduplicator.markIfNew(message.messageId)) return
            eventPublisher.publish(message)
            if (visibilityTracker.isVisible(message.chatRoomId)) return
            notificationPublisher.show(message)
        }
    }
