package com.wanderwildwood.garo.backup

import com.wanderwildwood.garo.backup.HomeAccount.Answer
import com.wanderwildwood.garo.backup.HomeAccount.Found
import com.wanderwildwood.garo.backup.HomeAccount.Held
import com.wanderwildwood.garo.backup.HomeAccount.Seen
import com.wanderwildwood.garo.backup.HomeAccount.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAccountTest {

    private val home = HomeAccount.HOME
    private val homeDebug = HomeAccount.HOME_DEBUG
    private val release = HomeAccount.HOME_CERT
    private val debugCert = HomeAccount.DEBUG_CERTS.first()
    private val strange = "00".repeat(32)
    private val ada = Held("https://cloud.example.org", "ada", "app-password-one", "stamp-1")

    // ---- certificates ----

    @Test fun homesCertificatesAreLowerCaseSha256() {
        for (cert in HomeAccount.DEBUG_CERTS + release) assertTrue(cert, Regex("[0-9a-f]{64}").matches(cert))
        assertFalse(release in HomeAccount.DEBUG_CERTS)
    }

    @Test fun sha256IsLowerCaseHex() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", HomeAccount.sha256(ByteArray(0)))
    }

    @Test fun onlyHomeSignedWithItsReleaseCertIsTrusted() {
        assertTrue(HomeAccount.trusted(home, listOf(release), false))
        assertTrue(HomeAccount.trusted(home, listOf(release), true))
        assertFalse(HomeAccount.trusted(home, listOf(strange), false))
        // Signed with Home's key and another as well.
        assertFalse(HomeAccount.trusted(home, listOf(release, strange), false))
        assertFalse(HomeAccount.trusted(home, emptyList(), false))
        assertFalse(HomeAccount.trusted(home, null, false))
        // Home's key on another package.
        assertFalse(HomeAccount.trusted("org.example.home", listOf(release), false))
        assertFalse(HomeAccount.trusted("org.example.home", listOf(release), true))
    }

    @Test fun homesDebugBuildIsTrustedOnlyByADebugBuild() {
        assertTrue(HomeAccount.trusted(homeDebug, listOf(debugCert), true))
        assertTrue(HomeAccount.trusted(homeDebug, HomeAccount.DEBUG_CERTS.toList(), true))
        assertFalse(HomeAccount.trusted(homeDebug, listOf(debugCert), false))
        assertFalse(HomeAccount.trusted(homeDebug, listOf(release), true))
        assertFalse(HomeAccount.trusted(homeDebug, listOf(debugCert, strange), true))
        // A debug key never stands in for the release one, even in a debug build.
        assertFalse(HomeAccount.trusted(home, listOf(debugCert), true))
        assertFalse(HomeAccount.trusted(home, listOf(debugCert), false))
    }

    // ---- which Home is asked ----

    private val gone = Seen(owner = null, installed = false, signers = null)

    private fun phone(vararg seen: Pair<String, Seen>): (String) -> Seen {
        val map = seen.toMap()
        return { pkg -> map[pkg] ?: gone }
    }

    @Test fun aReleaseBuildAsksOnlyHome() {
        assertEquals(listOf(home), HomeAccount.candidates(false))
        assertEquals(listOf(home, homeDebug), HomeAccount.candidates(true))
    }

    @Test fun theRealHomeIsFound() {
        val found = HomeAccount.decide(false, phone(home to Seen(home, true, listOf(release))))
        assertEquals(home, (found as Found.Home).pkg)
    }

    @Test fun noHomeOnThePhone() {
        assertTrue(HomeAccount.decide(false, phone()) is Found.None)
        assertTrue(HomeAccount.decide(true, phone()) is Found.None)
    }

    @Test fun anAddressTakenByAnotherAppIsNeverAsked() {
        // Home not installed, and an impostor holds com.wanderwildwood.iori.accounts.
        assertTrue(HomeAccount.decide(false, phone(home to Seen("org.example.thief", false, listOf(release)))) is Found.None)
        // Even while Home is installed.
        assertTrue(HomeAccount.decide(false, phone(home to Seen("org.example.thief", true, listOf(release)))) is Found.None)
    }

    @Test fun anAppNamedHomeWithAnotherKeyIsNeverAsked() {
        assertTrue(HomeAccount.decide(false, phone(home to Seen(home, true, listOf(strange)))) is Found.None)
        assertTrue(HomeAccount.decide(true, phone(home to Seen(home, true, listOf(debugCert)))) is Found.None)
        assertTrue(HomeAccount.decide(false, phone(home to Seen(home, true, listOf(release, strange)))) is Found.None)
    }

    @Test fun homeThatCannotBeCheckedJustNowIsUnsure() {
        // Installed, but its provider not to be found (being updated).
        assertTrue(HomeAccount.decide(false, phone(home to Seen(null, true, null))) is Found.Unsure)
        // The package manager could not say.
        assertTrue(HomeAccount.decide(false, phone(home to Seen(null, null, null))) is Found.Unsure)
        // Its certificates could not be read.
        assertTrue(HomeAccount.decide(false, phone(home to Seen(home, true, null))) is Found.Unsure)
        assertTrue(HomeAccount.decide(false, phone(home to Seen(home, true, emptyList()))) is Found.Unsure)
    }

    @Test fun homesDebugBuildIsFoundOnlyByADebugBuild() {
        val debugHome = phone(homeDebug to Seen(homeDebug, true, listOf(debugCert)))
        assertEquals(homeDebug, (HomeAccount.decide(true, debugHome) as Found.Home).pkg)
        assertTrue(HomeAccount.decide(false, debugHome) is Found.None)
        // A debug build with both: the release Home first.
        val both = phone(home to Seen(home, true, listOf(release)), homeDebug to Seen(homeDebug, true, listOf(debugCert)))
        assertEquals(home, (HomeAccount.decide(true, both) as Found.Home).pkg)
        // The release Home taken by an impostor, the debug Home real: the debug Home.
        val taken = phone(home to Seen("org.example.thief", false, listOf(strange)), homeDebug to Seen(homeDebug, true, listOf(debugCert)))
        assertEquals(homeDebug, (HomeAccount.decide(true, taken) as Found.Home).pkg)
    }

    @Test fun authorityAndCandidatesMatchHomesProvider() {
        assertEquals("com.wanderwildwood.iori.accounts", HomeAccount.authority(home))
        assertEquals("com.wanderwildwood.iori.debug.accounts", HomeAccount.authority(homeDebug))
    }

    // ---- Home's row ----

    private fun cols(vararg pairs: Pair<String, String?>): (String) -> String? {
        val map = pairs.toMap()
        return { map[it] }
    }

    @Test fun aWholeRowIsAnAccount() {
        val held = HomeAccount.row(false, cols("server" to "https://cloud.example.org/", "user" to "ada", "password" to "pw", "stamp" to "s1"))
        assertEquals(Held("https://cloud.example.org", "ada", "pw", "s1"), held)
    }

    @Test fun aRowWithAPartMissingIsNoAccount() {
        assertNull(HomeAccount.row(false, cols("user" to "ada", "password" to "pw", "stamp" to "s1")))
        assertNull(HomeAccount.row(false, cols("server" to "https://cloud.example.org", "password" to "pw")))
        assertNull(HomeAccount.row(false, cols("server" to "https://cloud.example.org", "user" to "ada", "password" to "")))
        assertNull(HomeAccount.row(false, cols("server" to "https://", "user" to "ada", "password" to "pw")))
        // No stamp is still an account (an older Home): it reads as changed each time.
        assertEquals("", HomeAccount.row(false, cols("server" to "https://c.example.org", "user" to "ada", "password" to "pw"))!!.stamp)
    }

    @Test fun plainHttpOnlyInADebugBuild() {
        val plain = cols("server" to "http://192.168.1.20", "user" to "ada", "password" to "pw", "stamp" to "s")
        assertNull(HomeAccount.row(false, plain))
        assertEquals("http://192.168.1.20", HomeAccount.row(true, plain)!!.server)
        assertNull(HomeAccount.row(true, cols("server" to "ftp://x.example.org", "user" to "ada", "password" to "pw")))
        assertNull(HomeAccount.row(true, cols("server" to "cloud.example.org", "user" to "ada", "password" to "pw")))
    }

    @Test fun thePasswordIsNeverPrinted() {
        assertFalse(ada.toString().contains(ada.password))
        assertTrue(ada.toString().contains("ada"))
    }

    // ---- following Home ----

    @Test fun nothingChangesWhileHomeHoldsTheSameStamp() {
        assertTrue(HomeAccount.follow(ada.who, "stamp-1", Answer.Has(ada)) is Step.Keep)
    }

    @Test fun whileHomeCannotBeAskedTheAppKeepsWhatItHas() {
        assertTrue(HomeAccount.follow(ada.who, "stamp-1", Answer.Unsure) is Step.Keep)
        assertTrue(HomeAccount.follow(ada.who, "", Answer.Unsure) is Step.Keep)
    }

    @Test fun withHomeGoneThePasswordIsLetGo() {
        val step = HomeAccount.follow(ada.who, "stamp-1", Answer.NoHome)
        assertTrue(step is Step.Drop && step.following)
        assertTrue(HomeAccount.follow(ada.who, "", Answer.NoHome) is Step.Keep)
    }

    @Test fun aNewPasswordForTheSameAccountIsTaken() {
        val changed = ada.copy(password = "app-password-two", stamp = "stamp-2")
        val step = HomeAccount.follow(ada.who, "stamp-1", Answer.Has(changed))
        assertEquals("app-password-two", (step as Step.Take).held.password)
    }

    @Test fun theFirstReadAfterThePressIsTaken() {
        assertTrue(HomeAccount.follow(ada.who, "", Answer.Has(ada)) is Step.Take)
    }

    @Test fun homeSigningOutLetsTheAccountGoAndKeepsFollowing() {
        val step = HomeAccount.follow(ada.who, "stamp-1", Answer.SignedOut)
        assertTrue(step is Step.Drop && step.following)
        // Already let go: nothing more to do while Home stays signed out.
        assertTrue(HomeAccount.follow(ada.who, "", Answer.SignedOut) is Step.Keep)
    }

    @Test fun theSameAccountComingBackIsTakenAgain() {
        val back = ada.copy(password = "app-password-new", stamp = "stamp-3")
        assertTrue(HomeAccount.follow(ada.who, "", Answer.Has(back)) is Step.Take)
    }

    @Test fun anotherAccountIsNeverTakenWithoutAPress() {
        val other = Held("https://cloud.example.org", "tomas", "app-password-three", "stamp-4")
        val step = HomeAccount.follow(ada.who, "stamp-1", Answer.Has(other))
        assertTrue(step is Step.Drop && !step.following)
        val elsewhere = ada.copy(server = "https://other.example.org", stamp = "stamp-5")
        val moved = HomeAccount.follow(ada.who, "", Answer.Has(elsewhere))
        assertTrue(moved is Step.Drop && !moved.following)
    }

    @Test fun aTrailingSlashDoesNotMakeAnotherAccount() {
        assertEquals(HomeAccount.who("https://cloud.example.org/", "ada"), ada.who)
    }
}
