package com.hotdog.meonggocuisine.feature.report.ui.sheltering

import com.hotdog.meonggocuisine.feature.report.ui.EventTimeInput
import com.hotdog.meonggocuisine.feature.report.ui.PhotoRejectionNotice
import com.hotdog.meonggocuisine.feature.report.ui.lost.AnimalSexOption
import com.hotdog.meonggocuisine.feature.report.ui.lost.AnimalSpeciesOption

/** 등록 폼의 필수 칸. [label] 은 오류 안내와 "등록 전 확인할 항목" 줄에 쓴다. 화면에 놓인 순서대로. */
enum class ShelteringPostField(val label: String) {
    PHOTOS("사진"),
    FOUND_DATE("발견 날짜"),
    FOUND_TIME("발견 시간"),
    REGION("발견 지역"),
    FOUND_PLACE("발견 장소"),
    PROTECTION_STATUS("현재 보호 상태"),
    CURRENT_PROTECTION_PLACE("현재 보호 장소"),
}

/**
 * 오류는 두 겹이다 — 실종 등록([com.hotdog.meonggocuisine.feature.report.ui.lost.LostPostCreateUiState])과 같은 규칙.
 *
 * - 화면 검증은 상태에서 그때그때 계산하되, 칸을 떠났거나 다 채운 뒤([touchedFields]) 또는 등록을 눌러 본
 *   뒤([submitAttempted])에만 보인다.
 * - 서버가 짚은 칸과 사진 검사 결과는 [externalErrors] 에 담고, 그 칸을 다시 고치면 지운다.
 *
 * 등록 버튼은 검증 오류가 있으면 잠기고([canSubmit]), 남은 칸을 [submitBlockers] 로 알려 준다.
 */
data class ShelteringPostCreateUiState(
    val photoUris: List<String> = emptyList(),
    /** 마지막으로 사진을 더했을 때 넣지 못한 것이 있으면 그 안내. 확인을 누르면 null. */
    val photoRejectionNotice: PhotoRejectionNotice? = null,
    val name: String = "",
    val species: AnimalSpeciesOption = AnimalSpeciesOption.DOG,
    val breedName: String = "",
    val sex: AnimalSexOption = AnimalSexOption.MALE,
    val color: String = "",
    val foundDate: String = "",
    val foundTime: EventTimeInput = EventTimeInput(),
    val foundPlace: String = "",
    val foundLocationVisible: Boolean = false,
    val protectionStatus: String = "",
    val currentProtectionPlace: String = "",
    val currentLocationVisible: Boolean = false,
    val featureText: String = "",
    val selectedRegionCode: String? = null,
    val selectedRegionName: String? = null,
    val touchedFields: Set<ShelteringPostField> = emptySet(),
    val submitAttempted: Boolean = false,
    val externalErrors: Map<ShelteringPostField, String> = emptyMap(),
    val requestError: String? = null,
    val isSubmitting: Boolean = false,
) {
    val validation: ShelteringPostCreateValidationErrors get() = ShelteringPostCreateInputValidator.validate(this)

    val photoError: String? get() = visibleError(ShelteringPostField.PHOTOS, validation.photos)
    val regionError: String? get() = visibleError(ShelteringPostField.REGION, validation.region)
    val foundDateError: String? get() = visibleError(ShelteringPostField.FOUND_DATE, validation.foundDate)
    val foundPlaceError: String? get() = visibleError(ShelteringPostField.FOUND_PLACE, validation.foundPlace)
    val protectionStatusError: String? get() = visibleError(ShelteringPostField.PROTECTION_STATUS, validation.protectionStatus)
    val currentProtectionPlaceError: String?
        get() = visibleError(ShelteringPostField.CURRENT_PROTECTION_PLACE, validation.currentProtectionPlace)

    /** 시 `13` 처럼 더 쳐도 맞을 수 없는 값은 칸을 떠나기 전에도 바로 보인다. */
    val foundTimeError: String?
        get() =
            externalErrors[ShelteringPostField.FOUND_TIME]
                ?: validation.foundTime?.takeIf { shows(ShelteringPostField.FOUND_TIME) || foundTime.hasOutOfRangePart }

    val canSubmit: Boolean get() = !isSubmitting && !validation.hasErrors

    /** 등록 버튼이 잠긴 이유 — 비었거나 잘못된 칸 이름, 화면 순서대로. */
    val submitBlockers: List<String> get() = validation.blockers()

    /** 아직 못 채운 칸. 단계별로 "이 단계에 남은 것"을 뽑을 때 쓴다. */
    val blockerFields: List<ShelteringPostField> get() = validation.blockerFields()

    private fun shows(field: ShelteringPostField): Boolean = submitAttempted || field in touchedFields

    private fun visibleError(
        field: ShelteringPostField,
        ruleError: String?,
    ): String? = externalErrors[field] ?: ruleError?.takeIf { shows(field) }
}
