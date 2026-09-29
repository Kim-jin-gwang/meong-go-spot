package com.hotdog.meonggocuisine.core.network

import com.hotdog.meonggocuisine.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Provider

/**
 * 보호 API 요청에 `Authorization` header를 붙이고, 만료된 access token 거부를 한 번만
 * 갱신·재시도합니다.
 *
 * 토큰 공급자는 Retrofit에 의존하므로 [Provider]로 늦게 받아 객체 생성 순환을 끊습니다.
 * 토큰과 header 값은 로그에 남기지 않습니다.
 */
class AuthTokenInterceptor
    private constructor(
        private val authTokenProvider: Provider<AuthTokenProvider>,
        private val json: Json,
        private val apiBaseUrl: HttpUrl,
    ) : Interceptor {
        @Inject
        constructor(
            authTokenProvider: Provider<AuthTokenProvider>,
            json: Json,
        ) : this(authTokenProvider, json, BuildConfig.API_BASE_URL.toHttpUrl())

        internal constructor(
            authTokenProvider: Provider<AuthTokenProvider>,
            json: Json,
            apiBaseUrl: String,
        ) : this(authTokenProvider, json, apiBaseUrl.toHttpUrl())

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            if (!request.isApiRequest() || request.isUnauthenticatedEndpoint()) return chain.proceed(request)

            val provider = authTokenProvider.get()
            val usedAccessToken = provider.accessToken()
            val response = chain.proceed(request.withBearer(usedAccessToken))

            return when (response.unauthorizedCode()) {
                EXPIRED_ACCESS_TOKEN -> retryAfterRefresh(chain, request, response, provider, usedAccessToken)
                INVALID_SESSION -> response.also { provider.invalidate() }
                else -> response
            }
        }

        /** 갱신에 성공하면 거부된 요청을 새 토큰으로 한 번만 다시 보냅니다. */
        private fun retryAfterRefresh(
            chain: Interceptor.Chain,
            request: Request,
            unauthorized: Response,
            provider: AuthTokenProvider,
            usedAccessToken: String?,
        ): Response =
            when (provider.refresh(usedAccessToken)) {
                AuthRefreshResult.REFRESHED -> {
                    unauthorized.close()
                    chain.proceed(request.withBearer(provider.accessToken()))
                }

                AuthRefreshResult.SESSION_EXPIRED -> unauthorized
            }

        private fun Request.isUnauthenticatedEndpoint(): Boolean {
            val path = url.encodedPath.trimEnd('/')
            return UNAUTHENTICATED_PATHS.any { path.endsWith("/$it") }
        }

        /** API origin 밖의 요청에는 access token을 전달하지 않습니다. */
        private fun Request.isApiRequest(): Boolean =
            url.scheme == apiBaseUrl.scheme &&
                url.host == apiBaseUrl.host &&
                url.port == apiBaseUrl.port &&
                url.encodedPath.startsWith(apiPathPrefix())

        private fun apiPathPrefix(): String = apiBaseUrl.encodedPath.trimEnd('/') + "/"

        private fun Request.withBearer(accessToken: String?): Request =
            if (accessToken == null) {
                this
            } else {
                newBuilder().header("Authorization", "Bearer $accessToken").build()
            }

        /** 401 응답 본문의 오류 코드입니다. 본문은 소비하지 않고 확인만 합니다. */
        private fun Response.unauthorizedCode(): String? {
            if (code != HTTP_UNAUTHORIZED) return null
            val body = runCatching { peekBody(ERROR_BODY_PEEK_BYTES).string() }.getOrNull() ?: return null
            return runCatching { json.decodeFromString<ApiErrorResponse>(body).code }.getOrNull()
        }

        private companion object {
            const val HTTP_UNAUTHORIZED = 401
            const val ERROR_BODY_PEEK_BYTES = 4096L
            const val EXPIRED_ACCESS_TOKEN = "AUTH-002"
            const val INVALID_SESSION = "AUTH-003"

            /** 인증 없이 호출하는 인증 API입니다. A4 로그아웃은 보호 API이므로 제외하지 않습니다. */
            val UNAUTHENTICATED_PATHS =
                setOf(
                    "auth/phone-verifications",
                    "auth/phone-verifications/confirm",
                    "auth/login-ids/availability",
                    "auth/signup",
                    "auth/login",
                    "auth/tokens/refresh",
                )
        }
    }
