package com.jrees.wowcompanion

/**
 * MOVABLE HOME, PHASE 4: THE ROOM BAR'S ORDER (SPEC.txt sections 2, 7, 8 and 13; the user's D3 ruling, 2026-11-20: "Reorder only; every
 * room stays on the bar" - the 09-28 "every room on the bar" ruling holds, so there is no tray and no room ever leaves the bar).
 *
 * The order is a list of [Room] NAMES in the layout record ([HomeLayout.rooms]), so it is per character with "All characters" exactly as
 * the rest of the layout (D5). Pure: the JVM tests pin it (RoomBarTest).
 *  - [DEFAULT] is today's bar: Room's own order, the rooms that are on the bar (Chat and Calendar are reached another way).
 *  - [normalize] makes any saved list a whole bar: unknown or doubled names go, a room the list does not know (a room added by a later
 *    build) comes back next to the room it follows in [DEFAULT]. So a record can never take a room off the bar (D3).
 *  - The Home disc stays in the MIDDLE: [leftCount] rooms left of it, the rest right (today's split: 6 | 6 at Character).
 *  - [position] is a room's place left to right with the disc between the halves: the rooms' push slides by it (CompanionHost.barOrder),
 *    and the rooms off the bar count right after the bar room they follow in Room's order (Chat after Controller, as today).
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

    /**
     * a room's place on the bar, left to right, in steps of 2 (the disc between the halves; a room off the bar one after the bar room it
     * follows in Room's order): only its comparison with another room's place means anything
     */
    fun position(r: Room, order: List<String>): Int {
        val o = normalize(order)
        val left = leftCount(o.size)
        fun at(i: Int) = if (i < left) i * 2 else (i + 1) * 2
        if (r == Room.HOME) return left * 2
        val i = o.indexOf(r.name)
        if (i >= 0) return at(i)
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
