package com.hotdog.meonggocuisine.feature.match.data

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class MatchDataModule {
    @Binds
    abstract fun bindMatchRepository(repository: DefaultMatchRepository): MatchRepository

    companion object {
        @Provides
        @Singleton
        fun provideMatchApi(retrofit: Retrofit): MatchApi = retrofit.create(MatchApi::class.java)
    }
}
