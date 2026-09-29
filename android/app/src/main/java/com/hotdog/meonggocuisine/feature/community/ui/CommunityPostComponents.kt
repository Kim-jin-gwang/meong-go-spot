package com.hotdog.meonggocuisine.feature.community.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hotdog.meonggocuisine.R
import com.hotdog.meonggocuisine.core.designsystem.component.FilterIcon
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoLoadingIndicator
import com.hotdog.meonggocuisine.core.designsystem.component.PlusIcon
import com.hotdog.meonggocuisine.core.designsystem.component.SearchIcon
import com.hotdog.meonggocuisine.core.designsystem.theme.MeonggoBanjeomTheme
import com.hotdog.meonggocuisine.core.designsystem.token.MeonggoCardColors
import com.hotdog.meonggocuisine.core.designsystem.token.MeonggoSpacing
import com.hotdog.meonggocuisine.core.text.speciesLabel
import com.hotdog.meonggocuisine.core.text.stripSpeciesPrefix
import com.hotdog.meonggocuisine.feature.community.data.LostPostSummary

/*
 * `잃어버렸어요`와 `보호하고 있어요`가 함께 쓰는 목록 부품입니다.
 *
 * 두 화면은 같은 [LostPostSummary] 를 같은 모양으로 보여 주는데, 예전에는 카드와 검색줄을 각
 * 화면이 따로 갖고 있어 한쪽만 손보면 디자인이 갈라졌다. 실제로 갈라진 적이 있어 여기로 모았다.
 * 화면마다 다른 건 문구(사진 설명·등록 버튼 이름)뿐이라 인자로 받는다.
 */

/**
 * 목록 위에 놓이는 지역·검색 묶음입니다.
 *
 * 목록 화면은 이걸 `LazyColumn` 의 첫 항목으로 넣는다. 고정해 두면 좁은 화면에서 카드가 두 장
 * 남짓만 보이는데, 지역과 검색어는 한 번 정하면 계속 보고 있을 값이 아니라 스크롤에 함께 밀린다.
 * 헤더(로고·채팅·마이페이지)만 고정이다. 목록이 비었거나 실패한 상태에서는 스크롤할 것이 없어
 * 그대로 위에 둔다.
 *
 * 정렬 줄은 여기 없다. 정렬과 축종·성별을 [CommunityFilterSheet] 로 모으면서 통째로 빠졌고,
 * 그만큼 카드가 한 장 더 보인다.
 */
@Composable
internal fun CommunityListTop(
    regionName: String,
    onRegionChangeClick: () -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onCreateClick: () -> Unit,
    createContentDescription: String,
    onFilterClick: () -> Unit,
    isFilterNarrowed: Boolean,
) {
    Column {
        CurrentRegion(regionName, onRegionChangeClick)
        CommunitySearchSection(
            query = query,
            onQueryChange = onQueryChange,
            onSearch = onSearch,
            onCreateClick = onCreateClick,
            createContentDescription = createContentDescription,
            onFilterClick = onFilterClick,
            isFilterNarrowed = isFilterNarrowed,
        )
    }
}

/**
 * 품종 검색칸과 필터·등록 버튼입니다.
 *
 * 갈색 외곽선 대신 흰 라운드 면이고, 테두리 없이 그림자로 경계를 잡아 홈 카드와 같은 재질로 보인다.
 */
@Composable
internal fun CommunitySearchSection(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onCreateClick: () -> Unit,
    createContentDescription: String,
    onFilterClick: () -> Unit,
    isFilterNarrowed: Boolean,
) {
    val focusManager = LocalFocusManager.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = LIST_GUTTER),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.weight(1f).height(50.dp).floatingCardShadow(RoundedCornerShape(20.dp)),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchIcon(color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.weight(1f).padding(start = 10.dp),
                    singleLine = true,
                    textStyle =
                        MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 14.5.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions =
                        KeyboardActions(onSearch = {
                            focusManager.clearFocus()
                            onSearch()
                        }),
                    decorationBox = { inner ->
                        if (query.isEmpty()) {
                            Text(
                                // 서버 P1 은 `breedName` 부분 검색만 받는다. `검색어를 입력하세요` 처럼
                                // 범위를 열어 두면 이름이나 지역을 넣고 0건을 보게 된다.
                                "품종을 입력하세요",
                                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.5.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    },
                )
            }
        }
        // 등록만큼 자주 누르는 자리는 아니라 면을 채우지 않는다. 둘 다 갈색으로 채우면 무엇이
        // 이 화면의 주요 동작인지 흐려진다.
        Surface(
            modifier =
                Modifier
                    .size(50.dp)
                    .semantics { contentDescription = if (isFilterNarrowed) "필터 (적용됨)" else "필터" }
                    .floatingCardShadow(RoundedCornerShape(18.dp)),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            onClick = onFilterClick,
        ) {
            Box(contentAlignment = Alignment.Center) {
                FilterIcon(color = MaterialTheme.colorScheme.onPrimaryContainer)
                // 조건이 걸려 있으면 점을 찍는다. 시트를 닫고 나면 무엇을 걸어 뒀는지 알 길이
                // 이것뿐이다 — 목록만 봐서는 걸러진 결과인지 원래 그것뿐인지 구분되지 않는다.
                if (isFilterNarrowed) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(9.dp)
                            .size(8.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                    )
                }
            }
        }
        // 이 화면의 주요 동작이라 파스텔이 아니라 브랜드 갈색으로 채운다.
        Surface(
            modifier =
                Modifier
                    .size(50.dp)
                    .semantics { contentDescription = createContentDescription }
                    .floatingCardShadow(RoundedCornerShape(18.dp)),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.primary,
            onClick = onCreateClick,
        ) {
            Box(contentAlignment = Alignment.Center) {
                PlusIcon(color = MaterialTheme.colorScheme.onPrimary, iconSize = 22.dp)
            }
        }
    }
}

/**
 * 게시물 카드 목록입니다.
 *
 * [thumbnailDescription] 과 [loadMoreLabel] 만 화면마다 다르다. 실종 목록과 보호 목록은 읽어 주는
 * 말이 달라야 하고("실종 동물 사진" / "보호 중인 동물 사진"), 더 불러올 때 알리는 말도 다르다.
 *
 * [header] 는 지역·검색·정렬 묶음이다. 고정해 두는 대신 첫 항목으로 넣어 카드와 함께 밀린다.
 * 좌우 여백을 `contentPadding` 이 아니라 각 항목이 갖는 것도 이 때문이다 — [header] 안의 부품은
 * 이미 자기 여백이 있어서 바깥에서 한 번 더 주면 두 겹이 된다.
 *
 * 아래 여백은 떠 있는 탭 바 높이만큼 준다. 탭 바가 내용 위에 겹쳐 있어서 여백이 없으면 마지막
 * 카드를 끝까지 올려도 바에 가린다.
 *
 * 다음 쪽은 내려가면 저절로 붙는다. `더 보기` 단추를 두던 때는 한 쪽을 볼 때마다 손을 멈추고
 * 단추를 눌러야 했는데, 이 목록에서 하는 일은 사진을 훑어 내려가는 것 하나다 — 그 손을 멈출
 * 이유가 없다. [loadMoreLabel] 은 이제 단추가 아니라 아래에 잠깐 뜨는 알림에 쓰인다.
 */
@Composable
internal fun CommunityPostList(
    posts: List<LostPostSummary>,
    hasNext: Boolean,
    isLoadingMore: Boolean,
    loadMoreError: String?,
    onLoadMore: () -> Unit,
    onPostClick: (Long) -> Unit,
    thumbnailDescription: String,
    loadMoreLabel: String,
    header: @Composable () -> Unit,
) {
    val listState = rememberLazyListState()
    // 바닥에 닿기 전에 미리 부른다. 다 내려간 뒤에 부르면 빈 자리를 보며 기다리게 된다.
    //
    // 실패했을 때는 멈춘다 — 안 그러면 바닥에 있는 채로 같은 요청을 끝없이 다시 보낸다.
    // 그때만 다시 시도 단추를 보인다.
    val shouldLoadMore by remember(hasNext, isLoadingMore, loadMoreError) {
        derivedStateOf {
            if (!hasNext || isLoadingMore || loadMoreError != null) {
                false
            } else {
                val info = listState.layoutInfo
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                lastVisible >= info.totalItemsCount - LOAD_MORE_PREFETCH
            }
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) onLoadMore()
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = BOTTOM_BAR_HEIGHT),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "listTop") { header() }
        items(posts, key = LostPostSummary::postId) { post ->
            Box(Modifier.padding(horizontal = LIST_GUTTER)) {
                CommunityPostCard(post, thumbnailDescription, onClick = { onPostClick(post.postId) })
            }
        }
        if (hasNext && loadMoreError == null) {
            item(key = "loadingMore") {
                Box(Modifier.fillMaxWidth().padding(MeonggoSpacing.large), contentAlignment = Alignment.Center) {
                    MeonggoLoadingIndicator(loadMoreLabel)
                }
            }
        }
        loadMoreError?.let { message ->
            item(key = "loadMoreError") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(MeonggoSpacing.large),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(MeonggoSpacing.medium),
                ) {
                    Text(message, color = MaterialTheme.colorScheme.error)
                    Button(onLoadMore) { Text("다시 시도") }
                }
            }
        }
    }
}

/**
 * 게시물 한 건의 요약 카드입니다.
 *
 * 목록은 요약이라 후보를 고를 때 쓰는 값만 담는다 — 사진, 이름 또는 품종, 성별, 사건 날짜,
 * 공개 지역, 출처. 나이·중성화 여부처럼 고른 뒤 확인하는 값과 보호소 연락처는 게시물 상세에서 본다.
 *
 * 축종은 카드에 적지 않는다. 사진과 품종에 이미 드러나고, 축종으로 좁히는 건 [CommunityFilterSheet]
 * 가 맡는다 — 좁혀 놓은 목록에서 모든 카드가 같은 축종을 되뇌는 것은 자리 낭비다.
 *
 * 출처는 제목 오른쪽에 둔다. 빼지는 않는다 — 출처 표시는 `UI-04`·`UR-DAT-003`(Must)의 인수
 * 조건이고, 보호소 게시물은 전화, 사용자 게시물은 채팅으로 연락 방법이 갈려서 목록에서부터
 * 구분이 필요하다.
 */
@Composable
private fun CommunityPostCard(
    post: LostPostSummary,
    thumbnailDescription: String,
    onClick: () -> Unit,
) {
    // 테두리 대신 그림자로 경계를 잡고 모서리를 홈 카드와 같은 22dp 로 둔다.
    Surface(
        modifier = Modifier.fillMaxWidth().floatingCardShadow(),
        shape = LIST_CARD_SHAPE,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        onClick = onClick,
    ) {
        Row(
            // 사진 왼쪽만 조금 더 띄운다. 카드 테두리에 사진이 붙어 보이지 않게.
            Modifier.padding(start = 18.dp, top = 14.dp, end = 14.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(THUMBNAIL_TEXT_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 사진은 파스텔 면 위에 얹는다. 사진이 없을 때 자리표시자가 흰 카드에서 사라지지 않는다.
            Surface(
                modifier = Modifier.size(THUMBNAIL_SIZE),
                shape = THUMBNAIL_SHAPE,
                color = MeonggoCardColors.adoptionSurface,
            ) {
                PostThumbnail(
                    imageUrl = post.thumbnailUrl,
                    contentDescription = thumbnailDescription,
                    modifier = Modifier.size(THUMBNAIL_SIZE),
                    shape = THUMBNAIL_SHAPE,
                )
            }
            // 이름·품종, 성별, 위치, 날짜를 같은 무게의 네 줄로 둔다. 예전에는 품종이 제목 자리에 굵게
            // 앉아 있었는데, 목록에서 알고 싶은 건 "무엇이 어디서 언제" 라 품종만 제목일 이유가 없었다.
            // 네 줄 모두 같은 크기의 그림을 같은 자리에 두어 줄이 눈으로 따라가진다.
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(INFO_LINE_GAP)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CommunityInfoLine(
                        iconRes = R.drawable.ic_list_breed,
                        text = animalLabel(post),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    SourceBadge(post.source)
                }
                CommunityInfoLine(iconRes = sexIcon(post.sex), text = sexLabel(post.sex))
                CommunityInfoLine(iconRes = R.drawable.ic_list_place, text = post.publicLocation)
                CommunityInfoLine(iconRes = R.drawable.ic_list_date, text = post.eventDate)
            }
        }
    }
}

/**
 * 그림 하나와 글 한 줄.
 *
 * 네 줄이 같은 그림 크기·같은 자리를 쓴다. 손으로 그리던 때는 줄마다 크기와 두께가 달라 줄이
 * 어긋나 보였다(2026-09-24). 그림은 이미 브랜드 색이라 덧칠하지 않는다.
 */
@Composable
private fun CommunityInfoLine(
    @DrawableRes iconRes: Int,
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(INFO_ICON_SIZE),
            // 그림마다 칠해진 색이 조금씩 달라 한 색으로 덮는다. 줄끼리 색이 다르면 한 벌로 안 읽힌다.
            colorFilter = ColorFilter.tint(INFO_ICON_COLOR),
        )
        Text(
            text,
            Modifier.padding(start = 8.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = INFO_LINE_ALPHA),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp),
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 성별 줄의 그림. 값에 맞는 기호를 쓴다 — 하나로 통일했더니 `수컷` 줄에 ♀ 가 붙었다.
 *
 * 성별을 모르는 건(`성별 미상`) 보호소 데이터에 있을 수 있어 그때는 발바닥으로 둔다. ♂·♀ 중
 * 아무거나 붙이면 없는 사실을 말하게 되고, 자리를 비우면 그 줄만 글이 앞으로 당겨진다.
 */
private fun sexIcon(sex: String): Int =
    when (sex) {
        "MALE" -> R.drawable.ic_list_sex_male
        "FEMALE" -> R.drawable.ic_list_sex_female
        else -> R.drawable.ic_list_breed
    }

/**
 * 발바닥 줄에 쓰는 말입니다.
 *
 * 이름을 지어 준 게시물은 `미누-포메` 처럼 이름과 품종을 함께 보여 준다. 이름만으로는 어떤
 * 아이인지 모르고, 품종만으로는 같은 품종 게시물이 구별되지 않는다. 둘 중 없는 건 빼고,
 * 품종이 없으면 축종으로 대신한다.
 */
private fun animalLabel(post: LostPostSummary): String {
    val breed = post.breedName?.let(::stripSpeciesPrefix)?.takeIf(String::isNotBlank)
    return listOfNotNull(post.name?.takeIf(String::isNotBlank), breed ?: speciesLabel(post.species))
        .joinToString("-")
}

/**
 * 출처 배지입니다.
 *
 * 제목 오른쪽에 색 없는 회백 면으로 조용히 둔다. 그래도 빼지는
 * 않는다 — 보호소 게시물은 전화, 사용자 게시물은 채팅으로 연락 방법이 갈려서, 누구에게 연락하는
 * 건지 목록에서부터 알 수 있어야 한다. 보호소 이름과 작성자 닉네임은 정책상 상세에서만 보인다.
 * 글자 대비 5.5:1.
 */
@Composable
private fun SourceBadge(source: String) {
    Surface(
        shape = BADGE_SHAPE,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Text(
            when (source) {
                "SHELTER" -> "보호소"
                "PUBLIC_LOST" -> "공공"
                else -> "사용자"
            },
            Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = INFO_LINE_ALPHA),
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 10.5.sp),
            fontWeight = FontWeight.Medium,
        )
    }
}

/** 배지 모양. 알약이라 사각 카드·칩과 구분되고, 글자 길이가 달라도 같은 모양으로 보인다. */
private val BADGE_SHAPE = RoundedCornerShape(percent = 50)

/** 사진 크기. 사진이 이 목록에서 가장 먼저 보는 정보라 제목·정보 두 줄보다 크게 둔다. */
private val THUMBNAIL_SIZE = 96.dp

/** 정보 줄 앞 아이콘 칸. 아이콘 모양이 달라도 글자가 같은 세로선에서 시작한다. */
private val INFO_ICON_SIZE = 17.dp

/** 사진과 정보 줄 사이. 카드 안쪽 여백보다 살짝 넓게 둬 사진과 글이 붙어 보이지 않게 한다. */
private val THUMBNAIL_TEXT_GAP = 17.dp

/** 사진 모서리. 카드(22dp)만큼 둥글면 사진이 아니라 또 하나의 면처럼 보인다. */
private val THUMBNAIL_SHAPE = RoundedCornerShape(6.dp)

/** 아이콘 공통 색. 나중에 받은 성별 그림의 색으로 맞췄다. */
private val INFO_ICON_COLOR = Color(0xFF615341)
private val INFO_LINE_GAP = 5.dp

/** 카드 정보줄의 진하기. 홈 카드 보조문구와 같은 값이다. */
private const val INFO_LINE_ALPHA = 0.72f

/**
 * 바닥에서 몇 칸 앞에서 다음 쪽을 부를지.
 *
 * 한 화면에 카드가 세 장 남짓 보인다. 세 칸이면 마지막 카드가 화면에 들어올 무렵 요청이 나가서,
 * 손가락이 바닥에 닿기 전에 다음 쪽이 붙는다.
 */
private const val LOAD_MORE_PREFETCH = 3

@Preview(showBackground = true)
@Composable
private fun CommunityPostCardPreview() {
    MeonggoBanjeomTheme {
        CommunityPostCard(
            post =
                LostPostSummary(
                    postId = 1L,
                    type = "SHELTERING",
                    source = "SHELTER",
                    name = null,
                    species = "CAT",
                    breedName = "[고양이] 한국 고양이",
                    sex = "MALE",
                    color = "기타(갈/검/흰)",
                    eventDate = "2026-09-20",
                    listedAt = "2026-09-20T09:00:00",
                    publicLocation = "서울특별시 강북구",
                    thumbnailUrl = null,
                ),
            thumbnailDescription = "보호 중인 동물 사진",
            onClick = {},
        )
    }
}
