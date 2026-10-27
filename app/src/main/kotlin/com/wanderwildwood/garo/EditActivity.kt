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
import com.wanderwildwood.garo.ui.monochrome

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
                ThemeMMD(colorScheme = monochrome) {
                    EditScreen(
                        uri = uri,
                        onSaved = { saved ->
                            Toast.makeText(this, R.string.edit_saved, Toast.LENGTH_SHORT).show()
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
