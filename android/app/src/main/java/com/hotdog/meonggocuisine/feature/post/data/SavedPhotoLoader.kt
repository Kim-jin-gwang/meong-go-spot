package com.hotdog.meonggocuisine.feature.post.data

import com.hotdog.meonggocuisine.core.network.ApiImageUrlResolver
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

/**
 * 서버에 이미 올라간 사진을 다시 올리려고 내려받습니다.
 *
 * 사진 전체 교체(P5)는 최종 사진 전체를 바이너리로 받는다. 화면에서 한 장만 지워도 남기기로 한
 * 사진들을 다시 실어야 하는데, 그 사진들은 기기에 없고 서버에만 있다 — 그래서 P8 로 받아 온다.
 * 받은 건 서버가 정규화해 둔 JPEG 이라 그대로 다시 올려도 규격 검사를 통과한다.
 *
 * 인증이 필요한 경로라 앱이 쓰는 [OkHttpClient] 를 그대로 쓴다 — 토큰 인터셉터가 붙어 있다.
 * 막는 입출력이므로 부르는 쪽이 IO 스레드를 잡는다.
 */
class SavedPhotoLoader
    @Inject
    constructor(
        private val httpClient: OkHttpClient,
        private val imageUrls: ApiImageUrlResolver,
    ) {
        /** @throws SavedPhotoUnavailableException 받지 못했거나 우리가 다룰 수 있는 사진이 아닐 때. */
        fun load(url: String): LoadedPhoto {
            val request = Request.Builder().url(imageUrls.resolve(url)).build()
            val response =
                try {
                    httpClient.newCall(request).execute()
                } catch (_: Exception) {
                    throw SavedPhotoUnavailableException()
                }
            response.use {
                val body = it.body
                if (!it.isSuccessful || body == null) throw SavedPhotoUnavailableException()
                val mimeType =
                    body.contentType()?.let { type -> "${type.type}/${type.subtype}" }?.lowercase()
                        ?: throw SavedPhotoUnavailableException()
                if (mimeType !in SUPPORTED_IMAGE_TYPES) throw SavedPhotoUnavailableException()
                return LoadedPhoto(
                    filename = url.substringAfterLast('/').substringBefore('?').ifBlank { "photo" },
                    mimeType = mimeType,
                    bytes = body.bytes(),
                )
            }
        }

        private companion object {
            val SUPPORTED_IMAGE_TYPES = setOf("image/jpeg", "image/png")
        }
    }

/** 내려받은 사진 한 장. [bytes] 를 그대로 multipart 에 싣는다. */
class LoadedPhoto(
    val filename: String,
    val mimeType: String,
    val bytes: ByteArray,
)

/** 남기기로 한 사진을 다시 못 받았다 — 이 상태로 올리면 그 사진이 조용히 사라진다. */
class SavedPhotoUnavailableException : RuntimeException()
