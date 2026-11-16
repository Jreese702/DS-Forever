package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * pg1, THE POLESTAR TAB (the user, 2026-11-16: "if Polestar is active it should have its own tab at the top of the quests tab"; every
 * recommended pick of design_polestar): the frames (check digit, stamps), the documents, the list's rows (the Done fold, notices,
 * Reset), CURSOR STABILITY (the user's untrack complaint: the cursor keeps its step after any action), the keys (B one step back, X / Y
 * only on the guide row and the shown step, D3's fight refusals), Change guide's rows (D5), the one-in-flight sender, the words' es / fr.
 */
class PolestarTabTest {
    private fun guide(s: Int? = 4, l: Int? = 4, n: Int = 12, st: String? = "dddluuuuuuuu", gs: Int = 444, extra: (JSONObject) -> Unit = {}): JSONObject {
        val d = JSONObject().put("type", "pguide").put("v", "pg1").put("have", true).put("gid", "forever/alliance/1-10/elwynn").put("gs", gs)
            .put("t", "Elwynn Forest").put("z", "Elwynn Forest").put("zm", 1429).put("lo", 1).put("hi", 10).put("n", n).put("dn", 3).put("tt", n)
            .put("sk", true).put("u", 0)
        if (s != null) d.put("s", s)
        if (l != null) d.put("l", l)
        if (st != null) d.put("st", st).put("tb", true)
        d.put("tabs", JSONArray().put(JSONObject().put("t", "Elwynn Forest").put("s", gs).put("i", s ?: 1).put("n", n))
            .put(JSONObject().put("t", "Fishing").put("s", 77).put("i", 1).put("n", 8))).put("ta", 1)
        extra(d)
        return d
    }
    private fun g(d: JSONObject = guide()) = PolestarTab.Guide.parse(d)!!

    @Test fun theFrameIsOpStampArgsAndTheCheckDigit() {
        // 38, 12, op, s1, s2, args, k (k = the sum of op, s1, s2 and args, mod 36: Map.lua WC.pg.frameTap)
        assertEquals(listOf(38, 12, 15, 12, 12, 0, 9, (15 + 12 + 12 + 0 + 9) % 36), PolestarTab.frame(PolestarTab.LOOK, 444, PolestarTab.two(9)))
        assertEquals(listOf(38, 12, 13, 0, 0, 13), PolestarTab.frame(PolestarTab.OPEN, 0))
        assertEquals(listOf(38, 12, 23, 1, 2, 0, 4, 1, (23 + 1 + 2 + 0 + 4 + 1) % 36), PolestarTab.frame(PolestarTab.TICK, 38, listOf(0, 4, 1)))
        for ((op, n) in PolestarTab.ARGS) {
            val f = PolestarTab.frame(op, 1295, List(n) { 35 })
            assertEquals("op $op: 2 + 1 + 2 + $n + 1 taps", 6 + n, f.size)
            assertTrue("op $op is 13-35 (never 36-39: they end a frame)", f[2] in 13..35)
            assertTrue("every tap after 38 is 0-35", f.drop(1).all { it in 0..35 })
            assertEquals(f.subList(2, f.size - 1).sum() % 36, f.last())
        }
        assertEquals(listOf(35, 35), PolestarTab.two(5000))      // (coerced: two base-36 digits)
        assertEquals(setOf(PolestarTab.LOAD, PolestarTab.CONTINUE, PolestarTab.CHOOSE, PolestarTab.RESET), PolestarTab.FIGHT)
    }

    @Test fun aLostTapNeverLeavesAWholeFrameAndASwappedDigitFailsTheCheck() {
        // (the add-on's rule, mirrored: a frame is whole only at its op's length; a lost tap leaves it short, so nothing runs)
        val f = PolestarTab.frame(PolestarTab.LOOK, 444, PolestarTab.two(9))
        for (drop in f.indices) {
            val cut = f.filterIndexed { i, _ -> i != drop }
            val whole = cut.size >= 2 && cut[0] == 38 && cut[1] == 12 && cut.size == 3 + 2 + (PolestarTab.ARGS[cut[2]] ?: -99) + 1
            assertFalse("dropping tap $drop leaves no whole frame ($cut)", whole && cut.subList(2, cut.size - 1).sum() % 36 == cut.last())
        }
        val bad = f.toMutableList().also { it[6] = (it[6] + 1) % 36 }
        assertTrue(bad.subList(2, bad.size - 1).sum() % 36 != bad.last())
        // its taps' own time: a repeated digit ("12, 12") is spaced by TapSpacing (pg1)
        assertTrue(PolestarTab.frameMs(f) >= (f.size - 1) * PolestarTab.GAP_MS + TapSpacing.MIN_MS)
    }

    @Test fun theGuideDocument() {
        val x = g()
        assertTrue(x.have && !x.none)
        assertEquals("Elwynn Forest", x.place()); assertEquals(444, x.stamp); assertEquals(4, x.shown); assertEquals(4, x.live)
        assertEquals('l', x.statusOf(4)); assertEquals('d', x.statusOf(1)); assertNull(x.statusOf(13))
        assertEquals(2, x.tabs!!.size); assertEquals(77, x.tabs!![1].stamp); assertEquals(1, x.active)
        assertFalse(x.away())
        assertTrue("looking ahead at 9", g(guide(s = 9)).away())
        assertTrue("a skipped step looked at, the guide done", g(guide(s = 6, l = null, st = "dddddsdddddd")).away())
        assertFalse("done, nothing looked at", g(guide(s = null, l = null)).away())
        val none = PolestarTab.Guide.parse(JSONObject().put("type", "pguide").put("have", true).put("none", true))!!
        assertTrue(none.none); assertNull(none.place())
        assertNull(PolestarTab.Guide.parse(JSONObject().put("type", "pstep")))
        // the zone first, else the title (a Class guide has no zone)
        assertEquals("Earth Totem", g(guide { it.remove("z"); it.put("t", "Earth Totem") }).place())
    }

    @Test fun theNotices() {
        assertEquals(emptyList<PolestarTab.Notice>(), PolestarTab.notices(g()))
        assertEquals(listOf(PolestarTab.Notice.AWAY), PolestarTab.notices(g(guide(s = 9))))
        val wait = g(guide(s = null, l = null, st = "dddddddddddd") { it.put("cp", true).put("rw", true).put("rc", true).put("fs", 444) })
        assertEquals(listOf(PolestarTab.Notice.CHOOSE), PolestarTab.notices(wait))
        val done = g(guide(s = null, l = null) { it.put("cp", true).put("nx", "Westfall").put("ni", 3) })
        assertEquals(listOf(PolestarTab.Notice.COMPLETE), PolestarTab.notices(done))
        assertEquals("Next: Westfall", PolestarTab.noteWords(PolestarTab.Notice.COMPLETE, done))
        val ol = g(guide(s = 9) { it.put("ol", true).put("ot", "Westfall").put("oi", 3) })
        assertEquals("at most 2, the outlevelled one after the look", listOf(PolestarTab.Notice.AWAY, PolestarTab.Notice.OUTLEVEL), PolestarTab.notices(ol))
        assertEquals("Jump to Westfall", PolestarTab.noteWords(PolestarTab.Notice.OUTLEVEL, ol))
        assertEquals(PolestarTab.Do.Load(3), PolestarTab.noteDo(PolestarTab.Notice.OUTLEVEL, ol))
        val olc = g(guide { it.put("ol", true).put("ot", "Westfall").put("oc", true) })
        assertEquals("the suggestion is a path choice's option: Choose your path", PolestarTab.CHOOSE_PATH, PolestarTab.noteWords(PolestarTab.Notice.OUTLEVEL, olc))
        assertEquals(PolestarTab.Do.Path, PolestarTab.noteDo(PolestarTab.Notice.OUTLEVEL, olc))
        assertEquals("Back to step 4", PolestarTab.noteWords(PolestarTab.Notice.AWAY, g(guide(s = 9))))
        assertEquals("Unskip step 6", PolestarTab.noteWords(PolestarTab.Notice.AWAY, g(guide(s = 6, st = "dddlukuuuuuu"))))
        val none = PolestarTab.Guide.parse(JSONObject().put("type", "pguide").put("have", true).put("none", true))!!
        assertEquals(listOf(PolestarTab.Notice.NO_GUIDE), PolestarTab.notices(none))
        assertEquals(PolestarTab.Do.Picker, PolestarTab.noteDo(PolestarTab.Notice.NO_GUIDE, none))
    }

    @Test fun theRowsFoldTheDoneStepsBeforeTheLiveOne() {
        val rows = PolestarTab.rows(g(), foldOpen = false)
        assertEquals("guide", rows[0].key)
        assertEquals(PolestarTab.Row.DoneFold(3, false), rows[1])
        assertEquals((4..12).map { "s:$it" }, rows.filterIsInstance<PolestarTab.Row.Step>().map { it.key })
        assertTrue((rows[2] as PolestarTab.Row.Step).shown && (rows[2] as PolestarTab.Row.Step).live)
        assertEquals("reset", rows.last().key)
        val open = PolestarTab.rows(g(), foldOpen = true)
        assertEquals((1..12).map { "s:$it" }, open.filterIsInstance<PolestarTab.Row.Step>().map { it.key })
        // looking back at step 2: only step 1 folds, 2..12 in the list (2 shown, 4 the live one)
        val back = PolestarTab.rows(g(guide(s = 2)), false)
        assertEquals(PolestarTab.Row.DoneFold(1, false), back[2])           // (after the guide row and its "away" notice)
        assertEquals("s:2", PolestarTab.landing(back))
        // steps not for this character are left out; a guide done folds every step
        val na = PolestarTab.rows(g(guide(st = "ddnluuuuunuu")), false)
        assertEquals(PolestarTab.Row.DoneFold(2, false), na[1])
        assertFalse(na.any { it.key == "s:10" })
        val done = PolestarTab.rows(g(guide(s = null, l = null, st = "dddddddddddd") { it.put("cp", true) }), false)
        assertEquals(listOf("guide", "n:COMPLETE", "done", "reset"), done.map { it.key })
        // no guide: the guide row and its notice only
        val none = PolestarTab.rows(PolestarTab.Guide.parse(JSONObject().put("type", "pguide").put("have", true).put("none", true)), false)
        assertEquals(listOf("guide", "n:NO_GUIDE"), none.map { it.key })
        assertEquals(emptyList<PolestarTab.Row>(), PolestarTab.rows(null, false))
    }

    @Test fun theCursorKeepsItsStepAfterAnyAction() {
        val c = PolestarTab.Cursor()
        c.enter(PolestarTab.rows(g(), false))
        assertEquals("entered on the shown step", "s:4", c.sel)
        // down to 9, Look at it: the cursor stays on 9 while the shown step moves there (no landing on refresh)
        repeat(5) { c.key("PADDDOWN", PolestarTab.rows(g(), false), emptyList(), null, null, g(), false) }
        assertEquals("s:9", c.sel)
        assertEquals(PolestarTab.Do.Look(9), c.key("PAD1", PolestarTab.rows(g(), false), emptyList(), null, null, g(), false))
        val looked = g(guide(s = 9))
        c.refresh(PolestarTab.rows(looked, false), 4, 9)
        assertEquals("s:9", c.sel)
        // a step done meanwhile (4 done, live 5): the cursor on 9 stays on 9
        val on = g(guide(s = 9, l = 5, st = "ddddluuuuuuu"))
        c.refresh(PolestarTab.rows(on, false), 9, 9)
        assertEquals("s:9", c.sel)
        // X on the shown step (9) skips it: Polestar shows 10 now - the cursor follows the shown step (X again skips 10)
        assertEquals(PolestarTab.Do.Skip, c.key("PAD3", PolestarTab.rows(on, false), emptyList(), null, null, on, false))
        val skipped = g(guide(s = 10, l = 5, st = "ddddluuukuuu"))
        c.refresh(PolestarTab.rows(skipped, false), 9, 10)
        assertEquals("s:10", c.sel)
        // the cursor on another step: a skip elsewhere leaves it where it is
        c.select("s:7")
        c.refresh(PolestarTab.rows(g(guide(s = 11, l = 5)), false), 10, 11)
        assertEquals("s:7", c.sel)
        // a step that folded away hands the cursor to the fold (it is in there), never back to the top
        c.select("s:5")
        val later = g(guide(s = 8, l = 8, st = "dddddddluuuu"))
        c.refresh(PolestarTab.rows(later, false), 11, 8)
        assertEquals("done", c.sel)
        // a notice that went (Back answered) gives its place to the guide row's next notice, else the guide row
        c.select("n:AWAY")
        c.refresh(PolestarTab.rows(g(), false))
        assertEquals("guide", c.sel)
    }

    @Test fun theKeys() {
        val c = PolestarTab.Cursor()
        val x = g(guide { it.put("u", 2) })
        val rows = PolestarTab.rows(x, false)
        c.enter(rows)
        fun k(key: String, fight: Boolean = false, detail: PolestarTab.StepDetail? = null) = c.key(key, rows, emptyList(), null, detail, x, fight)
        // the shown step: A dim (nothing), X Skip, Y Previous (a skip to undo)
        assertEquals(PolestarTab.Do.Nothing, k("PAD1"))
        assertEquals(PolestarTab.Do.Skip, k("PAD3"))
        assertEquals(PolestarTab.Do.Prev, k("PAD4"))
        // another step: A Look, no X / Y
        k("PADDDOWN")
        assertEquals("s:5", c.sel)
        assertEquals(PolestarTab.Do.Look(5), k("PAD1"))
        assertEquals(PolestarTab.Do.Nothing, k("PAD3"))
        assertEquals(PolestarTab.Do.Nothing, k("PAD4"))
        assertEquals("in a fight too (D3)", PolestarTab.Do.Look(5), k("PAD1", fight = true))
        // up to the fold, the guide row: A Change guide (refused in a fight), X / Y there too; Up from it: the tab row
        k("PADDUP"); k("PADDUP")
        assertEquals("done", c.sel)
        assertEquals(PolestarTab.Do.Fold, k("PAD1"))
        k("PADDUP")
        assertEquals("guide", c.sel)
        assertEquals(PolestarTab.Do.Picker, k("PAD1"))
        assertEquals(PolestarTab.Do.Refused(PolestarTab.FIGHT_LINE), k("PAD1", fight = true))
        assertEquals(PolestarTab.Do.Skip, k("PAD3"))
        assertEquals(PolestarTab.Do.ToTabs, k("PADDUP"))
        assertEquals("B on the list: Home", PolestarTab.Do.Leave, k("PAD2"))
        assertEquals("home", c.bLeads())
        // Reset: A asks, the question's A resets, B cancels; refused in a fight
        c.select("reset")
        assertEquals(PolestarTab.Do.AskReset, k("PAD1"))
        assertEquals(PolestarTab.Do.Refused(PolestarTab.FIGHT_LINE), k("PAD1", fight = true))
        c.openConfirm()
        assertEquals("back", c.bLeads())
        assertEquals(PolestarTab.Do.Refused(PolestarTab.FIGHT_LINE), k("PAD1", fight = true))
        assertEquals(PolestarTab.Do.Reset, k("PAD1"))
        assertEquals(PolestarTab.Do.Out, k("PAD2"))
        c.toList()
        // D2: Right onto a step's goals (when described and tickable), A ticks, Left / B back to the list
        c.select("s:6")
        val d = PolestarTab.StepDetail.parse(JSONObject().put("type", "pgstep").put("gs", 444).put("i", 6)
            .put("g", JSONArray().put(JSONObject().put("g", 1).put("s", "pending").put("x", "Kill 8 wolves").put("c", 2).put("m", 8).put("tk", true))
                .put(JSONObject().put("g", 2).put("s", "active").put("x", "A note"))
                .put(JSONObject().put("g", 3).put("s", "done").put("x", "Talk to Marshal").put("tk", true).put("ti", true))))!!
        assertEquals(PolestarTab.Do.Nothing, k("PADDRIGHT", detail = null))
        assertEquals(PolestarTab.Do.GoalsIn, k("PADDRIGHT", detail = d))
        assertEquals(PolestarTab.View.GOALS, c.view)
        assertEquals("back", c.bLeads())
        assertEquals(PolestarTab.Do.Tick(6, 1), k("PAD1", detail = d))
        assertEquals("ticking works in a fight", PolestarTab.Do.Tick(6, 1), k("PAD1", fight = true, detail = d))
        k("PADDDOWN", detail = d)
        assertEquals("the note is not a stop: the next tickable goal", PolestarTab.Do.Tick(6, 3), k("PAD1", detail = d))
        assertEquals(PolestarTab.Do.Out, k("PADDLEFT", detail = d))
        assertEquals(PolestarTab.View.LIST, c.view)
        // the notices' A
        val away = g(guide(s = 9))
        val ar = PolestarTab.rows(away, false)
        c.select("n:AWAY")
        assertEquals(PolestarTab.Do.Back, c.key("PAD1", ar, emptyList(), null, null, away, fight = true))
        // X / Y never on a row that is not the shown step or the guide (design B6), never without Skip from the add-on
        assertFalse(PolestarTab.canXY(PolestarTab.Row.ResetRow, x))
        assertFalse(PolestarTab.canXY(PolestarTab.Row.GuideRow, g(guide { it.put("sk", false) })))
    }

    private fun list(): PolestarTab.GuideList {
        val gs = JSONArray()
        fun add(t: String, f: Int, extra: (JSONObject) -> Unit = {}) = gs.put(JSONObject().put("t", t).put("f", f).put("st", "n").put("lo", 1).put("hi", 10).also(extra))
        add("Elwynn Forest", 1) { it.put("st", "s").put("tb", 1).put("sg", 1).put("h", true).put("dn", 3).put("tt", 12) }
        add("Loch Modan", 2) { it.put("sg", 3) }
        add("Westfall", 2) { it.put("sg", 2) }
        add("Earth Totem", 3) { it.put("gr", true).put("gl", 24) }
        add("Fishing", 4) { it.put("tb", 2) }
        return PolestarTab.GuideList.parse(JSONObject().put("type", "pglist").put("ls", 901)
            .put("f", JSONArray().put("Alliance 1–10").put("Alliance 10–20").put("Class").put("Professions")).put("g", gs))!!
    }

    @Test fun changeGuideListsOpenSuggestedThenFolders() {
        val l = list()
        assertEquals(901, l.stamp)
        val rows = PolestarTab.pickRows(g(), l, emptySet())
        assertEquals(listOf("h:Open", "t:1", "t:2", "h:Suggested", "g:1", "g:3", "g:2", "d:0", "d:1", "d:2", "d:3"), rows.map { it.key })
        assertFalse("headings are not stops", rows.first().stop)
        assertEquals("lands on the active tab", "t:1", PolestarTab.pickLanding(rows))
        val open = PolestarTab.pickRows(g(), l, setOf(1))
        assertEquals(listOf("d:1", "f:2", "f:3", "d:2"), open.map { it.key }.dropWhile { it != "d:1" }.take(4))
        // the keys: up / down over the stops, A on a folder opens it, A on a guide loads it (Continue when it is open in a tab)
        val c = PolestarTab.Cursor()
        c.openPicker(rows)
        fun k(key: String, rs: List<PolestarTab.PickRow> = rows, fight: Boolean = false) = c.key(key, emptyList(), rs, null, null, g(), fight)
        assertEquals("A on the active tab: back to the list", PolestarTab.Do.Out, k("PAD1"))
        k("PADDDOWN")
        assertEquals(PolestarTab.Do.Cont(2), k("PAD1"))
        assertEquals(PolestarTab.Do.Refused(PolestarTab.FIGHT_LINE), k("PAD1", fight = true))
        k("PADDDOWN")
        assertEquals("the heading skipped", "g:1", c.pick)
        assertEquals("Elwynn is the active tab: back to the list", PolestarTab.Do.Out, k("PAD1"))
        k("PADDDOWN")
        assertEquals(PolestarTab.Do.Load(3), k("PAD1"))
        repeat(3) { k("PADDDOWN") }
        assertEquals("d:1", c.pick)
        assertEquals(PolestarTab.Do.ToggleFolder(1), k("PAD1"))
        assertEquals("folders open in a fight (only Load / Continue wait)", PolestarTab.Do.ToggleFolder(1), k("PAD1", fight = true))
        assertEquals(PolestarTab.Do.Out, k("PAD2"))
        assertEquals("back", c.bLeads())
        // no tabs, no list yet: nothing to pick
        assertEquals(emptyList<PolestarTab.PickRow>(), PolestarTab.pickRows(null, null, emptySet()))
    }

    @Test fun choosingAPath() {
        val r = PolestarTab.Route.parse(JSONObject().put("type", "pgroute").put("fs", 444).put("ft", "Elwynn Forest")
            .put("o", JSONArray().put(JSONObject().put("t", "Westfall").put("lo", 10).put("hi", 20).put("sec", 120).put("q", 27).put("rec", true)
                .put("pt", "Farms").put("pr", JSONArray().put("a").put("b").put("c").put("d")).put("cn", JSONArray().put("x").put("y").put("z")))
                .put(JSONObject().put("t", "Loch Modan").put("ch", true))))!!
        assertEquals(2, r.options.size); assertEquals(3, r.options[0].pros.size); assertEquals(2, r.options[0].cons.size)
        assertNull(PolestarTab.Route.parse(JSONObject().put("type", "pgroute").put("none", true)))
        val c = PolestarTab.Cursor()
        c.openPath(r)
        assertEquals("opens on the chosen option", 1, c.path)
        assertEquals(PolestarTab.Do.Choose(2), c.key("PAD1", emptyList(), emptyList(), r, null, g(), false))
        assertEquals(PolestarTab.Do.Refused(PolestarTab.FIGHT_LINE), c.key("PAD1", emptyList(), emptyList(), r, null, g(), true))
        c.key("PADDUP", emptyList(), emptyList(), r, null, g(), false)
        assertEquals(PolestarTab.Do.Choose(1), c.key("PAD1", emptyList(), emptyList(), r, null, g(), false))
        assertEquals("B leaves it and tells Polestar nothing", PolestarTab.Do.Out, c.key("PAD2", emptyList(), emptyList(), r, null, g(), false))
    }

    @Test fun oneFrameInFlight() {
        val s = PolestarTab.Sender()
        val a = PolestarTab.frame(PolestarTab.LOOK, 444, listOf(0, 9))
        val b = PolestarTab.frame(PolestarTab.LOOK, 444, listOf(0, 10))
        val c = PolestarTab.frame(PolestarTab.LOOK, 444, listOf(0, 11))
        assertNotNull(s.send(PolestarTab.LOOK, a, 1_000L))
        assertTrue(s.busy)
        assertNull("the next press waits", s.send(PolestarTab.LOOK, b, 1_100L))
        assertNull("only the latest press waits", s.send(PolestarTab.LOOK, c, 1_200L))
        // garbled: sent once more; garbled again: given up, the waiting press goes
        val g1 = s.onAnswer(PolestarTab.LOOK, false, "garbled", 2_000L)
        assertEquals(a, g1.next!!.taps); assertTrue(g1.next!!.resend); assertFalse(g1.done)
        val g2 = s.onAnswer(PolestarTab.LOOK, false, "garbled", 3_000L)
        assertTrue(g2.done); assertEquals(c, g2.next!!.taps)
        // ok: done, nothing waiting
        val ok = s.onAnswer(PolestarTab.LOOK, true, null, 3_500L)
        assertTrue(ok.done && ok.ok && ok.next == null && !s.busy)
        // no answer: once more after the wait (plus the frame's own taps), then "lost"
        s.send(PolestarTab.RESET, PolestarTab.frame(PolestarTab.RESET, 444), 10_000L, dur = 900L)
        assertNull(s.tick(10_000L + PolestarTab.WAIT_MS))
        val t1 = s.tick(10_000L + PolestarTab.WAIT_MS + 900L)!!
        assertTrue(t1.next!!.resend)
        val t2 = s.tick(20_000L)!!
        assertTrue(t2.done && t2.why == "lost" && !s.busy)
        // "changed" ends it at once (the tab refreshes); an answer to another op is not this frame's
        s.send(PolestarTab.LOAD, PolestarTab.frame(PolestarTab.LOAD, 901, listOf(0, 3)), 30_000L)
        assertFalse(s.onAnswer(PolestarTab.LOOK, true, null, 30_100L).done)
        val ch = s.onAnswer(PolestarTab.LOAD, false, "changed", 30_200L)
        assertTrue(ch.done && ch.next == null)
        assertEquals(PolestarTab.CHANGED_LINE, PolestarTab.refusedLine("changed"))
        assertEquals(PolestarTab.FIGHT_LINE, PolestarTab.refusedLine("combat"))
        assertEquals(PolestarTab.LOST_LINE, PolestarTab.refusedLine("garbled"))
        assertNull(PolestarTab.refusedLine(null))
        // a tap that could not go: nothing in flight, nothing waiting
        s.send(PolestarTab.LOOK, a, 40_000L); s.send(PolestarTab.LOOK, b, 40_001L); s.failed()
        assertFalse(s.busy); assertNull(s.onAnswer(PolestarTab.LOOK, true, null, 40_002L).next)
    }

    @Test fun theBookKeepsTitlesAndGoalsByGuide() {
        val b = PolestarTab.Book()
        assertFalse(b.ready())
        assertTrue(b.onDoc(guide(), 1L))
        assertTrue(b.ready())
        b.onDoc(JSONObject().put("type", "pgsteps").put("gs", 444).put("n", 12).put("o", 1).put("t", JSONArray(listOf("Step one", "", "Three"))), 2L)
        assertEquals("Step one", b.title(1)); assertNull(b.title(2)); assertEquals("Three", b.title(3)); assertNull(b.title(12))
        b.onDoc(JSONObject().put("type", "pgstep").put("gs", 444).put("i", 4).put("t", "Wolves").put("g", JSONArray()), 3L)
        assertEquals("Wolves", b.step(4)!!.title); assertEquals(3L, b.stepAt(4))
        // another guide (a new stamp): the old titles and goals are not its
        b.onDoc(guide(gs = 999), 4L)
        assertNull(b.title(1)); assertNull(b.step(4))
        assertTrue(b.onDoc(JSONObject().put("type", "pglist").put("ls", 1).put("f", JSONArray()).put("g", JSONArray()), 5L))
        assertFalse(b.onDoc(JSONObject().put("type", "pstep"), 6L))
        val a = PolestarTab.answer(JSONObject().put("type", "pgop").put("op", 15).put("ok", false).put("why", "changed").put("i", 9))!!
        assertEquals(15, a.op); assertFalse(a.ok); assertEquals("changed", a.why); assertEquals(9, a.i)
    }

    @Test fun ourWordsHaveEsAndFrAndAreTerse() {
        val es = JSONObject(java.io.File(i18nDir(), "es.json").readText()); val fr = JSONObject(java.io.File(i18nDir(), "fr.json").readText())
        for (w in PolestarTab.WORDS) {
            assertTrue("es: $w", es.getJSONObject("plain").has(w)); assertTrue("fr: $w", fr.getJSONObject("plain").has(w))
            assertTrue("terse: $w", w.split(" ").size <= 6)
        }
        for (t in PolestarTab.TEMPLATES) { assertTrue("es: $t", es.getJSONObject("templates").has(t)); assertTrue("fr: $t", fr.getJSONObject("templates").has(t)) }
        // the files keep their CRLF line ends
        assertTrue(java.io.File(i18nDir(), "es.json").readText().contains("\"Change guide\": \"Cambiar guía\",\r\n"))
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
}
