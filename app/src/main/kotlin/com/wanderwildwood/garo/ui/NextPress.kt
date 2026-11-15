package com.wanderwildwood.garo.ui

import android.view.KeyEvent
import android.view.MotionEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/**
 * The reader's presses, counted, so a notice can stay until the next one. On this panel a
 * notice on a timer could be gone before it had been read; one that waits for the next press
 * is read first and goes on the way to whatever that press does.
 *
 * Each activity that shows a notice passes its touches and keys in ([seen]).
 */
object NextPress {
    private val count = MutableStateFlow(0)

    /** A finger down on the screen, or Back. Volume keys and the like are not a press here. */
    fun seen(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) count.update { it + 1 }
    }

    fun seen(event: KeyEvent) {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) count.update { it + 1 }
    }

    /** Returns at the first press after it was called. */
    suspend fun await() {
        val at = count.value
        count.first { it > at }
    }
}
