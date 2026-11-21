package com.jrees.wowcompanion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * MOVABLE HOME, PHASE 2: EDIT MODE'S WIRING (the user's D4: three ways in; SPEC.txt sections 3, 4 and 12), read from the sources: the
 * host's routes in (Settings > Bottom screen's first tile, Y on the Home disc, Home first from a room), Start = Save, the disc's tick =
 * Save, the class wheel / LB picture / toolbar words suspended while editing, Home's guest "home" held for it (not in a fight), B and
 * Party mode's D-pad left alone, no tap of the add-on's before Save except the guest's own hold. Text-only, as the other host tests.
 */
class HomeEditWiringTest {
    private val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
        .map { File(it) }.first { it.isDirectory }
    private val host = File(src, "CompanionHost.kt").readText()
    private val home = File(src, "HomeView.kt").readText()
    private val bar = File(src, "HomeBarSelect.kt").readText()
    private val tiles = File(src, "BottomTiles.kt").readText()
    private val strip = File(src, "Strip.kt").readText()
    private val model = File(src, "HomeEditModel.kt").readText()
    private val editView = File(src, "HomeEditView.kt").readText()

    private fun body(text: String, head: String): String {
        val i = text.indexOf(head)
        assertTrue("'$head' not found", i >= 0)
        val end = listOf("\n    /**", "\n    private fun ", "\n    fun ", "\n    override fun ").map { text.indexOf(it, i + head.length) }.filter { it > 0 }.minOrNull()
        return text.substring(i, end ?: text.length)
    }

    @Test fun theThreeWaysIn() {
        // Settings > Bottom screen: the FIRST tile, its A (a touch) = Edit Home, no band
        assertTrue(tiles.contains("val HOME_TILES = listOf(LAYOUT,"))
        assertTrue(tiles.contains("if (id == BottomTiles.LAYOUT) BottomTiles.editHome?.let { go -> focusIdx = i; selected = null; go(); return true }"))
        assertTrue(host.contains("BottomTiles.editHome = { editHome(\"Settings' Home layout tile\") }"))
        // Y on the Home disc from the bar cursor
        assertTrue(bar.contains("\"PAD4\" -> if (at == Room.HOME && onEditHome != null) { val go = onEditHome; close(); go?.invoke(); return }"))
        assertTrue(host.contains("barSelect.onEditHome = { editHome(\"Y on the Home disc\") }"))
        // from a room: Home first (the room slide), then edit mode on Chat
        val e = body(host, "private fun editHome(why: String)")
        assertTrue(e.contains("show(Room.HOME)"))
        assertTrue(e.contains("homeView.editOpen(EditStop.CHAT)"))
        // the hold: 450 ms, the ring from 150 ms, 24 px, the body only
        assertTrue(model.contains("const val HOLD_MS = 450L") && model.contains("const val RING_FROM_MS = 150L") && model.contains("const val HOLD_SLOP = 24f"))
        val hold = body(home, "private fun holdBodyAt(")
        assertTrue("never the chat's \"!\"", hold.contains("!chatPane.onBang("))
        assertTrue("never the zone band / calendar / mail", hold.contains("y < m.top + ZONE_BAND_BOTTOM"))
        assertTrue("never the zoom / auras", hold.contains("auraPane.owns("))
        assertTrue("never the card, never the party rows", hold.contains("Part.QUESTS") && hold.contains("Part.PARTY"))
        assertTrue("never armed in a fight (a slow touch there stays today's tap)", hold.contains("if (fightShown()) return null"))
    }

    @Test fun saveAndTheSuspensions() {
        assertTrue(body(host, "private fun startPressed()").contains("if (room == Room.HOME && homeView.editing) { startReadAt = SystemClock.uptimeMillis(); homeView.editKey(\"PADFORWARD\"); return }"))
        assertTrue(body(host, "private fun swirlTouched()").contains("if (room == Room.HOME && homeView.editing) { homeView.editSave(); return }"))
        // the class wheel and the LB picture share the host's overlay gate
        assertTrue(body(host, "private fun overlayUp()").contains("(room == Room.HOME && homeView.editing)"))
        assertTrue(host.contains("if (room == Room.HOME && homeView.editing) return@dpad"))
        // Home: B is edit mode's ladder (through the guest), Party mode's D-pad and the band's touch-cast wait
        // ... only while it is LIVE: parked by a fight, B shrinks the band, the band touch-casts and Party mode's D-pad works as always
        assertTrue(home.contains("private fun editLive(): Boolean = edit?.parked == false"))
        assertTrue(body(home, "private fun onB()").contains("if (editLive()) return"))
        assertTrue(home.contains("fun partyHoldsDpad(): Boolean = partyOnHere() && flow.full == null && isShown && !typeWanted && !editLive()"))
        assertTrue(body(home, "override fun onInterceptTouchEvent(").contains("if (editLive()) { barsDown = false; return false }"))
        // the guest "home" while editing, never while parked by a fight
        assertTrue(home.contains("fun homeOwnGuestWanted(): Boolean = chatWanted || mapWanted || auraPane.open != null || edit?.parked == false"))
        // the ribbon in the XP track, the disc's tick
        assertTrue(strip.contains("if (ribbon != null) ribbon(c, w, top, g0, g1)"))
        assertTrue(strip.contains("if (editTick) { drawEditTick(c); return }"))
    }

    /** movable Home fix round 1: the wiring the review found missing */
    @Test fun fixRound1Wiring() {
        // a Layouts reset ends edit mode (open or parked) BEFORE the stores are read again: no Save puts the old record back
        val reset = body(host, "private fun resetApply(")
        val cancel = reset.indexOf("if (ResetPlan.Kind.LAYOUTS in kinds && ::homeView.isInitialized) homeView.editCancel()")
        assertTrue("the Layouts reset cancels edit mode", cancel > 0)
        assertTrue("before HomeSettings.resetDone()", cancel < reset.indexOf("HomeSettings.resetDone()"))
        // the owner: recorded at the open, checked when the store's owner changes, a park's resume and Save
        assertTrue(home.contains("HomeLayoutStore.onOwner { editUi.post { editOwnerCheck(\"the character changed\") } }"))
        assertTrue(body(home, "fun editOpen(").contains("editOwner = HomeLayoutStore.owner()"))
        assertTrue(body(home, "private fun editAwayBack()").contains("editOwnerCheck("))
        // the hold: cancelled when another room opens, its gates again at the fire
        assertTrue(body(home, "fun giveBack()").contains("holdCancel()"))
        assertTrue(body(home, "private fun holdFired()").contains("if (!holdStillOk(s))"))
        assertTrue(home.contains("private fun holdStillOk(s: EditStop): Boolean = live && holdBodyAt(holdX, holdY) == s && overlayOpen?.invoke() != true"))
        // a parked session writes no transform over the class / pause / LB slides
        val decor = body(home, "private fun editDecor()")
        assertTrue(decor.contains("val liveEdit = edit?.takeIf { !it.parked }"))
        assertTrue(decor.contains("(classOpen || pauseT > 0f || pauseSide > 0f)) return"))
        // the map's own paint clipped out of a lifted widget's box
        assertTrue(body(home, "private fun dispatchDrawHome(").contains("for (b in liftClip) c.clipOutRect(b)"))
        // the first Chat glide: the room's frame less its margins (P4's bottomMargin = TRACKS), never the content's whole height
        val glide = body(host, "private fun chatGlideStart(")
        assertTrue(glide.contains("content.height - (lp?.let { it.topMargin + it.bottomMargin } ?: 0)"))
        assertFalse(glide.contains("?: content.height)"))
        // the party module dimmed in edit mode (its own gold row box is never a second cursor)
        assertTrue(editView.contains("dimRect(c, it, null)\n            frame(c, it, light = false)"))
        assertTrue(home.contains("partyView.boxShown = { !editLive() }"))
    }

    @Test fun nothingReachesTheAddOnBeforeSave() {
        // edit mode taps only its guest hold / free (GuestPad); Save is the one writer (the toolbar's switch through the host's own route)
        val section = home.substring(home.indexOf("MOVABLE HOME, PHASE 2: EDIT MODE"), home.indexOf("movable Home: the D-pad toast"))
        assertFalse("no TapBridge call of its own", section.contains("TapBridge."))
        assertTrue(section.contains("homeGuestTap(GuestPad.cells(pad, GUEST_ID, hold = hold))"))
        val leave = body(home, "private fun editLeave(save: Boolean)")
        // fix round 1: only to the record the draft was read under (HomeLayoutStore.editSave), never the preset's fold at Save
        assertTrue(leave.contains("HomeLayoutStore.editSave(o, m.saved, m.draft)"))
        assertTrue(leave.contains("HomeSettings.saveLayout(rec, all = m.allChars)"))
        assertFalse("a preset's fold is applied when picked, never at Save", leave.contains("folded?.let"))
        assertTrue(leave.contains("m.toolbarWant?.let { if (it != m.toolbarNow) onEditToolbar?.invoke(it) }"))
        // never opened in a fight; a fight parks it
        assertTrue(body(home, "fun editOpen(").contains("if (fightShown()) { editRefuse(start); return false }"))
        assertTrue(body(home, "private fun editFightCheck(").contains("m.park()"))
    }
}
