package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * fix/menus-fit (the user on 1516: "The LB menu doesn't fit right with the map swapped and bigger and probably a lot of other menus"):
 * every Home overlay that parts the columns, over ALL 2,268 records the editor can make (the bar under or ABOVE its column too) x rest / a fight (grown or not, the bar hidden
 * or staying) / Party mode. Pure: the solver's boxes, [HomeSlide]'s halves, [LbFit] and [ClassSlot] as HomeView uses them.
 *  - the LB picture: under the band as laid, inside the frame, never covered by a part once the sides moved (each part by its column);
 *  - the middle wheel: inside the frame, under the band, never covered by a parted column;
 *  - the class wheels: inside the frame (a hidden Split half below the page is no column's), under the band;
 *  - no column is "across" the page (only a full-screen module keeps its name's side in the slides).
 * Classic at rest stays 1516's ([classicIs1516]); under a grown band it is fitted like every layout (the user's pick 2026-11-21, "Fix in
 * Classic too": [classicsGrownFightSitsUnderTheBand]).
 * feat/radial-castbar (the user, 2026-11-23: "it should still move out of the way to make space for the full sized radials"): Across's
 * bar drops below the page for each of them (HomeSlide.barDrop), so every bar is checked moved as it is drawn, and each radial is the
 * size it has on the same page with the bar Off ([everyRadialIsFullSize]).
 * fix/party-drop-wheels (the user's pick 2026-11-23, "List drops with the bar"): Party mode's full-width list at the foot, right over
 * Across's bar, goes down with the bar for each of them (HomeSlide.partyDrop: the wheels' into its place with the bar Off, the LB
 * picture's below the page) - those pages are checked full size too, and the list as drawn never covers a radial ([theListDropsWithTheBar]).
 */
class HomeMenusFitTest {
    private val S = HomeLayoutSolver
    private val W = 1240f
    private val G = 8f
    private val CARD_H = 118.75f

    private fun states(): List<Pair<String, HomeLayoutSolver.State>> = listOf(
        "rest" to HomeLayoutSolver.State(),
        "rest card" to HomeLayoutSolver.State(card = true, cardH = CARD_H),
        "fight" to HomeLayoutSolver.State(takeover = true, card = true, cardH = CARD_H),
        "fight hidden" to HomeLayoutSolver.State(takeover = true, card = true, cardH = CARD_H, castHidden = true),
        "party" to HomeLayoutSolver.State(party = HomeLayoutSolver.Party(PartyLayout.Mode.REST, 3, false), partyOn = true),
        "fight party" to HomeLayoutSolver.State(takeover = true, party = HomeLayoutSolver.Party(PartyLayout.Mode.FIGHT_OFF, 5, true)),
    )

    /** fix/party-drop-wheels: Party mode in a Grow fight (the list's rows grown, the bar shown) and five at rest - the pages with the
     *  full-width list over Across's bar beyond [states]' one */
    private fun listStates(): List<Pair<String, HomeLayoutSolver.State>> = listOf(
        "party 5" to HomeLayoutSolver.State(party = HomeLayoutSolver.Party(PartyLayout.Mode.REST, 5, false), partyOn = true),
        "party fight grow" to HomeLayoutSolver.State(takeover = true, party = HomeLayoutSolver.Party(PartyLayout.Mode.FIGHT_ON, 5, false), partyOn = true),
    )

    private class Page(val l: HomeLayout, val s: HomeLayoutSolver.State, val r: HomeLayoutSolver.Result) {
        val split get() = l.cast == HomeLayout.CastDock.SPLIT
        /** the band's foot as Home's followers read it (HomeView.barsBottom: Top screen's fight lays the band above the page, foot 0) */
        val bb get() = maxOf(0f, r.boxes.getValue(HomePart.BARS).b,
            // (the 1523 merge, as HomeView.barsFoot: a Top-screen fight's strip carrying the cast bar is the band's foot then)
            r.boxes[HomePart.FIGHT_CAST]?.takeIf { r.fightStrip && it.t >= -0.5f }?.b ?: 0f)
        /** the parts on the page (the buff list lies inside the map; a bar hidden for a fight lies below the page, or up behind the band) */
        val shown: Map<HomePart, HomeLayoutSolver.Box> get() = r.boxes.filter { (p, b) -> p != HomePart.BARS && p != HomePart.FIGHT_CAST && p != HomePart.AURAS && b.t < s.h &&
            !((p == HomePart.STRIP || p == HomePart.CAST2) && b.b <= bb + 0.5f) }
        /** the 1523 merge (party sizing x feat/radial-castbar): Party mode's full-width list is no column's (HomeView.halfOf: NONE) */
        fun half(p: HomePart, b: HomeLayoutSolver.Box): HomeSlide.Half = if (p == HomePart.PARTY && r.partyWide) HomeSlide.Half.NONE else
            HomeSlide.half(p == HomePart.STRIP || p == HomePart.CAST2, r.castSide, r.castHidden && r.castSide != CastStripModel.Side.OFF,
                b.cx, 1240f, split = split)
        fun line(): Pair<Float, Float> = HomeLayoutSolver.columns(l, 1240f, 8f).let { it.first.second to it.second.first }
        /** Party mode's full-width list on the page (HomeView's pw), or null: the radials stand under a top list, over a foot one */
        val wide: HomeLayoutSolver.Box? get() = r.boxes[HomePart.PARTY]?.takeIf { r.partyWide && it.t < s.h }
        val wideTop: Boolean get() = wide.let { it != null && (it.t + it.b) / 2f < s.h / 2f }
    }

    private fun pages(): List<Page> {
        val out = ArrayList<Page>()
        for (l in HomeLayoutRules.all()) for ((_, s) in states()) out += Page(l, s, S.places(l, s))
        return out
    }

    private fun box(b: LbFit.Box) = HomeLayoutSolver.Box(b.l, b.t, b.r, b.b)
    private fun moved(b: HomeLayoutSolver.Box, dx: Float, dy: Float = 0f) = b.copy(l = b.l + dx, r = b.r + dx, t = b.t + dy, b = b.b + dy)
    private fun bar(p: HomePart) = p == HomePart.STRIP || p == HomePart.CAST2
    /** the band and feat/fight-castbar's fight strip (its line): never a column's part, they stay put (HomeView.bandPart; the 1523 merge) */
    private fun band(p: HomePart) = p == HomePart.BARS || p == HomePart.FIGHT_CAST
    /** feat/radial-castbar: how far [part] drops for an open radial that asked for the room (Across's bar below the page), as HomeView.barDrop */
    private fun drop(pg: Page, part: HomePart, b: HomeLayoutSolver.Box) = HomeSlide.barDrop(bar(part), pg.half(part, b), b.t, pg.s.h)
    /** a part as drawn under an open radial: by its half ([pl] / [pr]) and, [dropped], its drop; a full-screen module keeps its own place */
    private fun drawn(pg: Page, part: HomePart, b: HomeLayoutSolver.Box, pl: Float, pr: Float, dropped: Boolean): HomeLayoutSolver.Box? {
        if (!bar(part) && HomeSlide.fullWidth(b.l, b.r, W, G)) return null
        return moved(b, HomeSlide.dx(pg.half(part, b), pl, pr), if (dropped) drop(pg, part, b) else 0f)
    }
    private fun overlap(a: HomeLayoutSolver.Box, b: HomeLayoutSolver.Box) =
        a.l < b.r - 0.5f && b.l < a.r - 0.5f && a.t < b.b - 0.5f && b.t < a.b - 0.5f
    /** the bar of no column (Across) on the page, the one a radial may ask to drop (HomeView.acrossBox) */
    private fun across(p: Page): HomeLayoutSolver.Box? = p.shown.entries.firstOrNull { (part, b) -> drop(p, part, b) > 0f }?.value
    /** fix/party-drop-wheels: how far Party mode's full-width foot list right over Across's bar goes down with it (HomeView.partyDrop):
     *  the wheels' ([below] false) into its place with the bar Off, the LB picture's below the page; 0 = it stays */
    private fun listDrop(p: Page, below: Boolean = false): Float {
        val pw = p.wide ?: return 0f
        return HomeSlide.partyDrop(true, pw.t, pw.b, across(p)?.t, p.s.h, below)
    }
    /** the pages the ruling is about: the full-width list at the foot, right over Across's bar laid on the page */
    private fun listOverAcross(p: Page): Boolean {
        val pw = p.wide ?: return false
        val a = across(p) ?: return false
        return !p.wideTop && pw.b <= a.t + 0.5f
    }

    /** the LB picture as HomeView.lbFit places it (fitted, not Classic's held look) and whether Across's bar drops for it */
    private fun lb(p: Page): Pair<LbFit.Place, Boolean> {
        val line = p.line()
        fun at(bottom: Float) = LbFit.place(W, p.bb, line.first, line.second, bottom, classic = false, staleBars = 0f)
        val full = at(p.s.h - G)
        // (fix/party-drop-wheels, as HomeView.lbFit: over the list and the bar both, or both below the page)
        val pw = p.wide
        if (pw != null && listDrop(p, below = true) > 0f) {
            val kept = at(minOf(HomeSlide.stayFoot(across(p)?.t, p.s.h, G), pw.t - G))
            val drop = HomeSlide.makesRoom(pw.t, kept.k * LbPartyView.PICT_H, full.k * LbPartyView.PICT_H, LbFit.bars(kept).values.maxOf { it.b }, G)
            return (if (drop) full else kept) to drop
        }
        val a = across(p) ?: return full to false
        val kept = at(HomeSlide.stayFoot(a.t, p.s.h, G))
        val drop = HomeSlide.makesRoom(a.t, kept.k * LbPartyView.PICT_H, full.k * LbPartyView.PICT_H, LbFit.bars(kept).values.maxOf { it.b }, G)
        return (if (drop) full else kept) to drop
    }
    /** the middle wheel as HomeView.wheelFit takes it, and whether Across's bar drops for it */
    private fun middle(p: Page): Pair<ClassSlot.Middle, Boolean> {
        val line = p.line(); val mid = (line.first + line.second) / 2f
        val a = across(p)
        // (the 1523 merge, as HomeView.wheelFit: a full-width party list cuts both squares - under a top list, over a foot one)
        val pw = p.wide
        val bb = maxOf(p.bb, if (pw != null && p.wideTop) pw.b else 0f)
        val pwFoot = if (pw != null && !p.wideTop) pw.t - G else null
        // (fix/party-drop-wheels: the full square over the list dropped with the bar, in its place with the bar Off)
        val fullFoot = if (pwFoot == null) p.s.h - G else minOf(p.s.h - G, pwFoot + listDrop(p))
        val keptFoot = HomeSlide.stayFoot(a?.t, p.s.h, G).let { if (pwFoot == null) it else minOf(it, pwFoot) }
        val full = ClassSlot.middle(W, p.s.h, bb, G, W * 0.6f, bottom = fullFoot, mid = mid)
        val kept = ClassSlot.middle(W, p.s.h, bb, G, W * 0.6f, bottom = keptFoot, mid = mid)
        val drop = HomeSlide.makesRoom(a?.t, kept.side, full.side, kept.top + kept.side, G)
        return (if (drop) full else kept) to drop
    }
    /** a class wheel's square as HomeView.classLayerShow takes it (its column's parts; down to Across's foot when that bar drops for it) */
    private fun classSlot(p: Page, left: Boolean): Pair<ClassSlot.Slot, Boolean> {
        val want = if (left) HomeSlide.Half.LEFT else HomeSlide.Half.RIGHT
        val col = p.r.boxes.filter { (part, b) -> !band(part) && part != HomePart.AURAS && p.half(part, b) == want }
            .values.map { ClassSlot.Area(it.l, it.t, it.r, it.b) }
        val a = across(p)
        // (the 1523 merge, as HomeView.classLayerShow: a full-width party list cuts the column - its part under a top list, over a foot one)
        val pw = p.wide
        // (fix/party-drop-wheels: the full square over the list dropped with the bar)
        val clip = { c: ClassSlot.Area, dy: Float -> if (pw == null) c else if (p.wideTop) c.copy(top = maxOf(c.top, pw.b + G))
            else c.copy(bottom = minOf(c.bottom, pw.t + dy - G)) }
        val kept = ClassSlot.inColumn(clip(ClassSlot.column(col, p.bb, p.s.h, G, left, W), 0f))
        val full = ClassSlot.inColumn(clip(ClassSlot.column(col, p.bb, p.s.h, G, left, W, reach = a?.b ?: 0f), listDrop(p)))
        val drop = HomeSlide.makesRoom(a?.t, kept.side, full.side, kept.top + kept.side, G)
        return (if (drop) full else kept) to drop
    }

    @Test fun noColumnIsAcrossThePage() {
        for (side in HomeLayout.Side.values()) for (share in HomeLayout.Share.values()) {
            val b = S.places(HomeLayout(chatSide = side, chatShare = share), HomeLayoutSolver.State()).boxes
            for (p in listOf(HomePart.CHAT, HomePart.MAP)) {
                val x = b.getValue(p)
                assertTrue("$side $share $p is a column, not across", !HomeSlide.fullWidth(x.l, x.r, W, G))
                // its centre is on its own side of the line between the columns
                val line = HomeLayoutSolver.columns(HomeLayout(chatSide = side, chatShare = share), W, G)
                assertEquals("$side $share $p's centre is on its own side", x.cx < W / 2f, x.r <= line.first.second + 0.5f)
            }
        }
    }

    @Test fun theLbPictureFitsEveryLayout() {
        val bad = ArrayList<String>()
        var n = 0
        for (p in pages()) {
            val (pl, dropped) = lb(p)
            val bars = LbFit.bars(pl).values.map { box(it) }
            n++
            fun no(m: String) { if (bad.size < 12) bad += "${p.l} / ${p.s}: $m" }
            if (pl.k < LbFit.K_MIN - 1e-3f) no("scaled to ${pl.k}")
            for (b in bars) {
                if (b.l < G - 0.5f || b.r > W - G + 0.5f || b.b > p.s.h - G + 0.5f) no("bar $b off the frame")
                if (b.t < p.bb) no("bar $b under the band (bottom ${p.bb})")
            }
            for ((part, b) in p.shown) {
                val m = drawn(p, part, b, pl.pushLeft, pl.pushRight, dropped) ?: continue
                for (x in bars) if (overlap(m, x)) no("$part ${m} covers the picture's bar $x (push ${pl.pushLeft} / ${pl.pushRight})")
            }
        }
        assertTrue("checked $n pages:\n${bad.joinToString("\n")}", bad.isEmpty())
    }

    @Test fun theMiddleWheelFitsEveryLayout() {
        val bad = ArrayList<String>()
        for (p in pages()) {
            val (m, dropped) = middle(p)
            val sq = HomeLayoutSolver.Box(m.left, m.top, m.left + m.side, m.top + m.side)
            fun no(x: String) { if (bad.size < 12) bad += "${p.l} / ${p.s}: $x" }
            if (sq.l < G - 0.5f || sq.r > W - G + 0.5f || sq.b > p.s.h - G + 0.5f || sq.t < p.bb) no("square $sq off the frame / under the band")
            for ((part, b) in p.shown) {
                val mv = drawn(p, part, b, m.push, m.pushRight, dropped) ?: continue
                if (overlap(mv, sq)) no("$part $mv covers the wheel $sq")
            }
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun theClassWheelsStayInsideTheFrame() {
        val bad = ArrayList<String>()
        for (p in pages()) for (left in listOf(true, false)) {
            val want = if (left) HomeSlide.Half.LEFT else HomeSlide.Half.RIGHT
            val (sl, dropped) = classSlot(p, left)
            if (sl.left < 0f || sl.left + sl.side > W || sl.top < p.bb || sl.top + sl.side > p.s.h - G + 0.5f)
                if (bad.size < 12) bad += "${p.l} / ${p.s} ${if (left) "LB+LT" else "RB+RT"}: ${sl}"
            // feat/radial-castbar: no bar over it once its column slid off its edge and Across's bar dropped (the other column stays)
            val sq = HomeLayoutSolver.Box(sl.left, sl.top, sl.left + sl.side, sl.top + sl.side)
            for ((part, b) in p.shown) if (bar(part)) {
                val away = if (p.half(part, b) == want) ClassSlot.away(left, b.l, b.r, W, G) else 0f
                val mv = moved(b, away, if (dropped) drop(p, part, b) else 0f)
                if (overlap(mv, sq) && bad.size < 12) bad += "${p.l} / ${p.s} ${if (left) "LB+LT" else "RB+RT"}: $part $mv covers the wheel $sq"
            }
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    /**
     * feat/radial-castbar: THE FULL SIZE - over every record x state, the middle wheel, both class wheels and the LB picture are as large
     * as on the same page with the bar Off, whatever the bar's dock and slot size (the bar drops when it would make one smaller; one that
     * fits above Across's bar at that size leaves it where it is)
     */
    @Test fun everyRadialIsFullSize() {
        val bad = ArrayList<String>()
        var n = 0
        var skipped = 0
        var drops = 0
        var lists = 0
        for (l in HomeLayoutRules.all()) for ((sn, s) in states() + listStates()) {
            if (l.cast == HomeLayout.CastDock.OFF) continue
            val p = Page(l, s, S.places(l, s))
            val o = l.copy(cast = HomeLayout.CastDock.OFF)
            val q = Page(o, s, S.places(o, s))
            fun no(x: String) { if (bad.size < 12) bad += "$l / $sn: $x" }
            if (p.bb != q.bb) { skipped++; continue }                     // the band itself moved (an F-rule): not the radial's making
            // (the 1523 merge: party sizing lays its list by the bar - a foot list keeps its column over a one-column bar: the party list
            // itself moved, not the radial's making. fix/party-drop-wheels: a full-width foot list over Across's bar goes down with the
            // bar into its place with the bar Off ([listDrop]) - checked; over Split's halves (they part with their columns) it stays)
            if (p.r.partyWide != q.r.partyWide || p.wide != q.wide && !listOverAcross(p)) { skipped++; continue }
            if (p.wide != q.wide) { lists++
                val dropped = moved(p.wide!!, 0f, listDrop(p))
                if (kotlin.math.abs(dropped.t - q.wide!!.t) > 0.5f || kotlin.math.abs(dropped.b - q.wide!!.b) > 0.5f) no("the list dropped to $dropped, with no bar ${q.wide}")
            }
            val (m, md) = middle(p)
            if (kotlin.math.abs(m.side - middle(q).first.side) > 0.5f) no("middle wheel ${m.side}, with no bar ${middle(q).first.side}")
            for (left in listOf(true, false)) {
                val (a, ad) = classSlot(p, left); val b = classSlot(q, left).first
                if (kotlin.math.abs(a.side - b.side) > 0.5f) no("${if (left) "LB+LT" else "RB+RT"} $a, with no bar $b")
                if (ad) drops++
            }
            val (pl, ld) = lb(p)
            if (kotlin.math.abs(pl.k - lb(q).first.k) > 1e-4f) no("LB picture at ${pl.k}, with no bar ${lb(q).first.k}")
            if (md) drops++
            if (ld) drops++
            n++
        }
        println("everyRadialIsFullSize: $n pages, $skipped skipped, $drops drops, $lists with the party list dropped")
        assertTrue("checked $n pages ($skipped skipped, $drops drops, $lists with the party list dropped):\n${bad.joinToString("\n")}",
            bad.isEmpty() && n > 10_000 && drops > 1000 && lists > 100)
    }

    /**
     * fix/party-drop-wheels (the user's pick 2026-11-23, "List drops with the bar"): over every record x state with Party mode's full-width
     * list at the foot right over Across's bar - the list goes down with the bar whenever the bar drops for a radial (the same distance:
     * into its place with the bar Off, or below the page for the LB picture); as drawn it never covers the middle wheel, a class wheel or
     * the LB picture; an LB picture that fits over both moves neither ("Stay when it fits"); a top list never moves
     */
    @Test fun theListDropsWithTheBar() {
        val bad = ArrayList<String>()
        var n = 0; var stays = 0; var tops = 0
        fun sq(m: ClassSlot.Middle) = HomeLayoutSolver.Box(m.left, m.top, m.left + m.side, m.top + m.side)
        for (l in HomeLayoutRules.all()) for ((sn, s) in states() + listStates()) {
            val p = Page(l, s, S.places(l, s))
            val pw = p.wide ?: continue
            fun no(x: String) { if (bad.size < 12) bad += "$l / $sn: $x" }
            if (p.wideTop) { tops++; if (listDrop(p) != 0f || listDrop(p, true) != 0f) no("a top list drops"); continue }
            if (!listOverAcross(p)) continue
            val a = across(p)!!
            n++
            if (listDrop(p) != drop(p, HomePart.STRIP, a)) no("the list's drop ${listDrop(p)} is not the bar's ${drop(p, HomePart.STRIP, a)}")
            val (m, md) = middle(p)
            val ml = moved(pw, 0f, if (md) listDrop(p) else 0f)
            if (overlap(ml, sq(m))) no("middle wheel ${sq(m)}: the list drawn $ml over it")
            for (left in listOf(true, false)) {
                val (c, cd) = classSlot(p, left)
                val cs = HomeLayoutSolver.Box(c.left, c.top, c.left + c.side, c.top + c.side)
                val cl = moved(pw, 0f, if (cd) listDrop(p) else 0f)
                if (overlap(cl, cs)) no("${if (left) "LB+LT" else "RB+RT"} $cs: the list drawn $cl over it")
            }
            val (pl, ld) = lb(p)
            if (!ld) {
                stays++
                for (b in LbFit.bars(pl).values.map { box(it) }) if (overlap(pw, b)) no("LB picture: the list $pw stays over its bar $b")
            } else if (pw.t + listDrop(p, true) < p.s.h - 0.5f) no("LB picture: the list not below the page")
        }
        println("theListDropsWithTheBar: $n pages, $stays LB pictures fit over both, $tops top lists")
        assertTrue("checked $n pages ($stays LB pictures fit over both, $tops top lists):\n${bad.joinToString("\n")}",
            bad.isEmpty() && n > 100 && tops > 100)
    }

    /** Across's bar stays where a radial fits above it at its full size: Classic's LB picture at rest (1.0.31: "If its set to both it can
     *  stay in place"); the middle wheel and the class wheels it would shrink - it drops for them */
    @Test fun acrossDropsOnlyWhenARadialNeedsTheRoom() {
        val l = HomeLayout(cast = HomeLayout.CastDock.ACROSS)
        val st = HomeLayoutSolver.State()
        val p = Page(l, st, S.places(l, st))
        assertTrue("there is an Across bar", across(p) != null)
        assertTrue("the LB picture fits above it", !lb(p).second)
        assertEquals(1f, lb(p).first.k, 0f)
        assertTrue("the middle wheel needs the room", middle(p).second)
        assertTrue("the class wheels need the room", classSlot(p, true).second && classSlot(p, false).second)
        // Split's halves go with their columns: nothing drops, the middle wheel is full size already
        val sp = l.copy(cast = HomeLayout.CastDock.SPLIT)
        val q = Page(sp, st, S.places(sp, st))
        assertTrue(across(q) == null && !middle(q).second)
    }

    /** the bar above its column moves no menu: the LB picture and the middle wheel stand where its Under twin's do, in every state
     *  (the bar goes with its column in the slides; slid up behind the band it is no limit) */
    @Test fun aTopBarMovesNoMenu() {
        var n = 0
        for (p in pages()) {
            if (!p.l.cast.top) continue
            val t = Page(p.l.copy(cast = p.l.cast.withTop(false)), p.s, S.places(p.l.copy(cast = p.l.cast.withTop(false)), p.s))
            val line = p.line()
            assertEquals("${p.l} / ${p.s} (${line})", lb(t).first, lb(p).first)
            // the top bar goes with its column (shown), no column's when hidden
            val strip = p.r.boxes.getValue(HomePart.STRIP)
            val col = p.r.boxes.getValue(if (p.l.cast.column == HomeModule.CHAT) HomePart.CHAT else HomePart.MAP)
            if (!p.r.castHidden) assertEquals(p.half(HomePart.CHAT, col), p.half(HomePart.STRIP, strip))
            else assertTrue(HomePart.STRIP !in p.shown)
            n++
        }
        assertTrue(n > 600 * 5)
    }

    /** Fighter's Split bar hidden for a fight is no column's (it lies below the page); shown, each half is its column's */
    @Test fun aHiddenSplitHalfIsNoColumns() {
        val B = CastStripModel.Side.BOTH
        assertEquals(HomeSlide.Half.NONE, HomeSlide.half(true, B, true, 300f, W, split = true))
        assertEquals(HomeSlide.Half.NONE, HomeSlide.half(true, B, true, 930f, W, split = true))
        assertEquals(HomeSlide.Half.LEFT, HomeSlide.half(true, B, false, 300f, W, split = true))
        assertEquals(HomeSlide.Half.RIGHT, HomeSlide.half(true, B, false, 930f, W, split = true))
        assertEquals("Both's bar stays", HomeSlide.Half.NONE, HomeSlide.half(true, B, false, 620f, W))
    }

    /** Classic (chat left, half): the picture and the wheels stand where 1516 put them, both sides 340 */
    @Test fun classicIs1516() {
        val c = HomeLayoutSolver.columns(HomeLayout(), W, G)
        val pl = LbFit.place(W, 226f, c.first.second, c.second.first, 927f, classic = true, staleBars = 0f)
        assertEquals(620f, pl.cx, 0f); assertEquals(236f, pl.top, 0f); assertEquals(1f, pl.k, 0f)
        assertEquals(LbPartyView.SIDE_PX, pl.pushLeft, 0f); assertEquals(LbPartyView.SIDE_PX, pl.pushRight, 0f)
        // the new rule gives Classic's rest look too (the band 226: top 236, the page's middle, 340 each side)
        val nw = LbFit.place(W, 226f, c.first.second, c.second.first, 927f, classic = false, staleBars = 0f)
        assertEquals(pl, nw.copy(classic = true))
        val bars = LbFit.bars(pl)
        assertEquals(312f, bars.getValue("PADDLEFT").l, 0f); assertEquals(928f, bars.getValue("PADDRIGHT").r, 0f)
        assertEquals(236f + 462f, bars.getValue("PADDDOWN").b, 0f)
        val m = ClassSlot.middle(W, 935f, 226f, G, W * 0.6f, mid = (c.first.second + c.second.first) / 2f)
        val old = ClassSlot.middle(W, 935f, 226f, G, W * 0.6f)
        assertEquals(old, m)
        assertEquals(m.push, m.pushRight, 0f)
    }

    /**
     * the user's pick 2026-11-21, "Fix in Classic too": 1516 drew Classic's LB picture at 236 (or a stale pause's band + 16) over a band
     * grown for a fight; now it stands under the grown band as every layout's does (fitted: its top the band's foot + 10, scaled to the
     * frame, in the picture's place on the page - not the held 1516 coordinates), while at rest (Diamonds, the thin row, Same size) it
     * keeps 1516's place exactly, its stale-top quirk included
     */
    @Test fun classicsGrownFightSitsUnderTheBand() {
        val classic = HomeLayout(cast = HomeLayout.CastDock.UNDER_CHAT)
        val c = HomeLayoutSolver.columns(classic, W, G)
        val grown = S.places(classic, HomeLayoutSolver.State(takeover = true))
        val bb = grown.boxes.getValue(HomePart.BARS).b
        assertTrue("Classic's band grown for the fight: $bb", bb > 400f)
        val pl = LbFit.place(W, bb, c.first.second, c.second.first, 927f, classic = true, staleBars = 0f)
        assertEquals("under the grown band", bb + 10f, pl.top, 0f)
        assertFalse("fitted: Home's coordinates (the host moves it into the picture's)", pl.classic)
        assertEquals("the fitted place, as any layout's", LbFit.place(W, bb, c.first.second, c.second.first, 927f, classic = false, staleBars = 0f), pl)
        for (b in LbFit.bars(pl).values) { assertTrue("under the band: ${b.t}", b.t >= bb); assertTrue("inside the frame: ${b.b}", b.b <= 927f + 0.5f) }
        assertTrue(pl.k >= LbFit.K_MIN)
        // a stale pause's band (a grown fight's, 1516's quirk) cannot lift it over the band either
        assertEquals(bb + 10f, LbFit.place(W, bb, c.first.second, c.second.first, 927f, classic = true, staleBars = 600f).top, 0f)
        // at rest: 1516's place exactly (the diamonds 226, the thin row's lower band, the stale top kept)
        for (rest in listOf(226f, 120f)) {
            val r = LbFit.place(W, rest, c.first.second, c.second.first, 927f, classic = true, staleBars = 0f)
            assertEquals(LbFit.Place(620f, 236f, 1f, LbPartyView.SIDE_PX, LbPartyView.SIDE_PX, true), r)
        }
        assertEquals(616f, LbFit.place(W, 226f, c.first.second, c.second.first, 927f, classic = true, staleBars = 600f).top, 0f)
        // Same size (the band at its rest height in the fight): 1516's too
        val same = S.places(classic.copy(fight = HomeLayout.FightBand.SAME), HomeLayoutSolver.State(takeover = true)).boxes.getValue(HomePart.BARS).b
        assertTrue(LbFit.place(W, same, c.first.second, c.second.first, 927f, classic = true, staleBars = 0f).classic)
    }

    /** feat/radial-castbar: Compact's Across bar in a fight no longer shrinks the picture under a grown band - the bar drops below the
     *  page and the picture is as large as the space under the band allows (it scaled to fit above that bar) */
    @Test fun theLbPictureNoLongerScalesAboveABothBar() {
        val l = HomeArrangements.of(HomeArrangements.COMPACT, HomeLayout())
        val st = HomeLayoutSolver.State(takeover = true)
        val r = S.places(l, st)
        val strip = r.boxes.getValue(HomePart.STRIP)
        val p = Page(l, st, r)
        assertEquals("Across's bar", HomeSlide.Half.NONE, p.half(HomePart.STRIP, strip))
        assertEquals("drops below the page", st.h, strip.t + drop(p, HomePart.STRIP, strip), 0.01f)
        val bb = r.boxes.getValue(HomePart.BARS).b
        val c = HomeLayoutSolver.columns(l, W, G)
        val (pl, dropped) = lb(p)
        assertTrue("it drops for the picture", dropped)
        val old = LbFit.place(W, bb, c.first.second, c.second.first, strip.t - G, classic = false, staleBars = 0f)
        assertEquals(bb + 10f, pl.top, 0f)
        assertTrue("larger than above the bar: ${pl.k} vs ${old.k}", pl.k > old.k || pl.k == 1f)
        for (b in LbFit.bars(pl).values) assertTrue("inside the frame: ${b.b}", b.b <= st.h - G + 0.5f)
    }
}
