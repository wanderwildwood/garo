package com.wanderwildwood.garo.media

import android.content.Context

/** The three things there are to choose, kept between runs. */
data class Choices(
    val folderOrder: FolderOrder = FolderOrder.NEWEST,
    val pictureOrder: PictureOrder = PictureOrder.NEWEST,
    /** Pictures across a row of the grid. Three is the size at which a face is still a face. */
    val perRow: Int = 3,
) {
    companion object {
        val PER_ROW = listOf(2, 3, 4)
    }
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("garo", Context.MODE_PRIVATE)

    fun read(): Choices = Choices(
        folderOrder = enumOr(prefs.getString(FOLDER_ORDER, null), FolderOrder.NEWEST),
        pictureOrder = enumOr(prefs.getString(PICTURE_ORDER, null), PictureOrder.NEWEST),
        perRow = prefs.getInt(PER_ROW, 3).takeIf { it in Choices.PER_ROW } ?: 3,
    )

    fun write(choices: Choices) {
        prefs.edit()
            .putString(FOLDER_ORDER, choices.folderOrder.name)
            .putString(PICTURE_ORDER, choices.pictureOrder.name)
            .putInt(PER_ROW, choices.perRow)
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fallback

    private companion object {
        const val FOLDER_ORDER = "folder_order"
        const val PICTURE_ORDER = "picture_order"
        const val PER_ROW = "per_row"
    }
}
