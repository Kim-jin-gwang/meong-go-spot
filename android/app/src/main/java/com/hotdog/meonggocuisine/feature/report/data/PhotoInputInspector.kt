package com.hotdog.meonggocuisine.feature.report.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 고른 사진이 서버 규격에 맞는지 등록 화면에서 먼저 확인합니다.
 *
 * 규격을 어긴 사진은 서버가 받아 주지 않는데(`PhotoNormalizer`), 지금까지는 폼을 다 채우고
 * 등록을 누른 뒤에야 그 사실을 알 수 있었습니다. 고르는 순간 알려 줍니다.
 *
 * 이유 문구는 그 사진에 무엇이 문제였는지만 말하고, 등록할 수 있는 사진의 기준 전체는 안내 창
 * ([com.hotdog.meonggocuisine.feature.report.ui.PHOTO_REQUIREMENTS])이 한 줄로 함께 보여 줍니다.
 * 작은 사진은 생기는 흔한 경로(메신저 전송)를 짚어 줍니다.
 *
 * 경계값은 서버와 같아야 합니다 (docs/photo-upload-policy.md). 파일 용량은 서버가 413 으로만 알려 주므로
 * 여기서 먼저 잡습니다.
 */
interface PhotoInputInspector {
    /**
     * 등록할 수 없는 사진이면 사용자에게 보여 줄 이유를, 문제가 없으면 null 을 돌려줍니다.
     *
     * 사진을 여는 일은 콘텐츠 프로바이더를 거칩니다. 클라우드에 있는 사진이면 내려받기까지
     * 기다려야 하므로 화면을 멈추지 않도록 suspend 로 둡니다.
     */
    suspend fun rejection(uriText: String): String?
}

class DefaultPhotoInputInspector
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : PhotoInputInspector {
        override suspend fun rejection(uriText: String): String? = withContext(Dispatchers.IO) { inspect(uriText) }

        private fun inspect(uriText: String): String? {
            val uri = Uri.parse(uriText)
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            // 고른 뒤 사진이 지워지거나 권한이 끊기면 스트림 열기가 예외를 던진다. 사진을 고르다가
            // 앱이 죽는 것보다 그 사진만 못 쓴다고 알리는 편이 낫다.
            val read = runCatching { resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } }
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (read.isFailure || width <= 0 || height <= 0) return "사진을 읽을 수 없어요. 다른 사진을 골라 주세요."

            // 형식은 디코더가 실제 바이트에서 알아낸 값을 먼저 믿는다. 갤러리 앱에 따라
            // contentResolver 가 형식을 모른다고 답하는 경우가 있다.
            val mimeType = (bounds.outMimeType ?: resolver.getType(uri))?.lowercase()
            if (mimeType !in SUPPORTED_IMAGE_TYPES) {
                return "JPEG 또는 PNG 사진만 등록할 수 있어요. HEIC·WebP·GIF 형식은 넣을 수 없어요."
            }

            // 용량은 프로바이더가 모르면(-1) 넘어간다 — 그때는 서버가 413 으로 거른다.
            val bytes = runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
            if (bytes > MAX_BYTES) return "사진 파일이 10MB를 넘어 등록할 수 없어요. 다른 사진을 골라 주세요."

            return when {
                width < MIN_SIDE_PX || height < MIN_SIDE_PX ->
                    "사진이 너무 작아 등록할 수 없어요. 메신저로 받은 사진은 작게 줄어들어 있을 수 있으니 원본을 골라 주세요."
                width > MAX_SIDE_PX || height > MAX_SIDE_PX || width.toLong() * height > MAX_PIXELS ->
                    "사진이 너무 커서 등록할 수 없어요. 다른 사진을 골라 주세요."
                else -> null
            }
        }

        private companion object {
            val SUPPORTED_IMAGE_TYPES = setOf("image/jpeg", "image/png")

            // 서버 하한과 같다 — 2026-09-21 QA 로 512 → 64 완화(v1.4.0 운영 반영). 메신저로 받아 줄어든 사진도 등록된다.
            const val MIN_SIDE_PX = 64
            const val MAX_SIDE_PX = 10000
            const val MAX_PIXELS = 40_000_000L
            const val MAX_BYTES = 10L * 1024 * 1024
        }
    }
