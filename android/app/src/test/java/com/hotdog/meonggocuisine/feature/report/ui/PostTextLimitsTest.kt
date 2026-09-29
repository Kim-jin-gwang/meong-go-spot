package com.hotdog.meonggocuisine.feature.report.ui

import com.hotdog.meonggocuisine.feature.report.ui.PostTextLimits.codePointLength
import com.hotdog.meonggocuisine.feature.report.ui.PostTextLimits.limitTo
import org.junit.Assert.assertEquals
import org.junit.Test

class PostTextLimitsTest {
    @Test
    fun `한도 안이면 그대로 두고 넘치면 앞에서 자른다`() {
        assertEquals("망고", "망고".limitTo(2))
        assertEquals("망고", "망고반점".limitTo(2))
        assertEquals("", "".limitTo(5))
    }

    @Test
    fun `이모지처럼 두 char 인 글자는 반쪽으로 자르지 않는다`() {
        val value = "🐶🐱🐶"
        assertEquals(3, value.codePointLength())
        assertEquals("🐶🐱", value.limitTo(2))
    }

    @Test
    fun `보호 등록의 특징 한도는 보호 상태 한 줄을 붙여도 서버 한도 안이다`() {
        val prefix = "현재 보호 상태: ".codePointLength() + PostTextLimits.PROTECTION_STATUS + "\n".codePointLength()
        assert(prefix + PostTextLimits.SHELTERING_FEATURE_TEXT <= PostTextLimits.FEATURE_TEXT)
    }
}
