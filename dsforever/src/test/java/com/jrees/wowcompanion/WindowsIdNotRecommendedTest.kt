package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * WINDOWS ID IS NOT RECOMMENDED FOR BLZ51914400 (the user, 2026-11-16: "Stop recommending it"; the 1.0.32 sign-in triage, #288):
 * upstream's A/B found Original made no difference to 14400 for most testers. The setting stays at Settings > General > Account, the
 * start screen's Menu gets no entry for it, and nothing the start screen or the sign-in shows points players at it. Off-device.
 */
class WindowsIdNotRecommendedTest {

    private val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
        .map { File(it) }.first { it.isDirectory }
    private val i18n = listOf("../companion/app/src/main/assets/companion/i18n", "companion/app/src/main/assets/companion/i18n")
        .map { File(it) }.first { it.isDirectory }

    @Test fun theSettingStaysUnderGeneralAccount() {
        val b = SettingsTabs.Block.WINDOWS_ID
        assertEquals(SettingsTabs.Tab.GENERAL, b.tab)
        assertEquals(SettingsTabs.Group.ACCOUNT, b.group)
        assertEquals(listOf(WindowsId.ROW_ID), b.rows)
    }

    @Test fun theStartScreenAndTheSignInNeverNameIt() {
        // the start screen (its view, Menu, keys, presses, route, mode), the report page and its notice, and the sign-in's own words
        val files = listOf("StartView.kt", "StartMenu.kt", "StartKeys.kt", "StartPress.kt", "StartRoute.kt", "StartMode.kt",
            "ReportPage.kt", "ReportNotice.kt", "SignInCheck.kt", "SignInKeyboard.kt", "HomeLogin.kt", "HomeLoginPrompt.kt", "PreLoginTraffic.kt")
        for (n in files) {
            val f = File(src, n)
            assertTrue("$n exists", f.isFile)
            val t = f.readText()
            for (w in listOf("WindowsId", "Windows ID", "windowsId")) assertFalse("$n: $w", w in t)
        }
        // CompanionHost's start-screen lines (startView.line / the notices) never name it either
        val host = File(src, "CompanionHost.kt").readLines()
        val shown = host.filter { "startView." in it || "notice(" in it || "StartMenu." in it }
        assertTrue(shown.isNotEmpty())
        for (l in shown) assertFalse(l, "Windows ID" in l || "WindowsId" in l)
    }

    @Test fun itsRowSaysNothingOfTheSignIn() {
        // the beautify pass (2026-11-17): the row is General's compact row (GeneralTiles), its label and segments only; nothing drawn
        // for it claims it helps a refused sign-in
        val row = File(src, "GeneralTiles.kt").readLines().filter { "Row.WINDOWS_ID" in it }.joinToString("\n")
        assertTrue(row, row.isNotEmpty())
        for (w in listOf("BLZ", "14400", "sign-in", "Sign-in", "Battle.net", "disconnected")) assertFalse("the row: $w", w in row)
        // no translated line pairs it with the sign-in either
        for (l in listOf("es", "fr")) {
            val lines = File(i18n, "$l.json").readLines(Charsets.UTF_8).filter { it.startsWith("\"") && ("Windows ID" in it) }
            assertEquals(lines.toString(), 2, lines.size)               // the row's name and its restart line only
            for (x in lines) assertFalse(x, "BLZ" in x || "14400" in x || "sign" in x.lowercase())
        }
    }
}
