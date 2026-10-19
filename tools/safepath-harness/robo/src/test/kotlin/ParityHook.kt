import com.jrees.wowcompanion.ZoneInputs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * THE DEVICE SIDE OF THE PARITY TEST (ParityDevice; E:/safepath-work/docs/DEVICE_INTEGRATION_PLAN.md §6.2, build step 7): what the
 * device's own code must do for one zone, so the test can route the zone's goals the device's way and compare them with the harness's.
 *
 * The device side is not built yet (the `climb` harvest step, the loader, MapView.safeLeg: helper K's feat/device-safepath). Until a
 * class named [IMPL] (no package, in this folder: robo/src/test/kotlin/ParityDeviceHook.kt) implements this interface, ParityDevice
 * SKIPS and says so. That class is the only thing the device side has to add:
 *
 * ```
 * class ParityDeviceHook : ParityHook {
 *     override fun harvest(zone: ParityZone, files: File, log: (String) -> Unit) { ... the climb step, safepathzones' water ... }
 *     override fun route(zone: ParityZone, files: File, goals: List<ParityGoal>, log: (String) -> Unit): List<ParityRoute> { ... }
 * }
 * ```
 *
 * NAME-BLIND: the hook gets the zone's files only by fileDataID ([ParityZone.source], as the device reads the player's game), never the
 * dump folder itself (no kinds.txt, no names.txt / listfile names), and the spec with its hand-entered data withheld
 * ([ParityZone.withheld]: measured lifts, floorWmos, floorCity, air chains, voidBelow, roadTextures, swims' coordinates). A route that
 * differs because of that is a RULE GAP the report names; the tolerances are never loosened for it.
 */
interface ParityHook {
    /**
     * the device's harvest for [zone], as its steps would write it under the device's art root: [files] stands for that root (e.g.
     * terrain/<map>.clb (CLB3), safepath/water/<map>.bin, safepath/zones/<map>.bin, the floors and caves files). Read only
     * [ParityZone.source] for the zone's game files.
     */
    fun harvest(zone: ParityZone, files: File, log: (String) -> Unit)

    /**
     * each of [goals] planned the device's way: the device's loader over [files] (what [harvest] wrote, nothing kept in memory from it)
     * and SafeRoute.plan with the device's danger (DangerInputs) for [ParityZone.faction] at [ParityZone.level]. One [ParityRoute] per
     * goal, the same letter; a goal the device can't plan at all is left out (the report says it is missing).
     */
    fun route(zone: ParityZone, files: File, goals: List<ParityGoal>, log: (String) -> Unit): List<ParityRoute>

    companion object {
        /** the class the device side adds (no package) */
        const val IMPL = "ParityDeviceHook"
        /** the hook, or null and why not */
        fun find(): Pair<ParityHook?, String> {
            val c = runCatching { Class.forName(IMPL) }.getOrNull()
                ?: return null to "no class $IMPL yet (robo/src/test/kotlin/$IMPL.kt implementing ParityHook: the device's climb step, " +
                    "loader and SafeRoute for one zone)"
            if (!ParityHook::class.java.isAssignableFrom(c)) return null to "$IMPL does not implement ParityHook"
            return runCatching { c.getDeclaredConstructor().newInstance() as ParityHook to "" }.getOrElse { null to "$IMPL could not be made: ${it.message}" }
        }
    }
}

/**
 * one zone for the device side: [tag] (the zone spec's), [map] / [area] (its uiMap and AreaTable id), [faction] / [level] the test
 * character's, [maps] the zone and its neighbours ([uiMap, area] pairs, the spec's), [spec] the spec less what is [withheld],
 * [source] its files by fileDataID, [work] the work folder (db2/ tables, artroot/ the art harvest's recipe and transports), [network]
 * the drafted networks the harness wrote (safepath_<uiMap>.draft.json: the device's own draft step's output, as the harness made it),
 * [qdbToc] QuestieDB's toc (the spawns)
 */
class ParityZone(val tag: String, val map: Int, val area: Int, val faction: String, val level: Int, val maps: List<IntArray>,
                 val spec: JSONObject, val withheld: List<String>, val source: ZoneInputs.Source, val work: File, val network: File, val qdbToc: File?)

/** a goal: the harness's own start and goal points for route [letter] (its start as it snapped it, its goal as it resolved it) */
class ParityGoal(val letter: String, val name: String, val from: DoubleArray, val to: DoubleArray, val harnessReached: Boolean)

/** a planned route: its points (x, y, and Climb's IN_CAVE / LIFT / SWIM mark as a third value where it has one) */
class ParityRoute(val letter: String, val name: String, val reached: Boolean, val pts: List<DoubleArray>) {
    val flags: Int get() = maxOf(0, pts.size - 2)
    val yd: Double get() = pts.zipWithNext { a, b -> Math.hypot(b[0] - a[0], b[1] - a[1]) }.sum()
}

/** the spec's hand-entered data the device can't have (NAME-BLIND), and the spec without it */
object NameBlind {
    /** keys the device never gets: measured coordinates, names from the dump's manifest, a typed void floor (needs the user's OK) */
    val WITHHELD = listOf("lifts", "floorWmos", "floorCity", "airChain", "voidBelow", "roadTextures", "forever", "probes")
    fun strip(spec: JSONObject): Pair<JSONObject, List<String>> {
        val o = JSONObject(spec.toString()); val gone = ArrayList<String>()
        for (k in WITHHELD) if (o.has(k)) { o.remove(k); gone.add(k) }
        // a declared swim keeps its name and its island's NAME (the device finds islands by subzone name); its coordinates go
        o.optJSONArray("swims")?.let { a ->
            val kept = JSONArray()
            for (i in 0 until a.length()) { val s = a.getJSONObject(i); val isl = s.optJSONObject("island")
                val n = JSONObject().put("name", s.optString("name"))
                isl?.let { val ni = JSONObject(); for (k in listOf("node", "area")) if (it.has(k)) ni.put(k, it.getString(k)); if (ni.length() > 0) n.put("island", ni) }
                kept.put(n) }
            o.put("swims", kept); gone.add("swims' coordinates")
        }
        return o to gone
    }
}
