package com.hotdog.meonggocuisine

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.hotdog.meonggocuisine.core.network.ApiImageLoaderFactory
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MeonggoApplication :
    Application(),
    ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader = ApiImageLoaderFactory(this).newImageLoader()
}
