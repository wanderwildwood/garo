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
 * SCREEN_ORIENTATION_SENSOR turns nothing: the screen stays upright whichever way the phone
 * is held. An orientation asked for by name is always honoured, so this sees the phone turn
 * and asks for it.
 *
 * Sideways is taken up only after the phone has stayed that way for [HOLD_MS], and left only
 * when it is close to upright again, so a tilt on the way somewhere does not turn the panel
 * and redraw all of it. Upside down is ignored. The listener runs only while the activity is
 * in front and the turning is on.
 */
class TurnWithPhone(private val activity: Activity) {
    private var on = false
    private var held = false
    private var resumed = false
    private var pending = NONE
    private var last = UNKNOWN
    private val handler = Handler(Looper.getMainLooper())

    // Made on first use: an activity has no system services until its onCreate has run, and
    // this is built as a property of the activity.
    private val sensors by lazy { activity.getSystemService(SensorManager::class.java) }
    private val accelerometer by lazy { sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }
    private val listener by lazy {
        object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) = see(degrees(event.values[0], event.values[1], event.values[2]))
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
                activity.requestedOrientation = PORTRAIT
            }
            held -> activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
            else -> activity.requestedOrientation = shown
        }
        pending = NONE
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
            sensors?.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
        } else {
            sensors?.unregisterListener(listener)
            handler.removeCallbacks(settle)
            pending = NONE
        }
    }

    private fun see(degrees: Int) {
        last = degrees
        decide(hold = true)
    }

    /**
     * The listener speaks only when the angle changes, so a phone turned and then held still
     * says nothing more; the wait is a timer, and the angle is read again when it runs out.
     */
    private fun decide(hold: Boolean) {
        val want = wanted(last)
        if (want == NONE || want == shown) {
            pending = NONE
            handler.removeCallbacks(settle)
            return
        }
        if (!hold) {
            if (want == pending) apply(want)
            return
        }
        if (want != pending) {
            pending = want
            handler.removeCallbacks(settle)
            handler.postDelayed(settle, if (want == PORTRAIT) PORTRAIT_HOLD_MS else HOLD_MS)
        }
    }

    private val settle = Runnable { decide(hold = false) }

    private fun apply(want: Int) {
        shown = want
        pending = NONE
        activity.requestedOrientation = want
    }

    /**
     * Sideways only when the phone is clearly on its side; anything else (upright, leaning back,
     * flat, upside-down, between) is portrait. Held in the hand the angle jumps between upright,
     * flat and upside-down, so ignoring those never let the screen come back.
     */
    private fun wanted(degrees: Int): Int = when (degrees) {
        in 50..130 -> REVERSE_LANDSCAPE
        in 230..310 -> LANDSCAPE
        else -> PORTRAIT
    }

    companion object {
        /**
         * Which way up the phone is held, in degrees as the system's own orientation listener
         * reckons them, or [UNKNOWN] when it lies flat. The system's listener calls the phone flat
         * as soon as it leans back about 63 degrees, which a phone held sideways in the hand
         * often does, so its answer flickers between sideways and flat. This one calls it flat
         * only within about 11 degrees of level.
         */
        fun degrees(sx: Float, sy: Float, sz: Float): Int {
            val x = -sx
            val y = -sy
            val z = -sz
            if ((x * x + y * y) * FLAT_RATIO < z * z) return UNKNOWN
            val angle = 90 - Math.round(Math.atan2(-y.toDouble(), x.toDouble()) * 180 / Math.PI).toInt()
            return ((angle % 360) + 360) % 360
        }

        const val UNKNOWN = -1

        private const val FLAT_RATIO = 25f

        const val PORTRAIT = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        const val LANDSCAPE = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        const val REVERSE_LANDSCAPE = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        const val NONE = -2
        const val HOLD_MS = 600L
        const val PORTRAIT_HOLD_MS = 1200L

        /** Which way the screen is turned, kept for the process so a window built again starts the same way. */
        @Volatile
        var shown = PORTRAIT
    }
}
