package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ps3 (the user, 2026-11-11: "The polestar tracked quest went missing from my quests"): a Polestar step with no spot keeps its row */
class PolestarStepTest {
    @Test fun aStepWithAWaypointKeepsItsLocation() {
        val st = PolestarStep.parse(JSONObject("""{"type":"pstep","v":"ps3","t":"Kill wolves","g":"Wolf pelts","q":33,"m":1429,"x":0.4,"y":0.6}"""))!!
        assertTrue(st.hasLoc)
        assertEquals(1429, st.map); assertEquals(0.4, st.x, 1e-9); assertEquals(0.6, st.y, 1e-9); assertEquals(33, st.quest)
    }

    @Test fun aStepWithNoLocationIsStillAStepWithNoGoalPoint() {
        val st = PolestarStep.parse(JSONObject("""{"type":"pstep","v":"ps3","t":"Train at Kandris","g":"Talk to Kandris Dreamseeker","k":"talk","q":77,"nl":true}"""))
        assertNotNull("the row's step is kept", st)
        st!!
        assertFalse("no goal point for the router", st.hasLoc)
        assertEquals(0, st.map); assertTrue(st.x.isNaN() && st.y.isNaN())
        assertEquals("Train at Kandris", st.title); assertEquals("Talk to Kandris Dreamseeker", st.words); assertEquals(77, st.quest)
        // the light blue row is in the list
        val g = MapList.groups(listOf(MapList.Entry(10, "A", 5, false)), 5, emptyList(), emptyList())
        val items = MapList.items(true, listOf(77), g, polestar = st != null) { false }
        assertEquals(listOf(MapList.Item.Me, MapList.Item.Polestar, MapList.Item.Tracked(77)), items.take(3))
        // the game's focus still follows the step's quest
        val s = PolestarFocusSync()
        assertEquals(PolestarFocusSync.Act.None, s.tick(true, st.quest, 5, 0))
        assertEquals(PolestarFocusSync.Act.Focus(77), s.tick(true, st.quest, 5, PolestarFocusSync.SETTLE_MS))
    }

    @Test fun aStepWithNoLocationAndNoQuest() {
        val st = PolestarStep.parse(JSONObject("""{"type":"pstep","t":"Use the hearthstone","nl":true}"""))!!
        assertFalse(st.hasLoc); assertEquals(0, st.quest); assertNull(st.words)
    }

    @Test fun noneIsNoStep() {
        assertNull(PolestarStep.parse(JSONObject("""{"type":"pstep","v":"ps3","none":true}""")))
        assertNull("an empty doc", PolestarStep.parse(JSONObject("""{"type":"pstep"}""")))
    }

    // ps4 (the user, 2026-11-11: "My current polestar objective does not give any direction on where to go")
    @Test fun anItemStartedQuestCarriesItsItem() {
        val st = PolestarStep.parse(JSONObject("""{"type":"pstep","v":"ps4","t":"Vayn Moongaze","g":"Accept Ripped Missive","k":"accept","q":8460,"nl":true,"it":20310,"ih":true,"ic":134939,"itn":"Ripped Missive"}"""))!!
        assertTrue(st.useItem)
        assertEquals(20310, st.itemId); assertEquals(134939, st.itemIcon); assertEquals("Use Ripped Missive", st.useLine())
        val notHeld = PolestarStep.parse(JSONObject("""{"type":"pstep","t":"Vayn Moongaze","g":"Accept Ripped Missive","nl":true,"it":20310,"ih":false}"""))!!
        assertFalse("not in the bags: the step as today", notHeld.useItem)
        assertTrue(st.sig() != notHeld.sig())
        val old = PolestarStep.parse(JSONObject("""{"type":"pstep","v":"ps3","t":"Talk","nl":true}"""))!!
        assertFalse(old.useItem); assertEquals(0, old.itemId); assertNull(old.itemName)
        assertEquals("Use the item", PolestarStep("t", null, "accept", 1, 0, Double.NaN, Double.NaN, itemId = 5, itemHeld = true).useLine())
    }

    @Test fun theFallbackGuidance() {
        val inLog = setOf(77)
        // no point, its quest in the log: that quest's areas, as a focused quest
        val noLoc = PolestarStep.parse(JSONObject("""{"type":"pstep","t":"Train","g":"Talk","q":77,"nl":true}"""))
        val g = PolestarStep.guide(noLoc) { it in inLog }
        assertTrue(g is PolestarStep.Guide.Quest); assertEquals(77, (g as PolestarStep.Guide.Quest).q)
        // a point: Polestar's point wins, its quest in the log or not
        val pt = PolestarStep.guide(PolestarStep.parse(JSONObject("""{"type":"pstep","t":"Wolves","q":77,"m":1429,"x":0.4,"y":0.6}"""))) { it in inLog }
        assertTrue(pt is PolestarStep.Guide.Point); pt as PolestarStep.Guide.Point
        assertEquals(1429, pt.map); assertEquals(0.4, pt.x, 1e-9); assertEquals(0.6, pt.y, 1e-9)
        // no point, no quest in the log (an accept, an item to use): nothing
        assertEquals(PolestarStep.Guide.None, PolestarStep.guide(PolestarStep.parse(JSONObject("""{"type":"pstep","t":"Accept","q":8460,"nl":true}"""))) { it in inLog })
        assertEquals(PolestarStep.Guide.None, PolestarStep.guide(PolestarStep.parse(JSONObject("""{"type":"pstep","t":"Hearth","nl":true}"""))) { it in inLog })
        assertEquals(PolestarStep.Guide.None, PolestarStep.guide(null) { true })
    }

    @Test fun thePickIsToldOnChangeAndAgainWhilePicked() {
        val t = PolestarPickTell()
        assertEquals(listOf(38, 9), t.tick(false, 0))
        assertEquals(emptyList<Int>(), t.tick(false, 100_000))
        assertEquals(listOf(38, 8), t.tick(true, 100_001))
        assertEquals(emptyList<Int>(), t.tick(true, 100_001 + PolestarPickTell.AGAIN_MS - 1))
        assertEquals(listOf(38, 8), t.tick(true, 100_001 + PolestarPickTell.AGAIN_MS))
        assertEquals(listOf(38, 9), t.tick(false, 200_000))
        t.lost()
        assertEquals("a lost batch is said again", listOf(38, 9), t.tick(false, 200_001))
    }
}
