package com.hotdog.meonggocuisine.feature.post.data

/**
 * P4에 보낼 위치 역할 하나의 최종 입력입니다.
 *
 * 서버는 요청 필드를 저장된 같은 역할 객체에 병합하므로 화면이 다루는 필드만 담는다.
 * 공개를 켜거나 공개를 유지한 채 정확한 위치를 바꿀 때만 정책 버전이 필요하므로
 * 그 판단은 [PostEditRepository]가 저장된 값과 비교해서 한다.
 */
data class PostEditLocationInput(
    val exactLocation: String,
    val exactLocationVisible: Boolean,
    val savedExactLocation: String?,
    val savedExactLocationVisible: Boolean,
)

data class PostEditInput(
    val postId: Long,
    val version: Long,
    val name: String,
    val breedName: String,
    val sex: String,
    val color: String,
    val eventDate: String,
    val eventTime: String,
    val featureText: String,
    val eventLocation: PostEditLocationInput,
    val currentLocation: PostEditLocationInput? = null,
)

sealed interface PostEditResult {
    data class Success(val version: Long) : PostEditResult

    data class InvalidInput(
        val message: String,
        val fieldErrors: Map<String, String>,
    ) : PostEditResult

    /** POST-004 — 다른 곳에서 게시물이 바뀌어 저장 버전이 다르다. 새로고침이 필요하다. */
    data class VersionConflict(val message: String) : PostEditResult

    /** POST-007 — 공개 동의 안내 버전이 현재 값과 다르다. */
    data class DisclosureOutdated(val message: String) : PostEditResult

    data class Unauthorized(val message: String) : PostEditResult

    data class Forbidden(val message: String) : PostEditResult

    data class NotEditable(val message: String) : PostEditResult

    data class NotFound(val message: String) : PostEditResult

    data class Failure(val message: String) : PostEditResult
}

/**
 * 사진 전체 교체(P5)에 실을 사진 한 장입니다.
 *
 * 서버는 부분 추가·삭제를 받지 않고 최종 사진 전체를 바이너리로 받는다(`docs/api-spec.md` P5).
 * 그래서 화면에서 한 장만 지워도 남기기로 한 기존 사진까지 같이 올려야 한다 — [Saved] 는 서버에
 * 이미 있는 사진이고, 보낼 때 다시 받아서 싣는다. 서버가 부분 편집을 받게 되면 이 타입이 먼저 없어진다.
 */
sealed interface PostPhotoSource {
    /** 서버에 이미 있는 사진. [url] 은 상세가 준 `/api/v1/photos/{id}`. */
    data class Saved(val url: String) : PostPhotoSource

    /** 이번에 기기에서 고른 사진. */
    data class Picked(val uri: String) : PostPhotoSource
}

sealed interface PostPhotoReplaceResult {
    data class Success(
        val version: Long,
        val photos: List<PostPhotoResponse>,
    ) : PostPhotoReplaceResult

    data class PhotoInvalid(val message: String) : PostPhotoReplaceResult

    data class VersionConflict(val message: String) : PostPhotoReplaceResult

    data class Unauthorized(val message: String) : PostPhotoReplaceResult

    data class Forbidden(val message: String) : PostPhotoReplaceResult

    data class NotEditable(val message: String) : PostPhotoReplaceResult

    data class NotFound(val message: String) : PostPhotoReplaceResult

    data class Failure(val message: String) : PostPhotoReplaceResult
}

enum class PostCloseReason(
    val value: String,
    val label: String,
    val description: String,
) {
    RETURNED("RETURNED", "주인에게 돌아갔어요", "실종 동물을 찾아 보호자에게 인계했거나 직접 데려왔어요"),
    TRANSFERRED("TRANSFERRED", "보호센터에 인계했어요", "동물을 보호센터나 지자체에 넘겼어요"),
    OTHER("OTHER", "그 밖의 이유", "위 두 경우에 해당하지 않아요"),
}

sealed interface PostCloseResult {
    data class Success(val version: Long) : PostCloseResult

    data class VersionConflict(val message: String) : PostCloseResult

    data class Unauthorized(val message: String) : PostCloseResult

    data class Forbidden(val message: String) : PostCloseResult

    data class NotEditable(val message: String) : PostCloseResult

    data class NotFound(val message: String) : PostCloseResult

    data class Failure(val message: String) : PostCloseResult
}

interface PostEditRepository {
    suspend fun updatePost(input: PostEditInput): PostEditResult

    suspend fun replacePhotos(
        postId: Long,
        version: Long,
        photos: List<PostPhotoSource>,
    ): PostPhotoReplaceResult

    suspend fun closePost(
        postId: Long,
        version: Long,
        reason: PostCloseReason,
    ): PostCloseResult
}
