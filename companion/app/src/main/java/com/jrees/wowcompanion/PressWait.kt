package com.jrees.wowcompanion

import android.view.View

/**
 * A PRESS WAITING FOR THE GAME'S ANSWER (the user, 2026-10-07: "some kind of visual for the delay though, to stop the player from
 * spamming after the press"; the pick "B: the game's spinner"). One per view. After a press: [begin] with the button's key (its hit
 * id) and, when known, the command it sent. The button draws the kit's waiting state ([isWaiting]: pushed in + the spinner) until
 * the room's own answer signal calls [answered] / [refused] (a refusal just ends it: the room's note is the feedback), or for
 * [TIMEOUT_MS] at most. [accept] is the TOUCH gate: false while that key waits, so a second tap is ignored. A controller's repeats
 * can only be gated where the key lands (the add-on). Main thread.
 */
class PressWait(private val host: View) {
    private class W(val cmd: String?, val until: Long)
    private val waits = HashMap<String, W>()

    /** [key] waits from now: for [cmd]'s answer when given, else for the next answer of any command */
    fun begin(key: String, cmd: String? = null) {
        if (key.isEmpty()) return
        waits[key] = W(cmd, System.currentTimeMillis() + TIMEOUT_MS)
        host.postInvalidateOnAnimation()
        host.postDelayed({ host.invalidate() }, TIMEOUT_MS + 20L)
    }

    /** the game answered [cmd]: its waits end, and every wait that did not know its command */
    fun answered(cmd: String?) {
        if (waits.isEmpty()) return
        waits.entries.removeAll { (_, w) -> w.cmd == null || w.cmd == cmd }
        host.invalidate()
    }

    /** the game refused [cmd]: the wait ends the same way, with no flash of its own */
    fun refused(cmd: String?) = answered(cmd)

    /** true while [key] waits (asks the host for the next frame, so the spinner turns) */
    fun isWaiting(key: String): Boolean {
        val w = waits[key] ?: return false
        if (System.currentTimeMillis() >= w.until) { waits.remove(key); return false }
        host.postInvalidateOnAnimation()
        return true
    }

    /** the keys waiting now */
    fun keys(): List<String> = waits.keys.filter { isWaiting(it) }

    /** the touch gate: false while [key] waits */
    fun accept(key: String): Boolean = !isWaiting(key)

    /** everything ends (the window changed or closed) */
    fun clear() { if (waits.isNotEmpty()) { waits.clear(); host.invalidate() } }

    companion object {
        const val TIMEOUT_MS = 2_000L
    }
}
