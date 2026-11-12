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
}
