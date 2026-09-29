package com.hotdog.meonggocuisine.feature.account.data

/** 서버가 거부한 입력이 어느 칸의 것인지. 화면은 이 값으로 오류를 해당 입력 아래에 붙인다. */
enum class AccountField { NICKNAME, CURRENT_PASSWORD, NEW_PASSWORD }

sealed interface AccountResult {
    data object Success : AccountResult

    /** 서버가 요청을 거부했다. [field] 가 있으면 그 입력의 문제, 없으면 화면 전체 오류다. */
    data class Rejected(
        val message: String,
        val field: AccountField? = null,
    ) : AccountResult

    /** 세션이 더 유효하지 않다. 로컬 세션은 이미 지웠으므로 화면은 로그인 전 상태로 돌아가면 된다. */
    data object SessionExpired : AccountResult
}

sealed interface ProfileResult {
    data class Loaded(val nickname: String) : ProfileResult

    data class Failed(val message: String) : ProfileResult

    data object SessionExpired : ProfileResult
}

interface AccountRepository {
    /** A6. 앱 재시작 뒤 갱신 토큰만으로 살린 세션에는 닉네임이 없어 서버에서 읽는다. */
    suspend fun loadProfile(): ProfileResult

    /** A7. 성공하면 세션의 회원 정보도 새 닉네임으로 바꾼다. */
    suspend fun changeNickname(nickname: String): AccountResult

    /** A8. 성공하면 다른 기기 세션은 서버가 끊고, 이 기기 세션은 그대로 쓴다. */
    suspend fun changePassword(
        currentPassword: String,
        newPassword: String,
    ): AccountResult

    /** A5. 성공하면 로컬 세션과 저장된 갱신 토큰을 지운다. */
    suspend fun withdraw(currentPassword: String): AccountResult

    /**
     * A4. 서버 통보는 최선 노력이다 — 실패해도 로컬 세션은 반드시 지운다. 사용자가 "로그아웃"을 눌렀는데
     * 네트워크 때문에 로그인 상태가 남아 있으면 더 나쁘기 때문이다.
     */
    suspend fun logout()
}
