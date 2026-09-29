package com.hotdog.meonggocuisine.feature.post.ui.close

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoLoadingIndicator
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoScreenHeader
import com.hotdog.meonggocuisine.core.designsystem.theme.MeonggoBanjeomTheme
import com.hotdog.meonggocuisine.feature.post.data.PostCloseReason

@Composable
fun PostCloseRouteScreen(
    onBackClick: () -> Unit,
    onCloseSuccess: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PostCloseViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is PostCloseEvent.Closed -> onCloseSuccess(event.postId)
            }
        }
    }

    PostCloseScreen(
        uiState = uiState,
        onBackClick = onBackClick,
        onReasonSelect = viewModel::onReasonSelect,
        onCloseRequest = viewModel::onCloseRequest,
        onConfirm = viewModel::onConfirm,
        onConfirmDismiss = viewModel::onConfirmDismiss,
        onRefreshClick = viewModel::refresh,
        onRetryLoadClick = viewModel::retryLoad,
        modifier = modifier,
    )
}

@Composable
fun PostCloseScreen(
    uiState: PostCloseUiState,
    onBackClick: () -> Unit,
    onReasonSelect: (PostCloseReason) -> Unit,
    onCloseRequest: () -> Unit,
    onConfirm: () -> Unit,
    onConfirmDismiss: () -> Unit,
    onRefreshClick: () -> Unit,
    onRetryLoadClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 22.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            CloseHeader(onBackClick)
            when {
                uiState.isLoading -> LoadingBody()
                uiState.loadErrorMessage != null ->
                    LoadFailedBody(message = uiState.loadErrorMessage, onRetryLoadClick = onRetryLoadClick)
                else ->
                    CloseBody(
                        uiState = uiState,
                        onReasonSelect = onReasonSelect,
                        onCloseRequest = onCloseRequest,
                        onRefreshClick = onRefreshClick,
                    )
            }
        }
    }

    if (uiState.isConfirming) {
        CloseConfirmDialog(
            uiState = uiState,
            onConfirm = onConfirm,
            onDismiss = onConfirmDismiss,
        )
    }
}

@Composable
private fun CloseHeader(onBackClick: () -> Unit) {
    // 다른 화면과 같은 머리줄이다 — 예전에는 동그란 단추 안에 글리프를 넣어 직접 그렸다.
    MeonggoScreenHeader(title = "게시물 종료", onBackClick = onBackClick)
}

@Composable
private fun LoadingBody() {
    Box(
        modifier = Modifier.fillMaxWidth().height(240.dp),
        contentAlignment = Alignment.Center,
    ) {
        MeonggoLoadingIndicator(contentDescription = "게시물 정보를 불러오는 중")
    }
}

@Composable
private fun LoadFailedBody(
    message: String,
    onRetryLoadClick: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = onRetryLoadClick, shape = RoundedCornerShape(12.dp)) {
            Text("다시 불러오기")
        }
    }
}

@Composable
private fun CloseBody(
    uiState: PostCloseUiState,
    onReasonSelect: (PostCloseReason) -> Unit,
    onCloseRequest: () -> Unit,
    onRefreshClick: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        uiState.versionConflictMessage?.let { message ->
            VersionConflictNotice(message = message, onRefreshClick = onRefreshClick)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "${uiState.displayName} 게시물을 종료할까요?",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 16.sp),
            )
            Text(
                text = "종료하면 공개 목록과 새 유사 후보에서 바로 제외됩니다. 다시 활성 상태로 되돌릴 수 없습니다.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "종료 사유",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp),
            )
            uiState.reasons.forEach { reason ->
                ReasonRow(
                    reason = reason,
                    isSelected = uiState.selectedReason == reason,
                    onSelect = onReasonSelect,
                )
            }
        }
        KeptAfterCloseNotice()
        uiState.requestError?.let {
            Text(
                text = it,
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Button(
            onClick = onCloseRequest,
            enabled = uiState.canSubmit,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        ) {
            if (uiState.isSubmitting) {
                MeonggoLoadingIndicator(
                    contentDescription = "게시물 종료 요청 중",
                    modifier = Modifier.size(20.dp),
                )
            } else {
                Text("게시물 종료하기", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ReasonRow(
    reason: PostCloseReason,
    isSelected: Boolean,
    onSelect: (PostCloseReason) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onSelect(reason) },
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surface,
        border =
            BorderStroke(
                width = 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = isSelected, onClick = { onSelect(reason) })
            Spacer(Modifier.width(4.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = reason.label,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                )
                Text(
                    text = reason.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                )
            }
        }
    }
}

/** 종료가 무엇을 남기는지 미리 알려 사용자가 되돌릴 수 없는 선택을 판단하게 한다. */
@Composable
private fun KeptAfterCloseNotice() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "종료 후에도 남는 것",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp),
            )
            Text(
                text = "내 게시물 관리에서 종료 이력을 확인할 수 있고, 기존 채팅은 읽기 전용으로 유지됩니다.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun VersionConflictNotice(
    message: String,
    onRefreshClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(
                onClick = onRefreshClick,
                modifier = Modifier.height(38.dp),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("최신 내용 다시 불러오기", fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun CloseConfirmDialog(
    uiState: PostCloseUiState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("정말 종료할까요?") },
        text = {
            Text(
                text =
                    "${uiState.displayName} 게시물을 '${uiState.selectedReason?.label}' 사유로 종료합니다. " +
                        "종료하면 공개 목록과 새 유사 후보에서 즉시 제외되고 다시 되돌릴 수 없습니다.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("종료", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        },
    )
}

@Preview(showBackground = true, widthDp = 390, heightDp = 780, backgroundColor = 0xFFFFFFFF)
@Composable
private fun PostCloseScreenPreview() {
    MeonggoBanjeomTheme {
        PostCloseScreen(
            uiState =
                PostCloseUiState(
                    isLoading = false,
                    postId = 1002L,
                    version = 6L,
                    animalName = "콩이",
                    selectedReason = PostCloseReason.RETURNED,
                ),
            onBackClick = {},
            onReasonSelect = {},
            onCloseRequest = {},
            onConfirm = {},
            onConfirmDismiss = {},
            onRefreshClick = {},
            onRetryLoadClick = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 780, backgroundColor = 0xFFFFFFFF)
@Composable
private fun PostCloseVersionConflictPreview() {
    MeonggoBanjeomTheme {
        PostCloseScreen(
            uiState =
                PostCloseUiState(
                    isLoading = false,
                    postId = 1002L,
                    version = 6L,
                    isSheltering = true,
                    selectedReason = PostCloseReason.TRANSFERRED,
                    versionConflictMessage = "게시물이 변경되었습니다. 최신 내용을 다시 확인해 주세요.",
                ),
            onBackClick = {},
            onReasonSelect = {},
            onCloseRequest = {},
            onConfirm = {},
            onConfirmDismiss = {},
            onRefreshClick = {},
            onRetryLoadClick = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 420, backgroundColor = 0xFFFFFFFF)
@Composable
private fun PostCloseLoadFailedPreview() {
    MeonggoBanjeomTheme {
        PostCloseScreen(
            uiState = PostCloseUiState(isLoading = false, loadErrorMessage = "이미 종료된 게시물입니다."),
            onBackClick = {},
            onReasonSelect = {},
            onCloseRequest = {},
            onConfirm = {},
            onConfirmDismiss = {},
            onRefreshClick = {},
            onRetryLoadClick = {},
        )
    }
}
