package com.jrees.wowcompanion.stream

import android.view.KeyEvent
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * THE THOR'S CONTROLLER AS AN XBOX PAD ON THE PC ("Controller: goes to the PC"; Sunshine makes a virtual Xbox pad with ViGEm, so
 * WoW's gamepad mode works as it does on the Thor). Android's gamepad keys and axes -> GameStream's button flags, 0..255 triggers
 * and -32768..32767 sticks (y UP positive: Android's is down). Pure state; the activity sends [snapshot] whenever [key] or [axes]
 * changed something.
 *
 * NEVER SENT: the Guide / Home button (KEYCODE_BUTTON_MODE: on Windows it opens the Game Bar over WoW).
 */
class StreamPad {
    data class Snapshot(val buttons: Int, val lt: Int, val rt: Int, val lx: Int, val ly: Int, val rx: Int, val ry: Int)

    private var keyButtons = 0
    private var hatButtons = 0
    private var ltKey = false; private var rtKey = false
    private var lt = 0; private var rt = 0
    private var lx = 0; private var ly = 0; private var rx = 0; private var ry = 0

    /** the on-screen controls' own pad ([virtual]); merged with the physical one in [snapshot] */
    private var v = Snapshot(0, 0, 0, 0, 0, 0, 0)

    /** the physical pad and the touch overlay's virtual pad as ONE Xbox pad: buttons OR-ed, triggers the higher, each stick the stronger */
    fun snapshot(): Snapshot {
        fun strongest(ax: Int, ay: Int, bx: Int, by: Int) = if (ax.toLong() * ax + ay.toLong() * ay >= bx.toLong() * bx + by.toLong() * by) ax to ay else bx to by
        val (plx, ply) = strongest(lx, ly, v.lx, v.ly)
        val (prx, pry) = strongest(rx, ry, v.rx, v.ry)
        return Snapshot(keyButtons or hatButtons or v.buttons, maxOf(if (ltKey) 255 else lt, v.lt), maxOf(if (rtKey) 255 else rt, v.rt), plx, ply, prx, pry)
    }

    /**
     * The touch overlay's pad (Winlator's GamepadState: button bits by ExternalController's IDX_*, the D-pad as four booleans up /
     * right / down / left, triggers and sticks -1..1 with y DOWN positive as Android gives them); true = the merged pad changed.
     */
    fun virtual(winlatorButtons: Int, dpad: BooleanArray, lTrig: Float, rTrig: Float, x: Float, y: Float, z: Float, rz: Float): Boolean {
        val before = snapshot()
        var b = 0
        for ((idx, flag) in WINLATOR_BUTTONS) if (winlatorButtons and (1 shl idx) != 0) b = b or flag
        if (dpad.getOrElse(0) { false }) b = b or UP
        if (dpad.getOrElse(1) { false }) b = b or RIGHT
        if (dpad.getOrElse(2) { false }) b = b or DOWN
        if (dpad.getOrElse(3) { false }) b = b or LEFT
        val lt2 = if (winlatorButtons and (1 shl 10) != 0) 255 else trigger(lTrig)
        val rt2 = if (winlatorButtons and (1 shl 11) != 0) 255 else trigger(rTrig)
        v = Snapshot(b, lt2, rt2, stick(x), stick(-y), stick(z), stick(-rz))
        return snapshot() != before
    }

    /** a pad key; true = the state changed (send it). Unknown keys and the Guide button change nothing */
    fun key(keyCode: Int, down: Boolean): Boolean {
        val before = snapshot()
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L2 -> ltKey = down
            KeyEvent.KEYCODE_BUTTON_R2 -> rtKey = down
            else -> {
                val f = FLAGS[keyCode] ?: return false
                keyButtons = if (down) keyButtons or f else keyButtons and f.inv()
            }
        }
        return snapshot() != before
    }

    /** the sticks (-1..1, y down as Android gives it), the analog triggers (0..1) and the hat (-1..1); true = changed */
    fun axes(x: Float, y: Float, z: Float, rz: Float, lTrig: Float, rTrig: Float, hatX: Float, hatY: Float): Boolean {
        val before = snapshot()
        lx = stick(x); ly = stick(-y); rx = stick(z); ry = stick(-rz)
        lt = trigger(lTrig); rt = trigger(rTrig)
        var h = 0
        if (hatX < -0.5f) h = h or LEFT
        if (hatX > 0.5f) h = h or RIGHT
        if (hatY < -0.5f) h = h or UP
        if (hatY > 0.5f) h = h or DOWN
        hatButtons = h
        return snapshot() != before
    }

    /** everything let go (the stream ends, or a pop-up of ours takes the pad) */
    fun clear() { keyButtons = 0; hatButtons = 0; ltKey = false; rtKey = false; lt = 0; rt = 0; lx = 0; ly = 0; rx = 0; ry = 0; v = Snapshot(0, 0, 0, 0, 0, 0, 0) }

    companion object {
        const val UP = 0x0001; const val DOWN = 0x0002; const val LEFT = 0x0004; const val RIGHT = 0x0008
        const val PLAY = 0x0010; const val BACK = 0x0020; const val LS_CLK = 0x0040; const val RS_CLK = 0x0080
        const val LB = 0x0100; const val RB = 0x0200
        const val A = 0x1000; const val B = 0x2000; const val X = 0x4000; const val Y = 0x8000
        /** what the pad has (the arrival event): everything above, no Guide, no paddles */
        const val SUPPORTED = UP or DOWN or LEFT or RIGHT or PLAY or BACK or LS_CLK or RS_CLK or LB or RB or A or B or X or Y

        /** Winlator's ExternalController.IDX_BUTTON_* (A 0, B 1, X 2, Y 3, L1 4, R1 5, Select 6, Start 7, L3 8, R3 9; L2 10, R2 11 = triggers) */
        private val WINLATOR_BUTTONS = listOf(0 to A, 1 to B, 2 to X, 3 to Y, 4 to LB, 5 to RB, 6 to BACK, 7 to PLAY, 8 to LS_CLK, 9 to RS_CLK)

        private val FLAGS: Map<Int, Int> = mapOf(
            KeyEvent.KEYCODE_BUTTON_A to A, KeyEvent.KEYCODE_BUTTON_B to B, KeyEvent.KEYCODE_BUTTON_X to X, KeyEvent.KEYCODE_BUTTON_Y to Y,
            KeyEvent.KEYCODE_BUTTON_L1 to LB, KeyEvent.KEYCODE_BUTTON_R1 to RB,
            KeyEvent.KEYCODE_BUTTON_START to PLAY, KeyEvent.KEYCODE_BUTTON_SELECT to BACK, KeyEvent.KEYCODE_BACK to BACK,
            KeyEvent.KEYCODE_BUTTON_THUMBL to LS_CLK, KeyEvent.KEYCODE_BUTTON_THUMBR to RS_CLK,
            KeyEvent.KEYCODE_DPAD_UP to UP, KeyEvent.KEYCODE_DPAD_DOWN to DOWN, KeyEvent.KEYCODE_DPAD_LEFT to LEFT, KeyEvent.KEYCODE_DPAD_RIGHT to RIGHT,
        )

        /** a pad key Streaming forwards (the activity keeps the rest, e.g. volume) */
        fun isPadKey(keyCode: Int) = keyCode in FLAGS || keyCode == KeyEvent.KEYCODE_BUTTON_L2 || keyCode == KeyEvent.KEYCODE_BUTTON_R2 ||
            keyCode == KeyEvent.KEYCODE_BUTTON_MODE

        private const val DEADZONE = 0.07f
        fun stick(v: Float): Int {
            if (v.isNaN() || abs(v) < DEADZONE) return 0
            return (v.coerceIn(-1f, 1f) * 32767f).roundToInt().coerceIn(-32768, 32767)
        }
        fun trigger(v: Float): Int = if (v.isNaN() || v < 0.02f) 0 else (v.coerceIn(0f, 1f) * 255f).roundToInt()
    }
}
