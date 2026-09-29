package com.hotdog.meonggocuisine.feature.post.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.hotdog.meonggocuisine.core.media.UploadPhotoShrinker
import com.hotdog.meonggocuisine.core.network.ApiErrorResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.text.Normalizer
import javax.inject.Inject

class DefaultPostEditRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val postApi: PostApi,
        private val json: Json,
        private val savedPhotos: SavedPhotoLoader,
        private val shrinker: UploadPhotoShrinker,
    ) : PostEditRepository {
        override suspend fun updatePost(input: PostEditInput): PostEditResult =
            try {
                val response = postApi.updatePost(input.postId, input.toRequest())
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    PostEditResult.Success(body.data.version)
                } else {
                    val error = response.errorBody()?.string()?.let(::parseError)
                    when (error?.code) {
                        "COMMON-001" ->
                            PostEditResult.InvalidInput(
                                message = error.message,
                                fieldErrors = error.data?.fieldErrors.orEmpty().associate { it.field to it.reason },
                            )
                        "POST-004" -> PostEditResult.VersionConflict(error.message)
                        "POST-007" -> PostEditResult.DisclosureOutdated(error.message)
                        "AUTH-002", "AUTH-003" -> PostEditResult.Unauthorized(error.message)
                        "POST-002", "POST-005" -> PostEditResult.Forbidden(error.message)
                        "POST-003" -> PostEditResult.NotEditable(error.message)
                        "POST-001" -> PostEditResult.NotFound(error.message)
                        else -> PostEditResult.Failure(error?.message ?: UPDATE_FAILURE)
                    }
                }
            } catch (_: IOException) {
                PostEditResult.Failure(CONNECTION_FAILURE)
            } catch (_: Exception) {
                PostEditResult.Failure(UPDATE_FAILURE)
            }

        /**
         * 남길 사진과 새 사진을 합쳐 최종 전체를 올립니다.
         *
         * 사진을 읽고 내려받는 일이 모두 막는 입출력이라 통째로 [Dispatchers.IO] 에서 돈다.
         */
        override suspend fun replacePhotos(
            postId: Long,
            version: Long,
            photos: List<PostPhotoSource>,
        ): PostPhotoReplaceResult =
            withContext(Dispatchers.IO) {
                replacePhotosBlocking(postId, version, photos)
            }

        private suspend fun replacePhotosBlocking(
            postId: Long,
            version: Long,
            sources: List<PostPhotoSource>,
        ): PostPhotoReplaceResult =
            try {
                val photos = sources.map(::photoPart)
                val payload =
                    json
                        .encodeToString(ReplacePhotosPayload(version))
                        .toRequestBody("application/json".toMediaType())
                val response = postApi.replacePhotos(postId = postId, payload = payload, photos = photos)
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    PostPhotoReplaceResult.Success(version = body.data.version, photos = body.data.photos)
                } else {
                    val error = response.errorBody()?.string()?.let(::parseError)
                    when (error?.code) {
                        "PHOTO-001", "PHOTO-002", "PHOTO-003", "PHOTO-004", "PHOTO-005" ->
                            PostPhotoReplaceResult.PhotoInvalid(error.message)
                        "PHOTO-006", "PHOTO-008" -> PostPhotoReplaceResult.Failure(error.message)
                        "POST-004" -> PostPhotoReplaceResult.VersionConflict(error.message)
                        "AUTH-002", "AUTH-003" -> PostPhotoReplaceResult.Unauthorized(error.message)
                        "POST-002", "POST-005" -> PostPhotoReplaceResult.Forbidden(error.message)
                        "POST-003" -> PostPhotoReplaceResult.NotEditable(error.message)
                        "POST-001" -> PostPhotoReplaceResult.NotFound(error.message)
                        else -> PostPhotoReplaceResult.Failure(error?.message ?: PHOTO_FAILURE)
                    }
                }
            } catch (_: InvalidPhotoException) {
                PostPhotoReplaceResult.PhotoInvalid("JPEG 또는 PNG 사진만 등록할 수 있습니다.")
            } catch (_: SavedPhotoUnavailableException) {
                // 남기기로 한 사진을 못 받았다. 이대로 보내면 그 사진만 조용히 사라진다.
                PostPhotoReplaceResult.Failure("남겨 둔 사진을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.")
            } catch (_: IOException) {
                PostPhotoReplaceResult.Failure(CONNECTION_FAILURE)
            } catch (_: Exception) {
                PostPhotoReplaceResult.Failure(PHOTO_FAILURE)
            }

        override suspend fun closePost(
            postId: Long,
            version: Long,
            reason: PostCloseReason,
        ): PostCloseResult =
            try {
                val response =
                    postApi.closePost(
                        postId = postId,
                        request = ClosePostRequest(version = version, reason = reason.value),
                    )
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    PostCloseResult.Success(body.data.version)
                } else {
                    val error = response.errorBody()?.string()?.let(::parseError)
                    when (error?.code) {
                        "POST-004" -> PostCloseResult.VersionConflict(error.message)
                        "AUTH-002", "AUTH-003" -> PostCloseResult.Unauthorized(error.message)
                        "POST-002", "POST-005" -> PostCloseResult.Forbidden(error.message)
                        "POST-003" -> PostCloseResult.NotEditable(error.message)
                        "POST-001" -> PostCloseResult.NotFound(error.message)
                        else -> PostCloseResult.Failure(error?.message ?: CLOSE_FAILURE)
                    }
                }
            } catch (_: IOException) {
                PostCloseResult.Failure(CONNECTION_FAILURE)
            } catch (_: Exception) {
                PostCloseResult.Failure(CLOSE_FAILURE)
            }

        /**
         * 화면이 다루는 필드를 모두 담은 P4 payload를 만듭니다.
         *
         * 값을 비운 선택 필드는 명시적 null로 보내 서버가 삭제하도록 한다. 생략하면 기존 값이
         * 유지되므로 폼에서 지운 값이 되살아난다.
         */
        private fun PostEditInput.toRequest(): JsonObject =
            buildJsonObject {
                put("version", JsonPrimitive(version))
                put("name", name.optionalJson())
                put("breedName", breedName.optionalJson())
                put("sex", JsonPrimitive(sex))
                put("color", color.optionalJson())
                put("eventDate", JsonPrimitive(eventDate.trim()))
                put("eventTime", eventTime.normalized()?.let { JsonPrimitive("$it:00") } ?: JsonNull)
                put("featureText", featureText.optionalJson())
                put("eventLocation", eventLocation.toJson())
                currentLocation?.let { put("currentLocation", it.toJson()) }
            }

        private fun PostEditLocationInput.toJson(): JsonObject =
            buildJsonObject {
                put("exactLocation", exactLocation.optionalJson())
                put("exactLocationVisible", JsonPrimitive(exactLocationVisible))
                if (requiresDisclosureVersion()) {
                    put("disclosurePolicyVersion", JsonPrimitive(EXACT_LOCATION_POLICY_VERSION))
                }
            }

        /**
         * 비공개에서 공개로 바뀌거나, 공개를 유지한 채 정확한 위치 내용을 바꿀 때만 정책 버전이
         * 필요합니다. 공개를 끄는 요청과 다른 필드만 바꾸는 요청에는 넣지 않는다.
         */
        private fun PostEditLocationInput.requiresDisclosureVersion(): Boolean {
            if (!exactLocationVisible) return false
            if (!savedExactLocationVisible) return true
            return exactLocation.normalized() != savedExactLocation?.normalized()
        }

        private fun photoPart(source: PostPhotoSource): MultipartBody.Part =
            when (source) {
                is PostPhotoSource.Picked -> pickedPhotoPart(source.uri)
                is PostPhotoSource.Saved -> savedPhotoPart(source.url)
            }

        /** 서버에 있던 사진은 [SavedPhotoLoader] 로 받아 그대로 싣는다. */
        private fun savedPhotoPart(url: String): MultipartBody.Part {
            val photo = savedPhotos.load(url)
            return MultipartBody.Part.createFormData(
                name = "photos",
                filename = photo.filename,
                body = photo.bytes.toRequestBody(photo.mimeType.toMediaType()),
            )
        }

        private fun pickedPhotoPart(uriText: String): MultipartBody.Part {
            val uri = Uri.parse(uriText)
            val resolver = context.contentResolver
            val mimeType = resolver.getType(uri)?.lowercase() ?: throw InvalidPhotoException()
            if (mimeType !in SUPPORTED_IMAGE_TYPES) throw InvalidPhotoException()
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: throw InvalidPhotoException()
            val filename = resolver.displayName(uri).ifBlank { "photo" }
            // 등록과 같은 이유로 새로 고른 사진만 줄인다. 이미 저장된 사진(savedPhotoPart)은 서버 정규화 결과라 그대로.
            val upload = shrinker.shrink(bytes, mimeType)
            return MultipartBody.Part.createFormData(
                name = "photos",
                filename = if (upload.mimeType != mimeType) filename.substringBeforeLast('.') + ".jpg" else filename,
                body = upload.bytes.toRequestBody(upload.mimeType.toMediaType()),
            )
        }

        private fun ContentResolver.displayName(uri: Uri): String {
            query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index).orEmpty()
            }
            return "photo"
        }

        private fun String.optionalJson() = normalized()?.let(::JsonPrimitive) ?: JsonNull

        private fun String.normalized(): String? = Normalizer.normalize(this, Normalizer.Form.NFC).trim().takeIf(String::isNotEmpty)

        private fun parseError(value: String): ApiErrorResponse? =
            runCatching { json.decodeFromString<ApiErrorResponse>(value) }.getOrNull()

        private class InvalidPhotoException : RuntimeException()

        private companion object {
            val SUPPORTED_IMAGE_TYPES = setOf("image/jpeg", "image/png")
            const val UPDATE_FAILURE = "게시물 수정 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."
            const val PHOTO_FAILURE = "사진 교체 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."
            const val CLOSE_FAILURE = "게시물 종료 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."
            const val CONNECTION_FAILURE = "서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요."
        }
    }
