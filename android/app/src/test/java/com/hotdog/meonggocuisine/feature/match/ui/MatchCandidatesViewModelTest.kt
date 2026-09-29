package com.hotdog.meonggocuisine.feature.match.ui

import androidx.lifecycle.SavedStateHandle
import com.hotdog.meonggocuisine.feature.match.data.AnalysisStatus
import com.hotdog.meonggocuisine.feature.match.data.MatchBaseSummary
import com.hotdog.meonggocuisine.feature.match.data.MatchBaseSummaryResult
import com.hotdog.meonggocuisine.feature.match.data.MatchCandidate
import com.hotdog.meonggocuisine.feature.match.data.MatchComparisonResult
import com.hotdog.meonggocuisine.feature.match.data.MatchRepository
import com.hotdog.meonggocuisine.feature.match.data.MatchRunRequestResult
import com.hotdog.meonggocuisine.feature.match.data.MatchStatusResult
import com.hotdog.meonggocuisine.feature.match.data.MatchStatusSnapshot
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
class MatchCandidatesViewModelTest {
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
    fun `후보를 rank 순으로 표시한다`() =
        runTest(dispatcher) {
            val viewModel =
                viewModel(
                    FakeMatchRepository(
                        snapshot(
                            AnalysisStatus.SUCCEEDED,
                            candidates = listOf(candidate(1, 1001), candidate(2, 1002), candidate(3, 1003)),
                        ),
                    ),
                )

            advanceUntilIdle()

            assertEquals(MatchCandidatesPhase.CANDIDATES, viewModel.uiState.value.phase)
            assertEquals(listOf(1, 2, 3), viewModel.uiState.value.candidates.map(MatchCandidate::rank))
        }

    @Test
    fun `후보가 없으면 실패와 구분되는 후보 없음으로 표시한다`() =
        runTest(dispatcher) {
            val viewModel = viewModel(FakeMatchRepository(snapshot(AnalysisStatus.SUCCEEDED)))

            advanceUntilIdle()

            assertEquals(MatchCandidatesPhase.EMPTY, viewModel.uiState.value.phase)
            assertNull(viewModel.uiState.value.errorMessage)
        }

    @Test
    fun `처리 실패는 후보 없음과 다른 단계로 표시한다`() =
        runTest(dispatcher) {
            val viewModel =
                viewModel(
                    FakeMatchRepository(
                        snapshot(
                            AnalysisStatus.FAILED,
                            latestRunStatus = AnalysisStatus.FAILED,
                            errorCode = "MATCH_TIMEOUT",
                        ),
                    ),
                )

            advanceUntilIdle()

            assertEquals(MatchCandidatesPhase.FAILED, viewModel.uiState.value.phase)
            assertEquals("분석 시간이 초과되었습니다. 다시 시도해 주세요.", viewModel.uiState.value.errorMessage)
        }

    @Test
    fun `최신 실행이 실패해도 보존된 이전 결과가 있으면 후보를 표시한다`() =
        runTest(dispatcher) {
            val viewModel =
                viewModel(
                    FakeMatchRepository(
                        snapshot(
                            AnalysisStatus.SUCCEEDED,
                            latestRunStatus = AnalysisStatus.FAILED,
                            candidates = listOf(candidate(1, 1001)),
                            usingPreviousResult = true,
                        ),
                    ),
                )

            advanceUntilIdle()

            assertEquals(MatchCandidatesPhase.CANDIDATES, viewModel.uiState.value.phase)
            assertTrue(viewModel.uiState.value.usingPreviousResult)
        }

    @Test
    fun `게시물을 수정한 뒤에는 이전 후보를 유지하고 오래된 결과임을 표시한다`() =
        runTest(dispatcher) {
            val viewModel =
                viewModel(
                    FakeMatchRepository(
                        snapshot(
                            AnalysisStatus.STALE,
                            latestRunStatus = AnalysisStatus.SUCCEEDED,
                            candidates = listOf(candidate(1, 1001)),
                        ),
                    ),
                )

            advanceUntilIdle()

            assertEquals(MatchCandidatesPhase.CANDIDATES, viewModel.uiState.value.phase)
            assertTrue(viewModel.uiState.value.isStale)
            assertEquals(1, viewModel.uiState.value.candidates.size)
        }

    @Test
    fun `작성자가 아니면 후보를 표시하지 않는다`() =
        runTest(dispatcher) {
            val repository =
                FakeMatchRepository(
                    snapshot(AnalysisStatus.SUCCEEDED, candidates = listOf(candidate(1, 1001))),
                    statusResult = MatchStatusResult.NotAllowed("본인 게시물에서만 유사 후보를 볼 수 있습니다."),
                )
            val viewModel = viewModel(repository)

            advanceUntilIdle()

            assertEquals(MatchCandidatesPhase.BLOCKED, viewModel.uiState.value.phase)
            assertTrue(viewModel.uiState.value.candidates.isEmpty())
            assertFalse(viewModel.uiState.value.canRequestAnalysis)
        }

    @Test
    fun `아직 처리 중이면 진행 화면으로 돌려보낸다`() =
        runTest(dispatcher) {
            val viewModel =
                viewModel(
                    FakeMatchRepository(
                        snapshot(AnalysisStatus.RUNNING, latestRunStatus = AnalysisStatus.RUNNING),
                    ),
                )

            advanceUntilIdle()

            assertTrue(viewModel.analysisRestarted.value)
        }

    @Test
    fun `진행 화면 이동을 처리하면 신호를 되돌려 반복 이동을 막는다`() =
        runTest(dispatcher) {
            val viewModel =
                viewModel(
                    FakeMatchRepository(
                        snapshot(AnalysisStatus.RUNNING, latestRunStatus = AnalysisStatus.RUNNING),
                    ),
                )
            advanceUntilIdle()
            assertTrue(viewModel.analysisRestarted.value)

            viewModel.onAnalysisRestartedHandled()

            assertFalse(viewModel.analysisRestarted.value)
        }

    @Test
    fun `후보 없음에서 다시 분석하면 진행 화면으로 이동한다`() =
        runTest(dispatcher) {
            val repository =
                FakeMatchRepository(
                    snapshot(AnalysisStatus.SUCCEEDED),
                    runResult = MatchRunRequestResult.Accepted(matchRunId = 505L, status = AnalysisStatus.PENDING),
                )
            val viewModel = viewModel(repository)
            advanceUntilIdle()
            assertEquals(MatchCandidatesPhase.EMPTY, viewModel.uiState.value.phase)

            viewModel.requestAnalysis()
            advanceUntilIdle()

            assertEquals(1, repository.requestCount)
            assertTrue(viewModel.analysisRestarted.value)
        }

    @Test
    fun `요청 중에 다시 눌러도 분석은 한 번만 접수한다`() =
        runTest(dispatcher) {
            val repository = FakeMatchRepository(snapshot(AnalysisStatus.SUCCEEDED))
            val viewModel = viewModel(repository)
            advanceUntilIdle()

            viewModel.requestAnalysis()
            viewModel.requestAnalysis()
            advanceUntilIdle()

            assertEquals(1, repository.requestCount)
        }

    @Test
    fun `조회에 실패하면 실패 단계로 표시하고 다시 불러올 수 있다`() =
        runTest(dispatcher) {
            val repository =
                FakeMatchRepository(
                    snapshot(AnalysisStatus.SUCCEEDED, candidates = listOf(candidate(1, 1001))),
                    statusResult = MatchStatusResult.Failure("서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요."),
                )
            val viewModel = viewModel(repository)
            advanceUntilIdle()

            assertEquals(MatchCandidatesPhase.FAILED, viewModel.uiState.value.phase)
            assertEquals("서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.", viewModel.uiState.value.errorMessage)

            repository.statusResult = null
            viewModel.retry()
            advanceUntilIdle()

            assertEquals(MatchCandidatesPhase.CANDIDATES, viewModel.uiState.value.phase)
            assertEquals(2, repository.statusCount)
        }

    @Test
    fun `분석 요청이 거부되면 후보를 감추고 안내만 표시한다`() =
        runTest(dispatcher) {
            val repository =
                FakeMatchRepository(
                    snapshot(AnalysisStatus.SUCCEEDED, candidates = listOf(candidate(1, 1001))),
                    runResult = MatchRunRequestResult.NotAllowed("종료된 게시물은 분석할 수 없습니다."),
                )
            val viewModel = viewModel(repository)
            advanceUntilIdle()

            viewModel.requestAnalysis()
            advanceUntilIdle()

            assertEquals(MatchCandidatesPhase.BLOCKED, viewModel.uiState.value.phase)
            assertTrue(viewModel.uiState.value.candidates.isEmpty())
            assertFalse(viewModel.analysisRestarted.value)
        }

    private fun viewModel(repository: MatchRepository) =
        MatchCandidatesViewModel(
            savedStateHandle = SavedStateHandle(mapOf("postId" to POST_ID, "animalName" to "콩이")),
            repository = repository,
        )

    private fun snapshot(
        analysisStatus: AnalysisStatus,
        latestRunStatus: AnalysisStatus? = null,
        errorCode: String? = null,
        candidates: List<MatchCandidate> = emptyList(),
        usingPreviousResult: Boolean = false,
    ) = MatchStatusSnapshot(
        analysisStatus = analysisStatus,
        latestRunStatus = latestRunStatus,
        errorCode = errorCode,
        recommendedPollAfterMs = null,
        usingPreviousResult = usingPreviousResult,
        candidateCount = candidates.size,
        candidates = candidates,
    )

    private fun candidate(
        rank: Int,
        postId: Long,
    ) = MatchCandidate(
        rank = rank,
        postId = postId,
        source = "SHELTER",
        status = "ACTIVE",
        name = null,
        species = "DOG",
        breedName = "푸들",
        sex = "MALE",
        color = "갈색",
        eventDate = "2026-09-01",
        publicLocation = "서울특별시 마포구 서교동",
        thumbnailUrl = null,
        authorNickname = null,
        shelterName = "마포구 동물보호센터",
        shelterPhone = "02-1234-1234",
    )

    private class FakeMatchRepository(
        private val snapshot: MatchStatusSnapshot,
        var statusResult: MatchStatusResult? = null,
        private val runResult: MatchRunRequestResult = MatchRunRequestResult.Accepted(1L, AnalysisStatus.PENDING),
    ) : MatchRepository {
        var statusCount = 0
        var requestCount = 0
        var baseSummaryResult: MatchBaseSummaryResult? = null

        override suspend fun getStatus(postId: Long): MatchStatusResult {
            statusCount += 1
            return statusResult ?: MatchStatusResult.Success(snapshot)
        }

        override suspend fun requestAnalysis(postId: Long): MatchRunRequestResult {
            requestCount += 1
            return runResult
        }

        override suspend fun getComparison(
            postId: Long,
            candidatePostId: Long,
        ): MatchComparisonResult = MatchComparisonResult.Failure("이 테스트에서 사용하지 않는다")

        override suspend fun getBaseSummary(postId: Long): MatchBaseSummaryResult =
            baseSummaryResult ?: MatchBaseSummaryResult.Success(
                MatchBaseSummary(
                    postId = postId,
                    name = "콩이",
                    species = "DOG",
                    breedName = "푸들",
                    sex = "MALE",
                    color = "갈색",
                    eventDate = "2026-08-20",
                    eventTime = "15:30:00",
                    publicLocation = "서울 마포구 망원동",
                    thumbnailUrl = null,
                ),
            )
    }

    private companion object {
        const val POST_ID = 1002L
    }
}
