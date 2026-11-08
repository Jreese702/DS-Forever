package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * THE TARGET RADIAL'S SLICES (the user, 2026-11-07: "When targeting something while not in the combat state, I want the Y glyph to
 * appear over them for the inspect menu. Instead of that menu opening a drop down I want to design a radial for it"; picks: the bottom
 * screen, tap Y / aim / A picks, the 8 most used + More). Pure: the game's own menu for the target (Quests q51, `pmenu` src "radial",
 * parsed by [PlayerMenu.parse]) laid on the pause wheel's eight slices.
 *
 * THE RING (the kit's order, 0 = the top, clockwise): Whisper, Invite, Trade, Inspect, Follow, Duel, Add Friend where the target's menu
 * has them, in those places; a place the menu does not fill takes the menu's next entry (an NPC: Set Focus, Raid Target Icon ...);
 * slice 7 is always More (the whole menu as a list). Titles are no slices; a greyed entry stays greyed.
 */
object TargetRadial {
    /** one slice: the menu [entry] it runs, or More ([entry] null, [more] true) */
    data class Slice(val label: String, val icon: String, val entry: PlayerMenu.Entry?, val more: Boolean = false) {
        val enabled: Boolean get() = more || entry?.enabled == true
    }

    /** the preferred places: the entry key's words (the mixin's name, lower case) and the slice's picture (the wheel's ricon_*) */
    private val PREFERRED = listOf(
        listOf("whisper") to "chat",
        listOf("invite") to "groupfinder",
        listOf("trade") to "bags",
        listOf("inspect") to "character",
        listOf("follow") to "map",
        listOf("duel") to "spellbook",
        listOf("addfriend", "addbtagfriend", "friend") to "social",
    )
    const val MORE = 7

    private fun keyOf(e: PlayerMenu.Entry) = (e.key ?: e.text).lowercase().replace(" ", "")

    private fun matches(e: PlayerMenu.Entry, words: List<String>): Boolean {
        val k = keyOf(e)
        if (words.first() == "invite" && (k.contains("uninvite") || k.contains("invitetoguild"))) return false
        return words.any { k.contains(it) }
    }

    /** the eight slices for a target's menu ([items] = PlayerMenu.parse of the document's items) */
    fun slices(items: List<PlayerMenu.Entry>): List<Slice?> {
        val usable = items.filter { !it.title }
        val out = arrayOfNulls<Slice>(8)
        val used = HashSet<PlayerMenu.Entry>()
        for ((i, p) in PREFERRED.withIndex()) {
            val e = usable.firstOrNull { it !in used && matches(it, p.first) } ?: continue
            out[i] = Slice(e.text, p.second, e); used += e
        }
        // the places left: the menu's next entries, in its order
        val rest = usable.filter { it !in used }.iterator()
        for (i in 0 until MORE) if (out[i] == null && rest.hasNext()) {
            val e = rest.next()
            out[i] = Slice(e.text, "quests", e)
        }
        out[MORE] = Slice("More", "settings", null, more = true)
        return out.toList()
    }

    /** the document's target name (nm), or "" */
    fun name(d: JSONObject): String = d.optString("nm", "")
}
