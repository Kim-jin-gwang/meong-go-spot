package com.hotdog.meonggocuisine.feature.account.data

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AccountDataModule {
    @Binds
    abstract fun bindAccountRepository(repository: DefaultAccountRepository): AccountRepository

    companion object {
        @Provides
        @Singleton
        fun provideAccountApi(retrofit: Retrofit): AccountApi = retrofit.create(AccountApi::class.java)
    }
}
