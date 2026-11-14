package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * MEMORY RELIEF 2 (the user's picks, 2026-11-14), off-device: (1) relief at each loading screen in the world (GameLife.loadingScreen,
 * the add-on's word: tools/test_loading_screens.py); (2) the background art lets go of the game files within one file (KitBuild.icons,
 * GameFiles' prefetch, ArtHarvest's stops); (3) the Map room's pictures' minute (MapRoomLetGo; the room itself: app MemReliefTest).
 */
class MemRelief2Test {

    // ------------------------------------------------------------------ (1) relief at zone changes

    @Test fun eachLoadingScreenInTheWorldGetsAReliefAndItsEndANote() {
        DiagLog.useDir(Files.createTempDirectory("diag").toFile())
        val t0 = 1_000_000_000_000L
        GameLife.started(t0)
        assertNull("before the world: the world relief covers the login's loading screen", GameLife.loadingScreen(true, t0 + 5_000))
        assertNull(GameLife.loadingScreen(false, t0 + 9_000))
        GameLife.inWorld(t0 + 10_000)
        assertEquals("loading screen 1", GameLife.loadingScreen(true, t0 + 20 * 60_000L))
        assertEquals("loading screen 1 over after 6 s", GameLife.loadingScreen(false, t0 + 20 * 60_000L + 6_000))
        assertNull("an end said twice: nothing", GameLife.loadingScreen(false, t0 + 20 * 60_000L + 7_000))
        assertEquals("loading screen 2", GameLife.loadingScreen(true, t0 + 95 * 60_000L))       // a long session: every one
        assertEquals("loading screen 3", GameLife.loadingScreen(true, t0 + 95 * 60_000L + 500)) // a lost end: the next start still relieves
        assertEquals(3, GameLife.loads)
        GameLife.ended(0, t0 + 100 * 60_000L)
        assertNull("the game ended: nothing", GameLife.loadingScreen(true, t0 + 101 * 60_000L))
        GameLife.started(t0 + 102 * 60_000L)
        assertEquals("a new game counts again", 0, GameLife.loads)
        GameLife.ended(0, t0 + 103 * 60_000L)
    }

    @Test fun theNotesAndTheArtsReliefWithoutTheWatchAreNothing() {
        var got = -1
        MemWatch.onTrim("relief2-test") { level -> got = level; 0L }
        MemWatch.note("loading screen 1 over after 6 s")                     // logcat only: no watch, no throw
        MemWatch.relieve("art let go: allicons, 3 s after the launch", collectNow = true)
        assertEquals(-1, got)
        MemWatch.onTrim("relief2-test") { 0L }
    }

    // ------------------------------------------------------------------ (2) the art lets go within one file

    @Test fun theRunsStopsAsOneAnswer() {
        assertFalse(ArtHarvest.letGo(stopping = false, yieldBg = false, bgPhase = true, gameActive = false))
        assertTrue("the game up or launching: the background lets go", ArtHarvest.letGo(false, false, bgPhase = true, gameActive = true))
        assertFalse("the art before Play never pauses for the game (Play waits for it)", ArtHarvest.letGo(false, false, bgPhase = false, gameActive = true))
        assertTrue("a required step due: the background gives way", ArtHarvest.letGo(false, yieldBg = true, bgPhase = true, gameActive = false))
        assertFalse(ArtHarvest.letGo(false, yieldBg = true, bgPhase = false, gameActive = false))
        assertTrue("the game update: always", ArtHarvest.letGo(stopping = true, yieldBg = false, bgPhase = false, gameActive = false))
    }

    @Test fun aPrefetchFailureIsNotTheStepsButTheRunsStopIs() {
        assertNull(ArtHarvest.tolerant<Int> { throw java.io.IOException("the CDN") })
        assertNull(ArtHarvest.tolerant<Int> { error("a broken answer") })
        assertEquals(3, ArtHarvest.tolerant { 3 })
        for (stop in listOf(ArtHarvest.PausedForGame(), ArtHarvest.StoppedForUpdate(), ArtHarvest.YieldToRequired())) {
            try { ArtHarvest.tolerant<Int> { throw stop }; fail("${stop.javaClass.simpleName} swallowed") } catch (e: InterruptedException) {
                assertTrue(e === stop)
            }
        }
    }

    private class Stop : InterruptedException("the run stops")

    private fun blp(): ByteArray {                                          // KitTolerantTest's 4 x 4 BLP2
        val w = 4; val h = 4; val b = ByteArray(148 + w * h * 4)
        "BLP2".toByteArray().copyInto(b)
        b[8] = 3
        fun put(o: Int, v: Int) { for (i in 0..3) b[o + i] = (v shr (8 * i)).toByte() }
        put(12, w); put(16, h); put(20, 148); put(84, w * h * 4)
        for (i in 148 until b.size) b[i] = 0x7F
        return b
    }

    @Test fun issue254APackStopsWithinOneIconNotAtItsEnd() {
        val reads = AtomicInteger()
        val asked = AtomicBoolean(false)
        val caller = Thread.currentThread()
        val k = KitBuild(
            read = { _ -> if (reads.incrementAndGet() >= 10) asked.set(true); Thread.sleep(2); blp() },
            write = { _, _ -> }, asset = { null },
            letGo = { asked.get() },
            checkpoint = { if (Thread.currentThread() === caller && asked.get()) throw Stop() })
        val out = File(Files.createTempDirectory("allicons").toFile(), "000-pack.pak")
        try {
            k.icons(JSONObject().put("tile", 4).put("fids", JSONArray((1..1000).toList())), out, lenient = true) { _, _ -> byteArrayOf(1, 2) }
            fail("the pack was built whole")
        } catch (e: Stop) { }
        val at = reads.get()
        // the ask came at the 10th read: only the icons already in the pack's hands (at most one a thread) were read after it
        assertTrue("$at reads of 1000", at <= 10 + 4)
        Thread.sleep(150)
        assertEquals("no read after icons() returned (its threads waited for: the game files close next)", at, reads.get())
        assertFalse(out.exists())
        assertFalse("the pack's pieces so far go", File(out.path + ".blobs").exists())
        assertFalse(File(out.path + ".part").exists())
    }

    @Test fun askedButNotStoppedThePackIsStillWhole() {
        // the ask with no stop on the calling thread (the game closed in between): every icon read there instead, none lost
        val readers = Collections.synchronizedSet(HashSet<Thread>())
        val k = KitBuild(read = { _ -> readers.add(Thread.currentThread()); blp() }, write = { _, _ -> }, asset = { null }, letGo = { true })
        val out = File(Files.createTempDirectory("allicons").toFile(), "001-pack.pak")
        assertEquals(300, k.icons(JSONObject().put("tile", 4).put("fids", JSONArray((1..300).toList())), out, lenient = true) { _, _ -> byteArrayOf(7) })
        assertTrue(out.isFile)
        assertEquals(setOf(Thread.currentThread()), readers.toSet())
    }

    @Test fun aPackWithoutAnAskIsBuiltAsBefore() {
        val k = KitBuild(read = { _ -> blp() }, write = { _, _ -> }, asset = { null })
        val out = File(Files.createTempDirectory("allicons").toFile(), "002-pack.pak")
        assertEquals(600, k.icons(JSONObject().put("tile", 4).put("fids", JSONArray((1..600).toList())), out, lenient = true) { _, _ -> byteArrayOf(7) })
        assertTrue(out.isFile && !File(out.path + ".blobs").exists())
    }

    @Test fun thePrefetchProbeStopsAtTheAsk() {
        var n = 0
        val needs = GameFiles.probeNeeds((1..1000).toList(), letGo = { n >= 7 }) { fid -> n++; if (fid % 2 == 0) "data/$fid" else null }
        assertEquals(7, n)                                                   // seven probe reads, then the ask: no more
        assertEquals(setOf("data/2", "data/4", "data/6"), needs)
        assertEquals(500, GameFiles.probeNeeds((1..1000).toList(), letGo = { false }) { fid -> if (fid % 2 == 0) "d$fid" else null }.size)
    }

    @Test fun thePrefetchDownloadsStopAtTheAskWithinOneEachThread() {
        val fetched = AtomicInteger()
        val asked = AtomicBoolean(false)
        val ok = GameFiles.fetchUntil((1..400).toList(), 4, letGo = { asked.get() }, fetch = { _ ->
            Thread.sleep(3); if (fetched.incrementAndGet() >= 5) asked.set(true); true
        }) { }
        assertTrue("${fetched.get()} of 400 downloaded", fetched.get() <= 5 + 4)
        assertEquals(fetched.get(), ok)
        // without an ask: every one, as before
        assertEquals(50, GameFiles.fetchUntil((1..50).toList(), 4, letGo = { false }, fetch = { true }) { })
        // a stop thrown by the progress report still comes back on the calling thread (#138's rule)
        try { GameFiles.fetchUntil((1..20).toList(), 4, letGo = { false }, fetch = { true }) { throw ArtHarvest.StoppedForUpdate() }; fail() }
        catch (e: ArtHarvest.StoppedForUpdate) { }
    }

    // ------------------------------------------------------------------ (3) the Map room's pictures

    @Test fun theMapPicturesGoAMinuteAfterTheRoomIsLeft() {
        assertEquals(60_000L, MapRoomLetGo.AFTER_MS)
        assertEquals("map pictures let go (43 MB): the Map room left 60 s ago", MapRoomLetGo.line(43L shl 20))
        assertEquals("map pictures let go (72 MB): the Map room left 60 s ago", MapRoomLetGo.line(72L * 1024 * 1024 - 1000))
    }

    // ------------------------------------------------------------------ review (2026-11-14): the wiring the pure tests above cannot reach

    private val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
        .map { File(it) }.first { it.isDirectory }

    @Test fun onlyALiveLoadingScreenWordReachesTheRelief() {
        // the add-on's {"type":"load"} acts only when CURRENT (LogTail replays megabytes of backlog at a start: an old run's zone changes
        // must not each run a relief), and GameLife.loadingScreen is reached from nowhere else
        val host = File(src, "CompanionHost.kt").readText()
        val at = host.indexOf("GameLife.loadingScreen(")
        assertTrue("CompanionHost no longer hands the loading-screen word on", at >= 0)
        val gate = host.lastIndexOf("if (current && msg.optString(\"type\") == \"load\") {", at)
        assertTrue("the loading-screen word is not gated on a current document", gate >= 0)
        assertFalse("the call sits outside the current-only block", host.substring(gate, at).contains("\n        }"))
        assertEquals("one call only", at, host.lastIndexOf("GameLife.loadingScreen("))
        val callers = src.listFiles()!!.filter { it.name.endsWith(".kt") && it.name != "GameLife.kt" }
            .filter { it.readText().contains("GameLife.loadingScreen(") }.map { it.name }
        assertEquals(listOf("CompanionHost.kt"), callers)
    }

    @Test fun theArtsPauseReliefIsOnlyForAPassThatOpenedTheFiles() {
        val a = File(src, "ArtHarvest.kt").readText()
        val pause = a.indexOf("} catch (e: PausedForGame) {")
        val next = a.indexOf("} catch (e: YieldToRequired) {", pause)
        assertTrue(pause in 0 until next)
        val handler = a.substring(pause, next)
        assertTrue("the pause no longer relieves", handler.contains("if (openedFiles) MemWatch.relieve(\"art let go: "))
        assertTrue("the relief must not wait for the 10 s limit (the game start's own relief follows)", handler.contains("collectNow = true)"))
        assertTrue("the flag is not cleared after the relief", handler.contains("openedFiles = false"))
        // set only once the files are open (a pass paused before the open has nothing of the game's to free)
        val work = a.substring(a.indexOf("private fun work(ctx: Context"))
        assertTrue(work.indexOf("openedFiles = true") in work.indexOf("if (game == null) {") until work.indexOf("game.use { g ->"))
    }

    @Test fun everyMapPictureStitchChecksTheStopPerTile() {
        val m = File(src, "MapHarvestSteps.kt").readLines()
        val calls = m.filter { it.contains("stitch(g, ") }
        assertTrue("no stitch calls found (the check is out of date)", calls.size >= 2)
        for (c in calls) assertTrue("a picture stitched without the per-tile stop: ${c.trim()}", c.contains("{ job.checkpoint() }"))
        // and every prefetch the art steps make asks the stop before each download
        for (f in listOf("MapHarvestSteps.kt", "AllIconsStep.kt")) {
            val t = File(src, f).readText()
            var i = t.indexOf(".prefetch(")
            var n = 0
            while (i >= 0) {
                // the call's own argument list: to its matching ')'
                var depth = 0; var e = i + ".prefetch".length
                do { when (t[e]) { '(' -> depth++; ')' -> depth-- }; e++ } while (depth > 0)
                val call = t.substring(i, e)
                assertTrue("$f: a prefetch without the art's stop: $call", call.contains("letGo = { job.letGoAsked() }"))
                n++; i = t.indexOf(".prefetch(", e)
            }
            assertTrue("$f: no prefetch found", n > 0)
        }
    }

    @Test fun aReadAfterTheFilesClosedNeverReachesCascLib() {
        // KitBuild.icons waits [POOL_WAIT_S] for its threads in hand; one that outlives the wait (a stalled download) must find the handle
        // closed, not freed: every native call on it checks [closed] under the same lock close() sets it in
        val g = File(src, "GameFiles.kt").readText()
        assertTrue(g.contains("override fun close() { synchronized(accessLock) { if (!closed) { closed = true; CascNative.close(handle) } } }"))
        val read = g.substring(g.indexOf("private fun readAsset("), g.indexOf("for (attempt in 0..4)"))
        assertTrue("readAsset does not check the close first", read.contains("if (closed)"))
        assertTrue(g.contains("synchronized(accessLock) { if (closed) null else IndoorGeneratorCasc.contentKey(handle, fid) }"))
        val probe = g.substring(g.indexOf("val needs = synchronized(accessLock) {"), g.indexOf("probeNeeds(fids, letGo)"))
        assertTrue("the prefetch's probe reads do not check the close", probe.contains("if (closed)"))
        // no other native call on the handle outside the guarded paths (loadClientKeys runs at the open, before any step)
        val guarded = listOf("readAsset(", "IndoorGeneratorCasc.contentKey", "CascNative.close(handle)", "probeNeeds(", "addEncryptionKey")
        val unguarded = g.lines().filter { l -> l.contains("(handle") && guarded.none { l.contains(it) } }
        assertTrue("a native call on the handle without the close check: $unguarded", unguarded.isEmpty())
    }
}
