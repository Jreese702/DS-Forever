package com.winlator.xserver

import android.os.Looper
import android.view.KeyEvent
import com.winlator.xserver.events.Event
import com.winlator.xserver.events.KeyPress
import com.winlator.xserver.events.MappingNotify
import io.mockk.every
import io.mockk.mockk
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Upstream WoW Forever for Android v2.4.2 (8607f984) + v2.4.6-rc3/rc4 (83cdbc93, 9c4b577f), ported 2026-11-20:
 * - a physical key press gets NO X auto-repeat (Android's own repeats are enough), so a release lost to a focus move
 *   between our two screens can't repeat a key forever (#153 candidate);
 * - injected keys (on-screen keyboard, DS Forever's pad sink, input controls) keep the X repeat, which their own release stops;
 * - any new press ends the previous key's repeat;
 * - Shift keeps a key's identity: Shift+1 stays KEY_1 with keysyms {'1','!'}, not a re-mapped '!' key (Shift keybinds).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyboardRepeatShiftTest {
    private lateinit var xServer: XServer
    private val events = mutableListOf<Event>()

    private class Recorder(private val sink: MutableList<Event>) :
        EventListener(null, Bitmask((Event.KEY_PRESS or Event.KEY_RELEASE).toLong())) {
        override fun sendEvent(event: Event) { sink += event }
    }

    /** The real Keyboard and InputDeviceManager on an XServer shell: the real XServer needs the native Drawable library. */
    @Before fun setUp() {
        xServer = mockk(relaxed = true)
        val root = Window(1, null, 0, 0, 800, 600, null)
        root.addEventListener(Recorder(events))
        val windowManager = mockk<WindowManager>(relaxed = true)
        every { windowManager.focusedWindow } returns root
        every { windowManager.findPointWindow(any(), any()) } returns root
        val pointer = mockk<Pointer>(relaxed = true)
        every { pointer.buttonMask } returns Bitmask()
        setField(windowManager, "rootWindow", root)
        setField(xServer, "windowManager", windowManager)
        setField(xServer, "pointer", pointer)
        val keyboard = Keyboard.createKeyboard(xServer)
        setField(xServer, "keyboard", keyboard)
        // XServer.inject* take the X lock and call the Keyboard: the same here, without the lock
        every { xServer.injectKeyPress(any<XKeycode>()) } answers { keyboard.setKeyPress(firstArg<XKeycode>().id, 0) }
        every { xServer.injectKeyPress(any(), any<Int>()) } answers { keyboard.setKeyPress(firstArg<XKeycode>().id, secondArg()) }
        every { xServer.injectKeyPress(any(), any<Int>(), any<Boolean>()) } answers {
            keyboard.setKeyPress(firstArg<XKeycode>().id, secondArg(), thirdArg<Boolean>())
        }
        every { xServer.injectKeyPress(any(), any<Int>(), any<Int>(), any<Boolean>()) } answers {
            keyboard.setKeyPress(firstArg<XKeycode>().id, secondArg(), thirdArg<Int>(), arg<Boolean>(3))
        }
        every { xServer.injectKeyRelease(any()) } answers { keyboard.setKeyRelease(firstArg<XKeycode>().id) }
        // #153: the input queue runs inline here; the logical key state is the keyboard's own
        every { xServer.postInput(any()) } answers { firstArg<Runnable>().run() }
        every { xServer.isKeyPressed(any()) } answers { keyboard.pressedKeys.contains(firstArg<XKeycode>().id) }
        every { xServer.isShiftDown() } answers {
            keyboard.pressedKeys.contains(XKeycode.KEY_SHIFT_L.id) || keyboard.pressedKeys.contains(XKeycode.KEY_SHIFT_R.id)
        }
        InputDeviceManager(xServer)   // registers itself as the keyboard's listener
    }

    private fun setField(target: Any, name: String, value: Any) {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            val f = c.declaredFields.firstOrNull { it.name == name }
            if (f != null) { f.isAccessible = true; f.set(target, value); return }
            c = c.superclass
        }
        error("no field $name")
    }

    private fun presses() = events.count { it is KeyPress }
    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun hw(action: Int, keyCode: Int, meta: Int = 0) = KeyEvent(0L, 0L, action, keyCode, 0, meta)
    private fun keysyms(key: XKeycode): Pair<Int, Int> {
        val i = (key.id - Keyboard.MIN_KEYCODE) * Keyboard.KEYSYMS_PER_KEYCODE
        return xServer.keyboard.keysyms[i] to xServer.keyboard.keysyms[i + 1]
    }

    @Test fun physicalKeyHeldWithLostReleaseIsNotRepeatedByX() {
        xServer.keyboard.onKeyEvent(hw(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_W))
        assertEquals(1, presses())
        idle(10_000)   // the ACTION_UP never arrives (focus moved to the other screen mid-press)
        assertEquals("no X-side repeat for a physical key", 1, presses())
    }

    @Test fun androidRepeatsStillReachTheGame() {
        xServer.keyboard.onKeyEvent(hw(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_W))
        xServer.keyboard.onKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_W, 1))
        xServer.keyboard.onKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_W, 2))
        assertEquals(3, presses())
        xServer.keyboard.onKeyEvent(hw(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_W))
        assertEquals(1, events.count { it is com.winlator.xserver.events.KeyRelease })
    }

    @Test fun injectedKeyKeepsXRepeatUntilReleased() {
        xServer.injectKeyPress(XKeycode.KEY_BKSP, 0)
        idle(1_000)
        val held = presses()
        assertTrue("X repeats an injected held key ($held)", held > 2)
        xServer.injectKeyRelease(XKeycode.KEY_BKSP)
        idle(1_000)
        assertEquals("the release stops the repeat", held, presses())
    }

    @Test fun aNewPressEndsThePreviousKeysRepeat() {
        xServer.injectKeyPress(XKeycode.KEY_BKSP, 0)
        idle(700)
        xServer.keyboard.onKeyEvent(hw(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A))
        val after = presses()
        idle(2_000)
        assertEquals("BKSP stopped repeating at the A press", after, presses())
    }

    /** the input queue held back (an op stuck on the X lock): postInput lands in [held] until [drain] */
    private val held = mutableListOf<Runnable>()
    private fun holdQueue() { every { xServer.postInput(any()) } answers { held += firstArg<Runnable>() } }
    private fun inlineQueue() { every { xServer.postInput(any()) } answers { firstArg<Runnable>().run() } }
    private fun drain() { while (held.isNotEmpty()) held.removeAt(0).run() }

    /**
     * #153 review: the repeat's timer fired and queued its press behind a stalled op, then a new key press ran first. Before: the queued
     * repeat read the CURRENT repeating key when it ran and repeated the old key (or the new one, at once) - the new press could not
     * un-post it. Now it is stale and does nothing.
     */
    @Test fun aRepeatQueuedBeforeANewPressDoesNothing() {
        xServer.injectKeyPress(XKeycode.KEY_BKSP, 0)
        holdQueue()
        idle(520)                                    // BKSP's repeat timer fired: its press waits in the queue
        assertEquals(1, held.size)
        xServer.keyboard.onKeyEvent(hw(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A))   // A's press runs first (no X repeat of its own)
        val after = presses()
        drain()
        assertEquals("the stale repeat sent nothing", after, presses())
        inlineQueue()
        idle(2_000)
        assertEquals("BKSP never started repeating again", after, presses())
    }

    @Test fun aRepeatQueuedBeforeANewRepeatKeyDoesNotRepeatItEarly() {
        xServer.injectKeyPress(XKeycode.KEY_BKSP, 0)
        holdQueue()
        idle(520)
        assertEquals(1, held.size)
        xServer.injectKeyPress(XKeycode.KEY_A, 0)    // a new auto-repeat key: its first repeat is its own 500 ms away
        val after = presses()
        drain()
        assertEquals("A not repeated at once by BKSP's stale repeat", after, presses())
        inlineQueue()
        idle(400)
        assertEquals("A waits its delay", after, presses())
        idle(300)
        assertTrue("then A repeats (${presses()})", presses() > after)
        xServer.injectKeyRelease(XKeycode.KEY_A)
    }

    @Test fun shiftNumberKeepsTheKeysIdentity() {
        val before = keysyms(XKeycode.KEY_1)
        assertEquals('1'.code to '!'.code, before)
        val shift = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        xServer.keyboard.onKeyEvent(hw(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT))
        xServer.keyboard.onKeyEvent(hw(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_1, shift))
        xServer.keyboard.onKeyEvent(hw(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_1, shift))
        xServer.keyboard.onKeyEvent(hw(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT))
        assertEquals("Shift+1 is still the '1' key with '!' on its Shift level", '1'.code to '!'.code, keysyms(XKeycode.KEY_1))
        assertEquals("no keyboard re-map", 0, events.count { it is MappingNotify })
        assertEquals(2, presses())   // Shift, then 1
    }

    @Test fun shiftedLetterAndPunctuationKeepTheirLevels() {
        val shift = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        for ((code, key) in listOf(KeyEvent.KEYCODE_A to XKeycode.KEY_A, KeyEvent.KEYCODE_SLASH to XKeycode.KEY_SLASH,
                                   KeyEvent.KEYCODE_2 to XKeycode.KEY_2)) {
            val before = keysyms(key)
            xServer.keyboard.onKeyEvent(hw(KeyEvent.ACTION_DOWN, code, shift))   // synthetic Shift (no Shift key held)
            xServer.keyboard.onKeyEvent(hw(KeyEvent.ACTION_UP, code, shift))
            assertEquals("$key keeps both levels", before, keysyms(key))
        }
        assertEquals(0, events.count { it is MappingNotify })
        assertTrue("synthetic Shift released", XKeycode.KEY_SHIFT_L.id !in xServer.keyboard.pressedKeys)
    }

    /** A key left with one level (e.g. by a keypad, or a non-US layout's character): Shift+1 restores {'1','!'}, not {'!','!'}. */
    @Test fun shiftNumberRestoresBothLevelsAfterASingleLevelMapping() {
        xServer.keyboard.setKeysyms(XKeycode.KEY_1.id, '1'.code, '1'.code)
        val shift = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        xServer.keyboard.onKeyEvent(hw(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT))
        xServer.keyboard.onKeyEvent(hw(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_1, shift))
        assertEquals("the '1' key, with '!' on its Shift level", '1'.code to '!'.code, keysyms(XKeycode.KEY_1))
        assertEquals("one re-map, to the two-level key", 1, events.count { it is MappingNotify })
        xServer.keyboard.onKeyEvent(hw(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_1, shift))
        xServer.keyboard.onKeyEvent(hw(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT))
        // and an unshifted press keeps it
        xServer.keyboard.onKeyEvent(hw(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_1))
        assertEquals('1'.code to '!'.code, keysyms(XKeycode.KEY_1))
        assertEquals(1, events.count { it is MappingNotify })
    }

    @Test fun keyWithoutPrintableBaseFallsBackUnchanged() {
        xServer.keyboard.onKeyEvent(hw(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F5))
        xServer.keyboard.onKeyEvent(hw(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_F5))
        assertEquals(65474 to 0, keysyms(XKeycode.KEY_F5))
        assertEquals(0, events.count { it is MappingNotify })
    }
}
