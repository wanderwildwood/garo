package com.wanderwildwood.garo.tap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress

class TapUrlTest {
    private fun ip(s: String) = InetAddress.getByName(s)

    @Test fun buildsTheLink() {
        assertEquals("http://192.168.1.20:41234/abc", TapUrl.build(ip("192.168.1.20"), 41234, "abc"))
    }

    @Test fun theLinkFitsOnATag() {
        val link = TapUrl.build(ip("192.168.100.200"), 65535, TapUrl.newToken())
        assertEquals(0x03, Ndef.uriRecord(link)[4].toInt())
        assert(Ndef.file(Ndef.uriRecord(link)).size < 100)
    }

    @Test fun picksWifiOverEverythingElse() {
        val got = LocalAddress.pick(listOf(
            LocalAddress.Candidate("rmnet_data0", ip("10.20.30.40")),
            LocalAddress.Candidate("tun0", ip("100.64.0.9")),
            LocalAddress.Candidate("ap0", ip("192.168.43.1")),
            LocalAddress.Candidate("wlan0", ip("192.168.1.20")),
            LocalAddress.Candidate("lo", ip("127.0.0.1")),
        ))
        assertEquals(ip("192.168.1.20"), got)
    }

    @Test fun aHotspotCountsWhenThereIsNoWifi() {
        assertEquals(ip("192.168.43.1"), LocalAddress.pick(listOf(
            LocalAddress.Candidate("rmnet_data0", ip("10.20.30.40")),
            LocalAddress.Candidate("ap0", ip("192.168.43.1")),
        )))
    }

    @Test fun mobileDataAloneIsNothing() {
        assertNull(LocalAddress.pick(listOf(
            LocalAddress.Candidate("rmnet_data0", ip("10.20.30.40")),
            LocalAddress.Candidate("ccmni1", ip("10.1.2.3")),
            LocalAddress.Candidate("tun0", ip("10.8.0.2")),
            LocalAddress.Candidate("lo", ip("127.0.0.1")),
        )))
    }

    @Test fun downInterfacesPublicAddressesAndIpv6AreSkipped() {
        assertNull(LocalAddress.pick(listOf(
            LocalAddress.Candidate("wlan0", ip("192.168.1.20"), up = false),
            LocalAddress.Candidate("wlan0", ip("8.8.8.8")),
            LocalAddress.Candidate("wlan0", ip("fe80::1")),
            LocalAddress.Candidate("wlan0", ip("169.254.3.4")),
        )))
    }
}
