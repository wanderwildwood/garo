package com.wanderwildwood.garo.tap

import java.io.IOException

/**
 * The other side of [TypeFourTag]: asks an NFC Forum Type 4 tag for its NDEF message, one command
 * at a time. Plain Kotlin, nothing from Android; [transceive] is whatever carries a command to
 * the tag and brings back its answer (on a phone, `IsoDep.transceive`).
 *
 * The sequence is the one the specification gives:
 *
 *  1. SELECT the NDEF Tag Application by name, AID D2760000850101
 *  2. SELECT the capability container E103 and READ its 15 bytes
 *  3. SELECT the NDEF file the container names (E104) and READ NLEN, then the message
 *
 * Nothing the tag says is trusted past what is checked here: the container must be well formed,
 * the file must be one this reader is willing to read, and no read asks for more than the
 * container's MLe allows.
 */
class TypeFourReader(private val transceive: (ByteArray) -> ByteArray) {

    /** The tag did not answer as a Type 4 tag holding a message should. */
    class NotATagException(message: String) : IOException(message)

    /** The NDEF message on the tag, or throws [NotATagException] (or whatever [transceive] throws). */
    fun readMessage(): ByteArray {
        ok(transceive(selectApplication()), "application")
        ok(transceive(selectFile(TypeFourTag.FILE_CC)), "capability container")
        val cc = data(transceive(read(0, 15)), "capability container")
        if (cc.size != 15) throw NotATagException("capability container is ${cc.size} bytes")
        val ccLen = u16(cc, 0)
        if (ccLen < 15) throw NotATagException("capability container length $ccLen")
        // A mapping version of 2.x; 1.x tags exist but speak a different select.
        if ((cc[2].toInt() and 0xF0) != 0x20) throw NotATagException("mapping version")
        val mle = u16(cc, 3).coerceIn(1, MAX_READ)
        // The NDEF file control TLV: tag 04, length 06, file id, maximum size, read access, write access.
        if (cc[7].toInt() != 0x04 || cc[8].toInt() != 0x06) throw NotATagException("no NDEF file in the container")
        val fileId = u16(cc, 9)
        val maxSize = u16(cc, 11)
        if ((cc[13].toInt() and 0xFF) != 0x00) throw NotATagException("the NDEF file is not readable")
        if (maxSize < 3) throw NotATagException("NDEF file too small")

        ok(transceive(selectFile(fileId)), "NDEF file")
        val nlen = u16(data(transceive(read(0, 2)), "NDEF length").also { if (it.size != 2) throw NotATagException("NLEN") }, 0)
        if (nlen == 0) throw NotATagException("the tag is empty")
        if (nlen > maxSize - 2 || nlen > MAX_MESSAGE) throw NotATagException("message too long: $nlen")

        val out = ByteArray(nlen)
        var got = 0
        while (got < nlen) {
            val want = minOf(mle, nlen - got)
            val chunk = data(transceive(read(2 + got, want)), "NDEF message")
            if (chunk.isEmpty() || chunk.size > want) throw NotATagException("a read gave ${chunk.size} bytes of $want")
            chunk.copyInto(out, got)
            got += chunk.size
        }
        return out
    }

    /** The URI the tag carries, or null when its message does not begin with a URI record. */
    fun readUri(): String? = Ndef.firstUri(readMessage())

    private fun selectApplication() =
        byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, TypeFourTag.AID.size.toByte()) + TypeFourTag.AID + byteArrayOf(0x00)

    private fun selectFile(id: Int) =
        byteArrayOf(0x00, 0xA4.toByte(), 0x00, 0x0C, 0x02, (id ushr 8).toByte(), id.toByte())

    private fun read(offset: Int, le: Int) =
        byteArrayOf(0x00, 0xB0.toByte(), (offset ushr 8).toByte(), offset.toByte(), le.toByte())

    private fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun status(r: ByteArray): Int =
        if (r.size < 2) throw NotATagException("no status") else u16(r, r.size - 2)

    private fun ok(r: ByteArray, what: String) {
        if (status(r) != TypeFourTag.OK) throw NotATagException("$what: status %04X".format(status(r)))
    }

    private fun data(r: ByteArray, what: String): ByteArray {
        ok(r, what)
        return r.copyOfRange(0, r.size - 2)
    }

    companion object {
        /** Biggest message this reader takes: the sender's own limit. */
        const val MAX_MESSAGE = Ndef.MAX_MESSAGE

        /** Biggest single read asked for, whatever the tag allows (one length byte). */
        private const val MAX_READ = 0xFF
    }
}
