package com.hotdog.meonggocuisine.feature.auth.data

interface RefreshTokenStore {
    fun save(refreshToken: String)

    fun read(): String?

    fun clear()
}
