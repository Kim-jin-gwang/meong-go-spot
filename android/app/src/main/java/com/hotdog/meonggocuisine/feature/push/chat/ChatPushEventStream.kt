package com.hotdog.meonggocuisine.feature.push.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

interface ChatPushEventSource {
    val messages: Flow<ChatPushMessage>
}

interface ChatPushEventPublisher {
    fun publish(message: ChatPushMessage)
}

@Singleton
class ChatPushEventStream
    @Inject
    constructor() : ChatPushEventSource, ChatPushEventPublisher {
        private val mutableMessages = MutableSharedFlow<ChatPushMessage>(extraBufferCapacity = 1)
        override val messages: Flow<ChatPushMessage> = mutableMessages.asSharedFlow()

        override fun publish(message: ChatPushMessage) {
            mutableMessages.tryEmit(message)
        }
    }
