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

    /** ps1 (the user, 2026-11-11): Polestar's objective is a light blue row under the portrait, above the tracked quests; A follows it */
    @Test fun polestarsRowSitsAboveTheTrackedQuestsAndAFollowsIt() {
        val g = MapList.groups(listOf(e(10, "A", 5)), 5, emptyList(), emptyList())
        val items = MapList.items(true, listOf(1, 2), g, polestar = true) { false }
        assertEquals(listOf(MapList.Item.Me, MapList.Item.Polestar, MapList.Item.Tracked(1)), items.take(3))
        assertFalse(MapList.isGroup(MapList.POLESTAR))
        assertTrue(MapList.isGroup(MapList.groupKey("A")))
        val stops = MapList.stops(items)
        assertEquals(listOf(MapList.ME, MapList.POLESTAR, 1, 2, MapList.groupKey("A")), stops)
        val c = MapCursor()
        c.touched(MapList.POLESTAR, stops)
        assertEquals(MapCursor.Do.Polestar, c.key("PAD1", stops, 0, false))
        assertEquals(MapCursor.Do.Nothing, c.key("PAD3", stops, 0, false))      // X: nothing to track
        assertEquals(MapCursor.Do.Nothing, c.key("PAD4", stops, 0, false))      // Y: no quest box
        assertFalse(MapList.items(true, listOf(1), g) { false }.contains(MapList.Item.Polestar))
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


    // ---- TRACK / UNTRACK HOLD THE CURSOR'S PLACE (the user, 2026-11-16: "if I want to track or untrack a bunch at once I want to be able
    // quickly go down the list and hit untrack for each, not have to scroll all the way back up after each untrack")

    /** the list after quest [moved] went up into the tracked quests ([on]) or back down into its tab */
    private fun relist(tracked: List<Int>, log: List<MapList.Entry>, open: Set<String>, polestar: Boolean = false) =
        MapList.items(true, tracked, MapList.groups(log, 10, emptyList(), emptyList()), polestar = polestar) { it in open }

    @Test fun untrackingHandsTheCursorToTheNextTrackedQuestNotTheMovedOne() {
        val log = listOf(e(20, "A", 10))
        val before = MapList.stops(relist(listOf(1, 2, 3, 4), log, setOf("A")))
        val c = MapCursor(); c.startAt(before, 0)
        c.key("PADDDOWN", before, 0, false)                                 // on 2
        assertEquals(MapCursor.Do.Untrack(2), c.key("PAD3", before, 0, false, setOf(20)))
        // 2 went down into its (open) tab A: the cursor stays where it was, on 3, which moved into 2's place
        val after = MapList.stops(relist(listOf(1, 3, 4), log + e(2, "A", 10), setOf("A")))
        assertTrue(after.contains(2))
        assertEquals(3, c.stay(2, before, after)); assertEquals(3, c.sel)
        assertEquals(before.indexOf(2), after.indexOf(3))                   // the same place in the list
    }

    @Test fun untrackingABunchGoesDownTheListOnePressEach() {
        var tracked = listOf(1, 2, 3, 4, 5)
        var log = listOf(e(20, "A", 10))
        var stops = MapList.stops(relist(tracked, log, emptySet()))
        val c = MapCursor(); c.startAt(stops, 2)                           // on 2
        for (expectNext in listOf(3, 4, 5)) {
            val acted = c.sel
            assertEquals(MapCursor.Do.Untrack(acted), c.key("PAD3", stops, 0, false))
            tracked = tracked - acted; log = log + e(acted, "Z", 10)
            val after = MapList.stops(relist(tracked, log, emptySet()))
            assertEquals(expectNext, c.stay(acted, stops, after))
            stops = after
        }
        // the last tracked quest: the next stop down takes its place (the first tab), never back up the list
        val acted = c.sel
        tracked = tracked - acted; log = log + e(acted, "Z", 10)
        val after = MapList.stops(relist(tracked, log, emptySet()))
        assertTrue(MapList.isGroup(c.stay(acted, stops, after)))
        assertEquals(listOf(1), tracked)
    }

    @Test fun trackingABunchInATabTakesTheNextQuestOfTheTab() {
        var tracked = listOf(1, 2)
        var log = listOf(e(20, "A", 10), e(21, "A", 10), e(22, "A", 10), e(30, "B", 12))
        var stops = MapList.stops(relist(tracked, log, setOf("A")))
        val c = MapCursor(); c.touched(20, stops)
        for (expectNext in listOf(21, 22)) {
            val acted = c.sel
            assertEquals(MapCursor.Do.Track(acted), c.key("PAD1", stops, 0, false, MapList.untracked(relist(tracked, log, setOf("A")))))
            // A tracks and focuses: the quest goes to the top of the tracked quests, far above the cursor
            tracked = listOf(acted) + tracked; log = log.filter { it.id != acted }
            val after = MapList.stops(relist(tracked, log, setOf("A")))
            assertEquals(expectNext, c.stay(acted, stops, after))
            // one row came above it (the tracked quest) and one left at it: the next quest stands one stop further down the stops
            assertEquals(stops.indexOf(acted) + 1, after.indexOf(expectNext))
            stops = after
        }
        // the tab's last quest: its tab goes with it, the next tab (B) takes the place
        val acted = c.sel
        tracked = listOf(acted) + tracked; log = log.filter { it.id != acted }
        val after = MapList.stops(relist(tracked, log, setOf("A")))
        assertEquals(MapList.groupKey("B"), c.stay(acted, stops, after))
    }

    @Test fun theEndOfTheListHandsTheCursorBackUpAndAnEmptyListLetsItGo() {
        assertEquals(3, MapCursor.stayAfter(4, listOf(1, 3, 4), listOf(1, 3)))           // nothing after it: the nearest before
        assertEquals(1, MapCursor.stayAfter(9, listOf(1, 3), listOf(1, 3)))              // not in the list as it was: the first
        assertEquals(0, MapCursor.stayAfter(4, listOf(4), emptyList()))
        val c = MapCursor(); c.touched(4, listOf(4))
        assertEquals(0, c.stay(4, listOf(4), emptyList())); assertFalse(c.shown)
        // only the quest the cursor is on: a press elsewhere leaves it alone
        val d = MapCursor(); d.touched(3, listOf(1, 3, 4))
        assertEquals(3, d.stay(4, listOf(1, 3, 4), listOf(1, 3)))
        // the light blue row above the tracked quests stays where it is too (pk1: its X / Y are Skip / Previous, never a track)
        val before = MapList.stops(relist(listOf(1, 2), listOf(e(20, "A", 10)), emptySet(), polestar = true))
        val after = MapList.stops(relist(listOf(2), listOf(e(20, "A", 10), e(1, "A", 10)), emptySet(), polestar = true))
        val p = MapCursor(); p.touched(1, before)
        assertEquals(2, p.stay(1, before, after))
        assertEquals(listOf(MapList.ME, MapList.POLESTAR), after.take(2))
    }

    /**
     * the real order of a press (MapView.trackNow): the list's own keeping runs first ([MapCursor.moved] onto the light blue row, then
     * keepValid), then [MapCursor.stay] for the quest the cursor WAS on. Polestar merged with quest 1 (only in the log is needed): 1's
     * own row untracked into its closed tab handed the cursor UP to the light blue row, where the next X skips Polestar's step.
     */
    @Test fun untrackingTheQuestPolestarStandsForNeverHandsTheCursorToPolestarsRow() {
        val log = listOf(e(20, "A", 10))
        val before = MapList.stops(relist(listOf(1, 2, 3), log, emptySet(), polestar = true))
        val c = MapCursor(); c.touched(1, before)
        assertEquals(MapCursor.Do.Untrack(1), c.key("PAD3", before, MapList.POLESTAR, false))
        val after = MapList.stops(relist(listOf(2, 3), log + e(1, "A", 10), emptySet(), polestar = true))
        c.moved(1, MapList.POLESTAR, after); c.keepValid(after)              // (applyQuests: rebuildItems, then keepValid)
        assertEquals("the list's keeping alone took it to the light blue row", MapList.POLESTAR, c.sel)
        assertEquals(2, c.stay(1, before, after, wasOn = true)); assertEquals(2, c.sel)
        assertEquals(before.indexOf(1), after.indexOf(2))
        // X there now untracks 2: never a Polestar skip
        assertEquals(MapCursor.Do.Untrack(2), c.key("PAD3", after, MapList.POLESTAR, false, psSkip = true))
    }

    @Test fun theLastTrackedQuestsNewTabTakesItsPlace() {
        // quest 3's zone B had no tab (all of B tracked): untracked, its tab comes back right at its place, the first tab
        val log = listOf(e(20, "A", 10))
        val before = MapList.stops(relist(listOf(1, 2, 3), log, emptySet()))
        val after = MapList.stops(MapList.items(true, listOf(1, 2),
            MapList.groups(log + e(3, "B", 10), 10, listOf("B"), emptyList())) { false })
        assertEquals(listOf(MapList.ME, 1, 2, MapList.groupKey("B"), MapList.groupKey("A")), after)
        assertEquals(MapList.groupKey("B"), MapCursor.stayAfter(3, before, after))
        // a tab that was there already stays the next stop (no new one at the place)
        val afterOld = MapList.stops(relist(listOf(1, 2), log + e(3, "A", 10), emptySet()))
        assertEquals(MapList.groupKey("A"), MapCursor.stayAfter(3, before, afterOld))
    }
    /** pg1 / D6 (the user, 2026-11-16: "I need some kind of header in the quests menu of the homeroom map indicating that it is the
     *  polestar objective"): with no light blue row the header stands alone above the tracked quests, and the cursor skips it */
    @Test fun polestarsLoneHeaderIsNoStop() {
        val g = MapList.groups(listOf(e(10, "A", 5)), 5, emptyList(), emptyList())
        val items = MapList.items(true, listOf(1, 2), g, polestar = false, psHead = true) { false }
        assertEquals(listOf(MapList.Item.Me, MapList.Item.PsHead, MapList.Item.Tracked(1)), items.take(3))
        assertEquals(listOf(MapList.ME, 1, 2, MapList.groupKey("A")), MapList.stops(items))
        assertFalse("not a tab", MapList.isGroup(MapList.PS_HEAD))
        assertFalse(MapList.isGroup(MapList.POLESTAR))
        // with the row the row's own ribbon is the header: no second one
        val row = MapList.items(true, listOf(1), g, polestar = true, psHead = true) { false }
        assertEquals(listOf(MapList.Item.Me, MapList.Item.Polestar, MapList.Item.Tracked(1)), row.take(3))
        assertFalse(row.contains(MapList.Item.PsHead))
        // the cursor never lands on it: Down from the portrait goes to the first tracked quest
        val c = MapCursor()
        c.startAt(MapList.stops(items), MapList.ME)
        c.key("PADDDOWN", MapList.stops(items), 0, false, emptySet())
        assertEquals(1, c.sel)
        assertTrue(MapList.PS_HEAD != MapList.groupKey("A") && MapList.PS_HEAD < MapList.POLESTAR)
    }
}
