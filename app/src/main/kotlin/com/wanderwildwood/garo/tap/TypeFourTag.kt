package com.wanderwildwood.garo.tap

/**
 * An NFC Forum Type 4 tag, answered one command at a time. Plain Kotlin, nothing from Android,
 * so it is tested with made-up command sequences.
 *
 * It speaks the NDEF Tag Application of mapping version 2.0 and only that:
 *
 *  1. SELECT by name, AID D2760000850101
 *  2. SELECT file E103 (the capability container), READ BINARY of its 15 bytes
 *  3. SELECT file E104 (the NDEF file), READ BINARY of NLEN and then the message
 *
 * It is read-only: the capability container says writing is refused, and there is no UPDATE
 * BINARY. Every other command gets the status word ISO 7816 gives for it.
 *
 * [ndefFile] gives the NDEF file (NLEN + message), or null when there is nothing to hand out,
 * in which case the file is "not found". It is read when the file is selected, so one reader
 * sees one consistent file even if it changes meanwhile.
 */
class TypeFourTag(private val ndefFile: () -> ByteArray?) {

    private var applicationSelected = false
    private var selected: ByteArray? = null

    /** Back to nothing selected: the reader went away. */
    fun reset() {
        applicationSelected = false
        selected = null
    }

    fun process(apdu: ByteArray): ByteArray {
        if (apdu.size < 4) return sw(WRONG_LENGTH)
        if (apdu[0].toInt() != 0x00) return sw(CLA_NOT_SUPPORTED)
        return when (apdu[1].toInt() and 0xFF) {
            INS_SELECT -> select(apdu)
            INS_READ_BINARY -> readBinary(apdu)
            else -> sw(INS_NOT_SUPPORTED)
        }
    }

    private fun select(apdu: ByteArray): ByteArray {
        val p1 = apdu[2].toInt() and 0xFF
        val p2 = apdu[3].toInt() and 0xFF
        if (apdu.size < 5) return sw(WRONG_LENGTH)
        val lc = apdu[4].toInt() and 0xFF
        // Lc data, and then at most the one Le byte.
        if (apdu.size != 5 + lc && apdu.size != 6 + lc) return sw(WRONG_LENGTH)
        val data = apdu.copyOfRange(5, 5 + lc)
        // 0x0C: no data back. 0x00: the first record back, which this tag does not give.
        if (p2 != 0x0C && p2 != 0x00) return sw(WRONG_P1P2)

        return when (p1) {
            0x04 -> {
                selected = null
                applicationSelected = data.contentEquals(AID)
                if (applicationSelected) sw(OK) else sw(NOT_FOUND)
            }
            0x00 -> {
                if (!applicationSelected) return sw(NOT_FOUND)
                if (lc != 2) return sw(WRONG_DATA)
                val id = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                when (id) {
                    FILE_CC -> { selected = CAPABILITY_CONTAINER; sw(OK) }
                    FILE_NDEF -> {
                        val file = ndefFile()
                        if (file == null) {
                            selected = null
                            sw(NOT_FOUND)
                        } else {
                            selected = file
                            sw(OK)
                        }
                    }
                    else -> { selected = null; sw(NOT_FOUND) }
                }
            }
            else -> sw(WRONG_P1P2)
        }
    }

    private fun readBinary(apdu: ByteArray): ByteArray {
        if (apdu.size != 5) return sw(WRONG_LENGTH)
        val p1 = apdu[2].toInt() and 0xFF
        // Bit 8 of P1 would select a file by short id; this tag has only the two selected above.
        if (p1 and 0x80 != 0) return sw(WRONG_PARAMETERS)
        val offset = (p1 shl 8) or (apdu[3].toInt() and 0xFF)
        val file = selected ?: return sw(CONDITIONS)
        if (offset > file.size) return sw(WRONG_PARAMETERS)
        var le = apdu[4].toInt() and 0xFF
        if (le == 0) le = 256
        val end = minOf(file.size, offset + le)
        return file.copyOfRange(offset, end) + sw(OK)
    }

    companion object {
        val AID = byteArrayOf(0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01)
        const val FILE_CC = 0xE103
        const val FILE_NDEF = 0xE104

        /** Most bytes a reader may ask for in one READ BINARY, as the capability container says. */
        const val MLE = 0x003B

        /** Biggest NDEF file the capability container admits to. */
        const val MAX_NDEF_FILE = 0x0400

        val CAPABILITY_CONTAINER: ByteArray = byteArrayOf(
            0x00, 0x0F,                                 // CCLEN
            0x20,                                       // mapping version 2.0
            (MLE ushr 8).toByte(), MLE.toByte(),        // MLe
            0x00, 0x34,                                 // MLc
            0x04, 0x06,                                 // NDEF file control TLV
            0xE1.toByte(), 0x04,                        // file id
            (MAX_NDEF_FILE ushr 8).toByte(), MAX_NDEF_FILE.toByte(),
            0x00,                                       // read: free
            0xFF.toByte(),                              // write: never
        )

        private const val INS_SELECT = 0xA4
        private const val INS_READ_BINARY = 0xB0

        const val OK = 0x9000
        const val WRONG_LENGTH = 0x6700
        const val CONDITIONS = 0x6985
        const val WRONG_DATA = 0x6A80
        const val NOT_FOUND = 0x6A82
        const val WRONG_P1P2 = 0x6A86
        const val WRONG_PARAMETERS = 0x6B00
        const val INS_NOT_SUPPORTED = 0x6D00
        const val CLA_NOT_SUPPORTED = 0x6E00

        private fun sw(code: Int) = byteArrayOf((code ushr 8).toByte(), code.toByte())
    }
}
