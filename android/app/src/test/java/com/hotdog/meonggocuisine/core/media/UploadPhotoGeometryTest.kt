package com.hotdog.meonggocuisine.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UploadPhotoGeometryTest {
    @Test
    fun `작은 JPEG 는 그대로 보낸다`() {
        assertNull(UploadPhotoGeometry.plan(1600, 1200, 900_000, "image/jpeg"))
        assertNull(UploadPhotoGeometry.plan(2048, 1536, 1_500_000, "image/jpeg"))
    }

    @Test
    fun `크거나 무겁거나 PNG 면 줄인다`() {
        assertEquals(UploadPhotoGeometry.Plan(1), UploadPhotoGeometry.plan(2049, 1536, 100_000, "image/jpeg")) // 한 변이 넘음
        assertEquals(UploadPhotoGeometry.Plan(1), UploadPhotoGeometry.plan(1600, 1200, 2_500_000, "image/jpeg")) // 용량이 넘음
        assertEquals(UploadPhotoGeometry.Plan(1), UploadPhotoGeometry.plan(800, 600, 50_000, "image/png")) // PNG 는 JPEG 로
    }

    @Test
    fun `샘플 배수는 결과가 긴 변 이상으로 남는 가장 큰 2의 거듭제곱이다`() {
        // 4000 → 1배(2배면 2000 < 2048)
        assertEquals(1, UploadPhotoGeometry.plan(4000, 3000, 3_000_000, "image/jpeg")!!.sampleSize)
        // 4096 → 2배(2048 유지)
        assertEquals(2, UploadPhotoGeometry.plan(4096, 3072, 3_000_000, "image/jpeg")!!.sampleSize)
        // 9000 → 4배(2250), 8배면 1125
        assertEquals(4, UploadPhotoGeometry.plan(9000, 6000, 9_000_000, "image/jpeg")!!.sampleSize)
        // 세로 사진도 긴 변 기준
        assertEquals(4, UploadPhotoGeometry.plan(6000, 9000, 9_000_000, "image/jpeg")!!.sampleSize)
    }
}
