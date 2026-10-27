package com.wanderwildwood.garo.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewingTest {

    @Test
    fun `a picture saved from the editor above this one leaves the viewer on the same photo`() {
        // On the second of three; the editor's copy then joins the folder at the top.
        val before = listOf(30L, 20L, 10L)
        assertEquals(1, Viewing.index(before, saved = null, current = 20L, place = 1))
        val after = listOf(40L, 30L, 20L, 10L)
        assertEquals(2, Viewing.index(after, saved = null, current = 20L, place = 1))
    }

    @Test
    fun `the saved copy is shown once the folder has it, and not before`() {
        assertEquals(1, Viewing.index(listOf(30L, 20L, 10L), saved = 40L, current = 20L, place = 1))
        assertEquals(0, Viewing.index(listOf(40L, 30L, 20L, 10L), saved = 40L, current = 20L, place = 2))
    }

    @Test
    fun `after a delete the same place holds the next picture`() {
        assertEquals(1, Viewing.index(listOf(30L, 10L), saved = null, current = 20L, place = 1))
        assertEquals(1, Viewing.index(listOf(30L, 20L), saved = null, current = 10L, place = 2))
        assertEquals(0, Viewing.index(emptyList(), saved = null, current = 10L, place = 2))
    }
}
