import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * THE DEVICE PARITY TEST (E:/safepath-work/docs/DEVICE_INTEGRATION_PLAN.md §6.2, build step 7): each proof zone harvested the
 * device's way from the PC dumps (the `climb` step's CLB3 and the device's other files, NAME-BLIND), loaded back by the device's own
 * loader and routed by SafeRoute to the harness's own goals, then compared with the harness's routes (ParityCompare: reached equal,
 * flags ±1, length ±2%, Hausdorff ≤ 8 yd, segcheck and wetcheck "none" on the device's routes). Under 5 minutes in all.
 *
 *   1. the harness for each zone first, into one folder:   SAFEPATH_OUT=<dir> bash tools/safepath-harness/run.sh tools/safepath-harness/zones/<tag>.json
 *      (and copy robo/build/test-results/test/TEST-RenderSafePath.xml to <dir>/full_<tag>.xml: the goals of unreached routes)
 *   2. PARITY_HARNESS=<dir> SAFEPATH_OUT=<out> SAFEPATH_TEST=ParityDevice bash tools/safepath-harness/run.sh "" "PARITY"
 *
 * Env: PARITY_ZONES (tag:dir,...; default the four proof zones), PARITY_HARNESS (default the out folder), PARITY_PY=0 (no py checks).
 * Writes <out>/device/<tag>/ (the harvested files), <out>/device/levels_routes_<tag>.json (the device's routes, the harness's shape,
 * the harness's floors beside it for the py checks), <out>/parity_<tag>.txt / .json and <out>/parity.txt.
 *
 * SKIPS (JUnit's assumption) until the device side exists: ParityHook.IMPL, see ParityHook.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ParityDevice {
    private val out = File(System.getProperty("out.dir") ?: "out").also { it.mkdirs() }

    @Test fun deviceRoutesMatchTheHarness() {
        val (hook, why) = ParityHook.find()
        if (hook == null) println("PARITY SKIPPED: waiting for the device side - $why")
        assumeTrue("PARITY SKIPPED: waiting for the device side - $why", hook != null)
        val work = File(System.getenv("SAFEPATH_WORK") ?: "E:/safepath-work")
        val harnessDir = File(System.getenv("PARITY_HARNESS") ?: out.path)
        val here = File(System.getProperty("user.dir"))                         // robo/
        val zonesDir = File(here.parentFile, "zones"); val py = File(here.parentFile, "py")
        val zones = (System.getenv("PARITY_ZONES") ?: DEFAULT_ZONES).split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { it.substringBefore(':') to it.substringAfter(':') }
        val toc = System.getenv("QDBTOC")?.let { File(it) }?.takeIf { it.isFile }
        val t0 = System.currentTimeMillis()
        val reports = ArrayList<ParityCompare.Report>(); val all = StringBuilder()
        for ((tag, dir) in zones) {
            val z0 = System.currentTimeMillis()
            val hf = File(harnessDir, "levels_routes_$tag.json")
            check(hf.isFile) { "PARITY $tag: no harness routes at $hf - run the harness for the zone first (SAFEPATH_OUT=$harnessDir bash run.sh zones/$tag.json)" }
            val harness = ParityCompare.readRoutes(hf)
            val goals = ParityCompare.goals(harness, File(harnessDir, "full_$tag.xml").takeIf { it.isFile }?.readText())
            val spec = JSONObject(File(zonesDir, "$tag.json").readText())
            val (blind, withheld) = NameBlind.strip(spec)
            val maps = blind.optJSONArray("maps")?.let { a -> (0 until a.length()).map { i -> a.getJSONArray(i).let { intArrayOf(it.getInt(0), it.getInt(1)) } } } ?: emptyList()
            val zone = ParityZone(tag, blind.getInt("zone"), blind.getInt("area"), blind.optString("faction", "horde"), blind.optInt("level", 1), maps,
                blind, withheld, DumpSource(File(work, dir)), work, harnessDir, toc)
            val files = File(out, "device/$tag").also { it.deleteRecursively(); it.mkdirs() }
            val log: (String) -> Unit = { println("DEVICE $tag $it") }
            hook!!.harvest(zone, files, log)
            val h1 = System.currentTimeMillis()
            val device = hook.route(zone, files, goals, log)
            val h2 = System.currentTimeMillis()
            val df = File(out, "device/levels_routes_$tag.json")
            ParityCompare.writeRoutes(df, device); ParityCompare.copyBeside(harnessDir, tag, df)
            val (seg, wet, notes) = if (System.getenv("PARITY_PY") == "0") Triple(null, null, emptyList())
                else ParityCompare.checks(py, df, dir, work, device.size)
            val rep = ParityCompare.compare(tag, harness, device, seg, wet,
                notes + "withheld (name-blind): ${withheld.ifEmpty { listOf("nothing") }.joinToString()}" + "harvest ${h1 - z0} ms, routes ${h2 - h1} ms",
                System.currentTimeMillis() - z0)
            File(out, "parity_$tag.txt").writeText(rep.text() + "\n"); File(out, "parity_$tag.json").writeText(rep.json().toString(1))
            println(rep.text()); all.appendLine(rep.text()); reports.add(rep)
        }
        val ms = System.currentTimeMillis() - t0
        val fails = reports.flatMap { it.fails } + (if (ms > ParityCompare.BUDGET_MS) listOf("the run took $ms ms, over the ${ParityCompare.BUDGET_MS} ms budget") else emptyList())
        val sum = "PARITY ALL: ${reports.size} zones, ${reports.sumOf { r -> r.lines.count { it.ok } }}/${reports.sumOf { it.lines.size }} routes ok, ${fails.size} failures, $ms ms"
        println(sum); all.appendLine(sum); fails.forEach { all.appendLine("PARITY FAIL $it") }
        File(out, "parity.txt").writeText(all.toString())
        File(out, "parity.json").writeText(JSONObject().put("ms", ms).put("fails", JSONArray(fails)).put("zones", JSONArray(reports.map { it.json() })).toString(1))
        // differences are RULE GAPS: reported, never loosened
        assertTrue("PARITY: ${fails.size} failures (rule gaps; see ${File(out, "parity.txt")}):\n" + fails.joinToString("\n"), fails.isEmpty())
    }

    companion object {
        const val DEFAULT_ZONES = "durotar:kalimdor,elwynn:azeroth,silverpine:azeroth,mulgore:kalimdor"
    }
}
