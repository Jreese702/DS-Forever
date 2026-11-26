package com.jrees.wowcompanion

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A HOST THAT WENT IS LET GO (HostEnds; the Robolectric proof is HostsReleasedTest): what a host registered process-wide goes at its
 * end, newest first; a hook only while it is still this host's (a newer host's own is never touched, so a live host never misses a
 * notification); a removal added after the end runs at once; the registries hand back their removal.
 */
class HostEndsTest {
    object Hooks { @Volatile var h: (() -> Unit)? = null }

    @After fun clean() { Hooks.h = null; SingleScreen.reset() }

    @Test fun aHookGoesOnlyWhileItIsStillThisHostsOwn() {
        val old = HostEnds(); val new = HostEnds()
        val a: () -> Unit = {}; val b: () -> Unit = {}
        Hooks.h = a; old.own(Hooks::h, null)
        Hooks.h = b; new.own(Hooks::h, null)                 // the host made again sets its own before the old one's end
        old.end()
        assertSame("the newer host's hook stays", b, Hooks.h)
        new.end()
        assertNull("its own end takes it off", Hooks.h)
    }

    @Test fun everythingGoesAtTheEndNewestFirstAndOnce() {
        val e = HostEnds()
        val order = ArrayList<Int>()
        e.add { order += 1 }; e.add { order += 2 }; e.add { order += 3 }
        assertEquals(3, e.size)
        e.end()
        assertEquals(listOf(3, 2, 1), order)
        e.end()
        assertEquals("once", listOf(3, 2, 1), order)
        e.add { order += 4 }                                  // a view made after its host went: at once
        assertEquals(listOf(3, 2, 1, 4), order)
        assertTrue(e.ended)
    }

    @Test fun aRegistryHandsBackItsRemoval() {
        var heard = 0
        val e = HostEnds()
        e.add(SingleScreen.onChange { heard++ })
        SingleScreen.set(true, SingleScreen.Why.USER, at = 100L)
        assertEquals("a live host hears every change", 1, heard)
        e.end()
        SingleScreen.set(false, null, at = 200L)
        assertEquals("a host that went hears nothing more", 1, heard)
        SingleScreen.reset()
    }

    /**
     * the leak fix's review (2026-11-26; HostsReleasedTest drives each one): the Settings stage goes with the host that made it; its DS
     * toolbar hook is set once and owned; the report's auto-save hook is host-free (it stays when the host goes); a host whose start
     * throws is taken down by the one way every window makes one; a teardown that throws never skips the ends; the launcher's two
     * hooks are taken off at its stop
     */
    @Test fun theLeakReviewsHoldersAreTiedToTheirHost() {
        val stage = File(srcDir(), "PreviewStage.kt").readText()
        val build = stage.substringAfter("private fun build(ctx: Context): Tree {").substringBefore("\n    }\n")
        assertTrue(build.contains("ctx.atHostEnd { if (tree === t) { tree = null; askDirty = true } }"))
        assertTrue("kept before its end is registered", build.indexOf("tree = t") in 0 until build.indexOf("ctx.atHostEnd"))
        assertTrue(stage.contains("kb = it; ctx.atHostEnd { if (kb === it) kb = null }"))
        assertTrue(stage.contains("stripView = it; a.ctx.atHostEnd { if (stripView === it) stripView = null }"))
        assertFalse("never set outside build()", stage.contains("build(ctx).also { tree = it }") || stage.contains("build(a.ctx).also { tree = it }"))
        val host = File(srcDir(), "CompanionHost.kt").readText()
        assertTrue(host.contains("PreviewStage.roomBar = { castView.roomBar }; ends.own(PreviewStage::roomBar, { null })"))
        assertTrue(host.contains("PreviewStage.roomBarOn = { castView.roomBarOn }; ends.own(PreviewStage::roomBarOn, { false })"))
        assertEquals("set once", 1, Regex("PreviewStage\\.roomBar = ").findAll(host).count())
        assertFalse(host.contains("ends.own(DiagLog::onEndedByItself"))
        assertTrue(host.contains("DiagLog.onEndedByItself = reportAutoSave(applicationContext)"))
        assertTrue("made with no host in scope", host.substringAfter("    companion object {").contains("private fun reportAutoSave(app: Context): () -> Unit = {"))
        val made = host.substringAfter("fun made(base: Context, shell: HostShell, saved: Bundle?, start: Boolean = true): CompanionHost {")
            .substringBefore("\n        }\n")
        assertTrue(made.contains("catch (t: Throwable) { h.failedStart(t); throw t }"))
        assertTrue(host.substringAfter("private fun failedStart(t: Throwable) {").substringBefore("\n    }\n").contains("ends.end()"))
        assertTrue(host.substringAfter("    fun abandon() {").substringBefore("\n    }\n").contains("try { onStop() } finally { onDestroy() }"))
        assertTrue(host.substringAfter("    fun onDestroy() {").substringBefore("\n    }\n").trimEnd()
            .endsWith("} finally {\n            ends.end()\n        }"))
        for (f in listOf("DsForeverScreen.kt", "TopWindow.kt", "CompanionScreenActivity.kt")) {
            val code = fork(f)
            assertTrue(f, code.contains("CompanionHost.made("))
            assertFalse(f, Regex("CompanionHost\\((context|this), this\\)").containsMatchIn(code))
        }
        val stop = fork("DsForeverScreen.kt").substringAfter("    fun stop() {").substringBefore("\n    }\n")
        assertTrue(stop.contains("if (LauncherHooks.gameUp === gameUpHook) LauncherHooks.gameUp = { false }"))
        assertTrue(stop.contains("if (LauncherHooks.inFront === inFrontHook) LauncherHooks.inFront = { false }"))
        assertTrue("after the game detached", stop.indexOf("detachGame()") in 0 until stop.indexOf("LauncherHooks.gameUp === gameUpHook"))
    }

    private fun srcDir(): File = listOf(File("../companion/app/src/main/java/com/jrees/wowcompanion"), File("companion/app/src/main/java/com/jrees/wowcompanion"))
        .first { it.isDirectory }
    private fun fork(name: String): String =
        listOf("src/main/java/com/jrees/wowcompanion/fork/$name", "dsforever/src/main/java/com/jrees/wowcompanion/fork/$name")
            .map { File(it) }.first { it.canRead() }.readText()
}
