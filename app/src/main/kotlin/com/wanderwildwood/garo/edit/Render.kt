package com.wanderwildwood.garo.edit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * [Edits] made into pixels. The screen uses [turned] on a small copy and draws the marks and
 * the crop over it itself; saving uses [render] on the full picture.
 */
object Render {

    /** Turned a quarter at a time, mirrored, then straightened. Always a new bitmap. */
    fun turned(source: Bitmap, e: Edits): Bitmap {
        val m = Matrix()
        m.postRotate(90f * e.quarter)
        if (e.mirror) m.postScale(-1f, 1f)
        // With nothing to turn, Android hands back the same bitmap; a copy, so straightening
        // below never recycles the caller's.
        val turned = Bitmap.createBitmap(source, 0, 0, source.width, source.height, m, true)
            .let { if (it === source) source.copy(Bitmap.Config.ARGB_8888, true) else it }
        return if (e.straighten == 0) turned else straightened(turned, e.straighten)
    }

    /**
     * Rotated by [degrees] about the middle and zoomed just enough that no corner of the frame
     * is left empty, as a phone's own straighten does. The size stays the same.
     */
    private fun straightened(b: Bitmap, degrees: Int): Bitmap {
        val w = b.width
        val h = b.height
        val r = Math.toRadians(abs(degrees).toDouble())
        val c = cos(r)
        val s = sin(r)
        val zoom = max((w * c + h * s) / w, (w * s + h * c) / h).toFloat()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            translate(w / 2f, h / 2f)
            rotate(degrees.toFloat())
            scale(zoom, zoom)
            drawBitmap(b, -w / 2f, -h / 2f, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        b.recycle()
        return out
    }

    /** Everything, for saving: turned, marked, cropped, then brought down to [Edits.longSide]. */
    fun render(source: Bitmap, e: Edits): Bitmap {
        var b = turned(source, e)
        if (e.marks.isNotEmpty()) {
            if (!b.isMutable) b = b.copy(Bitmap.Config.ARGB_8888, true)
            drawMarks(Canvas(b), b.width.toFloat(), b.height.toFloat(), e.marks)
        }
        val x = (e.crop.left * b.width).roundToInt().coerceIn(0, b.width - 1)
        val y = (e.crop.top * b.height).roundToInt().coerceIn(0, b.height - 1)
        val w = (e.crop.width * b.width).roundToInt().coerceIn(1, b.width - x)
        val h = (e.crop.height * b.height).roundToInt().coerceIn(1, b.height - y)
        if (x != 0 || y != 0 || w != b.width || h != b.height) b = Bitmap.createBitmap(b, x, y, w, h)
        val long = max(b.width, b.height)
        if (e.longSide in 1 until long) {
            val k = e.longSide.toFloat() / long
            b = Bitmap.createScaledBitmap(b, (b.width * k).roundToInt().coerceAtLeast(1), (b.height * k).roundToInt().coerceAtLeast(1), true)
        }
        return b
    }

    /**
     * The marks, onto a canvas [w] by [h] that shows the whole turned picture. The screen calls
     * this too, with its canvas moved and scaled to where the picture sits.
     */
    fun drawMarks(canvas: Canvas, w: Float, h: Float, marks: List<Mark>) {
        val short = min(w, h)
        for (mark in marks) {
            val ink = if (mark.white) Color.WHITE else Color.BLACK
            when (mark) {
                is Mark.Line -> {
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                        strokeWidth = mark.width * short
                        color = ink
                    }
                    val first = mark.dots.firstOrNull() ?: continue
                    if (mark.dots.size == 1) {
                        canvas.drawPoint(first.x * w, first.y * h, paint)
                        continue
                    }
                    val path = Path().apply {
                        moveTo(first.x * w, first.y * h)
                        mark.dots.drop(1).forEach { lineTo(it.x * w, it.y * h) }
                    }
                    canvas.drawPath(path, paint)
                }
                is Mark.Words -> {
                    val size = mark.size * short
                    // A rim of the other ink round each letter, so words read over any picture.
                    val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        textSize = size
                        typeface = Typeface.DEFAULT_BOLD
                        style = Paint.Style.STROKE
                        strokeWidth = size / 6
                        strokeJoin = Paint.Join.ROUND
                        color = if (mark.white) Color.BLACK else Color.WHITE
                    }
                    val fill = Paint(rim).apply {
                        style = Paint.Style.FILL
                        color = ink
                    }
                    // Several lines, one under another; [at] is the top left of the first.
                    mark.text.lines().forEachIndexed { i, line ->
                        val baseline = mark.at.y * h + size * (i + 1)
                        canvas.drawText(line, mark.at.x * w, baseline, rim)
                        canvas.drawText(line, mark.at.x * w, baseline, fill)
                    }
                }
            }
        }
    }
}
