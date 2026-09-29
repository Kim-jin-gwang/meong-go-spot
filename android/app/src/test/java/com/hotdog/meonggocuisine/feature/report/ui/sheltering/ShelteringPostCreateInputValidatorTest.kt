package com.hotdog.meonggocuisine.feature.report.ui.sheltering

import com.hotdog.meonggocuisine.feature.report.ui.DayPeriod
import com.hotdog.meonggocuisine.feature.report.ui.EventTimeInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ShelteringPostCreateInputValidatorTest {
    @Test
    fun `사진이 없으면 등록할 수 없다`() {
        val errors = ShelteringPostCreateInputValidator.validate(validState(photoUris = emptyList()))

        assertEquals("사진을 1장 이상 선택해 주세요.", errors.photos)
        assertTrue(errors.hasErrors)
    }

    @Test
    fun `발견 정보와 보호 정보가 모두 있어야 등록할 수 있다`() {
        val errors =
            ShelteringPostCreateInputValidator.validate(
                validState(foundPlace = "", currentProtectionPlace = ""),
            )

        assertEquals("발견 장소를 입력해 주세요.", errors.foundPlace)
        assertEquals("현재 보호 장소를 입력해 주세요.", errors.currentProtectionPlace)
    }

    @Test
    fun `발견 시간은 시와 분이 모두 있어야 하고 범위 안이어야 한다`() {
        assertEquals(
            "발견 시간을 입력해 주세요.",
            ShelteringPostCreateInputValidator.validate(validState(foundTime = EventTimeInput(DayPeriod.PM, "2", ""))).foundTime,
        )
        assertEquals(
            "시는 1~12 사이로 입력해 주세요.",
            ShelteringPostCreateInputValidator.validate(validState(foundTime = EventTimeInput(DayPeriod.PM, "14", "20"))).foundTime,
        )
    }

    @Test
    fun `지역을 선택하지 않으면 등록할 수 없다`() {
        val errors = ShelteringPostCreateInputValidator.validate(validState(selectedRegionCode = null))

        assertEquals("발견 지역을 선택해 주세요.", errors.region)
        assertTrue(errors.hasErrors)
    }

    @Test
    fun `막힌 칸 이름을 화면 순서대로 알려 준다`() {
        val errors = ShelteringPostCreateInputValidator.validate(ShelteringPostCreateUiState())

        assertEquals(
            listOf("사진", "발견 날짜", "발견 시간", "발견 지역", "발견 장소", "현재 보호 상태", "현재 보호 장소"),
            errors.blockers(),
        )
    }

    @Test
    fun `필수값이 있으면 검증을 통과한다`() {
        val errors = ShelteringPostCreateInputValidator.validate(validState())

        assertFalse(errors.hasErrors)
    }

    private fun validState(
        photoUris: List<String> = listOf("content://photo/1"),
        foundTime: EventTimeInput = EventTimeInput(DayPeriod.PM, "2", "20"),
        foundPlace: String = "서울특별시 마포구 공원 인근",
        currentProtectionPlace: String = "서울특별시 마포구 자택",
        selectedRegionCode: String? = "11440",
    ) = ShelteringPostCreateUiState(
        photoUris = photoUris,
        foundDate = LocalDate.now().toString(),
        foundTime = foundTime,
        foundPlace = foundPlace,
        protectionStatus = "임시 보호 중",
        currentProtectionPlace = currentProtectionPlace,
        selectedRegionCode = selectedRegionCode,
        selectedRegionName = selectedRegionCode?.let { "서울특별시 마포구" },
    )
}
