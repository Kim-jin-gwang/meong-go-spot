package com.hotdog.meonggocuisine.core.media

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Uses the system photo picker when it is available and falls back to the document picker when it
 * is unavailable or disabled on the device.
 *
 * AndroidX selects [android.provider.MediaStore.ACTION_PICK_IMAGES] based on the API level. Some
 * emulators can report a supported API level while their photo picker module is disabled, leaving
 * no activity to handle that intent.
 */
class PickMultipleVisualMediaWithFallback(
    maxItems: Int,
) : ActivityResultContracts.PickMultipleVisualMedia(maxItems) {
    override fun createIntent(
        context: Context,
        input: PickVisualMediaRequest,
    ): Intent {
        val photoPickerIntent = super.createIntent(context, input)
        if (
            context.packageManager.resolveActivity(
                photoPickerIntent,
                PackageManager.MATCH_DEFAULT_ONLY,
            ) != null
        ) {
            return photoPickerIntent
        }

        return Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(photoPickerIntent.type ?: "*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
    }
}
