package com.wanderwildwood.garo.edit

/**
 * What has been done to a picture, kept as instructions rather than as pixels, so nothing is
 * lost until it is saved and the original is never touched.
 *
 * Everything is in fractions of the *turned* picture — after its quarter turns, mirroring and
 * straightening, before the crop — so a crop can be changed after drawing without the marks
 * moving off what they were drawn on. Turning or mirroring carries the crop and the marks
 * round with the picture ([turnedRight], [mirrored]).
 */
data class Edits(
    /** Quarter turns clockwise, 0 to 3. */
    val quarter: Int = 0,
    /** Mirrored left to right, after turning. */
    val mirror: Boolean = false,
    /** Degrees, a little either way, to level a horizon; the corners are filled by zooming in. */
    val straighten: Int = 0,
    val crop: Frame = Frame.ALL,
    /** Pen lines and words, oldest first; Undo takes the last off. */
    val marks: List<Mark> = emptyList(),
    /** The longest side in pixels when saved, or 0 for as it is. */
    val longSide: Int = 0,
) {
    val untouched: Boolean get() = this == Edits()

    fun turnedRight(): Edits = copy(
        quarter = (quarter + 1) % 4,
        crop = Frame(1 - crop.bottom, crop.left, 1 - crop.top, crop.right),
        marks = marks.map { it.moved { (x, y) -> Dot(1 - y, x) } },
    )

    fun mirrored(): Edits = copy(
        mirror = !mirror,
        crop = Frame(1 - crop.right, crop.top, 1 - crop.left, crop.bottom),
        marks = marks.map { it.moved { (x, y) -> Dot(1 - x, y) } },
    )

    companion object {
        const val MOST_STRAIGHTEN = 15
    }
}

/** A rectangle in fractions of the turned picture, 0 to 1 on each side. */
data class Frame(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top

    companion object {
        val ALL = Frame(0f, 0f, 1f, 1f)

        /**
         * The largest frame of [ratio] (width over height, in pixels) that fits a picture of
         * [w] by [h] pixels, centred.
         */
        fun largest(ratio: Float, w: Int, h: Int): Frame {
            val picture = w.toFloat() / h
            return if (ratio >= picture) {
                val tall = picture / ratio
                Frame(0f, (1 - tall) / 2, 1f, (1 + tall) / 2)
            } else {
                val wide = ratio / picture
                Frame((1 - wide) / 2, 0f, (1 + wide) / 2, 1f)
            }
        }
    }
}

data class Dot(val x: Float, val y: Float)

sealed interface Mark {
    /** Ink: black, or white for writing over something dark. */
    val white: Boolean

    fun moved(f: (Dot) -> Dot): Mark

    /** A line drawn with a finger; [width] is a fraction of the picture's shorter side. */
    data class Line(val dots: List<Dot>, val width: Float, override val white: Boolean) : Mark {
        override fun moved(f: (Dot) -> Dot) = copy(dots = dots.map(f))
    }

    /** Words placed with a tap; [at] is where the words start, [size] a fraction of the shorter side. */
    data class Words(val text: String, val at: Dot, val size: Float, override val white: Boolean) : Mark {
        override fun moved(f: (Dot) -> Dot) = copy(at = f(at))
    }
}
