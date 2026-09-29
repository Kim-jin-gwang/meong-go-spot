package com.hotdog.meonggocuisine.feature.chat.ui

import androidx.lifecycle.SavedStateHandle
import com.hotdog.meonggocuisine.feature.chat.data.ChatMessageSendResult
import com.hotdog.meonggocuisine.feature.chat.data.ChatMessagesResult
import com.hotdog.meonggocuisine.feature.chat.data.ChatReadResult
import com.hotdog.meonggocuisine.feature.chat.data.ChatRepository
import com.hotdog.meonggocuisine.feature.chat.data.ChatRoomListResult
import com.hotdog.meonggocuisine.feature.chat.data.ChatRoomStartResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class PostChatStartViewModelTest {
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
    fun `채팅방 준비에 성공하면 chatRoomId를 노출한다`() =
        runTest(dispatcher) {
            val repository = FakeChatRepository(ChatRoomStartResult.Success(7001))
            val viewModel = createViewModel(repository)

            advanceUntilIdle()

            assertEquals(7001L, viewModel.uiState.value.chatRoomId)
            assertEquals(POST_ID, repository.startedPostIds.single())
        }

    @Test
    fun `본인 게시물 등 정책 거부는 재시도 없이 안내한다`() =
        runTest(dispatcher) {
            val repository =
                FakeChatRepository(ChatRoomStartResult.NotAllowed("본인 게시물에는 채팅을 시작할 수 없습니다."))
            val viewModel = createViewModel(repository)

            advanceUntilIdle()

            assertEquals("본인 게시물에는 채팅을 시작할 수 없습니다.", viewModel.uiState.value.errorMessage)
            assertFalse(viewModel.uiState.value.canRetry)
        }

    @Test
    fun `통신 실패는 재시도할 수 있다`() =
        runTest(dispatcher) {
            val repository =
                FakeChatRepository(ChatRoomStartResult.Failure("서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요."))
            val viewModel = createViewModel(repository)

            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.canRetry)
        }

    private fun createViewModel(repository: ChatRepository): PostChatStartViewModel =
        PostChatStartViewModel(
            savedStateHandle = SavedStateHandle(mapOf("postId" to POST_ID)),
            repository = repository,
        )

    private class FakeChatRepository(
        private val startResult: ChatRoomStartResult,
    ) : ChatRepository {
        val startedPostIds = mutableListOf<Long>()

        override suspend fun startChatRoom(postId: Long): ChatRoomStartResult {
            startedPostIds += postId
            return startResult
        }

        override suspend fun getChatRooms(cursor: String?): ChatRoomListResult = ChatRoomListResult.Failure("사용하지 않음")

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

    private companion object {
        const val POST_ID = 1002L
    }
}
