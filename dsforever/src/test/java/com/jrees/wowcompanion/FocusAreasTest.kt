package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Home's map area pick (FocusAreas; the user, 2026-11-11, on 1428: "The newest update isn't showing the questie quest areas for the
 * focused quest", Home's small map only). 1427 parity for every quest but the merged Polestar quest, which is drawn tracked or not.
 */
class FocusAreasTest {
    private fun p(qid: Int, focused: Boolean = false, index: Int = -1) = FocusAreas.Pick(qid, focused, index)
    private fun s(tracked: Set<Int>, st: Int, merged: Int = 0, known: Boolean = true,
                  trackedRD: Map<Int, Boolean> = tracked.associateWith { false }, logRD: Map<Int, Boolean> = emptyMap()) =
        FocusAreas.In(known, tracked, st, merged, trackedRD, logRD)

    @Test fun parity1427TrackedFocusFilledTrackedOtherOutlineUntrackedNone() {
        val i = s(setOf(7, 8), st = 7)
        assertEquals(listOf(p(7, true, 0), p(8, false, 1)), FocusAreas.onMap(i, listOf(7 to false, 8 to false, 9 to false)))
        assertEquals(listOf(p(7, true), p(8)), FocusAreas.qdbOnly(i, listOf(7, 7, 8, 9), emptySet()))
    }

    @Test fun parity1427UntrackedGameFocusOrTrailStepDrawsNothing() {
        // st = the game's focus (or QuestTrail's step) on a quest the tracker does not list, no Polestar merge: none, as 1427
        val i = s(setOf(8), st = 9)
        assertEquals(listOf(p(8, false, 1)), FocusAreas.onMap(i, listOf(9 to false, 8 to false)))
        assertEquals(listOf(p(8)), FocusAreas.qdbOnly(i, listOf(9, 8), emptySet()))
    }

    @Test fun mergedPolestarQuestIsDrawnFilledTrackedOrNot() {
        val merged = PolestarMerge.quest(shown = true, picked = true, stepQuest = 9) { it == 9 }
        assertEquals(9, merged)
        val i = s(setOf(8), st = 9, merged = merged, logRD = mapOf(9 to false))
        assertEquals(listOf(p(9, true, 0), p(8, false, 1)), FocusAreas.onMap(i, listOf(9 to false, 8 to false)))
        assertEquals(listOf(p(9, true), p(8)), FocusAreas.qdbOnly(i, listOf(9, 8), emptySet()))
        val t = s(setOf(8, 9), st = 9, merged = merged)                       // tracked too: 1427's tracked focus
        assertEquals(listOf(p(9, true)), FocusAreas.qdbOnly(t, listOf(9), emptySet()))
    }

    @Test fun readyOrDoneSkipped() {
        // the map's done flag: the "?" at the hand-in, no area
        assertEquals(emptyList<FocusAreas.Pick>(), FocusAreas.onMap(s(setOf(7), st = 7), listOf(7 to true)))
        // QuestieDB-only: the tracked entry's ready / done
        assertEquals(emptyList<FocusAreas.Pick>(), FocusAreas.qdbOnly(s(setOf(7), st = 7, trackedRD = mapOf(7 to true)), listOf(7), emptySet()))
        // the untracked merged quest: its log entry's ready / done
        assertEquals(emptyList<FocusAreas.Pick>(),
            FocusAreas.qdbOnly(s(emptySet(), st = 9, merged = 9, logRD = mapOf(9 to true)), listOf(9), emptySet()))
        // the log entry is read for the merged quest only (1427 never read it)
        assertEquals(listOf(p(8)), FocusAreas.qdbOnly(s(setOf(8), st = 0, logRD = mapOf(8 to true)), listOf(8), emptySet()))
    }

    @Test fun qdbOnlyLeavesTheGamesMapQuestsToTheMapLoop() {
        assertEquals(listOf(p(8)), FocusAreas.qdbOnly(s(setOf(7, 8), st = 7), listOf(7, 8), setOf(7)))
    }

    @Test fun beforeTheFirstQuestsListEveryQuestIsDrawn() {
        val i = s(emptySet(), st = 0, known = false)
        assertEquals(listOf(p(5, false, 0), p(0, false, 1)), FocusAreas.onMap(i, listOf(5 to false, 0 to false)))
        assertEquals(listOf(p(5), p(6)), FocusAreas.qdbOnly(i, listOf(5, 6), emptySet()))
    }

    @Test fun noMergeWithoutTheQuestInTheLog() {
        val merged = PolestarMerge.quest(true, true, 99) { false }
        assertEquals(emptyList<FocusAreas.Pick>(), FocusAreas.qdbOnly(s(emptySet(), st = 0, merged = merged), listOf(99), emptySet()))
    }
}
