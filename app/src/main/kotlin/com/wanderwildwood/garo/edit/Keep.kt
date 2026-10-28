package com.wanderwildwood.garo.edit

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.wanderwildwood.garo.ui.SaveAs
import kotlin.math.max

/**
 * Reading the picture to edit, and writing the edited one as a new file where the reader chooses.
 * The original is never written to: a crop that went wrong is a second file to delete, not a
 * photograph lost.
 */
object Keep {

    /** The picture the right way up (its EXIF turn applied), no longer than [longSide]. */
    fun load(resolver: ContentResolver, uri: Uri, longSide: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            // Software, so it can be drawn into and copied; a hardware bitmap can be neither.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val long = max(info.size.width, info.size.height)
            if (long > longSide) {
                val k = longSide.toFloat() / long
                decoder.setTargetSize((info.size.width * k).toInt().coerceAtLeast(1), (info.size.height * k).toInt().coerceAtLeast(1))
            }
        }

    /** Width and height the right way up, without reading the pixels. */
    fun size(resolver: ContentResolver, uri: Uri): Pair<Int, Int>? = runCatching {
        var size: Pair<Int, Int>? = null
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            size = info.size.width to info.size.height
            // Stop before any pixels are read: only the header was wanted.
            decoder.setTargetSize(1, 1)
        }
        size
    }.getOrNull()

    /** Whether the edited picture is saved as PNG (a screenshot keeps its sharp edges) or JPEG. */
    fun isPng(resolver: ContentResolver, original: Uri): Boolean =
        resolver.getType(original) == "image/png" ||
            (displayName(resolver, original) ?: "").endsWith(".png", ignoreCase = true)

    /** The name offered when the reader is asked where to save: "Beach.jpg" gives "Beach edited.jpg". */
    fun suggestedName(resolver: ContentResolver, original: Uri): String =
        SaveAs.editedName(displayName(resolver, original), isPng(resolver, original))

    /**
     * Writes the edited picture to [destination], a document the reader chose in the system's
     * "save as" screen. The original is never written to. A half-written file is removed.
     */
    fun write(resolver: ContentResolver, destination: Uri, bitmap: Bitmap, png: Boolean) {
        try {
            resolver.openOutputStream(destination)!!.use { out ->
                bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, QUALITY, out)
            }
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, destination) }
            throw e
        }
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    private const val QUALITY = 95
}
