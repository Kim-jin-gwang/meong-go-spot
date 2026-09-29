package com.hotdog.meonggocuisine.feature.chat.ui

import com.hotdog.meonggocuisine.feature.chat.data.ChatMessageSendResult
import com.hotdog.meonggocuisine.feature.chat.data.ChatMessagesResult
import com.hotdog.meonggocuisine.feature.chat.data.ChatReadResult
import com.hotdog.meonggocuisine.feature.chat.data.ChatRepository
import com.hotdog.meonggocuisine.feature.chat.data.ChatRoomListResult
import com.hotdog.meonggocuisine.feature.chat.data.ChatRoomStartResult
import com.hotdog.meonggocuisine.feature.chat.data.ChatRoomSummary
import com.hotdog.meonggocuisine.feature.push.chat.ChatPushEventSource
import com.hotdog.meonggocuisine.feature.push.chat.ChatPushMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatListViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `초기 진입 시 채팅방 목록을 불러온다`() =
        runTest(dispatcher) {
            val repository =
                FakeChatRepository(
                    listResults = mutableListOf(ChatRoomListResult.Success(listOf(room(7001)), false, null)),
                )
            val viewModel = ChatListViewModel(repository, FakeChatPushEvents())

            advanceUntilIdle()

            assertEquals(listOf(7001L), viewModel.uiState.value.rooms.map(ChatRoomSummary::chatRoomId))
            assertFalse(viewModel.uiState.value.isLoading)
        }

    @Test
    fun `첫 화면 재개는 중복 조회하지 않고 이후 재개부터 첫 페이지를 갱신한다`() =
        runTest(dispatcher) {
            val repository =
                FakeChatRepository(
                    listResults =
                        mutableListOf(
                            ChatRoomListResult.Success(listOf(room(7001, unreadCount = 2)), false, null),
                            ChatRoomListResult.Success(listOf(room(7001)), false, null),
                        ),
                )
            val viewModel = ChatListViewModel(repository, FakeChatPushEvents())

            viewModel.onScreenResumed()
            advanceUntilIdle()
            assertEquals(listOf(null), repository.listCursors)

            viewModel.onScreenResumed()
            advanceUntilIdle()

            assertEquals(listOf(null, null), repository.listCursors)
            assertEquals(0, viewModel.uiState.value.rooms.single().unreadCount)
            assertFalse(viewModel.uiState.value.rooms.single().hasUnread)
        }

    @Test
    fun `수동 새로고침은 기존 목록을 유지하다가 최신 첫 페이지로 교체한다`() =
        runTest(dispatcher) {
            val repository =
                FakeChatRepository(
                    listResults =
                        mutableListOf(
                            ChatRoomListResult.Success(listOf(room(7001)), false, null),
                            ChatRoomListResult.Success(listOf(room(7002), room(7001)), false, null),
                        ),
                )
            val viewModel = ChatListViewModel(repository, FakeChatPushEvents())
            advanceUntilIdle()

            viewModel.refresh()

            assertTrue(viewModel.uiState.value.isRefreshing)
            assertEquals(listOf(7001L), viewModel.uiState.value.rooms.map(ChatRoomSummary::chatRoomId))
            advanceUntilIdle()
            assertEquals(listOf(7002L, 7001L), viewModel.uiState.value.rooms.map(ChatRoomSummary::chatRoomId))
            assertFalse(viewModel.uiState.value.isRefreshing)
        }

    @Test
    fun `첫 페이지 갱신 실패 시 기존 목록과 페이지네이션을 유지한다`() =
        runTest(dispatcher) {
            val repository =
                FakeChatRepository(
                    listResults =
                        mutableListOf(
                            ChatRoomListResult.Success(listOf(room(7001)), true, "cursor-1"),
                            ChatRoomListResult.Failure("서버에 연결할 수 없습니다."),
                        ),
                )
            val viewModel = ChatListViewModel(repository, FakeChatPushEvents())
            advanceUntilIdle()

            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(listOf(7001L), viewModel.uiState.value.rooms.map(ChatRoomSummary::chatRoomId))
            assertTrue(viewModel.uiState.value.hasNext)
            assertEquals("cursor-1", viewModel.uiState.value.nextCursor)
            assertEquals("서버에 연결할 수 없습니다.", viewModel.uiState.value.errorMessage)
        }

    @Test
    fun `더 보기는 다음 페이지를 이어 붙이고 중복 방을 제거한다`() =
        runTest(dispatcher) {
            val repository =
                FakeChatRepository(
                    listResults =
                        mutableListOf(
                            ChatRoomListResult.Success(listOf(room(7001), room(7002)), true, "cursor-1"),
                            ChatRoomListResult.Success(listOf(room(7002), room(7003)), false, null),
                        ),
                )
            val viewModel = ChatListViewModel(repository, FakeChatPushEvents())
            advanceUntilIdle()

            viewModel.loadMore()
            advanceUntilIdle()

            assertEquals(
                listOf(7001L, 7002L, 7003L),
                viewModel.uiState.value.rooms.map(ChatRoomSummary::chatRoomId),
            )
            assertEquals(listOf(null, "cursor-1"), repository.listCursors)
        }

    @Test
    fun `커서가 무효하면 첫 페이지를 다시 불러온다`() =
        runTest(dispatcher) {
            val repository =
                FakeChatRepository(
                    listResults =
                        mutableListOf(
                            ChatRoomListResult.Success(listOf(room(7001)), true, "cursor-1"),
                            ChatRoomListResult.InvalidCursor("유효하지 않은 커서입니다."),
                            ChatRoomListResult.Success(listOf(room(7001), room(7002)), false, null),
                        ),
                )
            val viewModel = ChatListViewModel(repository, FakeChatPushEvents())
            advanceUntilIdle()

            viewModel.loadMore()
            advanceUntilIdle()

            assertEquals(
                listOf(7001L, 7002L),
                viewModel.uiState.value.rooms.map(ChatRoomSummary::chatRoomId),
            )
            assertEquals(listOf(null, "cursor-1", null), repository.listCursors)
        }

    @Test
    fun `목록 화면에서 채팅 푸시를 받으면 최신 첫 페이지를 갱신한다`() =
        runTest(dispatcher) {
            val repository =
                FakeChatRepository(
                    listResults =
                        mutableListOf(
                            ChatRoomListResult.Success(listOf(room(7001)), false, null),
                            ChatRoomListResult.Success(listOf(room(7002, unreadCount = 1), room(7001)), false, null),
                        ),
                )
            val pushEvents = FakeChatPushEvents()
            val viewModel = ChatListViewModel(repository, pushEvents)
            viewModel.onScreenResumed()
            advanceUntilIdle()

            pushEvents.emit(ChatPushMessage(chatRoomId = 7002, messageId = 9001, postId = 1002))
            advanceUntilIdle()

            assertEquals(listOf(null, null), repository.listCursors)
            assertEquals(listOf(7002L, 7001L), viewModel.uiState.value.rooms.map(ChatRoomSummary::chatRoomId))
            assertEquals(1, viewModel.uiState.value.rooms.first().unreadCount)
        }

    @Test
    fun `목록 화면이 중지된 동안 받은 채팅 푸시는 즉시 조회하지 않는다`() =
        runTest(dispatcher) {
            val repository =
                FakeChatRepository(
                    listResults = mutableListOf(ChatRoomListResult.Success(listOf(room(7001)), false, null)),
                )
            val pushEvents = FakeChatPushEvents()
            val viewModel = ChatListViewModel(repository, pushEvents)
            viewModel.onScreenResumed()
            advanceUntilIdle()
            viewModel.onScreenPaused()

            pushEvents.emit(ChatPushMessage(chatRoomId = 7002, messageId = 9001, postId = 1002))
            advanceUntilIdle()

            assertEquals(listOf(null), repository.listCursors)
        }

    private fun room(
        id: Long,
        unreadCount: Int = 0,
    ) = ChatRoomSummary(
        chatRoomId = id,
        postId = 1002,
        postType = "LOST",
        postStatus = "ACTIVE",
        thumbnailUrl = null,
        otherNickname = "망고보호자",
        lastMessageContent = "비슷한 아이를 보호하고 있어요.",
        lastMessageAt = "2026-09-10T05:12:00Z",
        readOnly = false,
        unreadCount = unreadCount,
        hasUnread = unreadCount > 0,
    )

    private class FakeChatRepository(
        private val listResults: MutableList<ChatRoomListResult>,
    ) : ChatRepository {
        val listCursors = mutableListOf<String?>()

        override suspend fun startChatRoom(postId: Long): ChatRoomStartResult = ChatRoomStartResult.Failure("사용하지 않음")

        override suspend fun getChatRooms(cursor: String?): ChatRoomListResult {
            listCursors += cursor
            return if (listResults.isEmpty()) {
                ChatRoomListResult.Failure("결과 없음")
            } else {
                listResults.removeFirst()
            }
        }

        override suspend fun getMessages(
            chatRoomId: Long,
            cursor: String?,
            afterMessageId: Long?,
        ): ChatMessagesResult = ChatMessagesResult.Failure("사용하지 않음")

        override suspend fun updateLastRead(
            chatRoomId: Long,
            lastReadMessageId: Long,
        ): ChatReadResult = ChatReadResult.Failure("사용하지 않음")

        override suspend fun sendMessage(
            chatRoomId: Long,
            clientMessageId: String,
            content: String,
        ): ChatMessageSendResult = ChatMessageSendResult.Failure("사용하지 않음")
    }

    private class FakeChatPushEvents : ChatPushEventSource {
        private val mutableMessages = MutableSharedFlow<ChatPushMessage>(extraBufferCapacity = 1)
        override val messages: Flow<ChatPushMessage> = mutableMessages

        fun emit(message: ChatPushMessage) {
            mutableMessages.tryEmit(message)
        }
    }
}
