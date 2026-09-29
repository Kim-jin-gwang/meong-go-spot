package com.hotdog.meonggocuisine.feature.push.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPushMessageHandlerTest {
    @Test
    fun `유효한 채팅 payload는 시스템 알림으로 전달한다`() {
        val fixture = Fixture()

        fixture.handler.handle(validPayload())

        assertEquals(listOf(ChatPushMessage(11L, 21L, 31L)), fixture.publisher.messages)
        assertEquals(listOf(ChatPushMessage(11L, 21L, 31L)), fixture.eventPublisher.messages)
    }

    @Test
    fun `필수 식별자가 없거나 양수가 아니면 무시한다`() {
        val parser = ChatPushMessageParser()

        assertNull(parser.parse(validPayload().minus(ChatPushContract.KEY_MESSAGE_ID)))
        assertNull(parser.parse(validPayload().plus(ChatPushContract.KEY_CHAT_ROOM_ID to "0")))
        assertNull(parser.parse(validPayload().plus(ChatPushContract.KEY_TYPE to "OTHER")))
    }

    @Test
    fun `같은 messageId는 한 번만 알린다`() {
        val fixture = Fixture()

        fixture.handler.handle(validPayload())
        fixture.handler.handle(validPayload())

        assertEquals(1, fixture.publisher.messages.size)
        assertEquals(1, fixture.eventPublisher.messages.size)
    }

    @Test
    fun `foreground에서 같은 채팅방을 보고 있으면 알림을 억제한다`() {
        val fixture = Fixture()
        fixture.visibilityTracker.setAppForeground(true)
        fixture.visibilityTracker.setChatRoomVisible(11L, true)

        fixture.handler.handle(validPayload())

        assertTrue(fixture.publisher.messages.isEmpty())
        assertEquals(1, fixture.eventPublisher.messages.size)
    }

    @Test
    fun `같은 방이어도 앱이 background이면 알림을 표시한다`() {
        val fixture = Fixture()
        fixture.visibilityTracker.setChatRoomVisible(11L, true)
        fixture.visibilityTracker.setAppForeground(false)

        fixture.handler.handle(validPayload())

        assertEquals(1, fixture.publisher.messages.size)
    }

    @Test
    fun `visibility tracker는 앱과 채팅방이 모두 보일 때만 true다`() {
        val tracker = ChatPushVisibilityTracker()
        tracker.setChatRoomVisible(11L, true)
        assertFalse(tracker.isVisible(11L))

        tracker.setAppForeground(true)
        assertTrue(tracker.isVisible(11L))

        tracker.setChatRoomVisible(12L, true)
        tracker.setChatRoomVisible(11L, false)
        assertTrue(tracker.isVisible(12L))

        tracker.setChatRoomVisible(12L, false)
        assertFalse(tracker.isVisible(11L))
    }

    private class Fixture {
        val publisher = FakePublisher()
        val eventPublisher = FakeEventPublisher()
        val visibilityTracker = ChatPushVisibilityTracker()
        val handler =
            ChatPushMessageHandler(
                parser = ChatPushMessageParser(),
                deduplicator = InMemoryDeduplicator(),
                eventPublisher = eventPublisher,
                visibilityTracker = visibilityTracker,
                notificationPublisher = publisher,
            )
    }

    private class InMemoryDeduplicator : ChatPushDeduplicator {
        private val messageIds = mutableSetOf<Long>()

        override fun markIfNew(messageId: Long): Boolean = messageIds.add(messageId)
    }

    private class FakePublisher : ChatPushNotificationPublisher {
        val messages = mutableListOf<ChatPushMessage>()

        override fun show(message: ChatPushMessage) {
            messages += message
        }
    }

    private class FakeEventPublisher : ChatPushEventPublisher {
        val messages = mutableListOf<ChatPushMessage>()

        override fun publish(message: ChatPushMessage) {
            messages += message
        }
    }

    private companion object {
        fun validPayload(): Map<String, String> =
            mapOf(
                ChatPushContract.KEY_TYPE to ChatPushContract.TYPE_CHAT_MESSAGE,
                ChatPushContract.KEY_CHAT_ROOM_ID to "11",
                ChatPushContract.KEY_MESSAGE_ID to "21",
                ChatPushContract.KEY_POST_ID to "31",
            )
    }
}
