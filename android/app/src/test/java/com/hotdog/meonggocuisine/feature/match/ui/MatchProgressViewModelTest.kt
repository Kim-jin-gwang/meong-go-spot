package com.hotdog.meonggocuisine.feature.match.ui

import androidx.lifecycle.SavedStateHandle
import com.hotdog.meonggocuisine.feature.match.data.AnalysisStatus
import com.hotdog.meonggocuisine.feature.match.data.MatchBaseSummaryResult
import com.hotdog.meonggocuisine.feature.match.data.MatchComparisonResult
import com.hotdog.meonggocuisine.feature.match.data.MatchRepository
import com.hotdog.meonggocuisine.feature.match.data.MatchRunRequestResult
import com.hotdog.meonggocuisine.feature.match.data.MatchStatusResult
import com.hotdog.meonggocuisine.feature.match.data.MatchStatusSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MatchProgressViewModelTest {
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
    fun `분석을 요청한 적 없으면 분석 시작 단계를 표시한다`() =
        runTest(dispatcher) {
            val viewModel = viewModel(FakeMatchRepository(snapshot(AnalysisStatus.NOT_REQUESTED)))

            viewModel.startPolling()
            advanceUntilIdle()

            assertEquals(MatchProgressPhase.NOT_REQUESTED, viewModel.uiState.value.phase)
            assertTrue(viewModel.uiState.value.canRequestAnalysis)
        }

    @Test
    fun `PENDING과 RUNNING은 처리 중으로 표시한다`() =
        runTest(dispatcher) {
            for (status in listOf(AnalysisStatus.PENDING, AnalysisStatus.RUNNING)) {
                val viewModel = viewModel(FakeMatchRepository(snapshot(status, latestRunStatus = status)))

                viewModel.startPolling()
                runCurrent()

                assertEquals(MatchProgressPhase.IN_PROGRESS, viewModel.uiState.value.phase)
                viewModel.stopPolling()
            }
        }

    @Test
    fun `처리 중이면 서버가 권장한 간격으로 다시 조회한다`() =
        runTest(dispatcher) {
            val repository =
                FakeMatchRepository(snapshot(AnalysisStatus.RUNNING, latestRunStatus = AnalysisStatus.RUNNING, pollAfterMs = 2_000L))
            val viewModel = viewModel(repository)

            viewModel.startPolling()
            runCurrent()
            assertEquals(1, repository.statusCount)

            advanceTimeBy(1_999L)
            runCurrent()
            assertEquals(1, repository.statusCount)

            advanceTimeBy(2L)
            runCurrent()
            assertEquals(2, repository.statusCount)

            viewModel.stopPolling()
        }

    @Test
    fun `폴링을 멈추면 더 조회하지 않는다`() =
        runTest(dispatcher) {
            val repository = FakeMatchRepository(snapshot(AnalysisStatus.RUNNING, latestRunStatus = AnalysisStatus.RUNNING))
            val viewModel = viewModel(repository)

            viewModel.startPolling()
            runCurrent()
            val callsWhileVisible = repository.statusCount

            viewModel.stopPolling()
            advanceTimeBy(10_000L)
            runCurrent()

            assertEquals(callsWhileVisible, repository.statusCount)
        }

    @Test
    fun `분석이 끝나면 폴링을 멈추고 완료 단계로 넘어간다`() =
        runTest(dispatcher) {
            val repository =
                FakeMatchRepository(snapshot(AnalysisStatus.SUCCEEDED, latestRunStatus = AnalysisStatus.SUCCEEDED, candidateCount = 7))
            val viewModel = viewModel(repository)

            viewModel.startPolling()
            advanceUntilIdle()

            assertEquals(MatchProgressPhase.COMPLETED, viewModel.uiState.value.phase)
            assertEquals(7, viewModel.uiState.value.candidateCount)
            assertEquals(1, repository.statusCount)
        }

    @Test
    fun `FAILED는 완료와 구분된 실패 단계로 표시하고 재분석을 허용한다`() =
        runTest(dispatcher) {
            val repository =
                FakeMatchRepository(
                    snapshot(AnalysisStatus.FAILED, latestRunStatus = AnalysisStatus.FAILED, errorCode = "MATCH_TIMEOUT"),
                )
            val viewModel = viewModel(repository)

            viewModel.startPolling()
            advanceUntilIdle()

            assertEquals(MatchProgressPhase.FAILED, viewModel.uiState.value.phase)
            assertEquals("분석 시간이 초과되었습니다. 다시 시도해 주세요.", viewModel.uiState.value.errorMessage)
            assertTrue(viewModel.uiState.value.canRequestAnalysis)
        }

    @Test
    fun `게시물을 수정하면 STALE 단계를 표시하고 자동으로 다시 분석하지 않는다`() =
        runTest(dispatcher) {
            val repository =
                FakeMatchRepository(
                    snapshot(AnalysisStatus.STALE, latestRunStatus = AnalysisStatus.SUCCEEDED, candidateCount = 5),
                )
            val viewModel = viewModel(repository)

            viewModel.startPolling()
            advanceUntilIdle()

            assertEquals(MatchProgressPhase.STALE, viewModel.uiState.value.phase)
            assertEquals(0, repository.requestCount)
            assertTrue(viewModel.uiState.value.canRequestAnalysis)
        }

    @Test
    fun `STALE과 처리 중이 겹치면 처리 중을 먼저 표시한다`() =
        runTest(dispatcher) {
            val viewModel =
                viewModel(
                    FakeMatchRepository(snapshot(AnalysisStatus.STALE, latestRunStatus = AnalysisStatus.RUNNING)),
                )

            viewModel.startPolling()
            runCurrent()

            assertEquals(MatchProgressPhase.IN_PROGRESS, viewModel.uiState.value.phase)
            viewModel.stopPolling()
        }

    @Test
    fun `작성자가 아니면 폴링을 멈추고 결과를 표시하지 않는다`() =
        runTest(dispatcher) {
            val repository =
                FakeMatchRepository(
                    snapshot(AnalysisStatus.SUCCEEDED, latestRunStatus = AnalysisStatus.SUCCEEDED, candidateCount = 9),
                    statusResult = MatchStatusResult.NotAllowed("본인 게시물에서만 유사 후보를 볼 수 있습니다."),
                )
            val viewModel = viewModel(repository)

            viewModel.startPolling()
            advanceTimeBy(10_000L)
            advanceUntilIdle()

            assertEquals(MatchProgressPhase.BLOCKED, viewModel.uiState.value.phase)
            assertEquals(0, viewModel.uiState.value.candidateCount)
            assertEquals(1, repository.statusCount)
        }

    @Test
    fun `실패 상태에서 다시 분석하면 실행을 접수하고 상태 조회를 다시 시작한다`() =
        runTest(dispatcher) {
            val repository =
                FakeMatchRepository(
                    snapshot(AnalysisStatus.FAILED, latestRunStatus = AnalysisStatus.FAILED),
                    runResult = MatchRunRequestResult.Accepted(matchRunId = 505L, status = AnalysisStatus.PENDING),
                )
            val viewModel = viewModel(repository)
            viewModel.startPolling()
            advanceUntilIdle()
            val statusCallsBeforeRetry = repository.statusCount

            viewModel.requestAnalysis()
            advanceUntilIdle()

            assertEquals(1, repository.requestCount)
            assertTrue(repository.statusCount > statusCallsBeforeRetry)
        }

    @Test
    fun `분석 요청이 실패하면 실패 단계로 표시한다`() =
        runTest(dispatcher) {
            val repository =
                FakeMatchRepository(
                    snapshot(AnalysisStatus.NOT_REQUESTED),
                    runResult = MatchRunRequestResult.Failure("유사도 분석을 요청하지 못했습니다. 잠시 후 다시 시도해 주세요."),
                )
            val viewModel = viewModel(repository)
            viewModel.startPolling()
            advanceUntilIdle()

            viewModel.requestAnalysis()
            advanceUntilIdle()

            assertEquals(MatchProgressPhase.FAILED, viewModel.uiState.value.phase)
            assertEquals("유사도 분석을 요청하지 못했습니다. 잠시 후 다시 시도해 주세요.", viewModel.uiState.value.errorMessage)
        }

    @Test
    fun `분석 요청이 거부되면 폴링을 멈추고 안내만 표시한다`() =
        runTest(dispatcher) {
            val repository =
                FakeMatchRepository(
                    snapshot(AnalysisStatus.NOT_REQUESTED),
                    runResult = MatchRunRequestResult.NotAllowed("본인 게시물만 분석할 수 있습니다."),
                )
            val viewModel = viewModel(repository)
            viewModel.startPolling()
            advanceUntilIdle()

            viewModel.requestAnalysis()
            advanceUntilIdle()

            assertEquals(MatchProgressPhase.BLOCKED, viewModel.uiState.value.phase)
            assertEquals("본인 게시물만 분석할 수 있습니다.", viewModel.uiState.value.errorMessage)
        }

    @Test
    fun `요청 중에 다시 눌러도 분석은 한 번만 접수한다`() =
        runTest(dispatcher) {
            val repository =
                FakeMatchRepository(
                    snapshot(AnalysisStatus.NOT_REQUESTED),
                    runResult = MatchRunRequestResult.Accepted(matchRunId = 505L, status = AnalysisStatus.PENDING),
                )
            val viewModel = viewModel(repository)
            viewModel.startPolling()
            advanceUntilIdle()

            viewModel.requestAnalysis()
            viewModel.requestAnalysis()
            advanceUntilIdle()

            assertEquals(1, repository.requestCount)
            viewModel.stopPolling()
        }

    @Test
    fun `조회 실패는 이미 알고 있는 처리 중 단계를 유지하고 오류만 알린다`() =
        runTest(dispatcher) {
            val repository =
                FakeMatchRepository(
                    snapshot(AnalysisStatus.RUNNING, latestRunStatus = AnalysisStatus.RUNNING),
                )
            val viewModel = viewModel(repository)
            viewModel.startPolling()
            runCurrent()
            assertEquals(MatchProgressPhase.IN_PROGRESS, viewModel.uiState.value.phase)

            repository.statusResult = MatchStatusResult.Failure("서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.")
            advanceTimeBy(MatchProgressUiState.DEFAULT_POLL_AFTER_MS + 1)
            runCurrent()

            assertEquals(MatchProgressPhase.IN_PROGRESS, viewModel.uiState.value.phase)
            assertEquals("서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.", viewModel.uiState.value.errorMessage)
            viewModel.stopPolling()
        }

    @Test
    fun `완료 상태에서는 실패 문구를 남기지 않는다`() =
        runTest(dispatcher) {
            val viewModel =
                viewModel(
                    FakeMatchRepository(
                        snapshot(AnalysisStatus.SUCCEEDED, latestRunStatus = AnalysisStatus.SUCCEEDED, candidateCount = 3),
                    ),
                )

            viewModel.startPolling()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.errorMessage)
        }

    private fun viewModel(repository: MatchRepository) =
        MatchProgressViewModel(
            savedStateHandle = SavedStateHandle(mapOf("postId" to POST_ID)),
            repository = repository,
        )

    private fun snapshot(
        analysisStatus: AnalysisStatus,
        latestRunStatus: AnalysisStatus? = null,
        errorCode: String? = null,
        candidateCount: Int = 0,
        pollAfterMs: Long? = null,
    ) = MatchStatusSnapshot(
        analysisStatus = analysisStatus,
        latestRunStatus = latestRunStatus,
        errorCode = errorCode,
        recommendedPollAfterMs = pollAfterMs,
        usingPreviousResult = false,
        candidateCount = candidateCount,
        candidates = emptyList(),
    )

    private class FakeMatchRepository(
        private val snapshot: MatchStatusSnapshot,
        var statusResult: MatchStatusResult? = null,
        private val runResult: MatchRunRequestResult = MatchRunRequestResult.Accepted(1L, AnalysisStatus.PENDING),
    ) : MatchRepository {
        var statusCount = 0
        var requestCount = 0

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

        override suspend fun getBaseSummary(postId: Long): MatchBaseSummaryResult = MatchBaseSummaryResult.Unavailable
    }

    private companion object {
        const val POST_ID = 1002L
    }
}
