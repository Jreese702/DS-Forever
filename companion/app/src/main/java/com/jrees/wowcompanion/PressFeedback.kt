package com.jrees.wowcompanion

import android.os.SystemClock
import android.view.View

/**
 * PRESS FEEDBACK (the WHEN; Interact's [PressLook] draws the pushed-in LOOK) for a room's context buttons (the user 2026-10-07: "all other context buttons should have similar feedback";
 * Interact's recipe, UI Art's DOWN look). A button looks DOWN:
 *   (a) while a finger holds it, and for at least [MIN_MS] after a tap on it;
 *   (b) AT ONCE when the controller's A / B / X / Y presses it, read from the shared [PadInput] (display only: the game, or the
 *       addon, answers the press itself; nothing is sent from here), and again for at least [MIN_MS].
 * The keys are the view's own button names. [padTarget] maps a controller key ("PAD1" A, "PAD2" B, "PAD3" X, "PAD4" Y: the
 * physical face positions, Interact's map, proven on the Thor) to the button that press works now, or null.
 */
class PressFeedback(private val host: View) {
    private var finger: Any? = null
    private var pad: Any? = null
    private var padKey: String? = null
    private var lastKey: Any? = null
    private var lastUntil = 0L
    private var held = 0
    private var reader: PadInput? = null

    /** the button the controller key presses right now (the view's own state decides), or null */
    var padTarget: (String) -> Any? = { null }

    fun isDown(key: Any): Boolean = finger == key || pad == key || (lastKey == key && SystemClock.uptimeMillis() < lastUntil)

    /** the kit's state for a button: DISABLED, DOWN (pressed by a finger or the controller), or UP */
    fun state(key: Any, enabled: Boolean = true): UiKit.Button =
        if (!enabled) UiKit.Button.DISABLED else if (isDown(key)) UiKit.Button.DOWN else UiKit.Button.UP

    /** a finger went down on [key] (null = on no button, or it slid off) */
    fun finger(key: Any?) { if (finger != key) { finger = key; host.invalidate() } }

    /** the finger lifted; [tapped] = it was a tap on the button it held (that button stays DOWN [MIN_MS]) */
    fun lift(tapped: Boolean) {
        val k = finger
        finger = null
        if (tapped && k != null) hold(k)
        host.invalidate()
    }

    /** a tap on [key] that the view handled by itself (the button stays DOWN [MIN_MS]) */
    fun tapped(key: Any) { hold(key); host.invalidate() }

    private fun hold(k: Any) {
        lastKey = k
        lastUntil = SystemClock.uptimeMillis() + MIN_MS
        host.postInvalidateDelayed(MIN_MS + 16L)
    }

    /** the room is shown (true) or not: the controller is read only while it is (one shared reader for every view) */
    fun setActive(on: Boolean) {
        if (on && reader == null) {
            reader = PadInput(onPad = { _, _, _, _ -> }, onStatus = { _, _ -> }, onButtons = { m -> onHeld(m) }).also { it.start() }
        } else if (!on && reader != null) {
            reader?.stop(); reader = null
            pad = null; padKey = null; held = 0
        }
    }

    /** a held mask (the shared reader's, or one a view already runs): a NEW press pushes its button in at once */
    fun onHeld(mask: Int) {
        val down = mask and held.inv()
        held = mask
        var changed = false
        for ((slot, key) in SLOTS) {
            if (!PadInput.isHeld(mask, slot)) {
                if (padKey == key) { pad = null; padKey = null; changed = true }
                continue
            }
            if (!PadInput.isHeld(down, slot)) continue
            val t = padTarget(key) ?: continue
            pad = t; padKey = key
            hold(t)
            changed = true
        }
        if (changed) host.invalidate()
    }

    companion object {
        /** a press stays visibly DOWN at least this long (Interact's PRESS_MIN_MS) */
        const val MIN_MS = 160L
        private val SLOTS = listOf(PadInput.SLOT_FACE_BOTTOM to "PAD1", PadInput.SLOT_FACE_RIGHT to "PAD2",
                                   PadInput.SLOT_FACE_LEFT to "PAD3", PadInput.SLOT_FACE_TOP to "PAD4")
    }
}
