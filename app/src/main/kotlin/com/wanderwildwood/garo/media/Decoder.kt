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
import java.io.File
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
class Decoder(private val resolver: ContentResolver, cacheDir: File) {

    /** Set when an Immich server is: where an album's pictures come from. */
    @Volatile var immich: Immich? = null

    // Pictures fetched from Immich, kept on disk so a page turned once turns again without the
    // network — on the tailnet over mobile data, that is the difference between instant and
    // seconds. Android may clear it when space runs short, which costs only a fetch.
    private val remoteDir = File(cacheDir, "immich").apply { mkdirs() }


    // A few at once: enough that a page of the grid fills in a single pass, few enough that
    // the picture under the reader's thumb is never queued behind forty others.
    private val io = Dispatchers.IO.limitedParallelism(4)

    private val thumbnails = object : LruCache<String, Bitmap>(THUMBNAIL_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    // The viewer keeps the one on screen and the two either side of it, so turning a page in
    // either direction finds the next picture already decoded.
    private val full = object : LruCache<Uri, Bitmap>(3) {}

    fun cachedThumbnail(picture: Picture, px: Int): Bitmap? = thumbnails.get("$px:${picture.uri}")

    suspend fun thumbnail(picture: Picture, px: Int): Bitmap? {
        val uri = picture.uri
        val key = "$px:$uri"
        thumbnails.get(key)?.let { return it }
        return withContext(io) {
            runCatching {
                val remote = picture.remote
                if (remote != null) {
                    decode(fetch(remote, THUMBNAIL), px)
                } else {
                    resolver.loadThumbnail(uri, Size(px, px), null)
                }
            }.getOrNull()?.also { thumbnails.put(key, it) }
        }
    }

    fun cachedFull(picture: Picture): Bitmap? = full.get(picture.uri)

    /**
     * The picture at no more than [longSide] pixels along its longer edge — twice the panel is
     * enough to zoom into, where the camera's own twelve megapixels would cost fifty megabytes
     * apiece and show nothing more on sixteen greys.
     */
    suspend fun full(picture: Picture, longSide: Int): Bitmap? {
        val uri = picture.uri
        full.get(uri)?.let { return it }
        return withContext(io) {
            runCatching {
                val remote = picture.remote
                val source = if (remote != null) {
                    // Immich's preview, 1440 on its long side: as much as twice this panel
                    // can show, and a fraction of the original's weight over the network.
                    ImageDecoder.createSource(fetch(remote, PREVIEW))
                } else {
                    ImageDecoder.createSource(resolver, uri)
                }
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
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

    /** The file for an Immich picture at one size, fetched once and then read from disk. */
    private fun fetch(assetId: String, size: String): File {
        val file = File(File(remoteDir, size).apply { mkdirs() }, assetId)
        if (file.length() > 0) {
            file.setLastModified(System.currentTimeMillis())
            return file
        }
        val server = immich ?: throw IllegalStateException("no server")
        val bytes = server.image(assetId, size)
        val part = File(file.path + ".part")
        part.writeBytes(bytes)
        // Whole or not at all: a fetch cut off half way must not become a picture with half
        // its rows missing that is then served from disk for ever.
        part.renameTo(file)
        trim()
        return file
    }

    private fun decode(file: File, px: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val longest = max(info.size.width, info.size.height)
            if (longest > px * 2) {
                val scale = px * 2f / longest
                decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

    /** Keeps the disk cache under its limit, the least recently seen going first. */
    private fun trim() {
        val files = remoteDir.walkTopDown().filter { it.isFile }.toList()
        var total = files.sumOf { it.length() }
        if (total <= DISK_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            total -= f.length()
            f.delete()
            if (total <= DISK_BYTES * 3 / 4) break
        }
    }

    /** Everything fetched from a server, for when the server changes or is forgotten. */
    fun forgetRemote() {
        remoteDir.deleteRecursively()
        remoteDir.mkdirs()
        thumbnails.evictAll()
        full.evictAll()
    }

    fun forget(uri: Uri) {
        full.remove(uri)
        thumbnails.snapshot().keys.filter { it.endsWith(":$uri") }.forEach(thumbnails::remove)
    }

    private companion object {
        const val THUMBNAIL_BYTES = 24 * 1024 * 1024
        const val DISK_BYTES = 200L * 1024 * 1024
        const val THUMBNAIL = "thumbnail"
        const val PREVIEW = "preview"
    }
}
