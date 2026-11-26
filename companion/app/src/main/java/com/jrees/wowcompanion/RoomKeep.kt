package com.jrees.wowcompanion

/**
 * A ROOM'S OWN SMALL STATE FOR A HOST MADE AGAIN (the bottom screen comes straight back, round 2 of the review, 2026-11-26: "every
 * room's tab and scroll ... lost on every bottom-grid return"; SPEC 4.4 step 5's restoreTab). A park keeps every view as it is; a
 * rebuild (a reopen from Cocoon's bottom grid relaunches the activity, a display truly gone and back, Android's own close, a memory
 * release) makes new views, so the old host keeps the SHOWN room's tab, page and scroll in its snapshot (BottomSync.HostUi.room) and
 * the new host lays them on that room when it comes back by the adopt rule (the user's Q3: the game still has its window open - on
 * that same tab, so nothing is tapped to put it there). Strings only: no view, no context.
 */
interface RoomKeep {
    /** the room's own state now (its tab or page, its scroll, an open page), by keys of its own; empty = nothing to keep */
    fun keepUi(): Map<String, String>
    /** [k] back on this room, just after it came by the adopt rule (its onShown ran: the room's own landing is laid over) */
    fun restoreUi(k: Map<String, String>)
}
