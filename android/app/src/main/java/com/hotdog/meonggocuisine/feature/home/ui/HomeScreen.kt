package com.hotdog.meonggocuisine.feature.home.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotdog.meonggocuisine.R
import com.hotdog.meonggocuisine.core.designsystem.component.LocationPinIcon
import com.hotdog.meonggocuisine.core.designsystem.theme.MeonggoBanjeomTheme
import com.hotdog.meonggocuisine.core.designsystem.token.MeonggoCardColors
import com.hotdog.meonggocuisine.core.designsystem.token.MeonggoSpacing
import com.hotdog.meonggocuisine.feature.community.data.LostPostSummary
import com.hotdog.meonggocuisine.feature.community.ui.BrandHeader
import com.hotdog.meonggocuisine.feature.community.ui.CommunityBottomBar
import com.hotdog.meonggocuisine.feature.community.ui.CommunityTabType
import com.hotdog.meonggocuisine.feature.community.ui.PostThumbnail
import com.hotdog.meonggocuisine.feature.home.data.DailySummary
import com.hotdog.meonggocuisine.feature.home.data.HomeInsights

@Composable
fun HomeRouteScreen(
    onLostCreateClick: () -> Unit,
    onShelteringCreateClick: () -> Unit,
    onLostListClick: () -> Unit,
    onShelteringListClick: () -> Unit,
    onRegionChangeClick: () -> Unit,
    onPostClick: (Long) -> Unit,
    onProfileClick: () -> Unit,
    onAdoptionClick: () -> Unit,
    onChatClick: () -> Unit,
    onInsightsClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 지역 변경이나 게시물 등록을 마치고 돌아오면 화면이 이미 백스택에 있어 init 이 다시 돌지
    // 않는다. 다시 보일 때마다 불러와 목록이 뒤처지지 않게 한다.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    HomeScreen(
        uiState = uiState,
        onLostCreateClick = onLostCreateClick,
        onShelteringCreateClick = onShelteringCreateClick,
        onLostListClick = onLostListClick,
        onShelteringListClick = onShelteringListClick,
        onRegionChangeClick = onRegionChangeClick,
        onPostClick = onPostClick,
        onProfileClick = onProfileClick,
        onAdoptionClick = onAdoptionClick,
        onChatClick = onChatClick,
        onInsightsClick = onInsightsClick,
        modifier = modifier,
    )
}

@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onLostCreateClick: () -> Unit,
    onShelteringCreateClick: () -> Unit,
    onLostListClick: () -> Unit,
    onShelteringListClick: () -> Unit,
    onRegionChangeClick: () -> Unit,
    onPostClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onProfileClick: (() -> Unit)? = null,
    onAdoptionClick: () -> Unit = {},
    onChatClick: (() -> Unit)? = null,
    onInsightsClick: () -> Unit = {},
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { BrandHeader(onProfileClick, onChatClick) },
        bottomBar = {
            CommunityBottomBar(
                selectedTab = CommunityTabType.HOME,
                onHomeTabClick = {},
                onLostTabClick = onLostListClick,
                onShelteringTabClick = onShelteringListClick,
            )
        },
    ) { contentPadding ->
        // 홈은 스크롤하지 않는다. 헤더·안내 문구·세 갈래 카드·하단 탭이 한 화면에 함께 보여야
        // 사용자가 무엇을 하러 왔는지 바로 고를 수 있다.
        //
        // 일일 요약(InsightPager)과 목록 미리보기(ShelteringPreviewSection·LostPreviewSection)는
        // 지금 화면에 걸지 않는다. 다시 넣을 자리가 정해지면 여기서 호출만 되살리면 되므로
        // 아래 Composable 은 그대로 둔다.
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .padding(horizontal = 16.dp),
        ) {
            // 안내 문구 자리에 한 줄 소식 띠 — 카드 5장의 숫자를 10초마다 한 문장씩, 누르면 소식 화면(2026-09-22 결정).
            // 되돌리려면 이 호출을 Text("어떤 도움이 필요하신가요?") 안내 문구로 바꾸면 된다 (InsightTicker 주석 참고).
            InsightTicker(
                insights = uiState.insights,
                regionName = uiState.regionName,
                onClick = onInsightsClick,
                modifier = Modifier.padding(top = MeonggoSpacing.medium, bottom = 18.dp),
            )
            // 안내 문구와 하단 탭 사이의 남은 높이를 세 카드가 똑같이 나눠 갖는다.
            HomeActionCard(
                title = "잃어버렸어요",
                description = "찾는 게시물 등록하기",
                illustration = R.drawable.home_card_lost,
                surface = MeonggoCardColors.lostSurface,
                titleColor = MaterialTheme.colorScheme.onSurface,
                descriptionColor = MaterialTheme.colorScheme.onSurface.copy(alpha = MUTED_TEXT_ALPHA),
                onClick = onLostCreateClick,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.height(CARD_GAP))
            HomeActionCard(
                title = "보호하고 있어요",
                description = "보호 게시물 등록하기",
                illustration = R.drawable.home_card_sheltering,
                surface = MeonggoCardColors.shelteringSurface,
                titleColor = MaterialTheme.colorScheme.onSurface,
                descriptionColor = MaterialTheme.colorScheme.onSurface.copy(alpha = MUTED_TEXT_ALPHA),
                onClick = onShelteringCreateClick,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.height(CARD_GAP))
            HomeActionCard(
                title = "새 가족을 기다려요",
                description = "입양 목록 보기",
                illustration = R.drawable.home_card_adoption,
                surface = MeonggoCardColors.adoptionSurface,
                titleColor = MaterialTheme.colorScheme.onSurface,
                descriptionColor = MaterialTheme.colorScheme.onSurface.copy(alpha = MUTED_TEXT_ALPHA),
                onClick = onAdoptionClick,
                modifier = Modifier.weight(1f),
                borderColor = MeonggoCardColors.homeCardBorderSoft,
            )
            Spacer(Modifier.height(MeonggoSpacing.extraSmall))
        }
    }
}

/**
 * 홈의 주 갈래 한 장입니다. 사용자는 잃어버린 쪽이거나, 보호 중인 쪽이거나, 새 가족을 찾는 쪽이다.
 */
@Composable
private fun HomeActionCard(
    title: String,
    description: String,
    @DrawableRes illustration: Int,
    surface: Color,
    titleColor: Color,
    descriptionColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    borderColor: Color = MeonggoCardColors.homeCardBorder,
) {
    Surface(
        // 경계가 보일 만큼만 띄운다. 더 높이면 그림자가 테두리에 그라데이션으로 번진다.
        //
        // Surface 의 shadowElevation 대신 색을 지정할 수 있는 shadow 를 건다 — 기본 검정으로
        // 두면 밝은 면 옆에서만 띠가 도드라져 세 장이 따로 논다 (MeonggoCardColors.homeCardShadow).
        modifier =
            modifier.fillMaxWidth().shadow(
                CARD_ELEVATION,
                CARD_SHAPE,
                ambientColor = MeonggoCardColors.homeCardShadow,
                spotColor = MeonggoCardColors.homeCardShadow,
            ),
        shape = CARD_SHAPE,
        color = surface,
        // 테두리 진하기는 면이 제 힘으로 바탕과 갈리는 정도에 따라 다르다. 흰 카드는 자기 색이
        // 없어 그림자를 옅게 한 뒤로 경계가 사라졌으니 제값으로 두르고, 크림 카드는 노란기가
        // 옅어 같은 선을 두르면 선이 면보다 먼저 보이므로 눌러 둔다([borderColor], 2026-09-24).
        border = BorderStroke(CARD_BORDER_WIDTH, borderColor),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 그림 칸과 글 칸을 세 카드에서 똑같은 weight 로 나눈다. 일러스트마다 가로세로 비가
            // 달라도 제목이 같은 세로선에서 시작하고, 좁은 기기에서는 두 칸이 함께 줄어 제목이
            // 잘리지 않는다 (고정 dp 로 두면 360dp 기기에서 "새 가족을 기다려요"가 잘린다).
            Box(
                modifier = Modifier.weight(ILLUSTRATION_COLUMN_WEIGHT).fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                // 그림 칸은 카드 왼쪽 끝부터 제목이 시작하는 자리까지다. 그 안에서 그림을 가운데에
                // 두면 가로로 넓은 그림(가족)과 좁은 그림(고양이)이 서로 다른 폭이어도 한 덩어리로
                // 정렬돼 보인다. 제목 위치는 칸 너비가 정하므로 이 정렬과 무관하게 셋이 같다.
                //
                // 원본 여백은 잘라 둬서 그림이 칸을 꽉 채운다. 사방 [ILLUSTRATION_MARGIN] 을 둬
                // 카드의 둥근 모서리에 닿거나 제목에 붙지 않게 한다.
                Image(
                    painter = painterResource(illustration),
                    contentDescription = null,
                    modifier = Modifier.fillMaxHeight().padding(ILLUSTRATION_MARGIN),
                    contentScale = ContentScale.Fit,
                )
            }
            Column(modifier = Modifier.weight(TEXT_COLUMN_WEIGHT)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontSize = 20.sp,
                    lineHeight = 26.sp,
                    letterSpacing = (-0.5).sp,
                    color = titleColor,
                    maxLines = 1,
                )
                Spacer(Modifier.height(MeonggoSpacing.small))
                Text(
                    description,
                    style = MaterialTheme.typography.bodyLarge,
                    fontSize = 14.5.sp,
                    color = descriptionColor,
                    maxLines = 1,
                )
            }
            // 오른쪽 여백. 예전에는 꺾쇠가 있던 자리로, 글이 카드 끝에 닿지 않게 남겨 둔다.
            Spacer(Modifier.width(MeonggoSpacing.large))
        }
    }
}

private val CARD_GAP = 14.dp

/**
 * 안내 문구와 카드 보조문구의 진하기.
 *
 * `onSurfaceVariant` 로는 파스텔 면 위에서 대비가 4.4:1 까지 떨어진다. 제목보다는 물러나되
 * 읽히도록 본문 색을 옅게 써서 5.1:1 을 확보한다.
 */
private const val MUTED_TEXT_ALPHA = 0.72f

/** 카드 모양. 면을 감싸는 Surface 와 그 위의 명암이 같은 값을 써야 테두리가 어긋나지 않는다. */
private val CARD_SHAPE = RoundedCornerShape(22.dp)

/** 카드를 띄우는 높이. 경계만 짚으면 되므로 낮게 둔다 — 색은 [MeonggoCardColors.homeCardShadow]. */
private val CARD_ELEVATION = 1.dp

/** 카드 테두리 굵기. 선으로 읽히면 안 되고 면이 끝나는 자리만 짚어야 한다. */
private val CARD_BORDER_WIDTH = 1.dp

/** 그림 칸 사방에 두는 최소 여백. 가로로 가장 넓은 가족 그림이 이 선까지만 다가온다. */
private val ILLUSTRATION_MARGIN = 20.dp

/** 카드 안에서 그림 칸과 글 칸이 나눠 갖는 비율. 세 카드가 같은 값을 써야 제목이 나란히 선다. */
private const val ILLUSTRATION_COLUMN_WEIGHT = 0.43f
private const val TEXT_COLUMN_WEIGHT = 0.57f

@Composable
private fun ShelteringPreviewSection(
    regionName: String?,
    posts: List<LostPostSummary>,
    onMoreClick: () -> Unit,
    onRegionChangeClick: () -> Unit,
    onPostClick: (Long) -> Unit,
) {
    Column {
        SectionHeader("내 지역 보호 중", onMoreClick)
        Spacer(Modifier.height(MeonggoSpacing.medium))
        if (regionName == null) {
            SectionPlaceholder(
                message = "관심 지역을 선택하면 보호 중인 아이를 보여드려요",
                actionLabel = "지역 선택",
                onActionClick = onRegionChangeClick,
            )
            return@Column
        }
        RegionChip(regionName, onRegionChangeClick)
        Spacer(Modifier.height(MeonggoSpacing.medium))
        if (posts.isEmpty()) {
            SectionPlaceholder(
                message = "$regionName 에 보호 중인 동물이 없어요",
                actionLabel = "지역 변경",
                onActionClick = onRegionChangeClick,
            )
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(MeonggoSpacing.medium)) {
                items(posts, key = { it.postId }) { post ->
                    ShelteringPreviewItem(post, onPostClick)
                }
            }
        }
    }
}

@Composable
private fun ShelteringPreviewItem(
    post: LostPostSummary,
    onPostClick: (Long) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .width(104.dp)
                .clickable { onPostClick(post.postId) },
    ) {
        Box {
            PostThumbnail(
                imageUrl = post.thumbnailUrl,
                contentDescription = "${post.breedName ?: post.species} 사진",
                modifier = Modifier.size(104.dp),
            )
            PreviewSourceBadge(
                isShelter = post.source == "SHELTER",
                modifier = Modifier.padding(MeonggoSpacing.small),
            )
        }
        Spacer(Modifier.height(MeonggoSpacing.small))
        Text(
            post.breedName ?: post.species,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            post.eventDate,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun PreviewSourceBadge(
    isShelter: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color =
            if (isShelter) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
    ) {
        Text(
            if (isShelter) "보호소" else "사용자",
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            color =
                if (isShelter) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.primary
                },
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun LostPreviewSection(
    posts: List<LostPostSummary>,
    onMoreClick: () -> Unit,
    onCreateClick: () -> Unit,
    onPostClick: (Long) -> Unit,
) {
    Column {
        SectionHeader("최근 실종 신고", onMoreClick)
        Spacer(Modifier.height(MeonggoSpacing.medium))
        if (posts.isEmpty()) {
            SectionPlaceholder(
                message = "아직 등록된 실종 신고가 없어요",
                actionLabel = "첫 신고 등록",
                onActionClick = onCreateClick,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(MeonggoSpacing.small)) {
                posts.forEach { post -> LostPreviewCard(post, onPostClick) }
            }
        }
    }
}

@Composable
private fun LostPreviewCard(
    post: LostPostSummary,
    onPostClick: (Long) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        onClick = { onPostClick(post.postId) },
    ) {
        Row(
            modifier = Modifier.padding(MeonggoSpacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PostThumbnail(
                imageUrl = post.thumbnailUrl,
                contentDescription = "${post.breedName ?: post.species} 사진",
                modifier = Modifier.size(72.dp),
            )
            Column(Modifier.padding(start = MeonggoSpacing.medium)) {
                Text(
                    "${post.breedName ?: post.species}를 잃어버렸어요",
                    style = MaterialTheme.typography.titleSmall,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(MeonggoSpacing.extraSmall))
                Text(
                    listOfNotNull(post.color, post.publicLocation).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(MeonggoSpacing.small))
                EventDateTag(post.eventDate)
            }
        }
    }
}

/**
 * 실종 신고에만 붙이는 경과 표시입니다. 보호 중 카드에 같은 색을 쓰면 "빨강 = 급함" 이
 * 흐려지므로 여기서만 사용한다.
 */
@Composable
private fun EventDateTag(eventDate: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Text(
            "$eventDate 실종",
            modifier = Modifier.padding(horizontal = MeonggoSpacing.small, vertical = 3.dp),
            color = MaterialTheme.colorScheme.error,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun SectionHeader(
    title: String,
    onMoreClick: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontSize = 16.sp)
        Spacer(Modifier.weight(1f))
        Text(
            "더보기 ›",
            modifier = Modifier.clickable(onClick = onMoreClick),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun RegionChip(
    regionName: String,
    onRegionChangeClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        onClick = onRegionChangeClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = MeonggoSpacing.medium, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LocationPinIcon(Modifier.size(width = 12.dp, height = 14.dp))
            Text(
                regionName,
                modifier = Modifier.padding(start = MeonggoSpacing.small),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun SectionPlaceholder(
    message: String,
    actionLabel: String,
    onActionClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        onClick = onActionClick,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(MeonggoSpacing.small))
            Text(
                "$actionLabel ›",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
            )
        }
    }
}

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun HomeScreenPreview() {
    MeonggoBanjeomTheme {
        HomeScreen(
            uiState =
                HomeUiState(
                    isLoading = false,
                    insights =
                        HomeInsights(
                            dailyIntake =
                                DailySummary(
                                    ingestionRunId = 1,
                                    summaryDate = "2026-09-13",
                                    animalCount = 46,
                                    shelterCount = 82,
                                    completedAt = "2026-09-13T14:10:00Z",
                                ),
                        ),
                    regionName = "경상북도 포항시 북구",
                ),
            onLostCreateClick = {},
            onShelteringCreateClick = {},
            onLostListClick = {},
            onShelteringListClick = {},
            onRegionChangeClick = {},
            onPostClick = {},
        )
    }
}
