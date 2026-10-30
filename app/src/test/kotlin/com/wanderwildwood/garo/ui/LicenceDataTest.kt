package com.wanderwildwood.garo.ui

import com.wanderwildwood.garo.backup.Secrets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LicenceDataTest {

    @Test
    fun `every library has a name, a licence and a web address`() {
        assertTrue(LIBRARIES.isNotEmpty())
        LIBRARIES.forEach {
            assertTrue(it.name, it.name.isNotBlank() && it.licence.isNotBlank())
            assertTrue(it.link, it.link.startsWith("https://"))
        }
    }

    @Test
    fun `the list names what the build file pulls in`() {
        val all = LIBRARIES.joinToString(" ") { it.name }
        listOf("Fossify", "MMD", "Lato", "Compose", "OkHttp", "ZXing").forEach { assertTrue(it, all.contains(it)) }
    }

    @Test
    fun `the source link is the public repository`() {
        assertEquals("https://github.com/wanderwildwood/garo", SOURCE_URL)
    }

    @Test
    fun `licence text is cut into paragraphs by blank lines`() {
        val text = "First line\nsecond line\n\n  \nSecond paragraph\r\n\r\nThird"
        assertEquals(listOf("First line second line", "Second paragraph", "Third"), licenceParagraphs(text))
    }

    @Test
    fun `a sealed value is told from plain text`() {
        assertTrue(Secrets.isSealed("enc:v1:AAAA"))
        assertFalse(Secrets.isSealed("abc123"))
        assertFalse(Secrets.isSealed(null))
    }
}
