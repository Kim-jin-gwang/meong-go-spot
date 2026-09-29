package com.hotdog.meonggocuisine.feature.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoLoadingIndicator
import com.hotdog.meonggocuisine.core.designsystem.theme.MeonggoBanjeomTheme
import com.hotdog.meonggocuisine.core.designsystem.token.MeonggoSpacing

@Composable
fun PostChatStartRouteScreen(
    onChatRoomReady: (Long) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PostChatStartViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(uiState.chatRoomId) {
        uiState.chatRoomId?.let(onChatRoomReady)
    }
    PostChatStartScreen(
        uiState = uiState,
        onRetryClick = viewModel::retry,
        onBackClick = onBackClick,
        modifier = modifier,
    )
}

@Composable
fun PostChatStartScreen(
    uiState: PostChatStartUiState,
    onRetryClick: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize().padding(MeonggoSpacing.extraLarge),
        contentAlignment = Alignment.Center,
    ) {
        when {
            uiState.errorMessage != null ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = uiState.errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(MeonggoSpacing.medium))
                    Column(verticalArrangement = Arrangement.spacedBy(MeonggoSpacing.small)) {
                        if (uiState.canRetry) {
                            Button(onRetryClick) { Text("다시 시도") }
                        }
                        OutlinedButton(onBackClick) { Text("돌아가기") }
                    }
                }
            else -> MeonggoLoadingIndicator("채팅방을 준비하는 중")
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PostChatStartScreenPreview() {
    MeonggoBanjeomTheme {
        PostChatStartScreen(
            uiState = PostChatStartUiState(errorMessage = "본인 게시물에는 채팅을 시작할 수 없습니다.", canRetry = false),
            onRetryClick = {},
            onBackClick = {},
        )
    }
}
