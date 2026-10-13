package com.jrees.wowcompanion

/**
 * THE MAP ROOM'S QUEST LIST (pure: tools/map_test). The user after b104 (2026-10-07): "Show all of the quest with each zone in
 * collapsible tabs- selecting a quest opens it up where its at on the map- if I am looking at a zone, sort that zones quests to the
 * top of the list, pressing A focuses when looking at it in the current zone."
 *
 * Every quest of the log, grouped under its zone header (the quests document's `zone`, the game's own quest-log headers), in log
 * order; the group of the zone being looked at ([topZone]) goes first and is open; the others start collapsed (my pick, told to the
 * user) until the player opens them. [rows] flattens the open groups for the list cursor. [whereIs] answers which map shows a quest's
 * marker, so selecting a quest can take the map there.
 */
class MapQuestBook {
    sealed class Row {
        data class Header(val zone: String, val count: Int, val open: Boolean) : Row()
        data class Quest(val id: Int, val zone: String) : Row()
    }

    /** one quest of the log as the book needs it */
    class Entry(val id: Int, val zone: String)

    /** the zone being looked at: the headers of the quests on the shown map, then its own name (the log's headers are often a
     *  subzone: Elwynn's first quests sit under "Northshire Valley"). Their groups come first and open. Kept still while the list
     *  itself moves the map (UI Art: never re-sort under the cursor); set when the map is changed from the map's side. */
    var topZones: List<String> = emptyList()
    var topZone: String?
        get() = topZones.firstOrNull()
        set(v) { topZones = listOfNotNull(v) }
    private val toggled = HashMap<String, Boolean>()      // the player's own open / close, kept while the room lives

    fun isOpen(zone: String): Boolean = toggled[zone] ?: (zone in topZones)

    fun toggle(zone: String) { toggled[zone] = !isOpen(zone) }

    /** the groups in the list's order: the top zone's first, then the log's order of first appearance */
    fun groups(log: List<Entry>): List<Pair<String, List<Int>>> {
        val order = LinkedHashMap<String, MutableList<Int>>()
        for (e in log) if (e.id != 0) order.getOrPut(e.zone.ifEmpty { OTHER }) { ArrayList() }.add(e.id)
        val all = order.entries.map { it.key to it.value.toList() }
        val tops = topZones.mapNotNull { z -> all.firstOrNull { it.first == z } }
        return tops + all.filter { it.first !in topZones }
    }

    fun rows(log: List<Entry>): List<Row> {
        val out = ArrayList<Row>()
        for ((zone, ids) in groups(log)) {
            val open = isOpen(zone)
            out.add(Row.Header(zone, ids.size, open))
            if (open) for (id in ids) out.add(Row.Quest(id, zone))
        }
        return out
    }

    companion object {
        const val OTHER = "Other"

        /** the headers to put first for a map: those of the quests with a marker on it (log order), then the map's own name */
        fun topZonesFor(mapName: String, onMap: Collection<Int>, log: List<Entry>): List<String> {
            val out = ArrayList<String>()
            for (e in log) if (e.id in onMap) { val z = e.zone.ifEmpty { OTHER }; if (z !in out) out.add(z) }
            if (mapName.isNotEmpty() && mapName !in out) out.add(mapName)
            return out
        }

        /**
         * The map that shows quest [id]'s marker: a map named like its zone header that has the marker first, else any zone map that
         * has it, else a map named like the header (no marker known: at least its zone); null = nowhere known.
         * [zoneQuests] = Map.lua's mapquests (map -> the quests with a marker there); [names] = map id -> name; [isZone] = a zone /
         * city map (not the world or a continent).
         */
        fun whereIs(id: Int, zone: String, zoneQuests: Map<Int, List<Int>>, names: Map<Int, String>, isZone: (Int) -> Boolean): Int? {
            val withIt = zoneQuests.filter { (m, qs) -> id in qs && isZone(m) }.keys.sorted()
            withIt.firstOrNull { names[it] == zone }?.let { return it }
            withIt.firstOrNull()?.let { return it }
            return names.entries.filter { it.value == zone && isZone(it.key) }.map { it.key }.minOrNull()
        }
    }
}
