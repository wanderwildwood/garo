package com.wanderwildwood.garo.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WantedTest {

    @Test
    fun `any file, or any picture, takes every picture`() {
        assertTrue(Wanted("*/*", null).accepts("image/jpeg"))
        assertTrue(Wanted("image/*", null).accepts("image/heic"))
        assertTrue(Wanted(null, null).accepts(null))
    }

    @Test
    fun `the listed kinds decide when an app asks for any file and lists what it wants`() {
        // Messaging: */* with pictures and videos listed beside it.
        val messaging = Wanted("*/*", listOf("image/*", "video/*"))
        assertTrue(messaging.accepts("image/png"))
        // An account picture that takes two kinds only.
        val avatar = Wanted("*/*", listOf("image/jpeg", "image/png"))
        assertTrue(avatar.accepts("image/png"))
        assertFalse(avatar.accepts("image/webp"))
        assertFalse(avatar.accepts(null))
    }

    @Test
    fun `a request that cannot take a picture takes none`() {
        assertFalse(Wanted("audio/*", null).accepts("image/jpeg"))
        assertFalse(Wanted("*/*", listOf("application/pdf")).accepts("image/jpeg"))
    }

    @Test
    fun `the old cursor names mean a picture`() {
        assertTrue(Wanted("vnd.android.cursor.dir/image", null).accepts("image/gif"))
    }
}
