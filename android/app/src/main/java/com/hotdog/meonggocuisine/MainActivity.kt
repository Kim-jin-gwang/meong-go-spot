package com.hotdog.meonggocuisine

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.hotdog.meonggocuisine.core.designsystem.theme.MeonggoBanjeomTheme
import com.hotdog.meonggocuisine.core.navigation.AppNavHost
import com.hotdog.meonggocuisine.core.update.UpdateGateViewModel
import com.hotdog.meonggocuisine.core.update.UpdatePromptDialog
import com.hotdog.meonggocuisine.feature.auth.ui.AuthGateState
import com.hotdog.meonggocuisine.feature.auth.ui.AuthGateViewModel
import com.hotdog.meonggocuisine.feature.push.chat.ChatPushContract
import com.hotdog.meonggocuisine.feature.push.chat.ChatPushNavigationViewModel
import com.hotdog.meonggocuisine.feature.push.chat.ChatPushVisibilityTracker
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    // 시작 화면을 붙잡을지 판단해야 해서 Compose 바깥에서도 읽을 수 있게 액티비티에 둔다.
    // setContent 안의 hiltViewModel 과 같은 저장소를 쓰므로 인스턴스도 같다.
    private val authGateViewModel: AuthGateViewModel by viewModels()
    private val chatPushNavigationViewModel: ChatPushNavigationViewModel by viewModels()

    @Inject
    lateinit var chatPushVisibilityTracker: ChatPushVisibilityTracker

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 앱을 열면 그림 로고가 있는 시작 화면이 먼저 뜨고, 저장된 로그인을 되살린 뒤 홈으로 넘어간다.
        // 먼저 걷히면 로그인 여부를 비로그인으로 단정한 화면이 한 프레임 보이고, 그 사이에 누른
        // 인증 필요 화면이 로그인 화면으로 빠진다.
        //
        // installSplashScreen 은 super.onCreate 보다 먼저 불러야 한다.
        installSplashScreen().setKeepOnScreenCondition {
            authGateViewModel.state.value == AuthGateState.RESTORING
        }
        // targetSdk 36 부터는 edge-to-edge 를 끌 수 없다 (Android 16 동작 변경).
        // 화면마다 인셋을 다루는 대신 NavHost 바깥에서 safeDrawing 을 한 번 적용한다 —
        // windowInsetsPadding 이 인셋을 소비하므로 하위 Scaffold 가 중복으로 패딩하지 않는다.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        acceptChatPushIntent(intent)
        setContent {
            MeonggoBanjeomTheme {
                val navController = rememberNavController()
                val authState by authGateViewModel.state.collectAsStateWithLifecycle()
                val pendingChatNavigation by
                    chatPushNavigationViewModel.pendingNavigation.collectAsStateWithLifecycle()
                // 서버가 정한 최신·최소 지원 버전과 비교해 업데이트를 권고하거나 강제한다 (core/update).
                val updateGateViewModel: UpdateGateViewModel = hiltViewModel()
                val updateState by updateGateViewModel.state.collectAsStateWithLifecycle()
                LaunchedEffect(authState) {
                    if (authState == AuthGateState.AUTHENTICATED) requestNotificationPermissionOnce()
                }
                Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    // 되살리는 동안에는 시작 화면이 아직 덮고 있으므로 여기서는 아무것도 그리지 않는다.
                    if (authState != AuthGateState.RESTORING) {
                        AppNavHost(
                            navController = navController,
                            isAuthenticated = authState == AuthGateState.AUTHENTICATED,
                            pendingChatRoomId = pendingChatNavigation?.chatRoomId,
                            pendingChatMessageId = pendingChatNavigation?.messageId,
                            onPendingChatNavigationConsumed = chatPushNavigationViewModel::consume,
                            onChatRoomVisibilityChanged = chatPushVisibilityTracker::setChatRoomVisible,
                        )
                    }
                    UpdatePromptDialog(
                        state = updateState,
                        onDismissRecommendation = updateGateViewModel::dismissRecommendation,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptChatPushIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        chatPushVisibilityTracker.setAppForeground(true)
    }

    override fun onStop() {
        chatPushVisibilityTracker.setAppForeground(false)
        super.onStop()
    }

    private fun acceptChatPushIntent(intent: Intent?) {
        chatPushNavigationViewModel.accept(
            action = intent?.action,
            chatRoomId = intent?.getLongExtra(ChatPushContract.EXTRA_CHAT_ROOM_ID, -1L) ?: -1L,
            messageId = intent?.getLongExtra(ChatPushContract.EXTRA_MESSAGE_ID, -1L) ?: -1L,
        )
    }

    private fun requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val preferences = getSharedPreferences(NOTIFICATION_PERMISSION_PREFERENCES, MODE_PRIVATE)
        if (preferences.getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)) return
        preferences.edit().putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, true).apply()
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private companion object {
        const val NOTIFICATION_PERMISSION_PREFERENCES = "meonggocuisine_notification_permission"
        const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "requested"
    }
}
