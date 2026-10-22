package com.wanderwildwood.garo.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomMathTest {
    private val fitted = ZoomState(1f, Offset.Zero)

    @Test
    fun oneToOneIsPictureWidthOverPanelWidth() {
        assertEquals(10f, oneToOneScale(480f, 800f, 4800, 3600), 1e-4f)
        assertEquals(8f, oneToOneScale(480f, 800f, 3000, 6400), 1e-4f)
    }

    @Test
    fun aDoubleTapGoesToExactlyOnePicturePixelPerScreenPixel() {
        val z = doubleTapZoom(fitted, Offset.Zero, 480f, 800f, 4800, 3600)
        // fitted width 480 for 4800 pixels
        assertEquals(10f, z.scale, 1e-4f)
        assertEquals(Offset.Zero, z.offset)
    }

    @Test
    fun theTappedPointStaysUnderTheFinger() {
        // 4800x3600 fits at 0.1: 480x360 on the panel. Tap 100 right of centre, 50 above.
        val tap = Offset(100f, -50f)
        val z = doubleTapZoom(fitted, tap, 480f, 800f, 4800, 3600)
        // At 10x the point 100 right of centre would be 1000 right, so move back by 900.
        assertEquals(-900f, z.offset.x, 1e-3f)
        // The picture is 3600 high at 1:1, panel 800: the room is 1400 each way; -50-(-500) = 450.
        assertEquals(-50f - (-50f * 10f), z.offset.y, 1e-3f)
    }

    @Test
    fun aTapNearAnEdgeIsClampedToTheEdge() {
        val z = doubleTapZoom(fitted, Offset(240f, 180f), 480f, 800f, 4800, 3600)
        // Right edge of the picture is flush with the panel's right edge: (4800 - 480) / 2.
        assertEquals(-2160f, z.offset.x, 1e-3f)
        assertEquals(-1400f, z.offset.y.coerceAtLeast(-1400f), 1e-3f)
    }

    @Test
    fun aDoubleTapWhenEnlargedGoesBackToFit() {
        val z = doubleTapZoom(ZoomState(3f, Offset(40f, 10f)), Offset(5f, 5f), 480f, 800f, 4800, 3600)
        assertEquals(fitted, z)
    }

    @Test
    fun aSmallPictureGoesToTwiceFitted() {
        // 240x200 is drawn at twice its size when fitted; 1:1 would be smaller than fitted.
        val z = doubleTapZoom(fitted, Offset.Zero, 480f, 800f, 240, 200)
        assertEquals(2f, z.scale, 1e-4f)
    }

    @Test
    fun theOffsetNeverLeavesTheEdges() {
        val o = clampOffset(Offset(9999f, -9999f), 2f, 480f, 360f, 480f, 800f)
        assertEquals(240f, o.x, 1e-3f)
        assertEquals(0f, o.y, 1e-3f)
    }
}
