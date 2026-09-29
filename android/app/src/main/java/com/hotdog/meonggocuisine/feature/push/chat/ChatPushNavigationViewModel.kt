package com.hotdog.meonggocuisine.feature.push.chat

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class PendingChatNavigation(
    val chatRoomId: Long,
    val messageId: Long,
)

@HiltViewModel
class ChatPushNavigationViewModel
    @Inject
    constructor() : ViewModel() {
        private val mutablePendingNavigation = MutableStateFlow<PendingChatNavigation?>(null)
        val pendingNavigation: StateFlow<PendingChatNavigation?> = mutablePendingNavigation.asStateFlow()

        fun accept(
            action: String?,
            chatRoomId: Long,
            messageId: Long,
        ) {
            if (action != ChatPushContract.ACTION_OPEN_CHAT || chatRoomId <= 0L || messageId <= 0L) return
            val current = mutablePendingNavigation.value
            if (current?.messageId == messageId) return
            mutablePendingNavigation.value = PendingChatNavigation(chatRoomId, messageId)
        }

        fun consume(messageId: Long) {
            if (mutablePendingNavigation.value?.messageId == messageId) {
                mutablePendingNavigation.value = null
            }
        }
    }
