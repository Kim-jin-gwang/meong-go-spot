package com.hotdog.meonggocuisine.core.designsystem.theme

import androidx.compose.material3.lightColorScheme
import com.hotdog.meonggocuisine.core.designsystem.token.Brown300
import com.hotdog.meonggocuisine.core.designsystem.token.Brown50
import com.hotdog.meonggocuisine.core.designsystem.token.Brown600
import com.hotdog.meonggocuisine.core.designsystem.token.Brown700
import com.hotdog.meonggocuisine.core.designsystem.token.Brown800
import com.hotdog.meonggocuisine.core.designsystem.token.Neutral0
import com.hotdog.meonggocuisine.core.designsystem.token.Neutral100
import com.hotdog.meonggocuisine.core.designsystem.token.Neutral200
import com.hotdog.meonggocuisine.core.designsystem.token.Neutral25
import com.hotdog.meonggocuisine.core.designsystem.token.Neutral50
import com.hotdog.meonggocuisine.core.designsystem.token.Neutral500
import com.hotdog.meonggocuisine.core.designsystem.token.Neutral900
import com.hotdog.meonggocuisine.core.designsystem.token.Red100
import com.hotdog.meonggocuisine.core.designsystem.token.Red600
import com.hotdog.meonggocuisine.core.designsystem.token.Red700
import com.hotdog.meonggocuisine.core.designsystem.token.Sand300

internal val MeonggoLightColorScheme =
    lightColorScheme(
        primary = Brown600,
        onPrimary = Neutral0,
        primaryContainer = Brown50,
        onPrimaryContainer = Brown800,
        secondary = Brown700,
        onSecondary = Neutral0,
        secondaryContainer = Sand300,
        onSecondaryContainer = Brown800,
        tertiary = Brown300,
        onTertiary = Neutral900,
        // 화면 배경은 따뜻한 쪽으로 한 방울 기운 흰색이다. 한 단계 눌러 둘 영역은 surfaceVariant를 쓴다.
        // 순백이 필요한 면(떠 있는 하단 탭·헤더 버튼)은 surfaceContainerLowest 를 쓴다.
        background = Neutral25,
        onBackground = Neutral900,
        surface = Neutral25,
        onSurface = Neutral900,
        surfaceVariant = Brown50,
        onSurfaceVariant = Neutral500,
        surfaceContainerLowest = Neutral0,
        surfaceContainerLow = Neutral50,
        surfaceContainer = Neutral100,
        // 대화상자(AlertDialog)·메뉴·바텀시트는 High·Highest 를 쓴다. 비워 두면 Material 기본값(연보라 계열)이 나와
        // "로그아웃할까요?" 창이 앱 색과 어긋났다(2026-09-23 QA). 따뜻한 흰색 계열로 맞춘다.
        surfaceContainerHigh = Neutral50,
        surfaceContainerHighest = Neutral100,
        outline = Neutral200,
        outlineVariant = Brown300,
        error = Red600,
        onError = Neutral0,
        errorContainer = Red100,
        onErrorContainer = Red700,
    )
