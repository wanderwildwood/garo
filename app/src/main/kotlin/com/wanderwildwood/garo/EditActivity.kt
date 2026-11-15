package com.wanderwildwood.garo

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalCursorBlinkEnabled
import com.mudita.mmd.ThemeMMD
import com.wanderwildwood.garo.ui.EditScreen
import com.wanderwildwood.garo.ui.houseTypography
import com.wanderwildwood.garo.ui.monochrome
import com.wanderwildwood.garo.ui.ArmedScope

/**
 * Editing a picture, for this app's own viewer and for any other app's Edit — Files' share
 * sheet, Messaging, Email — in place of the phone's old built-in editor.
 *
 * Its own activity in the standard launch mode, so the saved picture goes back to the app that
 * asked, as the data of the result, readable by it.
 */
class EditActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        if (uri == null) {
            finish()
            return
        }
        setContent {
            // A blinking cursor is a repaint twice a second on an e-ink panel, for nothing.
            CompositionLocalProvider(LocalCursorBlinkEnabled provides false) {
                ThemeMMD(colorScheme = monochrome, typography = houseTypography) {
                    ArmedScope {
                        EditScreen(
                            uri = uri,
                            onSaved = { saved ->
                                // This app's own viewer says "Saved" at its foot until the next
                                // press. Another app's Edit (Files, Messaging) gets the picture
                                // back on a screen of its own, where nothing of this app's is
                                // left to say it: there a toast is the only way to tell.
                                if (callingActivity?.packageName != packageName) {
                                    Toast.makeText(this, R.string.edit_saved, Toast.LENGTH_SHORT).show()
                                }
                                val answer = Intent().setData(saved).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                answer.clipData = ClipData.newRawUri(null, saved)
                                setResult(Activity.RESULT_OK, answer)
                                finish()
                            },
                            onCancel = {
                                setResult(Activity.RESULT_CANCELED)
                                finish()
                            },
                        )
                    }
                }
            }
        }
    }
}
