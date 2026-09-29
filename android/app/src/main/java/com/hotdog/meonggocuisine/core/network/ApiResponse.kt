package com.hotdog.meonggocuisine.core.network

import kotlinx.serialization.Serializable

@Serializable
data class ApiResponse<T>(
    val code: String,
    val message: String,
    val data: T,
)

/**
 * `data` 없이 `code`·`message` 만 오는 성공 응답.
 *
 * 서버는 `data` 가 null 이면 JSON 에서 생략한다(`ApiResponse` 의 `@JsonInclude(NON_NULL)`).
 * kotlinx.serialization 은 기본값이 없는 프로퍼티를 타입이 nullable 이어도 필수 키로 보므로
 * [ApiResponse] 로 받으면 파싱이 실패한다. 명세에 `data` 가 없는 응답은 이 타입으로 받는다
 * (docs/api-spec.md A0-1).
 */
@Serializable
data class ApiMessageResponse(
    val code: String,
    val message: String,
)

@Serializable
data class ApiErrorResponse(
    val code: String,
    val message: String,
    val data: ApiErrorData? = null,
)

@Serializable
data class ApiErrorData(
    val fieldErrors: List<ApiFieldError> = emptyList(),
)

@Serializable
data class ApiFieldError(
    val field: String,
    val reason: String,
)
