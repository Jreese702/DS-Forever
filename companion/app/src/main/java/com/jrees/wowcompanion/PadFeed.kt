package com.jrees.wowcompanion

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * THE CONTROLLER FROM THE LAUNCHER (the private fork, phase 3c step 1; the user 2026-10-08: "(a) 3c: mirror controller"). Inside the
 * launcher there is no root, so no getevent; but the launcher's game screen receives every pad event from Android before the game
 * does. It hands DS Forever a COPY of each one ([key], [motion]; the game still gets every press, nothing is swallowed), and this turns
 * them into exactly what the getevent reader delivers to [PadHub]: the four layer buttons, the slot mask, both sticks' eighths and
 * vectors. Every PadInput subscriber (the wheels, Home's B and keys, Cast's layers, DirectPad) then works unchanged.
 *
 * Mapping: Android's generic gamepad layout (Generic.kl) back to the reader's physical positions - BUTTON_A = BTN_SOUTH (face
 * bottom), B = EAST (face right), X = NORTH (code 307: face LEFT, PadReader.FACE_307_IS_LEFT), Y = WEST (308: face top). [R] for the
 * Thor's own key layout; the first device test says.
 * Main thread only (the launcher's input callbacks).
 */
object PadFeed {
    private var keys = 0          // slot bits from key events (D-pad keys, face, Start / Select, stick clicks)
    private var hat = 0           // D-pad slot bits from the hat axes
    private var lt = false; private var rt = false; private var lb = false; private var rb = false
    private var ltAxis = false; private var rtAxis = false
    private val vec = FloatArray(4)          // left x, y, right x, y as last delivered
    private var lSec = -1
    private var rSec = -1

    private val KEY_SLOT: Map<Int, Int> = mapOf(
        KeyEvent.KEYCODE_DPAD_LEFT to PadInput.SLOT_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP to PadInput.SLOT_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_RIGHT to PadInput.SLOT_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN to PadInput.SLOT_DPAD_DOWN,
        KeyEvent.KEYCODE_BUTTON_A to PadInput.SLOT_FACE_BOTTOM, KeyEvent.KEYCODE_BUTTON_B to PadInput.SLOT_FACE_RIGHT,
        KeyEvent.KEYCODE_BUTTON_X to (if (PadInput.FACE_307_IS_LEFT) PadInput.SLOT_FACE_LEFT else PadInput.SLOT_FACE_TOP),
        KeyEvent.KEYCODE_BUTTON_Y to (if (PadInput.FACE_307_IS_LEFT) PadInput.SLOT_FACE_TOP else PadInput.SLOT_FACE_LEFT),
        KeyEvent.KEYCODE_BUTTON_START to PadInput.SLOT_START, KeyEvent.KEYCODE_BUTTON_SELECT to PadInput.SLOT_SELECT,
        KeyEvent.KEYCODE_BUTTON_THUMBL to PadInput.SLOT_LSTICK, KeyEvent.KEYCODE_BUTTON_THUMBR to PadInput.SLOT_RSTICK,
    )

    /** a copy of one key event from a game controller; nothing is consumed */
    fun key(e: KeyEvent) {
        if (e.action != KeyEvent.ACTION_DOWN && e.action != KeyEvent.ACTION_UP) return
        val down = e.action == KeyEvent.ACTION_DOWN
        if (down && e.repeatCount > 0) return
        if (down) LatLog.mark("pad", "k${e.keyCode}")
        when (e.keyCode) {
            KeyEvent.KEYCODE_BUTTON_L1 -> lb = down
            KeyEvent.KEYCODE_BUTTON_R1 -> rb = down
            KeyEvent.KEYCODE_BUTTON_L2 -> lt = down
            KeyEvent.KEYCODE_BUTTON_R2 -> rt = down
            else -> {
                val slot = KEY_SLOT[e.keyCode] ?: return
                keys = if (down) keys or (1 shl slot) else keys and (1 shl slot).inv()
                PadHub.feedButtons(keys or hat)
                return
            }
        }
        PadHub.feedPad(lt || ltAxis, rt || rtAxis, lb, rb)
    }

    /** a copy of one motion event from a game controller (sticks, hat, analog triggers); nothing is consumed */
    fun motion(e: MotionEvent) {
        if (e.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK || e.action != MotionEvent.ACTION_MOVE) return
        // the hat as the D-pad's four slots (OR-ed with the D-pad keys, as the reader does)
        val hx = e.getAxisValue(MotionEvent.AXIS_HAT_X); val hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y)
        var h = 0
        if (hx < -0.5f) h = h or (1 shl PadInput.SLOT_DPAD_LEFT)
        if (hx > 0.5f) h = h or (1 shl PadInput.SLOT_DPAD_RIGHT)
        if (hy < -0.5f) h = h or (1 shl PadInput.SLOT_DPAD_UP)
        if (hy > 0.5f) h = h or (1 shl PadInput.SLOT_DPAD_DOWN)
        if (h != hat) { hat = h; PadHub.feedButtons(keys or hat) }
        // analog triggers: held over 35 %, let go under 30 % (the reader's thresholds)
        val l = maxOf(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE))
        val r = maxOf(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS))
        val nl = if (ltAxis) l > 0.30f else l > 0.35f
        val nr = if (rtAxis) r > 0.30f else r > 0.35f
        if (nl != ltAxis || nr != rtAxis) { ltAxis = nl; rtAxis = nr; PadHub.feedPad(lt || ltAxis, rt || rtAxis, lb, rb) }
        // the sticks: left X / Y; right Z / RZ (or RX / RY on a pad that uses those), y DOWN positive like evdev
        stick(0, e.getAxisValue(MotionEvent.AXIS_X), e.getAxisValue(MotionEvent.AXIS_Y))
        val zx = e.getAxisValue(MotionEvent.AXIS_Z); val zy = e.getAxisValue(MotionEvent.AXIS_RZ)
        val qx = e.getAxisValue(MotionEvent.AXIS_RX); val qy = e.getAxisValue(MotionEvent.AXIS_RY)
        if (zx * zx + zy * zy >= qx * qx + qy * qy) stick(1, zx, zy) else stick(1, qx, qy)
    }

    private fun stick(st: Int, rawX: Float, rawY: Float) {
        val x = if (kotlin.math.abs(rawX) < DEAD) 0f else rawX
        val y = if (kotlin.math.abs(rawY) < DEAD) 0f else rawY
        val px = vec[st * 2]; val py = vec[st * 2 + 1]
        val home = x == 0f && y == 0f && (px != 0f || py != 0f)
        if (home || kotlin.math.abs(x - px) > VEC_EPS || kotlin.math.abs(y - py) > VEC_EPS) {
            vec[st * 2] = x; vec[st * 2 + 1] = y
            PadHub.feedVector(st, x, y)
        }
        // the eighth past half deflection, 0 at the top, clockwise (the reader's sectorOf)
        val sec = if (x * x + y * y < 0.25f) -1
            else ((((Math.toDegrees(kotlin.math.atan2(x.toDouble(), -y.toDouble())) + 360.0 + 22.5) % 360.0) / 45.0).toInt().coerceIn(0, 7))
        if (st == 0 && sec != lSec) { lSec = sec; PadHub.feedLeftStick(sec) }
        if (st == 1 && sec != rSec) { rSec = sec; PadHub.feedStick(sec) }
    }

    private const val DEAD = 0.08f
    private const val VEC_EPS = 0.02f
}
