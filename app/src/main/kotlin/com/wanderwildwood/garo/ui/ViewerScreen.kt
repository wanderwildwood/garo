package com.wanderwildwood.garo.ui

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.garo.R
import com.wanderwildwood.garo.media.Decoder
import com.wanderwildwood.garo.media.Picture
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** What the viewer has for the picture it is on. */
private sealed interface Shown {
    data object Decoding : Shown
    data class Ready(val bitmap: Bitmap) : Shown
    data object Failed : Shown
}

/**
 * One picture, the whole panel, on white.
 *
 * It turns like a page rather than sliding like a film strip. A tap at either side goes back or
 * on, the way an e-reader does, and a swipe does the same; nothing moves in between, because on
 * this panel a slide is a dozen grey half-frames and then the picture anyway. A tap in the
 * middle shows or hides the bar.
 *
 * Two fingers zoom, and one finger then moves about the enlarged picture; while it is enlarged,
 * taps at the sides do not turn the page, since a thumb exploring a corner would otherwise lose
 * its place. Pinching back to the whole picture lets them turn pages again. There is no
 * double-tap: to tell a double tap from a single one, every single tap would have to wait,
 * and a page turn that waits is the thing an e-ink reader learns to hate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    pictures: List<Picture>,
    startIndex: Int,
    decoder: Decoder,
    /** False for a picture another app handed over: it is not ours to delete. */
    canDelete: Boolean,
    onIndex: (Int) -> Unit,
    onBack: () -> Unit,
    onDelete: (Picture) -> Unit,
) {
    if (pictures.isEmpty()) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    var chosen by rememberSaveable { mutableStateOf(startIndex) }
    // After a delete the list is one shorter, and the same place now holds the next picture.
    val index = chosen.coerceIn(0, pictures.lastIndex)
    val picture = pictures[index]
    LaunchedEffect(index) { onIndex(index) }

    var barShown by rememberSaveable { mutableStateOf(true) }
    var infoOpen by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val longSide = remember(context) {
        val m = context.resources.displayMetrics
        2 * max(m.widthPixels, m.heightPixels)
    }

    // Keyed on the picture, so turning the page starts from a fresh state rather than the
    // previous picture's — which would otherwise be drawn for a frame under the new number,
    // and on this panel a frame is a full repaint.
    var shown by remember(picture.uri) {
        mutableStateOf(decoder.cachedFull(picture)?.let { Shown.Ready(it) } ?: Shown.Decoding)
    }
    LaunchedEffect(picture.uri) {
        if (shown !is Shown.Ready) shown = decoder.full(picture, longSide)?.let { Shown.Ready(it) } ?: Shown.Failed
    }

    // The pictures either side, decoded while this one is being looked at.
    LaunchedEffect(picture.uri, shown is Shown.Ready) {
        if (shown !is Shown.Ready) return@LaunchedEffect
        pictures.getOrNull(index + 1)?.let { decoder.full(it, longSide) }
        pictures.getOrNull(index - 1)?.let { decoder.full(it, longSide) }
    }

    fun turn(by: Int) {
        val next = index + by
        if (next in pictures.indices) chosen = next
    }

    Box(Modifier.fillMaxSize()) {
        when (val s = shown) {
            Shown.Decoding -> Unit
            Shown.Failed -> Explain(text = stringResource(R.string.viewer_failed))
            is Shown.Ready -> Zoomable(
                bitmap = s.bitmap,
                key = picture.uri,
                onTap = { fraction, zoomed ->
                    when {
                        zoomed -> barShown = !barShown
                        fraction < 1f / 3 -> turn(-1)
                        fraction > 2f / 3 -> turn(+1)
                        else -> barShown = !barShown
                    }
                },
                onSwipe = { turn(it) },
            )
        }

        if (barShown) {
            TopAppBarMMD(
                title = {
                    TextMMD(
                        text = if (pictures.size > 1) {
                            stringResource(R.string.viewer_position, index + 1, pictures.size)
                        } else {
                            picture.name
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { BarButton(Icons.Back, stringResource(R.string.viewer_cd_back), onBack) },
                actions = {
                    BarButton(Icons.Info, stringResource(R.string.viewer_cd_info)) { infoOpen = true }
                    // A picture on the server is not a file here to hand over or to delete; sharing
                    // one would mean fetching the original first, which is for a later version.
                    if (picture.remote == null) BarButton(Icons.Share, stringResource(R.string.viewer_cd_share)) {
                        val send = Intent(Intent.ACTION_SEND)
                            .setType(context.contentResolver.getType(picture.uri) ?: "image/*")
                            .putExtra(Intent.EXTRA_STREAM, picture.uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        // The grant travels on the clip as well as the stream: Android hands the
                        // read permission on through a chooser only from the clip.
                        send.clipData = ClipData.newRawUri(null, picture.uri)
                        runCatching {
                            context.startActivity(Intent.createChooser(send, null))
                        }.onFailure {
                            Toast.makeText(context, R.string.viewer_no_share, Toast.LENGTH_SHORT).show()
                        }
                    }
                    if (canDelete && picture.remote == null) {
                        BarButton(Icons.Delete, stringResource(R.string.viewer_cd_delete)) { onDelete(picture) }
                    }
                },
            )
        }
    }

    if (infoOpen) InfoDialog(picture = picture, onDismiss = { infoOpen = false })
}

/**
 * A picture fitted to the panel, with pinch-to-zoom and a one-finger pan once enlarged.
 *
 * Written out by hand rather than with `detectTransformGestures`, which takes every one-finger
 * drag as a pan and leaves nothing for the swipe that turns the page. Here one finger is a pan
 * only while the picture is enlarged; otherwise it is a tap or a swipe, decided when it lifts.
 * The pan follows the finger and stops when the finger does — no fling, nothing coasting.
 */
@Composable
private fun Zoomable(
    bitmap: Bitmap,
    key: Any,
    /** Where across the panel the tap landed, 0 to 1, and whether the picture is enlarged. */
    onTap: (fraction: Float, zoomed: Boolean) -> Unit,
    /** +1 for on, -1 for back. */
    onSwipe: (Int) -> Unit,
) {
    var scale by remember(key) { mutableFloatStateOf(1f) }
    var offset by remember(key) { mutableStateOf(Offset.Zero) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val slop = LocalViewConfiguration.current.touchSlop
    val swipe = with(LocalDensity.current) { 48.dp.toPx() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        // The size the picture is drawn at before any zoom: fitted inside the panel.
        val fit = min(boxW / bitmap.width, boxH / bitmap.height)
        val fitW = bitmap.width * fit
        val fitH = bitmap.height * fit

        fun clamp(o: Offset, s: Float): Offset {
            val maxX = max(0f, (fitW * s - boxW) / 2)
            val maxY = max(0f, (fitH * s - boxH) / 2)
            return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
        }

        // The touches are read on this unscaled box, not on the picture: a layer that has been
        // scaled reports positions in its own scaled space, and every pan would run at the
        // wrong speed.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(key, boxW, boxH) {
                    val centre = Offset(boxW / 2, boxH / 2)
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var travelled = Offset.Zero
                        var pinched = false
                        var pressed = true
                        while (pressed) {
                            val event = awaitPointerEvent()
                            val fingers = event.changes.count { it.pressed }
                            if (fingers >= 2) {
                                pinched = true
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                val focus = event.calculateCentroid(useCurrent = true) - centre
                                val newScale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                                // Keep the point between the fingers where it is while the scale changes.
                                val moved = (offset - focus) * (newScale / scale) + focus + pan
                                scale = newScale
                                offset = clamp(moved, newScale)
                                event.changes.forEach { it.consume() }
                            } else if (!pinched && fingers == 1) {
                                val change = event.changes.first { it.pressed }
                                val delta = change.positionChange()
                                travelled += delta
                                if (scale > 1f) {
                                    offset = clamp(offset + delta, scale)
                                    change.consume()
                                }
                            }
                            pressed = event.changes.any { it.pressed }
                        }

                        if (scale < 1.02f) {
                            scale = 1f
                            offset = Offset.Zero
                        }
                        if (pinched) return@awaitEachGesture

                        val still = travelled.getDistance() < slop
                        when {
                            still -> onTap(down.position.x / boxW, scale > 1f)
                            scale > 1f -> Unit
                            abs(travelled.x) > swipe && abs(travelled.x) > abs(travelled.y) ->
                                onSwipe(if (travelled.x < 0) +1 else -1)
                        }
                    }
                },
        ) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
        }
    }
}

/** Twice the panel's own resolution is decoded, so beyond about four times there is nothing more to see. */
private const val MAX_ZOOM = 4f
