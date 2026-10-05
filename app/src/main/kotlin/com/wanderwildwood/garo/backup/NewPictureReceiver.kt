package com.wanderwildwood.garo.backup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Told by Camera (写真機 shashinki) the moment it saves a photo, so backing up starts then,
 * within the limits set — Wi-Fi, charging — rather than waiting on Android's own signal for a
 * new picture, which on the Kompakt does not always come.
 *
 * Open to any app: all it can do is ask for a backup the settings already allow, early. It
 * carries nothing and is told nothing back.
 */
class NewPictureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        BackupWorker.runSoon(context)
    }

    companion object {
        const val ACTION = "com.wanderwildwood.garo.action.NEW_PICTURE"
    }
}
