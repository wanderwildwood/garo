package com.wanderwildwood.garo.cloud

import com.wanderwildwood.garo.backup.Account
import com.wanderwildwood.garo.backup.Refused
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.Collections
import kotlin.concurrent.thread

/**
 * The browsing calls against a stand-in for Nextcloud on the loopback address, as the backup's
 * own test does: one folder with a picture in it, a preview, and a login checked on every call.
 */
class CloudFilesTest {

    private lateinit var server: ServerSocket
    private val requests = Collections.synchronizedList(mutableListOf<String>())
    private val login = "Basic " + Base64.getEncoder().encodeToString("tester:made-up-app-password".toByteArray())
    private val picture = ByteArray(70_000) { (it * 7).toByte() }

    @Before
    fun start() {
        server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { socket.use { runCatching { serve(it) } } }
            }
        }
    }

    @After
    fun stop() = server.close()

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
        val first = line(input)
        requests += first
        val (method, target) = first.split(' ').let { it[0] to it[1] }
        val headers = HashMap<String, String>()
        while (true) {
            val h = line(input)
            if (h.isEmpty()) break
            headers[h.substringBefore(':').trim().lowercase()] = h.substringAfter(':').trim()
        }
        headers["content-length"]?.toInt()?.let { n -> input.readNBytes(n) }
        val (code, body) = when {
            headers["authorization"] != login -> 401 to ByteArray(0)
            method == "PROPFIND" && target == "/remote.php/dav/files/tester/My%20Pictures/" -> 207 to listing().toByteArray()
            method == "PROPFIND" -> 404 to ByteArray(0)
            method == "GET" && target == "/remote.php/dav/files/tester/My%20Pictures/%C3%A9t%C3%A9.jpg" -> 200 to picture
            method == "GET" && target == "/index.php/core/preview?fileId=7&x=64&y=64&a=1" -> 200 to picture.copyOf(100)
            else -> 404 to ByteArray(0)
        }
        val out = socket.getOutputStream()
        out.write("HTTP/1.1 $code X\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(body)
        out.flush()
    }

    private fun listing() = """<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
<d:response><d:href>/remote.php/dav/files/tester/My%20Pictures/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype><oc:size>70000</oc:size></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
<d:response><d:href>/remote.php/dav/files/tester/My%20Pictures/%C3%A9t%C3%A9.jpg</d:href><d:propstat><d:prop><d:resourcetype/><d:getcontentlength>70000</d:getcontentlength><d:getcontenttype>image/jpeg</d:getcontenttype><oc:fileid>7</oc:fileid><nc:has-preview>true</nc:has-preview></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
</d:multistatus>"""

    private fun files(password: String = "made-up-app-password") =
        CloudFiles(Account("http://127.0.0.1:${server.localPort}", "tester", password))

    @Test
    fun `a folder is listed, its picture fetched whole, and its preview by id`() = runBlocking<Unit> {
        val items = files().list("My Pictures")
        val listing = Cloud.listing(items, "My Pictures")
        assertEquals(listOf("My Pictures/été.jpg"), listing.media.map { it.path })
        assertTrue(requests.any { it.startsWith("PROPFIND /remote.php/dav/files/tester/My%20Pictures/ ") })

        val dir = createTempDir()
        try {
            val original = File(dir, "original.jpg")
            files().download("My Pictures/été.jpg", original)
            assertArrayEquals(picture, original.readBytes())
            val preview = File(dir, "preview")
            files().preview(7, 64, preview)
            assertEquals(100L, preview.length())
            // Nothing half-written is left beside them.
            assertEquals(setOf("original.jpg", "preview"), dir.list()!!.toSet())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the app password goes in a header, never in an address`() = runBlocking<Unit> {
        files().list("My Pictures")
        assertTrue(requests.isNotEmpty())
        assertFalse(requests.any { "made-up-app-password" in it || "tester:" in it })
        val headers = files().headers()
        assertEquals(login, headers["Authorization"])
    }

    @Test
    fun `a missing folder is missing, and a wrong password refused`() = runBlocking<Unit> {
        try {
            files().list("Not There")
            fail("a missing folder was listed")
        } catch (_: CloudFiles.Missing) {
        }
        try {
            files("wrong").list("My Pictures")
            fail("a wrong password was accepted")
        } catch (_: Refused) {
        }
    }

    @Test
    fun `a file that is not there leaves nothing behind`() = runBlocking<Unit> {
        val dir = createTempDir()
        try {
            try {
                files().download("My Pictures/gone.jpg", File(dir, "gone.jpg"))
                fail("a missing file was fetched")
            } catch (_: CloudFiles.Missing) {
            }
            assertEquals(0, dir.list()!!.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun createTempDir(): File = java.nio.file.Files.createTempDirectory("cloud").toFile()
}
