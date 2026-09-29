package com.hotdog.meonggocuisine.feature.report.ui.lost

import com.hotdog.meonggocuisine.feature.report.ui.DayPeriod
import com.hotdog.meonggocuisine.feature.report.ui.EventTimeInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LostPostCreateInputValidatorTest {
    @Test
    fun `사진이 없으면 등록할 수 없다`() {
        val errors = LostPostCreateInputValidator.validate(validState(photoUris = emptyList()))

        assertEquals("사진을 1장 이상 선택해 주세요.", errors.photos)
        assertTrue(errors.hasErrors)
    }

    @Test
    fun `내일 이후 날짜는 등록할 수 없다`() {
        val errors =
            LostPostCreateInputValidator.validate(
                validState(eventDate = LocalDate.now().plusDays(1).toString()),
            )

        assertEquals("내일 이후 날짜는 선택할 수 없습니다.", errors.eventDate)
    }

    @Test
    fun `시간은 시와 분이 모두 있어야 하고 범위 안이어야 한다`() {
        assertEquals(
            "실종 시간을 입력해 주세요.",
            LostPostCreateInputValidator.validate(validState(eventTime = EventTimeInput())).eventTime,
        )
        assertEquals(
            "시는 1~12 사이로 입력해 주세요.",
            LostPostCreateInputValidator.validate(validState(eventTime = EventTimeInput(DayPeriod.PM, "13", "00"))).eventTime,
        )
        assertEquals(
            "분은 0~59 사이로 입력해 주세요.",
            LostPostCreateInputValidator.validate(validState(eventTime = EventTimeInput(DayPeriod.PM, "6", "60"))).eventTime,
        )
    }

    @Test
    fun `지역을 선택하지 않으면 등록할 수 없다`() {
        val errors = LostPostCreateInputValidator.validate(validState(selectedRegionCode = null))

        assertEquals("실종 지역을 선택해 주세요.", errors.region)
        assertTrue(errors.hasErrors)
    }

    @Test
    fun `막힌 칸 이름을 화면 순서대로 알려 준다`() {
        val errors = LostPostCreateInputValidator.validate(LostPostCreateUiState())

        assertEquals(listOf("사진", "실종 날짜", "실종 시간", "실종 지역", "실종 장소"), errors.blockers())
    }

    @Test
    fun `필수값이 있으면 검증을 통과한다`() {
        val errors = LostPostCreateInputValidator.validate(validState())

        assertFalse(errors.hasErrors)
        assertTrue(errors.blockers().isEmpty())
    }

    private fun validState(
        photoUris: List<String> = listOf("content://photo/1"),
        eventDate: String = LocalDate.now().toString(),
        eventTime: EventTimeInput = EventTimeInput(DayPeriod.PM, "6", "30"),
        selectedRegionCode: String? = "30170",
    ) = LostPostCreateUiState(
        photoUris = photoUris,
        eventDate = eventDate,
        eventTime = eventTime,
        eventPlace = "대전광역시 서구 둔산동",
        selectedRegionCode = selectedRegionCode,
        selectedRegionName = selectedRegionCode?.let { "대전광역시 서구" },
    )
}
