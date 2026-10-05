package com.wanderwildwood.garo.media

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Parsing Immich 2.7.5's answers, shaped as its API spec gives them. */
class ImmichTest {

    private val album = Immich.Album(id = "a1", name = "Trip", count = 2, cover = null, newest = 0)

    @Test
    fun `albums carry their name, count, cover and newest picture`() {
        val json = JSONArray(
            """[{"id":"a1","albumName":"Trip","assetCount":12,"albumThumbnailAssetId":"c0ffee00-0000-4000-8000-000000000001",
                "lastModifiedAssetTimestamp":"2026-09-01T10:00:00.000Z","updatedAt":"2026-09-02T10:00:00.000Z"},
               {"id":"a2","albumName":"","assetCount":0,"albumThumbnailAssetId":null,"updatedAt":"2026-01-01T00:00:00.000Z"}]""",
        )
        val albums = Immich.parseAlbums(json)
        assertEquals("Trip", albums[0].name)
        assertEquals(12, albums[0].count)
        assertEquals("c0ffee00-0000-4000-8000-000000000001", albums[0].cover)
        assertEquals(1_788_256_800_000L, albums[0].newest)
        // No cover is null, not the word "null"; no name still gets something to show.
        assertNull(albums[1].cover)
        assertEquals("—", albums[1].name)
        // With no newest-picture date, the album's own change date stands in.
        assertEquals(1_767_225_600_000L, albums[1].newest)
    }

    @Test
    fun `pictures take the camera date, and leave it unknown rather than guess`() {
        val json = JSONArray(
            """[{"id":"c0ffee00-0000-4000-8000-000000000001","type":"IMAGE","originalFileName":"IMG_1.jpg",
                 "fileModifiedAt":"2026-08-01T00:00:00.000Z","width":3000,"height":4000,"originalMimeType":"image/jpeg",
                 "exifInfo":{"dateTimeOriginal":"2026-07-04T12:00:00.000+00:00","fileSizeInByte":123456,
                             "exifImageWidth":4000,"exifImageHeight":3000,"make":"Canon","model":"Canon EOS R6"}},
               {"id":"c0ffee00-0000-4000-8000-000000000002","type":"IMAGE","originalFileName":"shot.png",
                 "fileModifiedAt":"2026-08-02T00:00:00.000Z","exifInfo":{"dateTimeOriginal":null,"make":null,"model":null}},
               {"id":"c0ffee00-0000-4000-8000-000000000003","type":"VIDEO","originalFileName":"clip.mp4"}]""",
        )
        val pictures = Immich.parsePictures(json, album)
        assertEquals(2, pictures.size)

        val photo = pictures[0]
        assertEquals(1_783_166_400_000L, photo.taken)
        assertEquals(123_456L, photo.size)
        // The asset's own, upright size wins over EXIF's stored, sideways one.
        assertEquals(3000, photo.width)
        assertEquals(4000, photo.height)
        assertEquals("Canon EOS R6", photo.camera)
        assertEquals("c0ffee00-0000-4000-8000-000000000001", photo.remote)
        assertEquals(Immich.folderKey("a1"), photo.folderKey)

        val shot = pictures[1]
        assertNull(shot.taken)
        assertNull(shot.camera)
        assertEquals(1_785_628_800_000L, shot.`when`)
    }

    @Test
    fun `dates read with an offset or a Z, and the offset is applied`() {
        assertEquals(1_783_166_400_000L, Immich.time("2026-07-04T12:00:00.000+00:00"))
        assertEquals(1_783_166_400_000L, Immich.time("2026-07-04T12:00:00.000Z"))
        assertEquals(1_783_166_400_000L, Immich.time("2026-07-04T08:00:00-04:00"))
        assertNull(Immich.time("null"))
        assertNull(Immich.time("not a date"))
    }

    @Test
    fun `the maker is said once`() {
        assertEquals("Canon EOS R6", Immich.camera("Canon", "Canon EOS R6"))
        assertEquals("Google Pixel 4a", Immich.camera("Google", "Pixel 4a"))
        assertEquals("Canon", Immich.camera("Canon", ""))
        assertNull(Immich.camera(null, "null"))
    }

    @Test
    fun `two assets do not share a row key`() {
        assertNotEquals(
            Immich.longId("c0ffee00-0000-4000-8000-000000000001"),
            Immich.longId("c0ffee00-0000-4000-8000-000000000002"),
        )
    }
}
