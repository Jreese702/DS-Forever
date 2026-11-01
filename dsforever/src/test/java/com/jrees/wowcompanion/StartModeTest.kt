package com.jrees.wowcompanion

import com.jrees.wowcompanion.StartMode.By
import com.jrees.wowcompanion.StartMode.Cell
import com.jrees.wowcompanion.StartMode.Dir
import com.jrees.wowcompanion.StartMode.Mode
import com.jrees.wowcompanion.StartMode.Need
import com.jrees.wowcompanion.StartMode.Press
import com.jrees.wowcompanion.StartMode.Route
import com.jrees.wowcompanion.StartMode.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The start-up chooser's routing (S1 "Chooser", streaming phase 2). The user's ruling (2026-10-23): the normal icon ALWAYS opens the
 * chooser; only a shortcut's dsf.mode skips it; nothing is remembered.
 */
class StartModeTest {
    @Test fun parsesTheShortcutExtra() {
        assertEquals(Mode.LOCAL, StartMode.parse("local"))
        assertEquals(Mode.STREAM, StartMode.parse(" STREAM "))
        assertNull(StartMode.parse(null))
        assertNull(StartMode.parse(""))
        assertNull(StartMode.parse("streaming"))          // only the ids the shortcuts carry
        assertEquals("dsf.mode", StartMode.EXTRA)
    }

    @Test fun shortcutSkipsTheChooser() {
        assertEquals(Route.Into(Mode.STREAM, By.SHORTCUT), StartMode.resolve("stream"))
        assertEquals(Route.Into(Mode.LOCAL, By.SHORTCUT), StartMode.resolve("local"))
        assertEquals(Route.Into(Mode.STREAM, By.SHORTCUT), StartMode.onCreate(null, "stream", recreated = false, gameUp = false, restarting = false))
        assertEquals(Route.Into(Mode.LOCAL, By.SHORTCUT), StartMode.onCreate(null, "local", recreated = false, gameUp = false, restarting = false))
    }

    @Test fun iconAlwaysOpensTheChooser() {
        assertEquals(Route.Chooser, StartMode.resolve(null))
        assertEquals(Route.Chooser, StartMode.resolve(""))
        assertEquals(Route.Chooser, StartMode.resolve("bogus"))
        // a fresh process: the chooser
        assertEquals(Route.Chooser, StartMode.onCreate(null, null, recreated = false, gameUp = false, restarting = false))
        // the process still alive after Local or Streaming was used (picked or by a shortcut): the icon's new activity = the chooser
        for (mode in Mode.values()) for (by in By.values()) {
            assertEquals(Route.Chooser, StartMode.onCreate(Route.Into(mode, by), null, recreated = false, gameUp = false, restarting = false))
        }
        // already on the chooser: stays
        assertNull(StartMode.onCreate(Route.Chooser, null, recreated = false, gameUp = false, restarting = false))
    }

    @Test fun nothingIsRemembered() {
        // the start's only input is the intent's extra: no prefs key, no "remembered" way in, the cursor never on a "last" mode
        val fields = StartMode::class.java.declaredFields.map { it.name }
        assertFalse("KEY_LAST" in fields)
        assertFalse("PREFS" in fields)
        assertEquals(listOf("SHORTCUT", "PICKED"), By.values().map { it.name })
        assertEquals(Cell(Mode.LOCAL, Row.CARD), StartMode.startCell())
    }

    @Test fun recreatedActivityKeepsItsMode() {
        val local = Route.Into(Mode.LOCAL, By.PICKED)
        assertNull(StartMode.onCreate(local, null, recreated = true, gameUp = false, restarting = false))
        // a running game, a restart or a busy launch is never taken away by an icon tap
        assertNull(StartMode.onCreate(local, null, recreated = false, gameUp = true, restarting = false))
        assertNull(StartMode.onCreate(local, null, recreated = false, gameUp = false, restarting = true))
        assertNull(StartMode.onCreate(local, null, recreated = false, gameUp = false, restarting = false, busy = true))
        // a shortcut's new activity over an existing route: the shortcut's guards
        assertEquals(Route.Into(Mode.STREAM, By.SHORTCUT), StartMode.onCreate(local, "stream", recreated = false, gameUp = false, restarting = false))
        assertNull(StartMode.onCreate(local, "stream", recreated = false, gameUp = true, restarting = false))
    }

    @Test fun shortcutWhileRunning() {
        // from the chooser, or the other mode: into the shortcut's mode
        assertEquals(Route.Into(Mode.STREAM, By.SHORTCUT), StartMode.onShortcut(Route.Chooser, "stream", gameUp = false, restarting = false))
        assertEquals(Route.Into(Mode.STREAM, By.SHORTCUT),
            StartMode.onShortcut(Route.Into(Mode.LOCAL, By.PICKED), "stream", gameUp = false, restarting = false))
        // never away from a running game, a restart or a busy launch; nothing for the mode already in, or no extra
        assertNull(StartMode.onShortcut(Route.Into(Mode.LOCAL, By.PICKED), "stream", gameUp = true, restarting = false))
        assertNull(StartMode.onShortcut(Route.Into(Mode.LOCAL, By.PICKED), "stream", gameUp = false, restarting = true))
        assertNull(StartMode.onShortcut(Route.Into(Mode.LOCAL, By.PICKED), "stream", gameUp = false, restarting = false, busy = true))
        assertNull(StartMode.onShortcut(Route.Into(Mode.LOCAL, By.PICKED), "local", gameUp = false, restarting = false))
        assertNull(StartMode.onShortcut(Route.Chooser, null, gameUp = false, restarting = false))
    }

    /** LOCAL'S START-UP IS TODAY'S: a shortcut start into Local keeps the old splash rule (auto-launch && the game folder ready) */
    @Test fun localStartUpUnchanged() {
        for (auto in listOf(false, true)) for (ready in listOf(false, true)) {
            assertEquals(auto && ready, StartMode.splashAtStart(auto, ready, Route.Into(Mode.LOCAL, By.SHORTCUT)))
            // the icon's chooser, a pick on it, Streaming: nothing boots behind them
            assertFalse(StartMode.splashAtStart(auto, ready, Route.Chooser))
            assertFalse(StartMode.splashAtStart(auto, ready, Route.Into(Mode.LOCAL, By.PICKED)))
            assertFalse(StartMode.splashAtStart(auto, ready, Route.Into(Mode.STREAM, By.SHORTCUT)))
            assertFalse(StartMode.splashAtStart(auto, ready, null))
        }
        // Local's routes all show today's start screen; the chooser and Streaming do not
        for (by in By.values()) assertTrue(StartMode.showsLocal(Route.Into(Mode.LOCAL, by)))
        assertFalse(StartMode.showsLocal(Route.Chooser))
        assertFalse(StartMode.showsLocal(Route.Into(Mode.STREAM, By.PICKED)))
        // Local still needs every one of today's checks
        assertEquals(listOf(Need.STORAGE, Need.GAME_FOLDER, Need.GAME_ART, Need.ADDONS), StartMode.needs(Mode.LOCAL))
        assertTrue(StartMode.localChecks(Mode.LOCAL))
    }

    /** with no route (ModeBridge never started) the launcher's pad path is exactly today's: B and A fall through */
    @Test fun setupKeysUnchangedWithoutAMode() {
        ModeBridge.reset()
        SetupBridge.keyHandler = null
        SetupBridge.reportKey = null
        assertFalse(SetupBridge.key(android.view.KeyEvent.KEYCODE_BUTTON_B, true))
        assertFalse(SetupBridge.key(android.view.KeyEvent.KEYCODE_BUTTON_B, false))
        assertFalse(SetupBridge.key(android.view.KeyEvent.KEYCODE_BUTTON_A, true))
        SetupBridge.keyHandler = { _, _ -> true }
        assertTrue(SetupBridge.key(android.view.KeyEvent.KEYCODE_BUTTON_A, true))
        SetupBridge.keyHandler = null
    }

    @Test fun streamingNeedsNoLocalGame() {
        assertEquals(listOf(Need.PC_LINK), StartMode.needs(Mode.STREAM))
        assertFalse(StartMode.localChecks(Mode.STREAM))
        assertFalse(Need.GAME_ART in StartMode.needs(Mode.STREAM))
    }

    @Test fun backToTheChooser() {
        val local = Route.Into(Mode.LOCAL, By.SHORTCUT)
        assertTrue(StartMode.canGoBack(local, gameUp = false, busy = false, restarting = false, loginFormOpen = false))
        assertTrue(StartMode.canGoBack(Route.Into(Mode.STREAM, By.PICKED), false, false, false, false))
        assertFalse(StartMode.canGoBack(local, gameUp = true, busy = false, restarting = false, loginFormOpen = false))
        assertFalse(StartMode.canGoBack(local, gameUp = false, busy = true, restarting = false, loginFormOpen = false))
        assertFalse(StartMode.canGoBack(local, gameUp = false, busy = false, restarting = true, loginFormOpen = false))
        assertFalse(StartMode.canGoBack(local, gameUp = false, busy = false, restarting = false, loginFormOpen = true))
        assertFalse(StartMode.canGoBack(Route.Chooser, false, false, false, false))
        assertFalse(StartMode.canGoBack(null, false, false, false, false))
    }

    @Test fun chooserCursor() {
        val a = StartMode.startCell()
        assertEquals(Cell(Mode.LOCAL, Row.CARD), a)
        // the D-pad: the two cards and their shortcut buttons, no wrap
        assertEquals(Cell(Mode.STREAM, Row.CARD), StartMode.move(a, Dir.RIGHT))
        assertEquals(Cell(Mode.STREAM, Row.CARD), StartMode.move(StartMode.move(a, Dir.RIGHT), Dir.RIGHT))
        assertEquals(a, StartMode.move(a, Dir.LEFT))
        assertEquals(a, StartMode.move(a, Dir.UP))
        assertEquals(Cell(Mode.LOCAL, Row.FOOT), StartMode.move(a, Dir.DOWN))
        assertEquals(Cell(Mode.STREAM, Row.FOOT), StartMode.move(Cell(Mode.LOCAL, Row.FOOT), Dir.RIGHT))
        // A
        assertEquals(Press.Pick(Mode.STREAM), StartMode.press(Cell(Mode.STREAM, Row.CARD)))
        assertEquals(Press.Report, StartMode.press(Cell(Mode.LOCAL, Row.FOOT)))
    }

    @Test fun shortcutsCarryTheirMode() {
        for (m in Mode.values()) assertEquals(m, StartMode.parse(StartMode.shortcut(m).extra))
        assertNotEquals(StartMode.shortcut(Mode.LOCAL).id, StartMode.shortcut(Mode.STREAM).id)
        for (m in Mode.values()) assertTrue(StartMode.shortcut(m).shortLabel.length <= 10)     // the launcher's short label fits
    }
}
