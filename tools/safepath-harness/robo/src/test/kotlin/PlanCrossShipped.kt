import com.jrees.wowcompanion.Db2
import com.jrees.wowcompanion.PlaceNames
import com.jrees.wowcompanion.SafePath
import com.jrees.wowcompanion.ShippedCross
import com.jrees.wowcompanion.ShippedNet
import com.jrees.wowcompanion.TransportRoutes
import com.jrees.wowcompanion.TravelLinks
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * PlanCross's nine cases (cross/plans.json) again, planned as the DEVICE plans them now: TravelLinks over the SHIPPED NETWORK
 * (ShippedCross: the walks from the NET1 files in SHIPPED_NET_DIR, default the app's assets safepath/net; a map with no file keeps
 * links.json's crossing as its draft), the rides of transports.json (links.json's rideFaction), the faction's flights, links.json's
 * portals, tram, lift, doors and requirement links as the caller's own links. Prints a PLANX line a case: OK or FAIL as PlanCross, and
 * which maps on the chain have a shipped net (the device takes the chain only when all do). PlanCross itself is unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PlanCrossShipped {
    @Test fun plans() {
        val work = File(System.getenv("SAFEPATH_WORK") ?: return)
        val here = File(System.getenv("CROSS_DIR") ?: return)
        val repo = here.parentFile.parentFile.parentFile
        val netDir = File(System.getenv("SHIPPED_NET_DIR") ?: File(repo, "companion/app/src/main/assets/safepath/net").path)
        val links = JSONObject(File(here, "links.json").readText())
        val plans = JSONObject(File(here, "plans.json").readText()).getJSONArray("plans")
        val transports = JSONObject(File(work, "artroot/transports.json").readText())
        val rects = JSONObject(File(System.getenv("RECIPE")).readText()).getJSONObject("rects")
        val areaRows = Db2(File(work, "db2/AreaTable.db2").readBytes()).rows(strings = setOf(1)).associate { it.id to PlaceNames.Area(it.str(1), it.int(3)) }
        val areas = areaRows.mapValues { it.value.name }
        val uimap = HashMap<Int, Int>().also { o -> for (r in Db2(File(work, "db2/UiMapAssignment.db2").readBytes()).rows()) { val a = r.int(7); val m = r.int(4); if (a != 0 && m != 0 && a !in o) o[a] = m } }
        class R(val map: Int, val cont: Int, val t: Double, val b: Double, val l: Double, val r: Double) {
            val size get() = (t - b) * (l - r)
            fun holds(x: Double, y: Double) = x <= t && x >= b && y <= l && y >= r
        }
        val zonesR = rects.keys().asSequence().mapNotNull { k -> rects.optJSONArray(k)?.let { a -> R(k.toInt(), a.getInt(0), a.getDouble(1), a.getDouble(2), a.getDouble(3), a.getDouble(4)) } }
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
            repeat(6) { uimap[a]?.let { m -> if (zonesR.any { it.map == m && it.cont == cont }) return m }; a = areaRows[a]?.parent?.takeIf { it != 0 } ?: return null }
            return null
        }
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
            return zoneOf(cont, x, y) ?: zonesR.filter { it.cont == cont && it.holds(x, y) }.minByOrNull { it.size }?.map
        }
        fun mapName(m: Int) = placeName[m] ?: JSONObject(File(work, "artroot/worldmap/$m/index.json").takeIf { it.isFile }?.readText() ?: "{}").optString("name", "map $m")

        // the shipped files
        val packs = (netDir.listFiles { f -> f.name.endsWith(".bin") } ?: emptyArray()).mapNotNull { f -> f.inputStream().use { ShippedNet.read(it) } }
        println("PLANX shipped nets in $netDir: ${packs.map { it.header.map }.sorted()}")

        // the rides with links.json's ride factions
        val rideFaction = links.optJSONObject("rideFaction") ?: JSONObject()
        val allRides = TransportRoutes.fromJson(transports).map { r0 -> val f = rideFaction.optInt(r0.path.toString(), -1)
            if (f < 0) r0 else TransportRoutes.Ride(r0.path, r0.ca, r0.a, r0.cb, r0.b, r0.yd, f, f, r0.air, r0.na, r0.nb) }
        val masters = TransportRoutes.mastersFromJson(transports)
        val flights = TransportRoutes.flightsFromJson(transports).map { setOf(it[0], it[1]) }.toSet()
        // links.json's crossings on foot as one-exit drafts (a shipped file replaces its map's)
        val walks = links.optJSONArray("walks") ?: JSONArray()
        val drafts = (0 until walks.length()).map { i -> val w = walks.getJSONObject(i); val at = w.getJSONArray("at")
            SafePath.Net(w.getInt("a"), 0, "links.json", listOf(SafePath.Node(1, at.getDouble(0), at.getDouble(1), SafePath.EXIT)), emptyList(),
                listOf(SafePath.Exit(1, w.getInt("b"), "walk", w.getString("name")))) }
        val ls = links.optJSONArray("links") ?: JSONArray()
        var ok = 0
        for (pi in 0 until plans.length()) {
            val plan = plans.getJSONObject(pi)
            val mine = if (plan.optString("faction") == "horde") TransportRoutes.HORDE else TransportRoutes.ALLIANCE
            val manual = ArrayList<TravelLinks.Link>()
            for (i in 0 until ls.length()) {
                val k = ls.getJSONObject(i)
                if (!k.optBoolean("placed", true)) continue
                fun end(o: JSONObject) = o.optJSONArray("xy")?.let { doubleArrayOf(it.getDouble(0), it.getDouble(1)) } ?: doubleArrayOf(Double.NaN, Double.NaN)
                val f = k.getJSONObject("from"); val t = k.getJSONObject("to"); val a = end(f); val b = end(t)
                val fac = when (k.optString("faction", "")) { "alliance" -> TransportRoutes.ALLIANCE; "horde" -> TransportRoutes.HORDE; else -> 0 }
                val l = TravelLinks.Link(TravelLinks.Kind.LINK, f.getInt("map"), a[0], a[1], t.getInt("map"), b[0], b[1], k.optDouble("yd", 0.0),
                    k.getString("name"), k.getString("id"), oneWay = k.optBoolean("oneWay", false), requires = k.optString("requires").ifEmpty { null }, faction = fac)
                manual.add(l); if (!l.oneWay) manual.add(l.back("${k.getString("name")} back"))
            }
            val zones = packs.mapNotNull { p -> p.section(mine)?.let { ShippedCross.Zone(p.header.map, it) } }
            val base = TravelLinks.Inputs(mapAt = ::mapAt, mapName = ::mapName, nets = drafts, rides = allRides, masters = masters,
                taxiKnown = { true }, taxiConnected = { a, b -> setOf(a, b) in flights }, table = emptyList(), manual = manual)
            val has = plan.optJSONArray("has")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
            val who = TravelLinks.Character(mine, has, plan.optBoolean("flights", true))
            val fr = plan.getJSONObject("from"); val to = plan.getJSONObject("to")
            val sx = fr.getJSONArray("xy").getDouble(0); val sy = fr.getJSONArray("xy").getDouble(1)
            val gx = to.getJSONArray("xy").getDouble(0); val gy = to.getJSONArray("xy").getDouble(1)
            val sm = mapAt(fr.getInt("cont"), sx, sy); val gm = mapAt(to.getInt("cont"), gx, gy)
            val id = plan.getString("id")
            if (sm == null || gm == null) { println("PLANX $id FAIL: the start or the goal is on no map ($sm, $gm)"); continue }
            val c = ShippedCross.plan(ShippedCross.inputs(base, zones), who, sm, sx, sy, gm, gx, gy, zones.map { it.map }.toSet())
            val used = c?.route?.keys ?: emptyList()
            val gaps = c?.route?.gaps?.map { it.key } ?: emptyList()
            val got = when { c == null -> "none"; gaps.isNotEmpty() -> "gap"; else -> "plan" }
            val why = ArrayList<String>()
            plan.optString("expect").takeIf { it.isNotEmpty() && it != got }?.let { why.add("expected $it, got $got") }
            plan.optJSONArray("use")?.let { a -> for (i in 0 until a.length()) if (a.getString(i) !in used) why.add("does not use ${a.getString(i)}") }
            plan.optJSONArray("avoid")?.let { a -> for (i in 0 until a.length()) if (a.getString(i) in used) why.add("uses ${a.getString(i)}") }
            if (why.isEmpty()) ok++
            println("PLANX $id ${if (why.isEmpty()) "OK" else "FAIL: " + why.joinToString("; ")} | ${mapName(sm)} -> ${mapName(gm)}: " +
                (c?.let { "${it.describe(::mapName)} (${it.yards.toInt()} yd); shipped: ${if (it.covered) "all" else "missing " + it.missing.joinToString { m -> "${mapName(m)} ($m)" }}" }
                    ?: "no chain") + if (gaps.isNotEmpty()) "; UNMEASURED: ${gaps.joinToString()}" else "")
        }
        println("PLANX $ok/${plans.length()} OK")
    }
}
