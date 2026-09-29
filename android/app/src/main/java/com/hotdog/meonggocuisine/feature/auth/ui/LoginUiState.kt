package com.hotdog.meonggocuisine.feature.auth.ui

data class LoginUiState(
    val loginId: String = "",
    val password: String = "",
    val loginIdError: String? = null,
    val passwordError: String? = null,
    val requestError: String? = null,
    val isLoading: Boolean = false,
    val retryAfterSeconds: Int = 0,
) {
    val canSubmit: Boolean
        get() = !isLoading && retryAfterSeconds == 0
}
