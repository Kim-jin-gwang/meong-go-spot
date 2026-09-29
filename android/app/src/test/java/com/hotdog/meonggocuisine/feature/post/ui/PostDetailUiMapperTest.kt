package com.hotdog.meonggocuisine.feature.post.ui

import com.hotdog.meonggocuisine.feature.post.data.LostReportResponse
import com.hotdog.meonggocuisine.feature.post.data.PostAuthorResponse
import com.hotdog.meonggocuisine.feature.post.data.PostChatResponse
import com.hotdog.meonggocuisine.feature.post.data.PostDetailResponse
import com.hotdog.meonggocuisine.feature.post.data.PostLocationResponse
import com.hotdog.meonggocuisine.feature.post.data.PostPhotoResponse
import com.hotdog.meonggocuisine.feature.post.data.ShelterResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PostDetailUiMapperTest {
    @Test
    fun lostOwnerPostShowsOwnerActions() {
        val uiModel = sampleDetail(owner = true, type = "LOST").toUiModel()

        assertEquals("콩이를 잃어버렸어요", uiModel.title)
        assertEquals("게시물 상세", uiModel.headerTitle)
        assertTrue(uiModel.canAnalyzeMatch)
        assertTrue(uiModel.canEdit)
        assertTrue(uiModel.canClose)
        assertFalse(uiModel.chatAvailable)
    }

    @Test
    fun otherUserActivePostShowsChatOnly() {
        val uiModel = sampleDetail(owner = false, type = "LOST").toUiModel()

        assertFalse(uiModel.canAnalyzeMatch)
        assertFalse(uiModel.canEdit)
        assertFalse(uiModel.canClose)
        assertTrue(uiModel.chatAvailable)
    }

    @Test
    fun shelteringOwnerPostShowsProtectionFieldsAndOwnerHeader() {
        val uiModel = sampleDetail(owner = true, type = "SHELTERING").toUiModel()

        assertEquals("내 게시물 상세", uiModel.headerTitle)
        assertEquals("발견 일시", uiModel.primaryDateLabel)
        assertEquals("발견 지역", uiModel.primaryRegionLabel)
        assertEquals("양호", uiModel.currentStatus)
        assertEquals("본인 집", uiModel.currentPlace)
        assertTrue(uiModel.canEdit)
        assertTrue(uiModel.canClose)
    }

    @Test
    fun publicShelterPostDoesNotShowUserActions() {
        val uiModel = sampleDetail(owner = false, type = "SHELTERING", source = "SHELTER").toUiModel()

        assertEquals(PostDetailSource.SHELTER, uiModel.source)
        assertEquals("마포구 동물보호센터", uiModel.shelterName)
        assertFalse(uiModel.chatAvailable)
        assertFalse(uiModel.canEdit)
        assertFalse(uiModel.canClose)
    }

    @Test
    fun publicShelterPostShowsOfficialContactAndNotice() {
        val uiModel = sampleDetail(owner = false, type = "SHELTERING", source = "SHELTER").toUiModel()

        assertEquals("02-1234-1234", uiModel.shelterPhone)
        assertEquals("서울특별시 마포구 상암동 495", uiModel.shelterAddress)
        assertEquals("서울-마포-2026-00123", uiModel.shelterNoticeNo)
        assertEquals("2026.08.20 ~ 2026.08.30", uiModel.shelterNoticePeriod)
        assertEquals("보호중", uiModel.currentStatus)
    }

    @Test
    fun noticePeriodIsOmittedWhenEitherNoticeDateIsMissing() {
        val response =
            sampleDetail(owner = false, type = "SHELTERING", source = "SHELTER")
                .let { it.copy(shelter = it.shelter?.copy(noticeEndDate = null)) }

        val uiModel = response.toUiModel()

        assertEquals("서울-마포-2026-00123", uiModel.shelterNoticeNo)
        assertNull(uiModel.shelterNoticePeriod)
    }

    @Test
    fun hiddenExactLocationFallsBackToPublicLocation() {
        val uiModel =
            sampleDetail(
                owner = false,
                type = "SHELTERING",
                source = "SHELTER",
                exactLocationVisible = false,
            ).toUiModel()

        assertEquals("서울 마포구", uiModel.primaryRegionValue)
        assertEquals("서울 마포구", uiModel.currentPlace)
    }

    @Test
    fun `공공 분실 신고는 작성자·채팅·수정 없이 관할 기관과 원문 목록 주소를 보여준다`() {
        val response =
            sampleDetail(owner = false, type = "LOST", source = "PUBLIC_LOST").copy(
                author = null,
                chat = null,
                owner = null,
                currentLocation = null,
                report =
                    LostReportResponse(
                        orgName = "대전광역시 유성구",
                        contactNotice = "동물보호관리시스템에서 확인",
                        portalUrl = "https://www.animal.go.kr/front/awtis/loss/lossList.do?menuNo=1000100000&searchUpKindCd=417000",
                    ),
            )
        val uiModel = response.toUiModel()
        assertEquals(PostDetailSource.PUBLIC_LOST, uiModel.source)
        assertEquals(PostDetailType.LOST, uiModel.type)
        assertEquals("대전광역시 유성구", uiModel.reportOrgName)
        assertEquals(
            "https://www.animal.go.kr/front/awtis/loss/lossList.do?menuNo=1000100000&searchUpKindCd=417000",
            uiModel.reportPortalUrl,
        )
        assertEquals(false, uiModel.chatAvailable)
        assertEquals(false, uiModel.canEdit)
        assertEquals(false, uiModel.canAnalyzeMatch)
        assertEquals(null, uiModel.authorName)
    }

    private fun sampleDetail(
        owner: Boolean,
        type: String,
        source: String = "USER_POST",
        exactLocationVisible: Boolean = true,
    ) = PostDetailResponse(
        postId = 1L,
        type = type,
        source = source,
        status = "ACTIVE",
        name = "콩이",
        species = "DOG",
        breedName = "푸들",
        sex = "MALE",
        color = "갈색",
        eventDate = "2026-08-20",
        eventTime = "15:30:00",
        featureText = "현재 보호 상태: 양호\n갈색 목줄을 착용하고 있어요",
        eventLocation =
            PostLocationResponse(
                regionCode = "11440",
                publicLocation = "서울 마포구",
                exactLocation = "서울 마포구",
                exactLocationVisible = exactLocationVisible,
            ),
        currentLocation =
            PostLocationResponse(
                regionCode = "11440",
                publicLocation = "서울 마포구",
                exactLocation = "본인 집",
                exactLocationVisible = exactLocationVisible,
            ),
        photos = listOf(PostPhotoResponse(photoId = 1L, url = "sample://photo/", sortOrder = 1)),
        author = PostAuthorResponse(memberId = 1L, nickname = "콩이주인"),
        chat = PostChatResponse(available = true),
        owner = owner,
        shelter =
            if (source == "SHELTER") {
                ShelterResponse(
                    name = "마포구 동물보호센터",
                    phone = "02-1234-1234",
                    address = "서울특별시 마포구 상암동 495",
                    noticeNo = "서울-마포-2026-00123",
                    noticeStartDate = "2026-08-20",
                    noticeEndDate = "2026-08-30",
                    processState = "보호중",
                )
            } else {
                null
            },
        createdAt = "2026-08-20T15:30:00",
    )
}
