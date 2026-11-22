package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MOVABLE HOME, EVERY LAYOUT IN EVERY STATE (SPEC.txt section 5 / 15 HomeLayoutRulesTest; phase 1): all 2,268 records the editor can
 * make x every page state (rest / the takeover, Grow / Same / Top, no party / Party mode's rows in each row mode at 1-6 entries, a card or
 * none, the buff list open, the bar hidden in a fight): no overlap, inside the frame, every part placed, the columns tiling the inner width
 * exactly, the map at least 240 tall, the columns at least 400 wide, and the chat never shorter than Classic's in the same state (today's
 * Classic is itself under the spec's 180 in some states: the takeover's party rows under a card above the chat). R0 / F1 / F2 fire only
 * where expected: never on the Thor's 935 px page (P8: Classic = today), and F1 then F2 on a short page with Party mode's big rows.
 */
class HomeLayoutRulesTest {
    private val S = HomeLayoutSolver
    private val CARD_H = 118.75f          // the focused card at Home's scale (the golden's)
    private val FOLDED_H = 66f

    private fun states(h: Float = 935f): List<HomeLayoutSolver.State> {
        val out = ArrayList<HomeLayoutSolver.State>()
        for (takeover in listOf(false, true)) for (card in listOf(0f, FOLDED_H, CARD_H)) for (aura in listOf(false, true))
            for (hidden in listOf(false, true)) {
                if (hidden && !takeover) continue
                val parties = ArrayList<Pair<HomeLayoutSolver.Party?, Boolean>>()
                parties += null to false
                for (n in listOf(1, 3, 5, 6)) for (pet in listOf(false, true)) {
                    if (takeover) parties += HomeLayoutSolver.Party(PartyLayout.Mode.FIGHT_OFF, n, pet) to false   // the takeover's rows, Party mode off
                    else {
                        parties += HomeLayoutSolver.Party(PartyLayout.Mode.REST, n, pet) to false
                        parties += HomeLayoutSolver.Party(PartyLayout.Mode.REST, n, pet) to true
                        parties += HomeLayoutSolver.Party(PartyLayout.Mode.FIGHT_ON, n, pet) to true              // a fight with Party mode on
                    }
                }
                for ((p, on) in parties) out += HomeLayoutSolver.State(h = h, takeover = takeover, card = card > 0f, cardH = card,
                    auraOpen = aura, party = p, partyOn = on, castHidden = hidden)
            }
        return out
    }

    /** the chat's height Classic gives in [s] for [l]'s rest / fight / card / bar dock (side and width move nothing vertical) */
    private fun classicChat(l: HomeLayout, s: HomeLayoutSolver.State): Float {
        val c = HomeLayout(rest = l.rest, fight = l.fight, card = l.card, castSize = HomeLayout.SlotSize.L,
            cast = if (l.cast == HomeLayout.CastDock.SPLIT) HomeLayout.CastDock.UNDER_CHAT else l.cast)
        return S.places(c, s).boxes.getValue(HomePart.CHAT).h
    }

    @Test fun everyLayoutIsValidInEveryState() {
        val all = HomeLayoutRules.all()
        assertEquals("2 x 3 x 3 x 7 x 3 x 2 x 3", 2268, all.size)
        val st = states()
        var n = 0
        val bad = ArrayList<String>()
        for (l in all) for (s in st) {
            val r = S.places(l, s)
            n++
            val p = HomeLayoutRules.problems(l, s, r)
            if (p.isNotEmpty() && bad.size < 10) bad += "$l / $s: $p"
            val chat = r.boxes.getValue(HomePart.CHAT).h
            val floor = minOf(HomeLayoutRules.CHAT_MIN, classicChat(l, s))
            if (chat + 0.01f < floor && bad.size < 10) bad += "$l / $s: chat $chat under $floor"
            if (r.f1 || r.f2) bad += "a fallback fired on the Thor's page: $l / $s"
        }
        assertTrue("checked $n: ${bad.joinToString("\n")}", bad.isEmpty())
        assertTrue(n > 2268 * 100)
    }

    @Test fun columnsTileTheInnerWidthForEveryShareAndSide() {
        for (side in HomeLayout.Side.values()) for (share in HomeLayout.Share.values()) {
            val l = HomeLayout(chatSide = side, chatShare = share)
            val b = S.places(l, HomeLayoutSolver.State()).boxes
            val chat = b.getValue(HomePart.CHAT); val map = b.getValue(HomePart.MAP)
            val want = (1240f - 16f - 8f) * share.num / share.den
            assertEquals("$side $share chat width", want, chat.w, 0.01f)
            assertEquals("$side $share map width", 1216f - want, map.w, 0.01f)
            assertEquals(side == HomeLayout.Side.LEFT, chat.l < map.l)
            assertEquals(map, b.getValue(HomePart.AURAS))
        }
        // Classic = today's c - 4 / c + 4, to the float
        val c = S.places(HomeLayout(), HomeLayoutSolver.State()).boxes
        assertEquals(HomeLayoutSolver.Box(8f, 234f, 616f, 927f), c[HomePart.CHAT])
        assertEquals(HomeLayoutSolver.Box(624f, 234f, 1232f, 927f), c[HomePart.MAP])
    }

    @Test fun theCardFollowsItsHost() {
        val s = HomeLayoutSolver.State(card = true, cardH = CARD_H)
        for (side in HomeLayout.Side.values()) {
            val on = S.places(HomeLayout(chatSide = side), s).boxes
            val map = on.getValue(HomePart.MAP); val q = on.getValue(HomePart.QUESTS)
            assertEquals("on the map, inset as the zone band", map.l + 8f, q.l, 0.01f); assertEquals(map.r - 8f, q.r, 0.01f)
            assertEquals(map.t + HomeView.ZONE_BAND_BOTTOM + 4f, q.t, 0.01f)
            val above = S.places(HomeLayout(chatSide = side, card = HomeLayout.CardDock.ABOVE_CHAT), s).boxes
            val chat = above.getValue(HomePart.CHAT); val q2 = above.getValue(HomePart.QUESTS)
            assertEquals(chat.l, q2.l, 0.01f); assertEquals(chat.r, q2.r, 0.01f); assertEquals(q2.b + 8f, chat.t, 0.01f)
            assertFalse("Off: no card", HomePart.QUESTS in S.places(HomeLayout(chatSide = side, card = HomeLayout.CardDock.OFF), s).boxes)
        }
        // b128: the buff list open hides the card on the map, never above the chat
        val aura = HomeLayoutSolver.State(card = true, cardH = CARD_H, auraOpen = true)
        assertFalse(HomePart.QUESTS in S.places(HomeLayout(), aura).boxes)
        assertTrue(HomePart.QUESTS in S.places(HomeLayout(card = HomeLayout.CardDock.ABOVE_CHAT), aura).boxes)
    }

    @Test fun theBarDockFollowsTheWidgetsAndItsSize() {
        for (side in HomeLayout.Side.values()) for (dock in HomeLayout.CastDock.values()) for (size in HomeLayout.SlotSize.values()) {
            val l = HomeLayout(chatSide = side, cast = dock, castSize = size)
            val r = S.places(l, HomeLayoutSolver.State())
            val b = r.boxes
            if (dock == HomeLayout.CastDock.OFF) { assertFalse(HomePart.STRIP in b); continue }
            val strip = b.getValue(HomePart.STRIP)
            val chat = b.getValue(HomePart.CHAT); val map = b.getValue(HomePart.MAP)
            when (dock) {
                HomeLayout.CastDock.UNDER_CHAT -> { assertEquals(chat.l, strip.l, 0.01f); assertEquals(chat.r, strip.r, 0.01f); assertEquals(strip.t - 8f, chat.b, 0.01f); assertEquals(927f, map.b, 0.01f) }
                HomeLayout.CastDock.UNDER_MAP -> { assertEquals(map.l, strip.l, 0.01f); assertEquals(strip.t - 8f, map.b, 0.01f); assertEquals(927f, chat.b, 0.01f) }
                HomeLayout.CastDock.ABOVE_CHAT -> { assertEquals(chat.l, strip.l, 0.01f); assertEquals(chat.r, strip.r, 0.01f); assertEquals(234f, strip.t, 0.01f)
                    assertEquals(strip.b + 8f, chat.t, 0.01f); assertEquals(927f, chat.b, 0.01f); assertEquals(234f, map.t, 0.01f); assertEquals(927f, map.b, 0.01f) }
                HomeLayout.CastDock.ABOVE_MAP -> { assertEquals(map.l, strip.l, 0.01f); assertEquals(map.r, strip.r, 0.01f); assertEquals(234f, strip.t, 0.01f)
                    assertEquals(strip.b + 8f, map.t, 0.01f); assertEquals(927f, map.b, 0.01f); assertEquals(234f, chat.t, 0.01f); assertEquals(927f, chat.b, 0.01f) }
                HomeLayout.CastDock.ACROSS -> { assertEquals(8f, strip.l, 0.01f); assertEquals(1232f, strip.r, 0.01f); assertEquals(chat.b, map.b, 0.01f) }
                HomeLayout.CastDock.SPLIT -> {
                    val two = b.getValue(HomePart.CAST2)
                    assertEquals("two halves on one row", strip.t, two.t, 0.01f)
                    assertTrue(strip.r <= two.l)
                }
                else -> {}
            }
            assertTrue("$dock $size: the slot no bigger than its size", strip.h <= size.px + 2 * CastStripModel.PAD + 0.01f)
            // the geometric side the view scrolls by: Right (under the right column) rests at its end
            assertEquals(dock.top, r.castTop)
            val want = when (dock) {
                HomeLayout.CastDock.UNDER_CHAT, HomeLayout.CastDock.ABOVE_CHAT -> if (side == HomeLayout.Side.LEFT) CastStripModel.Side.LEFT else CastStripModel.Side.RIGHT
                HomeLayout.CastDock.UNDER_MAP, HomeLayout.CastDock.ABOVE_MAP -> if (side == HomeLayout.Side.LEFT) CastStripModel.Side.RIGHT else CastStripModel.Side.LEFT
                else -> CastStripModel.Side.BOTH
            }
            assertEquals(want, r.castSide)
        }
        // L = today's: CastStripModel.place's box exactly
        for (side in listOf(CastStripModel.Side.LEFT, CastStripModel.Side.RIGHT, CastStripModel.Side.BOTH)) {
            val l = HomeLayout(cast = HomeLayout.dockOf(side, HomeLayout.Side.LEFT))
            val strip = S.places(l, HomeLayoutSolver.State()).boxes.getValue(HomePart.STRIP)
            val old = CastStripModel.place(side, 1240f, 935f, 8f)!!
            assertEquals(HomeLayoutSolver.Box(old.l, old.t, old.r, old.b), strip)
        }
    }

    @Test fun thePartyModuleFollowsTheChatsColumn() {
        val p = HomeLayoutSolver.Party(PartyLayout.Mode.REST, 3, false)
        val s = HomeLayoutSolver.State(party = p, partyOn = true)
        val left = S.places(HomeLayout(), s)
        val right = S.places(HomeLayout(chatSide = HomeLayout.Side.RIGHT), s)
        assertFalse(left.partyTabRight); assertTrue(right.partyTabRight)
        val a = left.boxes.getValue(HomePart.PARTY); val b = right.boxes.getValue(HomePart.PARTY)
        assertEquals("flush on the left edge", 0f, a.l, 0.01f)
        assertEquals("flush on the right edge", 1240f, b.r, 0.01f)
        assertEquals("the mirror image", 1240f - a.r, b.l, 0.01f)
        assertEquals(a.t, b.t, 0.01f); assertEquals(a.b, b.b, 0.01f)
        assertEquals(left.boxes.getValue(HomePart.CHAT).t, right.boxes.getValue(HomePart.CHAT).t, 0.01f)
        // PartyLayout mirrored: the tab at the right edge, the well left of it, the cells inside the well
        val m = PartyLayout.module(PartyLayout.Mode.REST, 3, top = 234f, left = b.l, right = 1240f, tabRight = true)
        assertEquals(1240f, m.tag.r, 0.01f); assertEquals(1240f - PartyLayout.TAB_W, m.tag.l, 0.01f)
        assertEquals(m.tag.l - PartyLayout.TAB_GAP, m.well.r, 0.01f)
        for (cell in m.cells) assertTrue(cell.l >= m.well.l && cell.r <= m.well.r)
    }

    @Test fun r0NoGrowWithPartyModeOn() {
        val l = HomeLayout(fight = HomeLayout.FightBand.GROW)
        assertTrue(S.big(l, HomeLayoutSolver.State(takeover = true)))
        assertFalse(S.big(l, HomeLayoutSolver.State(takeover = true, partyOn = true)))
        assertFalse("Same size", S.big(l.copy(fight = HomeLayout.FightBand.SAME), HomeLayoutSolver.State(takeover = true)))
        assertFalse("Top screen keeps the rest size on the bottom", S.big(l.copy(fight = HomeLayout.FightBand.TOP), HomeLayoutSolver.State(takeover = true)))
        assertEquals(416f, S.barsBottom(l, HomeLayoutSolver.State(takeover = true)), 0f)
        assertEquals(226f, S.barsBottom(l, HomeLayoutSolver.State()), 0f)
        assertEquals(138f, S.barsBottom(l.copy(rest = HomeRest.ROW), HomeLayoutSolver.State()), 0f)
    }

    @Test fun f1ThenF2FireOnlyWhenTheChatIsTooShortInPartyMode() {
        // a short page (a smaller screen): Party mode's 6 big rows under a card above the chat and a bar under it
        val p = HomeLayoutSolver.Party(PartyLayout.Mode.FIGHT_ON, 6, true)
        val l = HomeLayout(card = HomeLayout.CardDock.ABOVE_CHAT, cast = HomeLayout.CastDock.UNDER_CHAT)
        val short = HomeLayoutSolver.State(h = 760f, card = true, cardH = CARD_H, party = p, partyOn = true)
        val r = S.places(l, short)
        assertTrue("F1: the card went onto the map", r.f1)
        assertEquals(HomeLayout.CardDock.ON_MAP, r.card)
        assertTrue("F2: the bar under the chat slid behind the room bar", r.f2 && r.castHidden)
        assertTrue(r.boxes.getValue(HomePart.STRIP).t >= 760f)
        assertTrue(r.boxes.getValue(HomePart.CHAT).h >= HomeLayoutRules.CHAT_HARD_MIN)
        // the bar under the MAP: only F1 (the chat's column has no bar to take away)
        val r2 = S.places(l.copy(cast = HomeLayout.CastDock.UNDER_MAP), short)
        assertFalse(r2.f2)
        // Party mode off: never (the takeover's rows are today's)
        val off = S.places(l, short.copy(partyOn = false, party = p.copy(mode = PartyLayout.Mode.REST)))
        assertFalse(off.f1 || off.f2)
        // on the Thor's page: never (P8)
        val thor = S.places(l, short.copy(h = 935f))
        assertFalse(thor.f1 || thor.f2)
    }

    @Test fun fullModulesTakeTheFrame() {
        for (m in HomeModule.values()) {
            val b = S.places(HomeLayout(chatSide = HomeLayout.Side.RIGHT, cast = HomeLayout.CastDock.ACROSS), HomeLayoutSolver.State(full = m)).boxes
            assertTrue(b.values.all { it == HomeLayoutSolver.Box(0f, 0f, 1240f, 935f) })
            assertFalse(HomePart.STRIP in b)
        }
    }

    @Test fun mirrorBitIsTheMapOnTheLeft() {
        assertEquals(0, S.mirrorBit(HomeLayout()))
        assertEquals(1, S.mirrorBit(HomeLayout(chatSide = HomeLayout.Side.RIGHT)))
        assertEquals(1, S.mirrorBit(HomeLayout().swapped()))
        assertEquals(HomeLayout(), HomeLayout().swapped().swapped())
    }

    // ---------------------------------------------------------------- the bar at the top of a column (Above chat / Above map)

    @Test fun aboveChatStacksBarCardPartyChat() {
        val p = HomeLayoutSolver.Party(PartyLayout.Mode.REST, 3, false)
        for (side in HomeLayout.Side.values()) {
            val l = HomeLayout(chatSide = side, card = HomeLayout.CardDock.ABOVE_CHAT, cast = HomeLayout.CastDock.ABOVE_CHAT)
            val b = S.places(l, HomeLayoutSolver.State(card = true, cardH = CARD_H, party = p, partyOn = true)).boxes
            val strip = b.getValue(HomePart.STRIP); val q = b.getValue(HomePart.QUESTS)
            val party = b.getValue(HomePart.PARTY); val chat = b.getValue(HomePart.CHAT)
            assertEquals("the bar first, under the band", 234f, strip.t, 0.01f)
            assertEquals("then the card", strip.b + 8f, q.t, 0.01f)
            assertEquals("then the party module", q.b + 8f, party.t, 0.01f)
            assertTrue("then the chat", chat.t >= party.t && chat.b == 927f)
            // the bar stands still while the card and the party module come and go (under the thumb)
            val bare = S.places(l, HomeLayoutSolver.State()).boxes.getValue(HomePart.STRIP)
            assertEquals(strip, bare)
        }
    }

    @Test fun aboveMapCarriesTheCardDown() {
        for (side in HomeLayout.Side.values()) {
            val b = S.places(HomeLayout(chatSide = side, cast = HomeLayout.CastDock.ABOVE_MAP), HomeLayoutSolver.State(card = true, cardH = CARD_H)).boxes
            val map = b.getValue(HomePart.MAP); val q = b.getValue(HomePart.QUESTS); val strip = b.getValue(HomePart.STRIP)
            assertEquals(strip.b + 8f, map.t, 0.01f)
            assertEquals("the card under the map's zone band", map.t + HomeView.ZONE_BAND_BOTTOM + 4f, q.t, 0.01f)
            assertTrue(q.b <= map.b)
        }
    }

    @Test fun aHiddenTopBarLiesBehindTheBand() {
        for (dock in listOf(HomeLayout.CastDock.ABOVE_CHAT, HomeLayout.CastDock.ABOVE_MAP)) for (fight in HomeLayout.FightBand.values()) {
            val l = HomeLayout(cast = dock, fight = fight)
            val r = S.places(l, HomeLayoutSolver.State(takeover = true, castHidden = true))
            val bars = r.boxes.getValue(HomePart.BARS); val strip = r.boxes.getValue(HomePart.STRIP)
            assertTrue(r.castHidden && r.castTop)
            assertEquals("its foot on the band's foot", bars.b, strip.b, 0.01f)
            val laid = S.places(l, HomeLayoutSolver.State(takeover = true)).boxes.getValue(HomePart.STRIP)
            val up = CastStripModel.hiddenAbove(CastStripModel.Box(laid.l, laid.t, laid.r, laid.b), 8f)
            assertEquals(HomeLayoutSolver.Box(up.l, up.t, up.r, up.b), strip)
            // its column takes the room back, up to the band
            val col = r.boxes.getValue(if (dock == HomeLayout.CastDock.ABOVE_CHAT) HomePart.CHAT else HomePart.MAP)
            assertEquals(bars.b + 8f, col.t, 0.01f)
        }
    }

    @Test fun showInAGrowFightRidesDownWithTheBand() {
        val l = HomeLayout(cast = HomeLayout.CastDock.ABOVE_MAP, fight = HomeLayout.FightBand.GROW)
        val b = S.places(l, HomeLayoutSolver.State(takeover = true)).boxes
        val strip = b.getValue(HomePart.STRIP); val map = b.getValue(HomePart.MAP)
        assertEquals(424f, strip.t, 0.01f)
        assertEquals(strip.b + 8f, map.t, 0.01f)
        assertTrue("the map over its 240 minimum: ${map.h}", map.h >= HomeLayoutRules.MAP_MIN)
    }

    @Test fun f2SlidesAnAboveChatBarUpBehindTheBand() {
        val p = HomeLayoutSolver.Party(PartyLayout.Mode.FIGHT_ON, 6, true)
        val l = HomeLayout(card = HomeLayout.CardDock.ABOVE_CHAT, cast = HomeLayout.CastDock.ABOVE_CHAT)
        val short = HomeLayoutSolver.State(h = 760f, card = true, cardH = CARD_H, party = p, partyOn = true)
        val r = S.places(l, short)
        assertTrue(HomeLayoutRules.cutsChat(l))
        assertTrue(r.f1 && r.f2 && r.castHidden && r.castTop)
        assertTrue(r.boxes.getValue(HomePart.STRIP).b <= r.boxes.getValue(HomePart.BARS).b + 0.01f)
        assertTrue(r.boxes.getValue(HomePart.CHAT).h >= HomeLayoutRules.CHAT_HARD_MIN)
        assertFalse("above the map: only F1", S.places(l.copy(cast = HomeLayout.CastDock.ABOVE_MAP), short).f2)
    }

    @Test fun anAboveRecordGivesItsColumnsTheHeightsOfItsUnderTwin() {
        val st = states()
        var n = 0
        for (l in HomeLayoutRules.all()) {
            if (!l.cast.top) continue
            val twin = l.copy(cast = l.cast.withTop(false))
            for (s in st) {
                val a = S.places(l, s).boxes; val b = S.places(twin, s).boxes
                for (p in listOf(HomePart.CHAT, HomePart.MAP)) assertEquals("$l $p / $s", b.getValue(p).h, a.getValue(p).h, 0.01f)
                n++
            }
        }
        assertTrue(n > 600 * 100)
    }

    @Test fun splitShipsWithEditMode() {
        // P2: Split's second half is laid (HomeView's castStrip2): shown as itself, one 4-slot half under each column
        val l = HomeLayout(cast = HomeLayout.CastDock.SPLIT)
        assertTrue(HomeLayoutRules.SPLIT_SHIPS)
        assertEquals(HomeLayout.CastDock.SPLIT, HomeLayoutRules.shown(l).cast)
        val b = HomeLayoutSolver.places(l, HomeLayoutSolver.State()).boxes
        assertTrue(b.getValue(HomePart.STRIP).r < b.getValue(HomePart.CAST2).l)
        assertEquals(l.copy(cast = HomeLayout.CastDock.UNDER_MAP), HomeLayoutRules.shown(l.copy(cast = HomeLayout.CastDock.UNDER_MAP)))
    }

    /** the user's ruling 2026-11-22 after 1518 (the D-pad order and the clock's spot are gone): a record 1518 saved with "pad" / "clock"
     *  reads as the same record without them (the clock back at the centre, the D-pad the mirror bit's), and every write drops them */
    @Test fun a1518RecordsPadAndClockAreReadAndIgnored() {
        val plain = """{"v":1,"name":"Classic","chat":"RIGHT","share":"HALF","cast":"UNDER_CHAT"}"""
        val with = plain.dropLast(1) + ""","pad":["RIGHT_COL","RADIAL","LEFT_COL","HOME"],"clock":"LEFT"}"""
        val a = HomeLayoutJson.parse(plain)
        val b = HomeLayoutJson.parse(with)
        assertEquals(a, b)
        assertTrue(b.mirrored)
        for (t in listOf(HomeLayoutJson.json(b), HomeLayoutJson.json(HomeLayoutStore.layOn(b, b, b.swapped())),
                HomeLayoutJson.json(HomeLayoutStore.layOn(b.copy(castSize = HomeLayout.SlotSize.M), b, b.copy(xpBar = false))))) {
            assertFalse(t, t.contains("\"pad\"")); assertFalse(t, t.contains("\"clock\""))
        }
        // junk values never throw (and never cost the record its other fields)
        for (junk in listOf(""","pad":"x","clock":5}""", ""","pad":[1,2],"clock":null}""", ""","pad":{},"clock":"MIDDLE"}"""))
            assertEquals(a, HomeLayoutJson.parse(plain.dropLast(1) + junk))
    }
}
