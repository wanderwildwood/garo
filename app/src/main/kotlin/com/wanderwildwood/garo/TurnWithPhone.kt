package com.wanderwildwood.garo

import android.app.Activity
import android.content.pm.ActivityInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper

/**
 * Turns the screen with the phone by watching the accelerometer itself.
 *
 * The Kompakt's system has auto-rotate switched off for good (its window policy says
 * mSupportAutoRotation=false and there is no setting to change it), so asking for
 * SCREEN_ORIENTATION_SENSOR turns nothing. An orientation asked for by name is always honoured,
 * so this watches the phone and asks.
 *
 * Which way the phone is held is decided by [RotationJudge], a port of Android 12's own
 * WindowOrientationListener (the judge a phone with auto-rotate uses), so the screen turns when
 * and only when an ordinary Android phone would. On top of that, e-ink: a turn redraws the whole
 * panel, so a new way up must be proposed for [HOLD_MS] before the screen follows. Upside down is
 * never taken. Edit this file in /opt/projects/shared-turnwithphone, then copy.sh; never an
 * app's copy.
 */
class TurnWithPhone(private val activity: Activity) {
    private var on = false
    private var held = false
    private var resumed = false
    private var pending = NONE
    private val judge = RotationJudge()
    private val handler = Handler(Looper.getMainLooper())

    // Made on first use: an activity has no system services until its onCreate has run, and
    // this is built as a property of the activity.
    private val sensors by lazy { activity.getSystemService(SensorManager::class.java) }
    private val accelerometer by lazy { sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }
    private val listener by lazy {
        object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                judge.current = rotationOf(shown)
                see(judge.feed(event.values[0], event.values[1], event.values[2], event.timestamp))
            }
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
    }

    /**
     * [on]: turn with the phone. [held]: leave the screen as it is for now, whichever way it is.
     * Call from onCreate before anything is drawn, so a window built again after a turn comes
     * up the way it was and does not turn back and forth.
     */
    fun set(on: Boolean, held: Boolean = false) {
        this.on = on
        this.held = held
        when {
            !on -> {
                shown = PORTRAIT
                ask(PORTRAIT)
            }
            held -> ask(ActivityInfo.SCREEN_ORIENTATION_LOCKED)
            else -> ask(shown)
        }
        pending = NONE
        handler.removeCallbacks(settle)
        update()
    }

    fun resume() {
        resumed = true
        update()
    }

    fun pause() {
        resumed = false
        update()
    }

    private fun update() {
        if (resumed && on && !held && accelerometer != null) {
            judge.reset()
            sensors?.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        } else {
            sensors?.unregisterListener(listener)
            handler.removeCallbacks(settle)
            pending = NONE
        }
    }

    /** [rotation] is the judge's proposal (0, 1, 3) or -1 for "no opinion, keep the screen". */
    private fun see(rotation: Int) {
        val want = orientationOf(rotation)
        if (want == NONE || want == shown) {
            if (pending != NONE) {
                pending = NONE
                handler.removeCallbacks(settle)
            }
            return
        }
        if (want != pending) {
            pending = want
            handler.removeCallbacks(settle)
            handler.postDelayed(settle, HOLD_MS)
        }
    }

    private val settle = Runnable {
        val want = pending
        if (want != NONE && want == orientationOf(judge.proposed)) {
            shown = want
            ask(want)
        }
        pending = NONE
    }

    /**
     * A screen may refuse a named orientation (a see-through window on Android 8 throws
     * IllegalStateException); the screen then simply stays as it is instead of the app closing.
     */
    private fun ask(orientation: Int) {
        runCatching { activity.requestedOrientation = orientation }
    }

    companion object {
        const val PORTRAIT = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        const val LANDSCAPE = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        const val REVERSE_LANDSCAPE = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        const val NONE = -2

        /** How long a new way up must be proposed before the panel is turned (e-ink: a turn redraws it all). */
        const val HOLD_MS = 600L

        /** The judge's rotation (Surface.ROTATION_*) to the orientation asked for; upside down and "no opinion" are NONE. */
        fun orientationOf(rotation: Int): Int = when (rotation) {
            0 -> PORTRAIT
            1 -> LANDSCAPE
            3 -> REVERSE_LANDSCAPE
            else -> NONE
        }

        fun rotationOf(orientation: Int): Int = when (orientation) {
            LANDSCAPE -> 1
            REVERSE_LANDSCAPE -> 3
            else -> 0
        }

        /** Which way the screen is turned, kept for the process so a window built again starts the same way. */
        @Volatile
        var shown = PORTRAIT
    }
}

/**
 * Android 12's WindowOrientationListener.AccelSensorJudge, ported line for line in its decisions
 * (frameworks/base services/core/java/com/android/server/wm/WindowOrientationListener.java,
 * android12-release), without the touch and rotation-resolver parts. Pure: no Android calls, so it
 * is unit-tested with recorded Kompakt readings. Feed it raw accelerometer values (the usual
 * Android frame, which the Kompakt uses: checked on a Kompakt 2026-11-05) and the sample time;
 * it answers the proposed rotation (Surface.ROTATION_0/90/180/270 as 0..3), or -1 when the phone
 * is flat, overhead, swinging, moving or between two ways up, and the screen should stay as it is.
 */
class RotationJudge {
    /** The rotation the screen is in now (0..3), for the hysteresis gap; -1 = unknown. */
    var current = -1

    var proposed = -1
        private set

    private var lastT = Long.MIN_VALUE
    private var fx = 0f
    private var fy = 0f
    private var fz = 0f
    private var predicted = -1
    private var predictedT = 0L
    private var flatT = Long.MIN_VALUE
    private var swingT = Long.MIN_VALUE
    private var accelT = Long.MIN_VALUE
    private var overhead = false
    private val tiltHistory = FloatArray(HISTORY)
    private val tiltHistoryT = LongArray(HISTORY)
    private var historyIndex = 0

    fun reset() {
        lastT = Long.MIN_VALUE
        predicted = -1
        proposed = -1
        flatT = Long.MIN_VALUE
        swingT = Long.MIN_VALUE
        accelT = Long.MIN_VALUE
        overhead = false
        tiltHistoryT.fill(0L)
        historyIndex = 0
    }

    fun feed(rawX: Float, rawY: Float, rawZ: Float, nanos: Long): Int {
        var x = rawX
        var y = rawY
        var z = rawZ
        val skip: Boolean
        if (lastT == Long.MIN_VALUE || nanos < lastT || nanos > lastT + MAX_FILTER_DELTA ||
            (x == 0f && y == 0f && z == 0f)
        ) {
            reset()
            skip = true
        } else {
            val dtMs = (nanos - lastT) * 0.000001f
            val alpha = dtMs / (FILTER_TIME_CONSTANT_MS + dtMs)
            x = alpha * (x - fx) + fx
            y = alpha * (y - fy) + fy
            z = alpha * (z - fz) + fz
            skip = false
        }
        lastT = nanos
        fx = x
        fy = y
        fz = z

        if (!skip) {
            val magnitude = Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()
            if (magnitude < NEAR_ZERO_MAGNITUDE) {
                predicted = -1
            } else {
                if (magnitude < G - ACCELERATION_TOLERANCE || magnitude > G + ACCELERATION_TOLERANCE) accelT = nanos
                val tilt = Math.round(Math.toDegrees(Math.asin((z / magnitude).toDouble()))).toInt()
                addTilt(nanos, tilt.toFloat())
                if (isFlat(nanos)) flatT = nanos
                if (isSwinging(nanos, tilt.toFloat())) swingT = nanos
                if (tilt <= TILT_OVERHEAD_ENTER) overhead = true else if (tilt >= TILT_OVERHEAD_EXIT) overhead = false
                if (overhead || Math.abs(tilt) > MAX_TILT) {
                    predicted = -1
                } else {
                    var angle = Math.round(-Math.toDegrees(Math.atan2(-x.toDouble(), y.toDouble()))).toInt()
                    if (angle < 0) angle += 360
                    var nearest = (angle + 45) / 90
                    if (nearest == 4) nearest = 0
                    if (tiltAcceptable(nearest, tilt) && angleAcceptable(nearest, angle)) {
                        if (predicted != nearest) {
                            predicted = nearest
                            predictedT = nanos
                        }
                    } else {
                        predicted = -1
                    }
                }
            }
        }
        if (predicted < 0 || settled(nanos)) proposed = predicted
        return proposed
    }

    private fun tiltAcceptable(rotation: Int, tilt: Int) =
        tilt >= TILT_TOLERANCE[rotation][0] && tilt <= TILT_TOLERANCE[rotation][1]

    private fun angleAcceptable(rotation: Int, angle: Int): Boolean {
        val cur = current
        if (cur >= 0) {
            if (rotation == cur || rotation == (cur + 1) % 4) {
                val lower = rotation * 90 - 45 + GAP / 2
                if (rotation == 0) {
                    if (angle >= 315 && angle < lower + 360) return false
                } else if (angle < lower) {
                    return false
                }
            }
            if (rotation == cur || rotation == (cur + 3) % 4) {
                val upper = rotation * 90 + 45 - GAP / 2
                if (rotation == 0) {
                    if (angle <= 45 && angle > upper) return false
                } else if (angle > upper) {
                    return false
                }
            }
        }
        return true
    }

    private fun settled(now: Long): Boolean =
        now >= predictedT + SETTLE &&
            (flatT == Long.MIN_VALUE || now >= flatT + SINCE_FLAT) &&
            (swingT == Long.MIN_VALUE || now >= swingT + SINCE_SWING) &&
            (accelT == Long.MIN_VALUE || now >= accelT + SINCE_ACCELERATION)

    private fun addTilt(now: Long, tilt: Float) {
        tiltHistory[historyIndex] = tilt
        tiltHistoryT[historyIndex] = now
        historyIndex = (historyIndex + 1) % HISTORY
        tiltHistoryT[historyIndex] = 0L
    }

    /** Walks the history from newest to oldest; -1 when it runs out. */
    private fun next(i: Int): Int {
        val j = (if (i == 0) HISTORY else i) - 1
        return if (tiltHistoryT[j] != 0L) j else -1
    }

    private fun isFlat(now: Long): Boolean {
        var i = historyIndex
        while (true) {
            i = next(i)
            if (i < 0) return false
            if (tiltHistory[i] < FLAT_ANGLE) return false
            if (tiltHistoryT[i] + FLAT_TIME <= now) return true
        }
    }

    private fun isSwinging(now: Long, tilt: Float): Boolean {
        var i = historyIndex
        while (true) {
            i = next(i)
            if (i < 0) return false
            if (tiltHistoryT[i] + SWING_TIME < now) return false
            if (tiltHistory[i] + SWING_AWAY_ANGLE_DELTA <= tilt) return true
        }
    }

    companion object {
        private const val MS = 1_000_000L
        const val SETTLE = 40 * MS
        const val SINCE_FLAT = 500 * MS
        const val SINCE_SWING = 300 * MS
        const val SINCE_ACCELERATION = 500 * MS
        const val FLAT_ANGLE = 80f
        const val FLAT_TIME = 1000 * MS
        const val SWING_AWAY_ANGLE_DELTA = 20f
        const val SWING_TIME = 300 * MS
        const val MAX_FILTER_DELTA = 1000 * MS
        const val FILTER_TIME_CONSTANT_MS = 200f
        const val NEAR_ZERO_MAGNITUDE = 1f
        const val ACCELERATION_TOLERANCE = 4f
        const val G = 9.80665f
        const val MAX_TILT = 80
        const val TILT_OVERHEAD_ENTER = -40
        const val TILT_OVERHEAD_EXIT = -15
        const val GAP = 45
        private const val HISTORY = 200

        /** Per rotation: how far from vertical (degrees) the phone may lean, facing down / up. */
        val TILT_TOLERANCE = arrayOf(intArrayOf(-25, 70), intArrayOf(-25, 65), intArrayOf(-25, 60), intArrayOf(-25, 65))
    }
}
