package com.wanderwildwood.garo.backup

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Base64
import kotlin.concurrent.thread

/**
 * The WebDAV calls against a small stand-in for Nextcloud on the loopback address: folders and
 * files kept in a map, PROPFIND, MKCOL and PUT answered as Nextcloud answers them, and a login
 * checked on every call. A bare socket server, one request a connection, since the JDK's own
 * HTTP server is not on an Android unit test's class path.
 */
class PhotoFolderTest {

    private lateinit var server: ServerSocket
    private val folders = sortedSetOf("/remote.php/dav/files/tester/")
    private val files = sortedMapOf<String, ByteArray>()
    private val calls = mutableListOf<String>()
    private val login = "Basic " + Base64.getEncoder().encodeToString("tester:made-up-app-password".toByteArray())

    @Before
    fun start() {
        server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use { runCatching { serve(it) } }
            }
        }
    }

    @After
    fun stop() = server.close()

    private class Exchange(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)

    private fun line(input: InputStream): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0 || c == '\n'.code) break
            if (c != '\r'.code) out.write(c)
        }
        return out.toString("UTF-8")
    }

    private fun serve(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val (method, target) = line(input).split(' ').let { it[0] to it[1] }
        val headers = HashMap<String, String>()
        while (true) {
            val h = line(input)
            if (h.isEmpty()) break
            headers[h.substringBefore(':').trim().lowercase()] = h.substringAfter(':').trim()
        }
        val length = headers["content-length"]?.toInt() ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        val (code, text) = answer(Exchange(method, URLDecoder.decode(target, "UTF-8"), headers, body))
        val bytes = text.toByteArray()
        val out = socket.getOutputStream()
        out.write("HTTP/1.1 $code X\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun answer(ex: Exchange): Pair<Int, String> {
        val path = ex.path
        calls += "${ex.method} $path"
        if (ex.headers["authorization"] != login) return 401 to ""
        return when (ex.method) {
            "PROPFIND" -> {
                val dir = if (path.endsWith("/")) path else "$path/"
                if (dir !in folders) return 404 to ""
                val out = StringBuilder("<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\">")
                out.append(response(dir, null))
                if (ex.headers["depth"] == "1") {
                    files.filterKeys { it.startsWith(dir) && !it.removePrefix(dir).contains('/') }
                        .forEach { (f, bytes) -> out.append(response(f, bytes.size.toLong())) }
                    folders.filter { it != dir && it.startsWith(dir) && !it.removePrefix(dir).trimEnd('/').contains('/') }
                        .forEach { out.append(response(it, null)) }
                }
                out.append("</d:multistatus>")
                207 to out.toString()
            }
            "MKCOL" -> {
                val dir = if (path.endsWith("/")) path else "$path/"
                val parent = dir.trimEnd('/').substringBeforeLast('/') + "/"
                when {
                    dir in folders -> 405 to ""
                    parent !in folders -> 409 to ""
                    else -> { folders += dir; 201 to "" }
                }
            }
            "PUT" -> {
                val parent = path.substringBeforeLast('/') + "/"
                if (parent !in folders) return 409 to ""
                if (ex.headers["content-length"]?.toLong() != ex.body.size.toLong()) return 400 to ""
                files[path] = ex.body
                201 to ""
            }
            else -> 405 to ""
        }
    }

    private fun response(path: String, size: Long?): String =
        "<d:response><d:href>${path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }}</d:href>" +
            "<d:propstat><d:prop>" +
            (if (size == null) "<d:resourcetype><d:collection/></d:resourcetype>" else "<d:getcontentlength>$size</d:getcontentlength><d:resourcetype/>") +
            "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"

    private fun account(password: String = "made-up-app-password") =
        Account("http://127.0.0.1:${server.localPort}", "tester", password)

    @Test
    fun `a missing folder is made, a picture is sent, and the next listing has it`() {
        val folder = PhotoFolder(account(), "Photos/Kompakt")
        assertNull(folder.list("DCIM/Camera"))
        folder.makeFolders("DCIM/Camera")
        assertTrue("/remote.php/dav/files/tester/Photos/Kompakt/DCIM/Camera/" in folders)
        assertEquals(emptyMap<String, Long>(), folder.list("DCIM/Camera"))

        val picture = ByteArray(3000) { it.toByte() }
        folder.put("DCIM/Camera", "My Shot.jpg", picture.size.toLong(), "image/jpeg") { picture.inputStream() }
        assertEquals(mapOf("My Shot.jpg" to 3000L), folder.list("DCIM/Camera"))
        assertTrue(files["/remote.php/dav/files/tester/Photos/Kompakt/DCIM/Camera/My Shot.jpg"]!!.contentEquals(picture))
    }

    @Test
    fun `making folders that are already there is not an error`() {
        val folder = PhotoFolder(account(), "Photos")
        folder.makeFolders("DCIM")
        folder.makeFolders("DCIM")
        assertEquals(2, calls.count { it == "MKCOL /remote.php/dav/files/tester/Photos/" })
    }

    @Test
    fun `a wrong app password is refused, not taken for a missing server`() {
        try {
            PhotoFolder(account("wrong"), "Photos").probe()
            fail("a wrong password was accepted")
        } catch (_: Refused) {
        }
        PhotoFolder(account(), "Photos").probe()
    }

    @Test
    fun `nothing listening is unreachable`() {
        val port = server.localPort
        server.close()
        try {
            PhotoFolder(Account("http://127.0.0.1:$port", "tester", "x"), "Photos").list("")
            fail("no server, yet an answer")
        } catch (_: Unreachable) {
        }
    }
}
