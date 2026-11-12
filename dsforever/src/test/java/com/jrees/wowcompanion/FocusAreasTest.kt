package com.jrees.wowcompanion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Home's map draws the SHOWN focus's areas, tracked or not (the user, 2026-11-11, on 1428: "The newest update isn't showing the
 * questie quest areas for the focused quest", Home's small map only): the merged light blue row's quest is the shown focus.
 */
class FocusAreasTest {
    @Test fun mergedPolestarQuestHasItsAreasEvenWhenTheTrackerDoesNotListIt() {
        // PolestarMerge: the picked row stands for quest 7 (in the log); MapView.shownFocus() = psFocus() = 7
        val merged = PolestarMerge.quest(shown = true, picked = true, stepQuest = 7) { it == 7 }
        assertTrue(FocusAreas.shown(7, focus = merged, trackedKnown = true, tracked = false))
        assertTrue(FocusAreas.shown(7, focus = merged, trackedKnown = true, tracked = true))     // 1427's case: tracked and focused
    }

    @Test fun otherQuestsAsBefore() {
        assertTrue(FocusAreas.shown(8, focus = 7, trackedKnown = true, tracked = true))          // a tracked quest: its outline
        assertFalse(FocusAreas.shown(9, focus = 7, trackedKnown = true, tracked = false))        // untracked, not the focus: none
        assertTrue(FocusAreas.shown(9, focus = 7, trackedKnown = false, tracked = false))        // before the first quests list: all
    }

    @Test fun noFocusMatchesNothing() {
        assertFalse(FocusAreas.shown(0, focus = 0, trackedKnown = true, tracked = false))
        assertFalse(FocusAreas.shown(9, focus = 0, trackedKnown = true, tracked = false))
        // a Polestar step with no quest in the log: no merge, no shown focus from it
        assertFalse(FocusAreas.shown(99, focus = PolestarMerge.quest(true, true, 99) { false }, trackedKnown = true, tracked = false))
    }
}
