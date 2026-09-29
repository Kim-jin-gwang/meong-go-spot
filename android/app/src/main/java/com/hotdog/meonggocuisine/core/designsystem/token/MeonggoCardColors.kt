package com.hotdog.meonggocuisine.core.designsystem.token

import androidx.compose.ui.graphics.Color

/**
 * 홈 메인 카드 세 장의 면 색입니다.
 *
 * 카드마다 고유한 브랜드 면이라 Material 역할(`primary`, `surfaceVariant` 등) 하나로 묶이지
 * 않는다. 화면에 색상 코드를 직접 적지 않기 위해 이름 있는 토큰으로 둔다
 * (`core/designsystem/README.md` "변경 위치").
 *
 * 값은 확정된 홈 화면 디자인에서 가져왔다. 색을 바꿀 때는 [Palette]의 원본만 고친다.
 */
object MeonggoCardColors {
    /** 잃어버렸어요 — 파스텔 브라운. */
    val lostSurface: Color = Brown100

    /**
     * 보호하고 있어요 — 순백.
     *
     * 바탕(`Neutral25`)이 따뜻한 흰색이라 한 단계 눌러 둔 면(`Neutral50`)을 쓰면 카드가 바탕에
     * 묻힌다. 반대로 바탕보다 밝게 올려야 카드로 읽힌다.
     */
    val shelteringSurface: Color = Neutral0

    /** 새 가족을 기다려요 — 파스텔 옐로. */
    val adoptionSurface: Color = Cream100

    /**
     * 떠 있는 면의 그림자 색입니다.
     *
     * 안드로이드 기본 그림자는 검정이라, 따뜻한 흰 바탕 위에서 카드 아래가 회색 띠로 보였다.
     * 브랜드 갈색을 옅게 깔면 같은 두께로도 회색기 없이 경계만 남는다.
     *
     * 알파는 시스템이 그림자 세기로 쓴다. API 28 아래에서는 색 지정을 무시하고 검정으로 그린다.
     */
    val floatingShadow: Color = Brown800.copy(alpha = 0.34f)

    /**
     * 홈 메인 카드 세 장의 그림자 색입니다. [floatingShadow] 보다 옅다.
     *
     * 안드로이드 기본값인 검정으로 두었더니 세 장이 서로 다르게 보였다. 갈색 카드는 면이 이미
     * 그림자만큼 어두워 그림자가 카드에 먹히는데, 흰 카드와 크림 카드는 밝은 면 바로 옆에 검은
     * 띠가 생겨 카드만 동동 떠 보였다. 셋을 갈색 카드 쪽으로 맞춘다 — 경계만 짚어 주면 된다
     * (2026-09-24).
     */
    val homeCardShadow: Color = Brown800.copy(alpha = 0.16f)

    /**
     * 홈 메인 카드 세 장의 테두리입니다.
     *
     * 갈색·크림 카드는 면 색만으로 바탕과 갈려서 이 선이 하는 일이 없다. 흰 카드만을 위한
     * 선이다 — 자기 색이 없어 [homeCardShadow] 를 옅게 한 뒤로 경계가 사라졌다. 한 장에만
     * 다른 부품을 쓰지 않으려고 셋에 같이 두른다.
     */
    val homeCardBorder: Color = Brown100.copy(alpha = 0.38f)

    /**
     * 면 색이 이미 바탕과 갈리는 카드의 테두리입니다. [homeCardBorder] 보다 옅다.
     *
     * 크림 카드가 쓴다. 같은 진하기로 두르면 노란기가 옅어서 갈색 선이 면보다 먼저 보인다 —
     * 여기서는 모서리만 정리해 주면 된다.
     */
    val homeCardBorderSoft: Color = Brown100.copy(alpha = 0.22f)
}
