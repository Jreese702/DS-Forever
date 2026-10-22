package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** test70: the route's log line names where the goal came from (a done quest's turn-in, the game's or QuestieDB's, or the objective) */
class GoalSourceTest {
    @Test fun aDoneQuestsGoalIsItsTurnInAndTheLineSaysWhose() {
        val mqDone = JSONObject().put("done", true).put("x", 0.5).put("y", 0.5).put("tx", 0.6965).put("ty", 0.6707)
        assertEquals("turn-in (the game's)", CursorRoute.goalSource(mqDone, null))
        val eReady = JSONObject().put("ready", true).put("qdb", JSONObject().put("turnin", JSONArray().put(JSONObject().put("m", 2521).put("x", 0.7).put("y", 0.67))))
        assertEquals("turn-in (QuestieDB's ender)", CursorRoute.goalSource(null, eReady))
        assertEquals("turn-in (no spot: the quest's point)", CursorRoute.goalSource(JSONObject().put("done", true), null))
        assertEquals("objective (the game's)", CursorRoute.goalSource(JSONObject().put("x", 0.4).put("y", 0.4), null))
        assertEquals("objective (QuestieDB)", CursorRoute.goalSource(null, JSONObject()))
    }
}
