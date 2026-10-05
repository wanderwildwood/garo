package com.wanderwildwood.garo.media

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Turns pictures into bitmaps, small for the grid and screen-sized for the viewer.
 *
 * No image library. Android already keeps a thumbnail for every picture in its index and hands
 * it over through `loadThumbnail`, which is faster than anything that decodes the original; and
 * `ImageDecoder` turns a photo the way its camera said to, which is the one thing a hand-rolled
 * decoder usually gets wrong. Fossify carries Glide and Picasso because it runs on phones
 * back to Android 8; on an Android 12 phone these two calls cover the same ground.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Decoder(private val resolver: ContentResolver) {

    // A few at once: enough that a page of the grid fills in a single pass, few enough that
    // the picture under the reader's thumb is never queued behind forty others.
    private val io = Dispatchers.IO.limitedParallelism(4)

    private val thumbnails = object : LruCache<String, Bitmap>(THUMBNAIL_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    // The viewer keeps the one on screen and the two either side of it, so turning a page in
    // either direction finds the next picture already decoded.
    private val full = object : LruCache<Uri, Bitmap>(3) {}

    fun cachedThumbnail(uri: Uri, px: Int): Bitmap? = thumbnails.get("$px:$uri")

    suspend fun thumbnail(uri: Uri, px: Int): Bitmap? {
        val key = "$px:$uri"
        thumbnails.get(key)?.let { return it }
        return withContext(io) {
            runCatching { resolver.loadThumbnail(uri, Size(px, px), null) }
                .getOrNull()
                ?.also { thumbnails.put(key, it) }
        }
    }

    fun cachedFull(uri: Uri): Bitmap? = full.get(uri)

    /**
     * The picture at no more than [longSide] pixels along its longer edge — twice the panel is
     * enough to zoom into, where the camera's own twelve megapixels would cost fifty megabytes
     * apiece and show nothing more on sixteen greys.
     */
    suspend fun full(uri: Uri, longSide: Int): Bitmap? {
        full.get(uri)?.let { return it }
        return withContext(io) {
            runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                    val w = info.size.width
                    val h = info.size.height
                    val longest = max(w, h)
                    if (longest > longSide) {
                        val scale = longSide.toFloat() / longest
                        decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                    }
                    // Software, not hardware: a hardware bitmap cannot be read back, and the
                    // panel has no GPU to gain from one.
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }.getOrNull()?.also { full.put(uri, it) }
        }
    }

    fun forget(uri: Uri) {
        full.remove(uri)
        thumbnails.snapshot().keys.filter { it.endsWith(":$uri") }.forEach(thumbnails::remove)
    }

    private companion object {
        const val THUMBNAIL_BYTES = 24 * 1024 * 1024
    }
}
