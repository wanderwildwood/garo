package com.wanderwildwood.garo.media

import android.content.Context
import com.wanderwildwood.garo.backup.Account
import com.wanderwildwood.garo.backup.Secrets

/** The things there are to choose, kept between runs. */
data class Choices(
    val folderOrder: FolderOrder = FolderOrder.NEWEST,
    val pictureOrder: PictureOrder = PictureOrder.NEWEST,
    /** Pictures across a row of the grid. Three is the size at which a face is still a face. */
    val perRow: Int = 3,
    /**
     * Whether the viewer turns sideways when the phone does. The Kompakt's own settings have no
     * auto-rotate to follow, so the app watches the phone itself.
     */
    val turnWithPhone: Boolean = true,
) {
    companion object {
        val PER_ROW = listOf(2, 3, 4)
    }
}

/** Where an Immich server is, and the key it was given. */
data class ImmichLogin(val server: String, val key: String)

/** Where backing up sends the pictures: one place at a time. */
enum class Destination { IMMICH, NEXTCLOUD }

/** Where pictures go in Nextcloud until another folder is named. */
const val DEFAULT_NEXTCLOUD_FOLDER = "Photos/Kompakt"

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("garo", Context.MODE_PRIVATE)

    fun read(): Choices = Choices(
        folderOrder = enumOr(prefs.getString(FOLDER_ORDER, null), FolderOrder.NEWEST),
        pictureOrder = enumOr(prefs.getString(PICTURE_ORDER, null), PictureOrder.NEWEST),
        perRow = prefs.getInt(PER_ROW, 3).takeIf { it in Choices.PER_ROW } ?: 3,
        turnWithPhone = prefs.getBoolean(TURN_WITH_PHONE, true),
    )

    fun write(choices: Choices) {
        prefs.edit()
            .putString(FOLDER_ORDER, choices.folderOrder.name)
            .putString(PICTURE_ORDER, choices.pictureOrder.name)
            .putInt(PER_ROW, choices.perRow)
            .putBoolean(TURN_WITH_PHONE, choices.turnWithPhone)
            .apply()
    }

    // The key opens every picture on the server, so it is sealed (AES-GCM, key in the phone's
    // keystore) like the Nextcloud password. A key saved in plain text by an earlier version is
    // sealed the first time it is read.
    fun immich(): ImmichLogin? {
        val server = prefs.getString(IMMICH_SERVER, null)?.takeIf { it.isNotBlank() } ?: return null
        val key = immichKey() ?: return null
        return ImmichLogin(server, key)
    }

    fun immichServer(): String? = prefs.getString(IMMICH_SERVER, null)?.takeIf { it.isNotBlank() }
    /** The saved key, for the key dialog to open with — hidden there until the eye is pressed. */
    fun immichKey(): String? {
        val stored = prefs.getString(IMMICH_KEY, null)?.takeIf { it.isNotBlank() } ?: return null
        val plain = Secrets.open(stored)?.takeIf { it.isNotBlank() } ?: return null
        if (!Secrets.isSealed(stored)) {
            Secrets.seal(plain)?.let { prefs.edit().putString(IMMICH_KEY, it).apply() }
        }
        return plain
    }
    fun hasImmichKey(): Boolean = !prefs.getString(IMMICH_KEY, null).isNullOrBlank()

    fun writeImmichServer(server: String?) = prefs.edit().putString(IMMICH_SERVER, server?.trim()).apply()
    /** Nothing is kept, rather than the key in the clear, when it cannot be sealed. */
    fun writeImmichKey(key: String?) {
        val trimmed = key?.trim()
        val sealed = if (trimmed.isNullOrEmpty()) null else Secrets.seal(trimmed)
        if (sealed == null) prefs.edit().remove(IMMICH_KEY).apply()
        else prefs.edit().putString(IMMICH_KEY, sealed).apply()
    }

    fun backupOn(): Boolean = prefs.getBoolean(BACKUP_ON, false)
    fun backupWhen(): BackupWhen = enumOr(prefs.getString(BACKUP_WHEN, null), BackupWhen.WIFI_CHARGING)
    fun writeBackupOn(on: Boolean) = prefs.edit().putBoolean(BACKUP_ON, on).apply()
    fun writeBackupWhen(w: BackupWhen) = prefs.edit().putString(BACKUP_WHEN, w.name).apply()

    /**
     * The folders backed up, each a path on the phone ending in "/", e.g. "DCIM/" or
     * "Pictures/Screenshots/"; everything below one is included. DCIM, the camera's, until chosen.
     */
    fun backupFolders(): Set<String> =
        prefs.getStringSet(BACKUP_FOLDERS, null)?.takeIf { it.isNotEmpty() } ?: Backup.DEFAULT_FOLDERS

    fun writeBackupFolders(folders: Set<String>) = prefs.edit().putStringSet(BACKUP_FOLDERS, folders).apply()

    /** Folders hidden by hand, each kept with its name so settings can list it to bring back. */
    fun hiddenFolders(): Map<String, String> =
        prefs.getStringSet(HIDDEN, emptySet()).orEmpty().mapNotNull { entry ->
            val cut = entry.indexOf('|')
            if (cut <= 0) null else entry.substring(0, cut) to entry.substring(cut + 1)
        }.toMap()

    fun writeHiddenFolders(hidden: Map<String, String>) =
        prefs.edit().putStringSet(HIDDEN, hidden.map { (key, label) -> "$key|$label" }.toSet()).apply()

    // Nextcloud ---------------------------------------------------------------------------------

    /** The account Nextcloud's sign-in handed over; null when signed out, or when the sealed password cannot be opened. */
    fun nextcloud(): Account? {
        val server = prefs.getString(NC_SERVER, null)?.takeIf { it.isNotBlank() } ?: return null
        val user = prefs.getString(NC_USER, null)?.takeIf { it.isNotBlank() } ?: return null
        val password = Secrets.open(prefs.getString(NC_PASSWORD, null)) ?: return null
        return Account(server, user, password)
    }

    /** False, and nothing kept, when the password could not be sealed: it is never kept in the clear. */
    fun writeNextcloud(account: Account): Boolean {
        val sealed = Secrets.seal(account.password) ?: return false
        prefs.edit()
            .putString(NC_SERVER, account.server)
            .putString(NC_USER, account.user)
            .putString(NC_PASSWORD, sealed)
            .apply()
        return true
    }

    fun forgetNextcloud() = prefs.edit().remove(NC_SERVER).remove(NC_USER).remove(NC_PASSWORD).apply()

    /** The folder in the account's files that pictures go into. */
    fun nextcloudFolder(): String = prefs.getString(NC_FOLDER, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_NEXTCLOUD_FOLDER

    fun writeNextcloudFolder(folder: String) = prefs.edit().putString(NC_FOLDER, folder).apply()

    /** Where backing up goes, as chosen; [destination] says where it really can. */
    fun backupTo(): Destination = enumOr(prefs.getString(BACKUP_TO, null), Destination.IMMICH)

    fun writeBackupTo(to: Destination) = prefs.edit().putString(BACKUP_TO, to.name).apply()

    /**
     * Where backing up goes now: the one chosen when it is set up, else the other when that is,
     * else nowhere.
     */
    fun destination(): Destination? {
        val ready = buildSet {
            if (immich() != null) add(Destination.IMMICH)
            if (nextcloud() != null) add(Destination.NEXTCLOUD)
        }
        return backupTo().takeIf { it in ready } ?: ready.firstOrNull()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fallback

    private companion object {
        const val FOLDER_ORDER = "folder_order"
        const val PICTURE_ORDER = "picture_order"
        const val PER_ROW = "per_row"
        const val TURN_WITH_PHONE = "turn_with_phone"
        const val IMMICH_SERVER = "immich_server"
        const val IMMICH_KEY = "immich_key"
        const val BACKUP_ON = "backup_on"
        const val BACKUP_WHEN = "backup_when"
        const val BACKUP_FOLDERS = "backup_folders"
        const val BACKUP_TO = "backup_to"
        const val NC_SERVER = "nextcloud_server"
        const val NC_USER = "nextcloud_user"
        const val NC_PASSWORD = "nextcloud_password"
        const val NC_FOLDER = "nextcloud_folder"
        const val HIDDEN = "hidden_folders"
    }
}
