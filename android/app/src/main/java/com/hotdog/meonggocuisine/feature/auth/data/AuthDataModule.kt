package com.hotdog.meonggocuisine.feature.auth.data

import com.hotdog.meonggocuisine.core.network.AuthTokenProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthDataModule {
    @Binds
    abstract fun bindAuthRepository(repository: DefaultAuthRepository): AuthRepository

    @Binds
    abstract fun bindAuthTokenProvider(provider: SessionAuthTokenProvider): AuthTokenProvider

    @Binds
    abstract fun bindSignupRepository(repository: DefaultSignupRepository): SignupRepository

    @Binds
    abstract fun bindAccountRecoveryRepository(repository: DefaultAccountRecoveryRepository): AccountRecoveryRepository

    @Binds
    abstract fun bindRefreshTokenStore(store: KeystoreRefreshTokenStore): RefreshTokenStore

    companion object {
        @Provides
        @Singleton
        fun provideAuthApi(retrofit: Retrofit): AuthApi = retrofit.create(AuthApi::class.java)
    }
}
