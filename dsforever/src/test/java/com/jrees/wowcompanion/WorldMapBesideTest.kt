package com.jrees.wowcompanion

import android.content.ComponentCallbacks2
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * THE WORLD MAP BESIDE THE GAME ON THE THOR MAX (the user's pick, 2026-11-16, #316 #317 #284: "Let ONLY the world-map art step build
 * beside the running game on the 16 GB Thor Max (DeviceTier / RAM >= ~15 GB); every other background art step keeps pausing while the
 * game runs; keep the memory relief and the pause points; log it"). The rule (PlayGate, DeviceTier), the live state it reads (GameLife,
 * MemWatch) and the worker's wiring (ArtHarvest, read as source: its pass needs the real game files).
 */
class WorldMapBesideTest {
    private val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
        .map { File(it) }.first { it.isDirectory }

    /** every step id the art has (ArtMapsFirstTest's list) */
    private val steps = listOf("kit", "cast", "icons", "allicons", "worldmap", "poiicons", "npcicons", "minimap", "transports",
        "travelicons", "wmominimap", "areas", "terrain", "groundtex", "seamless", "safepathzones", "climb", "interiors")

    private fun setTotalMb(mb: Long) =
        DeviceTier::class.java.getDeclaredField("totalMb").apply { isAccessible = true }.setLong(null, mb)

    /** Android's last warning (MemWatch.pressureAt) is the process's: one test's warning must not end another's pass */
    private fun clearWarning() =
        MemWatch::class.java.getDeclaredField("pressureAt").apply { isAccessible = true }.setLong(null, 0L)

    @Before fun diag() { DiagLog.useDir(Files.createTempDirectory("diag").toFile()); clearWarning() }

    @After fun reset() {
        clearWarning()
        setTotalMb(0L)
        GameLife.ended(0, System.currentTimeMillis())
        LauncherHooks.gameUp = { false }; LauncherHooks.launchState = ""; LauncherHooks.launchAtMs = 0L; LauncherHooks.gameStartSec = 0L
    }

    // ------------------------------------------------------------------ the model

    @Test fun onlyTheSixteenGigabyteThorMaxWithLighterModeOff() {
        assertTrue("the Thor Max as Android counts it", DeviceTier.besideModel(15_255L, lighter = false))
        assertTrue(DeviceTier.besideModel(DeviceTier.BESIDE_MB, lighter = false))
        assertFalse(DeviceTier.besideModel(DeviceTier.BESIDE_MB - 1, lighter = false))
        assertFalse("the Thor Pro (12 GB)", DeviceTier.besideModel(11_265L, lighter = false))
        assertFalse("the Thor Base (8 GB)", DeviceTier.besideModel(7_191L, lighter = false))
        assertFalse("the RAM not known yet", DeviceTier.besideModel(0L, lighter = false))
        assertFalse("lighter mode on: it waits like every other step", DeviceTier.besideModel(15_255L, lighter = true))
        assertEquals("Thor Max (16 GB)", DeviceTier.model(15_255L, lite = false))
    }

    // ------------------------------------------------------------------ the step and the moments

    @Test fun onlyTheWorldMapStep() {
        assertEquals("worldmap", PlayGate.BESIDE_STEP)
        assertTrue(File(src, "MapHarvestSteps.kt").readText().contains("override val id = \"${PlayGate.BESIDE_STEP}\""))
        for (s in steps) assertEquals(s, s == "worldmap", PlayGate.mayBuildBeside(s, model = true, inWorld = true, loading = false,
            calmMs = PlayGate.BESIDE_CALM_MS, pressure = false))
        assertFalse(PlayGate.mayBuildBeside(null, true, true, false, PlayGate.BESIDE_CALM_MS, false))
    }

    @Test fun thePausePointsStayTheLaunchTheLoadingScreensAndAndroidsWarning() {
        fun may(model: Boolean = true, inWorld: Boolean = true, loading: Boolean = false, calm: Long = PlayGate.BESIDE_CALM_MS,
                pressure: Boolean = false) = PlayGate.mayBuildBeside("worldmap", model, inWorld, loading, calm, pressure)
        assertTrue(may())
        assertFalse("another model", may(model = false))
        assertFalse("the launch / the login: not in the world yet", may(inWorld = false))
        assertFalse("a loading screen up", may(loading = true))
        assertFalse("too soon after the world entry or a load", may(calm = PlayGate.BESIDE_CALM_MS - 1))
        assertFalse("Android warned running-low in this game", may(pressure = true))
    }

    @Test fun theBackgroundPauseBendsOnlyForTheBesidePass() {
        assertTrue(PlayGate.artMustPause(backgroundPhase = true, gameActive = true))
        assertFalse(PlayGate.artMustPause(backgroundPhase = true, gameActive = true, beside = true))
        assertTrue("on the worker only, as before", PlayGate.artMustPause(true, true, onWorker = true, beside = false))
        assertFalse(PlayGate.artMustPause(backgroundPhase = false, gameActive = true, beside = false))
        // the stops as one answer: the beside pass neither lets go for the game nor for a required step it could not run in game
        assertFalse(ArtHarvest.letGo(stopping = false, yieldBg = false, bgPhase = true, gameActive = true, beside = true))
        assertFalse(ArtHarvest.letGo(stopping = false, yieldBg = true, bgPhase = true, gameActive = true, beside = true))
        assertTrue("the game update still stops it", ArtHarvest.letGo(stopping = true, yieldBg = false, bgPhase = true, gameActive = true, beside = true))
        assertTrue("not beside: as before", ArtHarvest.letGo(stopping = false, yieldBg = false, bgPhase = true, gameActive = true, beside = false))
    }

    // ------------------------------------------------------------------ the live state

    @Test fun androidsWarningsAreTheRunningLevelsOnly() {
        assertTrue(MemWatch.pressure(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
        assertTrue(MemWatch.pressure(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL))
        assertTrue(MemWatch.pressure(MemWatch.LOW_MEMORY))
        assertFalse(MemWatch.pressure(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
        assertFalse("where the screen is, not how short the device is", MemWatch.pressure(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
        assertFalse(MemWatch.pressure(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND))
        assertFalse("no game started", MemWatch.pressureSince(0L))
        val src = File(src, "MemWatch.kt").readText()
        val trimmed = src.substring(src.indexOf("fun trimmed(level: Int) {"), src.indexOf("/** every trim registration at [level]"))
        assertTrue("every trim from Android is kept", trimmed.contains("warned(level)"))
    }

    @Test fun theLiveRuleFollowsTheGameTheThorAndAndroid() {
        setTotalMb(15_255L)
        val now = System.currentTimeMillis()
        GameLife.started(now - 10 * 60_000L)
        assertFalse("before the world (the launch, the login)", PlayGate.besideNow("worldmap"))
        assertEquals(0L, GameLife.calmMs(now))
        GameLife.inWorld(now - 2 * 60_000L)
        assertTrue(GameLife.calmMs(now) >= 2 * 60_000L - 1_000L)
        assertTrue("the Thor Max, in the world, calm", PlayGate.besideNow("worldmap"))
        assertFalse("Home's map waits for the game to close", PlayGate.besideNow("minimap"))
        assertFalse(PlayGate.besideNow("allicons"))
        // a loading screen: it lets go; after its end the calm starts again
        GameLife.loadingScreen(true, now - 90_000L)
        assertTrue(GameLife.loadingNow)
        assertFalse(PlayGate.besideNow("worldmap"))
        GameLife.loadingScreen(false, now - 20_000L)
        assertFalse(GameLife.loadingNow)
        assertFalse("20 s after the load", PlayGate.besideNow("worldmap"))
        GameLife.loadingScreen(true, now - 80_000L); GameLife.loadingScreen(false, now - PlayGate.BESIDE_CALM_MS - 1_000L)
        assertTrue(PlayGate.besideNow("worldmap"))
        // another model: never
        setTotalMb(11_265L)
        assertFalse(PlayGate.besideNow("worldmap"))
        setTotalMb(15_255L)
        // Android's warning before this game does not count; one in it ends the pass for the rest of the game
        MemWatch.warned(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, now - 20 * 60_000L)
        assertTrue(PlayGate.besideNow("worldmap"))
        MemWatch.warned(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN, now)
        assertTrue(PlayGate.besideNow("worldmap"))
        MemWatch.warned(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, now - 1_000L)
        assertFalse(PlayGate.besideNow("worldmap"))
        // the next game starts clean
        GameLife.ended(0, now)
        assertFalse("the game ended", PlayGate.besideNow("worldmap"))
        GameLife.started(now + 1_000L)
        GameLife.inWorld(now + 2_000L)
        assertTrue(PlayGate.besideNow("worldmap").not())       // calm only from its world entry
        assertTrue(GameLife.calmMs(now + 2_000L + PlayGate.BESIDE_CALM_MS) >= PlayGate.BESIDE_CALM_MS)
        assertFalse("its warning time is its own", MemWatch.pressureSince(GameLife.startedAtMs))
    }

    /** review of the pick: a logout to the character list (or a /reload) is a pause point; the next world's load sends nothing */
    @Test fun aLogoutIsAPausePointUntilTheNextHello() {
        setTotalMb(15_255L)
        val now = System.currentTimeMillis()
        GameLife.started(now - 10 * 60_000L)
        GameLife.inWorld(now - 5 * 60_000L)
        assertTrue(PlayGate.besideNow("worldmap"))
        assertFalse(GameLife.awayNow)
        // Hub's "bye": the world closed; the character list and the next world's load are silent
        GameLife.worldLeft(now - 3 * 60_000L)
        assertTrue(GameLife.awayNow)
        assertEquals(0L, GameLife.calmMs(now))
        assertFalse("the character list, the next world's load", PlayGate.besideNow("worldmap"))
        // the add-on's hello: the world is back, the calm counts from it
        GameLife.worldBack(now - 20_000L)
        assertFalse(GameLife.awayNow)
        assertFalse("20 s after the hello", PlayGate.besideNow("worldmap"))
        GameLife.worldBack(now - 5 * 60_000L)
        assertFalse("a second hello does not move the calm back", PlayGate.besideNow("worldmap"))
        GameLife.worldLeft(now - 3 * 60_000L); GameLife.worldBack(now - PlayGate.BESIDE_CALM_MS - 1_000L)
        assertTrue(PlayGate.besideNow("worldmap"))
        // never before the world, and the next game starts with no logout word
        GameLife.ended(0, now); GameLife.worldLeft(now)
        GameLife.started(now + 1_000L)
        assertFalse(GameLife.awayNow)
        GameLife.worldLeft(now + 1_500L)
        GameLife.inWorld(now + 2_000L)
        assertFalse("a word before the world is not kept", GameLife.awayNow)
        // the wiring: the bye closes it, the hello opens it (CompanionHost)
        val host = File(src, "CompanionHost.kt").readText()
        fun branch(from: String, to: String): String { val a = host.indexOf(from); assertTrue(from, a >= 0); return host.substring(a, host.indexOf(to, a)) }
        assertTrue(branch("\"bye\" -> if (current) {", "\"chat\" -> ").contains("GameLife.worldLeft()"))
        assertTrue(branch("\"hello\" -> {", "\"social\" -> {").contains("if (current) GameLife.worldBack()"))
    }

    // ------------------------------------------------------------------ the worker (ArtHarvest), as source: the pass needs the game files

    private val harvest by lazy { File(src, "ArtHarvest.kt").readText() }
    private fun fn(name: String): String {
        val at = harvest.indexOf(name)
        assertTrue(name, at >= 0)
        return harvest.substring(at, harvest.indexOf("\n    }\n", at))
    }

    @Test fun thePassRunsTheWorldMapAloneAndLogsIt() {
        val pass = fn("private fun besidePass(ctx: Context, step: Step): Boolean {")
        assertTrue(pass.contains("require(step.id == PlayGate.BESIDE_STEP)"))
        assertTrue("one step, nothing else", pass.contains("runAll(ctx, g, listOf(step), beforePlay = false)"))
        assertEquals("one runAll", 1, Regex("runAll\\(").findAll(pass).count())
        assertTrue("the files open for it close with it", pass.contains("game.use { g ->"))
        assertTrue("its pause frees what it held", pass.contains("openedFiles = true"))
        assertTrue("logged", pass.contains("Log.i(TAG, \"harvest: the world map builds beside the game (\$why)"))
        assertTrue("in mem.log too", pass.contains("MemWatch.note(\"art beside the game: \${step.id} starts (\$why)\")"))
        assertTrue(pass.contains("MemWatch.note(l)"))
        assertTrue("a failure is not tried again in this game", Regex("besideFailedFor = GameLife.startedAtMs").findAll(pass).count() == 2)
        assertTrue("its flags are reset whatever ends it", pass.contains("} finally { bgPhase = false; besideOn = false }"))
    }

    @Test fun onlyTheWorkersGateStartsThePassAndOnlyForTheWorldMap() {
        assertEquals("one caller", 2, Regex("besidePass\\(").findAll(harvest).count())          // its definition and one call
        val work = fn("private fun work(ctx: Context, onBeforePlayDone: (Boolean) -> Unit) {")
        val gate = work.indexOf("if (!PlayGate.artMayOpen(")
        val call = work.indexOf("besidePass(ctx, beside)")
        val open = work.indexOf("GameFiles.open(ctx)")
        assertTrue("inside the game-up gate, before the run's own open", gate in 0 until call && call < open)
        assertTrue(work.contains("val beside = later.firstOrNull { it.id == PlayGate.BESIDE_STEP }"))
        assertTrue("then it parks for the rest", work.contains("throw PausedForGame(if (besidePass(ctx, beside)) \"\${beside.id} (built beside the game)\" else null)"))
    }

    @Test fun everyOtherStepStillPausesAndTheReliefStays() {
        val stop = fn("private fun checkStop() {")
        assertTrue(stop.contains("PlayGate.artMustPause(bgPhase, active, onWorker = Thread.currentThread() === worker, beside = beside)"))
        assertTrue(stop.contains("if (yieldBg && bgPhase && !beside) throw YieldToRequired()"))
        // beside only inside the pass, only for the world map, only while the live rule says so
        assertTrue(harvest.contains("active && besideOn && (currentStep ?: PlayGate.BESIDE_STEP) == PlayGate.BESIDE_STEP && PlayGate.besideNow(PlayGate.BESIDE_STEP)"))
        assertTrue(harvest.contains("letGo(stopping, yieldBg, bgPhase, active, besideNow(active))"))
        // the pause handler: the relief as before, then the park goes back in only for the world map
        val pause = harvest.indexOf("} catch (e: PausedForGame) {")
        val handler = harvest.substring(pause, harvest.indexOf("} catch (e: YieldToRequired) {", pause))
        assertTrue(handler.contains("if (openedFiles) MemWatch.relieve(\"art let go: \$step, \$lag\", collectNow = true)"))
        assertTrue(handler.contains("while (PlayGate.gameActiveNow() && !stopping && !yieldBg && !besideDue(besideLeft)) Thread.sleep(3_000)"))
        assertTrue(handler.indexOf("MemWatch.relieve(") < handler.indexOf("besideDue(besideLeft)"))
        assertTrue(handler.contains("Log.i(TAG, \"harvest: back to the world map beside the game\"); continue"))
        assertTrue(harvest.contains("private fun besideLeft(ctx: Context): Boolean = DeviceTier.besideModel() &&"))
        // a failed pass in this game: the park neither goes back in nor says it will
        assertTrue(harvest.contains("DeviceTier.besideModel() && besideFailedFor != GameLife.startedAtMs &&"))
        // the yield points of the world map step are untouched (ArtMapsFirstTest checks each picture)
        assertTrue(File(src, "MapHarvestSteps.kt").readText().contains("job.yieldPoint(i, maps.size, m.name)"))
    }
}
