package com.hotdog.meonggocuisine.feature.chat.data

import com.hotdog.meonggocuisine.core.network.ApiResponse
import com.hotdog.meonggocuisine.feature.auth.data.AuthSessionManager
import com.hotdog.meonggocuisine.feature.auth.data.RefreshTokenStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class DefaultChatRepositoryTest {
    @Test
    fun `채팅방 목록의 안 읽음 필드를 도메인 모델로 변환한다`() =
        runBlocking {
            val api =
                FakeChatApi(
                    roomListResponse =
                        success(
                            ChatRoomListResponse(
                                items =
                                    listOf(
                                        ChatRoomSummaryDto(
                                            chatRoomId = CHAT_ROOM_ID,
                                            post = ChatRoomPostDto(1002L, "LOST", "ACTIVE"),
                                            otherMember = ChatMemberDto(2L, "망고보호자"),
                                            lastMessage = ChatLastMessageDto(9001L, "새 메시지", CREATED_AT),
                                            unreadCount = 3,
                                            hasUnread = true,
                                        ),
                                    ),
                                page = ChatPageDto(size = 10, hasNext = false),
                            ),
                        ),
                )

            val result = repository(api).getChatRooms(null) as ChatRoomListResult.Success
            val room = result.rooms.single()

            assertEquals(9001L, room.lastMessageId)
            assertEquals(3, room.unreadCount)
            assertTrue(room.hasUnread)
        }

    @Test
    fun `메시지 조회의 증분 위치와 읽음 위치를 도메인 모델로 변환한다`() =
        runBlocking {
            val api =
                FakeChatApi(
                    messagesResponse =
                        success(
                            ChatMessagesResponse(
                                chatRoomId = CHAT_ROOM_ID,
                                myLastReadMessageId = 8980L,
                                otherLastReadMessageId = 8975L,
                                items = listOf(messageDto(9001L)),
                                page =
                                    ChatPageDto(
                                        size = 20,
                                        hasNext = true,
                                        nextAfterMessageId = 9001L,
                                    ),
                            ),
                        ),
                )

            val result =
                repository(api).getMessages(
                    chatRoomId = CHAT_ROOM_ID,
                    afterMessageId = 8979L,
                ) as ChatMessagesResult.Success

            assertEquals(8979L, api.messageRequests.single().afterMessageId)
            assertEquals(8980L, result.page.myLastReadMessageId)
            assertEquals(8975L, result.page.otherLastReadMessageId)
            assertEquals(9001L, result.page.nextAfterMessageId)
        }

    @Test
    fun `확인된 읽음 위치와 같거나 작은 요청은 서버에 반복 전송하지 않는다`() =
        runBlocking {
            val api = FakeChatApi()
            val repository = repository(api)

            assertEquals(ChatReadResult.Success(9001L), repository.updateLastRead(CHAT_ROOM_ID, 9001L))
            assertEquals(ChatReadResult.Success(9001L), repository.updateLastRead(CHAT_ROOM_ID, 9001L))
            assertEquals(ChatReadResult.Success(9001L), repository.updateLastRead(CHAT_ROOM_ID, 8999L))

            assertEquals(listOf(9001L), api.readRequests.map(ChatReadUpdateRequest::lastReadMessageId))
        }

    @Test
    fun `읽음 요청이 실패하면 같은 위치를 다음 호출에서 재시도한다`() =
        runBlocking {
            val api = FakeChatApi(readFailures = mutableListOf(IOException("offline"), null))
            val repository = repository(api)

            assertTrue(repository.updateLastRead(CHAT_ROOM_ID, 9001L) is ChatReadResult.Failure)
            assertEquals(ChatReadResult.Success(9001L), repository.updateLastRead(CHAT_ROOM_ID, 9001L))

            assertEquals(2, api.readRequests.size)
        }

    @Test
    fun `동시 읽음 응답이 역순으로 도착해도 확인 위치가 감소하지 않는다`() =
        runBlocking {
            val lowerRequestStarted = CompletableDeferred<Unit>()
            val releaseLowerResponse = CompletableDeferred<Unit>()
            val api =
                FakeChatApi(
                    readHandler = { request ->
                        if (request.lastReadMessageId == 9001L) {
                            lowerRequestStarted.complete(Unit)
                            releaseLowerResponse.await()
                        }
                        success(
                            ChatReadUpdateResponse(
                                chatRoomId = CHAT_ROOM_ID,
                                lastReadMessageId = request.lastReadMessageId,
                            ),
                        )
                    },
                )
            val repository = repository(api)

            val lowerResult = async { repository.updateLastRead(CHAT_ROOM_ID, 9001L) }
            lowerRequestStarted.await()
            val higherResult = async { repository.updateLastRead(CHAT_ROOM_ID, 9002L) }
            val confirmedHigherResult =
                try {
                    withTimeout(1_000) { higherResult.await() }
                } finally {
                    releaseLowerResponse.complete(Unit)
                }

            assertEquals(ChatReadResult.Success(9002L), confirmedHigherResult)
            assertEquals(ChatReadResult.Success(9002L), lowerResult.await())
            assertEquals(ChatReadResult.Success(9002L), repository.updateLastRead(CHAT_ROOM_ID, 9001L))
            assertEquals(listOf(9001L, 9002L), api.readRequests.map(ChatReadUpdateRequest::lastReadMessageId))
        }

    @Test
    fun `다른 방 메시지로 읽음 요청하면 입력 오류로 변환한다`() =
        runBlocking {
            val api =
                FakeChatApi(
                    readResponse =
                        error(
                            400,
                            """{"code":"CHAT-005","message":"채팅방에 속한 메시지가 아닙니다."}""",
                        ),
                )

            val result = repository(api).updateLastRead(CHAT_ROOM_ID, 9001L)

            assertTrue(result is ChatReadResult.InvalidMessage)
            assertFalse(result is ChatReadResult.Failure)
        }

    private fun repository(api: ChatApi) =
        DefaultChatRepository(
            chatApi = api,
            authSessionManager = AuthSessionManager(FakeRefreshTokenStore()),
            json = Json { ignoreUnknownKeys = true },
        )

    private fun messageDto(id: Long) =
        ChatMessageDto(
            messageId = id,
            sender = ChatMemberDto(2L, "망고보호자"),
            content = "메시지 $id",
            createdAt = CREATED_AT,
        )

    private fun <T> success(data: T): Response<ApiResponse<T>> =
        Response.success(ApiResponse(code = "SUCCESS", message = "성공", data = data))

    private fun <T> error(
        status: Int,
        body: String,
    ): Response<T> = Response.error(status, body.toResponseBody("application/json".toMediaType()))

    private class FakeRefreshTokenStore : RefreshTokenStore {
        override fun save(refreshToken: String) = Unit

        override fun read(): String? = null

        override fun clear() = Unit
    }

    private class FakeChatApi(
        private val roomListResponse: Response<ApiResponse<ChatRoomListResponse>>? = null,
        private val messagesResponse: Response<ApiResponse<ChatMessagesResponse>>? = null,
        private val readResponse: Response<ApiResponse<ChatReadUpdateResponse>>? = null,
        private val readFailures: MutableList<IOException?> = mutableListOf(),
        private val readHandler: (suspend (ChatReadUpdateRequest) -> Response<ApiResponse<ChatReadUpdateResponse>>)? = null,
    ) : ChatApi {
        data class MessageRequest(
            val cursor: String?,
            val afterMessageId: Long?,
        )

        val messageRequests = mutableListOf<MessageRequest>()
        val readRequests = mutableListOf<ChatReadUpdateRequest>()

        override suspend fun startChatRoom(postId: Long): Response<ApiResponse<ChatRoomStartResponse>> = error("not used")

        override suspend fun getChatRooms(cursor: String?): Response<ApiResponse<ChatRoomListResponse>> =
            roomListResponse ?: error("not stubbed")

        override suspend fun getMessages(
            chatRoomId: Long,
            cursor: String?,
            afterMessageId: Long?,
        ): Response<ApiResponse<ChatMessagesResponse>> {
            messageRequests += MessageRequest(cursor, afterMessageId)
            return messagesResponse ?: error("not stubbed")
        }

        override suspend fun sendMessage(
            chatRoomId: Long,
            request: ChatMessageSendRequest,
        ): Response<ApiResponse<ChatMessageDto>> = error("not used")

        override suspend fun updateLastRead(
            chatRoomId: Long,
            request: ChatReadUpdateRequest,
        ): Response<ApiResponse<ChatReadUpdateResponse>> {
            readRequests += request
            readHandler?.let { return it(request) }
            if (readFailures.isNotEmpty()) {
                readFailures.removeFirst()?.let { throw it }
            }
            return readResponse
                ?: Response.success(
                    ApiResponse(
                        code = "SUCCESS",
                        message = "성공",
                        data =
                            ChatReadUpdateResponse(
                                chatRoomId = chatRoomId,
                                lastReadMessageId = request.lastReadMessageId,
                            ),
                    ),
                )
        }
    }

    private companion object {
        const val CHAT_ROOM_ID = 7001L
        const val CREATED_AT = "2026-09-10T05:12:00Z"
    }
}
