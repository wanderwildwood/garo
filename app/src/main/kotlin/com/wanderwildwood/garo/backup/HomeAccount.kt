package com.wanderwildwood.garo.backup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The phone's one Nextcloud account, when Home (iori) holds one. The same file in every app of
 * this shop that talks to a Nextcloud, apart from its package line (shared-homeaccount, copy.sh).
 *
 * Home signs in to the Nextcloud once and hands the account only to the apps it knows by package
 * and signing certificate. This is the app's side. It asks Home's provider
 * (`content://com.wanderwildwood.iori.accounts/nextcloud`: one row, `server`, `user`, `password`,
 * `stamp`, `uid`, while Home is signed in; no row while it is not), and only after making sure the
 * provider really is Home's: the package that holds the address must be Home, signed with Home's
 * release certificate and nothing else ([trusted]). Anything else that took the address is never
 * asked, so no other app can hand this one a server of its own choosing.
 *
 * Without Home the app is as it was: it has its own sign-in, and this answers [Answer.NoHome].
 *
 * Rules for the app (README of shared-homeaccount):
 * - Ask ([ask], [read], [available]) off the main thread: each asks the package manager and
 *   another app's provider. [ask] with a callback does it on a thread of its own.
 * - Ask again on every start or resume, before each sync, and on each change notice ([observe]).
 * - Keep only the choice to use Home's account ([Held.who]); the password is read from Home when
 *   it is needed. A copy kept for the sync code is let go as [follow] says, never kept past it.
 * - The password is never logged ([Held.toString] leaves it out) and never put where Android
 *   backs up.
 * - Sign in to the server (basic auth) with [Held.user] and the password; build every WebDAV,
 *   CalDAV and CardDAV path with [Held.uid] (`/remote.php/dav/files/<uid>/`).
 */
object HomeAccount {

    /** Home. */
    const val HOME = "com.wanderwildwood.iori"

    /** Home's debug build, asked only by a debug build of this app. */
    const val HOME_DEBUG = "com.wanderwildwood.iori.debug"

    /** SHA-256 of the certificate Home's releases are signed with, lower-case hex. */
    const val HOME_CERT = "fb06881da0a2c7b0a98fc6c730098a106636974db877d63e18670b53b0890512"

    /**
     * The build box's and the desk machine's debug certificates (as in Home's IoriAccount.kt).
     * Looked at only by a debug build of this app, and only for [HOME_DEBUG].
     */
    val DEBUG_CERTS = setOf(
        "1e7e9cc0ea37a725d0eaf622187792c425dc0351a3e228ef462f8eaacbb9db06",
        "9ae26e87553000817b4185b15d9047845e87871d8f5204dac77b82183fee248b",
    )

    /** The provider's address in [home]: `<home>.accounts`. */
    fun authority(home: String): String = "$home.accounts"

    fun uri(home: String): Uri = Uri.parse("content://${authority(home)}/nextcloud")

    /**
     * The account as Home holds it now. [user] is the login name: what the app signs in with
     * (basic auth, with [password]). [uid] is the Nextcloud user id, which can differ from the
     * login name (an email address, another case): every WebDAV, CalDAV and CardDAV path is built
     * with it (`/remote.php/dav/files/<uid>/`). From a Home too old to give one, [uid] is [user].
     */
    data class Held(val server: String, val user: String, val password: String, val stamp: String, val uid: String = user) {
        /** Whose account it is (server and user, no password), to tell a new password from another account. */
        val who: String get() = who(server, user)

        /** Without the password, so it cannot end up in a log. */
        override fun toString(): String = "Held(server=$server, user=$user, uid=$uid, stamp=$stamp)"
    }

    /**
     * Server and user as one string. The server is written one way only: scheme and host in
     * lower case, no default port (443 for https, 80 for http), no slash at the end; its path is
     * kept as it is. So signing in to the same account again, typed another way, is the same.
     */
    fun who(server: String, user: String): String = sameServer(server) + "\n" + user

    /** [server] written as [who] writes it. */
    fun sameServer(server: String): String {
        val t = server.trim().trimEnd('/')
        val at = t.indexOf("://")
        if (at < 0) return t
        val scheme = t.substring(0, at).lowercase()
        val rest = t.substring(at + 3)
        val slash = rest.indexOf('/')
        var host = (if (slash < 0) rest else rest.substring(0, slash)).lowercase()
        val path = if (slash < 0) "" else rest.substring(slash)
        val colon = host.lastIndexOf(':')
        if (colon > host.lastIndexOf(']')) {
            val port = host.substring(colon + 1)
            if (port.isEmpty() || (scheme == "https" && port == "443") || (scheme == "http" && port == "80")) {
                host = host.substring(0, colon)
            }
        }
        return "$scheme://$host$path"
    }

    /** A [who] kept by the app (perhaps written by an older copy of this file) written as [who] writes it now. */
    private fun again(who: String): String {
        val n = who.indexOf('\n')
        return if (n < 0) who else who(who.substring(0, n), who.substring(n + 1))
    }

    sealed class Answer {
        /**
         * No Home to ask: none installed, or one that is not the real Home (another package or
         * another key holds the address).
         */
        object NoHome : Answer()

        /**
         * Home is there but gives no answer: it cannot be asked just now (while it is being
         * updated, say), or it does not hand its account to this app (too old, or this app is not
         * on its list; Android tells these apart from the first only as no answer). Nothing is to
         * be changed on this answer; ask again later. An app that Home does not hand its account
         * to never gets a row, so it never takes the account.
         */
        object Unsure : Answer()

        /** Home is there and nobody is signed in to it. */
        object SignedOut : Answer()

        class Has(val held: Held) : Answer()
    }

    // ---- what is decided without a phone (tested on the JVM) --------------------------------

    /**
     * Whether [pkg], which holds the accounts address, is Home: [HOME] signed only with
     * [HOME_CERT], or, for a debug build of this app ([debugBuild]), [HOME_DEBUG] signed only with
     * one of [DEBUG_CERTS]. A debug certificate never stands in for the release one. No
     * certificate read is a refusal.
     */
    fun trusted(pkg: String, signers: List<String>?, debugBuild: Boolean): Boolean {
        if (signers.isNullOrEmpty()) return false
        return when (pkg) {
            HOME -> signers.all { it == HOME_CERT }
            HOME_DEBUG -> debugBuild && signers.all { it in DEBUG_CERTS }
            else -> false
        }
    }

    /** What the phone says about one place Home could be, as the package manager answers it. */
    data class Seen(
        /** The package that holds the address `<home>.accounts`, or null when none (that this app can see) does. */
        val owner: String?,
        /** Whether the package named Home is installed; null when the package manager could not say. */
        val installed: Boolean?,
        /** SHA-256 of each certificate [owner] is signed with; null or empty when they could not be read. */
        val signers: List<String>?,
    )

    sealed class Found {
        /** Home is [pkg]: ask it. */
        class Home(val pkg: String) : Found()
        /** Nothing to ask. */
        object None : Found()
        /** Home is installed but cannot be checked just now. */
        object Unsure : Found()
    }

    /**
     * The packages that may be Home for this build of the app, in the order they are tried. A
     * debug build tries Home's debug build first: the release Home does not hand its account to
     * a debug-signed app, so with both installed the debug Home is the one that answers.
     */
    fun candidates(debugBuild: Boolean): List<String> = if (debugBuild) listOf(HOME_DEBUG, HOME) else listOf(HOME)

    /**
     * Which Home to ask, from what [see] tells of each candidate. The first that is the real
     * Home wins. A candidate whose address is held by another package, or whose package is
     * signed with any other key, is passed over and never asked. Unsure only when no candidate
     * is Home and one is installed but could not be checked.
     */
    fun decide(debugBuild: Boolean, see: (String) -> Seen): Found {
        var unsure = false
        for (pkg in candidates(debugBuild)) {
            val seen = see(pkg)
            when {
                seen.owner == null -> if (seen.installed != false) unsure = true
                seen.owner != pkg -> Unit
                seen.signers.isNullOrEmpty() -> unsure = true
                trusted(pkg, seen.signers, debugBuild) -> return Found.Home(pkg)
            }
        }
        return if (unsure) Found.Unsure else Found.None
    }

    /**
     * The account in Home's row, read through [column] (the text in a column, or null when the
     * column is missing or empty). Null when the row is not a whole account. The server must be
     * https; a debug build ([debugBuild]) also takes http, for a test server on the desk. Without
     * a `uid` column (an older Home), the user id is the login name.
     */
    fun row(debugBuild: Boolean, column: (String) -> String?): Held? {
        val server = column("server")?.trim()?.trimEnd('/').orEmpty()
        val user = column("user").orEmpty()
        val password = column("password").orEmpty()
        val stamp = column("stamp").orEmpty()
        if (server.isEmpty() || user.isEmpty() || password.isEmpty()) return null
        val https = server.startsWith("https://", ignoreCase = true)
        val http = server.startsWith("http://", ignoreCase = true)
        if (!https && !(debugBuild && http)) return null
        if (server.substringAfter("://").isEmpty()) return null
        val uid = column("uid")?.trim().takeUnless { it.isNullOrEmpty() } ?: user
        return Held(server, user, password, stamp, uid)
    }

    /**
     * The answer when the Home that passed the check gave no answer at all (a null cursor), with
     * [after] what [decide] finds once more. Only a Home that is gone by now is [Answer.NoHome];
     * a Home that is still there (refusing this app, too old, or being updated) is
     * [Answer.Unsure], never a sign-out.
     */
    fun unanswered(after: Found): Answer = if (after is Found.None) Answer.NoHome else Answer.Unsure

    /** What an app that uses Home's account does with Home's answer. */
    sealed class Step {
        /** Nothing has changed, or Home cannot be asked just now: the app keeps what it has. */
        object Keep : Step()

        /** The same account under a new stamp (a new password, or back after a sign-out): use it. */
        class Take(val held: Held) : Step()

        /**
         * Let the account go: forget any copy of the password and show this app signed out,
         * with what is on the phone left as it is. [following] says whether the app still keeps
         * its choice to use Home's account: yes when Home signed out or is gone, so the same
         * account is taken again when it comes back; no when Home now holds another account,
         * which is never taken without the person pressing the row again.
         */
        class Drop(val following: Boolean) : Step()
    }

    /**
     * [who] is the account the app chose to use from Home ([Held.who]) and [stamp] the stamp it
     * holds it under: empty while it holds nothing (Home signed out, or not asked yet).
     */
    fun follow(who: String, stamp: String, answer: Answer): Step = when (answer) {
        is Answer.Unsure -> Step.Keep
        is Answer.NoHome, is Answer.SignedOut -> if (stamp.isEmpty()) Step.Keep else Step.Drop(following = true)
        is Answer.Has -> when {
            answer.held.who != again(who) -> Step.Drop(following = false)
            answer.held.stamp == stamp && stamp.isNotEmpty() -> Step.Keep
            else -> Step.Take(answer.held)
        }
    }

    /** SHA-256 of [bytes], lower-case hex. */
    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // ---- asking Home on the phone -----------------------------------------------------------

    private fun debuggable(context: Context) = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private fun notOnMain(context: Context) {
        // Asking another app from the main thread can hold the screen; a debug build says so at once.
        if (Looper.myLooper() == Looper.getMainLooper() && debuggable(context)) {
            throw IllegalStateException("HomeAccount: ask Home off the main thread")
        }
    }

    /** The Home on this phone that may be asked, as [decide] finds it. */
    fun home(context: Context): Found {
        val pm = context.packageManager
        return decide(debuggable(context)) { pkg -> see(pm, pkg) }
    }

    private fun see(pm: PackageManager, pkg: String): Seen {
        val owner = runCatching { pm.resolveContentProvider(authority(pkg), 0)?.packageName }.getOrNull()
        val installed = try {
            pm.getPackageInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: RuntimeException) {
            null
        }
        val signers = if (owner == pkg) signers(pm, pkg) else null
        return Seen(owner, installed, signers)
    }

    /** The SHA-256 of each certificate [pkg] is signed with; null when they cannot be read. */
    @Suppress("DEPRECATION")
    private fun signers(pm: PackageManager, pkg: String): List<String>? = runCatching {
        val raw = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
        } else {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
        }
        raw?.map { sha256(it.toByteArray()) }
    }.getOrNull()

    /**
     * Asks Home for its account. Never on the main thread (a debug build throws there). Never
     * throws otherwise: what cannot be told reads as [Answer.Unsure].
     */
    fun ask(context: Context): Answer {
        notOnMain(context)
        val pkg = when (val found = home(context)) {
            is Found.None -> return Answer.NoHome
            is Found.Unsure -> return Answer.Unsure
            is Found.Home -> found.pkg
        }
        val debug = debuggable(context)
        val answer = try {
            // Null from the real Home: it does not hand its account to this app, is too old to
            // have one, or could not be reached (being updated, its process not starting). None
            // of these is a sign-out; only a Home that is gone by now is no Home.
            val cursor = context.contentResolver.query(uri(pkg), null, null, null, null)
                ?: return unanswered(home(context))
            cursor.use { c ->
                if (!c.moveToFirst()) Answer.SignedOut
                else row(debug) { name -> text(c, name) }?.let { Answer.Has(it) } ?: Answer.SignedOut
            }
        } catch (_: Exception) {
            return Answer.Unsure
        }
        // Home asked again after: had the address changed hands in between, the answer is not used.
        val after = home(context)
        return if (after is Found.Home && after.pkg == pkg) answer else Answer.Unsure
    }

    private fun text(c: Cursor, name: String): String? {
        val i = c.getColumnIndex(name)
        if (i < 0 || c.isNull(i)) return null
        return c.getString(i)
    }

    /** Home's account, or null (no Home, Home signed out, or Home not to be asked just now). Not on the main thread. */
    fun read(context: Context): Held? = (ask(context) as? Answer.Has)?.held

    /**
     * Whether the row "Use the phone's Nextcloud account" is shown: the real Home is installed
     * and hands this app an account. Not on the main thread.
     */
    fun available(context: Context): Boolean = ask(context) is Answer.Has

    private val asker: ExecutorService by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "HomeAccount").apply { isDaemon = true } } }
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** [ask] on a thread of its own; [then] is called on the main thread with the answer. */
    fun ask(context: Context, then: (Answer) -> Unit) {
        val app = context.applicationContext
        asker.execute {
            val answer = runCatching { ask(app) }.getOrDefault(Answer.Unsure)
            main.post { then(answer) }
        }
    }

    /**
     * Calls [onChange] on the main thread whenever Home says its account changed (signed in,
     * new password, signed out), and when Home (release or debug) is installed, updated or
     * removed. The app then asks again ([ask]) and does as [follow] says. A Home installed while
     * this watches is heard too: Android lets an app watch only an address some app holds, so the
     * address of a Home that comes later is watched from the moment it is installed. A notice
     * from anyone else only makes the app ask again, and asking checks Home first. Returns what
     * stops the watching (call it in onStop or onDestroy).
     */
    fun observe(context: Context, onChange: () -> Unit): () -> Unit {
        val app = context.applicationContext
        val homes = listOf(HOME, HOME_DEBUG)
        val observer = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) = onChange()
        }
        val watched = mutableSetOf<String>()
        var stopped = false
        fun watch() {
            for (pkg in homes) {
                if (pkg in watched) continue
                val ok = runCatching {
                    app.contentResolver.registerContentObserver(Uri.parse("content://${authority(pkg)}"), true, observer)
                }.isSuccess
                if (ok) watched += pkg
            }
        }
        watch()
        val installs = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val pkg = intent.data?.schemeSpecificPart ?: return
                if (stopped || pkg !in homes) return
                watch()
                onChange()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        val listening = runCatching {
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(installs, filter, null, main, Context.RECEIVER_NOT_EXPORTED)
            else app.registerReceiver(installs, filter, null, main)
        }.isSuccess
        return {
            if (!stopped) {
                stopped = true
                if (listening) runCatching { app.unregisterReceiver(installs) }
                if (watched.isNotEmpty()) runCatching { app.contentResolver.unregisterContentObserver(observer) }
            }
        }
    }
}
