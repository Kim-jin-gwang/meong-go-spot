package com.hotdog.meonggocuisine.feature.adoption.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hotdog.meonggocuisine.feature.adoption.data.AdoptionFavoriteResult
import com.hotdog.meonggocuisine.feature.adoption.data.AdoptionListResult
import com.hotdog.meonggocuisine.feature.adoption.data.AdoptionRepository
import com.hotdog.meonggocuisine.feature.adoption.data.AdoptionSwipeListResult
import com.hotdog.meonggocuisine.feature.adoption.data.SexFilter
import com.hotdog.meonggocuisine.feature.adoption.data.SpeciesFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AdoptionViewModel
    @Inject
    constructor(
        private val repository: AdoptionRepository,
    ) : ViewModel() {
        private val mutableUiState = MutableStateFlow(AdoptionUiState())
        val uiState: StateFlow<AdoptionUiState> = mutableUiState.asStateFlow()

        private var loadJob: Job? = null
        private var moreJob: Job? = null
        private var favoriteJob: Job? = null
        private var historyJob: Job? = null
        private var nextCursor: String? = null

        init {
            refresh()
            loadHistory()
        }

        /**
         * 화면이 다시 보일 때마다 부릅니다.
         *
         * 화면 진입 직후에는 생성자와 화면이 연달아 부르므로, 이미 받고 있으면 건너뛴다 —
         * 다시 부르면 먼저 보낸 요청을 취소하고 같은 요청을 한 번 더 보낸다.
         */
        fun refresh() {
            if (loadJob?.isActive == true) return
            load(mutableUiState.value.speciesFilter)
        }

        /**
         * 종과 성별을 한 번에 좁힙니다 (AD1).
         *
         * 시트에서 둘을 함께 골라 적용하므로 한 번만 부른다 — 따로 부르면 목록을 두 번 받는다.
         * 성별을 고르면 원천 성별이 `UNKNOWN` 인 동물은 서버가 뺀다(2026-09-25).
         */
        fun onFilterChange(
            species: SpeciesFilter,
            sex: SexFilter,
        ) {
            val state = mutableUiState.value
            if (species == state.speciesFilter && sex == state.sexFilter) return
            load(species, sex)
        }

        /**
         * 카드를 넘긴다. 좋아요로 넘겼든 그냥 넘겼든 여기를 지난다.
         *
         * 서버에 넘김을 적고(AD5) 결과는 기다리지 않는다 — 사용자가 한 일은 카드를 넘긴 것이고 그건
         * 이미 됐다. 찜을 거는 건 [onFavoriteToggle] 이 이미 했다.
         *
         * 히스토리는 다시 받아 온다. 방금 넘긴 아이가 앞에 와야 하고, 그 순서는 서버가 정한다.
         */
        fun onNext() {
            val state = mutableUiState.value
            val passing = state.current ?: return
            mutableUiState.value = state.copy(currentIndex = state.currentIndex + 1)
            viewModelScope.launch {
                repository.recordSwipe(passing.postId)
                loadHistory()
            }
            loadMoreIfNeeded()
        }

        /**
         * 넘긴 아이 목록을 받아 옵니다 (AD6).
         *
         * 첫 쪽만 받는다 — 히스토리는 "아까 그 아이" 를 찾는 자리라 최근 몇 개면 된다. 더 거슬러
         * 올라가는 흐름이 필요해지면 그때 커서를 잇는다.
         */
        fun refreshHistory() = loadHistory()

        private fun loadHistory() {
            if (historyJob?.isActive == true) return
            historyJob =
                viewModelScope.launch {
                    when (val result = repository.getSwipes()) {
                        is AdoptionSwipeListResult.Success ->
                            mutableUiState.value = mutableUiState.value.copy(passed = result.records)

                        // 히스토리를 못 받아도 카드는 넘길 수 있다. 화면을 오류로 덮지 않는다.
                        AdoptionSwipeListResult.CursorExpired -> Unit
                        is AdoptionSwipeListResult.Failure -> Unit
                    }
                }
        }

        fun dismissFavoriteError() {
            mutableUiState.value = mutableUiState.value.copy(favoriteErrorMessage = null)
        }

        /**
         * 현재 카드의 관심 표시를 켜고 끕니다.
         *
         * 화면을 먼저 바꾸고 서버에 보낸다. 실패하면 되돌린다 — 누를 때마다 기다리면 연타에서
         * 버튼이 굳는다. 후보 자격을 잃은 동물(`ADOPTION-001`)은 목록에서 내린다.
         */
        fun onFavoriteToggle() {
            if (favoriteJob?.isActive == true) return
            val state = mutableUiState.value
            val animal = state.current ?: return
            val target = !animal.favorited
            setFavorited(animal.postId, target)
            favoriteJob =
                viewModelScope.launch {
                    val result =
                        if (target) repository.addFavorite(animal.postId) else repository.removeFavorite(animal.postId)
                    when (result) {
                        AdoptionFavoriteResult.Success -> Unit
                        AdoptionFavoriteResult.NoLongerAvailable -> removeAnimal(animal.postId)
                        AdoptionFavoriteResult.Unauthorized -> {
                            setFavorited(animal.postId, !target)
                            mutableUiState.value =
                                mutableUiState.value.copy(favoriteErrorMessage = SESSION_EXPIRED)
                        }

                        is AdoptionFavoriteResult.Failure -> {
                            setFavorited(animal.postId, !target)
                            mutableUiState.value =
                                mutableUiState.value.copy(favoriteErrorMessage = result.message)
                        }
                    }
                }
        }

        /**
         * 히스토리에서 찜을 해제합니다 (AD4).
         *
         * 넘김 기록은 건드리지 않는다 — 해제는 "안 데려갈래" 지 "다시 보여 줘" 가 아니라서 그 동물이
         * 카드로 돌아오면 안 된다 (api-spec AD4, 2026-09-25).
         *
         * 지금 카드의 하트와 달리 되돌리지 않는다. 히스토리는 여러 마리를 훑으며 정리하는 자리라,
         * 실패한 한 건 때문에 화면이 되돌아가면 무엇이 빠졌는지 더 헷갈린다. 다음 조회가 맞춰 준다.
         */
        fun onUnfavorite(postId: Long) {
            val state = mutableUiState.value
            mutableUiState.value =
                state.copy(
                    animals = state.animals.map { if (it.postId == postId) it.copy(favorited = false) else it },
                    passed =
                        state.passed.map {
                            if (it.animal.postId == postId) it.copy(favorited = false) else it
                        },
                )
            viewModelScope.launch { repository.removeFavorite(postId) }
        }

        private fun setFavorited(
            postId: Long,
            favorited: Boolean,
        ) {
            val state = mutableUiState.value
            mutableUiState.value =
                state.copy(
                    animals = state.animals.map { if (it.postId == postId) it.copy(favorited = favorited) else it },
                    // 히스토리의 하트도 같은 값이다 — 서버가 아는 찜 상태 하나뿐이라 둘이 갈릴 일이 없다.
                    passed =
                        state.passed.map {
                            if (it.animal.postId == postId) it.copy(favorited = favorited) else it
                        },
                    favoriteErrorMessage = null,
                )
        }

        /** 자격을 잃은 동물은 카드에서 내린다. 지금 보고 있던 자리에 다음 동물이 올라온다. */
        private fun removeAnimal(postId: Long) {
            val state = mutableUiState.value
            val remaining = state.animals.filterNot { it.postId == postId }
            mutableUiState.value =
                state.copy(
                    animals = remaining,
                    currentIndex = state.currentIndex.coerceAtMost(remaining.size),
                    // 히스토리에서는 지우지 않는다 — 자격을 잃었을 뿐 넘긴 건 사실이다 (AD6).
                    passed =
                        state.passed.map {
                            if (it.animal.postId == postId) it.copy(available = false) else it
                        },
                )
        }

        /**
         * 남은 카드가 [PREFETCH_THRESHOLD]장 이하면 다음 페이지를 미리 받습니다.
         *
         * 마지막 카드에서 받기 시작하면 사용자가 빈 화면을 본다 (계약 §5).
         */
        private fun loadMoreIfNeeded() {
            if (moreJob?.isActive == true || !needsMoreCards()) return
            moreJob = viewModelScope.launch { fillDeck() }
        }

        /**
         * 카드가 다시 [PREFETCH_THRESHOLD]장을 넘을 때까지 페이지를 이어 받습니다.
         *
         * 한 쪽만 받고 끝내지 않는 건 이미 넘긴 아이를 걸러 내기 때문이다 — 한 페이지가 통째로
         * 걸러지면 붙일 카드가 하나도 없는데, 거기서 멈추면 뒤에 아직 남았는데도 다 본 것처럼 보인다.
         */
        private suspend fun fillDeck() {
            while (needsMoreCards()) {
                val state = mutableUiState.value
                val cursor = nextCursor ?: return
                when (val result = repository.getWaitingAnimals(NATIONWIDE, state.speciesFilter, state.sexFilter, cursor)) {
                    is AdoptionListResult.Success -> {
                        nextCursor = result.nextCursor
                        val fresh = result.animals
                        mutableUiState.value =
                            mutableUiState.value.let {
                                it.copy(animals = it.animals + fresh, hasNext = result.hasNext)
                            }
                    }

                    // 커서가 만료되면 이어 붙이지 않고 첫 페이지부터 다시 부른다. 그 뒤에 또 이어
                    // 받으러 가면 만료된 커서를 다시 집어 같은 자리를 맴돌므로 여기서 끊는다.
                    AdoptionListResult.CursorExpired -> {
                        load(state.speciesFilter, state.sexFilter, refillAfterLoad = false)
                        return
                    }

                    is AdoptionListResult.Failure -> return
                }
            }
        }

        private fun needsMoreCards(): Boolean {
            val state = mutableUiState.value
            if (!state.hasNext || nextCursor == null) return false
            return state.animals.size - state.currentIndex <= PREFETCH_THRESHOLD
        }

        private fun load(
            filter: SpeciesFilter,
            sex: SexFilter = mutableUiState.value.sexFilter,
            refillAfterLoad: Boolean = true,
        ) {
            loadJob?.cancel()
            moreJob?.cancel()
            nextCursor = null
            mutableUiState.value =
                mutableUiState.value.copy(
                    isLoading = true,
                    speciesFilter = filter,
                    sexFilter = sex,
                    animals = emptyList(),
                    currentIndex = 0,
                    errorMessage = null,
                    hasNext = false,
                    favoriteErrorMessage = null,
                )
            loadJob =
                viewModelScope.launch {
                    when (val result = repository.getWaitingAnimals(NATIONWIDE, filter, sex)) {
                        is AdoptionListResult.Success -> {
                            nextCursor = result.nextCursor
                            mutableUiState.value =
                                mutableUiState.value.copy(
                                    isLoading = false,
                                    animals = result.animals,
                                    hasNext = result.hasNext,
                                )
                            if (refillAfterLoad) loadMoreIfNeeded()
                        }

                        // 첫 페이지에는 커서를 보내지 않으므로 여기서 만료가 나오면 서버 오류다.
                        AdoptionListResult.CursorExpired ->
                            mutableUiState.value =
                                mutableUiState.value.copy(isLoading = false, errorMessage = LIST_FAILURE)

                        is AdoptionListResult.Failure ->
                            mutableUiState.value =
                                mutableUiState.value.copy(isLoading = false, errorMessage = result.message)
                    }
                }
        }

        private companion object {
            /**
             * 이 화면은 전국만 본다.
             *
             * 지역을 좁혀 두면 보여 줄 아이가 금세 떨어진다 — 카드를 넘기며 보는 화면이라 몇 장 만에
             * 바닥이 나면 화면 자체가 성립하지 않는다. 게다가 입양은 어차피 보호소에 직접 가야 해서,
             * 멀어도 마음이 가면 가는 사람이 있다. 지역으로 미리 지우지 않는다(2026-09-24).
             *
             * 서버는 regionCode 를 안 보내면 전국으로 읽는다 (P1·D3).
             */
            val NATIONWIDE: String? = null

            const val PREFETCH_THRESHOLD = 3
            const val LIST_FAILURE = "기다리는 아이를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요."
            const val SESSION_EXPIRED = "로그인이 필요합니다. 다시 로그인해 주세요."
        }
    }
