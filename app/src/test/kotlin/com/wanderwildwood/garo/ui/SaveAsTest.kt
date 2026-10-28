package com.wanderwildwood.garo.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SaveAsTest {
    @Test fun editedNameKeepsTheStem() {
        assertEquals("Beach edited.jpg", SaveAs.editedName("Beach.jpg", png = false))
        assertEquals("Beach.v2 edited.jpg", SaveAs.editedName("Beach.v2.heic", png = false))
    }

    @Test fun pngStaysPng() {
        assertEquals("Screen edited.png", SaveAs.editedName("Screen.png", png = true))
    }

    @Test fun noNameFallsBackToPicture() {
        assertEquals("Picture edited.jpg", SaveAs.editedName(null, png = false))
        assertEquals("Picture edited.jpg", SaveAs.editedName("", png = false))
    }

    @Test fun receivedTypesGetTheirEnding() {
        assertEquals("png", SaveAs.extensionFor("image/png"))
        assertEquals("heic", SaveAs.extensionFor("image/heif"))
        assertEquals("jpg", SaveAs.extensionFor("image/x-unknown"))
    }

    @Test fun safeNameHasNoSlash() {
        assertEquals("a_b.jpg", SaveAs.safeName("a/b.jpg"))
        assertEquals("picture", SaveAs.safeName(""))
    }
}
