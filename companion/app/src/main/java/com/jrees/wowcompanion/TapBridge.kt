package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import org.json.JSONObject
import java.io.DataOutputStream
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The INPUT half of the bridge: the app talks to the addon with ONE TAP on the top screen.
 *
 * An addon cannot receive anything from outside the game (no sockets, no file reads, no clipboard
 * read), and typed chat is broken on this device: Android's keyboard system swallows the characters
 * once the game's edit box has focus. So the addon (TapPad.lua) owns a grid of invisible buttons and
 * publishes where it is; a tap there arrives in the game as a mouse click (GameNative touchscreen
 * mode), and the button's OnClick runs. PROVEN on the Thor: 69 ms, exact to the pixel, no chat box,
 * the controller stays in play mode.
 *
 * THE GRID IS PUBLISHED IN THE GAME'S OWN PIXELS, NOT THE DISPLAY'S. The game renders at 960x540
 * here and GameNative scales it 2x onto the 1920x1080 panel, so every coordinate is converted with
 * the pixel size the addon reports alongside it -- never a constant, because a resolution or UI
 * scale change moves the whole grid and the addon re-publishes it.
 *
 * One serial worker, so two rooms' taps can never interleave into a sequence. A call is one tap, or
 * ([taps]) the few taps that make up ONE game action: a no-action pick and then the action, as the
 * Thor lock allows ("one touch = one game action; a no-action pick tap may come first").
 */
object TapBridge {
    /** the L1 key channel: OFF since the Alt+F4 closes (2026-10-09), see the batch send below */
    private const val KEYS_ON = false

    // ---------------------------------------------------------------- L1's handshake + sweep (TapPad pad10c)
    /** this game session's key token, from a CURRENT "padkeytest" answer to our click on keytest's cell 0; null = no keys */
    @Volatile private var keySession: String? = null
    @Volatile private var askedAt = 0L

    /** CompanionHost routes every "padkeytest" document here. Only a CURRENT handshake answer counts (a replayed one proves nothing) */
    fun onKeyTest(msg: JSONObject, current: Boolean) {
        if (!current || !msg.optBoolean("on", false)) return
        val s = msg.optString("s", "")
        keySession = if (msg.optBoolean("bound", false) && msg.optString("mods") == TapKeys.MODS && s.isNotEmpty()) s else null
        Log.i(TAG, "keys: handshake ${keySession ?: "refused"} (rows ${msg.optInt("rows")}, cols ${msg.optInt("cols")})")
    }

    private fun askHandshake(ctx: Context, pad: JSONObject?) {
        val now = SystemClock.uptimeMillis()
        if (now - askedAt < 10_000L) return
        askedAt = now
        taps(ctx, pad, listOf("keytest" to 0))       // a CLICK: keytest's cell 0 does nothing in the game but answer
    }

    /**
     * THE SWEEP (the probe, on the user's word; works with KEYS_ON off): inside the launcher only. A click on keytest's cell 0 (the
     * handshake, which also starts the add-on's 30 s trace), then -- once THIS session's answer is in (up to 3 s) -- every row key
     * alone and keytest's row key with each column key, [gapMs] apart. Nothing in the game reacts; the add-on reports each key it
     * heard ("padkeytest"), so a lost key, or one with a side effect, shows on its own. [onDone] gets a one-line outcome.
     */
    fun keySweep(ctx: Context, pad: JSONObject?, gapMs: Long = 300L, onDone: (String) -> Unit = {}) {
        if (InputSink.sink == null) { onDone("not inside the launcher: no sweep"); return }
        if (TapKeys.session(pad) == null) { onDone("the add-on publishes no keys (pad10c not loaded or nothing bound)"); return }
        keySession = null
        askedAt = SystemClock.uptimeMillis()
        if (!taps(ctx, pad, listOf("keytest" to 0))) { onDone("the handshake click was refused"); return }
        worker.execute {
            val until = SystemClock.uptimeMillis() + 3_000L
            while (keySession == null && SystemClock.uptimeMillis() < until) Thread.sleep(50)
            val script = TapKeys.sweep(pad, gapMs, keySession)
            if (script == null) { onDone("no handshake from this session (or the document's token is stale): no keys sent"); return@execute }
            Log.i(TAG, "keys: sweep of ${script.lines().count { it.startsWith("wckeys") }} keys, ${gapMs} ms apart")
            RootShell.run(script) { r -> onDone(if (r.exit == 0) "sweep sent" else "sweep refused (exit ${r.exit})") }
        }
    }
    private const val TAG = "TapBridge"
    private val worker = Executors.newSingleThreadExecutor()
    private var display0: Point? = null      // only ever holds a VALIDATED size

    /**
     * The top screen's size in the pixels `input -d 0 tap` uses, or null when it cannot be trusted.
     *
     * NEVER `getRealSize` / `getRealMetrics` on DEFAULT_DISPLAY. On the Thor that answer is
     * NON-DETERMINISTIC: the app lives on display 4, and the call intermittently returns the BOTTOM
     * screen's 1240x1080. It used to be asked once and cached, so one unlucky app start aimed EVERY tap of
     * EVERY room at x * 1240/1920, about 130 game px left of the grid. That is what happened all morning
     * on 2026-09-28; the mouse diagnostic proved it to the half-pixel (taps at game x 234 / 238.5 for cells
     * at 363 / 369, heights exact). The dual-screen crop project hit the same quirk and fixed it this way:
     * read the display's stable PHYSICAL MODE (the panel itself, not a per-app adjusted metric) and turn
     * it landscape. On the Thor display 0's mode is 1080x1920, used as 1920x1080.
     */
    private fun gameDisplay(ctx: Context): Point? {
        display0?.let { return it }
        val p = try {
            val dm = ctx.getSystemService(DisplayManager::class.java) ?: return null
            val mode = dm.getDisplay(Display.DEFAULT_DISPLAY)?.mode ?: return null
            val w = maxOf(mode.physicalWidth, mode.physicalHeight)
            val h = minOf(mode.physicalWidth, mode.physicalHeight)
            // the top screen is the Thor's largest panel: anything smaller is the wrong display
            val largest = dm.displays.maxOfOrNull { it.mode.physicalWidth.toLong() * it.mode.physicalHeight } ?: 0L
            if (w <= 0 || h <= 0 || w.toLong() * h < largest) null else Point(w, h)
        } catch (t: Throwable) {
            Log.w(TAG, "display 0 size unreadable: ${t.message}")
            null
        }
        if (p != null) {
            display0 = p
            Log.i(TAG, "display 0 = ${p.x}x${p.y} (physical mode, landscape)")
        }
        return p
    }

    /** display-pixel centre of cell [op, col], or null when the pad does not describe it */
    fun cellCentre(ctx: Context, pad: JSONObject?, op: String, col: Int): Point? {
        if (pad == null) return null
        val rows = pad.optJSONObject("rows") ?: return null
        if (!rows.has(op)) return null
        val row = rows.optInt(op, -1)
        val pw = pad.optDouble("pw", 0.0); val ph = pad.optDouble("ph", 0.0)
        val cw = pad.optDouble("cw", 0.0); val ch = pad.optDouble("ch", 0.0)
        if (row < 0 || pw <= 0 || ph <= 0 || cw <= 0 || ch <= 0) return null
        if (col < 0 || col >= pad.optInt("cols", 0)) return null
        val gx = pad.optDouble("x", 0.0) + (col + 0.5) * cw
        val gy = pad.optDouble("y", 0.0) + (row + 0.5) * ch
        val d = gameDisplay(ctx) ?: run { Log.w(TAG, "refused: no trusted display size"); return null }
        // The game fills the top screen (960x540 scaled 2x), so both must have the SAME SHAPE. A mismatch
        // means the size is wrong -- the old bug gave 1240x1080 (1.15) against the game's 1.78 -- and a tap
        // would land on the game world instead of the grid. Refuse rather than misfire.
        if (abs(d.x.toDouble() / d.y - pw / ph) > 0.02) {
            Log.w(TAG, "refused: display ${d.x}x${d.y} does not match the game's ${pw.toInt()}x${ph.toInt()}")
            return null
        }
        return Point((gx * d.x / pw).roundToInt(), (gy * d.y / ph).roundToInt())
    }

    /**
     * One tap on cell [op, col]. Returns false (and taps nothing) when the cell is unknown or the game is
     * not provably live (see [taps]).
     */
    fun tap(ctx: Context, pad: JSONObject?, op: String, col: Int, onFail: (String) -> Unit = {}): Boolean {
        return taps(ctx, pad, listOf(op to col), onFail)
    }

    /**
     * The taps of ONE game action, in order, in one shell session: nothing else this worker sends can
     * land between them. ALL OR NOTHING: if any cell is unknown, nothing is tapped and this returns false.
     *
     * NOTHING IS TAPPED UNLESS THE GAME IS PROVABLY LIVE: the same gate as GameInput ([InputGate]). On an
     * app start LogTail replays up to 8 MB of old documents, and a room that acts on one would tap the game
     * with no touch at all. Home's audit proved it for Professions (2026-09-30 14:55:53, AGENT_LOG 9596d1a).
     * The refusal is logged and given to [onFail] at once, on the caller's thread.
     *
     * AND NOTHING IS TAPPED WHILE THE CONTROLLER IS BUSY ([TapQuiet], step 0 of DirectPad): the batch waits on this object's one
     * worker until no non-D-pad button is held and none has changed for 30 ms (capped), then goes to the root
     * shell. One worker, so batches keep the order they were asked in.
     */
    fun taps(ctx: Context, pad: JSONObject?, cells: List<Pair<String, Int>>, onFail: (String) -> Unit = {}): Boolean =
        send(ctx, pad, cells, 0L, onFail)

    /**
     * [taps] with [gapMs] between the batch's taps (Map's pin digits, 2026-10-07): a pad cell swallows a second press within its
     * 0.25 s repeat guard (TapPad REPEAT, the tap's own second press), so a sequence that may tap the SAME cell two taps apart
     * needs the taps spaced: with 120 ms, two taps apart is >= 0.3 s. Still one batch: nothing else lands between them.
     */
    fun tapsSpaced(ctx: Context, pad: JSONObject?, cells: List<Pair<String, Int>>, gapMs: Long, onFail: (String) -> Unit = {}): Boolean =
        send(ctx, pad, cells, gapMs.coerceIn(0L, 1000L), onFail)

    private fun send(ctx: Context, pad: JSONObject?, cells: List<Pair<String, Int>>, gapMs: Long, onFail: (String) -> Unit): Boolean {
        if (cells.isEmpty()) return false
        val now = System.currentTimeMillis()
        if (!GameInput.live(now)) {
            val why = "${GameInput.gate.why(now) ?: "The game is not live"}: nothing was sent"
            Log.w(TAG, "refused ${cells.joinToString { "${it.first}:${it.second}" }}: $why")
            onFail(why)
            return false
        }
        // L1, THE KEY CHANNEL (the user's pick 2026-10-09; TapPad pad10 + [TapKeys]): inside the DS Forever launcher (InputSink's X
        // server carries Ctrl / Alt / Shift; adb's keys lost them), a batch whose cells ALL have keys goes as keys AT ONCE: a key binding
        // never meets the cursor the game parks while a face button is held, so there is no quiet-pad wait. Not while a text box has
        // the keyboard (bindings do not fire there: GameInput.typing). Anything else stays clicks. The same worker keeps the order.
        // OFF (2026-10-09): the first keyed action, Ctrl+Alt+Shift+F4, was ALT+F4 and closed the game at every world entry. Keys stay
        // off until Quests' new alphabet (no Alt with an F-key, no system chords) passes a probe; a replayed pad with keys can't send them
        // pad10c: CTRL-SHIFT only (no system chord, ever) and only after THIS session's handshake (TapKeys: a key sent unbound would
        // fall back to the player's plain binding). Still behind KEYS_ON: the sweep (keySweep) goes first, on the user's word.
        val inLauncher = InputSink.sink != null
        val keyScript = if (KEYS_ON && inLauncher && !GameInput.typing) TapKeys.script(pad, cells, gapMs, keySession) else null
        if (keyScript != null) {
            val what = cells.joinToString(" ") { "${it.first}:${it.second}" }
            worker.execute {
                Log.i(TAG, "quiet pad: $what keys (no wait)")
                RootShell.run(keyScript) { r ->
                    if (r.exit != 0) {
                        keySession = null                   // a new handshake before keys again
                        Log.w(TAG, "keys refused (exit ${r.exit}): $what")
                        // ONE line refused = nothing was pressed (InputSink checks the whole line first): the same action as clicks
                        if (!keyScript.contains('\n')) send(ctx, pad, cells, gapMs, onFail) else onFail("keys were refused (exit ${r.exit})")
                    }
                }
            }
            return true
        }
        // keys published for this batch but no handshake in this session yet: ask for one (a CLICK, no game action), at most every 10 s
        if (KEYS_ON && inLauncher && !GameInput.typing && TapKeys.session(pad) != null && TapKeys.session(pad) != keySession) askHandshake(ctx, pad)
        // why a batch whose rows publish keys still went as clicks (only said when keys exist), so one log read shows a stuck reason
        val keysOff = if (pad?.optJSONObject("keys") != null) when {
            !KEYS_ON -> "off"; !inLauncher -> "no launcher"; GameInput.typing -> "typing"
            TapKeys.session(pad) != keySession -> "no handshake"; else -> "a cell without keys" } else null
        val pts = cells.map { (op, col) -> cellCentre(ctx, pad, op, col) ?: return false }
        // -d 0: the top screen. The companion holds focus on display 4, and a bare input command would deliver to itself.
        // ONE root shell for every tap (RootShell, 2026-10-06: a new su per tap was a Magisk toast per tap); the taps of one action
        // are one batch, so nothing else lands between them
        // and a short rest after the action: the game takes one touch per frame, and two actions' taps close together lost one
        // (b61: a wheel's hold right behind another tap never reached the game)
        val gap = if (gapMs > 0L) "\nsleep ${"%.3f".format(java.util.Locale.ROOT, gapMs / 1000.0)}\n" else "\n"
        val script = pts.joinToString(gap) { c -> "input -d 0 tap ${c.x} ${c.y}" } + "\nsleep 0.08"
        val queuedAt = SystemClock.uptimeMillis()
        watchPad()
        // b90 (23:00:18 padstray, Home's focus): a press handled INSIDE the controller reader's own dispatch (DirectPad: Home's map
        // focus, a room's A) queues its tap before this object's own subscriber has heard that press; the worker then read a stale
        // "nothing held" and tapped with A down. The reader's state is current here (PadHub sets it before calling anyone)
        if (Looper.myLooper() == Looper.getMainLooper()) syncPad(queuedAt)
        val heldAtQueue = heldNow
        val what = cells.joinToString(" ") { "${it.first}:${it.second}" }
        worker.execute {
            val waited = settle(queuedAt)
            // one line per batch (Quests, after the 23:00 focus padstray): which cells, how long the quiet pad held them, and whether
            // a button was down when they were asked for, so a stray or a missing answer can be traced from ONE log read
            Log.i(TAG, "quiet pad: $what waited $waited ms (held at queue $heldAtQueue)" + (keysOff?.let { " keys off: $it" } ?: ""))
            RootShell.run(script) { r ->
                if (r.exit != 0) onFail(if (r.exit == -1) "no root shell (refused, or it died)" else "tap was refused (exit ${r.exit})")
            }
        }
        return true
    }

    // ---------------------------------------------------------------- the quiet pad ([TapQuiet])
    // Its clock comes from the app's ONE controller reader (PadInput -> PadHub, read-only getevent; Cast's file), through a
    // subscriber of our own that stays on for the app's life (PadHub runs one reader for every subscriber, so this adds no second
    // getevent). Without a reader (no root, no controller) nothing is ever seen changing and taps go at once, as before.
    @Volatile private var lastChangeAt = 0L      // SystemClock.uptimeMillis() of the last button change (any button)
    @Volatile private var heldNow = false        // a non-D-pad button is held (face, Start / Select, ...)
    @Volatile private var watcher: PadInput? = null
    private var lastMask = 0
    private var lastSide = 0
    private val main = Handler(Looper.getMainLooper())
    private val DPAD_MASK = (1 shl PadInput.SLOT_DPAD_LEFT) or (1 shl PadInput.SLOT_DPAD_UP) or
        (1 shl PadInput.SLOT_DPAD_RIGHT) or (1 shl PadInput.SLOT_DPAD_DOWN)

    /** start the quiet pad's reader (PadHub is main-thread only); any thread, idempotent. MainActivity may call it at start. */
    fun watchPad() {
        if (watcher != null) return
        main.post {
            if (watcher != null) return@post
            watcher = PadInput(
                // the shoulders and triggers: a change counts (conservative); held ones do not hold taps back (not measured to park)
                onPad = { lt, rt, lb, rb ->
                    val m = (if (lt) 1 else 0) or (if (rt) 2 else 0) or (if (lb) 4 else 0) or (if (rb) 8 else 0)
                    if (m != lastSide) { lastSide = m; lastChangeAt = SystemClock.uptimeMillis() }
                },
                onStatus = { ok, m -> if (!ok) Log.i(TAG, "quiet pad: controller reader: $m") },
                onButtons = { h ->
                    if (h != lastMask) { lastMask = h; lastChangeAt = SystemClock.uptimeMillis() }
                    heldNow = h and DPAD_MASK.inv() != 0
                },
            ).also { it.start() }
        }
    }

    /**
     * main thread: take PadHub's state now (the press being handled may not have reached our own subscriber yet). The shoulders and
     * triggers too, as the watcher counts them: a change of theirs is a change (a room's LB / RB tap waits [TapQuiet.SETTLE_MS]).
     */
    private fun syncPad(now: Long) {
        val h = PadHub.buttons
        if (h != lastMask) { lastMask = h; lastChangeAt = now }
        heldNow = h and DPAD_MASK.inv() != 0
        val m = (if (PadHub.lt) 1 else 0) or (if (PadHub.rt) 2 else 0) or (if (PadHub.lb) 4 else 0) or (if (PadHub.rb) 8 else 0)
        if (m != lastSide) { lastSide = m; lastChangeAt = now }
    }

    /** on the worker: wait until [TapQuiet] says the pad is quiet (it caps every wait); returns the ms waited */
    private fun settle(queuedAt: Long): Long {
        var freeSince = queuedAt
        while (true) {
            val now = SystemClock.uptimeMillis()
            val held = heldNow
            if (held) freeSince = now
            val w = TapQuiet.wait(now, queuedAt, lastChangeAt, held, freeSince)
            if (w <= 0L) break
            try { Thread.sleep(w) } catch (_: InterruptedException) { break }
        }
        return SystemClock.uptimeMillis() - queuedAt
    }
}
