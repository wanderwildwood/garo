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
class BackupRecord(
    private val file: File,
    /**
     * Where the pictures go: the server and the account (for Immich, by a hash of the key),
     * and for Nextcloud the folder. A record
     * written for another destination says nothing about this one and is not trusted (a backup
     * that was running when the destination changed wrote under its old one); empty accepts any.
     */
    private val target: String = "",
    /**
     * How an earlier version wrote this same destination, trusted too: an Immich record from
     * before the key's account was part of it. Written afresh under [target] on the next save.
     */
    private val earlier: String = "",
) {

    data class Status(
        /** When the last attempt finished, or 0 for never. */
        val lastRun: Long = 0L,
        /** What went wrong last time, if anything did. */
        val problem: Problem? = null,
    )

    /** Could not reach the server; it would not let this phone in; it answered, but not with a yes. */
    enum class Problem { UNREACHABLE, REFUSED, FAILED }

    private val done = HashSet<String>()

    /**
     * Pictures the server would not take, or that could not be read: passed over, so the ones
     * after them still go up. Tried again once the picture changes (its key does), or when
     * Back up now is pressed ([retrySkipped]): a limit on the server's side may have been lifted.
     */
    private val skipped = HashSet<String>()
    var status = Status()
        private set

    /** Read from a record written the [earlier] way, not yet under [target]. */
    private var fromEarlier = false

    init {
        runCatching {
            val o = JSONObject(file.readText())
            val wrote = o.optString("target")
            if (target.isNotEmpty() && wrote.isNotEmpty() && wrote != target &&
                (earlier.isEmpty() || wrote != earlier)
            ) return@runCatching
            fromEarlier = earlier.isNotEmpty() && wrote == earlier && wrote != target
            val list = o.optJSONArray("done")
            if (list != null) for (i in 0 until list.length()) done += list.getString(i)
            val passed = o.optJSONArray("skipped")
            if (passed != null) for (i in 0 until passed.length()) skipped += passed.getString(i)
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
        skipped -= key(picture)
    }

    fun isSkipped(picture: Picture) = key(picture) in skipped

    fun markSkipped(picture: Picture) {
        skipped += key(picture)
    }

    /** Every picture passed over is tried again on this run. */
    fun retrySkipped() {
        skipped.clear()
    }

    fun finish(problem: Problem?) {
        status = Status(lastRun = System.currentTimeMillis(), problem = problem)
        save()
    }

    fun save() {
        val o = JSONObject()
            .put("target", target)
            .put("done", org.json.JSONArray(done.toList()))
            .put("skipped", org.json.JSONArray(skipped.toList()))
            .put("lastRun", status.lastRun)
            .put("problem", status.problem?.name ?: "")
        val part = File(file.path + ".part")
        runCatching {
            part.writeText(o.toString())
            part.renameTo(file)
        }
    }

    /**
     * A record still written the [earlier] way is written afresh under [target], so it belongs
     * to this destination alone; called before the destination changes (another Immich key).
     */
    fun claimEarlier() {
        if (fromEarlier) {
            save()
            fromEarlier = false
        }
    }

    fun forget() {
        done.clear()
        skipped.clear()
        status = Status()
        file.delete()
    }

    companion object {
        fun key(picture: Picture) = "${picture.id}:${picture.size}:${picture.modified}"
    }
}

/** How a server's answer to one picture's upload is read. */
object BackupAnswer {
    /**
     * A 4xx that is about the file itself: a name the server will not have (400), a file over its
     * limit (413), a name too long (414), a type it refuses (415), a file it cannot read (422).
     * Not the sign-in (401/403), the server busy (408/429), nor a folder gone or a file locked
     * (404/409/423): the next picture would meet those as well, so the run fails and is tried again.
     */
    fun isNotTaken(code: Int): Boolean = code in NOT_TAKEN

    private val NOT_TAKEN = setOf(400, 413, 414, 415, 422)
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

    /**
     * The id the picture is asked about a second time, by the checksum of its bytes as Android
     * gives them, without the place it was taken: how 0.8.26 and earlier sent it. A copy already
     * there that way is the same picture, so a lost record sends no second one.
     */
    fun plainAssetId(picture: Picture) = deviceAssetId(picture) + "-plain"

    /**
     * Whether a picture goes up, given the checksums asked about ([sums], by id) and the ids the
     * server said it does not have ([wanted]): not when either copy of it is already there.
     */
    fun toSend(picture: Picture, sums: Map<String, String>, wanted: Set<String>): Boolean {
        val id = deviceAssetId(picture)
        val plain = plainAssetId(picture)
        return id in sums && id in wanted && (plain !in sums || plain in wanted)
    }

    /**
     * Which account an Immich key belongs to, as far as the phone can tell without asking: a
     * short hash of the key, never the key. Another key, even for the same account, reads as
     * another account; the server is then asked by checksum, so nothing goes up twice.
     */
    fun keyPrint(key: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(key.trim().toByteArray())
        return "key-" + digest.take(6).joinToString("") { "%02x".format(it) }
    }

    /** A destination as one line, for [BackupRecord]: where, as whom, into which folder. */
    fun target(to: Destination, server: String, user: String, folder: String): String =
        listOf(to.name, server.trim().trimEnd('/').lowercase(), user, folder.trim('/', ' ')).joinToString("|")
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
        /** [skipped]: pictures the server would not take or that could not be read. */
        data class Progress(val done: Int, val total: Int, val then: Then, val skipped: Int = 0) : Line
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
        skipped: Int = 0,
    ): Line = when {
        !on -> Line.Off
        problem == BackupRecord.Problem.REFUSED -> Line.Refused
        total == 0 -> Line.Nothing
        // All up (or passed over) and nothing wrong last time: when it last looked is the news.
        done + skipped >= total && lastRun > 0 && problem == null -> Line.Progress(done, total, Then.LastRun(lastRun), skipped)
        else -> Line.Progress(done, total, then(problem, lastRun, whenTo, now, reach), skipped)
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
