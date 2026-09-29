package com.hotdog.meonggocuisine.feature.report.ui.lost

import com.hotdog.meonggocuisine.feature.report.ui.EventDateTimeInput
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

object LostPostCreateInputValidator {
    const val MAX_PHOTO_COUNT = 10
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    fun validate(state: LostPostCreateUiState): LostPostCreateValidationErrors =
        LostPostCreateValidationErrors(
            photos =
                when {
                    state.photoUris.isEmpty() -> "사진을 1장 이상 선택해 주세요."
                    state.photoUris.size > MAX_PHOTO_COUNT -> "사진은 최대 10장까지 선택할 수 있습니다."
                    else -> null
                },
            region = if (state.selectedRegionCode == null) "실종 지역을 선택해 주세요." else null,
            eventDate = validateDate(state.eventDate),
            eventTime = state.eventTime.validationError(LostPostField.EVENT_TIME.label),
            eventPlace =
                if (state.eventPlace.trim().isEmpty()) {
                    "실종 장소를 입력해 주세요."
                } else {
                    null
                },
        )

    private fun validateDate(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return "실종 날짜를 입력해 주세요."
        if (EventDateTimeInput.digitCount(trimmed) < EventDateTimeInput.DATE_DIGITS) {
            return "날짜 8자리를 모두 입력해 주세요. 예: 20260909"
        }
        val date =
            try {
                LocalDate.parse(trimmed, dateFormatter)
            } catch (_: DateTimeParseException) {
                return "없는 날짜입니다. 월과 일을 확인해 주세요."
            }
        return if (date.isAfter(LocalDate.now())) {
            "내일 이후 날짜는 선택할 수 없습니다."
        } else {
            null
        }
    }
}

data class LostPostCreateValidationErrors(
    val photos: String? = null,
    val region: String? = null,
    val eventDate: String? = null,
    val eventTime: String? = null,
    val eventPlace: String? = null,
) {
    val hasErrors: Boolean get() = blockerFields().isNotEmpty()

    /**
     * 아직 못 채운 칸을 화면 순서대로.
     *
     * 이름이 아니라 칸 자체를 돌려준다. 등록이 단계로 나뉘면서 "이 단계에 남은 것"만 뽑아야 하는데,
     * 이름 글자를 맞춰 보는 방식은 문구를 고치는 순간 조용히 어긋나기 때문이다.
     */
    fun blockerFields(): List<LostPostField> =
        listOfNotNull(
            photos?.let { LostPostField.PHOTOS },
            eventDate?.let { LostPostField.EVENT_DATE },
            eventTime?.let { LostPostField.EVENT_TIME },
            region?.let { LostPostField.REGION },
            eventPlace?.let { LostPostField.EVENT_PLACE },
        )

    /** 못 채운 칸의 이름. */
    fun blockers(): List<String> = blockerFields().map { it.label }
}
