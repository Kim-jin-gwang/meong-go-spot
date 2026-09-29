package com.hotdog.meonggocuisine.feature.post.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.URLUtil
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import com.hotdog.meonggocuisine.BuildConfig
import com.hotdog.meonggocuisine.R
import com.hotdog.meonggocuisine.core.designsystem.component.HeartIcon
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoLoadingIndicator
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoPhotoPlaceholder
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoScreenHeader
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoSurfaces
import com.hotdog.meonggocuisine.core.designsystem.component.meonggoFloatingShadow
import com.hotdog.meonggocuisine.core.designsystem.theme.MeonggoBanjeomTheme
import com.hotdog.meonggocuisine.core.text.speciesLabel
import com.hotdog.meonggocuisine.feature.community.ui.sexLabel

@Composable
fun PostDetailRouteScreen(
    onBackClick: () -> Unit,
    onEditClick: (Long) -> Unit,
    onCloseClick: (Long) -> Unit,
    onAnalyzeClick: (Long, String?) -> Unit,
    onChatClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PostDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    PostDetailScreen(
        uiState = uiState,
        onBackClick = onBackClick,
        onRetryClick = viewModel::retry,
        onEditClick = onEditClick,
        onCloseClick = onCloseClick,
        onAnalyzeClick = onAnalyzeClick,
        onChatClick = onChatClick,
        onShelterCallClick = { phone -> context.startDialer(phone) },
        onPortalClick = { url -> context.openUrl(url) },
        modifier = modifier,
    )
}

/** 공공 분실 신고의 포털 게시판을 브라우저로 연다. http(s) 가 아니면 열지 않고, 처리할 앱이 없으면 조용히 넘어간다. */
private fun Context.openUrl(url: String) {
    if (!URLUtil.isNetworkUrl(url)) return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    runCatching { startActivity(intent) }
}

/**
 * 보호센터 공식 전화번호를 기본 전화 앱에 채워 넣습니다.
 *
 * 통화 권한이 필요한 직접 발신 대신 사용자가 발신을 확인하는 `ACTION_DIAL`을 사용합니다.
 */
private fun Context.startDialer(phone: String) {
    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
    runCatching { startActivity(intent) }
}

/**
 * 게시물 상세입니다.
 *
 * 사실 하나마다 카드를 한 장씩 쌓던 때는 한 건을 보는 데 화면 두 장이 필요했다. 실종 게시물을
 * 보는 사람은 날짜·지역·특징을 한눈에 맞춰 봐야 하는데, 스크롤로 갈라 놓으면 그 대조가 안 된다.
 * 지금은 흰 면 한 장 안에 이름표와 값을 줄로 세워, 흔한 건은 한 화면에 다 들어온다.
 *
 * 그래도 넘치면(보호소 게시물처럼 줄이 많은 경우) 카드 안에서 굴러간다. 머리줄과 아래 단추는 늘
 * 제자리다 — 등록 화면과 같은 짜임이다.
 */
@Composable
fun PostDetailScreen(
    uiState: PostDetailUiState,
    onBackClick: () -> Unit,
    onRetryClick: () -> Unit,
    onEditClick: (Long) -> Unit,
    onCloseClick: (Long) -> Unit,
    onAnalyzeClick: (Long, String?) -> Unit,
    onChatClick: (Long) -> Unit,
    onShelterCallClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    onPortalClick: (String) -> Unit = {},
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .navigationBarsPadding(),
    ) {
        MeonggoScreenHeader(
            title = uiState.detail?.headerTitle ?: "게시물 상세",
            onBackClick = onBackClick,
        )
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = MeonggoSurfaces.gutter)
                    .padding(bottom = ScreenBottomInset),
        ) {
            when {
                uiState.isLoading -> DetailCard(Modifier.weight(1f)) { LoadingContent() }
                uiState.errorMessage != null ->
                    DetailCard(Modifier.weight(1f)) { ErrorContent(uiState.errorMessage, onRetryClick) }

                uiState.detail != null ->
                    DetailContent(
                        detail = uiState.detail,
                        onEditClick = onEditClick,
                        onCloseClick = onCloseClick,
                        onAnalyzeClick = onAnalyzeClick,
                        onChatClick = onChatClick,
                        onShelterCallClick = onShelterCallClick,
                        onPortalClick = onPortalClick,
                    )
            }
        }
    }
}

/**
 * 카드 위에 덮는 상세입니다 — 머리줄도 아래 단추도 없이 내용만.
 *
 * 소개팅에서 쓴다. 거기서는 이 창이 화면을 갈아 끼우는 게 아니라 보던 카드를 잠깐 덮는 것이라,
 * `게시물 상세` 머리줄과 뒤로 단추가 있으면 다른 화면으로 넘어간 것처럼 보인다. 닫는 건 바깥을
 * 누르거나 뒤로 가기다.
 *
 * 아래 단추(분석·수정·종료)도 뺀다 — 여기 오는 건 보호소가 올린 공공 게시물이라 애초에 하나도
 * 켜지지 않는다.
 */
@Composable
fun PostDetailPopupRouteScreen(
    onChatClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    favorited: Boolean? = null,
    onFavoriteToggle: () -> Unit = {},
    viewModel: PostDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    PostDetailPopup(
        uiState = uiState,
        onRetryClick = viewModel::retry,
        onChatClick = onChatClick,
        onShelterCallClick = { phone -> context.startDialer(phone) },
        onPortalClick = { url -> context.openUrl(url) },
        favorited = favorited,
        onFavoriteToggle = onFavoriteToggle,
        modifier = modifier,
    )
}

@Composable
fun PostDetailPopup(
    uiState: PostDetailUiState,
    onRetryClick: () -> Unit,
    onChatClick: (Long) -> Unit,
    onShelterCallClick: (String) -> Unit,
    onPortalClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** null 이면 찜을 다루지 않는 자리다 — 하트를 그리지 않는다. */
    favorited: Boolean? = null,
    onFavoriteToggle: () -> Unit = {},
) {
    val detail = uiState.detail
    val photoPager = rememberPagerState(pageCount = { (detail?.photoUrls?.size ?: 0).coerceAtLeast(1) })
    var expandedPage by remember { mutableStateOf<Int?>(null) }
    DetailCard(modifier.heightIn(max = PopupMaxHeight), padding = PopupCardPadding) {
        when {
            uiState.isLoading -> Box(Modifier.height(PopupBusyHeight)) { LoadingContent() }
            uiState.errorMessage != null -> Box(Modifier.height(PopupBusyHeight)) { ErrorContent(uiState.errorMessage, onRetryClick) }
            detail != null ->
                // 화면과 달리 사진까지 같이 굴린다. 창이 내용만큼만 높아서, 사진을 고정하면 굴릴
                // 자리가 거의 안 남는다.
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(FactGap),
                ) {
                    Box {
                        DetailPhotos(
                            urls = detail.photoUrls,
                            pagerState = photoPager,
                            modifier = Modifier.height(PhotoHeight),
                            onPhotoClick = { expandedPage = it },
                        )
                        // 사진 오른쪽 아래에 얹는다. 넘긴 아이를 다시 찜하거나 무를 수 있는 유일한
                        // 자리다 — 카드로는 돌아가지 않는다(2026-09-25).
                        //
                        // 면을 깔지 않고 사진 위에 바로 올린다. 대신 선을 굵게 둔다 — 빈 하트는
                        // 윤곽선뿐이라 밝은 사진 위에서 기본 굵기로는 흐려진다.
                        favorited?.let { on ->
                            Box(
                                modifier =
                                    Modifier
                                        .align(Alignment.BottomEnd)
                                        // 사진 개수 배지와 같은 선에 놓는다. 배지는 여백 10dp·높이
                                        // 25dp 라 가운데가 바닥에서 22dp 인데, 하트는 44dp 칸 안의
                                        // 28dp 아이콘이라 칸을 바닥에 붙여야 가운데가 22dp 가 된다.
                                        // 옆 여백만 배지의 왼쪽 여백과 맞춘다(2 + 칸 안쪽 8 = 10dp).
                                        .padding(end = PopupFavoriteInset)
                                        .size(PopupFavoriteSize)
                                        .clip(CircleShape)
                                        .clickable(onClick = onFavoriteToggle)
                                        .semantics {
                                            contentDescription = if (on) "좋아요 해제" else "좋아요"
                                        },
                                contentAlignment = Alignment.Center,
                            ) {
                                // 빈 하트는 흰 선, 찜하면 제 색으로 찬다. 사진은 어두운 쪽이 많아
                                // 흰 선이 잘 보이고, 색이 들어오는 순간이 "담겼다" 로 읽힌다.
                                HeartIcon(
                                    color = if (on) PopupFavoriteColor else Color.White,
                                    filled = on,
                                    strokeWidth = POPUP_FAVORITE_STROKE,
                                    modifier = Modifier.size(PopupFavoriteIcon),
                                )
                            }
                        }
                    }
                    DetailFactList(detail, onChatClick, onShelterCallClick, onPortalClick)
                }
        }
    }
    expandedPage?.let { page ->
        PhotoViewerDialog(
            urls = detail?.photoUrls.orEmpty(),
            initialPage = page,
            onDismiss = { expandedPage = null },
        )
    }
}

/** 상세 한 장이 올라앉는 흰 면입니다. 목록 카드·등록 카드와 같은 둥글기와 그림자를 쓴다. */
@Composable
private fun DetailCard(
    modifier: Modifier = Modifier,
    padding: Dp = CardPadding,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth().meonggoFloatingShadow(),
        shape = MeonggoSurfaces.cardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Column(Modifier.fillMaxHeight().padding(padding), content = content)
    }
}

@Composable
private fun LoadingContent() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        MeonggoLoadingIndicator(contentDescription = "게시물 상세를 불러오는 중")
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetryClick: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(14.dp))
        DetailActionButton(label = "다시 시도", onClick = onRetryClick, modifier = Modifier.width(140.dp))
    }
}

@Composable
private fun ColumnScope.DetailContent(
    detail: PostDetailUiModel,
    onEditClick: (Long) -> Unit,
    onCloseClick: (Long) -> Unit,
    onAnalyzeClick: (Long, String?) -> Unit,
    onChatClick: (Long) -> Unit,
    onShelterCallClick: (String) -> Unit,
    onPortalClick: (String) -> Unit,
) {
    val photoPager = rememberPagerState(pageCount = { detail.photoUrls.size.coerceAtLeast(1) })
    var expandedPage by remember { mutableStateOf<Int?>(null) }
    DetailCard(Modifier.weight(1f)) {
        // 사진은 늘 같은 크기다. 게시물마다 줄 수가 달라 사진이 늘었다 줄었다 하면, 목록에서
        // 여러 건을 훑을 때 사진 자리가 계속 바뀌어 눈이 따라가지 못한다. 넘치는 건 줄 쪽이
        // 굴러서 받는다 — 사진과 아래 단추는 제자리에 둔다.
        Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(FactGap)) {
            DetailPhotos(
                urls = detail.photoUrls,
                pagerState = photoPager,
                modifier = Modifier.height(PhotoHeight),
                onPhotoClick = { expandedPage = it },
            )
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(FactGap),
            ) {
                DetailFactList(detail, onChatClick, onShelterCallClick, onPortalClick)
            }
        }
    }
    DetailActions(detail, onAnalyzeClick, onEditClick, onCloseClick)

    expandedPage?.let { page ->
        PhotoViewerDialog(
            urls = detail.photoUrls,
            initialPage = page,
            onDismiss = { expandedPage = null },
        )
    }
}

/**
 * 사진 아래에 놓이는 줄 전부입니다.
 *
 * 화면과 팝업이 같은 내용을 쓰되 담는 그릇이 다르다 — 화면은 사진을 고정하고 줄만 굴리고, 팝업은
 * 통째로 굴린다. 그 차이만 바깥에 두고 내용은 여기 한 군데에 둔다(2026-09-24).
 */
@Composable
private fun ColumnScope.DetailFactList(
    detail: PostDetailUiModel,
    onChatClick: (Long) -> Unit,
    onShelterCallClick: (String) -> Unit,
    onPortalClick: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(FactGap)) {
        run {
            // 무엇인지부터 칩으로 한 줄, 나머지는 덩이로 나눠 둔다. 줄이 다 같은 무게로 늘어서
            // 있으면 어디부터 볼지 알 수 없다는 말이 나왔다(2026-09-24).
            DetailChips(detail, Modifier.padding(top = ChipsTopGap, bottom = ChipsBottomGap))
            DetailSection(if (detail.type == PostDetailType.LOST) "실종" else "발견") {
                // 구역 이름이 이미 "발견"·"실종" 이라 줄 이름에서 그 말을 뺀다 — 안 그러면 `발견 발견 일시` 가 된다.
                DetailFact("일시", detail.primaryDateValue)
                DetailFact("지역", detail.primaryRegionValue)
            }
            // 지금 이 아이가 어디 있는지를 한 구역에 모은다. 보호소 게시물에서 `장소` 는 시·군·구까지라
            // 보호소 주소와 같은 곳을 두 번 말했다 — 더 자세한 주소만 남긴다(2026-09-24).
            if (detail.type == PostDetailType.SHELTERING) {
                DetailSection("보호") {
                    if (detail.source == PostDetailSource.SHELTER) {
                        DetailFact("보호소", detail.shelterName ?: "보호소")
                        detail.shelterAddress?.let { DetailFact("주소", it) }
                        detail.shelterPhone?.let { phone ->
                            DetailFact("연락처", phone) {
                                DetailSmallButton(
                                    label = "전화",
                                    onClick = { onShelterCallClick(phone) },
                                    contentDescription = "보호소 공식 전화번호 $phone 로 전화 걸기",
                                )
                            }
                        }
                    } else {
                        DetailFact("장소", detail.currentPlace ?: "확인 중")
                    }
                }
            }
            // 보호소는 위 구역에서 이미 연락처까지 말했다. 남은 건 사람에게 닿는 경우다.
            when (detail.source) {
                PostDetailSource.USER_POST ->
                    DetailSection("연락") {
                        DetailFact("작성자", detail.authorName ?: "작성자") {
                            if (detail.chatAvailable) {
                                DetailSmallButton(label = "메시지", onClick = { onChatClick(detail.postId) })
                            }
                        }
                    }

                PostDetailSource.PUBLIC_LOST ->
                    DetailSection("연락") {
                        DetailFact("관할 기관", detail.reportOrgName ?: "관할 기관 미확인")
                        // 신고자에게 닿는 길은 원문 게시판뿐이라 이 구역에 둔다. 특이사항 밑에 붙여 봤더니
                        // 생김새 설명 끝에 파란 줄이 따라붙어 특징의 일부처럼 읽혔다(2026-09-24).
                        detail.reportPortalUrl?.let { LostReportPortalLink(it, onPortalClick) }
                    }

                PostDetailSource.SHELTER -> Unit
            }
            val hasNotice = detail.shelterNoticeNo != null || detail.shelterNoticePeriod != null
            var noticeOpen by remember { mutableStateOf(false) }
            DetailSection(
                label = "특이사항",
                trailing =
                    if (detail.source == PostDetailSource.SHELTER && hasNotice) {
                        { NoticeInfoButton(open = noticeOpen, onToggle = { noticeOpen = !noticeOpen }) }
                    } else {
                        null
                    },
            ) {
                if (noticeOpen) {
                    NoticeBubble(detail.shelterNoticeNo, detail.shelterNoticePeriod)
                }
                Text(
                    text = detail.featureText ?: "특이사항이 없습니다",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
                )
            }
        }
    }
}

/**
 * 무엇인지 한눈에 알려 주는 칩 줄입니다.
 *
 * 목록 카드가 품종·성별로 게시물을 가리므로 상세도 같은 값으로 시작한다. 보호 상태만 색을 준다 —
 * 이 화면에서 제일 먼저 알고 싶은 값이고, 강조가 두 군데 이상이면 강조가 아니게 된다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailChips(
    detail: PostDetailUiModel,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        detail.animalName?.let { DetailChip(it) }
        DetailChip(detail.breedName ?: speciesLabel(detail.species))
        DetailChip(sexLabel(detail.sex))
        // 보호소 게시물은 상태가 사실상 늘 `보호중` 이라(수집기가 활성으로 받는 값이 `공고중`·`보호중` 뿐,
        // 운영 표본 70건 전부 `보호중`) 칩을 붙여도 게시물을 가리지 못한다. 사용자가 올린 보호 게시물만
        // 임시보호·병원·인계예정처럼 갈리므로 거기서만 띄운다.
        if (detail.type == PostDetailType.SHELTERING && detail.source != PostDetailSource.SHELTER) {
            DetailChip(detail.currentStatus ?: "확인 중", highlighted = true)
        }
    }
}

@Composable
private fun DetailChip(
    text: String,
    highlighted: Boolean = false,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color =
            if (highlighted) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surfaceContainerLowest
            },
        border =
            BorderStroke(
                width = 1.dp,
                color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            ),
    ) {
        // 높이를 글자에 맡기지 않는다. 한 줄짜리 Text 는 그 글자를 실제로 그린 글꼴의 위아래 여유만큼만
        // 차지하는데, 한글은 한글 글꼴로 `TEST` 같은 로마자는 라틴 글꼴로 그려져 그 여유가 서로 다르다 —
        // 이름이 로마자인 게시물만 칩 하나가 3dp 낮았다(2026-09-24). lineHeight 로는 반만 좁혀져서
        // 칸 높이를 아예 못 박는다. 폭은 그대로 글자를 따라간다.
        Box(
            modifier = Modifier.height(ChipHeight).padding(horizontal = 11.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

/**
 * 같은 이야기를 하는 줄들을 한 덩이로 묶습니다.
 *
 * 줄을 다 같은 무게로 늘어놓으면 어디부터 봐야 할지 모른다. 위에 옅은 선과 작은 이름표를 두어
 * 덩이를 가른다 — 보호소 게시물처럼 줄이 열 개 가까운 경우에 특히 차이가 크다.
 */
@Composable
private fun DetailSection(
    label: String,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(FactGap)) {
        // 구역 사이를 줄 사이보다 넓게 벌린다. 같은 간격이면 선과 이름표가 있어도 덩이로 안 읽힌다.
        // 빈 Spacer 를 넣지 않고 선에 여백을 준다 — Spacer 는 그 자체가 줄로 세어져 간격이 한 칸 더 붙는다.
        HorizontalDivider(
            modifier = Modifier.padding(top = SectionTopGap),
            color = MaterialTheme.colorScheme.outline.copy(alpha = SECTION_LINE_ALPHA),
        )
        Row(modifier = Modifier.padding(top = SectionLabelGap), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
                fontWeight = FontWeight.Bold,
            )
            trailing?.invoke()
        }
        content()
    }
}

/**
 * 구역 이름 옆의 동그라미 i. 눌러야 공고 번호·기간이 말풍선으로 붙습니다.
 *
 * 이 화면에서 사용자가 하는 일은 "내 아이인가" 하나고, 그 답은 사진·품종·성별·발견 지역·일시에서
 * 나온다. 공고 번호와 기간은 내 아이라고 판단한 뒤에야 쓸모가 생기는 값이라 줄을 차지하지 않는다.
 * 창을 띄우지 않는 건 화면을 가리지 않기 위해서다 — 보던 자리에 그대로 붙는 편이 덜 끊긴다.
 */
@Composable
private fun NoticeInfoButton(
    open: Boolean,
    onToggle: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .clip(CircleShape)
                .semantics { contentDescription = if (open) "공고 정보 닫기" else "공고 정보 보기" }
                .clickable(onClick = onToggle)
                .padding(4.dp),
    ) {
        InfoIcon(
            color = if (open) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 오른쪽 위에 꼭지가 달린 말풍선. 꼭지는 누른 아이콘 쪽을 가리킨다. */
@Composable
private fun NoticeBubble(
    noticeNo: String?,
    noticePeriod: String?,
) {
    val bubble = MaterialTheme.colorScheme.surfaceVariant
    Column(horizontalAlignment = Alignment.End, modifier = Modifier.fillMaxWidth()) {
        Canvas(Modifier.padding(end = BubbleTailInset).size(width = 14.dp, height = 7.dp)) {
            val path =
                Path().apply {
                    moveTo(size.width / 2, 0f)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
            drawPath(path, color = bubble)
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = bubble,
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(FactGap)) {
                noticeNo?.let { DetailFact("공고 번호", it) }
                noticePeriod?.let { DetailFact("공고 기간", it) }
            }
        }
    }
}

/** 동그라미 안의 i. 아이콘 라이브러리를 들이지 않고 Canvas 로 그린다(다른 아이콘과 같은 방식). */
@Composable
private fun InfoIcon(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.size(17.dp)) {
        val stroke = Stroke(width = 1.5.dp.toPx())
        val radius = size.minDimension / 2 - stroke.width / 2
        drawCircle(color = color, radius = radius, style = stroke)
        val centerX = size.width / 2
        drawCircle(color = color, radius = size.width * 0.045f, center = Offset(centerX, size.height * 0.3f))
        drawLine(
            color = color,
            start = Offset(centerX, size.height * 0.45f),
            end = Offset(centerX, size.height * 0.72f),
            strokeWidth = stroke.width,
        )
    }
}

/**
 * 이름표 한 줄과 값입니다.
 *
 * 이름표 너비를 고정해 값이 한 줄로 맞춰 선다. 사실마다 카드와 동그란 아이콘을 두던 때는 한 건에
 * 70dp 넘게 썼는데, 아이콘이 뜻을 더해 주지도 않았다.
 */

@Composable
private fun DetailFact(
    label: String,
    value: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    // 줄 높이를 잡아 둔다. 전화 단추가 붙은 줄만 키가 커져 그 위아래 간격이 달라 보였다.
    Row(
        modifier = Modifier.heightIn(min = FactRowHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.width(FactLabelWidth),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp),
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.5.sp),
        )
        trailing?.invoke()
    }
}

/**
 * 공공 분실 신고의 원문으로 가는 줄입니다.
 *
 * 신고자 연락처는 우리 쪽에 없고 동물보호관리시스템에만 있다. 그 사정을 세 줄로 적어 두었더니
 * 안내가 특이사항보다 길어 정작 읽을 내용이 밀렸다 — 갈 곳 한 줄만 남긴다(2026-09-24).
 *
 * 건별 식별자가 없어 상세가 아니라 목록으로 열린다. QA(2026-09-21) 에서 첫 항목을 "이 신고" 로
 * 읽어 다른 개로 본 일이 있어, 줄 이름에 "목록" 을 박아 둔다.
 */
@Composable
private fun LostReportPortalLink(
    url: String,
    onPortalClick: (String) -> Unit,
) {
    // 이 화면의 다른 글씨는 누를 수 없다. 색만으로는 그 차이가 안 보여 밑줄을 함께 둔다 —
    // 색을 구별하지 못해도 누를 수 있다는 게 드러난다.
    //
    // clickable 을 padding 보다 앞에 둔다. 뒤에 두면 여백이 누르는 영역 밖으로 빠져 글자 높이만 남는다.
    Text(
        text = "동물보호관리시스템에서 신고 목록 확인하기 ›",
        modifier =
            Modifier
                .clickable { onPortalClick(url) }
                .padding(vertical = 4.dp),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
        fontWeight = FontWeight.Bold,
        textDecoration = TextDecoration.Underline,
    )
}

/**
 * 상세의 사진 자리. 올린 사진을 옆으로 넘겨 봅니다.
 *
 * 여러 장을 올려도 첫 장만 보여 주던 자리다 — 나머지가 있다는 것조차 알 수 없었다. 자리 크기는
 * 그대로 두고 그 안에서 넘긴다. 사진마다 높이가 달라지면 아래 줄과 단추가 게시물마다 다른 곳에 놓인다.
 *
 * 몇 장 중 몇 번째인지는 사진 위에 얹는다. 밑에 따로 줄을 두면 사진이 한 장뿐인 게시물과 여러 장인
 * 게시물의 높이가 달라진다.
 */
@Composable
private fun DetailPhotos(
    urls: List<String>,
    pagerState: PagerState,
    onPhotoClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (urls.isEmpty()) {
            // 실제 동물 사진을 자리표시자로 쓰지 않는다 (MeonggoPhotoPlaceholder 주석 참고).
            MeonggoPhotoPlaceholder(contentDescription = "게시물 사진 없음")
            return@Box
        }
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val url = urls[page]
            Box(
                modifier = Modifier.fillMaxSize().clickable { onPhotoClick(page) },
                contentAlignment = Alignment.Center,
            ) {
                // 자리를 가득 채운다. 통째로 넣어 봤더니 세로 사진이 높이에 맞춰 줄면서 양옆이 크게 비어
                // 사진이 되레 작아 보였다 — 보호소·신고 사진은 대부분 휴대폰 세로 사진이다(2026-09-24).
                PostPhoto(
                    url = url,
                    contentScale = ContentScale.Crop,
                    contentDescription = "게시물 사진 ${page + 1}/${urls.size} (눌러서 크게 보기)",
                )
            }
        }
        if (urls.size > 1) {
            // 왼쪽 아래에 둔다. 오른쪽 아래는 소개팅 팝업의 찜 하트 자리라 겹친다(2026-09-25).
            Surface(
                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f),
            ) {
                Text(
                    text = "${pagerState.currentPage + 1}/${urls.size}",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** 실제 URL 또는 프리뷰용 샘플을 그린다. 상세 썸네일과 전체 화면 뷰어가 같은 규칙을 쓴다. */
@Composable
private fun PostPhoto(
    url: String,
    contentScale: ContentScale,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    if (url.startsWith(SAMPLE_PHOTO_PREFIX)) {
        Image(
            painter = painterResource(R.drawable.sample_lost_dog),
            contentDescription = contentDescription,
            modifier = modifier.fillMaxSize(),
            contentScale = contentScale,
        )
    } else {
        SubcomposeAsyncImage(
            model = url,
            contentDescription = contentDescription,
            modifier = modifier.fillMaxSize(),
            contentScale = contentScale,
            error = { MeonggoPhotoPlaceholder(contentDescription = "게시물 사진을 불러올 수 없음") },
        )
    }
}

/**
 * 사진 전체 화면 뷰어. 검은 배경 위에 사진만 놓고, 옆으로 넘겨 다른 사진을 보고, 두 손가락으로 확대·이동하고,
 * 한 번 탭(사진 위든 빈 곳이든)하거나 ✕ 를 누르면 닫힌다. 다른 UI 는 그대로 두고 사진만 키우는 것이 목적이라
 * 별도 화면(경로)을 만들지 않았다.
 *
 * 확대한 동안에는 넘기기를 막는다. 안 막으면 키운 사진을 손가락으로 끌어 볼 때 그 끌기를 줄이 가져가
 * 옆 사진으로 넘어가 버린다.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoViewerDialog(
    urls: List<String>,
    initialPage: Int,
    onDismiss: () -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = initialPage) { urls.size }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    // 다른 사진으로 넘어가면 확대를 푼다 — 앞 사진에서 키운 배율이 다음 사진에 그대로 남으면
    // 무엇을 보고 있는지 알 수 없다.
    LaunchedEffect(pagerState.currentPage) {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
    }
    val transformState =
        rememberTransformableState { zoomChange, panChange, _ ->
            scale = (scale * zoomChange).coerceIn(1f, 5f)
            if (scale <= 1f) {
                offsetX = 0f
                offsetY = 0f
            } else {
                offsetX += panChange.x
                offsetY += panChange.y
            }
        }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = scale <= 1f,
            ) { page ->
                val zoomed = page == pagerState.currentPage
                PostPhoto(
                    url = urls[page],
                    contentScale = ContentScale.Fit,
                    contentDescription = "게시물 사진 ${page + 1}/${urls.size} 크게 보기",
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = if (zoomed) scale else 1f
                                scaleY = if (zoomed) scale else 1f
                                translationX = if (zoomed) offsetX else 0f
                                translationY = if (zoomed) offsetY else 0f
                            }
                            // 탭은 닫기, 두 손가락은 확대·이동 — transformable 은 움직임이 있을 때만 이벤트를 가져가므로 둘이 공존한다.
                            .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) }
                            // 확대했을 때만 끌기를 가져간다. 늘 가져가면 한 손가락 옆넘김을 줄이 못 받아 사진이 안 넘어갔다.
                            .transformable(transformState, canPan = { scale > 1f }),
                )
            }
            if (urls.size > 1) {
                Text(
                    text = "${pagerState.currentPage + 1}/${urls.size}",
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Surface(
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = 16.dp).size(40.dp),
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.15f),
                onClick = onDismiss,
            ) {
                Box(contentAlignment = Alignment.Center) { Text("✕", color = Color.White, fontSize = 18.sp) }
            }
        }
    }
}

/** 값 옆에 붙는 작은 단추(전화·메시지)입니다. */
@Composable
private fun DetailSmallButton(
    label: String,
    onClick: () -> Unit,
    contentDescription: String? = null,
) {
    Surface(
        modifier =
            Modifier
                // Material 은 누르는 요소에 최소 48dp 를 강제한다. 그대로 두면 이 단추가 붙은 줄만
                // 키가 커져 위아래 간격이 다른 줄과 어긋났다.
                .requiredHeight(SmallButtonHeight)
                .padding(start = 8.dp)
                .then(
                    contentDescription
                        ?.let { Modifier.semantics { this.contentDescription = it } }
                        ?: Modifier,
                ),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        onClick = onClick,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.5.sp),
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * 카드 밑에 붙는 단추 줄입니다.
 *
 * 수정과 종료는 나란히 둔다 — 세 줄로 쌓으면 그만큼 카드가 짧아져 정작 봐야 할 내용이 밀린다.
 */
@Composable
private fun DetailActions(
    detail: PostDetailUiModel,
    onAnalyzeClick: (Long, String?) -> Unit,
    onEditClick: (Long) -> Unit,
    onCloseClick: (Long) -> Unit,
) {
    // canAnalyzeMatch 는 "이 사용자가 이 게시물을 분석할 권한이 있는가" 이고,
    // MATCHING_ENABLED 는 "그 기능이 출시됐는가" 다. 둘은 다른 관심사라 권한 모델을
    // 건드리지 않고 여기서 막는다. 엔진이 없는 동안 요청을 넣으면 영구 PENDING 이 된다.
    val showAnalyze = detail.canAnalyzeMatch && BuildConfig.MATCHING_ENABLED
    if (!showAnalyze && !detail.canEdit && !detail.canClose) return
    Spacer(Modifier.height(SectionGap))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (showAnalyze) {
            DetailActionButton(
                label = "유사도 분석하기",
                onClick = { onAnalyzeClick(detail.postId, detail.animalName) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // 수정·종료는 글을 쓴 사람만 하는 관리용이라 한 줄에 나란히, 테두리만 두른 모양으로
        // 둔다. 셋 다 꽉 찬 단추면 정작 눌러야 할 분석하기가 묻힌다.
        if (detail.canEdit || detail.canClose) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (detail.canEdit) {
                    DetailActionButton(
                        label = "게시글 수정",
                        onClick = { onEditClick(detail.postId) },
                        modifier = Modifier.weight(1f),
                        filled = false,
                    )
                }
                if (detail.canClose) {
                    // 빨강을 쓰지 않는다. 이 앱에서 빨강은 "고쳐야 할 것" 이고, 종료는 잘못이
                    // 아니라 되찾았을 때 하는 일이다. 되돌릴 수 없다는 경고는 종료 화면이 한다.
                    DetailActionButton(
                        label = "게시글 종료",
                        onClick = { onCloseClick(detail.postId) },
                        modifier = Modifier.weight(1f),
                        filled = false,
                        muted = true,
                    )
                }
            }
        }
    }
}

/** 등록 화면의 `다음`·`이전` 과 같은 모양입니다. */
@Composable
private fun DetailActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    muted: Boolean = false,
) {
    val accent = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
    val border = if (muted) MaterialTheme.colorScheme.outline else accent
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = if (filled) accent else MaterialTheme.colorScheme.surfaceContainerLowest,
        border = if (filled) null else BorderStroke(1.dp, border),
        onClick = onClick,
    ) {
        Box(Modifier.padding(vertical = 15.dp), contentAlignment = Alignment.Center) {
            Text(
                text = label,
                color = if (filled) MaterialTheme.colorScheme.onPrimary else accent,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private const val SAMPLE_PHOTO_PREFIX = "sample://photo/"

private val CardPadding = 18.dp

/**
 * 팝업 안쪽 여백. 상세 화면보다 두껍다.
 *
 * 팝업은 사방이 바탕에 둘러싸여 있어 같은 여백이라도 더 좁아 보인다 — 화면에서는 카드 밖 여백이
 * 숨 쉴 자리를 대신 내주지만 여기는 그게 없다(2026-09-24).
 */
private val PopupCardPadding = 26.dp

/** 칩 한 칸의 높이. 글꼴이 아니라 이 값이 정한다 — 한글 칩이 저절로 잡던 높이 그대로다. */
private val ChipHeight = 29.dp
private val FactGap = 8.dp
private val FactRowHeight = 30.dp
private val SmallButtonHeight = 28.dp
private val FactLabelWidth = 96.dp
private val PhotoHeight = 230.dp

/**
 * 팝업 창의 최대 높이. 이보다 길면 안에서 굴린다.
 *
 * 화면을 다 먹지는 않는다 — 위아래로 바탕이 보여야 덮은 것이지 넘어간 것이 아니다. 그 선 안에서는
 * 넉넉한 편이 낫다. 620dp 로 뒀더니 보호소 게시물이 연락처 줄에서 잘려 답답했다(2026-09-24).
 *
 * [PopupCardPadding] 이 두꺼워진 만큼 같이 올려 둔다 — 안 그러면 여백이 내용을 밀어낸다.
 */
private val PopupMaxHeight = 736.dp

/** 팝업 사진 위의 찜 하트. 소개팅 카드의 하트와 같은 색·크기를 쓴다. */
private val PopupFavoriteSize = 44.dp
private val PopupFavoriteIcon = 28.dp
private const val POPUP_FAVORITE_STROKE = 2.6f
private val PopupFavoriteInset = 2.dp
private val PopupFavoriteColor = Color(0xFFDB7C64)

/** 팝업이 불러오는 중·못 불러왔을 때의 높이. 내용이 없어 창이 납작해지는 것을 막는다. */
private val PopupBusyHeight = 200.dp
private const val SECTION_LINE_ALPHA = 0.5f
private val SectionGap = 14.dp
private val SectionTopGap = 8.dp
private val SectionLabelGap = 4.dp
private val ChipsTopGap = 12.dp
private val ChipsBottomGap = 4.dp
private val BubbleTailInset = 10.dp
private val ScreenBottomInset = 16.dp

@Preview(showBackground = true, widthDp = 411, heightDp = 900, backgroundColor = 0xFFFDFBF7)
@Composable
private fun LostOwnerPostDetailPreview() {
    MeonggoBanjeomTheme {
        PostDetailScreen(
            uiState = PostDetailUiState(detail = sampleLostDetail(owner = true)),
            onBackClick = {},
            onRetryClick = {},
            onEditClick = {},
            onCloseClick = {},
            onAnalyzeClick = { _, _ -> },
            onChatClick = {},
            onShelterCallClick = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 411, heightDp = 900, backgroundColor = 0xFFFDFBF7)
@Composable
private fun ShelteringOtherPostDetailPreview() {
    MeonggoBanjeomTheme {
        PostDetailScreen(
            uiState = PostDetailUiState(detail = sampleShelteringDetail(owner = false, source = PostDetailSource.USER_POST)),
            onBackClick = {},
            onRetryClick = {},
            onEditClick = {},
            onCloseClick = {},
            onAnalyzeClick = { _, _ -> },
            onChatClick = {},
            onShelterCallClick = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 411, heightDp = 900, backgroundColor = 0xFFFDFBF7)
@Composable
private fun ShelterPostDetailPreview() {
    MeonggoBanjeomTheme {
        PostDetailScreen(
            uiState = PostDetailUiState(detail = sampleShelteringDetail(owner = false, source = PostDetailSource.SHELTER)),
            onBackClick = {},
            onRetryClick = {},
            onEditClick = {},
            onCloseClick = {},
            onAnalyzeClick = { _, _ -> },
            onChatClick = {},
            onShelterCallClick = {},
        )
    }
}

private fun sampleLostDetail(owner: Boolean) =
    PostDetailUiModel(
        postId = 1,
        title = "콩이를 잃어버렸어요",
        headerTitle = "게시물 상세",
        animalName = "콩이",
        breedName = "말티즈",
        type = PostDetailType.LOST,
        source = PostDetailSource.USER_POST,
        status = "ACTIVE",
        owner = owner,
        photoUrls = listOf(SAMPLE_PHOTO_PREFIX, SAMPLE_PHOTO_PREFIX, SAMPLE_PHOTO_PREFIX),
        authorName = "콩이주인",
        shelterName = null,
        shelterPhone = null,
        shelterAddress = null,
        shelterNoticeNo = null,
        shelterNoticePeriod = null,
        reportOrgName = null,
        reportPortalUrl = null,
        primaryDateLabel = "실종 일시",
        primaryDateValue = "2026.08.20 오후 3:30",
        primaryRegionLabel = "실종 지역",
        primaryRegionValue = "서울 마포구",
        currentStatus = null,
        currentPlace = null,
        featureText = "갈색 목줄을 착용하고 있어요",
        species = "DOG",
        sex = "MALE",
        chatAvailable = !owner,
        canAnalyzeMatch = owner,
        canEdit = owner,
        canClose = owner,
    )

private fun sampleShelteringDetail(
    owner: Boolean,
    source: PostDetailSource,
) = PostDetailUiModel(
    postId = 2,
    title = if (source == PostDetailSource.SHELTER) "몽이를 보호하고 있어요" else "콩이를 보호하고 있어요",
    headerTitle = if (owner) "내 게시물 상세" else "게시물 상세",
    animalName = if (source == PostDetailSource.SHELTER) "몽이" else "콩이",
    breedName = "한국 고양이",
    type = PostDetailType.SHELTERING,
    source = source,
    status = "ACTIVE",
    owner = owner,
    photoUrls = listOf(SAMPLE_PHOTO_PREFIX, SAMPLE_PHOTO_PREFIX, SAMPLE_PHOTO_PREFIX),
    authorName = "ckdscls",
    shelterName = "마포구 동물보호센터",
    shelterPhone = if (source == PostDetailSource.SHELTER) "02-1234-1234" else null,
    shelterAddress = if (source == PostDetailSource.SHELTER) "서울특별시 마포구 상암동 495" else null,
    shelterNoticeNo = if (source == PostDetailSource.SHELTER) "서울-마포-2026-00123" else null,
    shelterNoticePeriod = if (source == PostDetailSource.SHELTER) "2026.08.20 ~ 2026.08.30" else null,
    reportOrgName = null,
    reportPortalUrl = null,
    primaryDateLabel = "발견 일시",
    primaryDateValue = "2026.08.20 오후 3:30",
    primaryRegionLabel = "발견 지역",
    primaryRegionValue = "서울 마포구",
    currentStatus = "양호",
    currentPlace = if (source == PostDetailSource.SHELTER) "보호소" else "본인 집",
    featureText = "갈색 목줄을 착용하고 있어요",
    species = "CAT",
    sex = "FEMALE",
    chatAvailable = !owner && source == PostDetailSource.USER_POST,
    canAnalyzeMatch = false,
    canEdit = owner,
    canClose = owner,
)
