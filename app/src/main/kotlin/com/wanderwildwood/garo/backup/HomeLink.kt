package com.wanderwildwood.garo.backup

import android.content.Context
import com.wanderwildwood.garo.media.Destination
import com.wanderwildwood.garo.media.Settings

/**
 * This app's side of using Home's Nextcloud account: asks Home and does to the settings what
 * [HomeAccount.follow] says. Used on every start and resume of the app, on each change notice
 * from Home and before each backup. Never on the main thread: it asks another app.
 */
object HomeLink {

    /** What came of one asking: Home's answer, and what was done with it. */
    class Result(val answer: HomeAccount.Answer, val step: HomeAccount.Step)

    fun follow(context: Context, settings: Settings = Settings(context)): Result {
        val answer = HomeAccount.ask(context)
        val who = settings.homeWho()
        if (who.isEmpty()) return Result(answer, HomeAccount.Step.Keep)
        // A mirror that cannot be opened any more (the keystore key gone) is taken again.
        val stamp = if (settings.nextcloud() == null) "" else settings.homeStamp()
        val step = HomeAccount.follow(who, stamp, answer)
        when (step) {
            is HomeAccount.Step.Keep -> Unit
            is HomeAccount.Step.Take -> {
                val held = step.held
                // Not sealed: the old copy is not kept in its place either. Taken again next time.
                if (!settings.takeHome(held.who, held.stamp, Account(held.server, held.user, held.password))) {
                    settings.dropHome(following = true)
                }
            }
            is HomeAccount.Step.Drop -> {
                settings.dropHome(step.following)
                if (!step.following) {
                    // Another account in Home: nothing recorded as up belongs to it, and backing
                    // up stops until the row is pressed again.
                    BackupWorker.record(context, Destination.NEXTCLOUD).forget()
                    if (settings.destination() == null) settings.writeBackupOn(false)
                }
            }
        }
        return Result(answer, step)
    }

    /** Whether to offer the row "Use the phone's Nextcloud account": Home has one, and it is not in use already. */
    fun offer(settings: Settings, answer: HomeAccount.Answer): Boolean =
        answer is HomeAccount.Answer.Has && !settings.usingHome()
}
