package com.hotdog.meonggocuisine.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import kotlin.math.max

/** 업로드할 바이트와 그 형식. 줄이지 않았으면 원본 그대로다. */
data class UploadPhoto(
    val bytes: ByteArray,
    val mimeType: String,
)

/**
 * 올리기 전에 사진을 줄인다.
 *
 * 폰 카메라 사진은 장당 2~3MB·4,000px 쯤이고 열 장이면 25MB 다. 서버는 어차피 긴 변을 줄이고 메타데이터를
 * 지운 JPEG 로 다시 인코딩해 저장하므로, 그 결과에 가깝게 앱에서 먼저 줄이면 업로드가 서너 배 빨라진다
 * (2026-09-23 QA — 열 장 등록이 타임아웃으로 실패했다). 매칭 모델 입력(수백 px)과 폰 화면 표시에는
 * [UploadPhotoGeometry.LONG_EDGE_PX] 로 충분하다.
 *
 * EXIF 방향은 여기서 적용해 바로 선 사진을 보낸다 — 다시 인코딩하면 EXIF 가 사라지기 때문이다. HDR 게인맵처럼
 * 뒤에 붙은 데이터도 같이 떨어진다. 디코딩에 실패하면 원본을 그대로 보내 서버 검증에 맡긴다.
 */
interface UploadPhotoShrinker {
    fun shrink(
        original: ByteArray,
        mimeType: String,
    ): UploadPhoto
}

class DefaultUploadPhotoShrinker
    @Inject
    constructor() : UploadPhotoShrinker {
        override fun shrink(
            original: ByteArray,
            mimeType: String,
        ): UploadPhoto {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(original, 0, original.size, bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width <= 0 || height <= 0) return UploadPhoto(original, mimeType)
            val plan = UploadPhotoGeometry.plan(width, height, original.size, mimeType)
            if (plan == null) return UploadPhoto(original, mimeType)

            return try {
                val options = BitmapFactory.Options().apply { inSampleSize = plan.sampleSize }
                val decoded = BitmapFactory.decodeByteArray(original, 0, original.size, options) ?: return UploadPhoto(original, mimeType)
                val oriented = orient(decoded, original, mimeType)
                val scaled = scaleToLongEdge(oriented, UploadPhotoGeometry.LONG_EDGE_PX)
                val output = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, UploadPhotoGeometry.JPEG_QUALITY, output)
                listOf(decoded, oriented, scaled).distinct().forEach(Bitmap::recycle)
                UploadPhoto(output.toByteArray(), "image/jpeg")
            } catch (_: OutOfMemoryError) {
                UploadPhoto(original, mimeType)
            } catch (_: RuntimeException) {
                UploadPhoto(original, mimeType)
            }
        }

        private fun orient(
            bitmap: Bitmap,
            original: ByteArray,
            mimeType: String,
        ): Bitmap {
            if (mimeType != "image/jpeg") return bitmap
            val orientation =
                runCatching {
                    ExifInterface(ByteArrayInputStream(original))
                        .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.postRotate(90f)
                    matrix.preScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    matrix.postRotate(270f)
                    matrix.preScale(-1f, 1f)
                }
                else -> return bitmap
            }
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }

        private fun scaleToLongEdge(
            bitmap: Bitmap,
            longEdge: Int,
        ): Bitmap {
            val current = max(bitmap.width, bitmap.height)
            if (current <= longEdge) return bitmap
            val ratio = longEdge.toDouble() / current
            val width = max(1, Math.round(bitmap.width * ratio).toInt())
            val height = max(1, Math.round(bitmap.height * ratio).toInt())
            return Bitmap.createScaledBitmap(bitmap, width, height, true)
        }
    }

/** 순수 계산 — 줄일지, 몇 배로 샘플링할지. 안드로이드 API 없이 테스트한다. */
object UploadPhotoGeometry {
    /** 업로드 긴 변. 서버 저장 상한(4,096)보다 작지만 매칭 모델 입력·폰 화면에는 충분하고 용량은 1/4 이다. */
    const val LONG_EDGE_PX = 2048
    const val JPEG_QUALITY = 90

    /** 이보다 작은 JPEG 는 그대로 보낸다 — 다시 인코딩해도 얻는 게 거의 없다. */
    const val PASS_THROUGH_BYTES = 1_500_000

    data class Plan(val sampleSize: Int)

    /**
     * 줄일 필요가 없으면 null. 줄인다면 디코딩 샘플 배수(2의 거듭제곱)를 정한다 — 원본 전체를 메모리에 올리지
     * 않기 위해, 샘플링한 결과가 [LONG_EDGE_PX] 이상으로 남는 가장 큰 배수를 고른다.
     */
    fun plan(
        width: Int,
        height: Int,
        bytes: Int,
        mimeType: String,
    ): Plan? {
        val longEdge = max(width, height)
        val smallEnough = longEdge <= LONG_EDGE_PX && bytes <= PASS_THROUGH_BYTES && mimeType == "image/jpeg"
        if (smallEnough) return null
        var sample = 1
        while (longEdge / (sample * 2) >= LONG_EDGE_PX) sample *= 2
        return Plan(sample)
    }
}
