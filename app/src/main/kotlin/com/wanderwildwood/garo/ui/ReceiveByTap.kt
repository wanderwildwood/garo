package com.wanderwildwood.garo.ui

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.garo.R
import com.wanderwildwood.garo.tap.Fetched
import com.wanderwildwood.garo.tap.PhotoFetch
import com.wanderwildwood.garo.tap.ReceiveUrl
import com.wanderwildwood.garo.tap.TypeFourReader
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** What Receive by tap has to show. */
private sealed interface Got {
    data object Waiting : Got
    data object Receiving : Got
    class Saved(val uri: Uri) : Got
    data object Nothing : Got
    data object NotWifi : Got
    data object SenderStopped : Got
    data object TooBig : Got
    data object NotPicture : Got
    data object SaveFailed : Got
    data object NfcOff : Got
    data object NoNfc : Got
}

/**
 * Reads one tap and fetches the picture it points at. The reader runs only while the screen is
 * showing ([listen] on resume, [quiet] on pause), reads the link off the other phone, accepts it
 * only if it is a private-address link of the exact shape Share by tap writes, downloads at most
 * 64 MB to a cache file, checks Android can decode it as a picture, and saves it into the phone's
 * pictures through MediaStore (Pictures/Received), which needs no permission.
 */
private class Receiver(private val context: Context, private val say: (Got) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    private var activity: Activity? = null

    private fun post(g: Got) { main.post { say(g) } }

    fun listen(activity: Activity) {
        val adapter = NfcAdapter.getDefaultAdapter(context)
        if (adapter == null) return say(Got.NoNfc)
        if (!adapter.isEnabled) return say(Got.NfcOff)
        this.activity = activity
        if (!busy.get()) say(Got.Waiting)
        runCatching {
            adapter.enableReaderMode(
                activity,
                { tag -> onTag(tag) },
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
                null,
            )
        }
    }

    fun quiet() {
        val a = activity ?: return
        activity = null
        runCatching { NfcAdapter.getDefaultAdapter(context)?.disableReaderMode(a) }
    }

    private fun onTag(tag: Tag) {
        if (!busy.compareAndSet(false, true)) return
        Thread({
            try {
                run(tag)
            } finally {
                busy.set(false)
            }
        }, "tap-receive").apply { isDaemon = true }.start()
    }

    private fun run(tag: Tag) {
        val link = runCatching {
            val iso = IsoDep.get(tag) ?: return@runCatching null
            iso.use {
                it.connect()
                it.timeout = 3000
                TypeFourReader { apdu -> it.transceive(apdu) }.readUri()
            }
        }.getOrNull()
        val target = ReceiveUrl.parse(link)
        if (target == null) return post(Got.Nothing)
        post(Got.Receiving)

        val dir = File(context.cacheDir, "receive").apply { mkdirs() }
        val temp = File(dir, UUID.randomUUID().toString())
        try {
            val result = temp.outputStream().use { PhotoFetch.fetch(target, it) }
            when (result) {
                is Fetched.Done -> post(save(temp))
                Fetched.Unreachable -> post(Got.NotWifi)
                Fetched.SenderStopped -> post(Got.SenderStopped)
                Fetched.TooBig -> post(Got.TooBig)
                Fetched.Failed -> post(Got.Nothing)
            }
        } catch (_: Exception) {
            post(Got.Nothing)
        } finally {
            temp.delete()
        }
    }

    /** Only a file Android can read the size of as a picture is kept. */
    private fun save(file: File): Got {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val mime = bounds.outMimeType
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || mime == null || !mime.startsWith("image/")) return Got.NotPicture
        val extension = when (mime) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/bmp" -> "bmp"
            "image/heif", "image/heic" -> "heic"
            else -> "jpg"
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "Received-$stamp.$extension")
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Received")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return Got.SaveFailed
        return try {
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            Got.Saved(uri)
        } catch (_: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            Got.SaveFailed
        }
    }
}

private tailrec fun Context.hostActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.hostActivity()
    else -> null
}

/**
 * Receive by tap: the other phone shows Share by tap, this one is held to it and keeps the
 * picture. Plain words and buttons, nothing moving. The reader is on only while this screen is in
 * front.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveByTapScreen(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val activity = remember(context) { context.hostActivity() }
    var got by remember { mutableStateOf<Got>(Got.Waiting) }
    var round by remember { mutableIntStateOf(0) }
    val receiver = remember { Receiver(context.applicationContext) { got = it } }

    val owner = LocalLifecycleOwner.current
    val view = LocalView.current
    DisposableEffect(owner, round) {
        view.keepScreenOn = true
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> activity?.let(receiver::listen)
                Lifecycle.Event.ON_PAUSE -> receiver.quiet()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) activity?.let(receiver::listen)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            view.keepScreenOn = false
            receiver.quiet()
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        TopAppBarMMD(
            title = { TextMMD(text = stringResource(R.string.receive_title)) },
            navigationIcon = { BarButton(Icons.Back, stringResource(R.string.viewer_cd_back), onClose) },
        )
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
            when (val g = got) {
                Got.Waiting -> {
                    Line(stringResource(R.string.receive_ready), big = true)
                    Line(stringResource(R.string.receive_wifi))
                }
                Got.Receiving -> Line(stringResource(R.string.receive_getting), big = true)
                is Got.Saved -> {
                    Line(stringResource(R.string.receive_saved), big = true)
                    Line(stringResource(R.string.receive_saved_detail))
                    Spacer(Modifier.height(14.dp))
                    Press(R.string.receive_open) {
                        // This app's own viewer, named, so another app that shows pictures is not asked.
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW).setDataAndType(g.uri, "image/*")
                                    .setPackage(context.packageName)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Press(R.string.receive_again) { got = Got.Waiting; round++ }
                    Spacer(Modifier.height(10.dp))
                    Press(R.string.tap_done, onClose)
                }
                Got.NfcOff -> {
                    Line(stringResource(R.string.receive_nfc_off))
                    Spacer(Modifier.height(14.dp))
                    Press(R.string.tap_open_nfc) { runCatching { context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) } }
                }
                Got.NoNfc -> Line(stringResource(R.string.receive_no_nfc))
                else -> {
                    Line(
                        stringResource(
                            when (g) {
                                Got.NotWifi -> R.string.receive_not_wifi
                                Got.SenderStopped -> R.string.receive_sender_stopped
                                Got.TooBig -> R.string.receive_too_big
                                Got.NotPicture -> R.string.receive_not_picture
                                Got.SaveFailed -> R.string.receive_save_failed
                                else -> R.string.receive_nothing
                            },
                        ),
                        big = true,
                    )
                    Line(
                        stringResource(
                            when (g) {
                                Got.SenderStopped -> R.string.receive_sender_stopped_detail
                                Got.Nothing -> R.string.receive_nothing_detail
                                else -> R.string.receive_wifi
                            },
                        ),
                    )
                    Spacer(Modifier.height(14.dp))
                    Press(R.string.receive_try_again) { got = Got.Waiting; round++ }
                }
            }
        }
    }
}
