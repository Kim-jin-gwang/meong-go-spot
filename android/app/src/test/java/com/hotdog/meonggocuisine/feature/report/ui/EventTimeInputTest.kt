package com.hotdog.meonggocuisine.feature.report.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventTimeInputTest {
    @Test
    fun `오전 오후와 시 분을 24시간 표기로 바꾼다`() {
        assertEquals("18:30", EventTimeInput(DayPeriod.PM, "6", "30").toTime24())
        assertEquals("09:07", EventTimeInput(DayPeriod.AM, "9", "7").toTime24())
        assertEquals("09:07", EventTimeInput(DayPeriod.AM, "09", "07").toTime24())
        // 오전 12시는 자정, 오후 12시는 정오.
        assertEquals("00:05", EventTimeInput(DayPeriod.AM, "12", "5").toTime24())
        assertEquals("12:00", EventTimeInput(DayPeriod.PM, "12", "0").toTime24())
    }

    @Test
    fun `비었거나 범위를 벗어나면 보낼 값이 없다`() {
        assertNull(EventTimeInput().toTime24())
        assertNull(EventTimeInput(DayPeriod.PM, "6", "").toTime24())
        assertNull(EventTimeInput(DayPeriod.PM, "13", "00").toTime24())
        assertNull(EventTimeInput(DayPeriod.PM, "0", "30").toTime24())
        assertNull(EventTimeInput(DayPeriod.PM, "6", "60").toTime24())
    }

    @Test
    fun `검증 문구는 틀린 칸을 먼저 짚고 빈 칸은 그 다음이다`() {
        assertEquals("실종 시간을 입력해 주세요.", EventTimeInput().validationError("실종 시간"))
        assertEquals("발견 시간을 입력해 주세요.", EventTimeInput(DayPeriod.PM, "6", "").validationError("발견 시간"))
        assertEquals("시는 1~12 사이로 입력해 주세요.", EventTimeInput(DayPeriod.PM, "13", "00").validationError("실종 시간"))
        // 분이 비어 있어도 틀린 시를 먼저 말한다.
        assertEquals("시는 1~12 사이로 입력해 주세요.", EventTimeInput(DayPeriod.PM, "13", "").validationError("실종 시간"))
        assertEquals("분은 0~59 사이로 입력해 주세요.", EventTimeInput(DayPeriod.PM, "6", "60").validationError("실종 시간"))
        assertNull(EventTimeInput(DayPeriod.PM, "6", "30").validationError("실종 시간"))
    }

    @Test
    fun `더 쳐도 맞을 수 없는 값만 바로 틀렸다고 본다`() {
        // "0" 은 "07" 이 되는 중일 수 있다.
        assertFalse(EventTimeInput(hour = "0").hasOutOfRangePart)
        assertFalse(EventTimeInput(hour = "1").hasOutOfRangePart)
        assertTrue(EventTimeInput(hour = "13").hasOutOfRangePart)
        assertTrue(EventTimeInput(hour = "00").hasOutOfRangePart)
        assertFalse(EventTimeInput(minute = "6").hasOutOfRangePart)
        assertTrue(EventTimeInput(minute = "60").hasOutOfRangePart)
        assertFalse(EventTimeInput(minute = "59").hasOutOfRangePart)
    }

    @Test
    fun `시 분 칸은 숫자 두 자리까지만 받는다`() {
        assertEquals("12", EventTimeInput.digits("123"))
        assertEquals("07", EventTimeInput.digits("0a7"))
        assertEquals("", EventTimeInput.digits("시"))
    }

    @Test
    fun `24시간 표기를 화면 표기로 되돌린다`() {
        assertEquals(EventTimeInput(DayPeriod.AM, "12", "30"), EventTimeInput.fromTime24("00:30"))
        assertEquals(EventTimeInput(DayPeriod.AM, "9", "05"), EventTimeInput.fromTime24("09:05"))
        assertEquals(EventTimeInput(DayPeriod.PM, "12", "00"), EventTimeInput.fromTime24("12:00"))
        assertEquals(EventTimeInput(DayPeriod.PM, "6", "30"), EventTimeInput.fromTime24("18:30"))
        assertNull(EventTimeInput.fromTime24("24:00"))
        assertNull(EventTimeInput.fromTime24("1830"))
    }
}
