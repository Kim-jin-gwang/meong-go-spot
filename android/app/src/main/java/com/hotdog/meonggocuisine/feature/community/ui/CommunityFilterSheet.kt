package com.hotdog.meonggocuisine.feature.community.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoChoiceRow
import com.hotdog.meonggocuisine.core.designsystem.theme.MeonggoBanjeomTheme
import com.hotdog.meonggocuisine.feature.community.data.PostListFilter
import com.hotdog.meonggocuisine.feature.community.data.PostListSort
import com.hotdog.meonggocuisine.feature.community.data.SexFilter
import com.hotdog.meonggocuisine.feature.community.data.SpeciesFilter

/**
 * 목록의 조회 조건을 고르는 시트입니다.
 *
 * 조건을 목록 위에 줄로 늘어놓지 않고 시트로 모은 이유는 두 가지다. 정렬만 화면에 있던 때는
 * 그 한 줄이 목록 위에서 자리만 차지했고, 조건을 하나 더 두려니 정렬과 모양이 어긋났다. 여기로
 * 모으면 그 줄이 통째로 없어져 카드가 한 장 더 보이고, 조건이 늘어도 화면은 그대로다.
 *
 * 고른 값은 [onApply] 를 누를 때까지 이 시트 안에만 있다. 고를 때마다 목록을 다시 부르면
 * 성별을 바꾸는 사이에 목록이 두 번 갈아엎인다.
 *
 * 사건 날짜(조회기간)는 아직 없다 — 이유는 [PostListFilter] 에 적어 뒀다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommunityFilterSheet(
    applied: PostListFilter,
    onDismiss: () -> Unit,
    onApply: (PostListFilter) -> Unit,
) {
    var draft by remember(applied) { mutableStateOf(applied) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = SHEET_RADIUS, topEnd = SHEET_RADIUS),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = SHEET_GUTTER, end = SHEET_GUTTER, bottom = SHEET_GUTTER),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "필터",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 19.sp),
                    fontWeight = FontWeight.Bold,
                )
                // 되돌릴 길이 없으면 조건을 걸어 본 사람이 하나씩 되짚어 풀어야 한다.
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    onClick = { draft = PostListFilter() },
                ) {
                    Text(
                        "초기화",
                        Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            FilterSection(
                title = "종",
                options = SpeciesFilter.entries,
                selected = draft.species,
                label = ::speciesFilterLabel,
                onSelect = { draft = draft.copy(species = it) },
            )
            FilterSection(
                title = "성별",
                options = SexFilter.entries,
                selected = draft.sex,
                label = ::sexFilterLabel,
                onSelect = { draft = draft.copy(sex = it) },
            )
            FilterSection(
                title = "정렬",
                options = PostListSort.entries,
                selected = draft.sort,
                label = ::sortLabel,
                onSelect = { draft = draft.copy(sort = it) },
            )

            Spacer(Modifier.height(26.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primary,
                onClick = { onApply(draft) },
            ) {
                Box(Modifier.padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "적용하기",
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.5.sp),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/**
 * 조건 한 묶음입니다. 선택지를 같은 너비로 나눠 어느 것이 더 중요해 보이지 않게 한다.
 */
@Composable
private fun <T> FilterSection(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Spacer(Modifier.height(22.dp))
    Text(
        title,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(10.dp))
    // 선택지 모양은 등록 화면의 종·성별 고르는 줄과 같다 — 같은 뜻의 버튼이 화면마다 다르게
    // 생기지 않도록 디자인 시스템에 두고 둘이 같이 쓴다.
    MeonggoChoiceRow(
        options = options,
        selected = selected,
        label = label,
        onSelect = onSelect,
    )
}

internal fun speciesFilterLabel(filter: SpeciesFilter): String =
    when (filter) {
        SpeciesFilter.ALL -> "전체"
        SpeciesFilter.DOG -> "강아지"
        SpeciesFilter.CAT -> "고양이"
    }

internal fun sexFilterLabel(filter: SexFilter): String =
    when (filter) {
        SexFilter.ALL -> "전체"
        SexFilter.MALE -> "수컷"
        SexFilter.FEMALE -> "암컷"
    }

internal fun sortLabel(sort: PostListSort): String =
    when (sort) {
        PostListSort.LATEST -> "최신순"
        PostListSort.OLDEST -> "오래된순"
    }

private val SHEET_RADIUS = 28.dp
private val SHEET_GUTTER = 20.dp

@Preview(showBackground = true, widthDp = 411)
@Composable
private fun FilterOptionsPreview() {
    MeonggoBanjeomTheme {
        Column(Modifier.padding(20.dp)) {
            FilterSection(
                title = "종",
                options = SpeciesFilter.entries,
                selected = SpeciesFilter.DOG,
                label = ::speciesFilterLabel,
                onSelect = {},
            )
            FilterSection(
                title = "성별",
                options = SexFilter.entries,
                selected = SexFilter.ALL,
                label = ::sexFilterLabel,
                onSelect = {},
            )
        }
    }
}
