package com.hotdog.meonggocuisine.core.designsystem.component

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.hotdog.meonggocuisine.core.designsystem.theme.MeonggoBanjeomTheme

@Composable
fun MeonggoLoadingIndicator(
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    CircularProgressIndicator(
        modifier = modifier.semantics { this.contentDescription = contentDescription },
    )
}

@Preview(showBackground = true)
@Composable
private fun MeonggoLoadingIndicatorPreview() {
    MeonggoBanjeomTheme {
        MeonggoLoadingIndicator(contentDescription = "목록을 불러오는 중")
    }
}
