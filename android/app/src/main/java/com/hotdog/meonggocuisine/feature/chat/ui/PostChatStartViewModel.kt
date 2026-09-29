package com.hotdog.meonggocuisine.feature.chat.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hotdog.meonggocuisine.feature.chat.data.ChatRepository
import com.hotdog.meonggocuisine.feature.chat.data.ChatRoomStartResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PostChatStartViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val repository: ChatRepository,
    ) : ViewModel() {
        // toRoute()는 Android Bundle이 필요해 JVM 단위 테스트에서 실행할 수 없어 키로 직접 읽는다.
        // PostChatStartRoute.postId 프로퍼티 이름과 같아야 한다.
        private val postId: Long = checkNotNull(savedStateHandle["postId"])
        private val mutableUiState = MutableStateFlow(PostChatStartUiState(isLoading = true))
        val uiState: StateFlow<PostChatStartUiState> = mutableUiState.asStateFlow()

        init {
            start()
        }

        fun retry() {
            if (mutableUiState.value.isLoading) return
            start()
        }

        private fun start() {
            viewModelScope.launch {
                mutableUiState.value = PostChatStartUiState(isLoading = true)
                mutableUiState.value =
                    when (val result = repository.startChatRoom(postId)) {
                        is ChatRoomStartResult.Success ->
                            PostChatStartUiState(chatRoomId = result.chatRoomId)
                        is ChatRoomStartResult.NotAllowed ->
                            PostChatStartUiState(errorMessage = result.message, canRetry = false)
                        is ChatRoomStartResult.Failure ->
                            PostChatStartUiState(errorMessage = result.message, canRetry = true)
                    }
            }
        }
    }

data class PostChatStartUiState(
    val isLoading: Boolean = false,
    val chatRoomId: Long? = null,
    val errorMessage: String? = null,
    val canRetry: Boolean = false,
)
