package com.hotdog.meonggocuisine.feature.report.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class EventDateTimeInputTest {
    @Test
    fun `날짜는 숫자를 치는 대로 구분선을 채워 넣는다`() {
        assertEquals("2", EventDateTimeInput.date("2"))
        assertEquals("2026", EventDateTimeInput.date("2026"))
        assertEquals("2026-0", EventDateTimeInput.date("20260"))
        assertEquals("2026-09", EventDateTimeInput.date("202609"))
        assertEquals("2026-09-09", EventDateTimeInput.date("20260909"))
    }

    @Test
    fun `날짜는 이미 구분선이 든 값을 다시 넣어도 같은 결과를 낸다`() {
        assertEquals("2026-09-09", EventDateTimeInput.date("2026-09-09"))
    }

    @Test
    fun `날짜는 숫자가 아닌 입력과 여덟 자리를 넘는 입력을 버린다`() {
        assertEquals("2026-09-09", EventDateTimeInput.date("2026-09-09-12"))
        assertEquals("2026-09-09", EventDateTimeInput.date("2026년 09월 09일"))
        assertEquals("", EventDateTimeInput.date("abc"))
    }

    @Test
    fun `지우기는 구분선이 아니라 숫자를 한 자씩 지운다`() {
        // 화면이 넘겨주는 값은 사용자가 마지막 글자를 지운 문자열이다.
        assertEquals("2026-09-0", EventDateTimeInput.date("2026-09-0"))
        assertEquals("2026-09", EventDateTimeInput.date("2026-09"))
        assertEquals("2026-0", EventDateTimeInput.date("2026-0"))
        assertEquals("2026", EventDateTimeInput.date("2026-"))
    }

    @Test
    fun `자릿수를 센다`() {
        assertEquals(8, EventDateTimeInput.digitCount("2026-09-09"))
        assertEquals(4, EventDateTimeInput.digitCount("17:30"))
        assertEquals(6, EventDateTimeInput.digitCount("2026-09"))
    }
}
