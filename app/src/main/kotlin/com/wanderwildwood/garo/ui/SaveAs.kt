package com.wanderwildwood.garo.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

/**
 * Android's own "save as" screen for one file, whose type and suggested name are given when it is
 * opened. A picture is written only where the reader chooses here; this app never writes a file
 * into a shared folder on its own.
 */
class SaveAs : ActivityResultContract<SaveAs.Ask, Uri?>() {
    class Ask(val name: String, val mime: String)

    override fun createIntent(context: Context, input: Ask): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.mime)
            .putExtra(Intent.EXTRA_TITLE, input.name)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == android.app.Activity.RESULT_OK) intent?.data else null

    companion object {
        /** "Beach.jpg" becomes "Beach edited.jpg"; a PNG stays a PNG, anything else is saved as JPEG. */
        fun editedName(original: String?, png: Boolean): String {
            val stem = original.orEmpty().substringBeforeLast('.').ifBlank { "Picture" }
            return "$stem edited.${if (png) "png" else "jpg"}"
        }

        /** The ending a received picture's type is saved under; unknown types are kept as JPEG. */
        fun extensionFor(mime: String): String = when (mime) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/bmp" -> "bmp"
            "image/heif", "image/heic" -> "heic"
            else -> "jpg"
        }

        /** A name that cannot hold a path separator. */
        fun safeName(name: String): String = name.replace('/', '_').ifBlank { "picture" }
    }
}
