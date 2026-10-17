package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** the map module's list (the user, 2026-10-16): the portrait, the tracked quests, the Untracked tabs, and the cursor over them */
class MapListTest {
    private fun e(id: Int, zone: String, lv: Int?, here: Boolean = false) = MapList.Entry(id, zone, lv, here)

    @Test fun tabsSortZoneThenClassAndProfessionThenNearestLevel() {
        val log = listOf(
            e(1, "Duskwood", 25), e(2, "Westfall", 14), e(3, "Warrior", 10), e(4, "Elwynn Forest", 8),
            e(5, "Fishing", 30), e(6, "Redridge Mountains", 16), e(7, "Westfall", 12),
        )
        val g = MapList.groups(log, plv = 15, current = listOf("Elwynn Forest"), special = listOf("Warrior", "Fishing"))
        // the zone the player is in, then class (level gap 5) and profession (15), then the rest by their nearest level
        assertEquals(listOf("Elwynn Forest", "Warrior", "Fishing", "Westfall", "Redridge Mountains", "Duskwood"), g.map { it.first })
        assertEquals(listOf(2, 7), g.first { it.first == "Westfall" }.second)     // 14 (gap 1) before 12 (gap 3)
    }

    @Test fun aQuestWithAMarkerHereMakesItsHeaderTheZones() {
        val g = MapList.groups(listOf(e(1, "Duskwood", 25), e(2, "Northshire Valley", 3, here = true)), 5, listOf("Elwynn Forest"), emptyList())
        assertEquals("Northshire Valley", g.first().first)
    }

    @Test fun everyTabStartsClosedAndThePortraitLeads() {
        val g = MapList.groups(listOf(e(10, "A", 5), e(11, "B", 5)), 5, emptyList(), emptyList())
        val items = MapList.items(true, listOf(1, 2), g) { false }
        assertEquals(MapList.Item.Me, items[0])
        assertEquals(listOf(MapList.Item.Tracked(1), MapList.Item.Tracked(2)), items.subList(1, 3))
        assertEquals(MapList.Item.Section(2), items[3])
        assertTrue(items.drop(4).all { it is MapList.Item.Group && !it.open })
        // the section line is no stop; the tabs are, under keys no quest has
        val stops = MapList.stops(items)
        assertEquals(listOf(MapList.ME, 1, 2, MapList.groupKey("A"), MapList.groupKey("B")), stops)
        assertTrue(stops.drop(3).all { MapList.isGroup(it) })
        val open = MapList.items(true, listOf(1, 2), g) { it == "B" }
        assertEquals(setOf(11), MapList.untracked(open))
    }

    @Test fun theCursorOpensOnTheFocusedQuestNotThePortrait() {
        val stops = listOf(MapList.ME, 7, 8, MapList.groupKey("A"))
        val c = MapCursor()
        assertTrue(c.startAt(stops, 0)); assertEquals(7, c.sel)
        assertTrue(c.startAt(stops, 8)); assertEquals(8, c.sel)
    }

    @Test fun aDoesWhatTheRowIs() {
        val g = MapList.groupKey("A")
        val stops = listOf(MapList.ME, 7, g, 20)
        val untracked = setOf(20)
        val c = MapCursor()
        c.startAt(stops, 7)
        assertEquals(MapCursor.Do.Select(MapList.ME), c.key("PADDUP", stops, 7, false, untracked))
        assertEquals(MapCursor.Do.PlayerMenu, c.key("PAD1", stops, 7, false, untracked))
        assertEquals(MapCursor.Do.Nothing, c.key("PAD3", stops, 7, false, untracked))       // no untrack on the portrait
        c.key("PADDDOWN", stops, 7, false, untracked)
        assertEquals(MapCursor.Do.Unfocus(7), c.key("PAD1", stops, 7, false, untracked))
        c.key("PADDDOWN", stops, 7, false, untracked)
        assertEquals(MapCursor.Do.Toggle(g), c.key("PAD1", stops, 7, false, untracked))
        c.key("PADDDOWN", stops, 7, false, untracked)
        assertEquals(MapCursor.Do.Track(20), c.key("PAD1", stops, 7, false, untracked))
        // the user, 2026-10-16: A tracks and focuses, X is the inverse of its tracking, Y opens its panel
        assertEquals(MapCursor.Do.Watch(20), c.key("PAD3", stops, 7, false, untracked))
        assertEquals(MapCursor.Do.Open(20), c.key("PAD4", stops, 7, false, untracked))
        c.key("PADDUP", stops, 7, false, untracked); c.key("PADDUP", stops, 7, false, untracked)
        assertEquals(MapCursor.Do.Untrack(7), c.key("PAD3", stops, 7, false, untracked))     // X on a tracked quest: untrack, as before
        assertEquals(MapCursor.Do.Open(7), c.key("PAD4", stops, 7, false, untracked))        // Y opens a tracked quest's panel too
        assertFalse(MapList.isGroup(MapList.ME))
    }

    @Test fun aTracksAnUntrackedQuestThroughItsColumn() {
        val hq = HomeQuests()
        fun doc(watched: Boolean) = org.json.JSONObject().put("list", org.json.JSONArray()
            .put(org.json.JSONObject().put("id", 1).put("title", "A").put("slot", 3).put("watch", 1))
            .put(org.json.JSONObject().put("id", 2).put("title", "B").put("slot", 5).apply { if (watched) put("watch", 1) }))
        hq.update(doc(false), current = true, now = 0L)
        assertEquals(FocusTap.Send(2, 5), hq.tapWatch(2, live = true, now = 10L))
        assertEquals(FocusTap.Refused("Waiting for the game's answer"), hq.tapWatch(2, live = true, now = 20L))
        assertEquals(FocusTap.Already, hq.tapWatch(1, live = true, now = 20L))
        hq.update(doc(true), current = true, now = 30L)        // the game tracked it: nothing waits any more
        assertEquals(null, hq.pending(40L))
    }

    @Test fun shareAndAbandonReachAnyQuestOfTheLog() {
        val hq = HomeQuests()
        val doc = org.json.JSONObject().put("list", org.json.JSONArray()
            .put(org.json.JSONObject().put("id", 2).put("title", "B").put("slot", 5)))
        hq.update(doc, current = true, now = 0L)
        assertEquals(FocusTap.Send(2, 5), hq.tapAny(2, "arm", live = true, now = 10L))
        val armed = org.json.JSONObject().put("op", "arm").put("id", 2).put("ok", true)
        assertEquals("", hq.ack(armed, 20L))
        assertEquals(FocusTap.Send(2, 0), hq.tapConfirm(2, live = true, now = 30L))
        assertEquals(FocusTap.Refused("Not in your quest log any more"), HomeQuests().tapAny(9, "share", true, 0L))
    }
}
