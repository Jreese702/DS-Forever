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
 * THE SETTINGS ROOM: the title and the TAB ROW fixed at the top ([SettingsTabBar]; board f-settings-tabs, the user's approved plan
 * 2026-10-15), and under it one tab's page in a scroll: SettingsView's own blocks of that tab, Home's section (Home), the game's
 * elements (Top screen), the companion's status (System). Which block is on which tab is [SettingsTabs]' data. The room opens on the
 * last tab used ([SettingsTabs.last]). [SettingsPad] is drawn over the page for the controller.
 */
class SettingsRoom(context: Context, private val view: SettingsView, padSource: () -> JSONObject?) : LinearLayout(context) {
    val gameUi = GameUiSection(context, GameUiSection.Part.GAME_UI)
    val homeSection = HomeSettingsSection(context)
    /** the companion's status (System); built after [gameUi] so the status line's one listener is its */
    val companion = GameUiSection(context, GameUiSection.Part.COMPANION)
    val tabBar = SettingsTabBar(context) { t -> show(t) }
    /** "What it does" (phase 2): the row's picture, right of the page, on the tabs that have pictures */
    val preview = SettingsPreviewView(context) { homeSection.dpadState()?.want }
    private val scroll = ScrollView(context).apply { setBackgroundColor(Theme.ink) }
    val pad: SettingsPad

    /** the tab shown */
    var tab: SettingsTabs.Tab = SettingsTabs.Tab.HOME
        private set

    init {
        orientation = VERTICAL
        setBackgroundColor(Theme.ink)
        SettingsTabs.init(context)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // each part measures its own height; this scroll scrolls the tab's page
            val wrap = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
            addView(view, wrap())
            addView(homeSection, wrap())
            addView(gameUi, wrap())
            addView(companion, wrap())
        }
        scroll.addView(column)
        addView(tabBar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val page = FrameLayout(context)
        page.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val body = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        pad = SettingsPad(context, scroll, listOf(
            // System's first button is "Reload game UI", the room's one destructive control: the cursor never STARTS there
            SettingsPad.Section(view, { view.focusTargets() }, startSkip = 1),
            SettingsPad.Section(homeSection, targets = { homeSection.focusTargets() }),
            SettingsPad.Section(gameUi, targets = { gameUi.focusTargets() }),
        ), gameUi, padSource, tabBar, { dir -> show(SettingsTabs.step(tab, dir)) }, { v, r -> rowOf(v, r.centerX(), r.centerY()) })
        page.addView(pad, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        body.addView(page, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        body.addView(preview, LinearLayout.LayoutParams(PREVIEW_W, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(body, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> pad.invalidate() }
        show(SettingsTabs.last, force = true)
    }

    /** show tab [t]: only its blocks are in the page (the others GONE, so they take no room and press nothing) */
    fun show(t: SettingsTabs.Tab, force: Boolean = false) {
        if (t == tab && !force) return
        tab = t
        SettingsTabs.last = t
        gameUi.closeSelection()
        view.tab = t
        view.visibility = if (SettingsTabs.viewBlocks(t).isEmpty()) View.GONE else View.VISIBLE
        homeSection.visibility = if (SettingsTabs.Block.HOME_SECTION.tab == t) View.VISIBLE else View.GONE
        gameUi.visibility = if (SettingsTabs.Block.GAME_UI.tab == t) View.VISIBLE else View.GONE
        companion.visibility = if (SettingsTabs.Block.COMPANION.tab == t) View.VISIBLE else View.GONE
        tabBar.tab = t
        scroll.scrollTo(0, 0)
        // the panel: on the tabs with pictures, starting on the tab's first row that has one
        val rows = SettingsTabs.blocks(t).flatMap { it.rows }
        preview.visibility = if (rows.any { SettingsPreview.has(it) }) View.VISIBLE else View.GONE
        preview.id = rows.firstOrNull { SettingsPreview.has(it) }
        pad.tabChanged()
    }

    /** the row at ([x], [y]) of section [v] (its own coordinates) shows in the panel, when it has a picture */
    private fun rowOf(v: View, x: Float, y: Float) {
        val id = when (v) { view -> view.rowAt(x, y); homeSection -> homeSection.rowAt(x, y); else -> null }
        if (SettingsPreview.has(id)) preview.id = id
    }

    /** a touch on a row: the panel shows it (the touch itself goes on to the row as always) */
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN && preview.visibility == View.VISIBLE) {
            val col = scroll.getChildAt(0) as? ViewGroup
            val sx = e.x; val sy = e.y - tabBar.height + scroll.scrollY
            if (col != null && sx < scroll.width) for (i in 0 until col.childCount) {
                val ch = col.getChildAt(i)
                if (ch.visibility == View.VISIBLE && sy >= ch.top && sy < ch.bottom) { rowOf(ch, sx, sy - ch.top); break }
            }
        }
        return super.dispatchTouchEvent(e)
    }

    private companion object {
        const val PREVIEW_W = 470
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
    /** the tab row (board f-settings-tabs): the cursor lands on it */
    private val tabBar: SettingsTabBar? = null,
    /** L / R (and the D-pad's left / right on the tab row): the next tab (-1 / +1) */
    private val onTabStep: (Int) -> Unit = {},
    /** the cursor moved onto a button: its section's view and the button there (the "What it does" panel follows its row) */
    private val onFocus: (View, RectF) -> Unit = { _, _ -> },
) : View(context) {

    /** one section of the room and its buttons, in its own coordinates; its first [startSkip] buttons are never where the cursor starts */
    class Section(val view: View, val targets: () -> List<RectF>, val startSkip: Int = 0)

    /** B with nothing nearer to do, when the leave-with-B cell cannot be tapped: Home directly (MainActivity) */
    var onLeave: (() -> Unit)? = null

    /** the report page over the room (board f-problem-report) takes each press while it is open: true = taken */
    var padOverride: ((String) -> Boolean)? = null

    var held = false; private set
    /** b112 (the rooms audit: one hold tap, never asked again): Map's HoldAsk, asked again every 0.7 s while unanswered, 4 at most */
    private val holdAsk = HoldAsk()
    /** a hold went out this visit (its answer may still come): the leave frees it even before the answer */
    private var holdAsked = false
    private var focus: PointF? = null           // the cursor's button centre in the scroll's content coordinates; null = not shown
    /**
     * THE CURSOR ON THE TAB ROW (f-settings-tabs; the house's "tabs open on landing"): the room opens with it there; L / R or the
     * D-pad's left / right switch the tab, A or down goes into the page, up from the page's first row and B come back here, and B
     * here leaves the room (rows -> tabs -> Home).
     */
    private var onTabs = false
        set(v) { field = v; tabBar?.cursor = v }
    private var lastPadN = -1
    private var bSaid: String? = null

    /** a button in content coordinates, with its section and its own rect */
    private class Target(val box: RectF, val section: Section, val local: RectF, val noStart: Boolean)

    private fun targets(): List<Target> {
        val out = ArrayList<Target>()
        for (s in sections) {
            if (s.view.visibility != View.VISIBLE) continue       // another tab's: its boxes are stale
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
        // the cursor is there AT ONCE, with its A (the user, 2026-10-15: "make sure that the cursor appears with the A glyph when
        // changing to the settings room"): on the tab row, before the hold's answer (a pending or late answer left none until a press)
        onTabs = tabBar != null
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
        held = false; holdAsked = false; focus = null; onTabs = false; invalidate()
    }

    /** the room showed another tab: the cursor is on the tab row (a touch's pick or L / R) */
    fun tabChanged() {
        focus = null
        if (tabBar != null && isShown) onTabs = true
        post { syncB() }
        invalidate()
    }

    /** the cursor onto the tab row (or, with no tab row, the page's first button) */
    private fun toTabs() {
        if (tabBar == null) { startTarget(targets())?.let { moveTo(it) }; return }
        focus = null; onTabs = true
        invalidate()
    }

    /** from the tab row into the page: its first button in view */
    private fun intoPage() {
        val all = targets()
        val t = startTarget(all) ?: return          // a page with no buttons (e.g. only the status): stay on the tabs
        onTabs = false
        moveTo(t)
    }

    /** {"type":"guestack","id":"settings"} */
    fun onGuestAck(msg: JSONObject) {
        when (msg.optString("op")) {
            // the cursor is THERE when the room holds the controller (the user 2026-09-30: "I hate that in the other menus I have to
            // ress buttons a few times to make the cursor appear, it should already be there"): on the first button in view
            "hold" -> { holdAsk.answered(); if (msg.optBoolean("ok")) {
                // b112: "pending" (capture v10 / v11: queued, or a fight's pending ENTER) does not hold yet, as in the Quests room
                held = msg.optString("why") != "pending"; bSaid = null
                // the cursor LANDS ON THE TABS (f-settings-tabs); without a tab row, on the first button in view
                if (focus == null) post { toTabs(); syncB() } else syncB()
            } }
            "free", "leave" -> { held = false; focus = null; onTabs = false; invalidate() }
        }
    }

    /** {"type":"guestpad","id":"settings"}: one press, only while the room shows and from a CURRENT document (MainActivity) */
    fun onPad(msg: JSONObject) {
        if (msg.has("lost")) { held = false; focus = null; onTabs = false; invalidate(); return }
        holdAsk.answered()
        val n = msg.optInt("n", -1)
        if (n == lastPadN) return                           // the same press seen twice
        lastPadN = n
        held = true
        val k = msg.optString("k")
        if (padOverride?.invoke(k) == true) return
        // L / R switch the tab from anywhere; the cursor goes to the tab row (board f-settings-tabs)
        if (k == "PADLSHOULDER" || k == "PADRSHOULDER") {
            if (tabBar != null) { onTabStep(if (k == "PADLSHOULDER") -1 else 1); onTabs = true }
            post { syncB() }; invalidate(); return
        }
        if (onTabs) {
            when (k) {
                "PADDLEFT", "PADDRIGHT" -> onTabStep(if (k == "PADDLEFT") -1 else 1)
                "PADDDOWN", "PAD1" -> intoPage()
                "PAD2" -> if (!tap(GuestPad.leaveB(padSource()))) onLeave?.invoke()
            }
            post { syncB() }; invalidate(); return
        }
        val all = targets()
        if (all.isEmpty()) { toTabs(); post { syncB() }; return }
        val here = nearest(all, focus)
        if (focus == null && k.startsWith("PADD")) {
            // the cursor should already be up (shown on the hold); if a touch scrolled it away, a D-pad press brings it back first
            startTarget(all)?.let { moveTo(it) }
        } else when (k) {
            "PADDUP", "PADDDOWN" -> {
                val next = vertical(all, here, if (k == "PADDUP") -1 else 1)
                if (next != null) moveTo(next) else if (k == "PADDUP" && tabBar != null) toTabs()   // up from the first row: the tabs
            }
            "PADDLEFT", "PADDRIGHT" -> horizontal(all, here, if (k == "PADDLEFT") -1 else 1)?.let { moveTo(it) }
            "PAD1" -> if (focus != null) here?.let { press(it) } else moveTo(all.first())
            "PAD2" -> when {
                gameUi.closeSelection() -> { post { focus = focus?.let { f -> nearest(targets(), f)?.let { centre(it.box) } }; invalidate() } }
                // B in the page: back to the tab row (rows -> tabs -> Home); without a tab row it leaves
                tabBar != null -> toTabs()
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
        onFocus(t.section.view, t.local)
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
        // B leads Home only from the tab row (or with no tab row and no bar open)
        val where = if (gameUi.hasSelection || (tabBar != null && !onTabs)) "back" else "home"
        if (where != bSaid && tap(GuestPad.bnext(padSource(), home = where == "home"))) bSaid = where
    }

    private fun tap(cells: List<Pair<String, Int>>?): Boolean {
        val pad = padSource()
        return cells != null && pad != null && TapBridge.taps(context, pad, cells) { }
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(c: Canvas) {
        if (!held || onTabs) return                 // on the tabs: the tab row draws its own cursor
        val f = focus ?: return
        val t = nearest(targets(), f) ?: return
        val r = RectF(t.box.left, t.box.top - scroll.scrollY, t.box.right, t.box.bottom - scroll.scrollY)
        UiKit.focus(c, r)
        // A presses the option under the cursor: its glyph on the cursor's top-left corner, half over it (the room's house mark)
        val gs = A_GLYPH
        UiKit.pad(c, UiKit.okGlyph(), r.left - 0.35f * gs + gs / 2f, r.top - 0.35f * gs + gs / 2f, gs)
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = false   // touches reach the sections under it

    companion object {
        const val ID = "settings"
        const val ROW_SLOP = 12f
        const val MARGIN = 40f
        const val A_GLYPH = 42f
    }
}
