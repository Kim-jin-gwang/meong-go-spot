package com.hotdog.meonggocuisine.feature.chat.data

import com.hotdog.meonggocuisine.core.network.ApiErrorResponse
import com.hotdog.meonggocuisine.feature.auth.data.AuthSessionManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject

class DefaultChatRepository
    @Inject
    constructor(
        private val chatApi: ChatApi,
        private val authSessionManager: AuthSessionManager,
        private val json: Json,
    ) : ChatRepository {
        private val readPositionMutex = Mutex()
        private val confirmedReadPositions = mutableMapOf<Long, Long>()

        override suspend fun startChatRoom(postId: Long): ChatRoomStartResult =
            try {
                val response = chatApi.startChatRoom(postId)
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    ChatRoomStartResult.Success(body.data.chatRoomId)
                } else {
                    val error = response.errorBody()?.string()?.let(::parseError)
                    when (error?.code) {
                        "CHAT-001", "CHAT-002", "CHAT-004" ->
                            ChatRoomStartResult.NotAllowed(error.message)
                        "POST-001" -> ChatRoomStartResult.Failure("게시물을 찾을 수 없습니다.")
                        "AUTH-002" -> ChatRoomStartResult.Failure(AUTH_FAILURE)
                        else -> ChatRoomStartResult.Failure(error?.message ?: START_FAILURE)
                    }
                }
            } catch (_: IOException) {
                ChatRoomStartResult.Failure(CONNECTION_FAILURE)
            } catch (_: Exception) {
                ChatRoomStartResult.Failure(START_FAILURE)
            }

        override suspend fun getChatRooms(cursor: String?): ChatRoomListResult =
            try {
                val response = chatApi.getChatRooms(cursor)
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    ChatRoomListResult.Success(
                        rooms = body.data.items.map(ChatRoomSummaryDto::toSummary),
                        hasNext = body.data.page.hasNext,
                        nextCursor = body.data.page.nextCursor,
                    )
                } else {
                    val error = response.errorBody()?.string()?.let(::parseError)
                    when (error?.code) {
                        "CURSOR-001" -> ChatRoomListResult.InvalidCursor(error.message)
                        "AUTH-002" -> ChatRoomListResult.Failure(AUTH_FAILURE)
                        else -> ChatRoomListResult.Failure(error?.message ?: LIST_FAILURE)
                    }
                }
            } catch (_: IOException) {
                ChatRoomListResult.Failure(CONNECTION_FAILURE)
            } catch (_: Exception) {
                ChatRoomListResult.Failure(LIST_FAILURE)
            }

        override suspend fun getMessages(
            chatRoomId: Long,
            cursor: String?,
            afterMessageId: Long?,
        ): ChatMessagesResult =
            try {
                val response = chatApi.getMessages(chatRoomId, cursor, afterMessageId)
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    body.data.myLastReadMessageId?.let { confirmedPosition ->
                        readPositionMutex.withLock {
                            confirmedReadPositions.merge(chatRoomId, confirmedPosition, ::maxOf)
                        }
                    }
                    ChatMessagesResult.Success(
                        ChatMessagePage(
                            post = body.data.post?.toRoomPost(),
                            otherNickname = body.data.otherMember?.nickname,
                            readOnly = body.data.readOnly,
                            messages = body.data.items.map(::toMessage),
                            hasNext = body.data.page.hasNext,
                            nextCursor = body.data.page.nextCursor,
                            pollAfterMs = body.data.pollAfterMs,
                            nextAfterMessageId = body.data.page.nextAfterMessageId,
                            myLastReadMessageId = body.data.myLastReadMessageId,
                            otherLastReadMessageId = body.data.otherLastReadMessageId,
                        ),
                    )
                } else {
                    val error = response.errorBody()?.string()?.let(::parseError)
                    when (error?.code) {
                        "CHAT-003" -> ChatMessagesResult.RoomNotFound(error.message)
                        "CURSOR-001" -> ChatMessagesResult.InvalidCursor(error.message)
                        "CHAT-005" -> ChatMessagesResult.InvalidAfterMessage(error.message)
                        "COMMON-001" ->
                            if (afterMessageId != null) {
                                ChatMessagesResult.InvalidAfterMessage(error.message)
                            } else {
                                ChatMessagesResult.InvalidCursor(error.message)
                            }
                        "AUTH-002" -> ChatMessagesResult.Failure(AUTH_FAILURE)
                        else -> ChatMessagesResult.Failure(error?.message ?: MESSAGES_FAILURE)
                    }
                }
            } catch (_: IOException) {
                ChatMessagesResult.Failure(CONNECTION_FAILURE)
            } catch (_: Exception) {
                ChatMessagesResult.Failure(MESSAGES_FAILURE)
            }

        override suspend fun updateLastRead(
            chatRoomId: Long,
            lastReadMessageId: Long,
        ): ChatReadResult {
            val confirmedPosition =
                readPositionMutex.withLock {
                    confirmedReadPositions[chatRoomId]
                }

            if (confirmedPosition != null && lastReadMessageId <= confirmedPosition) {
                return ChatReadResult.Success(confirmedPosition)
            }

            return try {
                val response =
                    chatApi.updateLastRead(
                        chatRoomId,
                        ChatReadUpdateRequest(lastReadMessageId),
                    )
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    val latestConfirmedPosition =
                        readPositionMutex.withLock {
                            confirmedReadPositions.merge(
                                chatRoomId,
                                body.data.lastReadMessageId,
                                ::maxOf,
                            ) ?: body.data.lastReadMessageId
                        }
                    ChatReadResult.Success(latestConfirmedPosition)
                } else {
                    val error = response.errorBody()?.string()?.let(::parseError)
                    when (error?.code) {
                        "CHAT-003" -> ChatReadResult.RoomNotFound(error.message)
                        "CHAT-005", "COMMON-001" -> ChatReadResult.InvalidMessage(error.message)
                        "AUTH-002" -> ChatReadResult.Failure(AUTH_FAILURE)
                        else -> ChatReadResult.Failure(error?.message ?: READ_FAILURE)
                    }
                }
            } catch (_: IOException) {
                ChatReadResult.Failure(CONNECTION_FAILURE)
            } catch (_: Exception) {
                ChatReadResult.Failure(READ_FAILURE)
            }
        }

        override suspend fun sendMessage(
            chatRoomId: Long,
            clientMessageId: String,
            content: String,
        ): ChatMessageSendResult =
            try {
                val response =
                    chatApi.sendMessage(
                        chatRoomId,
                        ChatMessageSendRequest(clientMessageId = clientMessageId, content = content),
                    )
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    ChatMessageSendResult.Success(toMessage(body.data))
                } else {
                    val error = response.errorBody()?.string()?.let(::parseError)
                    when (error?.code) {
                        "CHAT-004" -> ChatMessageSendResult.ReadOnly(error.message)
                        "CHAT-003" -> ChatMessageSendResult.RoomNotFound(error.message)
                        "COMMON-001", "IDEMPOTENCY-001" ->
                            ChatMessageSendResult.InvalidInput(error.message)
                        "AUTH-002" -> ChatMessageSendResult.Failure(AUTH_FAILURE)
                        else -> ChatMessageSendResult.Failure(error?.message ?: SEND_FAILURE)
                    }
                }
            } catch (_: IOException) {
                ChatMessageSendResult.Failure(CONNECTION_FAILURE)
            } catch (_: Exception) {
                ChatMessageSendResult.Failure(SEND_FAILURE)
            }

        private fun toMessage(dto: ChatMessageDto): ChatMessage {
            val myMemberId = authSessionManager.session.value?.member?.memberId
            return ChatMessage(
                messageId = dto.messageId,
                senderNickname = dto.sender.nickname,
                content = dto.content,
                createdAt = dto.createdAt,
                mine = myMemberId != null && dto.sender.memberId == myMemberId,
            )
        }

        private fun parseError(value: String): ApiErrorResponse? =
            runCatching { json.decodeFromString<ApiErrorResponse>(value) }.getOrNull()

        private companion object {
            const val CONNECTION_FAILURE = "서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요."
            const val AUTH_FAILURE = "로그인이 필요합니다. 다시 로그인해 주세요."
            const val START_FAILURE = "채팅방을 준비하지 못했습니다. 잠시 후 다시 시도해 주세요."
            const val LIST_FAILURE = "채팅방 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요."
            const val MESSAGES_FAILURE = "메시지를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요."
            const val SEND_FAILURE = "메시지를 전송하지 못했습니다. 잠시 후 다시 시도해 주세요."
            const val READ_FAILURE = "읽음 상태를 반영하지 못했습니다. 잠시 후 다시 시도해 주세요."
        }
    }

private fun ChatRoomSummaryDto.toSummary(): ChatRoomSummary =
    ChatRoomSummary(
        chatRoomId = chatRoomId,
        postId = post.postId,
        postType = post.type,
        postStatus = post.status,
        postName = post.name,
        postSpecies = post.species,
        postBreedName = post.breedName,
        postSex = post.sex,
        thumbnailUrl = post.thumbnailUrl,
        otherNickname = otherMember.nickname,
        lastMessageContent = lastMessage?.content,
        lastMessageAt = lastMessage?.createdAt,
        readOnly = readOnly,
        lastMessageId = lastMessage?.messageId,
        unreadCount = unreadCount,
        hasUnread = hasUnread,
    )

private fun ChatRoomPostDto.toRoomPost() =
    ChatRoomPost(
        postId = postId,
        type = type,
        status = status,
        name = name,
        species = species,
        breedName = breedName,
        sex = sex,
        thumbnailUrl = thumbnailUrl,
    )
