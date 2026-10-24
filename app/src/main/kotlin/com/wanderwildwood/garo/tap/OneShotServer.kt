package com.wanderwildwood.garo.tap

import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * A web server for one file, one download. Plain JDK sockets, nothing from Android.
 *
 * It listens on [bindAddress] only (never every address), on a port the system picks. `GET /<token>`
 * answers with the file; every other path is a 404 and nothing else is ever served. It stops
 * for good after the first complete download, after [lifetimeMs], or when [stop] is called,
 * whichever comes first; a download that breaks off does not count and the link works again.
 */
class OneShotServer(
    private val bindAddress: InetAddress,
    private val shareable: Shareable,
    val token: String = TapUrl.newToken(),
    private val lifetimeMs: Long = 120_000,
    private val onState: (State) -> Unit = {},
) {
    enum class State { WAITING, SENT, EXPIRED, STOPPED }

    private val socket = AtomicReference<ServerSocket?>(null)
    private val state = AtomicReference(State.WAITING)
    private val busy = AtomicBoolean(false)

    val current: State get() = state.get()

    /** Binds and starts listening; returns the port. */
    fun start(): Int {
        val server = ServerSocket(0, 4, bindAddress)
        socket.set(server)
        Thread({ acceptLoop(server) }, "tap-accept").apply { isDaemon = true }.start()
        Thread({
            val end = System.nanoTime() + lifetimeMs * 1_000_000
            while (state.get() == State.WAITING) {
                val left = (end - System.nanoTime()) / 1_000_000
                if (left <= 0) { finish(State.EXPIRED); break }
                try { Thread.sleep(minOf(left, 50)) } catch (_: InterruptedException) { break }
            }
        }, "tap-timer").apply { isDaemon = true }.start()
        return server.localPort
    }

    val port: Int get() = socket.get()?.localPort ?: -1

    fun stop() = finish(State.STOPPED)

    private fun finish(to: State) {
        if (!state.compareAndSet(State.WAITING, to)) return
        runCatching { socket.get()?.close() }
        onState(to)
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = try { server.accept() } catch (_: IOException) { return }
            Thread({ serve(client) }, "tap-client").apply { isDaemon = true }.start()
        }
    }

    private fun serve(client: Socket) {
        client.use { c ->
            try {
                c.soTimeout = 5_000
                val head = readHead(c) ?: return
                val line = head.lineSequence().firstOrNull().orEmpty().split(' ')
                if (line.size < 2) return respond(c, 400, "Bad request")
                val method = line[0]
                val path = line[1].substringBefore('?')
                if (!sameToken(path)) return respond(c, 404, "Not found")
                when (method) {
                    "HEAD" -> respond(c, 200, null, headOnly = true)
                    "GET" -> download(c)
                    else -> respond(c, 405, "Method not allowed")
                }
            } catch (_: IOException) {
            }
        }
    }

    private fun sameToken(path: String): Boolean =
        MessageDigest.isEqual(path.toByteArray(Charsets.UTF_8), "/$token".toByteArray(Charsets.UTF_8))

    private fun download(c: Socket) {
        // One download at a time; a second request while one is under way is told it is not there.
        if (state.get() != State.WAITING || !busy.compareAndSet(false, true)) return respond(c, 404, "Not found")
        var complete = false
        try {
            val out = BufferedOutputStream(c.getOutputStream(), 16 * 1024)
            out.write(headers(200, shareable.size, shareable.mime, filename = shareable.name).toByteArray(Charsets.ISO_8859_1))
            var sent = 0L
            shareable.openStream().use { input ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    sent += n
                }
            }
            out.flush()
            complete = sent == shareable.size
        } catch (_: IOException) {
        } finally {
            busy.set(false)
        }
        if (complete) finish(State.SENT)
    }

    private fun readHead(c: Socket): String? {
        val input = c.getInputStream()
        val sb = StringBuilder()
        while (sb.length < 8192) {
            val b = input.read()
            if (b < 0) return null
            sb.append(b.toChar())
            if (sb.endsWith("\r\n\r\n") || sb.endsWith("\n\n")) return sb.toString()
        }
        return null
    }

    private fun respond(c: Socket, code: Int, body: String?, headOnly: Boolean = false) {
        val out = c.getOutputStream()
        if (headOnly) {
            out.write(headers(200, shareable.size, shareable.mime, filename = shareable.name).toByteArray(Charsets.ISO_8859_1))
        } else {
            val text = (body ?: "").toByteArray(Charsets.UTF_8)
            out.write(headers(code, text.size.toLong(), "text/plain; charset=utf-8", filename = null).toByteArray(Charsets.ISO_8859_1))
            out.write(text)
        }
        out.flush()
    }

    companion object {
        private val REASONS = mapOf(200 to "OK", 400 to "Bad Request", 404 to "Not Found", 405 to "Method Not Allowed")

        fun headers(code: Int, length: Long, mime: String, filename: String?): String = buildString {
            append("HTTP/1.1 $code ${REASONS[code] ?: "OK"}\r\n")
            append("Content-Type: ${safeMime(mime)}\r\n")
            append("Content-Length: $length\r\n")
            if (filename != null) append("Content-Disposition: inline; filename=\"${safeName(filename)}\"\r\n")
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Content-Security-Policy: default-src 'none'; sandbox\r\n")
            append("Connection: close\r\n\r\n")
        }

        /** Pictures and videos keep their type; anything else is offered as a plain download. */
        fun safeMime(mime: String): String {
            val m = mime.lowercase().trim()
            val ok = Regex("^(image|video)/[a-z0-9.+-]+$").matches(m) && m != "image/svg+xml"
            return if (ok || m.startsWith("text/plain")) m else "application/octet-stream"
        }

        /** A file name that cannot break out of the header or name a path: ASCII letters, digits, dot, dash, underscore. */
        fun safeName(name: String): String {
            val base = name.substringAfterLast('/').substringAfterLast('\\')
            val clean = base.map { if (it.code in 0x20..0x7E && (it.isLetterOrDigit() || it in "._- ")) it else '_' }
                .joinToString("").trim().trimStart('.').take(100)
            return clean.ifEmpty { "picture" }
        }
    }
}
