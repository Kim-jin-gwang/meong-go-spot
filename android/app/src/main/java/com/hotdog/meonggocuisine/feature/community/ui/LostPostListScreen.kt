package com.hotdog.meonggocuisine.feature.community.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotdog.meonggocuisine.R
import com.hotdog.meonggocuisine.core.designsystem.component.ChatIcon
import com.hotdog.meonggocuisine.core.designsystem.component.HeartIcon
import com.hotdog.meonggocuisine.core.designsystem.component.HomeIcon
import com.hotdog.meonggocuisine.core.designsystem.component.LocationPinIcon
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoLoadingIndicator
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoPhotoThumbnail
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoSurfaces
import com.hotdog.meonggocuisine.core.designsystem.component.SearchIcon
import com.hotdog.meonggocuisine.core.designsystem.component.meonggoFloatingShadow
import com.hotdog.meonggocuisine.core.designsystem.theme.MeonggoBanjeomTheme
import com.hotdog.meonggocuisine.core.designsystem.token.MeonggoSpacing
import com.hotdog.meonggocuisine.feature.account.ui.PersonIcon
import com.hotdog.meonggocuisine.feature.community.data.LostPostSummary
import com.hotdog.meonggocuisine.feature.community.data.PostListFilter

@Composable
fun LostPostListRouteScreen(
    onPostClick: (Long) -> Unit,
    onCreateClick: () -> Unit,
    onRegionChangeClick: () -> Unit,
    onShelteringTabClick: () -> Unit,
    onProfileClick: () -> Unit,
    onChatClick: () -> Unit,
    onHomeTabClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LostPostListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LostPostListScreen(
        uiState = uiState,
        onSearchQueryChange = viewModel::onSearchQueryChange,
        onSearch = viewModel::search,
        onRetry = viewModel::retry,
        onLoadMore = viewModel::loadMore,
        onFilterApply = viewModel::onFilterApply,
        onPostClick = onPostClick,
        onCreateClick = onCreateClick,
        onRegionChangeClick = onRegionChangeClick,
        onShelteringTabClick = onShelteringTabClick,
        onProfileClick = onProfileClick,
        onChatClick = onChatClick,
        onHomeTabClick = onHomeTabClick,
        modifier = modifier,
    )
}

@Composable
fun LostPostListScreen(
    uiState: LostPostListUiState,
    onSearchQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onFilterApply: (PostListFilter) -> Unit,
    onPostClick: (Long) -> Unit,
    onCreateClick: () -> Unit,
    onRegionChangeClick: () -> Unit,
    onShelteringTabClick: () -> Unit,
    modifier: Modifier = Modifier,
    onProfileClick: (() -> Unit)? = null,
    onChatClick: (() -> Unit)? = null,
    onHomeTabClick: () -> Unit = {},
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { BrandHeader(onProfileClick, onChatClick) },
    ) { contentPadding ->
        // 탭 바는 `bottomBar` 가 아니라 내용 위에 겹쳐 띄운다. 자리를 따로 잡아 주면 목록이 그
        // 선에서 잘려 카드가 사각으로 끊기는데, 겹쳐 두면 카드가 바 밑으로 지나가는 게 좌우·아래
        // 틈으로 보인다.
        Box(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            // 시트를 열어 뒀는지는 화면만 아는 값이라 ViewModel 로 올리지 않는다.
            var isFilterOpen by rememberSaveable { mutableStateOf(false) }
            val listTop: @Composable () -> Unit = {
                CommunityListTop(
                    regionName = uiState.selectedRegionName ?: "전국",
                    onRegionChangeClick = onRegionChangeClick,
                    query = uiState.searchQuery,
                    onQueryChange = onSearchQueryChange,
                    onSearch = onSearch,
                    onCreateClick = onCreateClick,
                    createContentDescription = "실종 게시물 등록",
                    onFilterClick = { isFilterOpen = true },
                    isFilterNarrowed = uiState.filter.isNarrowed,
                )
            }
            if (isFilterOpen) {
                CommunityFilterSheet(
                    applied = uiState.filter,
                    onDismiss = { isFilterOpen = false },
                    onApply = {
                        isFilterOpen = false
                        onFilterApply(it)
                    },
                )
            }
            when {
                uiState.isLoading ->
                    Column(Modifier.fillMaxSize()) {
                        listTop()
                        LoadingContent()
                    }

                uiState.errorMessage != null && uiState.posts.isEmpty() ->
                    Column(Modifier.fillMaxSize()) {
                        listTop()
                        ErrorContent(uiState.errorMessage, onRetry)
                    }

                uiState.posts.isEmpty() ->
                    Column(Modifier.fillMaxSize()) {
                        listTop()
                        EmptyContent(uiState.isSearchResult)
                    }

                else ->
                    CommunityPostList(
                        posts = uiState.posts,
                        hasNext = uiState.hasNext,
                        isLoadingMore = uiState.isLoadingMore,
                        loadMoreError = uiState.errorMessage,
                        onLoadMore = onLoadMore,
                        onPostClick = onPostClick,
                        thumbnailDescription = "실종 동물 사진",
                        loadMoreLabel = "게시물을 더 불러오는 중",
                        header = listTop,
                    )
            }
            CommunityBottomBar(
                selectedTab = CommunityTabType.LOST,
                onLostTabClick = {},
                onShelteringTabClick = onShelteringTabClick,
                modifier = Modifier.align(Alignment.BottomCenter),
                onHomeTabClick = onHomeTabClick,
            )
        }
    }
}

/**
 * 앱 공통 헤더. 오른쪽 사람 아이콘은 마이페이지로 곧장 간다 — 예전의 ☰ 드롭다운(내 게시물 한 항목)을 대체했다.
 *
 * 채팅 아이콘은 로그인 여부와 무관하게 보인다. MVP에는 채팅 푸시가 없어 새 메시지를 알 방법이
 * 앱을 여는 것뿐이므로, 목록으로 가는 길을 항상 같은 자리에 둔다. 비로그인 상태의 탭은 각 화면이
 * 로그인으로 보내고 로그인 뒤 원래 목적지로 돌아온다.
 */
@Composable
internal fun BrandHeader(
    onProfileClick: (() -> Unit)? = null,
    onChatClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 헤더에는 글씨 로고만 둔다. 그림 로고는 앱을 열 때 시작 화면에서 한 번 보여 주므로
        // 여기서 또 쓰면 같은 로고가 두 번 나온다.
        //
        // 서비스명은 글씨 로고 에셋이다. 시스템 글꼴로 조판하면 로고의 둥근 획이 재현되지 않는다.
        // 에셋은 글자 여백을 잘라 둬서 높이만 정하면 너비가 비율대로 따라온다.
        // 오른쪽 아이콘은 터치 영역 안쪽 여백만큼 이미 안으로 들어와 있다. 글씨 로고도 같은
        // 만큼 띄워야 좌우가 같은 깊이로 보인다.
        Image(
            painter = painterResource(R.drawable.logo_wordmark),
            contentDescription = "멍고반점",
            modifier = Modifier.padding(start = MeonggoSpacing.small).height(30.dp),
            contentScale = ContentScale.Fit,
        )
        Spacer(Modifier.weight(1f))
        // 아이콘만 남으면 터치 영역의 안쪽 여백이 이미 두 아이콘을 벌려 준다.
        HeaderChatButton(onChatClick)
        HeaderProfileButton(onProfileClick)
    }
}

@Composable
internal fun HeaderChatButton(onClick: (() -> Unit)?) {
    HeaderIconButton(label = "채팅", onClick = onClick) {
        ChatIcon(color = MaterialTheme.colorScheme.onSurface, iconSize = HEADER_ICON_SIZE, strokeWidth = 2f)
    }
}

@Composable
internal fun HeaderProfileButton(onClick: (() -> Unit)?) {
    HeaderIconButton(label = "마이페이지", onClick = onClick) {
        PersonIcon(color = MaterialTheme.colorScheme.onSurface, iconSize = HEADER_ICON_SIZE, strokeWidth = 2f)
    }
}

/**
 * 헤더 오른쪽의 아이콘 버튼입니다.
 *
 * 면도 테두리도 두지 않고 아이콘만 남긴다. 헤더에 면이 생기면 아래 카드와 무게를 다투고,
 * 바탕이 따뜻한 흰색이라 어떤 면을 깔아도 튀거나 묻힌다. 누를 수 있다는 신호는 아이콘 자체와
 * 누를 때의 물결로 충분하다.
 *
 * 면이 없어도 손가락이 닿는 넓이는 [HEADER_BUTTON_TOUCH_SIZE] 로 지킨다.
 */
@Composable
private fun HeaderIconButton(
    label: String,
    onClick: (() -> Unit)?,
    icon: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick ?: {},
        enabled = onClick != null,
        modifier =
            Modifier
                .size(HEADER_BUTTON_TOUCH_SIZE)
                .semantics { contentDescription = label },
    ) {
        icon()
    }
}

/** 아이콘만 보여도 손가락이 닿는 넓이는 확보한다. */
private val HEADER_BUTTON_TOUCH_SIZE = 46.dp

/** 헤더 아이콘 크기. 면이 없어 작아 보이므로 왼쪽 로고·글씨와 무게가 맞도록 키웠다. */
private val HEADER_ICON_SIZE = 28.dp

@Composable
internal fun CurrentRegion(
    regionName: String,
    onRegionChangeClick: () -> Unit,
) {
    // 화면 폭을 꽉 채운 띠가 아니라 홈 카드와 같은 라운드 면이다. 홈에는 띠가 없어 띠가 남으면
    // 목록 화면만 다른 앱처럼 보인다.
    Surface(
        // 지역은 거들 뿐이라 자리를 적게 차지해야 게시물이 한 건이라도 더 보인다.
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = LIST_GUTTER, vertical = 8.dp)
                .floatingCardShadow(RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        // 노란 크림으로 채우면 아래 게시물 카드와 색이 다툰다. 중립 쪽으로 눌러 둔 면을 쓴다.
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 세로로 긴 비율이면 좁아 보인다. 폭을 높이에 맞춰 둥글게 둔다.
            LocationPinIcon(
                Modifier.size(width = 20.dp, height = 20.dp),
                holeColor = MaterialTheme.colorScheme.surfaceContainer,
            )
            Text(
                "현재 지역: $regionName",
                Modifier.padding(start = 10.dp),
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp, lineHeight = 20.sp),
            )
            Spacer(Modifier.weight(1f))
            Surface(
                shape = RoundedCornerShape(14.dp),
                // 카드 면보다 한 단계 밝게 올려 누를 수 있는 것으로 읽히게 한다.
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                onClick = onRegionChangeClick,
            ) {
                Text(
                    "지역 변경",
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** 목록 화면의 좌우 여백. 홈과 같은 값이라 카드 폭이 화면을 넘나들며 어긋나지 않는다. */
internal val LIST_GUTTER = MeonggoSurfaces.gutter

/** 목록 화면 카드의 그림자. 경계가 보일 만큼만 띄우는 홈 카드와 같은 값이다. */
internal val LIST_CARD_ELEVATION = MeonggoSurfaces.cardElevation

/** 목록 카드의 모서리. 홈 카드와 같다. */
internal val LIST_CARD_SHAPE = MeonggoSurfaces.cardShape

/**
 * 목록 화면에서 떠 있는 면(카드·검색칸·등록 버튼)의 그림자입니다.
 *
 * 그림자 색을 왜 따로 주는지는 [meonggoFloatingShadow] 에 적어 뒀다. 등록 화면도 같은 그림자를
 * 쓰게 되면서 디자인 시스템으로 옮겼고, 여기서는 목록 기본값을 붙여 부르기만 한다.
 *
 * 그림자를 그리는 쪽에서는 `Surface` 의 `shadowElevation` 을 쓰지 않는다 — 둘을 같이 주면
 * 그림자가 두 번 쌓인다.
 */
internal fun Modifier.floatingCardShadow(
    shape: Shape = LIST_CARD_SHAPE,
    elevation: Dp = LIST_CARD_ELEVATION,
): Modifier = meonggoFloatingShadow(shape = shape, elevation = elevation)

@Composable
internal fun PostThumbnail(
    imageUrl: String?,
    contentDescription: String = "동물 사진",
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
) {
    // 공통 부품으로 옮겼다. 채팅·유사도 분석도 같은 칸을 쓴다 (MeonggoPhotoThumbnail).
    MeonggoPhotoThumbnail(
        imageUrl = imageUrl,
        contentDescription = contentDescription,
        modifier = modifier,
        shape = shape,
    )
}

internal enum class CommunityTabType { HOME, LOST, SHELTERING }

@Composable
internal fun CommunityBottomBar(
    selectedTab: CommunityTabType,
    onLostTabClick: () -> Unit,
    onShelteringTabClick: () -> Unit,
    modifier: Modifier = Modifier,
    onHomeTabClick: () -> Unit = {},
) {
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = BOTTOM_BAR_MARGIN)
                .floatingCardShadow(RoundedCornerShape(26.dp), elevation = 8.dp),
        shape = RoundedCornerShape(26.dp),
        // 바탕이 따뜻한 흰색이라 탭 바는 순백으로 둬야 떠 있는 것으로 보인다.
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        // 세 탭을 똑같은 weight 로 정확히 3등분한다. 라벨 길이("홈" 대 "보호하고 있어요")가 달라도
        // 아이콘이 같은 간격으로 놓여야 한다. 칸을 나누는 세로줄은 디자인에 없어 두지 않는다.
        Row(Modifier.height(BOTTOM_BAR_ROW_HEIGHT)) {
            CommunityTab(
                label = "홈",
                tab = CommunityTabType.HOME,
                selectedTab = selectedTab,
                onClick = onHomeTabClick,
                modifier = Modifier.weight(1f),
            )
            CommunityTab(
                label = "잃어버렸어요",
                tab = CommunityTabType.LOST,
                selectedTab = selectedTab,
                onClick = onLostTabClick,
                modifier = Modifier.weight(1f),
            )
            CommunityTab(
                label = "보호하고 있어요",
                tab = CommunityTabType.SHELTERING,
                selectedTab = selectedTab,
                onClick = onShelteringTabClick,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun CommunityTab(
    label: String,
    tab: CommunityTabType,
    selectedTab: CommunityTabType,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = tab == selectedTab
    val color =
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(modifier = modifier.fillMaxHeight().clickable(onClick = onClick)) {
        // 활성 표시가 아래에 붙는 만큼 위에도 같은 높이를 비워 둔다. 그래야 표시가 있든 없든
        // 아이콘과 라벨이 바의 세로 한가운데에 똑같이 놓인다.
        //
        // 칸 너비는 가장 넓은 자식(라벨)에 맞춘다. 활성 표시가 그 너비를 그대로 채워 라벨과 같은
        // 길이가 된다 — 탭 전체 폭을 채우면 "홈"처럼 짧은 라벨에서 바가 칸 밖으로 삐져나온다.
        Column(
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .width(IntrinsicSize.Max)
                    .padding(top = INDICATOR_GAP + INDICATOR_HEIGHT),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (tab) {
                CommunityTabType.HOME -> HomeIcon(color = color)
                CommunityTabType.LOST -> SearchIcon(color = color)
                CommunityTabType.SHELTERING -> HeartIcon(color = color)
            }
            Spacer(Modifier.height(5.dp))
            Text(
                label,
                color = color,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                fontSize = 11.5.sp,
                maxLines = 1,
            )
            Spacer(Modifier.height(INDICATOR_GAP))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(INDICATOR_HEIGHT)
                    .background(
                        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        shape = RoundedCornerShape(2.dp),
                    ),
            )
        }
    }
}

private val INDICATOR_GAP = 6.dp
private val INDICATOR_HEIGHT = 3.dp

/** 떠 있는 탭 바가 화면 아래·좌우에서 떨어지는 거리. */
private val BOTTOM_BAR_MARGIN = 10.dp

/** 탭 한 칸의 높이. */
private val BOTTOM_BAR_ROW_HEIGHT = 66.dp

/**
 * 떠 있는 탭 바가 차지하는 전체 높이입니다.
 *
 * 목록 화면은 이 바를 `Scaffold` 의 `bottomBar` 로 두지 않고 내용 위에 겹쳐 띄운다. 그래야
 * 카드가 바 밑으로 지나가는 게 좌우·아래 틈으로 보인다. 대신 목록 맨 아래 카드가 바에 가리지
 * 않도록 이 높이만큼 스크롤 여유를 준다.
 */
internal val BOTTOM_BAR_HEIGHT = BOTTOM_BAR_ROW_HEIGHT + BOTTOM_BAR_MARGIN * 2

@Composable
private fun LoadingContent() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        MeonggoLoadingIndicator("실종 게시물 목록을 불러오는 중")
    }
}

@Composable
private fun EmptyContent(isSearchResult: Boolean) {
    Box(Modifier.fillMaxSize().padding(MeonggoSpacing.extraLarge), contentAlignment = Alignment.Center) {
        Text(
            if (isSearchResult) "검색 결과가 없습니다." else "등록된 실종 게시물이 없습니다.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(MeonggoSpacing.extraLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(MeonggoSpacing.medium))
        Button(onRetry) { Text("다시 시도") }
    }
}

internal fun sexLabel(sex: String): String =
    when (sex) {
        "MALE" -> "수컷"
        "FEMALE" -> "암컷"
        else -> "성별 미상"
    }

@Preview(showBackground = true, widthDp = 390, heightDp = 882)
@Composable
private fun LostPostListScreenPreview() {
    MeonggoBanjeomTheme {
        LostPostListScreen(
            uiState =
                LostPostListUiState(
                    isLoading = false,
                    posts =
                        listOf(
                            previewPost(1, "몽이", "비숑프리제", "흰색", "서울 마포구 서교동", "2026-08-19"),
                            previewPost(2, "보리", "시츄", "흰색/갈색", "서울 마포구 성산동", "2026-08-18"),
                            previewPost(3, "나비", "코리안숏헤어", "치즈", "서울 마포구 연남동", "2026-08-17"),
                            previewPost(4, "짱이", "코리안숏헤어", "치즈", "서울 마포구 연남동", "2026-08-16"),
                        ),
                ),
            onSearchQueryChange = {},
            onSearch = {},
            onRetry = {},
            onLoadMore = {},
            onFilterApply = {},
            onPostClick = {},
            onCreateClick = {},
            onRegionChangeClick = {},
            onShelteringTabClick = {},
        )
    }
}

private fun previewPost(
    id: Long,
    name: String,
    breed: String,
    color: String,
    location: String,
    date: String,
) = LostPostSummary(
    postId = id,
    type = "LOST",
    source = "USER_POST",
    name = name,
    species = if (breed == "코리안숏헤어") "CAT" else "DOG",
    breedName = breed,
    sex = if (id == 2L) "MALE" else "FEMALE",
    color = color,
    eventDate = date,
    listedAt = "${date}T12:00:00Z",
    publicLocation = location,
)
