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

/** the start-up chooser's routing (S1 "Chooser", streaming phase 2): shortcut extra -> mode, the remembered pick, the chooser */
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
        assertEquals(Route.Into(Mode.STREAM, By.SHORTCUT), StartMode.resolve("stream", null))
        assertEquals(Route.Into(Mode.LOCAL, By.SHORTCUT), StartMode.resolve("local", null))
        // the shortcut wins over the remembered pick
        assertEquals(Route.Into(Mode.STREAM, By.SHORTCUT), StartMode.resolve("stream", "local"))
        assertEquals(Route.Into(Mode.LOCAL, By.SHORTCUT), StartMode.resolve("local", "stream"))
    }

    @Test fun rememberedPickGoesStraightIn() {
        assertEquals(Route.Into(Mode.LOCAL, By.REMEMBERED), StartMode.resolve(null, "local"))
        assertEquals(Route.Into(Mode.STREAM, By.REMEMBERED), StartMode.resolve(null, "stream"))
        // a junk extra is no shortcut: the remembered pick still holds
        assertEquals(Route.Into(Mode.STREAM, By.REMEMBERED), StartMode.resolve("bogus", "stream"))
    }

    @Test fun chooserWhenNoMode() {
        assertEquals(Route.Chooser, StartMode.resolve(null, null))
        assertEquals(Route.Chooser, StartMode.resolve("", ""))
        assertEquals(Route.Chooser, StartMode.resolve("bogus", "old-value"))
    }

    @Test fun whatIsRemembered() {
        assertEquals("local", StartMode.remember(Route.Into(Mode.LOCAL, By.PICKED)))
        assertEquals("stream", StartMode.remember(Route.Into(Mode.STREAM, By.SHORTCUT)))
        assertNull(StartMode.remember(Route.Into(Mode.LOCAL, By.REMEMBERED)))   // nothing new to write
        assertNull(StartMode.remember(Route.Chooser))                          // the chooser never forgets the last pick
    }

    @Test fun shortcutWhileRunning() {
        // from the chooser, or the other mode: into the shortcut's mode
        assertEquals(Route.Into(Mode.STREAM, By.SHORTCUT), StartMode.onShortcut(Route.Chooser, "stream", gameUp = false, restarting = false))
        assertEquals(Route.Into(Mode.STREAM, By.SHORTCUT),
            StartMode.onShortcut(Route.Into(Mode.LOCAL, By.REMEMBERED), "stream", gameUp = false, restarting = false))
        // never away from a running game, a restart or a busy launch; nothing for the mode already in, or no extra
        assertNull(StartMode.onShortcut(Route.Into(Mode.LOCAL, By.PICKED), "stream", gameUp = true, restarting = false))
        assertNull(StartMode.onShortcut(Route.Into(Mode.LOCAL, By.PICKED), "stream", gameUp = false, restarting = true))
        assertNull(StartMode.onShortcut(Route.Into(Mode.LOCAL, By.PICKED), "stream", gameUp = false, restarting = false, busy = true))
        assertNull(StartMode.onShortcut(Route.Into(Mode.LOCAL, By.PICKED), "local", gameUp = false, restarting = false))
        assertNull(StartMode.onShortcut(Route.Chooser, null, gameUp = false, restarting = false))
    }

    /** LOCAL'S START-UP IS TODAY'S: the cold-start splash keeps the old rule (auto-launch && the game folder ready) */
    @Test fun localStartUpUnchanged() {
        for (auto in listOf(false, true)) for (ready in listOf(false, true)) {
            val today = auto && ready
            assertEquals(today, StartMode.splashAtStart(auto, ready, Route.Into(Mode.LOCAL, By.REMEMBERED)))
            assertEquals(today, StartMode.splashAtStart(auto, ready, Route.Into(Mode.LOCAL, By.SHORTCUT)))
            assertEquals(today, StartMode.splashAtStart(auto, ready, Route.Into(Mode.LOCAL, By.PICKED)))
            assertEquals(today, StartMode.splashAtStart(auto, ready, null))          // the mode never started: as before
            // nothing boots behind the chooser or in Streaming
            assertFalse(StartMode.splashAtStart(auto, ready, Route.Chooser))
            assertFalse(StartMode.splashAtStart(auto, ready, Route.Into(Mode.STREAM, By.REMEMBERED)))
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
        val local = Route.Into(Mode.LOCAL, By.REMEMBERED)
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
        // starts on the remembered pick's card (Local when none)
        assertEquals(Cell(Mode.LOCAL, Row.CARD), StartMode.startCell(null))
        assertEquals(Cell(Mode.STREAM, Row.CARD), StartMode.startCell("stream"))
        // the D-pad: the two cards and their shortcut buttons, no wrap
        val a = Cell(Mode.LOCAL, Row.CARD)
        assertEquals(Cell(Mode.STREAM, Row.CARD), StartMode.move(a, Dir.RIGHT))
        assertEquals(Cell(Mode.STREAM, Row.CARD), StartMode.move(StartMode.move(a, Dir.RIGHT), Dir.RIGHT))
        assertEquals(a, StartMode.move(a, Dir.LEFT))
        assertEquals(a, StartMode.move(a, Dir.UP))
        assertEquals(Cell(Mode.LOCAL, Row.SHORTCUT), StartMode.move(a, Dir.DOWN))
        assertEquals(Cell(Mode.STREAM, Row.SHORTCUT), StartMode.move(Cell(Mode.LOCAL, Row.SHORTCUT), Dir.RIGHT))
        // A
        assertEquals(Press.Pick(Mode.STREAM), StartMode.press(Cell(Mode.STREAM, Row.CARD)))
        assertEquals(Press.AddShortcut(Mode.LOCAL), StartMode.press(Cell(Mode.LOCAL, Row.SHORTCUT)))
    }

    @Test fun shortcutsCarryTheirMode() {
        for (m in Mode.values()) assertEquals(m, StartMode.parse(StartMode.shortcut(m).extra))
        assertNotEquals(StartMode.shortcut(Mode.LOCAL).id, StartMode.shortcut(Mode.STREAM).id)
        for (m in Mode.values()) assertTrue(StartMode.shortcut(m).shortLabel.length <= 10)     // the launcher's short label fits
    }
}
