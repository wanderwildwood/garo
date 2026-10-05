package com.wanderwildwood.garo.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BackupTest {

    private fun pic(id: Long, path: String?, taken: Long? = null, size: Long = 100, modified: Long = 1_000, remote: String? = null) = Picture(
        id = id,
        name = "IMG_$id.jpg",
        folderKey = "k",
        folderName = "f",
        path = path,
        volume = Arrange.PRIMARY_VOLUME,
        taken = taken,
        modified = modified,
        size = size,
        width = 0,
        height = 0,
        remote = remote,
    )

    @Test
    fun `the camera's pictures are the ones under DCIM, whichever camera app`() {
        assertTrue(Backup.isCamera(pic(1, "DCIM/Camera/")))
        assertTrue(Backup.isCamera(pic(2, "DCIM/OpenCamera/")))
        assertTrue(Backup.isCamera(pic(3, "dcim/Camera/")))
        assertFalse(Backup.isCamera(pic(4, "Pictures/Screenshots/")))
        assertFalse(Backup.isCamera(pic(5, "Download/")))
        assertFalse(Backup.isCamera(pic(6, null)))
        // Already on the server: an album picture is never sent back to it.
        assertFalse(Backup.isCamera(pic(7, "DCIM/Camera/", remote = "abc")))
    }

    @Test
    fun `a backlog goes up oldest first, and what is done is left`() {
        val pictures = listOf(
            pic(1, "DCIM/Camera/", taken = 300),
            pic(2, "DCIM/Camera/", taken = 100),
            pic(3, "Pictures/Screenshots/", taken = 50),
            pic(4, "DCIM/Camera/", taken = 200),
        )
        val pending = Backup.pending(pictures) { it.id == 4L }
        assertEquals(listOf(2L, 1L), pending.map { it.id })
    }

    @Test
    fun `a picture edited in place counts as new`() {
        val dir = createTempDir()
        try {
            val record = BackupRecord(File(dir, "backup.json"))
            val original = pic(1, "DCIM/Camera/", size = 100, modified = 1_000)
            record.markDone(original)
            assertTrue(record.isDone(original))
            assertFalse(record.isDone(original.copy(size = 120, modified = 2_000)))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the record survives being read again, with how the last try went`() {
        val dir = createTempDir()
        try {
            val file = File(dir, "backup.json")
            val first = BackupRecord(file)
            val p = pic(9, "DCIM/Camera/")
            first.markDone(p)
            first.finish(BackupRecord.Problem.UNREACHABLE)

            val again = BackupRecord(file)
            assertTrue(again.isDone(p))
            assertEquals(BackupRecord.Problem.UNREACHABLE, again.status.problem)
            assertTrue(again.status.lastRun > 0)

            again.forget()
            assertFalse(BackupRecord(file).isDone(p))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the server knows this phone's copy by its id and size`() {
        assertEquals("garo-42-1234", Backup.deviceAssetId(pic(42, "DCIM/Camera/", size = 1234)))
    }
}
