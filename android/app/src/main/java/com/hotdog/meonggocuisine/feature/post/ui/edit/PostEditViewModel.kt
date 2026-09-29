package com.hotdog.meonggocuisine.feature.post.ui.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hotdog.meonggocuisine.core.text.ProtectionStatusLine
import com.hotdog.meonggocuisine.feature.post.data.PostDetailRepository
import com.hotdog.meonggocuisine.feature.post.data.PostDetailResponse
import com.hotdog.meonggocuisine.feature.post.data.PostDetailResult
import com.hotdog.meonggocuisine.feature.post.data.PostEditInput
import com.hotdog.meonggocuisine.feature.post.data.PostEditLocationInput
import com.hotdog.meonggocuisine.feature.post.data.PostEditRepository
import com.hotdog.meonggocuisine.feature.post.data.PostEditResult
import com.hotdog.meonggocuisine.feature.post.data.PostPhotoReplaceResult
import com.hotdog.meonggocuisine.feature.post.data.PostPhotoSource
import com.hotdog.meonggocuisine.feature.report.ui.DayPeriod
import com.hotdog.meonggocuisine.feature.report.ui.EventTimeInput
import com.hotdog.meonggocuisine.feature.report.ui.PostTextLimits
import com.hotdog.meonggocuisine.feature.report.ui.PostTextLimits.limitTo
import com.hotdog.meonggocuisine.feature.report.ui.sheltering.ProtectionStatusOption
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface PostEditEvent {
    data class Updated(val postId: Long) : PostEditEvent
}

@HiltViewModel
class PostEditViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val detailRepository: PostDetailRepository,
        private val editRepository: PostEditRepository,
    ) : ViewModel() {
        // toRoute()는 Android Bundle이 필요해 JVM 단위 테스트에서 실행할 수 없어 키로 직접 읽는다.
        val postId: Long = checkNotNull(savedStateHandle[POST_ID_ARG])

        private val mutableUiState = MutableStateFlow(PostEditUiState(postId = postId))
        val uiState: StateFlow<PostEditUiState> = mutableUiState.asStateFlow()

        private val eventChannel = Channel<PostEditEvent>(Channel.BUFFERED)
        val events = eventChannel.receiveAsFlow()

        /** 공개 정책 버전을 요청에 넣어야 하는지 판단하려면 저장된 위치 값이 필요하다. */
        private var savedEventLocation = SavedLocation()
        private var savedCurrentLocation = SavedLocation()

        init {
            load()
        }

        /**
         * 서버 값으로 폼을 다시 채웁니다. 버전 충돌 안내의 새로고침 동선이 이 함수를 쓴다.
         */
        fun refresh() = load()

        fun retryLoad() = load()

        /** 고른 사진을 뒤에 더한다. 열 장을 넘기면 넘긴 만큼만 버리고 이유를 알린다. */
        fun onAddPhotos(uris: List<String>) {
            if (uris.isEmpty()) return
            update {
                val added = uris.map(PostPhotoSource::Picked).filterNot { it.uri in photoRefs }
                val merged = photos + added
                copy(
                    photos = merged.take(PostEditInputValidator.MAX_PHOTO_COUNT),
                    photoError =
                        if (merged.size > PostEditInputValidator.MAX_PHOTO_COUNT) {
                            "사진은 최대 10장까지 등록할 수 있습니다."
                        } else {
                            null
                        },
                    requestError = null,
                )
            }
        }

        /** 사진 한 장을 뺀다. 마지막 한 장은 저장할 때 막으므로 여기서는 지우는 것까지 허용한다. */
        fun onRemovePhoto(ref: String) =
            update {
                copy(
                    photos =
                        photos.filterNot {
                            when (it) {
                                is PostPhotoSource.Saved -> it.url == ref
                                is PostPhotoSource.Picked -> it.uri == ref
                            }
                        },
                    photoError = null,
                    requestError = null,
                )
            }

        // 글자 칸은 등록 화면과 같은 서버 한도(PostTextLimits)에서 잘라 넣는다.
        fun onNameChange(value: String) = update { copy(name = value.limitTo(PostTextLimits.NAME), requestError = null) }

        fun onBreedNameChange(value: String) = update { copy(breedName = value.limitTo(PostTextLimits.BREED_NAME), requestError = null) }

        fun onSexChange(value: AnimalSexOption) = update { copy(sex = value, requestError = null) }

        fun onColorChange(value: String) = update { copy(color = value.limitTo(PostTextLimits.COLOR), requestError = null) }

        fun onEventDateChange(value: String) = update { copy(eventDate = value, eventDateError = null, requestError = null) }

        fun onEventPeriodChange(value: DayPeriod) =
            update { copy(eventTime = eventTime.copy(period = value), eventTimeError = null, requestError = null) }

        fun onEventHourChange(value: String) =
            update {
                copy(
                    eventTime = eventTime.copy(hour = EventTimeInput.digits(value)),
                    eventTimeError = null,
                    requestError = null,
                )
            }

        fun onEventMinuteChange(value: String) =
            update {
                copy(
                    eventTime = eventTime.copy(minute = EventTimeInput.digits(value)),
                    eventTimeError = null,
                    requestError = null,
                )
            }

        /** 분 칸을 떠날 때 한 번 본다 — 치는 중인 `1` 을 곧바로 틀렸다고 하지 않기 위해서다. */
        fun onEventTimeLeave() = update { copy(eventTimeError = eventTime.validationError(if (isSheltering) "발견 시간" else "실종 시간")) }

        fun onEventPlaceChange(value: String) =
            update { copy(eventPlace = value.limitTo(PostTextLimits.PLACE), eventPlaceError = null, requestError = null) }

        fun onCurrentPlaceChange(value: String) =
            update { copy(currentPlace = value.limitTo(PostTextLimits.PLACE), currentPlaceError = null, requestError = null) }

        /**
         * 보호 게시물의 특징은 등록과 같은 한도로 자릅니다.
         *
         * 보낼 때 `현재 보호 상태: …` 한 줄이 앞에 붙으므로 그 몫을 빼 둔 한도를 쓴다
         * ([PostTextLimits.SHELTERING_FEATURE_TEXT]).
         */
        fun onFeatureTextChange(value: String) =
            update {
                copy(
                    featureText =
                        value.limitTo(
                            if (isSheltering) PostTextLimits.SHELTERING_FEATURE_TEXT else PostTextLimits.FEATURE_TEXT,
                        ),
                    requestError = null,
                )
            }

        fun onProtectionStatusChange(value: ProtectionStatusOption) =
            update { copy(protectionStatus = value, protectionStatusError = null, requestError = null) }

        /**
         * 공개 스위치를 다룹니다.
         *
         * `OFF -> ON`은 공개 대상과 범위를 확인해야만 켜지므로 확인 다이얼로그를 먼저 띄우고,
         * `ON -> OFF`는 추가 확인 없이 즉시 반영한다
         * (`docs/post-date-location-policy.md` 5).
         */
        fun onLocationVisibleChange(
            role: PostEditLocationRole,
            isVisible: Boolean,
        ) {
            if (isVisible) {
                update { copy(disclosureConfirmTarget = role, requestError = null) }
                return
            }
            update {
                when (role) {
                    PostEditLocationRole.EVENT -> copy(eventPlaceVisible = false, eventPlaceError = null, requestError = null)
                    PostEditLocationRole.CURRENT -> copy(currentPlaceVisible = false, currentPlaceError = null, requestError = null)
                }
            }
        }

        fun onDisclosureConfirm() {
            val role = mutableUiState.value.disclosureConfirmTarget ?: return
            update {
                when (role) {
                    PostEditLocationRole.EVENT -> copy(eventPlaceVisible = true, disclosureConfirmTarget = null)
                    PostEditLocationRole.CURRENT -> copy(currentPlaceVisible = true, disclosureConfirmTarget = null)
                }
            }
        }

        fun onDisclosureDismiss() = update { copy(disclosureConfirmTarget = null) }

        fun submit() {
            val state = mutableUiState.value
            if (!state.canSubmit) return

            val errors = PostEditInputValidator.validate(state)
            update {
                copy(
                    photoError = errors.photos,
                    protectionStatusError = errors.protectionStatus,
                    eventDateError = errors.eventDate,
                    eventTimeError = errors.eventTime,
                    eventPlaceError = errors.eventPlace,
                    currentPlaceError = errors.currentPlace,
                    requestError = null,
                    versionConflictMessage = null,
                )
            }
            if (errors.hasErrors) return

            viewModelScope.launch {
                update { copy(isSubmitting = true) }
                when (val result = editRepository.updatePost(state.toInput())) {
                    is PostEditResult.Success -> replacePhotosOrFinish(state, result.version)
                    is PostEditResult.InvalidInput -> applyFieldErrors(result)
                    is PostEditResult.VersionConflict ->
                        update { copy(isSubmitting = false, versionConflictMessage = result.message) }
                    is PostEditResult.DisclosureOutdated -> applyDisclosureOutdated(result.message)
                    is PostEditResult.Unauthorized -> update { copy(isSubmitting = false, requestError = result.message) }
                    is PostEditResult.Forbidden -> update { copy(isSubmitting = false, requestError = result.message) }
                    is PostEditResult.NotEditable -> update { copy(isSubmitting = false, requestError = result.message) }
                    is PostEditResult.NotFound -> update { copy(isSubmitting = false, requestError = result.message) }
                    is PostEditResult.Failure -> update { copy(isSubmitting = false, requestError = result.message) }
                }
            }
        }

        /**
         * 사진을 새로 골랐으면 P4가 올린 버전으로 P5를 이어서 호출합니다.
         *
         * 메타데이터는 이미 저장되었으므로 사진 교체만 실패하면 새 버전을 보존해 사진만 다시
         * 시도할 수 있게 한다.
         */
        private suspend fun replacePhotosOrFinish(
            state: PostEditUiState,
            version: Long,
        ) {
            if (!state.replacesPhotos) {
                update { copy(isSubmitting = false, version = version) }
                eventChannel.send(PostEditEvent.Updated(postId))
                return
            }

            val result =
                editRepository.replacePhotos(
                    postId = postId,
                    version = version,
                    photos = state.photos,
                )
            when (result) {
                is PostPhotoReplaceResult.Success -> {
                    update {
                        copy(
                            isSubmitting = false,
                            version = result.version,
                            savedPhotoUrls = result.photos.sortedBy { it.sortOrder }.map { it.url },
                            photos = result.photos.sortedBy { it.sortOrder }.map { PostPhotoSource.Saved(it.url) },
                        )
                    }
                    eventChannel.send(PostEditEvent.Updated(postId))
                }
                is PostPhotoReplaceResult.PhotoInvalid ->
                    update { copy(isSubmitting = false, version = version, photoError = result.message) }
                is PostPhotoReplaceResult.VersionConflict ->
                    update { copy(isSubmitting = false, version = version, versionConflictMessage = result.message) }
                is PostPhotoReplaceResult.Unauthorized ->
                    update { copy(isSubmitting = false, version = version, requestError = result.message) }
                is PostPhotoReplaceResult.Forbidden ->
                    update { copy(isSubmitting = false, version = version, requestError = result.message) }
                is PostPhotoReplaceResult.NotEditable ->
                    update { copy(isSubmitting = false, version = version, requestError = result.message) }
                is PostPhotoReplaceResult.NotFound ->
                    update { copy(isSubmitting = false, version = version, requestError = result.message) }
                is PostPhotoReplaceResult.Failure ->
                    update { copy(isSubmitting = false, version = version, requestError = result.message) }
            }
        }

        private fun applyFieldErrors(result: PostEditResult.InvalidInput) {
            update {
                copy(
                    isSubmitting = false,
                    eventDateError = result.fieldErrors["eventDate"] ?: eventDateError,
                    eventTimeError = result.fieldErrors["eventTime"] ?: eventTimeError,
                    eventPlaceError = result.fieldErrors["eventLocation.exactLocation"] ?: eventPlaceError,
                    currentPlaceError = result.fieldErrors["currentLocation.exactLocation"] ?: currentPlaceError,
                    requestError = result.message,
                )
            }
        }

        /**
         * POST-007입니다. 입력은 보존하고 공개로 바꾼 스위치만 되돌려 안내를 다시 확인하게 한다
         * (`docs/post-date-location-policy.md` 5).
         */
        private fun applyDisclosureOutdated(message: String) {
            update {
                copy(
                    isSubmitting = false,
                    eventPlaceVisible = if (savedEventLocation.isVisible) eventPlaceVisible else false,
                    currentPlaceVisible = if (savedCurrentLocation.isVisible) currentPlaceVisible else false,
                    requestError = message,
                )
            }
        }

        private fun load() {
            update { copy(isLoading = true, loadErrorMessage = null, versionConflictMessage = null) }
            viewModelScope.launch {
                when (val result = detailRepository.getPostDetail(postId)) {
                    is PostDetailResult.Success -> applyDetail(result.detail)
                    is PostDetailResult.NotFound ->
                        update { copy(isLoading = false, loadErrorMessage = result.message) }
                    is PostDetailResult.Failure ->
                        update { copy(isLoading = false, loadErrorMessage = result.message) }
                }
            }
        }

        private fun applyDetail(detail: PostDetailResponse) {
            val rejection = detail.editRejectionMessage()
            if (rejection != null) {
                update { copy(isLoading = false, loadErrorMessage = rejection) }
                return
            }

            savedEventLocation =
                SavedLocation(
                    exactLocation = detail.eventLocation.exactLocation,
                    isVisible = detail.eventLocation.exactLocationVisible == true,
                )
            savedCurrentLocation =
                SavedLocation(
                    exactLocation = detail.currentLocation?.exactLocation,
                    isVisible = detail.currentLocation?.exactLocationVisible == true,
                )

            // 보호 상태는 서버에 칸이 없어 특징 글 맨 앞줄에 실려 온다. 떼어 내야 사용자가 쓴 글만 남는다.
            val savedProtectionStatus = ProtectionStatusLine.statusOf(detail.featureText).orEmpty()
            val savedPhotos = detail.photos.sortedBy { it.sortOrder }.map { it.url }

            update {
                PostEditUiState(
                    isLoading = false,
                    postId = detail.postId,
                    version = detail.version ?: 0L,
                    isSheltering = detail.type == SHELTERING_TYPE,
                    savedPhotoUrls = savedPhotos,
                    photos = savedPhotos.map(PostPhotoSource::Saved),
                    name = detail.name.orEmpty(),
                    species = AnimalSpeciesOption.from(detail.species),
                    breedName = detail.breedName.orEmpty(),
                    sex = AnimalSexOption.from(detail.sex),
                    color = detail.color.orEmpty(),
                    eventDate = detail.eventDate,
                    eventTime = detail.eventTime?.let(EventTimeInput::fromTime24) ?: EventTimeInput(),
                    eventPlace = savedEventLocation.exactLocation.orEmpty(),
                    eventPlaceVisible = savedEventLocation.isVisible,
                    currentPlace = savedCurrentLocation.exactLocation.orEmpty(),
                    currentPlaceVisible = savedCurrentLocation.isVisible,
                    featureText = ProtectionStatusLine.withoutStatus(detail.featureText).orEmpty(),
                    protectionStatus = ProtectionStatusOption.from(savedProtectionStatus),
                    savedProtectionStatusText = savedProtectionStatus,
                )
            }
        }

        /**
         * 본인의 활성 사용자 게시물만 수정할 수 있습니다. 서버도 매 요청마다 같은 조건을
         * 검증하므로 여기서는 화면 진입을 막고 이유를 알린다.
         */
        private fun PostDetailResponse.editRejectionMessage(): String? =
            when {
                source == SHELTER_SOURCE -> "공공 보호동물 정보는 수정할 수 없습니다."
                owner != true -> "본인이 등록한 게시물만 수정할 수 있습니다."
                status != ACTIVE_STATUS -> "종료된 게시물은 수정할 수 없습니다."
                version == null -> "게시물 버전을 확인할 수 없어 수정할 수 없습니다. 잠시 후 다시 시도해 주세요."
                else -> null
            }

        private fun PostEditUiState.toInput(): PostEditInput =
            PostEditInput(
                postId = postId,
                version = version,
                name = name,
                breedName = breedName,
                sex = sex.value,
                color = color,
                eventDate = eventDate,
                eventTime = eventTime.toTime24().orEmpty(),
                // 보호 게시물은 떼어 놨던 보호 상태 줄을 다시 앞에 얹어 보낸다.
                featureText = if (isSheltering) ProtectionStatusLine.join(protectionStatusValue, featureText) else featureText,
                eventLocation =
                    PostEditLocationInput(
                        exactLocation = eventPlace,
                        exactLocationVisible = eventPlaceVisible,
                        savedExactLocation = savedEventLocation.exactLocation,
                        savedExactLocationVisible = savedEventLocation.isVisible,
                    ),
                currentLocation =
                    if (isSheltering) {
                        PostEditLocationInput(
                            exactLocation = currentPlace,
                            exactLocationVisible = currentPlaceVisible,
                            savedExactLocation = savedCurrentLocation.exactLocation,
                            savedExactLocationVisible = savedCurrentLocation.isVisible,
                        )
                    } else {
                        null
                    },
            )

        private fun update(block: PostEditUiState.() -> PostEditUiState) {
            mutableUiState.value = mutableUiState.value.block()
        }

        private data class SavedLocation(
            val exactLocation: String? = null,
            val isVisible: Boolean = false,
        )

        private companion object {
            const val POST_ID_ARG = "postId"
            const val SHELTER_SOURCE = "SHELTER"
            const val SHELTERING_TYPE = "SHELTERING"
            const val ACTIVE_STATUS = "ACTIVE"
        }
    }
