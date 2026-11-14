package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The login triage (2026-11-13; the user: "yes on the triage things"; #274 #271 #261 #247 #237 #235 #248): the sign-in trail from the
 * game's own log lines, the report's Region line, and a downloading read that never says "past the login screen". Off-device.
 */
class LoginTriageTest {

    // ---- the sign-in trail: the game's lines (formats as WoW Forever writes them, General.log / Connection.log) -> one line of ours

    private val flow = listOf(
        "10/6 21:08:48.585  [N][Lua] Switching to screen=\"AccountLogin\" (from \"none\")",
        "10/6 21:09:08.861  [N][Connection] Glue login started loginType=\"(enum)0\" launcherPortal=\"nullopt\" loginPortal=\"eu.actual.battle.net:1119\"",
        "10/6 21:09:09.511  [N][Connection] Battle.net front connected connectionId=\"1\" title_id=\"5730135\" platform=\"WinA\" locale=\"enUS\" usedToken=\"false\"",
        "10/6 21:09:10.240  [N][Connection] Battle.net logon form received result=\"ERROR_OK (0)\" hasCaptcha=\"true\"",
        "10/6 21:09:40.240  [N][Connection] Battle.net logon form submission requested",
        "10/6 21:09:40.848  [N][Connection] Battle.net logon form submission response received result=\"ERROR_OK (0)\" authenticatorRequired=\"true\" legalRequired=\"false\"",
        "10/6 21:09:50.961  [N][Connection] Battle.net credentials response received result=\"ERROR_OK (0)\"",
        "10/6 21:09:51.178  [N][Connection] Battle.net logon completed result=\"ERROR_OK (0)\" numGameAccounts=\"2\"",
        "10/6 21:09:51.344  [N][Connection] Battle.net logon completed in glue",
        "10/6 21:09:57.590  [N][Connection] Realm connection connected realmAddress=\"70-1-2\"",
        "10/6 21:09:59.408  [N][Lua] Switching to screen=\"CharacterSelect\" (from \"login\")",
    )

    @Test fun theWholeSignInBecomesTheTrail() {
        assertEquals(listOf(
            "sign-in trail (game 21:08:48.585): the game's screen 'AccountLogin' (from 'none')",
            "sign-in trail (game 21:09:08.861): the game signs in at eu.actual.battle.net",
            "sign-in trail (game 21:09:09.511): Battle.net connected, remembered sign-in used false",
            "sign-in trail (game 21:09:10.240): the sign-in form came: ok, the game asks for a captcha",
            "sign-in trail (game 21:09:40.240): the sign-in form is sent",
            "sign-in trail (game 21:09:40.848): Battle.net's answer to the sign-in: ok, the authenticator code is asked",
            "sign-in trail (game 21:09:50.961): the credentials' answer: ok",
            "sign-in trail (game 21:09:51.178): Battle.net sign-in completed: ok",
            "sign-in trail (game 21:09:57.590): the realm connection is up",
            "sign-in trail (game 21:09:59.408): the game's screen 'CharacterSelect' (from 'login')",
        ), flow.mapNotNull { HomeLogin.trail(it) })
    }

    @Test fun theRefusalSaysTheGamesReason() {
        // BLZ51914400 is the game's 14400; Connection.log may carry no [Connection] tag and a CR
        assertEquals("sign-in trail (game 07:34:17.512): the game's refusal: Status has no name (14400)",
            HomeLogin.trail("11/13 07:34:17.512  [N] Fatal login error received result=\"Status has no name. (14400)\"\r"))
        assertEquals("sign-in trail (game 07:34:17.512): Battle.net's answer to the sign-in: WOW_ERROR (14500)",
            HomeLogin.trail("11/13 07:34:17.512  [N] Battle.net logon form submission response received result=\"WOW_ERROR (14500)\" authenticatorRequired=\"false\""))
        assertEquals("sign-in trail (game 07:34:17.512): the sign-in form came: ok, no captcha",
            HomeLogin.trail("11/13 07:34:17.512  [N] Battle.net logon form received result=\"ERROR_OK (0)\" hasCaptcha=\"false\""))
        assertEquals("sign-in trail: the sign-in form is sent", HomeLogin.trail("Battle.net logon form submission requested"))   // no clock
    }

    @Test fun nothingButTheGamesWords() {
        // an odd portal, an odd screen name, and the lines that carry account details: never copied
        assertEquals("sign-in trail (game 21:09:08.861): the game signs in at a portal it does not name",
            HomeLogin.trail("10/6 21:09:08.861  [N][Connection] Glue login started loginPortal=\"me@example.com\""))
        assertNull(HomeLogin.trail("10/6 21:09:19.408  [N][Lua] Switching to screen=\"<x> y\" (from \"login\")"))
        assertEquals("sign-in trail (game 21:09:19.408): the game's screen 'experiencepreset'",
            HomeLogin.trail("10/6 21:09:19.408  [N][Lua] Switching to screen=\"experiencepreset\" (from \"a b\")"))
        for (l in listOf(
            "10/6 21:09:11.344  [N][Connection] Battle.net account service game info received responseType=\"GameInfo\" name=\"Somebody\" numGameAccounts=\"2\"",
            "10/6 21:09:11.344  [D][Connection] Battle.net game account names loaded gameAccountNames=\"WoW1|WoW2\"",
            "10/6 21:09:09.651  [N][Connection] Battle.net logon form requested",
            "10/6 21:09:11.344  [N][Connection] Game account list not saved because accountName CVar is empty",
            "10/6 21:09:13.426  WCB1|doc|{}",
            "",
        )) assertNull(l, HomeLogin.trail(l))
    }

    @Test fun theTrailSurvivesTheReportsPrivacyPass() {
        // the report drops lines naming a password or login.txt and masks key=value secrets: the trail's words must pass through whole
        val lines = flow.mapNotNull { HomeLogin.trail(it) } +
            HomeLogin.trail("11/13 07:34:17.512  [N] Fatal login error received result=\"Status has no name. (14400)\"")!!
        for (l in lines) assertEquals(l, StartupDiag.sanitize(l, emptyList()))
    }

    // ---- the report's Region line

    private val stamp = { ms: Long -> "T$ms" }

    @Test fun aUsInstallSigningInToEuIsNamed() {
        // #248: .build.info branch us, Config.wtf portal eu, a US-zoned device
        val l = GameRegion.reportLine("eu", GameRegion.BY_MENU, 42L, "us", "eu", "US", stamp)
        assertEquals("Region: the game signs in at eu; chosen eu (the Start menu, T42); the install's branch us; the device's country US (us); " +
            "DIFFERS: the game signs in at eu while the install and the device say us", l)
    }

    @Test fun matchingRegionsAreJustListed() {
        assertEquals("Region: the game signs in at eu; chosen none (the install's own region stands); the install's branch eu; the device's country DE (eu)",
            GameRegion.reportLine(null, null, 0L, "eu", "eu", "DE", stamp))
        // an EU player who installed on the US build and chose EU: the device agrees with the portal, so no DIFFERS
        assertFalse("DIFFERS" in GameRegion.reportLine("eu", GameRegion.BY_PROMPT, 1L, "us", "eu", "FR", stamp))
        // Taiwan signs in through kr: a Taiwan build on a kr portal is the same region
        assertFalse("DIFFERS" in GameRegion.reportLine("tw", GameRegion.BY_PROMPT, 1L, "tw", "kr", "TW", stamp))
        // no Config.wtf yet: the choice makes the portal
        assertTrue(GameRegion.reportLine("kr", "", 0L, "us", null, "", stamp).startsWith(
            "Region: the game signs in at kr; chosen kr (source not kept by an older version); the install's branch us; the device's country ? (us); DIFFERS"))
    }

    // ---- a downloading read never says "past the login screen"

    @Test fun aReadDuringADownloadIsBusy() {
        val read = 1_000_000L
        assertTrue(PreLoginTraffic.busyAtRead(downloading = true, lastHeavyAt = 0L, readAt = read))            // #248: the line was on
        assertTrue(PreLoginTraffic.busyAtRead(false, lastHeavyAt = read - 1_000L, readAt = read))              // #235: 1.3 MB/s just before
        assertTrue(PreLoginTraffic.busyAtRead(false, lastHeavyAt = read - PreLoginTraffic.HEAVY_BEFORE_READ_MS, readAt = read))
        assertFalse(PreLoginTraffic.busyAtRead(false, lastHeavyAt = read - PreLoginTraffic.HEAVY_BEFORE_READ_MS - 1L, readAt = read))
        assertFalse(PreLoginTraffic.busyAtRead(false, lastHeavyAt = 0L, readAt = read))                        // a quiet login screen
    }

    @Test fun aDownloadingReadsBurstIsNotTheSignIn() {
        // a read with a quiet login screen: the burst after it is character select (as before)
        assertTrue(PreLoginTraffic.trafficIsSignIn(consumed = true, active = false, windowSeen = true, expiredUnread = false,
            sinceStartS = 30L, graceS = 60L))
        assertTrue(PreLoginTraffic.trafficIsSignIn(true, false, true, false, 30L, 60L, downloadAtRead = false))
        // #237 / #247 / #248: the read came during the download: its MB/s say nothing of the sign-in
        assertFalse(PreLoginTraffic.trafficIsSignIn(true, false, true, false, 30L, 60L, downloadAtRead = true))
        // the other paths are unchanged
        assertFalse(PreLoginTraffic.trafficIsSignIn(false, active = true, windowSeen = true, expiredUnread = false, sinceStartS = 99L, graceS = 60L, downloadAtRead = false))
        assertTrue(PreLoginTraffic.trafficIsSignIn(false, false, windowSeen = true, expiredUnread = false, sinceStartS = 60L, graceS = 60L, downloadAtRead = false))
    }
}
