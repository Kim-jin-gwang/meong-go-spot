package com.hotdog.meonggocuisine.feature.post.data

import kotlinx.serialization.Serializable

@Serializable
data class MyPostListResponse(
    val items: List<MyPostSummary>,
    val page: MyPostListPage,
)

@Serializable
data class MyPostSummary(
    val postId: Long,
    val type: String,
    val source: String,
    val status: String,
    val version: Int,
    val name: String? = null,
    val species: String,
    val eventDate: String,
    val listedAt: String,
    val publicLocation: String,
    val thumbnailUrl: String? = null,
    val updatedAt: String,
)

@Serializable
data class MyPostListPage(
    val size: Int,
    val hasNext: Boolean,
    val nextCursor: String? = null,
)
