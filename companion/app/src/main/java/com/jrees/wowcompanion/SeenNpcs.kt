package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject

/**
 * THE NPCs THE GAME HAS SHOWN (the user, 2026-10-12, via Tracking: "Only NPCs actually seen"). The map's NPC pins were GHOSTS - "the
 * icon is on the minimap but nothing is in the game": QuestieDB's (classic) spots for NPCs WoW: Forever moved or removed. Now an NPC
 * is drawn only once DS Forever has SEEN it in the game:
 *   (a) its window opened (PoiHarvest, as before);
 *   (b) the game's soft target landed on it (the 10 Hz position document's soft: its creature id, within the gamepad's 20 yd);
 *   (c) a minimap dot names it (Map.lua's minimap probe, "mmprobe": a tracked town NPC's dot, its yards from the player).
 * QuestieDB stays the source of WHAT it is (kind, name, services), matched by its creature id, or for a probed dot by its name on
 * this map (the nearest of that name); it never places an NPC nobody has seen. A sighting within [SAME_YD] of QuestieDB's spot uses
 * QuestieDB's (exact); farther, the NPC is drawn where it was seen. World facts, not one character's: one store, in the app's files
 * (MapView keeps the file). Pure: tools/map_test.
 */
object SeenNpcs {
    const val SAME_YD = 40.0
    const val MOVED_YD = 5.0           // a later sighting this far from the stored spot moves it
    const val MAX_PER_MAP = 600

    /** [how]: 'o' opened, 's' soft target, 'p' minimap dot (the most exact spot) */
    class Seen(val entry: Int, var nx: Double, var ny: Double, var at: Long, var how: Char)

    private val byMap = HashMap<Int, LinkedHashMap<Int, Seen>>()

    /** bumps whenever what is drawn could change (a new NPC, a moved one): the map re-derives its pins */
    var version = 0
        private set
    var dirty = false
        private set

    fun has(map: Int, entry: Int): Boolean = byMap[map]?.containsKey(entry) == true
    fun get(map: Int, entry: Int): Seen? = byMap[map]?.get(entry)
    fun count(map: Int): Int = byMap[map]?.size ?: 0

    /**
     * Record a sighting of creature [entry] on map [map] at ([nx], [ny]) (the map's 0..1; [ax] / [ay] yards across / down). A soft-
     * target spot (near the player) never replaces a minimap dot's (exact). True = something drawn changed.
     */
    fun see(map: Int, entry: Int, nx: Double, ny: Double, how: Char, now: Long, ax: Double, ay: Double): Boolean {
        if (entry <= 0 || nx.isNaN() || ny.isNaN()) return false
        val m = byMap.getOrPut(map) { LinkedHashMap() }
        val s = m[entry]
        if (s == null) {
            m[entry] = Seen(entry, nx, ny, now, how)
            while (m.size > MAX_PER_MAP) m.remove(m.keys.first())
            dirty = true; version++
            return true
        }
        s.at = now
        dirty = true
        if (how == 's' && s.how == 'p') return false
        val d = Math.hypot((nx - s.nx) * ax, (ny - s.ny) * ay)
        if (d <= MOVED_YD && (how == s.how || how != 'p')) return false
        s.nx = nx; s.ny = ny; s.how = how
        version++
        return true
    }

    /** where to draw seen NPC [s]: QuestieDB's spot ([dbNx], [dbNy]) when the sighting is within SAME_YD of it, else where seen */
    fun spot(dbNx: Double, dbNy: Double, s: Seen, ax: Double, ay: Double): DoubleArray =
        if (Math.hypot((dbNx - s.nx) * ax, (dbNy - s.ny) * ay) <= SAME_YD) doubleArrayOf(dbNx, dbNy) else doubleArrayOf(s.nx, s.ny)

    /**
     * A minimap dot's spot in a map's 0..1 (Map.lua mp1: [north] / [west] yards from the player at world ([wx] north, [wy] west))
     * through the map's world rect (top / left world edges, [h] / [w] yards down / across).
     */
    fun dotSpot(wx: Double, wy: Double, north: Double, west: Double, top: Double, left: Double, h: Double, w: Double): DoubleArray =
        doubleArrayOf((left - (wy + west)) / w, (top - (wx + north)) / h)

    /** of [cands] (entry, nx, ny) with a probed dot's name, the one nearest the dot ([nx], [ny]); null when there is none */
    fun nearest(cands: List<Triple<Int, Double, Double>>, nx: Double, ny: Double, ax: Double, ay: Double): Int? =
        cands.minByOrNull { (_, x, y) -> Math.hypot((x - nx) * ax, (y - ny) * ay) }?.first

    // ---------------------------------------------------------------- the file (MapView reads and writes it)
    fun toJson(): String {
        val o = JSONObject()
        for ((map, m) in byMap) {
            val a = JSONArray()
            for (s in m.values) a.put(JSONArray(listOf(s.entry, round(s.nx), round(s.ny), s.at, s.how.toString())))
            o.put(map.toString(), a)
        }
        return JSONObject().put("v", 1).put("maps", o).toString()
    }

    fun load(json: String) {
        byMap.clear()
        val maps = runCatching { JSONObject(json).optJSONObject("maps") }.getOrNull() ?: return
        for (k in maps.keys()) {
            val map = k.toIntOrNull() ?: continue
            val a = maps.optJSONArray(k) ?: continue
            val m = LinkedHashMap<Int, Seen>()
            for (i in 0 until a.length()) {
                val r = a.optJSONArray(i) ?: continue
                val e = r.optInt(0, 0)
                if (e <= 0) continue
                m[e] = Seen(e, r.optDouble(1), r.optDouble(2), r.optDouble(3).toLong(), r.optString(4).firstOrNull() ?: 's')
            }
            if (m.isNotEmpty()) byMap[map] = m
        }
        version++
        dirty = false
    }

    fun saved() { dirty = false }
    fun clear() { byMap.clear(); version++; dirty = false }

    private fun round(v: Double) = Math.round(v * 100000.0) / 100000.0
}
