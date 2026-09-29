package com.hotdog.meonggocuisine.core.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hotdog.meonggocuisine.BuildConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class UpdateGateState(
    val prompt: UpdatePrompt = UpdatePrompt.NONE,
    val storeUrl: String? = null,
    val latestVersionCode: Int = 0,
)

/**
 * 앱이 켜질 때 한 번 서버의 버전 안내(V1)를 읽어 강제/권고 안내를 정한다.
 *
 * 액티비티가 살아 있는 동안 한 번이다(회전·재생성에는 다시 묻지 않음). 조회 실패는 안내 없음과 같다 — 업데이트 확인이 앱 진입을 막으면 안 된다.
 */
@HiltViewModel
class UpdateGateViewModel internal constructor(
    private val repository: AppVersionRepository,
    private val promptStore: UpdatePromptStore,
    private val currentVersionCode: Int,
    private val today: () -> LocalDate,
) : ViewModel() {
    @Inject
    constructor(
        repository: AppVersionRepository,
        promptStore: UpdatePromptStore,
    ) : this(repository, promptStore, BuildConfig.VERSION_CODE, { LocalDate.now() })

    private val mutableState = MutableStateFlow(UpdateGateState())
    val state: StateFlow<UpdateGateState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            val version = repository.fetch() ?: return@launch
            val dismissedToday = promptStore.dismissedOn() == today()
            mutableState.value =
                UpdateGateState(
                    prompt =
                        decideUpdatePrompt(
                            currentVersionCode = currentVersionCode,
                            latestVersionCode = version.latestVersionCode,
                            minSupportedVersionCode = version.minSupportedVersionCode,
                            recommendationDismissedToday = dismissedToday,
                        ),
                    storeUrl = version.storeUrl,
                    latestVersionCode = version.latestVersionCode,
                )
        }
    }

    /** 권고 안내의 "나중에". 강제 안내는 닫을 수 없으므로 여기 오지 않는다. */
    fun dismissRecommendation() {
        if (mutableState.value.prompt != UpdatePrompt.RECOMMEND) return
        promptStore.markDismissed(today())
        mutableState.value = mutableState.value.copy(prompt = UpdatePrompt.NONE)
    }
}
