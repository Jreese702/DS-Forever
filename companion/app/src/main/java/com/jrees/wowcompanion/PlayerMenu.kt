package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject

/**
 * THE PLAYER MENU ON THE BOTTOM SCREEN (the user, 2026-10-16, via tracking: "no not the menu on the top screen, I want a replication
 * of the menu on the bottom screen basically that the controller still drives"). The game's own menu for the player's portrait, as
 * Quests.lua q41 reads it ({"type":"pmenu"}): its entries in the game's words and order, greyed where the game greys them (or where
 * the companion may not run them: focus), checked where it checks them, with their sub-menus. Pure (no Android types), driven by the
 * map module: the D-pad walks the rows, A opens a sub-menu or runs an entry (the menu closes, as the game's does), B goes back a
 * level or closes. A title row (the game's own heading, the player's name) is no stop.
 */
class PlayerMenu {
    data class Entry(val text: String, val id: Int?, val enabled: Boolean, val checked: Boolean?, val title: Boolean, val sub: List<Entry>?)

    sealed class Do {
        object None : Do()
        object Close : Do()
        data class Run(val id: Int, val text: String) : Do()
        data class Refused(val why: String) : Do()
    }

    var open = false
        private set
    /** waiting for the game's answer to the read */
    var loading = false
        private set
    /** the game had no menu this build can read (or nothing to show) */
    var empty = false
        private set
    private var root: List<Entry> = emptyList()
    private val path = ArrayList<Int>()          // the sub-menus opened, by their row in the level above
    private val sels = arrayListOf(0)            // the selected row of each level

    fun openReading() { open = true; loading = true; empty = false; path.clear(); sels.clear(); sels.add(0) }
    fun close() { open = false; loading = false; path.clear(); sels.clear(); sels.add(0) }

    /** the game's document; the open levels stay where they are while they still exist */
    fun onDoc(d: JSONObject) {
        root = parse(d.optJSONArray("items"))
        loading = false
        empty = root.none { !it.title }
        while (path.isNotEmpty() && level(path.size).isEmpty()) { path.removeAt(path.size - 1); sels.removeAt(sels.size - 1) }
        val rows = rows()
        sels[sels.size - 1] = firstStop(rows, sels.last())
    }

    /** the rows of the level shown now */
    fun rows(): List<Entry> = level(path.size)
    /** the heading over the level shown now: the game's title row on top, a sub-menu's own words below it */
    fun heading(): String? = if (path.isEmpty()) root.firstOrNull { it.title }?.text else level(path.size - 1).getOrNull(path.last())?.text
    val sel: Int get() = sels.last()
    val depth: Int get() = path.size

    private fun level(n: Int): List<Entry> {
        var l = root
        for (k in 0 until n) l = l.getOrNull(path[k])?.sub ?: return emptyList()
        return l
    }

    private fun firstStop(rows: List<Entry>, from: Int): Int {
        if (rows.isEmpty()) return 0
        val i = from.coerceIn(0, rows.size - 1)
        if (!rows[i].title) return i
        return (i until rows.size).firstOrNull { !rows[it].title } ?: (0 until i).lastOrNull { !rows[it].title } ?: i
    }

    /** a touch on row [i]: it is selected and chosen at once */
    fun touch(i: Int): Do {
        val rows = rows()
        if (i !in rows.indices || rows[i].title) return Do.None
        sels[sels.size - 1] = i
        return key("PAD1")
    }

    /** one press while the menu is open (every key is the menu's) */
    fun key(k: String): Do {
        if (!open) return Do.None
        val rows = rows()
        when (k) {
            "PAD2" -> {
                if (path.isEmpty()) { close(); return Do.Close }
                path.removeAt(path.size - 1); sels.removeAt(sels.size - 1)
                return Do.None
            }
            "PADDUP", "PADDDOWN" -> {
                val step = if (k == "PADDDOWN") 1 else -1
                var i = sel + step
                while (i in rows.indices && rows[i].title) i += step
                if (i in rows.indices) sels[sels.size - 1] = i
                return Do.None
            }
            "PAD1" -> {
                val e = rows.getOrNull(sel) ?: return Do.None
                if (e.title) return Do.None
                if (!e.enabled) return Do.Refused("Not available now")
                if (e.sub != null) {
                    if (e.sub.none { !it.title }) return Do.Refused("Nothing in it now")
                    path.add(sel); sels.add(firstStop(e.sub, 0))
                    return Do.None
                }
                val id = e.id ?: return Do.None
                close()
                return Do.Run(id, e.text)
            }
        }
        return Do.None
    }

    companion object {
        /** an entry's id as the add-on's two digit taps: the first digit's column, then the second's (Quests.lua q41: 33 - digit) */
        fun digitCols(id: Int): List<Int> = listOf(33 - (id / 8) % 8, 33 - id % 8)

        fun parse(a: JSONArray?): List<Entry> {
            if (a == null) return emptyList()
            val out = ArrayList<Entry>(a.length())
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val t = o.optString("t", "")
                if (t.isEmpty()) continue
                out.add(Entry(t, if (o.has("i")) o.optInt("i") else null, o.optInt("en", 1) == 1,
                    if (o.has("ck")) o.optInt("ck") == 1 else null, o.optInt("ttl", 0) == 1,
                    if (o.has("sub")) parse(o.optJSONArray("sub")) else null))
            }
            return out
        }
    }
}
