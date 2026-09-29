package com.hotdog.meonggocuisine.core.designsystem.token

import androidx.compose.ui.graphics.Color

// 브랜드 원본 색상이다.
//
// 대비는 WCAG 본문 기준 4.5:1을 넘도록 골랐다. 화면에서 이 값을 직접 쓰지 않고
// MeonggoLightColorScheme이 연결한 Material 역할(primary, surfaceVariant 등)을 사용한다.

/** 강조·주요 버튼. 흰 글자 대비 6.17:1 */
internal val Brown600 = Color(0xFF7A5C30)

/**
 * 홈 메인 카드의 갈색 면.
 *
 * 채도를 낮춘 파스텔 톤이다. 진한 갈색으로 채우면 카드 하나만 무거워져 세 갈래가 나란한
 * 선택지로 읽히지 않는다. `Neutral900` 글자 대비 10.8:1.
 */
internal val Brown100 = Color(0xFFE4D6C2)

/** 눌림·보조 강조. 흰 글자 대비 7.57:1 */
internal val Brown700 = Color(0xFF6B4F28)

/** 연한 면 위의 강조 글자. `Brown50` 대비 7.75:1 */
internal val Brown800 = Color(0xFF5F4724)

/** 테두리·비활성 강조 */
internal val Brown300 = Color(0xFFC4A06A)

/** 보조 면. 카드 안에서 한 단계 눌러 둘 영역 */
internal val Brown50 = Color(0xFFF7F1E8)

/** 사진 자리·배지처럼 따뜻하게 채울 영역 */
internal val Sand300 = Color(0xFFF5C176)

/** 홈 메인 카드의 노란 면. 파스텔 톤. `Neutral900` 글자 대비 13.6:1 */
internal val Cream100 = Color(0xFFFBEFCF)

internal val Neutral0 = Color(0xFFFFFFFF)

/**
 * 화면 바탕. 순백이 아니라 따뜻한 쪽으로 한 방울 섞은 흰색이다.
 *
 * 화면 대부분이 흰 면이라 순백(`Neutral0`)으로 두면 눈이 부시다. 갈색 브랜드와 같은 방향으로
 * 아주 조금만 기울여 편안한 흰색으로 만든다. 흰 글자가 아닌 본문 글자만 올리므로 대비는
 * `Neutral900` 기준 14.8:1 로 넉넉하다.
 */
internal val Neutral25 = Color(0xFFFCFBF7)

/** 바탕과 구분되는 가장 옅은 면 */
internal val Neutral50 = Color(0xFFFAF8F5)

/**
 * 바탕을 한 단계 눌러 둔 면.
 *
 * `Brown50` 보다 노란기를 덜어 낸 따뜻한 회베이지다. 지역 표시처럼 화면 맨 위에 넓게 깔리는
 * 면은 노란 크림으로 채우면 아래 카드와 색이 다투므로 중립 쪽으로 둔다.
 * `Neutral900` 글자 대비 12.7:1.
 */
internal val Neutral100 = Color(0xFFEDE8DF)

/** 입력창·카드 테두리 */
internal val Neutral200 = Color(0xFFE3DBD0)

/** 보조 글자. 흰 배경 6.05:1, `Brown50` 위 5.39:1 */
internal val Neutral500 = Color(0xFF6B6157)

/** 본문 글자. 흰 배경 15.32:1 */
internal val Neutral900 = Color(0xFF2B241B)

internal val Red100 = Color(0xFFFBEAE8)

/** 종료·오류. 흰 글자 대비 5.66:1 */
internal val Red600 = Color(0xFFB3403A)

internal val Red700 = Color(0xFF8C2E29)
