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
 * THE CONTEXT RADIAL ON SCREEN (the user, 2026-11-07: "When targeting something while not in the combat state, I want the Y glyph to
 * appear over them for the inspect menu. Instead of that menu opening a drop down I want to design a radial for it"; picks: the BOTTOM
 * screen, TAP Y / aim / A picks, the 8 most used + More; then "I want to use these context radicals for everything, no more drop down
 * menus in any of those places, including when selecting more make another radial"). The pause wheel's look (UiKit.pauseRadial: the
 * game's GamepadRadial, Home's disc rising into the hub) with the game's own menu on its slices ([TargetRadial]), the name as its title,
 * the context icons mixed from game art ([MenuIcons]).
 *
 * WHERE IT OPENS (one view, three openers, each with its own [Input]):
 *  - the TARGET's menu: a bare Y with a target out of the controller's grow (PadMap m101 / m102) reads it (Quests q51, `pmenu` src
 *    "radial"); that document opens the wheel. [Input.HOLD]: it holds the guest "radial" and reads the controller itself.
 *  - Home's PORTRAIT menu (the map module's): [Input.READ]: the map module already holds the controller; the wheel reads it, the map
 *    ignores its keys meanwhile.
 *  - Party mode's MEMBER menu (the PartyPad's Y, a long press on a cell): [Input.KEYS]: the PartyPad's own presses come in by [key] (a
 *    hold of ours would end Party mode); a finger works too.
 *
 * INPUT: either stick (READ / HOLD) or the D-pad (Left / Right round the ring, Up the top, Down the bottom) aims; A picks; B goes back a
 * wheel, and from the first closes; Y or the hub closes. MORE and a SUB-MENU open the next wheel ([TargetRadial.Page]).
 *
 * A PICK ([open]'s onDo): a menu entry runs as the opener's menu ran it (digit taps or a secure cell); one with a question (Promote,
 * Uninvite) asks by a second pick.
 */
class TargetRadialView(
    context: Context,
    private val strip: Strip,
    private val questsPad: () -> JSONObject?,
    private val guestTap: (List<Pair<String, Int>>?) -> Boolean,
) : View(context) {
    enum class Input { HOLD, READ, KEYS }

    var onSlide: (Float) -> Unit = {}
    var onClose: (Float) -> Unit = { onSlide(0f) }
    var barsBottom: (() -> Float)? = null
    /** the target's picks (the host's taps; Whisper opens the Chat room): the TARGET opener's */
    var onDo: (PlayerMenu.Do, String) -> Unit = { _, _ -> }

    private var input = Input.HOLD
    /** who opened it: "target" / "portrait" / "party" (the host routes `pmenu` documents by it) */
    var opener = ""
        private set
    private var doAct: (PlayerMenu.Do, String) -> Unit = { _, _ -> }
    private var closed: () -> Unit = {}
    private var doc: JSONObject? = null
    private var items: List<PlayerMenu.Entry> = emptyList()
    private var name = ""
    private val pages = ArrayList<TargetRadial.Page>()
    private var slices: List<TargetRadial.Slice?> = emptyList()
    private var pointed = -1
    private var openedAt = 0L
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

    /** Quests q51's `pmenu` src "radial" (current): the TARGET's wheel opens, or refreshes (the checks after a run) */
    fun onDoc(d: JSONObject) {
        if (!isOpen) { open("target", Input.HOLD, TargetRadial.name(d), onDo, {}) }
        fill(d)
    }

    /** the menu's document for the wheel that is open (any opener): its entries, page by page */
    fun fill(d: JSONObject) {
        doc = d
        TargetRadial.name(d).takeIf { it.isNotEmpty() }?.let { name = it }
        items = PlayerMenu.parse(d.optJSONArray("items"), if (opener == "party") PlayerMenu.PARTY_GREY else emptySet())
        relayout()
        invalidate()
    }

    private fun relayout() {
        // a page that is gone after the game's re-read (a sub-menu emptied): back to the root
        var s = TargetRadial.slices(items, pages.lastOrNull() ?: TargetRadial.ROOT)
        if (s == null) { pages.clear(); s = TargetRadial.slices(items) }
        slices = s ?: emptyList()
        if (pointed >= 0 && slices.getOrNull(pointed) == null) pointed = -1
    }

    /**
     * open the wheel for [opener] ("target" / "portrait" / "party"), its [name] until the document comes (Asking the game...);
     * [onDo] = what a pick does, [onClosed] = after it closes (by any route)
     */
    fun open(opener: String, input: Input, name: String, onDo: (PlayerMenu.Do, String) -> Unit, onClosed: () -> Unit) {
        if (isOpen) close("another opener")
        this.opener = opener; this.input = input; this.name = name
        doAct = onDo; closed = onClosed
        doc = null; items = emptyList(); pages.clear(); slices = emptyList()
        Log.i("WCLOG", "context radial: OPEN ($opener, $name, ${input.name})")
        MenuIcons.prefetch()
        UiKit.pauseBarsBottom = barsBottom?.invoke() ?: 0f
        UiKit.pauseStripTop = run {
            val a = IntArray(2); val b = IntArray(2)
            strip.getLocationOnScreen(a); getLocationOnScreen(b)
            if (strip.height > 0) (a[1] - b[1]).toFloat() - strip.translationY else 0f
        }
        openedAt = SystemClock.uptimeMillis()
        pointed = -1; line = null; armedKey = null; aimStick = -1
        held = 0; seeded = false
        strip.homeLifted = true
        visibility = VISIBLE
        bringToFront()
        if (input == Input.HOLD) { owning = true; hold(0) }
        reader?.stop(); reader = null
        if (input != Input.KEYS) reader = PadInput(
            onPad = { _, _, _, _ -> },
            onStatus = { ok, m -> if (!ok) Log.i(TAG, "context radial: controller reader: $m") },
            onButtons = { h -> post { buttons(h) } },
            onStickVector = { st, x, y -> post {
                mag[st.coerceIn(0, 1)] = kotlin.math.hypot(x.toDouble(), y.toDouble())
                if (isOpen) vector(st, x.toDouble(), -y.toDouble()) else stickCheck()
            } },
        ).also { it.start() }
        invalidate()
    }

    /** gone at once (the pause wheel's rule); a hold is let go once the sticks are back */
    fun close(why: String) {
        if (!isOpen) return
        Log.i("WCLOG", "context radial: CLOSED ($opener, $why)")
        openedAt = 0L
        val from = slidT; slidT = 0f
        strip.homeLifted = false
        visibility = GONE
        onClose(from)
        if (input == Input.HOLD) stickLater() else { reader?.stop(); reader = null }
        val cb = closed; closed = {}
        cb()
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
        Log.i(TAG, "context radial: hold${if (attempt > 0) " again ($attempt)" else ""} ${if (sent) "sent" else "NOT sent"}")
        postDelayed({ if (mine == holdTry && !heldOk && isOpen && attempt < 2) hold(attempt + 1) }, HomeRadialView.HOLD_CHECK_MS)
    }

    private fun free(attempt: Int) {
        val rest = HomeRadialView.FREE_AFTER_USE_MS - (SystemClock.uptimeMillis() - lastActAt)
        if (rest > 0) { val token = ++freeTry; postDelayed({ if (token == freeTry && !isOpen) free(attempt) }, rest); return }
        freed = false
        val mine = ++freeTry
        val sent = guestTap(GuestPad.cells(questsPad(), GUEST, hold = false))
        Log.i(TAG, "context radial: free${if (attempt > 0) " again ($attempt)" else ""} ${if (sent) "sent" else "NOT sent"}")
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
        // the first report is the state found: the button still down from the press that opened the wheel is no press
        if (!seeded) { seeded = true; held = h; return }
        val k = when {
            pressed(PadInput.SLOT_FACE_RIGHT, h) -> "PAD2"
            pressed(PadInput.SLOT_FACE_TOP, h) -> "PAD4"
            pressed(PadInput.SLOT_FACE_BOTTOM, h) -> "PAD1"
            pressed(PadInput.SLOT_DPAD_RIGHT, h) -> "PADDRIGHT"
            pressed(PadInput.SLOT_DPAD_LEFT, h) -> "PADDLEFT"
            pressed(PadInput.SLOT_DPAD_UP, h) -> "PADDUP"
            pressed(PadInput.SLOT_DPAD_DOWN, h) -> "PADDDOWN"
            else -> null
        }
        held = h
        if (k != null) key(k)
    }

    /** one press, the game's key names (the reader's, or the PartyPad's `key` for Party mode) */
    fun key(k: String) {
        if (!isOpen) return
        when (k) {
            "PAD2" -> back()
            "PAD4" -> close("Y")
            "PAD1" -> if (pointed in 0..7) pick(pointed) else say("Point at a slice")
            "PADDRIGHT" -> step(+1)
            "PADDLEFT" -> step(-1)
            "PADDUP" -> { pointed = 0; invalidate() }
            "PADDDOWN" -> { pointed = 4; invalidate() }
        }
    }

    /** B: the wheel before (More / a sub-menu), or closed from the first */
    private fun back() {
        if (pages.isEmpty()) { close("B"); return }
        pages.removeAt(pages.size - 1)
        pointed = -1; line = null
        relayout(); invalidate()
    }

    private fun step(d: Int) {
        var p = pointed
        for (n in 0 until 8) {
            p = if (p < 0) (if (d > 0) 0 else 7) else (p + d + 8) % 8
            if (slices.getOrNull(p) != null) break
        }
        pointed = p
        line = null
        invalidate()
    }

    private fun vector(st: Int, x: Double, y: Double) {
        val m = kotlin.math.hypot(x, y)
        if (aimStick != st) { if (m < RadialModel.DEAD_IN) return; aimStick = st }
        if (m < RadialModel.DEAD_OUT) { aimStick = -1; return }
        // the user on test24: "the wheel direction is ... reacting the wrong way" - [y] is already up-positive here (open()'s -y), and
        // UiKit.pauseRadialAim flips it again; the pause wheel's own aim (RadialModel.aimOf: up-positive, with its hold on a slice)
        val s = RadialModel.aimOf(x, y, pointed)
        if (s in 0..7 && s != pointed && slices.getOrNull(s) != null) { pointed = s; line = null; invalidate() }
    }

    // ------------------------------------------------------------------------------------------------ a pick

    private fun pick(i: Int) {
        val s = slices.getOrNull(i) ?: return
        Log.i(TAG, "context radial: pick ${s.label}")
        s.next?.let { p ->
            // More / a sub-menu: the next wheel (B comes back)
            if (!s.enabled) { say("Not available here"); return }
            pages += p; pointed = -1; line = null; armedKey = null
            relayout(); invalidate()
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
        val run = doAct; val who = name
        close("picked ${s.label}")
        run(act, who)
    }

    // ------------------------------------------------------------------------------------------------ paint

    private fun discHere(): Pair<PointF, Float> {
        val r = strip.place(Room.HOME) ?: return PointF(width / 2f, height - 80f) to 140f
        val a = IntArray(2); val b = IntArray(2)
        strip.getLocationOnScreen(a); getLocationOnScreen(b)
        return PointF(r.centerX() + a[0] - b[0], r.centerY() + a[1] - b[1] - strip.translationY) to r.width()
    }

    private fun kitSlices(): List<UiKit.PauseSlice?> = slices.map { it?.let { s -> UiKit.PauseSlice(s.icon, s.label, s.enabled, menu = s.menu) } }

    /** the title: the name, and the wheel it is on (More, a sub-menu's words) */
    private fun title(): String {
        val n = name.ifEmpty { "Target" }
        val p = pages.lastOrNull() ?: return n
        return p.sub ?: "$n: ${Tr.t("More")}"
    }

    override fun onDraw(c: Canvas) {
        if (!isOpen) return
        val t = ((SystemClock.uptimeMillis() - openedAt) * HomePauseView.SPEED).coerceIn(0f, UiKit.PAUSE_OPEN_MS)
        onSlide(t); slidT = t
        if (doc == null && line == null) say("Asking the game...")
        val lineFor = SystemClock.uptimeMillis() - lineAt
        if (line != null && lineFor > HomePauseView.LINE_MS + HomePauseView.LINE_FADE_MS) line = null
        val lineAlpha = if (lineFor <= HomePauseView.LINE_MS) 1f else (1f - (lineFor - HomePauseView.LINE_MS).toFloat() / HomePauseView.LINE_FADE_MS).coerceIn(0f, 1f)
        val screen = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val (disc, size) = discHere()
        val moving = UiKit.pauseRadial(c, screen, disc, size, kitSlices(), -1, pointed, -1, t, 0f, line, lineAlpha = lineAlpha,
            pickGlyph = true, up = null, down = null, left = null, right = null, title = title())
        if (moving || t < UiKit.PAUSE_OPEN_MS || line != null) postInvalidateOnAnimation()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isOpen) return false
        if (e.action == MotionEvent.ACTION_UP) {
            val s = UiKit.pauseRadialHit(RectF(0f, 0f, width.toFloat(), height.toFloat()), e.x, e.y)
            when {
                s == UiKit.PAUSE_HUB -> back()                    // the hub = B (a wheel back, or closed)
                s < 0 -> close("outside")
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
