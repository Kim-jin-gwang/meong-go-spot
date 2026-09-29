package com.hotdog.meonggocuisine.feature.match.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotdog.meonggocuisine.core.designsystem.component.CalendarIcon
import com.hotdog.meonggocuisine.core.designsystem.component.LocationPinIcon
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoButton
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoLoadingIndicator
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoOutlinedButton
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoPhotoThumbnail
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoScreenHeader
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoSurfaces
import com.hotdog.meonggocuisine.core.designsystem.component.PawIcon
import com.hotdog.meonggocuisine.core.designsystem.component.meonggoFloatingShadow
import com.hotdog.meonggocuisine.core.designsystem.theme.MeonggoBanjeomTheme
import com.hotdog.meonggocuisine.core.designsystem.token.MeonggoSpacing
import com.hotdog.meonggocuisine.core.text.speciesLabel
import com.hotdog.meonggocuisine.core.text.stripSpeciesPrefix
import com.hotdog.meonggocuisine.feature.match.data.MatchBaseSummary
import com.hotdog.meonggocuisine.feature.match.data.MatchCandidate

@Composable
fun MatchCandidatesRouteScreen(
    onBackClick: () -> Unit,
    onCandidateClick: (Long, Long) -> Unit,
    onEditPostClick: (Long) -> Unit,
    onAnalysisRestarted: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MatchCandidatesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val analysisRestarted by viewModel.analysisRestarted.collectAsStateWithLifecycle()

    LaunchedEffect(analysisRestarted) {
        if (analysisRestarted) {
            viewModel.onAnalysisRestartedHandled()
            onAnalysisRestarted(viewModel.postId)
        }
    }

    MatchCandidatesScreen(
        uiState = uiState,
        onBackClick = onBackClick,
        onRetryClick = viewModel::retry,
        onRequestAnalysis = viewModel::requestAnalysis,
        onExpandCandidates = viewModel::expandCandidates,
        onCandidateClick = { candidatePostId -> onCandidateClick(viewModel.postId, candidatePostId) },
        onEditPostClick = { onEditPostClick(viewModel.postId) },
        modifier = modifier,
    )
}

@Composable
fun MatchCandidatesScreen(
    uiState: MatchCandidatesUiState,
    onBackClick: () -> Unit,
    onRetryClick: () -> Unit,
    onRequestAnalysis: () -> Unit,
    onExpandCandidates: () -> Unit,
    onCandidateClick: (Long) -> Unit,
    onEditPostClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
    ) {
        MeonggoScreenHeader(title = "내 반려동물", onBackClick = onBackClick)
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = MeonggoSurfaces.gutter, vertical = MeonggoSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(MeonggoSpacing.large),
        ) {
            uiState.baseSummary?.let { BaseSummaryCard(it) }

            when (uiState.phase) {
                MatchCandidatesPhase.LOADING -> LoadingSection()

                MatchCandidatesPhase.CANDIDATES ->
                    CandidateSection(
                        uiState = uiState,
                        onCandidateClick = onCandidateClick,
                        onExpandCandidates = onExpandCandidates,
                        onRequestAnalysis = onRequestAnalysis,
                    )

                MatchCandidatesPhase.EMPTY ->
                    ResultNoticeCard(
                        title = "아직 유사한 보호동물이 없어요",
                        description = "현재 조건에 맞는 유사 후보가 없어요.\n새로운 보호동물이 등록된 후 다시 분석해보세요.",
                        isRequesting = uiState.isRequesting,
                        primaryText = "다시 분석하기",
                        onPrimaryClick = onRequestAnalysis,
                        secondaryText = "게시물로 돌아가기",
                        onSecondaryClick = onBackClick,
                        footnote = uiState.errorMessage,
                    )

                MatchCandidatesPhase.FAILED ->
                    ResultNoticeCard(
                        title = "분석을 완료하지 못했어요",
                        description = uiState.errorMessage ?: "잠시 후 다시 시도해 주세요.",
                        isRequesting = uiState.isRequesting,
                        primaryText = "다시 분석하기",
                        onPrimaryClick = onRequestAnalysis,
                        secondaryText = "결과 다시 불러오기",
                        onSecondaryClick = onRetryClick,
                        isError = true,
                    )

                MatchCandidatesPhase.BLOCKED ->
                    ResultNoticeCard(
                        title = "후보를 볼 수 없어요",
                        description = uiState.errorMessage ?: "본인이 진행 중인 게시물에서만 후보를 볼 수 있어요.",
                        isRequesting = false,
                        primaryText = "게시물로 돌아가기",
                        onPrimaryClick = onBackClick,
                        isError = true,
                    )
            }
            Spacer(Modifier.height(MeonggoSpacing.large))
        }
    }
}

/** 디자인 상단의 내 신고 요약 카드입니다. */
@Composable
private fun BaseSummaryCard(summary: MatchBaseSummary) {
    OutlinedSurface {
        // 사진 높이를 옆에 선 글 높이에 맞춘다. 고정 크기로 두던 때는 글이 줄어들면 사진만
        // 남아 카드 아래가 비고, 글이 늘면 사진이 그만큼 작아 보였다.
        //
        // 글 높이를 재서 넘기는 방식이다. `IntrinsicSize.Min` 으로 잡으면 사진 칸 안의
        // Coil `SubcomposeAsyncImage` 가 제 크기를 미리 답하지 못해 앱이 죽는다.
        var infoHeight by remember { mutableIntStateOf(0) }
        val density = LocalDensity.current
        Row(
            modifier = Modifier.padding(MeonggoSpacing.large),
            horizontalArrangement = Arrangement.spacedBy(MeonggoSpacing.large),
        ) {
            Thumbnail(
                url = summary.thumbnailUrl,
                contentDescription = "내 반려동물 사진",
                radius = MeonggoSpacing.medium,
                modifier =
                    Modifier.size(
                        with(density) { infoHeight.toDp() }.coerceAtLeast(SummaryThumbnailMin),
                    ),
            )
            Column(
                modifier = Modifier.onSizeChanged { infoHeight = it.height },
                verticalArrangement = Arrangement.spacedBy(MeonggoSpacing.small),
            ) {
                Text(
                    text = summary.headline(),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconLine(sexLabel(summary.sex)) { color ->
                    PawIcon(color = color, iconSize = INFO_ICON_SIZE)
                }
                IconLine(summary.publicLocation) {
                    // 핀은 세로로 조금 길다. 정사각으로 눌러 두면 머리가 찌그러진다.
                    LocationPinIcon(Modifier.size(width = PIN_ICON_WIDTH, height = INFO_ICON_SIZE))
                }
                IconLine(summary.eventSummary()) { color ->
                    CalendarIcon(color = color, iconSize = INFO_ICON_SIZE)
                }
            }
        }
    }
}

@Composable
private fun CandidateSection(
    uiState: MatchCandidatesUiState,
    onCandidateClick: (Long) -> Unit,
    onExpandCandidates: () -> Unit,
    onRequestAnalysis: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MeonggoSpacing.medium)) {
        // 오른쪽에 두던 "닮은 순서" 라벨은 뺐다. 누를 수 있는 것처럼 보이는데 그렇지 않고,
        // 카드마다 붙는 순위 배지가 이미 같은 말을 한다.
        Text(
            text = "유사한 보호동물 ${uiState.candidates.size}마리를 찾았어요",
            // 위 요약 카드와 한 덩어리로 붙어 보여서 한 칸 띄우고, 구역 제목답게 조금 키웠다.
            modifier = Modifier.padding(top = MeonggoSpacing.medium),
            style = MaterialTheme.typography.titleMedium.copy(fontSize = SECTION_TITLE_SIZE),
            fontWeight = FontWeight.Bold,
        )

        if (uiState.isStale) {
            NoticeBanner("게시물을 수정한 뒤에는 아직 다시 찾아보지 않았어요. 이전 결과예요.")
        }
        if (uiState.usingPreviousResult) {
            NoticeBanner("가장 최근 분석이 끝나지 않아 이전 결과를 보여 드려요.")
        }

        CandidateGrid(
            candidates = uiState.visibleCandidates,
            onCandidateClick = onCandidateClick,
        )

        if (uiState.hasMoreCandidates) {
            MeonggoOutlinedButton(
                text = "더 많은 결과 보기 (${uiState.candidates.size})",
                onClick = onExpandCandidates,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Text(
            text = "같은 아이로 확정된 결과가 아니에요. 사진과 날짜, 지역을 직접 확인해 주세요.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        MeonggoOutlinedButton(
            text = "다시 분석하기",
            onClick = onRequestAnalysis,
            modifier = Modifier.fillMaxWidth(),
            isLoading = uiState.isRequesting,
        )
    }
}

/**
 * 두 칸 격자입니다. 후보는 최대 20건이라 lazy 목록을 쓰지 않습니다.
 *
 * 세 칸이던 때는 사진이 너무 작아 얼굴을 견줄 수 없었고, 후보가 두 건이면 오른쪽 한 칸이
 * 통째로 비었다. 이 화면에서 하는 일은 사진을 견주는 것 하나다.
 */
@Composable
private fun CandidateGrid(
    candidates: List<MatchCandidate>,
    onCandidateClick: (Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MeonggoSpacing.medium)) {
        candidates.chunked(GRID_COLUMN_COUNT).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(MeonggoSpacing.medium)) {
                row.forEach { candidate ->
                    CandidateCard(
                        candidate = candidate,
                        onClick = { onCandidateClick(candidate.postId) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(GRID_COLUMN_COUNT - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun CandidateCard(
    candidate: MatchCandidate,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedSurface(modifier = modifier.clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(MeonggoSpacing.small)) {
            Box {
                Thumbnail(
                    url = candidate.thumbnailUrl,
                    contentDescription = "후보 동물 사진",
                    radius = MeonggoSpacing.medium,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                )
                // 순위는 사진 위에 얹는다. 사진 위에 따로 한 줄을 두면 카드가 그만큼 길어지고,
                // 견줘야 할 사진이 작아진다.
                RankBadge(candidate.rank, Modifier.align(Alignment.TopStart).padding(6.dp))
                candidate.mark()?.let { mark ->
                    CardMark(mark, Modifier.align(Alignment.BottomStart).padding(6.dp))
                }
            }
            Text(
                text = candidate.kindLabel(),
                modifier = Modifier.padding(top = MeonggoSpacing.small),
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            candidate.traitLabel()?.let { CardInfoLine(it) }
            CardInfoLine(candidate.publicLocation)
            CardInfoLine(candidate.intakeSummary())
        }
    }
}

/**
 * 디자인의 배지 자리에 순위를 표시합니다.
 *
 * 유사도 백분율과 정확한 거리는 M1이 응답하지 않으며 사용자에게 표시하지 않습니다
 * (`docs/api-spec.md` M1, `docs/product/wireframes/README.md` 06).
 */
@Composable
private fun RankBadge(
    rank: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.semantics { contentDescription = "닮은 순서 ${rank}위" },
        shape = BADGE_SHAPE,
        color = MaterialTheme.colorScheme.primary,
    ) {
        Text(
            // 사진 위라 자리가 좁다. "닮은 순서" 는 구역 제목이 이미 말했으므로 숫자만 둔다.
            text = "${rank}위",
            modifier = Modifier.padding(horizontal = MeonggoSpacing.small, vertical = 3.dp),
            color = MaterialTheme.colorScheme.onPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** 사진 위에 얹는 단서입니다 — 지금 보호 중이 아니거나, 보호소가 아닌 후보일 때만 붙는다. */
@Composable
private fun CardMark(
    label: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = BADGE_SHAPE,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = MeonggoSpacing.small, vertical = 3.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** 디자인의 결과 없음 카드입니다. 처리 실패와 조회 불가도 같은 형태로 표시합니다. */
@Composable
private fun ResultNoticeCard(
    title: String,
    description: String,
    isRequesting: Boolean,
    primaryText: String,
    onPrimaryClick: () -> Unit,
    secondaryText: String? = null,
    onSecondaryClick: (() -> Unit)? = null,
    footnote: String? = null,
    isError: Boolean = false,
) {
    OutlinedSurface(container = MaterialTheme.colorScheme.surfaceVariant) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MeonggoSpacing.extraLarge, vertical = MeonggoSpacing.doubleExtraLarge),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            NoticeBadge(isError = isError)
            Text(
                text = title,
                modifier = Modifier.padding(top = MeonggoSpacing.extraLarge),
                style = MaterialTheme.typography.titleLarge,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = description,
                modifier = Modifier.padding(top = MeonggoSpacing.large),
                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            MeonggoButton(
                text = primaryText,
                onClick = onPrimaryClick,
                modifier = Modifier.fillMaxWidth().padding(top = MeonggoSpacing.doubleExtraLarge),
                isLoading = isRequesting,
                loadingStateDescription = "분석을 요청하는 중",
            )
            if (secondaryText != null && onSecondaryClick != null) {
                MeonggoOutlinedButton(
                    text = secondaryText,
                    onClick = onSecondaryClick,
                    modifier = Modifier.fillMaxWidth().padding(top = MeonggoSpacing.small),
                    enabled = !isRequesting,
                )
            }
            footnote?.let {
                Text(
                    text = it,
                    modifier = Modifier.padding(top = MeonggoSpacing.medium),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * 안내 카드의 원형 배지입니다. 유사도 분석 진행 화면의 배지와 같은 모양이다.
 *
 * 그림 문자(🔍·⚠️·🔒) 대신 직접 그린 발자국을 둔다. 무슨 일인지는 바로 아래 제목이 말하고,
 * 배지는 문제일 때만 색을 바꿔 거든다.
 */
@Composable
private fun NoticeBadge(isError: Boolean) {
    val container =
        if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.primaryContainer
        }
    val foreground =
        if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier.size(NoticeBadgeSize).clip(CircleShape).background(container),
        contentAlignment = Alignment.Center,
    ) {
        PawIcon(color = foreground, iconSize = NoticeBadgeIconSize)
    }
}

/**
 * 이 화면의 카드 한 장입니다. 게시물 목록 카드와 같은 면 — 22dp 모서리, 순백, 갈색 그림자.
 *
 * 테두리는 두르지 않는다. 목록·홈이 그림자만으로 경계를 잡는데 이 화면만 선을 두르고 있었다.
 */
@Composable
private fun OutlinedSurface(
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainerLowest,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth().meonggoFloatingShadow(),
        shape = MeonggoSurfaces.cardShape,
        color = container,
    ) {
        content()
    }
}

@Composable
private fun Thumbnail(
    url: String?,
    contentDescription: String,
    radius: Dp,
    modifier: Modifier = Modifier,
) {
    // 사진 칸은 게시물 목록과 같은 부품이다 — 사진이 없거나 원본이 지워졌으면 "사진 없음" 그림.
    MeonggoPhotoThumbnail(
        imageUrl = url,
        contentDescription = contentDescription,
        modifier = modifier,
        shape = RoundedCornerShape(radius),
    )
}

/**
 * 요약 카드의 한 줄입니다. 줄머리 그림은 직접 그린 아이콘을 받는다.
 *
 * 예전에는 🐾·📍·🗓 그림 문자를 넘겼는데, 기기마다 모양과 색이 달라 같은 줄의 글씨와 크기가
 * 맞지 않았다. 목록 화면은 이미 그린 아이콘을 쓰고 있어 두 화면이 서로 달라 보이기도 했다.
 */
@Composable
private fun IconLine(
    text: String,
    icon: @Composable (Color) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        icon(MaterialTheme.colorScheme.primary)
        Text(
            text = text,
            modifier = Modifier.padding(start = MeonggoSpacing.extraSmall),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CardInfoLine(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun NoticeBanner(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MeonggoSurfaces.cardShape,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(MeonggoSpacing.medium),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun LoadingSection() {
    Box(
        modifier = Modifier.fillMaxWidth().height(LoadingSectionHeight),
        contentAlignment = Alignment.Center,
    ) {
        MeonggoLoadingIndicator(contentDescription = "유사 후보를 불러오는 중")
    }
}

/**
 * 요약 카드의 첫 줄 — 이름과 품종입니다.
 *
 * 이름 없이 올린 게시물이 많아 품종(없으면 축종)만 남을 때가 잦다. 색은 넣지 않는다 —
 * 후보 카드와 같은 까닭으로, 공공·사용자 표기가 길고 제각각이라 줄이 들쭉날쭉해진다.
 * 성별은 아래 발자국 줄이 맡는다.
 */
private fun MatchBaseSummary.headline(): String =
    listOfNotNull(
        name?.takeIf(String::isNotBlank),
        breedName?.let(::stripSpeciesPrefix)?.takeIf(String::isNotBlank) ?: speciesLabel(species),
    ).joinToString(" · ")

private fun MatchBaseSummary.eventSummary(): String = listOfNotNull(eventDate.replace("-", "."), eventTime?.take(5), "실종").joinToString(" ")

/**
 * 카드 제목 — 품종입니다.
 *
 * 공공데이터의 품종명은 `[개] 포메라니안` 처럼 축종을 대괄호로 달고 온다. 그대로 쓰면 카드
 * 제목에 `[개]` 가 박히고, 색까지 앞에 붙이면 `흰색 [개] 포메라니안 (암컷)` 이 되어 두 줄로
 * 잘렸다. 축종 표기를 떼고 품종만 남기며, 색과 성별은 아랫줄로 내린다.
 */
private fun MatchCandidate.kindLabel(): String = breedName?.let(::stripSpeciesPrefix)?.takeIf(String::isNotBlank) ?: speciesLabel(species)

/**
 * 제목 밑 한 줄 — 성별.
 *
 * 색은 넣지 않는다. 공공데이터의 색 표기가 "붉고 엷은 황갈색" 처럼 길고 제각각이라 카드마다
 * 줄 길이가 들쭉날쭉했다. 색은 사진이 이미 보여 준다.
 */
private fun MatchCandidate.traitLabel(): String? = sexLabel(sex).takeIf { sex != "UNKNOWN" }

/** 사진 위 단서 — 보호소이면서 보호 중인 흔한 경우에는 아무것도 붙이지 않는다. */
private fun MatchCandidate.mark(): String? =
    when {
        source != "SHELTER" -> "사용자 게시물"
        status == "CLOSED" -> "과거 기록"
        else -> null
    }

private val SPECIES_TAG = Regex("""^\s*\[[^\]]*]\s*""")

private fun stripSpeciesTag(breedName: String): String = breedName.replace(SPECIES_TAG, "")

private fun MatchCandidate.intakeSummary(): String {
    val parts = eventDate.split("-")
    val month = parts.getOrNull(1)?.toIntOrNull()
    val day = parts.getOrNull(2)?.toIntOrNull()
    return if (month != null && day != null) "${month}월 ${day}일 입소" else eventDate.replace("-", ".")
}

private fun sexLabel(sex: String): String =
    when (sex) {
        "MALE" -> "수컷"
        "FEMALE" -> "암컷"
        else -> "성별 모름"
    }

private val LoadingSectionHeight = 240.dp
private val NoticeBadgeSize = 96.dp
private val NoticeBadgeIconSize = 40.dp

/** 글이 유난히 짧을 때 사진이 너무 작아지지 않게 잡아 두는 아래 한계. */
private val SummaryThumbnailMin = 72.dp

/** 구역 제목 크기. 화면 이름과 같은 20sp — 이 화면에서 본문을 여는 줄이다. */
private val SECTION_TITLE_SIZE = 20.sp

/** 요약 줄머리 그림의 크기. 옆에 오는 본문 글씨와 눈높이가 맞는 크기다. */
private val INFO_ICON_SIZE = 14.dp

/** 핀만 원본 비(22:24)를 지켜 조금 좁다. */
private val PIN_ICON_WIDTH = 13.dp

/** 순위 배지는 목록 화면의 배지와 같은 알약 모양이다. */
private val BADGE_SHAPE = RoundedCornerShape(percent = 50)
private const val GRID_COLUMN_COUNT = 2

@Preview(showBackground = true, widthDp = 390, heightDp = 900, backgroundColor = 0xFFFFFFFF)
@Composable
private fun MatchCandidatesFoundPreview() {
    MeonggoBanjeomTheme {
        MatchCandidatesScreen(
            uiState =
                MatchCandidatesUiState(
                    phase = MatchCandidatesPhase.CANDIDATES,
                    animalName = "콩이",
                    baseSummary = previewSummary(),
                    candidates = (1..8).map(::previewCandidate),
                ),
            onBackClick = {},
            onRetryClick = {},
            onRequestAnalysis = {},
            onExpandCandidates = {},
            onCandidateClick = {},
            onEditPostClick = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 900, backgroundColor = 0xFFFFFFFF)
@Composable
private fun MatchCandidatesEmptyPreview() {
    MeonggoBanjeomTheme {
        MatchCandidatesScreen(
            uiState =
                MatchCandidatesUiState(
                    phase = MatchCandidatesPhase.EMPTY,
                    animalName = "콩이",
                    baseSummary = previewSummary(),
                ),
            onBackClick = {},
            onRetryClick = {},
            onRequestAnalysis = {},
            onExpandCandidates = {},
            onCandidateClick = {},
            onEditPostClick = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 900, backgroundColor = 0xFFFFFFFF)
@Composable
private fun MatchCandidatesStalePreview() {
    MeonggoBanjeomTheme {
        MatchCandidatesScreen(
            uiState =
                MatchCandidatesUiState(
                    phase = MatchCandidatesPhase.CANDIDATES,
                    animalName = "콩이",
                    baseSummary = previewSummary(),
                    isStale = true,
                    candidates = (1..3).map(::previewCandidate),
                ),
            onBackClick = {},
            onRetryClick = {},
            onRequestAnalysis = {},
            onExpandCandidates = {},
            onCandidateClick = {},
            onEditPostClick = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 900, backgroundColor = 0xFFFFFFFF)
@Composable
private fun MatchCandidatesFailedPreview() {
    MeonggoBanjeomTheme {
        MatchCandidatesScreen(
            uiState =
                MatchCandidatesUiState(
                    phase = MatchCandidatesPhase.FAILED,
                    animalName = "콩이",
                    baseSummary = previewSummary(),
                    errorMessage = "분석 시간이 초과되었습니다. 다시 시도해 주세요.",
                ),
            onBackClick = {},
            onRetryClick = {},
            onRequestAnalysis = {},
            onExpandCandidates = {},
            onCandidateClick = {},
            onEditPostClick = {},
        )
    }
}

private fun previewSummary() =
    MatchBaseSummary(
        postId = 1002,
        name = "콩이",
        species = "DOG",
        breedName = "푸들",
        sex = "MALE",
        color = "갈색",
        eventDate = "2026-08-20",
        eventTime = "15:30:00",
        publicLocation = "서울 마포구 망원동",
        thumbnailUrl = null,
    )

private fun previewCandidate(rank: Int) =
    MatchCandidate(
        rank = rank,
        postId = 1000L + rank,
        source = if (rank % 3 == 0) "USER_POST" else "SHELTER",
        status = if (rank == 3) "CLOSED" else "ACTIVE",
        name = null,
        species = "DOG",
        breedName = if (rank == 3) "크림 푸들" else "푸들",
        sex = if (rank == 2) "FEMALE" else "MALE",
        color = if (rank == 3) null else "갈색",
        eventDate = "2026-08-${20 + rank}",
        publicLocation = "서울 은평구",
        thumbnailUrl = null,
        authorNickname = if (rank % 3 == 0) "망고보호자" else null,
        shelterName = if (rank % 3 != 0) "마포구 동물보호센터" else null,
        shelterPhone = if (rank % 3 != 0) "02-1234-1234" else null,
    )
