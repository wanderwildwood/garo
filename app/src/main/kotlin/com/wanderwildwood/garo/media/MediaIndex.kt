package com.wanderwildwood.garo.media

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns

/**
 * Reads the pictures out of Android's own media index.
 *
 * Fossify Gallery walks the file system as well, so that it can find folders the index has
 * not caught up with and show hidden ones. That needs the all-files permission, which is the
 * one permission a stranger should hesitate over. The index alone needs only permission to
 * read pictures, and on this phone the camera, the screenshot service and every app that
 * saves an image all report to it. What it leaves out is a folder holding a `.nomedia` file,
 * which is that folder asking not to be shown.
 *
 * Pictures and videos. A video plays in the viewer, smeared as the panel smears anything that
 * moves, but there, rather than sent off to another app.
 */
class MediaIndex(
    private val resolver: ContentResolver,
    /** What a picture at the very top of the phone's storage is filed under. */
    private val rootPhone: String,
    /** The same for a card. */
    private val rootCard: String,
) {

    /** Pictures and videos together, as one folder holds both. */
    fun pictures(): List<Picture> =
        read(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), video = false) +
            read(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), video = true)

    private fun read(collection: Uri, video: Boolean): List<Picture> {
        val columns = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.VOLUME_NAME,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.ORIENTATION,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DURATION,
        )

        val found = ArrayList<Picture>()
        resolver.query(collection, columns, null, null, null)?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val name = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val bucket = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_ID)
            val bucketName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            val path = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val volume = c.getColumnIndexOrThrow(MediaStore.MediaColumns.VOLUME_NAME)
            val taken = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            val modified = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val size = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val width = c.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
            val height = c.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
            val orientation = c.getColumnIndexOrThrow(MediaStore.MediaColumns.ORIENTATION)
            val mime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val duration = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)

            while (c.moveToNext()) {
                val rowId = c.getLong(id)
                val vol = c.getStringOrNull(volume)
                val turned = c.getInt(orientation) % 180 != 0
                val w = c.getInt(width)
                val h = c.getInt(height)
                // A folder with no name is the root of a volume. It has no bucket name to show,
                // so it takes the volume's own word for itself.
                val folderName = c.getStringOrNull(bucketName)
                    ?: if (vol == null || vol == Arrange.PRIMARY_VOLUME) rootPhone else rootCard
                found += Picture(
                    id = rowId,
                    name = c.getStringOrNull(name) ?: "",
                    folderKey = c.getStringOrNull(bucket) ?: "",
                    folderName = folderName,
                    path = c.getStringOrNull(path),
                    volume = vol,
                    // 0 is what a scanner writes when it found nothing, not a photo from 1970.
                    // Only 0: a scanned print dated before 1970 has a negative date, and a real one.
                    taken = if (c.isNull(taken)) null else c.getLong(taken).takeIf { it != 0L },
                    modified = c.getLong(modified) * 1000,
                    size = c.getLong(size),
                    // The index records the stored width and height; a picture saved on its
                    // side is shown turned, so its shape on screen swaps.
                    width = if (turned) h else w,
                    height = if (turned) w else h,
                    mime = c.getStringOrNull(mime),
                    video = video,
                    duration = if (c.isNull(duration)) 0L else c.getLong(duration),
                )
            }
        }
        return found
    }

    /**
     * The folders that hold sound: music, audiobooks, podcasts. A picture in one of those is a
     * cover — an album's, a book's — not a photograph anybody took, and a gallery listing them
     * buries the camera under a shelf of covers.
     */
    fun soundFolders(): Set<String> {
        val found = HashSet<String>()
        runCatching {
            resolver.query(
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
                arrayOf(MediaStore.Audio.Media.BUCKET_ID),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) if (!c.isNull(0)) found += c.getString(0)
            }
        }
        return found
    }

    /**
     * A picture handed over by another app, which the index may not know at all — an
     * attachment, a file from a share. Whatever the provider will say about it is used, and the
     * rest is left unknown rather than guessed.
     */
    fun outside(uri: Uri): Picture {
        val type = runCatching { resolver.getType(uri) }.getOrNull()
        var name: String? = null
        var size = 0L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getStringOrNull(0)
                    size = if (c.isNull(1)) 0L else c.getLong(1)
                }
            }
        }
        return Picture(
            id = -1,
            handed = uri,
            name = name ?: uri.lastPathSegment.orEmpty(),
            folderKey = "",
            folderName = "",
            path = null,
            volume = null,
            taken = null,
            modified = 0L,
            size = size,
            width = 0,
            height = 0,
            mime = type,
            video = type?.startsWith("video/") == true,
        )
    }

    private fun android.database.Cursor.getStringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)

    companion object {
        /**
         * The path on disk inside an address from Files (tana), which spells it out — its
         * provider maps the whole file system under one name. Only tana's, which this shop
         * controls; any other app's address is not read for a path it may not mean.
         */
        fun tanaPath(uri: Uri): String? {
            if (uri.authority != TANA_FILES) return null
            val p = uri.path ?: return null
            val at = p.lastIndexOf("/storage/")
            return if (at < 0) null else p.substring(at)
        }

        private const val TANA_FILES = "com.wanderwildwood.tana.files"

        /**
         * The index's own id for an address that came from it — the camera's review, or a file
         * manager handing over a picture it found through the index. Null for any other app's
         * address, which can only be shown on its own.
         */
        fun indexId(uri: Uri): Long? {
            if (uri.authority != MediaStore.AUTHORITY) return null
            return runCatching { android.content.ContentUris.parseId(uri) }.getOrNull()?.takeIf { it >= 0 }
        }
    }
}
