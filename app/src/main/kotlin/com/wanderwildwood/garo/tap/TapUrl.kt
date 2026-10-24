package com.wanderwildwood.garo.tap

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.SecureRandom

/** The link the other phone opens. */
object TapUrl {
    fun build(address: InetAddress, port: Int, token: String): String =
        "http://${address.hostAddress}:$port/$token"

    /** 128 bits from the system's secure random, as 32 hex digits. */
    fun newToken(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Which of the phone's addresses the server may be bound to: one on the Wi-Fi (or the hotspot
 * it is itself running), and never one on mobile data, a VPN or the loopback.
 */
object LocalAddress {
    class Candidate(val interfaceName: String, val address: InetAddress, val up: Boolean = true)

    private val OK_NAMES = Regex("^(wlan|swlan|softap|ap|eth)\\d*$")

    /** Wi-Fi first, then the hotspot, then wired; null when there is none. */
    fun pick(candidates: List<Candidate>): InetAddress? {
        fun rank(c: Candidate) = when {
            c.interfaceName.startsWith("wlan") -> 0
            c.interfaceName.startsWith("eth") -> 2
            else -> 1
        }
        return candidates
            .filter { it.up && it.address is Inet4Address && it.address.isSiteLocalAddress && OK_NAMES.matches(it.interfaceName) }
            .minByOrNull(::rank)?.address
    }

    fun find(): InetAddress? = runCatching {
        val all = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().flatMap { nif ->
            val up = runCatching { nif.isUp && !nif.isLoopback }.getOrDefault(false)
            nif.inetAddresses.toList().map { Candidate(nif.name, it, up) }
        }
        pick(all)
    }.getOrNull()
}
