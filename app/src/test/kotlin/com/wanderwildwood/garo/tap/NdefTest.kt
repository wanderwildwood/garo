package com.wanderwildwood.garo.tap

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NdefTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun httpUriUsesPrefixCode03AndAShortRecord() {
        val rest = "192.168.1.5:4000/ab"
        val expected = b(0xD1, 0x01, rest.length + 1, 0x55, 0x03) + rest.toByteArray()
        assertArrayEquals(expected, Ndef.uriRecord("http://192.168.1.5:4000/ab"))
    }

    @Test fun prefixCodes() {
        fun code(uri: String) = Ndef.uriRecord(uri)[4].toInt()
        assertEquals(0x01, code("http://www.example.org/a"))
        assertEquals(0x02, code("https://www.example.org/a"))
        assertEquals(0x03, code("http://10.0.0.2:80/a"))
        assertEquals(0x04, code("https://example.org/a"))
        assertEquals(0x00, code("ftp://example.org/a"))
        // the longest prefix wins, and what follows it is what is left
        assertArrayEquals("example.org/a".toByteArray(), Ndef.uriRecord("http://www.example.org/a").copyOfRange(5, 5 + 13))
    }

    @Test fun fileIsLengthThenMessage() {
        val m = Ndef.uriRecord("http://10.0.0.2:5/x")
        val f = Ndef.file(m)
        assertEquals(m.size, ((f[0].toInt() and 0xFF) shl 8) or (f[1].toInt() and 0xFF))
        assertArrayEquals(m, f.copyOfRange(2, f.size))
    }

    @Test fun textRecordCarriesLanguageAndText() {
        val r = Ndef.textRecord("hello")
        assertArrayEquals(b(0xD1, 0x01, 1 + 2 + 5, 0x54, 0x02) + "enhello".toByteArray(), r)
    }

    @Test fun webAddressTextBecomesAUriRecord() {
        assertEquals(0x55, Ndef.forText("https://example.org/x")[3].toInt())
        assertEquals(0x54, Ndef.forText("meet at the gate")[3].toInt())
    }

    @Test fun aLongTextUsesTheFourByteLength() {
        val text = "a".repeat(600)
        val r = Ndef.textRecord(text)
        // MB ME TNF1, no SR; one type byte; length 603 in four bytes.
        assertEquals(0xC1, r[0].toInt() and 0xFF)
        assertEquals(1, r[1].toInt())
        assertArrayEquals(b(0, 0, 0x02, 0x5B), r.copyOfRange(2, 6))
        assertEquals(0x54, r[6].toInt())
        assertEquals(7 + 603, r.size)
    }

    @Test fun aTextThatIsTooLongIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { Ndef.textRecord("a".repeat(Ndef.MAX_MESSAGE)) }
    }
}
