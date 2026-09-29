package com.hotdog.meonggocuisine.feature.report.ui.sheltering

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hotdog.meonggocuisine.feature.community.data.RegionStore
import com.hotdog.meonggocuisine.feature.report.data.PhotoInputInspector
import com.hotdog.meonggocuisine.feature.report.data.ShelteringPostCreateInput
import com.hotdog.meonggocuisine.feature.report.data.ShelteringPostCreateRepository
import com.hotdog.meonggocuisine.feature.report.data.ShelteringPostCreateResult
import com.hotdog.meonggocuisine.feature.report.ui.DayPeriod
import com.hotdog.meonggocuisine.feature.report.ui.EventDateTimeInput
import com.hotdog.meonggocuisine.feature.report.ui.EventTimeInput
import com.hotdog.meonggocuisine.feature.report.ui.PostTextLimits
import com.hotdog.meonggocuisine.feature.report.ui.PostTextLimits.limitTo
import com.hotdog.meonggocuisine.feature.report.ui.buildPhotoRejectionNotice
import com.hotdog.meonggocuisine.feature.report.ui.lost.AnimalSexOption
import com.hotdog.meonggocuisine.feature.report.ui.lost.AnimalSpeciesOption
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

sealed interface ShelteringPostCreateEvent {
    data object Created : ShelteringPostCreateEvent
}

@HiltViewModel
class ShelteringPostCreateViewModel
    @Inject
    constructor(
        private val repository: ShelteringPostCreateRepository,
        private val photoInspector: PhotoInputInspector,
        regionStore: RegionStore,
    ) : ViewModel() {
        // 게시물에는 시·군·구 코드가 필요하다. 저장된 커뮤니티 지역이 시·군·구면 초깃값으로만 쓰고,
        // "전국"·"시·도 전체"거나 없으면 비워 두어 사용자가 화면에서 직접 고르게 한다.
        private val storedRegion = regionStore.selectedRegion.value?.takeIf { it.isDistrict }
        private val mutableUiState =
            MutableStateFlow(
                ShelteringPostCreateUiState(
                    selectedRegionCode = storedRegion?.code,
                    selectedRegionName = storedRegion?.name,
                ),
            )
        val uiState: StateFlow<ShelteringPostCreateUiState> = mutableUiState.asStateFlow()

        private val eventChannel = Channel<ShelteringPostCreateEvent>(Channel.BUFFERED)
        val events = eventChannel.receiveAsFlow()

        fun addPhotos(uris: List<String>) {
            viewModelScope.launch {
                val inspected = uris.map { it to photoInspector.rejection(it) }
                val accepted = inspected.filter { it.second == null }.map { it.first }
                val rejected = inspected.mapNotNull { it.second }
                val rejection = rejected.firstOrNull()
                update {
                    val merged = (photoUris + accepted).distinct()
                    copy(
                        photoUris = merged.take(ShelteringPostCreateInputValidator.MAX_PHOTO_COUNT),
                        // 빠진 사진이 있으면 이유를 창으로 — 아래 빨간 한 줄은 첫 이유만 남는다.
                        photoRejectionNotice =
                            buildPhotoRejectionNotice(
                                uris.size,
                                rejected,
                                merged.size - ShelteringPostCreateInputValidator.MAX_PHOTO_COUNT,
                            ),
                        externalErrors =
                            externalErrors.withError(
                                ShelteringPostField.PHOTOS,
                                when {
                                    rejection != null -> rejection
                                    merged.size > ShelteringPostCreateInputValidator.MAX_PHOTO_COUNT -> "사진은 최대 10장까지 선택할 수 있습니다."
                                    else -> null
                                },
                            ),
                        touchedFields = touchedFields + ShelteringPostField.PHOTOS,
                        requestError = null,
                    )
                }
            }
        }

        fun dismissPhotoRejectionNotice() = update { copy(photoRejectionNotice = null) }

        fun removePhoto(uri: String) =
            update {
                copy(
                    photoUris = photoUris - uri,
                    externalErrors = externalErrors - ShelteringPostField.PHOTOS,
                    touchedFields = touchedFields + ShelteringPostField.PHOTOS,
                    requestError = null,
                )
            }

        /** 카메라 앱을 열 수 없을 때 등 사진을 가져올 입구가 막힌 이유를 사진 칸 아래에 보여 준다. */
        fun onPhotoSourceUnavailable(message: String) =
            update { copy(externalErrors = externalErrors + (ShelteringPostField.PHOTOS to message)) }

        // 글자 칸은 서버 한도(PostTextLimits)에서 잘라 넣는다 — 넘친 채 보내면 서버가 어느 칸인지 말해 주지 않는 400 을 준다.
        fun onNameChange(value: String) = update { copy(name = value.limitTo(PostTextLimits.NAME), requestError = null) }

        fun onSpeciesChange(value: AnimalSpeciesOption) = update { copy(species = value, requestError = null) }

        fun onBreedNameChange(value: String) = update { copy(breedName = value.limitTo(PostTextLimits.BREED_NAME), requestError = null) }

        fun onSexChange(value: AnimalSexOption) = update { copy(sex = value, requestError = null) }

        fun onColorChange(value: String) = update { copy(color = value.limitTo(PostTextLimits.COLOR), requestError = null) }

        /** 여덟 자리를 다 치면 그 자리에서 검사한다 — 없는 날짜·미래 날짜는 바로 알 수 있어야 한다. */
        fun onFoundDateChange(value: String) =
            update {
                val date = EventDateTimeInput.date(value)
                copy(
                    foundDate = date,
                    externalErrors = externalErrors - ShelteringPostField.FOUND_DATE,
                    touchedFields =
                        touchedFields.plusIf(
                            ShelteringPostField.FOUND_DATE,
                            EventDateTimeInput.digitCount(date) == EventDateTimeInput.DATE_DIGITS,
                        ),
                    requestError = null,
                )
            }

        fun onFoundPeriodChange(period: DayPeriod) =
            update {
                copy(
                    foundTime = foundTime.copy(period = period),
                    externalErrors = externalErrors - ShelteringPostField.FOUND_TIME,
                    requestError = null,
                )
            }

        fun onFoundHourChange(value: String) =
            update {
                copy(
                    foundTime = foundTime.copy(hour = EventTimeInput.digits(value)),
                    externalErrors = externalErrors - ShelteringPostField.FOUND_TIME,
                    requestError = null,
                )
            }

        /** 분 두 자리를 다 치면 시각 전체를 검사한다. 시 칸은 분을 치러 가는 길이라 여기서만 본다. */
        fun onFoundMinuteChange(value: String) =
            update {
                val minute = EventTimeInput.digits(value)
                copy(
                    foundTime = foundTime.copy(minute = minute),
                    externalErrors = externalErrors - ShelteringPostField.FOUND_TIME,
                    touchedFields = touchedFields.plusIf(ShelteringPostField.FOUND_TIME, minute.length == EventTimeInput.MAX_DIGITS),
                    requestError = null,
                )
            }

        fun onFoundPlaceChange(value: String) =
            update {
                copy(
                    foundPlace = value.limitTo(PostTextLimits.PLACE),
                    externalErrors = externalErrors - ShelteringPostField.FOUND_PLACE,
                    requestError = null,
                )
            }

        /** 칸을 떠났다. 그 칸의 검증 오류를 이제부터 보여 준다. */
        fun onFieldLeave(field: ShelteringPostField) = update { copy(touchedFields = touchedFields + field) }

        fun onRegionSelect(
            code: String?,
            name: String?,
        ) = update {
            copy(
                selectedRegionCode = code,
                selectedRegionName = name,
                externalErrors = externalErrors - ShelteringPostField.REGION,
                touchedFields = touchedFields + ShelteringPostField.REGION,
                requestError = null,
            )
        }

        fun onFoundLocationVisibleChange(value: Boolean) = update { copy(foundLocationVisible = value, requestError = null) }

        fun onProtectionStatusChange(value: String) =
            update {
                copy(
                    protectionStatus = value.limitTo(PostTextLimits.PROTECTION_STATUS),
                    externalErrors = externalErrors - ShelteringPostField.PROTECTION_STATUS,
                    requestError = null,
                )
            }

        fun onCurrentProtectionPlaceChange(value: String) =
            update {
                copy(
                    currentProtectionPlace = value.limitTo(PostTextLimits.PLACE),
                    externalErrors = externalErrors - ShelteringPostField.CURRENT_PROTECTION_PLACE,
                    requestError = null,
                )
            }

        fun onCurrentLocationVisibleChange(value: Boolean) = update { copy(currentLocationVisible = value, requestError = null) }

        fun onFeatureTextChange(value: String) =
            update { copy(featureText = value.limitTo(PostTextLimits.SHELTERING_FEATURE_TEXT), requestError = null) }

        fun submit() {
            val state = mutableUiState.value
            if (state.isSubmitting) return

            // 버튼은 오류가 있으면 잠기지만, 어떤 경로로든 여기 오면 손대지 않은 칸의 오류까지 모두 드러낸다.
            update { copy(submitAttempted = true, requestError = null) }
            if (state.validation.hasErrors) return

            viewModelScope.launch {
                update { copy(isSubmitting = true, requestError = null) }
                when (val result = repository.createShelteringPost(state.toInput())) {
                    is ShelteringPostCreateResult.Success -> {
                        update { copy(isSubmitting = false) }
                        eventChannel.send(ShelteringPostCreateEvent.Created)
                    }
                    is ShelteringPostCreateResult.InvalidInput ->
                        update {
                            copy(
                                isSubmitting = false,
                                externalErrors = externalErrors + result.fieldErrors.toFieldErrors(),
                                requestError = result.message,
                            )
                        }
                    is ShelteringPostCreateResult.PhotoInvalid ->
                        update {
                            copy(
                                isSubmitting = false,
                                externalErrors = externalErrors + (ShelteringPostField.PHOTOS to result.message),
                                // 사진 칸의 빨간 글씨는 첫 단계에만 있고 등록 버튼은 마지막 단계에 있다 — 여기에도 알려야
                                // 버튼이 먹통으로 보이지 않는다 (2026-09-23 QA).
                                requestError = photoSubmitError(result.message),
                            )
                        }
                    is ShelteringPostCreateResult.Failure ->
                        update { copy(isSubmitting = false, requestError = result.message) }
                }
            }
        }

        private fun ShelteringPostCreateUiState.toInput(): ShelteringPostCreateInput =
            ShelteringPostCreateInput(
                clientRequestId = UUID.randomUUID().toString(),
                name = name,
                species = species.value,
                breedName = breedName,
                sex = sex.value,
                color = color,
                foundDate = foundDate,
                // 검증을 통과한 상태에서만 부른다 — 시각·지역이 비었으면 submit 이 여기 오기 전에 끊는다.
                foundTime = requireNotNull(foundTime.toTime24()),
                foundPlace = foundPlace,
                foundLocationVisible = foundLocationVisible,
                protectionStatus = protectionStatus,
                currentProtectionPlace = currentProtectionPlace,
                currentLocationVisible = currentLocationVisible,
                regionCode = requireNotNull(selectedRegionCode),
                featureText = featureText,
                photoUris = photoUris,
            )

        /**
         * 서버 400 의 `fieldErrors` 키를 화면 칸으로 옮긴다. 요청 본문(`DefaultShelteringPostCreateRepository`)의
         * 필드 이름 기준 — 발견 정보는 `event*`, 현재 보호 장소는 `currentLocation`. 모르는 키는 `requestError` 문장에만 남는다.
         */
        private fun Map<String, String>.toFieldErrors(): Map<ShelteringPostField, String> =
            buildMap {
                this@toFieldErrors["eventDate"]?.let { put(ShelteringPostField.FOUND_DATE, it) }
                this@toFieldErrors["eventTime"]?.let { put(ShelteringPostField.FOUND_TIME, it) }
                this@toFieldErrors["eventLocation.regionCode"]?.let { put(ShelteringPostField.REGION, it) }
                this@toFieldErrors["eventLocation.exactLocation"]?.let { put(ShelteringPostField.FOUND_PLACE, it) }
                this@toFieldErrors["currentLocation.exactLocation"]?.let { put(ShelteringPostField.CURRENT_PROTECTION_PLACE, it) }
            }

        private fun Map<ShelteringPostField, String>.withError(
            field: ShelteringPostField,
            message: String?,
        ): Map<ShelteringPostField, String> = if (message == null) this - field else this + (field to message)

        private fun Set<ShelteringPostField>.plusIf(
            field: ShelteringPostField,
            condition: Boolean,
        ): Set<ShelteringPostField> = if (condition) this + field else this

        private fun photoSubmitError(message: String) = "$message 사진 등록 단계로 돌아가 사진을 확인해 주세요."

        private fun update(block: ShelteringPostCreateUiState.() -> ShelteringPostCreateUiState) {
            mutableUiState.value = mutableUiState.value.block()
        }
    }
