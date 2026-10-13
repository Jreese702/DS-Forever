package com.jrees.wowcompanion

/**
 * THE QUESTS ROOM AND THE GAME'S QUEST LOG (Quests.lua q25 + Map.lua mr2; the user's answer 2026-10-07 via tracking: "The Quests room
 * (Recommended)"). In this game the quest-log key opens the SAME window as the map (WorldMapFrame with its quest list). Map.lua
 * decides each open one frame after its OnShow: the quest-log route (the key, the radial's QuestLogMicroButton) is the QUESTS room's,
 * M is the Map room's. Pure (no Android types): tools/quests_test runs it.
 *   * the GAME opened its quest log: Quests.lua takes the controller in-game and sends {"type":"questwin","n","own":false,"shown":true};
 *     a CURRENT document whose n grew brings the room up ([gameOpened]; n grows across game reloads too, seeded from the server's second);
 *   * the room by touch / the bar: the hold first, then (like every room, "every room opens the game's window behind it") the game's
 *     quest log is opened behind it once the hold is real, through Map's SHARED open cell (mapw 4: armed while "map" or "pad:quests"
 *     holds and the map is closed) -- [wantOpen];
 *   * the way out the app takes: the game's close (mapw 5) while the quest log shows -- Quests.lua's OnHide then lets go itself --
 *     else the free ([leaveCol]). B that closes the window through the capture is the add-on's own (passed "PAD2").
 */
object QuestsWin {
    /** a CURRENT questwin document from the GAME's own open (not the room's): the Quests room comes up */
    fun gameOpened(lastN: Int, n: Int, own: Boolean, current: Boolean): Boolean = current && !own && n > lastN

    /**
     * Open the game's quest log behind the room now? Once: the room [held] really (not pending), the quest log is not shown (neither
     * by Quests.lua's word [qShown] nor by Map's [mapOpen]), Map's open cell is [armed], the add-on publishes the row ([hasRow]).
     */
    fun wantOpen(held: Boolean, qShown: Boolean, mapOpen: Boolean, armed: Boolean, openTapped: Boolean, hasRow: Boolean): Boolean =
        held && !qShown && !mapOpen && armed && !openTapped && hasRow

    /** leaving: Map's close cell while the quest log shows (the add-on lets go on its OnHide), else null = the room's free */
    fun leaveCol(qShown: Boolean, mapOpen: Boolean, hasRow: Boolean): Int? =
        if ((qShown || mapOpen) && hasRow) MapRoomPad.CLOSE else null

    /** how the room leaves: the close only / the close and then the free (spaced) / the free only */
    enum class Leave { CLOSE, CLOSE_THEN_FREE, FREE }

    /**
     * The way out (RC3, the user: "sometimes when closing the quests room the map opens on the top screen"; Map's reading of it): the
     * close while the quest log shows; BOTH, the close first, while the room's own OPEN tap may still be on its way ([openPending]: tapped,
     * no "shown" heard yet) -- if it lands, the close shuts it and the add-on lets go on the OnHide; if it never did, the close carries
     * nothing (Map arms it only while the map shows) and the free lets go; else the free. (Quests.lua q37 also refuses a free while the
     * map shows -- "close first" -- so a free that still beats the open costs a close tap, never an open map.)
     */
    fun leavePlan(qShown: Boolean, mapOpen: Boolean, openPending: Boolean, hasRow: Boolean): Leave = when {
        !hasRow -> Leave.FREE
        qShown || mapOpen -> Leave.CLOSE
        openPending -> Leave.CLOSE_THEN_FREE
        else -> Leave.FREE
    }
}
