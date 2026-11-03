package com.wanderwildwood.garo.backup

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NextcloudCheckTest {

    private val servers = mutableListOf<MockWebServer>()
    @After fun stop() { servers.forEach { runCatching { it.shutdown() } } }

    private val statusJson = """{"installed":true,"maintenance":false,"version":"29.0.1.1","productname":"Nextcloud"}"""
    private val whoJson = """{"ocs":{"meta":{"status":"ok"},"data":{"id":"ada"}}}"""
    private val local = { a: String -> a.startsWith("http://localhost") || a.startsWith("https://") }

    /** A pretend server on this machine: [nextcloud] answers as one, otherwise it is a plain web server. */
    private fun serve(nextcloud: Boolean, goodUser: String = "ada", goodPassword: String = "app-pass"): String {
        val s = MockWebServer()
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                val good = request.getHeader("Authorization") == okhttp3.Credentials.basic(goodUser, goodPassword, Charsets.UTF_8)
                val (code, body) = when {
                    nextcloud && path == "/status.php" -> 200 to statusJson
                    nextcloud && path == "/ocs/v2.php/cloud/user" ->
                        if (good) 200 to whoJson else 401 to """{"ocs":{"meta":{"statuscode":997}}}"""
                    !nextcloud && path == "/ocs/v2.php/cloud/user" -> 401 to "login please"
                    else -> 404 to "<html>nothing</html>"
                }
                return MockResponse().setResponseCode(code).setBody(body)
            }
        }
        s.start()
        servers += s
        return "http://localhost:${s.port}"
    }

    @Test fun `good credentials give the account`() {
        val r = NextcloudCheck.run(serve(true), " ada ", " app-pass ", local)
        assertTrue(r is CheckResult.Good)
        val a = (r as CheckResult.Good).account
        assertEquals("ada", a.user)
        assertEquals("app-pass", a.password)
        assertTrue(a.server.startsWith("http://localhost"))
    }

    @Test fun `the user name the server knows replaces a typed email`() {
        val r = NextcloudCheck.run(serve(true, goodUser = "ada@example.org"), "ada@example.org", "app-pass", local)
        assertEquals("ada", (r as CheckResult.Good).account.user)
    }

    @Test fun `a wrong password says so`() {
        val r = NextcloudCheck.run(serve(true), "ada", "nope", local)
        assertEquals(Problem.WRONG_PASSWORD, (r as CheckResult.Bad).problem)
    }

    @Test fun `a web server that is not a Nextcloud says so even when it refuses the login`() {
        assertEquals(Problem.NOT_NEXTCLOUD, (NextcloudCheck.run(serve(false), "ada", "app-pass", local) as CheckResult.Bad).problem)
    }

    @Test fun `plain http to a public address is refused before anything is sent`() {
        val r = NextcloudCheck.run("http://cloud.example.org", "ada", "x")
        assertEquals(Problem.PLAIN_HTTP, (r as CheckResult.Bad).problem)
    }

    @Test fun `errors map to the plain messages`() {
        assertEquals(Problem.NOT_FOUND, NextcloudCheck.problemFor(java.net.UnknownHostException("x")))
        assertEquals(Problem.CERTIFICATE, NextcloudCheck.problemFor(javax.net.ssl.SSLHandshakeException("x")))
        assertEquals(Problem.UNREACHABLE, NextcloudCheck.problemFor(java.net.SocketTimeoutException("x")))
    }

    @Test fun `a closed port is unreachable`() {
        val s = MockWebServer()
        s.start()
        val port = s.port
        s.shutdown()
        val r = NextcloudCheck.run("http://localhost:$port", "ada", "x", local)
        assertEquals(Problem.UNREACHABLE, (r as CheckResult.Bad).problem)
    }

    @Test fun `an empty address is a bad address`() {
        assertEquals(Problem.BAD_ADDRESS, (NextcloudCheck.run("  ", "ada", "x") as CheckResult.Bad).problem)
    }

    @Test fun `status and user replies are read strictly`() {
        assertTrue(NextcloudCheck.looksLikeNextcloud(200, statusJson))
        assertFalse(NextcloudCheck.looksLikeNextcloud(200, "<html></html>"))
        assertFalse(NextcloudCheck.looksLikeNextcloud(404, statusJson))
        assertEquals("ada", NextcloudCheck.userId(200, whoJson))
        assertNull(NextcloudCheck.userId(401, whoJson))
        assertNull(NextcloudCheck.userId(200, "nope"))
    }
}
