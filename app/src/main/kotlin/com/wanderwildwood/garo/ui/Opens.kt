package com.wanderwildwood.garo.ui

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.garo.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Which app opens a picture handed over by another app, as far as Android will say. */
sealed interface Opener {
    data object Us : Opener

    /** No app is set to always: Android asks, and this is the question to put to it. */
    data class Asks(val ask: Intent?) : Opener

    /**
     * Another app was chosen with Always. Undoing it is on that app's "Open by default" page,
     * which Mudita's settings leave out but Android still opens when asked.
     */
    data class Other(val label: String, val pkg: String) : Opener
}

/**
 * One VIEW per kind of picture and video actually on the phone, each holding the newest of
 * that kind — Always is remembered per kind, so a phone of JPEGs and PNGs is asked twice.
 */
private fun asks(context: Context): List<Intent> {
    val intents = mutableListOf<Intent>()
    for (collection in listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)) {
        val seen = mutableSetOf<String>()
        runCatching {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.MIME_TYPE),
                null,
                null,
                MediaStore.MediaColumns.DATE_MODIFIED + " DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val type = c.getString(1) ?: continue
                    if (!seen.add(type)) continue
                    intents += view(ContentUris.withAppendedId(collection, c.getLong(0)), type)
                }
            }
        }
    }
    return intents
}

/** Android's "Open by default" page for [pkg], where "Clear default preferences" is. */
private fun openByDefault(pkg: String) =
    Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, Uri.parse("package:$pkg"))

private fun view(uri: Uri, type: String) =
    Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

/**
 * Who answers each of [intents] when it is sent without a chooser. Android hands back its own
 * "which app?" page when none is set; that page is not among the apps that can open it, which
 * is how it is told apart on any version, whatever the page's package is called.
 */
private fun opener(context: Context, intents: List<Intent>): Opener {
    val pm = context.packageManager
    var asking: Intent? = null
    var anyAsks = false
    // Nothing on the phone yet, or no permission to look: the camera's kind is still worth
    // asking about, though there is no picture to put the question with.
    val real = intents.isNotEmpty()
    val probes = intents.ifEmpty { listOf(view(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/jpeg")) }
    for (intent in probes) {
        val chosen = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo ?: continue
        val able = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo }
        when {
            chosen.packageName == context.packageName -> Unit
            able.none { it.packageName == chosen.packageName && it.name == chosen.name } -> {
                anyAsks = true
                if (asking == null && real) asking = intent
            }
            else -> return Opener.Other(chosen.loadLabel(pm).toString(), chosen.packageName)
        }
    }
    return if (anyAsks) Opener.Asks(asking) else Opener.Us
}

/**
 * "Opens pictures and videos", read again each time the app comes back — the answer is given
 * on Android's page, and returning from it is when it may have changed.
 */
@Composable
fun OpensRow() {
    val context = LocalContext.current
    var round by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val watcher = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) round++ }
        lifecycleOwner.lifecycle.addObserver(watcher)
        onDispose { lifecycleOwner.lifecycle.removeObserver(watcher) }
    }
    val opener by produceState<Opener?>(null, round) {
        value = withContext(Dispatchers.IO) {
            opener(context, asks(context))
        }
    }
    var explain by remember { mutableStateOf<Opener?>(null) }

    val shown = opener ?: return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                when (shown) {
                    Opener.Us -> Unit
                    is Opener.Other -> explain = shown
                    is Opener.Asks -> {
                        val ask = shown.ask
                        if (ask == null || runCatching { context.startActivity(ask) }.isFailure) explain = shown
                    }
                }
            }
            .padding(vertical = 14.dp),
    ) {
        TextMMD(text = stringResource(R.string.settings_opens), style = MaterialTheme.typography.bodyMedium)
        TextMMD(
            text = when (shown) {
                Opener.Us -> stringResource(R.string.opens_us)
                is Opener.Asks -> stringResource(R.string.opens_asks)
                is Opener.Other -> stringResource(R.string.opens_other, shown.label)
            },
            style = MaterialTheme.typography.labelSmall,
        )
    }

    explain?.let { why ->
        EInkDialog(onDismiss = { explain = null }) {
            TextMMD(text = stringResource(R.string.settings_opens), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            TextMMD(
                text = when (why) {
                    is Opener.Other -> stringResource(R.string.opens_other_note, why.label)
                    else -> stringResource(R.string.opens_nothing_note)
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(14.dp))
            if (why is Opener.Other) {
                OutlinedButtonMMD(
                    onClick = {
                        explain = null
                        runCatching { context.startActivity(openByDefault(why.pkg)) }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    TextMMD(text = stringResource(R.string.opens_other_open), style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(10.dp))
            }
            OutlinedButtonMMD(onClick = { explain = null }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                TextMMD(text = stringResource(R.string.info_close), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
