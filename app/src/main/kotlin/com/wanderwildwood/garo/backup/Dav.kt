package com.wanderwildwood.garo.backup

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import org.w3c.dom.Element
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The backup folder in a Nextcloud, over WebDAV: `remote.php/dav/files/<user>/<folder>/`, with
 * the phone's own folders inside it as they are on the phone — DCIM/OpenCamera, Pictures/… — so
 * two pictures of the same name in two folders never meet.
 *
 * Four calls and nothing else: PROPFIND to see what a folder already holds (names and sizes),
 * MKCOL to make a folder that is missing, PUT to send a picture, and PROPFIND on the user's own
 * files to check a sign-in. Nothing is ever deleted or moved on the server.
 */
class PhotoFolder(
    private val account: Account,
    folder: String,
    private val client: OkHttpClient = http,
) {
    private val home = account.server.trimEnd('/') + "/remote.php/dav/files/" + encodePath(account.user) + "/"
    private val root = folder.trim('/', ' ')

    private fun request(url: String) = Request.Builder().url(url)
        .header("Authorization", Credentials.basic(account.user, account.password, Charsets.UTF_8))
        .header("User-Agent", AGENT)

    /** The address of [dir], a path under the backup folder ("" for the folder itself). */
    fun dirUrl(dir: String): String = home + Remote.join(root, dir).let { if (it.isEmpty()) "" else encodePath(it) + "/" }

    /**
     * The files directly in [dir], each by name with its size; null when the folder is not there
     * yet. Folders inside it are left out.
     */
    fun list(dir: String): Map<String, Long>? {
        val url = dirUrl(dir)
        val entries = try {
            propfind(url, 1)
        } catch (_: Missing) {
            return null
        }
        val self = decodeHref(url)
        return entries.asSequence()
            .filter { !it.isFolder && it.path.startsWith(self) }
            .map { it.path.removePrefix(self) to it.size }
            .filter { (name, _) -> name.isNotEmpty() && !name.contains('/') }
            .toMap()
    }

    /** [dir] and every folder above it, up to the user's files, each made where it is missing. */
    fun makeFolders(dir: String) {
        var sofar = home
        for (part in Remote.join(root, dir).split('/').filter { it.isNotEmpty() }) {
            sofar += encodePath(part) + "/"
            call(request(sofar).method("MKCOL", null).build()) { response ->
                // 405 is "already there".
                if (response.code != 201 && response.code != 405) check(response)
            }
        }
    }

    /**
     * One picture into [dir] as [name], streamed from [open] rather than read into memory, since
     * a video can be larger than the app's whole heap. [length] is sent as the Content-Length,
     * which Nextcloud wants to see before it takes a body.
     */
    fun put(dir: String, name: String, length: Long, mime: String?, open: () -> InputStream) {
        val type = (mime ?: "application/octet-stream").toMediaTypeOrNull()
        val body = object : RequestBody() {
            override fun contentType() = type
            override fun contentLength() = length
            override fun writeTo(sink: BufferedSink) {
                open().source().use { sink.writeAll(it) }
            }
        }
        call(request(dirUrl(dir) + encodePath(name)).put(body).build()) { response -> check(response) }
    }

    /**
     * Ask the server once, before an account is kept: that these credentials open the user's
     * files. [Refused] or [Unreachable] when not.
     */
    fun probe() {
        try {
            propfind(home, 0)
        } catch (_: Missing) {
            throw Refused("no files for ${account.user}")
        }
    }

    private fun propfind(url: String, depth: Int): List<Entry> {
        val request = request(url).method("PROPFIND", PROPFIND_BODY.toRequestBody(XML)).header("Depth", depth.toString()).build()
        return call(request) { response ->
            if (response.code == 404) throw Missing()
            check(response)
            if (response.code != 207) throw IOException("${response.code} for PROPFIND")
            response.body!!.byteStream().use(::parseMultistatus)
        }
    }

    private fun <T> call(request: Request, read: (Response) -> T): T {
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw Unreachable(e.message ?: "no answer")
        }
        return response.use(read)
    }

    private fun check(response: Response) {
        when {
            response.isSuccessful -> Unit
            response.code == 401 || response.code == 403 -> throw Refused("${response.code}")
            else -> throw IOException("${response.code} for ${response.request.method}")
        }
    }

    /** Nothing at that path. Inside this file only. */
    private class Missing : IOException("not there")
}

/**
 * Where each picture goes in the backup folder, and whether it needs to go at all, worked out
 * without Android or a network so it can be tested.
 */
object Remote {

    /** The folder under the backup folder a picture goes into: its own path on the phone. */
    fun dirFor(path: String?): String = path.orEmpty().trim('/')

    /** Two paths as one, without doubled or trailing slashes. */
    fun join(a: String, b: String): String =
        listOf(a, b).flatMap { it.split('/') }.filter { it.isNotEmpty() }.joinToString("/")

    sealed interface Step {
        /** Already there, the same name and size: nothing to send. */
        data class Skip(val name: String) : Step
        data class Send(val name: String) : Step
    }

    /**
     * What to do with a picture called [name], [size] bytes, given what its folder on the server
     * already holds. The same name and size counts as already backed up. The same name with
     * another size is a different picture (a phone reset starts a camera's numbering again), and
     * is never written over: it goes under the name with the phone's [id] added, which is this
     * picture's alone.
     */
    fun step(name: String, size: Long, id: Long, there: Map<String, Long>): Step {
        val held = there[name] ?: return Step.Send(name)
        if (held == size) return Step.Skip(name)
        val other = withId(name, id)
        return if (there[other] == size) Step.Skip(other) else Step.Send(other)
    }

    /** "IMG_0001.jpg" and 42 as "IMG_0001_42.jpg". */
    fun withId(name: String, id: Long): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) "${name}_$id" else name.substring(0, dot) + "_" + id + name.substring(dot)
    }
}

/** One entry of a WebDAV listing. [path] is decoded; a folder's ends in "/". */
data class Entry(val path: String, val size: Long, val isFolder: Boolean)

/**
 * The answer to a PROPFIND: one `<d:response>` per file or folder, with the properties asked
 * for that the server has. Read with the platform's own XML parser, which works the same off
 * the phone, where the tests run.
 */
fun parseMultistatus(body: InputStream): List<Entry> {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        // A server's answer is not trusted to name outside files for the parser to fetch.
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        isExpandEntityReferences = false
    }
    val document = factory.newDocumentBuilder().parse(body)
    val responses = document.getElementsByTagNameNS(DAV, "response")
    val out = mutableListOf<Entry>()
    for (i in 0 until responses.length) {
        val response = responses.item(i) as Element
        val href = response.first(DAV, "href")?.textContent?.trim() ?: continue
        var size = -1L
        var folder = false
        val propstats = response.getElementsByTagNameNS(DAV, "propstat")
        for (j in 0 until propstats.length) {
            val propstat = propstats.item(j) as Element
            if (propstat.first(DAV, "status")?.textContent?.contains(" 200 ") != true) continue
            propstat.first(DAV, "getcontentlength")?.textContent?.trim()?.toLongOrNull()?.let { size = it }
            if (propstat.first(DAV, "resourcetype")?.let { it.getElementsByTagNameNS(DAV, "collection").length > 0 } == true) {
                folder = true
            }
        }
        var path = decodeHref(href)
        if (folder && !path.endsWith("/")) path += "/"
        out += Entry(path, size, folder)
    }
    return out
}

/** The path an href names, decoded; a full URL or only a path both come out as the path. */
fun decodeHref(href: String): String {
    val raw = if (href.startsWith("http://") || href.startsWith("https://")) URI(href).rawPath else href
    return URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8").replace(Regex("/{2,}"), "/")
}

/** Each segment of a path percent-encoded for a URL, the slashes kept. */
fun encodePath(path: String): String =
    path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

private const val DAV = "DAV:"
private val XML = "application/xml; charset=utf-8".toMediaType()

private const val PROPFIND_BODY = """<?xml version="1.0"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:getcontentlength/><d:resourcetype/></d:prop></d:propfind>"""

private fun Element.first(ns: String, name: String): Element? =
    getElementsByTagNameNS(ns, name).let { if (it.length > 0) it.item(0) as Element else null }
