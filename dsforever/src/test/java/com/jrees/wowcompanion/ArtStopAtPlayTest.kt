package com.jrees.wowcompanion

import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * TEST BUILD 1489, THE #1 PLAYER PROBLEM (tracker #56, 189 reports; #361 beside it): Android's low-memory killer closes the game. The
 * user's approval, 2026-11-18: (1) every art step stops hard at Play - nothing builds while the game runs (the art before Play too, the
 * world map beside the game stays the 16 GB Thor Max's one exception: the user's pick 2026-11-18), it goes on once the game has closed; (2) Low-memory graphics on by default on the
 * 8 GB Thors, the 16 GB Thor Max off, a player's own saved choice kept. #361's WCMEM lines: "art icons" with "game up", native heap
 * 582 MB, for 17 s until the kill.
 */
class ArtStopAtPlayTest {
    private val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
        .map { File(it) }.first { it.isDirectory }
    private val launcher = listOf("../app/src/main/java/app/gamenative/ui/screen/wow", "app/src/main/java/app/gamenative/ui/screen/wow")
        .map { File(it) }.first { it.isDirectory }
    private val harvest by lazy { File(src, "ArtHarvest.kt").readText() }

    private fun setTotalMb(mb: Long) =
        DeviceTier::class.java.getDeclaredField("totalMb").apply { isAccessible = true }.setLong(null, mb)

    /** LauncherHooks.launchEvent without its StartupTrace mark (android.os.SystemClock is not on the JVM) */
    private fun step(what: String) { LauncherHooks.launchState = what; LauncherHooks.launchAtMs = System.currentTimeMillis() }

    @After fun reset() {
        setTotalMb(0L)
        Settings.startForTest(null)
        InputSink.sink = null
        LauncherHooks.gameUp = { false }; LauncherHooks.launchState = ""; LauncherHooks.launchAtMs = 0L; LauncherHooks.gameStartSec = 0L
        LauncherHooks.endLaunchWindow()
    }

    // ------------------------------------------------------------------ (1) every art step stops while the game runs

    @Test fun theArtBeforePlayPausesForTheGameToo() {
        // #361: the icons step (a before-Play step) built beside a game that was up
        assertTrue(PlayGate.artMustPause(gameActive = true))
        assertFalse(PlayGate.artMustPause(gameActive = false))
        assertFalse("never thrown on a pool thread (#138)", PlayGate.artMustPause(gameActive = true, onWorker = false))
        assertTrue("before Play or background: one answer", ArtHarvest.letGo(stopping = false, yieldBg = false, bgPhase = false, gameActive = true))
        assertTrue(ArtHarvest.letGo(stopping = false, yieldBg = false, bgPhase = true, gameActive = true))
        assertFalse(ArtHarvest.letGo(stopping = false, yieldBg = false, bgPhase = false, gameActive = false))
    }

    @Test fun noRunOpensTheGameFilesWhileTheGameIsUpOrLaunchingEvenWithRequiredStepsDue() {
        assertFalse(PlayGate.artMayOpen(gameActive = true))
        assertTrue(PlayGate.artMayOpen(gameActive = false))
        // the launch window alone holds the art back now (Play itself ends any old window before its own wait: endLaunchWindow)
        step("booting the game (container 3)")
        assertFalse(PlayGate.artMayOpen(gameActive = PlayGate.gameActiveNow()))
        LauncherHooks.endLaunchWindow()
        assertTrue(PlayGate.artMayOpen(gameActive = PlayGate.gameActiveNow()))
    }

    @Test fun playHoldsTheArtFromThePressUntilTheLaunchTakesOver() {
        val at = 100_000_000L
        assertTrue("Play pressed, no launch step yet", PlayGate.held(holdAtMs = at, launchAtMs = 0L, gameStartSec = 0L, nowMs = at + 2_000))
        assertFalse("no hold", PlayGate.held(0L, 0L, 0L, at))
        assertFalse("a launch step after the press: the launch window (or its failure) decides",
            PlayGate.held(at, launchAtMs = at + 3_000, gameStartSec = 0L, nowMs = at + 4_000))
        assertFalse("the game screen came up after the press: gameUp decides",
            PlayGate.held(at, launchAtMs = 0L, gameStartSec = at / 1000, nowMs = at + 4_000))
        assertFalse("a hold never outlives the launch window", PlayGate.held(at, 0L, 0L, at + PlayGate.LAUNCH_WINDOW_MS))
        assertTrue(PlayGate.gameActive(gameUp = false, launchState = "", launchAtMs = 0L, gameStartSec = 0L, nowMs = at + 1, holdAtMs = at))
    }

    @Test fun theLiveHoldFromPlayToALaunchFailure() {
        assertFalse(PlayGate.gameActiveNow())
        LauncherHooks.holdForPlay()
        assertTrue("held from the press", PlayGate.gameActiveNow())
        Thread.sleep(5)
        step("preparing the container (Turnip, by play)")
        assertTrue("the launch window takes over", PlayGate.gameActiveNow())
        Thread.sleep(5)
        step("launch failed: IOException: x")
        assertFalse("a failed launch frees the art at once", PlayGate.gameActiveNow())
        Thread.sleep(5)
        LauncherHooks.holdForPlay()
        assertTrue(PlayGate.gameActiveNow())
        LauncherHooks.endLaunchWindow()
        assertFalse("the launcher back in front ends the hold", PlayGate.gameActiveNow())
    }

    /** the user's pick 2026-11-18: the Thor Max keeps its one exception (the world map beside the game); 8 / 12 GB never */
    @Test fun onlyTheThorMaxStillBuildsTheWorldMapBesideTheGame() {
        setTotalMb(15_255L); assertTrue(DeviceTier.besideModel())
        setTotalMb(11_265L); assertFalse(DeviceTier.besideModel())
        setTotalMb(7_191L); assertFalse(DeviceTier.besideModel())
        // and with the game up only the beside pass bends the pause
        assertTrue(PlayGate.artMustPause(gameActive = true, beside = false))
        assertFalse(PlayGate.artMustPause(gameActive = true, beside = true))
    }

    @Test fun theWorkerHoldsTheFilesUntilItParks() {
        assertTrue(ArtHarvest.holdsGameFiles(alive = true, parked = false))
        assertFalse(ArtHarvest.holdsGameFiles(alive = true, parked = true))
        assertFalse(ArtHarvest.holdsGameFiles(alive = false, parked = false))
    }

    @Test fun theWorkerIsWiredToStopEverythingAndWaitForTheClose() {
        val stop = harvest.substring(harvest.indexOf("private fun checkStop() {"), harvest.indexOf("/** memory relief 2: [checkStop] on the run's own thread"))
        assertTrue("the pause comes before the required step's yield (the relief runs)",
            stop.indexOf("PlayGate.artMustPause(") in 0 until stop.indexOf("throw YieldToRequired()"))
        assertFalse("no background-only pause any more", stop.contains("artMustPause(bgPhase"))
        // the park: a required step due no longer pulls the worker back in while the game runs
        assertTrue(harvest.contains("while (PlayGate.gameActiveNow() && !stopping && !besideDue(besideLeft)) Thread.sleep(3_000)"))
        assertTrue(harvest.contains("if (!PlayGate.artMayOpen(gameActive = PlayGate.gameActiveNow()))"))
    }

    @Test fun playHoldsTheArtAndWaitsForItToLetGoBeforeTheContainer() {
        val screen = File(launcher, "WoWForeverScreen.kt").readText()
        val play = screen.substring(screen.indexOf("fun launchGame(by: String = \"play\") {"), screen.indexOf("LifecycleResumeEffect(gamePath) {"))
        val await = play.indexOf("awaitArtHarvest(context)")
        val hold = play.indexOf("com.jrees.wowcompanion.LauncherHooks.holdForPlay()")
        val wait = play.indexOf("com.jrees.wowcompanion.ArtHarvest.letGoForPlay(")
        val container = play.indexOf("launchEvent(\"preparing the container")
        assertTrue("held after the art before Play", await in 0 until hold)
        assertTrue("waited for before the container", hold < wait && wait < container)
    }

    // ------------------------------------------------------------------ (2) Low-memory graphics by Thor model

    @Test fun lowMemoryGraphicsDefaultsOnTheEightGigabyteThorsAndOffOnTheThorMax() {
        Settings.startForTest(Prefs())
        setTotalMb(7_191L); assertTrue("the Thor Base / Lite (8 GB)", Settings.lowMemGraphics)
        setTotalMb(7_700L); assertTrue(Settings.lowMemGraphics)
        setTotalMb(15_255L); assertFalse("the Thor Max (16 GB): the earlier ruling", Settings.lowMemGraphics)
        setTotalMb(15_183L); assertFalse(Settings.lowMemGraphics)
        assertTrue(DeviceTier.lowMemGraphicsDefault(7_191L))
        assertFalse(DeviceTier.lowMemGraphicsDefault(15_255L))
        assertFalse("the RAM unknown: never lighter by guess", DeviceTier.lowMemGraphicsDefault(0L))
    }

    @Test fun aPlayersOwnSavedChoiceIsKept() {
        val p = Prefs(); Settings.startForTest(p)
        setTotalMb(7_191L)
        Settings.lowMemGraphics = false
        assertFalse("off on an 8 GB Thor by the player's own pick", Settings.lowMemGraphics)
        setTotalMb(15_255L)
        Settings.lowMemGraphics = true
        assertTrue("on on the Thor Max by the player's own pick", Settings.lowMemGraphics)
    }

    private class Prefs(val m: HashMap<String, Any?> = HashMap()) : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = m
        override fun getString(k: String?, d: String?) = m[k] as? String ?: d
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(k: String?, d: MutableSet<String>?) = m[k] as? MutableSet<String> ?: d
        override fun getInt(k: String?, d: Int) = m[k] as? Int ?: d
        override fun getLong(k: String?, d: Long) = m[k] as? Long ?: d
        override fun getFloat(k: String?, d: Float) = m[k] as? Float ?: d
        override fun getBoolean(k: String?, d: Boolean) = m[k] as? Boolean ?: d
        override fun contains(k: String?) = m.containsKey(k)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            val put = HashMap<String, Any?>(); val gone = HashSet<String>()
            override fun putString(k: String, v: String?) = apply { put[k] = v }
            override fun putStringSet(k: String, v: MutableSet<String>?) = apply { put[k] = v }
            override fun putInt(k: String, v: Int) = apply { put[k] = v }
            override fun putLong(k: String, v: Long) = apply { put[k] = v }
            override fun putFloat(k: String, v: Float) = apply { put[k] = v }
            override fun putBoolean(k: String, v: Boolean) = apply { put[k] = v }
            override fun remove(k: String) = apply { gone.add(k) }
            override fun clear() = apply { gone.addAll(m.keys) }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() { for (k in gone) m.remove(k); m.putAll(put) }
        }
    }
}
