import com.jrees.wowcompanion.ShippedNet
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * THE SHIPPED NETWORK'S PACKER (tools/safepath-harness/export.sh; NET1, ShippedNet): the gated sections (RenderSafePath's
 * net1_<tag>.json, one spec's one faction each) grouped by map into its asset, NET1_ASSETS/<map>.bin, then read back and checked
 * against what was written (the file loads and round-trips). Specs sharing a map (Alterac and Dalaran: 1416) are ONE file with ONE
 * section a faction, the union of their networks (ShippedNet.merge; each way and exit keeps its spec's tag). The map's roadmap is the
 * one with the most nodes among its specs' (the specs' lattices are one map's: the same, unless a spec reads more floors); each
 * faction's roadmap danger from a spec whose roadmap that is. NET1_IN: the sections' files, separated by ';'. Prints a NET1PACK line:
 * OK or FAIL with why.
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
            // the roadmap: the largest of the specs' (its encoding compared: the same roadmap, the same bytes)
            val rms = ps.mapNotNull { p -> p.roadmap?.let { p to it.encode() } }
            val chosen = rms.maxByOrNull { it.first.roadmap!!.nodes }
            val sameRm = rms.filter { chosen != null && it.second.contentEquals(chosen.second) }.map { it.first }.toSet()
            if (rms.size > sameRm.size) println("NET1PACK map $map: ${rms.size - sameRm.size} spec(s)' roadmap differs from the largest's - their roadmap danger not used " +
                "(${rms.joinToString { "${it.first.sections.first().tag} ${it.first.roadmap!!.nodes} nodes" }})")
            val secs = ps.flatMap { p -> p.sections.map { p to it } }.groupBy { it.second.faction }.toSortedMap().map { (_, l) ->
                val danger = l.firstOrNull { it.first in sameRm }?.second?.roadmapDanger
                // (a spec whose roadmap is not the map's: its stitches name another roadmap's nodes - left out)
                ShippedNet.merge(l.map { if (it.first in sameRm) it.second else it.second.copy(stitches = emptyList()) }, danger) }
            val pack = ShippedNet.Pack(ShippedNet.Header(map, h.cont, h.rules, h.rulesCommit, h.build, ps.maxOf { it.header.exported }), secs, chosen?.first?.roadmap)
            val bytes = ShippedNet.bytes(pack)
            val f = File(dir, "$map.bin"); f.writeBytes(bytes)
            // the file loads, and what loads writes the same bytes again
            val back = f.inputStream().use { ShippedNet.read(it) }
            val ok = back != null && back.sections.size == pack.sections.size && ShippedNet.bytes(back).contentEquals(bytes) &&
                back.sections.zip(pack.sections).all { (a, b) -> a.net.edges.size == b.net.edges.size && a.net.nodes.size == b.net.nodes.size && a.faction == b.faction } &&
                (back.roadmap?.edges ?: -1) == (pack.roadmap?.edges ?: -1) &&
                back.sections.all { s -> s.roadmapDanger == null || s.roadmapDanger!!.size == (back.roadmap?.edges ?: -1) }
            val rmB = pack.roadmap?.let { ShippedNet.pack64(it.encode()).length } ?: 0
            println("NET1PACK ${if (ok) "OK" else "FAIL (did not round-trip)"} map $map -> ${f.path}: ${bytes.size} bytes (roadmap $rmB); rules ${h.rules} commit ${h.rulesCommit.take(10)} build ${h.build}; " +
                "roadmap ${pack.roadmap?.nodes ?: 0} nodes ${pack.roadmap?.edges ?: 0} edges; " +
                pack.sections.joinToString("; ") { "${it.tag} (faction ${it.faction}): ${it.net.nodes.size} nodes, ${it.net.edges.size} ways (${it.ways.count { w -> w.pts != null }} walked), ${it.net.exits.size} exits, ${it.links.size} links" })
        }
    }
}
