package com.wanderwildwood.garo.backup

import android.content.Context
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import android.provider.MediaStore
import com.wanderwildwood.garo.R
import com.wanderwildwood.garo.media.Backup
import com.wanderwildwood.garo.media.BackupRecord
import com.wanderwildwood.garo.media.BackupWhen
import com.wanderwildwood.garo.media.Destination
import com.wanderwildwood.garo.media.Picture
import com.wanderwildwood.garo.media.Immich
import com.wanderwildwood.garo.media.MediaIndex
import com.wanderwildwood.garo.media.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Backs the chosen folders' pictures up to Immich or Nextcloud, whichever is chosen, a few at a time, whenever Android lets it.
 *
 * Android gives a worker like this about ten minutes before it stops it, so it works in small
 * steps and records each picture the moment it is up; the next run starts where this one was
 * stopped. A backlog of hundreds goes up over a night of such runs, which on a phone that is
 * charging on Wi-Fi is what nobody notices — that is the point.
 */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    // One run at a time: the twice-daily run, the one after a new picture and Back up now can
    // all be let go at once, and two runs side by side would each send what the other is sending.
    // The second waits, then finds the record already says done.
    override suspend fun doWork(): Result = running.withLock { run() }

    private fun run(): Result {
        val settings = Settings(applicationContext)
        if (!settings.backupOn()) return Result.success()
        val to = settings.destination() ?: return Result.success()

        val record = record(applicationContext, to)
        val index = MediaIndex(
            applicationContext.contentResolver,
            rootPhone = applicationContext.getString(R.string.folder_root_phone),
            rootCard = applicationContext.getString(R.string.folder_root_card),
        )
        val pending = runCatching {
            Backup.pending(index.pictures(), settings.backupFolders(), videos = Backup.videosTo(to), isDone = record::isDone)
        }.getOrElse { return Result.retry() }
        if (pending.isEmpty()) {
            record.finish(null)
            return Result.success()
        }
        return when (to) {
            Destination.IMMICH -> toImmich(settings, record, pending)
            Destination.NEXTCLOUD -> toNextcloud(settings, record, pending)
        }
    }

    private fun toImmich(settings: Settings, record: BackupRecord, pending: List<Picture>): Result {
        val login = settings.immich() ?: return Result.success()
        val server = Immich(login.server, login.key)
        val resolver = applicationContext.contentResolver
        val deviceId = "garo-" + Build.MODEL.replace(' ', '-').lowercase()
        try {
            for (batch in pending.chunked(BATCH)) {
                if (isStopped) break
                val sums = batch.associate { p ->
                    Backup.deviceAssetId(p) to resolver.openInputStream(p.uri)!!.use(::sha1)
                }
                val wanted = server.toUpload(sums)
                for (p in batch) {
                    if (isStopped) break
                    if (Backup.deviceAssetId(p) in wanted) {
                        server.upload(
                            open = { resolver.openInputStream(p.uri)!! },
                            name = p.name,
                            mime = p.mime,
                            deviceAssetId = Backup.deviceAssetId(p),
                            deviceId = deviceId,
                            created = p.`when`,
                            modified = p.modified.takeIf { it > 0 } ?: p.`when`,
                        )
                    }
                    // Up now, or already there: either way not to be sent again.
                    record.markDone(p)
                    record.save()
                }
            }
        } catch (e: Immich.Trouble.Refused) {
            // The key cannot upload — most likely made without asset.upload. Trying again will
            // not change that, so it stops and says so in settings.
            record.finish(BackupRecord.Problem.REFUSED)
            return Result.success()
        } catch (e: Exception) {
            record.finish(BackupRecord.Problem.UNREACHABLE)
            return Result.retry()
        }
        record.finish(null)
        return Result.success()
    }

    /**
     * Into the Nextcloud folder, each picture under its own folder's path. Each folder is looked
     * at once a run, and a picture already there by the same name and size is not sent again,
     * so a full backup started over, or after a lost record, sends only what is missing.
     */
    private fun toNextcloud(settings: Settings, record: BackupRecord, pending: List<Picture>): Result {
        val account = settings.nextcloud() ?: return Result.success()
        val folder = PhotoFolder(account, settings.nextcloudFolder())
        val resolver = applicationContext.contentResolver
        val seen = HashMap<String, MutableMap<String, Long>>()
        try {
            for (p in pending) {
                if (isStopped) break
                val dir = Remote.dirFor(p.path)
                val there = seen.getOrPut(dir) {
                    folder.list(dir)?.toMutableMap() ?: run {
                        folder.makeFolders(dir)
                        mutableMapOf()
                    }
                }
                // The file's own length, which is what the server will report back; the index's
                // size can lag behind a file still being written.
                val length = runCatching {
                    resolver.openAssetFileDescriptor(p.uri, "r")!!.use { it.length }
                }.getOrNull()?.takeIf { it >= 0 } ?: p.size
                when (val step = Remote.step(p.name, length, p.id, there)) {
                    is Remote.Step.Skip -> Unit
                    is Remote.Step.Send -> {
                        folder.put(dir, step.name, length, p.mime) { resolver.openInputStream(p.uri)!! }
                        there[step.name] = length
                    }
                }
                record.markDone(p)
                record.save()
            }
        } catch (e: Refused) {
            // Signed out on the server, or the app password revoked: trying again will not help.
            record.finish(BackupRecord.Problem.REFUSED)
            return Result.success()
        } catch (e: Unreachable) {
            record.finish(BackupRecord.Problem.UNREACHABLE)
            return Result.retry()
        } catch (e: Exception) {
            // It answered, and not with a yes: full, or a folder it would not make.
            record.finish(BackupRecord.Problem.FAILED)
            return Result.retry()
        }
        record.finish(null)
        return Result.success()
    }

    private fun sha1(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-1")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val running = Mutex()
        private const val BATCH = 20
        private const val PERIODIC = "backup-periodic"
        private const val ON_NEW = "backup-new-picture"
        private const val NOW = "backup-now"

        /** What has gone up, one record for each destination: what is on Immich is not on Nextcloud. */
        fun record(context: Context, to: Destination = Destination.IMMICH) = BackupRecord(
            File(context.filesDir, if (to == Destination.IMMICH) "backup.json" else "backup-nextcloud.json"),
        )

        /**
         * Schedules backing up as settings say, or stops it. Called whenever settings change and
         * whenever the app opens — a phone that force-stopped the app forgot its schedule, and
         * opening the app is when it can be put back.
         */
        fun schedule(context: Context) {
            val settings = Settings(context)
            val work = WorkManager.getInstance(context)
            if (settings.destination() == null || !settings.backupOn()) {
                work.cancelUniqueWork(PERIODIC)
                work.cancelUniqueWork(ON_NEW)
                return
            }
            val constraints = constraints(settings.backupWhen())
            // Twice a day whatever happens, so a picture missed for any reason is caught.
            work.enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<BackupWorker>(12, TimeUnit.HOURS).setConstraints(constraints).build(),
            )
            watchForNewPictures(context, constraints)
        }

        /**
         * And soon after the camera saves a picture, when the network and the charger allow.
         * Android fires this once per change, so it is set again each time it runs.
         */
        private fun watchForNewPictures(context: Context, constraints: Constraints) {
            val watching = Constraints.Builder()
                .setRequiredNetworkType(constraints.requiredNetworkType)
                .setRequiresCharging(constraints.requiresCharging())
                .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                .setTriggerContentUpdateDelay(1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ON_NEW,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<NewPictureWorker>().setConstraints(watching).build(),
            )
        }

        internal fun constraints(whenTo: BackupWhen): Constraints = Constraints.Builder()
            .setRequiredNetworkType(if (whenTo == BackupWhen.ANY_NETWORK) NetworkType.CONNECTED else NetworkType.UNMETERED)
            .setRequiresCharging(whenTo == BackupWhen.WIFI_CHARGING)
            .build()

        /**
         * Back up soon, within the same limits. [replace] is for after the settings change: a
         * run already waiting was made under the old limits (waiting for a charger the reader
         * has since said not to wait for) and is put back under the new ones. A new picture
         * keeps a waiting run as it is, so a run of shots does not keep restarting it.
         */
        fun runSoon(context: Context, replace: Boolean = false): androidx.work.Operation? {
            val settings = Settings(context)
            if (settings.destination() == null || !settings.backupOn()) return null
            return WorkManager.getInstance(context).enqueueUniqueWork(
                NOW,
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<BackupWorker>().setConstraints(constraints(settings.backupWhen())).build(),
            )
        }

        /** Back up straight away on whatever network there is, whatever Upload says. */
        fun backUpNow(context: Context): androidx.work.Operation? {
            val settings = Settings(context)
            if (settings.destination() == null || !settings.backupOn()) return null
            return WorkManager.getInstance(context).enqueueUniqueWork(
                NOW,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<BackupWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        }
    }
}

/**
 * A new picture appeared: start a backup within the usual limits, and watch for the next one.
 * Re-arming replaces this very request, which is finishing anyway, so it is done last.
 */
class NewPictureWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        BackupWorker.runSoon(applicationContext)
        BackupWorker.schedule(applicationContext)
        return Result.success()
    }
}
