package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Quests q64 (the user, 2026-11-17: "one thing that does make sense though on the home screen quests is showing the Icon for if there
 * is a usable quest Item that needs to be used for the quest"): the quests document's `si` as the cards read it ([QuestItem]) and its
 * cooldown counted from a live document ([QuestItem.Cd]).
 */
class QuestItemTest {
    private fun doc(si: String) = JSONObject("""{"list":[{"id":33,"si":$si},{"id":7}]}""")

    @Test fun theItemIsRead() {
        val q = JSONObject("""{"id":33,"si":{"id":23688,"ic":135432,"c":3}}""")
        val it = QuestItem.parse(q)!!
        assertEquals(23688, it.id); assertEquals(135432, it.icon); assertEquals(3, it.count)
        assertEquals(0, QuestItem.parse(JSONObject("""{"si":{"id":7342}}"""))!!.count)
        assertNull(QuestItem.parse(JSONObject("""{"id":7}""")))
        assertNull("no id: no item", QuestItem.parse(JSONObject("""{"si":{"ic":1}}""")))
        assertNull(QuestItem.parse(null))
    }

    @Test fun theCooldownIsCounted() {
        QuestItem.Cd.reset()
        QuestItem.Cd.onQuests(doc("""{"id":23688,"cd":{"d":30,"l":12}}"""), current = true, now = 1_000L)
        assertEquals(12.0, QuestItem.Cd.left(23688, 1_000L), 0.01)
        assertEquals(30.0, QuestItem.Cd.len(23688, 1_000L), 0.01)
        assertEquals(2.0, QuestItem.Cd.left(23688, 11_000L), 0.01)
        assertEquals("run out", 0.0, QuestItem.Cd.left(23688, 13_000L), 0.0)
        // a replayed document (an old life's) counts nothing
        QuestItem.Cd.onQuests(doc("""{"id":23688,"cd":{"d":30,"l":20}}"""), current = false, now = 20_000L)
        assertEquals(0.0, QuestItem.Cd.left(23688, 20_000L), 0.0)
        // held (enable 0): dimmed, no countdown; the next document without cd: ready
        QuestItem.Cd.onQuests(doc("""{"id":23688,"cd":{"d":30,"h":true}}"""), current = true, now = 30_000L)
        assertTrue(QuestItem.Cd.held(23688)); assertEquals(0.0, QuestItem.Cd.left(23688, 30_000L), 0.0)
        QuestItem.Cd.onQuests(doc("""{"id":23688}"""), current = true, now = 31_000L)
        assertFalse(QuestItem.Cd.held(23688)); assertEquals(0.0, QuestItem.Cd.left(23688, 31_000L), 0.0)
        QuestItem.Cd.reset()
    }
}
