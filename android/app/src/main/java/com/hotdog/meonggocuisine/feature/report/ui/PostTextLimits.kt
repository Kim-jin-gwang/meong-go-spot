package com.hotdog.meonggocuisine.feature.report.ui

/**
 * 게시물 글자 칸의 최대 길이 — 서버 P3 검증(`PostInputPolicy.text`, docs/api-spec.md)과 같은 값.
 *
 * 서버는 넘치면 400 COMMON-001 만 돌려주고 어느 칸이 얼마나 넘쳤는지 말해 주지 않는다(QA 2026-09-21: 1만 자를 넣으면
 * "요청 값이 올바르지 않습니다"). 화면에서 애초에 넘치지 않게 잘라 넣고 남은 글자 수를 보여 준다.
 *
 * 서버가 코드 포인트로 세므로 여기서도 코드 포인트로 잘라 이모지가 반쪽 나지 않게 한다.
 */
object PostTextLimits {
    const val NAME = 50
    const val BREED_NAME = 100
    const val COLOR = 100
    const val PLACE = 200
    const val FEATURE_TEXT = 2000
    const val PROTECTION_STATUS = 100

    /** 보호 등록은 "현재 보호 상태: …" 한 줄을 특징 앞에 붙여 보낸다 — 합쳐서 [FEATURE_TEXT] 를 넘지 않게 특징 몫을 남긴다. */
    const val SHELTERING_FEATURE_TEXT = FEATURE_TEXT - PROTECTION_STATUS - 20

    fun String.codePointLength(): Int = codePointCount(0, length)

    fun String.limitTo(max: Int): String = if (codePointLength() <= max) this else substring(0, offsetByCodePoints(0, max))
}
