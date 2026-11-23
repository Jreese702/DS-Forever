package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE CLOCK BAND (feat/clock-extras-v2; the user, 2026-11-13: "lets center them where the ZR and LR appear (fade them out when holding ZL
 * and ZR) and make them space out evenly based on what else is turned on. the gold should be the total gold (including silver and copper)
 * when the hearthstone is ready it should have a subtle glow there should also be an ammo option."), off-device: the spot on the real
 * slot table, the even row, the trigger fade, the purse, the glow, the ammo. The clock + battery are NOT in the row (the user: "No the
 * time and Battery still needs to be centered at the top like it was before, do not change that"): the row stays under the top chip.
 * The purse is no row item (the user, 2026-11-13: "lets ditch the Icon for the gold and just list our total gold under the battery (still
 * give it a toggle)"): the chip's gold line, [GOLD_H] under the battery; the row clears it too.
 */
class ClockBandTest {
    /** Home's DIAMONDS band at rest on the Thor: 1240 x 218, the 18 px cast-bar strip */
    private val spot = CastBarPlace.clockSpot(1240f, 218f, CastBarPlace.REST_STRIP)
    /** the top clock chip's bottom + the clearance (HomeView: clockChip at b.top + 10, 84 high, as at 2e564ee8) */
    private val floor = ClockBand.CHIP_TOP + ClockBand.CHIP_H + ClockBand.CHIP_PAD
    /** the same with the gold line under the battery */
    private val goldFloor = ClockBand.chipBottom(true) + ClockBand.CHIP_PAD
    /** the extras' widths (Hearthstone, class teleport, free bag slots, ammo) */
    private val all = floatArrayOf(46f, 46f, 28f, 40f)

    @Test fun theChipIsAsBefore() {
        assertEquals(10f, ClockBand.CHIP_TOP, 0f)
        assertEquals(84f, ClockBand.CHIP_H, 0f)
        assertEquals(ClockBand.CHIP_TOP + ClockBand.CHIP_H, ClockBand.chipBottom(false), 0f)   // Gold Off: the chip as at 2e564ee8
        assertEquals(ClockBand.CHIP_TOP + ClockBand.CHIP_H + ClockBand.GOLD_H, ClockBand.chipBottom(true), 0f)
        // the gold line under the battery (its middle at 62): a line's height lower, inside the added height
        assertTrue(ClockBand.GOLD_Y - 62f >= 21f && ClockBand.GOLD_Y + 21f / 2f <= ClockBand.CHIP_H + ClockBand.GOLD_H)
    }

    /** the gold line ("123g 45s 67c" at 21 px with 13 px coins: at most ~150 wide) clears every button, the row under it clears it */
    @Test fun theGoldLineIsOnNoButtonAndTheRowClearsIt() {
        val gl = 620f - 80f; val gr = 620f + 80f
        val gt = ClockBand.CHIP_TOP + ClockBand.GOLD_Y - 12f; val gb = ClockBand.chipBottom(true)
        for (p in 0 until 8) {
            val e = slotBox(p)
            assertFalse("slot $p under the gold line", e[0] < gr && e[2] > gl && e[1] < gb && e[3] > gt)
        }
        for (n in 1..4) {
            val w = all.copyOfRange(0, n)
            val pl = ClockBand.place(w, spot[0], spot[2], spot[4])
            val x0 = pl.lefts[0]; val x1 = pl.lefts[n - 1] + w[n - 1] * pl.scale
            val h = ClockBand.CELL_H * pl.scale
            val t = ClockBand.rowTop(spot, x0, x1, h, goldFloor)
            assertTrue("n=$n under the gold line", t >= gb + ClockBand.CHIP_PAD - 0.01f)
            assertTrue("n=$n above the strip", t + h <= 218f - CastBarPlace.REST_STRIP)
            for (p in 0 until 8) {
                val e = slotBox(p)
                assertFalse("n=$n slot $p under the row (gold on)", e[0] < x1 && e[2] > x0 && e[1] < t + h && e[3] > t)
            }
        }
    }

    private fun slotBox(pos: Int): FloatArray {
        val b = FloatArray(3)
        CastBarPlace.slot(pos, true, 1f, (218f - CastBarPlace.REST_STRIP) / CastBarPlace.TAKE_H, 0f, 0f, 1240f, b)
        val r = b[2] / 2f
        return floatArrayOf(b[0] - r, b[1] - r, b[0] + r, b[1] + r)
    }

    @Test fun theSpotIsWhereTheTriggersJoinAndOnNoButton() {
        val (l, t, r, b) = spot.toList()
        val cx = spot[4]; val joinY = spot[5]
        assertEquals(620f, cx, 0.01f)                                         // the join's centre (w / 2)
        assertEquals(218f - CastBarPlace.REST_STRIP - CastBarPlace.JOIN_BOTTOM, joinY, 0.01f)
        assertTrue(cx - l > 200f && r - cx > 200f)                            // room both sides of the join (the nearer side sets the row's)
        // the joined ZL + ZR chips (w/2 -+ 64, 84 wide) lie inside the spot's width, and the spot overlaps their row
        assertTrue(l <= 620f - 64f - 42f && r >= 620f + 64f + 42f)
        assertTrue(t < joinY + 28f && b > joinY - 28f)
        assertTrue(b <= 218f - CastBarPlace.REST_STRIP)                       // above the cast bar's strip
        assertTrue(b - t >= ClockBand.CELL_H)                                 // a full-size row fits
        for (p in 0 until 8) {
            val e = slotBox(p)
            val overlap = e[0] < r && e[2] > l && e[1] < b && e[3] > t
            assertFalse("slot $p under the band", overlap)
        }
    }

    @Test fun aNarrowRowSitsOnTheJoinAWideOneUnderTheButtons() {
        val h = ClockBand.CELL_H
        // one extra (the Hearthstone, 46 wide) passes between the D-pad's Right and the faces' Y: centred on the join itself
        val one = ClockBand.place(floatArrayOf(46f), spot[0], spot[2], spot[4])
        val t1 = ClockBand.rowTop(spot, one.lefts[0], one.lefts[0] + 46f, h, floor)
        assertEquals(spot[5] - h / 2f, t1, 0.01f)
        // 1..4: as low as the spot's middle at most, under the clock chip, above the strip, on no button at any count
        for (n in 1..4) {
            val w = all.copyOfRange(0, n)
            val pl = ClockBand.place(w, spot[0], spot[2], spot[4])
            val x0 = pl.lefts[0]; val x1 = pl.lefts[n - 1] + w[n - 1] * pl.scale
            val t = ClockBand.rowTop(spot, x0, x1, h * pl.scale, floor)
            assertTrue(t >= spot[5] - h / 2f - 0.01f && t <= (spot[1] + spot[3]) / 2f - h * pl.scale / 2f + 0.01f)
            assertTrue("n=$n under the clock chip", t >= floor)
            assertTrue(t + h * pl.scale <= 218f - CastBarPlace.REST_STRIP)
            for (p in 0 until 8) {
                val e = slotBox(p)
                assertFalse("n=$n slot $p under the row", e[0] < x1 && e[2] > x0 && e[1] < t + h * pl.scale && e[3] > t)
            }
        }
    }

    @Test fun theFloorHoldsTheRowUnderTheChip() {
        // a spot reaching up past the chip: the row is pushed down to the floor, never onto the chip
        val s = spot.copyOf(); s[5] = 60f
        assertEquals(floor, ClockBand.rowTop(s, 600f, 640f, ClockBand.CELL_H, floor), 0.01f)
        assertEquals(60f - ClockBand.CELL_H / 2f, ClockBand.rowTop(s, 600f, 640f, ClockBand.CELL_H), 0.01f)   // no floor: on the join
    }

    /**
     * THE EVEN-PITCH RULE (feat/extras-polish; the user, 2026-11-23: "The badges on the home page when there are 3 for hearth bags and
     * ammo are not centered and look like poop when I use 3 with the ammo"): for every count and every mix of widths, each cell is centred
     * on its slot, the slots stand at ONE pitch, centred on the join (the middle icon of an odd count right under the clock), the closest
     * neighbours [gap] apart (GAP_MIN .. GAP_MAX), the whole row inside the spot
     */
    @Test fun itemsAreSpacedEvenlyAndCentredForEveryCount() {
        val mixes = listOf(all, floatArrayOf(28f, 52f, 86f, 46f, 28f), floatArrayOf(86f, 28f, 28f, 52f, 46f), FloatArray(6) { 28f + 11f * it })
        for (ws in mixes) for (n in 1..ws.size) {
            val w = ws.copyOfRange(0, n)
            val pl = ClockBand.place(w, spot[0], spot[2], spot[4])
            if (n <= 4 && ws === all) assertEquals(1f, pl.scale, 0f)
            val c = FloatArray(n) { pl.lefts[it] + w[it] * pl.scale / 2f }
            val mid = (c.first() + c.last()) / 2f
            assertEquals("n=$n the icons centred on the join", spot[4], mid, 0.01f)
            if (n % 2 == 1) assertEquals("n=$n the middle icon on the join", spot[4], c[n / 2], 0.01f)
            if (n > 1) {
                val pitch = c[1] - c[0]
                for (i in 1 until n) assertEquals("n=$n pitch $i", pitch, c[i] - c[i - 1], 0.01f)
                val tightest = (1 until n).minOf { pl.lefts[it] - (pl.lefts[it - 1] + w[it - 1] * pl.scale) }
                assertEquals("n=$n the closest neighbours gap apart", pl.gap, tightest, 0.01f)
            }
            assertTrue(pl.gap <= ClockBand.GAP_MAX + 0.01f && (n == 1 || pl.gap >= ClockBand.GAP_MIN * pl.scale - 0.01f))
            assertTrue(pl.lefts[0] >= spot[0] - 0.01f && pl.lefts[n - 1] + w[n - 1] * pl.scale <= spot[2] + 0.01f)
        }
        // fewer items stand closer to the middle; never wider apart than GAP_MAX
        val two = ClockBand.place(floatArrayOf(46f, 46f), spot[0], spot[2], spot[4])
        assertEquals(ClockBand.GAP_MAX, two.gap, 0.01f)
    }

    /**
     * THE USER'S CASE (2026-11-23): the Hearthstone ready (no value: the icon's 28), the bags "60/66" and the ammo "1843/3200" (a quiver)
     * - the old rule (equal gaps between the cells' EDGES, their extent centred) put the bags' icon ~28 px left of the clock (these widths) and
     * the gaps 40 vs 40 between the edges but 77 vs 105 between the icons; now the bags' icon is ON the join and the icons evenly apart
     */
    @Test fun theUsersThreeAreBalanced() {
        val w = floatArrayOf(28f, 46f, 84f)
        val pl = ClockBand.place(w, spot[0], spot[2], spot[4])
        val c = FloatArray(3) { pl.lefts[it] + w[it] * pl.scale / 2f }
        assertEquals("the bags under the clock", spot[4], c[1], 0.01f)
        assertEquals("evenly apart", c[1] - c[0], c[2] - c[1], 0.01f)
        // the ammo's wide value never touches the bags': the closest pair GAP_MIN at least
        assertTrue(pl.lefts[2] - (pl.lefts[1] + w[1] * pl.scale) >= ClockBand.GAP_MIN * pl.scale - 0.01f)
        // the old rule, for the record (equal edge gaps of GAP_MAX, the extent centred)
        val oldWhole = w.sum() + 2f * ClockBand.GAP_MAX
        val oldBags = spot[4] - oldWhole / 2f + w[0] + ClockBand.GAP_MAX + w[1] / 2f
        assertTrue("the old rule put the bags left of the clock (${spot[4] - oldBags} px)", spot[4] - oldBags > 15f)
    }

    /** the GROWN row (the info block grown into the empty column) keeps the same rule: the icons evenly apart, the middle under the clock */
    @Test fun theGrownRowIsEvenToo() {
        val chip = floatArrayOf(160f, 108f)
        for (ws in listOf(floatArrayOf(28f, 46f, 84f), floatArrayOf(28f, 52f, 46f, 84f, 28f), floatArrayOf(84f))) {
            val g = ClockBand.grow(spot, ClockBand.CHIP_TOP, chip[0], chip[1], ws) ?: continue
            val c = FloatArray(ws.size) { g.lefts[it] + ws[it] * g.rowScale / 2f }
            assertEquals("${ws.size}: centred", spot[4], (c.first() + c.last()) / 2f, 0.01f)
            for (i in 2 until ws.size) assertEquals("${ws.size}: pitch $i", c[1] - c[0], c[i] - c[i - 1], 0.01f)
            for (i in 1 until ws.size)
                assertTrue("${ws.size}: no overlap $i", g.lefts[i] >= g.lefts[i - 1] + ws[i - 1] * g.rowScale + ClockBand.GROWN_GAP * g.rowScale - 0.01f)
        }
    }

    @Test fun aRowTooWideScalesDownToFitTheSpot() {
        val w = FloatArray(4) { 200f }
        val pl = ClockBand.place(w, spot[0], spot[2], spot[4])
        assertTrue(pl.scale < 1f)
        val half = minOf(spot[4] - spot[0], spot[2] - spot[4])                // centred on the join: the nearer edge
        assertEquals(spot[4] - half, pl.lefts[0], 0.5f)
        assertEquals(spot[4] + half, pl.lefts[3] + 200f * pl.scale, 0.5f)
        assertEquals(ClockBand.GAP_MIN * pl.scale, pl.gap, 0.01f)
    }

    @Test fun theBandFadesOutWhileATriggerIsHeldAndBackOnRelease() {
        val ms = 120f
        assertEquals(1f, ClockBand.fade(1f, false, 0L, ms), 0f)
        assertEquals(0.5f, ClockBand.fade(1f, true, 60L, ms), 0.001f)
        assertEquals(0f, ClockBand.fade(1f, true, 120L, ms), 0f)
        assertEquals(0f, ClockBand.fade(1f, true, 5_000L, ms), 0f)
        // let go half-way: from where it was, back up
        assertEquals(0.5f, ClockBand.fade(0.5f, false, 0L, ms), 0.001f)
        assertEquals(1f, ClockBand.fade(0.5f, false, 120L, ms), 0f)
    }

    @Test fun thePurseIsGoldSilverAndCopperWithoutLeadingZeros() {
        assertEquals(listOf(123L to 0, 45L to 1, 67L to 2), ClockBand.coins(1_234_567L))
        assertEquals(listOf(34L to 1, 56L to 2), ClockBand.coins(3_456L))
        assertEquals(listOf(9L to 2), ClockBand.coins(9L))
        assertEquals(listOf(0L to 2), ClockBand.coins(0L))
        assertEquals(listOf(12L to 0, 0L to 1, 5L to 2), ClockBand.coins(120_005L))   // a zero after the first unit stays
        assertEquals(listOf(1L to 1, 0L to 2), ClockBand.coins(100L))
    }

    @Test fun theHearthstoneGlowsOnlyWhenReady() {
        assertTrue(ClockBand.hearthGlow(true, 0.0, false))
        assertFalse(ClockBand.hearthGlow(true, 754.0, false))                 // cooling
        assertFalse(ClockBand.hearthGlow(true, 0.0, true))                    // held dim / length unknown
        assertFalse(ClockBand.hearthGlow(null, 0.0, false))                   // not said yet
        assertFalse(ClockBand.hearthGlow(false, 0.0, false))                  // not in the bags
    }

    @Test fun ammoShowsForAPlayerWhoUsesAmmoEvenAtZero() {
        // feat/home-info-block (the user 2026-11-19: "I dont see it at all when im empty"): the ammo is read from the bags document's
        // `equipped`; 0 still shows; a character with no ammo slot item, no quiver and no AmmoNeeded gets no cell
        assertNull(ClockBand.ammo(null))
        assertNull(ClockBand.ammo(JSONObject("""{"equipped":{}}""")))                     // a caster, a warrior with none
        val zero = ClockBand.ammo(JSONObject("""{"equipped":{"ammo":{"id":2512,"icon":132382,"count":0}}}"""))
        assertNotNull(zero); assertEquals(0, zero!!.count); assertTrue(zero.empty)
        val a = ClockBand.ammo(JSONObject("""{"equipped":{"ammo":{"id":2512,"icon":132382,"count":1843},"ammoNeeded":true}}"""))
        assertNotNull(a)
        assertEquals(132382, a!!.icon); assertEquals(1843, a.count); assertEquals(0, a.of)    // no quiver / pouch: the count alone
        assertEquals("1843", ClockBand.count(1843)); assertEquals("12k", ClockBand.count(12_400))
    }

    @Test fun theOrderAndNoClockOrGoldInTheRow() {
        val k = ClockBand.Kind.values().toList()
        assertEquals(5, k.size)                                               // the clock + the gold are the top chip, not row items
        assertFalse(k.any { it.name == "CLOCK" || it.name == "GOLD" })
        assertEquals(k, ClockBand.kinds(true, true, true, true, true))
        assertEquals(k - ClockBand.Kind.PET, ClockBand.kinds(true, true, true, true))
        assertEquals(emptyList<ClockBand.Kind>(), ClockBand.kinds(false, false, false, false))
        assertEquals(listOf(ClockBand.Kind.HEARTH, ClockBand.Kind.AMMO), ClockBand.kinds(true, false, false, true))
        // each kind's key is its Settings row's (BottomTiles.CLOCK_LINES), the gold line's row has no kind
        assertEquals(listOf("hearth", "teleport", "bags", "ammo", "pet"), ClockBand.DEFAULT)
        assertEquals(BottomTiles.CLOCK_LINES.map { it.first } - "gold", ClockBand.DEFAULT)
        assertNull(ClockBand.kindOf("gold"))
    }

    /** feat/extras-polish (the user, 2026-11-23: "I also want to be able to change the order on these"): the player's order */
    @Test fun thePlayersOrder() {
        val on = setOf(ClockBand.Kind.HEARTH, ClockBand.Kind.BAGS, ClockBand.Kind.AMMO)
        assertEquals(listOf(ClockBand.Kind.AMMO, ClockBand.Kind.HEARTH, ClockBand.Kind.BAGS),
            ClockBand.kinds(listOf("ammo", "hearth", "teleport", "bags", "pet"), on))
        // a saved order made whole: unknown / doubled keys go, a missing one comes back after the one it follows today
        assertEquals(listOf("ammo", "pet", "hearth", "teleport", "bags"), ClockBand.order(listOf("ammo", "x", "hearth", "ammo", "bags")))
        assertEquals(ClockBand.DEFAULT, ClockBand.order(listOf("pet")))
        assertEquals(ClockBand.DEFAULT, ClockBand.order(null))
        assertEquals(ClockBand.DEFAULT, ClockBand.order(emptyList()))
        assertTrue(ClockBand.isDefault(listOf("hearth", "teleport", "bags")))
        // a move among the SHOWN ones (a hidden one keeps its place in the whole order)
        val shown = listOf("hearth", "bags", "ammo")
        assertEquals(listOf("teleport", "bags", "ammo", "hearth", "pet"), ClockBand.moveShown(ClockBand.DEFAULT, shown, "hearth", 2))
        assertEquals(listOf("ammo", "hearth", "teleport", "bags", "pet"), ClockBand.moveShown(ClockBand.DEFAULT, shown, "ammo", 0))
        assertEquals(listOf("hearth", "teleport", "ammo", "bags", "pet"), ClockBand.moveShown(ClockBand.DEFAULT, shown, "ammo", 1))
        assertEquals("not shown: unchanged", ClockBand.DEFAULT, ClockBand.moveShown(ClockBand.DEFAULT, shown, "teleport", 0))
        assertEquals("outside the row: unchanged", ClockBand.DEFAULT, ClockBand.moveShown(ClockBand.DEFAULT, shown, "hearth", 3))
        // the pet's three states, nothing else
        assertEquals(listOf(1, 2, 3), (1..3).map { ClockBand.petMood(it) })
        assertNull(ClockBand.petMood(0)); assertNull(ClockBand.petMood(4)); assertNull(ClockBand.petMood(null))
    }

    /** feat/extras-polish: the pet happiness row's words in Spanish and French (CRLF i18n files) */
    @Test fun thePetWordsAreInSpanishAndFrench() {
        val dir = listOf("../companion/app/src/main/assets/companion/i18n", "companion/app/src/main/assets/companion/i18n")
            .map { java.io.File(it) }.first { it.isDirectory }
        for (l in listOf("es", "fr")) {
            Tr.load(l, java.io.File(dir, "$l.json").readText(Charsets.UTF_8))
            for (w in listOf("Pet happiness", "In the row under the clock, with a hunter's pet.")) assertTrue("$l: $w", Tr.t(w, l) != w)
        }
    }

    /** the order rides the layout record: written only when not today's (a Classic record's text unchanged), read tolerantly, and
     *  Reset Home to Classic puts today's back */
    @Test fun theOrderIsInTheLayoutRecord() {
        val classic = HomeLayout()
        assertFalse(HomeLayoutJson.json(classic).contains("extras"))
        val mine = classic.copy(extras = listOf("ammo", "pet", "hearth", "teleport", "bags"))
        val j = HomeLayoutJson.json(mine)
        assertTrue(j.contains("\"extras\":[\"ammo\",\"pet\",\"hearth\",\"teleport\",\"bags\"]"))
        assertEquals(mine, HomeLayoutJson.parse(j))
        assertEquals(listOf("bags", "ammo", "pet", "hearth", "teleport"),
            HomeLayoutJson.parse("""{"v":1,"extras":["bags","nope","hearth","bags"]}""").extras)
        assertEquals(ClockBand.DEFAULT, mine.classic().extras)
        assertFalse(mine.samePlace(classic))
        // the record store's field-by-field merge carries it (edit mode's autosave over a record changed meanwhile)
        val other = classic.copy(chatSide = HomeLayout.Side.RIGHT)
        assertEquals(mine.extras, HomeLayoutStore.layOn(other, classic, mine).extras)
        assertEquals(HomeLayout.Side.RIGHT, HomeLayoutStore.layOn(other, classic, mine).chatSide)
    }
}
