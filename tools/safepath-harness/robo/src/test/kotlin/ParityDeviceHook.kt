import com.jrees.wowcompanion.Climb
import com.jrees.wowcompanion.ClimbStep
import com.jrees.wowcompanion.CityExits
import com.jrees.wowcompanion.Db2
import com.jrees.wowcompanion.DangerGrid
import com.jrees.wowcompanion.DangerInputs
import com.jrees.wowcompanion.MapHarvest
import com.jrees.wowcompanion.PlaceNames
import com.jrees.wowcompanion.QuestieNpcs
import com.jrees.wowcompanion.SafePath
import com.jrees.wowcompanion.SafePathV2
import com.jrees.wowcompanion.Terrain
import com.jrees.wowcompanion.TransportRoutes
import com.jrees.wowcompanion.WorldBuildings
import com.jrees.wowcompanion.ZoneInputs
import com.jrees.wowcompanion.ZoneWater
import org.json.JSONObject
import java.io.File

/**
 * THE DEVICE'S SIDE OF THE PARITY TEST (ParityHook; helper K's feat/device-safepath): one zone harvested by the device's own steps -
 * the `climb` step's lattice (ClimbStep.make: CLB3) and the `safepathzones` step's water (ZoneWater: WTR2) and ground zones (ZNS2) -
 * from its files by fileDataID only, then each goal routed the device's way: those files read back by the device's readers, the
 * network the device keeps (the zone's draft, its exits checked on the lattice, its walled ways walked or left out), the character's
 * danger (DangerInputs' device rules) and SafePathV2.route (SafeRoute within the device's limits, every segment checked). NAME-BLIND:
 * no caves (CAV1 is the next drop), no lifts, swims or floor cities.
 */
class ParityDeviceHook : ParityHook {
    private val recipe by lazy { JSONObject(File(System.getenv("RECIPE") ?: error("RECIPE not set (run.sh sets it)")).readText()).getJSONObject("rects") }
    private fun rectOf(map: Int) = recipe.getJSONArray(map.toString()).let { a -> MapHarvest.Rect(a.getInt(0), a.getDouble(1), a.getDouble(2), a.getDouble(3), a.getDouble(4)) }
    private fun lethal(work: File): Set<Int> = File(work, "db2/LiquidType.db2").takeIf { it.isFile }?.let { f ->
        Terrain.lethalLiquids(Db2(f.readBytes()).rows(strings = setOf(0)).associate { it.id to it.str(0) }) } ?: emptySet()

    /** a file by its fileDataID through the zone's source: a WMO root, a model, or a WMO group (its root's GFID) */
    private class ByFid(val src: ZoneInputs.Source) {
        private val groupOf = HashMap<Int, Pair<Int, Int>>()
        fun read(fid: Int): ByteArray? {
            groupOf[fid]?.let { (root, i) -> return src.wmoGroup(root, i) }
            src.wmo(fid)?.let { b -> WorldBuildings.groupFiles(b).forEachIndexed { i, g -> if (g != 0) groupOf[g] = fid to i }; return b }
            return src.m2(fid)
        }
    }

    override fun harvest(zone: ParityZone, files: File, log: (String) -> Unit) {
        val rect = rectOf(zone.map); val lethal = lethal(zone.work)
        Terrain.lethal = lethal
        val capitals = CityExits.CITIES.mapNotNull { c -> runCatching { rectOf(c) }.getOrNull()?.let { r -> doubleArrayOf(r.t, r.b, r.l, r.r) } }
        val t0 = System.nanoTime()
        val g = ClimbStep.make(zone.source, rect, lethal, capitals)
        g.write(File(files, "terrain/${zone.map}.clb"))
        val t1 = System.nanoTime()
        val by = ByFid(zone.source)
        ZoneWater(lethal, zone.source::adt, zone.source::obj0, by::read).build(rect).write(File(files, "safepath/water/${zone.map}.bin"))
        val t2 = System.nanoTime()
        val chunks = HashMap<Pair<Int, Int>, Map<Int, Int>>()
        SafePath.Zones.build(rect.t, rect.b, rect.l, rect.r, PlaceNames.CHUNK_YD) { wx, wy -> val l = PlaceNames.locate(wx, wy)
            chunks.getOrPut(l[0] to l[1]) { zone.source.adt(l[0], l[1])?.let { PlaceNames.chunkAreas(it) } ?: emptyMap() }[l[2]] }
            .write(File(files, "safepath/zones/${zone.map}.bin"))
        log("harvest: climb ${g.nx} x ${g.ny} (${g.rooms?.size ?: 0} rooms, ${g.deckZ?.count { !it.isNaN() } ?: 0} floor vertices) in ${(t1 - t0) / 1_000_000} ms, " +
            "water in ${(t2 - t1) / 1_000_000} ms, zones in ${(System.nanoTime() - t2) / 1_000_000} ms; CLB3 ${File(files, "terrain/${zone.map}.clb").length() / 1024} KB")
    }

    override fun route(zone: ParityZone, files: File, goals: List<ParityGoal>, log: (String) -> Unit): List<ParityRoute> {
        val rect = rectOf(zone.map)
        // the device's readers over what harvest wrote
        val g = Climb.Grid.read(File(files, "terrain/${zone.map}.clb")) ?: error("no CLB3 read back")
        val wg = SafePath.Water.read(File(files, "safepath/water/${zone.map}.bin"))
        val zg = SafePath.Zones.read(File(files, "safepath/zones/${zone.map}.bin"))
        val db2 = File(zone.work, "db2")
        val areas = Db2(File(db2, "AreaTable.db2").readBytes()).rows(strings = setOf(1)).associate { it.id to PlaceNames.Area(it.str(1), it.int(3)) }
        val uimap = HashMap<Int, Int>().also { o -> for (r in Db2(File(db2, "UiMapAssignment.db2").readBytes()).rows()) { val a = r.int(7); val m = r.int(4); if (a != 0 && m != 0 && a !in o) o[a] = m } }
        val ar = SafePath.Areas(areas, uimap)
        val uiAt: (Double, Double) -> Int = { x, y -> zg?.let { ar.uiMap(it.areaAt(x, y)) } ?: 0 }
        val own: (Double, Double) -> Boolean = { x, y -> uiAt(x, y) == zone.map }
        val mine = if (zone.faction == "alliance") TransportRoutes.ALLIANCE else TransportRoutes.HORDE
        val spawns = zone.qdbToc?.let { QuestieNpcs.read(it) }?.filter { it.area == zone.area } ?: emptyList()
        val deep = SafePathV2.deepOf(wg)
        // the device's network: the zone's draft (the device's own draft step's output, as the harness drafted it), its exits checked on
        // the lattice (SafePath.refresh's way), its walled ways walked or left out
        val draft = SafePath.Net.fromJson(JSONObject(File(zone.network, "safepath_${zone.map}.draft.json").readText())) ?: error("no draft for ${zone.map}")
        val gr = SafePath.Ground(g, zone.map, uiAt, { x, y -> !deep(x, y) })
        val net = SafePathV2.unwalled(SafePath.groundExits(SafePath.checkExits(draft, gr), gr), g, deep)
        val dc = SafePathV2.costs(DangerInputs.build(spawns, rect, mine, zone.level, SafePathV2.rulesFor(zone.map), "high", inZone = own), g, wg)
        // a hooked end walked straight only over level ground (the terrain's slopes: the terrain step's grid)
        val zi = ZoneInputs(zone.source, rect, Terrain.lethal, emptyList())
        val tg = Terrain.grid(rect) { gr2, gc -> zi.height(gr2, gc) }
        val clear = SafePath.clearOf { x, y -> Terrain.factor(tg.at(x, y)) }
        val k = DangerGrid.strength("high")
        log("route: network ${net.nodes.size} nodes, ${net.edges.size} edges (${net.edges.count { it.state == SafePath.REWALK }} walked round a lip); " +
            "${spawns.size} spawns; typical danger ${"%.2f".format(dc.typical)}")
        return goals.mapNotNull { goal ->
            val t0 = System.nanoTime()
            Climb.Scratch.resetPeak()
            val r = SafePathV2.route(g, wg, net, dc, k, clear, goal.from, goal.to, false, Climb.Limits())
            val ms = (System.nanoTime() - t0) / 1_000_000
            if (r == null) { log("route ${goal.letter}: refused by the final check (a wall or deep water) in $ms ms"); null }
            else {
                log("route ${goal.letter}: ${r.pts.size - 2} flags, ${r.yd.toInt()} yd, ${if (r.reached) "reached" else "UNREACHED"}, $ms ms, window ${Climb.Scratch.peakHere()} vertices")
                ParityRoute(goal.letter, goal.name, r.reached, r.pts)
            }
        }
    }
}
