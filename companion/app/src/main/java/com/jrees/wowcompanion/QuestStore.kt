package com.jrees.wowcompanion

/**
 * Who is asking, and the filter for the quest givers the game itself listed (the map's "!").
 *
 * THE BUNDLED GIVERS ARE GONE (the art harvest, the user 2026-10-09: no game data in the APK; "DROP the AzerothCore NPC pins"):
 * assets/quests_<continent>.json, baked from a 3.3.5a world database, and the filter that guessed from it. The "!" comes from
 * QuestieDB's available quests (Quests' qdbavail, MapView.deriveDbQuests) when Questie is installed, and from the givers the
 * game itself listed in a dialog (the harvester's map.qg) - both through [Me].
 */
object QuestStore {

    /** who is asking; [done] and [on] are quest ids */
    class Me(val level: Int, val race: Int, val cls: Int, val on: Set<Int>, val done: Set<Int>)

    /**
     * A RECORDED giver's offer list (harvester, map.qg): the game itself listed these quests in
     * its dialog, so level, race and prerequisites are already satisfied -- only "done since" and
     * "taken since" can have changed.
     */
    fun offersRecorded(ids: IntArray, me: Me): Boolean {
        for (id in ids) if (id !in me.done && id !in me.on) return true
        return false
    }
}
