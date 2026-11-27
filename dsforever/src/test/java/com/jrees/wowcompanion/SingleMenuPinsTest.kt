package com.jrees.wowcompanion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE DS MENU'S PAD ROUTING IN THE HOST (the 1536 pass 1), off-device from the source: the real input path (D-pad Down = the app's own
 * reader's early bar and the add-on's "home" command; the swirl button = the same cursor), ZL / ZR from the app's own reader only (the
 * room's guest holding), B's fold back into the row only out of a fight, a tap on the dim straight to the game, the row's B / close
 * freeing the bar's guest (the keys given back the moment it closes), and nothing new tapped into the game but the bar's own hold.
 */
class SingleMenuPinsTest {
    private val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
        .map { File(it) }.first { it.isDirectory }
    private val host = File(src, "CompanionHost.kt").readText()
    private val bar = File(src, "HomeBarSelect.kt").readText()
    private val menu = host.substringAfter("// ===== SINGLE: MENU").substringBefore("// ===== SINGLE: NPC")

    private fun fn(text: String, head: String): String {
        val i = text.indexOf(head)
        assertTrue("'$head' not found", i >= 0)
        return text.substring(i).substringBefore("\n    }\n")
    }

    @Test fun downIsStillTheBarCursorsOwnPath() {
        // the app's own reader: D-pad Down on Home with nobody holding opens the bar early; the add-on's "home" command confirms it
        val un = fn(host, "private fun padUnclaimed(")
        assertTrue(un.contains("k == \"PADDDOWN\"") && un.contains("barSelect.openEarly(room)"))
        assertTrue(bar.contains("\"home\" -> openOnHome(shown, blocked)"))
        // in single screen mode that cursor IS the menu's row: never a Home sub-state (the old 1240 panel), drawn by the menu
        assertTrue(host.contains("(barSelect.open && menu == null) || homeView.editing"))
        assertTrue(host.contains("menuRow = menuState().let { it == MenuRow.UP || it == MenuRow.HELD }"))
        assertTrue("the open cursor is the row", fn(host, "private fun menuStateNow()").contains("if (barSelect.open) {"))
        assertTrue(menu.contains("barSelect.drawn = { menu == null }"))
        assertTrue("the row stays until B (no idle close)", bar.contains("if (open && byCommand && !sticky())"))
    }

    @Test fun theSwirlButtonOpensTheSameRowWithTheCursorOnTheSwirl() {
        val c = fn(host, "private fun cornerRow()")
        assertTrue(c.contains("barSelect.openOnHome(room, blocked ="))
        assertTrue("a second touch drops it", c.contains("if (barSelect.open) {") && c.contains("barSelect.close(); return"))
    }

    @Test fun zlZrFromTheAppsOwnReaderOnlyWhileTheControllerIsOurs() {
        val un = fn(host, "private fun padUnclaimed(")
        assertTrue(un.contains("if (down && (k == DirectPadKeys.LT || k == DirectPadKeys.RT)) menuTrigger(k == DirectPadKeys.RT)"))
        val t = fn(menu, "private fun menuTrigger(")
        assertTrue("the ONE decision: the reader acts exactly while the launcher holds them back", t.contains("if (!SetupBridge.roomTriggers) return"))
        assertTrue("the room change is the usual route (its own leave / take)", t.contains("pickRoom(next)"))
        assertFalse("never a tap of its own", t.contains("TapBridge") || t.contains("guestTap"))
        val sync = fn(menu, "private fun menuTriggersSync()")
        assertTrue(sync.contains("held = capHolder.isNotEmpty() || padOwner.roomMode()"))
        assertTrue(sync.contains("SingleMenu.triggersHeld(menuMachine.phase, room,"))
        assertTrue("nothing of ours over the room", sync.contains("interactView.visibility == View.VISIBLE") && sync.contains("barSelect.open)"))
        assertTrue(sync.contains("SetupBridge.roomTriggers = live"))
    }

    @Test fun zlZrAreHeldBackFromTheGameWhileARoomOwnsThemAndGivenBackAtOnce() {
        // set at every look of the menu (the poll's too) and the moment the room closes (its look), cleared when the host goes
        val look = fn(menu, "private fun singleMenuLook(")
        assertTrue(look.contains("finally { menuTriggersSync() }"))
        assertTrue(fn(host, "private fun singlePlayEnd()").contains("SetupBridge.roomTriggers = false"))
        // the launcher: the digital keys held (a release always passes), the analog pull masked where player 1's state is written
        val root = listOf("..", ".").map { java.io.File(it) }.first { java.io.File(it, "app/src/main/java").isDirectory }
        val xs = java.io.File(root, "app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt").readText()
        assertTrue(xs.contains("trigger = com.jrees.wowcompanion.SetupBridge.roomTriggers)) {"))
        val wh = java.io.File(root, "app/src/main/java/com/winlator/winhandler/WinHandler.java").readText()
        // int/1536: WinHandler.holdOut's body lives in PadOut (no native library at its class init: MenuTriggersMarkTest loads it)
        assertTrue(wh.contains("return PadOut.holdOut(s);"))
        val po = java.io.File(root, "app/src/main/java/com/winlator/winhandler/PadOut.java").readText()
        val out = po.substringAfter("static GamepadState holdOut(GamepadState s) {").substringBefore("\n    }\n")
        assertTrue(out.contains("trig = com.jrees.wowcompanion.SetupBridge.INSTANCE.getRoomTriggers();"))
        // int/1536: the hold-back and hold to cast's mark share the triggers - a held-back trigger is free, the code goes on it
        // (HoldCast.trigOut; MenuTriggersMarkTest, app), the L2 / R2 bits cleared after the buttons are made from the player's state
        assertTrue(out.contains("o.triggerL = com.jrees.wowcompanion.HoldCast.trigOut(s.triggerL, trig, mk);") &&
            out.contains("o.triggerR = com.jrees.wowcompanion.HoldCast.trigOut(s.triggerR, trig, mk);"))
        assertTrue(out.contains("IDX_BUTTON_L2, false") && out.contains("IDX_BUTTON_R2, false"))
        assertTrue("the bits cleared after the buttons", out.indexOf("o.buttons = (short)") in 0 until out.indexOf("IDX_BUTTON_L2, false"))
        assertTrue("nothing written back early while the room holds them", out.contains("!band && !trig) return s;"))
        assertTrue("the game's own state object is never changed (a copy)", out.indexOf("o.copy(s);") in 0 until out.indexOf("HoldCast.trigOut("))
        val feed = wh.substringAfter("private void holdFeed(GamepadState s, boolean virtual) {").substringBefore("\n    }\n")
        assertTrue("the mark's free-trigger rule sees the triggers as the game gets them",
            feed.contains("HoldCast.trigIn(held ? 0f : s.triggerL, held ? 0f : s.triggerR);"))
        // int/1536: away (onStop, a park's too) the keys are the game's again; the return lets a room still on the stage take them back
        assertTrue(fn(host, "private fun stopBody(").contains("menuAway = true; menuTriggersSync()"))
        assertTrue(fn(host, "fun onStart()").contains("menuAway = false; menuTriggersSync()"))
        assertTrue(fn(menu, "private fun menuTriggersSync()").contains("!menuAway &&"))
    }

    @Test fun bFoldsOutOfAFightTheDimGoesToTheGame() {
        val show = fn(host, "private fun show(r: Room")
        assertTrue(show.contains("if (r == Room.HOME && was != Room.HOME) menuFoldBack(was)"))
        val fold = fn(menu, "private fun menuFoldBack(")
        assertTrue(fold.contains("SingleMenu.back(singleFight(), was,") && fold.contains("barSelect.openMenuAt(was, SingleMenu.FOLD_HOLD_MS)"))
        assertTrue(fold.contains("menuNoFold) return"))
        val dim = fn(host, "fun singleDimTap()")
        assertTrue(dim.contains("menuNoFold = true") && dim.contains("finally { menuNoFold = false }"))
        assertTrue("the row alone: a tap on the dim drops it", dim.contains("barSelect.open -> {") && dim.contains("barSelect.close()"))
    }

    @Test fun theKeysGoBackTheMomentTheMenuCloses() {
        val close = fn(bar, "fun close(freeNow: Boolean = true)")
        assertTrue("the bar's free on every close", close.contains("GuestPad.cells(padSource(), ID, hold = false)"))
        assertTrue("a fold's later hold never outlives the row", close.contains("removeCallbacks(askLater)"))
        val later = bar.substringAfter("private val askLater = Runnable {").substringBefore("\n    }\n")
        assertTrue("the fold's hold only while the row is still up", later.contains("if (!open || holdAsked) return@Runnable"))
        // the menu taps nothing into the game of its own: only the bar's guest (its hold / free) and the rooms' usual routes
        assertFalse(menu.contains("TapBridge.") || menu.contains("GameInput.") || menu.contains("guestTap("))
    }
}
