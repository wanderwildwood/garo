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
 * Pictures only. Videos are left out on purpose: the panel cannot play them.
 */
class MediaIndex(
    private val resolver: ContentResolver,
    /** What a picture at the very top of the phone's storage is filed under. */
    private val rootPhone: String,
    /** The same for a card. */
    private val rootCard: String,
) {

    fun pictures(): List<Picture> {
        // Every volume, so a card's pictures are found beside the phone's own.
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val columns = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.VOLUME_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.ORIENTATION,
            MediaStore.Images.Media.MIME_TYPE,
        )

        val found = ArrayList<Picture>()
        resolver.query(collection, columns, null, null, null)?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val name = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val bucket = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val bucketName = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val path = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            val volume = c.getColumnIndexOrThrow(MediaStore.Images.Media.VOLUME_NAME)
            val taken = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val modified = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val size = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val width = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val height = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val orientation = c.getColumnIndexOrThrow(MediaStore.Images.Media.ORIENTATION)
            val mime = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)

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
                )
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
            mime = runCatching { resolver.getType(uri) }.getOrNull(),
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
