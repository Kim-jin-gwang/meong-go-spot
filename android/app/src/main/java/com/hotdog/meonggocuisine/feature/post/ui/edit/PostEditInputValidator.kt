package com.hotdog.meonggocuisine.feature.post.ui.edit

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * 수정 폼의 선검증입니다. 서버도 같은 규칙을 검증하므로 여기서는 사용자가 바로 고칠 수 있는
 * 형식과 날짜 상한만 확인한다.
 */
object PostEditInputValidator {
    const val MAX_PHOTO_COUNT = 10
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val seoul = ZoneId.of("Asia/Seoul")

    fun validate(
        state: PostEditUiState,
        clock: Clock = Clock.system(seoul),
    ): PostEditValidationErrors =
        PostEditValidationErrors(
            photos =
                when {
                    state.photos.isEmpty() -> "사진을 1장 이상 남겨 주세요."
                    state.photos.size > MAX_PHOTO_COUNT -> "사진은 최대 10장까지 선택할 수 있습니다."
                    else -> null
                },
            protectionStatus =
                if (state.isSheltering && state.protectionStatusValue.isBlank()) {
                    "현재 보호 상태를 선택해 주세요."
                } else {
                    null
                },
            eventDate = validateDate(state.eventDate, clock),
            eventTime = state.eventTime.validationError(if (state.isSheltering) "발견 시간" else "실종 시간"),
            eventPlace = validatePlace(state.eventPlace, if (state.isSheltering) "발견 장소" else "실종 장소"),
            currentPlace = if (state.isSheltering) validatePlace(state.currentPlace, "현재 보호 장소") else null,
        )

    private fun validateDate(
        value: String,
        clock: Clock,
    ): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return "사건 날짜를 입력해 주세요."
        val date =
            try {
                LocalDate.parse(trimmed, dateFormatter)
            } catch (_: DateTimeParseException) {
                return "날짜는 YYYY-MM-DD 형식으로 입력해 주세요."
            }
        return if (date.isAfter(LocalDate.now(clock.withZone(seoul)))) {
            "내일 이후 날짜는 선택할 수 없습니다."
        } else {
            null
        }
    }

    /**
     * 장소는 등록과 같이 반드시 있어야 합니다.
     *
     * 등록 화면이 비워 두고는 못 넘어가게 하는 칸이라, 수정에서만 비울 수 있으면 같은 게시물이
     * 수정 한 번에 등록 때는 만들 수 없던 모양이 된다.
     */
    private fun validatePlace(
        value: String,
        label: String,
    ): String? = if (value.trim().isEmpty()) "${label}를 입력해 주세요." else null
}

data class PostEditValidationErrors(
    val photos: String? = null,
    val protectionStatus: String? = null,
    val eventDate: String? = null,
    val eventTime: String? = null,
    val eventPlace: String? = null,
    val currentPlace: String? = null,
) {
    val hasErrors: Boolean
        get() = listOf(photos, protectionStatus, eventDate, eventTime, eventPlace, currentPlace).any { it != null }
}
