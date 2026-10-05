package com.wanderwildwood.garo.media

import org.json.JSONObject
import java.io.File

/** When backing up may use the network. */
enum class BackupWhen {
    /** The default: no mobile data, and no drain on a battery that has a day ahead of it. */
    WIFI_CHARGING,
    WIFI,
    ANY_NETWORK,
}

/**
 * What has gone up, and how the last attempt went.
 *
 * Each picture is recorded by the phone's id together with its size and date, so a picture
 * edited in place counts as new and goes up again. The record is a convenience, not the
 * guarantee: the server is asked by checksum before anything is sent, so a lost record costs
 * a round of checking, never a duplicate.
 */
class BackupRecord(private val file: File) {

    data class Status(
        /** When the last attempt finished, or 0 for never. */
        val lastRun: Long = 0L,
        /** What went wrong last time, if anything did. */
        val problem: Problem? = null,
    )

    enum class Problem { UNREACHABLE, REFUSED }

    private val done = HashSet<String>()
    var status = Status()
        private set

    init {
        runCatching {
            val o = JSONObject(file.readText())
            val list = o.optJSONArray("done")
            if (list != null) for (i in 0 until list.length()) done += list.getString(i)
            status = Status(
                lastRun = o.optLong("lastRun"),
                problem = o.optString("problem").takeIf { it.isNotEmpty() }?.let { name ->
                    Problem.entries.firstOrNull { it.name == name }
                },
            )
        }
    }

    fun isDone(picture: Picture) = key(picture) in done

    fun markDone(picture: Picture) {
        done += key(picture)
    }

    fun finish(problem: Problem?) {
        status = Status(lastRun = System.currentTimeMillis(), problem = problem)
        save()
    }

    fun save() {
        val o = JSONObject()
            .put("done", org.json.JSONArray(done.toList()))
            .put("lastRun", status.lastRun)
            .put("problem", status.problem?.name ?: "")
        val part = File(file.path + ".part")
        runCatching {
            part.writeText(o.toString())
            part.renameTo(file)
        }
    }

    fun forget() {
        done.clear()
        status = Status()
        file.delete()
    }

    companion object {
        fun key(picture: Picture) = "${picture.id}:${picture.size}:${picture.modified}"
    }
}

object Backup {

    /**
     * The camera's pictures: anything under DCIM, which is where every camera app saves —
     * Open Camera in DCIM/OpenCamera, most others in DCIM/Camera. Screenshots, downloads and
     * pictures saved from messages are elsewhere and stay on the phone. Photos only, for now:
     * a video is many times a photo's size, and nobody has asked for those to go up.
     */
    fun isCamera(picture: Picture): Boolean =
        picture.remote == null && !picture.video && picture.path?.startsWith("DCIM/", ignoreCase = true) == true

    /** The camera's pictures not yet backed up, oldest first, so a backlog goes up in order. */
    fun pending(pictures: List<Picture>, isDone: (Picture) -> Boolean): List<Picture> =
        pictures.filter { isCamera(it) && !isDone(it) }.sortedWith(compareBy<Picture> { it.`when` }.thenBy { it.id })

    /**
     * How Immich tells this phone's copy of a picture from any other: the phone's id and size,
     * as stable as a picture on Android gets, and unlike a name unchanged by a rename.
     */
    fun deviceAssetId(picture: Picture) = "garo-${picture.id}-${picture.size}"
}
