package com.wanderwildwood.garo.ui

import android.content.Intent
import com.wanderwildwood.garo.media.MediaIndex
import com.wanderwildwood.garo.media.Picture

/** Asks Files (tana) to open a folder with one file already selected. */
object ShowInFiles {
    const val ACTION = "com.wanderwildwood.tana.action.SHOW_FILE"
    const val PACKAGE = "com.wanderwildwood.tana"
    const val EXTRA_PATH = "path"

    /**
     * The absolute path of the picture, from the index when it knows one, else from Files' own
     * address for a file Files handed over. Null when no path can be told.
     */
    fun path(picture: Picture): String? =
        picture.filePath ?: picture.handed?.let { MediaIndex.tanaPath(it) }

    fun intent(path: String): Intent =
        Intent(ACTION).setPackage(PACKAGE).putExtra(EXTRA_PATH, path)
}
