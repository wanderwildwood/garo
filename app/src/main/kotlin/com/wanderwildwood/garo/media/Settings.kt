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

/** Where an Immich server is, and the key it was given. */
data class ImmichLogin(val server: String, val key: String)

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

    // The key is kept in this app's own preferences, which no other app can read. It is not
    // encrypted further: anything able to read this app's private files could equally read it
    // out of memory, and Android's encrypted preferences are withdrawn.
    fun immich(): ImmichLogin? {
        val server = prefs.getString(IMMICH_SERVER, null)?.takeIf { it.isNotBlank() } ?: return null
        val key = prefs.getString(IMMICH_KEY, null)?.takeIf { it.isNotBlank() } ?: return null
        return ImmichLogin(server, key)
    }

    fun immichServer(): String? = prefs.getString(IMMICH_SERVER, null)?.takeIf { it.isNotBlank() }
    /** The saved key, for the key dialog to open with — hidden there until the eye is pressed. */
    fun immichKey(): String? = prefs.getString(IMMICH_KEY, null)?.takeIf { it.isNotBlank() }
    fun hasImmichKey(): Boolean = !prefs.getString(IMMICH_KEY, null).isNullOrBlank()

    fun writeImmichServer(server: String?) = prefs.edit().putString(IMMICH_SERVER, server?.trim()).apply()
    fun writeImmichKey(key: String?) = prefs.edit().putString(IMMICH_KEY, key?.trim()).apply()

    fun backupOn(): Boolean = prefs.getBoolean(BACKUP_ON, false)
    fun backupWhen(): BackupWhen = enumOr(prefs.getString(BACKUP_WHEN, null), BackupWhen.WIFI_CHARGING)
    fun writeBackupOn(on: Boolean) = prefs.edit().putBoolean(BACKUP_ON, on).apply()
    fun writeBackupWhen(w: BackupWhen) = prefs.edit().putString(BACKUP_WHEN, w.name).apply()

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fallback

    private companion object {
        const val FOLDER_ORDER = "folder_order"
        const val PICTURE_ORDER = "picture_order"
        const val PER_ROW = "per_row"
        const val IMMICH_SERVER = "immich_server"
        const val IMMICH_KEY = "immich_key"
        const val BACKUP_ON = "backup_on"
        const val BACKUP_WHEN = "backup_when"
    }
}
