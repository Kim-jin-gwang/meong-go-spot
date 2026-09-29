package com.hotdog.meonggocuisine.core.network

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiImageUrlResolverTest {
    private val resolver = ApiImageUrlResolver("http://10.0.2.2:8080/api/v1/")

    @Test
    fun resolvesBackendRelativePhotoUrlAgainstApiOrigin() {
        assertEquals("http://10.0.2.2:8080/api/v1/photos/42", resolver.resolve("/api/v1/photos/42"))
    }

    @Test
    fun resolvesRelativePhotoUrlWithQueryFragmentAndWhitespace() {
        assertEquals(
            "http://10.0.2.2:8080/api/v1/photos/42?size=thumb#icon",
            resolver.resolve(" /api/v1/photos/42?size=thumb#icon "),
        )
    }

    @Test
    fun keepsPublicAbsoluteUrlUnchanged() {
        val publicUrl = "https://images.example.org/animals/42.jpg"

        assertEquals(publicUrl, resolver.resolve(publicUrl))
    }

    @Test
    fun keepsLocalContentUriUnchanged() {
        val contentUri = "content://media/external/images/media/42"

        assertEquals(contentUri, resolver.resolve(contentUri))
    }

    // ── 공공 이미지 캐시 프록시 ───────────────────────────────────────────

    private val origin = "https://openapi.animal.go.kr/openapi/service/rest/fileDownloadSrvc/files"
    private val proxied = ApiImageUrlResolver("https://api.meonggo.shop/api/v1/", "https://api.meonggo.shop/img/")

    @Test
    fun routesPublicShelterImagesThroughTheProxyWhenConfigured() {
        assertEquals(
            "https://api.meonggo.shop/img/shelter/2026/09/202609091409245.jpg",
            proxied.resolve("$origin/shelter/2026/09/202609091409245.jpg"),
        )
    }

    @Test
    fun keepsPercentEncodedBracketsIntactThroughTheProxy() {
        // 공공 파일명의 ~9% 가 `…518[1].jpg` 다. 적재기가 %5B%5D 로 저장하므로 그대로 넘어가야 서버가 원본을 찾는다.
        assertEquals(
            "https://api.meonggo.shop/img/shelter/2026/09/202609111309518%5B1%5D.jpg",
            proxied.resolve("$origin/shelter/2026/09/202609111309518%5B1%5D.jpg"),
        )
    }

    @Test
    fun proxiesHttpSchemeAndUppercaseExtensionsAsWell() {
        // 적재기가 https 로 올려 저장하지만, 리졸버는 스킴을 보지 않고 호스트·경로 접두만 본다(AI 리뷰 !167 확인용).
        assertEquals(
            "https://api.meonggo.shop/img/shelter/2026/09/202609091409245.JPG",
            proxied.resolve("http://openapi.animal.go.kr/openapi/service/rest/fileDownloadSrvc/files/shelter/2026/09/202609091409245.JPG"),
        )
    }

    @Test
    fun leavesPublicImagesAloneWithoutAProxy() {
        // 로컬 개발 빌드(10.0.2.2)는 프록시가 없다 — 원본을 직접 받는다.
        val url = "$origin/shelter/2026/09/202609091409245.jpg"
        assertEquals(url, resolver.resolve(url))
        assertEquals(url, ApiImageUrlResolver("http://10.0.2.2:8080/api/v1/", "   ").resolve(url))
    }

    @Test
    fun doesNotProxyOtherHostsOrOtherPathsOnTheOriginHost() {
        val otherHost = "https://cdn.example.org/openapi/service/rest/fileDownloadSrvc/files/shelter/a.jpg"
        val otherPath = "https://openapi.animal.go.kr/openapi/service/rest/abandonmentPublicSrvc/a.jpg"
        assertEquals(otherHost, proxied.resolve(otherHost))
        assertEquals(otherPath, proxied.resolve(otherPath))
    }
}
