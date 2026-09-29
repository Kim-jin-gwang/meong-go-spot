package com.hotdog.meonggocuisine.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateDecisionTest {
    @Test
    fun `최소 지원 버전 미만이면 강제 업데이트다`() {
        assertEquals(
            UpdatePrompt.FORCE,
            decideUpdatePrompt(
                currentVersionCode = 4,
                latestVersionCode = 7,
                minSupportedVersionCode = 5,
                recommendationDismissedToday = true,
            ),
        )
    }

    @Test
    fun `최신 미만이면 권고이고 오늘 닫았으면 다시 보이지 않는다`() {
        assertEquals(UpdatePrompt.RECOMMEND, decideUpdatePrompt(6, 7, 5, recommendationDismissedToday = false))
        assertEquals(UpdatePrompt.NONE, decideUpdatePrompt(6, 7, 5, recommendationDismissedToday = true))
    }

    @Test
    fun `최신이거나 서버가 값을 정하지 않았으면 안내가 없다`() {
        assertEquals(UpdatePrompt.NONE, decideUpdatePrompt(7, 7, 5, false))
        assertEquals(UpdatePrompt.NONE, decideUpdatePrompt(8, 7, 5, false))
        // 0 = 정하지 않음 (로컬·테스트 기본)
        assertEquals(UpdatePrompt.NONE, decideUpdatePrompt(1, 0, 0, false))
    }

    @Test
    fun `원스토어 링크에서 딥링크를 만들고 모르는 링크는 null 이다`() {
        assertEquals(
            "onestore://common/product/0001009297",
            oneStoreDeepLink("https://m.onestore.co.kr/v2/ko-kr/app/0001009297"),
        )
        assertEquals("onestore://common/product/0001009297", oneStoreDeepLink("https://m.onestore.co.kr/v2/ko-kr/app/0001009297/"))
        assertEquals(
            "onestore://common/product/0001009297",
            oneStoreDeepLink("https://m.onestore.co.kr/v2/ko-kr/app/0001009297?utm_source=app#top"),
        )
        assertNull(oneStoreDeepLink("https://play.google.com/store/apps/details?id=com.hotdog.meonggocuisine"))
        assertNull(oneStoreDeepLink("https://m.onestore.co.kr/v2/ko-kr/app/"))
    }
}
