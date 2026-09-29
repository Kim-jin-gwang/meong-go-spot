package com.hotdog.meonggocuisine.feature.post.ui.close

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
import com.hotdog.meonggocuisine.feature.post.data.PostPhotoSource
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PostCloseViewModelTest {
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
    fun `종료할 게시물 이름과 버전을 담는다`() =
        runTest(dispatcher) {
            val viewModel = viewModel(FakeDetailRepository(detail()), FakeEditRepository())

            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.isLoading)
            assertEquals("콩이", state.displayName)
            assertEquals(6L, state.version)
        }

    @Test
    fun `이름이 없으면 기본 호칭으로 안내한다`() =
        runTest(dispatcher) {
            val viewModel =
                viewModel(FakeDetailRepository(detail(name = null, type = "SHELTERING")), FakeEditRepository())

            advanceUntilIdle()

            assertEquals("보호 중인 동물", viewModel.uiState.value.displayName)
        }

    @Test
    fun `사유를 고르지 않으면 종료를 요청할 수 없다`() =
        runTest(dispatcher) {
            val viewModel = viewModel(FakeDetailRepository(detail()), FakeEditRepository())
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.canSubmit)

            viewModel.onCloseRequest()

            assertFalse(viewModel.uiState.value.isConfirming)
        }

    @Test
    fun `사유를 고르면 확인 단계를 거친 뒤 종료를 요청한다`() =
        runTest(dispatcher) {
            val editRepository = FakeEditRepository()
            val viewModel = viewModel(FakeDetailRepository(detail()), editRepository)
            advanceUntilIdle()

            viewModel.onReasonSelect(PostCloseReason.RETURNED)
            viewModel.onCloseRequest()

            assertTrue(viewModel.uiState.value.isConfirming)
            assertEquals(0, editRepository.closeCount)

            viewModel.onConfirm()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isConfirming)
            assertEquals(1, editRepository.closeCount)
            assertEquals(PostCloseReason.RETURNED, editRepository.lastReason)
            assertEquals(6L, editRepository.lastVersion)
        }

    @Test
    fun `확인을 취소하면 종료를 요청하지 않는다`() =
        runTest(dispatcher) {
            val editRepository = FakeEditRepository()
            val viewModel = viewModel(FakeDetailRepository(detail()), editRepository)
            advanceUntilIdle()

            viewModel.onReasonSelect(PostCloseReason.OTHER)
            viewModel.onCloseRequest()
            viewModel.onConfirmDismiss()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isConfirming)
            assertEquals(0, editRepository.closeCount)
        }

    @Test
    fun `이미 종료된 게시물은 종료 화면을 열지 않는다`() =
        runTest(dispatcher) {
            val viewModel = viewModel(FakeDetailRepository(detail(status = "CLOSED")), FakeEditRepository())

            advanceUntilIdle()

            assertEquals("이미 종료된 게시물입니다.", viewModel.uiState.value.loadErrorMessage)
            assertFalse(viewModel.uiState.value.canSubmit)
        }

    @Test
    fun `본인 게시물이 아니면 종료할 수 없다`() =
        runTest(dispatcher) {
            val viewModel = viewModel(FakeDetailRepository(detail(owner = false)), FakeEditRepository())

            advanceUntilIdle()

            assertEquals("본인이 등록한 게시물만 종료할 수 있습니다.", viewModel.uiState.value.loadErrorMessage)
        }

    @Test
    fun `공공 보호동물은 종료할 수 없다`() =
        runTest(dispatcher) {
            val viewModel = viewModel(FakeDetailRepository(detail(source = "SHELTER")), FakeEditRepository())

            advanceUntilIdle()

            assertEquals("공공 보호동물 정보는 종료할 수 없습니다.", viewModel.uiState.value.loadErrorMessage)
        }

    @Test
    fun `버전 충돌은 별도 안내로 알리고 새로고침으로 최신 버전을 받는다`() =
        runTest(dispatcher) {
            val editRepository =
                FakeEditRepository(
                    closeResult = PostCloseResult.VersionConflict("게시물이 변경되었습니다. 최신 내용을 다시 확인해 주세요."),
                )
            val detailRepository = FakeDetailRepository(detail())
            val viewModel = viewModel(detailRepository, editRepository)
            advanceUntilIdle()

            viewModel.onReasonSelect(PostCloseReason.RETURNED)
            viewModel.onCloseRequest()
            viewModel.onConfirm()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isVersionConflicted)
            assertEquals(PostCloseReason.RETURNED, viewModel.uiState.value.selectedReason)

            detailRepository.detail = detail(version = 8L)
            viewModel.refresh()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isVersionConflicted)
            assertEquals(8L, viewModel.uiState.value.version)
        }

    @Test
    fun `종료에 실패하면 사유를 유지하고 오류만 알린다`() =
        runTest(dispatcher) {
            val editRepository =
                FakeEditRepository(closeResult = PostCloseResult.Failure("게시물 종료 중 오류가 발생했습니다."))
            val viewModel = viewModel(FakeDetailRepository(detail()), editRepository)
            advanceUntilIdle()

            viewModel.onReasonSelect(PostCloseReason.TRANSFERRED)
            viewModel.onCloseRequest()
            viewModel.onConfirm()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(PostCloseReason.TRANSFERRED, state.selectedReason)
            assertEquals("게시물 종료 중 오류가 발생했습니다.", state.requestError)
            assertFalse(state.isSubmitting)
        }

    @Test
    fun `종료 사유는 서버 CloseReason 세 가지를 제공한다`() =
        runTest(dispatcher) {
            val viewModel = viewModel(FakeDetailRepository(detail()), FakeEditRepository())
            advanceUntilIdle()

            assertEquals(
                listOf("RETURNED", "TRANSFERRED", "OTHER"),
                viewModel.uiState.value.reasons.map { it.value },
            )
        }

    private fun viewModel(
        detailRepository: PostDetailRepository,
        editRepository: PostEditRepository,
    ) = PostCloseViewModel(
        savedStateHandle = SavedStateHandle(mapOf("postId" to POST_ID)),
        detailRepository = detailRepository,
        editRepository = editRepository,
    )

    private fun detail(
        owner: Boolean = true,
        status: String = "ACTIVE",
        source: String = "USER_POST",
        type: String = "LOST",
        version: Long? = 6L,
        name: String? = "콩이",
    ) = PostDetailResponse(
        postId = POST_ID,
        type = type,
        source = source,
        status = status,
        version = version,
        name = name,
        species = "DOG",
        sex = "MALE",
        eventDate = "2026-08-20",
        eventLocation =
            PostLocationResponse(
                regionCode = "11680",
                publicLocation = "서울 강남구 역삼동",
            ),
        owner = owner,
        createdAt = "2026-08-20T11:00:00Z",
    )

    private class FakeDetailRepository(
        var detail: PostDetailResponse,
    ) : PostDetailRepository {
        override suspend fun getPostDetail(postId: Long): PostDetailResult = PostDetailResult.Success(detail)
    }

    private class FakeEditRepository(
        private val closeResult: PostCloseResult? = null,
    ) : PostEditRepository {
        var closeCount = 0
        var lastReason: PostCloseReason? = null
        var lastVersion: Long? = null

        override suspend fun updatePost(input: PostEditInput): PostEditResult = error("이 테스트에서 사용하지 않는다")

        override suspend fun replacePhotos(
            postId: Long,
            version: Long,
            photos: List<PostPhotoSource>,
        ): PostPhotoReplaceResult = error("이 테스트에서 사용하지 않는다")

        override suspend fun closePost(
            postId: Long,
            version: Long,
            reason: PostCloseReason,
        ): PostCloseResult {
            closeCount += 1
            lastReason = reason
            lastVersion = version
            return closeResult ?: PostCloseResult.Success(version + 1)
        }
    }

    private companion object {
        const val POST_ID = 1002L
    }
}
