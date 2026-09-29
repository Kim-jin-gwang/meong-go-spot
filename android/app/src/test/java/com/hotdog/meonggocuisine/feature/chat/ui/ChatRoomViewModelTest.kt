package com.hotdog.meonggocuisine.feature.chat.ui

import androidx.lifecycle.SavedStateHandle
import com.hotdog.meonggocuisine.feature.chat.data.ChatMessage
import com.hotdog.meonggocuisine.feature.chat.data.ChatMessagePage
import com.hotdog.meonggocuisine.feature.chat.data.ChatMessageSendResult
import com.hotdog.meonggocuisine.feature.chat.data.ChatMessagesResult
import com.hotdog.meonggocuisine.feature.chat.data.ChatReadResult
import com.hotdog.meonggocuisine.feature.chat.data.ChatRepository
import com.hotdog.meonggocuisine.feature.chat.data.ChatRoomListResult
import com.hotdog.meonggocuisine.feature.chat.data.ChatRoomStartResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatRoomViewModelTest {
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
    fun `폴링 결과는 messageId 기준으로 중복을 제거하고 시간순으로 정렬한다`() =
        runTest(dispatcher) {
            val repository =
                FakeChatRepository(
                    messagesResults =
                        mutableListOf(
                            successPage(messages(1, 2)),
                            successPage(messages(2, 3)),
                        ),
                )
            val viewModel = createViewModel(repository)
            advanceUntilIdle()

            viewModel.startPolling()
            advanceTimeBy(POLL_INTERVAL_MS + 1)
            viewModel.stopPolling()
            advanceUntilIdle()

            assertEquals(listOf(1L, 2L, 3L), viewModel.uiState.value.messages.map(ChatMessage::messageId))
            assertEquals(listOf(null, 2L), repository.messageRequests.map(FakeChatRepository.MessageRequest::afterMessageId))
        }

    @Test
    fun `한 폴링 주기에 20개를 넘는 새 메시지를 끝까지 이어서 조회한다`() =
        runTest(dispatcher) {
            val repository =
                FakeChatRepository(
                    messagesResults =
                        mutableListOf(
                            successPage(messages(1)),
                            successPage(
                                pageMessages = (2L..21L).map { message(it, mine = false) },
                                hasNext = true,
                                nextAfterMessageId = 21L,
                            ),
                            successPage((22L..25L).map { message(it, mine = false) }),
                        ),
                )
            val viewModel = createViewModel(repository)
            advanceUntilIdle()

            viewModel.startPolling()
            advanceTimeBy(POLL_INTERVAL_MS + 1)
            viewModel.stopPolling()
            advanceUntilIdle()

            assertEquals((1L..25L).toList(), viewModel.uiState.value.messages.map(ChatMessage::messageId))
            assertEquals(listOf(null, 1L, 21L), repository.messageRequests.map(FakeChatRepository.MessageRequest::afterMessageId))
        }

    @Test
    fun `증분 조회 실패 후 다음 폴링에서 같은 위치부터 복구한다`() =
        runTest(dispatcher) {
            val repository =
                FakeChatRepository(
                    messagesResults =
                        mutableListOf(
                            successPage(messages(1)),
                            ChatMessagesResult.Failure("서버에 연결할 수 없습니다."),
                            successPage(messages(2)),
                        ),
                )
            val viewModel = createViewModel(repository)
            advanceUntilIdle()

            viewModel.startPolling()
            advanceTimeBy(POLL_INTERVAL_MS + 1)
            advanceTimeBy(POLL_INTERVAL_MS + 1)
            viewModel.stopPolling()
            advanceUntilIdle()

            assertEquals(listOf(1L, 2L), viewModel.uiState.value.messages.map(ChatMessage::messageId))
            assertEquals(listOf(null, 1L, 1L), repository.messageRequests.map(FakeChatRepository.MessageRequest::afterMessageId))
        }

    @Test
    fun `메시지 조회의 읽음 위치를 화면 상태에 반영한다`() =
        runTest(dispatcher) {
            val viewModel =
                createViewModel(
                    FakeChatRepository(
                        messagesResults =
                            mutableListOf(
                                successPage(
                                    pageMessages = messages(1),
                                    myLastReadMessageId = 1L,
                                    otherLastReadMessageId = null,
                                ),
                            ),
                    ),
                )

            advanceUntilIdle()

            assertEquals(1L, viewModel.uiState.value.myLastReadMessageId)
            assertEquals(null, viewModel.uiState.value.otherLastReadMessageId)
        }

    @Test
    fun `화면에 표시한 최신 메시지로 읽음 위치를 갱신한다`() =
        runTest(dispatcher) {
            val repository =
                FakeChatRepository(
                    messagesResults = mutableListOf(successPage(messages(1, 2))),
                )
            val viewModel = createViewModel(repository)

            advanceUntilIdle()

            assertEquals(listOf(2L), repository.readRequests)
            assertEquals(2L, viewModel.uiState.value.myLastReadMessageId)
        }

    @Test
    fun `읽음 갱신 실패 후 다음 조회에서 같은 위치를 재시도한다`() =
        runTest(dispatcher) {
            val repository =
                FakeChatRepository(
                    messagesResults =
                        mutableListOf(
                            successPage(messages(1)),
                            successPage(emptyList()),
                        ),
                    readResults =
                        mutableListOf(
                            ChatReadResult.Failure("서버에 연결할 수 없습니다."),
                            ChatReadResult.Success(1L),
                        ),
                )
            val viewModel = createViewModel(repository)
            advanceUntilIdle()

            viewModel.startPolling()
            advanceTimeBy(POLL_INTERVAL_MS + 1)
            viewModel.stopPolling()
            advanceUntilIdle()

            assertEquals(listOf(1L, 1L), repository.readRequests)
            assertEquals(1L, viewModel.uiState.value.myLastReadMessageId)
        }

    @Test
    fun `읽기 전용 채팅도 화면에 표시한 메시지를 읽음 처리한다`() =
        runTest(dispatcher) {
            val repository =
                FakeChatRepository(
                    messagesResults = mutableListOf(successPage(messages(1), readOnly = true)),
                )
            val viewModel = createViewModel(repository)

            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.readOnly)
            assertEquals(listOf(1L), repository.readRequests)
        }

    @Test
    fun `내 메시지를 먼저 전송해도 아직 동기화하지 않은 상대 메시지를 건너뛰지 않는다`() =
        runTest(dispatcher) {
            val repository =
                FakeChatRepository(
                    messagesResults =
                        mutableListOf(
                            successPage(messages(1)),
                            successPage(listOf(message(2, mine = false), message(3, mine = true))),
                        ),
                    sendResults = mutableListOf(ChatMessageSendResult.Success(message(3, mine = true))),
                )
            val viewModel = createViewModel(repository)
            advanceUntilIdle()

            viewModel.onInputChange("내가 보낸 메시지")
            viewModel.send()
            advanceUntilIdle()
            viewModel.startPolling()
            advanceTimeBy(POLL_INTERVAL_MS + 1)
            viewModel.stopPolling()
            advanceUntilIdle()

            assertEquals(listOf(1L, 2L, 3L), viewModel.uiState.value.messages.map(ChatMessage::messageId))
            assertEquals(listOf(null, 1L), repository.messageRequests.map(FakeChatRepository.MessageRequest::afterMessageId))
        }

    @Test
    fun `1000자를 넘는 입력은 버리지 않고 1000자에서 잘라 넣는다`() =
        runTest(dispatcher) {
            val viewModel = createViewModel(FakeChatRepository(messagesResults = mutableListOf(successPage(messages(1)))))
            advanceUntilIdle()

            viewModel.onInputChange("가".repeat(1_200))

            assertEquals(1_000, viewModel.uiState.value.inputText.length)

            // 이모지는 char 두 개지만 한 글자다 — 1000번째 글자 경계에서 반쪽으로 잘리지 않는다
            viewModel.onInputChange("🐶".repeat(1_200))
            val emoji = viewModel.uiState.value.inputText
            assertEquals(1_000, emoji.codePointCount(0, emoji.length))
            assertEquals(2_000, emoji.length)
        }

    @Test
    fun `폴링을 중단하면 더 이상 메시지를 조회하지 않는다`() =
        runTest(dispatcher) {
            val repository = FakeChatRepository(messagesResults = mutableListOf(successPage(messages(1))))
            val viewModel = createViewModel(repository)
            advanceUntilIdle()

            viewModel.startPolling()
            advanceTimeBy(POLL_INTERVAL_MS + 1)
            viewModel.stopPolling()
            val callCountAfterStop = repository.messageRequests.size
            advanceTimeBy(POLL_INTERVAL_MS * 5)
            advanceUntilIdle()

            assertEquals(callCountAfterStop, repository.messageRequests.size)
        }

    @Test
    fun `전송에서 CHAT-004를 받으면 읽기 전용으로 전환한다`() =
        runTest(dispatcher) {
            val repository =
                FakeChatRepository(
                    messagesResults = mutableListOf(successPage(messages(1))),
                    sendResults = mutableListOf(ChatMessageSendResult.ReadOnly("종료된 게시물의 채팅에는 메시지를 보낼 수 없습니다.")),
                )
            val viewModel = createViewModel(repository)
            advanceUntilIdle()

            viewModel.onInputChange("안녕하세요")
            viewModel.send()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.readOnly)
            assertNotNull(viewModel.uiState.value.readOnlyNotice)
        }

    @Test
    fun `조회 응답의 readOnly가 true면 화면 상태도 읽기 전용이다`() =
        runTest(dispatcher) {
            val repository =
                FakeChatRepository(messagesResults = mutableListOf(successPage(messages(1), readOnly = true)))
            val viewModel = createViewModel(repository)

            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.readOnly)
        }

    @Test
    fun `초기 로딩 실패 후 폴링이 성공하면 오류 상태를 해제하고 메시지를 보여준다`() =
        runTest(dispatcher) {
            val repository =
                FakeChatRepository(
                    messagesResults =
                        mutableListOf(
                            ChatMessagesResult.Failure("서버에 연결할 수 없습니다."),
                            successPage(messages(1, 2)),
                        ),
                )
            val viewModel = createViewModel(repository)
            advanceUntilIdle()
            assertEquals("서버에 연결할 수 없습니다.", viewModel.uiState.value.errorMessage)

            viewModel.startPolling()
            advanceTimeBy(POLL_INTERVAL_MS + 1)
            viewModel.stopPolling()
            advanceUntilIdle()

            assertEquals(null, viewModel.uiState.value.errorMessage)
            assertEquals(listOf(1L, 2L), viewModel.uiState.value.messages.map(ChatMessage::messageId))
        }

    @Test
    fun `전송 실패 후 같은 내용을 재시도하면 같은 clientMessageId를 재사용한다`() =
        runTest(dispatcher) {
            val repository =
                FakeChatRepository(
                    messagesResults = mutableListOf(successPage(messages(1))),
                    sendResults =
                        mutableListOf(
                            ChatMessageSendResult.Failure("메시지를 전송하지 못했습니다."),
                            ChatMessageSendResult.Success(message(2, mine = true)),
                        ),
                )
            val viewModel = createViewModel(repository)
            advanceUntilIdle()

            viewModel.onInputChange("안녕하세요")
            viewModel.send()
            advanceUntilIdle()
            viewModel.send()
            advanceUntilIdle()

            assertEquals(2, repository.sendRequests.size)
            assertEquals(repository.sendRequests[0].clientMessageId, repository.sendRequests[1].clientMessageId)
            assertEquals("", viewModel.uiState.value.inputText)
        }

    @Test
    fun `내용을 바꾸면 새 clientMessageId를 생성한다`() =
        runTest(dispatcher) {
            val repository =
                FakeChatRepository(
                    messagesResults = mutableListOf(successPage(messages(1))),
                    sendResults =
                        mutableListOf(
                            ChatMessageSendResult.Failure("메시지를 전송하지 못했습니다."),
                            ChatMessageSendResult.Success(message(2, mine = true)),
                        ),
                )
            val viewModel = createViewModel(repository)
            advanceUntilIdle()

            viewModel.onInputChange("첫 번째 내용")
            viewModel.send()
            advanceUntilIdle()
            viewModel.onInputChange("다른 내용")
            viewModel.send()
            advanceUntilIdle()

            assertEquals(2, repository.sendRequests.size)
            assertTrue(repository.sendRequests[0].clientMessageId != repository.sendRequests[1].clientMessageId)
        }

    private fun createViewModel(repository: ChatRepository): ChatRoomViewModel =
        ChatRoomViewModel(
            savedStateHandle = SavedStateHandle(mapOf("chatRoomId" to CHAT_ROOM_ID)),
            repository = repository,
        )

    private fun successPage(
        pageMessages: List<ChatMessage>,
        readOnly: Boolean = false,
        hasNext: Boolean = false,
        nextAfterMessageId: Long? = null,
        myLastReadMessageId: Long? = null,
        otherLastReadMessageId: Long? = null,
    ) = ChatMessagesResult.Success(
        ChatMessagePage(
            readOnly = readOnly,
            messages = pageMessages,
            hasNext = hasNext,
            nextCursor = null,
            pollAfterMs = POLL_INTERVAL_MS,
            nextAfterMessageId = nextAfterMessageId,
            myLastReadMessageId = myLastReadMessageId,
            otherLastReadMessageId = otherLastReadMessageId,
        ),
    )

    private fun messages(vararg ids: Long): List<ChatMessage> = ids.map { message(it, mine = false) }

    private fun message(
        id: Long,
        mine: Boolean,
    ) = ChatMessage(
        messageId = id,
        senderNickname = if (mine) "나" else "망고보호자",
        content = "메시지 $id",
        createdAt = "2026-09-10T05:12:00Z",
        mine = mine,
    )

    private class FakeChatRepository(
        private val messagesResults: MutableList<ChatMessagesResult>,
        private val sendResults: MutableList<ChatMessageSendResult> = mutableListOf(),
        private val readResults: MutableList<ChatReadResult> = mutableListOf(),
    ) : ChatRepository {
        data class SendRequest(
            val clientMessageId: String,
            val content: String,
        )

        data class MessageRequest(
            val cursor: String?,
            val afterMessageId: Long?,
        )

        val sendRequests = mutableListOf<SendRequest>()
        val messageRequests = mutableListOf<MessageRequest>()
        val readRequests = mutableListOf<Long>()

        private var lastMessagesResult: ChatMessagesResult? = null

        override suspend fun startChatRoom(postId: Long): ChatRoomStartResult = ChatRoomStartResult.Failure("사용하지 않음")

        override suspend fun getChatRooms(cursor: String?): ChatRoomListResult = ChatRoomListResult.Failure("사용하지 않음")

        override suspend fun getMessages(
            chatRoomId: Long,
            cursor: String?,
            afterMessageId: Long?,
        ): ChatMessagesResult {
            messageRequests += MessageRequest(cursor, afterMessageId)
            val result = if (messagesResults.isEmpty()) lastMessagesResult else messagesResults.removeAt(0)
            lastMessagesResult = result
            return result ?: ChatMessagesResult.Failure("결과 없음")
        }

        override suspend fun updateLastRead(
            chatRoomId: Long,
            lastReadMessageId: Long,
        ): ChatReadResult {
            readRequests += lastReadMessageId
            return if (readResults.isEmpty()) {
                ChatReadResult.Success(lastReadMessageId)
            } else {
                readResults.removeFirst()
            }
        }

        override suspend fun sendMessage(
            chatRoomId: Long,
            clientMessageId: String,
            content: String,
        ): ChatMessageSendResult {
            sendRequests += SendRequest(clientMessageId, content)
            return if (sendResults.isEmpty()) {
                ChatMessageSendResult.Failure("결과 없음")
            } else {
                sendResults.removeAt(0)
            }
        }
    }

    private companion object {
        const val CHAT_ROOM_ID = 7001L
        const val POLL_INTERVAL_MS = 3_000L
    }
}
