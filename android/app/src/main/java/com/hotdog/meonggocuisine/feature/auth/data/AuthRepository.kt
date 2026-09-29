package com.hotdog.meonggocuisine.feature.auth.data

sealed interface LoginResult {
    data object Success : LoginResult

    data class InvalidInput(val message: String) : LoginResult

    data class AuthenticationFailed(val message: String) : LoginResult

    data class TemporarilyBlocked(
        val message: String,
        val retryAfterSeconds: Int,
    ) : LoginResult

    data class ServerFailure(val message: String) : LoginResult

    data class ConnectionFailure(val message: String) : LoginResult
}

/** [AuthRepository.refreshTokens] 결과입니다. */
sealed interface TokenRefreshResult {
    /** 새 access token을 받아 세션을 교체했습니다. */
    data object Refreshed : TokenRefreshResult

    /** 재로그인이 필요합니다. 세션과 저장된 갱신 토큰은 이미 삭제했습니다. */
    data object SessionExpired : TokenRefreshResult
}

interface AuthRepository {
    suspend fun login(
        loginId: String,
        password: String,
    ): LoginResult

    /**
     * 저장된 갱신 토큰으로 access token을 새로 받습니다.
     *
     * 서버가 세션을 거부하거나 처리 여부를 알 수 없는 통신 실패가 나면 같은 토큰으로 다시
     * 시도하지 않고 세션을 삭제합니다.
     */
    suspend fun refreshTokens(): TokenRefreshResult

    /**
     * 되살린 세션의 회원 정보를 A6로 채웁니다.
     *
     * 실패해도 세션은 그대로 둡니다. 회원 정보는 화면 표시에만 쓰이고, 없다고 해서 로그인
     * 상태가 아닌 것은 아니기 때문입니다. 401은 인터셉터가 이미 갱신·재시도를 처리합니다.
     */
    suspend fun loadCurrentMember()
}
