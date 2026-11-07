package com.wanderwildwood.garo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoAspectTest {

    @Test
    fun uprightVideoIsTallerThanWide() {
        // A phone video held upright: 1920x1080 frames turned a quarter, reported as 1080x1920.
        assertEquals(0.5625f, videoAspect(1080, 1920)!!, 0.0001f)
    }

    @Test
    fun sidewaysVideoIsWiderThanTall() {
        assertEquals(16f / 9f, videoAspect(1920, 1080)!!, 0.0001f)
    }

    @Test
    fun unknownSizeHasNoShapeYet() {
        assertNull(videoAspect(0, 0))
        assertNull(videoAspect(1080, 0))
        assertNull(videoAspect(-1, 1920))
    }
}
