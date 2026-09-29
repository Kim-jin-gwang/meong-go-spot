package com.hotdog.meonggocuisine.feature.report.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.hotdog.meonggocuisine.core.media.UploadPhotoShrinker
import com.hotdog.meonggocuisine.core.network.ApiErrorResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.text.Normalizer
import javax.inject.Inject

class DefaultLostPostCreateRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val reportApi: ReportApi,
        private val json: Json,
        private val shrinker: UploadPhotoShrinker,
    ) : LostPostCreateRepository {
        override suspend fun createLostPost(input: LostPostCreateInput): LostPostCreateResult =
            try {
                val photos = input.photoUris.map(::photoPart)
                val payload =
                    json
                        .encodeToString(input.toRequest())
                        .toRequestBody("application/json".toMediaType())
                val response = reportApi.createPost(payload = payload, photos = photos)
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    LostPostCreateResult.Success(body.data.postId)
                } else {
                    val error = response.errorBody()?.string()?.let(::parseError)
                    when (error?.code) {
                        "COMMON-001", "POST-007", "IDEMPOTENCY-001" ->
                            LostPostCreateResult.InvalidInput(
                                message = error.message,
                                fieldErrors = error.data?.fieldErrors.orEmpty().associate { it.field to it.reason },
                            )
                        "PHOTO-001", "PHOTO-002", "PHOTO-003", "PHOTO-004", "PHOTO-005" ->
                            LostPostCreateResult.PhotoInvalid(error.message)
                        else -> LostPostCreateResult.Failure(error?.message ?: CREATE_FAILURE)
                    }
                }
            } catch (exception: InvalidPhotoException) {
                LostPostCreateResult.PhotoInvalid(exception.reason)
            } catch (_: IOException) {
                LostPostCreateResult.Failure("서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.")
            } catch (_: Exception) {
                LostPostCreateResult.Failure(CREATE_FAILURE)
            }

        private fun photoPart(uriText: String): MultipartBody.Part {
            val uri = Uri.parse(uriText)
            val resolver = context.contentResolver
            val mimeType = resolver.getType(uri)?.lowercase() ?: throw InvalidPhotoException(UNSUPPORTED_PHOTO)
            if (mimeType !in SUPPORTED_IMAGE_TYPES) throw InvalidPhotoException(UNSUPPORTED_PHOTO)
            // 고른 뒤 사진이 지워지거나 URI 권한이 끊기면 읽기가 예외를 던진다. 감싸지 않으면
            // IOException 이 네트워크 실패로 잡혀 서버에 못 닿았다고 잘못 안내한다.
            val bytes =
                runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                    ?: throw InvalidPhotoException(UNREADABLE_PHOTO)
            val filename = runCatching { resolver.displayName(uri) }.getOrNull().orEmpty().ifBlank { "photo" }
            // 서버가 저장할 크기에 가깝게 먼저 줄인다 — 열 장 25MB 를 그대로 올리면 느린 회선에서 타임아웃이 난다.
            val upload = shrinker.shrink(bytes, mimeType)
            return MultipartBody.Part.createFormData(
                name = "photos",
                filename = if (upload.mimeType != mimeType) filename.substringBeforeLast('.') + ".jpg" else filename,
                body = upload.bytes.toRequestBody(upload.mimeType.toMediaType()),
            )
        }

        private fun android.content.ContentResolver.displayName(uri: Uri): String {
            query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index).orEmpty()
            }
            return "photo"
        }

        private fun LostPostCreateInput.toRequest(): CreatePostRequest =
            CreatePostRequest(
                clientRequestId = clientRequestId,
                type = "LOST",
                name = name.normalizedOptional(),
                species = species,
                breedName = breedName.normalizedOptional(),
                sex = sex,
                color = color.normalizedOptional(),
                eventDate = eventDate.trim(),
                eventTime = eventTime.trim().takeIf(String::isNotEmpty)?.let { "$it:00" },
                featureText = featureText.normalizedOptional(),
                eventLocation =
                    CreatePostLocation(
                        regionCode = regionCode,
                        exactLocation = eventPlace.normalizedOptional(),
                        exactLocationVisible = exactLocationVisible,
                        disclosurePolicyVersion =
                            if (exactLocationVisible) {
                                EXACT_LOCATION_POLICY_VERSION
                            } else {
                                null
                            },
                    ),
            )

        private fun String.normalizedOptional(): String? = Normalizer.normalize(this, Normalizer.Form.NFC).trim().takeIf(String::isNotEmpty)

        private fun parseError(value: String): ApiErrorResponse? =
            runCatching { json.decodeFromString<ApiErrorResponse>(value) }.getOrNull()

        private class InvalidPhotoException(val reason: String) : RuntimeException()

        private companion object {
            val SUPPORTED_IMAGE_TYPES = setOf("image/jpeg", "image/png")
            const val UNSUPPORTED_PHOTO = "JPEG 또는 PNG 사진만 등록할 수 있어요."
            const val UNREADABLE_PHOTO = "사진을 읽을 수 없어요. 다른 사진을 골라 주세요."
            const val CREATE_FAILURE = "실종동물 등록 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."
        }
    }
