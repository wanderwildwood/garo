package com.wanderwildwood.garo.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrangeTest {

    private fun pic(
        id: Long,
        name: String = "IMG_$id.jpg",
        folder: String = "camera",
        folderName: String = "Camera",
        path: String = "DCIM/Camera/",
        volume: String = Arrange.PRIMARY_VOLUME,
        taken: Long? = null,
        modified: Long = 0L,
    ) = Picture(
        id = id,
        name = name,
        folderKey = folder,
        folderName = folderName,
        path = path,
        volume = volume,
        taken = taken,
        modified = modified,
        size = 0,
        width = 0,
        height = 0,
    )

    @Test
    fun `names are read as a person reads them`() {
        val names = listOf("IMG_10.jpg", "IMG_9.jpg", "IMG_100.jpg", "img_2.jpg", "IMG_010.jpg")
        val sorted = names.sortedWith(Arrange::natural)
        assertEquals("img_2.jpg", sorted[0])
        assertEquals("IMG_9.jpg", sorted[1])
        // 10 and 010 are the same number; either order is fine, but both come before 100.
        assertEquals("IMG_100.jpg", sorted.last())
    }

    @Test
    fun `natural order is a total order on a mix of digits and words`() {
        val names = listOf("a1b2", "a1b10", "a01b3", "1", "a", "", "10a", "b", "a1")
        val sorted = names.sortedWith(Arrange::natural)
        for (i in 0 until sorted.lastIndex) {
            assertTrue("${sorted[i]} after ${sorted[i + 1]}", Arrange.natural(sorted[i], sorted[i + 1]) <= 0)
        }
        assertEquals("", sorted.first())
    }

    @Test
    fun `the camera date wins over the file date, and the file date stands in when there is none`() {
        val oldPhotoCopiedToday = pic(1, taken = 1_000L, modified = 9_000L)
        val screenshotYesterday = pic(2, taken = null, modified = 5_000L)
        val newest = Arrange.sort(listOf(oldPhotoCopiedToday, screenshotYesterday), PictureOrder.NEWEST)
        assertEquals(listOf(2L, 1L), newest.map { it.id })
    }

    @Test
    fun `pictures from the same moment keep one order between loads`() {
        val same = (1L..5L).map { pic(it, taken = 1_000L) }
        val a = Arrange.sort(same, PictureOrder.NEWEST).map { it.id }
        val b = Arrange.sort(same.reversed(), PictureOrder.NEWEST).map { it.id }
        assertEquals(a, b)
        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), a)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), Arrange.sort(same.shuffled(), PictureOrder.OLDEST).map { it.id })
    }

    @Test
    fun `folders come newest first, by their newest picture`() {
        val pictures = listOf(
            pic(1, folder = "a", folderName = "Alpha", taken = 100L),
            pic(2, folder = "a", folderName = "Alpha", taken = 900L),
            pic(3, folder = "b", folderName = "Beta", taken = 500L),
        )
        val folders = Arrange.folders(pictures, FolderOrder.NEWEST, PictureOrder.NEWEST, "card")
        assertEquals(listOf("Alpha", "Beta"), folders.map { it.label })
        // The cover is the first picture in the folder's own order.
        assertEquals(2L, folders[0].cover?.id)

        val byName = Arrange.folders(pictures, FolderOrder.NAME, PictureOrder.OLDEST, "card")
        assertEquals(listOf("Alpha", "Beta"), byName.map { it.label })
        assertEquals(1L, byName[0].cover?.id)
    }

    @Test
    fun `two folders with one name are told apart, and one alone is left as it is`() {
        val pictures = listOf(
            pic(1, folder = "phone-cam", folderName = "Camera", path = "DCIM/Camera/"),
            pic(2, folder = "card-cam", folderName = "Camera", path = "DCIM/Camera/", volume = "1234-5678"),
            pic(3, folder = "pics-cam", folderName = "Camera", path = "Pictures/Camera/"),
            pic(4, folder = "shots", folderName = "Screenshots", path = "Pictures/Screenshots/"),
        )
        val labels = Arrange.folders(pictures, FolderOrder.NAME, PictureOrder.NEWEST, "card").map { it.label }.toSet()
        assertEquals(setOf("Camera · DCIM", "Camera · card", "Camera · Pictures", "Screenshots"), labels)
    }

    @Test
    fun `all pictures is every folder in one pile by date, and only when there are two folders`() {
        val pictures = listOf(
            pic(1, folder = "a", folderName = "Alpha", taken = 100L),
            pic(2, folder = "a", folderName = "Alpha", taken = 900L),
            pic(3, folder = "b", folderName = "Beta", taken = 500L),
        )
        val folders = Arrange.folders(pictures, FolderOrder.NAME, PictureOrder.NAME, "card")
        val all = Arrange.all(folders, PictureOrder.NAME, "All")!!
        assertEquals(Arrange.ALL, all.key)
        // By date even when folders' pictures go by name.
        assertEquals(listOf(2L, 3L, 1L), all.pictures.map { it.id })
        assertEquals(listOf(1L, 3L, 2L), Arrange.all(folders, PictureOrder.OLDEST, "All")!!.pictures.map { it.id })

        val one = Arrange.folders(pictures.take(2), FolderOrder.NAME, PictureOrder.NEWEST, "card")
        assertEquals(null, Arrange.all(one, PictureOrder.NEWEST, "All"))
    }
}
