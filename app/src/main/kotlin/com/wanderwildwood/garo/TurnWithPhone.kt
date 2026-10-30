package com.wanderwildwood.garo

import android.app.Activity
import android.content.pm.ActivityInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
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

    private val sensors by lazy { activity.getSystemService(SensorManager::class.java) }
    private val accelerometer by lazy { sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }

    // Made on first use: an activity has no system services until its onCreate has run, and
    // this is built as a property of the activity.
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
            handler.postDelayed(settle, HOLD_MS)
        }
    }

    private val settle = Runnable { decide(hold = false) }

    private fun apply(want: Int) {
        shown = want
        pending = NONE
        activity.requestedOrientation = want
    }

    private fun wanted(degrees: Int): Int = when {
        // Flat on a table: no way is up, so nothing changes.
        degrees == UNKNOWN -> NONE
        degrees >= 330 || degrees <= 30 -> PORTRAIT
        degrees in 60..120 -> REVERSE_LANDSCAPE
        degrees in 240..300 -> LANDSCAPE
        else -> NONE
    }

    companion object {
        const val PORTRAIT = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        const val LANDSCAPE = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        const val REVERSE_LANDSCAPE = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        const val NONE = -2
        const val UNKNOWN = -1

        /**
         * The Kompakt's accelerometer is mounted turned: its x axis points out of the screen and
         * its z axis down the long edge, so the stock orientation listener sees an upright phone
         * as lying flat and never reports it. Read in the phone's own axes (x right, y up, z out
         * of the screen) the angle is the same sum the stock listener does; flat reads as unknown.
         */
        private val MOUNTED_TURNED = Build.MANUFACTURER.equals("Mudita", ignoreCase = true)

        fun degrees(sx: Float, sy: Float, sz: Float, turned: Boolean = MOUNTED_TURNED): Int {
            val x = -(if (turned) -sy else sx)
            val y = -(if (turned) -sz else sy)
            val z = -(if (turned) sx else sz)
            if ((x * x + y * y) * 4 < z * z) return UNKNOWN
            val angle = 90 - Math.round(Math.atan2(-y.toDouble(), x.toDouble()) * 180 / Math.PI).toInt()
            return ((angle % 360) + 360) % 360
        }
        const val HOLD_MS = 600L

        /** Which way the screen is turned, kept for the process so a window built again starts the same way. */
        @Volatile
        var shown = PORTRAIT
    }
}
