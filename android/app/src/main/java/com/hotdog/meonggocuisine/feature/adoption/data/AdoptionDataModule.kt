package com.hotdog.meonggocuisine.feature.adoption.data

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AdoptionDataModule {
    @Binds
    abstract fun bindAdoptionRepository(repository: DefaultAdoptionRepository): AdoptionRepository

    companion object {
        @Provides
        @Singleton
        fun provideAdoptionApi(retrofit: Retrofit): AdoptionApi = retrofit.create(AdoptionApi::class.java)
    }
}
