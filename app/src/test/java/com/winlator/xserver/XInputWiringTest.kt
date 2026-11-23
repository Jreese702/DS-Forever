package com.winlator.xserver

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #153: the wiring around the X input queue (XInputQueueTest drives the queue itself; the real XServer needs the native Drawable
 * library, so here its source): every inject* queues, the senders read the LOGICAL state back, the repeat timer and the socket writes
 * of the input thread never wait on the UI thread's behalf, and the frame-generation pushes and the HUD's frame count stay off it.
 */
class XInputWiringTest {
    private val root = listOf("src/main/java", "app/src/main/java").map { File(it) }.first { it.isDirectory }
    private fun text(p: String) = File(root, p).readText()
    private fun body(t: String, head: String): String {
        val i = t.indexOf(head); require(i >= 0) { head }
        var depth = 0; var k = t.indexOf('{', i)
        val start = k
        while (k < t.length) { if (t[k] == '{') depth++ else if (t[k] == '}') { depth--; if (depth == 0) break }; k++ }
        return t.substring(start, k + 1)
    }

    @Test fun everyInjectQueues() {
        val x = text("com/winlator/xserver/XServer.java")
        for (head in listOf("public void injectPointerMove(int x, int y)", "public void injectPointerMoveDelta(int dx, int dy)",
                            "public void injectPointerButtonPress(Pointer.Button buttonCode)", "public void injectPointerButtonRelease(Pointer.Button buttonCode)",
                            "public void injectKeyPress(XKeycode xKeycode, int keysym, int shiftedKeysym, boolean autoRepeat)",
                            "public void injectKeyRelease(XKeycode xKeycode)", "public void injectPointerMoveBy(int dx, int dy)",
                            "public void injectClickAt(int x, int y, Pointer.Button button, long holdMs)",
                            "private void submitButtonPress(Pointer.Button button, Runnable before)",
                            "private void submitButtonRelease(Pointer.Button button, Runnable after)")) {
            val b = body(x, head)
            assertFalse(head, b.contains("lock("))
            assertTrue(head, b.contains("inputQueue.") || b.contains("submit(") || b.contains("submitButton") || b.contains("postInput("))
        }
        // #153 review: relative-mouse deltas go straight to the Windows side (never behind a key stuck on the X lock)
        val delta = body(x, "public void injectPointerMoveDelta(int dx, int dy)")
        assertTrue(delta.indexOf("handler.mouseEvent(MouseEventFlags.MOVE") in 0 until delta.indexOf("inputQueue.moveBy("))
        // a release keeps the sender's hold even when the queue ran behind
        for (head in listOf("public void injectKeyRelease(XKeycode xKeycode)", "private void submitButtonRelease(Pointer.Button button, Runnable after)")) {
            val b = body(x, head)
            assertTrue(head, b.indexOf("keepHold(") in 0 until maxOf(b.indexOf("keyReleaseNow("), b.indexOf("pointerButtonReleaseNow(")))
        }
        // the click's spot to go back to is read on the input thread, in the press's own op
        val click = body(x, "public void injectClickAt(int x, int y, Pointer.Button button, long holdMs)")
        assertTrue(click.contains("submitButtonPress(button, () -> {") && click.indexOf("back[0] = pointer.getX()") > click.indexOf("submitButtonPress("))
        // the logical view is set in the same step as the queueing
        val submit = body(x, "private void submit(Runnable state, Runnable op)")
        assertTrue(submit.contains("synchronized (logical)") && submit.indexOf("state.run()") < submit.indexOf("inputQueue.post(op)"))
        // the slow-input line names the X locks' owners
        assertTrue(x.contains("new OwnedLock()") && x.contains("held by"))
    }

    @Test fun theSendersReadTheLogicalStateBack() {
        for (p in listOf("com/winlator/widget/TouchpadView.java", "com/winlator/inputcontrols/TouchMouse.java")) {
            assertFalse(p, text(p).contains("pointer.isButtonPressed("))
        }
        assertFalse(text("com/winlator/inputcontrols/Binding.java").contains("getModifiersMask().isSet(1)"))
        assertFalse(text("app/gamenative/externaldisplay/ExternalOnScreenKeyboardView.kt").contains("modifiersMask.isSet(1)"))
        val needs = body(text("com/winlator/xserver/Keyboard.java"), "private boolean needsSyntheticShift(KeyEvent event)")
        assertTrue(needs.contains("xServer.isShiftDown()") && !needs.contains("pressedKeys.contains("))
        // the cursor drag reads the pointer where it is when its move runs
        assertTrue(body(text("com/winlator/widget/TouchpadView.java"), "private void moveCursorByDelta(float dx, float dy)").contains("injectPointerMoveBy(mx, my)"))
    }

    @Test fun theRepeatAndTheSocketWritesStayOffTheUiThread() {
        val idm = text("com/winlator/xserver/InputDeviceManager.java")
        val repeat = body(idm, "private void scheduleAutoRepeat(long delayMs)")
        assertTrue(repeat.contains("xServer.postInput(") && repeat.contains("xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)"))
        // #153 review: a repeat already queued when a later press ran does nothing (the key and the generation are taken at the timer)
        assertTrue(repeat.indexOf("final int gen = autoRepeatGen") in 0 until repeat.indexOf("postAtTime(") &&
            repeat.contains("if (gen != autoRepeatGen || keycode != currentRepeatingKeycode) return;"))
        assertFalse(idm.contains("removeCallbacks(autoRepeatRunnable)"))
        val oq = body(text("com/winlator/xconnector/OutputQueue.java"), "static boolean onMainThread()")
        assertTrue(oq.contains("XInputQueue.onInputThread()"))
    }

    /** #153 review: DS Forever's cell tap never reads the X pointer on its own thread (a queued move or tap would be missed) */
    @Test fun theCellTapReadsItsBackSpotOnTheInputThread() {
        val tap = body(text("app/gamenative/ui/screen/xserver/XServerScreen.kt"), "override fun tap(fx: Float, fy: Float)")
        assertTrue(tap.contains("xs.injectClickAt("))
        assertFalse(tap.contains("pointer.getX()") || tap.contains("Thread.sleep(") || tap.contains("injectPointerMove("))
    }

    @Test fun frameGenerationPushesStayOffTheUiThread() {
        val l = text("app/gamenative/utils/LsfgVkManager.kt")
        val apply = body(l, "fun applyNativeRuntime(renderer: VulkanRenderer, container: Container, context: Context)")
        val run = apply.indexOf("runtimeExecutor.execute")
        assertTrue(run > 0)
        for (call in listOf("renderer.setFrameGenerationMode(", "renderer.setFrameGenerationRefreshRate(", "renderer.setFrameGenerationShaders(",
                            "renderer.setFrameGenerationEnabled(")) {
            assertTrue(call, apply.indexOf(call) > run)
        }
        val hud = body(l, "private fun readNativeMeasuredFps(): Float?")
        assertFalse("the HUD's frame count is read on the runtime thread", hud.contains("presentedFrameCount"))
        assertTrue(hud.contains("runtimeExecutor.execute"))
    }
}
