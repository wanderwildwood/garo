package com.wanderwildwood.garo.cloud

import com.wanderwildwood.garo.backup.AGENT
import com.wanderwildwood.garo.backup.Account
import com.wanderwildwood.garo.backup.Refused
import com.wanderwildwood.garo.backup.Unreachable
import com.wanderwildwood.garo.backup.http
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The signed-in Nextcloud, read for browsing: a folder's contents, a file, and Nextcloud's small
 * picture of a file. Three kinds of request, all reads.
 *
 * Every request carries the app password in an Authorization header and nowhere else: never in
 * an address, never in a log. Each one stops when the coroutine waiting for it is cancelled, so
 * a thumbnail that has scrolled off the page is not still being fetched.
 */
class CloudFiles(private val account: Account, private val client: OkHttpClient = http) {

    val user: String get() = account.user

    /** The headers a request for a file needs, for a player that makes its own requests. */
    fun headers(): Map<String, String> = mapOf(
        "Authorization" to Credentials.basic(account.user, account.password, Charsets.UTF_8),
        "User-Agent" to AGENT,
    )

    fun fileUrl(path: String): String = Cloud.fileUrl(account.server, account.user, path)

    /** The folder at [path] and what is directly in it; [Missing] when it is not there. */
    suspend fun list(path: String): List<CloudItem> {
        val request = request(Cloud.folderUrl(account.server, account.user, path))
            .method("PROPFIND", Cloud.PROPFIND_BODY.toRequestBody(XML))
            .header("Depth", "1")
            .build()
        return call(request) { response ->
            if (response.code == 404) throw Missing()
            check(response)
            if (response.code != 207) throw IOException("${response.code} for PROPFIND")
            response.body!!.byteStream().use { Cloud.parse(it, account.user) }
        }
    }

    /** The file at [path], written whole to [to], or nothing written at all. */
    suspend fun download(path: String, to: File) = fetch(fileUrl(path), to)

    /** Nextcloud's preview of the file [fileId], no larger than [px] square, into [to]. */
    suspend fun preview(fileId: Long, px: Int, to: File) = fetch(Cloud.previewUrl(account.server, fileId, px), to)

    private suspend fun fetch(url: String, to: File) {
        call(request(url).get().build()) { response ->
            if (response.code == 404) throw Missing()
            check(response)
            // A name of its own: the grid and the viewer may fetch the same file at once.
            val part = File(to.path + "." + System.nanoTime() + ".part")
            try {
                response.body!!.byteStream().use { input ->
                    part.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                        }
                    }
                }
                // Whole or not at all: a download cut off half way never becomes a picture with
                // half its rows missing that is then served from the cache for ever.
                if (!part.renameTo(to)) throw IOException("could not keep the file")
            } finally {
                part.delete()
            }
        }
    }

    private fun request(url: String) = Request.Builder().url(url)
        .header("Authorization", Credentials.basic(account.user, account.password, Charsets.UTF_8))
        .header("User-Agent", AGENT)

    /**
     * One request, cancelled with the coroutine: the call is dropped at once, and a body being
     * read stops at its next read, since the connection under it is closed.
     */
    private suspend fun <T> call(request: Request, read: (Response) -> T): T {
        val call = client.newCall(request)
        val response = try {
            call.await()
        } catch (e: IOException) {
            coroutineContext.ensureActive()
            throw Unreachable(e.message ?: "no answer")
        }
        return coroutineScope {
            // Cancelled with the caller, this closes the connection under a read that is blocked.
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
            try {
                withContext(Dispatchers.IO) { response.use(read) }
            } finally {
                watcher.cancel()
            }
        }
    }

    private fun check(response: Response) {
        when {
            response.isSuccessful -> Unit
            response.code == 401 || response.code == 403 -> throw Refused("${response.code}")
            else -> throw IOException("${response.code} for ${response.request.method}")
        }
    }

    /** Nothing at that path, or no preview of that file. */
    class Missing : IOException("not there")

    private companion object {
        val XML = "application/xml; charset=utf-8".toMediaType()
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }

        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { cancel() }
}
