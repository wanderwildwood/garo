package com.wanderwildwood.garo.media

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Albums and pictures from an Immich server, read-only.
 *
 * Three calls and an API key, nothing else: the albums (owned and shared), one album's pictures
 * a page at a time, and a picture at the size asked for. The key needs only album.read,
 * asset.read and asset.view. Written against Immich 2.7.5's API; no Immich library, because the
 * official one brings a generated client several times the size of this app.
 */
class Immich(server: String, private val key: String) {

    private val base = server.trim().trimEnd('/')

    /** What went wrong, in terms the settings screen can say plainly. */
    sealed class Trouble(message: String) : IOException(message) {
        /** The server answered and refused the key, or the key lacks a permission. */
        class Refused(code: Int) : Trouble("refused $code")
        /** No answer at all: off the tailnet, the server down, the address wrong. */
        class Unreachable(cause: Throwable) : Trouble(cause.message ?: "unreachable")
    }

    fun albums(): List<Album> {
        val own = JSONArray(get("/api/albums"))
        val shared = JSONArray(get("/api/albums?shared=true"))
        return (parseAlbums(own) + parseAlbums(shared)).distinctBy { it.id }
    }

    /** Every picture in the album, newest first. Videos are left out on the server's side. */
    fun pictures(album: Album): List<Picture> {
        val found = ArrayList<Picture>()
        var page: Int? = 1
        while (page != null) {
            val body = JSONObject()
                .put("albumIds", JSONArray().put(album.id))
                .put("type", "IMAGE")
                .put("withExif", true)
                .put("order", "desc")
                .put("size", PAGE)
                .put("page", page)
            val reply = JSONObject(post("/api/search/metadata", body.toString()))
            val assets = reply.getJSONObject("assets")
            found += parsePictures(assets.getJSONArray("items"), album)
            page = assets.optString("nextPage", "").toIntOrNull()
        }
        return found
    }

    /** "thumbnail" (about 250 px, for the grid) or "preview" (1440 px, for the viewer). */
    fun image(assetId: String, size: String): ByteArray =
        open("/api/assets/$assetId/thumbnail?size=$size").use { it.readBytes() }

    private fun get(path: String): String = open(path).bufferedReader().use { it.readText() }

    private fun post(path: String, json: String): String {
        val c = connect(path)
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(json.toByteArray()) }
        return answer(c).bufferedReader().use { it.readText() }
    }

    private fun open(path: String) = answer(connect(path))

    private fun connect(path: String): HttpURLConnection =
        try {
            (URL(base + path).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT
                readTimeout = TIMEOUT
                setRequestProperty("x-api-key", key)
                setRequestProperty("Accept", "application/json")
            }
        } catch (e: Exception) {
            throw Trouble.Unreachable(e)
        }

    private fun answer(c: HttpURLConnection) =
        try {
            val code = c.responseCode
            if (code == 401 || code == 403) throw Trouble.Refused(code)
            if (code !in 200..299) throw Trouble.Unreachable(IOException("HTTP $code"))
            c.inputStream
        } catch (t: Trouble) {
            throw t
        } catch (e: Exception) {
            throw Trouble.Unreachable(e)
        }

    data class Album(
        val id: String,
        val name: String,
        val count: Int,
        /** The picture Immich shows for the album, which is what this shows too. */
        val cover: String?,
        /** When its newest picture was added or taken, for ordering newest first. */
        val newest: Long,
    )

    companion object {
        private const val PAGE = 250
        private const val TIMEOUT = 15_000

        /** The key a folder made from an album goes by, kept apart from the phone's bucket ids. */
        fun folderKey(albumId: String) = "immich:$albumId"

        /** Immich's ids are UUIDs; the screens key their rows by a Long, so take half of it. */
        fun longId(assetId: String): Long =
            runCatching { UUID.fromString(assetId).leastSignificantBits }.getOrElse { assetId.hashCode().toLong() }

        internal fun parseAlbums(array: JSONArray): List<Album> = (0 until array.length()).map { i ->
            val a = array.getJSONObject(i)
            Album(
                id = a.getString("id"),
                name = a.optString("albumName").ifBlank { "—" },
                count = a.optInt("assetCount"),
                cover = a.optString("albumThumbnailAssetId").takeIf { it.isNotBlank() && it != "null" },
                newest = time(a.optString("lastModifiedAssetTimestamp"))
                    ?: time(a.optString("endDate"))
                    ?: time(a.optString("updatedAt"))
                    ?: 0L,
            )
        }

        internal fun parsePictures(items: JSONArray, album: Album): List<Picture> = (0 until items.length()).mapNotNull { i ->
            val a = items.getJSONObject(i)
            if (a.optString("type") != "IMAGE") return@mapNotNull null
            val exif = a.optJSONObject("exifInfo")
            remote(
                assetId = a.getString("id"),
                name = a.optString("originalFileName"),
                album = album,
                // The camera's own date when the file carried one; Immich's file dates are only
                // when the file was made, which may be the day it was copied.
                taken = exif?.let { time(it.optString("dateTimeOriginal")) },
                modified = time(a.optString("fileModifiedAt")) ?: 0L,
                size = exif?.optLong("fileSizeInByte") ?: 0L,
                // The asset's own size first: Immich turns it the way the picture is shown, where
                // EXIF's is the stored size, sideways for a phone held upright.
                width = a.optInt("width").takeIf { it > 0 } ?: exif?.optInt("exifImageWidth") ?: 0,
                height = a.optInt("height").takeIf { it > 0 } ?: exif?.optInt("exifImageHeight") ?: 0,
                mime = a.optString("originalMimeType").takeIf { it.isNotBlank() },
                camera = exif?.let { camera(it.optString("make"), it.optString("model")) },
            )
        }

        /** Maker and model, said once: most cameras repeat the maker at the start of the model. */
        internal fun camera(make: String?, model: String?): String? {
            val mk = make?.trim()?.takeUnless { it == "null" }.orEmpty()
            val md = model?.trim()?.takeUnless { it == "null" }.orEmpty()
            return when {
                md.isEmpty() -> mk
                mk.isEmpty() || md.startsWith(mk, ignoreCase = true) -> md
                else -> "$mk $md"
            }.ifBlank { null }
        }

        /** A picture from an album, or the album's cover before its pictures have been read. */
        fun remote(
            assetId: String,
            name: String,
            album: Album,
            taken: Long? = null,
            modified: Long = 0L,
            size: Long = 0L,
            width: Int = 0,
            height: Int = 0,
            mime: String? = null,
            camera: String? = null,
        ) = Picture(
            id = longId(assetId),
            name = name,
            folderKey = folderKey(album.id),
            folderName = album.name,
            path = null,
            volume = null,
            taken = taken,
            modified = modified,
            size = size,
            width = width,
            height = height,
            mime = mime,
            remote = assetId,
            camera = camera,
        )

        /**
         * Immich writes the camera's date with an offset ("+00:00") and its own dates with a "Z".
         * Android 12's Instant.parse refuses the first — newer Java takes both, which is why a
         * test on a desktop JVM cannot catch it — so both go through OffsetDateTime.
         */
        internal fun time(text: String?): Long? {
            if (text.isNullOrBlank() || text == "null") return null
            return runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }
                .recoverCatching { Instant.parse(text).toEpochMilli() }
                .getOrNull()
        }
    }
}
