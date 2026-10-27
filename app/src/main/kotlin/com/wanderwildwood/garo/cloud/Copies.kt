package com.wanderwildwood.garo.cloud

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.util.Locale

/**
 * A Nextcloud picture taken out of the app: a copy saved into the phone's own pictures, or a
 * copy in the cache handed to another app to share. Both start from the original the decoder
 * has already fetched; nothing here talks to the server.
 */
object Copies {

    /** Where a saved copy goes, as the phone's own pictures name their folders. */
    const val SAVED_FOLDER = "Pictures/Nextcloud"

    /**
     * Saved through Android's media index, which needs no permission for a file this app adds,
     * into Pictures/Nextcloud under its own name (the index adds a number if that name is taken).
     */
    fun saveToPhone(context: Context, file: File, item: CloudItem): Uri {
        val video = Cloud.kind(item) == Kind.VIDEO
        val resolver = context.contentResolver
        val collection = if (video) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, safeName(item.name))
            put(MediaStore.MediaColumns.MIME_TYPE, type(item))
            put(MediaStore.MediaColumns.RELATIVE_PATH, SAVED_FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IllegalStateException("not added")
        try {
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out, 64 * 1024) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    /**
     * A copy under the file's own name in the cache's share folder, for a FileProvider address:
     * the app it goes to sees "IMG_0001.jpg", not the cache's name for it. The previous one is
     * cleared first, so only the last picture shared is ever there.
     */
    fun forSharing(context: Context, file: File, item: CloudItem): File {
        val dir = File(context.cacheDir, "share").apply { deleteRecursively(); mkdirs() }
        val copy = File(dir, safeName(item.name))
        file.copyTo(copy, overwrite = true)
        return copy
    }

    /** The type to say it is: the server's, else what its name says, else a picture of some kind. */
    fun type(item: CloudItem): String =
        item.type?.takeIf { '/' in it && it != "application/octet-stream" }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(item.name.substringAfterLast('.', "").lowercase(Locale.ROOT))
            ?: if (Cloud.kind(item) == Kind.VIDEO) "video/*" else "image/*"

    private fun safeName(name: String): String = name.replace('/', '_').ifBlank { "picture" }
}
