package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * bags-gear1 THE EQUIP SLIDE-OUT (the user, 2026-11-16: "Choose which ring or weapon slot to replace"; "Design: X opens a slide-out of
 * ARMOR (item icons) next to the item. It lists the options for that slot, plus a blank entry that unequips."; "When pressed, X animates
 * the options sliding out."; the picks D1a-D5a): [SlideOut]'s rules - the rail's order, its way out, its geometry, its keys, what can go
 * on now, the card, the watch, the animation's shares and the Bags room's target row.
 */
class SlideOutTest {
    private fun row(equip: String, ilvl: Int = 10, id: Int = 1000 + ilvl, stats: JSONArray? = null) =
        JSONObject().put("equip", equip).put("ilvl", ilvl).put("id", id).put("name", "$equip $ilvl").put("quality", 2)
            .also { if (stats != null) it.put("stats", stats) }

    private fun stat(k: String, n: String, v: Int) = JSONObject().put("k", k).put("n", n).put("v", v)
    private fun pick(equip: String, ilvl: Int = 10, exact: Boolean = true, bag: Int = 0, slot: Int = 1, stats: JSONArray? = null) =
        WornReplace.Pick(bag, slot, row(equip, ilvl, stats = stats), exact)

    // ---------------------------------------------------------------- the rail's entries (G7, C5)

    @Test fun picksThenTheBlankLast() {
        val picks = listOf(pick("INVTYPE_FINGER", 20), pick("INVTYPE_FINGER", 12))
        val e = SlideOut.entries(picks, 11, worn = true)
        assertEquals(3, e.size)
        assertFalse(e[0].blank); assertFalse(e[1].blank); assertTrue("the blank goes last", e[2].blank)
    }

    @Test fun aWornSlotWithNothingStillHasItsBlank() {
        val e = SlideOut.entries(emptyList(), 5, worn = true)
        assertEquals(1, e.size); assertTrue(e[0].blank)
    }

    @Test fun anEmptySlotHasNoBlankAndNothingMeansNoRail() {
        assertTrue(SlideOut.entries(emptyList(), 5, worn = false).isEmpty())
        assertEquals(1, SlideOut.entries(listOf(pick("INVTYPE_CHEST")), 5, worn = false).size)
    }

    @Test fun theAmmoSlotHasNoBlank() {
        val e = SlideOut.entries(listOf(pick("INVTYPE_AMMO")), WornReplace.AMMO, worn = true)
        assertEquals(1, e.size); assertFalse(e[0].blank)
    }

    // ---------------------------------------------------------------- which way, where (D3a, C4)

    @Test fun towardTheDollsMiddle() {
        for (s in listOf(1, 2, 3, 15, 5, 4, 19, 9)) assertEquals("left column $s", SlideOut.Dir.RIGHT, SlideOut.dir(s))
        for (s in listOf(10, 6, 7, 8, 11, 12, 13, 14)) assertEquals("right column $s", SlideOut.Dir.LEFT, SlideOut.dir(s))
        for (s in listOf(16, 17, 18, 0)) assertEquals("weapons / ammo $s", SlideOut.Dir.UP, SlideOut.dir(s))
    }

    /** the doll at 1240 wide (CharacterView.doll: pitch 104, slot 92, the left column at x 30, the right at 574, weapons at y 810) */
    private val s = 92f
    private val pitch = 104f

    @Test fun aLeftColumnRailSlidesRightFourIconsInTheMiddle() {
        val head = SlideOut.Box(30f, 82f, s)
        val cells = (0 until SlideOut.SHOWN).map { SlideOut.cell(head, SlideOut.Dir.RIGHT, it, pitch, s) }
        assertEquals(134f, cells[0].left, 0.01f)                    // a 12 px gap off the slot
        assertEquals(82f, cells[0].top, 0.01f)
        assertEquals(pitch, cells[1].left - cells[0].left, 0.01f)
        assertTrue("four fit before the right column (x 574)", cells.last().right <= 574f)
        assertTrue("a fifth would cover it", SlideOut.cell(head, SlideOut.Dir.RIGHT, 4, pitch, s).right > 574f)
    }

    @Test fun aRightColumnRailSlidesLeft() {
        val hands = SlideOut.Box(574f, 82f, s)
        val c0 = SlideOut.cell(hands, SlideOut.Dir.LEFT, 0, pitch, s)
        assertEquals(574f - 12f, c0.right, 0.01f)
        val c3 = SlideOut.cell(hands, SlideOut.Dir.LEFT, 3, pitch, s)
        assertTrue("four fit after the left column (x 122)", c3.left >= 122f)
        assertEquals(c0.top, hands.top, 0.01f)
    }

    @Test fun theWeaponsRailSlidesUp() {
        val main = SlideOut.Box(400f, 810f, s)
        val c0 = SlideOut.cell(main, SlideOut.Dir.UP, 0, pitch, s)
        assertEquals(810f - 12f, c0.bottom, 0.01f)
        assertEquals(main.left, c0.left, 0.01f)
        val c3 = SlideOut.cell(main, SlideOut.Dir.UP, 3, pitch, s)
        assertTrue(c3.top > 82f)                                     // under the header
    }

    @Test fun theSmallAmmoSlotsRailIsFullSizeCentredOverIt() {
        val ammo = SlideOut.Box(500f, 863f, 39f)
        val c0 = SlideOut.cell(ammo, SlideOut.Dir.UP, 0, pitch, s)
        assertEquals(s, c0.size, 0.01f)
        assertEquals(ammo.cx, c0.cx, 0.01f)
        assertEquals(863f - 12f, c0.bottom, 0.01f)
    }

    @Test fun theWindowKeepsTheSelectionInView() {
        assertEquals(0, SlideOut.first(3, 4, 0))
        assertEquals(0, SlideOut.first(3, 6, 0))
        assertEquals(1, SlideOut.first(4, 6, 0))
        assertEquals(2, SlideOut.first(5, 6, 1))
        assertEquals(1, SlideOut.first(1, 6, 2))                    // back toward the slot
        assertEquals(false to true, SlideOut.more(0, 6))
        assertEquals(true to false, SlideOut.more(2, 6))
        assertEquals(false to false, SlideOut.more(0, 4))
    }

    @Test fun lerpRunsFromTheSlotToItsPlace() {
        val a = SlideOut.Box(0f, 0f, 10f); val b = SlideOut.Box(100f, 50f, 20f)
        assertEquals(a, SlideOut.lerp(a, b, 0f)); assertEquals(b, SlideOut.lerp(a, b, 1f))
        assertEquals(50f, SlideOut.lerp(a, b, 0.5f).left, 0.01f)
    }

    // ---------------------------------------------------------------- keys (G5)

    @Test fun theDPadFollowsTheRail() {
        assertEquals(1 to SlideOut.Act.NONE, SlideOut.key(0, 3, SlideOut.Dir.RIGHT, "PADDRIGHT"))
        assertEquals(0 to SlideOut.Act.NONE, SlideOut.key(1, 3, SlideOut.Dir.RIGHT, "PADDLEFT"))
        assertEquals(1 to SlideOut.Act.NONE, SlideOut.key(0, 3, SlideOut.Dir.LEFT, "PADDLEFT"))
        assertEquals(1 to SlideOut.Act.NONE, SlideOut.key(0, 3, SlideOut.Dir.UP, "PADDUP"))
        assertEquals(0 to SlideOut.Act.NONE, SlideOut.key(1, 3, SlideOut.Dir.UP, "PADDDOWN"))
        assertEquals("clamped at the end", 2 to SlideOut.Act.NONE, SlideOut.key(2, 3, SlideOut.Dir.RIGHT, "PADDRIGHT"))
        assertEquals("clamped at the slot", 0 to SlideOut.Act.NONE, SlideOut.key(0, 3, SlideOut.Dir.RIGHT, "PADDLEFT"))
    }

    @Test fun acrossTheRailNothingAChoosesBOrXGoesBack() {
        assertEquals(1 to SlideOut.Act.NONE, SlideOut.key(1, 3, SlideOut.Dir.RIGHT, "PADDUP"))
        assertEquals(1 to SlideOut.Act.NONE, SlideOut.key(1, 3, SlideOut.Dir.UP, "PADDLEFT"))
        assertEquals(1 to SlideOut.Act.CHOOSE, SlideOut.key(1, 3, SlideOut.Dir.UP, "PAD1"))
        assertEquals(1 to SlideOut.Act.BACK, SlideOut.key(1, 3, SlideOut.Dir.UP, "PAD2"))
        assertEquals(1 to SlideOut.Act.BACK, SlideOut.key(1, 3, SlideOut.Dir.UP, "PAD3"))
        assertEquals(1 to SlideOut.Act.NONE, SlideOut.key(1, 3, SlideOut.Dir.UP, "PAD4"))
    }

    // ---------------------------------------------------------------- what can go on now (the route table, G4, Bags full)

    private fun worn(vararg w: Pair<Int, JSONObject>) = mapOf(*w)
    private fun wornRow(id: Int, equip: String, stats: JSONArray? = null) =
        JSONObject().put("id", id).put("equip", equip).put("item", "Worn $id").also { if (stats != null) it.put("stats", stats) }

    @Test fun outOfAFightAnyPieceGoes() {
        assertNull(SlideOut.why(SlideOut.Entry(pick("INVTYPE_CHEST")), 5, false, false, 3, emptyMap(), routed = true))
        assertNull(SlideOut.why(SlideOut.Entry(pick("INVTYPE_FINGER", exact = false)), 12, false, false, 3, emptyMap(), routed = true))
    }

    @Test fun armourInAFightIsGreyedEvenTheBlank() {
        assertEquals("In combat", SlideOut.why(SlideOut.Entry(pick("INVTYPE_CHEST")), 5, true, false, 3, emptyMap(), routed = false))
        assertEquals("In combat", SlideOut.why(SlideOut.Entry(null), 5, true, false, 3, emptyMap(), routed = true))
    }

    @Test fun weaponsInAFightAsTheGameAllows() {
        assertNull(SlideOut.why(SlideOut.Entry(pick("INVTYPE_SHIELD")), 17, true, false, 3, emptyMap(), routed = true))
        assertNull("the blank: a weapon may come off in a fight", SlideOut.why(SlideOut.Entry(null), 16, true, false, 3, emptyMap(), routed = true))
        // D2a: a dual wielder's one-hander (two places to go) stays greyed in both hands in a fight
        assertEquals("In combat", SlideOut.why(SlideOut.Entry(pick("INVTYPE_WEAPON", exact = false)), 17, true, false, 3, emptyMap(), routed = false))
        assertEquals("In combat", SlideOut.why(SlideOut.Entry(pick("INVTYPE_WEAPON", exact = false)), 16, true, false, 3, emptyMap(), routed = false))
    }

    @Test fun aTwoHanderOverAWornOffHandNeedsABagSlot() {
        val w = worn(16 to wornRow(1, "INVTYPE_WEAPON"), 17 to wornRow(2, "INVTYPE_SHIELD"))
        val maul = SlideOut.Entry(pick("INVTYPE_2HWEAPON"))
        assertEquals("Bags full", SlideOut.why(maul, 16, false, false, 0, w, routed = true))
        assertNull(SlideOut.why(maul, 16, false, false, 1, w, routed = true))
        assertNull("not known yet: the game judges", SlideOut.why(maul, 16, false, false, null, w, routed = true))
        assertNull("no off hand worn", SlideOut.why(maul, 16, false, false, 0, worn(16 to wornRow(1, "INVTYPE_WEAPON")), routed = true))
    }

    @Test fun anOffHandPieceUnderAWornTwoHanderNeedsABagSlot() {
        val w = worn(16 to wornRow(1, "INVTYPE_2HWEAPON"))
        val shield = SlideOut.Entry(pick("INVTYPE_SHIELD"))
        assertEquals("Bags full", SlideOut.why(shield, 17, false, false, 0, w, routed = true))
        assertNull(SlideOut.why(shield, 17, false, false, 2, w, routed = true))
        assertNull("a one-hander in the main hand", SlideOut.why(shield, 17, false, false, 0, worn(16 to wornRow(1, "INVTYPE_WEAPON")), routed = true))
    }

    @Test fun theBlankKeepsUnequipsRule() {
        assertEquals("Bags full", SlideOut.why(SlideOut.Entry(null), 5, false, false, 0, emptyMap(), routed = true))
        assertEquals("Unavailable", SlideOut.why(SlideOut.Entry(null), 5, false, false, 3, emptyMap(), routed = false))
        assertNull(SlideOut.why(SlideOut.Entry(null), 5, false, false, 3, emptyMap(), routed = true))
    }

    @Test fun ammoByItsSecureCellNotUnderAWindow() {
        val arrows = SlideOut.Entry(pick("INVTYPE_AMMO"))
        assertNull(SlideOut.why(arrows, 0, false, false, 0, emptyMap(), routed = true))
        assertNull("in a fight: the game decides", SlideOut.why(arrows, 0, true, false, 0, emptyMap(), routed = true))
        assertEquals("Window open", SlideOut.why(arrows, 0, false, true, 3, emptyMap(), routed = true))
        assertEquals("Unavailable", SlideOut.why(arrows, 0, false, false, 3, emptyMap(), routed = false))
    }

    @Test fun noPadCellsIsUnavailable() {
        assertEquals("Unavailable", SlideOut.why(SlideOut.Entry(pick("INVTYPE_CHEST")), 5, false, false, 3, emptyMap(), routed = false))
    }

    // ---------------------------------------------------------------- the card (D4a)

    private val ring = arrayOf(stat("ITEM_MOD_STAMINA_SHORT", "Stamina", 3), stat("ITEM_MOD_AGILITY_SHORT", "Agility", 2))
    private fun arr(vararg o: JSONObject) = JSONArray().also { a -> o.forEach { a.put(it) } }

    @Test fun theShortCardNameStatsAndThisSlotsChanges() {
        val p = pick("INVTYPE_FINGER", 15, exact = false, stats = arr(*ring))
        val w = worn(11 to wornRow(901, "INVTYPE_FINGER", arr(stat("ITEM_MOD_STAMINA_SHORT", "Stamina", 2))), 12 to JSONObject().put("slot", 12))
        val card = SlideOut.card(SlideOut.Entry(p), 11, null, w, false)
        assertFalse(card.full)
        assertEquals("INVTYPE_FINGER 15", card.title)
        val t = (0 until card.lines.length()).map { card.lines.getJSONObject(it).optString("t") }
        assertEquals("its stats, then the changes against Finger 1 only (the app's own count: 3 - 2 Stamina)",
            listOf("+3 Stamina", "+2 Agility", " ", "Stat changes", "+1 Stamina", "+2 Agility"), t)
        val e12 = SlideOut.card(SlideOut.Entry(p), 12, null, w, false)
        val t12 = (0 until e12.lines.length()).map { e12.lines.getJSONObject(it).optString("t") }
        assertEquals("Finger 2 is empty: all gains", listOf("+3 Stamina", "+2 Agility", " ", "Stat changes", "+3 Stamina", "+2 Agility"), t12)
    }

    @Test fun theFullTooltipWhenABagsVisitKeptIt() {
        val p = pick("INVTYPE_FINGER", 15, exact = false, stats = arr(*ring))
        val tip = JSONObject().put("key", "bag 0 1 1015").put("lines", arr(JSONObject().put("t", "Ring of the Copper Vein"),
            JSONObject().put("t", "Binds when equipped"), JSONObject().put("t", "Finger")))
            .put("cmp", arr(JSONObject().put("slot", 11).put("dn", 1).put("nw", true).put("lines",
                arr(JSONObject().put("t", " "), JSONObject().put("t", "If you replace this item...").put("c", "ffd100"),
                    JSONObject().put("t", "+5 Spirit").put("c", "00ff00"))),
                JSONObject().put("slot", 12).put("dn", 1).put("nw", true).put("lines",
                    arr(JSONObject().put("t", "If you replace this item...").put("c", "ffd100"), JSONObject().put("t", "-9 Spirit").put("c", "ff2020")))))
        val w = worn(11 to wornRow(901, "INVTYPE_FINGER"), 12 to wornRow(902, "INVTYPE_FINGER"))
        val card = SlideOut.card(SlideOut.Entry(p), 11, tip, w, false)
        assertTrue(card.full)
        assertEquals("Ring of the Copper Vein", card.title)
        val t = (0 until card.lines.length()).map { card.lines.getJSONObject(it).optString("t") }
        assertEquals("Binds when equipped", t[0])
        assertTrue("the game's change for Finger 1", t.contains("+5 Spirit"))
        assertFalse("never Finger 2's", t.contains("-9 Spirit"))
    }

    @Test fun theBlankReadsUnequipAndTheLosses() {
        val w = worn(5 to wornRow(900, "INVTYPE_CHEST", arr(stat("RESISTANCE0_NAME", "Armor", 12), stat("ITEM_MOD_STAMINA_SHORT", "Stamina", 1))))
        val card = SlideOut.card(SlideOut.Entry(null), 5, null, w, false)
        assertEquals("Unequip", card.title); assertEquals(-1, card.quality)
        val t = (0 until card.lines.length()).map { card.lines.getJSONObject(it).optString("t") }
        assertEquals("Worn 900", t[0])
        assertTrue(t.contains("-12 Armor")); assertTrue(t.contains("-1 Stamina"))
        assertTrue("losses in the game's red", (1 until card.lines.length()).all { card.lines.getJSONObject(it).optString("c") == BagsCompare.LOSS })
    }

    @Test fun anOffHandPieceUnderATwoHanderShowsTheGamesCompare() {
        // the game compared the shield against the worn two-hander (slot 16): this slot (17) is the shield's only place
        val cards = arr(JSONObject().put("slot", 16).put("dn", 1).put("lines",
            arr(JSONObject().put("t", "Heavy Maul"), JSONObject().put("t", "Stat changes").put("c", "ffd100"), JSONObject().put("t", "-8 Strength").put("c", "ff2020"))))
        val l = SlideOut.changesFor(cards, 17, "INVTYPE_SHIELD", false)
        assertTrue(l.any { it.optString("t") == "-8 Strength" })
        assertTrue("a ring is not so", SlideOut.changesFor(cards, 12, "INVTYPE_FINGER", false).isEmpty())
    }

    @Test fun statLinesAsTheGameWritesThem() {
        val l = SlideOut.statLines(row("INVTYPE_CHEST", stats = arr(stat("RESISTANCE0_NAME", "Armor", 58), stat("ITEM_MOD_STAMINA_SHORT", "Stamina", 4))))
        assertEquals(listOf("58 Armor", "+4 Stamina"), l.map { it.optString("t") })
    }

    // ---------------------------------------------------------------- the watch (C6)

    @Test fun theSlotShowsThePieceOrTheGameRefusedIt() {
        val w = SlideOut.Watch(11, 7001, until = 2000L)
        assertEquals(SlideOut.Seen.WAIT, SlideOut.seen(w, 500L, 7050, asking = false))
        assertEquals(SlideOut.Seen.DONE, SlideOut.seen(w, 900L, 7001, asking = false))
        assertEquals(SlideOut.Seen.REFUSED, SlideOut.seen(SlideOut.Watch(11, 7001, 2000L), 2000L, 7050, asking = false))
    }

    @Test fun theGamesBindQuestionPausesTheWatch() {
        val w = SlideOut.Watch(13, 7101, until = 2000L)
        assertEquals(SlideOut.Seen.WAIT, SlideOut.seen(w, 1900L, 7150, asking = true))
        assertEquals(1900L + SlideOut.WATCH_MS, w.until)
        assertEquals("answered late: still within its new time", SlideOut.Seen.WAIT, SlideOut.seen(w, 3000L, 7150, asking = false))
        assertEquals(SlideOut.Seen.DONE, SlideOut.seen(w, 3100L, 7101, asking = false))
    }

    // ---------------------------------------------------------------- the animation (D5a)

    @Test fun quickSlidesWithAStagger() {
        assertEquals(160L, Motion.RAIL_OUT); assertEquals(15L, Motion.RAIL_STAGGER); assertEquals(120L, Motion.RAIL_IN)
        assertEquals(0f, SlideOut.outShare(0L, 0), 1e-6f)
        assertEquals(0.5f, SlideOut.outShare(80L, 0), 1e-6f)
        assertEquals("the second icon 15 ms behind", 0f, SlideOut.outShare(15L, 1), 1e-6f)
        assertEquals(1f, SlideOut.outShare(160L, 0), 1e-6f)
        assertTrue(SlideOut.outMoving(200L, 4)); assertFalse(SlideOut.outMoving(205L, 4))
        assertEquals(1f, SlideOut.inShare(120L), 1e-6f); assertEquals(0.5f, SlideOut.inShare(60L), 1e-6f)
    }

    // ---------------------------------------------------------------- the Bags room (D1a, G9)

    @Test fun onlyGearWithTwoPlacesOpensTheBagsRow() {
        assertEquals(listOf(11, 12), SlideOut.targets(row("INVTYPE_FINGER"), false))
        assertEquals(listOf(13, 14), SlideOut.targets(row("INVTYPE_TRINKET"), false))
        assertEquals(listOf(16), SlideOut.targets(row("INVTYPE_WEAPON"), false))
        assertEquals(listOf(16, 17), SlideOut.targets(row("INVTYPE_WEAPON"), true))
        assertEquals(listOf(16), SlideOut.targets(row("INVTYPE_2HWEAPON"), true))
        assertTrue(SlideOut.targets(row("INVTYPE_BAG"), false).isEmpty())
        assertTrue(SlideOut.targets(row("INVTYPE_AMMO"), false).isEmpty())
        assertTrue(SlideOut.opens(row("INVTYPE_FINGER"), false, combat = false, ctx = null))
        assertFalse("D1a: one place = X equips at once", SlideOut.opens(row("INVTYPE_CHEST"), false, false, null))
        assertFalse("a fight: the secure cell", SlideOut.opens(row("INVTYPE_FINGER"), false, combat = true, ctx = null))
        assertFalse("a vendor: Sell", SlideOut.opens(row("INVTYPE_FINGER"), false, combat = false, ctx = "merchant"))
    }

    /** review: the Bags room's row reads left to right on BOTH sides of its tile (the D-pad's right = the add-on's next target) */
    @Test fun theBagsRowReadsLeftToRightOnEitherSideOfItsTile() {
        val right = SlideOut.rowLefts(640f, 740f, 100f, 2, 1240f)
        assertEquals(listOf(752f, 864f), right)
        val left = SlideOut.rowLefts(1120f, 1220f, 100f, 2, 1240f)
        assertEquals("no room on the right: on the left", listOf(896f, 1008f), left)
        assertTrue("the first target leftmost", left[0] < left[1])
        assertEquals("12 px off the tile", 1120f - 12f, left[1] + 100f, 1e-3f)
        val three = SlideOut.rowLefts(1000f, 1100f, 100f, 3, 1240f)
        assertEquals(listOf(664f, 776f, 888f), three)
        assertEquals("one target", listOf(1112f), SlideOut.rowLefts(1000f, 1100f, 100f, 1, 1240f))
    }

    @Test fun theBagsRowStartsOnAnEmptySlotElseTheLowerItemLevel() {
        assertEquals(1, SlideOut.start(listOf(11, 12), mapOf(11 to wornRow(1, "INVTYPE_FINGER"), 12 to JSONObject())))
        assertEquals(1, SlideOut.start(listOf(13, 14), mapOf(13 to wornRow(1, "INVTYPE_TRINKET").put("ilvl", 25), 14 to wornRow(2, "INVTYPE_TRINKET").put("ilvl", 10))))
        assertEquals(0, SlideOut.start(listOf(13, 14), mapOf(13 to wornRow(1, "INVTYPE_TRINKET").put("ilvl", 10), 14 to wornRow(2, "INVTYPE_TRINKET").put("ilvl", 25))))
    }

    // ---------------------------------------------------------------- char-popout: the other slot's piece (the user, 2026-11-16: "on
    // weapons that are both 1 hand or can actually be swapped to the offhand or vice versa then that option should be listed in the pop
    // out options."; "if the only other option for something is nothing then I should be able to select that to unequip.")

    private fun hands(main: String?, off: String?): Map<Int, JSONObject> = buildMap {
        if (main != null) put(16, wornRow(16, main))
        if (off != null) put(17, wornRow(17, off))
    }

    @Test fun theOffHandsOneHanderIsListedForTheMainHand() {
        assertEquals(listOf(17), SlideOut.swaps(16, hands("INVTYPE_WEAPON", "INVTYPE_WEAPON"), dualWield = true))
        assertEquals("an empty main hand too", listOf(17), SlideOut.swaps(16, hands(null, "INVTYPE_WEAPON"), dualWield = true))
        assertEquals("an off-hand-only weapon never crosses",
            emptyList<Int>(), SlideOut.swaps(16, hands("INVTYPE_WEAPON", "INVTYPE_WEAPONOFFHAND"), dualWield = true))
        assertTrue("a shield never crosses", SlideOut.swaps(16, hands("INVTYPE_WEAPON", "INVTYPE_SHIELD"), true).isEmpty())
        assertTrue("a held-in-off-hand piece never crosses", SlideOut.swaps(16, hands("INVTYPE_WEAPON", "INVTYPE_HOLDABLE"), true).isEmpty())
        assertTrue("nothing in the off hand: nothing", SlideOut.swaps(16, hands("INVTYPE_WEAPON", null), true).isEmpty())
    }

    @Test fun theMainHandsOneHanderIsListedForTheOffHandOnlyForADualWielder() {
        assertEquals(listOf(16), SlideOut.swaps(17, hands("INVTYPE_WEAPON", "INVTYPE_SHIELD"), dualWield = true))
        assertTrue("no dual wield: never", SlideOut.swaps(17, hands("INVTYPE_WEAPON", "INVTYPE_SHIELD"), dualWield = false).isEmpty())
        assertTrue("a main-hand-only weapon never", SlideOut.swaps(17, hands("INVTYPE_WEAPONMAINHAND", null), true).isEmpty())
        assertTrue("a two-hander never", SlideOut.swaps(17, hands("INVTYPE_2HWEAPON", null), true).isEmpty())
        assertTrue("the ranged slot has no pair", SlideOut.swaps(18, hands("INVTYPE_WEAPON", "INVTYPE_WEAPON"), true).isEmpty())
    }

    @Test fun ringsAndTrinketsCrossBothWays() {
        val w = mapOf(11 to wornRow(1, "INVTYPE_FINGER"), 12 to wornRow(2, "INVTYPE_FINGER"), 13 to wornRow(3, "INVTYPE_TRINKET"))
        assertEquals(listOf(12), SlideOut.swaps(11, w, false))
        assertEquals(listOf(11), SlideOut.swaps(12, w, false))
        assertEquals("an empty trinket slot: the other trinket", listOf(13), SlideOut.swaps(14, w, false))
        assertTrue(SlideOut.swaps(13, w, false).isEmpty())
        assertTrue("no pair", SlideOut.swaps(5, w, false).isEmpty())
    }

    @Test fun theSwapFirstThenTheBagsThenTheBlank() {
        val e = SlideOut.entries(listOf(pick("INVTYPE_WEAPON", 20), pick("INVTYPE_WEAPON", 12)), 16, worn = true, swaps = listOf(17))
        assertEquals(4, e.size)
        assertTrue(e[0].swap); assertEquals(17, e[0].from); assertFalse(e[0].blank)
        assertFalse(e[1].swap); assertFalse(e[1].blank); assertFalse(e[2].blank)
        assertTrue("the blank last", e[3].blank); assertFalse(e[3].swap)
    }

    @Test fun nothingElseLeavesTheBlankAlone() {
        val e = SlideOut.entries(emptyList(), 3, worn = true, swaps = emptyList())
        assertEquals(1, e.size); assertTrue("the user: nothing is an option to unequip", e[0].blank)
        val only = SlideOut.entries(emptyList(), 12, worn = false, swaps = listOf(11))
        assertEquals("an empty ring slot: the other ring only, no blank", 1, only.size); assertTrue(only[0].swap)
    }

    @Test fun aSwapNeedsRoomWhenTheDisplacedPieceCantGoBack() {
        val sw = SlideOut.Entry(null, 17)
        val w = hands("INVTYPE_WEAPONMAINHAND", "INVTYPE_WEAPON")
        assertNull(SlideOut.why(sw, 16, false, false, 3, w, routed = true, dualWield = true))
        assertEquals("the main-hand-only weapon can't go to the off hand: bags full",
            "Bags full", SlideOut.why(sw, 16, false, false, 0, w, routed = true, dualWield = true))
        assertNull("two one-handers just change hands: no room needed",
            SlideOut.why(sw, 16, false, false, 0, hands("INVTYPE_WEAPON", "INVTYPE_WEAPON"), routed = true, dualWield = true))
        assertNull("an empty main hand: nothing comes off", SlideOut.why(sw, 16, false, false, 0, hands(null, "INVTYPE_WEAPON"), true, true))
        assertEquals("the shield goes to the bags", "Bags full",
            SlideOut.why(SlideOut.Entry(null, 16), 17, false, false, 0, hands("INVTYPE_WEAPON", "INVTYPE_SHIELD"), true, true))
        assertEquals("Unavailable", SlideOut.why(sw, 16, false, false, 3, w, routed = false, dualWield = true))
    }

    // char-popout2 (THE USER, 2026-11-16, picked "Allow it in fights": "the hand swap works in combat; if the game refuses, the pop-out
    // says so"): the two hands' swap is offered in a fight, as weapon Unequip is; rings and trinkets wait
    @Test fun theHandSwapIsAllowedInAFightRingsAndTrinketsAreNot() {
        val w = hands("INVTYPE_WEAPON", "INVTYPE_WEAPON")
        assertNull("off hand into the main hand in a fight", SlideOut.why(SlideOut.Entry(null, 17), 16, true, false, 3, w, true, true))
        assertNull("main hand into the off hand in a fight", SlideOut.why(SlideOut.Entry(null, 16), 17, true, false, 3, w, true, true))
        assertNull("a game window open changes nothing (no bag right-click)", SlideOut.why(SlideOut.Entry(null, 17), 16, true, true, 3, w, true, true))
        assertEquals("in a fight the displaced main-hand-only weapon still needs room", "Bags full",
            SlideOut.why(SlideOut.Entry(null, 17), 16, true, false, 0, hands("INVTYPE_WEAPONMAINHAND", "INVTYPE_WEAPON"), true, true))
        assertEquals("in a fight without the cells", "Unavailable", SlideOut.why(SlideOut.Entry(null, 17), 16, true, false, 3, w, false, true))
        val rt = mapOf(11 to wornRow(1, "INVTYPE_FINGER"), 12 to wornRow(2, "INVTYPE_FINGER"),
                       13 to wornRow(3, "INVTYPE_TRINKET"), 14 to wornRow(4, "INVTYPE_TRINKET"))
        assertEquals("rings", "In combat", SlideOut.why(SlideOut.Entry(null, 12), 11, true, false, 3, rt, true, false))
        assertEquals("trinkets", "In combat", SlideOut.why(SlideOut.Entry(null, 13), 14, true, false, 3, rt, true, false))
        assertNull("rings out of a fight", SlideOut.why(SlideOut.Entry(null, 12), 11, false, false, 3, rt, true, false))
        assertTrue(SlideOut.combatSwap(16, 17)); assertTrue(SlideOut.combatSwap(17, 16))
        assertFalse(SlideOut.combatSwap(18, 16)); assertFalse(SlideOut.combatSwap(11, 12)); assertFalse(SlideOut.combatSwap(16, 16))
        assertEquals("a refusal is one short line", "In combat", WornMore.say(false, "combat"))
        assertEquals("The game refused it", WornMore.say(false, "refused"))
        assertEquals("Bags full", WornMore.say(false, "full"))
    }

    @Test fun aSwapsCardIsTheOtherSlotsPiece() {
        val off = wornRow(17, "INVTYPE_WEAPON", JSONArray().put(stat("ITEM_MOD_AGILITY_SHORT", "Agility", 2))).put("quality", 2)
        val w = mapOf(16 to wornRow(16, "INVTYPE_WEAPON"), 17 to off)
        val c = SlideOut.card(SlideOut.Entry(null, 17), 16, null, w, true)
        assertEquals("Worn 17", c.title); assertEquals(2, c.quality)
        assertEquals("+2 Agility", c.lines.optJSONObject(0).optString("t"))
        val tip = JSONArray().put(JSONObject().put("t", "Fang")).put(JSONObject().put("t", "One-Hand"))
        val c2 = SlideOut.card(SlideOut.Entry(null, 17), 16, null, mapOf(16 to w[16]!!, 17 to JSONObject(off.toString()).put("tip", tip)), true)
        assertEquals("Fang", c2.title); assertTrue(c2.full); assertEquals("One-Hand", c2.lines.optJSONObject(0).optString("t"))
        val shield = wornRow(17, "INVTYPE_SHIELD", JSONArray().put(stat("RESISTANCE0_NAME", "Armor", 57)))
        val c3 = SlideOut.card(SlideOut.Entry(null, 16), 17, null, mapOf(16 to w[16]!!, 17 to shield), true)
        val all = (0 until c3.lines.length()).joinToString("|") { c3.lines.optJSONObject(it).optString("t") }
        assertTrue("the shield goes to the bags: its losses at the foot ($all)", all.contains("Armor"))
    }

    // ---------------------------------------------------------------- char-popout-current: the worn piece (the user, 2026-11-16: "the
    // cursor should be able to go back to the currently equipped item in the swap menu")

    @Test fun theWornPieceComesFirstThenTheSwapThenTheBagsThenTheBlank() {
        val e = SlideOut.entries(listOf(pick("INVTYPE_WEAPON", 20)), 16, worn = true, swaps = listOf(17), current = true)
        assertEquals(4, e.size)
        assertTrue("the worn piece first, beside the slot", e[0].current)
        assertFalse("it is not the blank", e[0].blank); assertFalse(e[0].swap); assertNull(e[0].pick)
        assertTrue(e[1].swap); assertFalse(e[2].current); assertTrue("the blank stays, last", e[3].blank)
        assertEquals("exactly one worn tile", 1, e.count { it.current })
    }

    @Test fun anEmptySlotHasNoWornTile() {
        assertTrue(SlideOut.entries(emptyList(), 5, worn = false, current = true).isEmpty())
        val e = SlideOut.entries(listOf(pick("INVTYPE_CHEST")), 5, worn = false, current = true)
        assertEquals(1, e.size); assertFalse(e[0].current)
    }

    @Test fun theWornAmmoIsItsTileNoBlank() {
        val e = SlideOut.entries(listOf(pick("INVTYPE_AMMO")), WornReplace.AMMO, worn = true, current = true)
        assertEquals(2, e.size); assertTrue(e[0].current); assertFalse(e[1].blank); assertFalse(e[1].current)
    }

    @Test fun theCursorCanComeBackToTheWornPiece() {
        val n = SlideOut.entries(listOf(pick("INVTYPE_HEAD", 18), pick("INVTYPE_HEAD", 9)), 1, worn = true, current = true).size
        assertEquals(4, n)
        var sel = 0
        sel = SlideOut.key(sel, n, SlideOut.dir(1), "PADDRIGHT").first; assertEquals(1, sel)
        sel = SlideOut.key(sel, n, SlideOut.dir(1), "PADDRIGHT").first; assertEquals(2, sel)
        sel = SlideOut.key(sel, n, SlideOut.dir(1), "PADDLEFT").first
        sel = SlideOut.key(sel, n, SlideOut.dir(1), "PADDLEFT").first
        assertEquals("back on the worn piece", 0, sel)
        assertEquals("the window shows it again", 0, SlideOut.first(sel, 6, 2))
    }

    @Test fun theWornPieceIsNeverGreyed() {
        val cur = SlideOut.Entry(null, current = true)
        assertNull("armour in a fight: keeping it is fine", SlideOut.why(cur, 5, true, false, 3, emptyMap(), routed = false))
        assertNull("a game window, bags full", SlideOut.why(cur, 16, false, true, 0, emptyMap(), routed = false))
        assertEquals("the blank in a fight still is", "In combat",
            SlideOut.why(SlideOut.Entry(null), 5, true, false, 3, emptyMap(), routed = true))
    }

    @Test fun theWornPiecesCardIsItsTooltipNoStatChanges() {
        val chest = wornRow(903, "INVTYPE_CHEST", arr(stat("RESISTANCE0_NAME", "Armor", 12), stat("ITEM_MOD_STAMINA_SHORT", "Stamina", 1)))
            .put("quality", 1)
        val short = SlideOut.card(SlideOut.Entry(null, current = true), 5, null, mapOf(5 to chest), false)
        assertEquals("Worn 903", short.title); assertEquals(1, short.quality)
        val t = (0 until short.lines.length()).map { short.lines.getJSONObject(it).optString("t") }
        assertEquals("its own stats, no stat changes", listOf("12 Armor", "+1 Stamina"), t)
        val tip = JSONArray().put(JSONObject().put("t", "Tattered Cloth Vest")).put(JSONObject().put("t", "Chest"))
            .put(JSONObject().put("t", "12 Armor"))
        val full = SlideOut.card(SlideOut.Entry(null, current = true), 5, null, mapOf(5 to JSONObject(chest.toString()).put("tip", tip)), false)
        assertEquals("Tattered Cloth Vest", full.title); assertTrue(full.full)
        val ft = (0 until full.lines.length()).map { full.lines.getJSONObject(it).optString("t") }
        assertEquals(listOf("Chest", "12 Armor"), ft)
        assertFalse("not the blank's card", full.title == WornMore.UNEQUIP)
        val ammo = JSONObject().put("id", 2512).put("name", "Rough Arrow").put("quality", 1).put("count", 200)
        assertEquals("the ammo slot's card is the worn ammo", "Rough Arrow",
            SlideOut.card(SlideOut.Entry(null, current = true), WornReplace.AMMO, null, emptyMap(), false, ammo = ammo).title)
    }
}
