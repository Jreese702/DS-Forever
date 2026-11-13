package com.jrees.wowcompanion

import android.view.InputDevice
import android.view.KeyEvent

/**
 * THE CONTROLLER IN THE PROBLEM REPORT (issues #16 "no controller input", #84 "je ne peux pas controller l'ecran du bas avec les
 * boutons", #116 "joystick not working", #147 "no matter what i do i cant get the bottom screen to work", 2026-11-12). None of those
 * four reports could say whether a single press ever reached DS Forever: the pad's path (the launcher's game screen -> [PadFeed] -> the
 * rooms) logged nothing, and the logcat lines that were there were pushed out by the art step's file misses within minutes. This keeps,
 * in DiagLog's rolling FILE (logcat's eviction cannot touch it):
 * - the FIRST press from each input device on each route (the game screen, Streaming, the bottom window), with the launcher's verdict on it: "a controller: DS Forever reads it", or "not a controller
 *   to the launcher: DS Forever never reads it" (a pad the launcher's ExternalController.isGameController refuses never reaches PadFeed,
 *   nor the game as a pad);
 * - the game screen losing / getting back the window focus while the game runs (the Thor's dead-A trap: when another window holds the
 *   focus, every press goes there and neither the game nor the bottom screen hears it);
 * - one line for the report's "Settings and state": how many presses this run read, how long ago the last one was, the devices, the focus.
 * Device names are kept only when they look like a model name (a Bluetooth name the player chose, "Tom's pad", is not kept). Never a key's
 * meaning beyond its code; never text typed (keyboard letters are not counted).
 * [note] / [focusNote] / [line] are pure enough for the tests (DiagLog.useDir).
 */
object PadDiag {
    data class Dev(val id: Int, val name: String, val vendor: Int, val product: Int, val sources: Int)

    private val lock = Any()
    private val seen = LinkedHashMap<String, Pair<String, Boolean>>() // device id + route -> its label, controller?
    @Volatile var presses = 0; private set
    @Volatile var lastPressAt = 0L; private set
    /** null = never checked (Streaming, or before the game screen); else the game screen's window focus at the last check */
    @Volatile var focus: Boolean? = null; private set
    @Volatile private var focusSince = 0L

    /** a key event as the launcher's game screen (or Streaming's, or the bottom window) got it; [controller] = the launcher's verdict */
    fun key(e: KeyEvent, controller: Boolean, route: String) {
        if (e.action != KeyEvent.ACTION_DOWN || e.repeatCount > 0) return
        val d = e.device
        val dev = if (d == null) null else runCatching { Dev(d.id, d.name ?: "", d.vendorId, d.productId, d.sources) }.getOrNull()
        note(dev, e.keyCode, controller, route)
    }

    /** a controller's key code: the buttons and the D-pad (Android's own KeyEvent.isGamepadButton plus the D-pad) */
    fun padKey(code: Int): Boolean = code in KeyEvent.KEYCODE_BUTTON_A..KeyEvent.KEYCODE_BUTTON_MODE ||
        code in KeyEvent.KEYCODE_BUTTON_1..KeyEvent.KEYCODE_BUTTON_16 ||
        code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_LEFT ||
        code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_CENTER

    /** one press (a key down, no repeat); the line it logged, null = nothing new to say */
    fun note(dev: Dev?, code: Int, controller: Boolean, route: String, now: Long = System.currentTimeMillis()): String? {
        if (controller) { presses++; lastPressAt = now }
        if (!controller && !padKey(code)) return null                 // a keyboard's letters: not the controller's business
        val id = "${dev?.id ?: -1}|$route"
        val label = label(dev)
        synchronized(lock) {
            if (seen.containsKey(id)) return null
            if (seen.size >= MAX_DEVICES) return null
            seen[id] = label to controller
        }
        val line = "first press from $label (key $code, via $route): " +
            if (controller) "a controller, DS Forever reads it" else "NOT a controller to the launcher: neither DS Forever nor the game reads it as a pad"
        DiagLog.event("pad", line, now)
        return line
    }

    /** the game screen's window focus while the game runs (once a second); logs only a change */
    fun focusNote(has: Boolean, typing: Boolean, now: Long = System.currentTimeMillis()): String? {
        val was = focus
        if (was == has) return null
        focus = has; focusSince = now
        val line = when {
            has && was == null -> return null                          // the normal start: nothing to say
            has -> "the game screen has the focus again"
            typing -> "the game screen lost the focus to the bottom screen's text field (the controller types there)"
            else -> "the game screen lost the focus: the controller's presses go to another window (neither the game nor the bottom screen hears them)"
        }
        DiagLog.event("pad", line, now)
        return line
    }

    /** the report's one line: the presses, the devices, the focus */
    fun line(now: Long = System.currentTimeMillis()): String {
        val devs = synchronized(lock) { seen.values.map { (l, c) -> if (c) l else "$l (not a controller)" }.distinct() }
        val p = if (presses == 0) "no controller press read this run" else
            "$presses ${if (presses == 1) "press" else "presses"} read, the last ${GameLife.minutes((now - lastPressAt).coerceAtLeast(0L))} ago"
        val f = when (focus) { null -> "?"; true -> "yes"; false -> "NO for ${GameLife.minutes((now - focusSince).coerceAtLeast(0L))}" }
        return "Controller: $p; devices: ${if (devs.isEmpty()) "none seen" else devs.joinToString(", ")}; game screen focus: $f"
    }

    /** a model name as it is; a name the player gave it is left out (privacy) */
    fun label(dev: Dev?): String {
        if (dev == null) return "an unknown device"
        val n = dev.name.trim()
        val safe = n.isNotEmpty() && n.length <= 40 && n.none { it == '\'' || it == '’' || it == '@' } && !n.contains(" de ") && !n.contains(" di ")
        val ids = "%04x:%04x".format(dev.vendor and 0xffff, dev.product and 0xffff)
        val kind = when {
            dev.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD -> "gamepad"
            dev.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK -> "joystick"
            dev.sources and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD -> "keyboard"
            else -> "sources 0x${Integer.toHexString(dev.sources)}"
        }
        return "${if (safe) "'$n'" else "a named device"} ($ids, $kind)"
    }

    /** tests: a fresh run */
    fun reset() { synchronized(lock) { seen.clear() }; presses = 0; lastPressAt = 0L; focus = null; focusSince = 0L }

    private const val MAX_DEVICES = 8
}

/**
 * THE ADD-ONS' SILENCE (#147, 1.0.24: the game had started 12 minutes before, the player was asked for the authenticator twice, and in
 * the 2 minutes the bottom screen was up not one add-on document came: the rooms had nothing to work with, the report could not say so).
 * Pure: once, [AFTER_MS] after the bottom screen came up with the game up and no current document yet, the line saying so.
 */
object AddonSilence {
    const val AFTER_MS = 120_000L
    fun line(docSeen: Boolean, gameUp: Boolean, hostUpMs: Long, gameUpMs: Long): String? {
        if (docSeen || !gameUp || hostUpMs < AFTER_MS) return null
        return "no current document from the game's add-ons ${GameLife.minutes(hostUpMs)} after the bottom screen came up " +
            "(the game up ${if (gameUpMs > 0) GameLife.minutes(gameUpMs) else "?"}): not in the world yet, or our add-ons did not load; the rooms wait for them"
    }
}
