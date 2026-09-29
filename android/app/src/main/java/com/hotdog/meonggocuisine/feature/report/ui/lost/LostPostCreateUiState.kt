package com.hotdog.meonggocuisine.feature.report.ui.lost

import com.hotdog.meonggocuisine.feature.report.ui.EventTimeInput
import com.hotdog.meonggocuisine.feature.report.ui.PhotoRejectionNotice

/** 등록 폼의 필수 칸. [label] 은 오류 안내와 "등록 전 확인할 항목" 줄에 쓴다. 화면에 놓인 순서대로. */
enum class LostPostField(val label: String) {
    PHOTOS("사진"),
    EVENT_DATE("실종 날짜"),
    EVENT_TIME("실종 시간"),
    REGION("실종 지역"),
    EVENT_PLACE("실종 장소"),
}

/**
 * 오류는 두 겹이다.
 *
 * - 화면 검증([LostPostCreateInputValidator])은 상태에서 그때그때 계산한다. 다만 사용자가 그 칸을 떠났거나
 *   다 채운 뒤([touchedFields]), 또는 등록을 눌러 본 뒤([submitAttempted])에만 보인다 — 치는 도중에 빨간 글씨가
 *   뜨면 잔소리가 된다.
 * - 서버가 400 으로 짚은 칸과 사진 검사 결과는 [externalErrors] 에 담고, 그 칸을 다시 고치면 지운다.
 *
 * 등록 버튼은 검증 오류가 하나라도 있으면 잠기고([canSubmit]), 무엇이 남았는지 [submitBlockers] 로 알려 준다.
 */
data class LostPostCreateUiState(
    val photoUris: List<String> = emptyList(),
    /** 마지막으로 사진을 더했을 때 넣지 못한 것이 있으면 그 안내. 확인을 누르면 null. */
    val photoRejectionNotice: PhotoRejectionNotice? = null,
    val name: String = "",
    val species: AnimalSpeciesOption = AnimalSpeciesOption.DOG,
    val breedName: String = "",
    val sex: AnimalSexOption = AnimalSexOption.MALE,
    val color: String = "",
    val eventDate: String = "",
    val eventTime: EventTimeInput = EventTimeInput(),
    val eventPlace: String = "",
    val exactLocationVisible: Boolean = false,
    val featureText: String = "",
    val selectedRegionCode: String? = null,
    val selectedRegionName: String? = null,
    val touchedFields: Set<LostPostField> = emptySet(),
    val submitAttempted: Boolean = false,
    val externalErrors: Map<LostPostField, String> = emptyMap(),
    val requestError: String? = null,
    val isSubmitting: Boolean = false,
) {
    val validation: LostPostCreateValidationErrors get() = LostPostCreateInputValidator.validate(this)

    val photoError: String? get() = visibleError(LostPostField.PHOTOS, validation.photos)
    val regionError: String? get() = visibleError(LostPostField.REGION, validation.region)
    val eventDateError: String? get() = visibleError(LostPostField.EVENT_DATE, validation.eventDate)
    val eventPlaceError: String? get() = visibleError(LostPostField.EVENT_PLACE, validation.eventPlace)

    /** 시 `13` 처럼 더 쳐도 맞을 수 없는 값은 칸을 떠나기 전에도 바로 보인다. */
    val eventTimeError: String?
        get() =
            externalErrors[LostPostField.EVENT_TIME]
                ?: validation.eventTime?.takeIf { shows(LostPostField.EVENT_TIME) || eventTime.hasOutOfRangePart }

    val canSubmit: Boolean get() = !isSubmitting && !validation.hasErrors

    /** 등록 버튼이 잠긴 이유 — 비었거나 잘못된 칸 이름, 화면 순서대로. */
    val submitBlockers: List<String> get() = validation.blockers()

    /** 아직 못 채운 칸. 단계별로 "이 단계에 남은 것"을 뽑을 때 쓴다. */
    val blockerFields: List<LostPostField> get() = validation.blockerFields()

    private fun shows(field: LostPostField): Boolean = submitAttempted || field in touchedFields

    private fun visibleError(
        field: LostPostField,
        ruleError: String?,
    ): String? = externalErrors[field] ?: ruleError?.takeIf { shows(field) }
}

enum class AnimalSpeciesOption(
    val value: String,
    val label: String,
) {
    DOG("DOG", "강아지"),
    CAT("CAT", "고양이"),
}

enum class AnimalSexOption(
    val value: String,
    val label: String,
) {
    MALE("MALE", "수컷"),
    FEMALE("FEMALE", "암컷"),
    UNKNOWN("UNKNOWN", "모름"),
}
