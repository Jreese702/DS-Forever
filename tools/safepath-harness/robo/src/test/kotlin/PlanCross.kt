import com.jrees.wowcompanion.Db2
import com.jrees.wowcompanion.MultiHop
import com.jrees.wowcompanion.PlaceNames
import com.jrees.wowcompanion.TransportRoutes
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * THE CROSS-CONTINENT PLANNER'S CHECKS (cross/plans.json, in the gate; the user: "daleran has one way portals to stormwind added in wow
 * forever. Anything like that need to be getting factored in on the big picture pathing"). Every way between places that is not a
 * walk inside one map is a DIRECTED hop of the app's own MultiHop: the boats, zeppelins and airships of transports.json (each ride both
 * ways, never from or to a hostile town's dock), the faction's flights, and cross/links.json's portals (one way), tram and crossings
 * on foot. A one-way link is one hop, so no plan ever goes back through it. A link's end nobody has measured (no position) is taken at
 * MultiHop.UNKNOWN_YD and named as a gap. Prints a PLAN line a check: OK, or FAIL with why.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PlanCross {
    @Test fun plans() {
        val work = File(System.getenv("SAFEPATH_WORK") ?: return)
        val here = File(System.getenv("CROSS_DIR") ?: return)
        val links = JSONObject(File(here, "links.json").readText())
        val plans = JSONObject(File(here, "plans.json").readText()).getJSONArray("plans")
        val transports = JSONObject(File(work, "artroot/transports.json").readText())
        val rects = JSONObject(File(System.getenv("RECIPE")).readText()).getJSONObject("rects")
        val areaRows = Db2(File(work, "db2/AreaTable.db2").readBytes()).rows(strings = setOf(1)).associate { it.id to PlaceNames.Area(it.str(1), it.int(3)) }
        val areas = areaRows.mapValues { it.value.name }
        // a spot's zone: its ground's AreaTable area, up its parents to one with a uiMap (UiMapAssignment), as the harness's own
        val uimap = HashMap<Int, Int>().also { o -> for (r in Db2(File(work, "db2/UiMapAssignment.db2").readBytes()).rows()) { val a = r.int(7); val m = r.int(4); if (a != 0 && m != 0 && a !in o) o[a] = m } }
        // the zones' rects (not the continents' and the world's: a zone is the smallest rect holding a spot)
        class R(val map: Int, val cont: Int, val t: Double, val b: Double, val l: Double, val r: Double) {
            val size get() = (t - b) * (l - r)
            fun holds(x: Double, y: Double) = x <= t && x >= b && y <= l && y >= r
        }
        val zones = rects.keys().asSequence().mapNotNull { k -> rects.optJSONArray(k)?.let { a -> R(k.toInt(), a.getInt(0), a.getDouble(1), a.getDouble(2), a.getDouble(3), a.getDouble(4)) } }
            .filter { it.size < 4.0e7 }.toList()
        val dirs = mapOf(0 to "azeroth", 1 to "kalimdor", 2991 to "zephras")
        val chunkAreas = HashMap<String, Map<Int, Int>>()
        fun areaIdAt(cont: Int, x: Double, y: Double): Int? {
            val d = dirs[cont] ?: return null; val l = PlaceNames.locate(x, y)
            val m = chunkAreas.getOrPut("$d/${l[0]}_${l[1]}") { File(work, "$d/adt/${l[0]}_${l[1]}.adt").takeIf { it.isFile }?.let { PlaceNames.chunkAreas(it.readBytes()) } ?: emptyMap() }
            return m[l[2]]?.takeIf { it != 0 }
        }
        fun areaAt(cont: Int, x: Double, y: Double): String? = areaIdAt(cont, x, y)?.let { areas[it] }
        fun zoneOf(cont: Int, x: Double, y: Double): Int? {
            var a = areaIdAt(cont, x, y) ?: return null
            repeat(6) { uimap[a]?.let { m -> if (zones.any { it.map == m && it.cont == cont }) return m }; a = areaRows[a]?.parent?.takeIf { it != 0 } ?: return null }
            return null
        }
        // the places: a walk inside a uiMap is open, but for a part of it no one walks to (Dalaran in the sky, the foot of Teldrassil)
        val places = links.optJSONArray("places") ?: JSONArray()
        val placeName = HashMap<Int, String>()
        fun mapAt(cont: Int, x: Double, y: Double): Int? {
            for (i in 0 until places.length()) {
                val p = places.getJSONObject(i); if (p.getInt("cont") != cont) continue
                placeName[p.getInt("id")] = p.getString("name")
                val box = p.optJSONArray("box")
                if (box != null && x <= box.getDouble(0) && x >= box.getDouble(1) && y <= box.getDouble(2) && y >= box.getDouble(3)) return p.getInt("id")
                if (p.has("area") && areaAt(cont, x, y) == p.getString("area")) return p.getInt("id")
            }
            return zoneOf(cont, x, y) ?: zones.filter { it.cont == cont && it.holds(x, y) }.minByOrNull { it.size }?.map
        }
        val rideFaction = links.optJSONObject("rideFaction") ?: JSONObject()
        for (pi in 0 until plans.length()) {
            val plan = plans.getJSONObject(pi)
            val mine = if (plan.optString("faction") == "horde") TransportRoutes.HORDE else TransportRoutes.ALLIANCE
            val hops = ArrayList<MultiHop.Hop>(); val left = ArrayList<String>()
            // the rides, both ways; a dock in a hostile town never boarded (fa / fb: its town's faction; links.json may name one)
            for (r0 in TransportRoutes.fromJson(transports)) {
                val f = rideFaction.optInt(r0.path.toString(), -1)
                val r1 = if (f < 0) r0 else TransportRoutes.Ride(r0.path, r0.ca, r0.a, r0.cb, r0.b, r0.yd, f, f, r0.air, r0.na, r0.nb)
                for ((k, rd) in listOf(r1, r1.back()).withIndex()) {
                    if (rd.hostileDock(mine) != 0) continue
                    val fm = mapAt(rd.ca, rd.a[0].toDouble(), rd.a[1].toDouble()) ?: continue; val tm = mapAt(rd.cb, rd.b[0].toDouble(), rd.b[1].toDouble()) ?: continue
                    if (fm == tm) continue
                    hops.add(MultiHop.Hop(fm, rd.a[0].toDouble(), rd.a[1].toDouble(), tm, rd.b[0].toDouble(), rd.b[1].toDouble(), rd.yd.coerceAtLeast(1.0),
                        "${if (rd.air) "the airship / zeppelin" else "the boat"} (path ${rd.path})${if (k == 1) " back" else ""}", "r${rd.path}"))
                }
            }
            // the faction's flights
            if (plan.optBoolean("flights", true)) {
                val ms = TransportRoutes.mastersFromJson(transports).filter { it.real && it.flags and mine != 0 }.associateBy { it.id }
                for (e in TransportRoutes.flightsFromJson(transports)) {
                    val a = ms[e[0]] ?: continue; val b = ms[e[1]] ?: continue
                    val fm = mapAt(a.cont, a.x.toDouble(), a.y.toDouble()) ?: continue; val tm = mapAt(b.cont, b.x.toDouble(), b.y.toDouble()) ?: continue
                    if (fm == tm || a.cont != b.cont) continue
                    val d = Math.hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble())
                    hops.add(MultiHop.Hop(fm, a.x.toDouble(), a.y.toDouble(), tm, b.x.toDouble(), b.y.toDouble(), 150.0 + d * 0.25, "the flight to ${b.town}", "f${a.id}>${b.id}"))
                }
            }
            // the crossings on foot (both ways) and links.json's own: one-way ones one hop, unplaced ones left out
            val walks = links.optJSONArray("walks") ?: JSONArray()
            for (i in 0 until walks.length()) { val w = walks.getJSONObject(i); val at = w.getJSONArray("at"); val x = at.getDouble(0); val y = at.getDouble(1)
                hops.add(MultiHop.Hop(w.getInt("a"), x, y, w.getInt("b"), x, y, 0.0, w.getString("name"), "w:${w.getInt("a")}>${w.getInt("b")}"))
                hops.add(MultiHop.Hop(w.getInt("b"), x, y, w.getInt("a"), x, y, 0.0, w.getString("name"), "w:${w.getInt("b")}>${w.getInt("a")}")) }
            val ls = links.optJSONArray("links") ?: JSONArray()
            for (i in 0 until ls.length()) {
                val k = ls.getJSONObject(i)
                if (!k.optBoolean("placed", true)) { left.add("${k.getString("id")} (not placed)"); continue }
                val fac = k.optString("faction", ""); if (fac == "alliance" && mine != TransportRoutes.ALLIANCE || fac == "horde" && mine != TransportRoutes.HORDE) continue
                fun end(o: JSONObject) = o.optJSONArray("xy")?.let { doubleArrayOf(it.getDouble(0), it.getDouble(1)) } ?: doubleArrayOf(Double.NaN, Double.NaN)
                val f = k.getJSONObject("from"); val t = k.getJSONObject("to"); val a = end(f); val b = end(t)
                hops.add(MultiHop.Hop(f.getInt("map"), a[0], a[1], t.getInt("map"), b[0], b[1], k.optDouble("yd", 0.0), k.getString("name"), k.getString("id")))
                if (!k.optBoolean("oneWay", false)) hops.add(MultiHop.Hop(t.getInt("map"), b[0], b[1], f.getInt("map"), a[0], a[1], k.optDouble("yd", 0.0), "${k.getString("name")} back", k.getString("id")))
            }
            val fr = plan.getJSONObject("from"); val to = plan.getJSONObject("to")
            val sx = fr.getJSONArray("xy").getDouble(0); val sy = fr.getJSONArray("xy").getDouble(1)
            val gx = to.getJSONArray("xy").getDouble(0); val gy = to.getJSONArray("xy").getDouble(1)
            val sm = mapAt(fr.getInt("cont"), sx, sy); val gm = mapAt(to.getInt("cont"), gx, gy)
            val id = plan.getString("id")
            if (sm == null || gm == null) { println("PLAN $id FAIL: the start or the goal is on no map ($sm, $gm)"); continue }
            if (System.getenv("PLAN_DEBUG") == id) for (h in hops.filter { it.key.startsWith("r") || it.key.startsWith("w") || !it.key.startsWith("f") }) println("HOP ${h.fromMap} -> ${h.toMap} ${h.key} ${h.label}")
            val p = MultiHop.plan(sm, sx, sy, gx, gy, hops, { m, _, _ -> m == gm }, maxHops = 8)
            val used = p?.hops?.map { it.key } ?: emptyList()
            val gaps = p?.hops?.filter { it.ax.isNaN() || it.bx.isNaN() }?.map { it.key } ?: emptyList()
            val got = when { p == null -> "none"; gaps.isNotEmpty() -> "gap"; else -> "plan" }
            val why = ArrayList<String>()
            plan.optString("expect").takeIf { it.isNotEmpty() && it != got }?.let { why.add("expected $it, got $got") }
            plan.optJSONArray("use")?.let { a -> for (i in 0 until a.length()) if (a.getString(i) !in used) why.add("does not use ${a.getString(i)}") }
            plan.optJSONArray("avoid")?.let { a -> for (i in 0 until a.length()) if (a.getString(i) in used) why.add("uses ${a.getString(i)}") }
            fun mapName(m: Int) = placeName[m] ?: JSONObject(File(work, "artroot/worldmap/$m/index.json").takeIf { it.isFile }?.readText() ?: "{}").optString("name", "map $m")
            println("PLAN $id (from map $sm, ${areaAt(fr.getInt("cont"), sx, sy)}, to $gm, ${areaAt(to.getInt("cont"), gx, gy)}) ${if (why.isEmpty()) "OK" else "FAIL: " + why.joinToString("; ")} | ${plan.getString("name")} | ${mapName(sm)} -> ${mapName(gm)}: " +
                (p?.let { "${it.describe()} (${it.cost.toInt()} yd)" + if (gaps.isNotEmpty()) "; UNMEASURED: ${gaps.joinToString()}" else "" } ?: "no chain of links reaches it") +
                if (left.isNotEmpty()) "; left out: ${left.joinToString()}" else "")
        }
    }
}
