package com.hotdog.meonggocuisine.feature.post.data

import kotlinx.serialization.Serializable

@Serializable
data class PostDetailResponse(
    val postId: Long,
    val type: String,
    val source: String,
    val status: String,
    val version: Long? = null,
    val name: String? = null,
    val species: String,
    val breedName: String? = null,
    val sex: String,
    val color: String? = null,
    val eventDate: String,
    val eventTime: String? = null,
    val featureText: String? = null,
    val eventLocation: PostLocationResponse,
    val currentLocation: PostLocationResponse? = null,
    val photos: List<PostPhotoResponse> = emptyList(),
    val author: PostAuthorResponse? = null,
    val chat: PostChatResponse? = null,
    val owner: Boolean? = null,
    val shelter: ShelterResponse? = null,
    /** 공공 분실 신고(source=PUBLIC_LOST)만 온다. 신고자 연락처는 서버가 저장하지 않아 없다. */
    val report: LostReportResponse? = null,
    val createdAt: String,
    val updatedAt: String? = null,
)

@Serializable
data class PostLocationResponse(
    val regionCode: String,
    val emdCode: String? = null,
    val publicLocation: String,
    val exactLocation: String? = null,
    val exactLocationVisible: Boolean? = null,
)

@Serializable
data class PostPhotoResponse(
    val photoId: Long,
    val url: String,
    val sortOrder: Int,
)

@Serializable
data class LostReportResponse(
    val orgName: String? = null,
    val happenPlace: String? = null,
    val rfidCode: String? = null,
    val firstSeenDate: String? = null,
    val lastSeenDate: String? = null,
    val contactNotice: String? = null,
    /** 포털 분실동물 게시판(실종일·축종으로 좁힌 목록). 건별 상세 링크는 원천에 식별자가 없어 만들 수 없다. */
    val portalUrl: String? = null,
)

@Serializable
data class PostAuthorResponse(
    val memberId: Long,
    val nickname: String,
)

@Serializable
data class PostChatResponse(
    val available: Boolean,
    val reason: String? = null,
)

@Serializable
data class ShelterResponse(
    val name: String,
    val phone: String? = null,
    val address: String? = null,
    val jurisdiction: String? = null,
    val noticeNo: String? = null,
    val noticeStartDate: String? = null,
    val noticeEndDate: String? = null,
    val processState: String? = null,
)
