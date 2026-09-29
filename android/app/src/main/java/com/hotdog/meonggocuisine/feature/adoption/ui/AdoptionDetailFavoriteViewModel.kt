package com.hotdog.meonggocuisine.feature.adoption.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hotdog.meonggocuisine.feature.adoption.data.AdoptionFavoriteResult
import com.hotdog.meonggocuisine.feature.adoption.data.AdoptionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 소개팅 상세 팝업의 하트를 맡습니다.
 *
 * 넘긴 아이를 다시 찜하려면 카드가 아닌 곳에서도 하트를 눌러야 한다. 팝업은 이미 히스토리에서
 * 여는 창이라 거기에 붙인다 — 카드로 돌려보낼 방법이 없으니 이 창이 유일한 자리다(2026-09-25).
 *
 * 처음 상태는 route 가 들고 온다. 팝업이 따로 조회하면 창이 뜨고 나서 하트가 뒤늦게 바뀐다.
 */
@HiltViewModel
class AdoptionDetailFavoriteViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val repository: AdoptionRepository,
    ) : ViewModel() {
        private val postId: Long = requireNotNull(savedStateHandle["postId"])
        private val mutableFavorited = MutableStateFlow(savedStateHandle["favorited"] ?: false)
        val favorited: StateFlow<Boolean> = mutableFavorited.asStateFlow()

        private var job: Job? = null

        /**
         * 화면을 먼저 바꾸고 서버에 보냅니다. 실패하면 되돌린다.
         *
         * 자격을 잃어 더는 찜할 수 없는 동물(`ADOPTION-001`)도 되돌린다 — 여기서 카드를 내릴 수는
         * 없고, 하트만 켜 두면 담긴 것처럼 보인다.
         */
        fun toggle() {
            if (job?.isActive == true) return
            val target = !mutableFavorited.value
            mutableFavorited.value = target
            job =
                viewModelScope.launch {
                    val result =
                        if (target) repository.addFavorite(postId) else repository.removeFavorite(postId)
                    if (result != AdoptionFavoriteResult.Success) mutableFavorited.value = !target
                }
        }
    }
