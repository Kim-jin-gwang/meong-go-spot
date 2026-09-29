package com.hotdog.meonggocuisine.core.designsystem.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.hotdog.meonggocuisine.core.designsystem.theme.MeonggoBanjeomTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeonggoTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
            )
        },
        modifier = modifier,
        navigationIcon = navigationIcon,
        actions = actions,
    )
}

@Preview(showBackground = true)
@Composable
private fun MeonggoTopBarPreview() {
    MeonggoBanjeomTheme {
        MeonggoTopBar(title = "잃어버렸어요")
    }
}
