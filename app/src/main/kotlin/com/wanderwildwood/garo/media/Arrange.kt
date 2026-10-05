package com.wanderwildwood.garo.media

import java.text.Collator

/** How the folder list is ordered. */
enum class FolderOrder { NEWEST, NAME }

/** How the pictures inside a folder are ordered. */
enum class PictureOrder { NEWEST, OLDEST, NAME }

/**
 * Turning a flat index of pictures into the folders the screens show.
 *
 * Pure, so it is tested without a phone. The ordering mirrors Fossify Gallery's defaults —
 * newest first throughout, names compared the way a person reads them ("IMG_9" before
 * "IMG_10") — with the options Fossify offers pared to the three that change what you see.
 */
object Arrange {

    fun folders(
        pictures: List<Picture>,
        folderOrder: FolderOrder,
        pictureOrder: PictureOrder,
        /** Added to a folder on a card whose name another folder shares, e.g. "card". */
        cardMark: String,
    ): List<Folder> {
        val grouped = pictures.groupBy { it.folderKey }
        val nameCounts = grouped.values.groupingBy { it.first().folderName }.eachCount()

        val folders = grouped.map { (key, inFolder) ->
            val first = inFolder.first()
            Folder(
                key = key,
                label = label(first, clashes = (nameCounts[first.folderName] ?: 0) > 1, cardMark = cardMark),
                pictures = sort(inFolder, pictureOrder),
            )
        }

        return when (folderOrder) {
            FolderOrder.NEWEST -> folders.sortedWith(
                compareByDescending<Folder> { it.newest }.thenComparator { a, b -> natural(a.label, b.label) },
            )
            FolderOrder.NAME -> folders.sortedWith { a, b -> natural(a.label, b.label) }
        }
    }

    fun sort(pictures: List<Picture>, order: PictureOrder): List<Picture> = when (order) {
        // Ties broken by id so two pictures from the same second keep one order between loads,
        // rather than swapping places under the reader's thumb when the list is read again.
        PictureOrder.NEWEST -> pictures.sortedWith(compareByDescending<Picture> { it.`when` }.thenByDescending { it.id })
        PictureOrder.OLDEST -> pictures.sortedWith(compareBy<Picture> { it.`when` }.thenBy { it.id })
        PictureOrder.NAME -> pictures.sortedWith { a, b -> natural(a.name, b.name).takeIf { it != 0 } ?: a.id.compareTo(b.id) }
    }

    /**
     * The folder's own name, and only when another folder shares it, the path that tells the
     * two apart. A card's folder says so rather than repeating a path the reader never chose.
     */
    internal fun label(picture: Picture, clashes: Boolean, cardMark: String): String {
        if (!clashes) return picture.folderName
        val onCard = picture.volume != null && picture.volume != PRIMARY_VOLUME
        val parent = picture.path?.trimEnd('/')?.substringBeforeLast('/', missingDelimiterValue = "")
        return when {
            onCard -> "${picture.folderName} · $cardMark"
            !parent.isNullOrEmpty() -> "${picture.folderName} · $parent"
            else -> picture.folderName
        }
    }

    private val collator: Collator = Collator.getInstance().apply { strength = Collator.SECONDARY }

    /**
     * Compares names with their runs of digits read as numbers, so a camera's IMG_9 comes
     * before its IMG_10. Everything else goes through the locale's collator.
     */
    internal fun natural(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val endA = digitsEnd(a, i)
                val endB = digitsEnd(b, j)
                val numA = a.substring(i, endA).trimStart('0')
                val numB = b.substring(j, endB).trimStart('0')
                val byLength = numA.length.compareTo(numB.length)
                if (byLength != 0) return byLength
                val byDigits = numA.compareTo(numB)
                if (byDigits != 0) return byDigits
                i = endA
                j = endB
            } else {
                val endA = textEnd(a, i)
                val endB = textEnd(b, j)
                val byText = collator.compare(a.substring(i, endA), b.substring(j, endB))
                if (byText != 0) return byText
                i = endA
                j = endB
            }
        }
        return (a.length - i).compareTo(b.length - j)
    }

    private fun digitsEnd(s: String, from: Int): Int {
        var k = from
        while (k < s.length && s[k].isDigit()) k++
        return k
    }

    private fun textEnd(s: String, from: Int): Int {
        var k = from
        while (k < s.length && !s[k].isDigit()) k++
        return k
    }

    const val PRIMARY_VOLUME = "external_primary"
}
