package com.wanderwildwood.garo.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.garo.R
import com.wanderwildwood.garo.media.Picture
import com.wanderwildwood.garo.tap.Offer
import com.wanderwildwood.garo.tap.Shareable
import com.wanderwildwood.garo.tap.TapShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.UUID

/** A picture copied into the cache, so that its size is exact and it can be read any number of times. */
private class CachedPicture(
    override val name: String,
    override val mime: String,
    val file: File,
) : Shareable {
    override val size: Long get() = file.length()
    override fun openStream(): InputStream = file.inputStream()
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/**
 * The Share by tap screen. It is the only place the tag and the server run: leaving the screen,
 * or the app going out of sight, stops both. Plain words, nothing moving.
 *
 * The picture is read the way Share reads it, through Android's media index, so a picture from
 * the phone's own library reaches the other phone with its location taken out by Android.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareByTapScreen(picture: Picture, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val activity = remember(context) { context.activity() }
    var status by remember { mutableStateOf<TapShare.Status?>(null) }
    var round by remember { mutableIntStateOf(0) }
    val share = remember { TapShare(context.applicationContext) { status = it } }
    var cached by remember { mutableStateOf<CachedPicture?>(null) }

    LaunchedEffect(round) {
        status = null
        val act = activity ?: run { status = TapShare.Status.Failed; return@LaunchedEffect }
        val c = cached ?: withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, "tap").apply { deleteRecursively(); mkdirs() }
                val f = File(dir, UUID.randomUUID().toString())
                context.contentResolver.openInputStream(picture.uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                CachedPicture(picture.name, context.contentResolver.getType(picture.uri) ?: picture.mime ?: "image/jpeg", f)
            }.getOrNull()
        }
        if (c == null) { status = TapShare.Status.Failed; return@LaunchedEffect }
        cached = c
        share.start(act, Offer.File(c))
    }

    val owner = LocalLifecycleOwner.current
    val view = LocalView.current
    DisposableEffect(owner) {
        view.keepScreenOn = true
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // Back from NFC or Wi-Fi settings: try again.
                Lifecycle.Event.ON_RESUME -> when (status) {
                    TapShare.Status.NfcOff, TapShare.Status.NoWifi -> round++
                    else -> share.prefer()
                }
                Lifecycle.Event.ON_STOP -> share.stop()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            view.keepScreenOn = false
            share.stop(silent = true)
            File(context.cacheDir, "tap").deleteRecursively()
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        TopAppBarMMD(
            title = { TextMMD(text = stringResource(R.string.tap_title)) },
            navigationIcon = { BarButton(Icons.Back, stringResource(R.string.viewer_cd_back), onClose) },
        )
        Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 20.dp, vertical = 16.dp)) {
            when (val s = status) {
                null -> Line(stringResource(R.string.tap_preparing))
                is TapShare.Status.Ready -> {
                    Line(stringResource(R.string.tap_ready), big = true)
                    Line(stringResource(R.string.tap_ready_wifi), small = true)
                    Line(stringResource(R.string.tap_ready_screen), small = true)
                    // The same link as a picture, for a phone whose tap does not work: any camera reads it.
                    s.link?.let {
                        Line(stringResource(R.string.tap_qr_label), small = true)
                        QrCode(it, Modifier.fillMaxWidth().weight(1f))
                    }
                    Line(stringResource(R.string.tap_limits), small = true)
                    Press(R.string.tap_stop) { share.stop() }
                }
                TapShare.Status.Sent -> {
                    Line(stringResource(R.string.tap_sent), big = true)
                    Line(stringResource(R.string.tap_sent_detail))
                    Spacer(Modifier.height(14.dp))
                    Press(R.string.tap_done, onClose)
                }
                TapShare.Status.Stopped -> {
                    Line(stringResource(R.string.tap_stopped), big = true)
                    Line(stringResource(R.string.tap_stopped_detail))
                    Spacer(Modifier.height(14.dp))
                    Press(R.string.tap_again) { round++ }
                    Spacer(Modifier.height(10.dp))
                    Press(R.string.tap_done, onClose)
                }
                TapShare.Status.NfcOff -> {
                    Line(stringResource(R.string.tap_nfc_off))
                    Spacer(Modifier.height(14.dp))
                    Press(R.string.tap_open_nfc) { runCatching { context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) } }
                }
                TapShare.Status.NoWifi -> {
                    Line(stringResource(R.string.tap_no_wifi))
                    Spacer(Modifier.height(14.dp))
                    Press(R.string.tap_open_wifi) { runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } }
                }
                TapShare.Status.NoNfc -> Line(stringResource(R.string.tap_no_nfc))
                TapShare.Status.Failed -> Line(stringResource(R.string.tap_failed))
            }
        }
    }
}

@Composable
internal fun Line(text: String, big: Boolean = false, small: Boolean = false) {
    TextMMD(
        text = text,
        style = when {
            big -> MaterialTheme.typography.bodyLarge
            small -> MaterialTheme.typography.bodySmall
            else -> MaterialTheme.typography.bodyMedium
        },
        modifier = Modifier.padding(bottom = if (small) 4.dp else 8.dp),
    )
}

@Composable
internal fun Press(label: Int, onClick: () -> Unit) {
    OutlinedButtonMMD(onClick = onClick, modifier = Modifier.fillMaxWidth().height(48.dp)) {
        TextMMD(text = stringResource(label), style = MaterialTheme.typography.bodySmall)
    }
}

/** Share's two ways: an app, or a tap. Asked only on a phone that can tap. */
@Composable
fun ShareChoiceDialog(onApp: () -> Unit, onTap: () -> Unit, onDismiss: () -> Unit) {
    EInkDialog(onDismiss = onDismiss) {
        Press(R.string.tap_choice_tap, onTap)
        Spacer(Modifier.height(10.dp))
        Press(R.string.tap_choice_app, onApp)
        Spacer(Modifier.height(10.dp))
        Press(R.string.tap_cancel, onDismiss)
    }
}
