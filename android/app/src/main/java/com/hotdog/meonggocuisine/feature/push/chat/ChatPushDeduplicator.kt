package com.hotdog.meonggocuisine.feature.push.chat

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

interface ChatPushDeduplicator {
    fun markIfNew(messageId: Long): Boolean
}

@Singleton
class SharedPreferencesChatPushDeduplicator
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : ChatPushDeduplicator {
        private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

        @Synchronized
        override fun markIfNew(messageId: Long): Boolean {
            val seenIds =
                preferences
                    .getString(KEY_SEEN_MESSAGE_IDS, null)
                    .orEmpty()
                    .split(SEPARATOR)
                    .mapNotNull(String::toLongOrNull)
            if (messageId in seenIds) return false

            val updatedIds = (seenIds + messageId).takeLast(MAX_SEEN_MESSAGE_IDS)
            // 알림 직후 프로세스가 끝나도 같은 FCM 재전달을 다시 표시하지 않도록 먼저 확정한다.
            preferences.edit().putString(KEY_SEEN_MESSAGE_IDS, updatedIds.joinToString(SEPARATOR)).commit()
            return true
        }

        private companion object {
            const val PREFERENCES_NAME = "meonggocuisine_chat_push"
            const val KEY_SEEN_MESSAGE_IDS = "seen_message_ids"
            const val SEPARATOR = ","
            const val MAX_SEEN_MESSAGE_IDS = 100
        }
    }
