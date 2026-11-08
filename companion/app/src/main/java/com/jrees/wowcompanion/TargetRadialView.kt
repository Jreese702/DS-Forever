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
 * THE TARGET RADIAL ON SCREEN (the user, 2026-11-07: "When targeting something while not in the combat state, I want the Y glyph to
 * appear over them for the inspect menu. Instead of that menu opening a drop down I want to design a radial for it"; picks: the BOTTOM
 * screen, TAP Y / aim / A picks, the 8 most used + More). The pause wheel's look (UiKit.pauseRadial: the game's GamepadRadial, Home's
 * disc rising into the hub) with the target's menu on its slices ([TargetRadial]) and the target's name as its title.
 *
 * OPENED BY the target's menu itself: a bare Y out of a fight with a target (Spells' router, PadMap m101) does no game action and reads
 * the target's menu (Quests q51, `pmenu` src "radial"); that document opens the wheel.
 *
 * INPUT while open (its own PadInput, as the pause wheel): either stick or the D-pad (Left / Right step round the ring) aims; A picks;
 * B, Y or the hub closes. A finger works too. MORE = the whole menu as a list ([onList]: Home's menu view over the left column); the
 * wheel's reader drives it (D-pad Up / Down, A, B) and keeps the controller until it closes.
 *
 * THE CONTROLLER: while open it holds the guest "radial" (the pause wheel's and the consumables wheel's: the router sends nothing to
 * them while it holds), so the game acts on none of it; let go once the sticks are back in the middle, as the pause wheel.
 *
 * A PICK ([onDo]): a menu entry runs as the portrait menu's (its digit taps, or its secure cell: Inspect = the game's /inspect, Set
 * Focus); one with a question (Promote, Uninvite) asks by a second pick; a sub-menu (Raid Target Icon) opens the list.
 */
class TargetRadialView(
    context: Context,
    private val strip: Strip,
    private val questsPad: () -> JSONObject?,
    private val guestTap: (List<Pair<String, Int>>?) -> Boolean,
) : View(context) {
    /** what a pick does (the host's taps; Whisper opens the Chat room) */
    var onDo: (PlayerMenu.Do, String) -> Unit = { _, _ -> }
    /** More: open the whole menu as a list (the document, the target's name) */
    var onList: (JSONObject, String) -> Unit = { _, _ -> }
    /** the list's own press (PlayerMenu keys: PADDUP / PADDDOWN / PAD1 / PAD2); returns false once the list has closed */
    var listKey: (String) -> Boolean = { false }
    var onSlide: (Float) -> Unit = {}
    var onClose: (Float) -> Unit = { onSlide(0f) }
    var barsBottom: (() -> Float)? = null

    private var doc: JSONObject? = null
    private var name = ""
    private var slices: List<TargetRadial.Slice?> = emptyList()
    private var pointed = -1
    private var openedAt = 0L
    private var listing = false
    private var reader: PadInput? = null
    private var held = 0
    private var seeded = false
    private var aimStick = -1
    private val mag = DoubleArray(2)
    private var line: String? = null
    private var lineAt = 0L
    /** a pick that asks first: armed by its first pick, done by a second within [CONFIRM_MS] */
    private var armedKey: String? = null
    private var armedAt = 0L
    private var lastActAt = 0L
    private var slidT = 0f
    private var holdTry = 0
    private var freeTry = 0
    @Volatile private var heldOk = false
    @Volatile private var freed = false

    val isOpen get() = openedAt != 0L

    init { visibility = GONE; isClickable = true }

    /** Quests q51's `pmenu` src "radial" (current): opens the wheel, or refreshes it (the checks after a run) */
    fun onDoc(d: JSONObject) {
        doc = d
        name = TargetRadial.name(d)
        slices = TargetRadial.slices(PlayerMenu.parse(d.optJSONArray("items")))
        if (!isOpen) open() else invalidate()
    }

    private fun open() {
        Log.i("WCLOG", "target radial: OPEN ($name, ${slices.count { it?.entry != null }} entries)")
        UiKit.pauseBarsBottom = barsBottom?.invoke() ?: 0f
        UiKit.pauseStripTop = run {
            val a = IntArray(2); val b = IntArray(2)
            strip.getLocationOnScreen(a); getLocationOnScreen(b)
            if (strip.height > 0) (a[1] - b[1]).toFloat() - strip.translationY else 0f
        }
        openedAt = SystemClock.uptimeMillis()
        owning = true
        pointed = -1; line = null; armedKey = null; aimStick = -1; listing = false
        held = 0; seeded = false
        strip.homeLifted = true
        visibility = VISIBLE
        bringToFront()
        hold(0)
        reader?.stop()
        reader = PadInput(
            onPad = { _, _, _, _ -> },
            onStatus = { ok, m -> if (!ok) Log.i(TAG, "target radial: controller reader: $m") },
            onButtons = { h -> post { buttons(h) } },
            onStickVector = { st, x, y -> post {
                mag[st.coerceIn(0, 1)] = kotlin.math.hypot(x.toDouble(), y.toDouble())
                if (isOpen && !listing) vector(st, x.toDouble(), -y.toDouble()) else stickCheck()
            } },
        ).also { it.start() }
        invalidate()
    }

    /** gone at once (the pause wheel's rule); the controller let go once the sticks are back */
    fun close(why: String) {
        if (!isOpen) return
        Log.i("WCLOG", "target radial: CLOSED ($why)")
        openedAt = 0L; listing = false
        val from = slidT; slidT = 0f
        strip.homeLifted = false
        visibility = GONE
        onClose(from)
        stickLater()
    }

    /** the app leaves the screen: gone, the hold freed */
    fun shut() {
        if (!isOpen) return
        close("the app left the screen")
    }

    private fun say(s: String) { line = s; lineAt = SystemClock.uptimeMillis(); invalidate() }

    // ------------------------------------------------------------------------------------------------ the controller

    private fun hold(attempt: Int) {
        heldOk = false
        val mine = ++holdTry
        val sent = guestTap(GuestPad.cells(questsPad(), GUEST, hold = true))
        Log.i(TAG, "target radial: hold${if (attempt > 0) " again ($attempt)" else ""} ${if (sent) "sent" else "NOT sent"}")
        postDelayed({ if (mine == holdTry && !heldOk && isOpen && attempt < 2) hold(attempt + 1) }, HomeRadialView.HOLD_CHECK_MS)
    }

    private fun free(attempt: Int) {
        val rest = HomeRadialView.FREE_AFTER_USE_MS - (SystemClock.uptimeMillis() - lastActAt)
        if (rest > 0) { val token = ++freeTry; postDelayed({ if (token == freeTry && !isOpen) free(attempt) }, rest); return }
        freed = false
        val mine = ++freeTry
        val sent = guestTap(GuestPad.cells(questsPad(), GUEST, hold = false))
        Log.i(TAG, "target radial: free${if (attempt > 0) " again ($attempt)" else ""} ${if (sent) "sent" else "NOT sent"}")
        postDelayed({
            if (mine != freeTry || isOpen) return@postDelayed
            if (!freed && attempt < 4) free(attempt + 1) else owning = false
        }, HomeRadialView.FREE_CHECK_MS)
    }

    /** the guest "radial"'s answers while this wheel is the one that holds it */
    fun onGuestAck(msg: JSONObject) {
        if (msg.optString("op") == "hold" && msg.optBoolean("ok")) heldOk = true
        if (msg.optString("op") == "free" && msg.optBoolean("ok")) { freed = true; if (!isOpen) owning = false }
    }
    /** this wheel holds the guest "radial", or is letting it go (the host routes the guest's answers here meanwhile) */
    var owning = false
        private set

    private var stickWait = false
    private val stickBack = Runnable { if (stickWait) { stickWait = false; free(0); reader?.stop(); reader = null } }
    private fun stickNeutral() = mag[0] < RadialModel.NEUTRAL && mag[1] < RadialModel.NEUTRAL
    private fun stickLater() {
        if (stickNeutral()) { free(0); reader?.stop(); reader = null; return }
        stickWait = true
        postDelayed(stickBack, RadialModel.STICK_BACK_MS)
    }
    private fun stickCheck() {
        if (!stickWait || !stickNeutral()) return
        stickWait = false; removeCallbacks(stickBack)
        free(0); reader?.stop(); reader = null
    }

    private fun pressed(slot: Int, h: Int) = PadInput.isHeld(h, slot) && !PadInput.isHeld(held, slot)

    private fun buttons(h: Int) {
        if (!isOpen) { held = h; return }
        // the first report is the state found: Y still down from the press that opened the wheel is no press
        if (!seeded) { seeded = true; held = h; return }
        if (listing) {
            val k = when {
                pressed(PadInput.SLOT_DPAD_UP, h) -> "PADDUP"
                pressed(PadInput.SLOT_DPAD_DOWN, h) -> "PADDDOWN"
                pressed(PadInput.SLOT_FACE_BOTTOM, h) -> "PAD1"
                pressed(PadInput.SLOT_FACE_RIGHT, h) -> "PAD2"
                else -> null
            }
            held = h
            if (k != null) { lastActAt = SystemClock.uptimeMillis(); if (!listKey(k)) close("the list closed") }
            return
        }
        when {
            pressed(PadInput.SLOT_FACE_RIGHT, h) -> { held = h; close("B"); return }
            pressed(PadInput.SLOT_FACE_TOP, h) -> { held = h; close("Y"); return }
            pressed(PadInput.SLOT_FACE_BOTTOM, h) -> { held = h; if (pointed in 0..7) pick(pointed) else say("Point at a slice"); return }
            pressed(PadInput.SLOT_DPAD_RIGHT, h) -> step(+1)
            pressed(PadInput.SLOT_DPAD_LEFT, h) -> step(-1)
            pressed(PadInput.SLOT_DPAD_UP, h) -> { pointed = 0; invalidate() }
            pressed(PadInput.SLOT_DPAD_DOWN, h) -> { pointed = 4; invalidate() }
        }
        held = h
    }

    private fun step(d: Int) {
        pointed = if (pointed < 0) (if (d > 0) 0 else 7) else (pointed + d + 8) % 8
        line = null
        invalidate()
    }

    private fun vector(st: Int, x: Double, y: Double) {
        val m = kotlin.math.hypot(x, y)
        if (aimStick != st) { if (m < RadialModel.DEAD_IN) return; aimStick = st }
        if (m < RadialModel.DEAD_OUT) { aimStick = -1; return }
        val s = UiKit.pauseRadialAim(x.toFloat(), y.toFloat())
        if (s in 0..7 && s != pointed) { pointed = s; line = null; invalidate() }
    }

    // ------------------------------------------------------------------------------------------------ a pick

    private fun pick(i: Int) {
        val s = slices.getOrNull(i) ?: return
        val d = doc ?: return
        Log.i(TAG, "target radial: pick ${s.label}")
        if (s.more || s.entry?.sub != null) {
            // the whole menu as a list (a sub-menu's entries are in it); the wheel keeps the controller and drives it
            listing = true
            strip.homeLifted = false
            onSlide(0f)                                               // Home's columns back: the list stands in the left one
            onList(d, name)
            invalidate()
            return
        }
        val e = s.entry ?: return
        if (!e.enabled) { say("Not available here"); return }
        if (e.confirm != null) {
            val now = SystemClock.uptimeMillis()
            if (armedKey != s.label || now - armedAt > CONFIRM_MS) { armedKey = s.label; armedAt = now; say("${e.confirm} Pick it again"); return }
            armedKey = null
        }
        val act = when {
            e.secure != null -> PlayerMenu.Do.Secure(e.secure, e.text, e.secureRow)
            e.id != null -> PlayerMenu.Do.Run(e.id, e.text, e.app)
            else -> null
        }
        if (act == null) { say("Not available here"); return }
        lastActAt = SystemClock.uptimeMillis()
        close("picked ${s.label}")
        onDo(act, name)
    }

    // ------------------------------------------------------------------------------------------------ paint

    private fun discHere(): Pair<PointF, Float> {
        val r = strip.place(Room.HOME) ?: return PointF(width / 2f, height - 80f) to 140f
        val a = IntArray(2); val b = IntArray(2)
        strip.getLocationOnScreen(a); getLocationOnScreen(b)
        return PointF(r.centerX() + a[0] - b[0], r.centerY() + a[1] - b[1] - strip.translationY) to r.width()
    }

    private fun kitSlices(): List<UiKit.PauseSlice?> = slices.map { it?.let { s -> UiKit.PauseSlice(s.icon, s.label, s.enabled) } }

    override fun onDraw(c: Canvas) {
        if (!isOpen) return
        if (listing) return                                          // the list over the left column; the wheel steps aside
        val t = ((SystemClock.uptimeMillis() - openedAt) * HomePauseView.SPEED).coerceIn(0f, UiKit.PAUSE_OPEN_MS)
        onSlide(t); slidT = t
        val lineFor = SystemClock.uptimeMillis() - lineAt
        if (line != null && lineFor > HomePauseView.LINE_MS + HomePauseView.LINE_FADE_MS) line = null
        val lineAlpha = if (lineFor <= HomePauseView.LINE_MS) 1f else (1f - (lineFor - HomePauseView.LINE_MS).toFloat() / HomePauseView.LINE_FADE_MS).coerceIn(0f, 1f)
        val screen = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val (disc, size) = discHere()
        val moving = UiKit.pauseRadial(c, screen, disc, size, kitSlices(), -1, pointed, -1, t, 0f, line, lineAlpha = lineAlpha,
            pickGlyph = true, up = null, down = null, left = null, right = null, title = name.ifEmpty { "Target" })
        if (moving || t < UiKit.PAUSE_OPEN_MS || line != null) postInvalidateOnAnimation()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isOpen) return false
        if (listing) return false                                     // the list takes its own touches
        if (e.action == MotionEvent.ACTION_UP) {
            val s = UiKit.pauseRadialHit(RectF(0f, 0f, width.toFloat(), height.toFloat()), e.x, e.y)
            when {
                s == UiKit.PAUSE_HUB || s < 0 -> close("the hub / outside")
                else -> { pointed = s; pick(s) }
            }
        }
        return true
    }

    companion object {
        const val TAG = "WCHOME"
        const val GUEST = "radial"
        const val CONFIRM_MS = 4_000L
    }
}
