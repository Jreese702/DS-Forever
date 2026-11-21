package com.jrees.wowcompanion

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * TEST BUILD 1489'S FOUR EXTRA MEMORY CUTS (the user's picks 2026-11-18, #56): (a) the native heap's free pages handed back at each
 * relief, (b) the loading pictures capped, downsampled, RGB_565 and let go as the loading ends, (c) the kit capped while the game runs
 * and the Map room's pictures let go right after it closes, (d) Low-memory graphics on the 8 GB Thors also lowers view distance,
 * particles, spells and shadows, a player's own values given back exactly when it is off.
 */
class LowMemCutsTest {
    private val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
        .map { File(it) }.first { it.isDirectory }
    private val repo = src.parentFile.parentFile.parentFile.parentFile.parentFile.parentFile.parentFile.parentFile
    private fun text(name: String) = File(src, name).readText()

    @After fun reset() {
        LauncherHooks.gameUp = { false }; LauncherHooks.launchState = ""; LauncherHooks.launchAtMs = 0L; LauncherHooks.endLaunchWindow()
    }

    // ------------------------------------------------------------------ (a) the native heap

    @Test fun theNativePurgeLineAndItsResidentSize() {
        assertEquals(3L * 4096L, NativeHeap.rssBytes("100 3 2 1 0 5 0"))
        assertEquals(-1L, NativeHeap.rssBytes(null))
        assertEquals(-1L, NativeHeap.rssBytes("garbage"))
        assertEquals("native purge: rss 812 -> 761 MB", NativeHeap.line(1, 812L shl 20, 761L shl 20))
        assertEquals("native purge: not supported", NativeHeap.line(0, 812L shl 20, 812L shl 20))
        assertEquals("native purge: done", NativeHeap.line(2, -1L, 5L))
        // off the device there is no library: nothing happens and the relief's line has no part for it
        assertFalse(NativeHeap.available)
        assertEquals("", NativeHeap.purgeLine())
    }

    @Test fun everyReliefPurgesAndTheLibraryIsBuiltAndKept() {
        val relieve = text("MemWatch.kt").let { it.substring(it.indexOf("fun relieve("), it.indexOf("fun note(l: String)")) }
        assertTrue(relieve.contains("NativeHeap.purgeLine()"))
        assertTrue("after the let-go and the collection", relieve.indexOf("collect(collectNow)") < relieve.indexOf("NativeHeap.purgeLine()"))
        val mk = File(repo, "dsforever/src/main/jni/Android.mk").readText()
        assertTrue(mk.contains("LOCAL_MODULE := dsf-heap") && mk.contains("LOCAL_SRC_FILES := dsf/dsf_heap.c"))
        val c = File(repo, "dsforever/src/main/jni/dsf/dsf_heap.c").readText()
        assertTrue(c.contains("Java_com_jrees_wowcompanion_NativeHeap_purge") && c.contains("M_PURGE_ALL") && c.contains("M_PURGE"))
        assertTrue("R8 keeps its JNI name", File(repo, "app/proguard-rules.pro").readText().contains("class com.jrees.wowcompanion.NativeHeap { native <methods>; }"))
        assertTrue(File(repo, "companion/tools/interiors_device/apk_jni_check.py").readText().contains("Java_com_jrees_wowcompanion_NativeHeap_purge"))
    }

    // ------------------------------------------------------------------ (b) the loading pictures

    @Test fun theLoadingPicturesAreNoLargerThanTheBottomScreenAndAtMostFourHeld() {
        assertEquals("already the bottom screen's size", 1, LowMemCuts.sampleFor(1240, 1080))
        assertEquals(1, LowMemCuts.sampleFor(2000, 2000))
        assertEquals(2, LowMemCuts.sampleFor(2480, 2160))
        assertEquals(4, LowMemCuts.sampleFor(5000, 4400))
        assertEquals(1, LowMemCuts.sampleFor(800, 600))
        assertEquals(4, LowMemCuts.SCENE_MAX_HELD)
        assertEquals(listOf(1, 2), LowMemCuts.overCap(listOf(1, 2, 3, 4, 5, 6)))
        assertEquals(emptyList<Int>(), LowMemCuts.overCap(listOf(1, 2, 3)))
        assertTrue(text("Scene.kt").contains("LowMemCuts.overCap(pics.keys.toList(), LowMemCuts.sceneMaxHeld(DeviceTier.light8()))"))
        assertTrue(text("Scene.kt").contains("inSampleSize = LowMemCuts.sampleFor(bounds.outWidth, bounds.outHeight)"))
        val scene = text("Scene.kt")
        assertTrue("RGB_565 asked (Android keeps ARGB_8888 for a picture with alpha)", scene.contains("inPreferredConfig = Bitmap.Config.RGB_565"))
        assertTrue("least recently asked first", scene.contains("LinkedHashMap<Int, Bitmap>(16, 0.75f, true)"))
        // the world's entry and each loading screen's end let them go
        val life = text("GameLife.kt")
        assertTrue(life.contains("runCatching { Scene.loadingOver(\"the world is up\") }"))
        assertTrue(life.contains("runCatching { Scene.loadingOver(\"loading screen \$loads over\") }"))
    }

    // ------------------------------------------------------------------ (c) the kit and the Map room

    @Test fun theKitCapLetsOnlyIdlePicturesGoOldestFirst() {
        val mb = 1L shl 20
        val now = 1_000_000L
        val sizes = linkedMapOf("a" to 10 * mb, "b" to 10 * mb, "c" to 10 * mb, "d" to 10 * mb)
        val used = mapOf("a" to now - 300_000L, "b" to now - 100_000L, "c" to now - 1_000L, "d" to now - 200_000L)
        // 40 MB over a 24 MB cap: the oldest idle ones go until under it; "c" was drawn a second ago and stays
        assertEquals(listOf("a", "d"), LowMemCuts.kitOverCap(sizes, used, now))
        assertEquals(emptyList<String>(), LowMemCuts.kitOverCap(mapOf("a" to 20 * mb), used, now))
        // every picture drawn within the idle time: nothing goes, whatever the size (no re-reading per frame)
        assertEquals(emptyList<String>(), LowMemCuts.kitOverCap(sizes, sizes.keys.associateWith { now - 10L }, now))
        assertEquals(24L * mb, LowMemCuts.KIT_GAME_CAP)
        assertTrue(text("UiKit.kt").contains("LowMemCuts.kitOverCap(texCache.mapValues"))
        val kit = text("UiKit.kt")
        assertTrue(kit.contains("if (runCatching { PlayGate.gameActiveNow() }.getOrDefault(false)) capKit()"))
        assertTrue(kit.contains("MemWatch.onTrim(\"kit\")"))
    }

    @Test fun theMapRoomsPicturesGoRightAfterItClosesWhileTheGameRuns() {
        assertEquals(2_000L, MapRoomLetGo.afterMs(gameActive = true))
        assertEquals(60_000L, MapRoomLetGo.afterMs(gameActive = false))
        assertEquals("map pictures let go (43 MB): the Map room left 2 s ago", MapRoomLetGo.line(43L shl 20, MapRoomLetGo.GAME_AFTER_MS))
        assertEquals("map pictures let go (43 MB): the Map room left 60 s ago", MapRoomLetGo.line(43L shl 20))
        assertTrue(text("WorldMapRoomView.kt").contains("letGoAfter = MapRoomLetGo.afterMs(runCatching { PlayGate.gameActiveNow() }.getOrDefault(false))"))
    }

    // ------------------------------------------------------------------ (d) the 8 GB Thors' extra settings

    private val us = mapOf("Branch" to "us")
    private fun wtf(root: File) = File(root, "${WowProduct.LAUNCH.flavorDir}/WTF/Config.wtf")
    private fun conf(root: File) = wtf(root).readLines()
        .filter { it.startsWith("SET ") }.associate { l -> l.removePrefix("SET ").split(" ", limit = 2).let { it[0] to it[1] } }
    private fun saved(root: File) = File(root, "${WowProduct.LAUNCH.flavorDir}/WTF/${GameConfigWtf.LOW_MEMORY_8GB_SAVED}")

    /** a folder whose Config.wtf holds the player's own picks (shadows high, a long view) and no particle line */
    private fun playersFolder(): File {
        val root = Files.createTempDirectory("lowmem8").toFile()
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, us)
        val f = wtf(root)
        f.writeText(f.readLines().filterNot { it.startsWith("SET particleDensity") }.joinToString("\n") { l ->
            when {
                l.startsWith("SET graphicsShadowQuality ") -> "SET graphicsShadowQuality \"3\""
                l.startsWith("SET farclip ") -> "SET farclip \"2000\""
                else -> l
            }
        } + "\nSET graphicsSpellDensity \"4\"\n")
        return root
    }

    @Test fun onTheEightGigabyteThorsTheExtrasAreWrittenAndTheOldValuesRecorded() {
        val root = playersFolder()
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, us, lowMemory = true, lowMemory8 = true)
        val c = conf(root)
        for ((k, v) in GameConfigWtf.LOW_MEMORY_8GB) assertEquals(k, v, c[k])
        for ((k, v) in GameConfigWtf.LOW_MEMORY_GRAPHICS) assertEquals("today's textures too: $k", v, c[k])
        val rec = GameConfigWtf.savedOf(saved(root).readText())
        assertEquals("\"3\"", rec["graphicsShadowQuality"])
        assertEquals("\"2000\"", rec["farclip"])
        assertEquals("\"4\"", rec["graphicsSpellDensity"])
        assertTrue(rec.containsKey("particleDensity")); assertNull("no line before", rec["particleDensity"])
        // a second start while on never records our own values over the player's
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, us, lowMemory = true, lowMemory8 = true)
        assertEquals(rec, GameConfigWtf.savedOf(saved(root).readText()))
    }

    @Test fun offAgainGivesThePlayersOwnValuesBackExactly() {
        val root = playersFolder()
        val before = conf(root)
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, us, lowMemory = true, lowMemory8 = true)
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, us, lowMemory = false, lowMemory8 = true)
        val after = conf(root)
        for (k in GameConfigWtf.LOW_MEMORY_8GB.keys) assertEquals(k, before[k], after[k])
        assertFalse("a key with no line before has none now", after.containsKey("particleDensity"))
        assertFalse("the record goes", saved(root).exists())
        // and a later start leaves them alone
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, us, lowMemory = false, lowMemory8 = true)
        assertEquals(after, conf(root))
    }

    @Test fun aValueThePlayerChangedInGameWhileItWasOnStaysTheirs() {
        val root = playersFolder()
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, us, lowMemory = true, lowMemory8 = true)
        val f = wtf(root)
        f.writeText(f.readText().replace("SET graphicsShadowQuality \"0\"", "SET graphicsShadowQuality \"2\""))
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, us, lowMemory = false, lowMemory8 = true)
        assertEquals("\"2\"", conf(root)["graphicsShadowQuality"])
        assertEquals("\"2000\"", conf(root)["farclip"])
    }

    @Test fun theSixteenGigabyteThorAndLowMemoryOffNeverGetTheExtras() {
        val root = playersFolder()
        val before = conf(root)
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, us, lowMemory = true, lowMemory8 = false)    // the 16 GB Max (from #56 G2 the 12 GB Pro gets the cuts)
        for (k in GameConfigWtf.LOW_MEMORY_8GB.keys) assertEquals(k, before[k], conf(root)[k])
        assertFalse(saved(root).exists())
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, us, lowMemory = false, lowMemory8 = true)    // an 8 GB Thor, the setting off
        for (k in GameConfigWtf.LOW_MEMORY_8GB.keys) assertEquals(k, before[k], conf(root)[k])
        assertFalse(saved(root).exists())
    }

    @Test fun aNewFileOnAnEightGigabyteThorStartsLowAndGoesBackToTheDefaults() {
        val root = Files.createTempDirectory("lowmem8new").toFile()
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, us, lowMemory = true, lowMemory8 = true)
        assertEquals("\"800\"", conf(root)["farclip"])
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, us, lowMemory = false, lowMemory8 = true)
        val c = conf(root)
        assertEquals("the default", "\"1200\"", c["farclip"])
        assertEquals("\"0\"", c["graphicsShadowQuality"])
        assertFalse(c.containsKey("graphicsViewDistance"))
        assertFalse(saved(root).exists())
    }

    @Test fun theRecordReadsBackAndTheEightGigabyteModels() {
        val m = linkedMapOf<String, String?>("farclip" to "\"2000\"", "particleDensity" to null)
        assertEquals(m, GameConfigWtf.savedOf(GameConfigWtf.savedText(m)))
        assertTrue(DeviceTier.eightGb(7_191L)); assertTrue(DeviceTier.eightGb(7_700L))
        assertFalse("the 12 GB Pro", DeviceTier.eightGb(11_265L)); assertFalse(DeviceTier.eightGb(15_255L))
        assertFalse("unknown", DeviceTier.eightGb(0L)); assertTrue("the Lite", DeviceTier.eightGb(7_100L, lite = true))
    }

    // ------------------------------------------------------------------ the start screen's indoor-maps line

    @Test fun theIndoorMapsLineSaysWhenTheyReallyBuild() {
        val flow = text("SetupFlow.kt")
        assertFalse(flow.contains("builds while playing"))
        assertTrue(flow.contains("\"Indoor maps (builds while the game is closed): \$it\""))
    }
}
