package com.hotdog.meonggocuisine.feature.post.data

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PostDataModule {
    @Binds
    abstract fun bindPostDetailRepository(repository: DefaultPostDetailRepository): PostDetailRepository

    @Binds
    abstract fun bindMyPostRepository(repository: DefaultMyPostRepository): MyPostRepository

    @Binds
    abstract fun bindPostEditRepository(repository: DefaultPostEditRepository): PostEditRepository

    companion object {
        @Provides
        @Singleton
        fun providePostApi(retrofit: Retrofit): PostApi = retrofit.create(PostApi::class.java)
    }
}
