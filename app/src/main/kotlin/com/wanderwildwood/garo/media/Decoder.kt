package com.wanderwildwood.garo.media

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.LruCache
import android.util.Size
import com.wanderwildwood.garo.cloud.Cloud
import com.wanderwildwood.garo.cloud.CloudFiles
import com.wanderwildwood.garo.cloud.CloudItem
import kotlinx.coroutines.CancellationException
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

    /** Set while signed in to Nextcloud: where its folders' pictures come from. */
    @Volatile var cloud: CloudFiles? = null

    // Pictures from Nextcloud: its small previews for the grid, and the originals the viewer,
    // Share and Save have fetched. The same 200 MB cap as Immich's, apart from it.
    private val cloudDir = File(cacheDir, "nextcloud")
    // The copy last handed to another app by Share (see cloud/Copies.kt).
    private val shareDir = File(cacheDir, "share")
    private val cloudThumbs get() = File(cloudDir, "thumb").apply { mkdirs() }
    private val cloudOriginals get() = File(cloudDir, "original").apply { mkdirs() }


    // A few at once: enough that a page of the grid fills in a single pass, few enough that
    // the picture under the reader's thumb is never queued behind forty others.
    private val io = Dispatchers.IO.limitedParallelism(4)

    private val thumbnails = object : LruCache<String, Bitmap>(THUMBNAIL_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    // The viewer keeps the one on screen and the two either side of it, so turning a page in
    // either direction finds the next picture already decoded.
    private val full = object : LruCache<Uri, Bitmap>(3) {}

    // The pixel size of the original behind each screen-sized bitmap, the right way up, so the
    // viewer knows what "one picture pixel per screen pixel" means for it.
    private val originals = object : LruCache<Uri, Size>(16) {}

    fun originalSize(picture: Picture): Size? = originals.get(picture.uri)

    fun cachedThumbnail(picture: Picture, px: Int): Bitmap? = thumbnails.get("$px:${picture.uri}")

    suspend fun thumbnail(picture: Picture, px: Int): Bitmap? {
        val uri = picture.uri
        val key = "$px:$uri"
        thumbnails.get(key)?.let { return it }
        return withContext(io) {
            runCatching {
                val remote = picture.remote
                val item = picture.cloud
                when {
                    remote != null -> decode(fetch(remote, THUMBNAIL), px)
                    item != null -> cloudThumbnail(picture, item, px)
                    picture.video -> videoFrame(uri, px)
                    else -> resolver.loadThumbnail(uri, Size(px, px), null)
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
                // A video's still is the frame Android's own thumbnailer picks, asked for at the
                // viewer's size; the video itself plays only when asked to.
                val item = picture.cloud
                if (picture.video) {
                    // A video in Nextcloud: its preview, or a frame read over the network.
                    if (item != null) return@runCatching cloudThumbnail(picture, item, minOf(longSide / 2, 1024))
                    return@runCatching videoFrame(uri, longSide)
                }
                val remote = picture.remote
                val source = if (item != null) {
                    // The original, fetched once into the cache: Nextcloud's previews stop well
                    // short of a camera's size, and zooming in wants the real thing.
                    ImageDecoder.createSource(cloudOriginal(item))
                } else if (remote != null) {
                    // Immich's preview, 1440 on its long side: as much as twice this panel
                    // can show, and a fraction of the original's weight over the network.
                    ImageDecoder.createSource(fetch(remote, PREVIEW))
                } else {
                    ImageDecoder.createSource(resolver, uri)
                }
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val w = info.size.width
                    val h = info.size.height
                    if (remote == null) originals.put(uri, Size(w, h))
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

    /**
     * Part of the original at full detail, for a picture enlarged beyond what the screen-sized
     * bitmap holds. [area] is in the original's pixels; [scale] is 1 for every pixel, less to
     * thin them out. Only the window is decoded, so a fifty-megapixel photo costs the window's
     * memory, not its own. Pictures on a server have no original here: null.
     */
    suspend fun region(picture: Picture, area: android.graphics.Rect, scale: Float): Bitmap? {
        if (picture.remote != null || picture.video) return null
        // A Nextcloud picture's original is in the cache once the viewer has shown it.
        val cached = picture.cloud?.let { File(cloudOriginals, Cloud.originalKey(it)).takeIf { f -> f.length() > 0 } ?: return null }
        return withContext(io) {
            runCatching {
                val source = if (cached != null) ImageDecoder.createSource(cached) else ImageDecoder.createSource(resolver, picture.uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val crop = android.graphics.Rect(area)
                    if (scale < 1f) {
                        val w = (info.size.width * scale).toInt().coerceAtLeast(1)
                        val h = (info.size.height * scale).toInt().coerceAtLeast(1)
                        decoder.setTargetSize(w, h)
                        crop.set(
                            (area.left * scale).toInt().coerceIn(0, w - 1),
                            (area.top * scale).toInt().coerceIn(0, h - 1),
                            (area.right * scale).toInt().coerceIn(1, w),
                            (area.bottom * scale).toInt().coerceIn(1, h),
                        )
                    }
                    decoder.setCrop(crop)
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }.getOrNull()
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
        trim(remoteDir)
        return file
    }

    /**
     * A Nextcloud file's small picture: Nextcloud's own preview when it has one, else made here —
     * from the picture itself, or a frame of the video read over the network — and kept, so the
     * next time it is a read from disk.
     */
    private suspend fun cloudThumbnail(picture: Picture, item: CloudItem, px: Int): Bitmap {
        val nc = cloud ?: throw IllegalStateException("not signed in")
        val file = File(cloudThumbs, Cloud.cacheKey(item, px))
        if (file.length() > 0) {
            file.setLastModified(System.currentTimeMillis())
            return decode(file, px)
        }
        val id = item.fileId
        if (item.hasPreview && id != null) {
            try {
                nc.preview(id, px * 2, file)
                trim(cloudDir)
                return decode(file, px)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // No preview after all (a server without the generator for this kind): made here.
            }
        }
        val bitmap = if (picture.video) cloudVideoFrame(nc, item, px * 2) else decode(cloudOriginal(item), px)
        runCatching {
            val part = File(file.path + "." + System.nanoTime() + ".part")
            part.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            part.renameTo(file)
        }
        return bitmap
    }

    /**
     * The original of a Nextcloud file, fetched once into the cache and then read from there:
     * what the viewer decodes, and what Share and Save to this phone hand on.
     */
    suspend fun cloudOriginal(item: CloudItem): File {
        val file = File(cloudOriginals, Cloud.originalKey(item))
        if (file.length() > 0) {
            file.setLastModified(System.currentTimeMillis())
            return file
        }
        val nc = cloud ?: throw IllegalStateException("not signed in")
        nc.download(item.path, file)
        trim(cloudDir)
        return file
    }

    /** One frame of a video on the server, read by Android's own retriever with the sign-in in a header. */
    private fun cloudVideoFrame(nc: CloudFiles, item: CloudItem, px: Int): Bitmap {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(nc.fileUrl(item.path), nc.headers())
            val frame = retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: throw IllegalStateException("no frame")
            val scale = minOf(1f, px.toFloat() / maxOf(frame.width, frame.height))
            return if (scale < 1f) {
                Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt().coerceAtLeast(1), (frame.height * scale).toInt().coerceAtLeast(1), true)
            } else {
                frame
            }
        } finally {
            retriever.release()
        }
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

    /** Keeps a disk cache under its limit, the least recently seen going first. */
    private fun trim(dir: File) {
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        var total = files.sumOf { it.length() }
        if (total <= DISK_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            total -= f.length()
            f.delete()
            if (total <= DISK_BYTES * 3 / 4) break
        }
    }

    /**
     * A still of a video: Android's own thumbnail when it has made one, and otherwise a frame
     * read out of the file itself — the thumbnailer gives up on some videos, and a blank square
     * says nothing about what is in one.
     */
    private fun videoFrame(uri: Uri, px: Int): Bitmap {
        runCatching { return resolver.loadThumbnail(uri, Size(px, px), null) }
        val retriever = android.media.MediaMetadataRetriever()
        try {
            resolver.openFileDescriptor(uri, "r")!!.use { fd ->
                retriever.setDataSource(fd.fileDescriptor)
                val frame = retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: throw IllegalStateException("no frame")
                val scale = minOf(1f, px.toFloat() / maxOf(frame.width, frame.height))
                return if (scale < 1f) {
                    Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt().coerceAtLeast(1), (frame.height * scale).toInt().coerceAtLeast(1), true)
                } else {
                    frame
                }
            }
        } finally {
            retriever.release()
        }
    }

    /** Everything fetched from a server, for when the server changes or is forgotten. */
    fun forgetRemote() {
        remoteDir.deleteRecursively()
        remoteDir.mkdirs()
        thumbnails.evictAll()
        full.evictAll()
    }

    /** Everything fetched from Nextcloud, for signing out or signing in as someone else. */
    fun forgetCloud() {
        cloudDir.deleteRecursively()
        shareDir.deleteRecursively()
        thumbnails.snapshot().keys.filter { it.contains(":nextcloud://") }.forEach(thumbnails::remove)
        full.snapshot().keys.filter { it.scheme == "nextcloud" }.forEach(full::remove)
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
