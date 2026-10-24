package com.wanderwildwood.garo.tap

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

class ReceiveTest {
    private val token = "0123456789abcdef0123456789abcdef"
    private fun url(host: String, port: Int = 41234, t: String = token) = "http://$host:$port/$t"

    @Test fun acceptsEveryPrivateRange() {
        for (h in listOf("192.168.0.1", "192.168.255.254", "10.0.0.5", "10.255.255.255", "172.16.0.1", "172.31.255.1", "169.254.1.1", "169.254.0.0")) {
            val t = ReceiveUrl.parse(url(h))
            assertNotNull(h, t)
            assertEquals(h, t!!.host)
            assertEquals(41234, t.port)
            assertEquals(token, t.token)
        }
    }

    @Test fun refusesPublicAndEdgeAddresses() {
        for (h in listOf("8.8.8.8", "1.1.1.1", "172.15.0.1", "172.32.0.1", "192.167.1.1", "192.169.1.1", "169.253.1.1", "11.0.0.1", "127.0.0.1", "0.0.0.0", "100.64.0.1", "224.0.0.1", "255.255.255.255")) {
            assertNull(h, ReceiveUrl.parse(url(h)))
        }
    }

    @Test fun refusesHostNamesSchemesAndOddSpellings() {
        for (u in listOf(
            "http://example.com:80/$token", "http://localhost:80/$token", "http://printer.local:80/$token",
            "https://192.168.1.2:80/$token", "ftp://192.168.1.2:80/$token", "HTTP://192.168.1.2:80/$token",
            "http://192.168.1.2/$token", "http://192.168.1.2:80", "http://192.168.1.2:80/",
            "http://192.168.1.2:80/$token/", "http://192.168.1.2:80/$token?x=1", "http://192.168.1.2:80/x/$token",
            "http://user@192.168.1.2:80/$token", "http://192.168.1.2@8.8.8.8:80/$token",
            "http://192.168.001.2:80/$token", "http://192.168.1.256:80/$token", "http://192.168.1:80/$token",
            "http://192.168.1.2.3:80/$token", "http://0xC0.168.1.2:80/$token", "http://3232235777:80/$token",
            "http://[::1]:80/$token", "http://192.168.1.2:80/$token ", " http://192.168.1.2:80/$token", "",
        )) assertNull(u, ReceiveUrl.parse(u))
        assertNull(ReceiveUrl.parse(null))
    }

    @Test fun portMustBeInRange() {
        assertNotNull(ReceiveUrl.parse(url("192.168.1.2", 1)))
        assertNotNull(ReceiveUrl.parse(url("192.168.1.2", 65535)))
        assertNull(ReceiveUrl.parse(url("192.168.1.2", 0)))
        assertNull(ReceiveUrl.parse(url("192.168.1.2", 65536)))
        assertNull(ReceiveUrl.parse(url("192.168.1.2", 99999)))
        assertNull(ReceiveUrl.parse(url("192.168.1.2", 123456)))
        assertNull(ReceiveUrl.parse("http://192.168.1.2:-1/$token"))
    }

    @Test fun tokenMustBeThirtyTwoHex() {
        for (t in listOf("", "0123", token.dropLast(1), token + "0", token.uppercase(), "g" + token.drop(1), "../../etc/passwd", token.replaceRange(5, 6, "-"))) {
            assertNull(t, ReceiveUrl.parse(url("192.168.1.2", t = t)))
        }
    }

    @Test fun whatTheSenderWritesIsWhatTheReceiverAccepts() {
        val built = TapUrl.build(InetAddress.getByName("192.168.43.1"), 38123, TapUrl.newToken())
        assertNotNull(ReceiveUrl.parse(built))
    }

    // ---- the download, against the real sender-side server on the loopback ----

    private val servers = mutableListOf<OneShotServer>()
    @After fun stopAll() = servers.forEach { it.stop() }

    private fun serve(bytes: ByteArray, mime: String = "image/jpeg"): ReceiveTarget {
        val s = OneShotServer(InetAddress.getByName("127.0.0.1"), BytesShareable("a.jpg", mime, bytes), lifetimeMs = 30_000)
        s.start(); servers.add(s)
        return ReceiveTarget("127.0.0.1", s.port, s.token)
    }

    @Test fun downloadsWhatTheSenderServes() {
        val photo = ByteArray(300_000) { (it * 7).toByte() }
        val out = ByteArrayOutputStream()
        val r = PhotoFetch.fetch(serve(photo), out)
        assertTrue(r is Fetched.Done)
        assertEquals(photo.size.toLong(), (r as Fetched.Done).bytes)
        assertEquals("image/jpeg", r.mime)
        assertArrayEquals(photo, out.toByteArray())
    }

    @Test fun aPictureOverTheCapIsRefusedBeforeItsBodyIsRead() {
        val out = ByteArrayOutputStream()
        val r = PhotoFetch.fetch(serve(ByteArray(5_000)), out, maxBytes = 4_999)
        assertEquals(Fetched.TooBig, r)
        assertEquals(0, out.size())
        assertTrue(PhotoFetch.fetch(serve(ByteArray(5_000)), ByteArrayOutputStream(), maxBytes = 5_000) is Fetched.Done)
    }

    @Test fun theCapIsSixtyFourMegabytes() = assertEquals(64L * 1024 * 1024, PhotoFetch.MAX_BYTES)

    @Test fun aWrongTokenIsTheSenderHavingStopped() {
        val t = serve(ByteArray(10))
        assertEquals(Fetched.SenderStopped, PhotoFetch.fetch(ReceiveTarget(t.host, t.port, "f".repeat(32)), ByteArrayOutputStream()))
    }

    @Test fun aClosedServerIsTheSenderHavingStopped() {
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        assertEquals(Fetched.SenderStopped, PhotoFetch.fetch(ReceiveTarget("127.0.0.1", port, token), ByteArrayOutputStream()))
    }

    @Test fun aSecondDownloadOfAOneShotLinkIsRefused() {
        val t = serve(ByteArray(1000))
        assertTrue(PhotoFetch.fetch(t, ByteArrayOutputStream()) is Fetched.Done)
        assertEquals(Fetched.SenderStopped, PhotoFetch.fetch(t, ByteArrayOutputStream()))
    }

    @Test fun aBodyThatBreaksOffIsNotAPicture() {
        val srv = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        Thread {
            srv.accept().use { c ->
                c.getInputStream().read(ByteArray(1024))
                c.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\nContent-Type: image/jpeg\r\n\r\nshort".toByteArray())
            }
            srv.close()
        }.start()
        assertEquals(Fetched.SenderStopped, PhotoFetch.fetch(ReceiveTarget("127.0.0.1", srv.localPort, token), ByteArrayOutputStream()))
    }

    @Test fun noContentLengthIsRefused() {
        val srv = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val served = AtomicInteger()
        Thread {
            srv.accept().use { c ->
                c.getInputStream().read(ByteArray(1024))
                c.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\n\r\nabc".toByteArray())
                served.incrementAndGet()
            }
            srv.close()
        }.start()
        assertEquals(Fetched.SenderStopped, PhotoFetch.fetch(ReceiveTarget("127.0.0.1", srv.localPort, token), ByteArrayOutputStream()))
    }

    @Test fun aServerThatNeverAnswersTimesOut() {
        val srv = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val r = try {
            PhotoFetch.fetch(ReceiveTarget("127.0.0.1", srv.localPort, token), ByteArrayOutputStream(), readTimeoutMs = 300)
        } finally { srv.close() }
        assertEquals(Fetched.SenderStopped, r)
    }
}
