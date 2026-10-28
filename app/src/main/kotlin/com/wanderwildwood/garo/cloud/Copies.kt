package com.wanderwildwood.garo.cloud

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import java.io.File
import java.util.Locale

/**
 * A Nextcloud picture taken out of the app: a copy saved where the reader chooses, or a
 * copy in the cache handed to another app to share. Both start from the original the decoder
 * has already fetched; nothing here talks to the server.
 */
object Copies {

    /**
     * Written to [destination], a document the reader chose in the system's "save as" screen; the
     * name offered there is [suggestedName].
     */
    fun saveTo(context: Context, file: File, destination: Uri) {
        val resolver = context.contentResolver
        try {
            resolver.openOutputStream(destination)!!.use { out -> file.inputStream().use { it.copyTo(out, 64 * 1024) } }
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, destination) }
            throw e
        }
    }

    fun suggestedName(item: CloudItem): String = safeName(item.name)

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
