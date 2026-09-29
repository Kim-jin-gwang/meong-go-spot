package com.hotdog.meonggocuisine.feature.adoption.data

/**
 * 히스토리에 남는 한 줄 — 언제 넘겼고 지금 찜인가.
 *
 * [favorited] 는 서버가 아는 현재 찜 상태다(AD6). 예전에는 "이번에 오른쪽으로 넘겼는지" 를 따로
 * 들고 있었는데, 넘김 기록이 계정에 남게 되면서 그 구분이 사라졌다 — 기기를 바꿔 들어와도 같은
 * 히스토리를 봐야 하고, 그때 "이번에" 라는 말은 뜻이 없다(2026-09-25).
 *
 * [available] 이 거짓이면 후보 자격을 잃은 동물이다. 그래도 칸은 지킨다 — 히스토리에서 아이가
 * 말없이 사라지면 사용자가 자기 기록을 믿지 못한다.
 */
data class AdoptionSwipeRecord(
    val animal: AdoptionAnimal,
    val swipedAt: String,
    val favorited: Boolean,
    val available: Boolean,
)
