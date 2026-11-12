package com.jrees.wowcompanion

/**
 * WHICH QUESTS' AREAS HOME'S MAP DRAWS, AND WHICH ONE IS FILLED (pure: no Android types; MapView.deriveQuestAreas draws what this
 * picks). The user, 2026-11-11, on 1428: "The newest update isn't showing the questie quest areas for the focused quest" - on Home's
 * small map only; the Map room showed them.
 *
 * THE CAUSE IS NOT PROVEN. Nothing in 1427..1428 (bd56e2df..097503d8) changes Home's area decision or drawing: with the same game
 * tracker state 1428 picks exactly what 1427 picked. The HYPOTHESIS: Home's map draws a quest's areas only while the game's tracker
 * lists it, while the Map room (WorldMapRoomView) draws the game's focused quest's areas tracked or not; so a focused quest that is in
 * the log but not tracked shows areas in the Map room and none on Home. 1428 makes that state easier to reach and harder to see: the
 * merged light blue row ([PolestarMerge]) hides the quest's own tracked / untracked row, and X on the merged row (MapCursor, 1428)
 * untracks it. Device check before release: is the merged quest in the game's tracker?
 *
 * The one change from 1427 (parity otherwise): the MERGED Polestar quest ([In.merged], MapView.psMerged()) is drawn tracked or not -
 * it IS the focus ([In.st] = psFocus() whenever merged), so filled. Any other untracked quest - also the game's focus or QuestTrail's
 * step on an untracked quest - draws nothing, as in 1427. Its ready / done skip reads the log entry when the tracker does not list it.
 * LIMIT: the add-on (Quests.lua areasBuild: q.focus or q.watch) sends no QuestieDB spots for an untracked quest unless it is the
 * game's super-tracked focus, so an untracked merged quest that is not the game's focus still has no QuestieDB area to draw.
 */
object FocusAreas {
    /** [tracked]: the tracker's quest ids (MapView.trackedNo); [trackedRD] / [logRD]: ready-or-done by id, of the tracked entries /
     *  of every logged quest (questById); [trackedKnown]: a quests list has come (before it, every quest is drawn, as 1427) */
    class In(
        val trackedKnown: Boolean,
        val tracked: Set<Int>,
        val st: Int,
        val merged: Int,
        val trackedRD: Map<Int, Boolean> = emptyMap(),
        val logRD: Map<Int, Boolean> = emptyMap(),
    )
    /** [index]: the quest's place in the map document's "quests" (-1: a QuestieDB-only quest) */
    data class Pick(val qid: Int, val focused: Boolean, val index: Int = -1)

    /** drawn at all: tracked, the merged Polestar quest, or anything before the first quests list */
    fun shown(qid: Int, s: In): Boolean = !s.trackedKnown || qid in s.tracked || (s.merged > 0 && qid == s.merged)

    private fun readyOrDone(qid: Int, s: In): Boolean =
        s.trackedRD[qid] ?: (if (s.merged > 0 && qid == s.merged) s.logRD[qid] else null) ?: false

    /** QuestieDB-only quests: [qdbQids] (this map's qdbareas objectives' quests, in order) the game places no point for ([onMap]) */
    fun qdbOnly(s: In, qdbQids: List<Int>, onMap: Set<Int>): List<Pick> =
        qdbQids.distinct().filter { it !in onMap && shown(it, s) && !readyOrDone(it, s) }.map { Pick(it, it == s.st) }

    /** the map document's quests ([quests]: id, done in order): skipped when done (the "?" at the hand-in) or not drawn */
    fun onMap(s: In, quests: List<Pair<Int, Boolean>>): List<Pick> =
        quests.mapIndexedNotNull { i, (id, done) ->
            if (done || !shown(id, s)) null else Pick(id, id != 0 && id == s.st, i)
        }
}
