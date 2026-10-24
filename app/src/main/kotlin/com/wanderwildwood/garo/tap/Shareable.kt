package com.wanderwildwood.garo.tap

import java.io.InputStream

/**
 * What is handed over by a tap. Nothing here knows what Gallery is, so this package, the whole
 * of `tap`, can be lifted into another app as it stands. See [TapShare].
 */
interface Shareable {
    /** The name the other phone saves it under. Cleaned before it is sent. */
    val name: String

    /** "image/jpeg" and so on. */
    val mime: String

    /** The exact number of bytes [openStream] gives. */
    val size: Long

    /** A fresh stream each time; the caller closes it. */
    fun openStream(): InputStream
}

/** Some bytes in memory as a [Shareable]. */
class BytesShareable(
    override val name: String,
    override val mime: String,
    private val bytes: ByteArray,
) : Shareable {
    override val size: Long get() = bytes.size.toLong()
    override fun openStream(): InputStream = bytes.inputStream()
}

/** What a tap carries: a file by way of a link, or a short text carried on the tag itself. */
sealed interface Offer {
    class File(val shareable: Shareable) : Offer

    /** A web address or a few words, under [Ndef.MAX_MESSAGE] bytes. No server: the tag holds it. */
    class Text(val text: String) : Offer
}
