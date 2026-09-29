package com.hotdog.meonggocuisine.feature.post.ui.edit

import androidx.lifecycle.SavedStateHandle
import com.hotdog.meonggocuisine.feature.post.data.PostCloseReason
import com.hotdog.meonggocuisine.feature.post.data.PostCloseResult
import com.hotdog.meonggocuisine.feature.post.data.PostDetailRepository
import com.hotdog.meonggocuisine.feature.post.data.PostDetailResponse
import com.hotdog.meonggocuisine.feature.post.data.PostDetailResult
import com.hotdog.meonggocuisine.feature.post.data.PostEditInput
import com.hotdog.meonggocuisine.feature.post.data.PostEditRepository
import com.hotdog.meonggocuisine.feature.post.data.PostEditResult
import com.hotdog.meonggocuisine.feature.post.data.PostLocationResponse
import com.hotdog.meonggocuisine.feature.post.data.PostPhotoReplaceResult
import com.hotdog.meonggocuisine.feature.post.data.PostPhotoResponse
import com.hotdog.meonggocuisine.feature.post.data.PostPhotoSource
import com.hotdog.meonggocuisine.feature.report.ui.DayPeriod
import com.hotdog.meonggocuisine.feature.report.ui.EventTimeInput
import com.hotdog.meonggocuisine.feature.report.ui.sheltering.ProtectionStatusOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PostEditViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `기존 값과 사진을 폼에 채운다`() =
        runTest(dispatcher) {
            val viewModel = viewModel(FakeDetailRepository(detail()), FakeEditRepository())

            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.isLoading)
            assertEquals("콩이", state.name)
            assertEquals("푸들", state.breedName)
            assertEquals(AnimalSexOption.MALE, state.sex)
            assertEquals("2026-08-20", state.eventDate)
            assertEquals(EventTimeInput(period = DayPeriod.PM, hour = "8", minute = "00"), state.eventTime)
            assertEquals("역삼역 3번 출구 인근", state.eventPlace)
            assertTrue(state.eventPlaceVisible)
            assertEquals(listOf("/api/v1/photos/1", "/api/v1/photos/2"), state.savedPhotoUrls)
            assertEquals(3L, state.version)
        }

    @Test
    fun `글자 칸은 서버 한도에서 잘라 넣는다`() =
        runTest(dispatcher) {
            val viewModel = viewModel(FakeDetailRepository(detail()), FakeEditRepository())
            advanceUntilIdle()

            viewModel.onNameChange("가".repeat(60))
            viewModel.onEventPlaceChange("나".repeat(250))
            viewModel.onFeatureTextChange("다".repeat(2500))

            assertEquals(50, viewModel.uiState.value.name.length)
            assertEquals(200, viewModel.uiState.value.eventPlace.length)
            assertEquals(2000, viewModel.uiState.value.featureText.length)
        }

    @Test
    fun `본인 게시물이 아니면 수정 화면을 열지 않는다`() =
        runTest(dispatcher) {
            val viewModel = viewModel(FakeDetailRepository(detail(owner = false)), FakeEditRepository())

            advanceUntilIdle()

            assertEquals("본인이 등록한 게시물만 수정할 수 있습니다.", viewModel.uiState.value.loadErrorMessage)
            assertFalse(viewModel.uiState.value.canSubmit)
        }

    @Test
    fun `종료된 게시물은 수정할 수 없다`() =
        runTest(dispatcher) {
            val viewModel = viewModel(FakeDetailRepository(detail(status = "CLOSED")), FakeEditRepository())

            advanceUntilIdle()

            assertEquals("종료된 게시물은 수정할 수 없습니다.", viewModel.uiState.value.loadErrorMessage)
        }

    @Test
    fun `공공 보호동물은 수정할 수 없다`() =
        runTest(dispatcher) {
            val viewModel = viewModel(FakeDetailRepository(detail(source = "SHELTER")), FakeEditRepository())

            advanceUntilIdle()

            assertEquals("공공 보호동물 정보는 수정할 수 없습니다.", viewModel.uiState.value.loadErrorMessage)
        }

    @Test
    fun `공개를 켜기 전에 확인 다이얼로그를 띄운다`() =
        runTest(dispatcher) {
            val viewModel = viewModel(FakeDetailRepository(detail(eventVisible = false)), FakeEditRepository())
            advanceUntilIdle()

            viewModel.onLocationVisibleChange(PostEditLocationRole.EVENT, true)

            assertEquals(PostEditLocationRole.EVENT, viewModel.uiState.value.disclosureConfirmTarget)
            assertFalse(viewModel.uiState.value.eventPlaceVisible)

            viewModel.onDisclosureConfirm()

            assertNull(viewModel.uiState.value.disclosureConfirmTarget)
            assertTrue(viewModel.uiState.value.eventPlaceVisible)
        }

    @Test
    fun `공개를 끌 때는 확인 없이 바로 반영한다`() =
        runTest(dispatcher) {
            val viewModel = viewModel(FakeDetailRepository(detail()), FakeEditRepository())
            advanceUntilIdle()

            viewModel.onLocationVisibleChange(PostEditLocationRole.EVENT, false)

            assertNull(viewModel.uiState.value.disclosureConfirmTarget)
            assertFalse(viewModel.uiState.value.eventPlaceVisible)
        }

    @Test
    fun `저장 요청에 상세의 버전을 담는다`() =
        runTest(dispatcher) {
            val editRepository = FakeEditRepository()
            val viewModel = viewModel(FakeDetailRepository(detail()), editRepository)
            advanceUntilIdle()

            viewModel.onNameChange("콩순이")
            viewModel.submit()
            advanceUntilIdle()

            assertEquals(3L, editRepository.lastInput?.version)
            assertEquals("콩순이", editRepository.lastInput?.name)
            assertEquals(POST_ID, editRepository.lastInput?.postId)
        }

    @Test
    fun `사진을 새로 고르지 않으면 사진 교체를 요청하지 않는다`() =
        runTest(dispatcher) {
            val editRepository = FakeEditRepository()
            val viewModel = viewModel(FakeDetailRepository(detail()), editRepository)
            advanceUntilIdle()

            viewModel.submit()
            advanceUntilIdle()

            assertEquals(1, editRepository.updateCount)
            assertEquals(0, editRepository.replaceCount)
        }

    @Test
    fun `사진을 새로 고르면 수정이 올린 버전으로 교체를 요청한다`() =
        runTest(dispatcher) {
            val editRepository = FakeEditRepository(updatedVersion = 4L)
            val viewModel = viewModel(FakeDetailRepository(detail()), editRepository)
            advanceUntilIdle()

            viewModel.onAddPhotos(listOf("content://new/1", "content://new/2"))
            viewModel.submit()
            advanceUntilIdle()

            // 남긴 사진과 더한 사진을 합쳐 최종 전체를 보낸다 — 서버가 부분 교체를 받지 않는다.
            assertEquals(1, editRepository.replaceCount)
            assertEquals(4L, editRepository.lastReplaceVersion)
            assertEquals(
                listOf("/api/v1/photos/1", "/api/v1/photos/2", "content://new/1", "content://new/2"),
                editRepository.lastReplaceUris,
            )
        }

    @Test
    fun `수정에 실패하면 입력을 유지하고 오류만 알린다`() =
        runTest(dispatcher) {
            val editRepository =
                FakeEditRepository(updateResult = PostEditResult.Failure("게시물 수정 중 오류가 발생했습니다."))
            val viewModel = viewModel(FakeDetailRepository(detail()), editRepository)
            advanceUntilIdle()

            viewModel.onNameChange("콩순이")
            viewModel.onFeatureTextChange("빨간 목줄")
            viewModel.submit()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals("콩순이", state.name)
            assertEquals("빨간 목줄", state.featureText)
            assertEquals("게시물 수정 중 오류가 발생했습니다.", state.requestError)
            assertFalse(state.isSubmitting)
        }

    @Test
    fun `버전 충돌은 별도 안내로 알리고 새로고침으로 최신 값을 받는다`() =
        runTest(dispatcher) {
            val editRepository =
                FakeEditRepository(
                    updateResult = PostEditResult.VersionConflict("게시물이 변경되었습니다. 최신 내용을 다시 확인해 주세요."),
                )
            val detailRepository = FakeDetailRepository(detail())
            val viewModel = viewModel(detailRepository, editRepository)
            advanceUntilIdle()

            viewModel.onNameChange("콩순이")
            viewModel.submit()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isVersionConflicted)
            assertEquals("콩순이", viewModel.uiState.value.name)

            detailRepository.detail = detail(version = 9L, name = "콩이")
            viewModel.refresh()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isVersionConflicted)
            assertEquals(9L, viewModel.uiState.value.version)
            assertEquals("콩이", viewModel.uiState.value.name)
            assertEquals(2, detailRepository.loadCount)
        }

    @Test
    fun `공개 동의 안내가 최신이 아니면 켠 스위치를 되돌린다`() =
        runTest(dispatcher) {
            val editRepository =
                FakeEditRepository(
                    updateResult = PostEditResult.DisclosureOutdated("최신 공개 동의 내용을 확인해 주세요."),
                )
            val viewModel = viewModel(FakeDetailRepository(detail(eventVisible = false)), editRepository)
            advanceUntilIdle()

            viewModel.onEventPlaceChange("역삼역 3번 출구 인근")
            viewModel.onLocationVisibleChange(PostEditLocationRole.EVENT, true)
            viewModel.onDisclosureConfirm()
            viewModel.submit()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.eventPlaceVisible)
            assertEquals("역삼역 3번 출구 인근", state.eventPlace)
            assertEquals("최신 공개 동의 내용을 확인해 주세요.", state.requestError)
        }

    @Test
    fun `공개를 켰는데 정확한 위치가 비면 요청하지 않는다`() =
        runTest(dispatcher) {
            val editRepository = FakeEditRepository()
            val viewModel = viewModel(FakeDetailRepository(detail(eventVisible = false, exactLocation = null)), editRepository)
            advanceUntilIdle()

            viewModel.onLocationVisibleChange(PostEditLocationRole.EVENT, true)
            viewModel.onDisclosureConfirm()
            viewModel.submit()
            advanceUntilIdle()

            assertEquals(0, editRepository.updateCount)
            assertNotNull(viewModel.uiState.value.eventPlaceError)
        }

    @Test
    fun `미래 날짜는 요청하지 않고 날짜 오류로 알린다`() =
        runTest(dispatcher) {
            val editRepository = FakeEditRepository()
            val viewModel = viewModel(FakeDetailRepository(detail()), editRepository)
            advanceUntilIdle()

            viewModel.onEventDateChange("2099-01-01")
            viewModel.submit()
            advanceUntilIdle()

            assertEquals(0, editRepository.updateCount)
            assertEquals("내일 이후 날짜는 선택할 수 없습니다.", viewModel.uiState.value.eventDateError)
        }

    @Test
    fun `보호 상태 줄은 특징에서 떼어 내고 선택으로 채운다`() =
        runTest(dispatcher) {
            val viewModel = viewModel(FakeDetailRepository(shelteringDetail()), FakeEditRepository())
            advanceUntilIdle()

            // 사용자가 쓴 적 없는 줄이 특징 칸에 남아 있으면 지우거나 고치다가 보호 상태가 깨진다.
            assertEquals("빨간 목줄", viewModel.uiState.value.featureText)
            assertEquals(ProtectionStatusOption.TEMPORARY, viewModel.uiState.value.protectionStatus)
        }

    @Test
    fun `저장할 때 고른 보호 상태를 특징 맨 앞줄로 다시 붙인다`() =
        runTest(dispatcher) {
            val editRepository = FakeEditRepository()
            val viewModel = viewModel(FakeDetailRepository(shelteringDetail()), editRepository)
            advanceUntilIdle()

            viewModel.onProtectionStatusChange(ProtectionStatusOption.HOSPITAL)
            viewModel.submit()
            advanceUntilIdle()

            assertEquals("현재 보호 상태: 병원\n빨간 목줄", editRepository.lastInput?.featureText)
        }

    @Test
    fun `보호 상태를 고르지 않으면 요청하지 않는다`() =
        runTest(dispatcher) {
            val editRepository = FakeEditRepository()
            val viewModel = viewModel(FakeDetailRepository(shelteringDetail(featureText = "빨간 목줄")), editRepository)
            advanceUntilIdle()

            viewModel.submit()
            advanceUntilIdle()

            assertEquals(0, editRepository.updateCount)
            assertEquals("현재 보호 상태를 선택해 주세요.", viewModel.uiState.value.protectionStatusError)
        }

    /** 목록에 없는 예전 값은 고르기 전까지 그대로 둔다 — 저장 한 번에 비워지면 안 된다. */
    @Test
    fun `선택지에 없는 예전 보호 상태는 그대로 다시 보낸다`() =
        runTest(dispatcher) {
            val editRepository = FakeEditRepository()
            val detailRepository = FakeDetailRepository(shelteringDetail(featureText = "현재 보호 상태: 임시 보호중\n빨간 목줄"))
            val viewModel = viewModel(detailRepository, editRepository)
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.protectionStatus)
            assertEquals("임시 보호중", viewModel.uiState.value.unlistedProtectionStatus)

            viewModel.submit()
            advanceUntilIdle()

            assertEquals("현재 보호 상태: 임시 보호중\n빨간 목줄", editRepository.lastInput?.featureText)
        }

    @Test
    fun `보호 게시물은 현재 보호 장소도 함께 보낸다`() =
        runTest(dispatcher) {
            val editRepository = FakeEditRepository()
            val viewModel = viewModel(FakeDetailRepository(shelteringDetail()), editRepository)
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isSheltering)
            assertEquals("은평구 갈현동 자택", viewModel.uiState.value.currentPlace)

            viewModel.submit()
            advanceUntilIdle()

            assertEquals("은평구 갈현동 자택", editRepository.lastInput?.currentLocation?.exactLocation)
        }

    @Test
    fun `실종 게시물은 현재 보호 장소를 보내지 않는다`() =
        runTest(dispatcher) {
            val editRepository = FakeEditRepository()
            val viewModel = viewModel(FakeDetailRepository(detail()), editRepository)
            advanceUntilIdle()

            viewModel.submit()
            advanceUntilIdle()

            assertNull(editRepository.lastInput?.currentLocation)
        }

    @Test
    fun `사진 교체만 실패하면 올라간 버전을 유지한다`() =
        runTest(dispatcher) {
            val editRepository =
                FakeEditRepository(
                    updatedVersion = 4L,
                    replaceResult = PostPhotoReplaceResult.PhotoInvalid("JPEG 또는 PNG 사진만 등록할 수 있습니다."),
                )
            val viewModel = viewModel(FakeDetailRepository(detail()), editRepository)
            advanceUntilIdle()

            viewModel.onAddPhotos(listOf("content://new/1"))
            viewModel.submit()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(4L, state.version)
            assertEquals("JPEG 또는 PNG 사진만 등록할 수 있습니다.", state.photoError)
            assertTrue(state.replacesPhotos)
        }

    @Test
    fun `사진을 지웠다가 되돌리면 교체를 요청하지 않는다`() =
        runTest(dispatcher) {
            val editRepository = FakeEditRepository()
            val viewModel = viewModel(FakeDetailRepository(detail()), editRepository)
            advanceUntilIdle()

            viewModel.onRemovePhoto("/api/v1/photos/2")
            assertTrue(viewModel.uiState.value.replacesPhotos)

            viewModel.onAddPhotos(listOf("/api/v1/photos/2"))
            // 지웠다 그대로 넣으면 순서까지 원래와 같아져 올릴 게 없다.
            assertFalse(viewModel.uiState.value.replacesPhotos)

            viewModel.submit()
            advanceUntilIdle()

            assertEquals(0, editRepository.replaceCount)
        }

    @Test
    fun `사진을 한 장도 남기지 않으면 요청하지 않는다`() =
        runTest(dispatcher) {
            val editRepository = FakeEditRepository()
            val viewModel = viewModel(FakeDetailRepository(detail()), editRepository)
            advanceUntilIdle()

            viewModel.uiState.value.photoRefs.forEach(viewModel::onRemovePhoto)
            viewModel.submit()
            advanceUntilIdle()

            assertEquals(0, editRepository.updateCount)
            assertEquals("사진을 1장 이상 남겨 주세요.", viewModel.uiState.value.photoError)
        }

    private fun viewModel(
        detailRepository: PostDetailRepository,
        editRepository: PostEditRepository,
    ) = PostEditViewModel(
        savedStateHandle = SavedStateHandle(mapOf("postId" to POST_ID)),
        detailRepository = detailRepository,
        editRepository = editRepository,
    )

    private fun detail(
        owner: Boolean = true,
        status: String = "ACTIVE",
        source: String = "USER_POST",
        version: Long? = 3L,
        name: String? = "콩이",
        eventVisible: Boolean = true,
        exactLocation: String? = "역삼역 3번 출구 인근",
    ) = PostDetailResponse(
        postId = POST_ID,
        type = "LOST",
        source = source,
        status = status,
        version = version,
        name = name,
        species = "DOG",
        breedName = "푸들",
        sex = "MALE",
        color = "갈색",
        eventDate = "2026-08-20",
        eventTime = "20:00:00",
        featureText = "빨간 목줄",
        eventLocation =
            PostLocationResponse(
                regionCode = "11680",
                publicLocation = "서울 강남구 역삼동",
                exactLocation = exactLocation,
                exactLocationVisible = eventVisible,
            ),
        photos =
            listOf(
                PostPhotoResponse(photoId = 1L, url = "/api/v1/photos/1", sortOrder = 0),
                PostPhotoResponse(photoId = 2L, url = "/api/v1/photos/2", sortOrder = 1),
            ),
        owner = owner,
        createdAt = "2026-08-20T11:00:00Z",
    )

    private fun shelteringDetail(featureText: String? = "현재 보호 상태: 임시보호\n빨간 목줄") =
        detail().copy(
            type = "SHELTERING",
            name = null,
            featureText = featureText,
            currentLocation =
                PostLocationResponse(
                    regionCode = "11380",
                    publicLocation = "서울 은평구 갈현동",
                    exactLocation = "은평구 갈현동 자택",
                    exactLocationVisible = false,
                ),
        )

    private class FakeDetailRepository(
        var detail: PostDetailResponse,
    ) : PostDetailRepository {
        var loadCount = 0

        override suspend fun getPostDetail(postId: Long): PostDetailResult {
            loadCount += 1
            return PostDetailResult.Success(detail)
        }
    }

    private class FakeEditRepository(
        private val updatedVersion: Long = 4L,
        private val updateResult: PostEditResult? = null,
        private val replaceResult: PostPhotoReplaceResult? = null,
    ) : PostEditRepository {
        var updateCount = 0
        var replaceCount = 0
        var lastInput: PostEditInput? = null
        var lastReplaceVersion: Long? = null
        var lastReplaceUris: List<String>? = null

        override suspend fun updatePost(input: PostEditInput): PostEditResult {
            updateCount += 1
            lastInput = input
            return updateResult ?: PostEditResult.Success(updatedVersion)
        }

        override suspend fun replacePhotos(
            postId: Long,
            version: Long,
            photos: List<PostPhotoSource>,
        ): PostPhotoReplaceResult {
            replaceCount += 1
            lastReplaceVersion = version
            lastReplaceUris =
                photos.map {
                    when (it) {
                        is PostPhotoSource.Saved -> it.url
                        is PostPhotoSource.Picked -> it.uri
                    }
                }
            return replaceResult
                ?: PostPhotoReplaceResult.Success(
                    version = version + 1,
                    photos = listOf(PostPhotoResponse(photoId = 9L, url = "/api/v1/photos/9", sortOrder = 0)),
                )
        }

        override suspend fun closePost(
            postId: Long,
            version: Long,
            reason: PostCloseReason,
        ): PostCloseResult = error("이 테스트에서 사용하지 않는다")
    }

    private companion object {
        const val POST_ID = 1002L
    }
}
