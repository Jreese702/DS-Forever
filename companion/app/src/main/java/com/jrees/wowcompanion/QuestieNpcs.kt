package com.jrees.wowcompanion

import java.io.File
import java.io.InputStream

/**
 * QUESTIEDB'S CREATURES, READ BY THE APP (b-danger-layer; the user, 2026-10-17: "map out the safe paths on maps to navigate around
 * those, to keep me from navigating into mountains or hostile mobs" - the mobs part is ours). QuestieDB (the add-on the app installs
 * itself, DependencyAddons) keeps its data in its .toc as metadata lines, "## X-Npc-<id>-<field>: <base64 CBOR>" (a value too long
 * for one line is "~N~" and its N parts "-1".."-N", joined before decoding): the scalar row "-S" (a CBOR map of field -> value:
 * 1 name, 4 / 5 min / max level, 6 rank, 13 friendlyToFaction) and the spawns "-7" (area id -> [[x, y], ...], percent of that area's
 * map). Measured on QuestieDB-Forever 1.0.5: 28,399 creatures. Hostility is QuestieDB's own: no field 13 = attacks both factions,
 * "AH" = attacks neither (a neutral, ignored: the user's pick), "A" / "H" = friendly to that side, hostile to the other.
 * Pure but for reading the file: the toc in, the hostile spawns out.
 */
object QuestieNpcs {
    /** one hostile spawn: on area [area]'s map at ([x], [y]) (0..1), levels [minLv]..[maxLv], [elite], hostile to [hostile]
     *  (TransportRoutes.ALLIANCE / HORDE bits), the creature's name [name] */
    class Spawn(val area: Int, val x: Float, val y: Float, val minLv: Int, val maxLv: Int, val elite: Boolean, val hostile: Int,
                val name: String)

    /** every hostile creature's spawns in the toc at [toc] (QuestieDB_Forever.toc / _Camelot.toc) */
    fun read(toc: File): List<Spawn> = toc.inputStream().buffered(1 shl 16).use { read(it) }

    fun read(input: InputStream): List<Spawn> {
        val scalar = HashMap<Int, String>()
        val spawns = HashMap<Int, String>()
        val chunks = HashMap<String, Array<String?>>()            // "<id>-<field>" -> its parts while they come
        val reader = input.bufferedReader(Charsets.UTF_8)
        while (true) {
            val line = reader.readLine() ?: break
            if (!line.startsWith(PREFIX)) continue
            val colon = line.indexOf(": ", PREFIX.length)
            if (colon < 0) continue
            val key = line.substring(PREFIX.length, colon)          // "<id>-<field>" or "<id>-<field>-<part>"
            val value = line.substring(colon + 2).trim()
            val p = key.split('-')
            val id = p[0].toIntOrNull() ?: continue
            val field = p.getOrNull(1) ?: continue
            if (field != "S" && field != "7") continue
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
        val out = ArrayList<Spawn>()
        for ((id, s) in scalar) {
            val row = runCatching { Cbor.decode(b64(s)) as? Map<*, *> }.getOrNull() ?: continue
            val friendly = (row[13L] as? ByteArray)?.toString(Charsets.UTF_8) ?: (row[13L] as? String)
            val hostile = when (friendly) { null -> 3; "A" -> TransportRoutes.HORDE; "H" -> TransportRoutes.ALLIANCE; else -> 0 }
            if (hostile == 0) continue                                 // "AH": a neutral (ignored) or friendly to all
            val sp = spawns[id] ?: continue
            val areas = runCatching { Cbor.decode(b64(sp)) as? Map<*, *> }.getOrNull() ?: continue
            val minLv = (row[4L] as? Long)?.toInt() ?: 0
            val maxLv = (row[5L] as? Long)?.toInt() ?: minLv
            val rank = (row[6L] as? Long)?.toInt() ?: 0
            val name = (row[1L] as? ByteArray)?.toString(Charsets.UTF_8) ?: (row[1L] as? String) ?: ""
            for ((a, list) in areas) {
                val area = (a as? Long)?.toInt() ?: continue
                for (pt in (list as? List<*>) ?: continue) {
                    val xy = pt as? List<*> ?: continue
                    val x = (xy.getOrNull(0) as? Number)?.toFloat() ?: continue
                    val y = (xy.getOrNull(1) as? Number)?.toFloat() ?: continue
                    if (x < 0f || y < 0f) continue                    // QuestieDB's -1, -1: an instance spawn
                    out.add(Spawn(area, x / 100f, y / 100f, minLv, maxLv, rank == 1 || rank == 2 || rank == 3, hostile, name))
                }
            }
        }
        return out
    }

    private const val PREFIX = "## X-Npc-"
    private fun b64(s: String): ByteArray = java.util.Base64.getMimeDecoder().decode(s)

    /** the toc of the QuestieDB the app installed, in the game's AddOns (Forever's, else Camelot's), or null */
    fun toc(addons: File): File? = listOf("QuestieDB/QuestieDB_Forever.toc", "QuestieDB/QuestieDB_Camelot.toc")
        .map { File(addons, it) }.firstOrNull { it.isFile }

    /**
     * A SMALL CBOR READER (RFC 8949: what QuestieDB writes): unsigned / negative integers as Long, byte and text strings as
     * ByteArray / String, arrays as List, maps as Map (keys as decoded), floats as Double, true / false / null; tags skipped.
     */
    object Cbor {
        fun decode(b: ByteArray): Any? = R(b).item()

        private class R(val b: ByteArray) {
            var i = 0
            fun u8() = b[i++].toInt() and 0xFF
            fun arg(ai: Int): Long = when (ai) {
                in 0..23 -> ai.toLong()
                24 -> u8().toLong()
                25 -> ((u8() shl 8) or u8()).toLong()
                26 -> { var v = 0L; repeat(4) { v = (v shl 8) or u8().toLong() }; v }
                27 -> { var v = 0L; repeat(8) { v = (v shl 8) or u8().toLong() }; v }
                else -> error("indefinite lengths are not written by QuestieDB")
            }
            fun item(): Any? {
                val ib = u8(); val mt = ib ushr 5; val ai = ib and 31
                return when (mt) {
                    0 -> arg(ai)
                    1 -> -1L - arg(ai)
                    2 -> { val n = arg(ai).toInt(); b.copyOfRange(i, i + n).also { i += n } }
                    3 -> { val n = arg(ai).toInt(); String(b, i, n, Charsets.UTF_8).also { i += n } }
                    4 -> { val n = arg(ai).toInt(); List(n) { item() } }
                    5 -> { val n = arg(ai).toInt(); val m = LinkedHashMap<Any?, Any?>(); repeat(n) { val k = item(); m[k] = item() }; m }
                    6 -> { arg(ai); item() }
                    else -> when (ai) {
                        20 -> false; 21 -> true; 22, 23 -> null
                        25 -> { val h = (u8() shl 8) or u8(); half(h) }
                        26 -> { var v = 0; repeat(4) { v = (v shl 8) or u8() }; java.lang.Float.intBitsToFloat(v).toDouble() }
                        27 -> { var v = 0L; repeat(8) { v = (v shl 8) or u8().toLong() }; java.lang.Double.longBitsToDouble(v) }
                        else -> null
                    }
                }
            }
            fun half(h: Int): Double {
                val e = (h shr 10) and 0x1F; val m = h and 0x3FF
                val v = when (e) { 0 -> m * Math.pow(2.0, -24.0); 31 -> if (m == 0) Double.POSITIVE_INFINITY else Double.NaN
                    else -> (m + 1024) * Math.pow(2.0, e - 25.0) }
                return if (h and 0x8000 != 0) -v else v
            }
        }
    }
}
