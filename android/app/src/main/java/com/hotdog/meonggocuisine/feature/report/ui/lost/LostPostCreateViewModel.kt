package com.hotdog.meonggocuisine.feature.report.ui.lost

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hotdog.meonggocuisine.feature.community.data.RegionStore
import com.hotdog.meonggocuisine.feature.report.data.LostPostCreateInput
import com.hotdog.meonggocuisine.feature.report.data.LostPostCreateRepository
import com.hotdog.meonggocuisine.feature.report.data.LostPostCreateResult
import com.hotdog.meonggocuisine.feature.report.data.PhotoInputInspector
import com.hotdog.meonggocuisine.feature.report.ui.DayPeriod
import com.hotdog.meonggocuisine.feature.report.ui.EventDateTimeInput
import com.hotdog.meonggocuisine.feature.report.ui.EventTimeInput
import com.hotdog.meonggocuisine.feature.report.ui.PostTextLimits
import com.hotdog.meonggocuisine.feature.report.ui.PostTextLimits.limitTo
import com.hotdog.meonggocuisine.feature.report.ui.buildPhotoRejectionNotice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

sealed interface LostPostCreateEvent {
    data class Created(val postId: Long) : LostPostCreateEvent
}

@HiltViewModel
class LostPostCreateViewModel
    @Inject
    constructor(
        private val repository: LostPostCreateRepository,
        private val photoInspector: PhotoInputInspector,
        regionStore: RegionStore,
    ) : ViewModel() {
        // 게시물에는 시·군·구 코드가 필요하다. 저장된 커뮤니티 지역이 시·군·구면 초깃값으로만 쓰고,
        // "전국"·"시·도 전체"거나 없으면 비워 두어 사용자가 화면에서 직접 고르게 한다.
        private val storedRegion = regionStore.selectedRegion.value?.takeIf { it.isDistrict }
        private val mutableUiState =
            MutableStateFlow(
                LostPostCreateUiState(
                    selectedRegionCode = storedRegion?.code,
                    selectedRegionName = storedRegion?.name,
                ),
            )
        val uiState: StateFlow<LostPostCreateUiState> = mutableUiState.asStateFlow()

        private val eventChannel = Channel<LostPostCreateEvent>(Channel.BUFFERED)
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
                        photoUris = merged.take(LostPostCreateInputValidator.MAX_PHOTO_COUNT),
                        // 빠진 사진이 있으면 이유를 창으로 — 아래 빨간 한 줄은 첫 이유만 남는다.
                        photoRejectionNotice =
                            buildPhotoRejectionNotice(uris.size, rejected, merged.size - LostPostCreateInputValidator.MAX_PHOTO_COUNT),
                        externalErrors =
                            externalErrors.withError(
                                LostPostField.PHOTOS,
                                when {
                                    rejection != null -> rejection
                                    merged.size > LostPostCreateInputValidator.MAX_PHOTO_COUNT -> "사진은 최대 10장까지 선택할 수 있습니다."
                                    else -> null
                                },
                            ),
                        touchedFields = touchedFields + LostPostField.PHOTOS,
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
                    externalErrors = externalErrors - LostPostField.PHOTOS,
                    touchedFields = touchedFields + LostPostField.PHOTOS,
                    requestError = null,
                )
            }

        // 글자 칸은 서버 한도(PostTextLimits)에서 잘라 넣는다 — 넘친 채 보내면 서버가 어느 칸인지 말해 주지 않는 400 을 준다.
        fun onNameChange(value: String) = update { copy(name = value.limitTo(PostTextLimits.NAME), requestError = null) }

        fun onSpeciesChange(value: AnimalSpeciesOption) = update { copy(species = value, requestError = null) }

        fun onBreedNameChange(value: String) = update { copy(breedName = value.limitTo(PostTextLimits.BREED_NAME), requestError = null) }

        fun onSexChange(value: AnimalSexOption) = update { copy(sex = value, requestError = null) }

        fun onColorChange(value: String) = update { copy(color = value.limitTo(PostTextLimits.COLOR), requestError = null) }

        /** 여덟 자리를 다 치면 그 자리에서 검사한다 — 없는 날짜·미래 날짜는 바로 알 수 있어야 한다. */
        fun onEventDateChange(value: String) =
            update {
                val date = EventDateTimeInput.date(value)
                copy(
                    eventDate = date,
                    externalErrors = externalErrors - LostPostField.EVENT_DATE,
                    touchedFields =
                        touchedFields.plusIf(
                            LostPostField.EVENT_DATE,
                            EventDateTimeInput.digitCount(date) == EventDateTimeInput.DATE_DIGITS,
                        ),
                    requestError = null,
                )
            }

        fun onEventPeriodChange(period: DayPeriod) =
            update {
                copy(
                    eventTime = eventTime.copy(period = period),
                    externalErrors = externalErrors - LostPostField.EVENT_TIME,
                    requestError = null,
                )
            }

        fun onEventHourChange(value: String) =
            update {
                copy(
                    eventTime = eventTime.copy(hour = EventTimeInput.digits(value)),
                    externalErrors = externalErrors - LostPostField.EVENT_TIME,
                    requestError = null,
                )
            }

        /** 분 두 자리를 다 치면 시각 전체를 검사한다. 시 칸은 분을 치러 가는 길이라 여기서만 본다. */
        fun onEventMinuteChange(value: String) =
            update {
                val minute = EventTimeInput.digits(value)
                copy(
                    eventTime = eventTime.copy(minute = minute),
                    externalErrors = externalErrors - LostPostField.EVENT_TIME,
                    touchedFields = touchedFields.plusIf(LostPostField.EVENT_TIME, minute.length == EventTimeInput.MAX_DIGITS),
                    requestError = null,
                )
            }

        fun onEventPlaceChange(value: String) =
            update {
                copy(
                    eventPlace = value.limitTo(PostTextLimits.PLACE),
                    externalErrors = externalErrors - LostPostField.EVENT_PLACE,
                    requestError = null,
                )
            }

        /** 칸을 떠났다. 그 칸의 검증 오류를 이제부터 보여 준다. */
        fun onFieldLeave(field: LostPostField) = update { copy(touchedFields = touchedFields + field) }

        fun onRegionSelect(
            code: String?,
            name: String?,
        ) = update {
            copy(
                selectedRegionCode = code,
                selectedRegionName = name,
                externalErrors = externalErrors - LostPostField.REGION,
                touchedFields = touchedFields + LostPostField.REGION,
                requestError = null,
            )
        }

        fun onExactLocationVisibleChange(value: Boolean) = update { copy(exactLocationVisible = value, requestError = null) }

        fun onFeatureTextChange(value: String) =
            update {
                copy(
                    featureText = value.limitTo(PostTextLimits.FEATURE_TEXT),
                    requestError = null,
                )
            }

        fun submit() {
            val state = mutableUiState.value
            if (state.isSubmitting) return

            // 버튼은 오류가 있으면 잠기지만, 어떤 경로로든 여기 오면 손대지 않은 칸의 오류까지 모두 드러낸다.
            update { copy(submitAttempted = true, requestError = null) }
            if (state.validation.hasErrors) return

            viewModelScope.launch {
                update { copy(isSubmitting = true, requestError = null) }
                when (val result = repository.createLostPost(state.toInput())) {
                    is LostPostCreateResult.Success -> {
                        update { copy(isSubmitting = false) }
                        eventChannel.send(LostPostCreateEvent.Created(result.postId))
                    }
                    is LostPostCreateResult.InvalidInput -> {
                        update {
                            copy(
                                isSubmitting = false,
                                externalErrors = externalErrors + result.fieldErrors.toFieldErrors(),
                                requestError = result.message,
                            )
                        }
                    }
                    is LostPostCreateResult.PhotoInvalid ->
                        update {
                            copy(
                                isSubmitting = false,
                                externalErrors = externalErrors + (LostPostField.PHOTOS to result.message),
                                // 사진 칸의 빨간 글씨는 첫 단계에만 있고 등록 버튼은 마지막 단계에 있다 — 여기에도 알려야
                                // 버튼이 먹통으로 보이지 않는다 (2026-09-23 QA).
                                requestError = photoSubmitError(result.message),
                            )
                        }
                    is LostPostCreateResult.Failure ->
                        update { copy(isSubmitting = false, requestError = result.message) }
                }
            }
        }

        private fun LostPostCreateUiState.toInput(): LostPostCreateInput =
            LostPostCreateInput(
                clientRequestId = UUID.randomUUID().toString(),
                name = name,
                species = species.value,
                breedName = breedName,
                sex = sex.value,
                color = color,
                eventDate = eventDate,
                // 검증을 통과한 상태에서만 부른다 — 시각·지역이 비었으면 submit 이 여기 오기 전에 끊는다.
                eventTime = requireNotNull(eventTime.toTime24()),
                eventPlace = eventPlace,
                regionCode = requireNotNull(selectedRegionCode),
                exactLocationVisible = exactLocationVisible,
                featureText = featureText,
                photoUris = photoUris,
            )

        /** 서버 400 의 `fieldErrors` 키를 화면 칸으로 옮긴다. 모르는 키는 `requestError` 문장에만 남는다. */
        private fun Map<String, String>.toFieldErrors(): Map<LostPostField, String> =
            buildMap {
                this@toFieldErrors["eventDate"]?.let { put(LostPostField.EVENT_DATE, it) }
                this@toFieldErrors["eventTime"]?.let { put(LostPostField.EVENT_TIME, it) }
                this@toFieldErrors["eventLocation.exactLocation"]?.let { put(LostPostField.EVENT_PLACE, it) }
                this@toFieldErrors["eventLocation.regionCode"]?.let { put(LostPostField.REGION, it) }
            }

        private fun Map<LostPostField, String>.withError(
            field: LostPostField,
            message: String?,
        ): Map<LostPostField, String> = if (message == null) this - field else this + (field to message)

        private fun Set<LostPostField>.plusIf(
            field: LostPostField,
            condition: Boolean,
        ): Set<LostPostField> = if (condition) this + field else this

        private fun photoSubmitError(message: String) = "$message 사진 등록 단계로 돌아가 사진을 확인해 주세요."

        private fun update(block: LostPostCreateUiState.() -> LostPostCreateUiState) {
            mutableUiState.value = mutableUiState.value.block()
        }
    }
