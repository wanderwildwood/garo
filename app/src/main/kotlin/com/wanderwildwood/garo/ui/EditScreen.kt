package com.wanderwildwood.garo.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.garo.R
import com.wanderwildwood.garo.edit.Dot
import com.wanderwildwood.garo.edit.Edits
import com.wanderwildwood.garo.edit.Frame
import com.wanderwildwood.garo.edit.Keep
import com.wanderwildwood.garo.edit.Mark
import com.wanderwildwood.garo.edit.Render
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private enum class Tool { CROP, DRAW, SIZE }

/** Crop shapes, width over height; null is free. Turned to the picture's own way round. */
private val SHAPES = listOf<Pair<Int, Float?>>(
    R.string.edit_shape_free to null,
    R.string.edit_shape_square to 1f,
    R.string.edit_shape_4_3 to 4f / 3f,
    R.string.edit_shape_16_9 to 16f / 9f,
)

/** Longest sides offered for saving smaller; 0 is as it is. 1600 is plenty for a message. */
private val SIZES = listOf(0, 2048, 1600, 1024)

/** Pen widths and word sizes, fractions of the picture's shorter side. */
private const val THIN = 0.006f
private const val THICK = 0.016f
private const val WORDS = 0.06f

/**
 * Editing one picture: crop and turn it, draw and write on it, and save it smaller for sending.
 *
 * The picture fills the top of the screen; under it the controls of whichever of Crop, Draw
 * and Size is chosen in the tabs at the foot. Nothing moves on its own: a handle follows the
 * finger, and the rest of the screen stays still. Save asks where to put the new picture
 * and never changes the original.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(uri: Uri, onSaved: (Uri) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val scope = rememberCoroutineScope()

    var source by remember { mutableStateOf<Bitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    var full by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(uri) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching { Keep.load(resolver, uri, PREVIEW_LONG_SIDE) to Keep.size(resolver, uri) }.getOrNull()
        }
        if (loaded == null) failed = true else {
            val shown = loaded.first
            source = shown
            // The full size the right way up: the longest side from the file, the way round
            // from the decoded picture, which has had the camera's turn applied.
            val long = loaded.second?.let { max(it.first, it.second) } ?: max(shown.width, shown.height)
            val k = long.toFloat() / max(shown.width, shown.height)
            full = (shown.width * k).roundToInt() to (shown.height * k).roundToInt()
        }
    }

    var edits by remember { mutableStateOf(Edits()) }
    var tool by remember { mutableStateOf(Tool.CROP) }
    var shape by remember { mutableStateOf<Float?>(null) }
    var words by remember { mutableStateOf(false) }
    var white by remember { mutableStateOf(false) }
    var thick by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    var writingAt by remember { mutableStateOf<Dot?>(null) }

    // The turned picture, made again only when the turning changes.
    val turned = remember(source, edits.quarter, edits.mirror, edits.straighten) {
        source?.let { Render.turned(it, edits) }
    }

    fun close() = if (edits.untouched) onCancel() else leaving = true
    BackHandler { close() }

    // Save asks where to put the picture, through Android's own "save as" screen; only then is it
    // rendered and written.
    var pickedPng by remember { mutableStateOf(false) }
    val chooseWhere = rememberLauncherForActivityResult(SaveAs()) { destination ->
        if (destination == null) {
            saving = false
        } else {
            scope.launch {
                val saved = withContext(Dispatchers.Default) {
                    runCatching {
                        val big = Keep.load(resolver, uri, SAVE_LONG_SIDE)
                        Keep.write(resolver, destination, Render.render(big, edits), pickedPng)
                        destination
                    }.getOrNull()
                }
                saving = false
                if (saved != null) onSaved(saved) else failed = true
            }
        }
    }

    fun save() {
        if (saving) return
        saving = true
        pickedPng = Keep.isPng(resolver, uri)
        val ask = SaveAs.Ask(Keep.suggestedName(resolver, uri), if (pickedPng) "image/png" else "image/jpeg")
        if (runCatching { chooseWhere.launch(ask) }.isFailure) saving = false
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(if (saving) R.string.edit_saving else R.string.edit_title)) },
                navigationIcon = { BarButton(Icons.Close, stringResource(R.string.edit_cd_close)) { close() } },
                actions = {
                    if (!edits.untouched && !saving) {
                        ButtonMMD(onClick = ::save, modifier = Modifier.padding(end = 8.dp).height(48.dp)) {
                            TextMMD(text = stringResource(R.string.edit_save), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                val picture = turned
                when {
                    failed -> TextMMD(text = stringResource(R.string.edit_failed), style = MaterialTheme.typography.bodyMedium)
                    picture != null -> Picture(
                        picture = picture,
                        edits = edits,
                        tool = tool,
                        shape = shape,
                        words = words,
                        white = white,
                        thick = thick,
                        onEdits = { edits = it },
                        onWriteAt = { writingAt = it },
                    )
                }
            }
            HorizontalDividerMMD()
            val picture = turned
            if (picture != null) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    when (tool) {
                        Tool.CROP -> CropControls(
                            shape = shape,
                            straighten = edits.straighten,
                            onShape = { ratio ->
                                shape = ratio
                                edits = edits.copy(
                                    crop = if (ratio == null) Frame.ALL else {
                                        // The picture's own way round: 4:3 on a tall picture is 3:4.
                                        val r = if (picture.height > picture.width) 1 / ratio else ratio
                                        Frame.largest(r, picture.width, picture.height)
                                    },
                                )
                            },
                            onTurn = { edits = edits.turnedRight(); shape = null },
                            onMirror = { edits = edits.mirrored() },
                            onStraighten = { by ->
                                edits = edits.copy(straighten = (edits.straighten + by).coerceIn(-Edits.MOST_STRAIGHTEN, Edits.MOST_STRAIGHTEN))
                            },
                        )
                        Tool.DRAW -> DrawControls(
                            words = words,
                            white = white,
                            thick = thick,
                            canUndo = edits.marks.isNotEmpty(),
                            onWords = { words = it },
                            onWhite = { white = it },
                            onThick = { thick = it },
                            onUndo = { edits = edits.copy(marks = edits.marks.dropLast(1)) },
                        )
                        Tool.SIZE -> SizeControls(
                            chosen = edits.longSide,
                            full = full,
                            edits = edits,
                            onChoose = { edits = edits.copy(longSide = it) },
                        )
                    }
                }
            }
            Tabs(
                chosen = tool.ordinal,
                labels = listOf(stringResource(R.string.edit_crop), stringResource(R.string.edit_draw), stringResource(R.string.edit_size)),
            ) { tool = Tool.entries[it] }
        }
    }

    writingAt?.let { at ->
        WordsDialog(
            onPlace = { text ->
                if (text.isNotBlank()) edits = edits.copy(marks = edits.marks + Mark.Words(text.trim(), at, WORDS, white))
                writingAt = null
            },
            onDismiss = { writingAt = null },
        )
    }
    if (leaving) {
        EInkDialog(onDismiss = { leaving = false }) {
            TextMMD(text = stringResource(R.string.edit_leave_title), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButtonMMD(onClick = { leaving = false }, modifier = Modifier.weight(1f).height(48.dp)) {
                    TextMMD(text = stringResource(R.string.edit_keep_editing), style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButtonMMD(onClick = { leaving = false; onCancel() }, modifier = Modifier.weight(1f).height(48.dp)) {
                    TextMMD(text = stringResource(R.string.edit_discard), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/**
 * Where the shown part of the turned picture sits on the canvas: [window] (the whole picture
 * while cropping, the crop otherwise) fitted to the canvas and centred.
 */
private class Fit(canvas: Size, picture: Bitmap, val window: Frame) {
    val shownW = window.width * picture.width
    val shownH = window.height * picture.height
    val scale = min(canvas.width / shownW, canvas.height / shownH)
    val w = shownW * scale
    val h = shownH * scale
    val left = (canvas.width - w) / 2
    val top = (canvas.height - h) / 2

    /** A point on the canvas, as a fraction of the whole turned picture. */
    fun toPicture(o: Offset) = Dot(
        window.left + (o.x - left) / w * window.width,
        window.top + (o.y - top) / h * window.height,
    )

    fun toCanvas(d: Dot) = Offset(left + (d.x - window.left) / window.width * w, top + (d.y - window.top) / window.height * h)

    /** The whole turned picture's rectangle on the canvas, most of it perhaps off the shown part. */
    val wholeLeft get() = left - window.left / window.width * w
    val wholeTop get() = top - window.top / window.height * h
    val wholeW get() = w / window.width
    val wholeH get() = h / window.height
}

@Composable
private fun Picture(
    picture: Bitmap,
    edits: Edits,
    tool: Tool,
    shape: Float?,
    words: Boolean,
    white: Boolean,
    thick: Boolean,
    onEdits: (Edits) -> Unit,
    onWriteAt: (Dot) -> Unit,
) {
    val image = remember(picture) { picture.asImageBitmap() }
    val handle = with(LocalDensity.current) { 36.dp.toPx() }
    val ink = MaterialTheme.colorScheme.onSurface
    var size by remember { mutableStateOf(Size.Zero) }
    var drawing by remember { mutableStateOf<Mark.Line?>(null) }
    val window = if (tool == Tool.CROP) Frame.ALL else edits.crop
    // Read by the gestures, which outlive any one recomposition.
    val current by rememberUpdated(edits)

    val gestures = when (tool) {
        Tool.CROP -> Modifier.pointerInput(shape, picture) {
            var grip: Grip? = null
            detectDragGestures(
                onDragStart = { at ->
                    val fit = Fit(size, picture, Frame.ALL)
                    grip = Grip.at(at, current.crop, fit, handle)
                },
                onDragEnd = { grip = null },
                onDragCancel = { grip = null },
            ) { change, _ ->
                val g = grip ?: return@detectDragGestures
                change.consume()
                val fit = Fit(size, picture, Frame.ALL)
                val p = fit.toPicture(change.position)
                val ratio = shape?.let { if (picture.height > picture.width) 1 / it else it }
                onEdits(current.copy(crop = g.drag(current.crop, p, ratio?.let { it * picture.height / picture.width })))
            }
        }
        Tool.DRAW -> if (words) {
            Modifier.pointerInput(picture, edits.crop) {
                detectTapGestures { at ->
                    val p = Fit(size, picture, current.crop).toPicture(at)
                    if (p.x in current.crop.left..current.crop.right && p.y in current.crop.top..current.crop.bottom) onWriteAt(p)
                }
            }
        } else {
            Modifier.pointerInput(picture, edits.crop, white, thick) {
                detectDragGestures(
                    onDragStart = { at ->
                        drawing = Mark.Line(listOf(Fit(size, picture, current.crop).toPicture(at)), if (thick) THICK else THIN, white)
                    },
                    onDragEnd = {
                        drawing?.let { onEdits(current.copy(marks = current.marks + it)) }
                        drawing = null
                    },
                    onDragCancel = { drawing = null },
                ) { change, _ ->
                    change.consume()
                    val line = drawing ?: return@detectDragGestures
                    drawing = line.copy(dots = line.dots + Fit(size, picture, current.crop).toPicture(change.position))
                }
            }
        }
        Tool.SIZE -> Modifier
    }

    Canvas(Modifier.fillMaxSize().onSizeChanged { size = it.toSize() }.then(gestures)) {
        val fit = Fit(this.size, picture, window)
        val src = IntOffset((window.left * picture.width).roundToInt(), (window.top * picture.height).roundToInt())
        val srcSize = IntSize(
            (window.width * picture.width).roundToInt().coerceIn(1, picture.width - src.x),
            (window.height * picture.height).roundToInt().coerceIn(1, picture.height - src.y),
        )
        drawImage(
            image,
            srcOffset = src,
            srcSize = srcSize,
            dstOffset = IntOffset(fit.left.roundToInt(), fit.top.roundToInt()),
            dstSize = IntSize(fit.w.roundToInt(), fit.h.roundToInt()),
        )
        // The marks, drawn as saving will draw them, onto the whole picture's place, clipped
        // to the part shown.
        val marks = edits.marks + listOfNotNull(drawing)
        if (marks.isNotEmpty()) {
            clipRect(fit.left, fit.top, fit.left + fit.w, fit.top + fit.h) {
                drawIntoCanvas { c ->
                    c.nativeCanvas.save()
                    c.nativeCanvas.translate(fit.wholeLeft, fit.wholeTop)
                    Render.drawMarks(c.nativeCanvas, fit.wholeW, fit.wholeH, marks)
                    c.nativeCanvas.restore()
                }
            }
        }
        if (tool == Tool.CROP) drawCrop(fit, edits.crop, ink, handle)
    }
}

/**
 * The crop: what is cut away hatched over in ink, the frame in black, and a square at each
 * corner. Hatching rather than a see-through white veil: the panel has no see-through, and a
 * veil reaches it as a grey dither, while a line is a line.
 */
private fun DrawScope.drawCrop(fit: Fit, crop: Frame, ink: Color, handle: Float) {
    val a = fit.toCanvas(Dot(crop.left, crop.top))
    val b = fit.toCanvas(Dot(crop.right, crop.bottom))
    val gap = 10.dp.toPx()
    val line = 1.5.dp.toPx()
    fun hatch(left: Float, top: Float, right: Float, bottom: Float) {
        if (right - left <= 0f || bottom - top <= 0f) return
        clipRect(left, top, right, bottom) {
            // Lines at 45 degrees, from the bottom-left corner across, spaced the same everywhere.
            var x = left - (bottom - top)
            while (x < right) {
                drawLine(ink, Offset(x, bottom), Offset(x + (bottom - top), top), strokeWidth = line)
                x += gap
            }
        }
    }
    hatch(fit.left, fit.top, fit.left + fit.w, a.y)
    hatch(fit.left, b.y, fit.left + fit.w, fit.top + fit.h)
    hatch(fit.left, a.y, a.x, b.y)
    hatch(b.x, a.y, fit.left + fit.w, b.y)
    drawRect(ink, a, Size(b.x - a.x, b.y - a.y), style = Stroke(width = 3.dp.toPx()))
    val s = handle / 2
    listOf(a, Offset(b.x, a.y), Offset(a.x, b.y), b).forEach { c ->
        drawRect(ink, Offset(c.x - s / 2, c.y - s / 2), Size(s, s))
    }
}

/** What a finger took hold of in the crop: a corner, or the whole frame to move it. */
private sealed interface Grip {
    fun drag(crop: Frame, to: Dot, ratio: Float?): Frame

    /** One corner; [right] and [bottom] say which. [ratio] is width over height in fractions. */
    data class Corner(val right: Boolean, val bottom: Boolean) : Grip {
        override fun drag(crop: Frame, to: Dot, ratio: Float?): Frame {
            // The corner opposite stays where it is.
            val ax = if (right) crop.left else crop.right
            val ay = if (bottom) crop.top else crop.bottom
            var x = to.x.coerceIn(0f, 1f)
            var y = to.y.coerceIn(0f, 1f)
            var w = max(abs(x - ax), SMALLEST)
            var h = max(abs(y - ay), SMALLEST)
            if (ratio != null) {
                h = w / ratio
                val roomY = if (bottom) 1 - ay else ay
                if (h > roomY) { h = roomY; w = h * ratio }
            }
            x = if (right) ax + w else ax - w
            y = if (bottom) ay + h else ay - h
            return Frame(min(ax, x), min(ay, y), max(ax, x), max(ay, y)).inside()
        }
    }

    /** The whole frame, by where the finger first came down inside it. */
    data class Move(val from: Dot, val start: Frame) : Grip {
        override fun drag(crop: Frame, to: Dot, ratio: Float?): Frame {
            val dx = (to.x - from.x).coerceIn(-start.left, 1 - start.right)
            val dy = (to.y - from.y).coerceIn(-start.top, 1 - start.bottom)
            return Frame(start.left + dx, start.top + dy, start.right + dx, start.bottom + dy)
        }
    }

    companion object {
        const val SMALLEST = 0.08f

        fun at(o: Offset, crop: Frame, fit: Fit, handle: Float): Grip? {
            val corners = listOf(
                Corner(right = false, bottom = false) to Dot(crop.left, crop.top),
                Corner(right = true, bottom = false) to Dot(crop.right, crop.top),
                Corner(right = false, bottom = true) to Dot(crop.left, crop.bottom),
                Corner(right = true, bottom = true) to Dot(crop.right, crop.bottom),
            )
            corners.minByOrNull { (_, d) -> (fit.toCanvas(d) - o).getDistance() }
                ?.takeIf { (_, d) -> (fit.toCanvas(d) - o).getDistance() <= handle }
                ?.let { return it.first }
            val p = fit.toPicture(o)
            return if (p.x in crop.left..crop.right && p.y in crop.top..crop.bottom) Move(p, crop) else null
        }
    }
}

private fun Frame.inside() = Frame(left.coerceIn(0f, 1f), top.coerceIn(0f, 1f), right.coerceIn(0f, 1f), bottom.coerceIn(0f, 1f))

@Composable
private fun <T> rememberUpdated(value: T) = androidx.compose.runtime.rememberUpdatedState(value)

@Composable
private fun CropControls(
    shape: Float?,
    straighten: Int,
    onShape: (Float?) -> Unit,
    onTurn: () -> Unit,
    onMirror: () -> Unit,
    onStraighten: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SHAPES.forEach { (label, ratio) ->
            Choice(stringResource(label), chosen = shape == ratio, modifier = Modifier.weight(1f)) { onShape(ratio) }
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Choice(stringResource(R.string.edit_turn), chosen = false, modifier = Modifier.weight(1f), onClick = onTurn)
        Choice(stringResource(R.string.edit_mirror), chosen = false, modifier = Modifier.weight(1f), onClick = onMirror)
        Choice("−", chosen = false, modifier = Modifier.width(48.dp)) { onStraighten(-1) }
        TextMMD(
            text = stringResource(R.string.edit_straighten, straighten),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(64.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Choice("+", chosen = false, modifier = Modifier.width(48.dp)) { onStraighten(1) }
    }
}

@Composable
private fun DrawControls(
    words: Boolean,
    white: Boolean,
    thick: Boolean,
    canUndo: Boolean,
    onWords: (Boolean) -> Unit,
    onWhite: (Boolean) -> Unit,
    onThick: (Boolean) -> Unit,
    onUndo: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Choice(stringResource(R.string.edit_pen), chosen = !words, modifier = Modifier.weight(1f)) { onWords(false) }
        Choice(stringResource(R.string.edit_words), chosen = words, modifier = Modifier.weight(1f)) { onWords(true) }
        // Only while there is something to undo; a faded button is a grey smear on this panel.
        if (canUndo) {
            Choice(stringResource(R.string.edit_undo), chosen = false, modifier = Modifier.weight(1f), onClick = onUndo)
        } else {
            Spacer(Modifier.weight(1f))
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Choice(stringResource(R.string.edit_black), chosen = !white, modifier = Modifier.weight(1f)) { onWhite(false) }
        Choice(stringResource(R.string.edit_white), chosen = white, modifier = Modifier.weight(1f)) { onWhite(true) }
        if (!words) {
            Choice(stringResource(R.string.edit_thin), chosen = !thick, modifier = Modifier.weight(1f)) { onThick(false) }
            Choice(stringResource(R.string.edit_thick), chosen = thick, modifier = Modifier.weight(1f)) { onThick(true) }
        }
    }
    TextMMD(
        text = stringResource(if (words) R.string.edit_words_note else R.string.edit_pen_note),
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun SizeControls(chosen: Int, full: Pair<Int, Int>?, edits: Edits, onChoose: (Int) -> Unit) {
    // What the saved picture would measure: the crop of the turned picture, then made smaller.
    val (fw, fh) = full ?: (0 to 0)
    val tw = if (edits.quarter % 2 == 0) fw else fh
    val th = if (edits.quarter % 2 == 0) fh else fw
    val cw = (edits.crop.width * tw).roundToInt()
    val ch = (edits.crop.height * th).roundToInt()
    val big = max(cw, ch)
    // Only the sizes that would make it smaller: "1600" for a picture already 1200 says nothing.
    SIZES.filter { it == 0 || it < big }.forEach { long ->
        val k = if (long in 1 until big) long.toFloat() / big else 1f
        val label = if (long == 0) stringResource(R.string.edit_size_as_is) else stringResource(R.string.edit_size_long, long)
        val measure = "${(cw * k).roundToInt()} × ${(ch * k).roundToInt()}"
        Row(
            Modifier
                .fillMaxWidth()
                .selectable(selected = chosen == long, role = Role.RadioButton) { onChoose(long) }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextMMD(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (chosen == long) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
            )
            TextMMD(text = measure, style = MaterialTheme.typography.labelSmall)
        }
    }
    TextMMD(text = stringResource(R.string.edit_size_note), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 2.dp))
}

/** A word to press, bordered; the chosen one is filled black. 48 dp high, a finger's width. */
@Composable
private fun Choice(text: String, chosen: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .height(48.dp)
            .border(2.dp, ink, shape)
            .background(if (chosen) ink else MaterialTheme.colorScheme.surface, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        TextMMD(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal,
            color = if (chosen) MaterialTheme.colorScheme.surface else ink,
            maxLines = 1,
        )
    }
}

/** Crop, Draw, Size: the shop's tabs, the chosen one a black stone. */
@Composable
private fun Tabs(chosen: Int, labels: List<String>, onTab: (Int) -> Unit) {
    HorizontalDividerMMD(thickness = 1.dp)
    StoneTabs(labels, chosen, onTab)
}

/** Words to put on the picture, typed; Place puts them where the finger came down. */
@Composable
private fun WordsDialog(onPlace: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    EInkDialog(onDismiss = onDismiss) {
        TextMMD(text = stringResource(R.string.edit_words_title), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(12.dp))
        TextFieldMMD(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButtonMMD(onClick = onDismiss, modifier = Modifier.weight(1f).height(48.dp)) {
                TextMMD(text = stringResource(R.string.edit_cancel), style = MaterialTheme.typography.bodySmall)
            }
            // Place appears once there are words to place, rather than standing there greyed.
            if (text.isNotBlank()) {
                OutlinedButtonMMD(onClick = { onPlace(text) }, modifier = Modifier.weight(1f).height(48.dp)) {
                    TextMMD(text = stringResource(R.string.edit_place), style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Big enough to look sharp on the panel while editing, small enough to turn quickly. */
private const val PREVIEW_LONG_SIDE = 1600

/** The most saving reads: a 12-megapixel photo whole; anything larger is brought down to it. */
private const val SAVE_LONG_SIDE = 4096
