package com.wanderwildwood.garo.edit

import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlin.math.max

/**
 * Reading the picture to edit, and writing the edited one as a new picture beside it. The
 * original is never written to: a crop that went wrong is a second file to delete, not a
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

    /**
     * The edited picture, written as a new one in the original's folder when the phone knows
     * it, or in Pictures, named after it: "Beach.jpg" becomes "Beach edited.jpg". PNG stays
     * PNG, so a screenshot keeps its sharp edges; everything else is saved as JPEG.
     */
    fun save(resolver: ContentResolver, original: Uri, bitmap: Bitmap): Uri {
        val name = displayName(resolver, original) ?: "Picture"
        val png = resolver.getType(original) == "image/png" || name.endsWith(".png", ignoreCase = true)
        val stem = name.substringBeforeLast('.').ifBlank { "Picture" }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$stem edited.${if (png) "png" else "jpg"}")
            put(MediaStore.MediaColumns.MIME_TYPE, if (png) "image/png" else "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder(resolver, original) ?: "Pictures/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: error("the phone would not make a new picture")
        try {
            resolver.openOutputStream(uri)!!.use { out ->
                bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, QUALITY, out)
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            // Half a file is worse than none.
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    /** The original's folder, for a picture the phone's media index holds ("DCIM/Camera/"). */
    private fun folder(resolver: ContentResolver, uri: Uri): String? = runCatching {
        if (uri.authority != MediaStore.AUTHORITY) return null
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()

    private const val QUALITY = 95
}
