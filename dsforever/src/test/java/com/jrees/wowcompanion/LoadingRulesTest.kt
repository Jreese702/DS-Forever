package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The start-up order and the top screen's words (the user, 2026-10-28): the chooser waits until the checks pass ("If a check fails ...
 * the chooser waits until every check passes"), the login / add-ons / optional rows never hold it; the checks box is terse.
 */
class LoadingRulesTest {
    private val ok = SetupFlow.Inputs(path = "/storage/emulated/0/WoW Forever", folderFound = true, art = SetupFlow.Art.READY)

    @Test fun theChooserWaitsForTheChecks() {
        assertTrue(SetupFlow.checksPass(ok))
        // what holds it: a red row, the art not ready, something busy, an out-of-date game
        assertFalse(SetupFlow.checksPass(ok.copy(folderFound = false)))
        assertFalse(SetupFlow.checksPass(ok.copy(wrongGame = true)))
        assertFalse(SetupFlow.checksPass(ok.copy(storage = false)))
        assertFalse(SetupFlow.checksPass(ok.copy(installedExternal = true)))
        assertFalse(SetupFlow.checksPass(ok.copy(staleData = "old")))
        assertFalse(SetupFlow.checksPass(ok.copy(art = SetupFlow.Art.RUNNING)))
        assertFalse(SetupFlow.checksPass(ok.copy(art = SetupFlow.Art.IDLE)))
        assertFalse(SetupFlow.checksPass(ok.copy(art = SetupFlow.Art.FAILED)))
        assertFalse(SetupFlow.checksPass(ok.copy(busy = "Updating the game")))
        assertFalse(SetupFlow.checksPass(ok.copy(gameOutdated = "1.60.2")))
        // what never holds it: the login (asked at Play), our add-ons (the first Play), the optional rows, the app's own update
        assertTrue(SetupFlow.checksPass(ok.copy(loginSaved = false, addonsInstalled = false, indoor = "not built",
            appUpdate = "1.0.14", appLine = "DS Forever 1.0.13: update 1.0.14 available", iconsLine = "Icons: 10 %")))
    }

    @Test fun anOkdStaleFolderNoLongerHoldsIt() {
        SetupBridge.publish(ok.copy(staleData = "old"))
        try {
            SetupBridge.staleOk = false
            assertFalse(SetupBridge.checksPass())
            SetupBridge.staleOk = true
            assertTrue(SetupBridge.checksPass())
        } finally { SetupBridge.staleOk = false; SetupBridge.publish(null) }
        assertFalse("nothing published = not passed", SetupBridge.checksPass())
    }

    @Test fun theChooserRouteRunsTheChecks() {
        assertTrue(StartMode.runsChecks(StartMode.Route.Chooser))
        assertTrue(StartMode.runsChecks(StartMode.Route.Into(StartMode.Mode.LOCAL, StartMode.By.PICKED)))
        assertFalse(StartMode.runsChecks(StartMode.Route.Into(StartMode.Mode.STREAM, StartMode.By.PICKED)))
        assertFalse(StartMode.runsChecks(null))
    }

    @Test fun theChecksBoxIsTerse() {
        val rows = SetupFlow.terse(listOf(
            SetupFlow.Check("Game folder: /storage/emulated/0/WoW Forever", SetupFlow.Mark.OK),
            SetupFlow.Check("Client build: 1.60.1.70634 (up to date)", SetupFlow.Mark.OK),
            SetupFlow.Check("DS Forever 1.0.13: up to date", SetupFlow.Mark.OK),
            SetupFlow.Check("Game art: 42% (tables)", SetupFlow.Mark.WAIT),
            SetupFlow.Check("Game folder: not found (/x)", SetupFlow.Mark.BAD),
        ))
        assertEquals(listOf("Game folder", "Client build", "DS Forever 1.0.13", "Game art", "Game folder: not found (/x)"), rows.map { it.label })
    }
}
