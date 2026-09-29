package com.hotdog.meonggocuisine.feature.push.chat

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class ChatPushModule {
    @Binds
    abstract fun bindChatPushEventSource(stream: ChatPushEventStream): ChatPushEventSource

    @Binds
    abstract fun bindChatPushEventPublisher(stream: ChatPushEventStream): ChatPushEventPublisher

    @Binds
    abstract fun bindChatPushDeduplicator(deduplicator: SharedPreferencesChatPushDeduplicator): ChatPushDeduplicator

    @Binds
    abstract fun bindChatPushNotificationPublisher(publisher: AndroidChatPushNotificationPublisher): ChatPushNotificationPublisher

    companion object {
        @Provides
        fun provideChatPushMessageParser(): ChatPushMessageParser = ChatPushMessageParser()
    }
}
