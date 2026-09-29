package com.hotdog.meonggocuisine.core.network

/**
 * 네트워크 계층에 인증 토큰을 공급하는 경계입니다.
 *
 * 구현은 인증 기능에 두어 `core/network`가 기능 패키지를 직접 참조하지 않게 합니다.
 */
interface AuthTokenProvider {
    /** 현재 access token입니다. 로그인 상태가 아니면 null입니다. */
    fun accessToken(): String?

    /**
     * access token을 갱신합니다. 여러 요청이 동시에 호출해도 갱신은 한 번만 수행하고
     * 나머지 요청은 그 결과를 함께 사용합니다.
     *
     * @param usedAccessToken 거부된 요청이 실제로 보낸 access token입니다.
     */
    fun refresh(usedAccessToken: String?): AuthRefreshResult

    /** 세션과 저장된 갱신 토큰을 삭제해 재로그인이 필요한 상태로 만듭니다. */
    fun invalidate()
}

/** [AuthTokenProvider.refresh] 결과입니다. */
enum class AuthRefreshResult {
    /** 새 access token을 받았습니다. 거부된 요청을 한 번 재시도할 수 있습니다. */
    REFRESHED,

    /** 재로그인이 필요합니다. 세션과 저장된 갱신 토큰은 이미 삭제했습니다. */
    SESSION_EXPIRED,
}
