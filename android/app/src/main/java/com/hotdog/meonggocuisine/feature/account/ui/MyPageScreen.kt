package com.hotdog.meonggocuisine.feature.account.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotdog.meonggocuisine.core.designsystem.component.ChevronRightIcon
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoSurfaces
import com.hotdog.meonggocuisine.core.designsystem.component.meonggoFloatingShadow
import com.hotdog.meonggocuisine.core.designsystem.theme.MeonggoBanjeomTheme

@Composable
fun MyPageRouteScreen(
    onBackClick: () -> Unit,
    onMyPostsClick: () -> Unit,
    onNicknameEditClick: (String) -> Unit,
    onPasswordChangeClick: () -> Unit,
    onWithdrawClick: () -> Unit,
    onSignedOut: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MyPageViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                MyPageEvent.SignedOut -> onSignedOut()
            }
        }
    }

    MyPageScreen(
        uiState = uiState,
        onBackClick = onBackClick,
        onMyPostsClick = onMyPostsClick,
        onNicknameEditClick = { onNicknameEditClick(uiState.nickname.orEmpty()) },
        onPasswordChangeClick = onPasswordChangeClick,
        onLogoutClick = viewModel::requestLogout,
        onLogoutConfirm = viewModel::confirmLogout,
        onLogoutDismiss = viewModel::dismissLogout,
        onWithdrawClick = onWithdrawClick,
        modifier = modifier,
    )
}

@Composable
fun MyPageScreen(
    uiState: MyPageUiState,
    onBackClick: () -> Unit,
    onMyPostsClick: () -> Unit,
    onNicknameEditClick: () -> Unit,
    onPasswordChangeClick: () -> Unit,
    onLogoutClick: () -> Unit,
    onLogoutConfirm: () -> Unit,
    onLogoutDismiss: () -> Unit,
    onWithdrawClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AccountScaffold(title = "마이페이지", onBackClick = onBackClick, modifier = modifier) {
        ProfileCard(uiState)
        Spacer(Modifier.height(20.dp))
        MenuGroup {
            MenuRow("내 게시물", onMyPostsClick)
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            MenuRow("닉네임 변경", onNicknameEditClick)
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            MenuRow("비밀번호 변경", onPasswordChangeClick)
        }
        Spacer(Modifier.height(16.dp))
        MenuGroup {
            MenuRow(
                title = if (uiState.isLoggingOut) "로그아웃 중…" else "로그아웃",
                onClick = onLogoutClick,
                enabled = !uiState.isLoggingOut,
                showChevron = false,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            MenuRow(
                title = "계정 삭제",
                onClick = onWithdrawClick,
                titleColor = MaterialTheme.colorScheme.error,
                showChevron = false,
            )
        }
    }
    if (uiState.showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = onLogoutDismiss,
            title = { Text("로그아웃할까요?") },
            text = { Text("이 기기에서만 로그아웃됩니다.", style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = onLogoutConfirm) { Text("로그아웃", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = onLogoutDismiss) { Text("취소") } },
        )
    }
}

/**
 * 목록 카드와 같은 면입니다 — 22dp 모서리에 갈색 그림자로만 경계를 잡는다.
 *
 * 면 색은 한 단계 눌러 둔 `surfaceVariant` 로 남겨 아래 메뉴 묶음(흰 면)과 갈린다.
 */
@Composable
private fun ProfileCard(uiState: MyPageUiState) {
    Surface(
        modifier = Modifier.fillMaxWidth().meonggoFloatingShadow(),
        shape = MeonggoSurfaces.cardShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(modifier = Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(56.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                PersonIcon(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp), strokeWidth = 2.2f)
            }
            Column(Modifier.padding(start = 16.dp)) {
                Text(
                    text =
                        when {
                            uiState.nickname != null -> uiState.nickname
                            uiState.isLoading -> "불러오는 중…"
                            else -> "회원"
                        },
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                )
                // 이름 밑에는 평소 아무것도 적지 않는다. "멍고반점 회원" 은 아는 사실을 되풀이할 뿐이었다.
                // 불러오지 못했을 때만 그 자리에 까닭을 적는다.
                uiState.loadError?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * 메뉴 한 묶음입니다. 게시물 목록 카드와 같은 흰 면·같은 모서리를 쓴다.
 *
 * 테두리는 두르지 않는다. 바탕이 따뜻한 흰색이라 순백 면과 그림자만으로 경계가 서고,
 * 선까지 더하면 카드가 목록 화면보다 무거워진다.
 */
@Composable
private fun MenuGroup(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().meonggoFloatingShadow(),
        shape = MeonggoSurfaces.cardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Column { content() }
    }
}

/**
 * 메뉴 한 줄입니다.
 *
 * 제목 한 줄만 둔다. 밑에 붙이던 설명("내가 올린 실종·보호 게시물" 같은 것)은 제목이 이미
 * 말하는 것을 되풀이했고, 줄마다 설명이 있고 없고가 달라 높이가 들쭉날쭉했다.
 *
 * 높이는 [MenuRowHeight] 로 못박는다. 글자 길이나 글꼴 크기가 달라도 묶음 안의 줄들이 같은
 * 간격으로 놓여야 누를 자리를 눈으로 셀 수 있다.
 */
@Composable
private fun MenuRow(
    title: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    showChevron: Boolean = true,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(MenuRowHeight)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            color = titleColor,
            maxLines = 1,
        )
        if (showChevron) {
            ChevronRightIcon(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                iconSize = 18.dp,
                strokeWidth = 2.dp,
            )
        }
    }
}

private val MenuRowHeight = 58.dp

@Preview(showBackground = true, widthDp = 390, heightDp = 780)
@Composable
private fun MyPageScreenPreview() {
    MeonggoBanjeomTheme {
        MyPageScreen(
            uiState = MyPageUiState(nickname = "망고보호자"),
            onBackClick = {},
            onMyPostsClick = {},
            onNicknameEditClick = {},
            onPasswordChangeClick = {},
            onLogoutClick = {},
            onLogoutConfirm = {},
            onLogoutDismiss = {},
            onWithdrawClick = {},
        )
    }
}
