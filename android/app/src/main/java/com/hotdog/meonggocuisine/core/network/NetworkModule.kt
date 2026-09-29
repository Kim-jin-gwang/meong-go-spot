package com.hotdog.meonggocuisine.core.network

import com.hotdog.meonggocuisine.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideJson(): Json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    @Provides
    @Singleton
    fun provideOkHttpClient(authTokenInterceptor: AuthTokenInterceptor): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(authTokenInterceptor)
            // 게시물 등록·수정은 사진 10장(장당 10MiB, 요청 50MiB)을 한 요청에 올린다. OkHttp 기본 10초로는 폰
            // 사진 몇 장만 골라도 업로드 도중 앱이 먼저 끊어 nginx 에 499 만 남고 서버엔 닿지도 않았다
            // (2026-09-23 QA "서버에 연결할 수 없습니다"). 쓰기·읽기는 진행이 멈춘 뒤 60초, 전체 호출은 3분 상한.
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(IO_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(IO_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    fun provideRetrofit(
        json: Json,
        okHttpClient: OkHttpClient,
    ): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    private const val CONNECT_TIMEOUT_SECONDS = 15L
    private const val IO_TIMEOUT_SECONDS = 60L
    private const val CALL_TIMEOUT_SECONDS = 180L
}
