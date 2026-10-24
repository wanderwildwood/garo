package com.wanderwildwood.garo.tap

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TypeFourTagTest {
    private val message = Ndef.uriRecord("http://192.168.1.20:41234/0123456789abcdef0123456789abcdef")
    private val file = Ndef.file(message)
    private val tag = TypeFourTag { file }

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun sw(r: ByteArray) = ((r[r.size - 2].toInt() and 0xFF) shl 8) or (r.last().toInt() and 0xFF)
    private fun data(r: ByteArray) = r.copyOfRange(0, r.size - 2)

    private val selectApp = b(0x00, 0xA4, 0x04, 0x00, 0x07, 0xD2, 0x76, 0x00, 0x00, 0x85, 0x01, 0x01, 0x00)
    private fun selectFile(id: Int, p2: Int = 0x0C) = b(0x00, 0xA4, 0x00, p2, 0x02, id shr 8, id and 0xFF)
    private fun read(offset: Int, le: Int) = b(0x00, 0xB0, offset shr 8, offset and 0xFF, le)

    @Test fun theFullSequenceReadsTheMessage() {
        assertEquals(0x9000, sw(tag.process(selectApp)))
        assertEquals(0x9000, sw(tag.process(selectFile(0xE103))))
        val cc = tag.process(read(0, 0x0F))
        assertEquals(0x9000, sw(cc))
        assertArrayEquals(TypeFourTag.CAPABILITY_CONTAINER, data(cc))
        // CC: length 15, version 2.0, NDEF file E104, readable, never writable.
        assertEquals(0x000F, ((cc[0].toInt() and 0xFF) shl 8) or (cc[1].toInt() and 0xFF))
        assertEquals(0x20, cc[2].toInt())
        assertEquals(0xE1, cc[9].toInt() and 0xFF)
        assertEquals(0x04, cc[10].toInt())
        assertEquals(0x00, cc[13].toInt())
        assertEquals(0xFF, cc[14].toInt() and 0xFF)

        assertEquals(0x9000, sw(tag.process(selectFile(0xE104))))
        val nlen = tag.process(read(0, 2))
        assertEquals(message.size, ((nlen[0].toInt() and 0xFF) shl 8) or (nlen[1].toInt() and 0xFF))
        // and the rest, in reads no bigger than MLe, exactly as a reader does it
        val got = java.io.ByteArrayOutputStream()
        var at = 2
        while (at < file.size) {
            val r = tag.process(read(at, minOf(TypeFourTag.MLE, file.size - at)))
            assertEquals(0x9000, sw(r))
            got.write(data(r))
            at += data(r).size
        }
        assertArrayEquals(message, got.toByteArray())
    }

    @Test fun theApplicationCanBeSelectedWithoutALeByte() {
        assertEquals(0x9000, sw(tag.process(selectApp.copyOf(selectApp.size - 1))))
    }

    @Test fun aWrongApplicationIsNotFound() {
        val wrong = selectApp.copyOf().also { it[11] = 0x02 }
        assertEquals(0x6A82, sw(tag.process(wrong)))
        // and nothing under it answers afterwards
        assertEquals(0x6A82, sw(tag.process(selectFile(0xE103))))
    }

    @Test fun filesCannotBeSelectedBeforeTheApplication() {
        assertEquals(0x6A82, sw(tag.process(selectFile(0xE103))))
    }

    @Test fun anUnknownFileIsNotFound() {
        tag.process(selectApp)
        assertEquals(0x6A82, sw(tag.process(selectFile(0xE105))))
        assertEquals(0x6985, sw(tag.process(read(0, 2))))
    }

    @Test fun readingWithNothingSelectedIsRefused() {
        tag.process(selectApp)
        assertEquals(0x6985, sw(tag.process(read(0, 2))))
    }

    @Test fun anOffsetPastTheEndIsAWrongParameter() {
        tag.process(selectApp); tag.process(selectFile(0xE104))
        assertEquals(0x6B00, sw(tag.process(read(file.size + 1, 2))))
        assertEquals(0x6B00, sw(tag.process(b(0x00, 0xB0, 0x80, 0x00, 0x02)))) // short-id form
        // at the very end: nothing to give, but not an error
        assertEquals(0x9000, sw(tag.process(read(file.size, 2))))
        assertEquals(2, tag.process(read(file.size, 2)).size)
    }

    @Test fun aReadPastTheEndGivesWhatThereIs() {
        tag.process(selectApp); tag.process(selectFile(0xE104))
        val r = tag.process(read(file.size - 3, 0x20))
        assertEquals(3, data(r).size)
        assertEquals(0x9000, sw(r))
    }

    @Test fun leOfZeroMeansTwoHundredFiftySix() {
        tag.process(selectApp); tag.process(selectFile(0xE103))
        assertEquals(15, data(tag.process(read(0, 0))).size)
    }

    @Test fun anNdefFileThatIsNotThereIsNotFound() {
        val empty = TypeFourTag { null }
        empty.process(selectApp)
        assertEquals(0x6A82, sw(empty.process(selectFile(0xE104))))
        assertEquals(0x9000, sw(empty.process(selectFile(0xE103))))
    }

    @Test fun badCommandsGetTheirStatusWords() {
        assertEquals(0x6E00, sw(tag.process(b(0x80, 0xA4, 0x04, 0x00, 0x00))))
        assertEquals(0x6D00, sw(tag.process(b(0x00, 0xD6, 0x00, 0x00, 0x01, 0x00)))) // UPDATE BINARY
        assertEquals(0x6D00, sw(tag.process(b(0x00, 0xCA, 0x00, 0x00, 0x00))))
        assertEquals(0x6700, sw(tag.process(b(0x00, 0xA4))))
        assertEquals(0x6700, sw(tag.process(b(0x00, 0xA4, 0x04, 0x00))))
        assertEquals(0x6700, sw(tag.process(b(0x00, 0xA4, 0x04, 0x00, 0x07, 0xD2))))
        assertEquals(0x6700, sw(tag.process(b(0x00, 0xB0, 0x00, 0x00))))
        assertEquals(0x6A86, sw(tag.process(b(0x00, 0xA4, 0x02, 0x0C, 0x02, 0xE1, 0x03))))
        assertEquals(0x6A86, sw(tag.process(b(0x00, 0xA4, 0x04, 0x07, 0x00))))
        tag.process(selectApp)
        assertEquals(0x6A80, sw(tag.process(b(0x00, 0xA4, 0x00, 0x0C, 0x01, 0xE1))))
    }

    @Test fun resetForgetsWhatWasSelected() {
        tag.process(selectApp); tag.process(selectFile(0xE104))
        tag.reset()
        assertEquals(0x6985, sw(tag.process(read(0, 2))))
        assertEquals(0x6A82, sw(tag.process(selectFile(0xE104))))
    }

    @Test fun theFileIsFixedWhenSelected() {
        var current: ByteArray? = file
        val t = TypeFourTag { current }
        t.process(selectApp); t.process(selectFile(0xE104))
        current = null
        assertEquals(0x9000, sw(t.process(read(0, 2))))
    }
}
