package com.wanderwildwood.garo.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NextPressTest {
    @Test
    fun aNoticeGoesWhenTheNextPressLifts() {
        val at = NextPress.mark()
        NextPress.down()
        assertFalse("still down: the tap has not landed yet", NextPress.pressedSince(at))
        NextPress.up()
        assertTrue(NextPress.pressedSince(at))
    }

    @Test
    fun thePressThatWasDownWhenTheNoticeCameIsNotTheNextOne() {
        NextPress.down()
        val at = NextPress.mark() // said during a long press
        NextPress.up()
        assertFalse(NextPress.pressedSince(at))
        NextPress.down()
        NextPress.up()
        assertTrue(NextPress.pressedSince(at))
    }

    @Test
    fun nothingPressedNothingGone() {
        NextPress.down()
        NextPress.up()
        val at = NextPress.mark()
        assertFalse(NextPress.pressedSince(at))
    }
}
