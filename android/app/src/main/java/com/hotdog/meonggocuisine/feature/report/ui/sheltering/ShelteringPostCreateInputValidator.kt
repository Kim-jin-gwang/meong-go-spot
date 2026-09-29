package com.hotdog.meonggocuisine.feature.report.ui.sheltering

import com.hotdog.meonggocuisine.feature.report.ui.EventDateTimeInput
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

object ShelteringPostCreateInputValidator {
    const val MAX_PHOTO_COUNT = 10

    fun validate(state: ShelteringPostCreateUiState): ShelteringPostCreateValidationErrors =
        ShelteringPostCreateValidationErrors(
            photos =
                when {
                    state.photoUris.isEmpty() -> "사진을 1장 이상 선택해 주세요."
                    state.photoUris.size > MAX_PHOTO_COUNT -> "사진은 최대 10장까지 선택할 수 있습니다."
                    else -> null
                },
            region = if (state.selectedRegionCode == null) "발견 지역을 선택해 주세요." else null,
            foundDate = validateDate(state.foundDate),
            foundTime = state.foundTime.validationError(ShelteringPostField.FOUND_TIME.label),
            foundPlace = required(state.foundPlace, "발견 장소를 입력해 주세요."),
            protectionStatus = required(state.protectionStatus, "현재 보호 상태를 입력해 주세요."),
            currentProtectionPlace = required(state.currentProtectionPlace, "현재 보호 장소를 입력해 주세요."),
        )

    private fun validateDate(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return "발견 날짜를 입력해 주세요."
        if (EventDateTimeInput.digitCount(trimmed) < EventDateTimeInput.DATE_DIGITS) {
            return "날짜 8자리를 모두 입력해 주세요. 예: 20260909"
        }
        val date =
            try {
                LocalDate.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE)
            } catch (_: DateTimeParseException) {
                return "없는 날짜입니다. 월과 일을 확인해 주세요."
            }
        return if (date.isAfter(LocalDate.now())) "내일 이후 날짜는 선택할 수 없습니다." else null
    }

    private fun required(
        value: String,
        message: String,
    ): String? = if (value.trim().isEmpty()) message else null
}

data class ShelteringPostCreateValidationErrors(
    val photos: String? = null,
    val region: String? = null,
    val foundDate: String? = null,
    val foundTime: String? = null,
    val foundPlace: String? = null,
    val protectionStatus: String? = null,
    val currentProtectionPlace: String? = null,
) {
    val hasErrors: Boolean get() = blockerFields().isNotEmpty()

    /**
     * 아직 못 채운 칸을 화면 순서대로.
     *
     * 이름이 아니라 칸 자체를 돌려주는 이유는 실종 등록과 같다
     * ([com.hotdog.meonggocuisine.feature.report.ui.lost.LostPostCreateValidationErrors.blockerFields]).
     */
    fun blockerFields(): List<ShelteringPostField> =
        listOfNotNull(
            photos?.let { ShelteringPostField.PHOTOS },
            foundDate?.let { ShelteringPostField.FOUND_DATE },
            foundTime?.let { ShelteringPostField.FOUND_TIME },
            region?.let { ShelteringPostField.REGION },
            foundPlace?.let { ShelteringPostField.FOUND_PLACE },
            protectionStatus?.let { ShelteringPostField.PROTECTION_STATUS },
            currentProtectionPlace?.let { ShelteringPostField.CURRENT_PROTECTION_PLACE },
        )

    /** 못 채운 칸의 이름. */
    fun blockers(): List<String> = blockerFields().map { it.label }
}
