package com.jrees.wowcompanion.fork

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SINGLE SCREEN MODE'S WINDOWS NEVER TOUCH THE GAME'S PANEL OR TAKE A SYSTEM ROUTE (spec 3.2 / 3.3): the top window and its sub windows
 * never ask for a display mode or a frame rate (that switches the game's own panel: StreamRate's job only), are never an overlay window
 * (a system permission) nor an activity on display 0 (the #383 / #384 restart loop), and never take the focus but while a field types.
 */
class NoDisplayModeTest {
    private fun code(name: String): String =
        listOf("src/main/java/com/jrees/wowcompanion/fork/$name", "dsforever/src/main/java/com/jrees/wowcompanion/fork/$name")
            .map { java.io.File(it) }.first { it.canRead() }.readText()
            .lines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.trimStart().startsWith("/**") }
            .joinToString("\n")

    @Test fun neverADisplayModeAFrameRateAnOverlayOrAnActivity() {
        for (f in listOf("TopWindow.kt", "SingleSub.kt")) {
            val c = code(f)
            for (bad in listOf("preferredDisplayModeId", "preferredRefreshRate", "preferredMinDisplayRefreshRate", "preferredMaxDisplayRefreshRate",
                "setFrameRate", "TYPE_APPLICATION_OVERLAY", "TYPE_SYSTEM", "REORDER_TO_FRONT", "SYSTEM_ALERT_WINDOW", "screenBrightness"))
                assertFalse("$f: $bad", c.contains(bad))
            assertFalse("$f: no activity of its own on display 0", c.contains("setLaunchDisplayId"))
            assertTrue("$f: a Dialog on the launcher's activity", c.contains("android.app.Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)"))
            assertTrue("$f: never focusable, as the Presentation", c.contains("WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL"))
            assertTrue("$f: no dim of Android's own", c.contains("clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)") && c.contains("setDimAmount(0f)"))
        }
        val top = code("TopWindow.kt")
        assertTrue("named for the ANR lines", top.contains("WinTitle.apply(w, com.jrees.wowcompanion.WinTitle.SINGLE)"))
        assertTrue("the host is never \"gone\" while its decor is hidden", top.contains("override fun hostGone(): Boolean = !up"))
        // the 1535 polish (SingleFade): HIDDEN = the decor GONE (no surface) once its fade-out lands (or at once before it is on screen)
        assertTrue("HIDDEN = the decor GONE (no surface)", top.contains("d.visibility = View.GONE") &&
            top.contains("if (!d.isAttachedToWindow) { d.visibility = if (on) View.VISIBLE else View.GONE; return }"))
        assertTrue("the host's onStop / onDestroy with the window", top.substringAfter("override fun onStop() {").substringBefore("\n    }")
            .contains("host?.let { it.onStop(); it.onDestroy() }"))
        assertTrue("its typing: the bottom window's own rules", top.contains("BottomTyping(context, { window })"))
        assertTrue(top.contains("override fun hostSingle(): SingleShell = this"))
    }
}
