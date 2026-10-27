package com.wanderwildwood.garo.cloud

import com.wanderwildwood.garo.media.Picture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading Nextcloud's answers, choosing what to show and in what order, and the addresses asked. */
class CloudTest {

    /** A listing as Nextcloud 30 writes it for a folder holding a little of everything. */
    private val answer = """<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
 <d:response>
  <d:href>/remote.php/dav/files/tester/Holiday%20Pictures/</d:href>
  <d:propstat><d:prop>
   <d:resourcetype><d:collection/></d:resourcetype>
   <d:getlastmodified>Mon, 26 Oct 2026 10:00:00 GMT</d:getlastmodified>
   <oc:fileid>100</oc:fileid><oc:size>5000</oc:size><nc:has-preview>false</nc:has-preview>
  </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
  <d:propstat><d:prop><d:getcontentlength/><d:getcontenttype/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat>
 </d:response>
 <d:response>
  <d:href>/remote.php/dav/files/tester/Holiday%20Pictures/Beach%20day.JPG</d:href>
  <d:propstat><d:prop>
   <d:resourcetype/>
   <d:getcontentlength>2048</d:getcontentlength>
   <d:getlastmodified>Tue, 27 Oct 2026 12:21:58 GMT</d:getlastmodified>
   <d:getcontenttype>image/jpeg</d:getcontenttype>
   <oc:fileid>101</oc:fileid><oc:size>2048</oc:size><nc:has-preview>true</nc:has-preview>
  </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
 </d:response>
 <d:response>
  <d:href>/remote.php/dav/files/tester/Holiday%20Pictures/%E6%B5%B7%E3%81%AE%E5%86%99%E7%9C%9F.webp</d:href>
  <d:propstat><d:prop>
   <d:resourcetype/>
   <d:getcontentlength>1024</d:getcontentlength>
   <d:getlastmodified>Sun, 25 Oct 2026 08:00:00 GMT</d:getlastmodified>
   <d:getcontenttype>image/webp; charset=binary</d:getcontenttype>
   <oc:fileid>102</oc:fileid><nc:has-preview>true</nc:has-preview>
  </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
 </d:response>
 <d:response>
  <d:href>/remote.php/dav/files/tester/Holiday%20Pictures/clip.mov</d:href>
  <d:propstat><d:prop>
   <d:resourcetype/>
   <d:getcontentlength>900000</d:getcontentlength>
   <d:getlastmodified>Mon, 26 Oct 2026 09:00:00 GMT</d:getlastmodified>
   <d:getcontenttype>video/quicktime</d:getcontenttype>
   <oc:fileid>103</oc:fileid><nc:has-preview>false</nc:has-preview>
  </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
 </d:response>
 <d:response>
  <d:href>/remote.php/dav/files/tester/Holiday%20Pictures/notes.txt</d:href>
  <d:propstat><d:prop>
   <d:resourcetype/><d:getcontentlength>12</d:getcontentlength>
   <d:getcontenttype>text/plain</d:getcontenttype><oc:fileid>104</oc:fileid>
  </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
 </d:response>
 <d:response>
  <d:href>/remote.php/dav/files/tester/Holiday%20Pictures/Day%2010/</d:href>
  <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype><oc:fileid>105</oc:fileid><oc:size>300</oc:size></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
 </d:response>
 <d:response>
  <d:href>/remote.php/dav/files/tester/Holiday%20Pictures/Day%209/</d:href>
  <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype><oc:fileid>106</oc:fileid><oc:size>300</oc:size></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
 </d:response>
 <d:response>
  <d:href>/remote.php/dav/files/tester/Holiday%20Pictures/Empty/</d:href>
  <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype><oc:fileid>107</oc:fileid><oc:size>0</oc:size></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
 </d:response>
 <d:response>
  <d:href>/remote.php/dav/files/tester/Holiday%20Pictures/.thumbs/</d:href>
  <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype><oc:fileid>108</oc:fileid><oc:size>99</oc:size></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
 </d:response>
 <d:response>
  <d:href>/remote.php/dav/files/tester/Holiday%20Pictures/scan</d:href>
  <d:propstat><d:prop>
   <d:resourcetype/><d:getcontentlength>500</d:getcontentlength>
   <d:getlastmodified>Mon, 26 Oct 2026 11:00:00 GMT</d:getlastmodified>
   <d:getcontenttype>image/png</d:getcontenttype><oc:fileid>109</oc:fileid>
  </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
 </d:response>
</d:multistatus>"""

    private fun parsed() = Cloud.parse(answer.byteInputStream(), "tester")

    @Test
    fun `every property is read, names decoded and paths relative to the user's files`() {
        val items = parsed().associateBy { it.path }
        assertEquals(10, items.size)

        val folder = items.getValue("Holiday Pictures")
        assertTrue(folder.folder)
        assertEquals(5000L, folder.size)
        assertEquals(100L, folder.fileId)

        val beach = items.getValue("Holiday Pictures/Beach day.JPG")
        assertFalse(beach.folder)
        assertEquals("Beach day.JPG", beach.name)
        assertEquals(2048L, beach.size)
        assertEquals(1_793_103_718_000L, beach.modified)
        assertEquals("image/jpeg", beach.type)
        assertEquals(101L, beach.fileId)
        assertTrue(beach.hasPreview)

        // Unicode names come back as they are; a type's parameters are dropped.
        val sea = items.getValue("Holiday Pictures/海の写真.webp")
        assertEquals("image/webp", sea.type)
        assertEquals(1024L, sea.size)

        val clip = items.getValue("Holiday Pictures/clip.mov")
        assertFalse(clip.hasPreview)

        // A folder with no oc:size says nothing about being empty.
        assertEquals(300L, items.getValue("Holiday Pictures/Day 9").size)
    }

    @Test
    fun `a Nextcloud in a folder of its site, and full URLs, give the same paths`() {
        val withPrefix = answer.replace("<d:href>/remote.php", "<d:href>https://example.org/cloud/remote.php")
        val items = Cloud.parse(withPrefix.byteInputStream(), "tester")
        assertEquals(parsed().map { it.path }, items.map { it.path })
    }

    @Test
    fun `another user's paths are not taken for this one's`() {
        assertTrue(Cloud.parse(answer.byteInputStream(), "someone-else").isEmpty())
    }

    @Test
    fun `pictures and videos are known by name, then by type`() {
        fun item(name: String, type: String? = null) = CloudItem(path = "a/$name", folder = false, type = type)
        assertEquals(Kind.PICTURE, Cloud.kind(item("x.HEIC")))
        assertEquals(Kind.PICTURE, Cloud.kind(item("x.avif")))
        assertEquals(Kind.PICTURE, Cloud.kind(item("x.gif")))
        assertEquals(Kind.VIDEO, Cloud.kind(item("x.MKV")))
        assertEquals(Kind.VIDEO, Cloud.kind(item("x.webm")))
        assertEquals(Kind.PICTURE, Cloud.kind(item("scan", "image/png")))
        assertNull(Cloud.kind(item("x.txt", "text/plain")))
        assertNull(Cloud.kind(item("x.svg", "image/svg+xml")))
        assertNull(Cloud.kind(item("x.pdf")))
        assertNull(Cloud.kind(CloudItem(path = "a/b.jpg", folder = true)))
    }

    @Test
    fun `folders first by name as read, then pictures and videos newest first, the rest left out`() {
        val listing = Cloud.listing(parsed(), "Holiday Pictures/")
        // Day 9 before Day 10; the empty folder and the hidden one are not shown.
        assertEquals(listOf("Day 9", "Day 10"), listing.folders.map { it.name })
        assertEquals(
            listOf("Beach day.JPG", "scan", "clip.mov", "海の写真.webp"),
            listing.media.map { it.name },
        )
    }

    @Test
    fun `the top of the files lists its own folders and not itself`() {
        val items = listOf(
            CloudItem(path = "", folder = true, size = 10),
            CloudItem(path = "Photos", folder = true, size = 10),
            CloudItem(path = "Photos/Inside", folder = true, size = 10),
            CloudItem(path = "top.jpg", folder = false, modified = 5),
        )
        val listing = Cloud.listing(items, "")
        assertEquals(listOf("Photos"), listing.folders.map { it.path })
        assertEquals(listOf("top.jpg"), listing.media.map { it.path })
    }

    @Test
    fun `addresses percent-encode every name and keep the slashes`() {
        val server = "https://cloud.example.org/"
        assertEquals("https://cloud.example.org/remote.php/dav/files/tester/", Cloud.home(server, "tester"))
        assertEquals(
            "https://cloud.example.org/remote.php/dav/files/tester/Holiday%20Pictures/Beach%20day%20%231.jpg",
            Cloud.fileUrl(server, "tester", "/Holiday Pictures/Beach day #1.jpg"),
        )
        assertEquals(
            "https://cloud.example.org/remote.php/dav/files/tester/%E6%B5%B7/%C3%A9t%C3%A9%2B.jpg",
            Cloud.fileUrl(server, "tester", "海/été+.jpg"),
        )
        assertEquals("https://cloud.example.org/remote.php/dav/files/a%40b.example/Photos/", Cloud.folderUrl(server, "a@b.example", "Photos"))
        assertEquals("https://cloud.example.org/remote.php/dav/files/tester/", Cloud.folderUrl(server, "tester", ""))
        assertEquals(
            "https://cloud.example.org/index.php/core/preview?fileId=101&x=320&y=320&a=1",
            Cloud.previewUrl(server, 101, 320),
        )
    }

    @Test
    fun `cache keys change with the file and the size, and carry nothing but safe characters`() {
        val beach = CloudItem(path = "Holiday Pictures/Beach day.JPG", folder = false, size = 2048, modified = 1000, fileId = 101)
        assertEquals("101-1000-2048-320", Cloud.cacheKey(beach, 320))
        assertNotEquals(Cloud.cacheKey(beach, 320), Cloud.cacheKey(beach.copy(modified = 2000), 320))
        assertNotEquals(Cloud.cacheKey(beach, 320), Cloud.cacheKey(beach, 160))
        assertEquals("101-1000-2048-0.jpg", Cloud.originalKey(beach))

        // No file id from the server: the path's hash stands in, still a plain name.
        val odd = CloudItem(path = "a/../ü b/c.WEBP", folder = false, size = 1, modified = 1)
        val key = Cloud.originalKey(odd)
        assertTrue(key, key.all { it.isLetterOrDigit() || it == '-' || it == '.' })
        assertTrue(key.endsWith(".webp"))
        assertNotEquals(key, Cloud.originalKey(odd.copy(path = "a/../ü b/d.WEBP")))
    }

    @Test
    fun `folder keys and paths go both ways`() {
        assertEquals("nextcloud:Photos/Kompakt", Cloud.folderKey("/Photos/Kompakt/"))
        assertEquals("Photos/Kompakt", Cloud.pathOf("nextcloud:Photos/Kompakt"))
        assertEquals("", Cloud.pathOf(Cloud.folderKey("")))
        assertNull(Cloud.pathOf("immich:timeline"))
        assertNull(Cloud.pathOf(null))
        assertEquals("Photos", Cloud.parentOf("Photos/Kompakt"))
        assertEquals("", Cloud.parentOf("Photos"))
    }

    @Test
    fun `a picture made from a listing is on the server, with its id and kind`() {
        val clip = parsed().first { it.name == "clip.mov" }
        val picture: Picture = Cloud.picture(clip, "Holiday Pictures")
        assertEquals(103L, picture.id)
        assertTrue(picture.video)
        assertTrue(picture.onServer)
        assertNull(picture.path)
        assertEquals("nextcloud:Holiday Pictures", picture.folderKey)
        assertEquals(900000L, picture.size)
    }

    @Test
    fun `dates in WebDAV's form are read, and nonsense is not`() {
        assertEquals(0L, Cloud.httpDate("Thu, 01 Jan 1970 00:00:00 GMT"))
        assertNull(Cloud.httpDate("yesterday"))
    }
}
