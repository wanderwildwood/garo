package com.wanderwildwood.garo

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The judge against a Kompakt, held six ways for ten seconds each (2026-11-05; the readings
 * are in kompakt-poses-2026-11-05.csv beside this test), and against made-up holds that used to
 * turn the screen wrongly: a phone on the desk at a slight tilt, face up or face down.
 */
class TurnWithPhoneTest {
    private val g = 9.81f
    private val ms = 1_000_000L

    private fun recorded(): List<FloatArray> {
        val stream = javaClass.classLoader!!.getResourceAsStream("kompakt-poses-2026-11-05.csv")
        return stream.bufferedReader().readLines().filter { it.isNotBlank() && !it.startsWith("#") }
            .map { l -> l.split(',').let { floatArrayOf(it[0].toDouble().toFloat(), it[1].toFloat(), it[2].toFloat(), it[3].toFloat()) } }
    }

    /** Plays readings through the judge and the e-ink hold, as TurnWithPhone does; returns the screen at each sample. */
    private fun play(samples: List<Pair<Long, FloatArray>>, start: Int = TurnWithPhone.PORTRAIT): List<Pair<Long, Int>> {
        val judge = RotationJudge()
        var shown = start
        var pending = TurnWithPhone.NONE
        var pendingSince = 0L
        return samples.map { (t, v) ->
            judge.current = TurnWithPhone.rotationOf(shown)
            val want = TurnWithPhone.orientationOf(judge.feed(v[0], v[1], v[2], t))
            if (want == TurnWithPhone.NONE || want == shown) {
                pending = TurnWithPhone.NONE
            } else if (want != pending) {
                pending = want
                pendingSince = t
            } else if (t - pendingSince >= TurnWithPhone.HOLD_MS * ms) {
                shown = want
                pending = TurnWithPhone.NONE
            }
            t to shown
        }
    }

    private fun steady(x: Float, y: Float, z: Float, seconds: Int, from: Long = 1_000_000_000L): List<Pair<Long, FloatArray>> =
        (0 until seconds * 50).map { i -> (from + i * 20 * ms) to floatArrayOf(x, y, z) }

    private fun screenAt(trace: List<Pair<Long, Int>>, second: Double): Int {
        val t0 = trace.first().first
        return trace.last { it.first - t0 <= (second * 1e9).toLong() }.second
    }

    @Test fun sixRecordedHolds() {
        val rows = recorded()
        val trace = play(rows.map { it[0].toLong() to floatArrayOf(it[1], it[2], it[3]) })
        assertEquals("upright", TurnWithPhone.PORTRAIT, screenAt(trace, 8.0))
        assertEquals("top to the left", TurnWithPhone.LANDSCAPE, screenAt(trace, 18.0))
        assertEquals("top to the right", TurnWithPhone.REVERSE_LANDSCAPE, screenAt(trace, 28.0))
        assertEquals("laid flat: stays as it was", TurnWithPhone.REVERSE_LANDSCAPE, screenAt(trace, 38.0))
        assertEquals("still tilted forward (overhead): stays as it was", TurnWithPhone.REVERSE_LANDSCAPE, screenAt(trace, 55.0))
        // Upright again only from about 58 s, so portrait comes at the very end of the recording.
        assertEquals("upright again", TurnWithPhone.PORTRAIT, trace.last().second)
    }

    @Test fun onTheDeskAtASlightTiltNeverTurns() {
        // Face up, 20 degrees off level toward a side (Android itself needs 25 before it turns).
        assertEquals(TurnWithPhone.PORTRAIT, play(steady(3.36f, 0f, 9.22f, 5)).last().second)
        // Face down, tipped 27 degrees: this turned Mailbox sideways on 2026-11-05.
        assertEquals(TurnWithPhone.PORTRAIT, play(steady(4.5f, -0.05f, -8.68f, 5)).last().second)
    }

    @Test fun flatKeepsLandscape() =
        assertEquals(TurnWithPhone.LANDSCAPE, play(steady(0.1f, 0f, g, 5), start = TurnWithPhone.LANDSCAPE).last().second)

    @Test fun upsideDownIsNeverTaken() =
        assertEquals(TurnWithPhone.PORTRAIT, play(steady(0f, -g, 1f, 5)).last().second)

    @Test fun sidewaysLeanedBackTurns() =
        // On its left side, leaning back 40 degrees, as read in a chair.
        assertEquals(TurnWithPhone.LANDSCAPE, play(steady(7.5f, 0f, 6.3f, 5)).last().second)

    @Test fun halfwayDoesNotFlap() =
        // 50 degrees round from upright is past halfway but inside the 45-degree gap: stays portrait.
        assertEquals(TurnWithPhone.PORTRAIT, play(steady(7.5f, 6.3f, 1f, 5)).last().second)

    @Test fun aQuickTiltDoesNotTurn() {
        // Upright, then on its side for 0.4 s, then upright again: shorter than the e-ink hold.
        val side = (0 until 20).map { i -> (3_000_000_000L + i * 20 * ms) to floatArrayOf(g, 0f, 1f) }
        val samples = steady(0f, g, 1f, 2) + side + steady(0f, g, 1f, 2, 3_400_000_000L)
        assertEquals(TurnWithPhone.PORTRAIT, play(samples).last().second)
    }

    @Test fun mapping() {
        assertEquals(TurnWithPhone.PORTRAIT, TurnWithPhone.orientationOf(0))
        assertEquals(TurnWithPhone.LANDSCAPE, TurnWithPhone.orientationOf(1))
        assertEquals(TurnWithPhone.NONE, TurnWithPhone.orientationOf(2))
        assertEquals(TurnWithPhone.REVERSE_LANDSCAPE, TurnWithPhone.orientationOf(3))
        assertEquals(TurnWithPhone.NONE, TurnWithPhone.orientationOf(-1))
    }
}
