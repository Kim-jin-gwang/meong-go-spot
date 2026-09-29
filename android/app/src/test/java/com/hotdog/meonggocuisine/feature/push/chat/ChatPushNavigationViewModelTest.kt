package com.hotdog.meonggocuisine.feature.push.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatPushNavigationViewModelTest {
    @Test
    fun `알림 intent를 대상 채팅방 이동으로 보관한다`() {
        val viewModel = ChatPushNavigationViewModel()

        viewModel.accept(ChatPushContract.ACTION_OPEN_CHAT, chatRoomId = 11L, messageId = 21L)

        assertEquals(PendingChatNavigation(11L, 21L), viewModel.pendingNavigation.value)
    }

    @Test
    fun `잘못된 action과 식별자는 무시한다`() {
        val viewModel = ChatPushNavigationViewModel()

        viewModel.accept("other", chatRoomId = 11L, messageId = 21L)
        viewModel.accept(ChatPushContract.ACTION_OPEN_CHAT, chatRoomId = 0L, messageId = 21L)

        assertNull(viewModel.pendingNavigation.value)
    }

    @Test
    fun `처리한 messageId만 pending 이동에서 제거한다`() {
        val viewModel = ChatPushNavigationViewModel()
        viewModel.accept(ChatPushContract.ACTION_OPEN_CHAT, chatRoomId = 11L, messageId = 21L)

        viewModel.consume(20L)
        assertEquals(PendingChatNavigation(11L, 21L), viewModel.pendingNavigation.value)

        viewModel.consume(21L)
        assertNull(viewModel.pendingNavigation.value)
    }
}
