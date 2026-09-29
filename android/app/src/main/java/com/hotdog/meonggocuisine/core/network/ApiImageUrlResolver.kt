package com.hotdog.meonggocuisine.core.network

import com.hotdog.meonggocuisine.BuildConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coil 에 넘길 사진 URL 을 만든다.
 *
 * 1. 백엔드가 준 상대 경로(`/api/v1/photos/{id}`)는 API origin 에 붙인다.
 * 2. 공공 API 원본 이미지(`https://openapi.animal.go.kr/openapi/service/rest/fileDownloadSrvc/files/…`)는
 *    [imageProxyBaseUrl] 이 있으면 우리 서버의 캐시 프록시(`https://api.meonggo.shop/img/…`)로 바꾼다.
 *    원본 서버는 TTFB 중앙값 0.7초·p90 6초에 캐시 헤더가 없다(2026-09-14 실측). 프록시는 한 사진을
 *    전 사용자 통틀어 한 번만 원본에서 받고, 이후는 서버 1 디스크에서 준다. 로컬 개발(10.0.2.2)처럼
 *    프록시가 없는 빌드는 [imageProxyBaseUrl] 이 비어 있어 원본 URL 을 그대로 쓴다.
 */
@Singleton
class ApiImageUrlResolver private constructor(
    private val apiOrigin: HttpUrl,
    private val imageProxyBaseUrl: HttpUrl?,
) {
    @Inject
    constructor() : this(
        BuildConfig.API_BASE_URL.toApiOrigin(),
        BuildConfig.IMAGE_PROXY_BASE_URL.toProxyBaseOrNull(),
    )

    internal constructor(apiBaseUrl: String, imageProxyBaseUrl: String = "") :
        this(apiBaseUrl.toApiOrigin(), imageProxyBaseUrl.toProxyBaseOrNull())

    fun resolve(value: String): String {
        val normalizedValue = value.trim()
        if (normalizedValue.startsWith(API_PATH_PREFIX)) {
            return apiOrigin.resolve(normalizedValue)?.toString() ?: value
        }
        val proxy = imageProxyBaseUrl ?: return value
        val url = normalizedValue.toHttpUrlOrNull() ?: return value
        if (url.host != PUBLIC_IMAGE_HOST || !url.encodedPath.startsWith(PUBLIC_IMAGE_PATH_PREFIX)) {
            return value
        }
        // 대괄호가 든 파일명(`…518[1].jpg`)이 %5B%5D 로 인코딩된 채 그대로 전달되도록 encodedPath 를 쓴다.
        val rest = url.encodedPath.removePrefix(PUBLIC_IMAGE_PATH_PREFIX)
        return proxy.newBuilder().addEncodedPathSegments(rest).build().toString()
    }

    private companion object {
        const val API_PATH_PREFIX = "/api/v1/"
        const val PUBLIC_IMAGE_HOST = "openapi.animal.go.kr"
        const val PUBLIC_IMAGE_PATH_PREFIX = "/openapi/service/rest/fileDownloadSrvc/files/"

        fun String.toApiOrigin(): HttpUrl =
            toHttpUrl()
                .newBuilder()
                .encodedPath("/")
                .query(null)
                .fragment(null)
                .build()

        fun String.toProxyBaseOrNull(): HttpUrl? = trim().takeIf { it.isNotEmpty() }?.toHttpUrlOrNull()
    }
}
