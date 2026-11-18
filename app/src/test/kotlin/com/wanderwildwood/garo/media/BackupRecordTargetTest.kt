package com.wanderwildwood.garo.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BackupRecordTargetTest {

    private fun picture(id: Long) = Picture(
        id = id,
        name = "IMG_$id.jpg",
        folderKey = "k",
        folderName = "f",
        path = "DCIM/Camera/",
        volume = Arrange.PRIMARY_VOLUME,
        taken = null,
        modified = 1_000,
        size = 100,
        width = 1,
        height = 1,
    )

    private fun <T> inDir(block: (File) -> T): T {
        val dir = kotlin.io.path.createTempDirectory("record").toFile()
        try {
            return block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun aRecordForAnotherDestinationIsNotTrusted() = inDir { dir ->
        val file = File(dir, "backup-nextcloud.json")
        val old = Backup.target(Destination.NEXTCLOUD, "https://cloud.example.org", "ada", "Photos/Kompakt")
        val new = Backup.target(Destination.NEXTCLOUD, "https://cloud.example.org", "ada", "Photos/Elsewhere")
        BackupRecord(file, old).apply { markDone(picture(1)); save() }
        assertTrue(BackupRecord(file, old).isDone(picture(1)))
        assertFalse(BackupRecord(file, new).isDone(picture(1)))
    }

    @Test fun aRecordFromBeforeTargetsIsKept() = inDir { dir ->
        val file = File(dir, "backup.json")
        BackupRecord(file).apply { markDone(picture(1)); save() }
        assertTrue(BackupRecord(file, Backup.target(Destination.IMMICH, "https://photos.example.org", "", "")).isDone(picture(1)))
    }

    @Test fun aSkippedPictureIsKeptAndGoesWhenDone() = inDir { dir ->
        val file = File(dir, "backup.json")
        BackupRecord(file, "t").apply { markSkipped(picture(2)); save() }
        val again = BackupRecord(file, "t")
        assertTrue(again.isSkipped(picture(2)))
        // A changed picture is a new key, so it is tried again.
        assertFalse(again.isSkipped(picture(2).copy(size = 101)))
        again.markDone(picture(2))
        assertFalse(again.isSkipped(picture(2)))
    }

    @Test fun onlyAFileItselfIsNotTaken() {
        listOf(400, 404, 409, 413, 415, 422).forEach { assertTrue("$it", BackupAnswer.isNotTaken(it)) }
        listOf(200, 201, 401, 403, 408, 429, 500, 503).forEach { assertFalse("$it", BackupAnswer.isNotTaken(it)) }
    }

    @Test fun theSkippedCountShowsOnTheLine() {
        val line = BackupStatus.of(
            on = true, problem = null, total = 10, done = 8, lastRun = 5,
            whenTo = BackupWhen.ANY_NETWORK, now = Conditions(true, true, true), reach = Reach.OK, skipped = 2,
        )
        assertEquals(BackupStatus.Line.Progress(8, 10, BackupStatus.Then.LastRun(5), 2), line)
    }
}
