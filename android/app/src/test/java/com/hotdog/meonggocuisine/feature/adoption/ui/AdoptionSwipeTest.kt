package com.hotdog.meonggocuisine.feature.adoption.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AdoptionSwipeTest {
    @Test
    fun `기준보다 짧게 움직이면 카드가 제자리로 돌아간다`() {
        assertEquals(AdoptionSwipeAction.RESET, resolveAdoptionSwipe(offsetX = 49f, thresholdPx = 50f))
        assertEquals(AdoptionSwipeAction.RESET, resolveAdoptionSwipe(offsetX = -49f, thresholdPx = 50f))
    }

    @Test
    fun `왼쪽으로 넘기면 좋아요 없이 다음 카드로 간다`() {
        assertEquals(AdoptionSwipeAction.SKIP, resolveAdoptionSwipe(offsetX = -50f, thresholdPx = 50f))
    }

    @Test
    fun `오른쪽으로 넘기면 좋아요 후 다음 카드로 간다`() {
        assertEquals(AdoptionSwipeAction.FAVORITE, resolveAdoptionSwipe(offsetX = 50f, thresholdPx = 50f))
    }
}
