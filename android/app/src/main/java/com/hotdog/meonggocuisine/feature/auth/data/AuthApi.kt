package com.hotdog.meonggocuisine.feature.auth.data

import com.hotdog.meonggocuisine.core.network.ApiMessageResponse
import com.hotdog.meonggocuisine.core.network.ApiResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface AuthApi {
    @POST("auth/phone-verifications")
    suspend fun requestPhoneVerification(
        @Body request: PhoneVerificationRequest,
    ): Response<ApiMessageResponse>

    @POST("auth/phone-verifications/confirm")
    suspend fun confirmPhoneVerification(
        @Body request: PhoneConfirmationRequest,
    ): Response<ApiResponse<PhoneVerificationResponse>>

    /**
     * A0-3 — 아이디를 쓸 수 있는지 미리 확인합니다.
     *
     * 가입을 눌러야 `MEMBER-001` 로 알 수 있으면 아이디·비밀번호·닉네임·휴대전화 인증을 다 끝낸
     * 뒤에야 처음부터 다시 하게 된다. 구버전 서버에 없는 동안에는 화면에서 가입 자체를 막지 않는다.
     */
    @GET("auth/login-ids/availability")
    suspend fun checkLoginIdAvailability(
        @Query("loginId") loginId: String,
    ): Response<ApiResponse<LoginIdAvailabilityResponse>>

    // ── A9 계정 찾기 — 비로그인. 가입 여부는 A9-1 응답으로 알 수 없다(서버가 미가입에도 202).
    @POST("auth/account-recovery/phone-verifications")
    suspend fun requestAccountRecoveryCode(
        @Body request: AccountRecoveryPhoneRequest,
    ): Response<ApiMessageResponse>

    @POST("auth/account-recovery/phone-verifications/confirm")
    suspend fun confirmAccountRecoveryCode(
        @Body request: AccountRecoveryConfirmationRequest,
    ): Response<ApiResponse<AccountRecoveryConfirmationResponse>>

    @POST("auth/account-recovery/password")
    suspend fun resetPasswordByRecovery(
        @Body request: PasswordResetRequest,
    ): Response<Unit>

    @POST("auth/signup")
    suspend fun signup(
        @Body request: SignupRequest,
    ): Response<ApiResponse<SignupResponse>>

    @POST("auth/login")
    suspend fun login(
        @Body request: LoginRequest,
    ): Response<ApiResponse<LoginResponse>>

    @POST("auth/tokens/refresh")
    suspend fun refreshTokens(
        @Body request: TokenRefreshRequest,
    ): Response<ApiResponse<TokenRefreshResponse>>

    /**
     * A6 — 되살린 세션의 회원 정보를 채웁니다.
     *
     * A3 응답에는 회원 정보가 없으므로 갱신 토큰만으로 되살린 세션은 자신이 누구인지 모른다.
     * 응답의 두 필드는 A2 로그인 응답의 `member`와 같다 (docs/api-spec.md A6).
     */
    @GET("members/me")
    suspend fun currentMember(): Response<ApiResponse<AuthMember>>
}
