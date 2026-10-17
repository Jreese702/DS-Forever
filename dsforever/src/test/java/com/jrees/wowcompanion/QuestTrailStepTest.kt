package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** b-qtrail-focus: QuestTrail's compass label names the quest of the log it points at, and its objective's words */
class QuestTrailStepTest {
    private val log = mapOf(101 to "The Fargodeep Mine", 102 to "Kobold Candles", 103 to "Kobold Candles Part 2", 104 to "Wolves Across the Border")

    @Test fun anObjectiveStepNamesItsQuestAndWords() {
        val s = QuestTrailBridge.step("Objective\nKobold Candles – Large Candle: 3/8", log)!!
        assertEquals(102, s.questId); assertEquals("Objective", s.kind); assertEquals("Large Candle: 3/8", s.objective)
        assertEquals("Large Candle: 3/8", s.words)
    }

    @Test fun theLongestTitleWinsAndAPrefixAloneDoesNot() {
        assertEquals(103, QuestTrailBridge.step("Objective\nKobold Candles Part 2 – Wax", log)!!.questId)
        // "Kobold Candles" + " Part 3": not a title of the log
        assertNull(QuestTrailBridge.step("Objective\nKobold Candles Part 3 – Wax", log))
    }

    @Test fun aTurnInHasNoObjectiveAndSaysWhatToDo() {
        val s = QuestTrailBridge.step("Fly to Stormwind, then Turn in\nWolves Across the Border (estimate)", log)!!
        assertEquals(104, s.questId); assertNull(s.objective); assertEquals("Fly to Stormwind, then Turn in", s.words)
    }

    @Test fun travelAndOtherLabelsNameNoQuest() {
        for (l in listOf("Flying to Stormwind", "On the way to Menethil Harbor", "Your corpse", "Recomputing path", "QuestTrail: computing…",
                "Take\nA Quest Not In The Log", "", null))
            assertNull(l, QuestTrailBridge.step(l, log))
    }

    @Test fun twoQuestsOfOneTitleAreToldApartByTheirObjectives() {
        val two = mapOf(7 to "Collecting Kelp", 9 to "Collecting Kelp")
        val objs = mapOf(7 to listOf("Crystal Kelp Frond: 2/10"), 9 to listOf("Murloc Fin: 0/5"))
        assertEquals(9, QuestTrailBridge.step("Objective\nCollecting Kelp – Murloc Fin", two) { objs[it] ?: emptyList() }!!.questId)
    }
}
