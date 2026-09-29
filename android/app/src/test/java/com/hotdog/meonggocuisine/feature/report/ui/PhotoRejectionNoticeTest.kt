package com.hotdog.meonggocuisine.feature.report.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhotoRejectionNoticeTest {
    @Test
    fun `하나도 빠지지 않으면 안내가 없다`() {
        assertNull(buildPhotoRejectionNotice(pickedCount = 3, rejections = emptyList(), overflowCount = 0))
        assertNull(buildPhotoRejectionNotice(pickedCount = 3, rejections = emptyList(), overflowCount = -7))
    }

    @Test
    fun `같은 이유는 장수를 붙여 한 줄로 묶고 순서는 처음 나온 대로다`() {
        val notice =
            buildPhotoRejectionNotice(
                pickedCount = 4,
                rejections = listOf("너무 작아요", "형식이 달라요", "너무 작아요"),
                overflowCount = 0,
            )

        assertEquals(4, notice?.pickedCount)
        assertEquals(3, notice?.rejectedCount)
        assertEquals(listOf("너무 작아요 (2장)", "형식이 달라요"), notice?.lines)
    }

    @Test
    fun `장수 한도에 걸린 사진은 마지막 줄로 알린다`() {
        val notice = buildPhotoRejectionNotice(pickedCount = 12, rejections = emptyList(), overflowCount = 2)

        assertEquals(2, notice?.rejectedCount)
        assertEquals(listOf("사진은 최대 10장까지라 2장은 넣지 않았어요."), notice?.lines)
    }
}
