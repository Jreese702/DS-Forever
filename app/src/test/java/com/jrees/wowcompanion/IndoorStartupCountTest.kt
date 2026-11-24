package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Fix 2 (2026-11-23, the plan's "Never finished": "When the app has just started it also wipes the count to 0/0"): the builder's first
 * wait of a process published PAUSED from the in-memory status (null before this process ran the step), so the start screen read 0/0
 * and status.json was overwritten with it. The real step on a real context: the count on disk survives the start-up wait. (The
 * companion's internals are read by reflection: this module sees only its public API.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IndoorStartupCountTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val step = IndoorSchedulerStep::class.java

    private fun field(n: String) = step.getDeclaredField(n).apply { isAccessible = true }

    @After fun stop() {
        IndoorSchedulerStep.stop()
        val until = System.currentTimeMillis() + 10_000L
        while (field("thread").get(IndoorSchedulerStep) != null && System.currentTimeMillis() < until) Thread.sleep(20)
    }

    @Test fun theStartUpWaitKeepsTheCountOnDisk() {
        // a game folder (the step looks for its build) and the last run's status on disk: 1616 of 1837 ready
        val game = java.nio.file.Files.createTempDirectory("ia-game").toFile()
        File(game, ".build.info").writeText("Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|Version!STRING:0|Product!STRING:0\nus|1|0123456789abcdef0123456789abcdef|1.0|wowf\n")
        app.getSharedPreferences(GameFiles.PREFS, android.content.Context.MODE_PRIVATE).edit().putString(GameFiles.KEY_GAME_PATH, game.path).commit()
        val dir = File(ArtHarvest.root(app), "interiors_sched").apply { mkdirs() }
        val status = File(dir, "status.json")
        status.writeText("""{"phase":"DEFERRED","ready":1616,"total":1837,"deferred":221,"note":"221 deferred","now":"","list":[]}""")
        field("status").set(IndoorSchedulerStep, null)                       // a fresh process: nothing in memory
        // a generator is registered (CompanionHost does it); never opened before the first game
        val factoryType = Class.forName("com.jrees.wowcompanion.IndoorGenerator\$Factory")
        val factory = java.lang.reflect.Proxy.newProxyInstance(factoryType.classLoader, arrayOf(factoryType)) { _, m, _ ->
            if (m.name == "open") error("never opened before Play") else null
        }
        field("factory").set(IndoorSchedulerStep, factory)
        IndoorSchedulerStep.start(app)
        // before the first game of the run it waits ("Builds after you play") with the game files closed
        val until = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < until && runCatching { JSONObject(status.readText()).optString("phase") }.getOrNull() != "PAUSED") Thread.sleep(20)
        val disk = JSONObject(status.readText())
        assertEquals("PAUSED", disk.optString("phase"))
        assertEquals("status.json keeps the count, not 0/0", 1616, disk.optInt("ready"))
        assertEquals(1837, disk.optInt("total"))
        assertEquals("Indoor Maps (Optional): 1616/1837 ready - Builds after you play", IndoorSchedulerStep.readiness(app).label)
        game.deleteRecursively()
    }

    /**
     * Review of 2026-11-23 (D1: "the row turns green"): a build stamped finished for this game build and builder keeps saying so. With
     * Settings' switch turned Off the row read "off in Settings"; turned On again the stamped step returned at once and the row kept
     * that line for good, never "Finished".
     */
    @Test fun aFinishedBuildSaysFinishedAfterTheSwitchWasOffAndOn() {
        val build = "fedcba9876543210fedcba9876543210"
        val game = java.nio.file.Files.createTempDirectory("ia-game2").toFile()
        File(game, ".build.info").writeText("Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|Version!STRING:0|Product!STRING:0\nus|1|$build|1.0|wowf\n")
        app.getSharedPreferences(GameFiles.PREFS, android.content.Context.MODE_PRIVATE).edit().putString(GameFiles.KEY_GAME_PATH, game.path).commit()
        val dir = File(ArtHarvest.root(app), "interiors_sched").apply { mkdirs() }
        val status = File(dir, "status.json")
        // the finished run's count, then Settings' switch Off: "off in Settings" with that count
        status.writeText("""{"phase":"STOPPED","ready":1632,"total":1853,"deferred":221,"note":"off in Settings","now":"","list":[],"rejected":221}""")
        // stamped finished for this game build and this builder
        ArtHarvest.markDetachedDone(app, IndoorSchedulerStep, build, IndoorSchedulerStep.inputs(app, build))
        field("status").set(IndoorSchedulerStep, null)
        val factoryType = Class.forName("com.jrees.wowcompanion.IndoorGenerator\$Factory")
        field("factory").set(IndoorSchedulerStep, java.lang.reflect.Proxy.newProxyInstance(factoryType.classLoader, arrayOf(factoryType)) { _, _, _ ->
            error("a finished build never opens the generator") })
        Settings.init(app); Settings.indoorBuild = true
        IndoorSchedulerStep.start(app)
        val until = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < until && field("thread").get(IndoorSchedulerStep) != null) Thread.sleep(20)
        assertEquals("Indoor Maps (Optional): Finished: 1,632 maps, 221 places can't be mapped", IndoorSchedulerStep.readiness(app).label)
        assertEquals("DONE", JSONObject(status.readText()).optString("phase"))
        game.deleteRecursively()
    }
}
