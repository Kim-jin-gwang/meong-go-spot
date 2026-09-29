package com.hotdog.meonggocuisine.core.update

import kotlinx.serialization.Serializable

/** V1 `GET /api/v1/app/version` 응답. 값이 0 이면 안내를 띄우지 않는다 (docs/api-spec.md V1). */
@Serializable
data class AppVersionResponse(
    val platform: String,
    val latestVersionCode: Int,
    val minSupportedVersionCode: Int,
    val storeUrl: String,
)
