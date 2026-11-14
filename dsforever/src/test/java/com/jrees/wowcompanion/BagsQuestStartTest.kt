package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #252 ("Items with quests attached to them cannot be started from the bag menu"): an item that begins a quest (Bags.lua
 * bags-qstart1 `qstart`) gets the Bags room's ONE action, "Start Quest" (the slot's own secure item cell: the game's right-click),
 * where it had none; at a vendor, bank, mailbox or trade the window's own action still wins.
 */
class BagsQuestStartTest {
    private fun row(vararg kv: Pair<String, Any>) = JSONObject().apply { for ((k, v) in kv) put(k, v) }

    @Test fun aQuestStarterOffersStartQuest() {
        assertEquals(BagsView.START_QUEST to true, BagsView.actionOf(row("id" to 1972, "quest" to true, "qstart" to true), null))
        assertEquals("Start Quest", BagsView.START_QUEST)
    }

    @Test fun beforeTheFixItHadNoAction() {
        // the same starter as an older add-on sends it (quest, but no qstart): no action, as reported
        assertNull(BagsView.actionOf(row("id" to 1972, "quest" to true, "equip" to "INVTYPE_NON_EQUIP_IGNORE"), null))
    }

    @Test fun aReadableStarterStartsTheQuestToo() {
        assertEquals(BagsView.START_QUEST to true, BagsView.actionOf(row("readable" to true, "qstart" to true), null))
    }

    @Test fun theWindowsOwnActionWins() {
        val starter = row("qstart" to true, "quest" to true)
        assertEquals("Sell" to true, BagsView.actionOf(starter, "merchant"))
        assertEquals("Deposit" to true, BagsView.actionOf(starter, "bank"))
        assertEquals("Attach" to true, BagsView.actionOf(starter, "mail"))
        assertEquals("Trade" to true, BagsView.actionOf(starter, "trade"))
    }

    @Test fun otherItemsAreUnchanged() {
        assertEquals("Read" to true, BagsView.actionOf(row("readable" to true), null))
        assertEquals("Use" to true, BagsView.actionOf(row("use" to true, "quest" to true), null))
        assertEquals("Equip" to true, BagsView.actionOf(row("equip" to "INVTYPE_HEAD"), null))
        assertNull(BagsView.actionOf(row("quest" to true), null))
    }

    @Test fun theWordsAreTranslated() {
        val dir = listOf("../companion/app/src/main/assets/companion/i18n", "companion/app/src/main/assets/companion/i18n")
            .map { java.io.File(it) }.first { it.isDirectory }
        Tr.load("es", java.io.File(dir, "es.json").readText())
        Tr.load("fr", java.io.File(dir, "fr.json").readText())
        assertEquals("Empezar misión", Tr.t(BagsView.START_QUEST, "es"))
        assertEquals("Commencer la quête", Tr.t(BagsView.START_QUEST, "fr"))
    }
}
