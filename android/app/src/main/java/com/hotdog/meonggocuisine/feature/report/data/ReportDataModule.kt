package com.hotdog.meonggocuisine.feature.report.data

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ReportDataModule {
    @Binds
    abstract fun bindLostPostCreateRepository(repository: DefaultLostPostCreateRepository): LostPostCreateRepository

    @Binds
    abstract fun bindShelteringPostCreateRepository(repository: DefaultShelteringPostCreateRepository): ShelteringPostCreateRepository

    @Binds
    abstract fun bindPhotoInputInspector(inspector: DefaultPhotoInputInspector): PhotoInputInspector

    companion object {
        @Provides
        @Singleton
        fun provideReportApi(retrofit: Retrofit): ReportApi = retrofit.create(ReportApi::class.java)
    }
}
