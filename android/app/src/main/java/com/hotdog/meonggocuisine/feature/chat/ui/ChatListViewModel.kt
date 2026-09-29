package com.hotdog.meonggocuisine.feature.chat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hotdog.meonggocuisine.feature.chat.data.ChatRepository
import com.hotdog.meonggocuisine.feature.chat.data.ChatRoomListResult
import com.hotdog.meonggocuisine.feature.chat.data.ChatRoomSummary
import com.hotdog.meonggocuisine.feature.push.chat.ChatPushEventSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatListViewModel
    @Inject
    constructor(
        private val repository: ChatRepository,
        private val chatPushEvents: ChatPushEventSource,
    ) : ViewModel() {
        private val mutableUiState = MutableStateFlow(ChatListUiState(isLoading = true))
        val uiState: StateFlow<ChatListUiState> = mutableUiState.asStateFlow()

        private var requestJob: Job? = null
        private var pushCollectionJob: Job? = null
        private var refreshPending = false
        private var hasResumedOnce = false

        init {
            loadRooms()
        }

        fun refresh() {
            if (requestJob?.isActive == true) {
                refreshPending = true
                return
            }
            startRefresh()
        }

        private fun startRefresh() {
            val state = mutableUiState.value
            mutableUiState.value =
                state.copy(
                    isLoading = state.rooms.isEmpty(),
                    isRefreshing = state.rooms.isNotEmpty(),
                    errorMessage = null,
                )
            loadRooms()
        }

        fun onScreenResumed() {
            if (pushCollectionJob == null) {
                pushCollectionJob =
                    viewModelScope.launch {
                        chatPushEvents.messages.collect { refresh() }
                    }
            }
            if (hasResumedOnce) {
                refresh()
            } else {
                hasResumedOnce = true
            }
        }

        fun onScreenPaused() {
            pushCollectionJob?.cancel()
            pushCollectionJob = null
        }

        fun retry() {
            if (requestJob?.isActive == true) return
            mutableUiState.value = mutableUiState.value.copy(isLoading = true, errorMessage = null)
            loadRooms()
        }

        fun loadMore() {
            val state = mutableUiState.value
            if (requestJob?.isActive == true || state.isLoadingMore || !state.hasNext || state.nextCursor == null) return
            mutableUiState.value = state.copy(isLoadingMore = true, errorMessage = null)
            loadRooms(cursor = state.nextCursor)
        }

        private fun loadRooms(cursor: String? = null) {
            requestJob =
                viewModelScope.launch {
                    try {
                        when (val result = repository.getChatRooms(cursor)) {
                            is ChatRoomListResult.Success -> showRooms(result, append = cursor != null)
                            is ChatRoomListResult.InvalidCursor -> {
                                val state = mutableUiState.value
                                mutableUiState.value =
                                    state.copy(
                                        isLoading = state.rooms.isEmpty(),
                                        isRefreshing = state.rooms.isNotEmpty(),
                                        isLoadingMore = false,
                                        errorMessage = null,
                                    )
                                refreshPending = true
                            }
                            is ChatRoomListResult.Failure -> showError(result.message, append = cursor != null)
                        }
                    } finally {
                        requestJob = null
                        if (refreshPending) {
                            refreshPending = false
                            startRefresh()
                        }
                    }
                }
        }

        private fun showRooms(
            result: ChatRoomListResult.Success,
            append: Boolean,
        ) {
            val currentRooms = if (append) mutableUiState.value.rooms else emptyList()
            mutableUiState.value =
                mutableUiState.value.copy(
                    rooms = (currentRooms + result.rooms).distinctBy(ChatRoomSummary::chatRoomId),
                    isLoading = false,
                    isRefreshing = false,
                    isLoadingMore = false,
                    hasNext = result.hasNext,
                    nextCursor = result.nextCursor,
                    errorMessage = null,
                )
        }

        private fun showError(
            message: String,
            append: Boolean,
        ) {
            val state = mutableUiState.value
            mutableUiState.value =
                state.copy(
                    isLoading = false,
                    isRefreshing = false,
                    isLoadingMore = false,
                    errorMessage = message,
                    hasNext = if (append || state.rooms.isNotEmpty()) state.hasNext else false,
                )
        }
    }

data class ChatListUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val rooms: List<ChatRoomSummary> = emptyList(),
    val isLoadingMore: Boolean = false,
    val hasNext: Boolean = false,
    val nextCursor: String? = null,
    val errorMessage: String? = null,
)
