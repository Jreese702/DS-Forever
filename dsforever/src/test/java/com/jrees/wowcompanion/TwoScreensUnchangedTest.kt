package com.jrees.wowcompanion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * TWO SCREENS EXACTLY AS BEFORE (spec 11: every single screen change sits behind `single != null` / SingleScreen.inUse): the box is the
 * identity without the top window, HostShell's new call answers null for every existing host, Home's restElsewhere starts false and
 * only widens Home's own `isShown` reads, and every host-side single-mode branch is guarded.
 */
class TwoScreensUnchangedTest {
    private val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
        .map { File(it) }.first { it.isDirectory }
    private val host = File(src, "CompanionHost.kt").readText()

    @Test fun theBoxIsTheIdentityWithTwoScreens() {
        val m = intArrayOf(0, 8, 8, 0)
        assertSame(m, SingleScreen.boxMargins(m, null, 1240, 1080))
        // one screen: the 340 px sides become margins, the child keeps its own inside the box
        assertArrayEquals(intArrayOf(340, 8, 348, 0), SingleScreen.boxMargins(m, SingleScreen.panelBox(1920, 1080), 1920, 1080))
        val boxed = host.substringAfter("private fun boxed(lp: FrameLayout.LayoutParams): FrameLayout.LayoutParams {").substringBefore("\n    }")
        assertTrue(boxed.contains("val s = single ?: return lp"))
        val all = host.substringAfter("private fun boxAll(f: FrameLayout): FrameLayout {").substringBefore("\n    }")
        assertTrue(all.contains("if (single == null) return f"))
        assertTrue("the pause wheel spans the window", all.contains("if (v === pauseView) continue"))
    }

    @Test fun everyExistingHostAnswersNoTopWindow() {
        val shell = object : HostShell {
            override fun setHostContent(v: android.view.View) {}
            override fun hostGone() = false
            override fun recreateHost() {}
            override fun hostWindow(): android.view.Window? = null
            override fun hostIntent(): android.content.Intent? = null
            override fun startHostActivity(i: android.content.Intent) {}
        }
        assertNull(shell.hostSingle())
        // the app's own MainActivity and the bottom Presentation / Swap screens' activity never override it
        val app = File(src, "MainActivity.kt").readText()
        assertFalse(app.contains("hostSingle"))
        val fork = listOf("src/main/java/com/jrees/wowcompanion/fork", "dsforever/src/main/java/com/jrees/wowcompanion/fork").map { File(it) }.first { it.isDirectory }
        assertFalse(File(fork, "CompanionScreenActivity.kt").readText().contains("hostSingle"))
        assertFalse(File(fork, "DsForeverScreen.kt").readText().substringAfter("private class HostPresentation(").contains("hostSingle"))
    }

    @Test fun homesRestElsewhereOnlyWidensIsShown() {
        val home = File(src, "HomeView.kt").readText()
        assertTrue(home.contains("    var restElsewhere = false\n"))
        assertTrue(home.contains("fun partyHoldsDpad(): Boolean = partyOnHere() && flow.full == null && (isShown || restElsewhere) && !typeWanted && !editLive()"))
        assertTrue(home.contains("flow.full == null && (isShown || restElsewhere) && overlayOpen?.invoke() != true) partyView.selectPressed()"))
        assertTrue(home.contains("flow.full != null || !(isShown || restElsewhere) || typeWanted"))
        assertTrue("set only by the look", Regex("restElsewhere = ").findAll(host).count() == 1 &&
            host.substringAfter("private fun singleSync(why: String) {").substringBefore("\n    }").contains("val s = single ?: return"))
    }

    @Test fun theHostsSingleBranchesAreGuarded() {
        val glance = host.substringAfter("private fun singleGlance(on: Boolean, why: String) {").substringBefore("\n    }")
        assertTrue(glance.contains("if (single == null) return"))
        val dim = host.substringAfter("fun singleDimTap() {").substringBefore("\n    }")
        assertTrue(dim.contains("if (single == null"))
        assertTrue(host.contains("if (single != null && room == Room.HOME && glanceHub && !singleHomeSub() && !shell.hostGone()) { singleGlance(false, \"B\"); return }"))
        assertTrue("the glance only exists in single screen mode", host.contains("if (r != Room.HOME) glanceHub = false"))
        val wire = host.substringAfter("private fun singleCoreWire() {").substringBefore("\n    }")
        assertTrue("the poll only on one screen", wire.contains("if (single != null) {"))
        // the pause wheel's single look only with the top window (its two-screen draw unchanged but for the button, asked for)
        assertTrue(host.contains("pauseView.single = single != null"))
        // GameUi's preset only while SingleScreen.inUse
        assertTrue(File(src, "GameUi.kt").readText().contains("return if (SingleScreen.inUse) SingleUi.mode(id, saved) else saved"))
    }
}
