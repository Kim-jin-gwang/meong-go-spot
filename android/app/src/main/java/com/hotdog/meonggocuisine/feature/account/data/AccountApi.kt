package com.hotdog.meonggocuisine.feature.account.data

import com.hotdog.meonggocuisine.core.network.ApiResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT

/** 본인 계정 API — docs/api-spec.md A4~A8. 모두 로그인 필요. */
interface AccountApi {
    @GET("members/me")
    suspend fun profile(): Response<ApiResponse<MemberProfileResponse>>

    @PATCH("members/me")
    suspend fun changeNickname(
        @Body request: NicknameUpdateRequest,
    ): Response<ApiResponse<MemberProfileResponse>>

    @PUT("members/me/password")
    suspend fun changePassword(
        @Body request: PasswordChangeRequest,
    ): Response<Unit>

    @POST("members/me/withdrawal")
    suspend fun withdraw(
        @Body request: WithdrawalRequest,
    ): Response<Unit>

    @POST("auth/logout")
    suspend fun logout(
        @Body request: LogoutRequest,
    ): Response<Unit>
}
