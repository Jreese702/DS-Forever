import com.jrees.wowcompanion.ZoneInputs
import java.io.File

/**
 * A ZONE'S FILES FROM THE PC CLIENT'S DUMPS (tools/safepath-harness/dump): what the device reads from the game by fileDataID, read from
 * the dump folder [dir] (a continent's: adt/, obj0/, wmo/, wmogroup/, m2/). Every dumped obj0 counts, in the folder's own order.
 */
class DumpSource(private val dir: File) : ZoneInputs.Source {
    private fun bytes(rel: String) = File(dir, rel).takeIf { it.isFile }?.readBytes()
    override fun adt(c: Int, r: Int) = bytes("adt/${c}_$r.adt")
    override fun obj0(c: Int, r: Int) = bytes("obj0/${c}_$r.adt")
    override fun wmo(fid: Int) = bytes("wmo/$fid")
    override fun wmoGroup(fid: Int, index: Int) = bytes("wmogroup/${fid}_$index")
    override fun m2(fid: Int) = bytes("m2/$fid")
    override fun objTiles(): List<IntArray> = (File(dir, "obj0").listFiles() ?: emptyArray()).mapNotNull { f ->
        Regex("""(\d+)_(\d+)\.adt""").matchEntire(f.name)?.let { m -> intArrayOf(m.groupValues[1].toInt(), m.groupValues[2].toInt()) } }
}
