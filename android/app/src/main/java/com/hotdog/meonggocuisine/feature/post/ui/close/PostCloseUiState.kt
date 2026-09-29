package com.hotdog.meonggocuisine.feature.post.ui.close

import com.hotdog.meonggocuisine.feature.post.data.PostCloseReason

data class PostCloseUiState(
    val isLoading: Boolean = true,
    val loadErrorMessage: String? = null,
    val postId: Long = 0L,
    val version: Long = 0L,
    val animalName: String? = null,
    val isSheltering: Boolean = false,
    val selectedReason: PostCloseReason? = null,
    val isConfirming: Boolean = false,
    val requestError: String? = null,
    val versionConflictMessage: String? = null,
    val isSubmitting: Boolean = false,
) {
    val displayName: String get() = animalName?.takeIf { it.isNotBlank() } ?: if (isSheltering) "보호 중인 동물" else "우리 아이"

    val canSubmit: Boolean get() = selectedReason != null && !isSubmitting && !isLoading && loadErrorMessage == null

    val isVersionConflicted: Boolean get() = versionConflictMessage != null

    val reasons: List<PostCloseReason> get() = PostCloseReason.entries
}
