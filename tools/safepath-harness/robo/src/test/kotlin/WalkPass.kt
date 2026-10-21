import com.jrees.wowcompanion.Climb
import com.jrees.wowcompanion.Db2
import com.jrees.wowcompanion.MapHarvest
import com.jrees.wowcompanion.ShippedNet
import com.jrees.wowcompanion.Terrain
import com.jrees.wowcompanion.ZoneInputs
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * THE LATTICE'S WALK CLASSES ADDED TO A PACK (ShippedNet.Walk; the user, 2026-10-21: "Yes, try it"): the zone's lattice built as
 * RenderSafePath builds it (the app's ZoneInputs and its Lattice), stripped to its classes (steps, faces, 45-48 degree faces: no heights,
 * no rooms) and written into the packed map file WALK_PACK in place; read back. ZONESPEC (run.sh's spec) the zone. Prints a WALKPACK line.
 *   WALK_PACK=<map>.bin SAFEPATH_TEST=WalkPass bash tools/safepath-harness/run.sh tools/safepath-harness/zones/<tag>.json WALKPACK
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class WalkPass {
    @Test fun walk() {
        val spec = JSONObject(File(System.getenv("ZONESPEC") ?: return).readText())
        val packF = File(System.getenv("WALK_PACK") ?: return)
        val work = File(System.getenv("SAFEPATH_WORK") ?: return)
        val t0 = System.nanoTime()
        val dir = File(work, spec.optString("dir", "kalimdor"))
        val db2 = File(System.getenv("DB2_DIR") ?: File(work, "db2").path)
        val map = spec.getInt("zone")
        val lethal = File(db2, "LiquidType.db2").takeIf { it.isFile }?.let { f -> Terrain.lethalLiquids(Db2(f.readBytes()).rows(strings = setOf(0)).associate { it.id to it.str(0) }) } ?: emptySet()
        if (lethal.isNotEmpty()) Terrain.lethal = lethal
        val recipeJ = JSONObject(File(System.getenv("RECIPE")).readText()).optJSONObject("rects")!!
        val a = recipeJ.optJSONArray(map.toString())!!
        val rect = MapHarvest.Rect(a.optInt(0), a.optDouble(1), a.optDouble(2), a.optDouble(3), a.optDouble(4))
        val capitalRects = com.jrees.wowcompanion.CityExits.CITIES.mapNotNull { c -> recipeJ.optJSONArray(c.toString())?.let { q -> doubleArrayOf(q.getDouble(1), q.getDouble(2), q.getDouble(3), q.getDouble(4)) } }
        val zi = ZoneInputs(DumpSource(dir), rect, lethal, capitalRects) { }
        val zl = zi.Lattice(zi.heightV(spec.optDouble("voidBelow", Double.NaN)))
        val cg = zl.cg
        val pack = packF.inputStream().use { ShippedNet.read(it) } ?: run { println("WALKPACK FAIL ${packF.name}: not NET1"); return }
        if (pack.header.map != map) { println("WALKPACK FAIL ${packF.name}: map ${pack.header.map}, the spec's $map"); return }
        val before = packF.length()
        val out = ShippedNet.Pack(pack.header, pack.sections, pack.roadmap, ShippedNet.Walk.strip(cg))
        val bytes = ShippedNet.bytes(out)
        packF.writeBytes(bytes)
        val back = packF.inputStream().use { ShippedNet.read(it) }
        val w = back?.walk
        val ok = back != null && w != null && w.nx == cg.nx && w.ny == cg.ny && w.gr0 == cg.gr0 && w.gc0 == cg.gc0 && w.cls.contentEquals(cg.cls) &&
            (cg.faces == null || w.faces?.contentEquals(cg.faces!!) == true) && back.roadmap?.walk === w &&
            ShippedNet.bytes(back).contentEquals(bytes)
        val walls = (0 until cg.nx * cg.ny).count { (cg.cls[it].toInt() and 3) == Climb.WALL }
        println("WALKPACK ${if (ok) "OK" else "FAIL (did not round-trip)"} map $map ${spec.optString("tag")} -> ${packF.path}: lattice ${cg.nx} x ${cg.ny} " +
            "(faces ${cg.faces != null}, 45-48 ${cg.faces48 != null}), ${walls * 100 / maxOf(1, cg.nx * cg.ny)}% east steps walled; pack $before -> ${bytes.size} bytes " +
            "(walk ${ShippedNet.pack64(ShippedNet.Walk.encode(cg)).length} base64); ${(System.nanoTime() - t0) / 1_000_000} ms")
    }
}
