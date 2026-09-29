package com.hotdog.meonggocuisine.feature.report.data

import kotlinx.serialization.Serializable

/**
 * `POST /api/v1/posts` 의 payload 파트입니다 (docs/api-spec.md).
 *
 * 동물 정보는 중첩 없이 최상위에 둡니다. 서버는 payload 와 위치 객체 모두 허용 필드 목록을
 * 검사해 모르는 키가 하나라도 있으면 400 을 내므로, 여기 없는 필드를 보내면 등록이 실패합니다.
 */
@Serializable
data class CreatePostRequest(
    val clientRequestId: String,
    val type: String,
    val name: String? = null,
    val species: String,
    val breedName: String? = null,
    val sex: String,
    val color: String? = null,
    val eventDate: String,
    val eventTime: String? = null,
    val eventLocation: CreatePostLocation,
    val currentLocation: CreatePostLocation? = null,
    val featureText: String? = null,
)

/**
 * 공개용 지역 표기는 보내지 않습니다. 서버가 `regionCode`·`emdCode` 로 직접 만들어 응답합니다.
 */
@Serializable
data class CreatePostLocation(
    val regionCode: String,
    val emdCode: String? = null,
    val exactLocation: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val exactLocationVisible: Boolean,
    val disclosurePolicyVersion: String? = null,
)

@Serializable
data class CreatePostResponse(
    val postId: Long,
    val type: String,
    val source: String,
    val status: String,
    val version: Long,
    val createdAt: String,
)

const val EXACT_LOCATION_POLICY_VERSION = "exact-location-v1"
