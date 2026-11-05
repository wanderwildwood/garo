package com.wanderwildwood.garo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

class StoneTabsTest {
    // The Kompakt: 480 px across at 213 dpi, so a dp is 1.33 px and, at font scale 1, so is an sp.
    private val room = 480
    private fun px(dp: Int) = Math.round(dp * 1.33f)

    /** Bold Lato runs about 0.47 em a letter ("Favourites" is 101 px at 16 sp); [scale] is the font scale. */
    private fun widths(labels: List<String>, scale: Float = 1f) = { sp: Float ->
        labels.map { ceil(it.length * sp * 0.47f * 1.33f * scale).toInt() + 1 }
    }

    private fun need(labels: List<String>, set: TabSet, scale: Float = 1f): Int {
        val w = widths(labels, scale)(set.sp)
        return w.sum() + w.size * 2 * px(set.inner) + (w.size - 1) * px(set.gap) + 2 * px(set.edge)
    }

    @Test
    fun aRowThatFitsTodayLooksAsItDid() {
        assertEquals(TAB_STEPS[0], fitTabs(room, widths(listOf("One", "Two", "Six", "Ten")), ::px))
        assertEquals(TAB_STEPS[0], TabSet(20f, 14, 6, 12))
        assertEquals(TAB_STEPS[1], TabSet(HOUSE_TYPE_FLOOR_SP, 8, 6, 12))
        assertEquals(TAB_STEPS[1], fitTabs(room, widths(listOf("Favorites", "Recents", "Contacts", "Keypad")), ::px))
    }

    @Test
    fun aLongerLabelOrALargerFontTakesRoomAndEveryTabStillFits() {
        // The forum report: "Favourites", one letter longer, pushed Keypad off as "K…".
        val labels = listOf("Favourites", "Recents", "Contacts", "Keypad")
        for (scale in listOf(1f, 1.1f, 1.15f, 1.3f)) {
            val set = fitTabs(room, widths(labels, scale), ::px)
            assertFalse("scale $scale", set.wrap)
            assertEquals(HOUSE_TYPE_FLOOR_SP, set.sp)
            assertTrue("scale $scale needs ${need(labels, set, scale)}", need(labels, set, scale) <= room)
        }
        assertTrue(fitTabs(room, widths(labels, 1.3f), ::px).inner < TAB_STEPS[1].inner)
    }

    @Test
    fun theTypeNeverGoesUnderTheFloorAndALongTranslationTakesTwoLines() {
        assertTrue(TAB_STEPS.all { it.sp >= HOUSE_TYPE_FLOOR_SP })
        val german = listOf("Favoriten", "Letzte Anrufe", "Kontakte", "Tastenfeld")
        val de = fitTabs(room, widths(german), ::px)
        assertFalse(de.wrap)
        assertTrue(need(german, de) <= room)
        val long = listOf("Favoritenliste", "Letzte Anrufe", "Kontaktverzeichnis", "Wähltastenfeld")
        val set = fitTabs(room, widths(long, 1.3f), ::px)
        assertTrue(set.wrap)
        assertEquals(HOUSE_TYPE_FLOOR_SP, set.sp)
    }

    @Test
    fun theRoomAroundTheStonesOnlyGivesWayAfterTheUsualRow() {
        TAB_STEPS.drop(1).zipWithNext().forEach { (a, b) ->
            assertEquals(a.sp, b.sp)
            assertTrue(b.inner < a.inner && b.gap <= a.gap && b.edge <= a.edge)
        }
    }

    @Test
    fun twoLineLabelsKeepTheirLongestWordAndShareTheRest() {
        // Fits: untouched.
        assertEquals(listOf(100, 80), shareTabs(200, listOf(100, 80), listOf(50, 80)))
        // Short by 40: the label that can take two lines gives it up, the one-word label keeps its width.
        assertEquals(listOf(60, 80), shareTabs(140, listOf(100, 80), listOf(50, 80)))
        // Not even the least fits: shared by full width, nothing hidden.
        val s = shareTabs(100, listOf(100, 100), listOf(80, 80))
        assertEquals(listOf(50, 50), s)
        assertTrue(shareTabs(140, listOf(100, 80), listOf(50, 80)).sum() <= 140)
    }
}
