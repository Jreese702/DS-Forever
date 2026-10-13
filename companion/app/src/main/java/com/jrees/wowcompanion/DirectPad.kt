package com.jrees.wowcompanion

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * DIRECTPAD: a room's controller presses straight from the app's own reader (~20 ms) instead of the game's relayed copy (~200 ms,
 * the latency study 2026-10-06). The SPEC (Quests' proposal, adopted):
 *  - a direct press goes to guest X only while X's room is the one shown AND the capture's holder is "pad:X" (never while pending:
 *    the game would act on that press itself) AND the reader is healthy;
 *  - meanwhile MainActivity DROPS X's relayed presses (the same press, later); "lost" documents are never dropped; no refree on a
 *    direct press; an app-started leave stops delivery at once ([stop]);
 *  - only the keys X registered (its capture's keys + its extras), on the PRESS (and the release for a room that asks);
 *  - hold-repeat here, for the keys a room declares ([DirectPadKeys.REPEAT_FIRST_MS] then [DirectPadKeys.REPEAT_MS]).
 * Rooms with their own add-on capture register the same way with their own [Reg.active].
 */
object DirectPad {
    private const val TAG = "DirectPad"

    class Reg(
        val id: String,
        val keys: Set<String>,
        val repeat: Set<String> = emptySet(),
        val wantsRelease: Boolean = false,
        /** the room is shown and its capture holds the controller (for the generic guests: MainActivity's rule) */
        val active: () -> Boolean,
        val onKey: (k: String, down: Boolean, repeat: Boolean) -> Unit,
    )

    private val regs = LinkedHashMap<String, Reg>()
    private val main = Handler(Looper.getMainLooper())
    private var reader: PadInput? = null
    private var mask = 0
    private val pads = BooleanArray(4)
    /** the reader has reported (its status ok) since it started */
    @Volatile var healthy = false; private set
    /** guests whose delivery an app-started leave stopped until their next hold */
    private val stopped = HashSet<String>()
    private var repeating: Pair<Reg, String>? = null

    fun register(r: Reg) { regs[r.id] = r; ensure() }
    fun unregister(id: String) { regs.remove(id) }

    /** is [id] getting direct presses now (MainActivity drops its relayed presses while true) */
    fun active(id: String): Boolean {
        val r = regs[id] ?: return false
        return healthy && id !in stopped && r.active()
    }

    /** the app leaves [id]'s room by itself (B, the disc, another room): no more direct presses until its next hold ([resume]) */
    fun stop(id: String) { stopped += id; if (repeating?.first?.id == id) cancelRepeat() }
    fun resume(id: String) { stopped -= id }

    /** the reader runs while the app is in front; [start] / [stop] follow the Activity */
    fun start() { ensure() }
    fun shutdown() { reader?.stop(); reader = null; healthy = false; cancelRepeat() }

    private fun ensure() {
        if (reader != null || regs.isEmpty()) return
        reader = PadInput(
            onPad = { lt, rt, lb, rb -> for ((k, down) in DirectPadKeys.padEdges(pads, lt, rt, lb, rb)) key(k, down)
                pads[0] = lt; pads[1] = rt; pads[2] = lb; pads[3] = rb },
            onStatus = { ok, m -> healthy = ok; if (!ok) Log.i(TAG, "reader: $m") },
            onButtons = { h -> val before = mask; mask = h; for ((k, down) in DirectPadKeys.edges(before, h)) key(k, down) },
        ).also { it.start() }
        healthy = true
    }

    /** b98: a press NO registered room takes right now (Home opens its bar on the D-pad's Down at once with it); main thread */
    var unclaimed: ((k: String, down: Boolean) -> Unit)? = null

    /** b98: a shoulder or trigger is held now (the game's router then gives the D-pad to its own override bars, not to Home's bar) */
    fun sideHeld(): Boolean = pads.any { it }

    private fun key(k: String, down: Boolean) {
        val r = regs.values.firstOrNull { k in it.keys && active(it.id) } ?: run {
            if (!down) stopRepeat(k)
            if (healthy) unclaimed?.invoke(k, down)
            return
        }
        if (down) {
            r.onKey(k, true, false)
            if (k in r.repeat) startRepeat(r, k)
        } else {
            stopRepeat(k)
            if (r.wantsRelease) r.onKey(k, false, false)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val (r, k) = repeating ?: return
            if (!active(r.id)) { repeating = null; return }
            r.onKey(k, true, true)
            main.postDelayed(this, DirectPadKeys.REPEAT_MS)
        }
    }

    private fun startRepeat(r: Reg, k: String) { cancelRepeat(); repeating = r to k; main.postDelayed(tick, DirectPadKeys.REPEAT_FIRST_MS) }
    private fun stopRepeat(k: String) { if (repeating?.second == k) cancelRepeat() }
    private fun cancelRepeat() { repeating = null; main.removeCallbacks(tick) }
}
