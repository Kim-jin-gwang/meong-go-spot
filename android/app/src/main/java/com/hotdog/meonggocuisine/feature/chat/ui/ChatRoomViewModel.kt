package com.hotdog.meonggocuisine.feature.chat.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hotdog.meonggocuisine.feature.chat.data.ChatMessage
import com.hotdog.meonggocuisine.feature.chat.data.ChatMessagePage
import com.hotdog.meonggocuisine.feature.chat.data.ChatMessageSendResult
import com.hotdog.meonggocuisine.feature.chat.data.ChatMessagesResult
import com.hotdog.meonggocuisine.feature.chat.data.ChatReadResult
import com.hotdog.meonggocuisine.feature.chat.data.ChatRepository
import com.hotdog.meonggocuisine.feature.chat.data.ChatRoomPost
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.Normalizer
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ChatRoomViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val repository: ChatRepository,
    ) : ViewModel() {
        // toRoute()는 Android Bundle이 필요해 JVM 단위 테스트에서 실행할 수 없어 키로 직접 읽는다.
        private val chatRoomId: Long = checkNotNull(savedStateHandle[CHAT_ROOM_ID_ARG])
        private val mutableUiState =
            MutableStateFlow(
                ChatRoomUiState(
                    isLoading = true,
                    routeNickname = savedStateHandle[OTHER_NICKNAME_ARG],
                ),
            )
        val uiState: StateFlow<ChatRoomUiState> = mutableUiState.asStateFlow()

        private var pollingJob: Job? = null
        private var previousCursor: String? = null
        private var lastSyncedMessageId: Long? = null
        private var pendingSend: PendingSend? = null

        init {
            loadInitial()
        }

        fun startPolling() {
            if (pollingJob?.isActive == true) return
            pollingJob =
                viewModelScope.launch {
                    while (isActive) {
                        delay(mutableUiState.value.pollAfterMs)
                        refreshLatest()
                    }
                }
        }

        fun stopPolling() {
            pollingJob?.cancel()
            pollingJob = null
        }

        fun retry() {
            if (mutableUiState.value.isLoading) return
            loadInitial()
        }

        fun loadPrevious() {
            val state = mutableUiState.value
            val cursor = previousCursor
            if (state.isLoading || state.isLoadingPrevious || !state.hasPrevious || cursor == null) return
            mutableUiState.value = state.copy(isLoadingPrevious = true)
            viewModelScope.launch {
                when (val result = repository.getMessages(chatRoomId, cursor)) {
                    is ChatMessagesResult.Success -> {
                        previousCursor = result.page.nextCursor
                        mutableUiState.value =
                            mutableUiState.value.copy(
                                messages = merge(result.page.messages),
                                hasPrevious = result.page.hasNext,
                                isLoadingPrevious = false,
                            )
                        markLatestMessageRead()
                    }
                    is ChatMessagesResult.InvalidCursor,
                    is ChatMessagesResult.InvalidAfterMessage,
                    -> loadInitial()
                    is ChatMessagesResult.RoomNotFound ->
                        mutableUiState.value =
                            ChatRoomUiState(errorMessage = result.message)
                    is ChatMessagesResult.Failure ->
                        mutableUiState.value =
                            mutableUiState.value.copy(isLoadingPrevious = false)
                }
            }
        }

        /** 한도를 넘는 붙여넣기는 버리지 않고 한도에서 잘라 넣는다 — 서버(C4)와 같이 코드 포인트로 센다. */
        fun onInputChange(value: String) {
            val limited =
                if (value.codePointCount(0, value.length) <= CHAT_MESSAGE_MAX_LENGTH) {
                    value
                } else {
                    value.substring(0, value.offsetByCodePoints(0, CHAT_MESSAGE_MAX_LENGTH))
                }
            mutableUiState.value = mutableUiState.value.copy(inputText = limited, sendErrorMessage = null)
        }

        fun send() {
            val state = mutableUiState.value
            if (state.isSending || state.readOnly) return
            val content = Normalizer.normalize(state.inputText, Normalizer.Form.NFC).trim()
            if (content.isEmpty() || content.codePointCount(0, content.length) > CHAT_MESSAGE_MAX_LENGTH) {
                mutableUiState.value =
                    state.copy(sendErrorMessage = "메시지는 공백 제외 1자 이상 1000자 이하로 입력해 주세요.")
                return
            }
            val clientMessageId =
                pendingSend?.takeIf { it.content == content }?.clientMessageId
                    ?: UUID.randomUUID().toString()
            pendingSend = PendingSend(clientMessageId, content)
            mutableUiState.value = state.copy(isSending = true, sendErrorMessage = null)
            viewModelScope.launch {
                when (val result = repository.sendMessage(chatRoomId, clientMessageId, content)) {
                    is ChatMessageSendResult.Success -> {
                        pendingSend = null
                        mutableUiState.value =
                            mutableUiState.value.copy(
                                messages = merge(listOf(result.message)),
                                inputText = "",
                                isSending = false,
                            )
                        markLatestMessageRead()
                    }
                    is ChatMessageSendResult.ReadOnly -> {
                        pendingSend = null
                        mutableUiState.value =
                            mutableUiState.value.copy(
                                readOnly = true,
                                readOnlyNotice = result.message,
                                isSending = false,
                            )
                    }
                    is ChatMessageSendResult.RoomNotFound ->
                        mutableUiState.value = ChatRoomUiState(errorMessage = result.message)
                    is ChatMessageSendResult.InvalidInput -> {
                        pendingSend = null
                        mutableUiState.value =
                            mutableUiState.value.copy(isSending = false, sendErrorMessage = result.message)
                    }
                    is ChatMessageSendResult.Failure ->
                        mutableUiState.value =
                            mutableUiState.value.copy(isSending = false, sendErrorMessage = result.message)
                }
            }
        }

        private fun loadInitial() {
            lastSyncedMessageId = null
            viewModelScope.launch {
                mutableUiState.value =
                    mutableUiState.value.copy(isLoading = true, isLoadingPrevious = false, errorMessage = null)
                when (val result = repository.getMessages(chatRoomId, cursor = null)) {
                    is ChatMessagesResult.Success -> {
                        previousCursor = result.page.nextCursor
                        lastSyncedMessageId = result.page.messages.maxOfOrNull(ChatMessage::messageId)
                        val currentState = mutableUiState.value
                        mutableUiState.value =
                            currentState.copy(
                                isLoading = false,
                                messages = result.page.messages.sortedBy(ChatMessage::messageId),
                                post = result.page.post ?: currentState.post,
                                serverNickname = result.page.otherNickname ?: currentState.serverNickname,
                                readOnly = result.page.readOnly,
                                myLastReadMessageId = latestReadPosition(currentState.myLastReadMessageId, result.page.myLastReadMessageId),
                                otherLastReadMessageId =
                                    latestReadPosition(currentState.otherLastReadMessageId, result.page.otherLastReadMessageId),
                                hasPrevious = result.page.hasNext,
                                pollAfterMs = result.page.pollAfterMs,
                            )
                        markLatestMessageRead()
                    }
                    is ChatMessagesResult.RoomNotFound ->
                        mutableUiState.value = ChatRoomUiState(errorMessage = result.message)
                    is ChatMessagesResult.InvalidCursor,
                    is ChatMessagesResult.InvalidAfterMessage,
                    is ChatMessagesResult.Failure,
                    ->
                        mutableUiState.value =
                            ChatRoomUiState(errorMessage = failureMessage(result))
                }
            }
        }

        private suspend fun refreshLatest() {
            val syncedMessageId = lastSyncedMessageId
            if (syncedMessageId == null) {
                refreshWithoutIncrementalAnchor()
                return
            }

            var afterMessageId: Long = syncedMessageId
            var refreshedAtLeastOnePage = false
            while (true) {
                when (
                    val result =
                        repository.getMessages(
                            chatRoomId = chatRoomId,
                            afterMessageId = afterMessageId,
                        )
                ) {
                    is ChatMessagesResult.Success -> {
                        refreshedAtLeastOnePage = true
                        applyRefreshPage(result.page)
                        result.page.messages.maxOfOrNull(ChatMessage::messageId)?.let { receivedMessageId ->
                            lastSyncedMessageId = maxOf(lastSyncedMessageId ?: receivedMessageId, receivedMessageId)
                        }
                        val nextAfterMessageId = result.page.nextAfterMessageId
                        if (!result.page.hasNext || nextAfterMessageId == null || nextAfterMessageId <= afterMessageId) {
                            markLatestMessageRead()
                            return
                        }
                        afterMessageId = nextAfterMessageId
                    }

                    is ChatMessagesResult.InvalidAfterMessage -> {
                        loadInitial()
                        return
                    }

                    is ChatMessagesResult.RoomNotFound -> {
                        stopPolling()
                        mutableUiState.value = ChatRoomUiState(errorMessage = result.message)
                        return
                    }

                    is ChatMessagesResult.InvalidCursor,
                    is ChatMessagesResult.Failure,
                    -> {
                        if (refreshedAtLeastOnePage) markLatestMessageRead()
                        return
                    }
                }
            }
        }

        private suspend fun refreshWithoutIncrementalAnchor() {
            when (val result = repository.getMessages(chatRoomId = chatRoomId)) {
                is ChatMessagesResult.Success -> {
                    if (previousCursor == null) {
                        previousCursor = result.page.nextCursor
                    }
                    lastSyncedMessageId = result.page.messages.maxOfOrNull(ChatMessage::messageId)
                    applyRefreshPage(result.page, recoverPreviousPage = true)
                    markLatestMessageRead()
                }

                is ChatMessagesResult.RoomNotFound -> {
                    stopPolling()
                    mutableUiState.value = ChatRoomUiState(errorMessage = result.message)
                }

                is ChatMessagesResult.InvalidCursor,
                is ChatMessagesResult.InvalidAfterMessage,
                is ChatMessagesResult.Failure,
                -> Unit
            }
        }

        private fun applyRefreshPage(
            page: ChatMessagePage,
            recoverPreviousPage: Boolean = false,
        ) {
            val state = mutableUiState.value
            mutableUiState.value =
                state.copy(
                    messages = merge(page.messages),
                    post = page.post ?: state.post,
                    serverNickname = page.otherNickname ?: state.serverNickname,
                    readOnly = page.readOnly,
                    readOnlyNotice = if (page.readOnly) state.readOnlyNotice else null,
                    myLastReadMessageId = latestReadPosition(state.myLastReadMessageId, page.myLastReadMessageId),
                    otherLastReadMessageId = latestReadPosition(state.otherLastReadMessageId, page.otherLastReadMessageId),
                    pollAfterMs = page.pollAfterMs,
                    hasPrevious = if (recoverPreviousPage && state.messages.isEmpty()) page.hasNext else state.hasPrevious,
                    isLoading = false,
                    errorMessage = null,
                )
        }

        private suspend fun markLatestMessageRead() {
            val latestMessageId = mutableUiState.value.messages.maxOfOrNull(ChatMessage::messageId) ?: return
            when (val result = repository.updateLastRead(chatRoomId, latestMessageId)) {
                is ChatReadResult.Success -> {
                    val state = mutableUiState.value
                    mutableUiState.value =
                        state.copy(
                            myLastReadMessageId = latestReadPosition(state.myLastReadMessageId, result.lastReadMessageId),
                        )
                }

                is ChatReadResult.RoomNotFound,
                is ChatReadResult.InvalidMessage,
                is ChatReadResult.Failure,
                -> Unit
            }
        }

        private fun latestReadPosition(
            current: Long?,
            received: Long?,
        ): Long? =
            when {
                current == null -> received
                received == null -> current
                else -> maxOf(current, received)
            }

        private fun merge(newMessages: List<ChatMessage>): List<ChatMessage> =
            (mutableUiState.value.messages + newMessages)
                .distinctBy(ChatMessage::messageId)
                .sortedBy(ChatMessage::messageId)

        private fun failureMessage(result: ChatMessagesResult): String =
            when (result) {
                is ChatMessagesResult.Failure -> result.message
                is ChatMessagesResult.InvalidCursor -> result.message
                is ChatMessagesResult.InvalidAfterMessage -> result.message
                is ChatMessagesResult.RoomNotFound -> result.message
                is ChatMessagesResult.Success -> ""
            }

        private data class PendingSend(
            val clientMessageId: String,
            val content: String,
        )

        private companion object {
            // ChatRoomRoute.chatRoomId 프로퍼티 이름과 같아야 한다.
            const val CHAT_ROOM_ID_ARG = "chatRoomId"
            const val OTHER_NICKNAME_ARG = "otherNickname"
        }
    }

/** 메시지 한 건의 최대 글자 수(코드 포인트) — 서버 C4 `ChatMessageInput.MAX_CODE_POINTS` 와 같다. 입력 칸 아래 `n/1000` 으로 보인다. */
const val CHAT_MESSAGE_MAX_LENGTH = 1_000

data class ChatRoomUiState(
    val isLoading: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    val readOnly: Boolean = false,
    val readOnlyNotice: String? = null,
    val inputText: String = "",
    val isSending: Boolean = false,
    val sendErrorMessage: String? = null,
    val errorMessage: String? = null,
    val hasPrevious: Boolean = false,
    val isLoadingPrevious: Boolean = false,
    val pollAfterMs: Long = 3_000L,
    val myLastReadMessageId: Long? = null,
    val otherLastReadMessageId: Long? = null,
    val post: ChatRoomPost? = null,
    val serverNickname: String? = null,
    val routeNickname: String? = null,
) {
    /**
     * 머리줄에 쓰는 상대 이름입니다.
     *
     * C3 가 알려 주는 값이 먼저다. 서버가 아직 그 값을 모르는 판이면, 목록에서 들어올 때
     * 경로가 실어 온 이름을 쓰고, 그것도 없으면 받은 메시지에서 찾는다. 셋 다 없으면 화면이
     * "1:1 채팅" 으로 돌아간다.
     */
    val otherNickname: String?
        get() = serverNickname ?: routeNickname ?: messages.firstOrNull { !it.mine }?.senderNickname
}
