package com.hotdog.meonggocuisine.core.update

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class UpdateModule {
    @Binds
    abstract fun bindAppVersionRepository(repository: DefaultAppVersionRepository): AppVersionRepository

    @Binds
    abstract fun bindUpdatePromptStore(store: PreferencesUpdatePromptStore): UpdatePromptStore

    companion object {
        @Provides
        @Singleton
        fun provideAppVersionApi(retrofit: Retrofit): AppVersionApi = retrofit.create(AppVersionApi::class.java)
    }
}
