package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Quests q65 (the user, 2026-11-17: "make the quest item usable by tap out of combat"): what a tap on a quest's item icon does
 * ([QuestItemTap]): the quest's qpick column, then pfocus 30 (the add-on's secure quest-item cell) - or a short refusal: in a fight,
 * while its cooldown runs or the game holds it, or with no cell to tap.
 */
class QuestItemTapTest {
    private val pad = JSONObject("""{"rows":{"qpick":3,"qact":4,"pfocus":9},"sizes":{"qpick":40,"qact":40,"pfocus":31}}""")
    private val item = QuestItem.parse(JSONObject("""{"id":33,"slot":5,"si":{"id":23688,"ic":135432}}"""))!!

    @Test fun theItemKnowsItsQuest() {
        assertEquals(33, item.quest); assertEquals(5, item.slot)
    }

    @Test fun outOfCombatThePairIsTapped() {
        QuestItem.Cd.reset()
        assertEquals(QuestItemTap.Out.Send(listOf("qpick" to 5, "pfocus" to 30)), QuestItemTap.decide(item, pad, fight = false, now = 0L))
    }

    @Test fun refusals() {
        QuestItem.Cd.reset()
        assertEquals(QuestItemTap.Out.Refused("In combat"), QuestItemTap.decide(item, pad, fight = true, now = 0L))
        QuestItem.Cd.onQuests(JSONObject("""{"list":[{"id":33,"si":{"id":23688,"cd":{"d":30,"l":10}}}]}"""), true, now = 0L)
        assertEquals(QuestItemTap.Out.Refused("Not ready"), QuestItemTap.decide(item, pad, fight = false, now = 1_000L))
        QuestItem.Cd.onQuests(JSONObject("""{"list":[{"id":33,"si":{"id":23688,"cd":{"d":30,"h":true}}}]}"""), true, now = 0L)
        assertEquals("held", QuestItemTap.Out.Refused("Not ready"), QuestItemTap.decide(item, pad, fight = false, now = 1_000L))
        QuestItem.Cd.reset()
        // an add-on before q65: pfocus has 30 cells, no quest-item cell
        val old = JSONObject("""{"rows":{"qpick":3,"pfocus":9},"sizes":{"qpick":40,"pfocus":30}}""")
        assertNull(QuestItemTap.cells(old, 5))
        assertEquals(QuestItemTap.Out.Refused("Not ready"), QuestItemTap.decide(item, old, fight = false, now = 0L))
        assertNull("no column", QuestItemTap.cells(pad, -1))
    }
}
