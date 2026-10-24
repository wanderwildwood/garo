package com.wanderwildwood.garo.tap

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

class OneShotServerTest {
    private val lo = InetAddress.getByName("127.0.0.1")
    private val photo = ByteArray(100_000) { (it * 31).toByte() }
    private val servers = mutableListOf<OneShotServer>()
    private val states = CopyOnWriteArrayList<OneShotServer.State>()

    @After fun stopAll() = servers.forEach { it.stop() }

    private fun server(name: String = "IMG_1.jpg", mime: String = "image/jpeg", life: Long = 30_000, bytes: ByteArray = photo): OneShotServer {
        val s = OneShotServer(lo, BytesShareable(name, mime, bytes), lifetimeMs = life) { states.add(it) }
        s.start()
        servers.add(s)
        return s
    }

    private fun get(s: OneShotServer, path: String, method: String = "GET"): HttpURLConnection =
        (URL("http://127.0.0.1:${s.port}$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 3000; readTimeout = 3000
        }

    private fun waitFor(what: () -> Boolean) {
        val end = System.currentTimeMillis() + 3000
        while (!what() && System.currentTimeMillis() < end) Thread.sleep(10)
    }

    @Test fun servesThePhotoWithItsTypeAndName() {
        val s = server()
        val c = get(s, "/${s.token}")
        assertEquals(200, c.responseCode)
        assertEquals("image/jpeg", c.contentType)
        assertEquals(photo.size.toLong(), c.contentLengthLong)
        assertEquals("inline; filename=\"IMG_1.jpg\"", c.getHeaderField("Content-Disposition"))
        assertArrayEquals(photo, c.inputStream.readBytes())
    }

    @Test fun stopsAfterOneCompletedDownload() {
        val s = server()
        get(s, "/${s.token}").inputStream.readBytes()
        waitFor { s.current == OneShotServer.State.SENT }
        assertEquals(OneShotServer.State.SENT, s.current)
        assertEquals(listOf(OneShotServer.State.SENT), states.toList())
        assertThrows(java.io.IOException::class.java) { Socket("127.0.0.1", s.port).use { it.getOutputStream().write(1); it.getInputStream().read().also { r -> if (r < 0) throw java.io.IOException() } } }
    }

    @Test fun everyOtherPathIs404() {
        val s = server()
        for (p in listOf("/", "/index.html", "/favicon.ico", "/${s.token}x", "/x${s.token}", "/" + s.token.uppercase(), "/${"0".repeat(32)}", "//${s.token}", "/${s.token}/")) {
            val c = get(s, p)
            assertEquals(p, 404, c.responseCode)
        }
        // none of that used it up
        assertEquals(OneShotServer.State.WAITING, s.current)
        assertEquals(200, get(s, "/${s.token}").responseCode)
    }

    @Test fun aQueryOnTheRightPathStillIsTheRightPath() {
        val s = server()
        assertEquals(200, get(s, "/${s.token}?x=1").responseCode)
    }

    @Test fun postsAreRefusedAndHeadDoesNotUseItUp() {
        val s = server()
        assertEquals(405, get(s, "/${s.token}", "POST").responseCode)
        assertEquals(404, get(s, "/nope", "POST").responseCode)
        val h = get(s, "/${s.token}", "HEAD")
        assertEquals(200, h.responseCode)
        assertEquals(OneShotServer.State.WAITING, s.current)
    }

    @Test fun expires() {
        val s = server(life = 250)
        waitFor { s.current == OneShotServer.State.EXPIRED }
        assertEquals(OneShotServer.State.EXPIRED, s.current)
        assertThrows(java.io.IOException::class.java) { get(s, "/${s.token}").responseCode }
        assertEquals(listOf(OneShotServer.State.EXPIRED), states.toList())
    }

    @Test fun stopStopsIt() {
        val s = server()
        s.stop()
        assertEquals(OneShotServer.State.STOPPED, s.current)
        assertThrows(java.io.IOException::class.java) { get(s, "/${s.token}").responseCode }
        s.stop()
        assertEquals(1, states.size)
    }

    @Test fun aDownloadThatBreaksOffDoesNotCount() {
        val big = ByteArray(40_000_000)
        val s = server(bytes = big)
        Socket("127.0.0.1", s.port).use { sock ->
            sock.getOutputStream().write("GET /${s.token} HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            sock.getInputStream().read(ByteArray(1000))
        }
        Thread.sleep(300)
        assertEquals(OneShotServer.State.WAITING, s.current)
        assertEquals(200, get(s, "/${s.token}").responseCode)
    }

    @Test fun listensOnTheGivenAddressOnly() {
        val s = server()
        // 127.0.0.2 is also loopback on Linux, but the server was not bound to it.
        assertThrows(java.io.IOException::class.java) { Socket("127.0.0.2", s.port).close() }
        Socket("127.0.0.1", s.port).close()
    }

    @Test fun nameAndTypeCannotBreakTheHeader() {
        assertEquals("evil_.jpg", OneShotServer.safeName("../../a/evil\".jpg"))
        assertEquals("a__b_.jpg", OneShotServer.safeName("a\r\nb\".jpg"))
        assertEquals("picture", OneShotServer.safeName("///"))
        assertEquals("picture", OneShotServer.safeName("..."))
        assertEquals("_.jpg", OneShotServer.safeName("é.jpg"))
        assertEquals("image/png", OneShotServer.safeMime("IMAGE/PNG"))
        assertEquals("application/octet-stream", OneShotServer.safeMime("text/html"))
        assertEquals("application/octet-stream", OneShotServer.safeMime("image/svg+xml"))
        assertEquals("application/octet-stream", OneShotServer.safeMime("image/png\r\nX: y"))
        assertFalse(OneShotServer.headers(200, 1, "image/png", "a\r\nb").contains("a\r\nb"))
    }

    @Test fun theTokenIs128BitsAndDifferentEachTime() {
        val a = TapUrl.newToken(); val b = TapUrl.newToken()
        assertEquals(32, a.length)
        assertTrue(a.all { it in "0123456789abcdef" })
        assertTrue(a != b)
    }
}
