package com.hotdog.meonggocuisine.core.text

/**
 * 공공 데이터의 품종명에서 축종 접두사를 뗍니다.
 *
 * 수집한 품종명은 `[고양이] 한국 고양이` 처럼 축종을 앞에 달고 온다. 화면에는 축종이 제목이나
 * 정보 줄에 이미 드러나므로 접두사를 그대로 두면 같은 말이 두세 번 나오고 제목이 잘린다.
 * 사용자가 직접 쓴 품종명에는 접두사가 없어 그대로 통과한다.
 *
 * 목록과 상세가 같이 쓴다 — 한쪽만 떼던 때는 목록에서 `한국 고양이` 로 본 게시물이 상세에서는
 * `[고양이] 한국 고양이` 로 보였다.
 */
fun stripSpeciesPrefix(breedName: String): String = if (breedName.startsWith("[")) breedName.substringAfter(']').trim() else breedName
