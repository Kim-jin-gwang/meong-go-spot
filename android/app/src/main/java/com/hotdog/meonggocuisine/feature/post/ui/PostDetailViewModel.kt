package com.hotdog.meonggocuisine.feature.post.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hotdog.meonggocuisine.core.text.ProtectionStatusLine
import com.hotdog.meonggocuisine.core.text.stripSpeciesPrefix
import com.hotdog.meonggocuisine.feature.post.data.PostDetailRepository
import com.hotdog.meonggocuisine.feature.post.data.PostDetailResponse
import com.hotdog.meonggocuisine.feature.post.data.PostDetailResult
import com.hotdog.meonggocuisine.feature.post.data.PostLocationResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PostDetailViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val repository: PostDetailRepository,
    ) : ViewModel() {
        // 이 상세는 두 길로 열린다 — 목록에서 화면으로(PostDetailRoute), 소개팅에서 카드 위 팝업으로
        // (AdoptionAnimalDetailRoute). 둘은 route 타입만 다르고 인자는 같은 postId 하나라, route 를
        // 통째로 읽는 대신 그 값만 꺼낸다(2026-09-24).
        private val postId: Long = requireNotNull(savedStateHandle["postId"])
        private val mutableUiState = MutableStateFlow(PostDetailUiState(isLoading = true))
        val uiState: StateFlow<PostDetailUiState> = mutableUiState.asStateFlow()

        init {
            load()
        }

        fun retry() = load()

        private fun load() {
            viewModelScope.launch {
                mutableUiState.value = PostDetailUiState(isLoading = true)
                mutableUiState.value =
                    when (val result = repository.getPostDetail(postId)) {
                        is PostDetailResult.Success -> PostDetailUiState(detail = result.detail.toUiModel())
                        is PostDetailResult.NotFound -> PostDetailUiState(errorMessage = result.message)
                        is PostDetailResult.Failure -> PostDetailUiState(errorMessage = result.message)
                    }
            }
        }
    }

data class PostDetailUiState(
    val isLoading: Boolean = false,
    val detail: PostDetailUiModel? = null,
    val errorMessage: String? = null,
)

data class PostDetailUiModel(
    val postId: Long,
    val title: String,
    val headerTitle: String,
    /** 분석 화면 문구에 쓰는 동물 이름입니다. 등록하지 않았으면 null입니다. */
    val animalName: String?,
    /** 사진이 없을 때 자리표시자 그림을 고르는 축종(DOG·CAT·OTHER). */
    val species: String,
    /** 품종. 등록하지 않았으면 null 이라 그 칩을 띄우지 않는다. */
    val breedName: String?,
    /** 성별(MALE·FEMALE·UNKNOWN). 목록과 같은 말로 칩에 쓴다. */
    val sex: String,
    val type: PostDetailType,
    val source: PostDetailSource,
    val status: String,
    val owner: Boolean,
    /** 올린 순서대로의 사진 전부. 상세가 옆으로 넘겨 보여 준다. */
    val photoUrls: List<String>,
    val authorName: String?,
    val shelterName: String?,
    val shelterPhone: String?,
    val shelterAddress: String?,
    val shelterNoticeNo: String?,
    val shelterNoticePeriod: String?,
    /** 공공 분실 신고의 관할 기관과 원문 목록 주소 (source=PUBLIC_LOST 에서만). */
    val reportOrgName: String?,
    val reportPortalUrl: String?,
    val primaryDateLabel: String,
    val primaryDateValue: String,
    val primaryRegionLabel: String,
    val primaryRegionValue: String,
    val currentStatus: String?,
    val currentPlace: String?,
    val featureText: String?,
    val chatAvailable: Boolean,
    val canAnalyzeMatch: Boolean,
    val canEdit: Boolean,
    val canClose: Boolean,
)

enum class PostDetailType { LOST, SHELTERING }

enum class PostDetailSource { USER_POST, SHELTER, PUBLIC_LOST }

internal fun PostDetailResponse.toUiModel(): PostDetailUiModel {
    val detailType = if (type == "SHELTERING") PostDetailType.SHELTERING else PostDetailType.LOST
    val detailSource =
        when (source) {
            "SHELTER" -> PostDetailSource.SHELTER
            "PUBLIC_LOST" -> PostDetailSource.PUBLIC_LOST
            else -> PostDetailSource.USER_POST
        }
    val isOwner = owner == true
    val isActive = status == "ACTIVE"
    val displayName = name?.takeIf(String::isNotBlank) ?: defaultName(detailType)
    val orderedPhotos = photos.sortedBy { it.sortOrder }.map { it.url }
    val ownerTitle = if (isOwner && detailType == PostDetailType.SHELTERING) "내 게시물 상세" else "게시물 상세"
    return PostDetailUiModel(
        postId = postId,
        title = detailTitle(displayName, detailType),
        headerTitle = ownerTitle,
        animalName = name?.takeIf(String::isNotBlank),
        species = species,
        breedName = breedName?.takeIf(String::isNotBlank)?.let(::stripSpeciesPrefix),
        sex = sex,
        type = detailType,
        source = detailSource,
        status = status,
        owner = isOwner,
        photoUrls = orderedPhotos,
        authorName = author?.nickname,
        shelterName = shelter?.name,
        shelterPhone = shelter?.phone,
        shelterAddress = shelter?.address,
        shelterNoticeNo = shelter?.noticeNo,
        shelterNoticePeriod = noticePeriod(shelter?.noticeStartDate, shelter?.noticeEndDate),
        reportOrgName = report?.orgName,
        reportPortalUrl = report?.portalUrl,
        primaryDateLabel = if (detailType == PostDetailType.LOST) "실종 일시" else "발견 일시",
        primaryDateValue = formatDateTime(eventDate, eventTime),
        primaryRegionLabel = if (detailType == PostDetailType.LOST) "실종 지역" else "발견 지역",
        primaryRegionValue = eventLocation.displayLocation(),
        currentStatus =
            if (detailType == PostDetailType.SHELTERING) {
                shelter?.processState ?: ProtectionStatusLine.statusOf(featureText)
            } else {
                null
            },
        currentPlace =
            if (detailType == PostDetailType.SHELTERING) {
                currentLocation?.displayLocation()
            } else {
                null
            },
        featureText = ProtectionStatusLine.withoutStatus(featureText),
        chatAvailable = chat?.available == true && !isOwner && isActive && detailSource == PostDetailSource.USER_POST,
        canAnalyzeMatch = isOwner && isActive && detailType == PostDetailType.LOST,
        canEdit = isOwner && isActive && detailSource == PostDetailSource.USER_POST,
        canClose = isOwner && isActive && detailSource == PostDetailSource.USER_POST,
    )
}

private fun detailTitle(
    name: String,
    type: PostDetailType,
): String = if (type == PostDetailType.LOST) "${name}를 잃어버렸어요" else "${name}를 보호하고 있어요"

private fun defaultName(type: PostDetailType): String = if (type == PostDetailType.LOST) "아이" else "동물"

/**
 * 화면에 표시할 위치입니다.
 *
 * 정확한 위치는 서버가 공개 조건을 만족시킨 경우에만 사용하고, 그 밖에는 공개 지역을 사용합니다.
 * 공공 보호동물처럼 정확한 위치를 공개하지 않는 게시물에서 값이 섞여 내려와도 노출하지 않습니다.
 */
private fun PostLocationResponse.displayLocation(): String =
    exactLocation?.takeIf { exactLocationVisible == true && it.isNotBlank() } ?: publicLocation

/** 공공 보호동물의 공고 기간입니다. 시작일과 종료일이 모두 있을 때만 만듭니다. */
private fun noticePeriod(
    startDate: String?,
    endDate: String?,
): String? {
    if (startDate.isNullOrBlank() || endDate.isNullOrBlank()) return null
    return "${startDate.replace("-", ".")} ~ ${endDate.replace("-", ".")}"
}

private fun formatDateTime(
    date: String,
    time: String?,
): String {
    val displayDate = date.replace("-", ".")
    val displayTime = time?.take(5)?.toDisplayTime()
    return listOf(displayDate, displayTime).filterNotNull().joinToString(" ")
}

private fun String.toDisplayTime(): String {
    val parts = split(":")
    val hour = parts.getOrNull(0)?.toIntOrNull() ?: return this
    val minute = parts.getOrNull(1) ?: return this
    val period = if (hour < 12) "오전" else "오후"
    val displayHour =
        when (val value = hour % 12) {
            0 -> 12
            else -> value
        }
    return "$period $displayHour:$minute"
}
