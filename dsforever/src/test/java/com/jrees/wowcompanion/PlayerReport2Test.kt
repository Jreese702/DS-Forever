package com.jrees.wowcompanion

import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A player's report (2026-10-14), off-device: the SD-card install, and the art's table errors over the sign-in */
class PlayerReport2Test {

    // ---- p-sd-card-install

    @Test fun internalIsFine() =
        assertNull(InstallCheck.external(0, true, "/data/app/~~x/app.dsforever.companion-1/base.apk", "/data/user/0/app.dsforever.companion"))

    @Test fun theSdCardIsSaid() {
        assertTrue(InstallCheck.external(InstallCheck.FLAG_EXTERNAL_STORAGE, true, "/mnt/asec/x/base.apk", "/data/user/0/x") != null)
        assertTrue(InstallCheck.external(0, false, "/mnt/expand/1234-abcd/app/x/base.apk", "/mnt/expand/1234-abcd/user/0/x")!!.startsWith("adopted"))
        assertTrue(InstallCheck.external(0, false, "/data/app/x/base.apk", "/data/user/0/x") != null)
    }

    @Test fun theSetupSaysItFirstAndPlayStays() {
        val i = SetupFlow.Inputs(path = "/g", folderFound = false, art = SetupFlow.Art.READY, installedExternal = true)
        val s = SetupFlow.screen(i)
        assertEquals("install", s.prompt?.id)
        assertEquals(SetupFlow.Action.OPEN_APP_SETTINGS, s.prompt?.buttons?.single()?.action)
        val ready = SetupFlow.screen(i.copy(folderFound = true, loginSaved = true))
        assertTrue(ready.play!!.enabled)                         // a device may still cope: Play is not taken away
        assertEquals(SetupFlow.Mark.BAD, SetupFlow.checks(i).first().mark)
    }

    // ---- p-build-tables-blocks-keyboard: the sign-in always wins at the login screen

    @Test fun theSignInPromptWinsOverAnArtFailureWhileTheGameRuns() {
        val failed = SetupFlow.Inputs(path = "/g", folderFound = true, art = SetupFlow.Art.FAILED,
            artWhy = "could not build kit: the game's atlas table did not read", gameRunning = true)
        for (st in listOf(SetupFlow.SignIn.FAILED, SetupFlow.SignIn.TYPING, SetupFlow.SignIn.RESTART, SetupFlow.SignIn.AUTH)) {
            val p = SetupFlow.screen(failed.copy(signIn = st)).prompt!!
            assertEquals("signin", p.id)
            assertTrue(st.name, p.buttons.any { it.action == SetupFlow.Action.ENTER_ON_GAME })   // Keyboard
        }
        assertNull(SetupFlow.screen(failed.copy(signIn = SetupFlow.SignIn.NONE)).prompt)   // and the art never takes its place
    }

    // ---- the CDN: every host, then once more; the real reason kept

    @Test fun aHiccupOnTheFirstPassIsRetried() {
        var calls = 0
        val pauses = ArrayList<Long>()
        val why = CdnRetry.run(listOf("a", "b"), pause = { pauses.add(it) }) { _ -> calls++; if (calls < 3) "HTTP 503" else null }
        assertNull(why)
        assertEquals(3, calls)
        assertEquals(listOf(CdnRetry.PAUSE_MS), pauses)
    }

    @Test fun theLastReasonIsKept() {
        val why = CdnRetry.run(listOf("a", "b"), pause = {}) { h -> if (h == "a") "HTTP 404" else throw SocketTimeoutException() }
        assertEquals("timed out from b", why)
        assertEquals("no CDN hosts in the game's .build.info", CdnRetry.run(emptyList(), pause = {}) { null })
        assertEquals("network error (reset)", CdnRetry.describe(IOException("reset")))
    }

    @Test fun aFinalAnswerStopsAtOnce() {
        // issue #7: a 404 for a game file the CDN keeps inside an archive is the same on every host; no second host, no pause
        val tried = ArrayList<String>(); val pauses = ArrayList<Long>()
        val why = CdnRetry.run(listOf("a", "b"), pause = { pauses.add(it) }, final = { it == "HTTP 404" }) { h -> tried.add(h); "HTTP 404" }
        assertEquals("HTTP 404 from a", why)
        assertEquals(listOf("a"), tried)
        assertEquals(emptyList<Long>(), pauses)
        // anything else still gets every host, twice
        tried.clear()
        CdnRetry.run(listOf("a", "b"), pause = {}, final = { it == "HTTP 404" }) { h -> tried.add(h); "HTTP 503" }
        assertEquals(listOf("a", "b", "a", "b"), tried)
    }

    @Test fun theSecondHostServesWhenTheFirstFails() {
        val tried = ArrayList<String>()
        assertNull(CdnRetry.run(listOf("a", "b"), pause = {}) { h -> tried.add(h); if (h == "a") "HTTP 500" else null })
        assertEquals(listOf("a", "b"), tried)
    }

    // ---- the art retries by itself

    @Test fun theArtRetriesAndSucceeds() {
        var n = 0
        val sleeps = ArrayList<Long>()
        val retries = ArrayList<String>()
        val r = HarvestRetry.run(sleep = { sleeps.add(it) }, onRetry = { _, _, why -> retries.add(why) }) {
            n++; if (n < 3) throw IOException("a game table could not be downloaded (x: HTTP 503 from a)") else "ok"
        }
        assertEquals("ok", r.getOrNull())
        assertEquals(HarvestRetry.DELAYS_MS.toList(), sleeps)
        assertEquals(2, retries.size)
    }

    @Test fun aRealFailureShowsItsLastReasonAfterTheTries() {
        var n = 0
        val r = HarvestRetry.run(sleep = {}) { n++; throw IllegalStateException("reason $n") }
        assertTrue(r.isFailure)
        assertEquals(1 + HarvestRetry.DELAYS_MS.size, n)
        assertEquals("reason 3", r.exceptionOrNull()!!.message)
    }

    @Test fun successFirstTimeNeverSleeps() {
        var slept = false
        assertEquals(1, HarvestRetry.run(sleep = { slept = true }) { 1 }.getOrNull())
        assertFalse(slept)
    }
}
