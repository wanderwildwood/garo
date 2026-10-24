package com.wanderwildwood.garo.tap

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class TypeFourReaderTest {
    private val link = "http://192.168.1.20:41234/0123456789abcdef0123456789abcdef"

    private fun readerOver(tag: TypeFourTag, log: MutableList<ByteArray> = mutableListOf()) =
        TypeFourReader { apdu -> log.add(apdu); tag.process(apdu) }

    @Test fun readsTheLinkOffTheTagItself() {
        val tag = TypeFourTag { Ndef.file(Ndef.uriRecord(link)) }
        assertEquals(link, readerOver(tag).readUri())
    }

    @Test fun readsTheWholeMessageInReadsNoBiggerThanTheTagAllows() {
        val message = Ndef.textRecord("x".repeat(700))
        val tag = TypeFourTag { Ndef.file(message) }
        val log = mutableListOf<ByteArray>()
        assertArrayEquals(message, readerOver(tag, log).readMessage())
        // every READ BINARY asks for at most MLe bytes
        log.filter { it[1] == 0xB0.toByte() }.forEach { assertTrue((it[4].toInt() and 0xFF) <= TypeFourTag.MLE) }
    }

    @Test fun usesTheSelectSequenceOfTheSpecification() {
        val log = mutableListOf<ByteArray>()
        readerOver(TypeFourTag { Ndef.file(Ndef.uriRecord(link)) }, log).readUri()
        assertArrayEquals(byteArrayOf(0, 0xA4.toByte(), 4, 0, 7) + TypeFourTag.AID + byteArrayOf(0), log[0])
        assertArrayEquals(byteArrayOf(0, 0xA4.toByte(), 0, 0x0C, 2, 0xE1.toByte(), 0x03), log[1])
        assertArrayEquals(byteArrayOf(0, 0xB0.toByte(), 0, 0, 15), log[2])
        assertArrayEquals(byteArrayOf(0, 0xA4.toByte(), 0, 0x0C, 2, 0xE1.toByte(), 0x04), log[3])
    }

    @Test fun aTagWithNothingToGiveIsNotATag() {
        assertThrows(TypeFourReader.NotATagException::class.java) { readerOver(TypeFourTag { null }).readMessage() }
    }

    @Test fun anEmptyMessageIsRefused() {
        assertThrows(TypeFourReader.NotATagException::class.java) { readerOver(TypeFourTag { Ndef.file(ByteArray(0)) }).readMessage() }
    }

    @Test fun aTagThatAnswersNoStatusIsRefused() {
        assertThrows(TypeFourReader.NotATagException::class.java) { TypeFourReader { ByteArray(0) }.readMessage() }
    }

    @Test fun aLengthLargerThanTheFileAdmitsIsRefused() {
        // NLEN claims 0x0500 bytes; the capability container only admits 0x0400.
        val tag = TypeFourTag { byteArrayOf(0x05, 0x00) + ByteArray(0x500) }
        assertThrows(TypeFourReader.NotATagException::class.java) { readerOver(tag).readMessage() }
    }

    @Test fun aTagThatCutsOutMidMessageIsRefused() {
        var n = 0
        val tag = TypeFourTag { Ndef.file(Ndef.textRecord("y".repeat(300))) }
        val reader = TypeFourReader { apdu -> if (++n > 6) byteArrayOf(0x6A, 0x82.toByte()) else tag.process(apdu) }
        assertThrows(TypeFourReader.NotATagException::class.java) { reader.readMessage() }
    }

    @Test fun aTextTagGivesNoUri() {
        assertNull(readerOver(TypeFourTag { Ndef.file(Ndef.textRecord("hello")) }).readUri())
    }

    @Test fun uriRecordsParseBackWithEveryPrefixWeWrite() {
        for (u in listOf(link, "https://example.org/a", "http://www.example.org/", "https://www.example.org/")) {
            assertEquals(u, Ndef.firstUri(Ndef.uriRecord(u)))
        }
        assertNull(Ndef.firstUri(Ndef.uriRecord("mailto:a@b.c")))
        assertNull(Ndef.firstUri(byteArrayOf(0xD1.toByte(), 1, 5, 'U'.code.toByte(), 3)))
        assertNull(Ndef.firstUri(ByteArray(0)))
    }

    @Test fun aLongUriUsesTheFourByteLengthAndStillParses() {
        val u = "http://10.0.0.1:80/" + "a".repeat(300)
        assertEquals(u, Ndef.firstUri(Ndef.uriRecord(u).also { /* MAX_MESSAGE is 800, this fits */ }))
    }
}
