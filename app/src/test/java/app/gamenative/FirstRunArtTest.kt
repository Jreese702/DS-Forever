package app.gamenative

import android.content.Context
import com.jrees.wowcompanion.ArtHarvest
import com.jrees.wowcompanion.DataStaleness
import com.jrees.wowcompanion.GameFiles
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Issue #571, the art's side: on a game folder that has never run the art does not open it (CascLib's "loading index files 0/0",
 * error 2) and its "failure" never holds Play; a folder that ran fails as before (it holds Play: the existing behaviour).
 */
@RunWith(RobolectricTestRunner::class)
class FirstRunArtTest {
    private val header = "Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|CDN Key!HEX:16|Install Key!HEX:16|IM Size!DEC:4|CDN Path!STRING:0|" +
        "CDN Hosts!STRING:0|CDN Servers!STRING:0|Tags!STRING:0|Armadillo!STRING:0|Last Activated!STRING:0|Version!STRING:0|KeyRing!HEX:16|Product!STRING:0"
    private val row = run {
        val v = mapOf("Branch" to "us", "Active" to "1", "Build Key" to "2582289d81e380163c7a13472df4cb81",
            "CDN Key" to "6bff2e685995b214c2ff5d08f3344542", "Version" to "1.60.1.70532", "Product" to "wowf")
        header.split('|').map { it.substringBefore('!') }.joinToString("|") { v[it] ?: "" }
    }

    private fun folder(ctx: Context, idx: Int): File {
        val root = Files.createTempDirectory("wow571art").toFile()
        File(root, ".build.info").writeText("$header\n$row\n")
        File(root, "_forever_").mkdirs(); File(root, "_forever_/Wow-arm64.exe").writeText("exe")
        val data = File(root, "Data/data").apply { mkdirs() }
        repeat(idx) { File(data, "%02x00000001.idx".format(it)).writeBytes(ByteArray(64)) }
        ctx.getSharedPreferences(GameFiles.PREFS, Context.MODE_PRIVATE).edit().putString(GameFiles.KEY_GAME_PATH, root.path).commit()
        return root
    }

    private fun run(ctx: Context): Boolean {
        ArtHarvest.restart(ctx)
        val done = CountDownLatch(1)
        var r = true
        ArtHarvest.start(ctx) { ok -> r = ok; done.countDown() }
        assertTrue("the art run answered", done.await(90, TimeUnit.SECONDS))
        return r
    }

    @Test fun aNeverRunFolderIsNotOpenedAndNeverHoldsPlay() {
        val ctx = RuntimeEnvironment.getApplication()
        val root = folder(ctx, idx = 0)
        assertTrue(DataStaleness.firstRun(root))
        assertFalse(run(ctx))                                   // nothing built yet
        val st = ArtHarvest.state
        assertTrue("$st", st is ArtHarvest.State.Failed)
        assertEquals(DataStaleness.FIRST_RUN_WHY, (st as ArtHarvest.State.Failed).why)
        assertFalse(st.blocksPlay)                              // before the fix: Failed(beforePlay = true) held Play
    }

    @Test fun aFolderThatRanStillHoldsPlayOnAFailure() {
        val ctx = RuntimeEnvironment.getApplication()
        val root = folder(ctx, idx = 16)
        assertFalse(DataStaleness.firstRun(root))
        assertFalse(run(ctx))                                   // no file reader off the device: the open fails as before
        val st = ArtHarvest.state
        assertTrue("$st", st is ArtHarvest.State.Failed)
        assertTrue((st as ArtHarvest.State.Failed).blocksPlay)
        assertFalse(st.why == DataStaleness.FIRST_RUN_WHY)
    }
}
