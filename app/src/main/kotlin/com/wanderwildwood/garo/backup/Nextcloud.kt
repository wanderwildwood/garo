package com.wanderwildwood.garo.backup

import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A Nextcloud account as its login page hands it over: the server, the user's login name and an
 * app password made for this phone alone, which can be revoked from the server's security
 * settings without touching the real one.
 */
data class Account(val server: String, val user: String, val password: String)

/** The server could not be reached, or its certificate could not be trusted: nothing was sent. */
class Unreachable(message: String) : IOException(message)

/** The server would not let this account in: signed out on the server, or a wrong password. */
class Refused(message: String) : IOException(message)

internal const val AGENT = "Gallery (garo)"

/**
 * One client for every Nextcloud call. The read timeout is long because a PUT of a video is
 * answered only once the server has written all of it.
 */
internal val http = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .writeTimeout(120, TimeUnit.SECONDS)
    .build()

/**
 * Nextcloud's own sign-in, the one its apps use (Login Flow v2): the phone asks the server for a
 * login page, the reader signs in there in the browser, and the server hands this phone an app
 * password. Nobody types the account's password into this app, and two-factor works because it
 * is the server's own page. A phone with no browser takes an app password typed in instead.
 *
 * The same as Notes' and Recipes', each app with its own copy.
 */
object LoginFlow {

    data class Started(val loginUrl: String, val pollUrl: String, val token: String)

    fun start(server: String): Started {
        val request = Request.Builder()
            .url("${normalise(server)}/index.php/login/v2")
            .post(FormBody.Builder().build())
            .header("User-Agent", AGENT)
            .build()
        val json = try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw Refused("${response.code}")
                JSONObject(response.body!!.string())
            }
        } catch (e: IOException) {
            if (e is Refused) throw e
            throw Unreachable(e.message ?: "no answer")
        }
        val poll = json.getJSONObject("poll")
        return Started(json.getString("login"), poll.getString("endpoint"), poll.getString("token"))
    }

    /** The account, once the reader has finished in the browser, or null while they have not. */
    fun poll(started: Started): Account? {
        val request = Request.Builder()
            .url(started.pollUrl)
            .post(FormBody.Builder().add("token", started.token).build())
            .header("User-Agent", AGENT)
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                if (response.code == 404) return null
                if (!response.isSuccessful) throw Refused("${response.code}")
                val json = JSONObject(response.body!!.string())
                Account(
                    server = json.getString("server").trimEnd('/'),
                    user = json.getString("loginName"),
                    password = json.getString("appPassword"),
                )
            }
        } catch (e: IOException) {
            if (e is Refused) throw e
            null
        }
    }

    /** What was typed, as an address: "cloud.example" becomes "https://cloud.example". */
    fun normalise(server: String): String {
        val trimmed = server.trim().trimEnd('/')
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
    }

    /**
     * Plain http is refused for this app as a whole (Android blocks unencrypted traffic for it),
     * so an address typed with http:// is caught here and said, rather than failing as "unreachable".
     */
    fun isPlain(server: String): Boolean = server.trim().startsWith("http://", ignoreCase = true)

    /**
     * Signing out on the server too: the app password is deleted there, so it opens nothing even
     * if a copy of it survived somewhere. Best effort: off the network, the reader is still
     * signed out here, and the password can be revoked from the server's own security settings.
     */
    fun revoke(account: Account): Boolean = runCatching {
        val request = Request.Builder()
            .url("${account.server.trimEnd('/')}/ocs/v2.php/core/apppassword")
            .delete()
            .header("Authorization", Credentials.basic(account.user, account.password, Charsets.UTF_8))
            .header("OCS-APIRequest", "true")
            .header("User-Agent", AGENT)
            .build()
        http.newCall(request).execute().use { it.isSuccessful }
    }.getOrDefault(false)
}
