package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ironforge (the user, 2026-11-23: "I cant see iron forges map at all when walking around in it"): the capitals' indoor art by position
 * (IndoorCityArt, the pick D2 "Game's art now"), the built room picked by its own turned box (IndoorPick, fix 3) and the note when there
 * is no indoor map at all (HomeMapNote.INDOOR, fix 4). Pure: the MapView side is HomeIndoorMapsTest (app).
 */
class IndoorCityArtTest {
    private val IF = IndoorCityArt.byRoot(7801267)!!
    /** a point on Ironforge's ring (the PC bake's Commons, world yards) */
    private val RING = doubleArrayOf(-4936.0, -930.0)

    private fun world(c: IndoorCityArt.City, lx: Double, ly: Double): DoubleArray {
        val t = c.l2w
        return doubleArrayOf(t[0] - lx * t[2] + ly * t[3], t[1] - lx * t[3] - ly * t[2])
    }

    @Test fun theFourCapitalsAndTheirFootprints() {
        assertEquals(setOf(7962574, 7801267, 7675285, 7964229), IndoorCityArt.CAPITALS.map { it.root }.toSet())
        assertEquals("the same roots the builder's city rule and the art index use", NativeIndoorGenerator.CITY_ROOTS,
            IndoorCityArt.CAPITALS.map { it.root }.toSet())
        assertEquals("their maps are the Map room's capitals", IndoorCityArt.CAPITALS.map { it.uiMap }.toSet(),
            IndoorCityArt.CAPITALS.map { it.uiMap }.filter { it in CityExits.CITIES }.toSet())
        assertEquals("Ironforge", IndoorCityArt.cityAt(0, RING[0], RING[1])?.name)
        assertNull("another continent", IndoorCityArt.cityAt(1, RING[0], RING[1]))
        assertNull("Dun Morogh outside the city", IndoorCityArt.cityAt(0, -5600.0, -500.0))
        assertEquals("Orgrimmar", IndoorCityArt.cityAt(1, 1630.0, -4400.0)?.name)
        assertEquals("Undercity", IndoorCityArt.cityAt(0, 1590.0, 240.0)?.name)
        assertEquals("Stormwind", IndoorCityArt.cityAt(0, -8830.0, 620.0)?.name)
        // the art set's own painted rect wins when it has one
        assertNull(IndoorCityArt.cityAt(0, RING[0], RING[1]) { doubleArrayOf(0.0, -1.0, 0.0, -1.0) })
    }

    @Test fun theRootsOwnPlacementTurnsTheLocalBoxes() {
        // Ironforge's root is turned about 50 degrees: local -> world -> local is the identity through MapView's Place formulas
        for ((lx, ly) in listOf(0.0 to 0.0, 120.0 to -40.0, -300.0 to 210.0)) {
            val w = world(IF, lx, ly)
            assertEquals(lx, IF.localX(w[0], w[1]), 1e-6); assertEquals(ly, IF.localY(w[0], w[1]), 1e-6)
        }
        val a = Math.toDegrees(Math.atan2(IF.l2w[3], IF.l2w[2]))
        assertTrue("turned ($a)", Math.abs(Math.abs(a) - 50.5) < 2.0)
    }

    @Test fun theTilesAreFullSizeUnlessHalfLosesNothing() {
        // review of 2026-11-23: the open map (~5 px a yard) drew 13 - 17 tiles at half size, upscaled about 5 x
        assertFalse("the open map at ~5 px a yard, 17 tiles in view", IndoorCityArt.halfSize(17, 5.1))
        assertFalse("Home's column", IndoorCityArt.halfSize(6, 9.0))
        assertFalse("a tile's own resolution", IndoorCityArt.halfSize(20, 2.0))
        assertTrue("zoomed out to a pixel a yard: half size loses nothing", IndoorCityArt.halfSize(20, 1.0))
        assertTrue("very many in view (memory)", IndoorCityArt.halfSize(IndoorCityArt.HALF_ABOVE + 1, 5.0))
    }

    @Test fun theIndoorsGateHasAPerCitySwitch() {
        assertTrue(IndoorCityArt.shows(IF, indoors = true))
        // the device read of 2026-11-24 (test 1524, on Ironforge's ring: "indoors": false, mapID 1455) and the user's pick "Ironforge +
        // Undercity": the two enclosed capitals you can mount in show their art by position; Stormwind and Orgrimmar keep the gate
        val byName = IndoorCityArt.CAPITALS.associateBy { it.name }
        assertEquals("Ironforge and the Undercity by position, Stormwind and Orgrimmar indoors only",
            mapOf("Stormwind" to false, "Ironforge" to true, "Undercity" to true, "Orgrimmar" to false), byName.mapValues { it.value.positionOnly })
        assertTrue("Ironforge's ring says outdoors: its art still shows", IndoorCityArt.shows(IF, indoors = false))
        assertFalse("Stormwind's streets really are outside", IndoorCityArt.shows(byName.getValue("Stormwind"), indoors = false))
        assertFalse(IndoorCityArt.shows(byName.getValue("Orgrimmar"), indoors = false))
    }

    @Test fun ironforgeAndTheUndercityByPositionOnTheirOwnMapOnly() {
        val ring = doubleArrayOf(-4936.0, -930.0)
        assertEquals("Ironforge", IndoorCityArt.byPosition(1455, 0, ring[0], ring[1])?.name)
        assertNull("the gates: Dun Morogh's map", IndoorCityArt.byPosition(1426, 0, ring[0], ring[1]))
        assertNull("on a taxi", IndoorCityArt.byPosition(1455, 0, ring[0], ring[1], onTaxi = true))
        assertNull("another continent", IndoorCityArt.byPosition(1455, 1, ring[0], ring[1]))
        assertNull("out of the footprint", IndoorCityArt.byPosition(1455, 0, -5600.0, -500.0))
        assertEquals("Undercity", IndoorCityArt.byPosition(1458, 0, 1590.0, 240.0)?.name)
        assertNull("the Ruins of Lordaeron above it: Tirisfal's map", IndoorCityArt.byPosition(1420, 0, 1590.0, 240.0))
        assertNull("Stormwind keeps the indoors gate", IndoorCityArt.byPosition(1453, 0, -8830.0, 620.0))
        assertNull("Orgrimmar too", IndoorCityArt.byPosition(1454, 1, 1630.0, -4400.0))
        assertTrue(IndoorCityArt.indoorsHere(false, 1455, 0, ring[0], ring[1]))
        assertTrue("the game's own flag always counts", IndoorCityArt.indoorsHere(true, 1426, 0, ring[0], ring[1]))
        assertFalse(IndoorCityArt.indoorsHere(false, 1420, 0, 1590.0, 240.0))
        val uc = IndoorCityArt.byRoot(7675285)!!
        assertTrue(IndoorCityArt.shows(uc, indoors = false, mapId = 1458))
        assertFalse("never the city's art on the Ruins", IndoorCityArt.shows(uc, indoors = false, mapId = 1420))
        // review of 1525: the art's gate is the same test as [byPosition]: never on a taxi (the game's own indoors still counts)
        assertFalse("a gryphon out of the Great Forge, still on Ironforge's map", IndoorCityArt.shows(IF, indoors = false, mapId = 1455, onTaxi = true))
        assertTrue(IndoorCityArt.shows(IF, indoors = true, mapId = 1455, onTaxi = true))
    }

    // the groups: a 90 yd tall hall, a shop on its floor, a shop above it, a cavern under the hall, a ramp down beside it
    private val hall = floatArrayOf(0f, 0f, 0f, 100f, 100f, 90f)
    private val shop = floatArrayOf(10f, 10f, 0.5f, 20f, 20f, 8f)
    private val upper = floatArrayOf(30f, 30f, 20f, 40f, 40f, 28f)
    private val cavern = floatArrayOf(45f, 45f, -30f, 70f, 70f, -10f)
    private val ramp = floatArrayOf(100f, 0f, -30f, 130f, 30f, 0f)
    private val boxes = listOf(hall, shop, upper, cavern, ramp)

    @Test fun theHallsFloorIsDrawnWhereTheFloorIsKnown() {
        val t = IndoorCityArt.Tracker()
        // walked out of a built shop on the hall's floor (its tracker said z = 1): the hall, sure; the shop on the same floor with it
        val p = t.pick(7801267, boxes, 50.0, 50.0, 1f)!!
        assertEquals(0, p.own); assertTrue(p.sure)
        assertEquals("the hall's floor: the hall and the shop on it, not the shop upstairs, the cavern or the ramp under it", setOf(0, 1), p.groups.toSet())
        assertEquals("the player's own group first", 0, p.groups.first())
        // the floor is no longer said (the hall has no built room): walking on keeps to the hall
        val q = t.pick(7801267, boxes, 35.0, 35.0, Float.NaN)!!
        assertEquals("under the upper shop the hall stays (the same floor)", 0, q.own)
        assertTrue(q.sure)
        assertFalse("the shop upstairs is not drawn", 2 in q.groups)
    }

    @Test fun noFloorKnownTakesTheMostSpecificGroupUnsureWhileAnotherIsPossible() {
        val t = IndoorCityArt.Tracker()
        val p = t.pick(7801267, boxes, 50.0, 50.0, Float.NaN)!!
        assertEquals("the cavern's box nested in the hall's, no height: the most specific group, its own floor", 3, p.own)
        assertFalse("the hall is another possible floor here: unsure (drawn at 55 %)", p.sure)
        assertEquals(-29.0f, p.floor, 0.01f)
        // walking on into ground the hall alone covers: no question any more
        val q = t.pick(7801267, boxes, 80.0, 80.0, Float.NaN)!!
        assertEquals(0, q.own); assertTrue(q.sure)
        assertNull("between the groups (out in Dun Morogh's air): nothing", t.pick(7801267, boxes, 500.0, 500.0, Float.NaN))
    }

    @Test fun walkingDownARampTakesTheFloorWithIt() {
        val t = IndoorCityArt.Tracker()
        t.pick(7801267, boxes, 50.0, 50.0, 1f)
        val p = t.pick(7801267, boxes, 115.0, 15.0, Float.NaN)!!
        assertEquals(4, p.own)
        assertTrue("the floor stays in reach of the ramp (${p.floor})", p.floor <= 0f && p.floor >= -30f)
    }

    @Test fun theUndercitysLiftsAndItsThroneRoom() {
        // the Undercity's shape (its groups measured on the PC client: walkways at -106, the quarters' 130 yd boxes down to the canal
        // bed at -130, the Ruins' throne room far above): the throne room is its own floor, the lift's foot the city's walkways
        val top = floatArrayOf(10f, 10f, 0f, 40f, 40f, 30f)
        val quarter = floatArrayOf(0f, 0f, -130f, 130f, 130f, -11f)
        val walk1 = floatArrayOf(50f, 50f, -106f, 80f, 80f, -91f)
        val walk2 = floatArrayOf(80f, 80f, -106f, 110f, 110f, -91f)
        val walk3 = floatArrayOf(20f, 90f, -106f, 50f, 120f, -91f)
        val shop = floatArrayOf(60f, 10f, -105f, 75f, 25f, -95f)
        val uc = listOf(top, quarter, walk1, walk2, walk3, shop)
        val t = IndoorCityArt.Tracker()
        val p = t.pick(7675285, uc, 20.0, 20.0, Float.NaN)!!
        assertEquals("in the throne room: its own floor, not the city under it", 0, p.own)
        assertEquals(1f, p.floor, 0.01f)
        assertFalse("the quarter's box under it is another possible floor", p.sure)
        // down the lift onto a walkway: the city's usual floor (the median of the bottoms), every walkway drawn, the throne room not
        val q = t.pick(7675285, uc, 60.0, 60.0, Float.NaN)!!
        assertEquals(2, q.own)
        assertEquals(-105f, q.floor, 0.01f)
        assertEquals(setOf(1, 2, 3, 4, 5), q.groups.toSet())
        assertTrue(q.sure)
    }

    @Test fun oneFloorUnderThePlayerIsSureAtOnce() {
        val t = IndoorCityArt.Tracker()
        val p = t.pick(7801267, boxes, 15.0, 15.0, Float.NaN)!!
        assertEquals("the hall and the shop share a floor: the smaller (the shop) is the player's", 1, p.own)
        assertTrue(p.sure)
    }

    @Test fun anotherCityStartsOver() {
        val t = IndoorCityArt.Tracker()
        t.pick(7801267, boxes, 50.0, 50.0, -20f)
        val p = t.pick(7675285, boxes, 50.0, 50.0, Float.NaN)!!
        assertEquals("no floor carried from Ironforge into the Undercity", -29.0f, p.floor, 0.01f)
        assertFalse(p.sure)
    }

    // ------------------------------------------------------------------ fix 3: the built room by its own turned box

    @Test fun aTurnedShopsWorldBoxNoLongerClaimsTheHallBesideIt() {
        // a 20 x 12 yd shop of Ironforge in the root's own space: its world box (axis-aligned, the city turned ~50 degrees) is much
        // bigger than the shop; a hall point inside that box but outside the shop's own rect is NOT in the shop
        val rect = doubleArrayOf(100.0, 120.0, 40.0, 52.0)
        val corners = listOf(100.0 to 40.0, 120.0 to 40.0, 120.0 to 52.0, 100.0 to 52.0).map { (x, y) -> world(IF, x, y) }
        val x0 = corners.minOf { it[0] }; val x1 = corners.maxOf { it[0] }; val y0 = corners.minOf { it[1] }; val y1 = corners.maxOf { it[1] }
        // a world point in the box's corner: inside the world box, outside the shop
        val w = doubleArrayOf(x0 + 1.0, y0 + 1.0)
        assertTrue(w[0] in x0..x1 && w[1] in y0..y1)
        assertFalse("outside the shop's own rect", IndoorPick.insideLocal(listOf(rect), IF.localX(w[0], w[1]), IF.localY(w[0], w[1])))
        val mid = world(IF, 110.0, 46.0)
        assertTrue(IndoorPick.insideLocal(listOf(rect), IF.localX(mid[0], mid[1]), IF.localY(mid[0], mid[1])))
    }

    @Test fun aRoomWithNoFloorNearIsPassedOverAndNoneMeansNoPlan() {
        val shopHere = IndoorPick.Candidate("7801267_d0_shop", 240.0, local = true, walk = false)
        val bigger = IndoorPick.Candidate("7801267_d0_district", 9000.0, local = true, walk = true)
        assertEquals("the shop's box holds the point but it has no floor there: the bigger room that has", bigger.id,
            IndoorPick.choose(listOf(shopHere, bigger))?.id)
        assertNull("no room with floor here: no plan (the note, the city's art)", IndoorPick.choose(listOf(shopHere)))
        assertNull(IndoorPick.choose(listOf(IndoorPick.Candidate("x", 1.0, local = false, walk = true))))
        val unread = IndoorPick.Candidate("7801267_d0_shop2", 100.0, local = true, walk = null)
        assertEquals("a height field not read yet: the smallest room meanwhile", unread.id, IndoorPick.choose(listOf(unread, bigger))?.id)
    }

    @Test fun floorNearAWallStillCounts() {
        // the walk is eroded beside the walls: a point 2 yd off the walkable cells is still in the room, 5 yd off is not
        val layers = { x: Double, _: Double -> if (x <= 0.0) 1 else 0 }
        assertTrue(IndoorPick.walkNear(0.5, 2.0, 0.0, layersAt = layers))
        assertFalse(IndoorPick.walkNear(0.5, 5.0, 0.0, layersAt = layers))
        assertTrue(IndoorPick.walkNear(0.5, -1.0, 0.0, layersAt = layers))
    }

    // ------------------------------------------------------------------ fix 4: the note

    @Test fun theNoteSaysThereIsNoIndoorMapHere() {
        assertEquals("No indoor map here yet", HomeMapNote.INDOOR)
        assertTrue(HomeMapNote.indoorShows(morphing = false, indoors = true, room = false, cityArt = false, otherNote = false, ready = true))
        assertFalse("outdoors", HomeMapNote.indoorShows(false, false, false, false, false, true))
        assertFalse("a built room", HomeMapNote.indoorShows(false, true, true, false, false, true))
        assertFalse("the game's art of a capital", HomeMapNote.indoorShows(false, true, false, true, false, true))
        assertFalse("the map's pictures note first", HomeMapNote.indoorShows(false, true, false, false, true, true))
        assertFalse("the rooms still being read", HomeMapNote.indoorShows(false, true, false, false, false, false))
        assertFalse("Home's glide", HomeMapNote.indoorShows(true, true, false, false, false, true))
        for (lang in listOf("es", "fr")) {
            val f = java.io.File("../companion/app/src/main/assets/companion/i18n/$lang.json")
            val o = org.json.JSONObject(f.readText())
            assertNotNull("$lang: the note", o.getJSONObject("plain").optString(HomeMapNote.INDOOR, null))
            assertNotNull("$lang: the finished line", o.getJSONObject("templates").optString("Finished: %s maps, %s places can't be mapped", null))
            assertNotNull("$lang: the finished row", o.getJSONObject("templates").optString("Indoor maps finished: %s maps, %s places can't be mapped", null))
            assertNotNull("$lang: its terse name", o.getJSONObject("plain").optString(SetupFlow.INDOOR_DONE, null))
            assertTrue("$lang keeps CRLF", f.readBytes().let { b -> String(b, Charsets.UTF_8).contains("\r\n") })
        }
    }
}
