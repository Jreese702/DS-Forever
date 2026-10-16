package app.gamenative

import com.jrees.wowcompanion.DataStaleness
import com.jrees.wowcompanion.GameFiles
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** issues #8 / #9, off-device end to end: the art's reads decide, the verdict is kept per folder, a recopy clears it */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataStalenessFlowTest {
    private val head = "Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|CDN Key!HEX:16|Install Key!HEX:16|IM Size!DEC:4|CDN Path!STRING:0|" +
        "CDN Hosts!STRING:0|CDN Servers!STRING:0|Tags!STRING:0|Armadillo!STRING:0|Last Activated!STRING:0|Version!STRING:0|" +
        "KeyRing!HEX:16|Product!STRING:0"
    private val row = "us|1|bk70425|ck||0|tpr/wow|level3.blizzard.com|||enUS text?||1.60.1.70425||wow_classic_beta"

    @Test fun aStaleFolderIsSaidUntilItsDataIsRecopied() {
        val ctx = RuntimeEnvironment.getApplication()
        val root = Files.createTempDirectory("Wow").toFile()
        File(root, ".build.info").writeText("$head\n$row\n")
        val data = File(root, "Data/data").apply { mkdirs() }
        File(data, "0000000001.idx").writeBytes(ByteArray(100))
        ctx.getSharedPreferences(GameFiles.PREFS, 0).edit().putString(GameFiles.KEY_GAME_PATH, root.path).commit()
        DataStaleness.init(ctx)
        val (_, build) = GameFiles.activeBuild(root)!!
        var t = 1_000_000L
        assertNull(DataStaleness.current(t))                                       // nothing known yet
        repeat(3) { DataStaleness.count(root, build, local = true) }
        repeat(57) { DataStaleness.count(root, build, local = false) }             // #9: nearly all from the CDN
        t += 60_000
        val msg = DataStaleness.current(t)!!
        assertTrue(msg.contains("1.60.1.70425")); assertTrue(msg.contains("Copy the Data folder again"))
        t += 60_000
        assertEquals(msg, DataStaleness.current(t))                                // kept (a later run's cache hits cannot clear it)
        File(data, "0000000001.idx").writeBytes(ByteArray(400))                    // the player recopies Data
        t += 60_000
        assertNull(DataStaleness.current(t))                                       // cleared
        t += 60_000
        assertNull(DataStaleness.current(t))
    }
}
