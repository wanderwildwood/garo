package com.wanderwildwood.garo.tap

/**
 * NDEF messages of one record: a URI, or a short text. Plain Kotlin, nothing from Android.
 *
 * A record is built short (one length byte) when the payload is 255 bytes or fewer and normal
 * (four length bytes) above that. [file] wraps a message the way a Type 4 tag stores it: two
 * bytes of length, then the message.
 */
object Ndef {
    /** The most a tag carries here. A URL is about 60 bytes; a short text may be longer. */
    const val MAX_MESSAGE = 800

    /** NFC Forum URI record prefix codes, longest first so the longest match wins. */
    private val PREFIXES = listOf(
        "http://www." to 0x01,
        "https://www." to 0x02,
        "https://" to 0x04,
        "http://" to 0x03,
        "mailto:" to 0x06,
        "tel:" to 0x05,
    )

    fun uriRecord(uri: String): ByteArray {
        val (prefix, code) = PREFIXES.firstOrNull { uri.startsWith(it.first) } ?: ("" to 0x00)
        val payload = byteArrayOf(code.toByte()) + uri.removePrefix(prefix).toByteArray(Charsets.UTF_8)
        return record(type = byteArrayOf('U'.code.toByte()), payload = payload)
    }

    /** A Well Known Text record, UTF-8, language "en". */
    fun textRecord(text: String, language: String = "en"): ByteArray {
        val lang = language.toByteArray(Charsets.US_ASCII)
        require(lang.size in 1..63)
        val payload = byteArrayOf(lang.size.toByte()) + lang + text.toByteArray(Charsets.UTF_8)
        return record(type = byteArrayOf('T'.code.toByte()), payload = payload)
    }

    /** What to put on the tag for a short text: a URI record if it is a web address, else text. */
    fun forText(text: String): ByteArray =
        if (text.startsWith("http://") || text.startsWith("https://")) uriRecord(text) else textRecord(text)

    /** A single record that is both first and last in its message. */
    private fun record(type: ByteArray, payload: ByteArray): ByteArray {
        val short = payload.size <= 255
        // MB, ME, SR (when short), TNF 001 (well known).
        val header = (0x80 or 0x40 or (if (short) 0x10 else 0) or 0x01)
        val length = if (short) {
            byteArrayOf(payload.size.toByte())
        } else {
            byteArrayOf((payload.size ushr 24).toByte(), (payload.size ushr 16).toByte(), (payload.size ushr 8).toByte(), payload.size.toByte())
        }
        val message = byteArrayOf(header.toByte(), type.size.toByte()) + length + type + payload
        require(message.size <= MAX_MESSAGE) { "too long for the tag" }
        return message
    }

    /** The NDEF file of a Type 4 tag: NLEN, then the message. */
    fun file(message: ByteArray): ByteArray =
        byteArrayOf((message.size ushr 8).toByte(), message.size.toByte()) + message

    /**
     * The URI of the first record of [message] when that is a Well Known URI record, else null.
     * Only the prefixes a web address can have are understood; a record with any other prefix
     * code, a truncated record or a chunked one gives null rather than a guess.
     */
    fun firstUri(message: ByteArray): String? {
        if (message.size < 3) return null
        val header = message[0].toInt() and 0xFF
        val tnf = header and 0x07
        val shortRecord = header and 0x10 != 0
        val hasId = header and 0x08 != 0
        val chunked = header and 0x20 != 0
        if (tnf != 0x01 || chunked || header and 0x80 == 0) return null
        var at = 1
        val typeLength = message[at++].toInt() and 0xFF
        val payloadLength: Long
        if (shortRecord) {
            if (at >= message.size) return null
            payloadLength = (message[at++].toLong() and 0xFF)
        } else {
            if (at + 4 > message.size) return null
            payloadLength = ((message[at].toLong() and 0xFF) shl 24) or ((message[at + 1].toLong() and 0xFF) shl 16) or
                ((message[at + 2].toLong() and 0xFF) shl 8) or (message[at + 3].toLong() and 0xFF)
            at += 4
        }
        val idLength = if (hasId) { if (at >= message.size) return null; message[at++].toInt() and 0xFF } else 0
        if (typeLength != 1 || at + typeLength + idLength + payloadLength > message.size || payloadLength < 1) return null
        if (message[at].toInt() != 'U'.code) return null
        at += typeLength + idLength
        val prefix = when (message[at].toInt() and 0xFF) {
            0x00 -> ""
            0x01 -> "http://www."
            0x02 -> "https://www."
            0x03 -> "http://"
            0x04 -> "https://"
            else -> return null
        }
        return prefix + String(message, at + 1, (payloadLength - 1).toInt(), Charsets.UTF_8)
    }
}
