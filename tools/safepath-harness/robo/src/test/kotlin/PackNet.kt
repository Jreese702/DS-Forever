import com.jrees.wowcompanion.ShippedNet
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * THE SHIPPED NETWORK'S PACKER (tools/safepath-harness/export.sh; NET1, ShippedNet): the gated sections of one map (RenderSafePath's
 * net1_<tag>.json, one faction each) into its asset, NET1_ASSETS/<map>.bin, then read back and checked against what was written (the
 * file loads and round-trips). NET1_IN: the sections' files, separated by ';'. Prints a NET1PACK line: OK or FAIL with why.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PackNet {
    @Test fun pack() {
        val ins = (System.getenv("NET1_IN") ?: return).split(';').filter { it.isNotBlank() }.map { File(it.trim()) }
        val dir = File(System.getenv("NET1_ASSETS") ?: return).also { it.mkdirs() }
        val packs = ins.map { f -> ShippedNet.Pack.fromJson(JSONObject(f.readText())) ?: error("NET1PACK FAIL ${f.name}: not NET1") }
        for ((map, ps) in packs.groupBy { it.header.map }) {
            val h = ps.first().header
            val bad = ps.filter { it.header.rules != h.rules || it.header.rulesCommit != h.rulesCommit || it.header.build != h.build || it.header.cont != h.cont }
            if (bad.isNotEmpty()) { println("NET1PACK FAIL map $map: its sections' stamps differ (${ps.map { "${it.header.rules}/${it.header.rulesCommit.take(7)}/${it.header.build.take(8)}" }})"); continue }
            val secs = ps.flatMap { it.sections }
            val dup = secs.groupBy { it.faction }.filter { it.value.size > 1 }.keys
            if (dup.isNotEmpty()) { println("NET1PACK FAIL map $map: two sections for faction(s) $dup (${secs.map { it.tag }})"); continue }
            val pack = ShippedNet.Pack(ShippedNet.Header(map, h.cont, h.rules, h.rulesCommit, h.build, ps.maxOf { it.header.exported }), secs.sortedBy { it.faction })
            val bytes = ShippedNet.bytes(pack)
            val f = File(dir, "$map.bin"); f.writeBytes(bytes)
            // the file loads, and what loads writes the same bytes again
            val back = f.inputStream().use { ShippedNet.read(it) }
            val ok = back != null && back.sections.size == pack.sections.size && ShippedNet.bytes(back).contentEquals(bytes) &&
                back.sections.zip(pack.sections).all { (a, b) -> a.net.edges.size == b.net.edges.size && a.net.nodes.size == b.net.nodes.size && a.faction == b.faction }
            println("NET1PACK ${if (ok) "OK" else "FAIL (did not round-trip)"} map $map -> ${f.path}: ${bytes.size} bytes; rules ${h.rules} commit ${h.rulesCommit.take(10)} build ${h.build}; " +
                pack.sections.joinToString("; ") { "${it.tag} (faction ${it.faction}): ${it.net.nodes.size} nodes, ${it.net.edges.size} ways, ${it.net.exits.size} exits, ${it.links.size} links" })
        }
    }
}
