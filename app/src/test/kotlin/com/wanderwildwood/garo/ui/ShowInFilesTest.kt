package com.wanderwildwood.garo.ui

import com.wanderwildwood.garo.media.Picture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShowInFilesTest {
    private fun pic(name: String, path: String?, volume: String?, remote: String? = null) = Picture(
        id = 1, name = name, folderKey = "k", folderName = "f", path = path, volume = volume,
        taken = null, modified = 0, size = 1, width = 1, height = 1, remote = remote,
    )

    @Test fun phonePath() =
        assertEquals("/storage/emulated/0/Download/a.GIF", pic("a.GIF", "Download/", "external_primary").filePath)

    @Test fun cardPath() =
        assertEquals("/storage/ABCD-1234/DCIM/b.JPEG", pic("b.JPEG", "DCIM/", "abcd-1234").filePath)

    @Test fun noFolderNoPath() = assertNull(pic("a.png", null, null).filePath)

    @Test fun serverPictureHasNoPath() = assertNull(pic("a.png", "Download/", "external_primary", remote = "x").filePath)

    @Test fun contractStrings() {
        assertEquals("com.wanderwildwood.tana.action.SHOW_FILE", ShowInFiles.ACTION)
        assertEquals("path", ShowInFiles.EXTRA_PATH)
    }
}
