package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** the user, 2026-11-11: "If I pick up a quest item like that we should make a prompt asking if we want to start that quest" (Dialogs d27) */
class QuestStartPromptTest {
    private fun entry(title: String? = "The Missing Courier", name: String? = "Ripped Missive", bag: Int = 0, slot: Int = 3, item: Int = 1972) =
        JSONObject().apply {
            put("i", 6); put("which", "QUEST_START"); put("text", "Start quest?")
            put("item", JSONObject().apply { if (name != null) put("name", name); put("icon", 134939) })
            put("qs", JSONObject().apply { put("item", item); put("bag", bag); put("slot", slot); put("quest", 900); if (title != null) put("title", title) })
            put("buttons", org.json.JSONArray().put(JSONObject().put("k", 1).put("label", "Start")).put(JSONObject().put("k", 2).put("label", "Not now")))
        }

    @Test fun theAddOnsEntryReadsAsAPrompt() {
        val p = QuestStartPrompt.parse(entry())
        assertNotNull(p)
        p!!
        assertEquals(1972, p.itemId); assertEquals("Ripped Missive", p.itemName); assertEquals(134939, p.icon)
        assertEquals(0, p.bag); assertEquals(3, p.slot); assertEquals(900, p.questId); assertEquals("The Missing Courier", p.title)
        assertEquals("The Missing Courier", p.sub)
    }

    @Test fun theLineUnderTheQuestionIsTheQuestsTitleOnly() {
        assertNull(QuestStartPrompt.parse(entry(title = null))!!.sub)                       // the game gave no title: the item's plate says it
        assertNull(QuestStartPrompt.parse(entry(title = "Ripped Missive"))!!.sub)           // never the item's name twice
        assertNull(QuestStartPrompt.parse(entry(title = " "))!!.sub)
        assertNull(QuestStartPrompt.parse(entry(name = null))!!.itemName)                  // an item not yet cached: no name, still a prompt
    }

    @Test fun onlyAWellFormedQuestStarterIsOne() {
        assertTrue(QuestStartPrompt.isPrompt("QUEST_START"))
        assertFalse(QuestStartPrompt.isPrompt("GUILD_INVITE"))
        assertNull(QuestStartPrompt.parse(JSONObject().put("which", "DEATH")))              // the game's own dialogs stay as they are
        assertNull(QuestStartPrompt.parse(entry().apply { remove("qs") }))                 // no item to use: not shown
        assertNull(QuestStartPrompt.parse(entry(item = 0)))
        assertNull(QuestStartPrompt.parse(entry(bag = -1)))
        assertNull(QuestStartPrompt.parse(entry(slot = 0)))
        assertNull(QuestStartPrompt.parse(null))
    }

    @Test fun theWordsAreTerseAndTheAppsOwn() {
        assertEquals("Start quest?", QuestStartPrompt.QUESTION)
        assertEquals("Start", QuestStartPrompt.label(1, "anything"))
        assertEquals("Not now", QuestStartPrompt.label(2, "anything"))
        assertEquals("other", QuestStartPrompt.label(3, "other"))
        assertEquals("¿Empezar la misión?", run {
            Tr.load("es", java.io.File(listOf("../companion/app/src/main/assets/companion/i18n", "companion/app/src/main/assets/companion/i18n")
                .first { java.io.File(it).isDirectory }, "es.json").readText())
            Tr.t(QuestStartPrompt.QUESTION, "es")
        })
    }
}
