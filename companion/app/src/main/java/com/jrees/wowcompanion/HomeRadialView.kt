package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import org.json.JSONObject

/**
 * THE CONSUMABLES RADIAL ON SCREEN (D-pad UP; the user's design 2026-10-05, the look v4): a view over the whole bottom screen (the
 * room AND the bar). It opens with the Home disc rising out of the bar into the wheel's hub (UiKit.consumableRadial draws it all;
 * [Strip.homeLifted] hides the bar's own disc meanwhile) and closes the same way back.
 *
 * INPUT, all read here while open (its own PadInput: the right stick's eighth, the buttons): the stick points; releasing it on a slice
 * or A uses the item; B closes; LB / RB turn the page; the D-pad steps round the ring (the fallback if a stick also moves the
 * character). A finger works too (a slice uses it, the hub closes). A USE = ONE TAP on the item's cell in Spells' secure "radial" row
 * (the game allows an item only from a hardware event); the game's answer comes back as `radialuse`.
 *
 * THE CONTROLLER: while open, the radial holds the generic guest "radial" (so the game does not act on A / B / LB / RB) - once
 * Quests.lua and [GuestPad.IDS] carry it; until then it opens without a hold ([guestTap] gets null cells and taps nothing).
 */
class HomeRadialView(
    context: Context,
    private val strip: Strip,
    /** the quests pad (the guest cells live on its `qact` row) */
    private val questsPad: () -> JSONObject?,
    /** whatever taps a list of pad cells (MainActivity's TapBridge route) */
    private val guestTap: (List<Pair<String, Int>>?) -> Boolean,
) : View(context) {
    private var doc: RadialModel.Doc? = null
    private val flow = RadialModel.Flow()
    /** the open motion's start (uptime ms), or 0 when closed */
    private var openedAt = 0L
    /** a close running backward from this t (ms along the open motion), started at [closingAt] */
    private var closingFrom = -1f
    private var closingAt = 0L
    private var reader: PadInput? = null
    private var held = 0
    private var lb = false
    private var rb = false
    /** the reader's first report has come (its starting state) */
    private var seeded = false
    /**
     * THE KEYS THAT OPENED THE WHEEL, still held (a bit per PadInput slot). It was D-pad Up only - the consumables radial's key - so the
     * SPELL radial, opened by a command on any other button (Track C, the user's fresh-install test 2026-10-12: "holding the spell
     * radial bind doesnt actually open the spell radial menu"), found Up "already let go" at its first report and closed at once.
     * Now: whatever was held when the reader started is the hold; letting all of it go is the release.
     */
    private var openKeys = 0
    /** the cell of a use waiting for its `radialuse`, or -1 */
    private var pendingUse = -1
    private var tappedAt = 0L
    /** the last use's tap (uptime ms), fight or not: the free waits [FREE_AFTER_USE_MS] behind it */
    private var lastUseAt = 0L
    /**
     * [doc] came from the game in THIS app life (live, or replayed from the log). False = only the list kept from the last app life
     * (b65, 15:08: the install restarted the app, the game sends `radial` only when it changes and the log replay reached back ~3 min,
     * so every wheel opened EMPTY and the user's A did nothing). A kept list shows at once, but its cells may be stale (a /reload
     * rebuilds them), so a use on it waits for the game's own list ([waiting]).
     */
    private var trusted = false
    /** the radial on show: "radial" (consumables, D-pad Up) or "sradial" (spells, Spells' Hub button); each keeps its own list */
    private var source = "radial"
    private val docs = HashMap<String, RadialModel.Doc>()
    private val trustedOf = HashSet<String>()
    /** a use asked for on the kept list, waiting for the game's (the item, asked at uptime ms) */
    private var waiting: RadialModel.Act.Use? = null
    private var waitingAt = 0L
    /** B's press on the hub (uptime ms), 0 = none: the controller is let go at once, the close motion starts after the press */
    private var hubPressAt = 0L
    private val pressCurve = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)

    /**
     * PINNING (the user's pin sheet, signed off 2026-10-06; Spells r7 keeps the pins, first page only): the wheel's "+" opens Bags'
     * picker ([picker], MainActivity hangs it over the room), a chosen item comes back to the wheel as THE SLICE CHOOSER (it floats
     * over the hub; A on an empty slice pins it there, A HELD [PIN_HOLD_MS] on a pinned one replaces it; B goes back to the picker).
     * The guest "radial" holds the controller the whole way, so this view's reader drives the picker too.
     */
    var picker: BagsPinPicker? = null

    /**
     * f-radial-middle (1.0.9, the user: "The Dpad up radial, and spell radial should slideup between the middle of the modules like the
     * class bars used to"): on Home the wheel takes Home's middle slot (HomeView.wheelSlot: the columns part for it), in THIS view's
     * coordinates; null = no slot (another room, a module full screen): the full-screen wheel. Called with false when the wheel goes.
     */
    var homeSlot: ((Boolean) -> RectF?)? = null
    /**
     * f-radial-behind (the user: "the radial needs to look like its behind the Map and Chat modules, not slide up in front of it"): in
     * Home's slot the wheel itself is drawn by Home, UNDER its columns ([drawUnder], through HomeView.wheelPainter): true = start that,
     * false = stop it. [underRedraw] asks Home's layer for a frame. This view keeps the input, and draws on top the disc (the one on
     * screen: the bar's own is hidden meanwhile, f-one-disc), the chooser and the item's name band.
     */
    var under: ((Boolean) -> Unit)? = null
    /** test26: the wheel closed (any way but the app leaving): the host makes it final - the guest's B dropped, the bar's disc blue */
    var onClosed: (() -> Unit)? = null
    var underRedraw: (() -> Unit)? = null
    private var slot: RectF? = null
    /** the wheel sliding back down into the bar after it has gone (the controller already let go): since (uptime ms), in that slot */
    private var leaveAt = 0L
    private var leaveSlot: RectF? = null
    private var shutting = false
    private val leaveCurve = Motion.curve()

    /**
     * NO TAP RIGHT AFTER A CONTROLLER PRESS (b67, measured 15:27:17-15:27:33: all 5 pins' taps, 2 each, became pad8 `padstray`s, and
     * Bags' bmvq 23 at the "+" too). A controller press makes the game park its hidden cursor at the freelook point, where TapPad's
     * sink sits, and a tap that reaches the game in that moment lands there. Every one was sent a few ms after the A that asked for it,
     * because this view reads the controller itself, ~10 ms ahead of the game. The b65 losses fit: the use after A at 15:08:02 had no
     * `radialuse`, and the free after B at 15:09:07 went missing. Uses by letting the stick go (no button) never failed.
     * So every tap this view sends waits until no button has changed for [SETTLE_MS] ([MAX_SETTLE_MS] at most, for a mashed pad).
     */
    @Volatile private var lastPadAt = 0L
    private var rawHeld = 0

    private fun settled(run: () -> Unit) {
        val queuedAt = SystemClock.uptimeMillis()
        fun attempt() {
            val now = SystemClock.uptimeMillis()
            val quiet = now - lastPadAt
            // b70 15:46:33 / 15:46:40: a replace fired at the end of A's 1 s hold, A still DOWN, and both taps strayed: a held face
            // button parks the cursor too (the D-pad does not: Hold-mode uses with Up held always landed). So it also waits for the
            // buttons to be let go ([MAX_HELD_MS] at most)
            val buttonDown = rawHeld and DPAD_BITS.inv() != 0
            if (buttonDown && now - queuedAt < MAX_HELD_MS) postDelayed({ attempt() }, 30L)
            else if (quiet >= SETTLE_MS || now - queuedAt >= MAX_SETTLE_MS) run()
            else postDelayed({ attempt() }, SETTLE_MS - quiet)
        }
        attempt()
    }
    private enum class Mode { WHEEL, PICK, CHOOSE }
    private var mode = Mode.WHEEL
    /** the item being placed in the chooser */
    private class Choice(val id: Int, val name: String, val icon: Int)
    private var choice: Choice? = null
    /** A held on a pinned slice in the chooser: since (uptime ms), on which slice; 0 = not held */
    private var aHeldAt = 0L
    /** the hold filled: the replace is asked for and goes out on A's release (the fill stays full, the band says "Let go to replace") */
    private var holdFired = false
    private var aSlice = -1
    /** the band's second line in the chooser (a refusal, "Pinning ..."), null = the kit's own */
    private var pinNote: String? = null
    /** the pin / unpin sent and not answered yet: (op, slice, item), at uptime ms */
    private var pinWant: Triple<String, Int, Int>? = null
    private var pinSentAt = 0L
    private var freeTry = 0
    private var holdTry = 0
    @Volatile private var heldOk = false
    @Volatile private var freed = false

    private fun tapCell(row: String, col: Int) = TapBridge.tap(context, questsPad(), row, col) { why -> Log.i(TAG, "radial: tap refused ($why)") }

    val isOpen get() = openedAt != 0L

    private val prefs = context.getSharedPreferences("wc_radial", Context.MODE_PRIVATE)

    init {
        visibility = GONE; isClickable = true
        try { prefs.getString("doc", null)?.let { RadialModel.parse(JSONObject(it))?.let { d -> docs["radial"] = d } } } catch (_: Throwable) {}
        try { prefs.getString("sdoc", null)?.let { RadialModel.parse(JSONObject(it))?.let { d -> docs["sradial"] = d } } } catch (_: Throwable) {}
        doc = docs["radial"]
    }

    /**
     * THE KEPT LISTS ARE THE CHARACTER'S (the per-character ruling 2026-10-07; the user 2026-10-09: Shaman spells showed on another
     * character): the lists kept from the last app life carry the character they came from ("who", name-realm). [character] (a CURRENT
     * padmap names it) drops kept lists of another character - the game's own list for this one comes when it changes or the wheel asks.
     */
    private var who: String? = prefs.getString("who", null)

    /** the character now ("name-realm", from a current padmap); another character's kept lists go */
    fun character(key: String) {
        if (key == who) return
        Log.i(TAG, "radial: character $key (kept lists were ${who ?: "unknown"}'s): kept lists dropped")
        for (k in listOf("radial", "sradial")) if (k !in trustedOf) docs.remove(k)
        if (source !in trustedOf) { doc = docs[source]; if (isOpen) invalidate() }
        who = key
        prefs.edit().remove("doc").remove("sdoc").putString("who", key).apply()
        // what this app life already got from the game IS this character's: kept again under its name
        docsMsg.forEach { (k, m) -> prefs.edit().putString(if (k == "sradial") "sdoc" else "doc", m).apply() }
    }
    private val docsMsg = HashMap<String, String>()

    /** every `radial` document (MainActivity's route): the items now; an open wheel redraws */
    fun onDoc(msg: JSONObject) {
        val d = RadialModel.parse(msg) ?: return
        val kind = if (msg.optString("type") == "smartor") "smartor" else if (d.places != null) "sradial" else "radial"
        docs[kind] = d; trustedOf += kind
        if (kind == "smartor") { if (source == kind) { doc = d; trusted = true; if (isOpen) invalidate() }; return }   // never kept: the game's word is current
        docsMsg[kind] = msg.toString()
        prefs.edit().putString(if (kind == "sradial") "sdoc" else "doc", msg.toString()).apply()
        if (kind != source) return                                  // the other radial's list: kept for its next open
        doc = d; trusted = true
        flow.clamp(d)
        if (isOpen) invalidate()
        // a use asked for on the kept list: the SAME item, at its cell in the game's list now, or nothing
        waiting?.let { w ->
            waiting = null
            val now = d.items.firstOrNull { it.id == w.id }
            if (SystemClock.uptimeMillis() - waitingAt > WAIT_MS) Log.i(TAG, "radial: the game's list came too late: ${w.id} not used")
            else if (now?.col == null || !RadialModel.usable(d, now)) Log.i(TAG, "radial: ${w.id} is not in the game's list now: not used")
            else use(d, RadialModel.Act.Use(now.col, now.id))
        }
    }

    /** the game's word that a use landed (`radialuse`): logged; the wheel has already closed */
    fun onUse(msg: JSONObject) {
        // the first device runs measure the channel: how long from the tap to the game's word
        if (msg.optInt("col", -2) == pendingUse) { pendingUse = -1; Log.i(TAG, "radial: ack after ${SystemClock.uptimeMillis() - tappedAt} ms") }
        Log.i(TAG, "radial: used ${msg.optInt("id")} (cell ${msg.optInt("col")}, fight ${msg.optBoolean("fight")})") }

    /** D-pad UP (Hub's barcmd "radial"), or Spells' spell radial (barcmd "sradial"): open, or close when already open */
    fun toggle(src: String = "radial") { if (isOpen && closingFrom < 0f) close() else if (!isOpen) open(src) }

    fun open(src: String = "radial") {
        source = src; doc = docs[src]; trusted = src in trustedOf
        flow.reset(); heldOk = false; aimStick = -1; bnextHome = true
        // each wheel its own pair (f-radial-settings: the item wheel's, the spell wheel's)
        val spell = src == "sradial"
        flow.holdOpen = !(if (spell) HomeSettings.spellTap else HomeSettings.radialTap)
        flow.pickA = if (spell) HomeSettings.spellPickA else HomeSettings.radialPickA
        // m62: Smart buffs' OR wheel opens on a Y press and stays until a pick or B; A picks (one buff, cast on yourself)
        if (src == "smartor") { flow.holdOpen = false; flow.pickA = true }
        flow.armMs = RadialModel.ARM_MS; flow.openAt = SystemClock.uptimeMillis(); stickWait = false; removeCallbacks(stickBack)
        openedAt = SystemClock.uptimeMillis(); closingFrom = -1f
        leaveAt = 0L; leaveSlot = null
        slot = homeSlot?.invoke(true)
        if (slot != null) under?.invoke(true)
        held = 0; lb = false; rb = false; seeded = false; openKeys = 0
        // one disc on screen (the user: "only one place at once"): the bar's own goes while the wheel's hub is up
        strip.homeLifted = true
        visibility = VISIBLE
        reader?.stop(); reader = null; rawHeld = 0
        hold(0)
        reader = PadInput(
            onPad = { _, _, l, r -> pads(l, r) },
            // a reader that stops cannot see the stick come back: the wait ends at once (RC4: the wait is long now)
            onStatus = { ok, m -> if (!ok) { Log.i(TAG, "radial: controller reader: $m"); post { if (stickWait) stickBack.run() } } },
            onButtons = { h -> buttons(h) },
            // BOTH sticks aim (the user: "they can both aim"), through the dead-zone and the hysteresis (RadialModel.aimOf); the add-on's
            // frame holds the game's sticks still meanwhile (Spells). Only the stick that aims counts: the other one's rest is no release
            onStickVector = { st, x, y ->
                mag[st.coerceIn(0, 1)] = kotlin.math.hypot(x.toDouble(), y.toDouble())
                if (live()) vector(st, x.toDouble(), -y.toDouble()) else stickCheck()
            },
        ).also { it.start() }
        Log.i(TAG, "radial: open $source (${doc?.items?.size ?: 0} items, ${RadialModel.pages(doc)} page(s))")
        // test24 (the user: "nothing on press"): one WCLOG line per open with everything that decides whether it can be SEEN
        firstFrame = true
        Log.i("WCLOG", "radial: open (mode=${if (flow.holdOpen) "hold" else "tap"}, src=$source, items=${doc?.items?.size ?: 0}, " +
            "trusted=$trusted, view=${width}x$height shown=$isShown attached=$isAttachedToWindow, held=0x${rawHeld.toString(16)})")
        invalidate()
    }

    /** close with the motion run backward; the bar gets its disc back at the end */
    fun close() {
        if (!isOpen || closingFrom >= 0f || hubPressAt != 0L) return
        picker?.close(); mode = Mode.WHEEL
        stickLater()
        LatLog.mark("wheel", "radial gone")
        done()                       // GONE AT ONCE (the user 2026-10-09: B "INSTANTly"); the free follows invisibly
    }

    /**
     * B (or a finger on the hub): the hub plays the bar's Home press (the user, 2026-10-06: "Pushing B on that setting should also make
     * the middle of that radial menu play the back button push animation feedback"), then the wheel closes. The controller is let go
     * AT ONCE, not after the press: the game's sticks are not held for the animation.
     */
    private fun pressHub() {
        if (!live()) return
        // the fold starts AT the B, the press plays on the hub while it folds (b70, the user's third "B is STILL so slow": measured, the
        // press held the full wheel for 230 ms and it was gone only at ~450 ms; the game's release was never what the eye waited for)
        stickLater()
        LatLog.mark("wheel", "radial gone (B)")
        done()                       // GONE AT ONCE, no hub press / fold (the user 2026-10-09)
    }

    /** the hub's press depth now, 0..1 */
    private fun hubDown(): Float {
        if (hubPressAt == 0L) return 0f
        val dt = (SystemClock.uptimeMillis() - hubPressAt).toFloat()
        if (dt < UiKit.CR_PRESS_IN_MS) return dt / UiKit.CR_PRESS_IN_MS
        return 1f - pressCurve.getInterpolation(((dt - UiKit.CR_PRESS_IN_MS) / UiKit.CR_PRESS_OUT_MS).coerceIn(0f, 1f))
    }

    /**
     * THE RELEASE OF THE CONTROLLER, CONFIRMED (b59 13:49:08: the free went out right behind the item's tap and the game, one touch per
     * frame, lost it; the user's B then went to a hidden wheel for 37 s). Right after a use it waits [FREE_AFTER_USE_MS]; the game's
     * answer (guestack free) confirms it; none within [FREE_CHECK_MS] = sent again, twice at most.
     */
    private fun free(attempt: Int) {
        val mine = ++freeTry; freed = false
        settled { sendFree(mine, attempt) }
    }

    private fun sendFree(mine: Int, attempt: Int) {
        if (mine != freeTry || live()) return                     // reopened meanwhile: that open holds again
        // b65 15:08:02: the free went 14 ms behind the use's tap and was lost: it keeps [FREE_AFTER_USE_MS] behind the last use
        val rest = FREE_AFTER_USE_MS - (SystemClock.uptimeMillis() - lastUseAt)
        if (rest > 0) { postDelayed({ sendFree(mine, attempt) }, rest); return }
        Log.i(TAG, "radial: free${if (attempt > 0) " again ($attempt)" else ""} ${if (guestTap(GuestPad.cells(questsPad(), GUEST, hold = false))) "sent" else "NOT sent"}")
        if (!isOpen || closingFrom >= 0f || hubPressAt != 0L) { reader?.stop(); reader = null }
        postDelayed({ if (mine == freeTry && !freed && attempt < 4 && !live()) free(attempt + 1) }, FREE_CHECK_MS)   // 4 re-sends (b74: 3 ignored in a row)
    }

    /**
     * THE HOLD OF THE CONTROLLER, CONFIRMED (b61: three of the wheel's holds never reached the game, so its stick frame did not show and
     * the camera turned: "sometimes when pushing the stick ... the camera turns"). No "hold ok" within [HOLD_CHECK_MS] while the wheel
     * is open = sent again, twice at most.
     */
    private fun hold(attempt: Int) {
        heldOk = false
        val mine = ++holdTry
        settled {
            if (mine == holdTry && live()) {
                Log.i(TAG, "radial: hold${if (attempt > 0) " again ($attempt)" else ""} ${if (guestTap(GuestPad.cells(questsPad(), GUEST, hold = true))) "sent" else "NOT sent"}")
                postDelayed({ if (mine == holdTry && !heldOk && live() && attempt < 2) hold(attempt + 1) }, HOLD_CHECK_MS)
            }
        }
    }

    /** the game's answers for the guest "radial" (MainActivity's guestack route) */
    fun onGuestAck(msg: JSONObject) {
        if (msg.optString("op") == "hold" && msg.optBoolean("ok")) heldOk = true
        if (msg.optString("op") == "free" && msg.optBoolean("ok")) freed = true
        Log.i(TAG, "radial: guest ${msg.optString("op")} ${if (msg.optBoolean("ok")) "ok" else "refused (${msg.optString("why")})"}")
    }

    /** the app leaves the screen: shut at once (no motion), the reader and the hold go */
    fun shut() {
        if (!isOpen) return
        reader?.stop(); reader = null
        guestTap(GuestPad.cells(questsPad(), GUEST, hold = false))
        shutting = true; done(); shutting = false
        leaveAt = 0L; leaveSlot = null
    }

    private fun done() {
        if (openedAt != 0L) Log.i("WCLOG", "radial: close (src=$source, after ${SystemClock.uptimeMillis() - openedAt} ms, " +
            "drawn=${!firstFrame}, why=${Throwable().stackTrace.getOrNull(1)?.methodName})")
        val shown = strip.homeLifted || (slot != null && mode != Mode.PICK)     // the wheel itself on screen (not Bags' picker)
        if (openedAt != 0L && !shutting) onClosed?.invoke()
        picker?.close(); mode = Mode.WHEEL; choice = null; holdReset(); pinNote = null
        if (!stickWait && (freeTry == 0 || freed)) { reader?.stop(); reader = null }
        openedAt = 0L; closingFrom = -1f; hubPressAt = 0L
        val was = slot; slot = null
        if (was != null) homeSlot?.invoke(false)
        // f-radial-middle: from Home's slot the wheel slides back DOWN into the bar as the columns close over its place; input is
        // already Home's (the controller let go above), only the picture rides it out
        if (was != null && shown && !shutting && isShown) {
            leaveSlot = was; leaveAt = SystemClock.uptimeMillis(); invalidate(); underRedraw?.invoke(); return
        }
        if (was != null) under?.invoke(false)
        strip.homeLifted = false
        visibility = GONE
    }

    /** the wheel's slide back down from Home's slot into the bar's disc, fading as it lands ([LEAVE_MS]); [part] UiKit.CR_* */
    private fun drawLeave(c: Canvas, part: Int = UiKit.CR_TOP) {
        val s = leaveSlot ?: return
        val p = ((SystemClock.uptimeMillis() - leaveAt) / LEAVE_MS).coerceIn(0f, 1f)
        if (p >= 1f) {
            if (part != UiKit.CR_TOP) return                                // the overlay's frame ends it
            leaveSlot = null; leaveAt = 0L; strip.homeLifted = false; under?.invoke(false); visibility = GONE; return
        }
        if (part == UiKit.CR_TOP) underRedraw?.invoke()
        // THE ARRIVAL RUN BACKWARD (f-one-disc): the hub glides from the slot down onto the bar's Home disc, its size and its blue, the
        // wheel riding down with it under the columns; the name band goes first. The bar's own disc comes back when it has landed
        val screen = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val (disc, size) = discHere()
        val plus = if (RadialModel.plusOn(doc) && flow.page == 0) UiKit.CR_PLUS else -1
        UiKit.consumableRadial(c, screen, disc, size, itemsOf(RadialModel.slices(doc, flow.page)), flow.pointed, flow.page,
            RadialModel.pages(doc), UiKit.CR_OPEN_MS * (1f - leaveCurve.getInterpolation(p)), glyphs = flow.pickA, plusSlot = plus,
            slot = s, part = part)
        if (part == UiKit.CR_TOP) postInvalidateOnAnimation()
    }

    /** f-radial-behind: the wheel without its band, in THIS view's coordinates (Home's layer under its columns draws it) */
    fun drawUnder(c: Canvas) {
        if (!isOpen) { drawLeave(c, UiKit.CR_WHEEL); return }
        if (slot == null || mode == Mode.PICK) return
        paint(c, UiKit.CR_WHEEL)
    }
    /** f-settings-previews: the player's own wheel, page 1 (the Settings panel's picture) */
    fun previewItems(spell: Boolean): List<UiKit.RadialItem?> = itemsOf(RadialModel.slices(docs[if (spell) "sradial" else "radial"], 0))
    private fun itemsOf(src: List<RadialModel.Item?>) =
        src.map { it?.let { i -> UiKit.RadialItem(Icons.get(i.icon), i.name, i.count, pinned = i.pinned, ranOut = i.ranOut,
            noMana = source != "radial" && NoMana.has(i.id)) } }        // m71: a spell wheel's spell out of mana

    /** the stick goes back only from neutral, or [RadialModel.STICK_BACK_MS] after the close (the user 2026-10-09; the pause wheel's rule) */
    private val mag = DoubleArray(2)
    private var stickWait = false
    private val stickBack = Runnable { if (stickWait) { stickWait = false; Log.i(TAG, "radial: stick back (time)"); LatLog.mark("wheel", "radial stick back (time)"); free(0) } }
    private fun stickNeutral() = mag[0] < RadialModel.NEUTRAL && mag[1] < RadialModel.NEUTRAL
    private fun stickLater() {
        if (stickNeutral()) { free(0); return }
        stickWait = true
        postDelayed(stickBack, RadialModel.STICK_BACK_MS)
    }
    private fun stickCheck() {
        if (!stickWait || !stickNeutral()) return
        stickWait = false; removeCallbacks(stickBack)
        Log.i(TAG, "radial: stick back (neutral)")
        LatLog.mark("wheel", "radial stick back (neutral)")
        free(0)
    }

    private fun pads(l: Boolean, r: Boolean) {
        if (l && !lb && flow.turn(doc, -1)) invalidate()
        if (r && !rb && flow.turn(doc, +1)) invalidate()
        lb = l; rb = r
    }

    /** the stick that aims now (0 left, 1 right), or -1 */
    private var aimStick = -1

    private fun vector(st: Int, x: Double, y: Double) {
        if (mode == Mode.PICK) { pickStick(st, x, y); return }
        val m = kotlin.math.hypot(x, y)
        if (aimStick != st) {
            if (m < RadialModel.DEAD_IN) return                 // the other stick at rest, or barely moved: not this wheel's aim
            aimStick = st
        }
        val before = flow.pointed
        val a = flow.vector(doc, x, y, SystemClock.uptimeMillis())
        if (m < RadialModel.DEAD_OUT) aimStick = -1
        if (flow.pointed != before || a != RadialModel.Act.None) Log.i(TAG, "radial: ${if (st == 0) "left" else "right"} stick aims ${flow.pointed}")
        if (flow.pointed != aSlice && !holdFired) aHeldAt = 0L     // the hold to replace is on ONE slice
        if (mode == Mode.WHEEL) act(a)
        invalidate()
    }

    /** the wheel takes input only while open and not closing (b60: a B or A then the Up release in the next report used the item twice) */
    private fun live() = isOpen && closingFrom < 0f && hubPressAt == 0L

    private fun buttons(h: Int) {
        if (h != rawHeld) { rawHeld = h; lastPadAt = SystemClock.uptimeMillis() }
        if (!live()) return
        // the reader's FIRST report is the state it found (Cast's seeding): Up still held = the HOLD way may follow; nothing is a press
        if (!seeded) {
            seeded = true; held = h
            openKeys = h
            Log.i(TAG, "radial: reader start ($source), opening keys ${if (openKeys != 0) "held (0x${openKeys.toString(16)})" else "already let go"} " +
                "(${if (flow.tap) "TAP" else "HOLD"})")
            // HOLD: the press is already over (a tap) = nothing aimed = the wheel closes; TAP: the wheel stays
            if (openKeys == 0 && flow.holdOpen) close()
            return
        }
        val down = h and held.inv()
        held = h
        // the opening keys let go after they opened the wheel: the HOLD way uses the aimed item; a quick one makes it the TAP way
        if (openKeys != 0 && (h and openKeys) == 0) {
            openKeys = 0
            Log.i(TAG, "radial: opening keys let go (aimed ${flow.pointed})${if (flow.tap) ": TAP, the wheel stays" else ": the wheel closes"}")
            act(flow.upReleased()); invalidate(); return
        }
        fun pressed(slot: Int) = PadInput.isHeld(down, slot)
        if (mode == Mode.PICK) { pickButtons(::pressed); return }
        if (mode == Mode.CHOOSE) { chooseButtons(h, ::pressed); return }
        when {
            // Start closes the wheel (Quests q34 holds it for the guest "radial" now, so the game no longer hears it here)
            pressed(PadInput.SLOT_START) -> close()
            pressed(PadInput.SLOT_FACE_RIGHT) -> if (flow.b() == RadialModel.Act.Close) pressHub()
            pressed(PadInput.SLOT_FACE_BOTTOM) -> act(flow.a(doc))
            // TAP: Up pressed again closes (HOLD never sees an Up press while open: Up is the held key)
            !flow.holdOpen && pressed(PadInput.SLOT_DPAD_UP) -> act(flow.upPressed())
            pressed(PadInput.SLOT_DPAD_UP) || pressed(PadInput.SLOT_DPAD_RIGHT) -> { flow.step(+1); invalidate() }
            pressed(PadInput.SLOT_DPAD_DOWN) || pressed(PadInput.SLOT_DPAD_LEFT) -> { flow.step(-1); invalidate() }
        }
    }

    private fun act(a: RadialModel.Act) {
        when (a) {
            RadialModel.Act.None -> Unit
            RadialModel.Act.Close -> close()
            RadialModel.Act.Plus -> startPick()
            is RadialModel.Act.Use -> {
                val d = doc
                if (d != null && !trusted) {
                    waiting = a; waitingAt = SystemClock.uptimeMillis()
                    Log.i(TAG, "radial: use ${a.id} asked on the kept list: waiting for the game's")
                } else if (d != null) use(d, a)
                close()
            }
        }
    }

    /** ONE tap on the item's cell in [d] (the game's own list) */
    private fun use(d: RadialModel.Doc, a: RadialModel.Act.Use) {
        lastUseAt = SystemClock.uptimeMillis()                  // the free keeps its distance from the moment it is asked
        settled { useNow(d, a) }
    }

    private fun useNow(d: RadialModel.Doc, a: RadialModel.Act.Use) {
        val sent = tapCell(d.row, a.col)
        if (sent) lastUseAt = SystemClock.uptimeMillis()
        Log.i(TAG, "radial: use ${a.id} (cell ${a.col}) ${if (sent) "tapped" else "NOT tapped"}")
        // the cell acts on the tap's RELEASE, which a fight's freelook re-centring can swallow (Quests): at RETRY_MS, ONE more tap -
        // but only when it surely did not happen (RadialModel.shouldRetry: no ack, the item untouched in the latest document)
        val before = d.items.firstOrNull { it.id == a.id }
        // ONLY IN A FIGHT (b61, out of combat: the game's answer took > 1 s and a retry went out before it, which would have eaten a
        // second piece of food): the lost release is a fight's freelook problem (Quests); there the deadline is [RETRY_MS]
        if (sent && before != null && d.fight) {
            pendingUse = a.col; tappedAt = SystemClock.uptimeMillis()
            postDelayed({
                val acked = pendingUse != a.col
                if (!acked) pendingUse = -1
                if (RadialModel.shouldRetry(before, doc, acked))
                    Log.i(TAG, "radial: no sign of cell ${a.col}: one more tap ${if (tapCell(d.row, a.col)) "sent" else "NOT sent"}")
                else if (!acked) Log.i(TAG, "radial: no ack for cell ${a.col}, but the item changed: no retry")
            }, RETRY_MS)
        }
    }

    // ------------------------------------------------------------------ pinning

    /** the "+": the wheel gives way to Bags' picker (the guest still holds; this reader drives the picker) */
    /**
     * B IN-GAME (Quests q17, b74's "the close feels SUPER delayed": three release taps after a pin flow were ignored, the controller
     * held 2 s): for the guest "radial" a B while the capture's bnext is "home" releases the capture in the game at the B itself, no
     * tap. A fresh hold starts at "home" (the wheel and the picker: B closes everything); the slot CHOOSER's B means "back to the
     * picker", so its bnext is "back" while it shows. The app's own free after B stays as the backstop.
     */
    private var bnextHome = true

    private fun setBNext(home: Boolean) {
        if (bnextHome == home) return
        bnextHome = home
        Log.i(TAG, "radial: bnext ${if (home) "home" else "back"} ${if (guestTap(GuestPad.bnext(questsPad(), home))) "sent" else "NOT sent"}")
    }

    private fun startPick() {
        val p = picker ?: run { Log.i(TAG, "radial: no picker"); return }
        mode = Mode.PICK; choice = null; holdReset(); pinNote = null
        flow.tap = true                         // from here on it is the TAP way: a stick let go never uses
        strip.homeLifted = false                // the wheel is gone: the bar's disc is back under the picker
        settled { if (mode == Mode.PICK && isOpen) p.open(RadialModel.pinnedIds(doc), { id, action -> onPicked(id, action) }, { pickerB() }) }
        Log.i(TAG, "radial: + : the picker")
        invalidate()
    }

    private fun onPicked(id: Int, action: BagsPinPicker.PinAction) {
        val d = doc
        when (action) {
            BagsPinPicker.PinAction.UNPIN -> {
                val taps = RadialModel.unpinTaps(d, id)
                // no wait on its answer (the picker shows the pin gone at once; the answer is logged, the doc follows it)
                if (taps == null) Log.i(TAG, "radial: unpin $id NOT sent (not pinned, or no pin row)")
                else settled { Log.i(TAG, "radial: unpin $id ${if (TapBridge.taps(context, questsPad(), taps)) "sent" else "NOT sent"}") }
            }
            else -> {
                val c = d?.cand?.get(id)
                val known = c ?: d?.pins?.values?.firstOrNull { it.id == id }?.let { RadialModel.Cand(-1, it.name, it.icon) }
                    ?: d?.items?.firstOrNull { it.id == id }?.let { RadialModel.Cand(-1, it.name, it.icon) }
                choice = Choice(id, known?.name ?: "", known?.icon ?: 0)
                mode = Mode.CHOOSE
                setBNext(false)
                flow.reset(); flow.tap = true; aimStick = -1; holdReset()
                pinNote = if (c == null) "This item can't be pinned right now" else null
                // the wheel comes back with its open motion, the item over the hub
                openedAt = SystemClock.uptimeMillis(); strip.homeLifted = true
                Log.i(TAG, "radial: chooser for $id (${action.name.lowercase()}${if (c == null) ", NOT pinnable" else ""})")
                invalidate()
            }
        }
    }

    /**
     * The picker's B CLOSES THE WHOLE RADIAL at once (b68, the user: "pressing B feels like its waiting for a response from the game
     * instead of just closing the whole radial": B went back to the wheel, which replayed its opening, and a second B closed it).
     * Nothing waits on the game: the controller's release goes out behind it.
     */
    private fun pickerB() {
        if (!isOpen) return
        Log.i(TAG, "radial: B in the picker: everything closes")
        stickDir[0] = -1; stickDir[1] = -1
        free(0)
        done()
    }

    /** after a pin: back to the wheel (TAP way: it stays; A uses, B closes) */
    private fun backToWheel() {
        mode = Mode.WHEEL; choice = null; pinNote = null; holdReset()
        setBNext(true)
        flow.reset(); flow.tap = true; aimStick = -1
        openedAt = SystemClock.uptimeMillis(); strip.homeLifted = true
        invalidate()
    }

    /** the chooser's B (or a finger on its hub): back to the picker, the cursor where it was */
    private fun backToPicker() { pinWant = null; holdReset(); setBNext(true); startPick() }

    /** each stick's direction in the picker (0 up, 1 right, 2 down, 3 left; -1 = at rest) and the repeat's turn */
    private val stickDir = intArrayOf(-1, -1)
    private val stickTurn = intArrayOf(0, 0)

    /**
     * A STICK IN THE PICKER = the D-pad (b68, the user: "should let me use the control stick as well as the Dpad"): pushed past
     * [RadialModel.DEAD_IN] it steps once that way, held it repeats after [STICK_REPEAT_FIRST_MS], every [STICK_REPEAT_MS]; it rests
     * under [RadialModel.DEAD_OUT]. Four ways, the larger axis wins. [y] is up +.
     */
    private fun pickStick(st: Int, x: Double, y: Double) {
        if (st !in 0..1) return
        val m = kotlin.math.hypot(x, y)
        val dir = when {
            m < (if (stickDir[st] >= 0) RadialModel.DEAD_OUT else RadialModel.DEAD_IN) -> -1
            kotlin.math.abs(x) > kotlin.math.abs(y) -> if (x > 0) 1 else 3
            else -> if (y > 0) 0 else 2
        }
        if (dir == stickDir[st]) return
        stickDir[st] = dir
        val turn = ++stickTurn[st]
        if (dir < 0) return
        fun step(first: Boolean) {
            if (stickTurn[st] != turn || stickDir[st] != dir || mode != Mode.PICK || !isOpen) return
            picker?.press(arrayOf("PADDUP", "PADDRIGHT", "PADDDOWN", "PADDLEFT")[dir])
            postDelayed({ step(false) }, if (first) STICK_REPEAT_FIRST_MS else STICK_REPEAT_MS)
        }
        step(true)
    }

    private fun pickButtons(pressed: (Int) -> Boolean) {
        val p = picker ?: return
        val key = when {
            pressed(PadInput.SLOT_FACE_BOTTOM) -> "PAD1"
            pressed(PadInput.SLOT_FACE_RIGHT) -> "PAD2"
            pressed(PadInput.SLOT_DPAD_UP) -> "PADDUP"
            pressed(PadInput.SLOT_DPAD_DOWN) -> "PADDDOWN"
            pressed(PadInput.SLOT_DPAD_LEFT) -> "PADDLEFT"
            pressed(PadInput.SLOT_DPAD_RIGHT) -> "PADDRIGHT"
            else -> return
        }
        p.press(key)
    }

    private fun chooseButtons(h: Int, pressed: (Int) -> Boolean) {
        // A let go before the hold filled: nothing replaced
        if (aHeldAt != 0L && !holdFired && !PadInput.isHeld(h, PadInput.SLOT_FACE_BOTTOM)) { aHeldAt = 0L; invalidate() }
        when {
            pressed(PadInput.SLOT_FACE_RIGHT) -> backToPicker()
            pressed(PadInput.SLOT_FACE_BOTTOM) -> chooseA(flow.pointed)
            holdFired -> Unit                                    // the replace is on its way: nothing else meanwhile
            pressed(PadInput.SLOT_DPAD_UP) || pressed(PadInput.SLOT_DPAD_RIGHT) -> { flow.step(+1); aHeldAt = 0L; invalidate() }
            pressed(PadInput.SLOT_DPAD_DOWN) || pressed(PadInput.SLOT_DPAD_LEFT) -> { flow.step(-1); aHeldAt = 0L; invalidate() }
        }
    }

    /** A (or a finger) on slice [s] in the chooser: an empty slice (or the item's own) pins at once; a pinned one needs A held */
    private fun chooseA(s: Int) {
        val c = choice ?: return
        if (s !in 0 until RadialModel.PER_PAGE || s == RadialModel.PLUS || pinWant != null) return
        val there = doc?.pins?.get(s)
        if (there == null || there.id == c.id) pin(s) else { aHeldAt = SystemClock.uptimeMillis(); aSlice = s; holdFired = false; invalidate() }
    }

    private fun pin(s: Int) {
        val c = choice ?: return
        val taps = RadialModel.pinTaps(doc, s, c.id)
        if (taps == null) { holdReset(); pinNote = "This item can't be pinned right now"; invalidate(); return }
        pinWant = Triple("pin", s, c.id); pinSentAt = SystemClock.uptimeMillis()
        val mine = pinSentAt
        settled {
            if (pinWant == null || pinSentAt != mine) return@settled
            if (!TapBridge.taps(context, questsPad(), taps)) { pinWant = null; holdReset(); pinNote = "Nothing was sent: the game is not live"; invalidate(); return@settled }
            pinNote = "Pinning..."
            Log.i(TAG, "radial: pin ${c.id} to slice $s sent")
            invalidate()
            postDelayed({
                if (pinWant != null && pinSentAt == mine) { pinWant = null; holdReset(); pinNote = "No answer from the game: try again"; invalidate() }
            }, PIN_ACK_MS)
        }
        invalidate()
    }

    /** the game's answer to a pin / unpin (`radialpinack`, Spells r7); the new radial doc follows it at once */
    fun onPinAck(msg: JSONObject) {
        val op = msg.optString("op"); val ok = msg.optBoolean("ok")
        Log.i(TAG, "radial: pin ack $op ${if (ok) "ok" else "refused (${msg.optString("why")})"} slice ${msg.optInt("slice", -1)} id ${msg.optInt("id")}")
        if (pinWant == null) return                                      // an unpin's answer (nothing waits on it), or a late one
        if (op == "pick" && ok) return                                   // the first of the two taps; the answer of the second follows
        pinWant = null
        if (!ok) { holdReset(); pinNote = "Not pinned: ${msg.optString("why")}"; invalidate(); return }
        // pinned: back to the wheel, the item on its slice (the doc re-sent behind this answer draws it)
        if (mode == Mode.CHOOSE) backToWheel()
    }

    /** how far A has been held on a pinned slice, 0..1; a full hold replaces it */
    private fun holdNow(): Float {
        if (aHeldAt == 0L) return 0f
        val f = (SystemClock.uptimeMillis() - aHeldAt).toFloat() / PIN_HOLD_MS
        if (f >= 1f) {
            // full: the replace is asked for ONCE; it goes out on A's release (settled), the fill stays full until the answer
            if (!holdFired) { holdFired = true; val s = aSlice; post { pin(s) } }
            return 1f
        }
        return f
    }

    private fun holdReset() { aHeldAt = 0L; holdFired = false }

    /** ms along the open motion now (running backward while closing) */
    private fun tNow(): Float {
        val now = SystemClock.uptimeMillis()
        if (closingFrom >= 0f) return closingFrom - (now - closingAt) * (UiKit.CR_OPEN_MS / FOLD_MS)
        // smoothness D (the user 2026-10-08): the kit's 440 ms open plays in OPEN_MS (300); its choreography unchanged
        return (now - openedAt) * (UiKit.CR_OPEN_MS / OPEN_MS)
    }

    private var firstFrame = false
    override fun onDraw(c: Canvas) {
        if (!isOpen) { drawLeave(c); return }
        if (firstFrame) { firstFrame = false; Log.i("WCLOG", "radial: first frame (${width}x$height, src=$source, items=${doc?.items?.size ?: 0})") }
        if (closingFrom >= 0f && tNow() <= 0f) { done(); return }
        // in Home's slot the wheel is Home's to draw, under its columns (f-radial-behind); here only the band on top
        if (slot != null) underRedraw?.invoke()
        if (paint(c, if (slot != null) UiKit.CR_TOP else UiKit.CR_ALL)) postInvalidateOnAnimation()
    }

    /** the wheel at this moment ([part] UiKit.CR_*); true = it wants another frame */
    private fun paint(c: Canvas, part: Int): Boolean {
        val t = tNow()
        val screen = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val (disc, size) = discHere()
        if (mode == Mode.PICK) return false                                  // Bags' picker shows; the wheel is away
        val choosing = mode == Mode.CHOOSE
        // the chooser shows page 1 as it is: pins AND the auto-filled items (b67, the user: "all of the slots show empty (doesnt show
        // what Items are currently there from the recommendeds)"). Only a PIN needs A held to replace; an auto-filled slice pins at once
        val src = RadialModel.slices(doc, if (choosing) 0 else flow.page)
        val items = itemsOf(src)
        val hold = if (choosing) holdNow() else 0f
        val onAuto = choosing && flow.pointed in 0 until RadialModel.PER_PAGE && flow.pointed != RadialModel.PLUS &&
            src.getOrNull(flow.pointed)?.pinned == false
        val ch = choice?.takeIf { choosing }?.let {
            UiKit.RadialChoice(Icons.get(it.icon), it.name, prompt = pinNote ?: if (onAuto) "Press ${Glyphs.name("a")} to pin it here" else null, hold = hold) }
        val plus = if (RadialModel.plusOn(doc) && (choosing || flow.page == 0)) UiKit.CR_PLUS else -1
        val moving = UiKit.consumableRadial(c, screen, disc, size, items, flow.pointed, if (choosing) 0 else flow.page,
            if (choosing) 1 else RadialModel.pages(doc), t, glyphs = flow.pickA, plusSlot = plus, choose = ch, hubDown = hubDown(), slot = slot,
            part = part)
        if (part == UiKit.CR_WHEEL) return moving
        // the secure row is not built yet (Spells r2's nopad): the items show, and the wheel says why none can be used
        if (doc?.noPad == true && t >= UiKit.CR_OPEN_MS * 0.7f) {
            UiKit.asBody(pNote)
            c.drawText("Using items from here comes with the next add-on update", width / 2f, 64f, pNote)
        }
        // an EMPTY spell radial (a fresh character: nothing placed yet) says where it is filled, instead of looking broken (Track C)
        if (source == "sradial" && doc?.items.isNullOrEmpty() && !choosing && t >= UiKit.CR_OPEN_MS * 0.7f) {
            UiKit.asBody(pNote)
            c.drawText("No spells yet: add them in Controller, Spell radial", width / 2f, 64f, pNote)
        }
        return moving || closingFrom >= 0f || hubPressAt != 0L || aHeldAt != 0L || slot != null
    }

    /** the bar's Home disc, in this view's coordinates: its middle and its width */
    private fun discHere(): Pair<PointF, Float> {
        val r = strip.place(Room.HOME) ?: return PointF(width / 2f, height - 80f) to 140f
        val a = IntArray(2); val b = IntArray(2)
        strip.getLocationOnScreen(a); getLocationOnScreen(b)
        // the bar's place at rest (the pause slides it down out of view: test40)
        return PointF(r.centerX() + a[0] - b[0], r.centerY() + a[1] - b[1] - strip.translationY) to r.width()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isOpen) return false                                           // the slide back down takes no touch
        if (closingFrom >= 0f || mode == Mode.PICK) return true
        val slice = UiKit.consumableRadialHit(RectF(0f, 0f, width.toFloat(), height.toFloat()), e.x, e.y, slot)
        if (mode == Mode.CHOOSE) {
            // a finger: an empty slice pins at the touch; a pinned one is held down (the same 1 s as A); the hub goes back
            when (e.action) {
                MotionEvent.ACTION_DOWN -> if (slice in 0 until RadialModel.PER_PAGE) { flow.point(slice); chooseA(slice).also { aSliceByFinger = aHeldAt != 0L } }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (aSliceByFinger && !holdFired) aHeldAt = 0L
                    else if (slice == UiKit.CR_HUB && e.action == MotionEvent.ACTION_UP) backToPicker()
                    aSliceByFinger = false
                }
            }
            invalidate(); return true
        }
        if (e.action == MotionEvent.ACTION_UP) {
            if (slice == UiKit.CR_HUB) pressHub() else act(flow.touch(doc, slice))
            invalidate()
        }
        return true        // the wheel takes every touch while it shows (nothing under it is pressed by accident)
    }

    /** the hold to replace was started by a finger (its lift ends it) */
    private var aSliceByFinger = false

    private val pNote = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 28f; color = Theme.gold; textAlign = android.graphics.Paint.Align.CENTER }

    companion object {
        const val RETRY_MS = 2_500L
        /** the opening press came this long before the wheel opened (router -> add-on -> log -> app), added to Up's measured hold */
        const val PRESS_LEAD_MS = 150L
        const val FREE_AFTER_USE_MS = 300L
        /** the free's answer took 90-370 ms on b65; 1.5 s held the game's sticks that long after a lost one ("B takes a year") */
        const val FREE_CHECK_MS = 350L     // b74: 600 -> 350 (a free's answer reaches the app in ~110-390 ms; an ignored one cost 0.6 s a try)
        /** a use on the kept list waits this long for the game's list (Spells re-sends it on every hold: ~0.2 s) */
        const val WAIT_MS = 1_500L
        const val HOLD_CHECK_MS = 700L
        /** A held this long on a pinned slice replaces it (the user's pin sheet: 1 s) */
        const val PIN_HOLD_MS = 1_000L
        /** a pin / unpin with no answer by then is reported (the answer is flushed at once: ~0.2 s) */
        const val PIN_ACK_MS = 2_500L
        /** a tap waits until no controller button has changed for this long (b67's strays: sent ~10 ms after the press) */
        const val SETTLE_MS = 30L          // test C (17:46): a HELD face button parks the cursor, a release is safe at once: 150 -> 30 (TapQuiet the same)
        /** every close folds the wheel back into the stone in this long (b70: the user wants B gone in a blink; the kit's is 220) */
        const val FOLD_MS = 180f
        /** smoothness D: the open, played faster than the kit's CR_OPEN_MS (440) */
        const val OPEN_MS = 120f      // the user 2026-10-09: "WAAAAAAY faster" (was 300)
        /** f-radial-middle: the slide back down from Home's slot, with Home's columns closing (Motion.MODULE_CLOSE) */
        const val LEAVE_MS = 200f
        /** a stick held in the picker repeats its step: the first after this, then every [STICK_REPEAT_MS] */
        const val STICK_REPEAT_FIRST_MS = 350L
        const val STICK_REPEAT_MS = 130L
        const val MAX_SETTLE_MS = 700L
        const val MAX_HELD_MS = 3_000L
        /** the D-pad's four bits in the reader's mask (a held D-pad does not park the cursor) */
        private val DPAD_BITS = listOf(PadInput.SLOT_DPAD_LEFT, PadInput.SLOT_DPAD_UP, PadInput.SLOT_DPAD_RIGHT, PadInput.SLOT_DPAD_DOWN)
            .fold(0) { m, slot -> m or (1 shl slot) }
        const val TAG = "WCHOME"
        const val GUEST = "radial"
    }
}
