package com.hotdog.meonggocuisine.feature.chat.data

import com.hotdog.meonggocuisine.core.network.ApiResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface ChatApi {
    @POST("posts/{postId}/chat-room")
    suspend fun startChatRoom(
        @Path("postId") postId: Long,
    ): Response<ApiResponse<ChatRoomStartResponse>>

    @GET("chat-rooms")
    suspend fun getChatRooms(
        @Query("cursor") cursor: String? = null,
    ): Response<ApiResponse<ChatRoomListResponse>>

    @GET("chat-rooms/{chatRoomId}/messages")
    suspend fun getMessages(
        @Path("chatRoomId") chatRoomId: Long,
        @Query("cursor") cursor: String? = null,
        @Query("afterMessageId") afterMessageId: Long? = null,
    ): Response<ApiResponse<ChatMessagesResponse>>

    @POST("chat-rooms/{chatRoomId}/messages")
    suspend fun sendMessage(
        @Path("chatRoomId") chatRoomId: Long,
        @Body request: ChatMessageSendRequest,
    ): Response<ApiResponse<ChatMessageDto>>

    @PUT("chat-rooms/{chatRoomId}/read")
    suspend fun updateLastRead(
        @Path("chatRoomId") chatRoomId: Long,
        @Body request: ChatReadUpdateRequest,
    ): Response<ApiResponse<ChatReadUpdateResponse>>
}
