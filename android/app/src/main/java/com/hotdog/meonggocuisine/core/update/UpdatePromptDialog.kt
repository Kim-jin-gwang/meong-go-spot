package com.hotdog.meonggocuisine.core.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.DialogProperties
import com.hotdog.meonggocuisine.core.designsystem.component.MeonggoButton

/**
 * 업데이트 안내. 강제(FORCE)는 바깥을 눌러도, 뒤로 가기를 눌러도 닫히지 않고 "업데이트" 만 있다.
 * 권고(RECOMMEND)는 "나중에" 로 닫을 수 있다.
 */
@Composable
fun UpdatePromptDialog(
    state: UpdateGateState,
    onDismissRecommendation: () -> Unit,
) {
    // 서버가 링크를 비워 보내면 열 곳이 없다 — 안내도 띄우지 않는다.
    val storeUrl = state.storeUrl
    if (state.prompt == UpdatePrompt.NONE || storeUrl.isNullOrBlank()) return
    val context = LocalContext.current
    val force = state.prompt == UpdatePrompt.FORCE
    AlertDialog(
        onDismissRequest = { if (!force) onDismissRecommendation() },
        properties = DialogProperties(dismissOnBackPress = !force, dismissOnClickOutside = !force),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = if (force) "업데이트가 필요해요" else "새 버전이 있어요",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
        },
        text = {
            Text(
                text =
                    if (force) {
                        "지금 쓰는 버전은 더 지원하지 않아요. 원스토어에서 최신 버전으로 업데이트한 뒤 이용해 주세요."
                    } else {
                        "원스토어에 새 버전이 올라왔어요. 지금 업데이트하면 새 기능과 수정 사항을 바로 쓸 수 있어요."
                    },
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            MeonggoButton(text = "업데이트", onClick = { openStore(context, storeUrl) })
        },
        dismissButton =
            if (force) {
                null
            } else {
                { TextButton(onClick = onDismissRecommendation) { Text("나중에") } }
            },
    )
}

/** 원스토어 앱이 있으면 상품 페이지 딥링크로, 없으면 웹 링크로 연다. */
private fun openStore(
    context: Context,
    storeUrl: String,
) {
    val deepLink = oneStoreDeepLink(storeUrl)
    if (deepLink != null) {
        if (launch(context, deepLink)) return
    }
    // 브라우저마저 없는 기기면 안내는 그대로 남고 사용자가 다른 경로로 받는다.
    launch(context, storeUrl)
}

/** 링크를 연다. 받아 줄 앱이 없거나(ActivityNotFound) 기기 정책이 막으면(Security) false. */
private fun launch(
    context: Context,
    url: String,
): Boolean =
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
