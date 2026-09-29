package com.hotdog.meonggocuisine.feature.adoption.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import com.hotdog.meonggocuisine.R
import com.hotdog.meonggocuisine.core.designsystem.component.FilterIcon
import com.hotdog.meonggocuisine.core.designsystem.component.HeartIcon
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoBackButton
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoLoadingIndicator
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoPhotoPlaceholder
import com.hotdog.meonggocuisine.core.designsystem.theme.MeonggoBanjeomTheme
import com.hotdog.meonggocuisine.core.designsystem.token.Brown800
import com.hotdog.meonggocuisine.core.designsystem.token.MeonggoSpacing
import com.hotdog.meonggocuisine.core.designsystem.token.Neutral900
import com.hotdog.meonggocuisine.core.text.stripSpeciesPrefix
import com.hotdog.meonggocuisine.feature.adoption.data.AdoptionAnimal
import com.hotdog.meonggocuisine.feature.adoption.data.AdoptionSwipeRecord
import com.hotdog.meonggocuisine.feature.adoption.data.Sex
import com.hotdog.meonggocuisine.feature.adoption.data.SexFilter
import com.hotdog.meonggocuisine.feature.adoption.data.Species
import com.hotdog.meonggocuisine.feature.adoption.data.SpeciesFilter
import com.hotdog.meonggocuisine.feature.community.ui.HeaderChatButton
import com.hotdog.meonggocuisine.feature.community.ui.HeaderProfileButton
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun AdoptionRouteScreen(
    onBackClick: () -> Unit,
    onAnimalClick: (Long, Boolean) -> Unit,
    onProfileClick: () -> Unit,
    onChatClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AdoptionViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }
    // 상세 팝업이 닫히면 이 화면이 다시 RESUMED 가 된다. 거기서 하트를 켜고 끌 수 있으므로
    // 히스토리를 다시 받는다 — 카드 더미는 건드리지 않는다(다시 부르면 보던 자리가 처음으로 간다).
    LifecycleResumeEffect(Unit) {
        viewModel.refreshHistory()
        onPauseOrDispose {}
    }
    AdoptionScreen(
        uiState = uiState,
        onBackClick = onBackClick,
        onAnimalClick = onAnimalClick,
        onProfileClick = onProfileClick,
        onChatClick = onChatClick,
        onNext = viewModel::onNext,
        onUnfavorite = viewModel::onUnfavorite,
        onFilterChange = viewModel::onFilterChange,
        // 화면에 들어온 시점에 이미 로그인 상태다(2026-09-25). 보는 중에 세션이 끊기면 ViewModel 이
        // AD3 의 401 을 받아 "다시 로그인해 주세요" 를 띄운다.
        onFavoriteToggle = {
            viewModel.onFavoriteToggle()
            true
        },
        modifier = modifier,
    )
}

/**
 * 사진 한 장이 화면을 채우고, 넘기면 다음 아이가 옵니다.
 *
 * 예전에는 사진 위아래로 안내 문구·통계·축종 필터·스와이프 안내가 쌓여 정작 사진이 화면의 절반도
 * 안 됐다. 이 화면에서 사람이 하는 일은 얼굴을 보고 마음이 가는지 보는 것 하나다 — 그 판단은 사진에서
 * 나오고, 품종·성별·지역은 마음이 간 다음에야 쓸모가 생긴다. 그래서 사진만 남기고 나머지는 눌렀을 때
 * 상세로 넘긴다(2026-09-24).
 *
 * 지역은 전국 고정이다. 좁혀 보는 기능은 다시 붙일 때 이 화면 위쪽에 들어온다.
 */
@Composable
fun AdoptionScreen(
    uiState: AdoptionUiState,
    onBackClick: () -> Unit,
    onAnimalClick: (Long, Boolean) -> Unit,
    onProfileClick: () -> Unit,
    onChatClick: () -> Unit,
    onNext: () -> Unit,
    onFavoriteToggle: () -> Boolean,
    onUnfavorite: (Long) -> Unit,
    onFilterChange: (SpeciesFilter, SexFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AdoptionHeader(
                onBackClick = onBackClick,
                onChatClick = onChatClick,
                onProfileClick = onProfileClick,
            )
        },
    ) { contentPadding ->
        var filterSheetOpen by remember { mutableStateOf(false) }
        var historySheetOpen by remember { mutableStateOf(false) }
        // 카드는 화면을 가득 채우지 않는다. 사방에 바탕이 보이고 그림자가 깔려야 "한 장씩 넘기는
        // 것" 으로 읽힌다 — 끝까지 채우면 그냥 배경 사진이 된다. 아래를 더 비워 두는 건 엄지가
        // 닿는 자리라, 거기서 끌어야 넘기기 편하다(2026-09-24).
        Column(modifier = Modifier.fillMaxSize().padding(contentPadding).padding(top = MeonggoSpacing.small)) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = CARD_SIDE_MARGIN)
                        .aspectRatio(CARD_ASPECT_RATIO),
            ) {
                when {
                    uiState.isLoading -> Loading()
                    uiState.errorMessage != null -> Message(uiState.errorMessage)
                    uiState.isEmpty -> Message("이 조건에 맞는 아이가 없어요")
                    uiState.isExhausted -> Exhausted()
                    else ->
                        CardStack(
                            uiState = uiState,
                            onNext = onNext,
                            onFavorite = onFavoriteToggle,
                            onAnimalClick = onAnimalClick,
                            modifier = Modifier.fillMaxSize(),
                        )
                }
            }
            // 걸러 볼 아이가 없을 때도 단추는 남는다 — 조건을 풀 길이 있어야 빈 화면에서 빠져나온다.
            AdoptionBottomBar(
                filterLabel = appliedFilterLabel(uiState.speciesFilter, uiState.sexFilter),
                passedCount = uiState.passed.size,
                onFilterClick = { filterSheetOpen = true },
                onHistoryClick = { historySheetOpen = true },
                modifier = Modifier.padding(horizontal = CARD_SIDE_MARGIN).padding(top = BAR_TOP_GAP),
            )
        }

        if (filterSheetOpen) {
            AdoptionFilterSheet(
                selected = uiState.speciesFilter,
                selectedSex = uiState.sexFilter,
                onApply = { species, sex ->
                    onFilterChange(species, sex)
                    filterSheetOpen = false
                },
                onDismiss = { filterSheetOpen = false },
            )
        }
        if (historySheetOpen) {
            AdoptionHistorySheet(
                passed = uiState.passed,
                favorites = uiState.passedFavorites,
                onAnimalClick = { postId, favorited ->
                    historySheetOpen = false
                    onAnimalClick(postId, favorited)
                },
                // 해제해도 시트는 닫지 않는다 — 여러 마리를 훑으며 정리하는 자리다.
                onUnfavorite = onUnfavorite,
                onDismiss = { historySheetOpen = false },
            )
        }
    }
}

@Composable
private fun AdoptionHeader(
    onBackClick: () -> Unit,
    onChatClick: () -> Unit,
    onProfileClick: () -> Unit,
) {
    // 제목은 줄 가운데에 둔다. 뒤로 단추 옆에 붙여 두던 때는 오른쪽 단추 두 개와 무게가 맞지 않아
    // 머리줄 전체가 왼쪽으로 쏠려 보였다. Row 로 늘어놓으면 제목 자리가 양옆 단추 폭에 따라 밀리므로
    // Box 에 얹어 화면 가운데에 못 박는다(2026-09-24).
    Box(modifier = Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 16.dp)) {
        MeonggoBackButton(onClick = onBackClick, modifier = Modifier.align(Alignment.CenterStart))
        // 글자 대신 로고를 둔다. 뜻은 로고가 그리므로 화면 이름은 읽어 주기로만 남긴다.
        Image(
            painter = painterResource(R.drawable.logo_sogaeting),
            contentDescription = "소개팅",
            modifier = Modifier.align(Alignment.Center).height(WORDMARK_HEIGHT),
            contentScale = ContentScale.Fit,
        )
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderChatButton(onChatClick)
            HeaderProfileButton(onProfileClick)
        }
    }
}

/**
 * 카드 두 장을 겹쳐 두고 맨 위 카드만 끕니다.
 *
 * 화면 너비의 [SWIPE_THRESHOLD_RATIO]를 넘겨 놓으면 왼쪽은 그냥 다음 카드로, 오른쪽은 좋아요 후
 * 다음 카드로 이동한다. 기준에 못 미치면 제자리로 돌아온다.
 */
@Composable
private fun CardStack(
    uiState: AdoptionUiState,
    onNext: () -> Unit,
    onFavorite: () -> Boolean,
    onAnimalClick: (Long, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = uiState.current ?: return
    val screenWidth = with(LocalConfiguration.current) { screenWidthDp.dp }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val screenWidthPx = with(density) { screenWidth.toPx() }
    val thresholdPx = screenWidthPx * SWIPE_THRESHOLD_RATIO
    val offsetX = remember(current.postId) { Animatable(0f) }
    val offsetY = remember(current.postId) { Animatable(0f) }
    var cardSize by remember(current.postId) { mutableStateOf(IntSize.Zero) }
    var isAnimating by remember(current.postId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 앞 카드가 얼마나 비켜났는지(0~1). 넘김 기준까지 끌면 1 이 되고, 뒤 카드는 그만큼 올라온다.
    // 기준을 넘겨 더 밀어도 1 에서 멈춘다 — 그 뒤로는 앞 카드가 화면 밖으로 나가는 구간이다.
    val advance = { (abs(offsetX.value) / thresholdPx).coerceIn(0f, 1f) }

    /** 기준에 못 미쳐 놓았을 때 제자리로 돌아갑니다. 두 축이 같이 움직여야 손에서 놓은 대로 따라온다. */
    suspend fun settleBack() {
        coroutineScope {
            launch { offsetX.animateTo(0f, RETURN_SPRING) }
            launch { offsetY.animateTo(0f, RETURN_SPRING) }
        }
    }

    /**
     * 카드를 화면 밖으로 보냅니다. 왼쪽이면 그냥 넘김, 오른쪽이면 좋아요 — 나가는 모습은 같다.
     *
     * 좋아요는 한동안 카드가 작아지며 머리줄의 마이페이지 단추로 빨려 들어갔다. 어디에 담겼는지
     * 알려 주려던 건데, 넘길 때마다 카드가 화면을 가로질러 반 초를 쓰는 바람에 연달아 보기가
     * 답답했다. 담긴 자리는 히스토리에서 확인하면 된다(2026-09-24).
     */
    suspend fun exitCard(toRight: Boolean) {
        val travel = cardSize.width.coerceAtLeast(screenWidthPx.toInt()) * CARD_EXIT_DISTANCE_RATIO
        offsetX.animateTo(
            if (toRight) travel else -travel,
            tween(SKIP_ANIMATION_MILLIS, easing = FastOutSlowInEasing),
        )
        onNext()
    }

    suspend fun skipCard() {
        if (isAnimating) return
        isAnimating = true
        exitCard(toRight = false)
    }

    suspend fun favoriteCard() {
        if (isAnimating) return
        isAnimating = true
        // 로그인이 필요해 좋아요가 안 걸리면 넘기지 않는다 — 넘겨 버리면 로그인하고 돌아와도 그
        // 아이는 이미 지나간 뒤다.
        if (!current.favorited && !onFavorite()) {
            settleBack()
            isAnimating = false
            return
        }
        exitCard(toRight = true)
    }

    Box(modifier = modifier) {
        // 다음 카드를 앞 카드 뒤에 겹쳐 둔다. 가만히 있을 때는 줄여 놓아서 앞 카드 안쪽으로 들어가
        // 화면에 안 나오고, 사진을 미리 받아 두는 역할만 한다 — 넘긴 뒤에 그제서야 받으면 다음 아이
        // 자리가 잠깐 비어 있다.
        //
        // 크기는 앞 카드와 같다. 줄여 두었더니 앞 카드가 빠지는 동안 작은 사진이 드러났다가 넘어간
        // 순간 제 크기로 툭 뛰었다 — 넘기는 내내 같은 크기여야 사진만 갈리는 것으로 보인다(2026-09-24).
        //
        // 그림자만 앞 카드가 비켜난 만큼 올린다. 가만히 있을 때도 깔아 두면 앞 카드 그림자와 정확히
        // 겹쳐 두 겹이 되어 평소 그림자가 배로 진해진다.
        uiState.next?.let { next ->
            AnimalCard(
                animal = next,
                onClick = {},
                modifier = Modifier.fillMaxSize().adoptionSoftShadow(advance),
            )
        }
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { cardSize = it.size }
                    .graphicsLayer {
                        translationX = offsetX.value
                        translationY = offsetY.value
                        rotationZ = offsetX.value / ROTATION_DIVISOR
                    }
                    // 그림자는 graphicsLayer 뒤에 건다. 앞에 걸면 카드만 움직이고 그림자는 제자리에 남는다.
                    .adoptionSoftShadow()
                    .pointerInput(current.postId, isAnimating) {
                        detectDragGestures(
                            onDragEnd = {
                                val moved = offsetX.value
                                scope.launch {
                                    when (resolveAdoptionSwipe(moved, thresholdPx)) {
                                        AdoptionSwipeAction.RESET -> settleBack()
                                        AdoptionSwipeAction.SKIP -> skipCard()
                                        AdoptionSwipeAction.FAVORITE -> favoriteCard()
                                    }
                                }
                            },
                            onDragCancel = {
                                scope.launch { settleBack() }
                            },
                            // 가로만 따라가던 때는 손이 위아래로 가도 카드가 옆으로만 밀려 손에서
                            // 떨어졌다. 두 축을 다 따라가야 손끝에 붙어 있는 것으로 보인다.
                            onDrag = { change, dragAmount ->
                                if (!isAnimating) {
                                    change.consume()
                                    scope.launch {
                                        offsetX.snapTo(offsetX.value + dragAmount.x)
                                        offsetY.snapTo(offsetY.value + dragAmount.y)
                                    }
                                }
                            },
                        )
                    },
        ) {
            AnimalCard(
                animal = current,
                onClick = { onAnimalClick(current.postId, current.favorited) },
                modifier = Modifier.fillMaxSize(),
            )
            // 끄는 쪽에 맞는 표시가 넘어가는 카드 한가운데에 배어 나온다. 손을 떼기 전에 어느 쪽으로
            // 가는지 알아야 되돌릴 수 있다.
            //
            // 둘 다 그려 두고 투명도만 바꾼다. 방향을 보고 어느 쪽을 그릴지 고르면 끄는 내내
            // 컴포지션이 다시 돈다 — 투명도는 그리기 단계에서만 읽는다.
            SwipeStamp(
                alpha = { if (offsetX.value > 0f) advance() else 0f },
                modifier = Modifier.align(Alignment.Center),
            ) {
                HeartIcon(color = FAVORITE_MARK_COLOR, filled = true, modifier = Modifier.size(STAMP_FAVORITE_ICON_SIZE))
            }
            SwipeStamp(
                alpha = { if (offsetX.value < 0f) advance() else 0f },
                modifier = Modifier.align(Alignment.Center),
            ) {
                SkipMark(color = SKIP_MARK_COLOR, markSize = STAMP_ICON_SIZE)
            }
        }
        // 단추는 카드와 같이 움직이지 않는다. 끄는 동안에도 엄지가 닿던 자리에 그대로 있어야 하고,
        // 카드에 붙여 두면 끌기 시작하는 손가락이 단추에 먼저 닿아 카드가 안 따라온다.
        AdoptionActionButton(
            onClick = { scope.launch { skipCard() } },
            contentDescription = "그냥 넘기기",
            modifier = Modifier.align(Alignment.BottomStart).padding(ACTION_BUTTON_MARGIN),
        ) {
            SkipMark(color = SKIP_MARK_COLOR)
        }
        AdoptionActionButton(
            onClick = { scope.launch { favoriteCard() } },
            contentDescription = if (current.favorited) "좋아요 취소" else "좋아요",
            modifier = Modifier.align(Alignment.BottomEnd).padding(ACTION_BUTTON_MARGIN),
        ) {
            HeartIcon(color = FAVORITE_MARK_COLOR, filled = true, modifier = Modifier.size(FAVORITE_ICON_SIZE))
        }
    }
}

/**
 * 카드 아래 줄 — 왼쪽에 필터, 그 옆부터 끝까지 히스토리입니다.
 *
 * 필터는 아무것도 안 걸었으면 동그라미 하나로 작게 있다가, 걸면 알약으로 늘어나 무엇을 걸었는지
 * 글자로 씁니다. 점만 찍어 두면 카드를 넘기다 "왜 고양이만 나오지" 할 때 답이 안 됩니다. 히스토리는
 * 그만큼 줄면서 숫자를 먼저 버립니다 — 폭이 모자랄 때 없어도 되는 건 숫자입니다.
 */
@Composable
private fun AdoptionBottomBar(
    filterLabel: String?,
    passedCount: Int,
    onFilterClick: () -> Unit,
    onHistoryClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MeonggoSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 테두리도 그림자도 없이 면 색만으로 가른다. 얇은 그림자를 깔아 봤더니 바로 위 카드 그림자와
        // 둘이 경쟁해 아래쪽이 지저분했다 — 카드 하나만 떠 있고 이 줄은 바탕에 놓여야 위계가 선다.
        // 대신 바탕보다 한 단계 눌러 둔 [BAR_FILL] 을 써서 경계는 남긴다(2026-09-24).
        Surface(
            modifier = Modifier.height(BAR_HEIGHT),
            shape = BAR_SHAPE,
            color = if (filterLabel == null) BAR_FILL else MaterialTheme.colorScheme.primary,
            onClick = onFilterClick,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = if (filterLabel == null) 0.dp else 14.dp).widthIn(min = BAR_HEIGHT),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterIcon(
                    color = if (filterLabel == null) BAR_INK else MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(BAR_ICON_SIZE),
                )
                if (filterLabel != null) {
                    Text(
                        filterLabel,
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = BAR_LABEL_SIZE),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            }
        }
        Surface(
            modifier = Modifier.weight(1f).height(BAR_HEIGHT),
            shape = BAR_SHAPE,
            color = BAR_FILL,
            onClick = onHistoryClick,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 왼쪽 필터 아이콘과 같은 색이다. 한 줄에 놓인 두 단추라 글씨 색이 갈리면 하나가
                // 더 중요한 것처럼 보인다.
                ClockIcon(
                    color = BAR_INK,
                    handColor = BAR_FILL,
                    modifier = Modifier.size(BAR_ICON_SIZE),
                )
                Text(
                    "히스토리",
                    color = BAR_INK,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = BAR_LABEL_SIZE),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                if (passedCount > 0) {
                    Text(
                        passedCount.toString(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = BAR_COUNT_SIZE),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 히스토리의 시계. 꽉 찬 동그라미에 바늘을 바탕색으로 파낸다 — 선으로 그리면 이 크기에서 뭉갠다. */
@Composable
private fun ClockIcon(
    color: Color,
    handColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val radius = size.minDimension / 2f
        val middle = Offset(size.width / 2f, size.height / 2f)
        val hand = size.minDimension * CLOCK_HAND_WIDTH
        drawCircle(color = color, radius = radius, center = middle)
        drawLine(
            color = handColor,
            start = middle,
            end = Offset(middle.x, middle.y - radius * CLOCK_LONG_HAND),
            strokeWidth = hand,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = handColor,
            start = middle,
            end = Offset(middle.x + radius * CLOCK_SHORT_HAND, middle.y + radius * CLOCK_SHORT_HAND * 0.62f),
            strokeWidth = hand,
            cap = StrokeCap.Round,
        )
    }
}

/**
 * 카드 아래 귀퉁이의 동그란 단추입니다.
 *
 * 사진 위에 얹히므로 흰 면과 그림자로 사진에서 띄운다 — 테두리만 두면 밝은 사진에서 사라진다.
 */
@Composable
private fun AdoptionActionButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.size(ACTION_BUTTON_SIZE).semantics { this.contentDescription = contentDescription },
        shape = CircleShape,
        color = ACTION_BUTTON_COLOR,
        shadowElevation = ACTION_BUTTON_ELEVATION,
        onClick = onClick,
    ) {
        Box(contentAlignment = Alignment.Center) { icon() }
    }
}

/**
 * 끄는 쪽을 알리는 큰 표시.
 *
 * 카드 위에 얹히므로 단추와 같은 흰 동그라미를 쓴다 — 아이콘만 크게 두면 사진 무늬에 묻힌다.
 */
@Composable
private fun SwipeStamp(
    alpha: () -> Float,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    Box(
        modifier =
            modifier
                .graphicsLayer { this.alpha = alpha() }
                .size(STAMP_SIZE)
                .background(ACTION_BUTTON_COLOR, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        icon()
    }
}

/** 넘기기 표시의 가위표. 끝을 둥글게 해 하트와 같은 결로 보이게 한다. */
@Composable
private fun SkipMark(
    color: Color,
    markSize: Dp = ACTION_ICON_SIZE,
) {
    Canvas(Modifier.size(markSize)) {
        val inset = size.minDimension * SKIP_MARK_INSET
        val stroke = size.minDimension * SKIP_MARK_STROKE
        drawLine(
            color = color,
            start = Offset(inset, inset),
            end = Offset(size.width - inset, size.height - inset),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(size.width - inset, inset),
            end = Offset(inset, size.height - inset),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/**
 * 카드와 아래 줄 단추 밑에 직접 그리는 그림자입니다.
 *
 * 플랫폼 그림자(`Modifier.shadow`)로는 이 화면에서 원하는 만큼 안 나왔다. elevation 을 16 → 26dp 로
 * 키우고 색 투명도를 34% → 85% 로 올려 봐도 바탕과 밝기 차가 20단계 남짓에 그쳤고, 그 차이가 44dp
 * 에 걸쳐 퍼져서 눈에는 아무것도 없는 것처럼 보였다 — 플랫폼 그림자는 진하기에 상한이 있다.
 *
 * 그래서 테두리를 조금씩 키운 둥근 사각형을 [SHADOW_STEPS] 겹 쌓아 직접 그린다. 바깥일수록 옅고
 * 카드에 붙을수록 진해서, 좁은 자리에 진한 띠가 생긴다. 눈이 읽는 건 넓은 그라데이션이 아니라
 * 가장자리의 대비다(2026-09-24).
 *
 * 카드에 가려지는 안쪽은 그리지 않는다 — 어차피 안 보이고, 반투명을 여러 겹 칠하면 그만큼 느려진다.
 *
 * [strength] 는 그릴 때 읽는다. 뒤 카드는 앞 카드가 밀려난 만큼만 그림자를 올려야 해서 값이 매 프레임
 * 바뀌는데, 인자로 받으면 그때마다 컴포지션을 다시 한다. 람다로 받아 그리기 단계에서만 읽는다.
 */
private fun Modifier.adoptionSoftShadow(strength: () -> Float = { 1f }): Modifier =
    drawBehind {
        val amount = strength()
        if (amount <= 0f) return@drawBehind
        val corner = CARD_CORNER.toPx()
        val spread = SHADOW_SPREAD.toPx()
        val offsetY = SHADOW_OFFSET_Y.toPx()
        // 바깥(옅음)부터 안쪽(진함)으로 덮어 간다. 겹칠수록 진해져 가장자리에서 가장 어둡다.
        for (step in SHADOW_STEPS downTo 1) {
            val outward = spread * step / SHADOW_STEPS
            // 위로는 조금만 번지게 한다. 사방으로 똑같이 퍼뜨렸더니 빛이 나는 것처럼 보였다 —
            // 그림자는 빛이 위에서 오니 아래가 진하고 위는 거의 없어야 떨어지는 것으로 읽힌다.
            val top = -outward * SHADOW_TOP_RATIO + offsetY
            val bottom = size.height + outward + offsetY
            drawRoundRect(
                color = SHADOW_COLOR.copy(alpha = SHADOW_ALPHA_PER_STEP * amount),
                topLeft = Offset(-outward, top),
                size = Size(size.width + outward * 2, bottom - top),
                cornerRadius = CornerRadius(corner + outward),
            )
        }
    }

internal enum class AdoptionSwipeAction {
    RESET,
    SKIP,
    FAVORITE,
}

internal fun resolveAdoptionSwipe(
    offsetX: Float,
    thresholdPx: Float,
): AdoptionSwipeAction =
    when {
        abs(offsetX) < thresholdPx -> AdoptionSwipeAction.RESET
        offsetX < 0f -> AdoptionSwipeAction.SKIP
        else -> AdoptionSwipeAction.FAVORITE
    }

@Composable
private fun AnimalCard(
    animal: AdoptionAnimal,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 사진 아래에 글 상자를 따로 두지 않는다. 그 상자가 카드의 3분의 1을 가져가 사진이 작아졌다.
    // 대신 사진 위에 얹는다 — 카드는 그대로 사진 한 장이고, 누구인지는 그 위에서 읽힌다.
    val label = animal.breedName?.let(::stripSpeciesPrefix) ?: animal.species.label
    val detail = listOfNotNull(animal.sex.label, animal.publicLocation).joinToString(" · ")
    Surface(
        modifier = modifier,
        shape = CARD_SHAPE,
        color = MaterialTheme.colorScheme.surfaceVariant,
        onClick = onClick,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (animal.thumbnailUrl == null) {
                MeonggoPhotoPlaceholder(modifier = Modifier.fillMaxSize())
            } else {
                SubcomposeAsyncImage(
                    model = animal.thumbnailUrl,
                    contentDescription = "$label 사진 (눌러서 자세히 보기)",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    loading = { MeonggoPhotoPlaceholder(modifier = Modifier.fillMaxSize()) },
                    error = { MeonggoPhotoPlaceholder(modifier = Modifier.fillMaxSize()) },
                )
            }
            // 사진 위에 흰 글자를 그냥 얹으면 밝은 사진에서 사라진다. 아래로 갈수록 어두워지는 면을
            // 깔아 글자 자리만 눌러 준다 — 사진 위쪽은 건드리지 않아 얼굴이 가려지지 않는다.
            Column(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, CARD_SCRIM_COLOR)))
                        .padding(
                            start = MeonggoSpacing.large,
                            end = MeonggoSpacing.large,
                            top = CARD_SCRIM_FADE,
                            bottom = MeonggoSpacing.extraLarge,
                        ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    label,
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    detail,
                    modifier = Modifier.padding(top = MeonggoSpacing.extraSmall),
                    color = Color.White.copy(alpha = CARD_SUBTITLE_ALPHA),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 걸러 볼 조건을 고르는 시트입니다.
 *
 * `전체` 칩은 두지 않는다. 여러 개를 고르는 자리에서 전체 칩이 같이 있으면 `강아지 + 전체` 처럼 뜻이
 * 겹치는 조합이 생긴다.
 *
 * 전체는 **아무것도 안 고른 상태**로 나타낸다. 둘 다 고른 것도 뜻이 같으므로 적용할 때 전체로 접고,
 * 다시 열면 아무것도 안 고른 모습으로 돌아온다 — 같은 뜻이 화면에서 두 모습으로 보이면 사용자가
 * 둘을 다른 것으로 여긴다. `초기화` 도 같은 자리, 즉 아무것도 안 고른 상태로 되돌린다.
 *
 * 성별도 종과 같은 규칙이다 — 서버 AD1 이 `sex` 를 받게 되면서 열렸다(2026-09-25). 고르면 원천 성별이
 * `UNKNOWN` 인 아이는 서버가 빠지는데, 그 사정은 칩 밑에 적지 않는다. 고른 성별만 보겠다는 뜻이
 * 이미 분명하고, 설명을 붙이면 두 칩짜리 묶음에 안내가 더 길어진다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdoptionFilterSheet(
    selected: SpeciesFilter,
    selectedSex: SexFilter,
    onApply: (SpeciesFilter, SexFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    var dog by remember(selected) { mutableStateOf(selected == SpeciesFilter.DOG) }
    var cat by remember(selected) { mutableStateOf(selected == SpeciesFilter.CAT) }
    var male by remember(selectedSex) { mutableStateOf(selectedSex == SexFilter.MALE) }
    var female by remember(selectedSex) { mutableStateOf(selectedSex == SexFilter.FEMALE) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(modifier = Modifier.padding(horizontal = MeonggoSpacing.large).padding(bottom = SHEET_BOTTOM_PADDING)) {
            FilterGroup("종") {
                FilterChoice("강아지", dog) { dog = !dog }
                FilterChoice("고양이", cat) { cat = !cat }
            }
            Spacer(Modifier.height(MeonggoSpacing.large))
            FilterGroup("성별") {
                FilterChoice("수컷", male) { male = !male }
                FilterChoice("암컷", female) { female = !female }
            }
            Spacer(Modifier.height(MeonggoSpacing.extraLarge))
            Row(horizontalArrangement = Arrangement.spacedBy(MeonggoSpacing.small)) {
                Surface(
                    modifier = Modifier.height(SHEET_BUTTON_HEIGHT),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    onClick = {
                        dog = false
                        cat = false
                        male = false
                        female = false
                    },
                ) {
                    Box(Modifier.padding(horizontal = MeonggoSpacing.large), contentAlignment = Alignment.Center) {
                        Text(
                            "초기화",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Surface(
                    modifier = Modifier.weight(1f).height(SHEET_BUTTON_HEIGHT),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primary,
                    onClick = { onApply(speciesFilterOf(dog = dog, cat = cat), sexFilterOf(male = male, female = female)) },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            "이 조건으로 보기",
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterGroup(
    label: String,
    content: @Composable RowScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MeonggoSpacing.small)) {
        // 묶음 이름은 소제목이다. 상세 화면의 구역 이름과 같은 크기로 맞춘다 — 칩보다 작으면
        // 이름표가 아니라 주석처럼 읽힌다.
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = SHEET_GROUP_LABEL_SIZE),
            fontWeight = FontWeight.Bold,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(MeonggoSpacing.small), content = content)
    }
}

@Composable
private fun FilterChoice(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
        enabled = enabled,
        onClick = onClick,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = MeonggoSpacing.large, vertical = MeonggoSpacing.small),
            color =
                when {
                    selected -> MaterialTheme.colorScheme.onPrimary
                    enabled -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISABLED_ALPHA)
                },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 안 고른 것도, 둘 다 고른 것도 전체다 — 서버에 종을 안 보내면 둘 다 온다. */
private fun speciesFilterOf(
    dog: Boolean,
    cat: Boolean,
): SpeciesFilter =
    when {
        dog && !cat -> SpeciesFilter.DOG
        cat && !dog -> SpeciesFilter.CAT
        else -> SpeciesFilter.ALL
    }

/** 둘 다 골랐거나 둘 다 안 골랐으면 전체다 — 뜻이 같아서 한 모습으로 접는다. */
private fun sexFilterOf(
    male: Boolean,
    female: Boolean,
): SexFilter =
    when {
        male && !female -> SexFilter.MALE
        female && !male -> SexFilter.FEMALE
        else -> SexFilter.ALL
    }

/**
 * 단추에 쓸 말. 안 걸었으면 null 이라 동그라미만 남는다.
 *
 * 둘 다 걸었으면 가운뎃점으로 잇는다 — `강아지 · 수컷`. 점 하나로는 "왜 이것만 나오지" 에 답하지
 * 못하고, 조건을 다 적으면 알약이 화면을 가로지른다.
 */
private fun appliedFilterLabel(
    species: SpeciesFilter,
    sex: SexFilter,
): String? {
    val parts = listOfNotNull(species.takeIf { it != SpeciesFilter.ALL }?.label, sex.takeIf { it != SexFilter.ALL }?.label)
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/**
 * 지나간 아이를 다시 보는 시트입니다 (단추 이름은 히스토리).
 *
 * 셋으로 나눈 격자는 얼굴이 알아볼 만한 가장 작은 크기다. 넷이면 강아지가 엄지만 해져서 정작
 * "아까 그 아이" 를 못 찾는다. 좋아요한 아이는 하트를 달아 넘긴 것과 가른다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdoptionHistorySheet(
    passed: List<AdoptionSwipeRecord>,
    favorites: List<AdoptionSwipeRecord>,
    onAnimalClick: (Long, Boolean) -> Unit,
    onUnfavorite: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var tab by remember { mutableStateOf(HistoryTab.ALL) }
    val shown = if (tab == HistoryTab.ALL) passed else favorites
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(modifier = Modifier.padding(horizontal = MeonggoSpacing.large).padding(bottom = SHEET_BOTTOM_PADDING)) {
            Text(
                "히스토리",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(MeonggoSpacing.medium))
            HistoryTabRow(
                selected = tab,
                allCount = passed.size,
                favoriteCount = favorites.size,
                onSelect = { tab = it },
            )
            Spacer(Modifier.height(MeonggoSpacing.medium))
            if (shown.isEmpty()) {
                Text(
                    if (tab == HistoryTab.ALL) "아직 넘긴 아이가 없어요" else "아직 좋아요한 아이가 없어요",
                    modifier = Modifier.fillMaxWidth().padding(vertical = MeonggoSpacing.extraLarge),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                return@Column
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(HISTORY_COLUMNS),
                modifier = Modifier.heightIn(max = HISTORY_MAX_HEIGHT),
                horizontalArrangement = Arrangement.spacedBy(MeonggoSpacing.small),
                verticalArrangement = Arrangement.spacedBy(MeonggoSpacing.small),
            ) {
                items(shown, key = { it.animal.postId }) { record ->
                    HistoryCell(
                        record = record,
                        onClick = { onAnimalClick(record.animal.postId, record.favorited) },
                        onUnfavorite = { onUnfavorite(record.animal.postId) },
                    )
                }
            }
        }
    }
}

private enum class HistoryTab(val label: String) {
    ALL("전체"),
    FAVORITE("좋아요"),
}

/**
 * 히스토리를 전체와 좋아요로 가르는 줄입니다.
 *
 * 밑줄 대신 칸을 통째로 칠한다 — 시트 안에서 밑줄 하나는 눌러서 바뀐 것인지 알아보기 어렵다.
 * 개수를 같이 쓰는 건 비어 있는 칸을 눌러 보고 나서야 비었다는 걸 알게 하지 않으려는 것이다.
 */
@Composable
private fun HistoryTabRow(
    selected: HistoryTab,
    allCount: Int,
    favoriteCount: Int,
    onSelect: (HistoryTab) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(HISTORY_TAB_CORNER),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(modifier = Modifier.padding(HISTORY_TAB_INSET)) {
            HistoryTab.entries.forEach { entry ->
                val count = if (entry == HistoryTab.ALL) allCount else favoriteCount
                HistoryTabCell(
                    label = entry.label,
                    count = count,
                    selected = entry == selected,
                    onClick = { onSelect(entry) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun HistoryTabCell(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.height(HISTORY_TAB_HEIGHT),
        shape = RoundedCornerShape(HISTORY_TAB_CORNER - HISTORY_TAB_INSET),
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerLowest else Color.Transparent,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                color = if (selected) BAR_INK else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            )
            Text(
                " $count",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun HistoryCell(
    record: AdoptionSwipeRecord,
    onClick: () -> Unit,
    onUnfavorite: () -> Unit,
) {
    val animal = record.animal
    val label = animal.breedName?.let(::stripSpeciesPrefix) ?: animal.species.label
    // 사진과 글자를 갈라 놓는다. 사진 위에 어두운 면을 깔고 글자를 얹는 건 소개팅 카드의 방식인데,
    // 이 앱의 목록들(잃어버렸어요·보호하고 있어요·좋아요)은 전부 사진 따로 글자 따로다. 시트 안에서만
    // 다른 규칙을 쓰면 같은 앱으로 안 읽힌다(2026-09-25).
    //
    // 모서리도 목록 썸네일과 같은 값을 쓴다. 사진이 카드가 아니라 사진으로 보여야 한다.
    Column(
        modifier =
            Modifier
                .clip(RoundedCornerShape(HISTORY_CELL_CORNER))
                .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(MeonggoSpacing.extraSmall),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(HISTORY_CELL_CORNER))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (animal.thumbnailUrl == null) {
                MeonggoPhotoPlaceholder(modifier = Modifier.fillMaxSize())
            } else {
                SubcomposeAsyncImage(
                    model = animal.thumbnailUrl,
                    contentDescription = "$label 사진",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    loading = { MeonggoPhotoPlaceholder(modifier = Modifier.fillMaxSize()) },
                    error = { MeonggoPhotoPlaceholder(modifier = Modifier.fillMaxSize()) },
                )
            }
            // 하트는 찜 상태이자 해제 단추다. 찜한 아이를 다시 보러 오는 곳이 여기라, 빼는 일도
            // 여기서 끝나야 한다 — 상세를 열고 닫고 하면 여러 마리를 정리할 때 번거롭다(2026-09-25).
            //
            // 칸 전체가 상세로 가는 클릭을 물고 있어서 하트에 따로 clickable 을 건다. 안쪽 아이콘은
            // 작아도 되지만 누르는 원은 손가락이 닿을 만큼 키운다.
            if (record.favorited) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(HISTORY_HEART_BADGE)
                            .clip(CircleShape)
                            .clickable(onClick = onUnfavorite)
                            .semantics { contentDescription = "$label 좋아요 해제" },
                    contentAlignment = Alignment.Center,
                ) {
                    HeartIcon(
                        color = FAVORITE_MARK_COLOR,
                        filled = true,
                        modifier = Modifier.size(HISTORY_HEART_ICON),
                    )
                }
            }
        }
        // 목록 카드의 정보줄과 같은 글씨다 — 크기·진하기·색을 맞춘다.
        Text(
            label,
            modifier = Modifier.padding(horizontal = MeonggoSpacing.extraSmall),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = HISTORY_CELL_LABEL_SIZE),
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 볼 카드가 떨어졌을 때 자리를 지키는 안내입니다.
 *
 * "처음부터 다시 보기" 를 뒀던 자리다. 넘긴 아이가 다시 올라오지 않게 된 뒤로는 되돌릴 것이
 * 없어 뺐다 — 다시 보려면 아래 히스토리로 간다(2026-09-24).
 */
@Composable
private fun Exhausted() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "오늘은 여기까지예요",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "지나온 아이는 아래 히스토리에서 다시 볼 수 있어요",
            modifier = Modifier.padding(top = MeonggoSpacing.small),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Loading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        MeonggoLoadingIndicator(contentDescription = "기다리는 아이를 불러오는 중")
    }
}

@Composable
private fun Message(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 카드 크기. 남은 자리를 다 쓰지 않고 가로세로 비를 못 박는다.
 *
 * 남은 높이의 몇 퍼센트로 잡으면 기기 높이에 따라 카드가 길쭉해졌다 납작해졌다 해서 같은 앱이
 * 기기마다 다른 모양으로 보인다. 비를 고정하면 어디서든 같은 카드가 나온다.
 *
 * 값은 참고한 소개팅 앱 화면을 재서 맞췄다 — 카드가 화면 폭의 88.6%, 높이의 72.6% 이고
 * 그 비가 가로:세로 = 0.578 이다. 아래가 더 비는 건 일부러다: 엄지가 닿는 자리라 거기서 끌어야
 * 넘기기 편하고, 카드가 바닥까지 닿으면 넘길 여유가 없어 보인다.
 */
private val CARD_SIDE_MARGIN = 22.dp
private const val CARD_ASPECT_RATIO = 0.578f

// CARD_CORNER 를 CARD_SHAPE 보다 먼저 둔다 — 파일 맨 위 프로퍼티는 적힌 순서대로 초기화된다.
private val CARD_CORNER = 24.dp
private val CARD_SHAPE = RoundedCornerShape(CARD_CORNER)

/** 넘기기·좋아요 단추. 엄지로 누르는 것이라 권장 최소 크기(48dp)보다 넉넉하게 둔다. */
private val ACTION_BUTTON_SIZE = 60.dp
private val ACTION_BUTTON_ELEVATION = 4.dp

/**
 * 단추와 스와이프 표시의 면.
 *
 * 비치게 해 봤는데 하필 그 자리가 아래쪽 어두운 음영 위라, 음영이 올라와 흰 단추가 회색으로
 * 보였다. 그냥 흰색으로 둔다(2026-09-24).
 */
private val ACTION_BUTTON_COLOR = Color.White
private val ACTION_ICON_SIZE = 26.dp

/** 하트만 한 뼘 크게. 같은 크기로 두면 가위표보다 작아 보인다 — 하트는 가운데가 비지 않고 꽉 찼다. */
private val FAVORITE_ICON_SIZE = 30.dp
private val ACTION_BUTTON_MARGIN = 18.dp

/**
 * 아래 줄 단추의 면.
 *
 * 음영을 뺀 뒤로는 색만으로 경계를 내야 한다. 공용 `surfaceVariant`(`Brown50`)는 바탕
 * (`Neutral25`)과 거의 붙어 있어 단추가 아니라 바탕의 얼룩처럼 보였다. `Brown100` 쪽으로 4분의 1쯤
 * 당겨 한 단계 눌러 둔다 — 더 가면 카드보다 아래 줄이 무거워진다(2026-09-24).
 */
private val BAR_FILL = Color(0xFFF2EADF)

/** 카드 아래 줄. 히스토리 글씨가 카드 위 이름과 비슷한 무게로 읽히려면 이만큼은 있어야 한다. */
private val BAR_HEIGHT = 56.dp
private val BAR_LABEL_SIZE = 16.sp
private val BAR_ICON_SIZE = 21.dp

/** 아래 줄의 글씨·그림 색. primary(Brown600) 보다 두 단계 어두운 갈색이라 크림색 면 위에서 또렷하다. */
private val BAR_INK = Brown800
private val BAR_COUNT_SIZE = 14.sp

/** 아래 줄 단추 모양. 카드 위의 동그란 단추와 갈라 놓는다 — 저건 사진에 얹힌 것이고 이건 화면의 것이다. */
private val BAR_CORNER = 16.dp
private val BAR_SHAPE = RoundedCornerShape(BAR_CORNER)

/** 시계 바늘 — 긴 바늘은 12시, 짧은 바늘은 4시 언저리. 굵기·길이는 아이콘 크기 대비 비율이다. */
private const val CLOCK_HAND_WIDTH = 0.095f
private const val CLOCK_LONG_HAND = 0.52f
private const val CLOCK_SHORT_HAND = 0.42f

/** 시트 안쪽 값. 아래 여백은 제스처 바에 글자가 물리지 않을 만큼 둔다. */
private val SHEET_BOTTOM_PADDING = 32.dp
private val SHEET_BUTTON_HEIGHT = 48.dp
private val SHEET_GROUP_LABEL_SIZE = 16.sp
private const val DISABLED_ALPHA = 0.45f

/** 히스토리 탭 줄. 안쪽 칸의 둥글기는 바깥에서 여백만큼 뺀 값이라 두 모서리가 나란히 돈다. */
private val HISTORY_TAB_HEIGHT = 38.dp
private val HISTORY_TAB_CORNER = 12.dp
private val HISTORY_TAB_INSET = 4.dp

/** 히스토리 격자. 셋이면 얼굴이 보이고 넷이면 엄지만 해진다. */
private const val HISTORY_COLUMNS = 3

/** 칸 모서리. 목록 썸네일(6dp)과 같은 값이라 사진이 카드가 아니라 사진으로 보인다. */
private val HISTORY_CELL_CORNER = 6.dp
private val HISTORY_CELL_LABEL_SIZE = 12.sp
private val HISTORY_MAX_HEIGHT = 420.dp

/** 하트 배지. 누르는 단추이기도 해서 손가락이 닿을 만큼 키웠다(18 → 30dp, 2026-09-25). */
private val HISTORY_HEART_BADGE = 30.dp
private val HISTORY_HEART_ICON = 22.dp

/** 끄는 쪽을 알리는 카드 한가운데 표시. 단추보다 커야 "지금 이쪽으로 간다" 로 읽힌다. */
private val STAMP_SIZE = 104.dp
private val STAMP_ICON_SIZE = 48.dp
private val STAMP_FAVORITE_ICON_SIZE = 55.dp

/**
 * 가위표는 먹색, 하트는 파스텔 분홍. 넘기기는 담담하고 좋아요는 눈에 띄어야 한다.
 *
 * 하트를 쨍한 빨강으로 두었더니 크림색 바탕의 다른 화면과 따로 놀았다. 분홍으로 낮췄다가 갈색
 * 팔레트와 결이 안 맞아, 옅은 벽돌색으로 옮겼다 — 붉되 따뜻한 쪽이라 이 앱에서는 이게 맞는다.
 */
private val SKIP_MARK_COLOR = Color(0xFF5A5A5A)
private val FAVORITE_MARK_COLOR = Color(0xFFDB7C64)

/** 가위표 획의 안쪽 여백과 굵기 (아이콘 크기 대비 비율). */
private const val SKIP_MARK_INSET = 0.22f
private const val SKIP_MARK_STROKE = 0.115f

/** 카드 아래 글자 자리에 까는 면. 사진이 밝아도 흰 글자가 읽히는 만큼만 어둡게 한다. */
private val CARD_SCRIM_COLOR = Color.Black.copy(alpha = 0.62f)

/** 그 면이 투명에서 시작해 다 어두워지기까지의 높이. 글자 위로 이만큼은 있어야 경계가 안 보인다. */
private val CARD_SCRIM_FADE = 64.dp

/** 둘째 줄은 이름보다 한 걸음 뒤로. 흰색 그대로 두면 두 줄이 같은 무게로 읽힌다. */
private const val CARD_SUBTITLE_ALPHA = 0.88f

/** 그림자 값. [adoptionSoftShadow] 가 왜 직접 그리는지는 거기 주석에 있다. */
private val SHADOW_SPREAD = 20.dp
private val SHADOW_OFFSET_Y = 8.dp
private const val SHADOW_STEPS = 14

/** 위로 번지는 정도. 0 이면 위에는 그림자가 없고 1 이면 사방이 같다. */
private const val SHADOW_TOP_RATIO = 0.3f

/**
 * 그림자 색. 앱 공용 그림자색(`Brown800`)을 쓰면 짙게 깔았을 때 크림색 바탕과 섞여 누렇게 뜬다.
 * 글자에 쓰는 따뜻한 먹색이 같은 팔레트면서 색이 안 돌아 깨끗하다.
 */
private val SHADOW_COLOR = Neutral900

/** 한 겹의 투명도. 겹겹이 쌓여 카드 가장자리에서 가장 진해진다 — 한 겹을 진하게 하면 테가 진다. */
private const val SHADOW_ALPHA_PER_STEP = 0.02f

/** 카드와 아래 줄 사이. 카드 그림자가 끝나는 자리에 줄이 오도록 그림자 크기에서 뽑는다. */
private val BAR_TOP_GAP = SHADOW_SPREAD + SHADOW_OFFSET_Y

/**
 * 머리줄 로고 높이.
 *
 * 로고가 글자만 남아서(하트·발자국 없음) 예전 34dp 보다 낮춘다 — 같은 높이로 두면 글자만 그만큼
 * 커져 머리줄을 다 먹는다. 26dp 면 이 자리에 있던 22sp 제목과 비슷한 무게다.
 */
private val WORDMARK_HEIGHT = 26.dp

private const val SWIPE_THRESHOLD_RATIO = 0.25f
private const val ROTATION_DIVISOR = 60f

/** 놓았을 때 제자리로 돌아오는 움직임. 살짝 넘겼다 잡히는 편이 손에서 놓은 물건처럼 보인다. */
private val RETURN_SPRING = spring<Float>(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow)
private const val CARD_EXIT_DISTANCE_RATIO = 1.2f

/** 카드가 화면 밖으로 나가는 시간. 좋아요도 같은 값을 쓴다 — 나가는 방향만 다르다. */
private const val SKIP_ANIMATION_MILLIS = 220

@Preview(showBackground = true)
@Composable
private fun AdoptionScreenPreview() {
    MeonggoBanjeomTheme {
        AdoptionScreen(
            uiState =
                AdoptionUiState(
                    isLoading = false,
                    animals =
                        listOf(
                            AdoptionAnimal(
                                postId = 1L,
                                species = Species.DOG,
                                breedName = "믹스견",
                                sex = Sex.MALE,
                                color = "갈색",
                                publicLocation = "서울특별시 강북구",
                                thumbnailUrl = null,
                                noticeEndDate = "2025-01-14",
                                daysSinceNoticeEnd = 612L,
                                lastSyncedAt = "2026-09-17T01:30:00Z",
                            ),
                        ),
                ),
            onBackClick = {},
            onAnimalClick = { _, _ -> },
            onProfileClick = {},
            onChatClick = {},
            onNext = {},
            onUnfavorite = {},
            onFavoriteToggle = { true },
            onFilterChange = { _, _ -> },
        )
    }
}
