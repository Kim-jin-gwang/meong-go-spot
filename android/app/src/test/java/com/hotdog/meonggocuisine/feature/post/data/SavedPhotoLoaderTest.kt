package com.hotdog.meonggocuisine.feature.post.data

import com.hotdog.meonggocuisine.core.network.ApiImageUrlResolver
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 남기기로 한 사진을 다시 싣기 위한 내려받기입니다.
 *
 * 서버가 부분 편집을 안 받아서 생긴 경로라 실패가 조용하면 안 된다 — 못 받은 걸 모르고 올리면
 * 그 사진만 사라진 채로 저장된다.
 */
class SavedPhotoLoaderTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `상대 경로를 API 주소로 풀어 내려받는다`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "image/jpeg")
                .setBody(Buffer().write(bytes)),
        )

        val photo = loader().load("/api/v1/photos/3101")

        assertEquals("/api/v1/photos/3101", server.takeRequest().path)
        assertEquals("image/jpeg", photo.mimeType)
        assertEquals("3101", photo.filename)
        assertArrayEquals(bytes, photo.bytes)
    }

    @Test(expected = SavedPhotoUnavailableException::class)
    fun `사진을 못 받으면 예외로 알린다`() {
        server.enqueue(MockResponse().setResponseCode(404))

        loader().load("/api/v1/photos/3101")
    }

    @Test(expected = SavedPhotoUnavailableException::class)
    fun `이미지가 아닌 응답도 예외로 알린다`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"code":"AUTH-002"}"""),
        )

        loader().load("/api/v1/photos/3101")
    }

    private fun loader() =
        SavedPhotoLoader(
            httpClient = OkHttpClient(),
            imageUrls = ApiImageUrlResolver(apiBaseUrl = server.url("/api/v1/").toString()),
        )
}
