package com.hotdog.meonggocuisine.feature.post.ui.edit

import com.hotdog.meonggocuisine.feature.post.data.PostPhotoSource
import com.hotdog.meonggocuisine.feature.report.ui.EventTimeInput
import com.hotdog.meonggocuisine.feature.report.ui.sheltering.ProtectionStatusOption

data class PostEditUiState(
    val isLoading: Boolean = true,
    val loadErrorMessage: String? = null,
    val postId: Long = 0L,
    val version: Long = 0L,
    val isSheltering: Boolean = false,
    /** 서버에 저장돼 있는 사진 — 바뀐 게 있는지 판단하는 기준. */
    val savedPhotoUrls: List<String> = emptyList(),
    /** 저장하면 이대로 남을 사진. 지우고 더한 결과가 여기 쌓인다. */
    val photos: List<PostPhotoSource> = emptyList(),
    val name: String = "",
    val species: AnimalSpeciesOption = AnimalSpeciesOption.DOG,
    val breedName: String = "",
    val sex: AnimalSexOption = AnimalSexOption.MALE,
    val color: String = "",
    val eventDate: String = "",
    val eventTime: EventTimeInput = EventTimeInput(),
    val eventPlace: String = "",
    val eventPlaceVisible: Boolean = false,
    val currentPlace: String = "",
    val currentPlaceVisible: Boolean = false,
    val featureText: String = "",
    val protectionStatus: ProtectionStatusOption? = null,
    /** 서버에 저장돼 있던 보호 상태 글자. 목록에 없는 예전 값이면 고를 때까지 이게 값이다. */
    val savedProtectionStatusText: String = "",
    val photoError: String? = null,
    val protectionStatusError: String? = null,
    val eventDateError: String? = null,
    val eventTimeError: String? = null,
    val eventPlaceError: String? = null,
    val currentPlaceError: String? = null,
    val requestError: String? = null,
    val versionConflictMessage: String? = null,
    val disclosureConfirmTarget: PostEditLocationRole? = null,
    val isSubmitting: Boolean = false,
) {
    /** 동물 종류는 서버가 변경을 허용하지 않아 화면에서 읽기만 한다. */
    val speciesLabel: String get() = species.label

    /**
     * 서버로 보낼 보호 상태 글자입니다.
     *
     * 선택지에 없는 예전 값은 사용자가 새로 고르기 전까지 그대로 둔다. 목록에 없다고 지워 버리면
     * 수정할 생각이 없던 칸이 저장 한 번에 비워진다.
     */
    val protectionStatusValue: String get() = protectionStatus?.label ?: savedProtectionStatusText

    /** 선택지 어디에도 안 맞는 저장 값. 아무것도 안 골라져 있는 이유를 화면이 알려 줄 때 쓴다. */
    val unlistedProtectionStatus: String? get() = savedProtectionStatusText.takeIf { it.isNotBlank() && protectionStatus == null }

    /** 화면에 있는 사진을 가리키는 글자. 목록 부품이 저장된 사진과 새 사진을 같은 줄에 그린다. */
    val photoRefs: List<String>
        get() =
            photos.map {
                when (it) {
                    is PostPhotoSource.Saved -> it.url
                    is PostPhotoSource.Picked -> it.uri
                }
            }

    /** 사진을 한 장도 건드리지 않았으면 P5를 호출하지 않는다 — 부르면 남긴 사진까지 다시 올라간다. */
    val replacesPhotos: Boolean get() = photoRefs != savedPhotoUrls

    val canSubmit: Boolean get() = !isSubmitting && !isLoading && loadErrorMessage == null

    val isVersionConflicted: Boolean get() = versionConflictMessage != null
}

/** 위치 역할입니다. 공개 확인 다이얼로그가 어느 스위치에서 열렸는지 구분한다. */
enum class PostEditLocationRole(
    val label: String,
) {
    EVENT("사건 장소"),
    CURRENT("현재 보호 장소"),
}

enum class AnimalSpeciesOption(
    val value: String,
    val label: String,
) {
    DOG("DOG", "강아지"),
    CAT("CAT", "고양이"),
    ;

    companion object {
        fun from(value: String?): AnimalSpeciesOption = entries.firstOrNull { it.value == value } ?: DOG
    }
}

enum class AnimalSexOption(
    val value: String,
    val label: String,
) {
    MALE("MALE", "수컷"),
    FEMALE("FEMALE", "암컷"),
    UNKNOWN("UNKNOWN", "모름"),
    ;

    companion object {
        fun from(value: String?): AnimalSexOption = entries.firstOrNull { it.value == value } ?: UNKNOWN
    }
}
