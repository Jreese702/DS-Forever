package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * bags-open1 (wk-containers; the user, 2026-11-16: "opening containers from my bags is a little cluncky, there is not a button although
 * weirdly x does work to open them, it opens the loot menu back in the home room, it should just open the loot menu inside of the bags and
 * then keep the player inside of the bag room."): THE LOOT WINDOW INSIDE THE BAGS ROOM.
 *
 * A box opened from the Bags room (its X, or a finger on its Open) opens the game's loot window. Since Interact i161 (capture v21) the
 * Bags room is SUSPENDED under that loot window instead of losing the controller: Bags is told nothing, its bags.ctl stays on, and the
 * interact document says `under: "bags"` while the "npc" hold drives the loot window. So the app stays in the Bags room and Interact's
 * own loot window (the compact window and its Make Space, the same A / X / B) is drawn over it; when the loot window closes the room
 * resumes on the same slot (rel by "resume", never "B"). A loot window from anywhere else (a corpse on Home, an older add-on, one opened
 * in a fight) has no `under` and keeps today's way.
 */
object BagsLoot {
    /** the interact document [doc] says the loot window holds OVER the Bags room (the room suspended under its own loot) */
    fun over(doc: JSONObject?): Boolean =
        doc != null && doc.optString("under") == "bags" && doc.optString("holder") == "npc" && doc.optJSONObject("loot") != null

    /**
     * A touch on the Home disc while it shows B closed a game window (InteractView.closeByTouch): in the Bags room with the loot window
     * over it that is the loot window's own B, one step back - the player stays in Bags. Anywhere else: Home, as the 2026-10-01 ruling.
     */
    fun discStaysInBags(roomIsBags: Boolean, lootOver: Boolean): Boolean = roomIsBags && lootOver
}
