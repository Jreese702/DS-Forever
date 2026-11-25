package com.jrees.wowcompanion

import com.jrees.wowcompanion.SinglePlayLayout.Arr
import com.jrees.wowcompanion.SinglePlayLayout.Box
import com.jrees.wowcompanion.SinglePlayLayout.Hud
import com.jrees.wowcompanion.SinglePlayLayout.Piece
import com.jrees.wowcompanion.SinglePlayLayout.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SINGLE SCREEN MODE WHILE PLAYING: THE PLAY DOCKS' PLACES (SinglePlayLayout; the user's pick "1, 2, and also the chat on the left thr ds
 * toolbar should have the same functionality in single screen mode. We can shift those panels ine and out of combat to all fit better"):
 * the out-of-combat and in-combat arrangements at the default HUD, every piece clear of the keep-outs (the compass and target bar, the
 * HUD block, the Nintendo bar out of fights, the Wings' worst envelope in fights, our party frames with Party mode off) for every HUD the
 * Settings steps allow, Party mode on / off, a 1280 x 720 screen and a wide phone, and the shift's steps (two window updates a piece).
 * Pure.
 */
class SinglePlayLayoutTest {
    private fun keeps(a: Arr, party: Boolean, hud: Hud = Hud(), w: Int = 1920, h: Int = 1080) =
        SinglePlayLayout.keepOuts(a, party, hud, w, h)

    private fun clear(p: SinglePlayLayout.Plan, party: Boolean, hud: Hud, w: Int = 1920, h: Int = 1080): List<String> {
        val bad = ArrayList<String>()
        for ((piece, b) in p.boxes()) {
            b ?: continue
            for (k in keeps(p.arr, party, hud, w, h)) if (b.hits(k.box)) bad += "${p.arr} $piece $b meets ${k.name} ${k.box} (hud $hud, party $party, $w x $h)"
            if (!b.inside(w, h)) bad += "${p.arr} $piece $b off the $w x $h screen"
        }
        return bad
    }

    @Test fun theDefaultArrangements() {
        val out = SinglePlayLayout.plan(Arr.OUT, partyMode = true)
        assertEquals(Box(1584, 16, 1904, 336), out.map)
        assertEquals(Box(16, 220, 456, 540), out.party)
        assertEquals("under the party list, 540 wide", Box(16, 560, 556, 840), out.chat)
        assertTrue("about eight chat lines out of combat: ${out.chatLines}", out.chatLines in 7..9)
        val inn = SinglePlayLayout.plan(Arr.IN, partyMode = true)
        assertEquals(Box(1664, 16, 1904, 256), inn.map)
        assertEquals(Box(16, 88, 456, 408), inn.party)
        assertEquals("as wide as the list over it", Box(16, 416, 456, 548), inn.chat)
        assertEquals("the 3 newest lines in a fight", 3, inn.chatLines)
        assertEquals("the map 320 out of combat, 240 in", 320 to 240, out.map.w to inn.map.w)
    }

    @Test fun partyModeOffNoPartyPieceAndTheChatUnderOurFrames() {
        val out = SinglePlayLayout.plan(Arr.OUT, partyMode = false)
        assertNull(out.party)
        assertEquals("the chat starts under our party frames (K5 ends at 506)", 520, out.chat.t)
        assertFalse(out.chat.hits(SinglePlayLayout.K5))
        val inn = SinglePlayLayout.plan(Arr.IN, partyMode = false)
        assertNull(inn.party)
        assertTrue("in a fight above K5 and above the Wings' envelope", inn.chat.b < SinglePlayLayout.K5.t && inn.chat.b < 550)
        assertEquals(3, inn.chatLines)
    }

    @Test fun theDefaultHudKeepOutsAreTheAddOnsNumbers() {
        assertEquals(Box(640, 780, 1280, 1080), SinglePlayLayout.k2(Hud()))
        assertEquals("Party mode's rows under the HUD reach further right", Box(640, 780, 1380, 1080), SinglePlayLayout.k2(Hud(), partyMode = true))
        val out = keeps(Arr.OUT, false).map { it.name.substringBefore(' ') }
        assertEquals(listOf("K1", "K2", "K3", "K5"), out)
        val inn = keeps(Arr.IN, true).map { it.name.substringBefore(' ') }
        assertEquals("in a fight the Wings, no Nintendo bar; Party mode on: no party frames", listOf("K1", "K2", "K4"), inn)
    }

    @Test fun nothingMeetsAKeepOutForEveryHudTheSettingsAllow() {
        val bad = ArrayList<String>()
        var n = 0
        for (a in Arr.values()) for (party in listOf(true, false)) for (y in -470..-170 step 10) for (s in 70..160 step 10) for (w in 240..400 step 20) {
            val hud = Hud(y, w, s)
            val p = SinglePlayLayout.plan(a, party, hud)
            bad += clear(p, party, hud)
            assertTrue("the chat keeps at least 3 lines and 300 px ($hud ${p.chat})", p.chat.h >= SinglePlayLayout.CHAT_MIN_H && p.chat.w >= SinglePlayLayout.CHAT_MIN_W)
            n++
        }
        assertTrue(bad.take(5).joinToString("\n"), bad.isEmpty())
        println("SinglePlayLayout: $n arrangements, none over a keep-out")
    }

    @Test fun aHudMovedUpShrinksTheChatRatherThanOverlap() {
        val high = Hud(-170, 400, 160)
        val p = SinglePlayLayout.plan(Arr.OUT, true, high)
        val k2 = SinglePlayLayout.k2(high)
        assertTrue("the chat narrowed to the HUD block's left ($k2 ${p.chat})", p.chat.r <= k2.l - SinglePlayLayout.GAP && p.chat.r < 556)
        val low = Hud(-450, 400, 160)
        val q = SinglePlayLayout.plan(Arr.OUT, true, low)
        val k2l = SinglePlayLayout.k2(low)
        assertTrue("a low, big HUD: the chat's lines first (its bottom up: ${q.chat}, $k2l)", q.chat.b <= k2l.t - SinglePlayLayout.GAP && q.chat.b < 824 && q.chat.w == 540)
    }

    @Test fun inCombatStaysAboveTheWingsEnvelope() {
        for (party in listOf(true, false)) {
            val p = SinglePlayLayout.plan(Arr.IN, party)
            for ((piece, b) in p.boxes()) {
                b ?: continue
                if (piece == Piece.MAP) assertTrue("the map right of the Wings: $b", b.l > 1540 || b.b < 550)
                else assertTrue("$piece above y 550 or left of x 152: $b", b.b <= 550 || b.r <= 152)
            }
        }
    }

    @Test fun theTalkPopUpStandsOverTheParty() {
        val out = SinglePlayLayout.plan(Arr.OUT, true)
        assertEquals("over the party list's top-left", out.party!!.l to out.party!!.t, out.talk.l to out.talk.t)
        assertTrue(out.talk.h >= 360)
        val solo = SinglePlayLayout.plan(Arr.OUT, false)
        assertEquals("without Party mode: over the chat's top", solo.chat.t, solo.talk.t)
        for (a in Arr.values()) for (party in listOf(true, false)) {
            val p = SinglePlayLayout.plan(a, party)
            for (k in keeps(a, party)) if (!k.name.startsWith("K5")) assertFalse("$a talk ${p.talk} meets ${k.name}", p.talk.hits(k.box))
            assertTrue(p.talk.inside(1920, 1080))
        }
    }

    @Test fun smallerAndWiderScreens() {
        val small = SinglePlayLayout.plan(Arr.OUT, true, Hud(), 1280, 720)
        assertEquals("1280 x 720: every box x 2/3", Box(1056, 11, 1269, 224), small.map)
        assertEquals(Box(11, 147, 304, 360), small.party)
        for (a in Arr.values()) for (party in listOf(true, false)) {
            assertTrue(clear(SinglePlayLayout.plan(a, party, Hud(), 1280, 720), party, Hud(), 1280, 720).isEmpty())
            assertTrue(clear(SinglePlayLayout.plan(a, party, Hud(), 2400, 1080), party, Hud(), 2400, 1080).isEmpty())
        }
        val wide = SinglePlayLayout.plan(Arr.OUT, true, Hud(), 2400, 1080)
        assertEquals("a wide phone: the map from the right edge", 2400 - 16, wide.map.r)
        assertEquals("the left pieces from the left edge", 16, wide.chat.l)
        assertEquals("the keep-outs from the centre", 1200, keeps(Arr.OUT, false, Hud(), 2400, 1080)[1].box.let { (it.l + it.r) / 2 })
    }

    @Test fun theShiftGrowsGlidesShrinks() {
        val out = SinglePlayLayout.plan(Arr.OUT, true)
        val inn = SinglePlayLayout.plan(Arr.IN, true)
        val st = SinglePlayLayout.steps(out.boxes(), inn.boxes(), Arr.IN)
        for (p in listOf(Piece.MAP, Piece.PARTY, Piece.CHAT)) {
            val mine = st.filter { it.piece == p }
            assertEquals("$p: place, glide, place", listOf(Step.Place::class, Step.Glide::class, Step.Place::class), mine.map { it::class })
            val grow = mine[0] as Step.Place; val glide = mine[1] as Step.Glide; val shrink = mine[2] as Step.Place
            assertEquals("$p's window grows to the union first", out.box(p)!!.union(inn.box(p)!!), grow.box)
            assertEquals(inn.box(p), shrink.box)
            assertEquals("into the fight: COMBAT_GROW", Motion.COMBAT_GROW, glide.ms)
            assertEquals(out.box(p), glide.from); assertEquals(inn.box(p), glide.to)
        }
        assertEquals("two window updates a piece a fight edge", 6, st.count { it is Step.Place })
        val back = SinglePlayLayout.steps(inn.boxes(), out.boxes(), Arr.OUT)
        assertEquals("out of it: COMBAT_SHRINK", Motion.COMBAT_SHRINK, (back.first { it is Step.Glide } as Step.Glide).ms)
        assertTrue("nothing moves: no step", SinglePlayLayout.steps(out.boxes(), out.boxes(), Arr.OUT).isEmpty())
        // Party mode turned on while playing: the party piece fades in at its box (one window, no move)
        val solo = SinglePlayLayout.plan(Arr.OUT, false)
        val on = SinglePlayLayout.steps(solo.boxes(), out.boxes(), Arr.OUT).filter { it.piece == Piece.PARTY }
        assertEquals(listOf(Step.Place(Piece.PARTY, out.party), Step.Glide(Piece.PARTY, null, out.party, SinglePlayLayout.SHRINK_MS), Step.Place(Piece.PARTY, out.party)), on)
    }

    @Test fun theGlideBetween() {
        val a = Box(0, 0, 100, 100); val b = Box(100, 100, 300, 200)
        assertEquals(a, SinglePlayLayout.at(a, b, 0f))
        assertEquals(b, SinglePlayLayout.at(a, b, 1f))
        assertEquals(Box(50, 50, 200, 150), SinglePlayLayout.at(a, b, 0.5f))
        assertEquals(b, SinglePlayLayout.at(null, b, 0.3f))
        assertNotNull(SinglePlayLayout.at(a, null, 0.3f))
    }

    @Test fun theArrangementFollowsHomesFightWord() {
        assertEquals(Arr.IN, SinglePlayLayout.arr(true))
        assertEquals(Arr.OUT, SinglePlayLayout.arr(false))
    }

    @Test fun theDockWindowsStayCheap() {
        for (a in Arr.values()) for (party in listOf(true, false)) {
            val p = SinglePlayLayout.plan(a, party)
            val area = p.boxes().values.filterNotNull().sumOf { it.area }
            assertTrue("$a party $party: ${area / 1e6} MP of docks", area <= SinglePlayLayout.AREA_MAX)
        }
        assertTrue("about a fifth of the top screen at most", SinglePlayLayout.AREA_MAX <= 1920L * 1080 / 5)
    }

    @Test fun thePartyWindowHugsTheList() {
        val p = SinglePlayLayout.plan(Arr.OUT, true, partyH = 232)
        assertEquals("the list's own height", Box(16, 220, 456, 452), p.party)
        assertEquals("never taller than its box", 320, SinglePlayLayout.plan(Arr.OUT, true, partyH = 900).party!!.h)
        assertEquals("never shorter than two rows", SinglePlayLayout.PARTY_MIN_H, SinglePlayLayout.plan(Arr.IN, true, partyH = 10).party!!.h)
        assertEquals("the chat stays where it was (fewer moves)", SinglePlayLayout.plan(Arr.OUT, true).chat, p.chat)
    }
}
