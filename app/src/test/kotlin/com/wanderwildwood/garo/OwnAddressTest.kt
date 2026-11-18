package com.wanderwildwood.garo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnAddressTest {
    private val pkg = "com.wanderwildwood.garo"

    @Test fun thisAppsOwnProvidersAreOwn() {
        assertTrue(isOwnAuthority("com.wanderwildwood.garo.files", pkg))
        assertTrue(isOwnAuthority("com.wanderwildwood.garo.darkmodeinit", pkg))
        assertTrue(isOwnAuthority("COM.wanderwildwood.garo.files", pkg))
        // With the user in front, as Android can write it.
        assertTrue(isOwnAuthority("0@com.wanderwildwood.garo.files", pkg))
    }

    @Test fun anyoneElsesAreNot() {
        assertFalse(isOwnAuthority("media", pkg))
        assertFalse(isOwnAuthority("com.android.externalstorage.documents", pkg))
        assertFalse(isOwnAuthority("com.wanderwildwood.tana.files", pkg))
        assertFalse(isOwnAuthority("com.wanderwildwood.garox.files", pkg))
        assertFalse(isOwnAuthority(null, pkg))
    }
}
