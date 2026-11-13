package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The spell radial WITHOUT the add-on's cooldown sweep (Radial.lua q1b, the user's pick 2026-11-13): the add-on says a cooldown once
 * (a list on an event, or `sradialcd` after a cast) and the app counts it down itself (RadialModel.Cd). No `cd` = no countdown, never a
 * guess. And the loot test's report line (BlockTally).
 */
class RadialCooldownTest {
    @After fun clean() { RadialModel.Cd.reset(); BlockTally.reset() }

    private fun cast(id: Int, d: Double?, l: Double?, fight: Boolean = false) = JSONObject().apply {
        put("type", "sradialcd"); put("v", "q1b"); put("id", id)
        if (d != null && l != null) put("cd", JSONObject().put("d", d).put("l", l))
        if (fight) put("fight", true)
    }

    @Test fun aCastsCooldownCountsDownOnTheAppsClock() {
        assertTrue(RadialModel.Cd.onCast(cast(403, 6.0, 5.5), now = 1_000L))
        assertEquals(5.5, RadialModel.Cd.left(403, now = 1_000L), 1e-6)
        assertEquals(6.0, RadialModel.Cd.len(403, now = 1_000L), 1e-6)
        assertEquals(3.5, RadialModel.Cd.left(403, now = 3_000L), 1e-6)
        assertEquals(0.0, RadialModel.Cd.left(403, now = 6_600L), 1e-6)       // run out: ready, and forgotten
        assertEquals(0.0, RadialModel.Cd.len(403, now = 6_600L), 1e-6)
    }

    @Test fun aCastWithNoSafeValueShowsNoCountdown() {
        RadialModel.Cd.onCast(cast(331, 30.0, 20.0), now = 0L)
        // the next cast's read was secret (a fight): no cd -> nothing guessed, the old one goes
        assertTrue(RadialModel.Cd.onCast(cast(331, null, null, fight = true), now = 1_000L))
        assertEquals(0.0, RadialModel.Cd.left(331, now = 1_000L), 1e-6)
    }

    @Test fun otherDocumentsAreNotCasts() {
        assertFalse(RadialModel.Cd.onCast(JSONObject().put("type", "sradialuse").put("id", 403)))
        assertFalse(RadialModel.Cd.onCast(JSONObject().put("type", "sradialcd").put("id", 0)))
    }

    @Test fun aListSetsAndClearsOutOfAFightOnly() {
        val list = JSONObject("""{"type":"sradial","v":"q1b","r":{"items":[
            {"s":1,"id":403,"cd":{"d":10,"l":4}},{"s":2,"id":331}]}}""")
        RadialModel.Cd.note(331, 8.0, 8.0, now = 0L)
        RadialModel.Cd.onList(list, now = 0L)
        assertEquals(4.0, RadialModel.Cd.left(403, now = 0L), 1e-6)
        assertEquals(0.0, RadialModel.Cd.left(331, now = 0L), 1e-6)            // out of a fight, no cd = ready
        // in a fight a missing cd is "not known", not "ready": what was counted stays
        RadialModel.Cd.note(331, 8.0, 8.0, now = 0L)
        RadialModel.Cd.onList(JSONObject("""{"type":"sradial","r":{"fight":true,"items":[{"s":2,"id":331}]}}"""), now = 0L)
        assertEquals(8.0, RadialModel.Cd.left(331, now = 0L), 1e-6)
    }

    @Test fun theSeveralRadialsListShapeToo() {
        RadialModel.Cd.onList(JSONObject("""{"type":"sradial","v":"q4","r":{"list":[{"u":1,"items":[{"id":324,"cd":{"d":12,"l":12}}]}]}}"""), now = 0L)
        assertEquals(12.0, RadialModel.Cd.left(324, now = 0L), 1e-6)
    }

    @Test fun theReportLineCountsForbiddenAndBlocked() {
        assertEquals("Blocked actions this run: forbidden 0, blocked 0", BlockTally.line())
        BlockTally.note(JSONObject("""{"ev":"forbidden","who":"WowCompanionSpells","fn":"SetPreferredGamepadInteractTarget()","n":1}"""))
        BlockTally.note(JSONObject("""{"ev":"blocked","who":"WowCompanionSocial","fn":"GetMOTD()","n":2}"""))
        BlockTally.note(JSONObject("""{"ev":"other","n":3}"""))
        assertEquals(1, BlockTally.forbidden)
        assertEquals(1, BlockTally.blocked)
        assertEquals("Blocked actions this run: forbidden 1, blocked 1 (last: WowCompanionSocial blocked GetMOTD())", BlockTally.line())
        BlockTally.note(JSONObject("""{"ev":"forbidden","who":"WowCompanionSpells","fn":"X()","n":20}"""))
        assertTrue(BlockTally.line().contains("maybe more"))
    }
}
