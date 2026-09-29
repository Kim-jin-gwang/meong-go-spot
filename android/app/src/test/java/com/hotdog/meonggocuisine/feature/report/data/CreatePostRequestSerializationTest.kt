package com.hotdog.meonggocuisine.feature.report.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 서버는 payload 와 위치 객체의 허용 필드 목록을 검사해 모르는 키가 하나라도 있으면 400 을 낸다
 * (`PostInputPolicy`). 모르는 키는 컴파일을 깨지 않고 등록만 실패시키므로 여기서 막는다.
 *
 * 허용 목록은 docs/api-spec.md `POST /api/v1/posts` 를 그대로 옮긴 것이다.
 */
class CreatePostRequestSerializationTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    @Test
    fun `payload 는 서버가 허용하는 최상위 필드만 보낸다`() {
        val encoded = json.encodeToString(fullRequest()).let(json::parseToJsonElement).jsonObject

        assertTrue(
            "허용되지 않은 필드: ${encoded.keys - ALLOWED_FIELDS}",
            ALLOWED_FIELDS.containsAll(encoded.keys),
        )
    }

    @Test
    fun `위치 객체는 서버가 허용하는 필드만 보낸다`() {
        val encoded = json.encodeToString(fullRequest()).let(json::parseToJsonElement).jsonObject
        val locations =
            listOfNotNull(
                encoded["eventLocation"]?.jsonObject,
                encoded["currentLocation"]?.jsonObject,
            )

        assertEquals(2, locations.size)
        locations.forEach { location ->
            assertTrue(
                "허용되지 않은 필드: ${location.keys - ALLOWED_LOCATION_FIELDS}",
                ALLOWED_LOCATION_FIELDS.containsAll(location.keys),
            )
        }
    }

    @Test
    fun `동물 정보는 중첩하지 않고 최상위에 둔다`() {
        val encoded = json.encodeToString(fullRequest()).let(json::parseToJsonElement).jsonObject

        assertEquals(null, encoded["details"])
        assertTrue(encoded.containsKey("species"))
        assertTrue(encoded.containsKey("eventDate"))
    }

    @Test
    fun `값이 없는 선택 필드는 키 자체를 보내지 않는다`() {
        val encoded = json.encodeToString(minimalRequest()).let(json::parseToJsonElement).jsonObject

        assertEquals(null, encoded["name"])
        assertEquals(null, encoded["currentLocation"])
        assertEquals(null, encoded["eventLocation"]?.jsonObject?.get("exactLocation"))
    }

    private fun fullRequest() =
        CreatePostRequest(
            clientRequestId = "9cb37af4-5b75-4f72-9d58-257ee88e95f3",
            type = "SHELTERING",
            name = "망고",
            species = "DOG",
            breedName = "푸들",
            sex = "FEMALE",
            color = "갈색",
            eventDate = "2026-08-25",
            eventTime = "19:30:00",
            featureText = "빨간 목줄을 착용했습니다.",
            eventLocation = location(exactLocation = "역삼역 3번 출구 인근"),
            currentLocation = location(exactLocation = "자택"),
        )

    private fun minimalRequest() =
        CreatePostRequest(
            clientRequestId = "9cb37af4-5b75-4f72-9d58-257ee88e95f3",
            type = "LOST",
            species = "DOG",
            sex = "UNKNOWN",
            eventDate = "2026-08-25",
            eventLocation = CreatePostLocation(regionCode = "11680", exactLocationVisible = false),
        )

    private fun location(exactLocation: String) =
        CreatePostLocation(
            regionCode = "11680",
            emdCode = "1168010100",
            exactLocation = exactLocation,
            latitude = 37.5,
            longitude = 127.036,
            exactLocationVisible = true,
            disclosurePolicyVersion = EXACT_LOCATION_POLICY_VERSION,
        )

    private companion object {
        val ALLOWED_FIELDS =
            setOf(
                "clientRequestId",
                "type",
                "name",
                "species",
                "breedName",
                "sex",
                "color",
                "eventDate",
                "eventTime",
                "eventLocation",
                "currentLocation",
                "featureText",
            )
        val ALLOWED_LOCATION_FIELDS =
            setOf(
                "regionCode",
                "emdCode",
                "exactLocation",
                "latitude",
                "longitude",
                "exactLocationVisible",
                "disclosurePolicyVersion",
            )
    }
}
