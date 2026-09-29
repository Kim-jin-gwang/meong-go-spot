package com.hotdog.meonggocuisine.feature.post.ui.close

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hotdog.meonggocuisine.feature.post.data.PostCloseReason
import com.hotdog.meonggocuisine.feature.post.data.PostCloseResult
import com.hotdog.meonggocuisine.feature.post.data.PostDetailRepository
import com.hotdog.meonggocuisine.feature.post.data.PostDetailResponse
import com.hotdog.meonggocuisine.feature.post.data.PostDetailResult
import com.hotdog.meonggocuisine.feature.post.data.PostEditRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface PostCloseEvent {
    data class Closed(val postId: Long) : PostCloseEvent
}

@HiltViewModel
class PostCloseViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val detailRepository: PostDetailRepository,
        private val editRepository: PostEditRepository,
    ) : ViewModel() {
        // toRoute()는 Android Bundle이 필요해 JVM 단위 테스트에서 실행할 수 없어 키로 직접 읽는다.
        val postId: Long = checkNotNull(savedStateHandle[POST_ID_ARG])

        private val mutableUiState = MutableStateFlow(PostCloseUiState(postId = postId))
        val uiState: StateFlow<PostCloseUiState> = mutableUiState.asStateFlow()

        private val eventChannel = Channel<PostCloseEvent>(Channel.BUFFERED)
        val events = eventChannel.receiveAsFlow()

        init {
            load()
        }

        fun refresh() = load()

        fun retryLoad() = load()

        fun onReasonSelect(reason: PostCloseReason) = update { copy(selectedReason = reason, requestError = null) }

        /** 종료는 되돌릴 수 없으므로 사유를 고른 뒤 확인 단계를 한 번 더 거친다. */
        fun onCloseRequest() {
            val state = mutableUiState.value
            if (!state.canSubmit) return
            update { copy(isConfirming = true, requestError = null, versionConflictMessage = null) }
        }

        fun onConfirmDismiss() = update { copy(isConfirming = false) }

        fun onConfirm() {
            val state = mutableUiState.value
            val reason = state.selectedReason ?: return
            if (state.isSubmitting) return

            update { copy(isConfirming = false, isSubmitting = true, requestError = null, versionConflictMessage = null) }
            viewModelScope.launch {
                when (val result = editRepository.closePost(postId, state.version, reason)) {
                    is PostCloseResult.Success -> {
                        update { copy(isSubmitting = false, version = result.version) }
                        eventChannel.send(PostCloseEvent.Closed(postId))
                    }
                    is PostCloseResult.VersionConflict ->
                        update { copy(isSubmitting = false, versionConflictMessage = result.message) }
                    is PostCloseResult.Unauthorized -> update { copy(isSubmitting = false, requestError = result.message) }
                    is PostCloseResult.Forbidden -> update { copy(isSubmitting = false, requestError = result.message) }
                    is PostCloseResult.NotEditable -> update { copy(isSubmitting = false, requestError = result.message) }
                    is PostCloseResult.NotFound -> update { copy(isSubmitting = false, requestError = result.message) }
                    is PostCloseResult.Failure -> update { copy(isSubmitting = false, requestError = result.message) }
                }
            }
        }

        private fun load() {
            update { copy(isLoading = true, loadErrorMessage = null, versionConflictMessage = null) }
            viewModelScope.launch {
                when (val result = detailRepository.getPostDetail(postId)) {
                    is PostDetailResult.Success -> applyDetail(result.detail)
                    is PostDetailResult.NotFound ->
                        update { copy(isLoading = false, loadErrorMessage = result.message) }
                    is PostDetailResult.Failure ->
                        update { copy(isLoading = false, loadErrorMessage = result.message) }
                }
            }
        }

        private fun applyDetail(detail: PostDetailResponse) {
            val rejection = detail.closeRejectionMessage()
            if (rejection != null) {
                update { copy(isLoading = false, loadErrorMessage = rejection) }
                return
            }
            update {
                copy(
                    isLoading = false,
                    loadErrorMessage = null,
                    postId = detail.postId,
                    version = detail.version ?: 0L,
                    animalName = detail.name,
                    isSheltering = detail.type == SHELTERING_TYPE,
                )
            }
        }

        private fun PostDetailResponse.closeRejectionMessage(): String? =
            when {
                source == SHELTER_SOURCE -> "공공 보호동물 정보는 종료할 수 없습니다."
                owner != true -> "본인이 등록한 게시물만 종료할 수 있습니다."
                status != ACTIVE_STATUS -> "이미 종료된 게시물입니다."
                version == null -> "게시물 버전을 확인할 수 없어 종료할 수 없습니다. 잠시 후 다시 시도해 주세요."
                else -> null
            }

        private fun update(block: PostCloseUiState.() -> PostCloseUiState) {
            mutableUiState.value = mutableUiState.value.block()
        }

        private companion object {
            const val POST_ID_ARG = "postId"
            const val SHELTER_SOURCE = "SHELTER"
            const val SHELTERING_TYPE = "SHELTERING"
            const val ACTIVE_STATUS = "ACTIVE"
        }
    }
