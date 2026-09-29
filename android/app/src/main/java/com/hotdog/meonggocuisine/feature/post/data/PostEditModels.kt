package com.hotdog.meonggocuisine.feature.post.data

import kotlinx.serialization.Serializable

@Serializable
data class UpdatePostResponse(
    val postId: Long,
    val version: Long,
    val updatedAt: String? = null,
)

@Serializable
data class ReplacePhotosResponse(
    val postId: Long,
    val version: Long,
    val photos: List<PostPhotoResponse> = emptyList(),
    val updatedAt: String? = null,
)

@Serializable
data class ClosePostRequest(
    val version: Long,
    val reason: String,
)

@Serializable
data class ClosePostResponse(
    val postId: Long,
    val status: String,
    val version: Long,
    val closeReason: String? = null,
    val closedAt: String? = null,
)

@Serializable
data class ReplacePhotosPayload(
    val version: Long,
)

const val EXACT_LOCATION_POLICY_VERSION = "exact-location-v1"
