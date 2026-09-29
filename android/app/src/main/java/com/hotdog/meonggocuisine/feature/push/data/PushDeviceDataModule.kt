package com.hotdog.meonggocuisine.feature.push.data

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import retrofit2.Retrofit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PushDeviceScope

@Module
@InstallIn(SingletonComponent::class)
abstract class PushDeviceDataModule {
    @Binds
    abstract fun bindPushDeviceLifecycle(manager: PushDeviceLifecycleManager): PushDeviceLifecycle

    @Binds
    abstract fun bindInstallationIdStore(store: SharedPreferencesInstallationIdStore): InstallationIdStore

    @Binds
    abstract fun bindPushTokenProvider(provider: FirebaseMessagingTokenProvider): PushTokenProvider

    companion object {
        @Provides
        @Singleton
        fun providePushDeviceApi(retrofit: Retrofit): PushDeviceApi = retrofit.create(PushDeviceApi::class.java)

        @Provides
        @Singleton
        @PushDeviceScope
        fun providePushDeviceScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
