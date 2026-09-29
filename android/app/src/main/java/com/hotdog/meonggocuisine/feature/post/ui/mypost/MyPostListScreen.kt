package com.hotdog.meonggocuisine.feature.post.ui.mypost

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoLoadingIndicator
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoPhotoPlaceholder
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoScreenHeader
import com.hotdog.meonggocuisine.core.designsystem.theme.MeonggoBanjeomTheme
import com.hotdog.meonggocuisine.core.designsystem.token.MeonggoSpacing
import com.hotdog.meonggocuisine.core.text.speciesLabel
import com.hotdog.meonggocuisine.feature.post.data.MyPostSummary
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun MyPostListRouteScreen(
    onBackClick: () -> Unit,
    onPostClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MyPostListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    MyPostListScreen(
        uiState = uiState,
        onFilterSelect = viewModel::onFilterSelect,
        onRetry = viewModel::retry,
        onLoadMore = viewModel::loadMore,
        onBackClick = onBackClick,
        onPostClick = onPostClick,
        modifier = modifier,
    )
}

@Composable
fun MyPostListScreen(
    uiState: MyPostListUiState,
    onFilterSelect: (MyPostStatusFilter) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onBackClick: () -> Unit,
    onPostClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { MyPostListHeader(onBackClick) },
    ) { contentPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            StatusFilterRow(uiState.selectedFilter, onFilterSelect)
            when {
                uiState.isLoading -> LoadingContent()
                uiState.errorMessage != null && uiState.posts.isEmpty() ->
                    ErrorContent(uiState.errorMessage, onRetry)
                uiState.posts.isEmpty() -> EmptyContent(uiState.selectedFilter)
                else ->
                    MyPostList(
                        posts = uiState.posts,
                        hasNext = uiState.hasNext,
                        isLoadingMore = uiState.isLoadingMore,
                        loadMoreError = uiState.errorMessage,
                        onLoadMore = onLoadMore,
                        onPostClick = onPostClick,
                    )
            }
        }
    }
}

/** 다른 화면과 같은 머리줄이다 — 예전에는 글리프로 직접 그려 마이페이지에서 들어오면 줄이 바뀌었다. */
@Composable
private fun MyPostListHeader(onBackClick: () -> Unit) {
    MeonggoScreenHeader(title = "내 게시물", onBackClick = onBackClick)
}

@Composable
private fun StatusFilterRow(
    selectedFilter: MyPostStatusFilter,
    onFilterSelect: (MyPostStatusFilter) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MyPostStatusFilter.entries.forEach { filter ->
            FilterChip(
                selected = selectedFilter == filter,
                onClick = { onFilterSelect(filter) },
                label = { Text(filter.label) },
            )
        }
    }
}

@Composable
private fun MyPostList(
    posts: List<MyPostSummary>,
    hasNext: Boolean,
    isLoadingMore: Boolean,
    loadMoreError: String?,
    onLoadMore: () -> Unit,
    onPostClick: (Long) -> Unit,
) {
    val listState = rememberLazyListState()
    // 추가 로딩 실패 상태에서 자동 로드를 계속하면 실패-재요청 루프가 되므로 수동 재시도로 전환한다.
    val autoLoadEnabled = loadMoreError == null
    InfiniteScrollEffect(listState, hasNext, isLoadingMore, autoLoadEnabled, onLoadMore)
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 18.dp, top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(posts, key = MyPostSummary::postId) { post ->
            MyPostCard(post, onClick = { onPostClick(post.postId) })
        }
        if (isLoadingMore) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(MeonggoSpacing.large),
                    contentAlignment = Alignment.Center,
                ) {
                    MeonggoLoadingIndicator("게시물을 더 불러오는 중")
                }
            }
        }
        loadMoreError?.let { message ->
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(MeonggoSpacing.medium),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    Button(onLoadMore) { Text("다시 시도") }
                }
            }
        }
    }
}

@Composable
private fun InfiniteScrollEffect(
    listState: LazyListState,
    hasNext: Boolean,
    isLoadingMore: Boolean,
    autoLoadEnabled: Boolean,
    onLoadMore: () -> Unit,
) {
    LaunchedEffect(listState, hasNext, isLoadingMore, autoLoadEnabled) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= layoutInfo.totalItemsCount - LOAD_MORE_THRESHOLD
        }.distinctUntilChanged()
            .collect { nearEnd ->
                if (nearEnd && hasNext && !isLoadingMore && autoLoadEnabled) onLoadMore()
            }
    }
}

@Composable
private fun MyPostCard(
    post: MyPostSummary,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MyPostThumbnail(post.thumbnailUrl, modifier = Modifier.size(94.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TypeBadge(post.type)
                    StatusBadge(post.status)
                }
                Text(
                    post.name ?: speciesLabel(post.species),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                InfoLine("⌖", post.publicLocation)
                InfoLine("◷", "${formatDate(post.eventDate)} ${eventDateLabel(post.type)}")
                InfoLine("↻", "${formatInstantDate(post.updatedAt)} 수정 · ${formatInstantDate(post.listedAt)} 등록")
            }
        }
    }
}

@Composable
private fun TypeBadge(type: String) {
    val label = if (type == "SHELTERING") "보호" else "실종"
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            color = MaterialTheme.colorScheme.primary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun StatusBadge(status: String) {
    val closed = status == "CLOSED"
    val label = if (closed) "종료" else "진행 중"
    val color = if (closed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, color),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun InfoLine(
    icon: String,
    text: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(icon, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
        Text(
            text,
            Modifier.padding(start = 7.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MyPostThumbnail(
    imageUrl: String?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (imageUrl == null) {
            MeonggoPhotoPlaceholder(contentDescription = "게시물 대표 사진 없음")
        } else {
            SubcomposeAsyncImage(
                model = imageUrl,
                contentDescription = "게시물 대표 사진",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                error = { MeonggoPhotoPlaceholder(contentDescription = "게시물 대표 사진을 불러올 수 없음") },
            )
        }
    }
}

@Composable
private fun LoadingContent() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        MeonggoLoadingIndicator("내 게시물을 불러오는 중")
    }
}

@Composable
private fun EmptyContent(filter: MyPostStatusFilter) {
    val message =
        when (filter) {
            MyPostStatusFilter.ALL -> "등록한 게시물이 없어요."
            MyPostStatusFilter.ACTIVE -> "진행 중인 게시물이 없어요."
            MyPostStatusFilter.CLOSED -> "종료된 게시물이 없어요."
        }
    Box(Modifier.fillMaxSize().padding(MeonggoSpacing.extraLarge), contentAlignment = Alignment.Center) {
        Text(
            message,
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

private fun eventDateLabel(type: String): String = if (type == "SHELTERING") "발견" else "실종"

private fun formatDate(date: String): String = date.replace("-", ".")

private fun formatInstantDate(instant: String): String = formatDate(instant.take(10))

private const val LOAD_MORE_THRESHOLD = 3

@Preview(showBackground = true, widthDp = 390, heightDp = 882)
@Composable
private fun MyPostListScreenPreview() {
    MeonggoBanjeomTheme {
        MyPostListScreen(
            uiState =
                MyPostListUiState(
                    isLoading = false,
                    posts =
                        listOf(
                            previewMyPost(1, "망고", "LOST", "ACTIVE"),
                            previewMyPost(2, "보리", "SHELTERING", "ACTIVE"),
                            previewMyPost(3, "나비", "LOST", "CLOSED"),
                        ),
                ),
            onFilterSelect = {},
            onRetry = {},
            onLoadMore = {},
            onBackClick = {},
            onPostClick = {},
        )
    }
}

private fun previewMyPost(
    id: Long,
    name: String,
    type: String,
    status: String,
) = MyPostSummary(
    postId = id,
    type = type,
    source = "USER_POST",
    status = status,
    version = 1,
    name = name,
    species = "DOG",
    eventDate = "2026-08-25",
    listedAt = "2026-08-25T11:00:00Z",
    publicLocation = "서울특별시 강남구",
    updatedAt = "2026-08-27T05:40:00Z",
)
