package com.hotdog.meonggocuisine.feature.chat.ui

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotdog.meonggocuisine.R
import com.hotdog.meonggocuisine.core.designsystem.component.ChevronRightIcon
import com.hotdog.meonggocuisine.core.designsystem.component.LockIcon
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoButton
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoLoadingIndicator
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoOutlinedButton
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoPhotoThumbnail
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoScreenHeader
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoSurfaces
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoTextButton
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoTextField
import com.hotdog.meonggocuisine.core.designsystem.theme.MeonggoBanjeomTheme
import com.hotdog.meonggocuisine.core.designsystem.token.MeonggoRadius
import com.hotdog.meonggocuisine.core.designsystem.token.MeonggoSpacing
import com.hotdog.meonggocuisine.feature.chat.data.ChatMessage
import com.hotdog.meonggocuisine.feature.chat.data.ChatRoomPost
import java.time.LocalDate

@Composable
fun ChatRoomRouteScreen(
    chatRoomId: Long,
    onBackClick: () -> Unit,
    onPostClick: (Long) -> Unit,
    onVisibilityChanged: (Long, Boolean) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    viewModel: ChatRoomViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleStartEffect(chatRoomId) {
        onVisibilityChanged(chatRoomId, true)
        viewModel.startPolling()
        onStopOrDispose {
            onVisibilityChanged(chatRoomId, false)
            viewModel.stopPolling()
        }
    }
    ChatRoomScreen(
        uiState = uiState,
        onBackClick = onBackClick,
        onPostClick = onPostClick,
        onRetryClick = viewModel::retry,
        onLoadPreviousClick = viewModel::loadPrevious,
        onInputChange = viewModel::onInputChange,
        onSendClick = viewModel::send,
        modifier = modifier,
    )
}

@Composable
fun ChatRoomScreen(
    uiState: ChatRoomUiState,
    onBackClick: () -> Unit,
    onPostClick: (Long) -> Unit,
    onRetryClick: () -> Unit,
    onLoadPreviousClick: () -> Unit,
    onInputChange: (String) -> Unit,
    onSendClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize().imePadding(),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            // 누구와 하는 대화인지가 화면 이름이다. "1:1 채팅" 은 어느 방에서나 같아서
            // 방을 옮겨도 머리줄이 바뀌지 않았다.
            MeonggoScreenHeader(
                title = uiState.otherNickname ?: "1:1 채팅",
                onBackClick = onBackClick,
            )
        },
    ) { contentPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            uiState.post?.let { post ->
                PostBar(post = post, readOnly = uiState.readOnly, onClick = { onPostClick(post.postId) })
            }
            when {
                uiState.isLoading -> CenteredLoading()
                uiState.errorMessage != null -> CenteredError(uiState.errorMessage, onRetryClick)
                else -> {
                    MessageList(
                        uiState = uiState,
                        onLoadPreviousClick = onLoadPreviousClick,
                        modifier = Modifier.weight(1f),
                    )
                    // 띠의 자물쇠와 입력창의 안내가 이미 말한다. 서버가 따로 까닭을 준
                    // 때(이를테면 상대 탈퇴)와 띠가 없을 때만 문장으로 덧붙인다.
                    if (uiState.readOnly && (uiState.post == null || uiState.readOnlyNotice != null)) {
                        ReadOnlyNotice(uiState.readOnlyNotice)
                    }
                    MessageInputBar(
                        inputText = uiState.inputText,
                        isSending = uiState.isSending,
                        readOnly = uiState.readOnly,
                        sendErrorMessage = uiState.sendErrorMessage,
                        onInputChange = onInputChange,
                        onSendClick = onSendClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageList(
    uiState: ChatRoomUiState,
    onLoadPreviousClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(uiState.messages.lastOrNull()?.messageId) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.lastIndex + 1)
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding =
            PaddingValues(
                horizontal = MeonggoSurfaces.gutter,
                vertical = MeonggoSpacing.small,
            ),
        verticalArrangement = Arrangement.spacedBy(MeonggoSpacing.small),
    ) {
        item {
            if (uiState.hasPrevious) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (uiState.isLoadingPrevious) {
                        MeonggoLoadingIndicator("이전 메시지를 불러오는 중")
                    } else {
                        MeonggoTextButton(text = "이전 메시지 보기", onClick = onLoadPreviousClick)
                    }
                }
            }
        }
        if (uiState.messages.isEmpty()) {
            item {
                Text(
                    text = "아직 주고받은 메시지가 없습니다. 첫 메시지를 보내 보세요.",
                    modifier = Modifier.fillMaxWidth().padding(MeonggoSpacing.extraLarge),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
        itemsIndexed(uiState.messages, key = { _, message -> message.messageId }) { index, message ->
            val date = chatDate(message.createdAt)
            val previousDate = uiState.messages.getOrNull(index - 1)?.let { chatDate(it.createdAt) }
            if (date != null && date != previousDate) {
                DateSeparator(date)
            }
            MessageBubble(
                message = message,
                otherLastReadMessageId = uiState.otherLastReadMessageId,
            )
        }
    }
}

/** 이보다 길거나 줄이 많으면 접어 두고 "본문 보기" 로 펼친다. 한 메시지가 화면을 다 차지하면 대화 흐름을 잃는다. */
private const val COLLAPSED_MESSAGE_CHARS = 300
private const val COLLAPSED_MESSAGE_LINES = 8

/** 입력이 이만큼 차면 `n/1000` 을 보여 준다 — 짧은 대화에서는 숫자가 보이지 않는다. */
private const val MESSAGE_COUNTER_FROM = 800

/** 입력창이 늘어나는 상한. 더 늘면 대화가 화면에서 밀려난다. */
private const val INPUT_MAX_LINES = 4

/** 상대 말풍선 옆 사진의 크기. 닉네임 한 줄과 말풍선 한 줄에 걸치는 크기다. */
private val AvatarSize = 34.dp

/** 말풍선이 넓어질 수 있는 한계. 사진 자리를 빼고도 화면에 여유가 남는 폭이다. */
private val BubbleMaxWidth = 240.dp

/** 입력창과 전송 단추가 함께 쓰는 모서리. 나란히 선 둘이 다른 모양이면 한 줄로 안 읽힌다. */
private val InputShape = RoundedCornerShape(MeonggoRadius.medium)

/** 전송 단추 높이. 한 줄짜리 입력창과 같은 높이다. */
private val SendButtonHeight = 56.dp

/** 말풍선 모서리. 보낸 쪽 아래만 눌러 둔다. */
private val BubbleCorner = 18.dp
private val BubbleTailCorner = 6.dp
private val MineBubbleShape =
    RoundedCornerShape(
        topStart = BubbleCorner,
        topEnd = BubbleCorner,
        bottomStart = BubbleCorner,
        bottomEnd = BubbleTailCorner,
    )
private val TheirBubbleShape =
    RoundedCornerShape(
        topStart = BubbleCorner,
        topEnd = BubbleCorner,
        bottomStart = BubbleTailCorner,
        bottomEnd = BubbleCorner,
    )

/**
 * 줄 사이에 끼우는 날짜입니다.
 *
 * 말풍선마다 날짜를 붙이면 같은 날 대화에 같은 글자가 줄줄이 붙는다. 날짜가 바뀌는 자리에만
 * 한 번 긋고, 말풍선 옆에는 시각만 남긴다.
 */
@Composable
private fun DateSeparator(date: LocalDate) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = MeonggoSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outline)
        Text(
            text = formatChatDate(date),
            modifier = Modifier.padding(horizontal = MeonggoSpacing.medium),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outline)
    }
}

/**
 * 메시지 한 줄입니다.
 *
 * 상대 말풍선은 사진 옆에서 나온다 — 누가 한 말인지 닉네임을 읽지 않고도 갈린다. 두 사람의
 * 말풍선 색도 갈라 둔다. 예전에는 내 쪽 `primaryContainer` 와 상대 쪽 `surfaceVariant` 가
 * 팔레트에서 같은 `Brown50` 이라 색이 전혀 구분되지 않았다.
 */
@Composable
private fun MessageBubble(
    message: ChatMessage,
    otherLastReadMessageId: Long?,
) {
    val content = message.content
    val isLong =
        content.codePointCount(0, content.length) > COLLAPSED_MESSAGE_CHARS ||
            content.count { it == '\n' } + 1 > COLLAPSED_MESSAGE_LINES
    // 펼침은 메시지별로 기억한다 — 목록이 갱신(폴링)돼도 접히지 않게.
    var expanded by rememberSaveable(message.messageId) { mutableStateOf(false) }
    val body: @Composable () -> Unit = {
        BubbleBody(
            message = message,
            isLong = isLong,
            expanded = expanded,
            onToggleExpand = { expanded = !expanded },
        )
    }

    if (message.mine) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = if (otherLastReadMessageId != null && message.messageId <= otherLastReadMessageId) "읽음" else "안 읽음",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                MessageTime(message.createdAt)
            }
            Spacer(Modifier.width(MeonggoSpacing.small))
            body()
        }
        return
    }

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Image(
            painter = painterResource(R.drawable.img_member_avatar),
            contentDescription = null,
            modifier = Modifier.size(AvatarSize).clip(CircleShape),
        )
        Spacer(Modifier.width(MeonggoSpacing.small))
        Column {
            Text(
                text = message.senderNickname,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                body()
                Spacer(Modifier.width(MeonggoSpacing.small))
                MessageTime(message.createdAt)
            }
        }
    }
}

@Composable
private fun BubbleBody(
    message: ChatMessage,
    isLong: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
) {
    val container =
        if (message.mine) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    val onContainer =
        if (message.mine) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    Surface(
        // 보낸 쪽 아래 모서리만 눌러 둔다. 말풍선이 어느 쪽에서 나왔는지 색 말고도 드러난다.
        shape = if (message.mine) MineBubbleShape else TheirBubbleShape,
        color = container,
        contentColor = onContainer,
    ) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = BubbleMaxWidth)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = message.content,
                maxLines = if (isLong && !expanded) COLLAPSED_MESSAGE_LINES else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (isLong) {
                Text(
                    text = if (expanded) "접기" else "본문 보기",
                    modifier =
                        Modifier
                            .padding(top = 2.dp)
                            .clickable(onClick = onToggleExpand)
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                )
            }
        }
    }
}

@Composable
private fun MessageTime(createdAt: String) {
    Text(
        text = formatChatClock(createdAt),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 머리줄 밑에 깔리는 게시물 띠입니다.
 *
 * 채팅방은 게시물 하나마다 따로 열린다(`docs/erd.md` 규칙 13). 같은 사람과 게시물 둘로
 * 이야기하면 방도 둘인데, 화면에 상대 이름만 있으면 어느 아이 이야기인지 알 수 없었다.
 *
 * 카드로 띄우지 않고 머리줄에 붙은 띠로 둔다. 눌러서 게시물로 가는 곳이지만 대화보다 앞에
 * 나설 자리는 아니다 — 카드로 띄우면 대화 위에 또 하나의 덩어리가 떠 시선이 갈린다.
 *
 * 게시물이 끝났으면 이 띠가 읽기 전용 안내까지 겸한다. 같은 사실을 두 곳에서 말하지 않는다.
 */
@Composable
private fun PostBar(
    post: ChatRoomPost,
    readOnly: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = MeonggoSurfaces.gutter,
                            vertical = MeonggoSpacing.small,
                        ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MeonggoPhotoThumbnail(
                    imageUrl = post.thumbnailUrl,
                    contentDescription = "게시물 동물 사진",
                    modifier = Modifier.size(PostBarThumbnail),
                    shape = RoundedCornerShape(6.dp),
                )
                Column(
                    modifier = Modifier.weight(1f).padding(horizontal = MeonggoSpacing.medium),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = postTypeLabel(post.type),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = postDescriptor(post.name, post.species, post.breedName, post.sex),
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 끝난 대화에는 꺾쇠 대신 자물쇠를 둔다. 꺾쇠는 "여기서 더 할 일이 있다"고
                // 말하는데 그 말이 틀리다. 게시물로 넘어가는 것 자체는 그대로 된다.
                if (readOnly) {
                    LockIcon(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        iconSize = 16.dp,
                        strokeWidth = 1.8.dp,
                        modifier = Modifier.semantics { contentDescription = "종료된 게시물" },
                    )
                } else {
                    ChevronRightIcon(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        iconSize = 16.dp,
                        strokeWidth = 1.8.dp,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
    }
}

/** 게시물 띠의 사진. 두 줄짜리 글 높이에 맞춘 크기다. */
private val PostBarThumbnail = 40.dp

/** 읽기 전용 안내입니다. 화면을 가로지르는 띠 대신, 다른 화면의 안내와 같은 둥근 면에 담는다. */
@Composable
private fun ReadOnlyNotice(notice: String?) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = MeonggoSurfaces.gutter, vertical = MeonggoSpacing.small),
        shape = MeonggoSurfaces.cardShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = notice ?: "이 채팅은 읽기 전용입니다. 새 메시지를 보낼 수 없습니다.",
            modifier = Modifier.fillMaxWidth().padding(MeonggoSpacing.medium),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MessageInputBar(
    inputText: String,
    isSending: Boolean,
    readOnly: Boolean,
    sendErrorMessage: String?,
    onInputChange: (String) -> Unit,
    onSendClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = MeonggoSurfaces.gutter, vertical = MeonggoSpacing.medium),
    ) {
        sendErrorMessage?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = MeonggoSpacing.small),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            MeonggoTextField(
                value = inputText,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = if (readOnly) "메시지를 보낼 수 없습니다" else "메시지를 입력해 주세요",
                enabled = !readOnly,
                singleLine = false,
                maxLines = INPUT_MAX_LINES,
                shape = InputShape,
            )
            Spacer(Modifier.width(MeonggoSpacing.small))
            MeonggoButton(
                text = "전송",
                onClick = onSendClick,
                modifier = Modifier.height(SendButtonHeight),
                enabled = !readOnly && inputText.isNotBlank(),
                isLoading = isSending,
                loadingStateDescription = "메시지를 보내는 중",
                shape = InputShape,
            )
        }
        val used = inputText.codePointCount(0, inputText.length)
        if (used >= MESSAGE_COUNTER_FROM) {
            Text(
                text = "$used/$CHAT_MESSAGE_MAX_LENGTH",
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                textAlign = TextAlign.End,
                style = MaterialTheme.typography.labelSmall,
                color =
                    if (used >= CHAT_MESSAGE_MAX_LENGTH) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
    }
}

@Composable
private fun CenteredLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        MeonggoLoadingIndicator("메시지를 불러오는 중")
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
        Text(message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        Spacer(Modifier.height(MeonggoSpacing.medium))
        MeonggoOutlinedButton(text = "다시 시도", onClick = onRetryClick)
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 800)
@Composable
private fun ChatRoomScreenPreview() {
    MeonggoBanjeomTheme {
        ChatRoomScreen(
            onPostClick = {},
            uiState =
                ChatRoomUiState(
                    post =
                        ChatRoomPost(
                            postId = 1002,
                            type = "LOST",
                            status = "ACTIVE",
                            name = "콩이",
                            species = "DOG",
                            breedName = "말티즈",
                            sex = "MALE",
                            thumbnailUrl = null,
                        ),
                    messages =
                        listOf(
                            ChatMessage(1, "망고보호자", "비슷한 아이를 보호하고 있어요.", "2026-09-10T05:12:00Z", mine = false),
                            ChatMessage(2, "나", "사진을 더 볼 수 있을까요?", "2026-09-10T05:13:00Z", mine = true),
                        ),
                    otherLastReadMessageId = 2,
                ),
            onBackClick = {},
            onRetryClick = {},
            onLoadPreviousClick = {},
            onInputChange = {},
            onSendClick = {},
        )
    }
}
