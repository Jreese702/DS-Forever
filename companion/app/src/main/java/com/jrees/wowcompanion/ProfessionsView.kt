package com.jrees.wowcompanion

import android.content.Context
import android.util.Log
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import com.jrees.wowcompanion.ProfessionsModel as M

/**
 * THE PROFESSIONS ROOM, built to UI Art's SPEC (`wow-companion-design/professions/SPEC.md`, the user's picks of
 * 2026-09-28: "Yes, build them as drawn", recipes in "One column, detail beside it"). The book of cards, and a crafting
 * page per profession with its recipes on the left, the chosen recipe on the right and the game's foot at the bottom.
 *
 * WHAT REACHES THE GAME, and nothing else: a tap on Open, Create, Create All, Track (one game action each), and the
 * no-action "select" tap that tells the addon which recipe Create means (SPEC 5; the addon names what it selected, and
 * Create stays disabled until that is the recipe on screen). Tabs, cards, headings, recipes, the count, search and the
 * filter are the room's own.
 *
 * The data is [ProfessionsStore]'s (the addon's documents + the Bags room's counts), the rules [ProfessionsModel]'s.
 * THE CONTROLLER (SPEC 5): while the room shows, the addon holds the controller as a guest of the shared capture (the app taps
 * `book` or `page` on entering and when it changes, `free` on leaving) and relays every press ([onPad]). On the book, with a
 * profession open, B is the GAME'S own close button (capture version 2), and the addon reports "BACK" when the game closed it. The glow is the room's: every touch target is also a
 * glow target with the same action, so A does exactly what a tap does. X = Create, Y = Track, B = back (a page to the book; the
 * book out of the room, to HOME: user ruling 2026-09-28 23:12). The glow is drawn only while the addon says it holds the controller.
 */
class ProfessionsView(c: Context) : FrameLayout(c), BLeadsHome {

    // ------------------------------------------------------------ the room's own state (nothing of it reaches the game)
    /** 0 = the book; otherwise the profession (skill line) whose page is shown */
    private var viewed = 0
    private val chosen = HashMap<Int, Int>()                  // profession -> chosen recipe
    private val count = HashMap<Int, Int>()                   // recipe -> the count box
    private val collapsed = HashMap<Int, HashSet<Int>>()      // profession -> folded categories
    private var onlyMakeable = false
    private var onlySkillUp = false
    private var filterOpen = false
    private var listScroll = 0f
    private var detailScroll = 0f

    // what the room is waiting for from the game
    private var opening = 0; private var openingAt = 0L
    private var pendingTap = 0L                               // a Create / Create All / Track tap in flight: no second one
    private var selSentFor = 0; private var selSentAt = 0L
    private var message: String? = null; private var messageAt = 0L
    private var closingAt = 0L                                // the close cell was tapped on leaving; its answer is awaited
    private var leftByB = false                               // B closed it: the game did, and the controller already went back
    private var modeSent: String? = null; private var modeSentAt = 0L
    private var quietUntil = 0L                               // just entered: the game's own jumps are not followed (see enter())
    private var orphanFreeAt = -1_000_000L                    // the last `free` sent for a press made while the room was not shown
    private var bookSentAt = -1_000_000L                      // the room's own book tap (entering): a leave soon after closes
    private var bookPendingAt = 0L                            // entered: the book tap waits until the room HOLDS the controller
    private var openAnsweredAt = -1_000_000L                  // the addon's last answer to an open tap

    // the controller: where the glow is (a key that survives redraws), the last press seen, what B on the book does
    // COPYING THE GAME (user ruling 2026-09-30): the glow lands on a book card's LIVE buttons, never on a card. The user then
    // asked (2026-09-30 ~14:40) that the cursor reach the TABS too: the glow moves there, and a tab changes only on A
    private sealed class Fk {
        data class Btn(val id: Int, val i: Int) : Fk()
        data class Tab(val id: Int) : Fk()
        data class Head(val cat: Int) : Fk()
        data class Rec(val id: Int) : Fk()
        object Track : Fk(); object All : Fk(); object Count : Fk(); object Create : Fk()
    }
    private var focus: Fk? = null
    private var lastRow: Fk? = null
    private var lastPadN = -1
    /** B: MainActivity goes HOME (user ruling 23:12), which leaves the room (see [leave]) */
    var onLeave: () -> Unit = {}

    /** RC3: B leaves for Home on the press, except while a craft runs (then B is the game's /stopcasting and the room stays: bstop) */
    override fun bLeadsHome(): Boolean = ProfessionsStore.overview?.bstop != true

    private val board = Board(c)
    private val search = EditText(c)

    init {
        addView(board, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        search.apply {
            background = UiKit.background { cv, r -> UiKit.searchBox(cv, r, "", "") }
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 26f)
            setTextColor(Theme.text); setHintTextColor(Theme.dim)
            UiKit.asBody(this)
            setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT
            // not the full-screen keyboard Android gives a wide screen: the list must stay in view while it filters (seen on the emulator)
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            gravity = Gravity.CENTER_VERTICAL
            setPadding(70, 0, 20, 0)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { listScroll = 0f; board.invalidate() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            })
            setOnEditorActionListener { v, _, _ ->
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
                v.clearFocus(); true
            }
        }
        addView(search, LayoutParams(SPLIT.toInt() - 134 - 16, 72).apply { leftMargin = 16; topMargin = 74 })
        search.visibility = GONE
    }

    // ------------------------------------------------------------ fed by MainActivity

    /** the store changed (a prof / profrec document, the bags); redraw, and keep the addon's selection in step */
    fun refresh() {
        val ov = ProfessionsStore.overview
        if (bookPendingAt != 0L && ov?.held == true) sendBook()            // held after a retry (another room let go)
        if (opening != 0 && (ov?.open == opening || SystemClock.uptimeMillis() - openingAt > OPEN_WAIT)) opening = 0
        if (ov?.busy == null && pendingTap != 0L && SystemClock.uptimeMillis() - pendingTap > 800) pendingTap = 0L
        syncSelection()
        layoutSearch()
        board.invalidate()
    }

    /** the addon's answer to a tap: a refusal (its reason, or the game's red text) shows 3 s at the foot */
    fun onAck(msg: org.json.JSONObject) {
        val op = msg.optString("op")
        if (op in listOf("make", "all", "track", "untrack")) pendingTap = 0L
        if (op == "open") { openAnsweredAt = SystemClock.uptimeMillis(); if (!msg.optBoolean("ok")) opening = 0 }
        if (op == "close") closingAt = 0L
        // the addon's answer to the entry: "open now" (or held) -> the ONE book tap; `noopen` (queued behind another room, or a fight's
        // pending take) -> never open the window then (p19)
        if ((op == "book" || op == "page") && msg.optBoolean("noopen")) bookPendingAt = 0L
        else if ((op == "book" || op == "page") && msg.optBoolean("ok")) sendBook()
        if (!msg.optBoolean("ok") && op != "select" && op != "blocked") {
            message = msg.optString("why").takeIf { it.isNotEmpty() && it[0].code > 3 }
            messageAt = SystemClock.uptimeMillis()
            board.postInvalidateDelayed(MESSAGE_MS + 50)
        }
        board.invalidate()
    }

    /** the game opened a profession: its page (MainActivity shows the room first) */
    fun showProfession(id: Int) {
        // right after the player entered, the game's window may open a profession BY ITSELF (on a reopen the camelot window casts
        // its tabs' spells: Home's rounds 2 and 3, 2026-09-30, came up on Skinning). The room stays on the book the player entered
        if (SystemClock.uptimeMillis() < quietUntil) return
        goTo(id)
    }


    // ------------------------------------------------------------ the pieces the page needs

    private fun profession(id: Int): M.Profession? = ProfessionsStore.overview?.list?.firstOrNull { it.id == id }
    private fun live(id: Int) = ProfessionsStore.isLive(id)

    private fun rows(book: M.Book): List<M.Row> = M.rows(book, ProfessionsStore.counts, live(book.id),
        M.Filter(search.text?.toString() ?: "", onlyMakeable, onlySkillUp), collapsed.getOrPut(book.id) { HashSet() })

    /** the chosen recipe of the viewed page; the first of the list when none is chosen yet, as the game does */
    private fun chosenRecipe(book: M.Book, rows: List<M.Row>): M.Recipe? {
        book.recipe(chosen[book.id])?.let { return it }
        val first = rows.firstOrNull { it is M.Row.Item } as? M.Row.Item ?: return null
        chosen[book.id] = first.recipe.id
        return first.recipe
    }

    private fun goTo(id: Int) {
        if (viewed != id) { viewed = id; listScroll = 0f; detailScroll = 0f; filterOpen = false; search.setText(""); lastRow = null; focus = null }
        layoutSearch()
        syncSelection()
        board.invalidate()
    }

    private fun layoutSearch() {
        val show = viewed != 0 && ProfessionsStore.book(viewed) != null
        search.hint = ProfessionsStore.overview?.word("search", "Search") ?: "Search"
        search.visibility = if (show) VISIBLE else GONE
        if (!show) search.clearFocus()
    }

    // every tap behind GameInput's LIVE gate (refused while the log is only being replayed, or the game is not answering): the
    // lock, after a restart once tapped from backlog documents (2026-09-30); the geometry is still TapBridge's
    private fun send(t: M.Tap?): Boolean = t != null && GameInput.tap(context, ProfessionsStore.pad(), t.row, t.col)

    /**
     * SELECTING a profession, the game's way (user ruling 2026-09-30): its tab, or its recipe button on the book. The room shows
     * its page, and when it is not the profession open in the game ONE tap on its secure cell casts its spell (the game's tab and
     * button do exactly that). Never a second cast while one is in flight: a cast of the open profession would close it.
     * 0 = the book (the game's Overview tab): no game action.
     */
    private fun select(id: Int) {
        goTo(id)
        if (id == 0) return
        val t = M.openTap(ProfessionsStore.overview, profession(id)) ?: return
        val now = SystemClock.uptimeMillis()
        if (opening == id && now - openingAt < OPEN_WAIT) return
        if (send(t)) {
            opening = id; openingAt = now; board.postInvalidateDelayed(OPEN_WAIT + 50)
            // not answered within a moment: the tap was lost on its way (user's answer, 2026-09-30): ONE more, never a third
            board.postDelayed({
                if (!isShown || viewed != id || opening != id) return@postDelayed
                send(M.retryOpen(ProfessionsStore.overview, profession(id), openAnsweredAt >= now))
            }, OPEN_RETRY)
        }
    }

    /** the no-action tap that makes the addon select the recipe on screen; at most one every 2 s per recipe */
    private fun syncSelection() {
        if (!isShown || viewed == 0) return
        val ov = ProfessionsStore.overview ?: return
        val book = ProfessionsStore.book(viewed) ?: return
        val r = chosenRecipe(book, rows(book)) ?: return
        val t = M.selectTap(ov, book, r) ?: return
        val now = SystemClock.uptimeMillis()
        if (selSentFor == r.id && now - selSentAt < 2000) return
        if (send(t)) { selSentFor = r.id; selSentAt = now }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) refresh() else { filterOpen = false; search.clearFocus() }
        padNow.setActive(isShown)
    }

    /** b117 (the user on b116: "the button push is delayed a moment after I push it"): [onPad] runs on the game's document, a
     *  round trip after the press. The app's own controller reader (PadHub, via Spells' PressFeedback) shows A / X / Y's button
     *  DOWN in the press's own frame; the drawing only, what the press does still comes from the document */
    private val padNow: PressFeedback by lazy {
        PressFeedback(board).apply {
            padTarget = { k ->
                if (ProfessionsStore.overview?.held != true) null
                else when (k) {
                    "PAD1" -> focus?.takeIf { f -> board.nav.any { it.key == f && it.act != null } }
                    "PAD3" -> if (viewed != 0 && board.nav.any { it.key == Fk.Create && it.act != null }) Fk.Create else null
                    "PAD4" -> if (viewed != 0 && board.nav.any { it.key == Fk.Track && it.act != null }) Fk.Track else null
                    else -> null
                }
            }
        }
    }

    // ------------------------------------------------------------ the drawing and the touches
    private inner class Board(c: Context) : View(c) {

        /** [key] = the button the touch presses, for its pushed-in look (none on rows, tabs, the filter) */
        private inner class Hit(val r: RectF, val key: Fk? = null, val act: () -> Unit)
        private val hits = ArrayList<Hit>()

        // ---- PRESS FEEDBACK (the user, 2026-10-07: "Popups working, all other context buttons should have similar feedback"):
        // Interact's PressLook on the room's buttons (the book's live spell buttons, Create All, Create, Track). A finger shows its
        // button DOWN while on it and PRESS_MIN_MS after it lifts; A / X / Y show the button they perform DOWN at once (onPad), never
        // waiting for the game's document. Only the drawing changes: what a press does is untouched.
        private var fingerKey: Fk? = null
        private var pressKey: Fk? = null; private var pressUntil = 0L
        fun isDown(k: Fk) = fingerKey == k || (pressKey == k && SystemClock.uptimeMillis() < pressUntil) || padNow.isDown(k)
        fun flash(k: Fk?) {
            if (k == null) return
            pressKey = k; pressUntil = SystemClock.uptimeMillis() + PRESS_MIN_MS
            invalidate()
            postInvalidateDelayed(PRESS_MIN_MS + 10L)
        }
        /** every glow target of this frame, in the order drawn; [top] / [h] place a list row in the list's own space */
        inner class Nav(val key: Fk, val r: RectF, val act: (() -> Unit)?, val top: Float = 0f, val h: Float = 0f)
        val nav = ArrayList<Nav>()
        private fun nav(key: Fk, r: RectF, act: (() -> Unit)?, top: Float = 0f, h: Float = 0f) { nav.add(Nav(key, RectF(r), act, top, h)) }
        var listBox = RectF(); var detailBox = RectF(); var trackTop = 0f
        private var listHeight = 0f; private var detailHeight = 0f

        override fun onDraw(c: Canvas) {
            hits.clear(); nav.clear()
            val w = width.toFloat(); val h = height.toFloat()
            c.drawColor(Theme.ink)
            val ov = ProfessionsStore.overview
            val prof = if (viewed != 0) profession(viewed) else null
            if (viewed != 0 && prof == null) viewed = 0                 // unlearned since: back to the book
            val book = if (viewed != 0) ProfessionsStore.book(viewed) else null
            val title = if (viewed == 0) ov?.word("title", "Professions") ?: "Professions" else prof?.name ?: ""
            val note = if (viewed != 0) M.seenAgo(System.currentTimeMillis(), ProfessionsStore.seenAt(viewed), live(viewed)) else null
            UiKit.header(c, w, title, note)
            val tabsX = w - 118f
            val right = tabsX - 4f
            UiKit.professionGround(c, RectF(0f, 62f, right, h), page = viewed != 0)
            drawTabs(c, ov, tabsX, w)
            if (viewed == 0) drawBook(c, ov, right, h)
            else if (prof != null) drawPage(c, ov, prof, book, right, h)
            if (message != null && SystemClock.uptimeMillis() - messageAt > MESSAGE_MS) message = null
            drawGlow(c, ov)
        }

        /**
         * The glow round what the controller is on (SPEC 5), only while the room holds the controller. It is THERE from the start,
         * on what is selected (the user, 2026-09-30: "I hate that in the other menus I have to ress buttons a few times to make the
         * cursor appear, it should already be there"): no press is needed to show it.
         */
        private fun drawGlow(c: Canvas, ov: M.Overview?) {
            if (ov?.held != true) return
            if (focus == null || nav.none { it.key == focus }) {
                focus = startTarget()
                if (focus is Fk.Rec) lastRow = focus
            }
            val f = focus ?: return
            val n = nav.firstOrNull { it.key == f } ?: return
            when (f) {
                is Fk.Btn -> {}                                        // professionButton(chosen) draws the game's own glow
                is Fk.Tab -> {}                                        // LATER 19: the tab draws its own cursor (sideTab cursor)
                is Fk.Head, is Fk.Rec -> { c.save(); c.clipRect(listBox); UiKit.focus(c, n.r); c.restore() }
                is Fk.Track -> { c.save(); c.clipRect(detailBox); UiKit.focus(c, n.r); c.restore() }
                else -> UiKit.focus(c, n.r)
            }
        }

        // ---- the tabs down the right edge: the book, then every profession with a crafting page (SPEC 2)
        private fun drawTabs(c: Canvas, ov: M.Overview?, tabsX: Float, w: Float) {
            val tabs = listOf(0) + (ov?.tabs?.map { it.id } ?: emptyList())
            // b120 (the user on side tabs: "(a) No glyphs"): the tabs sit together, no gap and no LB/RB glyph; LB/RB still switch them
            val boxes = UiKit.tabColumn(tabs.size, tabsX, 72f, height - 8f, gap = 2f)
            for ((i, id) in tabs.withIndex()) {
                val r = boxes[i]
                val icon = if (id == 0) UiKit.tex("prof_overview") else Icons.get(profession(id)?.icon)
                UiKit.sideTab(c, r, icon, id == viewed, cursor = ov?.held == true && focus == Fk.Tab(id))
                hits.add(Hit(RectF(tabsX - 6f, r.top, w, r.bottom + 2f)) { select(id) })
                // the glow's box, kept on screen (SPEC 5.1): the plain tab box puts the frame past the right edge
                nav(Fk.Tab(id), RectF(tabsX + 2f, r.top + 4f, w - 10f, r.bottom - 4f), { select(id); focus = Fk.Tab(id) })
            }
        }

        // ---- the book (SPEC 3)
        private fun drawBook(c: Canvas, ov: M.Overview?, right: Float, h: Float) {
            val byPos = ov?.cards?.associateBy { it.pos } ?: emptyMap()
            var y = 80f
            for (pos in 1..2) {
                val r = RectF(16f, y, right - 16f, y + 214f)
                val p = byPos[pos]
                if (p == null) emptyCard(c, r, false, ov, pos) else mainCard(c, r, p)
                y += 228f
            }
            val cw = (right - 32f - 28f) / 3f
            for ((i, pos) in listOf(5, 4, 3).withIndex()) {              // the game's book: Cooking, Fishing, First Aid
                val r = RectF(16f + i * (cw + 14f), y, 16f + i * (cw + 14f) + cw, h - 14f)
                val p = byPos[pos]
                if (p == null) emptyCard(c, r, true, ov, pos) else smallCard(c, r, p)
            }
        }

        private fun skillLabel(rank: Int, max: Int, mod: Int) = if (mod != 0) "$rank (+$mod) / $max" else "$rank / $max"
        private fun fill(rank: Int, max: Int) = if (max > 0) rank / max.toFloat() else 0f

        private fun noteOf(p: M.Profession): Pair<String, Int> = when (val n = M.cardRecipes(p, ProfessionsStore.book(p.id))) {
            is M.CardRecipes.Count -> when (n.n) { 0 -> "No recipes learned" to Theme.dim; 1 -> "1 recipe" to Theme.text; else -> "${n.n} recipes" to Theme.text }
            M.CardRecipes.None -> "No recipes" to Theme.dim
            M.CardRecipes.Unseen -> "Not seen yet" to Theme.dim
        }

        private fun mainCard(c: Canvas, r: RectF, p: M.Profession) {
            UiKit.professionCard(c, r, p.id)
            val bar = RectF(r.right - 560f, r.top + 28f, r.right - 40f, r.top + 74f)
            val pt = fitted(p.name, UiKit.titlePaint(46f), bar.left - 24f - (r.left + 28f), 36f)
            c.drawText(p.name, r.left + 28f, r.top + 50f - (pt.ascent() + pt.descent()) / 2f, pt)
            UiKit.skillBar(c, bar, fill(p.rank, p.max), skillLabel(p.rank, p.max, p.mod))
            val (note, colour) = noteOf(p)
            val pn = UiKit.bodyPaint(26f, colour).apply { textAlign = Paint.Align.RIGHT }
            c.drawText(note, r.right - 40f, r.top + 148f - (pn.ascent() + pn.descent()) / 2f, pn)
            for ((i, b) in p.btn.withIndex()) {
                spellButton(c, RectF(r.left + 20f + i * 330f, r.top + 92f, r.left + 20f + i * 330f + 316f, r.top + 203f), p, i, b)
            }
        }

        private fun smallCard(c: Canvas, r: RectF, p: M.Profession) {
            UiKit.professionCard(c, r, p.id, small = true)
            val pt = fitted(p.name, UiKit.titlePaint(36f), r.width() - 24f, 28f).apply { textAlign = Paint.Align.CENTER }
            c.drawText(p.name, r.centerX(), r.top + 44f - (pt.ascent() + pt.descent()) / 2f, pt)
            UiKit.skillBar(c, RectF(r.left + 30f, r.top + 78f, r.right - 30f, r.top + 120f), fill(p.rank, p.max), skillLabel(p.rank, p.max, p.mod), 24f)
            val (note, colour) = noteOf(p)
            val pn = UiKit.bodyPaint(26f, colour).apply { textAlign = Paint.Align.CENTER }
            val two = p.btn.size >= 2
            c.drawText(note, r.centerX(), r.top + (if (two) 140f else 152f) - (pn.ascent() + pn.descent()) / 2f, pn)
            val low = RectF(r.left + 14f, r.bottom - 125f, r.right - 14f, r.bottom - 14f)
            if (two) {
                spellButton(c, RectF(r.left + 14f, r.bottom - 236f, r.right - 14f, r.bottom - 125f), p, 0, p.btn[0])
                spellButton(c, low, p, 1, p.btn[1])
            } else p.btn.firstOrNull()?.let { spellButton(c, low, p, 0, it) }
        }

        /**
         * One of the game's spell buttons. LIVE = the one that opens the profession's recipes (the game's own cast, through the
         * secure cell): a touch or A selects the profession, as the game's button does. Every other button is INERT (the user's
         * "no spell casting" ruling): shown dim, no touch, the glow skips it.
         */
        private fun spellButton(c: Canvas, box: RectF, p: M.Profession, i: Int, b: M.Btn) {
            val live = b.opens && p.craft
            val key = Fk.Btn(p.id, i)
            val chosen = live && ProfessionsStore.overview?.held == true && focus == key
            val pushed = live && isDown(key)
            val down = live && (opening == p.id || pushed)
            UiKit.pressed(c, box, pushed) {
                UiKit.professionButton(c, box, Icons.get(b.icon), b.name, inert = !live, chosen = chosen, down = down)
            }
            if (!live) return
            hits.add(Hit(RectF(box), key) { select(p.id) })
            nav(key, box, { select(p.id) })
        }

        /**
         * A place without a profession: the plain card and the game's own words. A small one as the game fills it (camelot book
         * templates: the profession's name as the header, then its own line; UI Art's SPEC 3, 2026-09-30); no bar, no button.
         */
        private fun emptyCard(c: Canvas, r: RectF, small: Boolean, ov: M.Overview?, pos: Int) {
            UiKit.professionCard(c, r, 0, small)
            if (small) {
                val key = when (pos) { 3 -> "faid"; 4 -> "fish"; else -> "cook" }        // GetProfessions: 3 First Aid, 4 Fishing, 5 Cooking
                val name = ov?.str?.get(key + "Name") ?: return
                val pt = fitted(name, UiKit.titlePaint(36f), r.width() - 24f, 28f).apply { textAlign = Paint.Align.CENTER }
                c.drawText(name, r.centerX(), r.top + 44f - (pt.ascent() + pt.descent()) / 2f, pt)
                val line = ov.str[key + "Missing"] ?: return
                val pl = UiKit.bodyPaint(22f, Theme.text).apply { textAlign = Paint.Align.CENTER }
                var y = r.top + 84f - pl.ascent()
                for (l in wrap(line, pl, r.width() - 56f, 8)) { c.drawText(l, r.centerX(), y, pl); y += pl.fontSpacing }
                return
            }
            val head = ov?.str?.get(if (pos == 1) "first" else "second") ?: return
            val line = ov?.str?.get("missing")
            val p = UiKit.bodyPaint(26f, Theme.dim).apply { textAlign = Paint.Align.CENTER }
            c.drawText(head, r.centerX(), r.centerY() - (if (line != null) 18f else 0f) - (p.ascent() + p.descent()) / 2f, p)
            if (line != null) c.drawText(fit(line, p, r.width() - 60f), r.centerX(), r.centerY() + 18f - (p.ascent() + p.descent()) / 2f, p)
        }

        // ---- a crafting page (SPEC 4)
        private fun drawPage(c: Canvas, ov: M.Overview?, prof: M.Profession, book: M.Book?, right: Float, h: Float) {
            val rank = book?.rank ?: prof.rank; val max = book?.max ?: prof.max; val mod = book?.mod ?: prof.mod
            UiKit.skillBar(c, RectF(right - 430f, 10f, right - 20f, 52f), fill(rank, max), skillLabel(rank, max, mod), 24f)
            val foot = h - 158f                     // SPEC 4 (UI Art 3ccc4af): the foot ends 35 px over the bar, clear of Home's raised button
            UiKit.paneDivider(c, SPLIT, 62f, foot)
            val rows = if (book != null) rows(book) else emptyList()
            val recipe = if (book != null) chosenRecipe(book, rows) else null
            // the list
            val fx = SPLIT - 72f; val fy = 110f
            if (book != null) {
                UiKit.trackerButton(c, "filter", fx, fy, 76f, filterOpen)
                hits.add(Hit(RectF(fx - 55.5f, fy - 55.5f, fx + 55.5f, fy + 55.5f)) { filterOpen = !filterOpen; invalidate() })
                drawList(c, book, rows, recipe, foot)
            } else {
                val p = UiKit.bodyPaint(26f, Theme.dim).apply { textAlign = Paint.Align.CENTER }
                c.drawText("${prof.name}'s recipes show here", SPLIT / 2f, 380f, p)       // UI Art's SPEC (2026-09-30: no Open button)
                c.drawText("once it is open in the game", SPLIT / 2f, 416f, p)
            }
            if (recipe != null && book != null) drawDetail(c, ov, book, recipe, right, foot)
            drawFoot(c, ov, prof, book, recipe, right, foot)
            if (filterOpen && book != null) drawFilter(c, ov, prof, foot)
        }

        private fun drawList(c: Canvas, book: M.Book, rows: List<M.Row>, recipe: M.Recipe?, foot: Float) {
            listBox = RectF(0f, 150f, SPLIT - 8f, foot)
            c.save(); c.clipRect(listBox)
            var y = 160f - listScroll
            var prevItem = false
            val folds = collapsed.getOrPut(book.id) { HashSet() }
            for (row in rows) when (row) {
                is M.Row.Header -> {
                    if (prevItem) y += 6f                                     // 6 px after a category's last recipe
                    val indent = row.depth * 24f
                    val r = RectF(4f + indent, y, SPLIT - 12f, y + 66f)
                    UiKit.listHeading(c, r, row.cat.name, !row.collapsed)
                    val id = row.cat.id
                    val fold = { if (!folds.remove(id)) folds.add(id); invalidate() }
                    addListHit(r, fold)
                    nav(Fk.Head(id), r, fold, y + listScroll - 160f, 66f)
                    y += 66f; prevItem = false
                }
                is M.Row.Item -> {
                    val box = RectF(12f, y, SPLIT - 20f, y + 96f)
                    val r = row.recipe
                    UiKit.listLine(c, box, r.id == recipe?.id)
                    UiKit.slot(c, Icons.get(r.icon), box.left + 18f, box.top + 16f, 64f)
                    var nameRight = box.right - 20f
                    if (row.canMake > 0) {
                        val pc = UiKit.numberPaint(28f, Color.WHITE).apply { textAlign = Paint.Align.RIGHT }
                        val s = "[${row.canMake}]"
                        c.drawText(s, box.right - 20f, box.centerY() - 1f - (pc.ascent() + pc.descent()) / 2f, pc)
                        nameRight -= pc.measureText(s) + 16f
                    }
                    val colour = diffColour(r.diff)
                    val ups = if (r.ups > 1) r.ups.toString() else null
                    val pu = UiKit.numberPaint(22f, colour)
                    val upsW = if (ups != null) pu.measureText(ups) + 8f else 0f
                    val pn = fitted(r.name, UiKit.bodyPaint(27f, colour), nameRight - (box.left + 98f) - upsW, 21f)
                    val name = fit(r.name, pn, nameRight - (box.left + 98f) - upsW)
                    val cy = box.centerY() - 1f - (pn.ascent() + pn.descent()) / 2f
                    c.drawText(name, box.left + 98f, cy, pn)
                    if (ups != null) c.drawText(ups, box.left + 98f + pn.measureText(name) + 8f, cy, pu)
                    val id = r.id
                    addListHit(box) { choose(book, id) }
                    nav(Fk.Rec(id), box, { choose(book, id) }, y + listScroll - 160f, 96f)
                    y += 96f; prevItem = true
                }
            }
            c.restore()
            listHeight = y + listScroll - 160f
            if (rows.isEmpty()) {
                val p = UiKit.bodyPaint(26f, Theme.dim).apply { textAlign = Paint.Align.CENTER }
                c.drawText(if (book.recipes.isEmpty()) "No recipes" else "Nothing matches", SPLIT / 2f, 380f, p)
            }
        }

        private fun addListHit(r: RectF, act: () -> Unit) {
            val vis = RectF(r); if (!vis.intersect(listBox)) return
            hits.add(Hit(vis, act = act))
        }

        private fun drawDetail(c: Canvas, ov: M.Overview?, book: M.Book, r: M.Recipe, right: Float, foot: Float) {
            val x0 = SPLIT + 28f; val x1 = right - 24f
            detailBox = RectF(SPLIT + 8f, 62f, right, foot)
            c.save(); c.clipRect(detailBox)
            c.translate(0f, -detailScroll)
            val out = r.out
            UiKit.framed(c, Icons.get(out?.let { if (it.icon > 0) it.icon else null } ?: r.icon), x0, 84f, 116f)
            if (out != null && out.min > 1) {
                val pq = UiKit.numberPaint(28f, Color.WHITE).apply { textAlign = Paint.Align.RIGHT }
                c.drawText(if (out.max > out.min) "${out.min}-${out.max}" else "${out.min}", x0 + 116f - 12f, 84f + 116f - 14f, pq)
            }
            val nameColour = Theme.quality(out?.quality)
            val nameText = out?.name ?: r.name
            val pn = UiKit.bodyPaint(34f, nameColour)
            val lines = wrap(nameText, pn, x1 - (x0 + 136f), 2)
            if (lines.size == 1) c.drawText(lines[0], x0 + 136f, 120f - (pn.ascent() + pn.descent()) / 2f, pn)
            else { c.drawText(lines[0], x0 + 136f, 104f - (pn.ascent() + pn.descent()) / 2f, pn)
                   c.drawText(lines[1], x0 + 136f, 140f - (pn.ascent() + pn.descent()) / 2f, pn) }
            val pm = UiKit.bodyPaint(24f, Theme.dim)
            val makes = out?.let { if (it.max > it.min) "Makes ${it.min}-${it.max}" else "Makes ${maxOf(it.min, 1)}" } ?: "Makes 1"
            c.drawText(makes, x0 + 136f, 164f - (pm.ascent() + pm.descent()) / 2f, pm)
            UiKit.paneRule(c, x0, 214f, x1)
            var shift = 0f
            var y = 236f
            // WHAT IT MAKES, above the reagents (the user, 2026-09-30: "for half the Items in professions, I have no idea what I am
            // crafting because theirs no description on them either- which honestly could just be above the reagents"): the game's
            // own tooltip lines for the item, in the game's colours, a two-sided line with its right text at the right edge. The
            // recipe's description stands in only where the game gives no tooltip (an enchant).
            val tip = M.tipShown(r)
            if (tip.isNotEmpty()) {
                for (l in tip) {
                    val pl = UiKit.bodyPaint(23f, hexColour(l.c, Color.WHITE))
                    if (l.r != null) {
                        val pr = UiKit.bodyPaint(23f, hexColour(l.rc, Color.WHITE)).apply { textAlign = Paint.Align.RIGHT }
                        c.drawText(fit(l.t, pl, x1 - x0 - pr.measureText(l.r) - 16f), x0, y - pl.ascent(), pl)
                        c.drawText(l.r, x1, y - pr.ascent(), pr)
                        y += pl.fontSpacing
                    } else for (s in wrap(l.t, pl, x1 - x0, 4)) { c.drawText(s, x0, y - pl.ascent(), pl); y += pl.fontSpacing }
                }
                shift += (y - 236f) + 12f
            } else r.desc?.let { d ->
                val pd = UiKit.bodyPaint(23f, Theme.text)
                val dl = wrap(d.replace('\n', ' '), pd, x1 - x0, 4)
                for (l in dl) { c.drawText(l, x0, y - pd.ascent(), pd); y += pd.fontSpacing }
                shift += (y - 236f) + 12f
            }
            y = 236f + shift
            if (r.req.isNotEmpty()) {
                val tmpl = ov?.word("requires", "Requires: %s") ?: "Requires: %s"
                val head = tmpl.substringBefore("%s")
                var x = x0
                val pr = UiKit.bodyPaint(24f, Theme.dim)
                val base = y - pr.ascent()
                c.drawText(head, x, base, pr); x += pr.measureText(head)
                for ((i, q) in r.req.withIndex()) {
                    val pq = UiKit.bodyPaint(24f, if (q.met) Theme.text else Theme.unusable)
                    val s = q.name + if (i < r.req.size - 1) ", " else ""
                    c.drawText(s, x, base, pq); x += pq.measureText(s)
                }
                y += pr.fontSpacing + 8f; shift = y - 236f + 12f
            }
            if (r.cooldown > 0) {
                val pc = UiKit.bodyPaint(24f, Theme.unusable)
                val words = (ov?.word("cooldown", "Cooldown remaining:") ?: "Cooldown remaining:") + " " + M.duration(r.cooldown)
                c.drawText(words, x0, y - pc.ascent(), pc)
                y += pc.fontSpacing + 8f; shift = y - 236f + 12f
            }
            val pr = UiKit.bodyPaint(28f, Theme.gold)
            val reagWord = (ov?.word("reagents", "Reagents") ?: "Reagents").trimEnd(':', ' ')
            c.drawText(reagWord, x0, 252f + shift - (pr.ascent() + pr.descent()) / 2f, pr)
            y = 284f + shift
            val n = countOf(r, book)
            for (line in M.lines(r, n, ProfessionsStore.counts)) {
                val g = line.reagent
                UiKit.slot(c, Icons.get(g.icon), x0 + 4f, y + 4f, 72f)
                val p1 = UiKit.bodyPaint(27f, Color.WHITE)
                c.drawText(fit(g.name ?: "…", p1, x1 - (x0 + 96f)), x0 + 96f, y + 26f - (p1.ascent() + p1.descent()) / 2f, p1)
                val p2 = UiKit.numberPaint(28f, if (line.short > 0) Theme.unusable else Theme.text)
                c.drawText("${line.have} / ${line.need}", x0 + 96f, y + 60f - (p2.ascent() + p2.descent()) / 2f, p2)
                y += 92f
            }
            // Track: the game's check box; ONE game action, only on the live page (the addon must hold this recipe)
            val live = live(book.id)
            val canTrack = live && ov?.sel == r.id
            UiKit.pressed(c, RectF(x0 + 4f, y + 21f, x0 + 60f, y + 77f), canTrack && isDown(Fk.Track)) {
                UiKit.check(c, x0 + 32f, y + 49f, 56f, r.tracked, canTrack)
            }
            val pt = UiKit.bodyPaint(25f, if (canTrack) Theme.text else Theme.dim)
            c.drawText(ov?.word("track", "Track") ?: "Track", x0 + 74f, y + 49f - (pt.ascent() + pt.descent()) / 2f, pt)
            val row = RectF(x0, y + 49f - 55.5f - detailScroll, x1, y + 49f + 55.5f - detailScroll)
            trackTop = y + 49f - 55.5f
            nav(Fk.Track, row, if (canTrack) ({ track(r, !r.tracked) }) else null)
            if (canTrack && row.intersect(detailBox)) hits.add(Hit(row, Fk.Track) { track(r, !r.tracked) })
            y += 111f
            c.restore()
            detailHeight = y - 62f
        }

        // ---- the foot (SPEC 4.3)
        private fun drawFoot(c: Canvas, ov: M.Overview?, prof: M.Profession, book: M.Book?, r: M.Recipe?, right: Float, foot: Float) {
            UiKit.divider(c, 0f, foot, right, foot)
            val by = foot + 12f
            val createBox = RectF(right - 330f, by, right - 24f, by + 111f)
            val now = SystemClock.uptimeMillis()
            val msg = message?.takeIf { now - messageAt <= MESSAGE_MS }
            val counts = ProfessionsStore.counts
            val act = M.action(ov, prof, book, r, if (r != null && book != null) countOf(r, book) else 1, counts)
            if (act.kind == M.Kind.OPEN || act.kind == M.Kind.NONE && ov?.open != prof.id) {
                // not open in the game (copying the game: no Open button; selecting the tab opens it): one sentence, or the game's
                // red text, or why the tap cannot be sent
                val waiting = opening == prof.id
                val line = msg ?: when { waiting -> "Opening…"; !act.enabled && act.why != null -> act.why; else -> M.notOpenLine(prof) }
                val pl = UiKit.bodyPaint(26f, if (msg != null) Theme.unusable else Theme.dim)
                c.drawText(fit(line, pl, right - 60f), 28f, by + 55f - (pl.ascent() + pl.descent()) / 2f, pl)
                return
            }
            val busy = ov?.busy
            if (busy != null) {
                // crafting: no Stop button (the game's window has none); B cancels (the user, 2026-09-30), said as the game's
                // controller bar says it, a glyph and the game's word
                val done = busy.made.coerceIn(0, busy.n)
                val pl = UiKit.bodyPaint(26f, Theme.text)
                c.drawText("Making ${minOf(done + 1, busy.n)} of ${busy.n}", 28f, by + 30f - (pl.ascent() + pl.descent()) / 2f, pl)
                if (ov?.bstop == true) UiKit.hints(c, 560f, by + 28f, "rev_b" to (ov.word("cancel", "Cancel")))
                // the game's cast timer, like its own cast bar: redrawn while the current item's cast runs
                UiKit.progress(c, RectF(28f, by + 56f, 560f, by + 96f), M.craftFill(busy, System.currentTimeMillis() - ProfessionsStore.overviewAt))
                if ((busy.castMs ?: 0) > 0) postInvalidateDelayed(50)
                UiKit.button(c, createBox, ov?.word("create", "Create") ?: "Create", "rev_x", UiKit.Button.DISABLED)
                nav(Fk.Create, createBox, null)
                return
            }
            if (r == null || book == null) {
                UiKit.button(c, createBox, ov?.word("create", "Create") ?: "Create", "rev_x", UiKit.Button.DISABLED)
                nav(Fk.Create, createBox, null)
                return
            }
            val can = M.canMake(r, counts, live(book.id))
            val n = countOf(r, book)
            val all = M.createAll(ov, prof, book, r, counts)
            val allLabel = M.fill(ov?.word("createAllFmt", "%s (%d)") ?: "%s (%d)", ov?.word("createAll", "Create All") ?: "Create All", can)
            val allBox = RectF(24f, by, 354f, by + 111f)
            val inFlight = pendingTap != 0L && now - pendingTap < TAP_WAIT
            // a press stays DOWN its PRESS_MIN_MS although the tap it sent greys the button at once (inFlight)
            val allDown = all.enabled && isDown(Fk.All)
            // the kit's press: UiKit.button pushes itself in when DOWN (art-kit)
            UiKit.button(c, allBox, allLabel, when { allDown -> UiKit.Button.DOWN; all.enabled && !inFlight -> UiKit.Button.UP
                                                     else -> UiKit.Button.DISABLED }, 30f)
            val doAll = { if (send(all.tap)) { pendingTap = SystemClock.uptimeMillis(); postInvalidateDelayed(TAP_WAIT + 50) } }
            if (all.enabled && !inFlight) hits.add(Hit(RectF(allBox), Fk.All) { doAll() })
            nav(Fk.All, allBox, if (all.enabled && !inFlight) doAll else null)
            val range = M.countRange(can)
            val canLess = can > 0 && n > range.first; val canMore = can > 0 && n < range.last
            UiKit.countBox(c, 610f, by + 55f, n, canLess, canMore)
            nav(Fk.Count, RectF(610f - 68f, by + 55f - 52f, 610f + 68f, by + 55f + 52f), null)
            countStep = { d -> if ((d < 0 && canLess) || (d > 0 && canMore)) setCount(r, n + d) }
            if (canLess) hits.add(Hit(RectF(610f - 120f - 55.5f, by + 55f - 55.5f, 610f - 120f + 55.5f, by + 55f + 55.5f)) { setCount(r, n - 1) })
            if (canMore) hits.add(Hit(RectF(610f + 120f - 55.5f, by + 55f - 55.5f, 610f + 120f + 55.5f, by + 55f + 55.5f)) { setCount(r, n + 1) })
            val createDown = act.enabled && isDown(Fk.Create)
            UiKit.button(c, createBox, ov?.word("create", "Create") ?: "Create", "rev_x",
                when { createDown -> UiKit.Button.DOWN; act.enabled && !inFlight -> UiKit.Button.UP; else -> UiKit.Button.DISABLED })
            val doCreate = { if (send(act.tap)) { pendingTap = SystemClock.uptimeMillis(); postInvalidateDelayed(TAP_WAIT + 50) } }
            if (act.enabled && !inFlight) hits.add(Hit(RectF(createBox), Fk.Create) { doCreate() })
            nav(Fk.Create, createBox, if (act.enabled && !inFlight) doCreate else null)
            // a line over Create: the game's red text for 3 s. No "not in combat" of the room's own (SPEC_COMBAT 4, the user's agreed
            // spec): in a fight Create goes to the game, and the game decides; its refusal shows here and flashes red
            val over = msg
            if (over != null) {
                val po = UiKit.bodyPaint(24f, Theme.unusable).apply { textAlign = Paint.Align.CENTER }
                c.drawText(fit(over, po, 900f), createBox.centerX() - 200f, by - 22f - (po.ascent() + po.descent()) / 2f, po)
            }
        }

        // ---- the filter's menu over the list: the game's lines (the item-slot line is not in this build)
        private fun drawFilter(c: Canvas, ov: M.Overview?, prof: M.Profession, foot: Float) {
            val lines = listOf(
                Triple(ov?.word("skillUp", "Has skill up") ?: "Has skill up", onlySkillUp) { onlySkillUp = !onlySkillUp },
                Triple(ov?.word("makeable", "Have Materials") ?: "Have Materials", onlyMakeable) { onlyMakeable = !onlyMakeable })
            val box = RectF(16f, 156f, SPLIT - 16f, 156f + 24f + lines.size * 111f)
            // a touch anywhere else closes it (added first, so the rows below win)
            hits.add(Hit(RectF(0f, 0f, width.toFloat(), height.toFloat())) { filterOpen = false; invalidate() })
            val inner = UiKit.menu(c, box, solid = true)
            for ((i, l) in lines.withIndex()) {
                val r = RectF(inner.left, inner.top + i * 111f, inner.right, inner.top + (i + 1) * 111f)
                UiKit.check(c, r.left + 44f, r.centerY(), 56f, l.second)
                val p = UiKit.bodyPaint(27f, Theme.text)
                c.drawText(l.first, r.left + 92f, r.centerY() - (p.ascent() + p.descent()) / 2f, p)
                hits.add(Hit(r) { l.third(); listScroll = 0f; invalidate() })
            }
        }

        // ---- touch: a tap is the topmost hit; a drag scrolls the pane it started in
        /** one less / one more on the count box, set by the foot's last drawing */
        var countStep: (Int) -> Unit = {}
        private var downX = 0f; private var downY = 0f; private var lastY = 0f
        private var dragging = false; private var dragPane = 0
        private val slop = ViewConfiguration.get(c).scaledTouchSlop

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y; lastY = e.y; dragging = false
                    fingerKey = hits.lastOrNull { it.r.contains(e.x, e.y) }?.key
                    if (fingerKey != null) invalidate()
                    dragPane = when { filterOpen -> 0; listBox.contains(e.x, e.y) -> 1; detailBox.contains(e.x, e.y) -> 2; else -> 0 }
                    if (search.hasFocus()) { search.clearFocus()
                        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken, 0) }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && dragPane != 0 && Math.abs(e.y - downY) > slop) { dragging = true; fingerKey = null }
                    if (dragging) {
                        val dy = lastY - e.y
                        if (dragPane == 1) listScroll = (listScroll + dy).coerceIn(0f, maxOf(0f, listHeight - listBox.height() + 20f))
                        else detailScroll = (detailScroll + dy).coerceIn(0f, maxOf(0f, detailHeight - detailBox.height()))
                        invalidate()
                    }
                    lastY = e.y
                }
                MotionEvent.ACTION_UP -> {
                    fingerKey = null
                    if (!dragging) hits.lastOrNull { it.r.contains(e.x, e.y) }?.let { h -> flash(h.key); h.act() }
                    invalidate()
                }
                MotionEvent.ACTION_CANCEL -> { fingerKey = null; invalidate() }
            }
            return true
        }
    }

    // ------------------------------------------------------------ the controller (SPEC 5)

    /** the room was entered: hold the controller (MainActivity calls this); one tap, never repeated within 1.5 s */
    fun enter() {
        val t = M.enterTap(ProfessionsStore.overview, viewed == 0) ?: return
        val mode = if (viewed == 0) "book" else "page"
        val now = SystemClock.uptimeMillis()
        if (mode == modeSent && now - modeSentAt < MODE_WAIT) return
        if (!send(t)) return
        modeSent = mode; modeSentAt = now
        // USER'S ANSWER (2026-09-30 ~14:45): entering the room opens the game's Professions window on its BOOK, hidden, with the
        // game's own Professions button (one touch = that one game action). The game readies its tab bar only when its window
        // first shows its book; straight onto a recipe page its tab code throws and its error window pops. Leaving closes it.
        // The game's button lands on its book only the first time after a reload; later it reopens its window where it was, and
        // its own show code may open a profession. The window is hidden, so the ROOM shows its book (the user's answer, "opens
        // the book"), and does not follow the game's own jumps for a moment. No extra game action.
        // ONLY ONCE THE ROOM HOLDS THE CONTROLLER (2026-09-30 22:49: the Calendar room still held it, the capture could not hide
        // the window and it showed on the top screen): the book tap waits for the addon's yes to the hold (or for `held` after
        // its retry), at most BOOK_WAIT; then never.
        goTo(0); quietUntil = now + ENTRY_QUIET
        bookPendingAt = now
    }

    /** uptime ms of the shared controller's last holder change (MainActivity); 0 = unknown */
    var lastHandOverAt: () -> Long = { 0L }

    /** the entry's book tap, once (see [enter]); only while the room is shown and the entry is fresh */
    private fun sendBook() {
        val at = bookPendingAt
        if (at == 0L) return
        val now = SystemClock.uptimeMillis()
        if (!isShown || now - at > BOOK_WAIT) { bookPendingAt = 0L; return }
        // b97 (Home as integrator; b94 23:41:54.801): a tap ~0.2 s after the bar let the controller go landed on the game's park
        // point and the window never opened. The book tap waits until the hand-over has settled (still ONE tap, never repeated)
        val settle = HANDOVER_SETTLE - (now - lastHandOverAt())
        if (settle in 1..HANDOVER_SETTLE) { postDelayed({ sendBook() }, settle); return }
        if (send(M.bookTap(ProfessionsStore.overview))) {
            bookPendingAt = 0L
            bookSentAt = now; quietUntil = now + ENTRY_QUIET
        }
    }

    /**
     * The room is being left, any way but B (MainActivity calls this): close the profession as the game would, with ONE secure
     * click on the game's own close button, which also hands the controller back; with nothing open, just hand it back (`free`).
     * If the close tap is not answered (a lost tap), the controller is handed back anyway: the game's window then shows and the
     * game's own B still closes it. A game action is never repeated.
     */
    fun leave() {
        modeSent = null
        bookPendingAt = 0L
        // IN A FIGHT the leave is Interact's (capture i81; Home taps its leave cell, which clicks this room's spec.leave = the game's
        // close button, so a craft keeps running): the room sends nothing of its own there, and never retries
        if (ProfessionsStore.overview?.combat == true) { leftByB = false; closingAt = 0L; return }
        if (leftByB) { leftByB = false; return }
        val ov = ProfessionsStore.overview
        val close = M.closeTap(ov, SystemClock.uptimeMillis() - bookSentAt < ENTRY_QUIET)
        if (close != null && send(close)) {
            val at = SystemClock.uptimeMillis(); closingAt = at
            board.postDelayed({ if (closingAt == at) { closingAt = 0L; send(M.freeTap(ProfessionsStore.overview)) } }, CLOSE_WAIT)
            return
        }
        send(M.freeTap(ov))
    }

    /**
     * THE GENERIC SELF-HEAL (tracking's yes under the user's delegation, 2026-09-30: "no room can keep the controller after it's
     * left"): Home calls this when a CURRENT interact document names "professions" as the holder while the room is not on screen
     * (at most once per 2 s). ONE `free` tap: the addon hands the shared capture back. NO GAME ACTION: it neither opens nor closes
     * the game's window and presses nothing in the game, whatever this view believes about held / shown. (If the game's window
     * was left open, it then shows on the top screen, and the game's own B closes it, as after a lost close tap.)
     * Returns true when the tap went out; one log line either way.
     */
    fun freeAgain(): Boolean {
        bookPendingAt = 0L; modeSent = null
        val sent = send(M.freeTap(ProfessionsStore.overview))
        Log.i(TAG, "freeAgain: free ${if (sent) "sent" else "NOT sent (no pad, no free column, or the live gate refused)"}")
        return sent
    }

    /**
     * A LIVE press relayed for this room while it is NOT shown (MainActivity checks both): the addon still holds the controller,
     * e.g. the app was killed while the room held it. The player's own press hands it back (M.orphanFree): one no-action tap.
     */
    fun orphanPress() {
        val now = SystemClock.uptimeMillis()
        if (send(M.orphanFree(ProfessionsStore.overview, now, orphanFreeAt))) orphanFreeAt = now
    }

    /** a press the addon relayed ({"type":"profpad"}); only while this room is shown (MainActivity checks) */
    fun onPad(msg: org.json.JSONObject) {
        val n = msg.optInt("n", -1)
        if (n == lastPadN) return                           // the same press seen twice
        lastPadN = n
        if (msg.has("lost")) {
            // capture v8 (Interact, 2026-10-01): in a fight the capture keeps the room's controller, and B leaves through the
            // capture itself ("lost" B), the same as B to Home. Leaving then closes the game's window the room's way (one secure
            // click), as every leave does: that is what the player's B meant
            if (msg.optString("lost") == "B") { onLeave(); return }
            board.invalidate(); return
        }
        val k = msg.optString("k")
        // B was the game's own close button: the game has closed its window and the controller went back with "B"
        if (k == "BACK") { leftByB = true; onLeave(); return }
        // LB / RB: the next tab, as the game's own tab indicators (capture version 4)
        if (k == "PADLSHOULDER" || k == "PADRSHOULDER") {
            M.nextTab(ProfessionsStore.overview, viewed, if (k == "PADLSHOULDER") -1 else 1)?.let { select(it) }
            board.invalidate(); return
        }
        if (focus == null || board.nav.none { it.key == focus }) {       // not drawn yet (the glow is drawn from the start)
            focus = startTarget()
            if (focus is Fk.Rec) lastRow = focus
            keepInView()
        }
        when (k) {
            "PADDUP" -> moveOpen(0, -1); "PADDDOWN" -> moveOpen(0, 1); "PADDLEFT" -> moveOpen(-1, 0); "PADDRIGHT" -> moveOpen(1, 0)
            // A / X / Y: the button they perform shows DOWN at once (board.flash, the drawing only; the user 2026-10-07)
            "PAD1" -> { val f = focus; board.nav.firstOrNull { it.key == f }?.act?.let { board.flash(f); it() } }   // A
            "PAD2" -> onLeave()                                                     // B, with nothing open in the game: Home
            "PAD3" -> if (viewed != 0) board.nav.firstOrNull { it.key == Fk.Create }?.act?.let { board.flash(Fk.Create); it() }   // X = Create
            "PAD4" -> if (viewed != 0) board.nav.firstOrNull { it.key == Fk.Track }?.act?.let { board.flash(Fk.Track); it() }     // Y = Track
        }
        board.invalidate()
    }

    /**
     * Where the glow starts (the user, LATER 19 via Home: "when opening to a page that has tabs and something already highlighted
     * because its selected, that feels like a really great natural starting point for the cursor"): on a page its chosen recipe
     * (the row the game shows selected), on the book the selected tab; else the first target, as before.
     */
    private fun startTarget(): Fk? {
        val nav = board.nav
        fun has(k: Fk) = nav.any { it.key == k }
        if (viewed != 0) chosen[viewed]?.let { Fk.Rec(it) }?.takeIf { has(it) }?.let { return it }
        return Fk.Tab(viewed).takeIf { has(it) } ?: firstTarget()
    }

    private fun firstTarget(): Fk? {
        val nav = board.nav
        return if (viewed == 0) nav.firstOrNull { it.key is Fk.Btn }?.key
        else (nav.firstOrNull { it.key is Fk.Head || it.key is Fk.Rec }
            ?: nav.firstOrNull { it.key == Fk.All } ?: nav.firstOrNull { it.key == Fk.Create })?.key
    }

    /** b117 (the user 2026-10-07 on Character: "the character tabs auto open to the tab that the cursor is on- I REALLY like this and want to make the other tabs consistent"): the cursor that lands on a tab opens it, as A did (one open per landing; select's own wait guards a repeat) */
    private fun moveOpen(dx: Int, dy: Int) {
        val was = focus
        move(dx, dy)
        val now = focus
        if (now is Fk.Tab && now != was && now.id != viewed) select(now.id)
    }

    private fun move(dx: Int, dy: Int) {
        val nav = board.nav
        val f = focus ?: return
        val rows = nav.filter { it.key is Fk.Head || it.key is Fk.Rec }
        fun has(k: Fk) = nav.any { it.key == k }
        fun toFoot() { focus = listOf(Fk.All, Fk.Create).firstOrNull { has(it) } ?: f }
        fun toRows() { focus = lastRow?.takeIf { has(it) } ?: rows.firstOrNull()?.key ?: f }
        when (f) {
            is Fk.Btn -> {                                     // the book's live buttons, in the order drawn
                val btns = nav.filter { it.key is Fk.Btn }
                val i = btns.indexOfFirst { it.key == f }
                val next = btns.getOrNull(i + 1)?.key as? Fk.Btn
                when {
                    dx > 0 -> focus = if (next != null && next.id == f.id) next else Fk.Tab(viewed)   // right: its neighbour, else the tabs
                    dy > 0 -> btns.getOrNull(i + 1)?.let { focus = it.key }
                    else -> btns.getOrNull(i - 1)?.let { focus = it.key }
                }
            }
            is Fk.Tab -> {                                     // the glow walks the tabs; landing opens the tab (moveOpen, b117)
                val tabs = nav.filter { it.key is Fk.Tab }
                val i = tabs.indexOfFirst { it.key == f }
                when {
                    dy != 0 -> tabs.getOrNull(i + dy)?.let { focus = it.key }
                    dx < 0 -> focus = if (viewed == 0) nav.firstOrNull { it.key is Fk.Btn }?.key ?: f
                                      else if (has(Fk.Track)) Fk.Track else rows.firstOrNull()?.key ?: f
                }
            }
            is Fk.Head, is Fk.Rec -> {
                val i = rows.indexOfFirst { it.key == f }
                when {
                    dy < 0 -> rows.getOrNull(i - 1)?.let { focus = it.key }
                    dy > 0 -> { val next = rows.getOrNull(i + 1); if (next != null) focus = next.key else { lastRow = f; toFoot() } }
                    dx > 0 -> { lastRow = f; focus = if (has(Fk.Track)) Fk.Track else Fk.Tab(viewed) }
                }
            }
            Fk.Track -> when {
                dx < 0 -> toRows()
                dx > 0 -> focus = Fk.Tab(viewed)
                dy > 0 -> toFoot()
            }
            Fk.All -> when { dx > 0 && has(Fk.Count) -> focus = Fk.Count; dy < 0 -> toRows() }
            Fk.Count -> when {                                 // left / right change the count; up leaves it (SPEC 5)
                dx != 0 -> board.countStep(dx)
                dy < 0 -> toRows()
                dy > 0 && has(Fk.Create) -> focus = Fk.Create  // my addition: the SPEC gives no other way from the count to Create
            }
            Fk.Create -> when { dx < 0 && has(Fk.Count) -> focus = Fk.Count; dy < 0 -> toRows() }
        }
        if (focus is Fk.Head || focus is Fk.Rec) lastRow = focus
        keepInView()
    }

    /** scroll the list (or the detail) so the glow is in view */
    private fun keepInView() {
        val f = focus ?: return
        val n = board.nav.firstOrNull { it.key == f } ?: return
        if (f is Fk.Head || f is Fk.Rec) {
            val view = board.listBox.bottom - 160f
            if (n.top < listScroll) listScroll = n.top
            else if (n.top + n.h > listScroll + view) listScroll = n.top + n.h - view
        } else if (f == Fk.Track) {
            val view = board.detailBox.height()
            val top = board.trackTop - 62f
            if (top < detailScroll) detailScroll = top
            else if (top + 111f > detailScroll + view) detailScroll = top + 111f - view
        }
    }

    // ------------------------------------------------------------ actions

    private fun choose(book: M.Book, id: Int) {
        chosen[book.id] = id
        detailScroll = 0f
        syncSelection()
        board.invalidate()
    }

    private fun track(r: M.Recipe, want: Boolean) {
        val book = ProfessionsStore.book(viewed) ?: return
        if (pendingTap != 0L && SystemClock.uptimeMillis() - pendingTap < TAP_WAIT) return
        if (send(M.trackTap(ProfessionsStore.overview, book, r, want))) pendingTap = SystemClock.uptimeMillis()
        board.invalidate()
    }

    private fun countOf(r: M.Recipe, book: M.Book): Int {
        val range = M.countRange(M.canMake(r, ProfessionsStore.counts, live(book.id)))
        return (count[r.id] ?: 1).coerceIn(range.first, range.last)
    }

    private fun setCount(r: M.Recipe, n: Int) { count[r.id] = n; board.invalidate() }

    // ------------------------------------------------------------ small helpers
    companion object {
        const val SPLIT = 600f
        private const val TAG = "WCPROF"
        private const val MESSAGE_MS = 3000L
        private const val OPEN_WAIT = 6000L
        private const val TAP_WAIT = 3000L
        private const val CLOSE_WAIT = 1200L       // the close cell's answer; after it the controller is handed back anyway
        private const val MODE_WAIT = 1500L        // the same hold tap is not sent twice within this
        private const val ENTRY_QUIET = 3000L      // after entering, the game's own profession jumps are not followed
        private const val OPEN_RETRY = 1200L       // an open not answered by then is sent once more
        private const val BOOK_WAIT = 4000L        // the entry's book tap waits this long, at most, for the room to hold
        private const val HANDOVER_SETTLE = 450L   // b97: the book tap is never sent sooner than this after a holder change
        private const val PRESS_MIN_MS = 160L      // a press stays visibly DOWN at least this long (Interact's PressLook rule)

        private val DIFF = intArrayOf(Color.rgb(255, 128, 64), Color.rgb(255, 255, 0), Color.rgb(64, 191, 64), Color.rgb(128, 128, 128))
        fun diffColour(d: Int) = if (d in 0..3) DIFF[d] else Theme.text

        /** [p] made smaller, down to [min], until [text] fits [width] */
        fun fitted(text: String, p: Paint, width: Float, min: Float): Paint {
            while (p.measureText(text) > width && p.textSize > min) p.textSize = p.textSize - 1f
            return p
        }

        /** the game's colour "rrggbb" as sent by the addon, or [def] */
        fun hexColour(hex: String?, def: Int): Int =
            if (hex != null && hex.length == 6) runCatching { Color.parseColor("#$hex") }.getOrDefault(def) else def

        fun fit(text: String, p: Paint, width: Float): String =
            TextUtils.ellipsize(text, android.text.TextPaint(p), maxOf(width, 10f), TextUtils.TruncateAt.END).toString()

        /** words into at most [max] lines of [width]; the last one ellipsized */
        fun wrap(text: String, p: Paint, width: Float, max: Int): List<String> {
            val out = ArrayList<String>()
            var line = ""
            val words = text.split(' ')
            for ((i, word) in words.withIndex()) {
                val t = if (line.isEmpty()) word else "$line $word"
                if (p.measureText(t) <= width || line.isEmpty()) { line = t; continue }
                out.add(line)
                if (out.size == max - 1) { out.add(fit(words.subList(i, words.size).joinToString(" "), p, width)); return out }
                line = word
            }
            if (line.isNotEmpty()) out.add(line)
            return out
        }
    }
}
