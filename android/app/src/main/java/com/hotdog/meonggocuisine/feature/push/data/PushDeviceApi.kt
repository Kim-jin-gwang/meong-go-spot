package com.hotdog.meonggocuisine.feature.push.data

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.PUT
import retrofit2.http.Path

@Serializable
data class PushDeviceRegistrationRequest(
    val platform: String = "ANDROID",
    val token: String,
)

interface PushDeviceApi {
    @PUT("members/me/push-devices/{installationId}")
    suspend fun register(
        @Path("installationId") installationId: String,
        @Body request: PushDeviceRegistrationRequest,
    ): Response<Unit>

    @DELETE("members/me/push-devices/{installationId}")
    suspend fun unregister(
        @Path("installationId") installationId: String,
    ): Response<Unit>
}
