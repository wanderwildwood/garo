package com.wanderwildwood.garo.media

import com.wanderwildwood.garo.media.BackupStatus.Line
import com.wanderwildwood.garo.media.BackupStatus.Then
import com.wanderwildwood.garo.media.BackupStatus.Wait
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupStatusTest {

    private val wifi = Conditions(network = true, unmetered = true, charging = false)
    private val wifiCharging = Conditions(network = true, unmetered = true, charging = true)
    private val mobile = Conditions(network = true, unmetered = false, charging = false)
    private val offline = Conditions(network = false, unmetered = false, charging = false)

    private fun line(
        now: Conditions,
        reach: Reach = Reach.UNKNOWN,
        whenTo: BackupWhen = BackupWhen.WIFI,
        problem: BackupRecord.Problem? = null,
        done: Int = 3,
        total: Int = 10,
        lastRun: Long = 0L,
        on: Boolean = true,
    ) = BackupStatus.of(on, problem, total, done, lastRun, whenTo, now, reach)

    private fun then(l: Line) = (l as Line.Progress).then

    @Test
    fun `on Wi-Fi with the server not answering it says the server, not Wi-Fi`() {
        assertEquals(Then.Unreachable, then(line(wifi, reach = Reach.UNREACHABLE)))
        assertEquals(Then.Unreachable, then(line(wifi, problem = BackupRecord.Problem.UNREACHABLE, lastRun = 5)))
        // Even under Wi-Fi-and-charger, off the charger: the server is the news.
        assertEquals(Then.Unreachable, then(line(wifi, reach = Reach.UNREACHABLE, whenTo = BackupWhen.WIFI_CHARGING)))
    }

    @Test
    fun `with no network at all it waits for one, whatever the server did last time`() {
        assertEquals(Then.Waiting(Wait.WIFI), then(line(offline, reach = Reach.UNREACHABLE)))
        assertEquals(Then.Waiting(Wait.NETWORK), then(line(offline, whenTo = BackupWhen.ANY_NETWORK)))
        assertEquals(Then.Waiting(Wait.WIFI_AND_CHARGER), then(line(offline, whenTo = BackupWhen.WIFI_CHARGING)))
    }

    @Test
    fun `on mobile data it waits for Wi-Fi only when told to`() {
        assertEquals(Then.Waiting(Wait.WIFI), then(line(mobile)))
        assertEquals(Then.Soon, then(line(mobile, whenTo = BackupWhen.ANY_NETWORK)))
    }

    @Test
    fun `on Wi-Fi off the charger it waits for the charger only`() {
        assertEquals(Then.Waiting(Wait.CHARGER), then(line(wifi, whenTo = BackupWhen.WIFI_CHARGING)))
        assertEquals(Then.Soon, then(line(wifiCharging, whenTo = BackupWhen.WIFI_CHARGING)))
    }

    @Test
    fun `an old failure is not repeated once the server has answered again`() {
        assertEquals(Then.LastRun(5), then(line(wifi, reach = Reach.OK, problem = BackupRecord.Problem.UNREACHABLE, lastRun = 5)))
    }

    @Test
    fun `all up says when it last looked, not what it waits for`() {
        assertEquals(Then.LastRun(9), then(line(offline, done = 10, total = 10, lastRun = 9)))
    }

    @Test
    fun `off, refused and nothing to do come before any count`() {
        assertEquals(Line.Off, line(wifi, on = false))
        assertEquals(Line.Refused, line(wifi, problem = BackupRecord.Problem.REFUSED))
        assertEquals(Line.Nothing, line(wifi, total = 0, done = 0))
    }

    @Test
    fun `a server that answered but would not take the pictures is said as that`() {
        assertEquals(Then.Failed, then(line(wifi, problem = BackupRecord.Problem.FAILED, lastRun = 5)))
    }

    @Test
    fun `videos go to Nextcloud, not to Immich`() {
        assertEquals(true, Backup.videosTo(Destination.NEXTCLOUD))
        assertEquals(false, Backup.videosTo(Destination.IMMICH))
    }
}
