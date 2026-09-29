package com.hotdog.meonggocuisine.core.network

import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.map.Mapper
import coil.request.Options
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient

class ApiImageUrlMapper(
    private val urls: ApiImageUrlResolver,
) : Mapper<String, String> {
    override fun map(
        data: String,
        options: Options,
    ): String? = urls.resolve(data).takeIf { it != data }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ApiImageLoaderEntryPoint {
    fun apiImageUrlResolver(): ApiImageUrlResolver

    fun okHttpClient(): OkHttpClient
}

class ApiImageLoaderFactory(
    private val context: Context,
) : ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader {
        val entryPoint =
            EntryPointAccessors.fromApplication(context, ApiImageLoaderEntryPoint::class.java)
        return ImageLoader.Builder(context)
            .okHttpClient { entryPoint.okHttpClient() }
            .components {
                add(ApiImageUrlMapper(entryPoint.apiImageUrlResolver()))
            }
            // 공공 이미지 원본 서버(openapi.animal.go.kr)는 Cache-Control·ETag·Last-Modified 를 하나도
            // 보내지 않는다(2026-09-14 실측). 기본값(true)대로 원본 헤더를 따르면 신선도도 검증자도 없어
            // 스크롤로 돌아올 때마다 같은 사진을 다시 받는다 — 장당 TTFB 중앙값 0.7초, p90 6초.
            // 공고 사진은 한 번 올라오면 바뀌지 않으므로 헤더와 무관하게 디스크에 둔다.
            .respectCacheHeaders(false)
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    // 목록 한 페이지(10장) ≈ 3.6MB, 원본 중앙값 365KB. 256MB 면 약 700장 — 며칠치 열람 분량.
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
    }
}
