package com.wanderwildwood.garo.backup

import com.wanderwildwood.garo.backup.Remote.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteTest {

    @Test
    fun `a picture goes under its own folder's path on the phone`() {
        assertEquals("DCIM/OpenCamera", Remote.dirFor("DCIM/OpenCamera/"))
        assertEquals("", Remote.dirFor(""))
        assertEquals("", Remote.dirFor(null))
        assertEquals("Photos/Kompakt/DCIM/Camera", Remote.join("/Photos/Kompakt/", "DCIM/Camera"))
        assertEquals("Photos", Remote.join("Photos", ""))
        assertEquals("", Remote.join("", ""))
    }

    @Test
    fun `the same name and size is already backed up`() {
        assertEquals(Step.Skip("IMG_1.jpg"), Remote.step("IMG_1.jpg", 100, 7, mapOf("IMG_1.jpg" to 100L)))
    }

    @Test
    fun `a free name is sent as it is`() {
        assertEquals(Step.Send("IMG_1.jpg"), Remote.step("IMG_1.jpg", 100, 7, mapOf("IMG_2.jpg" to 100L)))
    }

    @Test
    fun `the same name with another size is never written over`() {
        assertEquals(Step.Send("IMG_1_7.jpg"), Remote.step("IMG_1.jpg", 100, 7, mapOf("IMG_1.jpg" to 90L)))
        // Sent that way before: now there, so skipped.
        assertEquals(Step.Skip("IMG_1_7.jpg"), Remote.step("IMG_1.jpg", 100, 7, mapOf("IMG_1.jpg" to 90L, "IMG_1_7.jpg" to 100L)))
        assertEquals("notes_7", Remote.withId("notes", 7))
        assertEquals(".hidden_7", Remote.withId(".hidden", 7))
    }

    @Test
    fun `a typed address becomes https, and plain http is caught`() {
        assertEquals("https://cloud.example.com", LoginFlow.normalise(" cloud.example.com/ "))
        assertEquals("https://cloud.example.com/nc", LoginFlow.normalise("https://cloud.example.com/nc/"))
        assertTrue(LoginFlow.isPlain("http://cloud.example.com"))
        assertFalse(LoginFlow.isPlain("https://cloud.example.com"))
        assertFalse(LoginFlow.isPlain("cloud.example.com"))
    }

    @Test
    fun `a listing gives each file's decoded path and size, and marks folders`() {
        val xml = """<?xml version="1.0"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response><d:href>/remote.php/dav/files/anna/Photos/Kompakt/DCIM/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
                <d:propstat><d:prop><d:getcontentlength/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat>
              </d:response>
              <d:response><d:href>/remote.php/dav/files/anna/Photos/Kompakt/DCIM/My%20Shot.jpg</d:href>
                <d:propstat><d:prop><d:getcontentlength>2048</d:getcontentlength><d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
            </d:multistatus>"""
        val entries = parseMultistatus(xml.byteInputStream())
        assertEquals(
            listOf(
                Entry("/remote.php/dav/files/anna/Photos/Kompakt/DCIM/", -1, true),
                Entry("/remote.php/dav/files/anna/Photos/Kompakt/DCIM/My Shot.jpg", 2048, false),
            ),
            entries,
        )
        assertEquals("Photos/Kompakt/My%20Shot.jpg", encodePath("Photos/Kompakt/My Shot.jpg"))
    }
}
