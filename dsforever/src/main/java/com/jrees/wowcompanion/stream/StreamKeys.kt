package com.jrees.wowcompanion.stream

import android.util.Log
import com.jrees.wowcompanion.TapSink

/** Moonlight's keyboard + mouse channel (MoonBridge on the device, a recorder in the tests) */
interface MoonInput {
    /** [vk] = a Windows virtual-key code; [mods] = MODIFIER_* bits held */
    fun key(vk: Int, down: Boolean, mods: Int)
    fun mousePosition(x: Int, y: Int, refW: Int, refH: Int)
    fun mouseMove(dx: Int, dy: Int)
    fun mouseButton(down: Boolean, button: Int)
    /** the wheel, high resolution (120 = one notch; positive = up) */
    fun scroll(amount: Int) {}
}

/**
 * StreamKeys: the companion's taps and key chords into WoW ON THE PC, through Moonlight's keyboard and mouse channel (Streaming,
 * S2). It stands where the launcher's X server stands in Local ([com.jrees.wowcompanion.InputSink.Sink]), so TapBridge, the tap pad
 * rows, typed chat and the trackpad are unchanged above it:
 *  - key(xKeycode): the X keycode (evdev + 8, US layout, as InputSink speaks) -> the Windows virtual key -> Moonlight;
 *  - tap(fx, fy): the pointer to that fraction of the stream (the PC's desktop = WoW, full screen) and a left click there.
 *
 * KEY SAFETY ON A REAL WINDOWS DESKTOP (the chord rules, a second time: InputSink.play already refuses Alt / F-keys / system keys in a
 * key-channel line). Never sent, whatever asks: Alt (Alt+F4, Alt+Tab), the Windows keys, Menu, every F-key, Print Screen, Pause,
 * the lock keys; and Esc while Ctrl is held (Ctrl+Esc = Start, Ctrl+Shift+Esc = Task Manager). A refused key is logged once and
 * dropped; its release is dropped too, so nothing sticks.
 */
class StreamKeys(
    private val input: MoonInput,
    @Volatile var streamW: Int,
    @Volatile var streamH: Int,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) : TapSink {
    override val route get() = "stream"

    private val held = HashSet<Int>()          // virtual keys down right now
    @Volatile private var px = 0
    @Volatile private var py = 0

    @Synchronized override fun key(xKeycode: Int, down: Boolean) {
        // never the key itself in the log: the player may be typing a Battle.net password through the stream (the user, 2026-10-28)
        val vk = XToVk.vk(xKeycode) ?: run { refused("unknown key"); return }
        if (down && blocked(vk)) { refused("blocked key 0x${Integer.toHexString(vk)}"); return }
        if (!down && vk !in held) return                       // the release of a refused (or never pressed) key
        if (down) held.add(vk) else held.remove(vk)
        input.key(vk, down, mods())
    }

    /** a key is refused when it is a system key, or Esc with Ctrl down */
    @Synchronized fun blocked(vk: Int): Boolean = XToVk.systemKey(vk) || (vk == XToVk.VK_ESCAPE && (XToVk.VK_LCONTROL in held || XToVk.VK_RCONTROL in held))

    private fun mods(): Int {
        var m = 0
        if (XToVk.VK_LSHIFT in held || XToVk.VK_RSHIFT in held) m = m or MODIFIER_SHIFT
        if (XToVk.VK_LCONTROL in held || XToVk.VK_RCONTROL in held) m = m or MODIFIER_CTRL
        return m
    }

    override fun tap(fx: Float, fy: Float) {
        moveTo(fx, fy)
        input.mouseButton(true, BUTTON_LEFT)
        sleep(CLICK_MS)
        input.mouseButton(false, BUTTON_LEFT)
    }

    override fun moveTo(fx: Float, fy: Float) {
        val w = streamW.coerceAtLeast(1); val h = streamH.coerceAtLeast(1)
        px = (fx.coerceIn(0f, 1f) * (w - 1)).toInt(); py = (fy.coerceIn(0f, 1f) * (h - 1)).toInt()
        // 1.0.21 (streaming taps missed the tap pad: the PC's cursor measured at 1169,101 for the uisw cell at 1169,103): the position
        // goes out on a FINE reference, not in stream pixels - Sunshine scales it to the PC's screen in floating point, so a 1080p
        // stream no longer rounds a click off the pad's 2-game-pixel rows on a 1440p PC
        input.mousePosition(Math.round(fx.coerceIn(0f, 1f) * REF), Math.round(fy.coerceIn(0f, 1f) * REF), REF, REF)
    }

    override fun moveBy(dx: Int, dy: Int) {
        px = (px + dx).coerceIn(0, streamW - 1); py = (py + dy).coerceIn(0, streamH - 1)
        input.mouseMove(dx, dy)
    }

    override fun button(right: Boolean, down: Boolean) = input.mouseButton(down, if (right) BUTTON_RIGHT else BUTTON_LEFT)

    /** the PC draws its own cursor: nothing to show here */
    override fun showPointer(on: Boolean) {}
    override fun pointerNote(): String = "$px,$py of ${streamW}x$streamH (stream)"

    /** the stream is ending: every key still down goes up (nothing sticks on the PC) */
    @Synchronized fun releaseAll() {
        for (vk in held.toList()) { held.remove(vk); try { input.key(vk, false, mods()) } catch (_: Throwable) {} }
    }

    @Synchronized fun heldKeys(): Set<Int> = held.toSet()

    private var refusedSaid = 0
    private fun refused(why: String) {
        if (refusedSaid++ < 20) try { Log.w("DSFSTREAM", "stream key not sent: $why") } catch (_: Throwable) {}
    }

    companion object {
        const val MODIFIER_SHIFT = 0x01
        const val MODIFIER_CTRL = 0x02
        const val BUTTON_LEFT = 0x01
        const val BUTTON_RIGHT = 0x03
        /** a click's press and release this far apart (the PC's game sees two separate events, as the X server's do) */
        const val CLICK_MS = 16L
        /** the mouse position's reference size (Moonlight sends shorts: at most 32767) */
        const val REF = 30000
    }
}

/** InputSink's X keycodes (evdev + 8, US layout) -> Windows virtual keys */
object XToVk {
    const val VK_ESCAPE = 0x1B
    const val VK_LSHIFT = 0xA0; const val VK_RSHIFT = 0xA1
    const val VK_LCONTROL = 0xA2; const val VK_RCONTROL = 0xA3
    const val VK_LMENU = 0xA4; const val VK_RMENU = 0xA5
    const val VK_LWIN = 0x5B; const val VK_RWIN = 0x5C

    private val MAP: Map<Int, Int> = buildMap {
        // letters: InputSink's ANDROID_TO_X order a..z
        val letters = intArrayOf(38, 56, 54, 40, 26, 41, 42, 43, 31, 44, 45, 46, 58, 57, 32, 33, 24, 27, 39, 28, 30, 55, 25, 53, 29, 52)
        for (i in 0 until 26) put(letters[i], 0x41 + i)
        for (i in 1..9) put(9 + i, 0x30 + i); put(19, 0x30)                      // 1..9, 0
        for (i in 0 until 10) put(67 + i, 0x70 + i); put(95, 0x7A); put(96, 0x7B) // F1..F12 (mapped so they can be REFUSED by name)
        put(9, VK_ESCAPE); put(36, 0x0D); put(23, 0x09); put(65, 0x20); put(22, 0x08)  // Esc, Enter, Tab, Space, Backspace
        put(20, 0xBD); put(21, 0xBB); put(34, 0xDB); put(35, 0xDD); put(51, 0xDC)       // - = [ ] \
        put(47, 0xBA); put(48, 0xDE); put(59, 0xBC); put(60, 0xBE); put(61, 0xBF); put(49, 0xC0) // ; ' , . / `
        put(110, 0x24); put(115, 0x23); put(112, 0x21); put(117, 0x22); put(118, 0x2D); put(119, 0x2E) // Home End PgUp PgDn Ins Del
        put(111, 0x26); put(116, 0x28); put(113, 0x25); put(114, 0x27)               // Up Down Left Right
        put(50, VK_LSHIFT); put(62, VK_RSHIFT); put(37, VK_LCONTROL); put(105, VK_RCONTROL)
        put(64, VK_LMENU); put(108, VK_RMENU); put(133, VK_LWIN); put(134, VK_RWIN); put(135, 0x5D) // Alt, Win, Menu (refused)
        put(107, 0x2C); put(127, 0x13); put(66, 0x14); put(77, 0x90); put(78, 0x91) // PrintScreen Pause CapsLock NumLock ScrollLock
    }

    fun vk(xKeycode: Int): Int? = MAP[xKeycode]

    /** never sent to the PC */
    fun systemKey(vk: Int): Boolean = vk == 0x12 || vk == VK_LMENU || vk == VK_RMENU || vk == VK_LWIN || vk == VK_RWIN ||
        vk == 0x5D || vk == 0x5F || vk in 0x70..0x87 || vk == 0x2C || vk == 0x13 || vk == 0x14 || vk == 0x90 || vk == 0x91
}
