package com.wanderwildwood.garo.media

/**
 * Which pictures another app asked for, from the type it named and any it listed beside it.
 *
 * An app that will take any file asks for star-slash-star and then, often, lists what it really
 * wants (Messaging lists pictures and videos). Only pictures are offered here, so a request that
 * cannot take a picture at all offers nothing rather than something it will refuse.
 */
class Wanted(type: String?, extra: List<String>?) {

    private val patterns: List<String> = buildList {
        val listed = extra.orEmpty().map { it.lowercase() }.filter { it.isNotBlank() }
        if (listed.isNotEmpty()) addAll(listed) else add(type?.lowercase() ?: "*/*")
    }.map {
        // The old cursor-style names for "a picture" mean the same as image/*.
        if (it == "vnd.android.cursor.dir/image" || it == "vnd.android.cursor.item/image") "image/*" else it
    }

    /** True if [mime] fits any of the patterns. A picture of unknown type fits only a wide one. */
    fun accepts(mime: String?): Boolean = patterns.any { pattern ->
        when {
            pattern == "*/*" || pattern == "image/*" -> true
            mime == null -> false
            pattern.endsWith("/*") -> mime.lowercase().startsWith(pattern.dropLast(1))
            else -> mime.equals(pattern, ignoreCase = true)
        }
    }
}
