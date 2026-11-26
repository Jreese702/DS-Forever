package com.jrees.wowcompanion.fork

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SINGLE SCREEN MODE'S TRANSITIONS, THE WINDOWS (the 1535 polish; the user on test 1533, 2026-11-26: "Right now everything kind of pops
 * open, we need to have smooth transitions for everything"): the top window fades in with the panel rising and fades its last picture
 * out (no touch taken meanwhile) before its decor is GONE; its dim eases; every small window (the docks, the strips, the hint plate)
 * fades in and fades its last picture out; the host hands the closing picture over BEFORE the docks take Home's views. The windows
 * themselves need a device (Dialogs on the launcher's activity): their code is pinned here, SingleFadeTest runs the pieces.
 */
class SingleFadePinsTest {
    private fun code(rel: String): String = listOf(File(rel), File("../$rel"), File(rel.removePrefix("dsforever/")))
        .first { it.isFile }.readText()

    private val top get() = code("dsforever/src/main/java/com/jrees/wowcompanion/fork/TopWindow.kt")
    private val sub get() = code("dsforever/src/main/java/com/jrees/wowcompanion/fork/SingleSub.kt")
    private val host get() = code("companion/app/src/main/java/com/jrees/wowcompanion/CompanionHost.kt")

    @Test fun theTopWindowFadesInAndOut() {
        val show = top.substringAfter("private fun showDecor(on: Boolean) {").substringBefore("\n    }\n")
        assertTrue("before it is on screen: as it is", show.contains("if (!d.isAttachedToWindow)"))
        assertTrue("in: the window's alpha with the panel rising", show.contains("frame.alpha = k; hostSlot.translationY = fx.RISE"))
        assertTrue("out: the last picture", show.contains("ghostView.pic = ghostNext"))
        assertTrue("out: no touch meanwhile", show.contains("touchFlag(false)"))
        assertTrue("out: GONE when it lands (no surface)", show.contains("d.visibility = View.GONE"))
        assertTrue("a show during a fade-out takes it back from where it is", show.contains("if (fadingOut) { fade?.cancel()"))
        val look = top.substringAfter("override fun look(l: SingleLook.Look) {").substringBefore("override fun sub(")
        assertTrue("the dim eases", look.contains("SingleFade.run(from, l.dim, com.jrees.wowcompanion.SingleFade.DIM_MS"))
        assertTrue("its picture: the ghost over the host's content", top.contains("frame.addView(ghostView"))
        assertTrue(top.contains("override fun ghost(p: android.graphics.Picture?) { ghostNext = p }"))
    }

    @Test fun theSmallWindowsFade() {
        val shown = sub.substringAfter("override fun shown(on: Boolean) {").substringBefore("\n    }\n")
        assertTrue("in", shown.contains("fx.SUB_IN_MS"))
        assertTrue("out: its last picture (its view may be back with its owner)", shown.contains("ghost.pic = fx.recordSelf(slot)"))
        assertTrue("out: no touch meanwhile, then GONE", shown.contains("touchFlag(false)") && shown.contains("d.visibility = View.GONE"))
        assertTrue("a touchable change waits for the fade", sub.contains("if (added && !fadingOut) touchFlag(on)"))
    }

    @Test fun theHostHandsTheClosingPictureOverFirst() {
        val sync = host.substringAfter("private fun singleSync(why: String) {").substringBefore("\n    }\n")
        val ghost = sync.indexOf("s.ghost(singleGhost())")
        assertTrue("FULL to HIDDEN: the picture", sync.contains("if (singleLookNow?.frame == SingleLook.Frame.FULL && look.frame == SingleLook.Frame.HIDDEN) s.ghost(singleGhost())"))
        assertTrue("before the docks take Home's views and the look is laid", ghost in 0 until sync.indexOf("singlePlayLook(look)") &&
            ghost < sync.indexOf("s.look(look)"))
        assertTrue("what shows is noted while it shows (the poll too)", sync.indexOf("singleGhostNote()") in 0 until sync.indexOf("if (look == singleLookNow)"))
    }
}
