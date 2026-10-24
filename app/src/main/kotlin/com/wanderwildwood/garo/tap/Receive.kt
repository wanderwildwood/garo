package com.wanderwildwood.garo.tap

import java.io.IOException
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Where a tap says to fetch from, and only if it is somewhere this phone may go: an IPv4
 * address in a private range, a port, and the 128-bit token, in exactly the form [TapUrl.build]
 * writes. Anything else, a host name, https, a public address, is refused. That is the safety
 * rule of Receive by tap: whoever holds a phone to this one cannot make it reach out to the
 * internet or to a name of their choosing.
 */
class ReceiveTarget(val host: String, val port: Int, val token: String) {
    val path: String get() = "/$token"
}

object ReceiveUrl {
    private val FORM = Regex("^http://([0-9]{1,3}(?:\\.[0-9]{1,3}){3}):([0-9]{1,5})/([0-9a-f]{32})$")
    private val OCTET = Regex("^(0|[1-9][0-9]{0,2})$")

    fun parse(url: String?): ReceiveTarget? {
        val m = FORM.matchEntire(url ?: return null) ?: return null
        val host = m.groupValues[1]
        val octets = host.split('.')
        if (!octets.all { OCTET.matches(it) }) return null
        val b = octets.map { it.toInt() }
        if (b.any { it > 255 } || !private(b)) return null
        val port = m.groupValues[2].toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return ReceiveTarget(host, port, m.groupValues[3])
    }

    /** 10/8, 172.16/12, 192.168/16 and the link-local 169.254/16. */
    fun private(b: List<Int>): Boolean = when {
        b[0] == 10 -> true
        b[0] == 172 && b[1] in 16..31 -> true
        b[0] == 192 && b[1] == 168 -> true
        b[0] == 169 && b[1] == 254 -> true
        else -> false
    }
}

/** How a fetch from the other phone ended. */
sealed interface Fetched {
    /** All [bytes] bytes arrived, of [mime] as the sender said (not to be trusted). */
    class Done(val bytes: Long, val mime: String) : Fetched
    /** Nothing answered at that address: another network, or the phone is gone. */
    data object Unreachable : Fetched
    /** It answered, but would not give the picture, or broke off part way. */
    data object SenderStopped : Fetched
    /** Bigger than allowed. */
    data object TooBig : Fetched
    data object Failed : Fetched
}

/**
 * One plain HTTP GET to a private address, written on a socket rather than through
 * HttpURLConnection. The platform's HTTP stack refuses cleartext for an app that targets Android
 * 9 or later unless the app switches cleartext on for itself, which can only be done for named
 * hosts or for everything; a socket here needs no such switch, and the manifest keeps refusing
 * plain http for everything else, Immich included.
 */
object PhotoFetch {
    const val MAX_BYTES = 64L * 1024 * 1024

    fun fetch(
        target: ReceiveTarget,
        into: OutputStream,
        maxBytes: Long = MAX_BYTES,
        connectTimeoutMs: Int = 4_000,
        readTimeoutMs: Int = 10_000,
    ): Fetched {
        val socket = Socket()
        try {
            try {
                socket.connect(InetSocketAddress(target.host, target.port), connectTimeoutMs)
            } catch (e: ConnectException) {
                // Something is there and nothing is listening (refused): the sender closed its
                // server. Anything else, no route, unreachable, a timeout: not on this network.
                return if (e.message.orEmpty().contains("refused", ignoreCase = true)) Fetched.SenderStopped else Fetched.Unreachable
            } catch (_: NoRouteToHostException) {
                return Fetched.Unreachable
            } catch (_: SocketTimeoutException) {
                return Fetched.Unreachable
            }
            socket.soTimeout = readTimeoutMs
            socket.getOutputStream().apply {
                write("GET ${target.path} HTTP/1.1\r\nHost: ${target.host}:${target.port}\r\nAccept: image/*\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                flush()
            }
            val input = socket.getInputStream().buffered()
            val head = readHead(input) ?: return Fetched.SenderStopped
            val lines = head.split("\r\n")
            val status = lines.first().split(' ').getOrNull(1)?.toIntOrNull() ?: return Fetched.SenderStopped
            if (status != 200) return Fetched.SenderStopped
            val headers = lines.drop(1).mapNotNull { l ->
                val i = l.indexOf(':')
                if (i <= 0) null else l.substring(0, i).trim().lowercase() to l.substring(i + 1).trim()
            }.toMap()
            // Length is required: without it a short read could not be told from a whole picture.
            val length = headers["content-length"]?.toLongOrNull() ?: return Fetched.SenderStopped
            if (length <= 0) return Fetched.SenderStopped
            if (length > maxBytes) return Fetched.TooBig
            val buf = ByteArray(16 * 1024)
            var got = 0L
            while (got < length) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), length - got).toInt())
                if (n < 0) return Fetched.SenderStopped
                into.write(buf, 0, n)
                got += n
            }
            return Fetched.Done(got, headers["content-type"].orEmpty())
        } catch (_: SocketTimeoutException) {
            return Fetched.SenderStopped
        } catch (_: IOException) {
            return Fetched.SenderStopped
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun readHead(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 8192) {
            val b = input.read()
            if (b < 0) return null
            sb.append(b.toChar())
            if (sb.endsWith("\r\n\r\n")) return sb.substring(0, sb.length - 4)
        }
        return null
    }
}
