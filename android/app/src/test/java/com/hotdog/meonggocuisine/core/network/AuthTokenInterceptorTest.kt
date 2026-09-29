package com.hotdog.meonggocuisine.core.network

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import javax.inject.Provider

class AuthTokenInterceptorTest {
    @Test
    fun attachesAccessTokenOnlyToConfiguredApiOrigin() {
        val apiServer = MockWebServer()
        val externalServer = MockWebServer()
        apiServer.start()
        externalServer.start()

        try {
            apiServer.enqueue(MockResponse().setResponseCode(200))
            externalServer.enqueue(MockResponse().setResponseCode(200))
            val client =
                OkHttpClient.Builder()
                    .addInterceptor(
                        AuthTokenInterceptor(
                            Provider { FakeAuthTokenProvider() },
                            Json,
                            apiServer.url("/api/v1/").toString(),
                        ),
                    ).build()

            client.newCall(Request.Builder().url(apiServer.url("/api/v1/posts")).build()).execute().close()
            client.newCall(Request.Builder().url(externalServer.url("/animals/42.jpg")).build()).execute().close()

            assertEquals("Bearer access-token", apiServer.takeRequest().getHeader("Authorization"))
            assertNull(externalServer.takeRequest().getHeader("Authorization"))
        } finally {
            apiServer.shutdown()
            externalServer.shutdown()
        }
    }

    @Test
    fun `회원가입 전 공개 API에는 만료 토큰을 붙이거나 갱신하지 않는다`() {
        val apiServer = MockWebServer()
        apiServer.start()
        try {
            repeat(3) {
                apiServer.enqueue(
                    MockResponse()
                        .setResponseCode(401)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"code":"AUTH-002","message":"access token expired"}"""),
                )
            }
            val tokenProvider = FakeAuthTokenProvider()
            val client =
                OkHttpClient.Builder()
                    .addInterceptor(
                        AuthTokenInterceptor(
                            Provider { tokenProvider },
                            Json,
                            apiServer.url("/api/v1/").toString(),
                        ),
                    ).build()

            listOf(
                "/api/v1/auth/phone-verifications",
                "/api/v1/auth/phone-verifications/confirm",
                "/api/v1/auth/login-ids/availability?loginId=mango206",
            ).forEach { path ->
                client
                    .newCall(Request.Builder().url(apiServer.url(path)).build())
                    .execute()
                    .close()
            }

            repeat(3) {
                assertNull(apiServer.takeRequest().getHeader("Authorization"))
            }
            assertEquals(0, tokenProvider.refreshCount)
        } finally {
            apiServer.shutdown()
        }
    }

    private class FakeAuthTokenProvider : AuthTokenProvider {
        var refreshCount = 0

        override fun accessToken(): String = "access-token"

        override fun refresh(usedAccessToken: String?): AuthRefreshResult {
            refreshCount += 1
            return AuthRefreshResult.SESSION_EXPIRED
        }

        override fun invalidate() = Unit
    }
}
