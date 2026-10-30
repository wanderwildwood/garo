package com.wanderwildwood.garo

import org.junit.Assert.assertEquals
import org.junit.Test

class TurnWithPhoneTest {
    private val g = 9.8f
    private val unknown = TurnWithPhone.UNKNOWN

    @Test fun stockUpright() = assertEquals(0, TurnWithPhone.degrees(0f, g, 0f, turned = false))
    @Test fun stockUpsideDown() = assertEquals(180, TurnWithPhone.degrees(0f, -g, 0f, turned = false))
    @Test fun stockLeftSideUp() = assertEquals(270, TurnWithPhone.degrees(g, 0f, 0f, turned = false))
    @Test fun stockRightSideUp() = assertEquals(90, TurnWithPhone.degrees(-g, 0f, 0f, turned = false))
    @Test fun stockFlatIsUnknown() = assertEquals(unknown, TurnWithPhone.degrees(0f, 0f, g, turned = false))
    @Test fun stockLeaningBack() = assertEquals(0, TurnWithPhone.degrees(0f, 7f, 7f, turned = false))

    @Test fun turnedUpright() = assertEquals(0, TurnWithPhone.degrees(0f, 0f, -g, turned = true))
    @Test fun turnedUpsideDown() = assertEquals(180, TurnWithPhone.degrees(0f, 0f, g, turned = true))
    @Test fun turnedLeftSideUp() = assertEquals(270, TurnWithPhone.degrees(0f, -g, 0f, turned = true))
    @Test fun turnedRightSideUp() = assertEquals(90, TurnWithPhone.degrees(0f, g, 0f, turned = true))
    @Test fun turnedFlatIsUnknown() = assertEquals(unknown, TurnWithPhone.degrees(-g, 0f, 0f, turned = true))
    @Test fun turnedUprightReadsFlatToStock() = assertEquals(unknown, TurnWithPhone.degrees(0f, 0f, -g, turned = false))
}
