package com.wanderwildwood.garo.cloud

import com.wanderwildwood.garo.backup.decodeHref
import com.wanderwildwood.garo.backup.encodePath
import com.wanderwildwood.garo.media.Arrange
import com.wanderwildwood.garo.media.Picture
import org.w3c.dom.Element
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory

/**
 * One file or folder in a Nextcloud, as a WebDAV listing describes it.
 *
 * [path] is under the user's own files, decoded, with no slash at either end ("" is the top);
 * [fileId] is Nextcloud's own number for the file, which its preview endpoint asks for.
 */
data class CloudItem(
    val path: String,
    val folder: Boolean,
    val size: Long = 0L,
    /** Last modified, in milliseconds; 0 when the server did not say. */
    val modified: Long = 0L,
    val type: String? = null,
    val fileId: Long? = null,
    /** Nextcloud can draw a small picture of it (nc:has-preview). */
    val hasPreview: Boolean = false,
) {
    val name: String get() = path.substringAfterLast('/')
}

/** What a picture or video is, by its name first and then by the type the server gave it. */
enum class Kind { PICTURE, VIDEO }

/** One folder's contents as the screens show them: its folders first, then its pictures and videos. */
data class CloudListing(val folders: List<CloudItem>, val media: List<CloudItem>)

/**
 * Reading a Nextcloud's own folders, for browsing: no Android in here, so it is tested on its own.
 *
 * Browsing is read-only. The calls it builds are PROPFIND on a folder, GET of a file, and GET of
 * Nextcloud's preview of one; nothing is written, moved or deleted on the server.
 */
object Cloud {

    val PICTURES = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "avif")
    val VIDEOS = setOf("mp4", "mkv", "webm", "mov")

    /** The properties asked for: enough to sort, label and fetch a small picture of each file. */
    const val PROPFIND_BODY = """<?xml version="1.0"?>
<d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/><d:getcontenttype/><oc:fileid/><oc:size/><nc:has-preview/></d:prop></d:propfind>"""

    fun kind(item: CloudItem): Kind? {
        if (item.folder) return null
        val ext = item.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext in PICTURES) return Kind.PICTURE
        if (ext in VIDEOS) return Kind.VIDEO
        val type = item.type?.lowercase(Locale.ROOT).orEmpty()
        // A type the name does not settle: only the kinds this can show, not every image/* there is.
        return when {
            type in setOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/heic", "image/heif", "image/avif") -> Kind.PICTURE
            type in setOf("video/mp4", "video/x-matroska", "video/webm", "video/quicktime") -> Kind.VIDEO
            else -> null
        }
    }

    /**
     * The entries of a PROPFIND on folder [self] (a path as [CloudItem.path] has it), sorted for
     * showing: its folders by name, the way a person reads them, then its pictures and videos newest
     * first. The folder itself is left out, and so are hidden ones (a name starting with a dot),
     * folders Nextcloud says hold nothing at all, and every file that is not a picture or video.
     */
    fun listing(items: List<CloudItem>, self: String): CloudListing {
        val here = self.trim('/')
        val inside = items.filter { it.path != here && parentOf(it.path) == here && !it.name.startsWith(".") }
        val folders = inside.filter { it.folder && it.size != 0L }
            .sortedWith { a, b -> Arrange.natural(a.name, b.name) }
        val media = inside.filter { kind(it) != null }
            .sortedWith(compareByDescending<CloudItem> { it.modified }.thenComparator { a, b -> Arrange.natural(a.name, b.name) })
        return CloudListing(folders, media)
    }

    /** The key a Nextcloud folder goes by among the screens' folders, kept apart from the rest. */
    fun folderKey(path: String) = "nextcloud:" + path.trim('/')

    /** The path behind a [folderKey], or null for a key that is not a Nextcloud folder. */
    fun pathOf(key: String?): String? = key?.takeIf { it.startsWith("nextcloud:") }?.removePrefix("nextcloud:")

    /** A picture or video in a Nextcloud folder, as the grid and the viewer take one. */
    fun picture(item: CloudItem, folderLabel: String): Picture = Picture(
        // Nextcloud's file id is a number of its own, unique on the server; the path's hash only
        // for a server that does not give one.
        id = item.fileId ?: item.path.hashCode().toLong(),
        name = item.name,
        folderKey = folderKey(parentOf(item.path)),
        folderName = folderLabel,
        path = null,
        volume = null,
        taken = null,
        modified = item.modified,
        size = item.size,
        width = 0,
        height = 0,
        mime = item.type,
        video = kind(item) == Kind.VIDEO,
        cloud = item,
    )

    fun parentOf(path: String): String = path.trim('/').substringBeforeLast('/', "")

    /** The WebDAV root of [user]'s files on [server], ending in a slash. */
    fun home(server: String, user: String): String =
        server.trimEnd('/') + "/remote.php/dav/files/" + encodePath(user) + "/"

    /** A file or folder's address: every name percent-encoded, spaces as %20, the slashes kept. */
    fun fileUrl(server: String, user: String, path: String): String {
        val clean = path.trim('/')
        return home(server, user) + if (clean.isEmpty()) "" else encodePath(clean)
    }

    /** A folder's address, with the slash WebDAV likes after a collection. */
    fun folderUrl(server: String, user: String, path: String): String {
        val clean = path.trim('/')
        return home(server, user) + if (clean.isEmpty()) "" else encodePath(clean) + "/"
    }

    /**
     * Nextcloud's own small picture of a file, by its id, no larger than [px] square, its shape
     * kept (`a=1`). The same call its web page makes for its grid.
     */
    fun previewUrl(server: String, fileId: Long, px: Int): String =
        server.trimEnd('/') + "/index.php/core/preview?fileId=$fileId&x=$px&y=$px&a=1"

    /**
     * The name a cached copy of [item] goes by: its id, its date and the size asked for, so a file
     * changed on the server is fetched again rather than shown as it was. Letters, digits and
     * dashes only, whatever the file is called.
     */
    fun cacheKey(item: CloudItem, px: Int): String {
        val id = item.fileId?.toString() ?: ("p" + Integer.toHexString(item.path.hashCode()) + Integer.toHexString(item.path.length))
        return "$id-${item.modified}-${item.size}-$px"
    }

    /** A cached original: the key with no size, and the file's own extension, for the decoder's sake. */
    fun originalKey(item: CloudItem): String {
        val ext = item.name.substringAfterLast('.', "").lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }.take(5)
        return cacheKey(item, 0) + if (ext.isEmpty()) "" else ".$ext"
    }

    /**
     * Read a PROPFIND answer. [user] is whose files these are: every href is turned into a path
     * under `remote.php/dav/files/<user>/`, whatever comes before it — a Nextcloud installed in a
     * folder of its site has more in front.
     */
    fun parse(body: InputStream, user: String): List<CloudItem> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // A server's answer is not trusted to name outside files for the parser to fetch.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            isExpandEntityReferences = false
        }
        val document = factory.newDocumentBuilder().parse(body)
        val marker = "/remote.php/dav/files/$user/"
        val responses = document.getElementsByTagNameNS(DAV, "response")
        val out = mutableListOf<CloudItem>()
        for (i in 0 until responses.length) {
            val response = responses.item(i) as Element
            val href = response.first(DAV, "href")?.textContent?.trim() ?: continue
            val decoded = decodeHref(href)
            val at = decoded.indexOf(marker)
            if (at < 0) continue
            val path = decoded.substring(at + marker.length).trim('/')
            var folder = false
            var length = -1L
            var ocSize = -1L
            var modified = 0L
            var type: String? = null
            var fileId: Long? = null
            var preview = false
            val propstats = response.getElementsByTagNameNS(DAV, "propstat")
            for (j in 0 until propstats.length) {
                val propstat = propstats.item(j) as Element
                if (propstat.first(DAV, "status")?.textContent?.contains(" 200 ") != true) continue
                if (propstat.first(DAV, "resourcetype")?.let { it.getElementsByTagNameNS(DAV, "collection").length > 0 } == true) folder = true
                propstat.first(DAV, "getcontentlength")?.text()?.toLongOrNull()?.let { length = it }
                propstat.first(OC, "size")?.text()?.toLongOrNull()?.let { ocSize = it }
                propstat.first(DAV, "getlastmodified")?.text()?.let { modified = httpDate(it) ?: 0L }
                propstat.first(DAV, "getcontenttype")?.text()?.takeIf { it.isNotEmpty() }?.let { type = it.substringBefore(';').trim() }
                propstat.first(OC, "fileid")?.text()?.toLongOrNull()?.let { fileId = it }
                propstat.first(NC, "has-preview")?.text()?.let { preview = it.equals("true", ignoreCase = true) || it == "1" }
            }
            out += CloudItem(
                path = path,
                folder = folder,
                // A folder's size is oc:size, everything inside it; -1 when the server did not say.
                size = if (folder) ocSize else maxOf(length, ocSize, 0L),
                modified = modified,
                type = type,
                fileId = fileId,
                hasPreview = preview,
            )
        }
        return out
    }

    /** "Tue, 27 Oct 2026 12:21:58 GMT", the form WebDAV's getlastmodified takes, as milliseconds. */
    fun httpDate(text: String): Long? = runCatching {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }
            .parse(text.trim())?.time
    }.getOrNull()

    private const val DAV = "DAV:"
    private const val OC = "http://owncloud.org/ns"
    private const val NC = "http://nextcloud.org/ns"

    private fun Element.text(): String = textContent.trim()

    private fun Element.first(ns: String, name: String): Element? =
        getElementsByTagNameNS(ns, name).let { if (it.length > 0) it.item(0) as Element else null }
}
