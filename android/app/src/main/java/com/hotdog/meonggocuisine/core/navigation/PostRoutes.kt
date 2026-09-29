package com.hotdog.meonggocuisine.core.navigation

import kotlinx.serialization.Serializable

@Serializable
data object MyPostListRoute : AuthRequiredRoute

@Serializable
data class PostDetailRoute(
    val postId: Long,
) : PublicRoute

@Serializable
data class PostEditRoute(
    val postId: Long,
) : AuthRequiredRoute

@Serializable
data class PostCloseRoute(
    val postId: Long,
) : AuthRequiredRoute
