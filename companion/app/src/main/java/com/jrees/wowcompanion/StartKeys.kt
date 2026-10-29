package com.jrees.wowcompanion

import android.view.InputDevice
import android.view.KeyEvent

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

    fun route(e: KeyEvent): Boolean =
        pad(e.source) && (e.action == KeyEvent.ACTION_DOWN || e.action == KeyEvent.ACTION_UP) &&
            route(e.keyCode, e.action == KeyEvent.ACTION_DOWN, e.repeatCount == 0)
}
