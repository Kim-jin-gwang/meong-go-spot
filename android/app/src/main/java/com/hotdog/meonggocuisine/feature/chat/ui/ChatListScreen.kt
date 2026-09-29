package com.hotdog.meonggocuisine.feature.chat.ui

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoLoadingIndicator
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoOutlinedButton
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoPhotoThumbnail
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoScreenHeader
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoSurfaces
import com.hotdog.meonggocuisine.core.designsystem.component.RefreshIcon
import com.hotdog.meonggocuisine.core.designsystem.component.meonggoFloatingShadow
import com.hotdog.meonggocuisine.core.designsystem.theme.MeonggoBanjeomTheme
import com.hotdog.meonggocuisine.core.designsystem.token.MeonggoSpacing
import com.hotdog.meonggocuisine.feature.chat.data.ChatRoomSummary

@Composable
fun ChatListRouteScreen(
    onRoomClick: (Long, String) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChatListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.onScreenResumed()
        onPauseOrDispose { viewModel.onScreenPaused() }
    }
    ChatListScreen(
        uiState = uiState,
        onRoomClick = onRoomClick,
        onBackClick = onBackClick,
        onRefreshClick = viewModel::refresh,
        onRetryClick = viewModel::retry,
        onLoadMoreClick = viewModel::loadMore,
        modifier = modifier,
    )
}

@Composable
fun ChatListScreen(
    uiState: ChatListUiState,
    onRoomClick: (Long, String) -> Unit,
    onBackClick: () -> Unit,
    onRefreshClick: () -> Unit,
    onRetryClick: () -> Unit,
    onLoadMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            MeonggoScreenHeader(
                title = "내 채팅",
                onBackClick = onBackClick,
                actions = {
                    RefreshAction(
                        enabled = !uiState.isLoading && !uiState.isRefreshing,
                        isRefreshing = uiState.isRefreshing,
                        onClick = onRefreshClick,
                    )
                },
            )
        },
    ) { contentPadding ->
        Box(Modifier.fillMaxSize().padding(contentPadding)) {
            when {
                uiState.isLoading -> CenteredLoading()
                uiState.errorMessage != null && uiState.rooms.isEmpty() ->
                    CenteredError(uiState.errorMessage, onRetryClick)
                uiState.rooms.isEmpty() -> EmptyRooms()
                else ->
                    RoomList(
                        uiState = uiState,
                        onRoomClick = onRoomClick,
                        onLoadMoreClick = onLoadMoreClick,
                    )
            }
        }
    }
}

/**
 * 머리줄의 다시 불러오기입니다.
 *
 * 글자("새로고침") 대신 도는 화살표를 둔다. 제목 옆에 글이 둘이면 어느 쪽이 화면 이름인지
 * 한눈에 갈리지 않는다. 눌러야 할 것은 그림으로 두고, 읽어 줄 이름은 접근성 설명에 남긴다.
 */
@Composable
private fun RefreshAction(
    enabled: Boolean,
    isRefreshing: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .clip(CircleShape)
                .semantics { contentDescription = if (isRefreshing) "갱신 중" else "새로고침" }
                .clickable(enabled = enabled, onClick = onClick)
                .padding(REFRESH_TOUCH_INSET),
    ) {
        RefreshIcon(
            // 왼쪽 꺾쇠와 같은 색이다. 같은 줄에 나란히 선 두 그림이라 한쪽만 브랜드 갈색으로
            // 채우면 머리줄이 기울어 보인다.
            color =
                if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
    }
}

/** 뒤로 가기 꺾쇠와 같은 값이다 — 크기·굵기는 RefreshIcon 의 기본값을 그대로 쓴다. */
private val REFRESH_TOUCH_INSET = 8.dp

/**
 * 채팅방 목록입니다.
 *
 * 다음 쪽은 내려가면 저절로 붙는다 — 게시물 목록과 같은 방식이다(CommunityPostList). "더 보기"
 * 단추를 두던 때는 한 쪽을 볼 때마다 손을 멈춰야 했는데, 여기서 하는 일도 훑어 내려가는 것 하나다.
 *
 * 실패하면 멈추고 다시 시도 단추를 보인다. 안 그러면 바닥에 있는 채로 같은 요청을 끝없이 보낸다.
 */
@Composable
private fun RoomList(
    uiState: ChatListUiState,
    onRoomClick: (Long, String) -> Unit,
    onLoadMoreClick: () -> Unit,
) {
    val listState = rememberLazyListState()
    val shouldLoadMore by remember(uiState.hasNext, uiState.isLoadingMore, uiState.errorMessage) {
        derivedStateOf {
            if (!uiState.hasNext || uiState.isLoadingMore || uiState.errorMessage != null) {
                false
            } else {
                val info = listState.layoutInfo
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                lastVisible >= info.totalItemsCount - LOAD_MORE_PREFETCH
            }
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) onLoadMoreClick()
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(
                horizontal = MeonggoSurfaces.gutter,
                vertical = MeonggoSpacing.small,
            ),
        verticalArrangement = Arrangement.spacedBy(MeonggoSpacing.medium),
    ) {
        items(uiState.rooms, key = ChatRoomSummary::chatRoomId) { room ->
            ChatRoomCard(room, onClick = { onRoomClick(room.chatRoomId, room.otherNickname) })
        }
        if (uiState.hasNext && uiState.errorMessage == null) {
            item(key = "loadingMore") {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(MeonggoSpacing.large),
                    contentAlignment = Alignment.Center,
                ) {
                    MeonggoLoadingIndicator("채팅방을 더 불러오는 중")
                }
            }
        }
        uiState.errorMessage?.let { message ->
            item(key = "loadMoreError") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(MeonggoSpacing.large),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(MeonggoSpacing.medium),
                ) {
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    MeonggoOutlinedButton(text = "다시 시도", onClick = onLoadMoreClick)
                }
            }
        }
    }
}

/** 바닥에 닿기 전에 미리 부른다. 다 내려간 뒤에 부르면 빈 자리를 보며 기다리게 된다. */
private const val LOAD_MORE_PREFETCH = 3

@Composable
private fun ChatRoomCard(
    room: ChatRoomSummary,
    onClick: () -> Unit,
) {
    // 게시물 목록 카드와 같은 면이다 — 22dp 모서리, 순백, 테두리 없이 갈색 그림자로만 경계.
    Surface(
        modifier = Modifier.fillMaxWidth().meonggoFloatingShadow(),
        shape = MeonggoSurfaces.cardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(MeonggoSpacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoomThumbnail(room.thumbnailUrl)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = room.otherNickname,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (room.readOnly) {
                        Text(
                            text = "읽기 전용",
                            modifier = Modifier.padding(start = MeonggoSpacing.small),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // 어느 게시물의 대화인지 밝힌다. 같은 사람과 게시물 둘로 이야기하면 방이
                // 둘인데(docs/erd.md 규칙 13), 닉네임만 있으면 두 줄이 똑같아 보였다.
                Text(
                    text = postLine(room),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = room.lastMessageContent ?: "아직 메시지가 없습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatChatTime(room.lastMessageAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (room.hasUnread && room.unreadCount > 0) {
                    Spacer(Modifier.height(MeonggoSpacing.small))
                    UnreadBadge(room.unreadCount)
                }
            }
        }
    }
}

@Composable
private fun UnreadBadge(unreadCount: Int) {
    Surface(
        shape = BADGE_SHAPE,
        color = MaterialTheme.colorScheme.error,
    ) {
        Text(
            text = if (unreadCount > 99) "99+" else unreadCount.toString(),
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onError,
        )
    }
}

/** 사진 칸은 게시물 목록과 같은 부품을 쓴다 — 사진이 없으면 "사진 없음" 그림이 나온다. */
@Composable
private fun RoomThumbnail(imageUrl: String?) {
    MeonggoPhotoThumbnail(
        imageUrl = imageUrl,
        contentDescription = "게시물 동물 사진",
        modifier = Modifier.size(THUMBNAIL_SIZE),
        shape = THUMBNAIL_SHAPE,
    )
}

private fun postLine(room: ChatRoomSummary): String =
    listOfNotNull(
        postTypeLabel(room.postType),
        postDescriptor(room.postName, room.postSpecies, room.postBreedName, room.postSex)
            .takeIf(String::isNotBlank),
        "종료".takeIf { room.readOnly },
    ).joinToString(" · ")

private val THUMBNAIL_SIZE = 56.dp

/** 게시물 목록 카드의 사진과 같은 모서리다. */
private val THUMBNAIL_SHAPE = RoundedCornerShape(6.dp)

private val BADGE_SHAPE = RoundedCornerShape(percent = 50)

@Composable
private fun EmptyRooms() {
    Box(Modifier.fillMaxSize().padding(MeonggoSpacing.extraLarge), contentAlignment = Alignment.Center) {
        Text(
            text = "참여 중인 채팅이 없습니다.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CenteredLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        MeonggoLoadingIndicator("채팅방 목록을 불러오는 중")
    }
}

@Composable
private fun CenteredError(
    message: String,
    onRetryClick: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(MeonggoSpacing.extraLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(MeonggoSpacing.medium))
        MeonggoOutlinedButton(text = "다시 시도", onClick = onRetryClick)
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 800)
@Composable
private fun ChatListScreenPreview() {
    MeonggoBanjeomTheme {
        ChatListScreen(
            uiState =
                ChatListUiState(
                    rooms =
                        listOf(
                            ChatRoomSummary(
                                chatRoomId = 7001,
                                postId = 1002,
                                postType = "LOST",
                                postStatus = "ACTIVE",
                                thumbnailUrl = null,
                                otherNickname = "망고보호자",
                                lastMessageContent = "비슷한 아이를 보호하고 있어요.",
                                lastMessageAt = "2026-09-10T05:12:00Z",
                                readOnly = false,
                                unreadCount = 3,
                                hasUnread = true,
                            ),
                            ChatRoomSummary(
                                chatRoomId = 7002,
                                postId = 1003,
                                postType = "LOST",
                                postStatus = "CLOSED",
                                thumbnailUrl = null,
                                otherNickname = "탈퇴한 회원",
                                lastMessageContent = "감사합니다!",
                                lastMessageAt = "2026-09-01T05:12:00Z",
                                readOnly = true,
                            ),
                        ),
                ),
            onRoomClick = { _, _ -> },
            onBackClick = {},
            onRefreshClick = {},
            onRetryClick = {},
            onLoadMoreClick = {},
        )
    }
}
