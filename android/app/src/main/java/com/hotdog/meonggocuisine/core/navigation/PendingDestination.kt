package com.hotdog.meonggocuisine.core.navigation

import androidx.navigation.NavController

/**
 * 로그인이 필요해 미뤄 둔 목적지입니다.
 *
 * `savedStateHandle`은 프로세스 종료를 견디도록 Bundle에 담을 수 있는 값만 저장한다. 그래서
 * 경로 객체를 그대로 넣지 않고 종류 이름과 식별자만 저장한 뒤 경로를 다시 만든다.
 */
internal enum class PendingDestination {
    POST_CHAT_START,
    LOST_POST_CREATE,
    SHELTERING_POST_CREATE,
    MY_PAGE,
    CHAT_LIST,
    ADOPTION,
    ;

    fun toRoute(postId: Long?): AppRoute? =
        when (this) {
            POST_CHAT_START -> postId?.let(::PostChatStartRoute)
            LOST_POST_CREATE -> LostPostCreateRoute
            SHELTERING_POST_CREATE -> ShelteringPostCreateRoute
            MY_PAGE -> MyPageRoute
            CHAT_LIST -> ChatListRoute
            ADOPTION -> AdoptionRoute
        }
}

/**
 * 로그인 화면으로 보내면서 원래 가려던 목적지를 현재 화면에 적어 둡니다.
 *
 * 각 화면이 로그인 여부를 따로 판단하지 않도록 판단과 기록을 이 한 곳에 모은다.
 */
internal fun NavController.navigateToLogin(
    destination: PendingDestination,
    postId: Long? = null,
) {
    currentBackStackEntry?.savedStateHandle?.let { handle ->
        handle[PENDING_DESTINATION_KEY] = destination.name
        if (postId == null) {
            handle.remove<Long>(PENDING_POST_ID_KEY)
        } else {
            handle[PENDING_POST_ID_KEY] = postId
        }
    }
    navigate(LoginRoute)
}

/**
 * 미뤄 둔 목적지를 꺼내고 기록을 지웁니다.
 *
 * 로그인 화면을 띄운 화면에 기록이 남아 있으므로 이전 back stack 항목에서 읽는다. 한 번 쓰고
 * 지워서 나중에 같은 화면으로 돌아왔을 때 다시 이동하지 않게 한다.
 */
internal fun NavController.consumePendingDestination(): AppRoute? {
    val handle = previousBackStackEntry?.savedStateHandle ?: return null
    val destination =
        handle.remove<String>(PENDING_DESTINATION_KEY)?.let { name ->
            PendingDestination.entries.firstOrNull { it.name == name }
        }
    val postId = handle.remove<Long>(PENDING_POST_ID_KEY)
    return destination?.toRoute(postId)
}

private const val PENDING_DESTINATION_KEY = "pendingDestination"
private const val PENDING_POST_ID_KEY = "pendingPostId"
