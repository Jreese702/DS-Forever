package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One row for Polestar's quest (the user, 2026-11-11: "consolidate that into one when it truly is tracking a quest and show that
 * quests objectives instead of 'complete that quest'"): the merge decision, the row's words, the list's keys and the selection.
 */
class PolestarMergeTest {
    private val log = setOf(7, 8, 20)
    private fun e(id: Int, zone: String) = MapList.Entry(id, zone, 5, false)

    @Test fun mergesOnlyWhenPickedShownAndTheStepsQuestIsInTheLog() {
        assertEquals(7, PolestarMerge.quest(shown = true, picked = true, stepQuest = 7) { it in log })
        assertEquals(0, PolestarMerge.quest(true, picked = false, stepQuest = 7) { it in log })      // not picked: as today
        assertEquals(0, PolestarMerge.quest(shown = false, picked = true, stepQuest = 7) { it in log })
        assertEquals(0, PolestarMerge.quest(true, true, stepQuest = 0) { it in log })                 // a step with no quest
        assertEquals(0, PolestarMerge.quest(true, true, stepQuest = 99) { it in log })                // accept: not in the log yet
    }

    @Test fun mergedRowReadsTheQuestsOwnObjectives() {
        val lines = listOf("Kobold Vermin slain: 4/10" to false, "Candles: 8/8" to true)
        val c = PolestarMerge.content(7, "Complete Kobold Camp Cleanup", "Complete the quest", "Kobold Camp Cleanup", lines)!!
        assertTrue(c.merged)
        assertEquals("Kobold Camp Cleanup", c.title)
        assertEquals(lines, c.lines)                                                                  // done / progress kept
    }

    @Test fun unmergedRowKeepsTheStepsWords() {
        val c = PolestarMerge.content(0, "Accept Wolves Across the Border", "Talk to Eagan Peltskinner", null, emptyList())!!
        assertFalse(c.merged)
        assertEquals("Accept Wolves Across the Border", c.title)
        assertEquals(listOf("Talk to Eagan Peltskinner" to false), c.lines)
        val same = PolestarMerge.content(0, "Fly to Goldshire", "Fly to Goldshire", null, emptyList())!!
        assertEquals(emptyList<Pair<String, Boolean>>(), same.lines)                                 // the words once
        assertEquals("Train", PolestarMerge.content(0, null, "Train", null, emptyList())!!.title)
        assertNull(PolestarMerge.content(0, null, null, null, emptyList()))
        // merged but the quest has no title yet: the step's words stand in
        assertFalse(PolestarMerge.content(7, "Step", "Go", "", listOf("x" to false))!!.merged)
    }

    @Test fun theMergedQuestsTrackedRowIsLeftOutAndEveryKeyIsOnce() {
        val g = MapList.groups(listOf(e(20, "A")), 5, emptyList(), emptyList())
        val items = MapList.items(true, listOf(7, 8), g, polestar = true, merged = 7) { false }
        assertEquals(listOf(MapList.Item.Me, MapList.Item.Polestar, MapList.Item.Tracked(8), MapList.Item.Section(1)), items.take(4))
        val stops = MapList.stops(items)
        assertEquals(stops.size, stops.toSet().size)
        assertFalse(7 in stops)
        // no Polestar row: nothing merges, whatever is asked
        assertTrue(MapList.Item.Tracked(7) in MapList.items(true, listOf(7, 8), g, polestar = false, merged = 7) { false })
    }

    @Test fun anUntrackedMergedQuestLeavesItsTabAndAnEmptyTabGoes() {
        val g = MapList.groups(listOf(e(20, "A"), e(21, "B"), e(22, "B")), 5, emptyList(), emptyList())
        val items = MapList.items(false, listOf(7), g, polestar = true, merged = 20) { true }
        assertFalse(items.any { it is MapList.Item.Group && it.zone == "A" })
        assertEquals(MapList.Item.Section(2), items.first { it is MapList.Item.Section })
        assertEquals(setOf(21, 22), MapList.untracked(items))
        val b = MapList.items(false, listOf(7), g, polestar = true, merged = 21) { true }
        assertEquals(MapList.Item.Group("B", 1, true), b.first { it is MapList.Item.Group && it.zone == "B" })
        assertEquals(setOf(20, 22), MapList.untracked(b))
    }

    @Test fun aSelectionOnTheMergedQuestMovesOntoTheRowAndStaysOnItWhenItUnmerges() {
        val g = emptyList<Pair<String, List<Int>>>()
        val before = MapList.stops(MapList.items(true, listOf(7, 8), g, polestar = true) { false })
        val c = MapCursor()
        c.touched(7, before)
        val merged = MapList.stops(MapList.items(true, listOf(7, 8), g, polestar = true, merged = 7) { false })
        c.moved(7, MapList.POLESTAR, merged)
        assertEquals(MapList.POLESTAR, c.sel)
        assertEquals(MapCursor.Do.Polestar, c.key("PAD1", merged, MapList.POLESTAR, false))        // A: still the Polestar toggle
        assertEquals(MapCursor.Do.Select(8), c.key("PADDDOWN", merged, MapList.POLESTAR, false))
        // unmerged (picked off): the row stays a stop, the quest's row comes back below it; nothing dangles
        c.touched(MapList.POLESTAR, merged)
        val after = MapList.stops(MapList.items(true, listOf(7, 8), g, polestar = true) { false })
        assertEquals(MapCursor.Do.Select(7), c.key("PADDDOWN", after, 7, false))
        // a selection elsewhere is left alone
        val d = MapCursor(); d.touched(8, before); d.moved(7, MapList.POLESTAR, merged)
        assertEquals(8, d.sel)
    }
    // pk1 (the user, 2026-11-12: "X Skip, Y Previous"): the merged row's X / Y are no longer its quest's Track / Open
    // (PolestarSkipTest has the Skip / Previous routing); with nothing to skip or undo they do nothing
    @Test fun theMergedRowsXAndYAreNoLongerItsQuests() {
        val g = emptyList<Pair<String, List<Int>>>()
        val merged = MapList.stops(MapList.items(true, listOf(7, 8), g, polestar = true, merged = 7) { false })
        val c = MapCursor(); c.touched(MapList.POLESTAR, merged)
        assertEquals(MapCursor.Do.Nothing, c.key("PAD3", merged, MapList.POLESTAR, false))
        assertEquals(MapCursor.Do.Nothing, c.key("PAD4", merged, MapList.POLESTAR, false))
        assertEquals(MapCursor.Do.Polestar, c.key("PAD1", merged, MapList.POLESTAR, false))
        // unmerged (a travel step): the blue row's X / Y still do nothing
        val plain = MapList.stops(MapList.items(true, listOf(7, 8), g, polestar = true) { false })
        val d = MapCursor(); d.touched(MapList.POLESTAR, plain)
        assertEquals(MapCursor.Do.Nothing, d.key("PAD3", plain, MapList.POLESTAR, false))
        assertEquals(MapCursor.Do.Nothing, d.key("PAD4", plain, MapList.POLESTAR, false))
    }

    // the merged row's key is POLESTAR (negative): everything that reads the cursor's key as a quest id resolves it first
    @Test fun theMergedKeyResolvesToItsQuest() {
        val log = setOf(7, 8)
        val merged = PolestarMerge.quest(shown = true, picked = true, stepQuest = 7) { it in log }
        assertEquals(7, PolestarMerge.key(MapList.POLESTAR, merged))                 // merged: the row IS quest 7 on the map
        assertEquals(8, PolestarMerge.key(8, merged))                                // another quest's row: itself
        assertEquals(MapList.ME, PolestarMerge.key(MapList.ME, merged))              // the portrait: itself
        // unmerged (a travel / accept step): the row stays POLESTAR - no quest of its own, nothing on the map is looked up
        val travel = PolestarMerge.quest(true, true, stepQuest = 0) { it in log }
        assertEquals(MapList.POLESTAR, PolestarMerge.key(MapList.POLESTAR, travel))
        // not picked: nothing merges, every key is itself
        val off = PolestarMerge.quest(true, picked = false, stepQuest = 7) { it in log }
        assertEquals(MapList.POLESTAR, PolestarMerge.key(MapList.POLESTAR, off))
        assertEquals(7, PolestarMerge.key(7, off))
        assertTrue(PolestarMerge.key(MapList.POLESTAR, off) <= 0)                     // never taken for a quest
    }

}
