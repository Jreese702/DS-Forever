package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.jrees.wowcompanion.GroupFinderModel.Action
import com.jrees.wowcompanion.GroupFinderModel.ActivityText
import com.jrees.wowcompanion.GroupFinderModel.Display
import com.jrees.wowcompanion.GroupFinderModel.FilterRow
import com.jrees.wowcompanion.GroupFinderModel.ListingRow
import com.jrees.wowcompanion.GroupFinderModel.ListingView
import com.jrees.wowcompanion.GroupFinderModel.PostGate
import com.jrees.wowcompanion.GroupFinderModel.Result
import com.jrees.wowcompanion.GroupFinderModel.Row
import com.jrees.wowcompanion.GroupFinderModel.SearchGate
import com.jrees.wowcompanion.GroupFinderModel.SearchStatus
import com.jrees.wowcompanion.GroupFinderModel.Slot
import com.jrees.wowcompanion.GroupFinderModel.Tone
import com.jrees.wowcompanion.GroupFinderModel.WhoGate
import com.jrees.wowcompanion.GroupFinderModel.WhoOption
import com.jrees.wowcompanion.GroupFinderModel.WhoPlayer
import org.json.JSONObject

/**
 * THE GROUP FINDER ROOM (groupfinder session): the game's "Looking For Group" window (LFGParentFrame) with its three tabs, drawn
 * with kit calls only to UI Art's specs in `wow-companion-design/groupfinder/`:
 * - tab 1, CREATE LISTING (`LISTING_SPEC.md`, after `tools/kitpreview/ListingSample.kt`; user 2026-09-30: "B, the game's own
 *   window", Quests & Zones / Custom greyed with the reason, Back reads the game's Delist while listed);
 * - tab 2, the GROUP BROWSER (`SPEC.md`, after `FinderSample.kt`; the user's picks 2026-09-28: the list and the chosen entry side
 *   by side, the game's two drop-downs, only the actions that can be done);
 * - tab 3, the WHO LIST (`WHO_SPEC.md`, after `WhoSample.kt`; user 2026-09-30: "Only in Group Finder, like the game", no jumping
 *   between rooms).
 * B that leaves the room goes HOME (user, 2026-09-28 23:12). While the game's own window is open (every Who answer opens it),
 * B is the game's own close, pressed by the capture (the addon's spec.back); the next B is the room's (UI Art, told to the user).
 * LB / RB switch the tabs; the cursor reaches the tabs and A opens one (the Professions room's rule).
 *
 * The data is [GroupFinderModel] (the addon's `gfcat` / `gf` documents). Every game action is ONE tap on a pad row: `gf`
 * (search, invite, delist), `gfl` (the listing's roles and Post), `gfw` (the Who search and its invite), `gfclose` (the SECURE
 * cell that presses the game's own close button when the room is left by touch). Choosing a line, folding, ticking a draft or a
 * filter and scrolling are no game action. Send Message shows the Chat room with the name ready (the chat session's
 * `whisperTo`); nothing is sent to the game for it.
 */
class GroupFinderView(context: Context) : View(context), BLeadsHome {

    /** B that leaves the room: the activity shows Home */
    var onLeave: () -> Unit = {}
    /** Send Message: the Chat room with the name ready (the chat session's `whisperTo`) */
    var onWhisper: (String) -> Unit = {}
    /** the chat session's `ChatNames.canWhisper`: a name typed chat cannot spell leaves Send Message grey */
    var canWhisper: (String) -> Boolean = { true }

    var catalogue: GroupFinderModel.Catalogue? = null
        private set
    var state: GroupFinderModel.State? = null
        private set

    private companion object {
        /** a leave this soon after the visit's own open tap closes the game's window even before the addon reports it open */
        const val OPEN_CLOSE_MS = 3_000L      // (b85's limit; b89 closes on any leave of a visit that opened the window)
        /** the hold unconfirmed this long (the addon's ctl.held) = asked again */
        const val HOLD_CHECK_MS = 700L
        const val LISTING = 0
        const val BROWSE = 1
        const val WHO = 2
        val TAB_ICONS = listOf("tab_listing", "tab_browse", "tab_wholist")
        const val WHO_WAIT_MS = 6000L
        /** the game window's first tab report after the room comes on is its opening, not a switch (it never moves the room off tab 1) */
        const val LANDING_MS = 3000L
        const val PRESS_MIN_MS = 160L         // a press stays visibly DOWN at least this long (Interact's PressLook rule)
    }

    // ---------------------------------------------------------------- the app's own choices
    private var tab = LISTING                   // the shown tab; follows the game's window when it switches tab (not on its opening)
    private var lastWinTab: String? = null
    /** the room just came on: the game window's first tab report (our own open behind the room) does not move the room off tab 1 */
    private var landing = false
    // the Group Browser
    private var category: Int? = null           // the Category drop-down's value
    private var mineChosen = false              // "Your Listing" was the last choice
    private var ticked: Set<Int> = emptySet()   // Filter by activity (greys, never searches)
    private var chosen: String? = null          // the chosen line, by its leader's name (lines re-order; names do not)
    private val folded = HashSet<Boolean>()     // folded headings: true = Players, false = Groups
    private var open: String? = null            // "category", "activity", "group:<id>"; on Who "wfilter", "wclass", "wrace"
    private var listScroll = 0f
    private var listContent = 0f
    private var menuScroll = 0f
    private var menuContent = 0f
    private var menuView = 0f
    private var listBox = RectF()
    private val HOME_CLEAR = 40f
    // Create Listing
    private var backToCats = false              // the game's Back: the category buttons again (a category then starts a fresh draft)
    private var foldedGroups: Set<Int> = emptySet()
    private var foldedFor: Int? = null          // the category the folds were set for (the game folds on opening a category)
    private var actScroll = 0f
    private var actContent = 0f
    private var actBox = RectF()
    // the Who List
    private var whoChosen: String? = null
    private var whoScroll = 0f
    private var whoContent = 0f
    private var whoBox = RectF()
    private var whoSearching = false            // our search is on its way: the search square grey, "Searching..."
    private var whoAtAsked: Double? = null      // the answer's stamp when we searched: a new stamp ends the wait
    private val whoGiveUp = Runnable { whoSearching = false; invalidate() }

    // ---------------------------------------------------------------- the controller's glow
    private sealed class Fk {
        object Category : Fk()
        object Activity : Fk()
        object Search : Fk()
        object No : Fk()
        object WhoFilter : Fk()
        data class Heading(val solo: Boolean) : Fk()
        data class Line(val name: String) : Fk()
        data class Button(val i: Int) : Fk()        // 0 = the X button (the foot, or Post), 1 = the Y button (over it, or Back)
        data class Tab(val i: Int) : Fk()
        data class MenuRow(val i: Int) : Fk()
        data class Role(val i: Int) : Fk()          // Create Listing: Tank, Healer, Damage, New Players Welcome
        data class Cat(val id: Int) : Fk()          // Create Listing: a category button
        data class Act(val key: String) : Fk()      // Create Listing: "a<activityID>" or "g<groupID>"
        data class WhoLine(val name: String) : Fk()
    }

    private class Nav(val key: Fk, val r: RectF, val act: () -> Unit)

    private val nav = ArrayList<Nav>()          // every target, in full (the D-pad reaches lines out of view)
    private val hits = ArrayList<Nav>()         // what a finger can touch now (clipped to what shows)
    private val groupArrows = HashMap<Int, RectF>()

    // ---- PRESS FEEDBACK (the user, 2026-10-07: "Popups working, all other context buttons should have similar feedback"): Interact's
    // PressLook on the room's buttons. A finger shows its button DOWN while on it and PRESS_MIN_MS after it lifts; the controller's
    // A / X / Y shows the button it performs DOWN at once, never waiting for the game's document. Only the drawing changes.
    private val pressable = HashSet<Fk>()       // the buttons drawn usable this frame: only these are drawn pressed
    private var fingerKey: Fk? = null           // the button a finger is on
    private var pressKey: Fk? = null            // the last press keeps its DOWN look until pressUntil (uptime)
    private var pressUntil = 0L

    private fun isDown(k: Fk) = fingerKey == k || (pressKey == k && SystemClock.uptimeMillis() < pressUntil) || padNow.isDown(k)

    /** b117 (the user on b116: "the button push is delayed a moment after I push it"): [onPad] runs on the game's document, a
     *  round trip after the press. The app's own controller reader (PadHub, via Spells' PressFeedback) shows A / X / Y's button
     *  DOWN in the press's own frame; the drawing only, what the press does still comes from the document */
    private val padNow = PressFeedback(this).apply {
        padTarget = { k ->
            if (!shown || state?.controllerHeld != true) null
            else when (k) {
                "PAD1" -> focus?.takeIf { f -> nav.any { it.key == f && it.act != null } }
                "PAD3" -> if (open == null) Fk.Button(0) else null
                "PAD4" -> if (open == null) Fk.Button(1) else null
                else -> null
            }
        }
    }

    private fun flash(k: Fk?) {
        if (k == null || k !in pressable) return
        pressKey = k; pressUntil = SystemClock.uptimeMillis() + PRESS_MIN_MS
        invalidate()
        postInvalidateDelayed(PRESS_MIN_MS + 10L)
    }

    /** one of the game's red buttons with its glyph, pushed in while [key] is pressed (the kit's press: UiKit.button DOWN, art-kit) */
    private fun kitButton(c: Canvas, r: RectF, label: String, glyph: String, enabled: Boolean, key: Fk) {
        if (enabled) pressable += key
        val down = isDown(key)
        val st = if (down) UiKit.Button.DOWN else if (enabled) UiKit.Button.UP else UiKit.Button.DISABLED
        UiKit.button(c, r, label, glyph, st)
    }

    /** a kit piece with no pressed state of its own (search square, role, category): pushed in over [r] while [key] is pressed */
    private fun pushed(c: Canvas, r: RectF, enabled: Boolean, key: Fk, draw: () -> Unit) {
        if (enabled) pressable += key
        UiKit.pressed(c, r, isDown(key), draw)
    }
    private var focus: Fk? = null
    /**
     * where the cursor goes as soon as that target is drawn (the room just opened; a category opened its activities; Back showed the
     * categories): the user's ruling (LATER 19, 2026-09-30) "it should already be there", no press to make it appear
     */
    private var pendingFocus: (() -> Fk?)? = null
    private var lastPadN = -1

    // ---------------------------------------------------------------- the addon's documents; showing and leaving

    private var shown = false
    private var holdSent = false
    /** the room is being left with B: the release tells the capture "B" (interact's contract) */
    private var leftByB = false
    /** what the next B does, as last told to the capture (v3's bnext): "home" or "back"; null = not told yet */
    private var bTold: String? = null

    /** a `gf`, `gfcat` or `gfack` document (MainActivity routes them here) */
    fun onDoc(msg: JSONObject) {
        when (msg.optString("type")) {
            "gfcat" -> GroupFinderModel.parseCatalogue(msg)?.let { catalogue = it }
            "gf" -> GroupFinderModel.parseState(msg)?.let { s ->
                // a search the game's own window made shows its category in the drop-down, as the game's does
                val sc = s.search.category
                if (!s.search.mine && sc != null && s.search.status != SearchStatus.IDLE && sc != category) {
                    category = sc; mineChosen = false; ticked = emptySet()
                }
                // the game's window opened, or switched its tab: the room shows that tab (a Who answer opens its Who tab)
                val wt = s.windowTab
                if (landing && wt != null) landing = false          // the window our open showed: the room stays on its first tab
                else if (wt != null && wt != lastWinTab) when (wt) { "listing" -> showTab(LISTING); "browse" -> showTab(BROWSE); "who" -> showTab(WHO) }
                lastWinTab = wt
                // a new Who answer ends our wait
                if (whoSearching && s.who?.at != null && s.who.at != whoAtAsked) { whoSearching = false; removeCallbacks(whoGiveUp) }
                // listed: the game's Back is Delist now, never a step back to the categories
                if (s.listing.on) backToCats = false
                state = s
                if (shown && !holdSent) { holdSent = tap(s.keys.hold); if (holdSent) openWanted = true }
                openWhenHeld(s)
            }
            "gfack" -> if (msg.optString("op") == "who" && !msg.optBoolean("ok")) { whoSearching = false; removeCallbacks(whoGiveUp) }
        }
        invalidate()
    }

    /**
     * the room came on the screen: it takes the controller (the addon's `h`, no game action) and, every room alike (the user,
     * 2026-10-01: "Every room opens the game's own window behind it, as Professions does"), opens the game's own window behind it with
     * ONE tap on the secure open cell (a click on the game's own Group Finder button, in the game's code). Only while the window is
     * shut (the addon makes the cell inert while it is open, so it is never toggled shut), and only once the hold is REAL: capture v11
     * may queue the take behind another holder or a fight's enter (Home: "Never open the game window before a plain true or
     * state().holder == id"), so the open waits for a document that counts a new real hold
     */
    fun onShown() {
        shown = true; bTold = null
        padNow.setActive(true)
        // OPENS ON TAB 1 EVERY TIME, whatever the route (the user 2026-10-09: "it opens the second tab, but the game normally opens the
        // first tab by default"; LFGParentFrame's default), the cursor on that top tab (the tabs-open-on-landing rule)
        tab = LISTING; open = null; landing = true
        focus = Fk.Tab(LISTING)
        postDelayed({ landing = false }, LANDING_MS)             // no window report by then: later tab changes follow the game again
        openAfterTakes = state?.controllerTakes ?: 0
        holdSent = tap(state?.keys?.hold)
        openWanted = holdSent
        if (holdSent) holdCheck(0)
        invalidate()
    }

    /**
     * The hold, CONFIRMED (b88 22:40:50: the hold tap landed on the freelook park point - padstray n 5 - and the room showed without
     * the controller; Social's b83 race). Not held [HOLD_CHECK_MS] later while the room still shows = asked again, twice at most (the
     * addon's hold is idempotent).
     */
    private fun holdCheck(attempt: Int) {
        postDelayed({
            if (!shown || state?.controllerHeld == true || attempt >= 2) return@postDelayed
            val again = tap(state?.keys?.hold)
            Log.i("GroupFinder", "hold again (${attempt + 1}) ${if (again) "sent" else "not sent"}")
            holdCheck(attempt + 1)
        }, HOLD_CHECK_MS)
    }

    /** the window opens behind the room once the hold this show asked for is real (a new real hold, not queued or pending) */
    private var openWanted = false
    private var openAfterTakes = 0
    private fun openWhenHeld(s: GroupFinderModel.State) {
        if (!shown || !openWanted) return
        if (s.windowOpen == true) { openWanted = false; return }   // already open (the game's own key opened it): nothing to do
        if (!s.controllerHeld || s.controllerWait != null || s.controllerTakes <= openAfterTakes) return
        openWanted = false
        GroupFinderModel.openTap(s)?.let { if (TapBridge.tap(context, s.pad, GroupFinderModel.CLOSE_ROW, it)) openSentAt = System.currentTimeMillis() }
    }

    /** when this visit's open tap went out (0 = not this visit): a leave soon after closes the window even before the addon reports it */
    private var openSentAt = 0L

    /**
     * the room was left: the controller goes back (the addon's `f`); left while the game's own window is open (a Who answer opened
     * it), the SECURE cell presses the game's close button, once (never repeated on a missing answer)
     */
    fun onHidden() {
        padNow.setActive(false)
        // IN A FIGHT the room's own leave (the secure close, then `f`) is replaced by Interact's leave (capture i81, Home 2026-10-01):
        // nothing of ours is tapped there, and nothing is retried
        val fight = state?.combat == true
        if (fight) Log.i("GroupFinder", "left in a fight: Interact's leave lets go (no close / free tap of ours)")
        // b84 (21:52:39, Home as integrator - the user's go): B came 0.1 s before the window this visit OPENED; the addon's report
        // still said "shut", so no close went, the window stayed open behind Home and the room took the controller back (a second
        // B was needed). A leave within OPEN_CLOSE_MS of our own open tap closes too: the secure close is a no-op on a shut window,
        // and the one tap queue keeps it behind the open
        // b88 22:40:22: B 3.07 s after this visit's open, the addon's report still said "shut" and the window stayed open: any leave of a
        // visit that opened the window closes it (no time limit; the secure close is a no-op on a shut window)
        val openJust = openSentAt != 0L
        if (!fight && shown && (state?.windowOpen == true || openJust)) GroupFinderModel.closeTap(state)?.let { TapBridge.tap(context, state?.pad, GroupFinderModel.CLOSE_ROW, it) }
        val freeSent = !fight && shown && holdSent && tap(if (leftByB) state?.keys?.freeB ?: state?.keys?.free else state?.keys?.free)
        Log.i("GroupFinder", "left: shown $shown, hold sent $holdSent, by B $leftByB, window ${state?.windowOpen}, open just $openJust, free sent $freeSent")
        shown = false; holdSent = false; leftByB = false; bTold = null; open = null; focus = null; pendingFocus = null; openWanted = false
        openSentAt = 0L
    }

    /**
     * HOME'S SELF-HEAL (one rule for every room, 2026-09-30): the addon's NO-GAME-ACTION release of the shared capture (key `f`: the
     * room no longer wants the controller; the capture is released when it names "groupfinder"), sent WHATEVER this view's held / shown
     * flags say. Home calls it, at most once per 2 s, when a current interact doc names holder "groupfinder" while the room is not on
     * screen. It never opens or closes a game window and never presses anything in the game. True when the tap went out.
     */
    fun freeAgain(): Boolean {
        // in a fight a release is Interact's leave (capture i81 answers "tap leave"): ours is not sent there
        if (state?.combat == true) { Log.i("GroupFinder", "freeAgain: in a fight, Interact's leave does it; nothing sent"); return false }
        val col = state?.keys?.free
        val ok = tap(col)
        if (ok && !shown) holdSent = false
        Log.i("GroupFinder", "freeAgain: no-action free " + (if (ok) "sent (gf col $col)" else "NOT sent (col $col, state ${state != null})"))
        return ok
    }

    /**
     * where the next B leads: "back" while one of the room's menus is open (B closes it), else "home" (B is the game's close, then Home:
     * ONE B on every route, the user 2026-10-01). The addon takes the controller again with or without the game's close behind B.
     * Told on change only (no game action)
     */
    /** RC3: the app's own B read goes Home on the press when no menu is open (the same word [tellB] gives the addon) */
    override fun bLeadsHome(): Boolean = open == null

    private fun tellB() {
        if (!shown || !holdSent) return
        val now = if (open != null) "back" else "home"
        if (now == bTold) return
        val k = state?.keys ?: return
        if (tap(if (now == "home") k.bHome else k.bBack)) bTold = now
    }

    private fun tapOn(row: String, col: Int?): Boolean {
        val s = state ?: return false
        if (col == null) return false
        return TapBridge.tap(context, s.pad, row, col)
    }

    private fun tap(col: Int?): Boolean = tapOn(GroupFinderModel.PAD_ROW, col)
    private fun ltap(col: Int?): Boolean = tapOn(GroupFinderModel.LIST_ROW, col)
    private fun wtap(col: Int?): Boolean = tapOn(GroupFinderModel.WHO_ROW, col)

    /** a tab: by A on it, a touch, LB / RB, or the game's own window. Menus close; the glow goes to the tab when it was on */
    private fun showTab(i: Int) {
        if (i == tab) return
        tab = i
        open = null; menuScroll = 0f
        focus = Fk.Tab(i)
        invalidate()
    }

    private fun currentCategory(): GroupFinderModel.Category? = GroupFinderModel.category(catalogue, category)

    private fun searchGate(): SearchGate = GroupFinderModel.searchGate(state, currentCategory())

    /** Search Again: ONE game action, the search tap for the chosen category */
    private fun searchAgain() {
        if (searchGate() == SearchGate.OK) tap(currentCategory()?.searchSlot)
    }

    /** a Category row: the list closes AND the category is searched (ONE game action), as in the game */
    private fun chooseCategory(row: GroupFinderModel.CategoryRow) {
        closeMenu()
        chosen = null; listScroll = 0f
        val s = state
        if (row.mine) {
            // the game's SearchActiveEntry: my first activity's category, my activities in it ticked
            mineChosen = true
            category = s?.let { GroupFinderModel.categoryOfMine(catalogue, it) }
            val inCat = currentCategory()?.activities?.map { it.id }?.toSet() ?: emptySet()
            ticked = (s?.listing?.activities ?: emptyList()).filter { it in inCat }.toSet()
            if (s != null && s.enabled != false) tap(row.slot)
        } else {
            mineChosen = false
            if (category != row.category?.id) ticked = emptySet()
            category = row.category?.id
            if (searchGate() == SearchGate.OK) tap(row.slot)
        }
        invalidate()
    }

    private fun choose(name: String) {
        chosen = name
        invalidate()
    }

    private fun chosenResult(): Result? {
        val n = chosen ?: return null
        return state?.search?.results?.firstOrNull { it.leader == n }
    }

    private fun toggleMenu(which: String) {
        open = if (open == which) null else which
        menuScroll = 0f
        focus = if (open != null) Fk.MenuRow(menuStartRow()) else focusFor(which)
        invalidate()
    }

    /** where the cursor starts in an open menu: on the row already chosen (the Category list's current category), else the first */
    private fun menuStartRow(): Int {
        if (open != "category") return 0
        val i = GroupFinderModel.categoryRows(catalogue, state).indexOfFirst { if (it.mine) mineChosen else (!mineChosen && category == it.category?.id) }
        return if (i < 0) 0 else i
    }

    private fun closeMenu() {
        val was = open
        open = null; menuScroll = 0f
        focus = focusFor(was)
    }

    private fun focusFor(menu: String?): Fk = when {
        menu == "category" -> Fk.Category
        menu?.startsWith("w") == true -> Fk.WhoFilter
        else -> Fk.Activity
    }

    /** one step back out of a menu: a sub-menu to its parent, else the menu closes */
    private fun menuBack() {
        when {
            open?.startsWith("group:") == true -> { open = "activity"; menuScroll = 0f; focus = Fk.MenuRow(0) }
            open == "wclass" || open == "wrace" -> {
                val i = if (open == "wclass") 2 else 3
                open = "wfilter"; menuScroll = 0f; focus = Fk.MenuRow(i)
            }
            else -> closeMenu()
        }
    }

    // ---------------------------------------------------------------- drawing

    private val bmp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val ground = Paint().apply { color = Color.rgb(18, 14, 9) }
    private val rule = Paint().apply { color = Color.rgb(74, 58, 28) }

    private fun text(c: Canvas, s: String, x: Float, cy: Float, p: Paint) {
        c.drawText(s, x, cy - (p.ascent() + p.descent()) / 2f, p)
    }

    private fun add(key: Fk, r: RectF, act: () -> Unit) {
        val n = Nav(key, r, act)
        nav += n; hits += n
    }

    /** a target in a scrolled area: the D-pad knows its whole place, a finger only the part that shows */
    private fun addClipped(key: Fk, r: RectF, area: RectF, act: () -> Unit) {
        nav += Nav(key, r, act)
        val vis = RectF(r.left, maxOf(r.top, area.top), r.right, minOf(r.bottom, area.bottom))
        if (vis.bottom > vis.top) hits += Nav(key, vis, act)
    }

    override fun onDraw(c: Canvas) {
        nav.clear(); hits.clear(); groupArrows.clear(); pressable.clear()
        val w = width.toFloat(); val h = height.toFloat()
        c.drawColor(Theme.ink)
        UiKit.header(c, w, "Looking For Group")                 // the game's title
        val tabsX = w - 118f
        val right = tabsX - 4f
        when (tab) {
            LISTING -> drawListing(c, w, h, tabsX, right)
            WHO -> drawWho(c, w, h, tabsX, right)
            else -> drawBrowse(c, w, h, tabsX, right)
        }
        pendingFocus?.let { want -> want()?.let { f -> focus = f; pendingFocus = null } }
        drawFocus(c)
        tellB()
    }

    /**
     * the game's three tabs: Create Listing, Group Browser, Who List. The cursor reaches them and A opens one (the glow never
     * switches); LB / RB step through them, with the game's shoulder glyphs above the column while the capture delivers them
     */
    private fun tabs(c: Canvas, w: Float, tabsX: Float) {
        // b120 (the user on side tabs: "(a) No glyphs"): the tabs sit together, no gap and no LB/RB glyph; LB/RB still switch them
        val boxes = UiKit.tabColumn(TAB_ICONS.size, tabsX, 72f, height - 8f, gap = 2f)
        for ((i, t) in TAB_ICONS.withIndex()) {
            val r = boxes[i]
            // LATER 19 (the user's pick): the tab cursor is the tab's own lit frame, never the gold box (drawFocus skips tabs)
            UiKit.sideTab(c, r, UiKit.tex(t), i == tab, cursor = focus == Fk.Tab(i))
            // the glow's box kept on the screen (WHO_SPEC 5); the finger's box a little larger
            nav += Nav(Fk.Tab(i), RectF(tabsX + 2f, r.top + 4f, w - 10f, r.bottom - 4f)) { showTab(i) }
            hits += Nav(Fk.Tab(i), RectF(tabsX - 6f, r.top, w, r.bottom + 2f)) { showTab(i) }
        }
    }

    // ================================================================ tab 2: the Group Browser (SPEC 2 .. 5)

    private fun drawBrowse(c: Canvas, w: Float, h: Float, tabsX: Float, right: Float) {
        val split = right * 0.62f
        UiKit.pane(c, RectF(0f, 62f, split, h))
        UiKit.pane(c, RectF(split, 62f, right, h), right = true)
        UiKit.paneDivider(c, split, 62f, h)
        tabs(c, w, tabsX)
        val list = RectF(0f, 68f, split - 8f, h - 6f)
        val detail = RectF(split + 26f, 72f, right - 22f, h - 16f)
        head(c, list)
        body(c, list)
        detail(c, detail)
        when {
            open == "category" -> categoryMenu(c, split, right, h)
            open != null -> activityMenu(c, split, right, h)
        }
    }

    /** the head row: Category, Filter by activity, Search Again (the game's order, left to right) */
    private fun head(c: Canvas, list: RectF) {
        val x0 = 14f; val x1 = list.right - 14f
        val cw = (x1 - x0 - 92f - 20f) * 0.43f
        // switched off by the server, or the game says this character cannot use it (MEASURED on the Thor 2026-09-29 at level 5:
        // can = false, "LEVEL_TOO_LOW"; the game then refuses to open its own window at all)
        val off = state?.enabled == false || state?.canUse == false
        val cat = currentCategory()
        val catR = RectF(x0, list.top + 2f, x0 + cw, list.top + 94f)
        UiKit.dropDown(c, catR, cat?.name ?: "Category", open = open == "category", enabled = !off)
        val actR = RectF(x0 + cw + 10f, list.top + 2f, x1 - 102f, list.top + 94f)
        val label = when (val l = GroupFinderModel.filterLabel(catalogue, ticked)) {
            GroupFinderModel.FilterLabel.None -> "Filter by activity"
            is GroupFinderModel.FilterLabel.One -> l.name
            is GroupFinderModel.FilterLabel.Count -> if (l.n == 1) "1 activity selected" else "${l.n} activities selected"
        }
        UiKit.dropDown(c, actR, label, open = open != null && open != "category", enabled = cat != null && !off)
        val canSearch = searchGate() == SearchGate.OK
        pushed(c, RectF(x1 - 92f, list.top + 2f, x1, list.top + 94f), canSearch, Fk.Search) {
            UiKit.searchAgain(c, x1 - 46f, list.top + 48f, 92f, canSearch)
        }
        // touch boxes made 111 high (the floor for a finger)
        add(Fk.Category, RectF(catR.left, list.top - 8f, catR.right, list.top + 103f)) { if (!off) toggleMenu("category") }
        add(Fk.Activity, RectF(actR.left, list.top - 8f, actR.right, list.top + 103f)) {
            if (cat != null && !off) toggleMenu("activity")
        }
        val sx = x1 - 46f; val sy = list.top + 48f
        add(Fk.Search, RectF(sx - 55.5f, sy - 55.5f, sx + 55.5f, sy + 55.5f)) { searchAgain() }
    }

    /**
     * the game refuses the group finder to this character, or the realm has it off: UI Art's SPEC 4 (10:12), the game's own
     * FEATURE_NOT_YET_AVAILABLE and under it UI Art's line for LEVEL_TOO_LOW (the client does not say which level), or the
     * reason's code for any other reason. True when it drew the words
     */
    private fun refused(c: Canvas, r: RectF, s: GroupFinderModel.State): Boolean {
        if (s.enabled == false) { say(c, r, "The group finder is switched off on this realm."); return true }
        if (s.canUse != false) return false
        val first = "This feature is not yet available."
        say(c, r, first)
        val second = if (s.cannotReason == "LEVEL_TOO_LOW") "The game opens the group finder to higher levels." else s.cannotReason
        if (second != null) sayUnder(c, r, first, second)
        return true
    }

    /** the list pane: the lines, or the words that stand in their place (SPEC 4) */
    private fun body(c: Canvas, list: RectF) {
        listBox = RectF(list.left, list.top + 104f, list.right, list.bottom)
        val s = state
        val failed = s != null && (s.search.status == SearchStatus.FAILED || s.search.status == SearchStatus.TIMEOUT
            || s.search.status == SearchStatus.ERROR)
        val words: String? = when {
            s == null -> "Choose a category to see who is looking."
            s.lockdown -> "The game hides the list during a fight in an instance. It comes back after it."
            refused(c, list, s) -> return
            s.search.status == SearchStatus.SEARCHING -> {
                UiKit.searching(c, list.centerX(), list.top + 312f, "Searching...")
                return
            }
            failed -> "Search Failed. Please wait a moment and try again." + (s.search.why?.let { "\n\n$it" } ?: "")
            s.search.status == SearchStatus.IDLE -> "Choose a category to see who is looking."
            visibleRows(s).none { it is Row.Entry } ->
                "No groups found. If you can't find what you're looking for, consider starting a group."
            else -> null
        }
        if (words != null) { say(c, list, words); return }
        lines(c, list, s!!)
    }

    /** the model's rows (the game's order and headings), without the lines whose leader the game hides */
    private fun visibleRows(s: GroupFinderModel.State): List<Row> =
        GroupFinderModel.rows(s, ticked).filter { it !is Row.Entry || it.result.leader != null }

    private fun lines(c: Canvas, list: RectF, s: GroupFinderModel.State) {
        val area = listBox
        var y = area.top - listScroll
        var total = 0f
        var under: Boolean? = null
        c.save(); c.clipRect(area)
        for (row in visibleRows(s)) {
            when (row) {
                is Row.Divider -> {
                    under = row.solo
                    val r = RectF(12f, y, list.right - 12f, y + 56f)
                    val solo = row.solo
                    UiKit.listHeading(c, r, if (solo) "Players" else "Groups", open = solo !in folded, signRight = true, plateHeight = 50f)
                    addClipped(Fk.Heading(solo), r, area) { if (!folded.remove(solo)) folded.add(solo); invalidate() }
                    y += 56f; total += 56f
                }
                is Row.Entry -> {
                    val res = row.result
                    if (under != null && under in folded && !res.self) continue
                    val name = res.leader ?: continue
                    val r = RectF(12f, y, list.right - 12f, y + 100f)
                    if (r.bottom >= area.top && r.top <= area.bottom) drawLine(c, r, res, row.tone, s)
                    addClipped(Fk.Line(name), r, area) { choose(name) }
                    y += 100f; total += 100f
                }
            }
        }
        c.restore()
        // Home rises 34 px over the room's foot (x 550 .. 690, KIT.md 3): the list may scroll that much further, so
        // no line has to stay under it (seen on the emulator: the last line's roles sat under Home)
        listContent = total + HOME_CLEAR
    }

    private fun roleWord(r: String?): String? = when (r) {
        "TANK" -> "tank"; "HEALER" -> "heal"; "DAMAGER" -> "dps"; else -> null
    }

    private fun toneColour(t: Tone): Int = when (t) {
        Tone.MATCH -> Theme.finderMatch; Tone.SELF -> Theme.finderMine; else -> Theme.finderPlain
    }

    private fun activityWords(s: GroupFinderModel.State, res: Result): String =
        when (val t = GroupFinderModel.activityText(catalogue, s, res)) {
            ActivityText.Self -> "Your Listing"
            is ActivityText.One -> t.name
            is ActivityText.Many -> if (t.matching) "${t.count} matching activities" else "${t.count} activities"
        }

    /** one line: a player looking alone, or a group (SPEC 4's table) */
    private fun drawLine(c: Canvas, r: RectF, res: Result, tone: Tone, s: GroupFinderModel.State) {
        val act = activityWords(s, res)
        val off = tone == Tone.DIM
        val tc = toneColour(tone)
        val isChosen = chosen == res.leader
        val name = res.leader ?: ""
        val colour = Theme.classColourOf(res.leaderClass)
        val display = GroupFinderModel.display(catalogue, res)
        if (res.solo) {
            val roles = if (display != Display.SOLO_ROLES) emptyList() else res.soloRoles?.let {
                listOfNotNull(if (it.tank) "tank" else null, if (it.healer) "heal" else null, if (it.dps) "dps" else null)
            } ?: emptyList()
            UiKit.finderPlayer(c, r, name, colour, res.leaderLevel ?: 0, res.leaderClass ?: "", act, tc, roles,
                res.newPlayerFriendly, isChosen, off)
            return
        }
        var places: List<String> = emptyList()
        var counts: IntArray? = null
        var word: String? = null
        when (display) {
            Display.ROLE_ENUMERATE -> places = GroupFinderModel.roleIcons(catalogue, res).map {
                when (it) { Slot.TANK -> "tank"; Slot.HEALER -> "heal"; Slot.DPS -> "dps"; Slot.EMPTY -> "empty" }
            }
            Display.CLASS_ENUMERATE -> {
                val (_, maxPlayers) = GroupFinderModel.bestDisplay(catalogue, res)
                val classes = res.memberList.mapNotNull { m -> m.classFile?.let { "class_" + it.lowercase() } }.take(maxPlayers)
                places = List(maxOf(0, maxPlayers - classes.size)) { "empty" } + classes
            }
            Display.ROLE_COUNT -> res.have?.let { counts = intArrayOf(it.tank, it.healer, it.dps) }
            Display.PLAYER_COUNT -> word = GroupFinderModel.playerCount(res)?.toString()
            Display.DESCRIPTION -> word = "Has a description"
            else -> {}
        }
        UiKit.finderGroup(c, r, name, colour, act, tc, places = places, counts = counts, word = word,
            friendly = res.newPlayerFriendly, chosen = isChosen, off = off)
    }

    private fun layoutOf(words: String, size: Float, width: Float, colour: Int = Theme.dim,
                         align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL): StaticLayout =
        StaticLayout.Builder.obtain(words, 0, words.length, TextPaint(UiKit.bodyPaint(size, colour)), width.toInt())
            .setAlignment(align).setLineSpacing(6f, 1f).build()

    private fun say(c: Canvas, r: RectF, words: String) {
        val sl = layoutOf(words, 27f, r.width() - 140f)
        c.save(); c.translate(60f, r.top + 250f); sl.draw(c); c.restore()
    }

    /** a second, smaller line 40 px under the words [first] that [say] drew (bodyPaint 25, Theme.dim) */
    private fun sayUnder(c: Canvas, r: RectF, first: String, second: String) {
        val top = r.top + 250f + layoutOf(first, 27f, r.width() - 140f).height + 40f
        val sl = layoutOf(second, 25f, r.width() - 140f)
        c.save(); c.translate(60f, top); sl.draw(c); c.restore()
    }

    private fun nothing(c: Canvas, d: RectF) {
        val p = UiKit.bodyPaint(25f, Theme.dim).apply { textAlign = Paint.Align.CENTER }
        c.drawText("Nothing chosen", d.centerX(), d.top + 348f, p)
    }

    // ---------------------------------------------------------------- the chosen entry (SPEC 5)

    private fun detail(c: Canvas, d: RectF) {
        val s = state
        val res = chosenResult()
        if (s == null || res == null || s.lockdown) { nothing(c, d); return }
        val hasTwo = !res.self && GroupFinderModel.actions(s, res).any { it is Action.Invite }
        c.save(); c.clipRect(d.left - 20f, d.top, d.right + 20f, d.bottom - (if (hasTwo) 250f else 125f))
        if (res.solo) soloDetail(c, d, res) else groupDetail(c, d, res, s)
        c.restore()
        buttons(c, d, res, s)
    }

    /** a player looking alone: who, level and class in the game's words, roles, what for, newcomers welcome */
    private fun soloDetail(c: Canvas, d: RectF, res: Result) {
        var y = UiKit.playerHead(c, d, res.leaderClass ?: "", res.leader ?: "", Theme.classColourOf(res.leaderClass))
        val level = listOfNotNull(res.leaderLevel?.toString(), res.leaderClassName).joinToString(" ")
        y = UiKit.detailRow(c, d.left, y, d.right, "Level", level)
        val pl = UiKit.bodyPaint(25f, Theme.gold)
        text(c, "Roles", d.left + 8f, y, pl)
        var x = d.left + 140f
        res.soloRoles?.let { r ->
            for (role in listOfNotNull(if (r.tank) "tank" else null, if (r.healer) "heal" else null, if (r.dps) "dps" else null)) {
                UiKit.role(role)?.let { c.drawBitmap(it, null, RectF(x, y - 23f, x + 46f, y + 23f), bmp) }
                x += 52f
            }
        }
        y += 52f
        text(c, "Looking for", d.left + 8f, y, pl)
        y += 38f
        val pv = UiKit.bodyPaint(25f, Color.WHITE)
        for (id in res.activities) {
            text(c, GroupFinderModel.activity(catalogue, id)?.name ?: "", d.left + 28f, y, pv)
            y += 34f
        }
        if (res.newPlayerFriendly) { y += 12f; UiKit.finderWelcome(c, d.left, y, "New Players Welcome") }
    }

    /** a group: newcomers, delisted, its members (the leader first), how many, what for, a description exists */
    private fun groupDetail(c: Canvas, d: RectF, res: Result, s: GroupFinderModel.State) {
        var y = d.top + 34f
        if (res.newPlayerFriendly) y = UiKit.finderWelcome(c, d.left, y, "New Players Welcome")
        if (res.delisted) {
            text(c, "This group has been delisted.", d.left + 8f, y, UiKit.bodyPaint(25f, Theme.unusable))
            y += 50f
        }
        val members = res.memberList.sortedByDescending { it.leader }
        // two lines a member (UI Art a5807f4): the whole name over the pane's width, "Lvl 24 Warrior" under it (the game's class word)
        for (m in members) y = UiKit.finderMember(c, d.left, y, d.right, m.name ?: "", Theme.classColourOf(m.classFile), m.level ?: 0,
            roleWord(m.role), m.leader, classWord = m.className)
        if (members.isNotEmpty()) { UiKit.paneRule(c, d.left, y - 14f, d.right); y += 36f }
        text(c, "Members:", d.left + 8f, y, UiKit.bodyPaint(25f, Theme.gold))
        val n = res.members ?: 0
        val have = res.have
        val count = if (n > 10 || have == null) "$n" else "$n  (${have.tank}/${have.healer}/${have.dps})"
        text(c, count, d.left + 140f, y, UiKit.bodyPaint(25f, Color.WHITE))
        y += 48f
        val tone = GroupFinderModel.tone(s, res, ticked)
        val pa = UiKit.bodyPaint(25f, if (tone == Tone.DIM) Theme.finderOff else toneColour(tone))
        // an activity group's name, its activities 20 px further in
        val acts = res.activities.mapNotNull { GroupFinderModel.activity(catalogue, it) }
        for (a in acts.filter { it.group == null }) { text(c, a.name, d.left + 8f, y, pa); y += 34f }
        for ((gid, inGroup) in acts.filter { it.group != null }.groupBy { it.group!! }) {
            val gname = currentCategory()?.groups?.firstOrNull { it.id == gid }?.name
                ?: catalogue?.categories?.flatMap { it.groups }?.firstOrNull { it.id == gid }?.name ?: ""
            text(c, gname, d.left + 8f, y, pa); y += 34f
            for (a in inGroup) { text(c, a.name, d.left + 28f, y, pa); y += 34f }
        }
        y += 10f
        if (res.hasDescription) {
            text(c, "Has a description", d.left + 8f, y, UiKit.bodyPaint(23f, Theme.note))
            y += 30f
            text(c, "The game shows it on the top screen", d.left + 8f, y, UiKit.bodyPaint(20f, Theme.dim))
        }
    }

    /** "Only what can be done": a group, Send Message; a player alone I may invite, Group Invite with Send Message over it */
    private fun buttons(c: Canvas, d: RectF, res: Result, s: GroupFinderModel.State) {
        if (res.self) return                                        // my own listing: nothing to do from here
        val name = res.leader ?: return
        val acts = GroupFinderModel.actions(s, res)                 // the layout, before the chat rule
        val invite = acts.firstOrNull { it is Action.Invite } as Action.Invite?
        val whisper = acts.any { it is Action.Whisper }
        val spellable = canWhisper(name)
        val foot = RectF(d.left, d.bottom - 111f, d.right, d.bottom)
        val over = RectF(d.left, d.bottom - 236f, d.right, d.bottom - 125f)
        if (invite != null) {
            kitButton(c, foot, "Group Invite", "rev_x", true, Fk.Button(0))
            add(Fk.Button(0), foot) { tap(invite.slot) }
            kitButton(c, over, "Send Message", "rev_y", spellable, Fk.Button(1))
            add(Fk.Button(1), over) { if (spellable) onWhisper(name) }
        } else if (whisper) {
            kitButton(c, foot, "Send Message", "rev_x", spellable, Fk.Button(0))
            add(Fk.Button(0), foot) { if (spellable) onWhisper(name) }
        }
    }

    // ---------------------------------------------------------------- menus over the right-hand pane (SPEC 3, WHO_SPEC 4)

    private fun menuBox(c: Canvas, split: Float, right: Float, h: Float, title: String, height: Float): RectF {
        c.drawRect(split + 2f, 62f, right, h, ground)                  // the pane covered with its own dark ground
        val box = RectF(split + 8f, 70f, right - 6f, minOf(70f + height, h - 8f))
        UiKit.menu(c, box, solid = true)
        text(c, title, box.left + 36f, box.top + 52f, UiKit.bodyPaint(30f, Theme.gold))
        UiKit.divider(c, box.left + 20f, box.top + 91f, box.right - 20f, box.top + 91f)
        return box
    }

    /** an open menu: a touch outside it, or B, goes one step back (a sub-menu to its parent, else closed, without a search) */
    private fun menuCatcher() {
        nav.clear(); hits.clear()
        hits.add(Nav(Fk.No, RectF(0f, 0f, width.toFloat(), height.toFloat())) { menuBack(); invalidate() })
    }

    private fun categoryMenu(c: Canvas, split: Float, right: Float, h: Float) {
        menuCatcher()
        val rows = GroupFinderModel.categoryRows(catalogue, state)
        val box = menuBox(c, split, right, h, "Category", 100f + maxOf(rows.size, 1) * 106f + 80f)
        var y = box.top + 100f
        for ((i, row) in rows.withIndex()) {
            val r = RectF(box.left + 10f, y, box.right - 10f, y + 106f)
            val label = if (row.mine) "Your Listing" else row.category?.name ?: ""
            val on = if (row.mine) mineChosen else (!mineChosen && category == row.category?.id)
            UiKit.menuChoice(c, r, label, selected = focus == Fk.MenuRow(i), on = on, radio = true)
            add(Fk.MenuRow(i), r) { chooseCategory(row) }
            y += 106f
        }
        UiKit.hints(c, box.right - 28f, box.bottom - 56f, "rev_a" to "Search", "rev_b" to "Back")
    }

    /** a scrolled menu's area: the focused row kept in view */
    private fun menuArea(box: RectF, n: Int, rowH: Float): RectF {
        val area = RectF(box.left, box.top + 100f, box.right, box.bottom - 80f)
        menuContent = n * rowH
        menuView = area.height()
        (focus as? Fk.MenuRow)?.let { f ->
            val y0 = f.i * rowH - menuScroll
            if (y0 < 0f) menuScroll = f.i * rowH
            else if (y0 + rowH > menuView) menuScroll = (f.i + 1) * rowH - menuView
        }
        return area
    }

    /** Filter by activity: ticking greys the lines that do not match, it searches nothing; a group's arrow opens its own list */
    private fun activityMenu(c: Canvas, split: Float, right: Float, h: Float) {
        menuCatcher()
        val all = GroupFinderModel.filterRows(currentCategory())
        val gid = open?.removePrefix("group:")?.toIntOrNull()
        val sub = if (gid != null) all.filterIsInstance<FilterRow.Group>().firstOrNull { it.group.id == gid } else null
        val shown: List<FilterRow> = sub?.activities?.map { FilterRow.One(it) } ?: all
        val box = menuBox(c, split, right, h, sub?.group?.name ?: "Filter by activity", h - 70f - 8f)
        val area = menuArea(box, shown.size, 96f)
        c.save(); c.clipRect(area)
        for ((i, row) in shown.withIndex()) {
            val r = RectF(box.left + 10f, area.top + i * 96f - menuScroll, box.right - 10f, area.top + (i + 1) * 96f - menuScroll)
            val sel = focus == Fk.MenuRow(i)
            when (row) {
                is FilterRow.One -> {
                    UiKit.menuChoice(c, r, row.activity.name, selected = sel, on = row.activity.id in ticked)
                    addClipped(Fk.MenuRow(i), r, area) { toggleActivity(row.activity.id) }
                }
                is FilterRow.Group -> {
                    UiKit.menuChoice(c, r, row.group.name, selected = sel, on = GroupFinderModel.groupTicked(row, ticked), more = true)
                    addClipped(Fk.MenuRow(i), r, area) { ticked = GroupFinderModel.toggleGroup(row, ticked); invalidate() }
                    val arrow = RectF(r.right - 111f, r.top, r.right, r.bottom)
                    groupArrows[i] = arrow
                    val vis = RectF(arrow.left, maxOf(arrow.top, area.top), arrow.right, minOf(arrow.bottom, area.bottom))
                    if (vis.bottom > vis.top) hits += Nav(Fk.MenuRow(i), vis) { openGroup(row) }
                }
            }
        }
        c.restore()
        UiKit.hints(c, box.right - 28f, box.bottom - 56f, "rev_a" to "Tick", "rev_b" to "Back")
    }

    private fun toggleActivity(id: Int) {
        ticked = if (id in ticked) ticked - id else ticked + id
        invalidate()
    }

    private fun openGroup(g: FilterRow.Group) {
        open = "group:${g.group.id}"; menuScroll = 0f
        focus = Fk.MenuRow(0)
        invalidate()
    }

    // ================================================================ tab 1: Create Listing (LISTING_SPEC)

    private fun listingView(s: GroupFinderModel.State): ListingView {
        val v = GroupFinderModel.listingView(s)
        return if (v == ListingView.ACTIVITIES && backToCats && !s.listing.on) ListingView.CATEGORIES else v
    }

    private fun drawListing(c: Canvas, w: Float, h: Float, tabsX: Float, right: Float) {
        UiKit.pane(c, RectF(0f, 62f, right, h))                          // ONE pane, no split: the game's column
        tabs(c, w, tabsX)
        val s = state
        val pane = RectF(0f, 68f, right, h)
        if (s == null) { say(c, pane, "Waiting for the game."); return }
        if (refused(c, pane, s)) return
        val v = listingView(s)
        if (v == ListingView.LOCKED) { locked(c, s); return }
        roles(c, right, s)
        if (v == ListingView.CATEGORIES) listingCategories(c, right, s) else activities(c, right, s)
        foot(c, right, h, s, v)
    }

    /**
     * the roles band (the game's RolesSection): Tank / Healer / Damage and New Players Welcome. Solo, the roles are the player's own
     * (`rt` / `rh` / `rd`, the game's SetRoles: one tap each); in a group the game swaps them for a drop-down the addon does not
     * drive, so they show greyed. New Players Welcome is the draft's (`ln`), sent with Post
     */
    private fun roles(c: Canvas, right: Float, s: GroupFinderModel.State) {
        c.drawRect(0f, 62f, right, 264f, ground)
        c.drawRect(0f, 262f, right, 264f, rule)
        val keys = s.draft?.keys ?: emptyMap()
        val published = GroupFinderModel.listRowPublished(s)
        val roles = s.roles
        for ((i, r) in listOf(Triple("tank", "Tank", "rt"), Triple("heal", "Healer", "rh"), Triple("dps", "Damage", "rd")).withIndex()) {
            val x = 60f + i * 170f
            val on = when (r.first) { "tank" -> roles?.tank; "heal" -> roles?.healer; else -> roles?.dps } == true
            val col = keys[r.third]
            val enabled = !s.inGroup && col != null && published
            pushed(c, RectF(x - 12f, 76f, x + 116f, 236f), enabled, Fk.Role(i)) {
                UiKit.roleButton(c, x, 86f, 104f, r.first, on, label = r.second, enabled = enabled)
            }
            add(Fk.Role(i), RectF(x - 22f, 76f, x + 126f, 256f)) { if (enabled) ltap(col) }
        }
        val nx = right - 420f
        pushed(c, RectF(nx, 86f, right - 10f, 190f), published, Fk.Role(3)) {
            UiKit.newcomerButton(c, nx, 86f, 104f, on = s.draft?.newPlayers == true)
        }
        add(Fk.Role(3), RectF(nx - 10f, 76f, right - 10f, 200f)) { if (published) ltap(keys["ln"]) }
    }

    private fun categoryPicture(id: Int): String = when (id) {
        2 -> "finder_cat_dungeons"; 114 -> "finder_cat_raids"; 116 -> "finder_cat_questing"; 118 -> "finder_cat_pvp"
        else -> "finder_cat_custom"
    }

    /** the category view: the game's buttons in one column; the two that need a typed description greyed, with the reason */
    private fun listingCategories(c: Canvas, right: Float, s: GroupFinderModel.State) {
        val bw = 620f
        val x = (right - bw) / 2f
        for ((i, lc) in GroupFinderModel.listingCategories(catalogue, s).withIndex()) {
            val r = RectF(x, 280f + i * 104f, x + bw, 280f + i * 104f + 96f)
            val id = lc.category.id
            val off = lc.needsDescription
            pushed(c, r, !off && lc.slot != null, Fk.Cat(id)) {
                UiKit.categoryButton(c, r, categoryPicture(id), lc.category.name, off = off,
                    reason = if (off) "Needs a description: list it in the game" else null)
            }
            add(Fk.Cat(id), RectF(r.left, r.top - 4f, r.right, r.bottom + 4f)) {
                if (!off && lc.slot != null && ltap(lc.slot)) {
                    // the game's category button: a fresh draft in that category (the addon's `c<cat>`); the list opens folded as the game's
                    backToCats = false; actScroll = 0f
                    foldedGroups = GroupFinderModel.foldedAtStart(lc.category, catalogue?.level); foldedFor = id
                    // on the first activity once the game's answer draws them; no answer in 2 s: the usual start, so it never stays hidden
                    val t0 = System.currentTimeMillis()
                    focus = null; pendingFocus = { nav.firstOrNull { it.key is Fk.Act }?.key ?: if (System.currentTimeMillis() - t0 > 2000) firstTarget() else null }
                    postDelayed({ invalidate() }, 2100)
                }
            }
        }
    }

    /** the activity view: the category's name, Show All Level Ranges (read, never written), the list, the description box */
    private fun activities(c: Canvas, right: Float, s: GroupFinderModel.State) {
        val d = s.draft ?: return
        val cat = GroupFinderModel.category(catalogue, d.category)
        text(c, cat?.name ?: "", 22f, 296f, UiKit.titlePaint(34f))
        val ps = UiKit.bodyPaint(21f, Theme.dim).apply { textAlign = Paint.Align.RIGHT }
        text(c, "Show All Level Ranges", right - 84f, 296f, ps)
        UiKit.check(c, right - 45f, 296f, 42f, catalogue?.noLevelFilter == true)
        c.drawRect(12f, 326f, right - 22f, 328f, rule)
        // whole rows only (LISTING_SPEC 4b: "while y + 64 <= 700"): five show above the description's label, the rest drag into view
        val area = RectF(12f, 336f, right - 30f, 336f + ((700f - 336f) / 64f).toInt() * 64f)
        actBox = area
        if (cat != null && foldedFor != cat.id) { foldedGroups = GroupFinderModel.foldedAtStart(cat, catalogue?.level); foldedFor = cat.id }
        val rows = GroupFinderModel.listingRows(cat, foldedGroups)
        val tickCol = d.ticks.associate { it.id to it.slot }
        val groupCol = d.groupTicks.associate { it.group to it.slot }
        val on = d.activities.toSet()
        var y = area.top - actScroll
        c.save(); c.clipRect(area)
        for (row in rows) {
            val r = RectF(area.left, y, area.right, y + 64f)
            val visible = r.bottom >= area.top && r.top <= area.bottom
            when (row) {
                is ListingRow.Group -> {
                    val gid = row.group.id
                    if (visible) UiKit.activityRow(c, r, row.group.name, null, row.activities.any { it.id in on }, group = true, open = row.open,
                        foldable = row.foldable)
                    addClipped(Fk.Act("g$gid"), r, area) { ltap(groupCol[gid]) }
                    if (row.foldable) {
                        // the game's minus / plus: folds the group (the app's alone); the finger's box over it wins over the tick
                        val fold = RectF(r.left, r.top, r.left + 50f, r.bottom)
                        val vis = RectF(fold.left, maxOf(fold.top, area.top), fold.right, minOf(fold.bottom, area.bottom))
                        if (vis.bottom > vis.top) hits += Nav(Fk.Act("g$gid"), vis) {
                            foldedGroups = if (gid in foldedGroups) foldedGroups - gid else foldedGroups + gid
                            invalidate()
                        }
                    }
                }
                is ListingRow.One -> {
                    val a = row.activity
                    if (visible) UiKit.activityRow(c, r, a.name, GroupFinderModel.levelText(a), a.id in on, saved = a.saved)
                    addClipped(Fk.Act("a${a.id}"), r, area) { ltap(tickCol[a.id]) }
                }
            }
            y += 64f
        }
        c.restore()
        actContent = rows.size * 64f
        if (actContent > area.height()) {
            actScroll = actScroll.coerceIn(0f, actContent - area.height())
            UiKit.scrollBar(c, RectF(right - 20f, 336f, right - 8f, 700f), actScroll / actContent, (actScroll + area.height()) / actContent)
        } else actScroll = 0f
        // the game protects the box: the room never shows or writes the text, it only says whether the game's window holds some
        UiKit.descriptionBox(c, RectF(180f, 742f, right - 180f, 808f), "More details about your group (optional)", d.descriptionTyped)
    }

    /** the game's words for a grey Post (LISTING_SPEC 6); null when Post works */
    private fun postWhy(gate: PostGate, s: GroupFinderModel.State): String? = when (gate) {
        PostGate.OK -> null
        PostGate.NO_CATEGORY -> "Make a selection."
        PostGate.NEEDS_DESCRIPTION -> "Needs a description: list it in the game"
        PostGate.TOO_MANY -> "You've selected too many activities."
        PostGate.GROUP_TOO_BIG -> "Your group already has the maximum number of members (${GroupFinderModel.groupTooBig(catalogue, s) ?: 0}) for this activity."
        PostGate.NO_ACTIVITY -> "Tick an activity"
        PostGate.NOTHING_CHANGED -> "Nothing changed"
        PostGate.WAITING -> "Waiting for the game"
        PostGate.COMBAT -> "Not in combat"
        PostGate.NO_COLUMN -> "Not ready yet"
        PostGate.LOCKED -> null
    }

    /**
     * Back (the game's Delist while listed) bottom left, Post bottom right in the game's word. Back steps back to the categories only
     * in the activity view (the game's UpdateBackButtonEnableState); listed, it ends the listing: ONE game action (the `gf` row's
     * delist), the game's own button (user: "Yes, as the game")
     */
    private fun foot(c: Canvas, right: Float, h: Float, s: GroupFinderModel.State, v: ListingView) {
        val fy = h - 118f
        val listed = GroupFinderModel.backUnlists(s)
        val backOk = if (listed) s.listing.leader && s.delistSlot != null && GroupFinderModel.padRowPublished(s) && !s.combat
                     else v == ListingView.ACTIVITIES
        val backR = RectF(18f, fy, 268f, fy + 100f)
        kitButton(c, backR, if (listed) "Delist" else "Back", "rev_y", backOk, Fk.Button(1))
        add(Fk.Button(1), backR) {
            if (!backOk) return@add
            if (listed) tap(s.delistSlot)
            else { backToCats = true; focus = null; pendingFocus = { nav.firstOrNull { it.key is Fk.Cat }?.key }; invalidate() }
        }
        val raw = GroupFinderModel.postGate(catalogue, s)
        val gate = if (v == ListingView.CATEGORIES && raw !in setOf(PostGate.NO_COLUMN, PostGate.WAITING, PostGate.COMBAT)) PostGate.NO_CATEGORY else raw
        val word = when (GroupFinderModel.postWord(s)) {
            GroupFinderModel.PostWord.UPDATE -> "Update"            // LFG_POST_GROUP_UPDATE
            GroupFinderModel.PostWord.PARTY -> "List Group"         // LFG_POST_GROUP_PARTY
            GroupFinderModel.PostWord.SOLO -> "List Self"           // LFG_POST_GROUP_SOLO
        }
        val post = RectF(right - 308f, fy, right - 18f, fy + 100f)
        kitButton(c, post, word, "rev_x", gate == PostGate.OK, Fk.Button(0))
        add(Fk.Button(0), post) { if (gate == PostGate.OK) ltap(s.draft?.keys?.get("lp")) }
        // why Post is grey (or the game's last refusal, when it works again), at most two lines 500 px wide, beside it
        val why = postWhy(gate, s) ?: s.draft?.why
        if (why != null) {
            val sl = layoutOf(why, 22f, 500f, Theme.dim, Layout.Alignment.ALIGN_OPPOSITE)
            val lines = minOf(sl.lineCount, 2)
            val hgt = sl.getLineBottom(lines - 1).toFloat()
            c.save(); c.translate(post.left - 20f - 500f, post.centerY() - hgt / 2f)
            c.clipRect(0f, 0f, 500f, hgt); sl.draw(c); c.restore()
        }
    }

    /** not the leader (the game's LockedView): its sentence and, while the group is listed, what it is listed for (at most 13) */
    private fun locked(c: Canvas, s: GroupFinderModel.State) {
        val p = UiKit.bodyPaint(27f, Theme.gold)
        if (!s.listing.on) { text(c, "Only the group leader can create a listing for your group.", 60f, 140f, p); return }
        text(c, "Only the group leader can modify your group's listing.", 60f, 140f, p)
        text(c, "Your group is currently listed for:", 60f, 214f, p)
        val pa = UiKit.bodyPaint(26f, Color.WHITE)
        for ((i, id) in s.listing.activities.take(13).withIndex())
            text(c, GroupFinderModel.activity(catalogue, id)?.name ?: "", 100f, 270f + i * 46f, pa)
    }

    // ================================================================ tab 3: the Who List (WHO_SPEC)

    private fun drawWho(c: Canvas, w: Float, h: Float, tabsX: Float, right: Float) {
        val split = right * 0.62f
        UiKit.pane(c, RectF(0f, 62f, split, h))
        UiKit.pane(c, RectF(split, 62f, right, h), right = true)
        UiKit.paneDivider(c, split, 62f, h)
        tabs(c, w, tabsX)
        val list = RectF(0f, 68f, split - 8f, h - 6f)
        val detail = RectF(split + 26f, 72f, right - 22f, h - 16f)
        val s = state
        val who = s?.who
        whoHead(c, list, s)
        whoBody(c, list, who)
        whoDetail(c, detail, s, who)
        when (open) {
            "wfilter" -> whoFilterMenu(c, split, right, h, who)
            "wclass" -> whoOptionMenu(c, split, right, h, "Class", who?.filter?.classes ?: emptyList(),
                who?.filter?.classesAllSlot, who?.filter?.classesNoneSlot)
            "wrace" -> whoOptionMenu(c, split, right, h, "Race", who?.filter?.races ?: emptyList(),
                who?.filter?.racesAllSlot, who?.filter?.racesNoneSlot)
        }
    }

    /** the head row: the game's filter drop-down reading what it is set to, and its search square (the magnifier) */
    private fun whoHead(c: Canvas, list: RectF, s: GroupFinderModel.State?) {
        val f = s?.who?.filter
        val r = RectF(14f, list.top + 2f, list.right - 116f, list.top + 94f)
        UiKit.dropDown(c, r, f?.let { GroupFinderModel.whoFilterWords(it) } ?: "All players",
            open = open?.startsWith("w") == true, enabled = f != null)
        val canSearch = GroupFinderModel.whoSearchGate(s) == WhoGate.OK && !whoSearching
        val cx = list.right - 60f; val cy = list.top + 48f
        pushed(c, RectF(cx - 46f, cy - 46f, cx + 46f, cy + 46f), canSearch, Fk.Search) {
            UiKit.searchAgain(c, cx, cy, 92f, canSearch, icon = "search_glass")
        }
        add(Fk.WhoFilter, RectF(r.left, list.top - 8f, r.right, list.top + 103f)) { if (f != null) toggleMenu("wfilter") }
        add(Fk.Search, RectF(cx - 55.5f, cy - 55.5f, cx + 55.5f, cy + 55.5f)) { whoSearch() }
    }

    /** the Who search: ONE game action with the filter as it stands (the menu only sets it, as the game's does) */
    private fun whoSearch() {
        val s = state ?: return
        if (whoSearching || GroupFinderModel.whoSearchGate(s) != WhoGate.OK) return
        if (!wtap(s.who?.filter?.searchSlot)) return
        whoSearching = true
        whoAtAsked = s.who?.at
        whoChosen = null; whoScroll = 0f
        removeCallbacks(whoGiveUp); postDelayed(whoGiveUp, WHO_WAIT_MS)
        invalidate()
    }

    private fun sayCentred(c: Canvas, list: RectF, words: String) {
        val p = UiKit.bodyPaint(26f, Theme.dim).apply { textAlign = Paint.Align.CENTER }
        c.drawText(words, list.centerX(), list.top + 380f, p)
    }

    private fun whoLine2(p: WhoPlayer): String =
        listOfNotNull(p.level?.let { "Level $it" }, p.race, p.className).joinToString(" ")

    /** the list pane: the players (the whole line is the touch target), clipped above the game's totals line */
    private fun whoBody(c: Canvas, list: RectF, who: GroupFinderModel.Who?) {
        whoBox = RectF(list.left, list.top + 104f, list.right, list.bottom - 60f)
        when {
            whoSearching -> { UiKit.searching(c, list.centerX(), list.top + 312f, "Searching..."); return }
            who == null || who.none -> { sayCentred(c, list, "Choose who to look for, then search."); return }
        }
        val pt = UiKit.bodyPaint(23f, Theme.dim).apply { textAlign = Paint.Align.CENTER }
        text(c, GroupFinderModel.whoTotals(who!!), list.centerX(), list.bottom - 30f, pt)
        if (who.players.isEmpty()) { sayCentred(c, list, "Nobody found. Change the filter and search again."); return }
        val area = whoBox
        var y = area.top - whoScroll
        c.save(); c.clipRect(area)
        for (p in who.players) {
            val r = RectF(12f, y, list.right - 12f, y + 100f)
            if (r.bottom >= area.top && r.top <= area.bottom)
                UiKit.playerLine(c, r, "online", p.name, Theme.classColourOf(p.classFile), whoLine2(p), whoChosen == p.name,
                    word = p.guild?.let { "<$it>" })
            addClipped(Fk.WhoLine(p.name), r, area) { whoChosen = p.name; invalidate() }
            y += 100f
        }
        c.restore()
        whoContent = who.players.size * 100f
    }

    /** the chosen player: the Browse tab's player detail with the Who's rows; Group Invite (X) the ONE game action, Send Message (Y) */
    private fun whoDetail(c: Canvas, d: RectF, s: GroupFinderModel.State?, who: GroupFinderModel.Who?) {
        val p = who?.players?.firstOrNull { it.name == whoChosen }
        if (s == null || p == null || whoSearching) { nothing(c, d); return }
        c.save(); c.clipRect(d.left - 20f, d.top, d.right + 20f, d.bottom - 250f)
        var y = UiKit.playerHead(c, d, p.classFile ?: "", p.name, Theme.classColourOf(p.classFile))
        y = UiKit.detailRow(c, d.left, y, d.right, "Level", listOfNotNull(p.level?.toString(), p.className).joinToString(" "))
        p.race?.let { y = UiKit.detailRow(c, d.left, y, d.right, "Race", it) }
        p.zone?.let { y = UiKit.detailRow(c, d.left, y, d.right, "Zone", it) }
        p.guild?.let { UiKit.detailRow(c, d.left, y, d.right, "Guild", "<$it>") }
        c.restore()
        val invite = GroupFinderModel.whoActions(s, p).firstOrNull { it is Action.Invite } as Action.Invite?
        val spellable = canWhisper(p.name)
        val foot = RectF(d.left, d.bottom - 111f, d.right, d.bottom)
        val over = RectF(d.left, d.bottom - 236f, d.right, d.bottom - 125f)
        kitButton(c, foot, "Group Invite", "rev_x", invite != null, Fk.Button(0))
        add(Fk.Button(0), foot) { if (invite != null) wtap(invite.slot) }
        kitButton(c, over, "Send Message", "rev_y", spellable, Fk.Button(1))
        add(Fk.Button(1), over) { if (spellable) onWhisper(p.name) }
    }

    /** the game's filter: My level, My zone (ticks), then Class and Race, each a menu of its own. A tick is no game action */
    private fun whoFilterMenu(c: Canvas, split: Float, right: Float, h: Float, who: GroupFinderModel.Who?) {
        menuCatcher()
        val f = who?.filter ?: return
        val box = menuBox(c, split, right, h, "Filter", 100f + 4 * 106f + 80f)
        var y = box.top + 100f
        val rows = listOf(
            Triple("My level", f.level, { wtap(f.levelSlot); Unit }),
            Triple("My zone", f.zone, { wtap(f.zoneSlot); Unit }),
            Triple("Class", false, { open = "wclass"; menuScroll = 0f; focus = Fk.MenuRow(0) }),
            Triple("Race", false, { open = "wrace"; menuScroll = 0f; focus = Fk.MenuRow(0) }))
        for ((i, row) in rows.withIndex()) {
            val r = RectF(box.left + 10f, y, box.right - 10f, y + 106f)
            val menu = i >= 2
            UiKit.menuChoice(c, r, row.first, selected = focus == Fk.MenuRow(i), on = row.second, more = menu, mark = !menu)
            add(Fk.MenuRow(i), r) { row.third(); invalidate() }
            y += 106f
        }
        UiKit.hints(c, box.right - 28f, box.bottom - 56f, "rev_a" to "Tick", "rev_b" to "Back")
    }

    /** Class / Race: the game's Check All / Uncheck All (one tap each), then a tick for each; the list drags */
    private fun whoOptionMenu(c: Canvas, split: Float, right: Float, h: Float, title: String, opts: List<WhoOption>,
                              allSlot: Int?, noneSlot: Int?) {
        menuCatcher()
        val box = menuBox(c, split, right, h, title, h - 70f - 8f)
        val area = menuArea(box, opts.size + 2, 96f)
        c.save(); c.clipRect(area)
        for (i in 0 until opts.size + 2) {
            val r = RectF(box.left + 10f, area.top + i * 96f - menuScroll, box.right - 10f, area.top + (i + 1) * 96f - menuScroll)
            val sel = focus == Fk.MenuRow(i)
            when (i) {
                0 -> { UiKit.menuChoice(c, r, "Check All", selected = sel, on = false, mark = false); addClipped(Fk.MenuRow(0), r, area) { wtap(allSlot) } }
                1 -> { UiKit.menuChoice(c, r, "Uncheck All", selected = sel, on = false, mark = false); addClipped(Fk.MenuRow(1), r, area) { wtap(noneSlot) } }
                else -> {
                    val o = opts[i - 2]
                    UiKit.menuChoice(c, r, o.name, selected = sel, on = o.on, enabled = o.slot != null)
                    addClipped(Fk.MenuRow(i), r, area) { wtap(o.slot) }
                }
            }
        }
        c.restore()
        UiKit.hints(c, box.right - 28f, box.bottom - 56f, "rev_a" to "Tick", "rev_b" to "Back")
    }

    // ---------------------------------------------------------------- the glow

    /** the scrolled area a target lives in, if any */
    private fun areaOf(f: Fk): RectF? = when (f) {
        is Fk.Line, is Fk.Heading -> listBox
        is Fk.Act -> actBox
        is Fk.WhoLine -> whoBox
        else -> null
    }

    private fun drawFocus(c: Canvas) {
        val f = focus ?: return
        if (f is Fk.MenuRow) return                                  // a menu row glows by its own call
        if (f is Fk.Line && f.name == chosen) return                 // the chosen line's own gold ground
        if (f is Fk.WhoLine && f.name == whoChosen) return
        if (f is Fk.Tab) return                                      // LATER 19: the tab draws its own cursor (sideTab cursor)
        val n = nav.firstOrNull { it.key == f } ?: return
        areaOf(f)?.let { a -> if (n.r.bottom < a.top || n.r.top > a.bottom) return }
        UiKit.focus(c, if (f is Fk.Act) RectF(n.r.left + 40f, n.r.top + 2f, n.r.right, n.r.bottom - 2f) else n.r)
    }

    // ---------------------------------------------------------------- touch

    private var downY = 0f
    private var lastY = 0f
    private var downX = 0f
    private var moved = false
    private var scrollKey: String? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private fun scrolledMenu(): Boolean = open != null && open != "category" && open != "wfilter"

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastY = e.y; moved = false
                fingerKey = hits.lastOrNull { it.r.contains(e.x, e.y) }?.key?.takeIf { it in pressable }
                if (fingerKey != null) invalidate()
                scrollKey = when {
                    scrolledMenu() -> "menu"
                    open != null -> null
                    tab == BROWSE && listBox.contains(e.x, e.y) -> "list"
                    tab == LISTING && actBox.contains(e.x, e.y) -> "acts"
                    tab == WHO && whoBox.contains(e.x, e.y) -> "who"
                    else -> null
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && (Math.abs(e.y - downY) > slop || Math.abs(e.x - downX) > slop)) { moved = true; fingerKey = null; invalidate() }
                if (moved) {
                    val dy = lastY - e.y
                    when (scrollKey) {
                        "list" -> listScroll = (listScroll + dy).coerceIn(0f, maxOf(0f, listContent - listBox.height()))
                        "menu" -> menuScroll = (menuScroll + dy).coerceIn(0f, maxOf(0f, menuContent - menuView))
                        "acts" -> actScroll = (actScroll + dy).coerceIn(0f, maxOf(0f, actContent - actBox.height()))
                        "who" -> whoScroll = (whoScroll + dy).coerceIn(0f, maxOf(0f, whoContent - whoBox.height()))
                    }
                    invalidate()
                }
                lastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                // the cursor stays on screen and follows the finger: it goes to what was touched (the action may move it on)
                fingerKey = null
                if (!moved) hits.lastOrNull { it.r.contains(e.x, e.y) }?.let { h ->
                    if (h.key != Fk.No) { focus = h.key; pendingFocus = null }
                    flash(h.key)                                    // DOWN a moment after the lift (PRESS_MIN_MS)
                    h.act()
                }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { fingerKey = null; invalidate() }
        }
        return true
    }

    // ---------------------------------------------------------------- the controller (SPEC 6, LISTING_SPEC 6, WHO_SPEC 5)

    /** a press the addon relayed ({"type":"gfpad"}); only while this room shows (MainActivity checks) */
    fun onPad(msg: JSONObject) {
        val n = msg.optInt("n", -1)
        if (n == lastPadN) return                                   // the same press seen twice
        lastPadN = n
        if (msg.has("lost")) { invalidate(); return }
        val k = msg.optString("k")
        // LB / RB: the previous / next tab, from anywhere but an open menu (the Professions room's rule; no wrap)
        if (k == "PADLSHOULDER" || k == "PADRSHOULDER") {
            if (open == null) {
                val to = tab + if (k == "PADLSHOULDER") -1 else 1
                if (to in LISTING..WHO) showTab(to)
            }
            invalidate(); return
        }
        if (focus == null || nav.none { it.key == focus }) {
            focus = firstTarget()
            if (k.startsWith("PADD")) { invalidate(); return }      // the first press only shows where the glow is
        }
        when (k) {
            "PADDUP" -> move(0, -1)
            "PADDDOWN" -> move(0, 1)
            "PADDLEFT" -> move(-1, 0)
            "PADDRIGHT" -> move(1, 0)
            // A / X / Y show the button they perform DOWN at once (flash: the drawing only, the user 2026-10-07)
            "PAD1" -> { flash(focus); nav.firstOrNull { it.key == focus }?.act?.invoke() }       // A
            "PAD2" -> back()                                                                     // B
            "PAD3" -> if (open == null) { flash(Fk.Button(0)); nav.firstOrNull { it.key == Fk.Button(0) }?.act?.invoke() }   // X: the foot button / Post
            "PAD4" -> if (open == null) { flash(Fk.Button(1)); nav.firstOrNull { it.key == Fk.Button(1) }?.act?.invoke() }   // Y: the one over it / Back
        }
        invalidate()
    }

    /**
     * Where the cursor starts (LATER 19, the user: "when opening to a page that has tabs and something already highlighted because its
     * selected, that feels like a really great natural starting point for the cursor"): on the chosen line when there is one, else on
     * the tab that is open; in a menu, on the row already chosen
     */
    private fun firstTarget(): Fk = when {
        open != null -> Fk.MenuRow(menuStartRow())
        tab == BROWSE && chosen != null && nav.any { it.key == Fk.Line(chosen!!) } -> Fk.Line(chosen!!)
        tab == WHO && whoChosen != null && nav.any { it.key == Fk.WhoLine(whoChosen!!) } -> Fk.WhoLine(whoChosen!!)
        else -> Fk.Tab(tab)
    }

    /**
     * B that reaches the room: an open menu goes one step back; otherwise B leaves for HOME. ONE B on every route (the user, 2026-10-01:
     * rooms act the exact same): with the capture's binding B is the game's own close and the addon lets go with "B", so it only reaches
     * the room while a menu is open, or with an older capture. On Create Listing B never delists and never steps back a view.
     */
    private fun back() {
        // b87 22:33 (the user: "had to press b twice"): 14 relayed Bs and the room never let go; these lines say which way B went
        Log.i("GroupFinder", "B: ${if (open != null) "menu back ($open)" else "leave (shown $shown, hold sent $holdSent)"}")
        when {
            open != null -> menuBack()
            else -> { leftByB = true; onLeave() }
        }
    }

    /** the glow moves to the nearest target that way; in the activity list, right on a group opens its own list */
    private fun move(dx: Int, dy: Int) {
        val f = focus ?: return
        val cur = nav.firstOrNull { it.key == f } ?: return
        if (tab == BROWSE && f is Fk.MenuRow && dx > 0 && open == "activity" && groupArrows.containsKey(f.i)) {
            val g = GroupFinderModel.filterRows(currentCategory()).getOrNull(f.i) as? FilterRow.Group
            if (g != null) { openGroup(g); return }
        }
        val cx = cur.r.centerX(); val cy = cur.r.centerY()
        var best: Nav? = null; var bestD = Float.MAX_VALUE
        for (n in nav) {
            if (n.key == f || n.key == Fk.No) continue
            val ex = n.r.centerX() - cx; val ey = n.r.centerY() - cy
            val along = if (dx != 0) ex * dx else ey * dy
            if (along <= 4f) continue
            val across = if (dx != 0) Math.abs(ey) else Math.abs(ex)
            val d = along + across * 2.5f
            if (d < bestD) { bestD = d; best = n }
        }
        val b = best ?: return
        focus = b.key
        // b117 (the user 2026-10-07 on Character: "the character tabs auto open to the tab that the cursor is on- I REALLY like this and want to make the other tabs consistent"): the cursor on a tab opens it
        (b.key as? Fk.Tab)?.let { showTab(it.i); return }
        // a line out of view scrolls into it
        val a = areaOf(b.key) ?: return
        val content = when (b.key) { is Fk.Act -> actContent; is Fk.WhoLine -> whoContent; else -> listContent }
        val by = if (b.r.top < a.top) -(a.top - b.r.top) else if (b.r.bottom > a.bottom) b.r.bottom - a.bottom else 0f
        if (by == 0f) return
        val max = maxOf(0f, content - a.height())
        when (b.key) {
            is Fk.Act -> actScroll = (actScroll + by).coerceIn(0f, max)
            is Fk.WhoLine -> whoScroll = (whoScroll + by).coerceIn(0f, max)
            else -> listScroll = (listScroll + by).coerceIn(0f, max)
        }
    }
}
