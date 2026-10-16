package com.jrees.wowcompanion

import kotlin.math.abs

/**
 * THE MAP MODULE'S LIST, top to bottom (pure: no Android types). The user, 2026-10-16: "I want an untracked quest section under my
 * tracked quests, with tabs dividing them similar to in the quests book. all of them should be collapsed by default. They should be
 * sorted by Current zone->Class/Profession quests->closest to current level. above the quests should be my own Character Portrait".
 *
 *   [Item.Me]         the player's portrait (highlighted: the map centres on the player; A: the game's own player menu)
 *   [Item.Tracked]    the tracked quests, as before (their order is the view's: the focused one on top)
 *   [Item.Section]    the "Untracked" line (not a cursor stop)
 *   [Item.Group]      one tab per quest-log header (the game's own headers, as the quest book groups them), closed until opened
 *   [Item.Untracked]  the quests of an open tab
 *
 * Tab order: the zone the player is in (the headers of the quests whose marker is on the player's map, then the zone's and subzone's
 * own names), then the class and profession headers (the add-on names them: the player's class and skill lines), then the rest by
 * the level of their quest nearest the player's (the log's order on a tie). Inside a tab: nearest the player's level first.
 * Every item has a [Item.key]: a quest its id, the portrait [ME], a tab [groupKey] (negative, so never a quest's).
 */
object MapList {
    const val ME = -1
    private const val SECTION = -2

    sealed class Item {
        abstract val key: Int
        object Me : Item() { override val key = ME }
        data class Tracked(val id: Int) : Item() { override val key get() = id }
        data class Section(val count: Int) : Item() { override val key get() = SECTION }
        data class Group(val zone: String, val count: Int, val open: Boolean) : Item() { override val key get() = groupKey(zone) }
        data class Untracked(val id: Int, val zone: String) : Item() { override val key get() = id }
    }

    /** one untracked quest of the log: its id, its log header, its level, whether its marker is on the player's map */
    class Entry(val id: Int, val zone: String, val level: Int?, val here: Boolean)

    /** a tab's cursor key: negative and below the portrait's and the section's */
    fun groupKey(zone: String): Int = -(3 + (zone.hashCode() and 0x3fffffff))

    /** a key that is a tab's */
    fun isGroup(key: Int): Boolean = key <= -3

    private fun gap(e: Entry, plv: Int?): Int = if (e.level == null || plv == null) Int.MAX_VALUE else abs(e.level - plv)

    /**
     * The tabs in their order, each with its quests in theirs. [current] = the names of the player's zone and subzone; [special] =
     * the headers that are the player's class or professions.
     */
    fun groups(log: List<Entry>, plv: Int?, current: Collection<String>, special: Collection<String>): List<Pair<String, List<Int>>> {
        val byZone = LinkedHashMap<String, MutableList<Entry>>()
        for (e in log) if (e.id > 0) byZone.getOrPut(e.zone.ifEmpty { MapQuestBook.OTHER }) { ArrayList() }.add(e)
        val here = LinkedHashSet<String>()
        for ((z, es) in byZone) if (es.any { it.here }) here.add(z)
        for (z in current) if (z.isNotEmpty() && byZone.containsKey(z)) here.add(z)
        val order = byZone.keys.toList()
        fun nearest(z: String): Int = byZone[z]!!.minOf { gap(it, plv) }
        fun rank(z: String): Int = when { z in here -> 0; z in special -> 1; else -> 2 }
        val sorted = order.sortedWith(compareBy<String>({ rank(it) }, { if (rank(it) == 0) here.indexOf(it) else nearest(it) },
            { order.indexOf(it) }))
        return sorted.map { z ->
            val es = byZone[z]!!
            z to es.sortedWith(compareBy<Entry>({ gap(it, plv) }, { es.indexOf(it) })).map { it.id }
        }
    }

    /** the whole list; [isOpen] says which tabs the player opened (none is, until then) */
    fun items(me: Boolean, tracked: List<Int>, groups: List<Pair<String, List<Int>>>, isOpen: (String) -> Boolean): List<Item> {
        val out = ArrayList<Item>()
        if (me) out.add(Item.Me)
        for (id in tracked) out.add(Item.Tracked(id))
        if (groups.isNotEmpty()) {
            out.add(Item.Section(groups.sumOf { it.second.size }))
            for ((z, ids) in groups) {
                val open = isOpen(z)
                out.add(Item.Group(z, ids.size, open))
                if (open) for (id in ids) out.add(Item.Untracked(id, z))
            }
        }
        return out
    }

    /** the cursor's stops, in order (the section line is none) */
    fun stops(items: List<Item>): List<Int> = items.filter { it !is Item.Section }.map { it.key }

    /** the untracked quests the list shows */
    fun untracked(items: List<Item>): Set<Int> = items.filterIsInstance<Item.Untracked>().map { it.id }.toSet()
}
