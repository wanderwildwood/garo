package com.wanderwildwood.garo

import org.junit.Assert.assertEquals
import org.junit.Test

/** Readings are raw accelerometer values in the usual Android frame, as the Kompakt reports them (checked on the phone, 31 Oct). */
class TurnWithPhoneTest {
    private val g = 9.8f
    private val unknown = TurnWithPhone.UNKNOWN
    private fun degrees(x: Float, y: Float, z: Float) = TurnWithPhone.degrees(x, y, z)

    @Test fun upright() = assertEquals(0, degrees(0f, g, 0f))
    @Test fun upsideDown() = assertEquals(180, degrees(0f, -g, 0f))
    @Test fun leftSideUp() = assertEquals(270, degrees(g, 0f, 0f))
    @Test fun rightSideUp() = assertEquals(90, degrees(-g, 0f, 0f))
    @Test fun flatFaceUpIsUnknown() = assertEquals(unknown, degrees(0f, 0f, g))
    @Test fun flatFaceDownIsUnknown() = assertEquals(unknown, degrees(0f, 0f, -g))
    @Test fun leaningBackUprightStaysUpright() = assertEquals(0, degrees(0f, 7f, 7f))
    @Test fun sidewaysLeanedBackStillSideways() = assertEquals(270, degrees(7f, 0f, 7f))
    @Test fun recordedUprightHold() = assertEquals(true, degrees(0.2f, 9.4f, 2.8f).let { it >= 340 || it <= 20 })
    @Test fun recordedLeftHold() = assertEquals(true, degrees(9.8f, -0.1f, 1.3f) in 255..285)
    @Test fun recordedRightHold() = assertEquals(true, degrees(-9.5f, -0.2f, 1.7f) in 75..105)
}
