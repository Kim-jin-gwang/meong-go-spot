package com.hotdog.meonggocuisine.feature.auth.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * 서버가 실제로 주는 본문을 그대로 흘려 계약을 고정한다.
 *
 * 서버는 `data` 가 null 이면 JSON 에서 생략하고(`@JsonInclude(NON_NULL)`), docs/api-spec.md A0-1 의
 * 202 예시에도 `data` 가 없다. 앱이 `data` 를 필수 키로 요구하면 문자는 나갔는데 화면에는 오류가
 * 뜨고 인증번호 입력칸이 나타나지 않는다 — 누를 때마다 발송 비용만 나간다.
 */
class PhoneVerificationResponseContractTest {
    // docs/api-spec.md A0-1 의 202 응답 예시를 그대로 옮긴 것이다. 손으로 고치지 않는다.
    private val specBody = """{"code": "SUCCESS", "message": "인증 코드를 전송했습니다."}"""

    @Test
    fun `data 없는 202 응답을 성공으로 읽는다`() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                server.enqueue(
                    MockResponse()
                        .setResponseCode(202)
                        .setHeader("Content-Type", "application/json")
                        .setBody(specBody),
                )
                val json = Json { ignoreUnknownKeys = true }
                val api =
                    Retrofit.Builder()
                        .baseUrl(server.url("/api/v1/"))
                        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                        .build()
                        .create(AuthApi::class.java)

                val response =
                    api.requestPhoneVerification(
                        PhoneVerificationRequest(
                            phoneNumber = "01012345678",
                            privacyCollectionAgreed = true,
                            privacyCollectionPolicyVersion = PRIVACY_COLLECTION_POLICY_VERSION,
                        ),
                    )

                assertEquals(202, response.code())
                assertEquals("SUCCESS", response.body()?.code)
                assertEquals("인증 코드를 전송했습니다.", response.body()?.message)
                val request = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
                assertEquals("true", request["privacyCollectionAgreed"]?.jsonPrimitive?.content)
                assertEquals(PRIVACY_COLLECTION_POLICY_VERSION, request["privacyCollectionPolicyVersion"]?.jsonPrimitive?.content)
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun `인증번호 확인 요청에도 개인정보 동의와 정책 버전을 보낸다`() {
        val request =
            Json.encodeToJsonElement(
                PhoneConfirmationRequest(
                    phoneNumber = "01012345678",
                    verificationCode = "123456",
                    privacyCollectionAgreed = true,
                    privacyCollectionPolicyVersion = PRIVACY_COLLECTION_POLICY_VERSION,
                ),
            ).jsonObject

        assertEquals("true", request["privacyCollectionAgreed"]?.jsonPrimitive?.content)
        assertEquals(PRIVACY_COLLECTION_POLICY_VERSION, request["privacyCollectionPolicyVersion"]?.jsonPrimitive?.content)
    }
}
