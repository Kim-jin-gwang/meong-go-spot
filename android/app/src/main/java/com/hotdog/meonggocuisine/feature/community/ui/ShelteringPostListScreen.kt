package com.hotdog.meonggocuisine.feature.community.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoLoadingIndicator
import com.hotdog.meonggocuisine.core.designsystem.theme.MeonggoBanjeomTheme
import com.hotdog.meonggocuisine.core.designsystem.token.MeonggoSpacing
import com.hotdog.meonggocuisine.feature.community.data.LostPostSummary
import com.hotdog.meonggocuisine.feature.community.data.PostListFilter

@Composable
fun ShelteringPostListRouteScreen(
    onRegionSelectionRequired: () -> Unit,
    onRegionChangeClick: () -> Unit,
    onPostClick: (Long) -> Unit,
    onCreateClick: () -> Unit,
    onLostTabClick: () -> Unit,
    onProfileClick: () -> Unit,
    onChatClick: () -> Unit,
    onHomeTabClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ShelteringPostListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(uiState.needsRegionSelection) {
        if (uiState.needsRegionSelection) onRegionSelectionRequired()
    }
    ShelteringPostListScreen(
        uiState = uiState,
        onSearchQueryChange = viewModel::onSearchQueryChange,
        onSearch = viewModel::search,
        onRetry = viewModel::retry,
        onLoadMore = viewModel::loadMore,
        onFilterApply = viewModel::onFilterApply,
        onRegionChangeClick = onRegionChangeClick,
        onPostClick = onPostClick,
        onCreateClick = onCreateClick,
        onLostTabClick = onLostTabClick,
        onProfileClick = onProfileClick,
        onChatClick = onChatClick,
        onHomeTabClick = onHomeTabClick,
        modifier = modifier,
    )
}

@Composable
fun ShelteringPostListScreen(
    uiState: ShelteringPostListUiState,
    onSearchQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onFilterApply: (PostListFilter) -> Unit,
    onRegionChangeClick: () -> Unit,
    onPostClick: (Long) -> Unit,
    onCreateClick: () -> Unit,
    onLostTabClick: () -> Unit,
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
        if (!uiState.needsRegionSelection) {
            // 탭 바는 `bottomBar` 가 아니라 내용 위에 겹쳐 띄운다. 자리를 따로 잡아 주면 목록이
            // 그 선에서 잘려 카드가 사각으로 끊기는데, 겹쳐 두면 카드가 바 밑으로 지나가는 게
            // 좌우·아래 틈으로 보인다.
            Box(Modifier.fillMaxSize().padding(contentPadding)) {
                // 시트를 열어 뒀는지는 화면만 아는 값이라 ViewModel 로 올리지 않는다.
                var isFilterOpen by rememberSaveable { mutableStateOf(false) }
                val listTop: @Composable () -> Unit = {
                    CommunityListTop(
                        regionName = uiState.selectedRegionName.orEmpty(),
                        onRegionChangeClick = onRegionChangeClick,
                        query = uiState.searchQuery,
                        onQueryChange = onSearchQueryChange,
                        onSearch = onSearch,
                        onCreateClick = onCreateClick,
                        createContentDescription = "보호 게시물 등록",
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
                            ShelteringLoadingContent()
                        }

                    uiState.errorMessage != null && uiState.posts.isEmpty() ->
                        Column(Modifier.fillMaxSize()) {
                            listTop()
                            ShelteringErrorContent(uiState.errorMessage, onRetry)
                        }

                    uiState.posts.isEmpty() ->
                        Column(Modifier.fillMaxSize()) {
                            listTop()
                            ShelteringEmptyContent(uiState.isSearchResult)
                        }

                    else ->
                        CommunityPostList(
                            posts = uiState.posts,
                            hasNext = uiState.hasNext,
                            isLoadingMore = uiState.isLoadingMore,
                            loadMoreError = uiState.errorMessage,
                            onLoadMore = onLoadMore,
                            onPostClick = onPostClick,
                            thumbnailDescription = "보호 중인 동물 사진",
                            loadMoreLabel = "보호동물을 더 불러오는 중",
                            header = listTop,
                        )
                }
                CommunityBottomBar(
                    selectedTab = CommunityTabType.SHELTERING,
                    onLostTabClick = onLostTabClick,
                    onShelteringTabClick = {},
                    modifier = Modifier.align(Alignment.BottomCenter),
                    onHomeTabClick = onHomeTabClick,
                )
            }
        }
    }
}

@Composable
private fun ShelteringLoadingContent() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        MeonggoLoadingIndicator("보호동물 목록을 불러오는 중")
    }
}

@Composable
private fun ShelteringEmptyContent(isSearchResult: Boolean) {
    Box(Modifier.fillMaxSize().padding(MeonggoSpacing.extraLarge), contentAlignment = Alignment.Center) {
        Text(
            if (isSearchResult) "검색 결과가 없습니다." else "선택한 지역에 보호 중인 동물이 없습니다.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ShelteringErrorContent(
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

@Preview(showBackground = true, widthDp = 390, heightDp = 882)
@Composable
private fun ShelteringPostListPreview() {
    MeonggoBanjeomTheme {
        ShelteringPostListScreen(
            uiState =
                ShelteringPostListUiState(
                    selectedRegionCode = "11440",
                    selectedRegionName = "서울특별시 마포구",
                    posts =
                        listOf(
                            shelterPreviewPost(1, "콩이", "푸들", "갈색", "USER_POST"),
                            shelterPreviewPost(2, "몽이", "비숑프리제", "흰색", "SHELTER"),
                            shelterPreviewPost(3, "나비", "코리안숏헤어", "치즈", "USER_POST"),
                        ),
                ),
            onSearchQueryChange = {}, onSearch = {}, onRetry = {}, onLoadMore = {},
            onFilterApply = {},
            onRegionChangeClick = {}, onPostClick = {}, onCreateClick = {}, onLostTabClick = {},
        )
    }
}

private fun shelterPreviewPost(
    id: Long,
    name: String,
    breed: String,
    color: String,
    source: String,
) = LostPostSummary(
    postId = id,
    type = "SHELTERING",
    source = source,
    name = name,
    species = if (breed == "코리안숏헤어") "CAT" else "DOG",
    breedName = breed,
    sex = if (id == 1L) "FEMALE" else "MALE",
    color = color,
    eventDate = "2026-08-${21 - id}",
    listedAt = "2026-08-${21 - id}T12:00:00Z",
    publicLocation = "서울 마포구 ${if (id == 3L) "연남동" else "서교동"}",
)
