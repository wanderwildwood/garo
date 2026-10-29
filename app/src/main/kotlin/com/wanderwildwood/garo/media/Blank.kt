package com.wanderwildwood.garo.media

/** Whether a picture shows nothing: sampled pixels that are all clear, or all one colour. */
object Blank {
    fun isBlank(pixels: IntArray): Boolean {
        if (pixels.isEmpty()) return true
        if (pixels.all { (it ushr 24) == 0 }) return true
        // A clear pixel is the same as white on this panel's white page.
        val seen = pixels.map { if ((it ushr 24) == 0) WHITE else it or (0xFF shl 24) }.toSet()
        return seen.size == 1
    }

    private const val WHITE = 0xFFFFFFFF.toInt()
}
