package com.hotdog.meonggocuisine.feature.community.data

import kotlinx.serialization.Serializable

@Serializable
data class PostListResponse(
    val items: List<LostPostSummary>,
    val page: PostListPage,
)

@Serializable
data class LostPostSummary(
    val postId: Long,
    val type: String,
    val source: String,
    val name: String? = null,
    val species: String,
    val breedName: String? = null,
    val sex: String,
    val color: String? = null,
    val eventDate: String,
    val listedAt: String,
    val publicLocation: String,
    val thumbnailUrl: String? = null,
)

@Serializable
data class PostListPage(
    val size: Int,
    val hasNext: Boolean,
    val nextCursor: String? = null,
)
