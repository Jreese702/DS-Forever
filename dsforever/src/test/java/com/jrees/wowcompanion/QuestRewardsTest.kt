package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Quests q63 (the user, 2026-11-17: "The quests don't show rewards at all anywhere"): the quests document's `rw` as the room reads it
 * ([QuestRewards]), the cursor's steps onto the rewards ([QuestCursor]), the tooltips' store ([QuestRewardTips]) and the new words.
 */
class QuestRewardsTest {
    private val wolves = JSONObject("""{"id":33,"title":"Wolves Across the Border","rw":{"m":350,"xp":450,
        "ch":[{"n":"Fur-lined Boots","ic":132539,"c":1,"q":2,"id":2032},{"n":"Hunting Gloves","ic":132952,"c":1,"q":2,"id":2033,"u":false}],
        "it":[{"n":"Tough Wolf Meat","ic":133970,"c":5,"q":1,"id":750},{"ic":134400,"c":1,"q":3,"id":6256}],
        "sp":[{"n":"Flametongue Weapon","ic":135814,"id":8024}]}}""")

    @Test fun theDocumentsRewardsAreRead() {
        val r = QuestRewards.parse(wolves)!!
        assertEquals(350L, r.money); assertEquals(450, r.xp); assertEquals(0, r.honor)
        assertEquals(listOf("Fur-lined Boots", "Hunting Gloves"), r.choices.map { it.name })
        assertTrue(r.choices[0].usable); assertFalse("u = false: the game's red", r.choices[1].usable)
        assertEquals(5, r.items[0].count); assertEquals(1, r.items[0].quality)
        assertNull("a name the client had not loaded yet: shown by its id", r.items[1].name); assertEquals(6256, r.items[1].id)
        assertEquals("Flametongue Weapon", r.spells.single().name)
        // the cursor's stops in reading order: the choices, the fixed items, the spells
        assertEquals(listOf("c0", "c1", "i0", "i1", "s0"), r.pieces().map { it.key })
        assertEquals("Tough Wolf Meat", r.itemOf(QuestRewards.Piece('i', 0))?.name)
        assertNull(r.itemOf(QuestRewards.Piece('s', 0)))
    }

    @Test fun noRewardsNoBlock() {
        assertNull(QuestRewards.parse(JSONObject("""{"id":7}""")))
        assertNull(QuestRewards.parse(JSONObject("""{"id":7,"rw":{}}""")))
        assertNull(QuestRewards.parse(null))
        val coin = QuestRewards.parse(JSONObject("""{"id":46,"rw":{"m":10500,"hon":25}}"""))!!
        assertEquals(25, coin.honor); assertTrue(coin.pieces().isEmpty())
    }

    @Test fun rightStepsOntoTheRewards() {
        val c = QuestCursor().apply { show() }
        val ids = listOf(33, 46)
        assertEquals(QuestCursor.Do.Reward(0), c.key("PADDRIGHT", ids, 33, false, rewards = 5))
        assertEquals("no rewards: Right does nothing", QuestCursor.Do.Nothing, c.key("PADDRIGHT", ids, 46, false, rewards = 0))
        assertEquals(QuestCursor.Do.Reward(1), c.key("PADDDOWN", ids, 33, false, reward = 0, rewards = 5))
        assertEquals(QuestCursor.Do.Nothing, c.key("PADDDOWN", ids, 33, false, reward = 4, rewards = 5))
        assertEquals(QuestCursor.Do.Reward(2), c.key("PADDUP", ids, 33, false, reward = 3, rewards = 5))
        assertEquals(QuestCursor.Do.Nothing, c.key("PADDUP", ids, 33, false, reward = 0, rewards = 5))
        assertEquals("Left: back to the list", QuestCursor.Do.Reward(-1), c.key("PADDLEFT", ids, 33, false, reward = 2, rewards = 5))
        assertEquals("B: back to the list", QuestCursor.Do.Reward(-1), c.key("PAD2", ids, 33, false, reward = 2, rewards = 5))
        for (k in listOf("PAD1", "PAD3", "PAD4")) assertEquals("$k does nothing on a reward", QuestCursor.Do.Nothing,
            c.key(k, ids, 33, false, reward = 1, rewards = 5))
        assertEquals("B leads back while on a reward", "back", c.bLeads(false, false, reward = true))
        assertEquals("home", c.bLeads(false, false, reward = false))
        // the confirm bar and the More menu still come first
        assertEquals(QuestCursor.Do.Cancel, c.key("PAD2", ids, 33, true, reward = 1, rewards = 5))
        assertEquals(QuestCursor.Do.MenuClose, c.key("PAD2", ids, 33, false, menu = 0, menuRows = 3, reward = 1, rewards = 5))
        // the list's own keys unchanged
        assertEquals(QuestCursor.Do.Select(46), c.key("PADDDOWN", ids, 33, false, rewards = 5))
        assertEquals(QuestCursor.Do.Track, c.key("PAD1", ids, 33, false, rewards = 5))
    }

    @Test fun theTooltipsAreKeptByItem() {
        QuestRewardTips.clear()
        assertTrue(QuestRewardTips.onDoc(JSONObject("""{"type":"qrtip","id":2032,"tip":{"key":"link item:2032","lines":[{"t":"Feet"}]}}""")))
        assertFalse(QuestRewardTips.onDoc(JSONObject("""{"type":"qrtip","id":0,"tip":{}}""")))
        assertEquals("Feet", QuestRewardTips.of(2032)!!.optJSONArray("lines")!!.optJSONObject(0).optString("t"))
        assertNull(QuestRewardTips.of(2033))
    }

    @Test fun theNewWordsAreTranslated() {
        val dir = listOf("../companion/app/src/main/assets/companion/i18n", "companion/app/src/main/assets/companion/i18n")
            .map { java.io.File(it) }.first { it.isDirectory }
        for (l in listOf("es", "fr")) {
            Tr.load(l, java.io.File(dir, "$l.json").readText())
            for (w in listOf("Choose one", "You will learn:", "Rewards", "You will receive:", "You will also receive:", "Experience:"))
                assertNotEquals("$l: $w", w, Tr.t(w, l))
        }
        assertEquals("Honneur :", Tr.t("Honor:", "fr"))
    }
}
