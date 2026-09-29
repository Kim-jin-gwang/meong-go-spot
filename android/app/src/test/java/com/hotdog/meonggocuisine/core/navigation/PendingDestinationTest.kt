package com.hotdog.meonggocuisine.core.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingDestinationTest {
    @Test
    fun `채팅 시작은 식별자로 경로를 되살린다`() {
        assertEquals(PostChatStartRoute(1002L), PendingDestination.POST_CHAT_START.toRoute(1002L))
    }

    @Test
    fun `채팅 시작에 식별자가 없으면 경로를 만들지 않는다`() {
        assertNull(PendingDestination.POST_CHAT_START.toRoute(null))
    }

    @Test
    fun `식별자가 없는 목적지는 식별자 없이 되살린다`() {
        assertEquals(LostPostCreateRoute, PendingDestination.LOST_POST_CREATE.toRoute(null))
        assertEquals(ShelteringPostCreateRoute, PendingDestination.SHELTERING_POST_CREATE.toRoute(null))
        assertEquals(MyPageRoute, PendingDestination.MY_PAGE.toRoute(null))
    }

    @Test
    fun `식별자가 필요 없는 목적지는 남은 식별자를 무시한다`() {
        assertEquals(MyPageRoute, PendingDestination.MY_PAGE.toRoute(9999L))
    }

    @Test
    fun `되살린 경로는 모두 로그인이 필요한 경로다`() {
        PendingDestination.entries.forEach { destination ->
            val route = destination.toRoute(1002L)
            assert(route is AuthRequiredRoute) { "$destination 의 경로가 AuthRequiredRoute가 아니다" }
        }
    }

    @Test
    fun `저장한 이름으로 목적지를 다시 찾을 수 있다`() {
        PendingDestination.entries.forEach { destination ->
            assertEquals(
                destination,
                PendingDestination.entries.firstOrNull { it.name == destination.name },
            )
        }
    }
}
