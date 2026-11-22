package com.jrees.wowcompanion

/**
 * MOVABLE HOME, PHASE 4: THE ROOM BAR'S ORDER (SPEC.txt sections 2, 7, 8 and 13) - and, since test build 1518's build 2 (the user's
 * ruling 2026-11-21: "just have the individual icons wiggle like their own apps"), WHICH ROOMS ARE ON IT: each room is its own app, a
 * "-" badge hides it from the bar, a "+" spot at the bar's end puts it back. Home (the disc) and Settings (the room every setting is
 * reached from) can never be hidden ([UNHIDEABLE]): the player can always get back.
 *
 * The order is a list of [Room] NAMES in the layout record ([HomeLayout.rooms]) and the hidden rooms another ([HomeLayout.hidden]), so
 * both are per character exactly as the rest of the layout (D5). Pure: the JVM tests pin it (RoomBarTest).
 *  - [DEFAULT] is today's bar: Room's own order, the rooms that are on the bar (Chat and Calendar are reached another way).
 *  - [normalize] makes any saved list a WHOLE order: unknown or doubled names go, a room the list does not know (a room added by a
 *    later build) comes back next to the room it follows in [DEFAULT]. The order always holds every bar room - a hidden one keeps its
 *    place in it, so it comes back exactly where it was ([visible] leaves it out of the bar).
 *  - The Home disc stays in the MIDDLE: [leftCount] rooms left of it, the rest right (today's split: 6 | 6 at Character).
 *  - [position] is a room's place left to right with the disc between the halves: the rooms' push slides by it (CompanionHost.barOrder);
 *    the rooms off the bar (and the hidden ones) count right after the bar room they follow (Chat after Controller, as today).
 */
object RoomBar {
    /** today's bar, left to right (Room's order, the rooms on the bar) */
    val DEFAULT: List<String> = Room.values().filter { it != Room.HOME && it.onBar }.map { it.name }

    /** a whole bar from [order]: known bar rooms in its order (once each), any bar room it misses put back where DEFAULT has it */
    fun normalize(order: List<String>?): List<String> {
        if (order == null) return DEFAULT
        val out = ArrayList<String>()
        for (n in order) if (n in DEFAULT && n !in out) out += n
        if (out.size == DEFAULT.size) return out
        for ((i, n) in DEFAULT.withIndex()) {
            if (n in out) continue
            // after the room it follows in DEFAULT (else first)
            val prev = (i - 1 downTo 0).map { DEFAULT[it] }.firstOrNull { it in out }
            out.add(if (prev == null) 0 else out.indexOf(prev) + 1, n)
        }
        return out
    }

    fun isDefault(order: List<String>): Boolean = normalize(order) == DEFAULT

    /** the rooms of a (normalized) order */
    fun rooms(order: List<String>): List<Room> = normalize(order).mapNotNull { n -> Room.values().firstOrNull { it.name == n } }

    /** how many rooms stand left of the Home disc (today's 6 of 12; an odd bar gives the left one more, as the old fallback did) */
    fun leftCount(n: Int): Int = (n + 1) / 2

    /** [order] with the room at [from] moved to [to] (the others close up behind it) */
    fun move(order: List<String>, from: Int, to: Int): List<String> {
        if (from !in order.indices || to !in order.indices || from == to) return order
        val out = order.toMutableList()
        val r = out.removeAt(from)
        out.add(to, r)
        return out
    }

    /** the rooms that can never leave the bar (Home is the disc, never in the list): the player's way to every setting */
    val UNHIDEABLE: Set<String> = setOf(Room.SETTINGS.name)
    fun canHide(r: String): Boolean = r in DEFAULT && r !in UNHIDEABLE

    /** a saved hidden list as the record keeps it: known bar rooms that can be hidden, once each, in [DEFAULT]'s order (never Settings:
     *  a hand-edited record can never strand the player) */
    fun hiddenOf(names: List<String>?): List<String> = if (names.isNullOrEmpty()) emptyList() else DEFAULT.filter { it in names && canHide(it) }

    /** the rooms the bar shows, left to right: the whole order less the hidden ones */
    fun visible(order: List<String>, hidden: List<String>): List<String> {
        val o = normalize(order)
        return if (hidden.isEmpty()) o else o.filter { it !in hidden }
    }
    /** the rooms of [l]'s bar as drawn (the Strip's order) */
    fun shown(l: HomeLayout): List<Room> = visible(l.rooms, l.hidden).mapNotNull { n -> Room.values().firstOrNull { it.name == n } }

    /** a bar room's name in words (the hidden rooms' tray: the picture and its name) */
    fun word(r: String): String = when (r) {
        "MAP" -> "Map"; "QUESTS" -> "Quests"; "SPELLBOOK" -> "Spellbook"; "TALENTS" -> "Talents"; "PROFESSIONS" -> "Professions"
        "GROUPFINDER" -> "Group Finder"; "CHARACTER" -> "Character"; "BAGS" -> "Bags"; "CONTROLLER" -> "Controller"; "SOCIAL" -> "Social"
        "MACROS" -> "Macros"; "SETTINGS" -> "Settings"
        else -> r.lowercase().replaceFirstChar { it.uppercase() }
    }

    /** [r] hidden ([l]'s list as the record keeps it; a room that cannot be hidden: unchanged) */
    fun hide(l: List<String>, r: String): List<String> = if (!canHide(r) || r in l) hiddenOf(l) else hiddenOf(l + r)
    fun unhide(l: List<String>, r: String): List<String> = hiddenOf(l - r)

    /**
     * the VISIBLE room at [from] moved to visible place [to] (the others close up behind it), as a whole order again: every hidden room
     * goes back right after the visible room it followed (first, when none did), so it returns beside its old neighbour
     */
    fun moveVisible(order: List<String>, hidden: List<String>, from: Int, to: Int): List<String> {
        val full = normalize(order)
        if (hidden.isEmpty()) return move(full, from, to)
        val vis = full.filter { it !in hidden }
        if (from !in vis.indices || to !in vis.indices || from == to) return full
        val moved = move(vis, from, to)
        // each hidden room's anchor: the visible room before it in the whole order (null = it led the bar)
        val out = ArrayList<String>(full.size)
        val lead = ArrayList<String>()
        val after = HashMap<String, MutableList<String>>()
        var anchor: String? = null
        for (n in full) {
            if (n !in hidden) { anchor = n; continue }
            if (anchor == null) lead += n else after.getOrPut(anchor) { ArrayList() } += n
        }
        out += lead
        for (n in moved) { out += n; after[n]?.let { out += it } }
        return out
    }

    /**
     * a room's place on the bar, left to right, in steps of 2 (the disc between the halves; a hidden room one after the visible room it
     * follows in the order, a room off the bar one after the bar room it follows in Room's order): only its comparison with another
     * room's place means anything
     */
    fun position(r: Room, order: List<String>, hidden: List<String> = emptyList()): Int {
        val full = normalize(order)
        val o = if (hidden.isEmpty()) full else full.filter { it !in hidden }
        val left = leftCount(o.size)
        fun at(i: Int) = if (i < left) i * 2 else (i + 1) * 2
        if (r == Room.HOME) return left * 2
        val i = o.indexOf(r.name)
        if (i >= 0) return at(i)
        val h = full.indexOf(r.name)
        if (h >= 0) {
            // hidden: right after the visible room before it in the whole order (before the first one when none is)
            for (k in h - 1 downTo 0) { val j = o.indexOf(full[k]); if (j >= 0) return at(j) + 1 }
            return -1
        }
        val all = Room.values()
        for (k in r.ordinal - 1 downTo 0) {
            val p = all[k]
            if (p == Room.HOME) return left * 2 + 1
            val j = o.indexOf(p.name)
            if (j >= 0) return at(j) + 1
        }
        return -1
    }
}
