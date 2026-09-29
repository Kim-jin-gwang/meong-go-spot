package com.hotdog.meonggocuisine.core.navigation

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.navigation.toRoute
import com.hotdog.meonggocuisine.feature.account.ui.MyPageRouteScreen
import com.hotdog.meonggocuisine.feature.account.ui.NicknameEditRouteScreen
import com.hotdog.meonggocuisine.feature.account.ui.PasswordChangeRouteScreen
import com.hotdog.meonggocuisine.feature.account.ui.WithdrawRouteScreen
import com.hotdog.meonggocuisine.feature.adoption.ui.AdoptionDetailFavoriteViewModel
import com.hotdog.meonggocuisine.feature.adoption.ui.AdoptionRouteScreen
import com.hotdog.meonggocuisine.feature.auth.ui.LoginRouteScreen
import com.hotdog.meonggocuisine.feature.auth.ui.recovery.AccountRecoveryRouteScreen
import com.hotdog.meonggocuisine.feature.auth.ui.signup.SignUpRouteScreen
import com.hotdog.meonggocuisine.feature.chat.ui.ChatListRouteScreen
import com.hotdog.meonggocuisine.feature.chat.ui.ChatRoomRouteScreen
import com.hotdog.meonggocuisine.feature.chat.ui.PostChatStartRouteScreen
import com.hotdog.meonggocuisine.feature.community.ui.LostPostListRouteScreen
import com.hotdog.meonggocuisine.feature.community.ui.RegionSelectionRouteScreen
import com.hotdog.meonggocuisine.feature.community.ui.ShelteringPostListRouteScreen
import com.hotdog.meonggocuisine.feature.home.ui.HomeRouteScreen
import com.hotdog.meonggocuisine.feature.home.ui.InsightsRouteScreen
import com.hotdog.meonggocuisine.feature.match.ui.MatchCandidatesRouteScreen
import com.hotdog.meonggocuisine.feature.match.ui.MatchComparisonRouteScreen
import com.hotdog.meonggocuisine.feature.match.ui.MatchProgressRouteScreen
import com.hotdog.meonggocuisine.feature.post.ui.PostDetailPopupRouteScreen
import com.hotdog.meonggocuisine.feature.post.ui.PostDetailRouteScreen
import com.hotdog.meonggocuisine.feature.post.ui.close.PostCloseRouteScreen
import com.hotdog.meonggocuisine.feature.post.ui.edit.PostEditRouteScreen
import com.hotdog.meonggocuisine.feature.post.ui.mypost.MyPostListRouteScreen
import com.hotdog.meonggocuisine.feature.report.ui.lost.LostPostCreateRouteScreen
import com.hotdog.meonggocuisine.feature.report.ui.sheltering.ShelteringPostCreateRouteScreen

@Composable
fun AppNavHost(
    navController: NavHostController,
    isAuthenticated: Boolean,
    pendingChatRoomId: Long? = null,
    pendingChatMessageId: Long? = null,
    onPendingChatNavigationConsumed: (Long) -> Unit = {},
    onChatRoomVisibilityChanged: (Long, Boolean) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    var isWaitingForPushLogin by rememberSaveable(pendingChatMessageId) { mutableStateOf(false) }
    LaunchedEffect(pendingChatRoomId, pendingChatMessageId, isAuthenticated) {
        val chatRoomId = pendingChatRoomId ?: return@LaunchedEffect
        val messageId = pendingChatMessageId ?: return@LaunchedEffect
        if (isAuthenticated && !isWaitingForPushLogin) {
            navController.navigate(ChatRoomRoute(chatRoomId)) { launchSingleTop = true }
            onPendingChatNavigationConsumed(messageId)
        } else if (!isAuthenticated) {
            isWaitingForPushLogin = true
            navController.navigate(LoginRoute) { launchSingleTop = true }
        }
    }
    NavHost(
        navController = navController,
        startDestination = HomeRoute,
        modifier = modifier,
    ) {
        composable<HomeRoute> {
            HomeRouteScreen(
                onLostCreateClick = {
                    if (isAuthenticated) {
                        navController.navigate(LostPostCreateRoute)
                    } else {
                        navController.navigateToLogin(PendingDestination.LOST_POST_CREATE)
                    }
                },
                onShelteringCreateClick = {
                    if (isAuthenticated) {
                        navController.navigate(ShelteringPostCreateRoute)
                    } else {
                        navController.navigateToLogin(PendingDestination.SHELTERING_POST_CREATE)
                    }
                },
                onLostListClick = {
                    navController.navigateToTab(LostPostListRoute)
                },
                onShelteringListClick = {
                    navController.navigateToTab(ShelteringPostListRoute)
                },
                onRegionChangeClick = {
                    navController.navigate(RegionSelectionRoute) { launchSingleTop = true }
                },
                // 게시물 읽기는 비로그인도 허용한다 (QA 2026-09-21). 쓰기·채팅만 로그인을 요구한다.
                onPostClick = { postId -> navController.navigate(PostDetailRoute(postId)) },
                // 2026-09-25: 소개팅은 로그인 필수가 됐다. 넘긴 아이를 기억하려면 누군지 알아야 한다.
                onAdoptionClick = {
                    if (isAuthenticated) {
                        navController.navigate(AdoptionRoute)
                    } else {
                        navController.navigateToLogin(PendingDestination.ADOPTION)
                    }
                },
                onInsightsClick = { navController.navigate(InsightsRoute) { launchSingleTop = true } },
                onProfileClick = {
                    if (isAuthenticated) {
                        navController.navigate(MyPageRoute)
                    } else {
                        navController.navigateToLogin(PendingDestination.MY_PAGE)
                    }
                },
                onChatClick = {
                    if (isAuthenticated) {
                        navController.navigate(ChatListRoute)
                    } else {
                        navController.navigateToLogin(PendingDestination.CHAT_LIST)
                    }
                },
            )
        }
        composable<LostPostListRoute> {
            LostPostListRouteScreen(
                onPostClick = { postId -> navController.navigate(PostDetailRoute(postId)) },
                onCreateClick = {
                    if (isAuthenticated) {
                        navController.navigate(LostPostCreateRoute)
                    } else {
                        navController.navigateToLogin(PendingDestination.LOST_POST_CREATE)
                    }
                },
                onRegionChangeClick = {
                    navController.navigate(RegionSelectionRoute)
                },
                onShelteringTabClick = {
                    navController.navigateToTab(ShelteringPostListRoute)
                },
                onProfileClick = {
                    if (isAuthenticated) {
                        navController.navigate(MyPageRoute)
                    } else {
                        navController.navigateToLogin(PendingDestination.MY_PAGE)
                    }
                },
                onChatClick = {
                    if (isAuthenticated) {
                        navController.navigate(ChatListRoute)
                    } else {
                        navController.navigateToLogin(PendingDestination.CHAT_LIST)
                    }
                },
                onHomeTabClick = {
                    navController.navigateToTab(HomeRoute)
                },
            )
        }
        composable<ShelteringPostListRoute> {
            ShelteringPostListRouteScreen(
                onRegionSelectionRequired = {
                    navController.navigate(RegionSelectionRoute) { launchSingleTop = true }
                },
                onRegionChangeClick = {
                    navController.navigate(RegionSelectionRoute) { launchSingleTop = true }
                },
                onPostClick = { postId -> navController.navigate(PostDetailRoute(postId)) },
                onCreateClick = {
                    if (isAuthenticated) {
                        navController.navigate(ShelteringPostCreateRoute)
                    } else {
                        navController.navigateToLogin(PendingDestination.SHELTERING_POST_CREATE)
                    }
                },
                onLostTabClick = {
                    navController.navigateToTab(LostPostListRoute)
                },
                onProfileClick = {
                    if (isAuthenticated) {
                        navController.navigate(MyPageRoute)
                    } else {
                        navController.navigateToLogin(PendingDestination.MY_PAGE)
                    }
                },
                onChatClick = {
                    if (isAuthenticated) {
                        navController.navigate(ChatListRoute)
                    } else {
                        navController.navigateToLogin(PendingDestination.CHAT_LIST)
                    }
                },
                onHomeTabClick = {
                    navController.navigateToTab(HomeRoute)
                },
            )
        }
        composable<AdoptionRoute> {
            AdoptionRouteScreen(
                onBackClick = {
                    if (!navController.popBackStack()) {
                        navController.navigate(HomeRoute)
                    }
                },
                onAnimalClick = { postId, favorited ->
                    navController.navigate(AdoptionAnimalDetailRoute(postId, favorited))
                },
                onProfileClick = {
                    if (isAuthenticated) {
                        navController.navigate(MyPageRoute)
                    } else {
                        navController.navigateToLogin(PendingDestination.MY_PAGE)
                    }
                },
                onChatClick = {
                    if (isAuthenticated) {
                        navController.navigate(ChatListRoute)
                    } else {
                        navController.navigateToLogin(PendingDestination.CHAT_LIST)
                    }
                },
            )
        }
        // 소개팅 카드 위에 뜨는 상세. 화면을 갈아 끼우지 않고 덮기만 해서 닫으면 보던 카드가 그대로 있다.
        // 폭을 플랫폼 기본값(작은 대화상자)에 맡기지 않는다 — 상세는 사진과 줄 여러 개라 좁으면 다 접힌다.
        dialog<AdoptionAnimalDetailRoute>(
            dialogProperties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            // 하트는 이 창만의 상태다. 소개팅 화면은 창이 닫혀 다시 보일 때 히스토리를 새로 받는다.
            val favorites: AdoptionDetailFavoriteViewModel = hiltViewModel()
            val favorited by favorites.favorited.collectAsStateWithLifecycle()
            PostDetailPopupRouteScreen(
                modifier = Modifier.padding(DIALOG_INSET),
                favorited = favorited,
                onFavoriteToggle = favorites::toggle,
                onChatClick = { postId ->
                    if (isAuthenticated) {
                        navController.navigate(PostChatStartRoute(postId))
                    } else {
                        navController.navigateToLogin(PendingDestination.POST_CHAT_START, postId)
                    }
                },
            )
        }
        composable<InsightsRoute> {
            InsightsRouteScreen(
                onBackClick = { navController.popBackStack() },
                onRegionChangeClick = { navController.navigate(RegionSelectionRoute) { launchSingleTop = true } },
                onShelteringListClick = { navController.navigateToTab(ShelteringPostListRoute) },
                onLostListClick = { navController.navigateToTab(LostPostListRoute) },
            )
        }
        composable<RegionSelectionRoute> {
            RegionSelectionRouteScreen(
                onSelectionComplete = {
                    // 지역 선택은 홈·잃어버렸어요·보호하고 있어요 어디서든 바로 위에 열린다. 연 화면으로 그대로 돌아간다.
                    // 예전처럼 특정 화면을 골라 팝하면 잃어버렸어요에서 열었을 때 그 화면을 지나쳐 홈까지 갔다 (2026-09-15 버그).
                    if (!navController.popBackStack()) {
                        navController.navigate(ShelteringPostListRoute) {
                            popUpTo(RegionSelectionRoute) { inclusive = true }
                        }
                    }
                },
                onLostTabClick = {
                    navController.navigateToTab(LostPostListRoute)
                },
                onHomeTabClick = {
                    navController.navigateToTab(HomeRoute)
                },
            )
        }
        composable<LoginRoute> {
            LoginRouteScreen(
                onLoginSuccess = {
                    val pushDestination = pendingChatRoomId?.let(::ChatRoomRoute)
                    val pendingDestination = pushDestination ?: navController.consumePendingDestination()
                    if (!navController.popBackStack()) {
                        navController.navigate(LostPostListRoute)
                    }
                    pendingDestination?.let { navController.navigate(it) }
                    if (pushDestination != null) {
                        pendingChatMessageId?.let(onPendingChatNavigationConsumed)
                        isWaitingForPushLogin = false
                    }
                },
                onSignUpClick = {
                    navController.navigate(SignUpRoute)
                },
                onFindAccountClick = {
                    navController.navigate(AccountRecoveryRoute)
                },
            )
        }
        composable<AccountRecoveryRoute> {
            AccountRecoveryRouteScreen(
                onBackClick = { navController.popBackStack() },
                // 로그인 화면은 보통 바로 아래에 있다. 없으면(딥링크 등) 새로 띄우고 이 화면은 지운다.
                onGoToLogin = {
                    if (!navController.popBackStack(LoginRoute, inclusive = false)) {
                        navController.navigate(LoginRoute) {
                            popUpTo(AccountRecoveryRoute) { inclusive = true }
                        }
                    }
                },
            )
        }
        composable<SignUpRoute> {
            SignUpRouteScreen(
                onSignUpSuccess = {
                    if (!navController.popBackStack(LoginRoute, inclusive = false)) {
                        navController.navigate(LoginRoute) {
                            popUpTo(SignUpRoute) { inclusive = true }
                        }
                    }
                },
                onLoginClick = {
                    if (!navController.popBackStack(LoginRoute, inclusive = false)) {
                        navController.navigate(LoginRoute) {
                            popUpTo(SignUpRoute) { inclusive = true }
                        }
                    }
                },
            )
        }
        composable<MyPageRoute> {
            MyPageRouteScreen(
                onBackClick = {
                    if (!navController.popBackStack()) {
                        navController.navigate(HomeRoute)
                    }
                },
                onMyPostsClick = { navController.navigate(MyPostListRoute) },
                onNicknameEditClick = { nickname -> navController.navigate(NicknameEditRoute(nickname)) },
                onPasswordChangeClick = { navController.navigate(PasswordChangeRoute) },
                onWithdrawClick = { navController.navigate(WithdrawRoute) },
                onSignedOut = { navController.restartAtHome() },
            )
        }
        composable<NicknameEditRoute> {
            NicknameEditRouteScreen(
                onBackClick = { navController.popBackStack() },
                onSaved = { navController.popBackStack() },
                onSessionExpired = { navController.restartAtHome() },
            )
        }
        composable<PasswordChangeRoute> {
            PasswordChangeRouteScreen(
                onBackClick = { navController.popBackStack() },
                onChanged = { navController.popBackStack() },
                onSessionExpired = { navController.restartAtHome() },
            )
        }
        composable<WithdrawRoute> {
            WithdrawRouteScreen(
                onBackClick = { navController.popBackStack() },
                onSignedOut = { navController.restartAtHome() },
            )
        }
        composable<MyPostListRoute> {
            MyPostListRouteScreen(
                onBackClick = {
                    if (!navController.popBackStack()) {
                        navController.navigate(LostPostListRoute)
                    }
                },
                onPostClick = { postId -> navController.navigate(PostDetailRoute(postId)) },
            )
        }
        composable<PostDetailRoute> {
            PostDetailRouteScreen(
                onBackClick = {
                    if (!navController.popBackStack()) {
                        navController.navigate(LostPostListRoute)
                    }
                },
                onEditClick = { postId -> navController.navigate(PostEditRoute(postId)) },
                onCloseClick = { postId -> navController.navigate(PostCloseRoute(postId)) },
                onAnalyzeClick = { postId, animalName ->
                    // 더블탭으로 진행 화면이 두 장 쌓이면 뒤로가기가 실패 화면을 반복해서 보여 준다.
                    navController.navigate(MatchProgressRoute(postId, animalName)) {
                        launchSingleTop = true
                    }
                },
                // 상세는 공개지만 채팅은 로그인 필수 — 로그인만 마치면 채팅 시작으로 이어 준다.
                onChatClick = { postId ->
                    if (isAuthenticated) {
                        navController.navigate(PostChatStartRoute(postId))
                    } else {
                        navController.navigateToLogin(PendingDestination.POST_CHAT_START, postId)
                    }
                },
            )
        }
        composable<PostEditRoute> {
            PostEditRouteScreen(
                onBackClick = { navController.popBackStack() },
                onUpdateSuccess = { postId ->
                    navController.navigate(PostDetailRoute(postId)) {
                        popUpTo<PostEditRoute> { inclusive = true }
                    }
                },
            )
        }
        composable<PostCloseRoute> {
            PostCloseRouteScreen(
                onBackClick = { navController.popBackStack() },
                onCloseSuccess = {
                    navController.navigate(MyPostListRoute) {
                        popUpTo<PostCloseRoute> { inclusive = true }
                    }
                },
            )
        }
        composable<MatchProgressRoute> {
            MatchProgressRouteScreen(
                onBackClick = {
                    if (!navController.popBackStack()) {
                        navController.navigate(LostPostListRoute)
                    }
                },
                onCandidatesReady = { postId, animalName ->
                    navController.navigate(MatchCandidatesRoute(postId, animalName)) {
                        popUpTo<MatchProgressRoute> { inclusive = true }
                    }
                },
                // 수정 화면 뒤로가기가 실패 화면이 아니라 상세로 돌아가도록 진행 화면을 스택에서 뺀다.
                onEditPostClick = { postId ->
                    navController.navigate(PostEditRoute(postId)) {
                        popUpTo<MatchProgressRoute> { inclusive = true }
                    }
                },
            )
        }
        composable<MatchCandidatesRoute> {
            MatchCandidatesRouteScreen(
                onBackClick = {
                    if (!navController.popBackStack()) {
                        navController.navigate(LostPostListRoute)
                    }
                },
                // 후보를 누르면 그 게시물 상세로 간다 (2026-09-15 요청). 비교 화면(MatchComparisonRoute)은
                // 라우트를 남겨 두었지만 여기서는 더 열지 않는다 — 상세에서 보호소 연락·채팅이 다 되기 때문.
                onCandidateClick = { _, candidatePostId ->
                    navController.navigate(PostDetailRoute(candidatePostId))
                },
                onEditPostClick = { postId ->
                    navController.navigate(PostEditRoute(postId)) {
                        popUpTo<MatchCandidatesRoute> { inclusive = true }
                    }
                },
                onAnalysisRestarted = { postId ->
                    navController.navigate(MatchProgressRoute(postId)) {
                        popUpTo<MatchCandidatesRoute> { inclusive = true }
                    }
                },
            )
        }
        composable<MatchComparisonRoute> {
            val context = LocalContext.current
            MatchComparisonRouteScreen(
                onBackClick = {
                    if (!navController.popBackStack()) {
                        navController.navigate(LostPostListRoute)
                    }
                },
                onShelterCallClick = { phone -> context.startDialer(phone) },
                onChatClick = { candidatePostId ->
                    navController.navigate(PostChatStartRoute(candidatePostId))
                },
            )
        }
        composable<PostChatStartRoute> {
            PostChatStartRouteScreen(
                onChatRoomReady = { chatRoomId ->
                    navController.navigate(ChatRoomRoute(chatRoomId)) {
                        popUpTo<PostChatStartRoute> { inclusive = true }
                    }
                },
                onBackClick = {
                    if (!navController.popBackStack()) {
                        navController.navigate(LostPostListRoute)
                    }
                },
            )
        }
        composable<ChatRoomRoute> { backStackEntry ->
            val chatRoomId = backStackEntry.toRoute<ChatRoomRoute>().chatRoomId
            ChatRoomRouteScreen(
                chatRoomId = chatRoomId,
                onBackClick = {
                    if (!navController.popBackStack()) {
                        navController.navigate(ChatListRoute)
                    }
                },
                onPostClick = { postId -> navController.navigate(PostDetailRoute(postId)) },
                onVisibilityChanged = onChatRoomVisibilityChanged,
            )
        }
        composable<ChatListRoute> {
            ChatListRouteScreen(
                onRoomClick = { chatRoomId, otherNickname ->
                    navController.navigate(ChatRoomRoute(chatRoomId, otherNickname))
                },
                onBackClick = {
                    if (!navController.popBackStack()) {
                        navController.navigate(LostPostListRoute)
                    }
                },
            )
        }
        composable<LostPostCreateRoute> {
            LostPostCreateRouteScreen(
                onBackClick = {
                    if (!navController.popBackStack()) {
                        navController.navigate(LostPostListRoute)
                    }
                },
                onCreateSuccess = { postId ->
                    navController.navigate(PostDetailRoute(postId)) {
                        popUpTo(LostPostListRoute)
                    }
                },
            )
        }
        composable<ShelteringPostCreateRoute> {
            ShelteringPostCreateRouteScreen(
                onBackClick = {
                    if (!navController.popBackStack()) {
                        navController.navigate(ShelteringPostListRoute)
                    }
                },
                onCreateSuccess = {
                    navController.navigate(ShelteringPostListRoute) {
                        popUpTo(ShelteringPostListRoute) { inclusive = true }
                    }
                },
            )
        }
    }
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
 * 하단 탭으로 이동합니다.
 *
 * 탭을 오갈 때마다 백스택에 쌓이면 뒤로 가기가 지나온 탭을 모두 되짚으므로 시작 지점 위를 비우고 이동합니다.
 * 목록 화면은 진입할 때 한 번만 불러오므로 상태를 저장·복원하지 않고 매번 새로 만듭니다.
 */
private fun NavHostController.navigateToTab(route: PublicRoute) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id)
        launchSingleTop = true
    }
}

/**
 * 로그아웃·탈퇴·세션 만료 뒤 홈으로 돌아갑니다.
 *
 * 마이페이지 계열 화면은 모두 로그인 필수라 백스택에 남겨 두면 뒤로 가기로 다시 열린다. 시작 지점까지 전부 비우고
 * 홈만 남긴다.
 */
private fun NavHostController.restartAtHome() {
    navigate(HomeRoute) {
        popUpTo(graph.findStartDestination().id) { inclusive = true }
        launchSingleTop = true
    }
}

/** 소개팅 상세 대화상자가 카드 더미를 살짝 남겨 두는 여백. 덮기만 하고 갈아 끼우지 않는다는 게 보여야 한다. */
private val DIALOG_INSET = 12.dp
