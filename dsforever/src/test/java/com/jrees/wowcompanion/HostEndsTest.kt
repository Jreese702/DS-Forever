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

    /**
     * int/1534 (HostEnds over test 1533's code; HostsReleasedTest's fight and waiting-row tests drive each one): hold to cast's two hooks
     * owned; every sender's retry off the main looper at the host's end, hold to cast's two at a park as well; the waiting row's height
     * hook let go at the end and never run for a host that went; a top window that went makes no window again, one made while parked
     * starts parked
     */
    @Test fun test1533sRegistrationsEndWithTheirHost() {
        val host = File(srcDir(), "CompanionHost.kt").readText()
        assertTrue(host.contains("ends.own(HoldCast::onTicksWanted, null)"))
        assertTrue(host.contains("ends.own(HoldCast::onBumpWanted, null)"))
        assertTrue(host.contains("ends.add { for (r in sendRetries()) ui.removeCallbacks(r) }"))
        val retries = host.substringAfter("private fun sendRetries(): List<Runnable> =").substringBefore("\n\n")
        // every self-reposting sender of the host: "private val <name>Again = Runnable"
        for (m in Regex("private val (\\w+Again) = Runnable").findAll(host)) {
            val n = m.groupValues[1]
            assertTrue("$n off at the host's end", retries.contains(n) || n in listOf("holdAgain", "bumpAgain"))
        }
        assertTrue(host.substringAfter("private fun holdSends(): List<Runnable> =").substringBefore("\n").contains("listOf(holdAgain, bumpAgain)"))
        assertTrue(retries.contains("+ holdSends()"))
        assertTrue("a park stops them", host.substringAfter("    fun park(why: String) {").substringBefore("\n    }\n")
            .contains("for (r in holdSends()) ui.removeCallbacks(r)"))
        assertTrue(host.contains("startView.onBareNeed = { if (!ends.ended && !shell.hostGone() && stripKind == SingleNpc.START) stripPlace() }"))
        assertTrue(host.contains("ends.add { startView.onBareNeed = null }"))
        assertTrue("the prompts' hold ask stops with its host", host.substringAfter("    private fun setupAskHold() {").substringBefore("\n    }\n")
            .contains("if (ends.ended) return"))
        val home = File(srcDir(), "HomeView.kt").readText()
        assertTrue(home.contains("context.hostOwn(HoldCast::onChange, null)"))
        val top = fork("TopWindow.kt")
        val sub = top.substringAfter("override fun sub(id: String): SingleSub {").substringBefore("\n    }\n")
        assertTrue("a window that went", sub.contains("if (!up) return NoSub"))
        assertTrue("made while parked: parked", sub.contains(".also { if (parkedNow) it.park(true) }"))
    }

    /**
     * THE INT/1534 REVIEW: a document the reader had posted before the host's end runs on it after the end (LogTail.stop never waits for
     * a poll under way) - every sender's check, and the key question's 3 s look, must stop first for a host that went (else a retry is
     * posted for good: the dead host kept whole, its taps beside the next host's). HostsReleasedTest's queued-document test runs it
     */
    @Test fun aHostThatWentNeverSendsFromALateDocument() {
        val host = File(srcDir(), "CompanionHost.kt").readText()
        assertTrue(host.contains("private fun sendsGone(): Boolean = ends.ended"))
        // every self-reposting sender's check (the function its "<name>Again" runnable runs), and the key question
        val checks = Regex("private val \\w+Again = Runnable \\{ last(?:Dpad|PadMap)\\?\\.let \\{ (\\w+)\\(it").findAll(host).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("singleTopCheck", "mirrorCheck", "topCheck", "castCheck", "swingCheck", "previewCheck", "holdTicksCheck", "holdBumpCheck"), checks)
        for (f in checks + "keyAsk") {
            val body = host.substringAfter("    private fun $f(").substringAfter("{\n").substringBefore("\n    }\n")
            val first = body.lines().map { it.trim() }.first { it.isNotEmpty() && !it.startsWith("//") }
            val guard = if (f == "singleTopCheck") body.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") }[1] else first
            assertTrue("$f stops first for a host that went: $guard", guard.startsWith("if (sendsGone()"))
            // hold to cast's two: parked too (the park stopped them; a document posted before it would start them again)
            if (f == "holdTicksCheck" || f == "holdBumpCheck") assertTrue("$f: not while parked", guard.startsWith("if (sendsGone() || parked) return"))
        }
        // (singleTopCheck: the add-on's mode is process-wide - the game's cast bar - so a late document still sets it first)
        assertTrue(host.substringAfter("    private fun singleTopCheck(").substringAfter("{\n").trim().startsWith("SingleUi.addonSingle = d.ss == 1"))
    }

    private fun srcDir(): File = listOf(File("../companion/app/src/main/java/com/jrees/wowcompanion"), File("companion/app/src/main/java/com/jrees/wowcompanion"))
        .first { it.isDirectory }
    private fun fork(name: String): String =
        listOf("src/main/java/com/jrees/wowcompanion/fork/$name", "dsforever/src/main/java/com/jrees/wowcompanion/fork/$name")
            .map { File(it) }.first { it.canRead() }.readText()
}
