package com.hotdog.meonggocuisine.feature.match.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoButton
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoOutlinedButton
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoScreenHeader
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoSurfaces
import com.hotdog.meonggocuisine.core.designsystem.component.PawIcon
import com.hotdog.meonggocuisine.core.designsystem.component.SearchIcon
import com.hotdog.meonggocuisine.core.designsystem.component.meonggoFloatingShadow
import com.hotdog.meonggocuisine.core.designsystem.theme.MeonggoBanjeomTheme
import com.hotdog.meonggocuisine.core.designsystem.token.MeonggoSpacing

@Composable
fun MatchProgressRouteScreen(
    onBackClick: () -> Unit,
    onCandidatesReady: (Long, String?) -> Unit,
    onEditPostClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MatchProgressViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleStartEffect(Unit) {
        viewModel.startPolling()
        onStopOrDispose { viewModel.stopPolling() }
    }

    LaunchedEffect(uiState.phase) {
        if (uiState.phase == MatchProgressPhase.COMPLETED) onCandidatesReady(viewModel.postId, uiState.animalName)
    }

    MatchProgressScreen(
        uiState = uiState,
        onBackClick = onBackClick,
        onRequestAnalysis = viewModel::requestAnalysis,
        onEditPostClick = { onEditPostClick(viewModel.postId) },
        modifier = modifier,
    )
}

@Composable
fun MatchProgressScreen(
    uiState: MatchProgressUiState,
    onBackClick: () -> Unit,
    onRequestAnalysis: () -> Unit,
    onEditPostClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
    ) {
        // 머리줄을 둔다. 분석을 기다리는 동안에도 돌아갈 자리가 화면에 보여야 한다 —
        // 예전에는 기기 뒤로 가기밖에 없었고, 다른 화면은 모두 이 머리줄을 쓴다.
        MeonggoScreenHeader(title = "유사도 분석", onBackClick = onBackClick)
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(
                        start = MeonggoSurfaces.gutter,
                        end = MeonggoSurfaces.gutter,
                        bottom = MeonggoSpacing.extraLarge,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.fillMaxSize().widthIn(max = CARD_MAX_WIDTH).meonggoFloatingShadow(),
                shape = MeonggoSurfaces.cardShape,
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
            ) {
                MatchProgressContent(
                    uiState = uiState,
                    onBackClick = onBackClick,
                    onRequestAnalysis = onRequestAnalysis,
                    onEditPostClick = onEditPostClick,
                )
            }
        }
    }
}

@Composable
private fun MatchProgressContent(
    uiState: MatchProgressUiState,
    onBackClick: () -> Unit,
    onRequestAnalysis: () -> Unit,
    onEditPostClick: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = MeonggoSpacing.extraLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        StatusBadge(phase = uiState.phase)

        Text(
            text = uiState.headline(),
            modifier = Modifier.padding(top = MeonggoSpacing.extraLarge),
            style = MaterialTheme.typography.titleLarge,
            color = titleColor(uiState.phase),
            textAlign = TextAlign.Center,
        )

        Text(
            text = uiState.body(),
            modifier = Modifier.padding(top = MeonggoSpacing.large),
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        when (uiState.phase) {
            MatchProgressPhase.LOADING,
            MatchProgressPhase.IN_PROGRESS,
            MatchProgressPhase.COMPLETED,
            -> SearchingProgressBar()

            MatchProgressPhase.NOT_REQUESTED ->
                PrimaryAction("유사도 분석하기", uiState.isRequesting, onRequestAnalysis)

            MatchProgressPhase.STALE ->
                PrimaryAction("다시 분석하기", uiState.isRequesting, onRequestAnalysis)

            MatchProgressPhase.FAILED -> {
                PrimaryAction("다시 분석하기", uiState.isRequesting, onRequestAnalysis)
                MeonggoOutlinedButton(
                    text = "등록 내용 확인하기",
                    onClick = onEditPostClick,
                    modifier = Modifier.fillMaxWidth().padding(top = MeonggoSpacing.small),
                    enabled = !uiState.isRequesting,
                )
            }

            MatchProgressPhase.BLOCKED ->
                PrimaryAction("돌아가기", isRequesting = false, onClick = onBackClick)
        }
    }
}

/**
 * 디자인의 원형 배지입니다. 상태를 그림과 함께 알립니다.
 *
 * 그림은 직접 그린 아이콘이다. 예전에는 🔍·🐾·⚠️·🔒 그림 문자를 썼는데, 기기와 OS 판마다
 * 모양과 색이 제각각이라 같은 앱 안에서 이 화면만 다른 그림책처럼 보였다
 * (core/designsystem/README.md — 글리프 대신 직접 그린다).
 *
 * 상태는 색만으로 가르지 않는다. 제목과 본문이 무슨 일인지 먼저 말하고, 배지는 거들기만 한다.
 */
@Composable
private fun StatusBadge(phase: MatchProgressPhase) {
    val isProblem = phase == MatchProgressPhase.FAILED || phase == MatchProgressPhase.BLOCKED
    val container =
        if (isProblem) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.primaryContainer
        }
    val foreground =
        if (isProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier.size(BADGE_SIZE).clip(CircleShape).background(container),
        contentAlignment = Alignment.Center,
    ) {
        if (phase.isSearching) {
            SearchIcon(color = foreground, iconSize = BADGE_ICON_SIZE, strokeWidth = BADGE_STROKE)
        } else {
            PawIcon(color = foreground, iconSize = BADGE_ICON_SIZE)
        }
    }
}

/**
 * 분석 진행 표시입니다.
 *
 * 서버가 진행률을 제공하지 않으므로 수치 없이 진행 중임만 알립니다
 * ([docs/product/wireframes/README.md]의 05 화면 규칙).
 */
@Composable
private fun SearchingProgressBar() {
    LinearProgressIndicator(
        modifier =
            Modifier
                .padding(top = MeonggoSpacing.doubleExtraLarge)
                .width(PROGRESS_WIDTH)
                .height(PROGRESS_HEIGHT)
                .clip(RoundedCornerShape(PROGRESS_HEIGHT / 2))
                .semantics { contentDescription = "유사한 동물을 찾고 있어요" },
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.outline,
        strokeCap = StrokeCap.Round,
    )
}

@Composable
private fun PrimaryAction(
    text: String,
    isRequesting: Boolean,
    onClick: () -> Unit,
) {
    MeonggoButton(
        text = text,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(top = MeonggoSpacing.doubleExtraLarge),
        isLoading = isRequesting,
        loadingStateDescription = "분석을 요청하는 중",
    )
}

@Composable
private fun titleColor(phase: MatchProgressPhase) =
    when (phase) {
        MatchProgressPhase.FAILED, MatchProgressPhase.BLOCKED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }

/** 찾고 있는 중인지. 찾는 동안에는 배지에 돋보기를, 그 밖에는 발자국을 둔다. */
private val MatchProgressPhase.isSearching: Boolean
    get() =
        this == MatchProgressPhase.LOADING ||
            this == MatchProgressPhase.IN_PROGRESS ||
            this == MatchProgressPhase.COMPLETED

/** 디자인의 두 줄 제목입니다. 이름을 모르면 이름 없는 문구를 사용합니다. */
private fun MatchProgressUiState.headline(): String {
    // 배지가 이미 발자국을 보이므로 제목에서는 그림 문자를 뺀다.
    val subject = animalName?.let { "${it}와 닮은 아이를" } ?: "닮은 아이를"
    return when (phase) {
        MatchProgressPhase.LOADING, MatchProgressPhase.COMPLETED -> "$subject\n찾고 있어요"
        MatchProgressPhase.IN_PROGRESS -> "$subject\n찾고 있어요"
        MatchProgressPhase.NOT_REQUESTED -> "$subject\n찾아볼까요?"
        MatchProgressPhase.STALE -> "게시물이 바뀌었어요"
        MatchProgressPhase.FAILED -> "분석을 완료하지 못했어요"
        MatchProgressPhase.BLOCKED -> "분석 결과를 볼 수 없어요"
    }
}

private fun MatchProgressUiState.body(): String {
    val subject = animalName?.let { "${it}와" } ?: "이 아이와"
    return when (phase) {
        MatchProgressPhase.LOADING -> "분석 상태를 확인하고 있어요.\n조금만 기다려주세요."
        MatchProgressPhase.IN_PROGRESS, MatchProgressPhase.COMPLETED ->
            "지금 보호 중인 아이들 가운데\n$subject 닮은 아이가 있는지 살펴보고 있어요.\n조금만 기다려주세요."
        MatchProgressPhase.NOT_REQUESTED ->
            "등록한 사진으로 지금 보호 중인 아이들 가운데\n닮은 아이를 찾아 드려요."
        MatchProgressPhase.STALE ->
            "수정한 내용으로는 아직 찾아보지 않았어요.\n다시 분석하면 새로 찾아 드려요."
        MatchProgressPhase.FAILED -> errorMessage ?: "잠시 후 다시 시도해 주세요."
        MatchProgressPhase.BLOCKED -> errorMessage ?: "본인이 진행 중인 게시물에서만 분석할 수 있어요."
    }
}

private val CARD_MAX_WIDTH = 390.dp
private val BADGE_SIZE = 110.dp
private val BADGE_ICON_SIZE = 46.dp
private const val BADGE_STROKE = 3.4f
private val PROGRESS_WIDTH = 240.dp
private val PROGRESS_HEIGHT = 8.dp

@Preview(showBackground = true, widthDp = 390, heightDp = 844, backgroundColor = 0xFFFFFFFF)
@Composable
private fun MatchProgressInProgressPreview() {
    MeonggoBanjeomTheme {
        MatchProgressScreen(
            uiState = MatchProgressUiState(phase = MatchProgressPhase.IN_PROGRESS, animalName = "콩이"),
            onBackClick = {},
            onRequestAnalysis = {},
            onEditPostClick = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844, backgroundColor = 0xFFFFFFFF)
@Composable
private fun MatchProgressNotRequestedPreview() {
    MeonggoBanjeomTheme {
        MatchProgressScreen(
            uiState = MatchProgressUiState(phase = MatchProgressPhase.NOT_REQUESTED, animalName = "콩이"),
            onBackClick = {},
            onRequestAnalysis = {},
            onEditPostClick = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844, backgroundColor = 0xFFFFFFFF)
@Composable
private fun MatchProgressFailedPreview() {
    MeonggoBanjeomTheme {
        MatchProgressScreen(
            uiState =
                MatchProgressUiState(
                    phase = MatchProgressPhase.FAILED,
                    animalName = "콩이",
                    errorMessage = "분석 시간이 초과되었습니다. 다시 시도해 주세요.",
                ),
            onBackClick = {},
            onRequestAnalysis = {},
            onEditPostClick = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844, backgroundColor = 0xFFFFFFFF)
@Composable
private fun MatchProgressStalePreview() {
    MeonggoBanjeomTheme {
        MatchProgressScreen(
            uiState = MatchProgressUiState(phase = MatchProgressPhase.STALE, animalName = "콩이", candidateCount = 12),
            onBackClick = {},
            onRequestAnalysis = {},
            onEditPostClick = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844, backgroundColor = 0xFFFFFFFF)
@Composable
private fun MatchProgressBlockedPreview() {
    MeonggoBanjeomTheme {
        MatchProgressScreen(
            uiState =
                MatchProgressUiState(
                    phase = MatchProgressPhase.BLOCKED,
                    errorMessage = "본인 게시물에서만 유사 후보를 볼 수 있습니다.",
                ),
            onBackClick = {},
            onRequestAnalysis = {},
            onEditPostClick = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844, backgroundColor = 0xFFFFFFFF)
@Composable
private fun MatchProgressWithoutNamePreview() {
    MeonggoBanjeomTheme {
        MatchProgressScreen(
            uiState = MatchProgressUiState(phase = MatchProgressPhase.IN_PROGRESS),
            onBackClick = {},
            onRequestAnalysis = {},
            onEditPostClick = {},
        )
    }
}
