package com.hotdog.meonggocuisine.feature.community.data

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CommunityDataModule {
    @Binds
    abstract fun bindLostPostRepository(repository: DefaultLostPostRepository): LostPostRepository

    @Binds
    abstract fun bindShelteringPostRepository(repository: DefaultShelteringPostRepository): ShelteringPostRepository

    @Binds
    abstract fun bindRegionStore(store: SelectedRegionStore): RegionStore

    companion object {
        @Provides
        @Singleton
        fun provideCommunityApi(retrofit: Retrofit): CommunityApi = retrofit.create(CommunityApi::class.java)
    }
}
