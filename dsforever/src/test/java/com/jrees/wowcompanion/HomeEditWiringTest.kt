package com.jrees.wowcompanion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * MOVABLE HOME, PHASE 2: EDIT MODE'S WIRING (the user's D4: three ways in; SPEC.txt sections 3, 4 and 12), read from the sources: the
 * host's routes in (Settings > Bottom screen's first tile, Y on the Home disc, Home first from a room), Start = Done, the disc's tick =
 * Done, the class wheel / LB picture / toolbar words suspended while editing, Home's guest "home" held for it (not in a fight), B and
 * Party mode's D-pad left alone; 1518: the autosave through the store's rules, Discard's write back, a held B on the direct feed, and no
 * tap of the add-on's while edit mode is live except the guest's own hold. Text-only, as the other host tests.
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
        assertTrue(host.contains("BottomTiles.editHome = { editHome(\"Settings' Edit Home tile\") }"))
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

    @Test fun doneAndTheSuspensions() {
        assertTrue(body(host, "private fun startPressed()").contains("if (room == Room.HOME && homeView.editing) { startReadAt = SystemClock.uptimeMillis(); homeView.editKey(\"PADFORWARD\"); return }"))
        assertTrue(body(host, "private fun swirlTouched()").contains("if (room == Room.HOME && homeView.editing) { homeView.editDone(); return }"))
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

    /** movable Home fix round 3: the wiring of the review's findings */
    @Test fun fixRound3Wiring() {
        // the hold never arms while hold to talk's pop-up is up (it lies over the chat and the map)
        assertTrue(body(home, "private fun holdBodyAt(").contains("if (castView.talkPanel != null) return null"))
        // the Zoom steppers: the app's own stepper look (no plate lit as a pick, the signs at 40)
        assertTrue(editView.contains("UiKit.choice(c, minus, r.words[0], false, textSize = STEP_TEXT); UiKit.choice(c, plus, r.words[1], false, textSize = STEP_TEXT)"))
        assertTrue(editView.contains("const val STEP_TEXT = 40f"))
        assertTrue(editView.contains("hintsFor(m, m.menu?.let { host.menuRows(it).getOrNull(m.menuRow)?.kind })"))
        // the toolbar mirror's last unanswered send ends Home's Save hold on the pictures (a cross-fade back to `d`)
        assertTrue(host.contains("else ui.postDelayed(mirrorGiveUp, ToolbarMirror.RESEND_MS + 200L)"))
        assertTrue(body(host, "private fun mirrorCheck(").contains("ui.removeCallbacks(mirrorAgain); ui.removeCallbacks(mirrorGiveUp)"))
        // Home back over a room-parked session: the draft laid in borrow itself (the Chat room's closing glide reads it at once)
        assertTrue(body(home, "fun borrow()").contains("if (editBackDue) editRestSync()"))
        assertTrue(home.contains("private fun editDraft(): HomeLayout? = edit?.takeIf { !it.parked || editBackDue }?.draft"))
    }

    /** movable Home fix round 1: the wiring the review found missing */
    @Test fun fixRound1Wiring() {
        // a Layouts reset ends edit mode (open or parked) BEFORE the stores are read again: no Save puts the old record back
        val reset = body(host, "private fun resetApply(")
        val cancel = reset.indexOf("if (ResetPlan.Kind.LAYOUTS in kinds && ::homeView.isInitialized) homeView.editCancel()")
        assertTrue("the Layouts reset cancels edit mode", cancel > 0)
        assertTrue("before HomeSettings.resetDone(", cancel < reset.indexOf("HomeSettings.resetDone(ResetPlan.Kind.LAYOUTS in kinds)"))
        // the owner: recorded at the open, checked when the store's owner changes, a park's resume and Save
        assertTrue(home.contains("HomeLayoutStore.onOwner { editUi.post { editOwnerCheck(\"the character changed\") } }"))
        assertTrue(body(home, "fun editOpen(").contains("editOwner = HomeLayoutStore.owner()"))
        assertTrue(body(home, "private fun editAwayBack()").contains("editOwnerCheck("))
        // the hold: cancelled when another room opens, its gates again at the fire
        assertTrue(body(home, "fun giveBack()").contains("holdCancel()"))
        assertTrue(body(home, "private fun holdFired()").contains("HomeEditModel.holdFire(fightShown(), editBlocked?.invoke() == true, holdStillOk(s))"))
        assertTrue(home.contains("private fun holdStillOk(s: EditStop): Boolean = live && holdBodyAt(holdX, holdY) == s && overlayOpen?.invoke() != true"))
        // a parked session writes no transform over the class / pause / LB slides
        val decor = body(home, "private fun editDecor()")
        assertTrue(decor.contains("val liveEdit = edit?.takeIf { !it.parked }"))
        assertTrue(decor.contains("(classOpen || wheelOpen || pauseT > 0f || pauseSide > 0f)) return"))
        // the map's own paint clipped out of a lifted widget's box
        assertTrue(body(home, "private fun dispatchDrawHome(").contains("for (b in liftClip) c.clipOutRect(b)"))
        // the first Chat glide: the room's frame less its margins (P4's bottomMargin = TRACKS), never the content's whole height
        val glide = body(host, "private fun chatGlideStart(")
        assertTrue(glide.contains("content.height - (lp?.let { it.topMargin + it.bottomMargin } ?: 0)"))
        assertFalse(glide.contains("?: content.height)"))
        // the party module's own gold row box is never a second cursor in edit mode; after 1518 it is a widget (dimmed, framed and
        // tagged as the others, a stop: the user's ruling 2026-11-22)
        assertTrue(editView.contains("p[HomePart.PARTY]?.let { putBox(EditStop.PARTY, it) }"))
        assertTrue(home.contains("partyView.boxShown = { !editLive() }"))
    }

    @Test fun fixRound2Wiring() {
        // a Layouts reset ends edit mode BEFORE the reset touches the prefs (Discard's fold put-back never outlives the reset)
        val reset = body(host, "private fun resetApply(")
        val cancel = reset.indexOf("homeView.editCancel()")
        assertTrue(cancel > 0 && cancel < reset.indexOf("resetRun.run(b, who, all, localGame)"))
        // 1518: no layouts - edit mode opens on the record only (no flips, no slots, no fold of a preset's); the band has no pieces
        // to move (the user's ruling 2026-11-22: the top bar stays still)
        assertTrue(body(home, "fun editOpen(").contains("HomeEditModel(saved, start)"))
        assertFalse(home.contains("editPiece"))
        assertFalse(body(home, "fun editOpen(").contains("foldOpen"))
        // the cursor's widget gone: the card and the bar (the model's set)
        assertTrue(body(home, "private fun editCursorCheck(").contains("if (m.cursor !in HomeEditModel.GONE_CHECKED) return"))
        // the push to / from Home moves by Home's FRAME (less the XP track's place) and clips Home's page to it; the clip goes at the end
        val push = body(host, "private fun roomMotionStart(")
        assertTrue(push.contains("val homeFrame = ((homeView.height.takeIf { it > 0 } ?: content.height) - homeView.trackInsetNow).coerceAtLeast(1)"))
        assertTrue(push.contains("leavingHome -> 0f to vh") && push.contains("to == Room.HOME -> 0f to -vh"))
        assertFalse("never the content's whole height for Home", push.contains("leavingHome -> 0f to h "))
        assertTrue(push.contains("homeMoving?.clipBounds = android.graphics.Rect(0, 0, content.width, homeFrame)"))
        assertTrue(body(host, "private fun roomMotionEnd(").contains("roomAnimClipped?.clipBounds = null"))
    }

    @Test fun theAutosaveAndNothingSentWhileLive() {
        // edit mode taps only its guest hold / free (GuestPad); the store is the one writer (the toolbar's switch at Done, the host's route)
        val section = home.substring(home.indexOf("MOVABLE HOME, PHASE 2: EDIT MODE"), home.indexOf("movable Home: the D-pad toast"))
        assertFalse("no TapBridge call of its own", section.contains("TapBridge."))
        assertTrue(section.contains("homeGuestTap(GuestPad.cells(pad, GUEST_ID, hold = hold))"))
        // 1518, the autosave: a settled draft (nothing lifted, no finger down) through the store's per-character rules, every effect batch
        val persist = body(home, "private fun editPersist(")
        assertTrue(persist.contains("if (!now && (m.parked || !m.settled)) return"))
        assertTrue(persist.contains("HomeLayoutStore.editSave(o, prev, m.draft)"))
        assertTrue(persist.contains("HomeSettings.saveLayout(rec)"))
        assertTrue(body(home, "private fun editRun(").contains("m?.let { editPersist(it) }"))
        // Done: the last write, then the toolbar's switch; Discard: the opening record back over the session's own changes
        val end = body(home, "private fun editEnd(")
        assertTrue(end.contains("if (write && !discard) editPersist(m, now = true)"))
        assertTrue(end.contains("HomeLayoutStore.editSave(o, prev, m.saved)"))
        // 1518 build 3: no toolbar switch in edit mode (the band's menu is gone: Settings > Bottom screen has it); the record's sides
        // (its mirror bit) are held on the pictures until the add-on's `d` says them
        assertFalse(home.contains("onEditToolbar"))
        assertTrue(end.contains("roomBarHold = if (write) HomeLayoutSolver.mirrorBit(HomeSettings.layout) else null"))
        assertFalse("no All characters from edit mode", end.contains("all ="))
        // the add-on hears the record's sides / fight bar only once edit mode is not live
        assertTrue(body(host, "private fun mirrorCheck(").contains("if (homeView.editing) { ui.removeCallbacks(mirrorAgain); ui.removeCallbacks(mirrorGiveUp); return }"))
        assertTrue(body(host, "private fun topCheck(").contains("if (homeView.editing) { ui.removeCallbacks(topAgain); return }"))
        assertTrue(host.contains("homeView.onEditLive = { on -> if (!on) ui.post { lastDpad?.let { mirrorCheck(it); topCheck(it) } } }"))
        // a held B on the direct feed (press and release) is Discard; the relayed guest's B stays the plain ladder
        assertTrue(host.contains("if (k == \"PAD2\" && !setupPad && room == Room.HOME && homeView.editing) homeView.editB(down) else homeKey(k, down, rep)"))
        assertTrue(body(home, "fun editB(").contains("if (!editHoldUp()) editKey(\"PAD2\")"))
        // a Layouts reset ends edit mode with nothing written
        assertTrue(home.contains("fun editCancel() { if (edit != null) editEnd(discard = false, write = false) }"))
        // never opened in a fight; a fight parks it
        assertTrue(body(home, "fun editOpen(").contains("if (fightShown()) { editRefuse(start); return false }"))
        assertTrue(body(home, "private fun editFightCheck(").contains("m.park()"))
    }
    /**
     * review round 2: the wiggle's ticker redraws the chrome and Home every frame the whole time edit mode is open (it never rests: the
     * user's pick 2026-11-21), so the per-frame
     * paths make no paints, blurs, maps, pairs or boxes: the chrome's text paints are cached (textP), the lift's shadow blur is made once,
     * the host's parts / the edit view's boxes / the lifted boxes are reused, the finger's offset comes back in a FloatArray
     */
    @Test fun theTickerFramesMakeNoPaintsOrMaps() {
        for (k in listOf("bodyPaint(", "titlePaint(", "numberPaint(")) {
            val uses = editView.split("UiKit." + k).size - 1
            assertTrue("HomeEditView makes its text paints once (textP): $k x $uses", uses == 1)
        }
        assertTrue("one blur per paint, made with it", editView.lines().filter { it.contains("BlurMaskFilter(") }.all { it.trimStart().startsWith("private val ") })
        for (f in listOf("private fun drawWidgets(", "private fun tag(", "private fun shadow(",
            "private fun frame(", "private fun goldBox(")) {
            val b = body(editView, f)
            for (bad in listOf("LinkedHashMap", "listOf(", "Triple(", "BlurMaskFilter(", ".map {", ".filter {")) assertFalse("$f: $bad", b.contains(bad))
        }
        val parts = body(home, "override fun parts(): Map<HomePart, RectF>").substringBefore("return partsOut")
        assertFalse("parts: a map", parts.contains("LinkedHashMap<HomePart, RectF>()")); assertFalse("parts: boxes", parts.contains("RectF(r)"))
        assertFalse("liftedBoxes", body(home, "fun liftedBoxes(): List<RectF>").contains("val out = ArrayList<RectF>()"))
        assertTrue("liftOffset", home.contains("private fun liftOffset(out: FloatArray)"))
        assertFalse("editDecor", body(home, "private fun editDecor(").contains("liftOffset()"))
        // the 2026-11-21 picks: the frame's keep-in (every pose, every frame) and the cursor's seat make no boxes, poses or matrices
        val motion = File(src, "HomeEditMotion.kt").readText()
        for ((f, b) in listOf("posedIn" to body(home, "private fun posedIn("), "keepIn" to body(motion, "fun keepIn("),
            "stopMatrix" to body(home, "private fun stopMatrix("), "editTick" to body(home, "private fun editTick("))
            .map { it.first to it.second.substringBefore("\n    }\n") }) {
            for (bad in listOf("RectF(", "Pose()", "Matrix()", "FloatArray(", "listOf(", "Pair(", " to ")) assertFalse("$f: $bad", b.contains(bad))
        }
        assertTrue("the seat's matrices are the view's own", home.contains("private val seatA = android.graphics.Matrix()"))
        // review round 3: the ticker redraws the room bar and Home's map paint every frame the whole time edit mode is open (it never
        // rests), so those draws make no boxes, lists, sublists or parsed colours either: the bar's rooms in pooled boxes over index
        // ranges, its tracks / tick / disc / pictures in its own boxes, the map's band and its buttons in Home's
        for ((f, b) in listOf("drawBar" to body(strip, "private fun drawBar("), "group" to body(strip, "private fun group("),
            "drawEditTick" to body(strip, "private fun drawEditTick("), "art" to body(strip, "private fun art("),
            "the map's paint" to body(home, "private fun dispatchDrawHome(").substringAfter("val mapPosed = ").substringBefore("if (slideSave >= 0) c.restoreToCount(slideSave)"))) {
            assertTrue("$f: read", b.length > 80)
            for (bad in listOf("RectF(", ".take(", ".drop(", "withIndex", "parseColor", "floatArrayOf(", "FloatArray(", "listOf(",
                "HomeCalendarButton.box(", "HomeMailButton.box(")) assertFalse("$f: $bad", b.contains(bad))
        }
        assertFalse("drawKitHome: its disc's box", body(strip, "private fun drawKitHome(").contains("RectF("))
        assertTrue("the bar's boxes pooled", strip.contains("private val hitPool = Array(Room.values().size) { RectF() }"))
        assertFalse("no boxed glide starts", strip.contains("HashMap<Room, Float>"))
        // never rests: no idle clock left in the motion, and the input no longer feeds one
        assertFalse(motion.contains("WIGGLE_IDLE")); assertFalse(motion.contains("fun poke("))
        assertFalse(home.contains("motion.poke("))
    }
}
