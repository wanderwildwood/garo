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

    /** Could not reach the server; it would not let this phone in; it answered, but not with a yes. */
    enum class Problem { UNREACHABLE, REFUSED, FAILED }

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

    /** Backed up until other folders are chosen: DCIM, where every camera app saves. */
    val DEFAULT_FOLDERS = setOf("DCIM/")

    /**
     * The camera's pictures: anything under DCIM, which is where every camera app saves —
     * Open Camera in DCIM/OpenCamera, most others in DCIM/Camera.
     */
    fun isCamera(picture: Picture): Boolean = isChosen(picture, DEFAULT_FOLDERS)

    /**
     * A picture on the phone in one of [folders] or below it. Videos only when [videos] says so:
     * a video is many times a photo's size, and Immich backing up has always been photos.
     */
    fun isChosen(picture: Picture, folders: Set<String>, videos: Boolean = false): Boolean {
        if (picture.remote != null || picture.handed != null) return false
        if (picture.video && !videos) return false
        val path = picture.path ?: return false
        val below = if (path.endsWith("/")) path else "$path/"
        return folders.any { below.startsWith(it, ignoreCase = true) }
    }

    /**
     * Whether videos go too. To Nextcloud they do: it keeps files as files, and a video is a file
     * like a photo. To Immich only photos, as it always has been, so a phone that backed up there
     * does not start sending gigabytes of video on its own after an update.
     */
    fun videosTo(to: Destination): Boolean = to == Destination.NEXTCLOUD

    /** The chosen pictures not yet backed up, oldest first, so a backlog goes up in order. */
    fun pending(
        pictures: List<Picture>,
        folders: Set<String> = DEFAULT_FOLDERS,
        videos: Boolean = false,
        isDone: (Picture) -> Boolean,
    ): List<Picture> =
        pictures.filter { isChosen(it, folders, videos) && !isDone(it) }
            .sortedWith(compareBy<Picture> { it.`when` }.thenBy { it.id })

    /**
     * The folders a person can choose from: DCIM as one, since every camera app saves somewhere
     * under it, then every other folder on the phone holding a picture, by its path.
     */
    fun choosable(pictures: List<Picture>): List<String> {
        val paths = pictures.asSequence()
            .filter { it.remote == null && it.handed == null }
            .mapNotNull { it.path?.let { p -> if (p.endsWith("/")) p else "$p/" } }
            .filterNot { it.startsWith("DCIM/", ignoreCase = true) }
            .toSortedSet(String.CASE_INSENSITIVE_ORDER)
        return listOf("DCIM/") + paths
    }

    /**
     * How Immich tells this phone's copy of a picture from any other: the phone's id and size,
     * as stable as a picture on Android gets, and unlike a name unchanged by a rename.
     */
    fun deviceAssetId(picture: Picture) = "garo-${picture.id}-${picture.size}"
}

/** What the phone offers a backup just now: any network, Wi-Fi (or another unmetered one), a charger. */
data class Conditions(val network: Boolean, val unmetered: Boolean, val charging: Boolean)

/** Whether the backup's server answered when this app last asked it. */
enum class Reach { UNKNOWN, OK, UNREACHABLE }

/**
 * The line under the backup switch, worked out without Android so it can be tested. Said
 * plainly, including "not yet": a backup that only looks finished is worse than none.
 */
object BackupStatus {

    enum class Wait { NETWORK, WIFI, CHARGER, WIFI_AND_CHARGER }

    sealed interface Then {
        /** There is a network, and the server did not answer on it. */
        data object Unreachable : Then
        /** The server answered, and not with a yes: full, say. */
        data object Failed : Then
        data class Waiting(val wait: Wait) : Then
        data class LastRun(val at: Long) : Then
        /** Nothing in the way, and it has not run yet. */
        data object Soon : Then
    }

    sealed interface Line {
        data object Off : Line
        data object Refused : Line
        data object Nothing : Line
        data class Progress(val done: Int, val total: Int, val then: Then) : Line
    }

    fun of(
        on: Boolean,
        problem: BackupRecord.Problem?,
        total: Int,
        done: Int,
        lastRun: Long,
        whenTo: BackupWhen,
        now: Conditions,
        reach: Reach,
    ): Line = when {
        !on -> Line.Off
        problem == BackupRecord.Problem.REFUSED -> Line.Refused
        total == 0 -> Line.Nothing
        // All up and nothing wrong last time: when it last looked is the news, not what it waits for.
        done >= total && lastRun > 0 && problem == null -> Line.Progress(done, total, Then.LastRun(lastRun))
        else -> Line.Progress(done, total, then(problem, lastRun, whenTo, now, reach))
    }

    private fun then(problem: BackupRecord.Problem?, lastRun: Long, whenTo: BackupWhen, now: Conditions, reach: Reach): Then {
        val wifi = whenTo != BackupWhen.ANY_NETWORK
        // No network at all: that is what it waits for, whatever the server did last time.
        if (!now.network) return Then.Waiting(
            when {
                whenTo == BackupWhen.WIFI_CHARGING && !now.charging -> Wait.WIFI_AND_CHARGER
                wifi -> Wait.WIFI
                else -> Wait.NETWORK
            },
        )
        // A network, and the server not answering on it: said as that, not as waiting for Wi-Fi.
        if (reach == Reach.UNREACHABLE || (problem == BackupRecord.Problem.UNREACHABLE && reach != Reach.OK)) {
            return Then.Unreachable
        }
        val wait = when {
            wifi && !now.unmetered && whenTo == BackupWhen.WIFI_CHARGING && !now.charging -> Wait.WIFI_AND_CHARGER
            wifi && !now.unmetered -> Wait.WIFI
            whenTo == BackupWhen.WIFI_CHARGING && !now.charging -> Wait.CHARGER
            else -> null
        }
        if (wait != null) return Then.Waiting(wait)
        if (problem == BackupRecord.Problem.FAILED) return Then.Failed
        return if (lastRun > 0) Then.LastRun(lastRun) else Then.Soon
    }
}
