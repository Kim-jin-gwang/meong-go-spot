package com.hotdog.meonggocuisine.feature.report.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File

/**
 * 카메라 앱으로 사진을 한 장 찍어 앱 캐시에 받습니다.
 *
 * 갤러리 선택(PickVisualMedia)과 달리 찍은 사진은 카메라 앱이 쓸 자리가 먼저 있어야 합니다. 캐시 폴더
 * `camera/` 에 파일 자리를 만들고 FileProvider 로 content URI 를 내주면, 그 뒤로는 갤러리에서 고른
 * 사진과 같은 content URI 라 검사(`PhotoInputInspector`)와 업로드가 그대로 동작합니다.
 *
 * 카메라 앱이 뜬 동안 우리 액티비티가 죽을 수 있어(메모리 부족) 기다리는 URI 는 rememberSaveable 로
 * 살립니다. 사용자가 취소하면 빈 파일을 지웁니다.
 *
 * CAMERA 권한은 선언하지 않습니다. 사진은 카메라 앱이 찍고 우리는 결과 파일만 받으므로 필요 없고,
 * 선언하면 오히려 권한 요청 절차가 생깁니다.
 */
@Composable
fun rememberCameraCapture(
    onCaptured: (Uri) -> Unit,
    onUnavailable: () -> Unit,
): () -> Unit {
    val context = LocalContext.current
    var pendingUri by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
            val uri = pendingUri?.let(Uri::parse)
            pendingUri = null
            if (uri != null) {
                if (saved) onCaptured(uri) else CameraCaptureFiles.discard(context, uri)
            }
        }
    return {
        // 연타로 카메라가 뜨기 전에 다시 눌리면 앞서 만든 빈 자리를 먼저 치운다.
        pendingUri?.let { CameraCaptureFiles.discard(context, Uri.parse(it)) }
        pendingUri = null
        // 저장 공간이 없으면 자리 만들기부터 실패한다(IOException). 앨범을 권한다.
        val uri = CameraCaptureFiles.newPhotoUri(context)
        if (uri == null) {
            onUnavailable()
        } else {
            pendingUri = uri.toString()
            // 카메라 앱이 없는 기기(일부 태블릿·에뮬레이터 설정)는 ActivityNotFound, 카메라 앱 호출을 정책으로 막은
            // 기기(일부 제조사 업무용 프로필)는 SecurityException 을 던진다. 둘 다 빈 자리를 치우고 앨범을 권한다.
            val unavailable = {
                pendingUri = null
                CameraCaptureFiles.discard(context, uri)
                onUnavailable()
            }
            try {
                launcher.launch(uri)
            } catch (_: ActivityNotFoundException) {
                unavailable()
            } catch (_: SecurityException) {
                unavailable()
            }
        }
    }
}

/** 촬영 결과가 놓이는 캐시 파일. 매니페스트의 FileProvider `camera/` 경로와 짝이다. */
object CameraCaptureFiles {
    private const val DIRECTORY = "camera"

    /** 캐시에 빈 자리를 만들어 content URI 로 돌려준다. 저장 공간 부족 등으로 못 만들면 null. */
    fun newPhotoUri(context: Context): Uri? =
        runCatching {
            val directory = File(context.cacheDir, DIRECTORY).apply { mkdirs() }
            // 시각 기반 이름은 연타로 겹칠 수 있다. createTempFile 이 고유한 이름을 보장한다.
            val file = File.createTempFile("capture-", ".jpg", directory)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()

    fun discard(
        context: Context,
        uri: Uri,
    ) {
        runCatching { context.contentResolver.delete(uri, null, null) }
    }
}
