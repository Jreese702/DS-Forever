package com.jrees.wowcompanion

/**
 * WHICH QUESTS' AREAS HOME'S MAP DRAWS (pure: no Android types). The user, 2026-11-11, on 1428: "The newest update isn't showing the
 * questie quest areas for the focused quest" - on Home's small map only; the Map room showed them.
 *
 * Home's map (MapView) drew a quest's areas only while the game's tracker lists it (trackedNo). The Map room (WorldMapRoomView) draws
 * the game's focused quest's QuestieDB areas whether it is tracked or not, and Quests' add-on sends a focused quest's spots tracked or
 * not (areasBuild: q.focus or q.watch). Since 1428 the merged light blue row ([PolestarMerge]) IS Home's focused quest - its card,
 * the list's one row (its own tracked / untracked row left out) - so a merged quest the tracker does not list (Polestar's step on a
 * logged but untracked quest, or one untracked with X on the merged row, MapCursor 1428) was Home's focused quest with no areas.
 *
 * Now the SHOWN focus ([focus]: MapView.shownFocus(), the merged Polestar quest counted) always has its areas, filled, as a tracked
 * focused quest's in 1427; every other quest as before: drawn while tracked, or every quest before the first quests list
 * ([trackedKnown] false).
 */
object FocusAreas {
    fun shown(qid: Int, focus: Int, trackedKnown: Boolean, tracked: Boolean): Boolean =
        !trackedKnown || tracked || (focus > 0 && qid == focus)
}
