package com.hotdog.meonggocuisine.core.navigation

import kotlinx.serialization.Serializable

@Serializable
data object LostPostCreateRoute : AuthRequiredRoute

@Serializable
data object ShelteringPostCreateRoute : AuthRequiredRoute
