package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** fix/desk-click-switch (the user, 2026-11-19: Polestar on the PC "is still not on my step"): the stream starts on the player's step */
class PolestarHandoffTest {
    private fun guide(gs: Int, s: Int?, l: Int?, tabs: JSONArray? = null, have: Boolean = true, none: Boolean = false): PolestarTab.Guide {
        val d = JSONObject().put("type", "pguide").put("have", have).put("none", none).put("gid", "g$gs").put("gs", gs).put("t", "A guide").put("n", 40)
        if (s != null) d.put("s", s)
        if (l != null) d.put("l", l)
        if (tabs != null) d.put("tabs", tabs)
        return PolestarTab.Guide.parse(d)!!
    }
    private val saved = PolestarHandoff.Saved(500, "g500", 17, 17, 1_000L)

    @Test fun theSameGuideGoesBackToTheLiveStepOrLooksAtTheSavedOne() {
        val h = PolestarHandoff()
        assertEquals(PolestarHandoff.Act.Back(500), h.onGuide(guide(500, s = 9, l = 17), saved, fight = false))
        assertTrue(h.finished)
        assertEquals("once a session", PolestarHandoff.Act.None, h.onGuide(guide(500, s = 9, l = 17), saved, false))
        val h2 = PolestarHandoff()
        assertEquals(PolestarHandoff.Act.Look(17, 500), h2.onGuide(guide(500, s = 9, l = 9), saved, false))
        val h3 = PolestarHandoff()
        assertTrue(h3.onGuide(guide(500, s = 17, l = 17), saved, false) is PolestarHandoff.Act.Done)
    }

    @Test fun anotherGuideOpensTheMenuThenContinuesTheSavedTabThenLooks() {
        val h = PolestarHandoff()
        assertEquals(PolestarHandoff.Act.Open, h.onGuide(guide(700, s = 3, l = 3), saved, false))
        assertEquals("waits for the tabs", PolestarHandoff.Act.None, h.onGuide(guide(700, s = 3, l = 3), saved, false))
        val tabs = JSONArray().put(JSONObject().put("t", "Other").put("s", 700)).put(JSONObject().put("t", "Ours").put("s", 500))
        assertEquals(PolestarHandoff.Act.Continue(2, 500), h.onGuide(guide(700, s = 3, l = 3, tabs), saved, false))
        assertEquals(PolestarHandoff.Act.None, h.onGuide(guide(700, s = 3, l = 3, tabs), saved, false))
        assertEquals(PolestarHandoff.Act.Look(17, 500), h.onGuide(guide(500, s = 12, l = 12), saved, false))
        // a tab that isn't there: left to the player
        val h2 = PolestarHandoff()
        h2.onGuide(guide(700, s = 3, l = 3), saved, false)
        assertTrue(h2.onGuide(guide(700, s = 3, l = 3, JSONArray().put(JSONObject().put("t", "Other").put("s", 700))), saved, false) is PolestarHandoff.Act.Done)
    }

    @Test fun nothingInAFightNothingKeptOrThePlayersOwnPressFirst() {
        val h = PolestarHandoff()
        assertEquals(PolestarHandoff.Act.None, h.onGuide(guide(500, s = 9, l = 9), saved, fight = true))
        assertEquals(PolestarHandoff.Act.None, h.onGuide(guide(500, s = null, l = null, have = false), saved, false))
        h.playerPressed()
        assertEquals(PolestarHandoff.Act.None, h.onGuide(guide(500, s = 9, l = 9), saved, false))
        val h2 = PolestarHandoff()
        assertTrue(h2.onGuide(guide(500, s = 9, l = 9), null, false) is PolestarHandoff.Act.Done)
        h2.reset()
        assertEquals("a new session: again", PolestarHandoff.Act.Look(17, 500), h2.onGuide(guide(500, s = 9, l = 9), saved, false))
    }

    @Test fun theSavedStateRoundTrips() {
        val s = PolestarHandoff.Saved.of(guide(500, s = 17, l = 18), 42L)!!
        assertEquals(PolestarHandoff.Saved(500, "g500", 17, 18, 42L), PolestarHandoff.Saved.parse(s.json().toString()))
        assertEquals(null, PolestarHandoff.Saved.of(guide(500, s = null, l = null), 1L))
        assertEquals(null, PolestarHandoff.Saved.parse("nope"))
    }
}
