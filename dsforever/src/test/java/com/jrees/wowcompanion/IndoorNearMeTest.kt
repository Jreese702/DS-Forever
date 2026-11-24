package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Fix 6 (2026-11-23): "build what's near me first" never had a position. The step read the add-on's `inst` as the continent, but the
 * add-on (Map.lua, the pos document) sends `inst` as IsInInstance's BOOLEAN and the map id as `cont` (UnitPosition's fourth value), so
 * org.json's optInt answered -1 and nothing was kept (nor the motion fed). Reflection only: this file runs on the code before the fix.
 */
class IndoorNearMeTest {
    private fun live(): Any? = IndoorSchedulerStep::class.java.getDeclaredField("live").apply { isAccessible = true }.get(IndoorSchedulerStep)

    @Test fun nearMeFirstReadsTheContinentTheAddonSends() {
        // the add-on's pos document (Map.lua): inst is IsInInstance's BOOLEAN, the map id is cont (UnitPosition's fourth value)
        val doc = JSONObject().put("inst", false).put("cont", 0).put("wx", -4981.2).put("wy", -882.7).put("indoors", true)
        IndoorSchedulerStep.notePosition(doc)
        assertEquals(IndoorBakeQueue.Position(0, -4981.2, -882.7), live())
        // in an instance UnitPosition answers nothing: no position, the last one stands
        IndoorSchedulerStep.notePosition(JSONObject().put("inst", true).put("indoors", true))
        assertEquals(IndoorBakeQueue.Position(0, -4981.2, -882.7), live())
        IndoorSchedulerStep.notePosition(JSONObject().put("inst", false).put("cont", 1).put("wx", 1600.0).put("wy", -4400.0))
        assertEquals(IndoorBakeQueue.Position(1, 1600.0, -4400.0), live())
    }

}
