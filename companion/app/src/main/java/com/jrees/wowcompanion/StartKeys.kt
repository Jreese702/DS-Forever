package com.jrees.wowcompanion

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * THE START-UP PAGES' PAD KEYS, ONE ROUTE (the user on the Thor, 2026-10-28: "Controller inputs need to be able to change the Local vs
 * Streaming mode"; A on Update did nothing): a game controller's key goes to the bottom's chooser first ([ModeBridge.key]), then the
 * setup's buttons ([SetupBridge.key]). The launcher's start screen and the bottom window (when it holds the focus) both send it here.
 */
object StartKeys {
    fun pad(source: Int): Boolean =
        source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD || source and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD

    /** [code] down ([first] = not a repeat) or up: true = a start-up page took it */
    fun route(code: Int, down: Boolean, first: Boolean): Boolean {
        val d = down && first
        return ModeBridge.key(code, d) || SetupBridge.key(code, d)
    }

    private val stick = StartStick()

    /**
     * The left stick and the hat as the D-pad's keys (the user on the Thor, 2026-10-30: "The control stick and dpad needs to be able to
     * select between local or streaming"; they come as motion, which no start-up page heard). True = a start-up page took a move.
     */
    fun motion(e: MotionEvent): Boolean {
        if (e.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK || e.action != MotionEvent.ACTION_MOVE) return false
        var took = false
        stick.move(e.getAxisValue(MotionEvent.AXIS_HAT_X), e.getAxisValue(MotionEvent.AXIS_HAT_Y),
            e.getAxisValue(MotionEvent.AXIS_X), e.getAxisValue(MotionEvent.AXIS_Y)) { code, down -> if (route(code, down, down)) took = true }
        return took
    }

    fun route(e: KeyEvent): Boolean =
        pad(e.source) && (e.action == KeyEvent.ACTION_DOWN || e.action == KeyEvent.ACTION_UP) &&
            route(e.keyCode, e.action == KeyEvent.ACTION_DOWN, e.repeatCount == 0)
}

/** one D-pad direction at a time out of the hat (first) and the stick: one press per push; the stick must come back before the next */
class StartStick {
    private var held = 0

    /** [send] (keycode, down) for what changed */
    fun move(hatX: Float, hatY: Float, x: Float, y: Float, send: (Int, Boolean) -> Unit) {
        val hat = dir(hatX, hatY, 0.5f)
        val now = when {
            hat != 0 -> hat
            held != 0 && still(held, x, y) -> held
            else -> dir(x, y, PUSH)
        }
        if (now == held) return
        if (held != 0) send(held, false)
        held = now
        if (now != 0) send(now, true)
    }

    private fun still(code: Int, x: Float, y: Float) = when (code) {
        KeyEvent.KEYCODE_DPAD_LEFT -> x < -LET_GO
        KeyEvent.KEYCODE_DPAD_RIGHT -> x > LET_GO
        KeyEvent.KEYCODE_DPAD_UP -> y < -LET_GO
        else -> y > LET_GO
    }

    /** the stronger axis, past [min] */
    private fun dir(x: Float, y: Float, min: Float): Int = when {
        kotlin.math.abs(x) >= kotlin.math.abs(y) && x <= -min -> KeyEvent.KEYCODE_DPAD_LEFT
        kotlin.math.abs(x) >= kotlin.math.abs(y) && x >= min -> KeyEvent.KEYCODE_DPAD_RIGHT
        y <= -min -> KeyEvent.KEYCODE_DPAD_UP
        y >= min -> KeyEvent.KEYCODE_DPAD_DOWN
        else -> 0
    }

    private companion object {
        const val PUSH = 0.6f
        const val LET_GO = 0.35f
    }
}
