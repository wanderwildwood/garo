package com.wanderwildwood.garo.media

/** A video's length or place, the way a player shows it: 0:42, 3:07, 1:02:15. */
object Clock {
    fun format(ms: Long): String {
        val total = (ms.coerceAtLeast(0) + 500) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
