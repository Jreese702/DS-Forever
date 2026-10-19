package com.jrees.wowcompanion

import java.io.File
import java.io.InputStream

/**
 * QUESTIEDB'S OBJECTS, READ BY THE APP (SafePath device step 11, TravelLinks): the portals a character can step through are game objects
 * QuestieDB lists by name with their spawns ("Skyborne Portal to Stormwind", "Portal to Valanaar"). Their data sits in the same toc as the
 * creatures' (QuestieNpcs), as "## X-Object-<id>-<field>: <base64 CBOR>" lines (a value too long for one line is "~N~" and its parts
 * "-1".."-N"): the scalar row "-S" (field 1 name, 5 zoneID) and the spawns "-4" (area id -> [[x, y], ...], percent of that area's map;
 * QuestieDB's objectMeta: 1 name, 2 questStarts, 3 questEnds, 4 spawns, 5 zoneID, 6 factionID, 7 waypoints). Measured on
 * QuestieDB-Forever: 13,714 object lines. Only the objects whose name [read]'s filter keeps are decoded. No positions are typed here:
 * they are the player's own QuestieDB's, read at run time. Pure but for reading the file.
 */
object QuestieObjects {
    /** one object: [id], [name], QuestieDB's [zone], and its [spawns] per area id ([x, y] in that area's map, 0..1) */
    class Obj(val id: Int, val name: String, val zone: Int, val spawns: Map<Int, List<DoubleArray>>)

    fun read(toc: File, keep: (String) -> Boolean): List<Obj> = toc.inputStream().buffered(1 shl 16).use { read(it, keep) }

    fun read(input: InputStream, keep: (String) -> Boolean): List<Obj> {
        val scalar = HashMap<Int, String>()
        val spawns = HashMap<Int, String>()
        val chunks = HashMap<String, Array<String?>>()
        val reader = input.bufferedReader(Charsets.UTF_8)
        while (true) {
            val line = reader.readLine() ?: break
            if (!line.startsWith(PREFIX)) continue
            val colon = line.indexOf(": ", PREFIX.length)
            if (colon < 0) continue
            val p = line.substring(PREFIX.length, colon).split('-')
            val value = line.substring(colon + 2).trim()
            val id = p[0].toIntOrNull() ?: continue
            val field = p.getOrNull(1) ?: continue
            if (field != "S" && field != "4") continue
            val target = if (field == "S") scalar else spawns
            if (p.size == 2) {
                val n = Regex("^~(\\d+)~$").find(value)?.groupValues?.get(1)?.toIntOrNull()
                if (n != null) chunks["$id-$field"] = arrayOfNulls(n) else target[id] = value
            } else {
                val part = p[2].toIntOrNull() ?: continue
                val arr = chunks["$id-$field"] ?: continue
                if (part in 1..arr.size) arr[part - 1] = value
                if (arr.all { it != null }) { target[id] = arr.joinToString(""); chunks.remove("$id-$field") }
            }
        }
        val out = ArrayList<Obj>()
        for ((id, s) in scalar) {
            val row = runCatching { QuestieNpcs.Cbor.decode(b64(s)) as? Map<*, *> }.getOrNull() ?: continue
            val name = str(row[1L]) ?: continue
            if (!keep(name)) continue
            val zone = (row[5L] as? Long)?.toInt() ?: 0
            val sp = spawns[id]?.let { v -> runCatching { when (val d = QuestieNpcs.Cbor.decode(b64(v))) {
                is Map<*, *> -> d
                is List<*> -> d.withIndex().associate { (i, x) -> (i + 1).toLong() to x }     // areas 1..n written as a sequence
                else -> null } }.getOrNull() } ?: emptyMap<Any?, Any?>()
            val byArea = HashMap<Int, MutableList<DoubleArray>>()
            for ((a, list) in sp) {
                val area = (a as? Long)?.toInt() ?: continue
                for (pt in (list as? List<*>) ?: continue) {
                    val xy = pt as? List<*> ?: continue
                    val x = (xy.getOrNull(0) as? Number)?.toDouble() ?: continue
                    val y = (xy.getOrNull(1) as? Number)?.toDouble() ?: continue
                    if (x < 0.0 || y < 0.0) continue                     // QuestieDB's -1, -1: an instance spawn
                    byArea.getOrPut(area) { ArrayList() }.add(doubleArrayOf(x / 100.0, y / 100.0))
                }
            }
            out.add(Obj(id, name, zone, byArea))
        }
        return out.sortedBy { it.id }
    }

    /**
     * A PORTAL BY ITS NAME (a link KIND by QuestieDB's object name; typed words, no positions): "<anything> Portal to <place>" (also "Portal
     * To"). The place's words, or null when the name is no portal to a named place ("Demon Portal", "Naxxramas Portal" - an instance's
     * door or a quest's prop, not a way between maps)
     */
    val PORTAL = Regex("^(?:.+ )?Portal [Tt]o (.+)$")
    fun portalPlace(name: String): String? = PORTAL.find(name.trim())?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * The portals among [objs] as TravelLinks' portals: each spawn put on its area's uiMap ([areaMap]: the game's UiMapAssignment, as
     * DangerStore's) and into world yards ([toWorld]: map, x, y in 0..1 -> [wx, wy]; MapView's worldOf), its destination's map by
     * [placeMap] (the place's words -> a uiMap, null = not known: the landing's map is then only known once learned). An object with no
     * spawn (QuestieDB lists the Skyborne Portal to Dalaran with none) gives no portal.
     */
    fun portals(objs: List<Obj>, areaMap: (Int) -> Int?, toWorld: (Int, Double, Double) -> DoubleArray?,
                placeMap: (String) -> Int?): List<TravelLinks.Portal> {
        val out = ArrayList<TravelLinks.Portal>()
        for (o in objs) {
            val place = portalPlace(o.name) ?: continue
            val to = placeMap(place)
            for ((area, pts) in o.spawns) {
                val m = areaMap(area) ?: continue
                for (pt in pts) {
                    val w = toWorld(m, pt[0], pt[1]) ?: continue
                    out.add(TravelLinks.Portal(o.id, o.name, m, w[0], w[1], place, to))
                }
            }
        }
        return out
    }

    private fun str(v: Any?): String? = (v as? ByteArray)?.toString(Charsets.UTF_8) ?: (v as? String)
    private const val PREFIX = "## X-Object-"
    private fun b64(s: String): ByteArray = java.util.Base64.getMimeDecoder().decode(s)
}
