package com.jrees.wowcompanion

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * READ-ONLY controller reader for the Cast room's layer switching.
 *
 * The game swaps its action bar the instant LT / RT / LB / RB is held. Routing that through the
 * addon log would arrive late and cost two log flushes per press, so the app watches the Thor's
 * own controller instead: one persistent root `getevent -l`, parsed line by line.
 *
 * SECURITY LOCK: this class only ever READS. getevent opens the nodes read-only and never grabs
 * them (no EVIOCGRAB), so the game still receives every input untouched. Nothing here writes to
 * a device node or injects anything, and nothing read here may ever cause a keystroke -- it only
 * decides which layer the bottom screen DRAWS.
 *
 * getevent sets stdout unbuffered (`setbuf(stdout, NULL)` in toolbox/getevent.c), so lines arrive
 * as events happen even though our end is a pipe, not a pty.
 *
 * Only nodes named "Odin Controller" count. The Thor exposes two (event9 and event11 at the
 * time of writing); their states are OR-ed. Mapping (ASSUMED, calibrate on the device):
 *   LB = BTN_TL, RB = BTN_TR, LT = BTN_TL2 or ABS_BRAKE, RT = BTN_TR2 or ABS_GAS.
 * An analog trigger counts as held above 35% of its range (and lets go below 30%).
 * [triggerSwap] exchanges LT and RT for a device where the assumption is backwards.
 *
 * It also reports the D-pad and the four face buttons as PHYSICAL POSITIONS (slots
 * [SLOT_DPAD_LEFT]..[SLOT_FACE_BOTTOM], indexed like the replica's pad slots) so the Cast room can
 * draw its own button under the player's thumb as pressed. Display only, like everything here.
 * The code -> position table is [KEY_SLOT]; the one unconfirmed guess in it is [FACE_307_IS_LEFT].
 * D-pad: BTN_DPAD_* keys and ABS_HAT0X/Y are both read and OR-ed.
 *
 * Lifecycle: [start] when the Cast room becomes visible, [stop] when it is hidden. The root
 * getevent's life is tied to OUR end of its stdin: the root script runs a watchdog `cat` on that
 * stdin and kills getevent when it sees EOF. EOF comes from stop() closing the stream, and just as
 * well from the app dying (crash, force-stop, LMK, reinstall), where no code of ours runs. stop()
 * also kills getevent by pid as a second line, only while that pid still IS a getevent.
 * [start], [stop] and [triggerSwap] are main-thread calls; listener and status calls are always
 * delivered on the main thread.
 */
/**
 * ONE SHARED READER (user 2026-10-06, the root-toast fix: "one su, not one per view"). Every view keeps using [PadInput]
 * exactly as before (same constructor, start / stop, buttons / isHeld, the SLOT_x constants), but an instance is now only a
 * SUBSCRIPTION: [PadHub] runs ONE [PadReader] (one su + one getevent) while at least one subscription is started, and
 * hands every subscriber the same events. A new subscriber gets the CURRENT state at once (layers, held buttons, both
 * sticks, the last status), so a view that starts late still sees a button that is already held. The reader stops 1.5 s
 * after the last subscriber leaves (Home's lend / give-back must not restart su). Main thread throughout.
 */
class PadInput(
    private val onPad: (lt: Boolean, rt: Boolean, lb: Boolean, rb: Boolean) -> Unit,
    private val onStatus: (ok: Boolean, message: String) -> Unit,
    /** D-pad / face held state changed: bit (1 shl SLOT_x) set = held. Main thread. */
    private val onButtons: (held: Int) -> Unit = {},
    /** the RIGHT stick: -1 at rest, else 0..7 (0 = up, clockwise), past half deflection. Changes only. Main thread. */
    private val onStick: (sector: Int) -> Unit = {},
    /** the LEFT stick, the same way. Main thread. */
    private val onLeftStick: (sector: Int) -> Unit = {},
    /**
     * Either stick as a VECTOR (Home's radial: its own dead-zone and hysteresis): [stick] 0 = left, 1 = right; x and y in
     * -1..1, x RIGHT positive, y DOWN positive (evdev's own direction: pushing up gives a negative y). The node pushed
     * furthest decides, as for the sectors. Delivered on a change of more than 0.02 on either axis (and on a return to 0).
     * Main thread.
     */
    private val onStickVector: (stick: Int, x: Float, y: Float) -> Unit = { _, _, _ -> },
) {
    /** exchange LT and RT for THIS subscriber (CastSettings.triggerSwap, now fixed false). Main thread. */
    var triggerSwap = false
        set(v) { if (field != v) { field = v; if (active) pad(PadHub.lt, PadHub.rt, PadHub.lb, PadHub.rb) } }

    private var active = false

    /** D-pad / face slots held right now (0 while stopped). Main thread. */
    val buttons: Int get() = if (active) PadHub.buttons else 0

    /** true while the physical button at [slot] (SLOT_x) is held. Main thread. */
    fun isHeld(slot: Int): Boolean = isHeld(buttons, slot)

    fun start() {
        if (active) return
        active = true
        PadHub.add(this)
    }

    fun stop() {
        if (!active) return
        active = false
        PadHub.remove(this)
        // this view falls back to rest at once, as before
        onPad(false, false, false, false); onButtons(0); onStick(-1); onLeftStick(-1)
        onStickVector(0, 0f, 0f); onStickVector(1, 0f, 0f)
    }

    internal fun pad(lt: Boolean, rt: Boolean, lb: Boolean, rb: Boolean) {
        if (triggerSwap) onPad(rt, lt, lb, rb) else onPad(lt, rt, lb, rb)
    }
    internal fun status(ok: Boolean, message: String) = onStatus(ok, message)
    internal fun held(mask: Int) = onButtons(mask)
    internal fun stick(sector: Int) = onStick(sector)
    internal fun leftStick(sector: Int) = onLeftStick(sector)
    internal fun vector(stick: Int, x: Float, y: Float) = onStickVector(stick, x, y)

    companion object {
        const val CONTROLLER_NAME = PadReader.CONTROLLER_NAME
        const val SLOT_DPAD_LEFT = PadReader.SLOT_DPAD_LEFT
        const val SLOT_DPAD_UP = PadReader.SLOT_DPAD_UP
        const val SLOT_DPAD_RIGHT = PadReader.SLOT_DPAD_RIGHT
        const val SLOT_DPAD_DOWN = PadReader.SLOT_DPAD_DOWN
        const val SLOT_FACE_LEFT = PadReader.SLOT_FACE_LEFT
        const val SLOT_FACE_TOP = PadReader.SLOT_FACE_TOP
        const val SLOT_FACE_RIGHT = PadReader.SLOT_FACE_RIGHT
        const val SLOT_FACE_BOTTOM = PadReader.SLOT_FACE_BOTTOM
        const val SLOT_START = PadReader.SLOT_START
        const val SLOT_SELECT = PadReader.SLOT_SELECT
        const val SLOT_LSTICK = PadReader.SLOT_LSTICK
        const val SLOT_RSTICK = PadReader.SLOT_RSTICK
        const val SLOT_COUNT = PadReader.SLOT_COUNT
        const val FACE_307_IS_LEFT = PadReader.FACE_307_IS_LEFT
        fun isHeld(mask: Int, slot: Int): Boolean = PadReader.isHeld(mask, slot)
    }
}

/** The one reader behind every [PadInput]; main thread only. */
internal object PadHub {
    private val subs = ArrayList<PadInput>()
    private val main = Handler(Looper.getMainLooper())
    var lt = false; private set
    var rt = false; private set
    var lb = false; private set
    var rb = false; private set
    var buttons = 0; private set
    private var stick = -1
    private var leftStick = -1
    private val vec = FloatArray(4)                     // left x, y, right x, y
    private var status: Pair<Boolean, String>? = null

    private val reader = PadReader(
        onPad = { a, b, c, d -> lt = a; rt = b; lb = c; rb = d; for (s in subs.toList()) s.pad(a, b, c, d) },
        onStatus = { ok, m -> status = ok to m; for (s in subs.toList()) s.status(ok, m) },
        onButtons = { h -> buttons = h; for (s in subs.toList()) s.held(h) },
        onStick = { v -> stick = v; for (s in subs.toList()) s.stick(v) },
        onLeftStick = { v -> leftStick = v; for (s in subs.toList()) s.leftStick(v) },
        onStickVector = { st, x, y -> vec[st * 2] = x; vec[st * 2 + 1] = y; for (s in subs.toList()) s.vector(st, x, y) },
    )
    private var readerOn = false

    // THE LAUNCHER'S COPY (PadFeed, the private fork's 3c): the same deliveries the reader makes, when there is no reader (no root)
    fun feedPad(a: Boolean, b: Boolean, c: Boolean, d: Boolean) { lt = a; rt = b; lb = c; rb = d; for (s in subs.toList()) s.pad(a, b, c, d) }
    fun feedButtons(h: Int) { buttons = h; for (s in subs.toList()) s.held(h) }
    fun feedStick(v: Int) { stick = v; for (s in subs.toList()) s.stick(v) }
    fun feedLeftStick(v: Int) { leftStick = v; for (s in subs.toList()) s.leftStick(v) }
    fun feedVector(st: Int, x: Float, y: Float) { vec[st * 2] = x; vec[st * 2 + 1] = y; for (s in subs.toList()) s.vector(st, x, y) }

    private val stopLater = Runnable { if (subs.isEmpty() && readerOn) { readerOn = false; reader.stop(); status = null } }

    fun add(p: PadInput) {
        main.removeCallbacks(stopLater)
        if (p !in subs) subs.add(p)
        // no root (inside the launcher): no reader; PadFeed delivers instead
        if (RootPolicy.allowed && (!readerOn || !reader.isRunning)) { readerOn = true; restart() }
        // the state right now, so a late starter sees what is already held
        status?.let { p.status(it.first, it.second) }
        p.pad(lt, rt, lb, rb)
        p.held(buttons)
        p.stick(stick); p.leftStick(leftStick)
        p.vector(0, vec[0], vec[1]); p.vector(1, vec[2], vec[3])
    }

    /** (re)start the one reader; the remembered state is dropped first, so nothing stale outlives a reader */
    private fun restart() {
        lt = false; rt = false; lb = false; rb = false; buttons = 0; stick = -1; leftStick = -1; vec.fill(0f)
        lastRestartMs = android.os.SystemClock.elapsedRealtime()
        reader.start()
    }
    private var lastRestartMs = 0L

    /** a reader that died while views still listen comes back, at most once per 30 s (a failing su must not spam requests) */
    private val revive = Runnable {
        if (subs.isNotEmpty() && readerOn && !reader.isRunning) {
            for (s in subs.toList()) {
                s.pad(false, false, false, false); s.held(0); s.stick(-1); s.leftStick(-1); s.vector(0, 0f, 0f); s.vector(1, 0f, 0f)
            }
            restart()
        }
    }
    fun readerStopped() {
        main.removeCallbacks(revive)
        val wait = (REVIVE_MS - (android.os.SystemClock.elapsedRealtime() - lastRestartMs)).coerceAtLeast(1000L)
        main.postDelayed(revive, wait)
    }

    fun remove(p: PadInput) {
        subs.remove(p)
        if (subs.isEmpty()) { main.removeCallbacks(stopLater); main.postDelayed(stopLater, STOP_GRACE_MS) }
    }

    private const val STOP_GRACE_MS = 1500L
    private const val REVIVE_MS = 30_000L
}

/** The getevent reader itself (formerly PadInput). Used only by [PadHub]. */
internal class PadReader(
    private val onPad: (lt: Boolean, rt: Boolean, lb: Boolean, rb: Boolean) -> Unit,
    private val onStatus: (ok: Boolean, message: String) -> Unit,
    /** D-pad / face held state changed: bit (1 shl SLOT_x) set = held. Main thread. */
    private val onButtons: (held: Int) -> Unit = {},
    /**
     * The RIGHT stick as the game's radial reads it: -1 at rest, else 0..7 = the eighth it points at,
     * 0 at the top, clockwise (flyoutradial.lua). Changes only. Main thread.
     */
    private val onStick: (sector: Int) -> Unit = {},
    /**
     * The LEFT stick, the same way as [onStick]: -1 at rest, else 0..7 = the eighth it points at past half deflection,
     * 0 at the top, clockwise. Changes only. Main thread. (Home's consumables radial aims with both sticks.)
     */
    private val onLeftStick: (sector: Int) -> Unit = {},
    /** either stick as a vector: 0 left / 1 right, x right +, y DOWN + (evdev), -1..1; changes > 0.02. Main thread. */
    private val onStickVector: (stick: Int, x: Float, y: Float) -> Unit = { _, _, _ -> },
) {
    /** exchange LT and RT (CastSettings.triggerSwap). Main thread. */
    var triggerSwap = false
        set(v) {
            if (field == v) return
            field = v
            swapVolatile = v
            // re-deliver what is held now, seen through the new mapping
            if (running) deliver(delivered and 2 != 0, delivered and 1 != 0,
                delivered and 4 != 0, delivered and 8 != 0)
        }
    @Volatile private var swapVolatile = false

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    @Volatile private var running = false
    @Volatile private var generation = 0

    /** true while a reader process is wanted and alive (false after it died by itself) */
    val isRunning: Boolean get() = running
    private var proc: Process? = null      // guarded by lock
    private var gpid = -1                  // guarded by lock: the root getevent's pid, once reported

    private var delivered = 0              // main thread: last bits handed to onPad
    private var deliveredButtons = 0       // main thread: last slot mask handed to onButtons
    private var lastStatus: String? = null // main thread

    /** D-pad / face slots held right now, as last delivered to onButtons. Main thread. */
    val buttons: Int get() = deliveredButtons

    /** true while the physical button at [slot] (SLOT_x) is held. Main thread. */
    fun isHeld(slot: Int): Boolean = isHeld(deliveredButtons, slot)

    fun start() {
        if (running) return
        running = true
        val run = Run(++generation)
        Thread({ run.work() }, "padinput").apply { isDaemon = true; start() }
    }

    fun stop() {
        if (!running) return
        running = false
        generation++                         // lines still in flight from the old reader are now stale
        val p: Process?; val id: Int
        synchronized(lock) { p = proc; id = gpid; proc = null; gpid = -1 }
        // killing needs a root shell; never block the main thread on it. If the pid is not known
        // yet, closing the stdin alone ends getevent (the root watchdog), whenever it starts.
        Thread({ kill(id, p) }, "padinput-kill").apply { isDaemon = true; start() }
        deliver(false, false, false, false)  // fall back to layer n at once
        deliverButtons(0)                    // and let every drawn button up
        onStick(-1)
        onLeftStick(-1)
        onStickVector(0, 0f, 0f); onStickVector(1, 0f, 0f)
    }

    private fun deliver(lt: Boolean, rt: Boolean, lb: Boolean, rb: Boolean) {
        delivered = bits(lt, rt, lb, rb)
        onPad(lt, rt, lb, rb)
    }

    private fun deliverButtons(held: Int) {
        deliveredButtons = held
        onButtons(held)
    }

    private fun bits(lt: Boolean, rt: Boolean, lb: Boolean, rb: Boolean) =
        (if (lt) 1 else 0) or (if (rt) 2 else 0) or (if (lb) 4 else 0) or (if (rb) 8 else 0)

    private fun status(gen: Int, ok: Boolean, message: String) {
        main.post {
            if (gen != generation) return@post
            val key = "$ok|$message"
            if (key == lastStatus) return@post
            lastStatus = key
            if (!ok) Log.w(TAG, message)
            onStatus(ok, message)
        }
    }

    /**
     * End the root getevent (it runs as root, so our own destroy() cannot reach it).
     * 1. Close its stdin: the root watchdog sees EOF and kills it (works whatever the timing).
     * 2. Kill by pid, only while /proc says that pid is still a getevent (never a recycled pid).
     */
    private fun kill(id: Int, p: Process?) {
        try { p?.outputStream?.close() } catch (_: Throwable) {}
        if (id > 0) {
            try {
                val k = ProcessBuilder("su", "-c",
                    "grep -q getevent /proc/$id/cmdline 2>/dev/null && kill $id")
                    .redirectErrorStream(true).start()
                k.outputStream.close()
                k.inputStream.use { it.readBytes() }
                k.waitFor(3, TimeUnit.SECONDS)
                k.destroy()
            } catch (t: Throwable) {
                Log.w(TAG, "kill $id failed", t)
            }
        }
        try { p?.destroy() } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------------ one reader process

    private class Axis { var min = 0; var max = Int.MIN_VALUE; var seen = 0; var held = false }

    private class Node {
        var ltBtn = false; var rtBtn = false; var lb = false; var rb = false
        val brake = Axis(); val gas = Axis()
        var keys = 0     // slot mask from EV_KEY (BTN_DPAD_* and the face buttons)
        var seenDown = 0 // keys seen pressed since this reader started (a release without one = held from before)
        var hat = 0      // slot mask from ABS_HAT0X / ABS_HAT0Y (D-pad slots only)
        var rx = 0; var ry = 0   // right stick (ABS_Z / ABS_RZ, +-32767)
        var lx = 0; var ly = 0   // left stick (ABS_X / ABS_Y, +-32767)
    }

    /** Everything one getevent process needs, owned by its own reader thread. */
    private inner class Run(val gen: Int) {
        private val nodes = HashMap<String, Node>()                  // Odin Controller nodes only
        private val ranges = HashMap<String, Pair<Int, Int>>()       // "path|ABS_x" -> (min, max)
        private var lastBits = -1
        private var lastHeld = -1
        private var lastSector = -2
        private var lastLeftSector = -2
        private val lastVec = floatArrayOf(Float.NaN, Float.NaN, Float.NaN, Float.NaN)
        private val live get() = gen == generation && running

        fun work() {
            if (!RootPolicy.allowed) return          // the fork: no getevent (no root there); its controller comes from the launcher
            var startedAt = 0L
            var gotHello = false
            var p: Process? = null
            try {
                // The script is an ARGUMENT, so our stdin stays free as the watchdog's lifeline.
                // -lp lists every node's name and axis ranges and exits; the persistent -l reader
                // then runs in the background (its stdin is /dev/null), and a watchdog `cat` reads
                // OUR stdin (saved as fd 3) and kills it at EOF: stop() closing the stream, or the
                // app dying. If getevent ends by itself, the watchdog is killed instead and the
                // shell exits, so we see EOF as before.
                val sh = ProcessBuilder("su", "-c", SCRIPT).redirectErrorStream(true).start()
                p = sh
                synchronized(lock) {
                    if (!live) { sh.destroy(); return }   // closes the stdin: the watchdog ends it
                    proc = sh
                }
                val r = BufferedReader(InputStreamReader(sh.inputStream), 8192)
                var probing = true
                var cur: String? = null
                while (true) {
                    val line = r.readLine() ?: break
                    if (!live) break
                    if (!gotHello) {
                        if (line.startsWith("WCPAD ")) {
                            gotHello = true               // su worked
                            startedAt = System.currentTimeMillis()
                        }
                        continue
                    }
                    if (line.startsWith("WCPADG ")) {
                        val id = line.substring(7).trim().toIntOrNull() ?: -1
                        // stop() already ran: it closed the stdin, so the root side is ending
                        // getevent by itself; nothing to record
                        val gone = synchronized(lock) { if (live) { gpid = id; false } else true }
                        if (gone) break
                        continue
                    }
                    if (probing) {
                        if (line.startsWith("WCPAD_END")) {
                            probing = false
                            if (nodes.isEmpty()) status(gen, false, "no $CONTROLLER_NAME node")
                            else status(gen, true, "pad ${nodes.keys.sorted().joinToString(",")}")
                            emit()                        // the seeded state (a held direction / stick) at once
                            continue
                        }
                        cur = device(line, cur)
                        axisRange(line, cur)
                        continue
                    }
                    if (line.startsWith("/dev/input/")) event(line)
                    else cur = device(line, cur)
                }
            } catch (t: Throwable) {
                if (live) Log.w(TAG, "getevent reader failed", t)
            } finally {
                try { p?.inputStream?.close() } catch (_: Throwable) {}
                try { p?.outputStream?.close() } catch (_: Throwable) {}
            }

            // ---- the stream ended. If stop() ended it, stop() owns the kill. Otherwise getevent
            // itself exited: EOF on its stdout means the process is gone, so do NOT kill by pid
            // (the number may already belong to something else). Closing the stdin above
            // released the root side's watchdog.
            if (!live) return
            synchronized(lock) { proc = null; gpid = -1 }
            try { p?.destroy() } catch (_: Throwable) {}
            main.post {
                if (gen == generation && running) { deliver(false, false, false, false); deliverButtons(0) }
            }
            val ranFor = if (startedAt == 0L) 0L else System.currentTimeMillis() - startedAt
            when {
                !gotHello -> status(gen, false, "su failed")
                ranFor >= RESTART_AFTER_MS -> main.postDelayed({
                    // it had been working: a transient death, so read again
                    if (running && gen == generation) { running = false; start() }
                }, 1000)
                else -> status(gen, false, "getevent ended")
            }
            // A reader that died fast (or never got su) must not leave running = true: every later start()
            // would be a no-op and the dead reader would stay dead silently (Home, 2026-09-29; the band
            // layouts never draw the pad-off mark). The owner's next start() (attach / shown again) heals it.
            if (!gotHello || ranFor < RESTART_AFTER_MS) main.post {
                if (gen == generation && running) { running = false; Log.w(TAG, "reader died (gen $gen)"); PadHub.readerStopped() }
            }
        }

        /** "add device N: /dev/input/eventX", its "  name: "..."" line, and "remove device" */
        private fun device(line: String, cur: String?): String? {
            if (line.startsWith("add device")) {
                val i = line.indexOf(": ")
                return if (i > 0) line.substring(i + 2).trim() else cur
            }
            if (line.startsWith("add device") || line.startsWith("remove device")) Log.d(TAG, line.trim())
            if (line.startsWith("remove device")) {
                val i = line.indexOf(": ")
                if (i > 0 && nodes.remove(line.substring(i + 2).trim()) != null) emit()
                return cur
            }
            val t = line.trimStart()
            if (cur != null && t.startsWith("name:")) {
                val q1 = t.indexOf('"'); val q2 = t.lastIndexOf('"')
                val name = if (q1 >= 0 && q2 > q1) t.substring(q1 + 1, q2) else t.substring(5).trim()
                if (name == CONTROLLER_NAME && cur !in nodes) {
                    val n = Node()
                    ranges["$cur|ABS_BRAKE"]?.let { n.brake.min = it.first; n.brake.max = it.second }
                    ranges["$cur|ABS_GAS"]?.let { n.gas.min = it.first; n.gas.max = it.second }
                    nodes[cur] = n
                }
            }
            return cur
        }

        /** -p lines like "    ABS (0003): ABS_BRAKE  : value 0, min 0, max 255, fuzz 0, ..." */
        private fun axisRange(line: String, cur: String?) {
            if (cur == null) return
            // THE STATE AT START (Home's radial, 2026-10-06): -lp prints each axis's CURRENT value, so a D-pad direction or a
            // stick already held when the reader starts is known at once (its release then arrives as a normal change).
            // Keys (BTN_DPAD_*) are not listed by -lp (no EVIOCGKEY from the shell): a D-pad that reports as keys only is
            // seen from its next event.
            nodes[cur]?.let { n ->
                val v = numberAfter(line, "value ")
                if (v != null) when {
                    line.contains("ABS_HAT0X") -> hat(n, v, SLOT_DPAD_LEFT, SLOT_DPAD_RIGHT)
                    line.contains("ABS_HAT0Y") -> { if (v != 0) Log.d(TAG, "seed hatY $v $cur"); hat(n, v, SLOT_DPAD_UP, SLOT_DPAD_DOWN) }
                    line.contains("ABS_RZ") -> n.ry = v
                    line.contains("ABS_Z ") || line.contains("ABS_Z:") -> n.rx = v
                    line.contains("ABS_X ") || line.contains("ABS_X:") -> n.lx = v
                    line.contains("ABS_Y ") || line.contains("ABS_Y:") -> n.ly = v
                }
            }
            val brake = when {
                line.contains("ABS_BRAKE") -> true
                line.contains("ABS_GAS") -> false
                else -> return
            }
            val min = numberAfter(line, "min ") ?: return
            val max = numberAfter(line, "max ") ?: return
            ranges[if (brake) "$cur|ABS_BRAKE" else "$cur|ABS_GAS"] = min to max
            nodes[cur]?.let { n ->
                val a = if (brake) n.brake else n.gas
                a.min = min; a.max = max
            }
        }

        /**
         * "/dev/input/event9: EV_KEY       BTN_TL2              DOWN" / "... EV_ABS ABS_BRAKE 00004e20".
         * getevent -l prints a raw "%04x" type/code when it has no label, so those are accepted too.
         */
        private fun event(line: String) {
            val colon = line.indexOf(':')
            if (colon <= 0) return
            val node = line.substring(0, colon)
            val n = nodes[node] ?: return
            val parts = line.substring(colon + 1).trim().split(WS)
            if (parts.size < 3) return
            val type = parts[0]; val code = parts[1]; val value = parts[2]
            if (type == "EV_SYN" || type == "0000") {
                // SYN_DROPPED: the kernel's queue for this reader overflowed (say the reader stalled
                // while the sticks flooded ABS lines), so events are lost, maybe an UP. getevent
                // cannot re-read the state (no EVIOCGKEY), so let every control of this node go;
                // the next press brings it back. Display only: erring toward released is safe.
                if (code == "SYN_DROPPED" || code == "0003") { Log.d(TAG, "SYN_DROPPED $node"); release(n); emit() }
                return
            }
            var changed = false
            if (type == "EV_KEY" || type == "0001") {
                val down = keyDown(value)
                when (code) {
                    "BTN_TL", "0136" -> { changed = n.lb != down; n.lb = down }
                    "BTN_TR", "0137" -> { changed = n.rb != down; n.rb = down }
                    "BTN_TL2", "0138" -> { changed = n.ltBtn != down; n.ltBtn = down }
                    "BTN_TR2", "0139" -> { changed = n.rtBtn != down; n.rtBtn = down }
                    else -> {
                        val slot = KEY_SLOT[code] ?: KEY_SLOT[code.lowercase()] ?: return
                        val bit = 1 shl slot
                        if (slot <= SLOT_DPAD_DOWN) Log.d(TAG, "dpad key $code ${if (down) "down" else "up"} $node")
                        // a release of a key never seen DOWN since this reader started: it was held from before the start
                        // (-lp cannot list key states). Report it as held, then released: one change down, one up.
                        if (!down && (n.seenDown and bit) == 0 && (n.keys and bit) == 0) {
                            n.seenDown = n.seenDown or bit
                            n.keys = n.keys or bit; emit()
                            n.keys = n.keys and bit.inv(); emit()
                            return
                        }
                        if (down) n.seenDown = n.seenDown or bit
                        val k = if (down) n.keys or bit else n.keys and bit.inv()
                        changed = k != n.keys
                        n.keys = k
                    }
                }
            } else if (type == "EV_ABS" || type == "0003") {
                val v = value.toLongOrNull(16)?.toInt() ?: return   // %08x of an int: two's complement
                changed = when (code) {
                    "ABS_BRAKE", "000a" -> analog(n.brake, v)
                    "ABS_GAS", "0009" -> analog(n.gas, v)
                    "ABS_HAT0X", "0010" -> { Log.d(TAG, "hatX $v $node"); hat(n, v, SLOT_DPAD_LEFT, SLOT_DPAD_RIGHT) }
                    "ABS_HAT0Y", "0011" -> { Log.d(TAG, "hatY $v $node"); hat(n, v, SLOT_DPAD_UP, SLOT_DPAD_DOWN) }
                    "ABS_X", "0000" -> { val c = n.lx != v; n.lx = v; c }
                    "ABS_Y", "0001" -> { val c = n.ly != v; n.ly = v; c }
                    "ABS_Z", "0002" -> { val c = n.rx != v; n.rx = v; c }
                    "ABS_RZ", "0005" -> { val c = n.ry != v; n.ry = v; c }
                    else -> return
                }
            }
            if (changed) emit()
        }

        /** one stick as a -1..1 vector (the node pushed furthest), posted when it moved more than [VEC_EPS] or came back to 0 */
        private fun vectorOf(stick: Int, axes: (Node) -> Pair<Int, Int>) {
            var sx = 0; var sy = 0; var best = -1L
            for (n in nodes.values) {
                val (x, y) = axes(n)
                val m = x.toLong() * x + y.toLong() * y
                if (m > best) { best = m; sx = x; sy = y }
            }
            val x = (sx / 32767f).coerceIn(-1f, 1f); val y = (sy / 32767f).coerceIn(-1f, 1f)
            val px = lastVec[stick * 2]; val py = lastVec[stick * 2 + 1]
            val home = x == 0f && y == 0f && (px != 0f || py != 0f)
            if (!home && !px.isNaN() && kotlin.math.abs(x - px) <= VEC_EPS && kotlin.math.abs(y - py) <= VEC_EPS) return
            lastVec[stick * 2] = x; lastVec[stick * 2 + 1] = y
            main.post { if (gen == generation && running) onStickVector(stick, x, y) }
        }

        /** one stick's eighth over every node: -1 under half deflection, else 0 at the top, clockwise (evdev y grows down) */
        private fun sectorOf(axes: (Node) -> Pair<Int, Int>): Int {
            var sx = 0; var sy = 0; var best = 0L
            for (n in nodes.values) {
                val (x, y) = axes(n)
                val m = x.toLong() * x + y.toLong() * y
                if (m > best) { best = m; sx = x; sy = y }
            }
            if (best < STICK_MIN_SQ) return -1
            val deg = Math.toDegrees(kotlin.math.atan2(sx.toDouble(), -sy.toDouble()))
            return (((deg + 360.0 + 22.5) % 360.0) / 45.0).toInt().coerceIn(0, 7)
        }

        /** every control of [n] let go (after SYN_DROPPED); the axis ranges are kept */
        private fun release(n: Node) {
            n.ltBtn = false; n.rtBtn = false; n.lb = false; n.rb = false
            n.brake.held = false; n.gas.held = false
            n.keys = 0; n.hat = 0; n.rx = 0; n.ry = 0; n.lx = 0; n.ly = 0
        }

        /** "DOWN" / "REPEAT" = held, "UP" = not; a raw "%08x" value counts as held when non-zero */
        private fun keyDown(value: String): Boolean = when (value) {
            "UP" -> false
            "DOWN", "REPEAT" -> true
            else -> (value.toLongOrNull(16) ?: 1L) != 0L
        }

        /**
         * One hat axis: negative = [neg] (left / up), positive = [pos] (right / down), 0 lets both
         * of this axis go. True when the node's hat mask changed.
         */
        private fun hat(n: Node, v: Int, neg: Int, pos: Int): Boolean {
            val h = (n.hat and ((1 shl neg) or (1 shl pos)).inv()) or when {
                v < 0 -> 1 shl neg
                v > 0 -> 1 shl pos
                else -> 0
            }
            if (h == n.hat) return false
            n.hat = h
            return true
        }

        /** true when the held state flipped */
        private fun analog(a: Axis, v: Int): Boolean {
            if (v > a.seen) a.seen = v
            val max = if (a.max > a.min) a.max else guessMax(a.seen)
            val span = (max - a.min).coerceAtLeast(1)
            val frac = (v - a.min).toFloat() / span
            val held = if (a.held) frac > RELEASE_FRAC else frac > PRESS_FRAC
            if (held == a.held) return false
            a.held = held
            return true
        }

        private fun emit() {
            if (!live) return
            var lt = false; var rt = false; var lb = false; var rb = false
            var held = 0
            for (n in nodes.values) {
                lt = lt || n.ltBtn || n.brake.held
                rt = rt || n.rtBtn || n.gas.held
                lb = lb || n.lb
                rb = rb || n.rb
                held = held or n.keys or n.hat
            }
            if (swapVolatile) { val t = lt; lt = rt; rt = t }
            val b = bits(lt, rt, lb, rb)
            if (b != lastBits) {
                lastBits = b
                main.post { if (gen == generation && running && b != delivered) deliver(lt, rt, lb, rb) }
            }
            // each stick: the node pushed furthest decides; half deflection to count (the radial's flick)
            val sector = sectorOf { n -> n.rx to n.ry }
            if (sector != lastSector) {
                lastSector = sector
                main.post { if (gen == generation && running) onStick(sector) }
            }
            val left = sectorOf { n -> n.lx to n.ly }
            if (left != lastLeftSector) {
                lastLeftSector = left
                main.post { if (gen == generation && running) onLeftStick(left) }
            }
            vectorOf(0) { n -> n.lx to n.ly }
            vectorOf(1) { n -> n.rx to n.ry }
            if (held != lastHeld) {
                lastHeld = held
                main.post {
                    if (gen == generation && running && held != deliveredButtons) deliverButtons(held)
                }
            }
        }
    }

    companion object {
        /** half of the stick's 32767 range, squared: the radial counts a flick past half way */
        private const val STICK_MIN_SQ = 16384L * 16384L
        private const val VEC_EPS = 0.02f
        private const val TAG = "PadInput"
        const val CONTROLLER_NAME = "Odin Controller"
        private const val PRESS_FRAC = 0.35f
        private const val RELEASE_FRAC = 0.30f
        private const val RESTART_AFTER_MS = 5000L
        private val WS = Regex("\\s+")

        // ---- physical D-pad / face positions: bit (1 shl SLOT_x) of onButtons' mask. Indexed like
        // the Cast replica's pad slots (0..3 D-pad, 4..7 face). Positions, never button names.
        const val SLOT_DPAD_LEFT = 0
        const val SLOT_DPAD_UP = 1
        const val SLOT_DPAD_RIGHT = 2
        const val SLOT_DPAD_DOWN = 3
        const val SLOT_FACE_LEFT = 4
        const val SLOT_FACE_TOP = 5
        const val SLOT_FACE_RIGHT = 6
        const val SLOT_FACE_BOTTOM = 7
        // the menu keys and stick clicks (Home's DirectPad, 2026-10-06: every key a capture can relay); HOME is the system's
        const val SLOT_START = 8                 // BTN_START: the game's PADFORWARD
        const val SLOT_SELECT = 9                // BTN_SELECT: PADBACK
        const val SLOT_LSTICK = 10               // BTN_THUMBL: PADLSTICK
        const val SLOT_RSTICK = 11               // BTN_THUMBR: PADRSTICK
        const val SLOT_COUNT = 12

        /** true when [slot] (SLOT_x) is set in an onButtons [mask] */
        fun isHeld(mask: Int, slot: Int): Boolean =
            slot in 0 until SLOT_COUNT && (mask shr slot) and 1 != 0

        /**
         * THE ONE UNCONFIRMED GUESS in [KEY_SLOT]: which of codes 307 / 308 is the LEFT face button.
         * The Odin Controller reports XBOX POSITIONS (user-confirmed: 304 = bottom, 305 = right),
         * whatever the Thor prints on the caps. For 307 / 308 the kernel's aliases contradict that
         * layout (307 is named BTN_NORTH, yet an Xbox X sits on the LEFT: the old xpad mapping), so
         * follow Android's Generic.kl instead: 307 = BUTTON_X (Xbox: left), 308 = BUTTON_Y (Xbox:
         * top). If holding the top face button lights the left slot on the device, flip this.
         */
        const val FACE_307_IS_LEFT = true

        /**
         * getevent -l code -> slot, for EV_KEY. getevent prints a code by any one of its aliases
         * (whichever its label table lists first; the Thor's shows BTN_GAMEPAD / BTN_EAST /
         * BTN_NORTH / BTN_WEST), or as raw lowercase "%04x" when it has no label, so all are keys.
         * The D-pad also arrives as ABS_HAT0X / ABS_HAT0Y (see Run.hat). Not mapped: 306 BTN_C,
         * 309 BTN_Z, MODE (HOME, the system's). SELECT / START / THUMBL / THUMBR are slots 8-11 (2026-10-06).
         *
         *   code   aliases                          slot
         *   0x130  BTN_GAMEPAD BTN_SOUTH BTN_A      face bottom
         *   0x131  BTN_EAST BTN_B                   face right
         *   0x133  BTN_NORTH BTN_X                  face left  (top when !FACE_307_IS_LEFT)
         *   0x134  BTN_WEST BTN_Y                   face top   (left when !FACE_307_IS_LEFT)
         *   0x220  BTN_DPAD_UP                      d-pad up
         *   0x221  BTN_DPAD_DOWN                    d-pad down
         *   0x222  BTN_DPAD_LEFT                    d-pad left
         *   0x223  BTN_DPAD_RIGHT                   d-pad right
         */
        private val KEY_SLOT: Map<String, Int> = HashMap<String, Int>().apply {
            fun map(slot: Int, vararg keys: String) { for (k in keys) put(k, slot) }
            val slot307 = if (FACE_307_IS_LEFT) SLOT_FACE_LEFT else SLOT_FACE_TOP
            val slot308 = if (FACE_307_IS_LEFT) SLOT_FACE_TOP else SLOT_FACE_LEFT
            map(SLOT_FACE_BOTTOM, "BTN_GAMEPAD", "BTN_SOUTH", "BTN_A", "0130")
            map(SLOT_FACE_RIGHT, "BTN_EAST", "BTN_B", "0131")
            map(slot307, "BTN_NORTH", "BTN_X", "0133")
            map(slot308, "BTN_WEST", "BTN_Y", "0134")
            map(SLOT_DPAD_UP, "BTN_DPAD_UP", "0220")
            map(SLOT_DPAD_DOWN, "BTN_DPAD_DOWN", "0221")
            map(SLOT_DPAD_LEFT, "BTN_DPAD_LEFT", "0222")
            map(SLOT_DPAD_RIGHT, "BTN_DPAD_RIGHT", "0223")
            map(SLOT_SELECT, "BTN_SELECT", "013a")
            map(SLOT_START, "BTN_START", "013b")
            map(SLOT_LSTICK, "BTN_THUMBL", "013d")
            map(SLOT_RSTICK, "BTN_THUMBR", "013e")
        }

        /**
         * The root side, run by `su -c` (mksh). `exec 3<&0` keeps OUR stdin as fd 3, because a
         * background command's stdin is /dev/null unless redirected. g = getevent's own pid (a
         * simple background command is forked and exec'd, so $! is getevent itself). w = the
         * watchdog: `cat` blocks on our stdin, and at EOF it kills g. If getevent exits by itself,
         * `wait` returns and the shell kills the watchdog before it could ever kill a dead pid;
         * its `cat` (output /dev/null) ends when we close the stream after EOF.
         */
        private const val SCRIPT = "exec 3<&0; " +
            "echo WCPAD \$\$; " +
            "getevent -lp 2>/dev/null; " +
            "echo WCPAD_END; " +
            "getevent -l 2>/dev/null & g=\$!; " +
            "echo WCPADG \$g; " +
            "{ cat >/dev/null; kill \$g 2>/dev/null; } <&3 >/dev/null 2>&1 & w=\$!; " +
            "wait \$g; " +
            "kill \$w 2>/dev/null; " +
            "echo WCPAD_EXIT"

        private fun numberAfter(s: String, key: String): Int? {
            val i = s.indexOf(key)
            if (i < 0) return null
            var j = i + key.length
            val start = j
            if (j < s.length && s[j] == '-') j++
            while (j < s.length && s[j].isDigit()) j++
            return s.substring(start, j).toIntOrNull()
        }

        /** axis range unknown (-p did not list it): the smallest common range covering what we saw */
        private fun guessMax(seen: Int): Int = when {
            seen <= 255 -> 255
            seen <= 1023 -> 1023
            seen <= 4095 -> 4095
            seen <= 32767 -> 32767
            else -> 65535
        }
    }
}
