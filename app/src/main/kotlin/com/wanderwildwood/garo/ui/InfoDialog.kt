package com.wanderwildwood.garo.ui

import android.content.Intent
import android.media.ExifInterface
import android.provider.DocumentsContract
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.garo.R
import com.wanderwildwood.garo.media.Picture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * What is known about a picture, and nothing filled in where it is not.
 *
 * The date says which date it is: when the camera took it, or — for a screenshot, a download, a
 * picture a messaging app saved — only when the file was written, which can be years after.
 * Where the picture came from another app and the phone will say neither, the row is left out.
 */
@Composable
fun InfoDialog(picture: Picture, onDismiss: () -> Unit) {
    val context = LocalContext.current

    // The camera's make and model sit inside the file, not in the index, so it is opened to read them.
    val camera by produceState<String?>(null, picture.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(picture.uri)?.use { stream ->
                    val exif = ExifInterface(stream)
                    val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim().orEmpty()
                    val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim().orEmpty()
                    // Most cameras repeat the maker at the start of the model; say it once.
                    when {
                        model.startsWith(make, ignoreCase = true) -> model
                        else -> "$make $model".trim()
                    }.ifEmpty { null }
                }
            }.getOrNull()
        }
    }

    fun date(ms: Long): String =
        DateFormat.getLongDateFormat(context).format(Date(ms)) + ", " + DateFormat.getTimeFormat(context).format(Date(ms))

    EInkDialog(onDismiss = onDismiss) {
        TextMMD(text = picture.name, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(10.dp))

        when {
            picture.taken != null -> Fact(stringResource(R.string.info_taken), date(picture.taken))
            picture.modified > 0 -> Fact(stringResource(R.string.info_saved), date(picture.modified))
        }

        val dimensions = if (picture.width > 0 && picture.height > 0) {
            stringResource(R.string.info_dimensions, picture.width, picture.height)
        } else {
            null
        }
        val size = if (picture.size > 0) Formatter.formatShortFileSize(context, picture.size) else null
        listOfNotNull(dimensions, size).takeIf { it.isNotEmpty() }?.let {
            Fact(stringResource(R.string.info_size), it.joinToString("  ·  "))
        }

        picture.path?.let { Fact(stringResource(R.string.info_folder), it.trimEnd('/')) }
        camera?.let { Fact(stringResource(R.string.info_camera), it) }

        // The folder in Files, when there is a file manager to open it in.
        val showFolder = remember(picture) { folderIntent(picture)?.takeIf { it.resolveActivity(context.packageManager) != null } }
        if (showFolder != null) {
            Spacer(Modifier.height(14.dp))
            OutlinedButtonMMD(
                onClick = {
                    runCatching { context.startActivity(showFolder) }
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                TextMMD(text = stringResource(R.string.info_show_folder), style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(if (showFolder != null) 10.dp else 14.dp))
        OutlinedButtonMMD(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            TextMMD(text = stringResource(R.string.info_close), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    TextMMD(text = label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
    TextMMD(text = value, style = MaterialTheme.typography.bodyMedium)
}

/** "Open this folder", in the form Files (tana) and Android's own file app both answer. */
private fun folderIntent(picture: Picture): Intent? {
    val id = picture.folderDocumentId ?: return null
    val uri = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_DOCUMENTS, id)
    return Intent(Intent.ACTION_VIEW).setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
}

private const val EXTERNAL_STORAGE_DOCUMENTS = "com.android.externalstorage.documents"
