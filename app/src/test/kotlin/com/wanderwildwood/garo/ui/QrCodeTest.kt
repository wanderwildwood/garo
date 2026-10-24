package com.wanderwildwood.garo.ui

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeTest {
    /** Paints [modules] as black on white with the quiet zone and [scale] pixels a module, as QrCode draws it. */
    private fun render(modules: List<BooleanArray>, scale: Int): Triple<IntArray, Int, Int> {
        val n = modules.size + 2 * QR_QUIET
        val side = n * scale
        val pixels = IntArray(side * side) { 0xFFFFFFFF.toInt() }
        for (y in modules.indices) for (x in modules[y].indices) if (modules[y][x]) {
            for (dy in 0 until scale) for (dx in 0 until scale) {
                pixels[((y + QR_QUIET) * scale + dy) * side + (x + QR_QUIET) * scale + dx] = 0xFF000000.toInt()
            }
        }
        return Triple(pixels, side, side)
    }

    private fun decode(text: String, scale: Int = 6): String {
        val modules = qrModules(text)
        assertNotNull(modules)
        val (px, w, h) = render(modules!!, scale)
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, h, px)))
        return QRCodeReader().decode(bitmap).text
    }

    @Test fun theShareLinkComesBackOutOfItsCode() {
        val link = "http://192.168.1.20:41234/0123456789abcdef0123456789abcdef"
        assertEquals(link, decode(link))
    }

    @Test fun otherLinksOfTheSameShapeToo() {
        for (l in listOf("http://10.0.0.5:1025/ffffffffffffffffffffffffffffffff", "http://172.31.255.254:65535/00000000000000000000000000000000", "http://169.254.7.7:8080/a1b2c3d4e5f60718293a4b5c6d7e8f90")) {
            assertEquals(l, decode(l, scale = 4))
        }
    }

    @Test fun aLinkIsASmallCodeSoItCanBeBig() {
        // 58 characters need version 4 (33 modules) at the low error level: a big module on a small screen.
        assertEquals(33, qrModules("http://192.168.1.20:41234/0123456789abcdef0123456789abcdef")!!.size)
    }

    @Test fun theCodeIsSquareAndHasItsFinderCorners() {
        val m = qrModules("http://192.168.1.20:41234/0123456789abcdef0123456789abcdef")!!
        assertTrue(m.all { it.size == m.size })
        // top-left finder pattern: a black 7x7 ring with a black 3x3 centre
        assertTrue((0..6).all { m[0][it] && m[6][it] && m[it][0] && m[it][6] })
        assertTrue(m[3][3] && !m[1][1])
    }

    @Test fun textThatCannotFitGivesNoCode() {
        assertEquals(null, qrModules("x".repeat(5000)))
    }
}
