package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONObject

/**
 * THE SETTINGS ROOM (home session; the hub session that built its Game UI section is closed): the three sections stacked in
 * one scroll - the Map / Controller settings ([SettingsView], map session), the Game UI section ([GameUiSection]) and Home's
 * own ([HomeSettingsSection]) - with [SettingsPad] drawn over them for the controller.
 */
class SettingsRoom(context: Context, top: SettingsView, rest: SettingsView, padSource: () -> JSONObject?) :
    FrameLayout(context) {
    val gameUi = GameUiSection(context)
    val homeSection = HomeSettingsSection(context)
    private val scroll = ScrollView(context).apply { setBackgroundColor(Theme.ink) }
    val pad: SettingsPad

    init {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // THE ORDER (the user, 2026-10-06: "the home section should closer to the top"): the title and Game (Map's TOP part), Home,
            // Map and Controller (Map's REST part), then Game UI and Companion. Each part measures its own height; this scroll scrolls
            val wrap = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
            addView(top, wrap())
            addView(homeSection, wrap())
            addView(rest, wrap())
            addView(gameUi, wrap())
        }
        scroll.addView(column)
        addView(scroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        pad = SettingsPad(context, scroll, listOf(
            // TOP's one button is "Reload game UI", the room's one destructive control: the cursor never STARTS there
            SettingsPad.Section(top, { top.focusTargets() }, startSkip = 1),
            SettingsPad.Section(homeSection, targets = { homeSection.focusTargets() }),
            SettingsPad.Section(rest, { rest.focusTargets() }),
            SettingsPad.Section(gameUi, targets = { gameUi.focusTargets() }),
        ), gameUi, padSource)
        addView(pad, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> pad.invalidate() }
    }
}

/**
 * THE SETTINGS ROOM WITH THE CONTROLLER (LATER item 5, the user: "When opening any room I want a unified experience on how the
 * home button behaves, B returning to the home room if there is not another obvious function of the B button"). The room is the
 * generic guest "settings" of the quests addon's facility (Quests.lua q6, GuestPad): entering by touch holds the controller,
 * leaving by touch frees it. The cursor ([UiKit.focus]) is up as soon as the room holds, on the first button in view, and walks every
 * button of the three sections in reading order; the
 * D-pad moves it to the nearest button in that direction; A presses the button EXACTLY as a touch does (a down / up at its centre,
 * so each section keeps all its own logic, the Reload's two-tap confirm included); B closes an open On / Off / Dynamic bar
 * first, otherwise leaves the room with B (Home). The Home button shows B whenever B would go Home (bnext).
 *
 * This view only draws the cursor over the scroll; it never takes a touch.
 */
class SettingsPad(
    context: Context,
    private val scroll: ScrollView,
    private val sections: List<Section>,
    private val gameUi: GameUiSection,
    private val padSource: () -> JSONObject?,
) : View(context) {

    /** one section of the room and its buttons, in its own coordinates; its first [startSkip] buttons are never where the cursor starts */
    class Section(val view: View, val targets: () -> List<RectF>, val startSkip: Int = 0)

    /** B with nothing nearer to do, when the leave-with-B cell cannot be tapped: Home directly (MainActivity) */
    var onLeave: (() -> Unit)? = null

    var held = false; private set
    /** b112 (the rooms audit: one hold tap, never asked again): Map's HoldAsk, asked again every 0.7 s while unanswered, 4 at most */
    private val holdAsk = HoldAsk()
    /** a hold went out this visit (its answer may still come): the leave frees it even before the answer */
    private var holdAsked = false
    private var focus: PointF? = null           // the cursor's button centre in the scroll's content coordinates; null = not shown
    private var lastPadN = -1
    private var bSaid: String? = null

    /** a button in content coordinates, with its section and its own rect */
    private class Target(val box: RectF, val section: Section, val local: RectF, val noStart: Boolean)

    private fun targets(): List<Target> {
        val out = ArrayList<Target>()
        for (s in sections) {
            val top = s.view.top.toFloat()
            for ((i, r) in s.targets().withIndex()) out.add(Target(RectF(r.left, r.top + top, r.right, r.bottom + top), s, r, i < s.startSkip))
        }
        return out
    }

    /** where the cursor starts: the first button in view that may start it (the user's ruling, via tracking 2026-09-30: not on Reload) */
    private fun startTarget(all: List<Target>): Target? =
        all.firstOrNull { !it.noStart && it.box.bottom > scroll.scrollY } ?: all.firstOrNull { !it.noStart } ?: all.firstOrNull()

    // ---------------------------------------------------------------- the guest (MainActivity calls these on the touch path)

    /** the room was entered by touch: hold the controller (one tap) */
    fun enter() {
        focus = null; lastPadN = -1; bSaid = null
        holdAsk.shown()
        askHold()
    }

    /** one hold tap now if HoldAsk says so, and the next check [HoldAsk.EVERY_MS] later */
    private fun askHold() {
        if (!holdAsk.due()) return
        val sent = tap(GuestPad.cells(padSource(), ID, hold = true))
        holdAsked = holdAsked || sent
        if (holdAsk.asked(sent)) postDelayed({ askHold() }, HoldAsk.EVERY_MS)
    }

    /** the room was left by touch: hand the controller back (one tap), if it was held */
    fun leave() {
        holdAsk.hidden()
        // b112: a hold asked this visit is freed even if its answer has not come yet (a late hold must not keep the controller)
        if (held || holdAsked) tap(GuestPad.cells(padSource(), ID, hold = false))
        held = false; holdAsked = false; focus = null; invalidate()
    }

    /** {"type":"guestack","id":"settings"} */
    fun onGuestAck(msg: JSONObject) {
        when (msg.optString("op")) {
            // the cursor is THERE when the room holds the controller (the user 2026-09-30: "I hate that in the other menus I have to
            // ress buttons a few times to make the cursor appear, it should already be there"): on the first button in view
            "hold" -> { holdAsk.answered(); if (msg.optBoolean("ok")) {
                // b112: "pending" (capture v10 / v11: queued, or a fight's pending ENTER) does not hold yet, as in the Quests room
                held = msg.optString("why") != "pending"; bSaid = null; syncB()
                if (focus == null) post { startTarget(targets())?.let { moveTo(it) } }
            } }
            "free", "leave" -> { held = false; focus = null; invalidate() }
        }
    }

    /** {"type":"guestpad","id":"settings"}: one press, only while the room shows and from a CURRENT document (MainActivity) */
    fun onPad(msg: JSONObject) {
        if (msg.has("lost")) { held = false; focus = null; invalidate(); return }
        holdAsk.answered()
        val n = msg.optInt("n", -1)
        if (n == lastPadN) return                           // the same press seen twice
        lastPadN = n
        held = true
        val all = targets()
        if (all.isEmpty()) return
        val here = nearest(all, focus)
        val k = msg.optString("k")
        if (focus == null && k.startsWith("PADD")) {
            // the cursor should already be up (shown on the hold); if a touch scrolled it away, a D-pad press brings it back first
            startTarget(all)?.let { moveTo(it) }
        } else when (k) {
            "PADDUP", "PADDDOWN" -> vertical(all, here, if (k == "PADDUP") -1 else 1)?.let { moveTo(it) }
            "PADDLEFT", "PADDRIGHT" -> horizontal(all, here, if (k == "PADDLEFT") -1 else 1)?.let { moveTo(it) }
            "PAD1" -> if (focus != null) here?.let { press(it) } else moveTo(all.first())
            "PAD2" -> when {
                gameUi.closeSelection() -> { post { focus = focus?.let { f -> nearest(targets(), f)?.let { centre(it.box) } }; invalidate() } }
                else -> if (!tap(GuestPad.leaveB(padSource()))) onLeave?.invoke()
            }
        }
        post { syncB() }
        invalidate()
    }

    // ---------------------------------------------------------------- moving and pressing

    private fun centre(r: RectF) = PointF(r.centerX(), r.centerY())

    private fun nearest(all: List<Target>, p: PointF?): Target? =
        if (p == null) null else all.minByOrNull { Math.hypot((it.box.centerX() - p.x).toDouble(), (it.box.centerY() - p.y).toDouble()) }

    /** the nearest row above / below: the smallest vertical step, then the closest in x on that row */
    private fun vertical(all: List<Target>, here: Target?, dir: Int): Target? {
        val h = here ?: return all.first()
        val cands = all.filter { (it.box.centerY() - h.box.centerY()) * dir > ROW_SLOP }
        val step = cands.minOfOrNull { Math.abs(it.box.centerY() - h.box.centerY()) } ?: return null
        return cands.filter { Math.abs(it.box.centerY() - h.box.centerY()) <= step + ROW_SLOP }
            .minByOrNull { Math.abs(it.box.centerX() - h.box.centerX()) }
    }

    /** the nearest button left / right on the same row */
    private fun horizontal(all: List<Target>, here: Target?, dir: Int): Target? {
        val h = here ?: return all.first()
        return all.filter { Math.abs(it.box.centerY() - h.box.centerY()) <= h.box.height() / 2f &&
                (it.box.centerX() - h.box.centerX()) * dir > 1f }
            .minByOrNull { Math.abs(it.box.centerX() - h.box.centerX()) }
    }

    private fun moveTo(t: Target) {
        focus = centre(t.box)
        // keep it in view, with a margin
        val y0 = scroll.scrollY; val vh = scroll.height
        if (t.box.top - MARGIN < y0) scroll.smoothScrollTo(0, (t.box.top - MARGIN).toInt().coerceAtLeast(0))
        else if (t.box.bottom + MARGIN > y0 + vh) scroll.smoothScrollTo(0, (t.box.bottom + MARGIN - vh).toInt())
        invalidate()
    }

    /** A: exactly a touch at the button's centre (down, then up), so the section's own logic decides */
    private fun press(t: Target) {
        val x = t.local.centerX(); val y = t.local.centerY()
        val at = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(at, at, MotionEvent.ACTION_DOWN, x, y, 0)
        t.section.view.dispatchTouchEvent(down); down.recycle()
        val up = MotionEvent.obtain(at, at + 40, MotionEvent.ACTION_UP, x, y, 0)
        t.section.view.dispatchTouchEvent(up); up.recycle()
        // a bar that opened or closed moves the buttons: the cursor stays on the same button
        post { invalidate() }
    }

    /** tell the capture where B leads now (the Home button shows B only when it leads Home) */
    private fun syncB() {
        if (!held) return
        val where = if (gameUi.hasSelection) "back" else "home"
        if (where != bSaid && tap(GuestPad.bnext(padSource(), home = where == "home"))) bSaid = where
    }

    private fun tap(cells: List<Pair<String, Int>>?): Boolean {
        val pad = padSource()
        return cells != null && pad != null && TapBridge.taps(context, pad, cells) { }
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(c: Canvas) {
        if (!held) return
        val f = focus ?: return
        val t = nearest(targets(), f) ?: return
        val r = RectF(t.box.left, t.box.top - scroll.scrollY, t.box.right, t.box.bottom - scroll.scrollY)
        UiKit.focus(c, r)
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = false   // touches reach the sections under it

    companion object {
        const val ID = "settings"
        const val ROW_SLOP = 12f
        const val MARGIN = 40f
    }
}
