package com.wanderwildwood.garo.tap

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.nfc.cardemulation.HostApduService
import android.os.Handler
import android.os.Looper

/**
 * Share by tap: the whole engine behind one object. It depends on Android's NFC classes and on
 * the rest of this package, and on nothing of the app around it, so another app can take the
 * `tap` package and a manifest entry for [TapTagService] and be done.
 *
 * What it does for an [Offer]:
 *
 *  - a file: checks NFC is on and the phone has a Wi-Fi (or hotspot) address, copies nothing,
 *    starts a [OneShotServer] on that address, and makes the tag hold the link to it;
 *  - a short text: checks NFC only, and makes the tag hold the text itself.
 *
 * The tag is a [TypeFourTag] answered by [TapTagService], which is switched on while this is
 * running and off again by [stop]. Call [stop] when the screen is left.
 */
class TapShare(private val context: Context, private val onStatus: (Status) -> Unit) {

    sealed interface Status {
        /** Waiting for the other phone. [link] is the address to type if the tap will not do. */
        data class Ready(val link: String?, val text: String?) : Status
        data object NoNfc : Status
        data object NfcOff : Status
        data object NoWifi : Status
        /** The other phone has the whole file. */
        data object Sent : Status
        /** Time ran out, or the screen was left, or it was stopped. */
        data object Stopped : Status
        data object Failed : Status
    }

    private var server: OneShotServer? = null
    private var activity: Activity? = null
    private var running = false
    private val main = Handler(Looper.getMainLooper())

    private fun say(s: Status) { main.post { onStatus(s) } }

    /** Checks, then starts. Does nothing further when a check fails; the status says which. */
    fun start(activity: Activity, offer: Offer) {
        stop(silent = true)
        val adapter = NfcAdapter.getDefaultAdapter(context)
        if (adapter == null || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)) return say(Status.NoNfc)
        if (!adapter.isEnabled) return say(Status.NfcOff)

        val link: String?
        val message: ByteArray
        when (offer) {
            is Offer.Text -> {
                link = null
                message = runCatching { Ndef.forText(offer.text) }.getOrNull() ?: return say(Status.Failed)
            }
            is Offer.File -> {
                val address = LocalAddress.find() ?: return say(Status.NoWifi)
                val s = OneShotServer(address, offer.shareable) { state ->
                    when (state) {
                        OneShotServer.State.SENT -> end(Status.Sent)
                        OneShotServer.State.EXPIRED, OneShotServer.State.STOPPED -> end(Status.Stopped)
                        else -> Unit
                    }
                }
                val port = try { s.start() } catch (_: Exception) { return say(Status.Failed) }
                server = s
                link = TapUrl.build(address, port, s.token)
                message = Ndef.uriRecord(link)
            }
        }
        TapTagService.ndefFile = Ndef.file(message)
        TapTagService.tag.reset()
        this.activity = activity
        running = true
        context.packageManager.setComponentEnabledSetting(component(), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        prefer(adapter)
        main.postDelayed({ if (running) NfcAdapter.getDefaultAdapter(context)?.let(::prefer) }, 600)
        say(Status.Ready(link, (offer as? Offer.Text)?.text))
    }

    /** Make this app's tag the one that answers, while the screen is in front. */
    fun prefer(adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(context)) {
        val a = activity ?: return
        if (!running || adapter == null) return
        runCatching { CardEmulation.getInstance(adapter).setPreferredService(a, component()) }
    }

    private fun end(status: Status) {
        teardown()
        say(status)
    }

    fun stop(silent: Boolean = false) {
        val was = running || server != null
        teardown()
        if (was && !silent) say(Status.Stopped)
    }

    private fun teardown() {
        running = false
        server?.let { s -> server = null; s.stop() }
        TapTagService.ndefFile = null
        TapTagService.tag.reset()
        val a = activity
        activity = null
        runCatching {
            if (a != null) NfcAdapter.getDefaultAdapter(context)?.let { CardEmulation.getInstance(it).unsetPreferredService(a) }
        }
        runCatching {
            context.packageManager.setComponentEnabledSetting(component(), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
    }

    private fun component() = ComponentName(context, TapTagService::class.java)
}

/**
 * The tag itself, as far as the phone's NFC is concerned. Switched on only while [TapShare] runs
 * (the manifest has it disabled), and with nothing to hand out it answers "not found".
 */
class TapTagService : HostApduService() {
    override fun processCommandApdu(commandApdu: ByteArray, extras: android.os.Bundle?): ByteArray =
        tag.process(commandApdu)

    override fun onDeactivated(reason: Int) = tag.reset()

    companion object {
        @Volatile
        var ndefFile: ByteArray? = null
        val tag = TypeFourTag { ndefFile }
    }
}
