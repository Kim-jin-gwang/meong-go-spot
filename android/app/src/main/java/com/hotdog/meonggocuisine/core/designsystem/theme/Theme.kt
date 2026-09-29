package com.hotdog.meonggocuisine.core.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

@Composable
fun MeonggoBanjeomTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MeonggoLightColorScheme,
        typography = MeonggoTypography,
        shapes = MeonggoShapes,
        content = content,
    )
}
