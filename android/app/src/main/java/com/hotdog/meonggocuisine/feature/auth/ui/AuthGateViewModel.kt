package com.hotdog.meonggocuisine.feature.auth.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hotdog.meonggocuisine.feature.auth.data.AuthRepository
import com.hotdog.meonggocuisine.feature.auth.data.AuthSessionManager
import com.hotdog.meonggocuisine.feature.auth.data.TokenRefreshResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 앱을 켠 직후의 로그인 상태입니다.
 *
 * 저장된 갱신 토큰으로 세션을 되살리는 동안에는 로그인 여부를 아직 알 수 없다. 이 구간을
 * [RESTORING]으로 따로 두지 않으면 화면이 비로그인으로 단정해, 갱신 토큰이 멀쩡한 사용자를
 * 로그인 화면으로 보낸다.
 */
enum class AuthGateState {
    RESTORING,
    AUTHENTICATED,
    UNAUTHENTICATED,
}

/**
 * 앱 시작 시 저장된 갱신 토큰으로 로그인 세션을 되살립니다.
 *
 * access token은 메모리에만 있어 프로세스가 끝나면 사라지고 Keystore의 갱신 토큰만 남는다. 그
 * 토큰으로 A3을 한 번 호출해 세션을 복구하지 않으면 사용자는 앱을 켤 때마다 다시 로그인해야 한다.
 *
 * 화면 회전으로 복구를 다시 실행하지 않도록 [ViewModel]에 둔다.
 */
@HiltViewModel
class AuthGateViewModel
    @Inject
    constructor(
        private val authRepository: AuthRepository,
        private val authSessionManager: AuthSessionManager,
    ) : ViewModel() {
        private val isRestoring = MutableStateFlow(true)

        val state: StateFlow<AuthGateState> =
            combine(isRestoring, authSessionManager.session) { restoring, session ->
                when {
                    restoring -> AuthGateState.RESTORING
                    session != null -> AuthGateState.AUTHENTICATED
                    else -> AuthGateState.UNAUTHENTICATED
                }
            }.stateIn(viewModelScope, SharingStarted.Eagerly, AuthGateState.RESTORING)

        init {
            viewModelScope.launch {
                // 저장된 토큰이 없으면 되살릴 세션도 없다. A3을 부르면 실패 처리로 저장소를 지우며
                // 디스크에 쓰므로, 한 번도 로그인하지 않은 사용자에게는 호출하지 않는다.
                val restored =
                    authSessionManager.readRefreshToken() != null &&
                        authRepository.refreshTokens() == TokenRefreshResult.Refreshed
                isRestoring.value = false

                // A3 응답에는 회원 정보가 없어 되살린 세션은 자신이 누구인지 모른다. 채팅의 내
                // 메시지 판별처럼 회원 번호를 쓰는 화면이 있으므로 A6로 채운다. 화면 이동 판단에는
                // 필요 없으니 대기를 끝낸 뒤에 부른다.
                if (restored) authRepository.loadCurrentMember()
            }
        }
    }
