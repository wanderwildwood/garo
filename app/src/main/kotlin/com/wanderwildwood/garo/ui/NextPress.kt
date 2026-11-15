package com.wanderwildwood.garo.ui

import android.view.KeyEvent
import android.view.MotionEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * The reader's presses, counted, so a notice can stay until the next one. On this panel a
 * notice on a timer could be gone before it had been read; one that waits for the next press
 * is read first and goes on the way to whatever that press does.
 *
 * A press on the screen counts when the finger lifts, not when it comes down: the notice goes
 * after the tap has landed, so a line leaving the foot cannot move a key or a row out from under
 * the finger and lose the press. A press that was already down when the notice came (a long
 * press that said something) is not the next one; only one that starts after it is.
 *
 * Each activity that shows a notice passes its touches and keys in ([seen]).
 */
object NextPress {
    /** Presses begun so far. */
    private var begun = 0

    /** The number of the last press that has ended. */
    private val ended = MutableStateFlow(0)

    /** A finger down and up again on the screen, or Back. Volume keys and the like are not a press here. */
    fun seen(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> down()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> up()
        }
    }

    fun seen(event: KeyEvent) {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
            down()
            up()
        }
    }

    internal fun down() {
        begun++
    }

    internal fun up() {
        ended.value = begun
    }

    /** Where the count stands now, for [pressedSince]. */
    internal fun mark(): Int = begun

    /** Whether a press begun after [mark] has ended. */
    internal fun pressedSince(mark: Int): Boolean = ended.value > mark

    /** Returns once a press that began after it was called has ended. */
    suspend fun await() {
        val at = mark()
        ended.first { it > at }
    }
}
