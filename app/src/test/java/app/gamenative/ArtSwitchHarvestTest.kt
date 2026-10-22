package app.gamenative

import android.content.Context
import com.jrees.wowcompanion.ArtHarvest
import com.jrees.wowcompanion.GameFiles
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * test55: after the launch switch the art never started for the new build. The art is stamped per build, so the folder moving to
 * wowf makes every before-Play step pending again; [ArtHarvest.restart] forgets the old run so the next start is a fresh one.
 */
@RunWith(RobolectricTestRunner::class)
class ArtSwitchHarvestTest {
    private val header = "Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|CDN Key!HEX:16|Install Key!HEX:16|IM Size!DEC:4|CDN Path!STRING:0|" +
        "CDN Hosts!STRING:0|CDN Servers!STRING:0|Tags!STRING:0|Armadillo!STRING:0|Last Activated!STRING:0|Version!STRING:0|KeyRing!HEX:16|Product!STRING:0"
    private fun row(key: String, version: String, product: String): String {
        val cols = header.split('|').map { it.substringBefore('!') }
        val v = mapOf("Branch" to "us", "Active" to "1", "Build Key" to key, "CDN Key" to "6bff2e685995b214c2ff5d08f3344542",
            "Version" to version, "Product" to product)
        return cols.joinToString("|") { v[it] ?: "" }
    }
    private val beta = row("84bea4233331e390fda8724c6f91ffa5", "1.60.1.70450", "wow_classic_beta")
    private val wowf = row("2582289d81e380163c7a13472df4cb81", "1.60.1.70532", "wowf")

    private fun folder(ctx: Context): File {
        val root = Files.createTempDirectory("wowroot").toFile()
        File(root, ".build.info").writeText("$header\n$beta\n")
        File(root, "_classic_beta_").mkdirs(); File(root, "_classic_beta_/WowB-ARM64.exe").writeText("exe")
        ctx.getSharedPreferences(GameFiles.PREFS, Context.MODE_PRIVATE).edit().putString(GameFiles.KEY_GAME_PATH, root.path).commit()
        return root
    }

    @Test fun theArtStampedForTheBetaIsPendingAgainOnTheLaunchBuild() {
        val ctx = RuntimeEnvironment.getApplication()
        val root = folder(ctx)
        assertEquals("84bea4233331e390fda8724c6f91ffa5", GameFiles.activeBuild(root)!!.second)
        // every step done for the beta's build
        val st = JSONObject()
        for (s in ArtHarvest.STEPS) st.put(s.id, JSONObject().put("build", "84bea4233331e390fda8724c6f91ffa5").put("v", s.version))
        File(ArtHarvest.root(ctx), "stamp.json").writeText(st.toString())
        val beforePlay = ArtHarvest.STEPS.filter { it.beforePlay && !it.detached }.map { it.id }
        val betaPending = ArtHarvest.pending(ctx, beforePlay = true).map { it.id }
        // the switch: the wowf row added (the beta's kept) and the launch client's exe on disk
        File(root, ".build.info").writeText("$header\n$beta\n$wowf\n")
        File(root, "_forever_").mkdirs(); File(root, "_forever_/Wow-arm64.exe").writeText("exe")
        assertEquals("2582289d81e380163c7a13472df4cb81", GameFiles.activeBuild(root)!!.second)
        val now = ArtHarvest.pending(ctx, beforePlay = true).map { it.id }
        assertTrue("every before-Play step pending for the new build: $now", now.containsAll(beforePlay))
        assertTrue("more pending than for the beta: $betaPending -> $now", now.size >= betaPending.size && now.isNotEmpty())
        assertFalse(ArtHarvest.beforePlayReady(ctx))
    }

    @Test fun aRestartedHarvestRunsAgainAndAnswersFromTheNewRun() {
        val ctx = RuntimeEnvironment.getApplication()
        val root = folder(ctx)
        File(root, ".build.info").writeText("$header\n$beta\n$wowf\n")
        File(root, "_forever_").mkdirs(); File(root, "_forever_/Wow-arm64.exe").writeText("exe")
        ArtHarvest.restart(ctx)
        assertEquals(ArtHarvest.State.Idle, ArtHarvest.state)
        // a fresh run on the new build: no game data in this folder, so it answers "not ready" from ITS run (never a stale "ready")
        val done = CountDownLatch(1); var answer: Boolean? = null
        ArtHarvest.start(ctx) { r -> answer = r; done.countDown() }
        assertTrue("the new run answered", done.await(60, TimeUnit.SECONDS))
        assertEquals(false, answer)
        assertTrue(ArtHarvest.state is ArtHarvest.State.Failed)
    }
}
