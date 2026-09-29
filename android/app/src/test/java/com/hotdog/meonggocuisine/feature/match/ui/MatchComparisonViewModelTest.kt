package com.hotdog.meonggocuisine.feature.match.ui

import androidx.lifecycle.SavedStateHandle
import com.hotdog.meonggocuisine.feature.match.data.AnalysisStatus
import com.hotdog.meonggocuisine.feature.match.data.MatchBaseSummaryResult
import com.hotdog.meonggocuisine.feature.match.data.MatchComparison
import com.hotdog.meonggocuisine.feature.match.data.MatchComparisonAnimal
import com.hotdog.meonggocuisine.feature.match.data.MatchComparisonResult
import com.hotdog.meonggocuisine.feature.match.data.MatchRepository
import com.hotdog.meonggocuisine.feature.match.data.MatchRunRequestResult
import com.hotdog.meonggocuisine.feature.match.data.MatchStatusResult
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MatchComparisonViewModelTest {
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
    fun `내 동물과 후보 동물을 구분해 담는다`() =
        runTest(dispatcher) {
            val viewModel = viewModel(FakeMatchRepository(comparison(isShelter = true)))

            advanceUntilIdle()

            val comparison = viewModel.uiState.value.comparison
            assertEquals("콩이", comparison?.mine?.displayName)
            assertEquals("보호소 #12345", comparison?.candidate?.displayName)
            assertFalse(viewModel.uiState.value.isLoading)
        }

    @Test
    fun `공공 후보는 보호센터 공식 연락처를 담는다`() =
        runTest(dispatcher) {
            val viewModel = viewModel(FakeMatchRepository(comparison(isShelter = true)))

            advanceUntilIdle()

            val candidate = viewModel.uiState.value.comparison?.candidate
            assertTrue(candidate!!.isShelter)
            assertEquals("02-123-4567", candidate.shelterPhone)
            assertFalse(candidate.chatAvailable)
        }

    @Test
    fun `사용자 후보는 채팅 가능 여부를 담는다`() =
        runTest(dispatcher) {
            val viewModel = viewModel(FakeMatchRepository(comparison(isShelter = false)))

            advanceUntilIdle()

            val candidate = viewModel.uiState.value.comparison?.candidate
            assertFalse(candidate!!.isShelter)
            assertTrue(candidate.chatAvailable)
            assertNull(candidate.shelterPhone)
        }

    @Test
    fun `추천 근거에 유사도 수치와 거리를 담지 않는다`() =
        runTest(dispatcher) {
            val viewModel = viewModel(FakeMatchRepository(comparison(isShelter = true)))

            advanceUntilIdle()

            val reasons = viewModel.uiState.value.recommendationReasons
            assertTrue(reasons.isNotEmpty())
            assertTrue(reasons.none { it.contains("%") })
            assertTrue(reasons.none { it.contains("km") })
        }

    @Test
    fun `후보가 실종 이후에 발견되면 시점 관계를 근거로 담는다`() =
        runTest(dispatcher) {
            val viewModel = viewModel(FakeMatchRepository(comparison(isShelter = true)))

            advanceUntilIdle()

            val reasons = viewModel.uiState.value.recommendationReasons
            assertTrue(reasons.any { it.contains("실종 이후") && it.contains("2026.08.22") })
        }

    @Test
    fun `후보가 실종 이전에 발견되면 시점 근거를 담지 않는다`() =
        runTest(dispatcher) {
            val earlier =
                comparison(isShelter = true).let {
                    it.copy(candidate = it.candidate.copy(eventDate = "2026-08-10"))
                }
            val viewModel = viewModel(FakeMatchRepository(earlier))

            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.recommendationReasons.none { it.contains("실종 이후") })
        }

    @Test
    fun `추천 근거에 후보의 공개 지역을 담는다`() =
        runTest(dispatcher) {
            val viewModel = viewModel(FakeMatchRepository(comparison(isShelter = true)))

            advanceUntilIdle()

            assertTrue(
                viewModel.uiState.value.recommendationReasons.any { it.contains("서울 은평구 갈현동") },
            )
        }

    @Test
    fun `조회에 실패하면 오류를 표시하고 다시 불러올 수 있다`() =
        runTest(dispatcher) {
            val repository =
                FakeMatchRepository(
                    comparison(isShelter = true),
                    comparisonResult = MatchComparisonResult.Failure("비교 정보를 불러오지 못했습니다."),
                )
            val viewModel = viewModel(repository)
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.comparison)
            assertEquals("비교 정보를 불러오지 못했습니다.", viewModel.uiState.value.errorMessage)

            repository.comparisonResult = null
            viewModel.retry()
            advanceUntilIdle()

            assertEquals("콩이", viewModel.uiState.value.comparison?.mine?.displayName)
            assertEquals(2, repository.comparisonCount)
        }

    @Test
    fun `권한이 없으면 비교 정보를 표시하지 않는다`() =
        runTest(dispatcher) {
            val viewModel =
                viewModel(
                    FakeMatchRepository(
                        comparison(isShelter = true),
                        comparisonResult = MatchComparisonResult.Unauthorized("로그인이 필요합니다."),
                    ),
                )

            advanceUntilIdle()

            assertNull(viewModel.uiState.value.comparison)
            assertEquals("로그인이 필요합니다.", viewModel.uiState.value.errorMessage)
        }

    @Test
    fun `기준 게시물과 후보 게시물 식별자를 함께 요청한다`() =
        runTest(dispatcher) {
            val repository = FakeMatchRepository(comparison(isShelter = true))
            val viewModel = viewModel(repository)

            advanceUntilIdle()

            assertEquals(POST_ID, viewModel.postId)
            assertEquals(CANDIDATE_POST_ID, viewModel.candidatePostId)
            assertEquals(POST_ID to CANDIDATE_POST_ID, repository.requestedIds)
        }

    private fun viewModel(repository: MatchRepository) =
        MatchComparisonViewModel(
            savedStateHandle =
                SavedStateHandle(
                    mapOf("postId" to POST_ID, "candidatePostId" to CANDIDATE_POST_ID),
                ),
            repository = repository,
        )

    private fun comparison(isShelter: Boolean) =
        MatchComparison(
            mine =
                animal(
                    postId = POST_ID,
                    source = "USER_POST",
                    displayName = "콩이",
                    eventDate = "2026-08-20",
                    publicLocation = "서울 마포구 망원동",
                ),
            candidate =
                animal(
                    postId = CANDIDATE_POST_ID,
                    source = if (isShelter) "SHELTER" else "USER_POST",
                    displayName = if (isShelter) "보호소 #12345" else "갈색 푸들",
                    eventDate = "2026-08-22",
                    publicLocation = "서울 은평구 갈현동",
                    shelterPhone = if (isShelter) "02-123-4567" else null,
                    chatAvailable = !isShelter,
                ),
        )

    private fun animal(
        postId: Long,
        source: String,
        displayName: String,
        eventDate: String,
        publicLocation: String,
        shelterPhone: String? = null,
        chatAvailable: Boolean = false,
    ) = MatchComparisonAnimal(
        postId = postId,
        source = source,
        status = "ACTIVE",
        displayName = displayName,
        attributeSummary = "푸들 · 수컷 · 갈색",
        eventDate = eventDate,
        eventTime = null,
        publicLocation = publicLocation,
        currentLocation = null,
        featureText = null,
        thumbnailUrl = null,
        authorNickname = null,
        shelterName = if (shelterPhone != null) "은평구 동물보호센터" else null,
        shelterPhone = shelterPhone,
        shelterAddress = null,
        shelterNoticeNo = if (shelterPhone != null) "12345" else null,
        shelterProcessState = null,
        chatAvailable = chatAvailable,
    )

    private class FakeMatchRepository(
        private val comparison: MatchComparison,
        var comparisonResult: MatchComparisonResult? = null,
    ) : MatchRepository {
        var comparisonCount = 0
        var requestedIds: Pair<Long, Long>? = null

        override suspend fun getComparison(
            postId: Long,
            candidatePostId: Long,
        ): MatchComparisonResult {
            comparisonCount += 1
            requestedIds = postId to candidatePostId
            return comparisonResult ?: MatchComparisonResult.Success(comparison)
        }

        override suspend fun getStatus(postId: Long): MatchStatusResult = error("이 테스트에서 사용하지 않는다")

        override suspend fun requestAnalysis(postId: Long): MatchRunRequestResult =
            MatchRunRequestResult.Accepted(1L, AnalysisStatus.PENDING)

        override suspend fun getBaseSummary(postId: Long): MatchBaseSummaryResult = MatchBaseSummaryResult.Unavailable
    }

    private companion object {
        const val POST_ID = 1002L
        const val CANDIDATE_POST_ID = 2001L
    }
}
