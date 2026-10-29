package com.wanderwildwood.garo.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlankTest {
    @Test fun allClearIsBlank() = assertTrue(Blank.isBlank(IntArray(9)))
    @Test fun oneColourIsBlank() = assertTrue(Blank.isBlank(IntArray(9) { 0xFFFFFFFF.toInt() }))
    @Test fun clearAndWhiteIsBlank() = assertTrue(Blank.isBlank(intArrayOf(0, 0xFFFFFFFF.toInt(), 0)))
    @Test fun aDarkMarkIsNotBlank() = assertFalse(Blank.isBlank(intArrayOf(0, 0xFF000000.toInt(), 0)))
    @Test fun emptyIsBlank() = assertTrue(Blank.isBlank(IntArray(0)))
}
