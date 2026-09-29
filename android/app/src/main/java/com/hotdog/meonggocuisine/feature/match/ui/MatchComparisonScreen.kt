package com.hotdog.meonggocuisine.feature.match.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotdog.meonggocuisine.core.designsystem.component.CheckIcon
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoButton
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoLoadingIndicator
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoOutlinedButton
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoPhotoThumbnail
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoScreenHeader
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoSurfaces
import com.hotdog.meonggocuisine.core.designsystem.component.meonggoFloatingShadow
import com.hotdog.meonggocuisine.core.designsystem.theme.MeonggoBanjeomTheme
import com.hotdog.meonggocuisine.core.designsystem.token.MeonggoRadius
import com.hotdog.meonggocuisine.core.designsystem.token.MeonggoSpacing
import com.hotdog.meonggocuisine.feature.match.data.MatchComparison
import com.hotdog.meonggocuisine.feature.match.data.MatchComparisonAnimal

@Composable
fun MatchComparisonRouteScreen(
    onBackClick: () -> Unit,
    onShelterCallClick: (String) -> Unit,
    onChatClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MatchComparisonViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    MatchComparisonScreen(
        uiState = uiState,
        onBackClick = onBackClick,
        onRetryClick = viewModel::retry,
        onShelterCallClick = onShelterCallClick,
        onChatClick = { onChatClick(viewModel.candidatePostId) },
        modifier = modifier,
    )
}

@Composable
fun MatchComparisonScreen(
    uiState: MatchComparisonUiState,
    onBackClick: () -> Unit,
    onRetryClick: () -> Unit,
    onShelterCallClick: (String) -> Unit,
    onChatClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
    ) {
        MeonggoScreenHeader(title = "비교 상세", onBackClick = onBackClick)
        when {
            uiState.isLoading -> CenteredBox { MeonggoLoadingIndicator("비교 정보를 불러오는 중") }

            uiState.comparison == null ->
                CenteredBox {
                    Column(
                        modifier = Modifier.padding(horizontal = MeonggoSpacing.extraLarge),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = uiState.errorMessage ?: "비교 정보를 불러오지 못했습니다.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                        MeonggoOutlinedButton(
                            text = "다시 시도",
                            onClick = onRetryClick,
                            modifier = Modifier.padding(top = MeonggoSpacing.large),
                        )
                    }
                }

            else ->
                ComparisonContent(
                    comparison = uiState.comparison,
                    reasons = uiState.recommendationReasons,
                    onShelterCallClick = onShelterCallClick,
                    onChatClick = onChatClick,
                )
        }
    }
}

@Composable
private fun ComparisonContent(
    comparison: MatchComparison,
    reasons: List<String>,
    onShelterCallClick: (String) -> Unit,
    onChatClick: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MeonggoSurfaces.gutter, vertical = MeonggoSpacing.large),
        verticalArrangement = Arrangement.spacedBy(MeonggoSpacing.large),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            ColumnLabel("내 반려동물", Modifier.weight(1f))
            Spacer(Modifier.width(VsBadgeSize))
            ColumnLabel("보호소 동물".takeIf { comparison.candidate.isShelter } ?: "보호 중인 동물", Modifier.weight(1f))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            AnimalColumn(comparison.mine, Modifier.weight(1f))
            VsBadge()
            AnimalColumn(comparison.candidate, Modifier.weight(1f))
        }

        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(MeonggoSpacing.medium),
        ) {
            InfoCard(
                title = if (comparison.candidate.isShelter) "보호소 정보" else "발견 정보",
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                comparison.candidate.detailRows().forEach { (label, value) ->
                    LabeledRow(label, value)
                }
            }
            InfoCard(
                title = "왜 이 동물을 추천했나요?",
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                reasons.forEach { CheckLine(it) }
            }
        }

        Text(
            text = "같은 아이로 확정된 결과가 아니에요. 사진과 날짜, 지역을 직접 확인해 주세요.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        CandidateAction(
            candidate = comparison.candidate,
            onShelterCallClick = onShelterCallClick,
            onChatClick = onChatClick,
        )
        Spacer(Modifier.height(MeonggoSpacing.large))
    }
}

@Composable
private fun ColumnLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun AnimalColumn(
    animal: MatchComparisonAnimal,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 사진 칸은 게시물 목록과 같은 부품이다 — 사진이 없거나 원본이 지워졌으면 "사진 없음" 그림.
        MeonggoPhotoThumbnail(
            imageUrl = animal.thumbnailUrl,
            contentDescription = "${animal.displayName} 사진",
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            shape = RoundedCornerShape(PhotoRadius),
        )
        Text(
            text = animal.displayName,
            modifier = Modifier.padding(top = MeonggoSpacing.medium),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = animal.attributeSummary,
            modifier = Modifier.padding(top = MeonggoSpacing.extraSmall),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun VsBadge() {
    Box(
        modifier =
            Modifier
                .padding(top = VsBadgeTopPadding)
                .size(VsBadgeSize)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "VS",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun InfoCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // 게시물 목록 카드와 같은 면이다 — 22dp 모서리, 순백, 테두리 없이 갈색 그림자로만 경계.
    Surface(
        modifier = modifier.meonggoFloatingShadow(),
        shape = MeonggoSurfaces.cardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Column(
            modifier = Modifier.padding(MeonggoSpacing.large),
            verticalArrangement = Arrangement.spacedBy(MeonggoSpacing.medium),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            content()
        }
    }
}

@Composable
private fun LabeledRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MeonggoSpacing.small),
    ) {
        Text(
            text = label,
            modifier = Modifier.width(LabelWidth),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun CheckLine(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(MeonggoSpacing.small)) {
        // 글리프 대신 직접 그린 체크. 기기 글꼴마다 굵기가 달라 어떤 기기에서는 묻혔다.
        CheckIcon(
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = CheckIconTopPadding),
            iconSize = CheckIconSize,
        )
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 후보 출처에 따른 연락 동선입니다.
 *
 * 공공 보호동물은 보호센터 공식 전화번호로, 다른 회원의 활성 사용자 게시물은 1:1 채팅으로
 * 연결합니다. 본인 게시물과 종료된 게시물에는 채팅을 제공하지 않습니다.
 */
@Composable
private fun CandidateAction(
    candidate: MatchComparisonAnimal,
    onShelterCallClick: (String) -> Unit,
    onChatClick: () -> Unit,
) {
    when {
        candidate.isShelter && candidate.shelterPhone != null ->
            MeonggoButton(
                text = "보호소에 전화하기",
                onClick = { onShelterCallClick(candidate.shelterPhone) },
                modifier = Modifier.fillMaxWidth(),
            )

        candidate.isShelter ->
            Text(
                text = "이 보호소의 공식 연락처가 등록되지 않았어요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

        candidate.chatAvailable ->
            MeonggoButton(
                text = "작성자와 채팅하기",
                onClick = onChatClick,
                modifier = Modifier.fillMaxWidth(),
            )

        else ->
            Text(
                text = "이 게시물에는 채팅을 시작할 수 없어요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
    }
}

@Composable
private fun CenteredBox(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

/** 디자인의 라벨·값 행입니다. 정확한 위치와 좌표는 담지 않습니다. */
private fun MatchComparisonAnimal.detailRows(): List<Pair<String, String>> =
    buildList {
        add("발견 장소" to publicLocation)
        add("발견 날짜" to eventDate.toDisplayDate())
        shelterProcessState?.let { add("보호 상태" to it) }
        if (isShelter) {
            shelterName?.let { add("보호소" to it) }
            shelterAddress?.let { add("주소" to it) }
            shelterPhone?.let { add("연락처" to it) }
        } else {
            currentLocation?.let { add("보호 장소" to it) }
            authorNickname?.let { add("작성자" to it) }
        }
    }

private val PhotoRadius = MeonggoRadius.large
private val CheckIconSize = 14.dp

/** 체크는 줄 첫 글자의 가운데 높이에 맞춘다. 위에 붙이면 글씨보다 먼저 눈에 띈다. */
private val CheckIconTopPadding = 3.dp
private val VsBadgeSize = 48.dp
private val VsBadgeTopPadding = 56.dp
private val LabelWidth = 60.dp

@Preview(showBackground = true, widthDp = 390, heightDp = 900, backgroundColor = 0xFFFFFFFF)
@Composable
private fun MatchComparisonShelterPreview() {
    MeonggoBanjeomTheme {
        MatchComparisonScreen(
            uiState = MatchComparisonUiState(isLoading = false, comparison = previewComparison(isShelter = true)),
            onBackClick = {},
            onRetryClick = {},
            onShelterCallClick = {},
            onChatClick = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 900, backgroundColor = 0xFFFFFFFF)
@Composable
private fun MatchComparisonUserPostPreview() {
    MeonggoBanjeomTheme {
        MatchComparisonScreen(
            uiState = MatchComparisonUiState(isLoading = false, comparison = previewComparison(isShelter = false)),
            onBackClick = {},
            onRetryClick = {},
            onShelterCallClick = {},
            onChatClick = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 900, backgroundColor = 0xFFFFFFFF)
@Composable
private fun MatchComparisonFailedPreview() {
    MeonggoBanjeomTheme {
        MatchComparisonScreen(
            uiState =
                MatchComparisonUiState(
                    isLoading = false,
                    errorMessage = "비교 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.",
                ),
            onBackClick = {},
            onRetryClick = {},
            onShelterCallClick = {},
            onChatClick = {},
        )
    }
}

private fun previewComparison(isShelter: Boolean) =
    MatchComparison(
        mine =
            MatchComparisonAnimal(
                postId = 1002,
                source = "USER_POST",
                status = "ACTIVE",
                displayName = "콩이",
                attributeSummary = "푸들 · 수컷 · 갈색",
                eventDate = "2026-08-20",
                eventTime = "15:30:00",
                publicLocation = "서울 마포구 망원동",
                currentLocation = null,
                featureText = "갈색 목줄을 착용하고 있어요",
                thumbnailUrl = null,
                authorNickname = "콩이주인",
                shelterName = null,
                shelterPhone = null,
                shelterAddress = null,
                shelterNoticeNo = null,
                shelterProcessState = null,
                chatAvailable = false,
            ),
        candidate =
            MatchComparisonAnimal(
                postId = 2001,
                source = if (isShelter) "SHELTER" else "USER_POST",
                status = "ACTIVE",
                displayName = if (isShelter) "보호소 #12345" else "갈색 푸들",
                attributeSummary = "푸들 · 수컷 · 갈색",
                eventDate = "2026-08-22",
                eventTime = null,
                publicLocation = "서울 은평구 갈현동",
                currentLocation = if (isShelter) null else "서울 은평구",
                featureText = null,
                thumbnailUrl = null,
                authorNickname = if (isShelter) null else "은평보호자",
                shelterName = if (isShelter) "은평구 동물보호센터" else null,
                shelterPhone = if (isShelter) "02-123-4567" else null,
                shelterAddress = if (isShelter) "서울 은평구 갈현로 123" else null,
                shelterNoticeNo = if (isShelter) "12345" else null,
                shelterProcessState = if (isShelter) "보호중" else null,
                chatAvailable = !isShelter,
            ),
    )
