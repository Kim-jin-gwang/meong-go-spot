package com.hotdog.meonggocuisine.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import com.hotdog.meonggocuisine.core.designsystem.token.MeonggoRadius

internal val MeonggoShapes =
    Shapes(
        small = RoundedCornerShape(MeonggoRadius.small),
        medium = RoundedCornerShape(MeonggoRadius.medium),
        large = RoundedCornerShape(MeonggoRadius.large),
    )
