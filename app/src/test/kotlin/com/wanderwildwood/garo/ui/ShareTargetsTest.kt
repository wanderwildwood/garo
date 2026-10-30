package com.wanderwildwood.garo.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ShareTargetsTest {
    private fun t(label: String, pkg: String, cls: String = "A") = ShareTarget(label, pkg, cls)

    @Test
    fun `apps are ordered by label ignoring case`() {
        val out = ShareTargets.ordered(listOf(t("zebra", "z"), t("Bravo", "b"), t("alpha", "a")), "self")
        assertEquals(listOf("alpha", "Bravo", "zebra"), out.map { it.label })
    }

    @Test
    fun `gallery itself is left out`() {
        val out = ShareTargets.ordered(listOf(t("Gallery", "self"), t("Mail", "m")), "self")
        assertEquals(listOf("Mail"), out.map { it.label })
    }

    @Test
    fun `the same component is listed once`() {
        val out = ShareTargets.ordered(listOf(t("Mail", "m"), t("Mail", "m")), "self")
        assertEquals(1, out.size)
    }

    @Test
    fun `one file is a SEND of its own type`() {
        assertEquals(SendPlan(ShareTargets.SEND, "image/png"), ShareTargets.plan(listOf("image/png")))
    }

    @Test
    fun `an unknown type falls back to image`() {
        assertEquals(SendPlan(ShareTargets.SEND, "image/*"), ShareTargets.plan(listOf(null)))
    }

    @Test
    fun `many files are SEND_MULTIPLE with the common type, family or any`() {
        assertEquals(SendPlan(ShareTargets.SEND_MULTIPLE, "image/jpeg"), ShareTargets.plan(listOf("image/jpeg", "image/jpeg")))
        assertEquals(SendPlan(ShareTargets.SEND_MULTIPLE, "image/*"), ShareTargets.plan(listOf("image/jpeg", "image/png")))
        assertEquals(SendPlan(ShareTargets.SEND_MULTIPLE, "*/*"), ShareTargets.plan(listOf("image/jpeg", "video/mp4")))
    }
}
