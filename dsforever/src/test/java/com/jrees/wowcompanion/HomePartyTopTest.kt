package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PARTY MODE KEEPS THE TOP SCREEN (the user's ruling C, 2026-11-22: "It should stay at the top in party mode but the party bars should
 * fill the area"): with In fights = Top screen, a fight with Party mode on lays the band above the page as any Top fight does (no
 * takeover: [HomeLayoutSolver.away] by [HomeLayoutSolver.State.partyFight]), the map's column grows up to the top gap, and the party module
 * ("the party bars") takes the chat column's freed strip - its well from the column's new top down to the foot it had in b1e28506's Party
 * fight, the rows sharing the height ([PartyLayout.fillH]: 88..192 px; a solo or two-row party gives the rest to the chat). Out of
 * Party mode, Grow / Same size and the takeover: nothing changes.
 */
class HomePartyTopTest {
    private val S = HomeLayoutSolver
    private val CARD_H = 118.75f
    private val top = HomeLayout(fight = HomeLayout.FightBand.TOP)

    private fun party(mode: PartyLayout.Mode, n: Int, pet: Boolean = false) = HomeLayoutSolver.Party(mode, n, pet)
    private fun fightState(n: Int, pet: Boolean = false, card: Boolean = false) = HomeLayoutSolver.State(card = card, cardH = if (card) CARD_H else 0f,
        party = party(PartyLayout.Mode.FIGHT_TOP, n, pet), partyOn = true, partyFight = true)
    /** b1e28506's Party fight (the band on the page, FIGHT_ON rows): the reference the filled module keeps its foot from */
    private fun refState(s: HomeLayoutSolver.State) = s.copy(partyFight = false, party = s.party!!.copy(mode = PartyLayout.Mode.FIGHT_ON))
    private fun rowCount(p: HomeLayoutSolver.Party) = PartyModel.rows(p.n, p.pet).size

    @Test fun thePartyFightLaysTheBandAwayAndTheRowsFillTheStrip() {
        val s = fightState(3)
        assertTrue(S.away(top, s)); assertTrue(S.partyAway(top, s))
        val r = S.places(top, s)
        val ref = S.places(top, refState(s))
        assertTrue("the band away (b1e28506 laid it on the page)", r.away); assertFalse(ref.away)
        assertEquals(-HomeLayoutSolver.AWAY_PAST, r.boxes.getValue(HomePart.BARS).b, 0f)
        val p = r.boxes.getValue(HomePart.PARTY); val rp = ref.boxes.getValue(HomePart.PARTY)
        assertEquals("the well from the top gap", 8f, p.t, 0.01f)
        assertEquals("its foot where the Party fight had it", rp.b, p.b, 0.01f)
        assertEquals("the Diamonds' Party fight well: 234..538", 234f, rp.t, 0.01f); assertEquals(538f, rp.b, 0.01f)
        assertEquals("three rows of 163.3", (530f - 24f - 16f) / 3f, PartyLayout.rowsFit(3, p.h), 0.01f)
        assertEquals("the chat where it was", ref.boxes.getValue(HomePart.CHAT).t, r.boxes.getValue(HomePart.CHAT).t, 0.01f)
        assertEquals("the map grows up", 8f, r.boxes.getValue(HomePart.MAP).t, 0f)
        assertTrue(HomeLayoutRules.problems(top, s, r).isEmpty())
        // a two-row and a one-row party: capped at FILL_MAX_H, the rest to the chat
        for ((n, rows) in listOf(2 to 2, 1 to 1)) {
            val s2 = fightState(n)
            val r2 = S.places(top, s2); val ref2 = S.places(top, refState(s2))
            val p2 = r2.boxes.getValue(HomePart.PARTY)
            assertEquals("n=$n: capped", PartyLayout.FILL_MAX_H, PartyLayout.rowsFit(rows, p2.h), 0.01f)
            assertEquals(8f + 2 * PartyLayout.IN + rows * PartyLayout.FILL_MAX_H + (rows - 1) * PartyLayout.GAP, p2.b, 0.01f)
            assertTrue("n=$n: the chat grows up", r2.boxes.getValue(HomePart.CHAT).t < ref2.boxes.getValue(HomePart.CHAT).t - 1f)
            assertEquals(p2.b + PartyLayout.CHAT_GAP, r2.boxes.getValue(HomePart.CHAT).t, 0.01f)
        }
    }

    @Test fun onlyTheFightWithTopScreenAndPartyModeOn() {
        val s = fightState(3)
        assertFalse("no fight", S.away(top, s.copy(partyFight = false)))
        assertFalse("Party mode off", S.away(top, s.copy(partyOn = false)))
        assertFalse("a module full screen", S.away(top, s.copy(full = HomeModule.MAP)))
        for (f in listOf(HomeLayout.FightBand.GROW, HomeLayout.FightBand.SAME)) {
            val l = top.copy(fight = f)
            assertFalse(S.away(l, s))
            // Grow / Same size: a Party fight lays as before (partyFight changes nothing)
            assertEquals(S.places(l, refState(s)).boxes, S.places(l, refState(s).copy(partyFight = true)).boxes)
        }
        // the takeover with Party mode on (R0) and without it: unchanged
        val tk = HomeLayoutSolver.State(takeover = true, partyOn = true, party = party(PartyLayout.Mode.FIGHT_OFF, 3))
        assertFalse(S.away(top, tk)); assertFalse(S.away(top, tk.copy(partyFight = true)))
        assertEquals(S.places(top, tk).boxes, S.places(top, tk.copy(partyFight = true)).boxes)
        val tk2 = HomeLayoutSolver.State(takeover = true)
        assertTrue(S.away(top, tk2)); assertEquals(S.places(top, tk2).boxes, S.places(top, tk2.copy(partyFight = true)).boxes)
        // no party module (QOL > Party mode off): the columns grow as in any Top fight
        val noModule = S.places(top, HomeLayoutSolver.State(partyOn = true, partyFight = true))
        assertTrue(noModule.away)
        assertEquals(8f, noModule.boxes.getValue(HomePart.CHAT).t, 0f); assertEquals(8f, noModule.boxes.getValue(HomePart.MAP).t, 0f)
    }

    /** the sweep: every side x width x card dock x bar dock x party shape x rest, with a card shown and without */
    @Test fun everyRecordLaysTheFilledModuleValidly() {
        var n = 0
        val bad = ArrayList<String>()
        for (side in HomeLayout.Side.values()) for (share in HomeLayout.Share.values()) for (card in HomeLayout.CardDock.values())
            for (cast in HomeLayout.CastDock.values()) for (rest in HomeRest.values()) {
                val l = HomeLayout(chatSide = side, chatShare = share, card = card, cast = cast, rest = rest, fight = HomeLayout.FightBand.TOP)
                for (cnt in 1..6) for (pet in listOf(false, true)) for (shown in listOf(false, true)) {
                    if (pet && cnt == 1) continue
                    val s = fightState(cnt, pet, card = shown)
                    val r = S.places(l, s); val ref = S.places(l, refState(s)); n++
                    fun bad(m: String) { if (bad.size < 12) bad += "$l n=$cnt pet=$pet card=$shown: $m" }
                    if (!r.away) bad("not away")
                    val pr = HomeLayoutRules.problems(l, s, r)
                    if (pr.isNotEmpty()) bad("$pr")
                    val p = r.boxes.getValue(HomePart.PARTY); val rp = ref.boxes.getValue(HomePart.PARTY)
                    val chat = r.boxes.getValue(HomePart.CHAT); val map = r.boxes.getValue(HomePart.MAP)
                    val rows = PartyLayout.rowsFit(rowCount(s.party!!), p.h)
                    if (rows < PartyLayout.FIGHT_H - 0.01f || rows > PartyLayout.FILL_MAX_H + 0.01f) bad("rows $rows")
                    if (p.b > rp.b + 0.01f) bad("the well's foot ${p.b} below the reference's ${rp.b}")
                    if (rows < PartyLayout.FILL_MAX_H - 0.01f && kotlin.math.abs(p.b - rp.b) > 0.01f) bad("foot ${p.b} vs ${rp.b} (not capped)")
                    if (p.t > rp.t - 100f) bad("the well did not grow up: ${p.t} vs ${rp.t}")
                    if (chat.t > ref.boxes.getValue(HomePart.CHAT).t + 0.01f) bad("chat top ${chat.t} under the reference's")
                    if (r.f1 != ref.f1 || r.f2 != ref.f2) bad("fallbacks ${r.f1}/${r.f2} vs ${ref.f1}/${ref.f2}")
                    // the well inside the chat's column (the tab on the screen's outer edge)
                    val chatLeft = side == HomeLayout.Side.LEFT
                    if (chatLeft && p.r > chat.r + 0.01f || !chatLeft && p.l < chat.l - 0.01f) bad("the well out of the chat column: $p / $chat")
                    // the map's column as in the Top fight out of Party mode
                    val plain = S.places(l, HomeLayoutSolver.State(takeover = true, card = shown, cardH = if (shown) CARD_H else 0f))
                    if (kotlin.math.abs(map.t - plain.boxes.getValue(HomePart.MAP).t) > 0.01f && !r.f1) bad("map top ${map.t}")
                }
            }
        assertTrue("checked $n: ${bad.joinToString("\n")}", bad.isEmpty())
        assertTrue(n > 5000)
    }

    /**
     * THE 1519 INTEGRATION (feat/home-apps2's party docks x feat/topbar-fight's fill): the strip the band frees goes to the party list
     * in ITS column for every dock - Above chat / Above map: the well from the column's new top down to the reference page's foot (the
     * chat / map under it where it was); Under chat / Under map: the chat / map rides up into the strip keeping its height and the well's
     * top follows it up as far, its foot on the column's foot. The numbers for Diamonds, the chat on the left, three members
     */
    @Test fun everyPartyDockFillsTheStripInItsColumn() {
        for (dock in HomeLayout.PartyDock.values()) {
            val l = top.copy(party = dock)
            val s = fightState(3)
            val r = S.places(l, s); val ref = S.places(l, refState(s))
            val part = if (dock.column == HomeModule.CHAT) HomePart.CHAT else HomePart.MAP
            val other = if (part == HomePart.CHAT) HomePart.MAP else HomePart.CHAT
            val p = r.boxes.getValue(HomePart.PARTY); val rp = ref.boxes.getValue(HomePart.PARTY)
            val c = r.boxes.getValue(part); val rc = ref.boxes.getValue(part)
            assertTrue("$dock: away", r.away)
            assertTrue("$dock: ${HomeLayoutRules.problems(l, s, r)}", HomeLayoutRules.problems(l, s, r).isEmpty())
            val freed = 234f - 8f                                         // the Diamonds' rest band: the column tops 234 -> 8
            assertEquals("$dock: its well grew by the freed strip", rp.h + freed, p.h, 0.01f)
            assertEquals("$dock: three rows of 163.3", (530f - 24f - 16f) / 3f, PartyLayout.rowsFit(3, p.h - PartyModuleView.RISE), 0.01f)
            assertEquals("$dock: the column's other part keeps its height", rc.h, c.h, 0.01f)
            if (dock.top) {
                assertEquals("$dock: the well from the top gap", 8f, p.t + PartyModuleView.RISE, 0.01f)
                assertEquals("$dock: its foot where the Party fight had it", rp.b, p.b, 0.01f)
                assertEquals("$dock: the $part where it was", rc.t, c.t, 0.01f)
            } else {
                assertEquals("$dock: the $part from the top gap", 8f, c.t, 0.01f)
                assertEquals("$dock: the well's foot on the column's foot", rp.b, p.b, 0.01f)
                assertEquals("$dock: the well under the $part", c.b + PartyLayout.CHAT_GAP, p.t + PartyModuleView.RISE, 0.01f)
            }
            assertEquals("$dock: the other column grows up", 8f, r.boxes.getValue(other).t, 0.01f)
        }
    }

    /** the sweep for every party dock: side x width x card dock x bar dock x rest x party shape x card shown */
    @Test fun everyDockLaysTheFilledModuleValidly() {
        var n = 0
        val bad = ArrayList<String>()
        for (dock in HomeLayout.PartyDock.values()) for (side in HomeLayout.Side.values()) for (share in HomeLayout.Share.values())
            for (card in HomeLayout.CardDock.values()) for (cast in HomeLayout.CastDock.values()) for (rest in HomeRest.values()) {
                val l = HomeLayout(chatSide = side, chatShare = share, card = card, cast = cast, rest = rest, party = dock,
                    fight = HomeLayout.FightBand.TOP)
                for (cnt in 1..6) for (pet in listOf(false, true)) for (shown in listOf(false, true)) {
                    if (pet && cnt == 1) continue
                    val s = fightState(cnt, pet, card = shown)
                    val r = S.places(l, s); val ref = S.places(l, refState(s)); n++
                    fun bad(m: String) { if (bad.size < 12) bad += "$l n=$cnt pet=$pet card=$shown: $m" }
                    if (!r.away) bad("not away")
                    val pr = HomeLayoutRules.problems(l, s, r)
                    if (pr.isNotEmpty()) bad("$pr")
                    val part = if (dock.column == HomeModule.CHAT) HomePart.CHAT else HomePart.MAP
                    val other = if (part == HomePart.CHAT) HomePart.MAP else HomePart.CHAT
                    val p = r.boxes.getValue(HomePart.PARTY); val rp = ref.boxes.getValue(HomePart.PARTY)
                    val c = r.boxes.getValue(part); val rc = ref.boxes.getValue(part)
                    val rows = PartyLayout.rowsFit(rowCount(s.party!!), p.h - PartyModuleView.RISE)
                    val capped = rows > PartyLayout.FILL_MAX_H - 0.01f
                    if (rows < PartyLayout.FIGHT_H - 0.01f || rows > PartyLayout.FILL_MAX_H + 0.01f) bad("rows $rows")
                    if (p.t > rp.t - 100f) bad("the well did not grow up into the strip: ${p.t} vs ${rp.t}")
                    if (c.h < rc.h - 0.01f) bad("the $part shrank: ${c.h} vs ${rc.h}")
                    if (!capped && kotlin.math.abs(c.h - rc.h) > 0.01f) bad("the $part took the strip (not capped): ${c.h} vs ${rc.h}")
                    if (dock.top && p.b > rp.b + 0.01f) bad("the well's foot ${p.b} below the reference's ${rp.b}")
                    if (dock.top && !capped && kotlin.math.abs(p.b - rp.b) > 0.01f) bad("foot ${p.b} vs ${rp.b} (not capped)")
                    if (!dock.top && kotlin.math.abs(p.b - rp.b) > 0.01f) bad("a foot dock's foot ${p.b} vs ${rp.b}")
                    if (r.boxes.getValue(other).t > ref.boxes.getValue(other).t - 100f) bad("the $other did not grow up")
                    if (r.f1 != ref.f1 || r.f2 != ref.f2 || r.f3 != ref.f3) bad("fallbacks ${r.f1}/${r.f2}/${r.f3} vs ${ref.f1}/${ref.f2}/${ref.f3}")
                    // the 1519 audit (finding 3): the tap-to-cast bar kept in a Top fight (castHidden false) is never slid away by an F2 /
                    // F3 the away page inherits - the band-on reference needs neither in any record x party shape (at most three rows)
                    if (r.castHidden || ref.f2 || ref.f3) bad("the bar slid away on the away page (F2 ${ref.f2} / F3 ${ref.f3})")
                    // the well inside its column, its tab on the screen's outer edge
                    if (S.partyRight(l) && p.l < c.l - 0.01f || !S.partyRight(l) && p.r > c.r + 0.01f) bad("the well out of its column: $p / $c")
                    if (r.partyTabRight != S.partyRight(l)) bad("the tab's edge")
                }
            }
        assertTrue("checked $n: ${bad.joinToString("\n")}", bad.isEmpty())
        assertTrue("$n", n > 15000)
    }

    /** the thin-row rest: the strip freed is the thin band's (138), the rows 134 / 157 / 192 */
    @Test fun theThinRowRest() {
        val l = top.copy(rest = HomeRest.ROW)
        for ((cnt, want) in listOf(3 to 134f, 2 to 157f, 1 to 192f)) {
            val r = S.places(l, fightState(cnt))
            val p = r.boxes.getValue(HomePart.PARTY)
            assertEquals("n=$cnt", want, PartyLayout.rowsFit(PartyModel.rows(cnt).size, p.h), 1f)
        }
    }
}
