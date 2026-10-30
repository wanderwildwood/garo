package com.wanderwildwood.garo.ui

import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/** One app that can take what is being shared. */
data class ShareTarget(val label: String, val packageName: String, val className: String)

/** What to send, before it is an Intent: the action and the type, for one file or several. */
data class SendPlan(val action: String, val type: String)

/**
 * Gallery's own list of apps for "Send with an app", in place of Android's chooser, whose sheet
 * (with its picture preview and Edit shortcut) is not made for this panel.
 */
object ShareTargets {
    const val SEND = "android.intent.action.SEND"
    const val SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"

    /** Alphabetical by label, ignoring case; the same label falls back to the package name. */
    fun ordered(targets: List<ShareTarget>, self: String): List<ShareTarget> =
        targets.filter { it.packageName != self }
            .distinctBy { it.packageName to it.className }
            .sortedWith(compareBy<ShareTarget, String>(String.CASE_INSENSITIVE_ORDER) { it.label }.thenBy { it.packageName })

    /** One type shared as itself; several as their common type, their common family, or any. */
    fun plan(types: List<String?>): SendPlan {
        val known = types.map { it?.takeIf { t -> '/' in t } ?: "image/*" }
        if (known.size <= 1) return SendPlan(SEND, known.firstOrNull() ?: "image/*")
        val type = when {
            known.distinct().size == 1 -> known[0]
            known.map { it.substringBefore('/') }.distinct().size == 1 -> known[0].substringBefore('/') + "/*"
            else -> "*/*"
        }
        return SendPlan(SEND_MULTIPLE, type)
    }

    fun intent(uris: List<Uri>, types: List<String?>): Intent {
        val plan = plan(types)
        val send = Intent(plan.action).setType(plan.type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (plan.action == SEND) {
            send.putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        // The grant travels on the clip; Android hands read permission on only from there.
        send.clipData = ClipData.newRawUri(null, uris.first()).also { clip ->
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        }
        return send
    }

    fun find(pm: PackageManager, send: Intent, self: String): List<ShareTarget> =
        ordered(
            pm.queryIntentActivities(send, 0).mapNotNull { r ->
                val a = r.activityInfo ?: return@mapNotNull null
                if (!a.exported) return@mapNotNull null
                ShareTarget(r.loadLabel(pm).toString(), a.packageName, a.name)
            },
            self,
        )

    /** The exact component, so no chooser stands between the press and the app. */
    fun aimed(send: Intent, target: ShareTarget): Intent =
        Intent(send).setComponent(ComponentName(target.packageName, target.className))
}
