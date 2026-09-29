package com.hotdog.meonggocuisine.feature.push.data

import com.hotdog.meonggocuisine.feature.auth.data.AuthSessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

interface PushDeviceLifecycle {
    fun onSessionAvailable()

    fun onNewToken(token: String)

    suspend fun unregisterBeforeLogout()

    fun onSessionCleared()
}

/** 현재 로그인 세션과 이 앱 설치의 FCM 등록을 맞춥니다. 토큰 값은 저장하거나 로그로 남기지 않습니다. */
@Singleton
class PushDeviceLifecycleManager
    @Inject
    constructor(
        private val api: PushDeviceApi,
        private val installationIdStore: InstallationIdStore,
        private val tokenProvider: PushTokenProvider,
        private val authSessionManager: AuthSessionManager,
        @PushDeviceScope private val scope: CoroutineScope,
    ) : PushDeviceLifecycle {
        private var registrationJob: Job? = null

        override fun onSessionAvailable() {
            scheduleRegistration(token = null)
        }

        override fun onNewToken(token: String) {
            if (token.isBlank()) return
            scheduleRegistration(token)
        }

        /** N2를 먼저 최선 노력으로 호출한다. 성공 여부와 무관하게 호출자는 A4 로그아웃을 계속한다. */
        override suspend fun unregisterBeforeLogout() {
            registrationJob?.cancel()
            if (authSessionManager.session.value == null) return
            runCatching { api.unregister(installationIdStore.getOrCreate()) }
        }

        override fun onSessionCleared() {
            registrationJob?.cancel()
            registrationJob = null
        }

        private fun scheduleRegistration(token: String?) {
            registrationJob?.cancel()
            registrationJob =
                scope.launch {
                    if (authSessionManager.session.value == null) return@launch
                    val registrationToken = token ?: runCatching { tokenProvider.currentToken() }.getOrNull()
                    if (registrationToken.isNullOrBlank()) return@launch
                    registerWithRetry(registrationToken)
                }
        }

        private suspend fun registerWithRetry(token: String) {
            RETRY_DELAYS_MILLIS.forEachIndexed { attempt, retryDelay ->
                if (authSessionManager.session.value == null) return
                if (attempt > 0) delay(retryDelay)
                if (register(token)) return
            }
        }

        private suspend fun register(token: String): Boolean =
            try {
                val response =
                    api.register(
                        installationId = installationIdStore.getOrCreate(),
                        request = PushDeviceRegistrationRequest(token = token),
                    )
                response.isSuccessful || response.code() in 400..499
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: IOException) {
                false
            } catch (_: Exception) {
                false
            }

        private companion object {
            val RETRY_DELAYS_MILLIS = longArrayOf(0L, 5_000L, 30_000L, 120_000L, 600_000L)
        }
    }
