package com.wanderwildwood.garo.ui

import androidx.compose.ui.geometry.Offset
import kotlin.math.max
import kotlin.math.min

/** Where the picture sits in the viewer: how much it is enlarged and how far it has been moved. */
data class ZoomState(val scale: Float, val offset: Offset)

/** The scale (1 = fitted to the panel) at which one picture pixel is one screen pixel. */
fun oneToOneScale(boxW: Float, boxH: Float, pictureW: Int, pictureH: Int): Float {
    val fit = min(boxW / pictureW, boxH / pictureH)
    return 1f / fit
}

/** Keeps the enlarged picture covering the panel: it can be moved until its edge meets the panel's. */
fun clampOffset(o: Offset, scale: Float, fitW: Float, fitH: Float, boxW: Float, boxH: Float): Offset {
    val maxX = max(0f, (fitW * scale - boxW) / 2)
    val maxY = max(0f, (fitH * scale - boxH) / 2)
    return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
}

/**
 * What a double tap does. Enlarged already: back to fitted. Otherwise: to exactly one picture
 * pixel per screen pixel, the tapped point staying under the finger as far as the picture's edges
 * allow. A picture smaller than the panel is already past 1:1 when fitted, so it goes to twice
 * the fitted size instead; a picture hardly bigger than the panel is treated alike, since 1:1
 * would show nothing more.
 *
 * [tap] is measured from the centre of the panel.
 */
fun doubleTapZoom(
    current: ZoomState,
    tap: Offset,
    boxW: Float,
    boxH: Float,
    pictureW: Int,
    pictureH: Int,
): ZoomState {
    if (current.scale > 1.02f) return ZoomState(1f, Offset.Zero)
    val fit = min(boxW / pictureW, boxH / pictureH)
    val oneToOne = 1f / fit
    val target = if (oneToOne > 1.1f) oneToOne else 2f
    // The point of the fitted picture under the finger keeps its place on the panel.
    val under = (tap - current.offset) / current.scale
    val moved = tap - under * target
    val fitW = pictureW * fit
    val fitH = pictureH * fit
    return ZoomState(target, clampOffset(moved, target, fitW, fitH, boxW, boxH))
}
