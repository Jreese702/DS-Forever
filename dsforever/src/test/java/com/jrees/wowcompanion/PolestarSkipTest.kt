package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * pk1 (the user, 2026-11-12: "we need to make a way to skip a polestar objective, idealy in the home map moduale with X or Y, maybe we
 * also add a previous step button? idk"; the picks: X Skip, Y Previous = "Undo my last skip", no confirm, Home's map + the Map room +
 * the pause wheel): the step doc's sk / u, the taps, the light blue row's X / Y and hints (merged with a quest and not), the Map room's
 * Polestar row and the pause wheel's two slices.
 */
class PolestarSkipTest {
    private fun step(sk: Boolean, u: Int, q: Int = 0) = PolestarStep.parse(JSONObject(
        """{"type":"pstep","v":"ps6","t":"Wolves","g":"Kill wolves","q":$q,"m":1429,"x":0.4,"y":0.6${if (sk) ",\"sk\":true,\"u\":$u" else ""}}"""))

    @Test fun theStepDocSaysSkipAndHowManyCanBeUndone() {
        val s = step(sk = true, u = 2)!!
        assertTrue(s.skip); assertEquals(2, s.undo)
        val old = step(sk = false, u = 0)!!                                  // an older add-on: no sk, no u
        assertFalse(old.skip); assertEquals(0, old.undo)
        assertTrue(PolestarSkip.canSkip(true, s)); assertTrue(PolestarSkip.canPrev(true, s))
        assertFalse(PolestarSkip.canSkip(true, old)); assertFalse(PolestarSkip.canPrev(true, old))
        assertFalse("nothing to undo", PolestarSkip.canPrev(true, step(sk = true, u = 0)))
        assertFalse("not shown (another setting)", PolestarSkip.canSkip(false, s))
        assertFalse(PolestarSkip.canSkip(true, null))
    }

    @Test fun theTapsAre38Then10Or11() {
        assertEquals(listOf(38, 10), PolestarSkip.cols(prev = false))
        assertEquals(listOf(38, 11), PolestarSkip.cols(prev = true))
        // never the row's other words: 8 / 9 the row holds, 0-7 the automation mask
        assertTrue(PolestarSkip.SKIP !in 0..9 && PolestarSkip.PREV !in 0..9)
        assertEquals(PolestarNav.PS_SAY, PolestarSkip.SAY)
    }

    private val g = emptyList<Pair<String, List<Int>>>()

    @Test fun xSkipsAndYGoesBackOnTheLightBlueRow() {
        val plain = MapList.stops(MapList.items(true, listOf(7, 8), g, polestar = true) { false })
        val c = MapCursor(); c.touched(MapList.POLESTAR, plain)
        assertEquals(MapCursor.Do.PsMenu, c.key("PAD3", plain, MapList.POLESTAR, false, psMenu = true, psSteps = true))
        assertEquals(MapCursor.Do.PsSteps, c.key("PAD4", plain, MapList.POLESTAR, false, psMenu = true, psSteps = true))
        assertEquals(MapCursor.Do.Polestar, c.key("PAD1", plain, MapList.POLESTAR, false, psMenu = true, psSteps = true))
        // pm3 (the user's pick, 2026-11-16: "Yes, match the Map room"): X the Polestar menu, Y the Next / Previous step choice; no
        // guide: Y does nothing; no menu: X does nothing
        assertEquals(MapCursor.Do.Nothing, c.key("PAD4", plain, MapList.POLESTAR, false, psMenu = true, psSteps = false))
        assertEquals(MapCursor.Do.Nothing, c.key("PAD3", plain, MapList.POLESTAR, false, psMenu = false, psSteps = false))
    }

    @Test fun mergedWithAQuestXAndYAreStillSkipAndPrevious() {
        val merged = MapList.stops(MapList.items(true, listOf(7, 8), g, polestar = true, merged = 7) { false })
        val c = MapCursor(); c.touched(MapList.POLESTAR, merged)
        assertEquals(MapCursor.Do.PsMenu, c.key("PAD3", merged, MapList.POLESTAR, false, psMenu = true, psSteps = true))
        assertEquals(MapCursor.Do.PsSteps, c.key("PAD4", merged, MapList.POLESTAR, false, psMenu = true, psSteps = true))
        // that quest's Track / Open are not on X / Y any more
        for (k in listOf("PAD3", "PAD4")) {
            val d = c.key(k, merged, MapList.POLESTAR, false, psMenu = true, psSteps = true)
            assertFalse(d is MapCursor.Do.Untrack || d is MapCursor.Do.Watch || d is MapCursor.Do.Open)
        }
        // another quest's row keeps its own X / Y
        c.touched(8, merged)
        assertEquals(MapCursor.Do.Untrack(8), c.key("PAD3", merged, MapList.POLESTAR, false, psMenu = true, psSteps = true))
        assertEquals(MapCursor.Do.Open(8), c.key("PAD4", merged, MapList.POLESTAR, false, psMenu = true, psSteps = true))
    }

    // pk2 (the user, 2026-11-12: "Open stays reachable on the quest's own row"): on Home's map the merged quest's own row stays a
    // cursor stop, so its Track / Untrack and Open are reachable while the light blue row's X / Y are Skip / Previous
    @Test fun mergedQuestsOwnRowStaysReachableOnHomesMap() {
        // tracked merged quest 7
        val keys = MapList.stops(MapList.items(true, listOf(7, 8), g, polestar = true,
            merged = PolestarMerge.listMerged(7)) { false })
        assertTrue("the quest's own row is a stop", keys.contains(7))
        assertTrue(keys.contains(MapList.POLESTAR))
        val c = MapCursor(); c.touched(7, keys)
        assertEquals(MapCursor.Do.Untrack(7), c.key("PAD3", keys, MapList.POLESTAR, false, psMenu = true, psSteps = true))
        assertEquals(MapCursor.Do.Open(7), c.key("PAD4", keys, MapList.POLESTAR, false, psMenu = true, psSteps = true))
        c.touched(MapList.POLESTAR, keys)
        assertEquals(MapCursor.Do.PsMenu, c.key("PAD3", keys, MapList.POLESTAR, false, psMenu = true, psSteps = true))
        assertEquals(MapCursor.Do.PsSteps, c.key("PAD4", keys, MapList.POLESTAR, false, psMenu = true, psSteps = true))
        // untracked merged quest 9 in an open tab: X tracks it, Y opens it
        val tabs = listOf("Elwynn Forest" to listOf(9))
        val items = MapList.items(true, listOf(8), tabs, polestar = true, merged = PolestarMerge.listMerged(9)) { true }
        val k2 = MapList.stops(items)
        assertTrue(k2.contains(9))
        val c2 = MapCursor(); c2.touched(9, k2)
        assertEquals(MapCursor.Do.Watch(9), c2.key("PAD3", k2, MapList.POLESTAR, false, MapList.untracked(items), psMenu = true, psSteps = true))
        assertEquals(MapCursor.Do.Open(9), c2.key("PAD4", k2, MapList.POLESTAR, false, MapList.untracked(items), psMenu = true, psSteps = true))
    }

    @Test fun theRowsHintsSayOnlyWhatWorks() {
        // pm3: the Map room's words - X Polestar (the menu), Y Skip (the Next / Previous step choice)
        assertEquals(listOf("a" to "Unfocus", "x" to "Polestar", "y" to "Skip"), PolestarSkip.rowHints(true, false, true, true))
        assertEquals(listOf("a" to "Focus", "x" to "Polestar"), PolestarSkip.rowHints(false, false, true, false))   // no guide: no Y
        assertEquals(listOf("a" to "Unfocus"), PolestarSkip.rowHints(true, false, false, false))                    // an older add-on
        assertEquals(listOf("a" to "Choose", "x" to "Polestar", "y" to "Skip"), PolestarSkip.rowHints(false, true, true, true))
        // merged or not, never the quest's Track / Open, and never pk1's words on the row any more
        val words = PolestarSkip.rowHints(true, false, true, true).map { it.second }
        assertFalse(words.any { it == "Track" || it == "Untrack" || it == "Open" || it == "Skip step" || it == "Undo skip" })
        // the same words as the Map room's legend
        assertEquals(MapRoomKeys.STEPS_WORD, PolestarSkip.rowHints(true, false, true, true).last().second)
        assertEquals(PolestarTab.TAB, PolestarSkip.rowHints(true, false, true, true)[1].second)
    }

    // pm3 (the user's pick, 2026-11-16: "Rename to 'Undo skip'"; "Previous step" kept for the real look-back)
    @Test fun theUndoIsUndoSkipAndPreviousStepIsTheLookBack() {
        assertEquals("Undo skip", PolestarSkip.PREV_WORD)
        assertEquals("Previous step", MapRoomKeys.PREV_WORD)
        assertEquals(MapRoomKeys.PREV_WORD, MapRoomKeys.PsSkip.PREV.word)
        assertEquals("Undo skip", HomePauseModel.PS_PREV.label)
        val es = java.io.File(i18nDir(), "es.json").readText(); val fr = java.io.File(i18nDir(), "fr.json").readText()
        for (w in listOf("Undo skip", "Previous step", "Next step", "Skip", "Polestar")) {
            assertTrue("es: $w", es.contains("\"$w\":")); assertTrue("fr: $w", fr.contains("\"$w\":"))
        }
        assertTrue(es.contains("\"Undo skip\": \"Deshacer salto\"")); assertTrue(fr.contains("\"Undo skip\": \"Annuler le saut\""))
    }

    @Test fun theMapRoomsPolestarRowTakesXAndY() {
        val book = MapQuestBook()
        val log = listOf(MapQuestBook.Entry(7, "Elwynn Forest"))
        assertEquals(MapQuestBook.Row.Polestar, book.rows(log, polestar = true).first())
        assertFalse(book.rows(log).contains(MapQuestBook.Row.Polestar))
        // pm1 (the user, 2026-11-16: "x to open the polestar menu and y to open skip options to go to the next or previous step")
        assertEquals(MapRoomKeys.PsDo.MENU, MapRoomKeys.polestarKey("PAD3", menu = true, skips = true))
        assertEquals(MapRoomKeys.PsDo.SKIPS, MapRoomKeys.polestarKey("PAD4", menu = true, skips = true))
        assertEquals(MapRoomKeys.PsDo.NOTHING, MapRoomKeys.polestarKey("PAD3", menu = false, skips = true))   // no menu: never Track
        assertEquals(MapRoomKeys.PsDo.NOTHING, MapRoomKeys.polestarKey("PAD4", menu = true, skips = false))   // nothing to skip: not Options
        assertEquals(MapRoomKeys.PsDo.SKIPS, MapRoomKeys.polestarKey("PAD4", menu = false, skips = true))     // the skips without the menu
        assertEquals(MapRoomKeys.PsDo.NOTHING, MapRoomKeys.polestarKey("PAD1", menu = true, skips = true))
        assertEquals(MapRoomKeys.PsDo.PASS, MapRoomKeys.polestarKey("PADDDOWN", menu = true, skips = true))   // the list still moves
        assertEquals(MapRoomKeys.PsDo.PASS, MapRoomKeys.polestarKey("PAD2", menu = true, skips = true))
    }

    @Test fun theMapRoomsSkipChoiceIsNextAndPrevious() {
        // pm2: two rows, Next step / Previous step = Polestar's own Engine.Next / Engine.Prev (a look): dim only at the guide's ends
        assertEquals(listOf("Next step", "Previous step"), MapRoomKeys.PsSkip.values().map { it.word })
        assertTrue(MapRoomKeys.psSkipOn(MapRoomKeys.PsSkip.NEXT, shown = 4, count = 10))
        assertTrue("no skip needed: Previous works on any later step", MapRoomKeys.psSkipOn(MapRoomKeys.PsSkip.PREV, shown = 4, count = 10))
        assertFalse("step 1: Previous dim", MapRoomKeys.psSkipOn(MapRoomKeys.PsSkip.PREV, shown = 1, count = 10))
        assertFalse("the last step: Next dim", MapRoomKeys.psSkipOn(MapRoomKeys.PsSkip.NEXT, shown = 10, count = 10))
        assertTrue("not known: both", MapRoomKeys.psSkipOn(MapRoomKeys.PsSkip.PREV, shown = null, count = 0))
        // D-pad + A, B / Y close
        assertEquals(MapRoomKeys.SkipDo.Move(1), MapRoomKeys.psSkipKey("PADDDOWN", 0))
        assertEquals(MapRoomKeys.SkipDo.Move(1), MapRoomKeys.psSkipKey("PADDDOWN", 1))                 // the last row stays
        assertEquals(MapRoomKeys.SkipDo.Move(0), MapRoomKeys.psSkipKey("PADDUP", 1))
        assertEquals(MapRoomKeys.SkipDo.Run(MapRoomKeys.PsSkip.NEXT), MapRoomKeys.psSkipKey("PAD1", 0))
        assertEquals(MapRoomKeys.SkipDo.Run(MapRoomKeys.PsSkip.PREV), MapRoomKeys.psSkipKey("PAD1", 1))
        assertEquals(MapRoomKeys.SkipDo.Close, MapRoomKeys.psSkipKey("PAD2", 0))
        assertEquals(MapRoomKeys.SkipDo.Close, MapRoomKeys.psSkipKey("PAD4", 1))
        assertEquals(MapRoomKeys.SkipDo.None, MapRoomKeys.psSkipKey("PAD3", 0))
        // pg2: framed ops 24 / 25 with the guide's stamp, no args (444 = 12, 12; k = 24 + 12 + 12 mod 36)
        assertEquals(listOf(38, 12, 24, 12, 12, 12), PolestarTab.frame(PolestarTab.NEXT, 444))
        assertEquals(listOf(38, 12, 25, 12, 12, 13), PolestarTab.frame(PolestarTab.PREV, 444))
        assertFalse("a look: never in a fight's refusals", PolestarTab.FIGHT.contains(PolestarTab.NEXT) || PolestarTab.FIGHT.contains(PolestarTab.PREV))
        assertFalse("a step each: never re-sent unanswered", PolestarTab.AGAIN_UNANSWERED.contains(PolestarTab.NEXT))
        // pk1's Skip for good / Undo my last skip keep their pnav columns (the Polestar menu's X / Y, Home's row, the wheel)
        assertEquals(listOf(38, 10), PolestarSkip.cols(prev = false))
        assertEquals(listOf(38, 11), PolestarSkip.cols(prev = true))
    }

    private fun page3(skip: Boolean, prev: Boolean): HomePauseModel.Flow {
        val f = HomePauseModel.Flow()
        f.psSkip = skip; f.psPrev = prev
        f.turn(+1)                                                      // the centre page -> page 3
        return f
    }
    private val up = HomePauseModel.fromGame(2)                          // N: Skip step
    private val upLeft = HomePauseModel.fromGame(3)                      // NW: Undo skip

    @Test fun thePauseWheelHasSkipAndPreviousOnPage3() {
        val f = page3(skip = true, prev = true)
        assertEquals(PolestarSkip.KEY_SKIP, f.slices()[up]?.key)
        assertEquals(PolestarSkip.KEY_PREV, f.slices()[upLeft]?.key)
        val a = f.touch(up) as HomePauseModel.Act.Pick
        assertEquals(PolestarSkip.KEY_SKIP, a.slice.key); assertEquals(HomePauseModel.Kind.HOME, a.slice.kind)
        assertEquals("Skip step", a.slice.label)
        val b = f.touch(upLeft) as HomePauseModel.Act.Pick
        assertEquals(PolestarSkip.KEY_PREV, b.slice.key); assertEquals("pm3: the undo's words", "Undo skip", b.slice.label)
        // the other slices of the page are where they were
        // (the Macros room took the Calendar's slice, Spells s10: the user, 2026-11-13, "Use the room slot for macros")
        assertEquals("macros", f.slices()[HomePauseModel.fromGame(1)]?.key)
        assertEquals("pvp", f.slices()[HomePauseModel.fromGame(4)]?.key)
    }

    @Test fun previousIsGreyedWithNothingToUndoAndBothHideWithNoStep() {
        val f = page3(skip = true, prev = false)
        val p = f.slices()[upLeft]!!
        assertFalse(p.enabled)
        val r = f.touch(upLeft) as HomePauseModel.Act.Refuse
        assertEquals("Nothing to undo", r.slice.offLine)
        assertTrue(f.slices()[up]!!.enabled)
        val none = page3(skip = false, prev = false)
        assertNull(none.slices()[up]); assertNull(none.slices()[upLeft])
        assertEquals(HomePauseModel.Act.None, none.touch(up))
        // the stick lights nothing on a hidden slot
        assertEquals(HomePauseModel.Act.None, none.vector(0.0, 1.0)); assertEquals(-1, none.pointed)
        // the centre page is unchanged
        val c = HomePauseModel.Flow(); c.psSkip = true; c.psPrev = true
        assertFalse(c.slices().any { it?.key == PolestarSkip.KEY_SKIP || it?.key == PolestarSkip.KEY_PREV })
    }

    @Test fun aRefusedPressSaysWhyTersely() {
        // pk3 (the user's pick 2026-11-12, "Work in fights too"): no combat refusal any more; an error of Polestar's own: one line
        assertEquals("Polestar refused it", PolestarSkip.refusedLine("combat"))
        assertEquals("Nothing to undo", PolestarSkip.refusedLine("nothing"))
        assertEquals("No Polestar step", PolestarSkip.refusedLine("no Polestar"))
        assertEquals("Polestar refused it", PolestarSkip.refusedLine("Interface/AddOns/Polestar/Core/Engine.lua:12: oops"))
        assertNull(PolestarSkip.refusedLine("")); assertNull(PolestarSkip.refusedLine(null))
        assertEquals("Nothing to undo", PolestarSkip.cantLine(prev = true, canSkip = true))
        assertEquals("No Polestar step", PolestarSkip.cantLine(prev = true, canSkip = false))
        assertEquals("No Polestar step", PolestarSkip.cantLine(prev = false, canSkip = false))
    }

    // pk3 rough edge a): "Previous does nothing for ~1 s right after a Skip" - the ok answer's count is taken at once
    @Test fun theUndoCountFollowsTheOkAnswerAtOnce() {
        val st = step(sk = true, u = 0)!!
        assertFalse(PolestarSkip.canPrev(true, st))
        val n = PolestarSkip.undoAfter("skip", ok = true, u = 1, cur = st.undo)
        val after = st.withUndo(n)
        assertEquals(1, after.undo); assertTrue(PolestarSkip.canPrev(true, after))
        assertEquals("the rest of the step kept", st.sig(), after.sig()); assertTrue(after.skip)
        assertEquals(0, PolestarSkip.undoAfter("prev", ok = true, u = 0, cur = 1))
        // a pk1 answer (no u): +1 / -1 for an ok press, never below 0; a refusal leaves it
        assertEquals(3, PolestarSkip.undoAfter("skip", ok = true, u = null, cur = 2))
        assertEquals(1, PolestarSkip.undoAfter("prev", ok = true, u = null, cur = 2))
        assertEquals(0, PolestarSkip.undoAfter("prev", ok = true, u = null, cur = 0))
        assertEquals(2, PolestarSkip.undoAfter("prev", ok = false, u = null, cur = 2))
        // a refusal WITH the add-on's count: the count (a skip undone in Polestar's own window meanwhile)
        assertEquals(0, PolestarSkip.undoAfter("prev", ok = false, u = 0, cur = 2))
        assertEquals(0, st.withUndo(-3).undo)
    }

    // pk3 rough edge c): a refused Skip / Previous from the pause wheel says why on the wheel's own line, wherever it was opened
    @Test fun aRefusedWheelPressSaysWhyOnTheWheel() {
        val now = 100_000L
        assertEquals(PolestarSkip.Where.WHEEL, PolestarSkip.noteWhere(false, "Nothing to undo", now, now - 300L, inMapRoom = false))
        assertEquals("the Map room too", PolestarSkip.Where.WHEEL, PolestarSkip.noteWhere(false, "No Polestar step", now, now - 300L, inMapRoom = true))
        assertEquals(PolestarSkip.Where.MAP_ROOM, PolestarSkip.noteWhere(false, "No Polestar step", now, 0L, inMapRoom = true))
        assertEquals(PolestarSkip.Where.HOME_MAP, PolestarSkip.noteWhere(false, "No Polestar step", now, 0L, inMapRoom = false))
        assertEquals("a wheel press long ago: not the wheel's", PolestarSkip.Where.HOME_MAP,
            PolestarSkip.noteWhere(false, "No Polestar step", now, now - PolestarSkip.WHEEL_ANSWER_MS - 1L, inMapRoom = false))
        assertEquals(PolestarSkip.Where.NONE, PolestarSkip.noteWhere(true, null, now, now - 300L, inMapRoom = false))
        assertEquals(PolestarSkip.Where.NONE, PolestarSkip.noteWhere(false, null, now, now - 300L, inMapRoom = false))
        // the words are terse and have ES / FR
        val es = java.io.File(i18nDir(), "es.json").readText(); val fr = java.io.File(i18nDir(), "fr.json").readText()
        for (w in listOf(PolestarSkip.NOTHING_LINE, PolestarSkip.NO_STEP_LINE, PolestarSkip.REFUSED_LINE)) {
            assertTrue("es: $w", es.contains("\"$w\":")); assertTrue("fr: $w", fr.contains("\"$w\":"))
            assertTrue("terse: $w", w.split(" ").size <= 4)
        }
        assertFalse("no combat line left", es.contains("\"After combat\"") || fr.contains("\"After combat\""))
    }
    private fun i18nDir(): java.io.File {
        var d: java.io.File? = java.io.File("").absoluteFile
        while (d != null) {
            val f = java.io.File(d, "companion/app/src/main/assets/companion/i18n")
            if (f.isDirectory) return f
            d = d.parentFile
        }
        error("i18n folder not found")
    }

    // pk3 rough edge b): "Two quick presses can lose the second" - every pnav cell is spaced >= MIN_MS (400 ms) across batches
    @Test fun twoQuickSkipsAreSpacedAcrossBatches() {
        val c = TapSpacing.Clock()
        val skip = PolestarSkip.cols(prev = false).map { PolestarNav.ROW to it }
        assertEquals(0L, TapSpacing.lead(c, 1_000L, skip, 120L))
        // the first Skip ran 1000..1200 (38 at 1000, 10 at 1120, the rest); the shell is free at once for the second
        TapSpacing.ran(c, 1_200L, skip, 120L)
        val lead = TapSpacing.lead(c, 1_200L, skip, 120L)
        assertTrue("waits ($lead ms)", lead > 0L)
        val lastSay = c.lastAt[PolestarNav.ROW to PolestarSkip.SAY]!!
        assertTrue("38 recorded no earlier than it ran ($lastSay)", lastSay >= 1_000L)
        assertTrue("the second 38 >= MIN_MS after the first", 1_200L + lead - lastSay >= TapSpacing.MIN_MS)
        assertTrue("the second 10 too", 1_200L + lead + 120L - c.lastAt[PolestarNav.ROW to PolestarSkip.SKIP]!! >= TapSpacing.MIN_MS)
        // Skip then Previous: 38 is shared, so it is spaced as well
        val prev = PolestarSkip.cols(prev = true).map { PolestarNav.ROW to it }
        assertTrue(TapSpacing.lead(c, 1_200L, prev, 120L) > 0L)
        // long after: no wait; other rows never wait; game points never
        assertEquals(0L, TapSpacing.lead(c, 5_000L, skip, 120L))
        TapSpacing.ran(c, 6_000L, listOf("pfocus" to 3), 0L)
        assertEquals(0L, TapSpacing.lead(c, 6_000L, listOf("pfocus" to 3), 0L))
        assertEquals(0L, TapSpacing.lead(c, 6_000L, null, 0L))
        TapSpacing.ran(c, 6_000L, null, 0L)
        // PolestarNav's own 38 then 8 right behind a Skip waits too
        TapSpacing.ran(c, 7_000L, skip, 120L)
        assertTrue(TapSpacing.lead(c, 7_000L, listOf(PolestarNav.ROW to PolestarNav.PS_SAY, PolestarNav.ROW to PolestarNav.PS_ON), 0L) > 0L)
        assertTrue("never more than MIN_MS", TapSpacing.lead(c, 7_000L, skip, 0L) <= TapSpacing.MIN_MS)
        // review fix: the margin over TapPad's 0.25 s guard covers a press handled one slow frame late (a fight at ~7 fps), less the
        // ran() estimate's ~20 ms of slack (TAP_REST 20 + TAIL 30 against TAIL_MIN_MS 30)
        assertTrue("MIN_MS covers the 0.25 s guard plus a slow frame", TapSpacing.MIN_MS - TapSpacing.FRAME_MS >= 250L)
        assertTrue("a frame of 150 ms is budgeted", TapSpacing.FRAME_MS >= 150L)
    }
    /** pg1 (design_polestar B3: "Repeated digits inside one batch can be dropped"): a spaced row's cell repeated INSIDE a batch waits for
     *  MIN_MS after its tap before it (a frame's "1, 1", a GO's "1, 34, 1"); other cells keep the batch's gap; across batches as before */
    @Test fun aRepeatInsideABatchIsSpacedToo() {
        val row = PolestarNav.ROW
        val frame = listOf(38, 12, 15, 1, 1, 0, 9, 26).map { row to it }
        val at = TapSpacing.offsets(frame, 120L)
        assertEquals(0L, at[0]); assertEquals(120L, at[1]); assertEquals(240L, at[2]); assertEquals(360L, at[3])
        assertEquals("the second 1 waits MIN_MS after the first", 360L + TapSpacing.MIN_MS, at[4])
        assertEquals("then the gap again", at[4] + 120L, at[5])
        // two taps apart (a GO's 1, 34, 1): 240 ms is under MIN_MS, so it waits too
        val go = listOf(36, 1, 34, 1).map { row to it }
        val g = TapSpacing.offsets(go, 120L)
        assertTrue(g[3] - g[1] >= TapSpacing.MIN_MS)
        // no repeat: exactly the gap; another row: never spaced; game points (null): the gap
        assertEquals(listOf(0L, 120L, 240L), TapSpacing.offsets(listOf(38, 12, 13).map { row to it }, 120L).toList())
        assertEquals(listOf(0L, 0L), TapSpacing.offsets(listOf("pfocus" to 3, "pfocus" to 3), 0L).toList())
        assertEquals(listOf(0L, 50L), TapSpacing.offsets(null, 50L, 2).toList())
        // ran() stamps each tap at its offset (the batch's end less the rest of it), so the next batch's lead counts from the real tap
        val c = TapSpacing.Clock()
        TapSpacing.ran(c, 10_000L, frame, 120L)
        assertEquals(10_000L - (at[7] - at[4]) - TapSpacing.TAIL_MIN_MS, c.lastAt[row to 1])
        // (the frame's 9, its second to last tap, at 10 000 - 120 - 30): a batch tapping 9 at once waits the rest of MIN_MS
        assertEquals(10_000L - 120L - TapSpacing.TAIL_MIN_MS + TapSpacing.MIN_MS - 10_000L, TapSpacing.lead(c, 10_000L, listOf(row to 9), 120L))
        assertEquals("the frame's 38 is long past", 0L, TapSpacing.lead(c, 10_000L, listOf(row to 38), 120L))
        // Bags' rows (shared with this spacing): two names of one bag in a batch are spaced as well
        val bags = listOf(BagsMoveRoute.NAME_ROW to 12, BagsMoveRoute.ROW to 3, BagsMoveRoute.NAME_ROW to 12, BagsMoveRoute.ROW to 7)
        val b = TapSpacing.offsets(bags, 0L)
        assertEquals(0L, b[1]); assertEquals(TapSpacing.MIN_MS, b[2]); assertEquals(TapSpacing.MIN_MS, b[3])
    }
}
