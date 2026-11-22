package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.jrees.wowcompanion.MacrosModel.State
import org.json.JSONObject

/**
 * THE MACROS ROOM (the user, 2026-11-13: "Lets scrap the smart ranged attack and start building a macro room. The calender can be moved
 * up to a button on the right side of the map name make it small. Use the room slot for macros."), the game's macro window rebuilt
 * (MacroFrame: its General and character tabs, a 6-wide icon grid, the selected macro beside it, n/255). The user's picks: D1 a macro
 * changes ONLY on the player's New / Done / Delete, never in a fight, on both tabs; D2 the game's own window opening brings this room
 * up (CompanionHost) and the room closes that window ONCE with its own close button (the `wx` cell's secure click); D3 A opens a macro
 * to edit, X puts it on a button (the Spellbook's Bind box, hosted by this room: SpellsOverlay draws it); D4 a typed line (the chat
 * keyboard, the add-on's borrowed field); D7 stand-in art (the question-mark icon).
 * Spells s14 THE MACRO BUILDER v2 (the user, 2026-11-16: the recipes "make no sense" and only add lines; every RECOMMENDED pick): the
 * editor shows each line as a STRIP (D3: the spell's icon, "on Focus", "if Enemy", "else Me", the code small under it), an empty macro
 * its PRESET tiles (D2); A on a line opens the BUILDER (D1: the action and its spell, then the cases), each chip's pop-out sliding out
 * of it (UiKit.slideOut); Select or the Done button finishes a line (D4); the companion's own macros carry a lock, view only (D6). Every
 * chip and pop-out cell is the add-on's (its s-cells); the app has no grammar.
 * Spells s15 THE LOOK PASS (the user, 2026-11-16: "it needs to be a bit more visually consistent and the icon needs work for the room";
 * the pick "Full match"): drawn as the other rooms are (see [Pane]); the editor's + Line / More and the builder's Done / More, More's
 * rows (Presets / Delete, Aa Text / Remove or Revert) a one-column pop-out; Delete, Remove and Replace ASK (Cancel [B] / Confirm [A]).
 *
 * The STATE is the add-on's ([MacrosModel]); this view draws its `macros` document, holds / frees the controller as the room shows
 * (the add-on's `h` / `f` / `fb`, no game action), and turns a finger into ONE tap on the room's row: select the k-th thing on show
 * (s<k>), then A where the thing is an action (an editor row, a pick, a tile, "+"). The labels are menu words (Tr.t); the macros' names
 * and lines are the player's own text, never translated. B leads Home at the grid (CompanionHost.bPressed); one step back elsewhere.
 */
class MacrosView(c: Context) : FrameLayout(c), BLeadsHome {

    /** B that leaves the room (the add-on's macpad BACK): the activity shows Home */
    var onLeave: () -> Unit = {}
    /** the shared grid for the lazy row's padtab pick (no `macros` pad before the row exists): the quests / spells documents' */
    var padSource: () -> JSONObject? = { null }
    /** a document-free preview (the shots tests): no taps, no timers */
    var dryRun = false
    /** s14: a spell's description by its id (the spells document the Spellbook room parses: SpellsModel's `desc`), for the Spell / Item
     *  pop-out's tooltip; null = none (an item's: its name and picture only) */
    var spellTip: (Int) -> String? = { null }
    /** s15: the spells document the Spellbook room parses, for the Spell / Item pop-out's icon tabs (the class's picture on General, the
     *  pet's own on Pet, as the Controller room's chooser and the Spellbook draw them); null = none yet */
    var book: () -> SpellsModel.Book? = { null }

    var state: State? = null
        private set
    private var shown = false
    private var holdSent = false
    private var leftByB = false
    private var rowAskedAt = -1_000_000L
    /** the lazy row's asks this showing (a fight: the add-on makes secure rows out of combat only; asked again after it) */
    private var rowTries = 0
    private var wasFight = false
    private var holdTapAt = -1_000_000L
    private var freeTapAt = -1_000_000L
    private var retries = 0
    private var wxSent = false
    private val recheck = Runnable { reconcile() }

    private val pane = Pane(c)
    /** the chat keyboard, along the room's bottom (Bags' own pattern): the add-on's borrowed field types it */
    val keyboard = ChatKeyboard(c, swap = false).apply { visibility = View.GONE; sendWord = "Done" }

    init {
        ThemeLive.bg(this, Tok.INK)
        addView(pane, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(keyboard, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM))
    }

    // ---------------------------------------------------------------- documents; showing and leaving

    /** a `macros` / `macack` document; [live] = CURRENT (not the backlog LogTail replays at start): the automatic taps wait for one */
    fun onDoc(msg: JSONObject, live: Boolean = true) {
        when (msg.optString("type")) {
            "macros" -> MacrosModel.parse(msg)?.let { s ->
                val hadKeys = state?.keys != null
                state = s
                if (wasFight && !s.combat) rowTries = 0                        // the fight's end: the row may be made now
                wasFight = s.combat
                if (!s.win) wxSent = false
                if (live && shown) {
                    if (!holdSent && s.keys != null) sendHold(if (hadKeys) "on a document" else "the row is made")
                    // D2: the game's own window is open while the room holds: its own close button, ONCE per opening (Spells s10 review:
                    // only once the cell IS that button - one made before the game's macro UI loaded is armed later, out of combat)
                    if (MacrosModel.closeWindowNow(s, wxSent)) { wxSent = tap("wx"); Log.i(TAG, "the game's window: close ${if (wxSent) "sent" else "not sent"}") }
                }
                takeKbd(s, live)
            }
            "macack" -> if (!msg.optBoolean("ok", true)) Log.i(TAG, "refused: ${msg.optString("op")} (${msg.optString("why")})")
        }
        if (live) reconcile()
        pane.invalidate()
    }

    /** the room came on the screen: it takes the controller; with no row yet, it asks for the row first (Book's padtab pick) */
    fun onShown() {
        shown = true; leftByB = false; retries = 0; holdSent = false; rowTries = 0
        // Spells s12: a row shared with the Calendar's is asked on EVERY showing (the Calendar may have it back): the hold then goes on
        // the document that has the keys, never on keys a document gave before the row changed hands
        if (state?.keys == null || state?.shared == true) { rowAskedAt = -1_000_000L; askRow("shown") } else sendHold("shown")
        reconcileLater()
        pane.invalidate()
    }

    /** the room was left: the controller goes back (`f`, or `fb` when B left it); a keyboard or the Bind box goes with it */
    fun onHidden() {
        if (shown && (holdSent || state?.held == true)) {
            val sent = tap(if (leftByB) "fb" else "f")
            if (sent) freeTapAt = SystemClock.uptimeMillis()
            Log.i(TAG, "hidden: ${if (leftByB) "fb" else "f"} ${if (sent) "sent" else "not sent"}")
        }
        hideKeyboard()
        retries = 0
        reconcileLater()
        shown = false; holdSent = false; leftByB = false
    }

    /** Home's self-heal: the add-on still holds for this room while it is not on screen (one `f`, no game action) */
    fun freeAgain(): Boolean {
        val sent = tap("f")
        if (sent) freeTapAt = SystemClock.uptimeMillis()
        Log.i(TAG, "free again: ${if (sent) "sent" else "not sent"}")
        return sent
    }

    /** the add-on's presses relayed while it holds: only its BACK (B at the grid) and a lost hold matter to the app */
    fun onPad(msg: JSONObject) {
        if (msg.optString("k") == "BACK" && shown) { leftByB = true; onLeave() }
    }

    /** a finger's A / B in the Bind box this room hosts (SpellsOverlay's hosted Keep / Undo / Back): the room's own a / b cells */
    fun boxKey(k: String) { if (k == "a" || k == "b") tap(k) }

    override fun bLeadsHome(): Boolean = MacrosModel.bLeadsHome(state) && kbField == null

    private fun askRow(why: String) {
        val now = SystemClock.uptimeMillis()
        if (dryRun || now - rowAskedAt < RETRY_MS || rowTries >= RETRY_MAX) return
        rowTries++
        val sent = TapBridge.taps(context, padSource(), MacrosModel.rowCells()) { w -> Log.i(TAG, "row: refused ($w)") }
        if (sent) rowAskedAt = now
        Log.i(TAG, "row ($why): padtab ${MacrosModel.PICK} then ${MacrosModel.ROW_CODE} ${if (sent) "sent" else "not sent"}")
    }

    private fun sendHold(why: String): Boolean {
        val sent = tap("h")
        if (sent) { holdSent = true; holdTapAt = SystemClock.uptimeMillis() }
        Log.i(TAG, "hold ($why): ${if (sent) "sent" else "not sent"}")
        return sent
    }

    /** the add-on's own word against the screen (Calendar's c10 rule): a lost h / f goes again, RETRY_MAX times at most */
    private fun reconcile() {
        removeCallbacks(recheck)
        if (dryRun) return
        if (shown && state?.keys == null && state?.noRow != true) {
            if (rowTries < RETRY_MAX) { askRow("no row yet"); postDelayed(recheck, RETRY_MS + 50) }
            return
        }
        val fix = MacrosModel.ctlFix(state, shown)
        if (fix == null) { retries = 0; return }
        val since = SystemClock.uptimeMillis() - (if (fix == "h") holdTapAt else freeTapAt)
        if (since < RETRY_MS) { postDelayed(recheck, RETRY_MS - since + 50); return }
        if (retries >= RETRY_MAX) return
        retries++
        val sent = if (fix == "h") sendHold("again, try $retries") else freeAgain()
        if (sent) postDelayed(recheck, RETRY_MS + 50)
    }

    private fun reconcileLater() { removeCallbacks(recheck); if (!dryRun) postDelayed(recheck, RETRY_MS + 50) }

    private fun tap(k: String): Boolean {
        if (dryRun) return false
        val s = state ?: return false
        val cells = MacrosModel.cell(s, k) ?: return false
        return TapBridge.taps(context, s.pad ?: padSource(), cells) { w -> Log.i(TAG, "tap $k refused ($w)") }
    }

    private fun taps(cells: List<Pair<String, Int>>?): Boolean {
        if (dryRun || cells == null) return false
        val s = state ?: return false
        return TapBridge.taps(context, s.pad ?: padSource(), cells) { w -> Log.i(TAG, "taps refused ($w)") }
    }

    // ---------------------------------------------------------------- the keyboard (the add-on's borrowed chat field, InteractView's way)

    private var kbField: String? = null
    private var kbText = ""
    private var kbMax = ChatStream.MAX
    private val fieldStream = ChatStream({ cells, gap ->
        TapBridge.tapsSpaced(context, state?.pad ?: padSource(), cells, gap) { why -> Log.i(TAG, "keyboard: $why") }
    }, field = true).apply { onEvent = { _ -> post { pane.invalidate() } } }
    private val fieldTick = object : Runnable {
        override fun run() { fieldStream.tick(); if (fieldStream.sending) postDelayed(this, 500L) }
    }

    private fun takeKbd(s: State, live: Boolean) {
        val k = s.kbd
        if (k == null) { if (kbField != null) hideKeyboard(); return }
        if (!live && !dryRun) return
        if (k.name == kbField) return
        kbField = k.name
        kbMax = k.max.coerceIn(1, ChatStream.MAX)
        kbText = fieldStream.clean(k.text, kbMax)
        fieldStream.startField(kbText)
        keyboard.text = { kbText }
        keyboard.onText = { t -> kbText = fieldStream.clean(t, kbMax); fieldStream.setBody(kbText); pane.invalidate() }
        keyboard.onSend = { if (fieldStream.send()) { removeCallbacks(fieldTick); postDelayed(fieldTick, 500L) } }
        keyboard.onClose = { tap("kx"); hideKeyboard() }
        keyboard.open(); keyboard.visibility = View.VISIBLE
    }

    private fun hideKeyboard() {
        kbField = null; kbRepeat = null
        removeCallbacks(kbTick); removeCallbacks(fieldTick)
        keyboard.visibility = View.GONE
        pane.invalidate()
    }

    /** the keyboard is up: Start is its Done (CompanionHost asks before the pause wheel takes Start) */
    fun ownsStart(): Boolean = kbField != null && isShown

    private var kbRepeat: String? = null
    private val kbTick = object : Runnable {
        override fun run() { val k = kbRepeat ?: return; keyboard.press(k); postDelayed(this, KB_REPEAT_MS) }
    }

    /** a controller key from the host's own reader (only while the keyboard is up; the add-on stands still meanwhile) */
    fun onPadKey(k: String, down: Boolean) {
        if (kbField == null || !isShown) return
        val dpad = k == "PADDUP" || k == "PADDDOWN" || k == "PADDLEFT" || k == "PADDRIGHT"
        if (!down) { if (kbRepeat == k) { kbRepeat = null; removeCallbacks(kbTick) }; return }
        keyboard.press(k)
        if (dpad && kbField != null) { kbRepeat = k; removeCallbacks(kbTick); postDelayed(kbTick, KB_REPEAT_FIRST_MS) }
    }

    /** Chat.lua's answers ({"type":"chatt"}): the field's check and its hand-over */
    fun onChatAnswer(msg: JSONObject) {
        if (kbField == null || msg.optString("type") != "chatt") return
        fieldStream.heard(ChatStream.Heard("chatt", msg.optString("op"), msg.optBoolean("ok"),
            if (msg.has("why")) msg.optString("why") else null, msg.optString("ty", ""),
            msg.optInt("len", -1), msg.optInt("h", -1), msg.optInt("tlen", -1), msg.optInt("th", -1), msg.optString("into")))
    }

    // ---------------------------------------------------------------- the page

    /**
     * Spells s15 THE LOOK PASS (the user, 2026-11-16: "it needs to be a bit more visually consistent and the icon needs work for the
     * room"; the pick "Full match"): drawn as the other rooms are - the rooms' flat header (no window frame, no X: B leaves through the
     * add-on and the bar's Home disc says so; a sub-screen's "B Back" stands at the header's right end, a finger's back too), the grid's
     * two panes and ONE pane for the editor / builder / presets / preview, the tabs with the game's gold arrows, Morpheus headings,
     * Quests' action band (one A button and Y More; Cancel [B] / Confirm [A] for Delete / Remove / Replace), the builder's chips in the
     * theme's colours (the ring edge only on the big containers: the case cards, the lines' well, the preset tiles), the kit's lock and
     * plus, the book's paging, the game's tooltip, ONE status at the header's right end, one empty / waiting line.
     */
    private inner class Pane(c: Context) : View(c) {
        private val hits = ArrayList<Pair<RectF, List<Pair<String, Int>>?>>()
        private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val pBmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        /** s14: the open pop-out and when it opened (UiKit.slideOut's clock) */
        private var popKey: String? = null
        private var popAt = 0L
        /** s14: where each chip was drawn this frame (a pop-out slides out of its chip) */
        private val chipAt = HashMap<String, RectF>()
        /** s15: the grid's two tabs and the Spell / Item pop-out's icon tabs (the gaps round the chosen one, where the gold arrows stand) */
        private val gridGaps = UiKit.TabGaps(vertical = false)
        private val popGaps = UiKit.TabGaps(vertical = false)

        override fun onDraw(c: Canvas) {
            hits.clear(); chipAt.clear()
            val w = width.toFloat(); val h = height.toFloat()
            val full = RectF(0f, 0f, w, h)
            c.drawColor(Theme.ink)
            UiKit.header(c, w, "Macros")
            val body = RectF(0f, HEADER, w, h)
            val s = state
            when {
                s == null || !s.ready -> { UiKit.pane(c, body); say(c, body, "Waiting for the game") }
                s.off -> { UiKit.pane(c, body); lockScreen(c, body, "Macros are off") }
                else -> {
                    when (s.view) {
                        "edit", "move" -> editor(c, s, body)
                        "build" -> builder(c, s, body)
                        "pre" -> presets(c, s, body)
                        "ps" -> preview(c, s, body)
                        else -> grid(c, s, body)
                    }
                    headerRight(c, s, w)
                    (s.builder?.pop ?: s.pop)?.let { popOut(c, s, it, body) } ?: run { popKey = null }
                }
            }
            if (kbField != null) typingLine(c, full)
        }

        private fun hit(r: RectF, cells: List<Pair<String, Int>>?) { if (cells != null) hits += RectF(r) to cells }

        /** the room's one empty / waiting line (Quests' own): 22 px, dim, at the pane's top left */
        private fun say(c: Canvas, r: RectF, text: String, x: Float = r.left + 24f) {
            if (text.isEmpty()) return
            c.drawText(Tr.t(text), x, r.top + 48f, UiKit.bodyPaint(22f, Theme.dim))
        }

        /** the game rule turns macros off (C_GameRules MacrosDisabled): the lock, and nothing to touch */
        private fun lockScreen(c: Canvas, r: RectF, text: String) {
            lock(c, r.left + 44f, r.top + 40f, 40f)
            say(c, r, text, x = r.left + 76f)
        }

        /** the game's lock (the kit's bank_lock: bankslot-icon-lock, as UiKit.bankSlot draws it), [size] px; drawn only without the kit */
        private fun lock(c: Canvas, cx: Float, cy: Float, size: Float, alpha: Int = 255) {
            UiKit.tex("bank_lock")?.let { b ->
                val k = size / maxOf(b.width, b.height)
                val bw = b.width * k; val bh = b.height * k
                pBmp.alpha = alpha
                c.drawBitmap(b, null, RectF(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f), pBmp)
                pBmp.alpha = 255
                return
            }
            val colour = Theme.gold
            pLine.color = colour; pLine.strokeWidth = size * 0.11f; pLine.alpha = alpha
            val bw = size * 0.72f; val bh = size * 0.52f
            val body = RectF(cx - bw / 2f, cy - bh / 2f + size * 0.16f, cx + bw / 2f, cy + bh / 2f + size * 0.16f)
            pFill.color = colour; pFill.alpha = alpha; c.drawRoundRect(body, size * 0.08f, size * 0.08f, pFill)
            val arc = RectF(cx - bw * 0.32f, body.top - size * 0.42f, cx + bw * 0.32f, body.top + size * 0.2f)
            c.drawArc(arc, 180f, 180f, false, pLine)
            pFill.alpha = 255; pLine.alpha = 255
        }

        private fun nameFit(text: String, p: Paint, maxW: Float): String {
            if (maxW <= 0f) return ""
            if (p.measureText(text) <= maxW) return text
            val n = p.breakText(text, true, (maxW - p.measureText("…")).coerceAtLeast(0f), null)
            return text.take(n) + "…"
        }

        private fun icon(id: Int): android.graphics.Bitmap? = Icons.get(id.takeIf { it > 0 } ?: Icons.QUESTION_MARK)

        private fun yGlyph(): String = Glyphs.translate("rev_y", CastSettings.buttonLayout)
        private fun selectGlyph(): String = Glyphs.translate("rev_minus", CastSettings.buttonLayout)

        /** a red button with its glyph, or none */
        private fun button(c: Canvas, r: RectF, label: String, glyph: String?, state: UiKit.Button = UiKit.Button.UP) {
            if (glyph != null) UiKit.button(c, r, label, glyph, state) else UiKit.button(c, r, label, state)
        }

        /** what asks now (Spells s15: Delete / Remove / Replace, the add-on's): a pop-out never stands over one */
        private fun asking(s: State): Boolean = (s.view == "edit" && (s.del || s.remLine != null)) ||
            (s.view == "build" && s.builder?.remArmed == true) || (s.view == "ps" && s.preset?.armed == true)

        // ---- the header's right end: a sub-screen's B Back (a finger's back too), then the ONE status word left of it
        private fun headerRight(c: Canvas, s: State, w: Float) {
            var right = w - 24f
            val sub = s.view == "edit" || s.view == "move" || s.view == "build" || s.view == "pre" || s.view == "ps"
            if (sub && kbField == null && (s.builder?.pop ?: s.pop) == null && !asking(s)) {
                val hx = UiKit.hints(c, w - 20f, HEADER / 2f, UiKit.backGlyph() to "Back")
                hit(RectF(hx - 12f, 0f, w, HEADER), MacrosModel.cell(s, "b"))
                right = hx - 30f
            }
            status(c, s, right)
        }

        /** the room's ONE status (the Spellbook's place): a refusal's word, the game's window, no pad row, a fight; in the builder its
         *  own word folded in (a hard fault red, a soft one amber, Draft dim) */
        private fun status(c: Canvas, s: State, right: Float) {
            val room = when {
                s.noRow -> "No pad row"
                s.win -> "Game window open"
                s.view == "move" && s.note == null -> "Move"
                else -> MacrosModel.noteWord(s.note) ?: MacrosModel.hardWord(s.note)?.takeIf { s.note == "pick" }
                    ?: if (s.combat) "In combat" else null
            }
            val b = s.builder?.takeIf { s.view == "build" }
            val (word, colour) = when {
                room != null -> room to (if (s.note != null) Theme.warn else Theme.dim)
                b != null -> MacrosModel.hardWord(b.hard)?.let { it to Theme.unusable }
                    ?: MacrosModel.warnWord(b.warns.firstOrNull())?.let { it to Theme.warn }
                    ?: (if (b.draft) "Draft" to Theme.dim else null)
                    ?: return
                else -> return
            }
            val p = UiKit.bodyPaint(24f, colour).apply { textAlign = Paint.Align.RIGHT }
            c.drawText(nameFit(Tr.t(word), p, 420f), right, HEADER / 2f - (p.ascent() + p.descent()) / 2f, p)
        }

        // ---- Quests' action band at a single pane's foot: Theme.panel, the bronze hairline, two buttons half the width, 88 px tall
        private fun band(c: Canvas, r: RectF): Pair<RectF, RectF> {
            val top = r.bottom - BAND_H
            pFill.color = Theme.panel; c.drawRect(r.left, top, r.right, r.bottom, pFill)
            pFill.color = Theme.bronzeDim; c.drawRect(r.left, top, r.right, top + 1f, pFill)
            val half = (r.width() - PAD * 2 - 10f) / 2f
            val a = RectF(r.left + PAD, top + 12f, r.left + PAD + half, r.bottom - 12f)
            return a to RectF(a.right + 10f, a.top, r.right - PAD, a.bottom)
        }

        /** an ask (the user's pick: Cancel / Confirm): Cancel [B] on the left, the confirm [A] where the button that asked stood */
        private fun askPair(c: Canvas, s: State, a: RectF, b: RectF, word: String) {
            button(c, a, "Cancel", if (s.held) UiKit.backGlyph() else null)
            button(c, b, word, if (s.held) UiKit.okGlyph() else null)
            hit(a, MacrosModel.cell(s, "b"))
            hit(b, MacrosModel.cell(s, "a"))
        }

        /** the room's More (Quests' Y More): its Y while Y opens it, the gold box while the cursor is on it */
        private fun moreButton(c: Canvas, s: State, r: RectF, focused: Boolean, yOpens: Boolean, cells: List<Pair<String, Int>>?) {
            button(c, r, "More", if (s.held && yOpens) yGlyph() else null)
            if (focused) UiKit.focus(c, r)
            hit(r, cells)
            chipAt["more"] = r
        }

        // ---- the grid (the game's window: tabs, 6 wide, the selection beside it)
        private fun grid(c: Canvas, s: State, r: RectF) {
            val lp = RectF(r.left, r.top, GRID_SPLIT, r.bottom)
            val side = RectF(GRID_SPLIT, r.top, r.right, r.bottom)
            UiKit.pane(c, lp)
            UiKit.pane(c, side, right = true)
            // the tabs: General n/max, the character's n/max (the game's GENERAL_MACROS / CHARACTER_SPECIFIC_MACROS, terse); the cursor's
            // tab is the kit's lit tab with the game's gold arrows beside it (D-pad Left / Right; A opens it)
            val open = if (s.tab == "c") 1 else 0
            val onTabs = s.cur == -1 || s.cur == -2
            val curTab = if (onTabs) -s.cur - 1 else open
            val tabs = gridGaps.row(2, lp.left + 8f, lp.right - 8f, r.top + 14f, 64f, 300f, curTab, this)
            for ((i, tr) in tabs.withIndex()) {
                val on = i == open
                UiKit.tab(c, tr, "", on, onTop = true, cursor = s.cur == -(i + 1))
                // the label drawn here, not by the kit's tab (it runs Tr.t): the character's name is never translated
                val cnt = if (i == 0) s.general else s.char
                val label = if (i == 0) Tr.t("General") else s.me.ifEmpty { Tr.t("Character") }
                val p = UiKit.bodyPaint(26f, if (on) Color.WHITE else Theme.gold)
                val np = UiKit.numberPaint(22f, Theme.textDim)
                val num = "${cnt.n}/${cnt.max}"
                val shown = nameFit(label, p, tr.width() - np.measureText(num) - 44f)
                val tw = p.measureText(shown) + 10f + np.measureText(num)
                val x0 = tr.centerX() - tw / 2f
                val ty = tr.centerY() + 8f - (p.ascent() + p.descent()) / 2f
                c.drawText(shown, x0, ty, p)
                c.drawText(num, x0 + p.measureText(shown) + 10f, ty, np)
                hit(tr, MacrosModel.cell(s, if (i == 0) "tg" else "tc"))
            }
            if (onTabs) UiKit.moveArrows(c, tabs[curTab], left = curTab > 0, right = curTab < 1, words = false)
            // the cells: 6 x 4, an icon in its slot and the name under it
            val gx = lp.left + 24f; val gy = r.top + 96f
            val cw = 112f; val ch = 142f
            for ((k, cell) in s.cells.withIndex()) {
                val col = k % 6; val row = k / 6
                val x = gx + col * cw; val y = gy + row * ch
                val icon = RectF(x + 14f, y + 6f, x + 98f, y + 90f)
                val np = UiKit.bodyPaint(20f, Theme.text).apply { textAlign = Paint.Align.CENTER }
                if (cell.plus) {
                    UiKit.slot(c, null, icon.left, icon.top, icon.width())
                    plus(c, icon)
                    c.drawText(Tr.t("New"), icon.centerX(), y + 120f, np)
                    hit(RectF(x, y, x + cw, y + ch), MacrosModel.pickCells(s, k, act = true))
                } else {
                    UiKit.slot(c, icon(cell.icon), icon.left, icon.top, icon.width())
                    if (cell.locked) lock(c, icon.right - 14f, icon.bottom - 16f, 30f)                // s14 D6: the companion's own
                    c.drawText(nameFit(cell.name, np, cw - 8f), icon.centerX(), y + 120f, np)       // the player's text: never Tr.t
                    hit(RectF(x, y, x + cw, y + ch), MacrosModel.pickCells(s, k, act = false))
                }
                if (k == s.cur) UiKit.focus(c, RectF(icon.left - 4f, icon.top - 4f, icon.right + 4f, icon.bottom + 4f))
            }
            // the pages: the book's own paging under the grid, only when there is more than one (the Spellbook's)
            if (s.pages > 1) {
                val py = gy + 4 * ch + 40f
                val mid = gx + 3 * cw
                UiKit.bookPaging(c, mid, py, s.page, s.pages, onPaper = false)
                if (s.page > 1) hit(RectF(mid - 206f, py - 55f, mid - 94f, py + 56f), MacrosModel.cell(s, "pp"))
                if (s.page < s.pages) hit(RectF(mid + 94f, py - 55f, mid + 206f, py + 56f), MacrosModel.cell(s, "pn"))
            }
            // the selection beside the grid (read only): its icon, name, lines, n/255; A Edit and X Bind under it (the Spellbook's Bind's size)
            val m = s.mac.takeIf { s.cur >= 0 && s.cells.getOrNull(s.cur)?.plus == false }
            if (m == null) {
                if (s.cells.isEmpty()) say(c, side, "No macros")
                return
            }
            val bTop = r.bottom - 141f
            macPreview(c, m, RectF(side.left + 24f, side.top + 24f, side.right - 24f, bTop - 20f))
            val bw = (side.width() - 48f - 16f) / 2f
            val eb = RectF(side.left + 24f, bTop, side.left + 24f + bw, r.bottom - 30f)
            val xb = RectF(eb.right + 16f, bTop, side.right - 24f, r.bottom - 30f)
            val canEdit = !m.locked
            val canBind = !s.combat && !m.locked
            UiKit.button(c, eb, "Edit", UiKit.okGlyph(), if (canEdit) UiKit.Button.UP else UiKit.Button.DISABLED)
            UiKit.button(c, xb, "Bind", UiKit.xGlyph(), if (canBind) UiKit.Button.UP else UiKit.Button.DISABLED)
            if (canEdit) hit(eb, MacrosModel.cell(s, "a"))
            if (canBind) hit(xb, MacrosModel.cell(s, "x"))
        }

        /** the "+" tile's plus: the kit's list plus (the game's common-button-list-plus) at 0.6 of the slot; drawn only without the kit */
        private fun plus(c: Canvas, slot: RectF) {
            UiKit.tex("list_plus")?.let { b ->
                val s = slot.width() * 0.6f
                val k = s / maxOf(b.width, b.height)
                val bw = b.width * k; val bh = b.height * k
                c.drawBitmap(b, null, RectF(slot.centerX() - bw / 2f, slot.centerY() - bh / 2f, slot.centerX() + bw / 2f, slot.centerY() + bh / 2f), pBmp)
                return
            }
            val pp = UiKit.titlePaint(56f, Theme.gold).apply { textAlign = Paint.Align.CENTER }
            c.drawText("+", slot.centerX(), slot.centerY() - (pp.ascent() + pp.descent()) / 2f, pp)
        }

        /** a macro as the game's window shows it beside the grid: the icon, the name (Morpheus), its lines (the player's text), n/255 */
        private fun macPreview(c: Canvas, m: MacrosModel.Mac, r: RectF) {
            UiKit.slot(c, icon(m.icon), r.left + 6f, r.top + 6f, 84f)
            val np = UiKit.titlePaint(40f, Theme.gold)
            c.drawText(nameFit(m.name, np, r.width() - 160f), r.left + 112f, r.top + 48f - (np.ascent() + np.descent()) / 2f, np)
            if (m.locked) lock(c, r.right - 24f, r.top + 40f, 40f)                                 // s14 D6: view only
            val lp = UiKit.bodyPaint(24f, Theme.text, shadow = false)
            val maxLines = ((r.height() - 172f) / 34f).toInt().coerceAtLeast(1)
            var y = r.top + 132f
            for ((i, ln) in m.lines.withIndex()) {
                if (i >= maxLines) { c.drawText("…", r.left + 6f, y, lp); break }
                c.drawText(nameFit(ln, lp, r.width() - 12f), r.left + 6f, y, lp)
                y += 34f
            }
            val cp = UiKit.numberPaint(22f, Theme.textDim).apply { textAlign = Paint.Align.RIGHT }
            c.drawText(MacrosModel.counter(m), r.right, r.bottom, cp)
        }

        // ---- s14 chips (s15: the theme's colours): a raised plate with the bronze hairline, an icon and a word; lit = the gold box and
        // gold words (the menus' rule), warn = amber, bad = the app's red (Theme.unusable)
        private fun chip(c: Canvas, r: RectF, label: String?, iconId: Int? = null, lit: Boolean = false, warn: Boolean = false,
                         bad: Boolean = false, dim: Boolean = false, size: Float = 26f, tr: Boolean = true, small: String? = null) {
            pFill.color = Theme.raised; c.drawRoundRect(r, 12f, 12f, pFill)
            pLine.strokeWidth = if (bad || warn) 3f else 2f
            pLine.color = when { bad -> Theme.unusable; warn -> Theme.warn; else -> Theme.bronzeDim }
            c.drawRoundRect(r, 12f, 12f, pLine)
            var x = r.left + 14f
            if (iconId != null) {
                val sz = (r.height() - 16f).coerceAtMost(56f)
                UiKit.slot(c, icon(iconId), x, r.centerY() - sz / 2f, sz)
                x += sz + 12f
            }
            if (small != null) {
                val sp = UiKit.bodyPaint(20f, Theme.textDim, shadow = false)
                c.drawText(Tr.t(small), x, r.top + 24f, sp)
            }
            if (label != null) {
                val p = UiKit.bodyPaint(size, when { dim -> Theme.dim; lit -> Theme.gold; else -> Theme.text })
                val text = if (tr) Tr.t(label) else label
                val y = if (small != null) r.bottom - 12f else r.centerY() - (p.ascent() + p.descent()) / 2f
                c.drawText(nameFit(text, p, r.right - 10f - x), x, y, p)
            }
            if (lit) UiKit.focus(c, r)
        }

        /** one condition as a small pill (the theme's raised ground, the bronze hairline): its word (or the game's own text, kept as
         *  written), struck through in the app's red for its "no" form; the modifiers carry the player's own button glyph */
        private fun condPill(c: Canvas, x: Float, cy: Float, k: String, h: Float = 40f): Float {
            val word = MacrosModel.condWord(k)
            val text = word?.let { Tr.t(it) } ?: k.removePrefix("~")
            val p = UiKit.bodyPaint(20f, Theme.text, shadow = false)
            val glyph = MacrosModel.condGlyph(k)
            val gw = if (glyph != null) h - 6f else 0f
            val w = p.measureText(text) + 22f + gw
            val r = RectF(x, cy - h / 2f, x + w, cy + h / 2f)
            pFill.color = Theme.raised; pFill.alpha = 153; c.drawRoundRect(r, h / 2f, h / 2f, pFill); pFill.alpha = 255
            pLine.strokeWidth = 1.5f; pLine.color = Theme.bronzeDim; c.drawRoundRect(r, h / 2f, h / 2f, pLine)
            if (glyph != null) UiKit.pad(c, Glyphs.translate("rev_$glyph", CastSettings.buttonLayout), x + 6f + gw / 2f, cy, gw - 4f)
            val tx = x + 11f + gw
            c.drawText(text, tx, cy - (p.ascent() + p.descent()) / 2f, p)
            if (MacrosModel.condStruck(k)) {
                pLine.strokeWidth = 2.5f; pLine.color = Theme.unusable
                c.drawLine(tx - 2f, cy, tx + p.measureText(text) + 2f, cy, pLine)
            }
            return r.right + 8f
        }

        private fun unitText(u: String?): String {
            val w = MacrosModel.unitWord(u) ?: return u ?: ""                 // a typed name: the player's text
            val n = MacrosModel.partyNo(u)
            return if (n != null) Tr.t(w) + " $n" else Tr.t(w)
        }

        /** a strip's cases: "on Focus" "if Enemy, Alive" "else Me": the words small, the units and conditions as pills */
        private fun casesInline(c: Canvas, x0: Float, cy: Float, maxX: Float, cases: List<MacrosModel.Case>): Float {
            var x = x0
            val sp = UiKit.bodyPaint(20f, Theme.textDim, shadow = false)
            for ((i, cs) in cases.withIndex()) {
                if (x > maxX - 60f) { c.drawText("…", x, cy + 6f, sp); return x + 20f }
                if (i > 0) { val w = Tr.t("else"); c.drawText(w, x, cy + 6f, sp); x += sp.measureText(w) + 8f }
                if (cs.unit != null) {
                    val w = Tr.t("on"); c.drawText(w, x, cy + 6f, sp); x += sp.measureText(w) + 6f
                    val up = UiKit.bodyPaint(20f, Theme.gold, shadow = false)
                    val t = unitText(cs.unit)
                    c.drawText(t, x, cy + 7f, up); x += up.measureText(t) + 10f
                }
                if (cs.conds.isNotEmpty()) {
                    val w = Tr.t("if"); c.drawText(w, x, cy + 6f, sp); x += sp.measureText(w) + 6f
                    for (k in cs.conds) { if (x > maxX - 40f) break; x = condPill(c, x, cy, k, 34f) }
                }
                cs.arg?.let { a ->
                    UiKit.slot(c, icon(a.icon), x, cy - 16f, 32f); x += 38f
                    val np = UiKit.bodyPaint(20f, Theme.text, shadow = false)
                    val t = nameFit(a.name, np, 200f); c.drawText(t, x, cy + 7f, np); x += np.measureText(t) + 10f
                }
                if (cs.unit == null && cs.conds.isEmpty() && cases.size > 1 && cs.arg == null) {
                    val up = UiKit.bodyPaint(20f, Theme.gold, shadow = false)
                    val t = Tr.t("Target"); c.drawText(t, x, cy + 7f, up); x += up.measureText(t) + 10f
                }
                x += 6f
            }
            return x
        }

        /** D3: one line as a strip of icons and short tags, its code small under it (20 px) */
        private fun strip(c: Canvas, r: RectF, st: MacrosModel.Strip, code: String, locked: Boolean, reserve: Float = 0f) {
            val cy = r.top + 28f
            if (st.kind == "text" || st.kind == "lock" || st.kind == "empty") {
                if (locked) lock(c, r.left + 26f, r.centerY(), 32f, 200)
                val p = UiKit.bodyPaint(24f, if (locked) Theme.textDim else Color.WHITE, shadow = false)
                c.drawText(nameFit(code, p, r.width() - 70f - reserve), r.left + 54f, r.centerY() - (p.ascent() + p.descent()) / 2f, p)
                return
            }
            var x = r.left + 8f
            if (st.seq.isNotEmpty()) {
                for (e in st.seq.take(4)) { UiKit.slot(c, icon(e.icon), x, r.top + 6f, 44f); x += 50f }
            } else {
                // a command with no picture of its own (Start attack, Stop casting ...): no slot (its word leads)
                val pic = st.look?.icon ?: (if (st.cmd == "cast" || st.cmd == "use" || st.cmd == "tip") 0 else null)
                if (pic != null) UiKit.slot(c, icon(pic), x, r.top + 6f, 44f)
                x += 54f
            }
            val wp = UiKit.bodyPaint(20f, Theme.gold, shadow = false)
            val cw = st.cmdName ?: Tr.t(MacrosModel.cmdWord(st.cmd))          // a pet order: the game's own word
            c.drawText(cw, x, cy + 7f, wp); x += wp.measureText(cw) + 10f
            st.look?.name?.takeIf { it.isNotEmpty() && st.seq.isEmpty() }?.let { n ->
                val np = UiKit.bodyPaint(24f, Color.WHITE, shadow = false)
                val t = nameFit((if (st.bang) "!" else "") + n, np, 300f)          // the game's name of it: never Tr.t
                c.drawText(t, x, cy + 8f, np); x += np.measureText(t) + 8f
                st.sub?.let { sub -> val sp = UiKit.bodyPaint(20f, Theme.textDim, shadow = false); c.drawText(sub, x, cy + 8f, sp); x += sp.measureText(sub) + 10f }
            }
            if (st.seq.isNotEmpty()) {
                val np = UiKit.bodyPaint(24f, Color.WHITE, shadow = false)
                val t = nameFit(st.seq.joinToString(", ") { it.name }, np, 320f); c.drawText(t, x, cy + 8f, np); x += np.measureText(t) + 10f
            }
            val warnW = MacrosModel.warnWord(st.warn)
            val maxX = r.right - reserve - (if (warnW != null) 200f else 16f)
            casesInline(c, x + 6f, cy, maxX, st.cases.takeIf { it.size > 1 || it.firstOrNull()?.let { f -> f.unit != null || f.conds.isNotEmpty() } == true } ?: emptyList())
            if (warnW != null) {
                val ap = UiKit.bodyPaint(20f, Theme.warn, shadow = false).apply { textAlign = Paint.Align.RIGHT }
                c.drawText(nameFit(Tr.t(warnW), ap, 190f), r.right - 12f - reserve, cy + 7f, ap)
            }
            val code0 = UiKit.bodyPaint(20f, Theme.textDim, shadow = false)
            c.drawText(nameFit(code, code0, r.width() - 72f - reserve), r.left + 62f, r.bottom - 8f, code0)
        }

        // ---- the editor: the name, the icon, the strips (or an empty macro's preset tiles), then + Line and More (s15)
        private fun editor(c: Canvas, s0: State, r: RectF) {
            val m = s0.mac ?: return
            // ONE gold cursor: while a pop-out is open the cursor is in it, the page under it shows none
            val s = if (s0.pop != null) s0.copy(ef = null) else s0
            val insAt = MacrosModel.insertUnder(s0.pop)
            UiKit.pane(c, r)
            val left = r.left + PAD; val right = r.right - PAD
            // the icon chip and the name row
            val iconBox = RectF(left, r.top + 16f, left + 104f, r.top + 120f)
            UiKit.slot(c, icon(m.icon), iconBox.left + 10f, iconBox.top + 10f, 84f)
            if (m.icon == Icons.QUESTION_MARK || m.icon == 0) {
                val ap = UiKit.bodyPaint(20f, Theme.textDim).apply { textAlign = Paint.Align.CENTER }
                c.drawText(Tr.t("Auto"), iconBox.centerX(), iconBox.bottom + 20f, ap)
            }
            if (s.ef == "icon") UiKit.focus(c, iconBox)
            hit(iconBox, MacrosModel.pickCells(s, 1, act = true))
            chipAt["icon"] = iconBox
            val nameBox = RectF(left + 120f, r.top + 16f, right, r.top + 120f)
            UiKit.inputBox(c, nameBox)
            val np = UiKit.bodyPaint(30f, Theme.gold)                        // the player's text in the field: Friz, never Tr.t
            c.drawText(nameFit(m.name, np, nameBox.width() - 200f), nameBox.left + 24f, nameBox.centerY() - (np.ascent() + np.descent()) / 2f, np)
            val cp = UiKit.numberPaint(22f, Theme.textDim).apply { textAlign = Paint.Align.RIGHT }
            c.drawText(MacrosModel.counter(m), nameBox.right - 24f, nameBox.centerY() - (cp.ascent() + cp.descent()) / 2f, cp)
            if (s.ef == "name") UiKit.focus(c, nameBox)
            hit(nameBox, MacrosModel.pickCells(s, 0, act = true))
            val top = nameBox.bottom + 30f
            val bandTop = r.bottom - BAND_H
            if (m.lines.isEmpty() && s.tiles.isNotEmpty()) {
                // D2: an empty macro offers the presets right here (up to 8 tiles, four a row)
                tiles(c, s, RectF(left, top, right, bandTop - 16f), 2, s.ef)
            } else {
                val lineH = 74f
                val visible = ((bandTop - 24f - top - 16f) / lineH).toInt().coerceAtLeast(1)
                val n = minOf(m.lines.size, MacrosModel.LINE_ROWS)
                // (s14 review) a Move scrolls with the line being moved (past the 7 lines on show it went out of sight)
                val focusIdx = ((if (s.view == "move") s.moveAt else null) ?: s.ef?.takeIf { it.startsWith("l") }?.drop(1)?.toIntOrNull()
                    ?: s.moveAt ?: 1) - 1
                val first = if (focusIdx >= visible) focusIdx - visible + 1 else 0
                val box = RectF(left, top, right, top + visible * lineH + 16f)
                UiKit.module(c, box)                                             // the lines' well: the ring edge (a big container)
                for (i in first until minOf(n, first + visible)) {
                    val y = top + 8f + (i - first) * lineH
                    val lr = RectF(left + 12f, y + 2f, right - 12f, y + lineH - 2f)
                    val st = s.strips.getOrNull(i) ?: MacrosModel.Strip("text", null, null, null, false, null, emptyList(), null, null, emptyList(), null)
                    val moving = s.view == "move" && s.moveAt == i + 1
                    if (moving) { pFill.color = Theme.selected; c.drawRoundRect(lr, 8f, 8f, pFill) }
                    val glow = s.ef == "l${i + 1}" && s.view != "move"
                    val asked = s.view == "edit" && s.remLine == i + 1          // s16: the line X asks to remove
                    val moveHint = glow && st.kind != "lock" && !asked
                    val xHint = moveHint && s.xRemove                            // s16: X Remove, left of Y Move (never in a fight)
                    val draft = s.draftLine == i + 1 && s.view != "move"
                    if (asked) { pFill.color = Theme.selected; c.drawRoundRect(lr, 8f, 8f, pFill) }
                    if (insAt == i + 1) {                                        // s16: More's Insert below: under this line
                        pFill.color = Theme.selected; c.drawRoundRect(lr, 8f, 8f, pFill)
                        pFill.color = Theme.gold; c.drawRect(lr.left + 8f, lr.bottom - 1f, lr.right - 8f, lr.bottom + 3f, pFill)
                    }
                    strip(c, lr, st, m.lines.getOrElse(i) { "" }, (i + 1) in m.lock,
                        reserve = (if (moveHint) 180f else 0f) + (if (xHint) 210f else 0f) + (if (draft) 70f else 0f))
                    if (moving) UiKit.moveArrowsUpDown(c, lr, up = i > 0, down = i < n - 1)
                    hit(lr, MacrosModel.pickCells(s, i + 2, act = true))
                    var dx = lr.right - 12f
                    if (glow) {
                        UiKit.focus(c, lr)
                        // the Y glyph's Move (UiKit.hints: the kit's glyph and word), its own hit over the line's
                        if (moveHint) {
                            val hx = UiKit.hints(c, lr.right - 14f, lr.centerY(), yGlyph() to "Move")
                            hit(RectF(hx - 10f, lr.top, lr.right, lr.bottom), MacrosModel.cell(s, "y"))
                            dx = hx - 14f
                            // s16 (the user's "X = Remove"): the X glyph's Remove, its tap the row's `x` (the same ask)
                            if (xHint) {
                                val rx = UiKit.hints(c, hx - 24f, lr.centerY(), UiKit.xGlyph() to "Remove")
                                hit(RectF(rx - 10f, lr.top, hx - 10f, lr.bottom), MacrosModel.cell(s, "x"))
                                dx = rx - 14f
                            }
                        }
                    }
                    if (draft) {
                        val dp = UiKit.bodyPaint(20f, Theme.warn, shadow = false).apply { textAlign = Paint.Align.RIGHT }
                        c.drawText(Tr.t("Draft"), dx, lr.bottom - 8f, dp)
                    }
                }
                if (m.lines.isEmpty()) say(c, box, "Empty")
            }
            // the band: + Line (A on it) and More (Y); Delete asks with Cancel / Delete, s16 a line's X with Cancel / Remove
            val (a, b) = band(c, r)
            if (s.del) { askPair(c, s, a, b, "Delete"); return }
            if (s.remLine != null && s.view == "edit") { askPair(c, s, a, b, "Remove"); return }
            val addF = s.ef == "add"
            button(c, a, if (s.draftLine == null && s.draftAt != null) "+ Line (Draft)" else "+ Line", if (s.held && addF) UiKit.okGlyph() else null)
            if (addF) UiKit.focus(c, a)
            hit(a, MacrosModel.pickCells(s, MacrosModel.editCell("add"), act = true))
            chipAt["add"] = a
            moreButton(c, s, b, s.ef == "more", s.ym, MacrosModel.pickCells(s, MacrosModel.editCell("more"), act = true))
        }

        /** the preset tiles: four a row, each a module (the ring edge: the room's tiles); [ef] the glow's key ("t3"), or the presets view's */
        private fun tiles(c: Canvas, s: State, area: RectF, sBase: Int, ef: String?) {
            val per = 4
            val gap = 16f
            val tw = (area.width() - (per - 1) * gap) / per
            val th = minOf(250f, (area.height() - gap) / 2f)
            for ((k, t) in s.tiles.withIndex()) {
                val x = area.left + (k % per) * (tw + gap); val y = area.top + (k / per) * (th + gap)
                val tr = RectF(x, y, x + tw, y + th)
                UiKit.module(c, tr)
                val lp = UiKit.bodyPaint(26f, Theme.gold).apply { textAlign = Paint.Align.CENTER }
                c.drawText(nameFit(Tr.t(MacrosModel.presetWord(t.key)), lp, tw - 28f), tr.centerX(), tr.top + 50f, lp)
                var ix = tr.centerX() - (t.icons.size.coerceAtMost(3) * 62f) / 2f
                for (ic in t.icons.take(3)) { UiKit.slot(c, icon(ic), ix + 4f, tr.top + 74f, 54f); ix += 62f }
                val sp = UiKit.bodyPaint(20f, Theme.textDim, shadow = false)
                var ly = tr.top + 160f
                for (ln in t.text.split("\n").take(3)) {
                    if (ly > tr.bottom - 12f) break
                    c.drawText(nameFit(ln, sp, tw - 32f), tr.left + 16f, ly, sp); ly += 26f
                }
                val key = "t${k + 1}"
                if (ef == key || (ef == null && s.presetCur == k + 1)) UiKit.focus(c, tr)
                hit(tr, MacrosModel.pickCells(s, sBase + k, act = true))
            }
        }

        // ---- the presets view (More > Presets on a macro with lines): the tiles; a preset's Done asks before it replaces (D2)
        private fun presets(c: Canvas, s: State, r: RectF) {
            UiKit.pane(c, r)
            val hp = UiKit.titlePaint(30f, Theme.gold)
            c.drawText(Tr.t("Presets"), r.left + PAD, r.top + 44f, hp)
            tiles(c, s, RectF(r.left + PAD, r.top + 70f, r.right - PAD, r.bottom - 20f), 0, null)
        }

        // ---- a preset's preview: its lines as strips, its picks, Done (Select); on a macro with lines Done asks: Cancel / Replace
        private fun preview(c: Canvas, s: State, r: RectF) {
            val p = s.preset?.let { p0 -> if (s.pop != null) p0.copy(cur = null) else p0 } ?: return       // ONE cursor (see [editor])
            UiKit.pane(c, r)
            val left = r.left + PAD; val right = r.right - PAD
            val hp = UiKit.titlePaint(30f, Theme.gold)
            c.drawText(Tr.t(MacrosModel.presetWord(p.key)), left, r.top + 44f, hp)
            val cp = UiKit.numberPaint(22f, if (MacrosModel.tooLong(p.len)) Theme.unusable else Theme.textDim).apply { textAlign = Paint.Align.RIGHT }
            c.drawText("${p.len}/${MacrosModel.BODY_MAX}", right, r.top + 44f, cp)
            val top = r.top + 70f
            val box = RectF(left, top, right, top + p.strips.size.coerceAtLeast(1) * 74f + 16f)
            UiKit.module(c, box)
            val lines = p.text.split("\n")
            for ((i, st) in p.strips.withIndex()) {
                val y = top + 8f + i * 74f
                strip(c, RectF(left + 12f, y + 2f, right - 12f, y + 72f), st, lines.getOrElse(i) { "" }, false)
            }
            // the picks: one chip each (A opens the Spell list for it)
            val py = box.bottom + 30f
            val pw = 340f
            for ((k, pk) in p.picks.withIndex()) {
                val pr = RectF(left + k * (pw + 16f), py, left + k * (pw + 16f) + pw, py + 90f)
                val key = "p${k + 1}"
                chip(c, pr, pk.first?.name?.takeIf { it.isNotEmpty() } ?: Tr.t("Pick"), pk.first?.icon ?: 0, lit = p.cur == key,
                     bad = p.hard == "pick" && pk.first == null && pk.second != "harm3", tr = false)
                chipAt[key] = pr
                hit(pr, MacrosModel.pickCells(s, k, act = true))
            }
            val (a, b) = band(c, r)
            if (p.armed) { askPair(c, s, a, b, "Replace"); return }
            button(c, b, "Done", if (s.held) selectGlyph() else null, if (p.hard != null || s.combat) UiKit.Button.DISABLED else UiKit.Button.UP)
            if (p.cur == "done") UiKit.focus(c, b)
            hit(b, MacrosModel.pickCells(s, 3, act = true))
            chipAt["done"] = b
        }

        // ---- THE BUILDER (D1): Line n, the line's text, its action row, its cases, + Case; Done (Select) and More (Aa Text / Remove)
        private fun builder(c: Canvas, s: State, r: RectF) {
            val b = s.builder?.let { b0 -> if (b0.pop != null) b0.copy(cur = null) else b0 } ?: return     // ONE cursor (see [editor])
            UiKit.pane(c, r)
            val left = r.left + PAD; val right = r.right - PAD
            // the heading: Line n (or New line) in Morpheus, n/255 (its word is the header's one status)
            val tp = UiKit.titlePaint(30f, Theme.gold)
            c.drawText(if (b.line == null) Tr.t("New line") else Tr.t("Line") + " ${b.line}", left, r.top + 44f, tp)
            val cp = UiKit.numberPaint(22f, if (MacrosModel.tooLong(b.len)) Theme.unusable else Theme.textDim).apply { textAlign = Paint.Align.RIGHT }
            c.drawText("${b.len}/${MacrosModel.BODY_MAX}", right, r.top + 44f, cp)
            // the composed text in the game's edit box (red past 255): the player's own text, never Tr.t
            val wellR = RectF(left, r.top + 62f, right, r.top + 122f)
            UiKit.inputBox(c, wellR)
            val wp = UiKit.bodyPaint(26f, if (MacrosModel.tooLong(b.len)) Theme.unusable else Color.WHITE, shadow = false)
            c.drawText(nameFit(b.text, wp, wellR.width() - 48f), wellR.left + 24f, wellR.centerY() - (wp.ascent() + wp.descent()) / 2f, wp)
            // the chips, row by row as the add-on lays them
            var y = wellR.bottom + 18f
            val bad = if (s.combat) null else b.hardChip
            val warn = !s.combat && b.warns.isNotEmpty()
            for ((ri, row) in b.rows.withIndex()) {
                val isCase = row.firstOrNull()?.matches(Regex("^c\\d[ufa]$")) == true
                if (row.firstOrNull() == "done" || row.firstOrNull() == "more" || row.firstOrNull() == "text") break    // the band's
                val h = when { ri == 0 -> 96f; isCase -> 84f; else -> 70f }
                if (isCase) {
                    val n = row.first().substring(1, 2).toInt()
                    val card = RectF(left, y, right, y + h)
                    UiKit.module(c, card)                                        // a case card: the ring edge (a big container)
                    val tabR = RectF(left + 12f, y + 12f, left + 88f, y + h - 12f)
                    val tl = UiKit.bodyPaint(24f, Theme.gold).apply { textAlign = Paint.Align.CENTER }
                    c.drawText(if (n == 1) "1" else Tr.t("else"), tabR.centerX(), tabR.centerY() - (tl.ascent() + tl.descent()) / 2f, tl)
                    var x = tabR.right + 12f
                    val cs = b.cases.getOrNull(n - 1)
                    val focusedCase = b.cur != null && Regex("^c${n}[ufa]$").matches(b.cur)
                    val removable = b.cases.size > 1 || cs?.unit != null || cs?.conds?.isNotEmpty() == true
                    val maxR = right - CASE_HINT_W
                    for (key in row) {
                        val part = key.last()
                        val w = when (part) { 'u' -> 250f; 'f' -> 400f; else -> 250f }.coerceAtMost(maxR - x)
                        val cr = RectF(x, y + 12f, x + w, y + h - 12f)
                        when (part) {
                            'u' -> chip(c, cr, cs?.unit?.let { unitText(it) } ?: Tr.t("Target"), lit = b.cur == key, small = "On", tr = false)
                            'f' -> {
                                chip(c, cr, if (cs?.conds.isNullOrEmpty()) "Always" else null, lit = b.cur == key, small = "If")
                                var px = cr.left + 14f
                                for (k in cs?.conds.orEmpty()) { if (px > cr.right - 60f) break; px = condPill(c, px, cr.centerY() + 10f, k, 30f) }
                            }
                            else -> chip(c, cr, cs?.arg?.name?.takeIf { it.isNotEmpty() } ?: Tr.t("Pick"), cs?.arg?.icon ?: 0,
                                lit = b.cur == key, bad = bad == key, tr = false)
                        }
                        chipAt[key] = cr
                        MacrosModel.chipCell(b, key)?.let { sk -> hit(cr, MacrosModel.pickCells(s, sk, act = true)) }
                        x = cr.right + 12f
                    }
                    // Y takes the case away: the hint on the FOCUSED case only (the clutter rule: select, then ONE action), its own hit
                    if (focusedCase && removable && b.pop == null) {
                        val hx = UiKit.hints(c, right - 16f, card.centerY(), yGlyph() to "Remove")
                        hit(RectF(hx - 10f, card.top, right, card.bottom), MacrosModel.cell(s, "y"))
                    }
                } else {
                    var x = left
                    // (s14 review) the spell chip gives way so the row fits: Cast + spell + Rank + Keep on squeezed Keep on's word under
                    // its own check box (room_build_rank)
                    fun baseW(key: String) = when (key) { "cmd" -> 260f; "arg" -> 460f; "rank", "reset" -> 220f; "keep" -> 200f; "add" -> 260f; else -> 200f }
                    val others = row.filter { it != "arg" }.sumOf { baseW(it).toDouble() }.toFloat() + 14f * (row.size - 1)
                    val argW = (right - left - others).coerceIn(220f, 460f)
                    for (key in row) {
                        val w = if (key == "arg") argW else baseW(key)
                        val cr = RectF(x, y, (x + w).coerceAtMost(right), y + h - 10f)
                        when (key) {
                            "cmd" -> chip(c, cr, b.cmdName ?: MacrosModel.cmdWord(b.cmd), lit = b.cur == key, tr = b.cmdName == null)
                            "arg" -> argChip(c, cr, b, lit = b.cur == key, bad = bad == key, warn = warn)
                            "rank" -> chip(c, cr, b.rank?.let { (at, m) -> if (at == 0) Tr.t("Top") else "$at / $m" } ?: Tr.t("Top"),
                                lit = b.cur == key, small = "Rank", tr = false)
                            "reset" -> chip(c, cr, b.reset?.split("/")?.joinToString(" ") { o -> MacrosModel.resetWord(o)?.let { Tr.t(it) } ?: "$o s" }
                                ?: Tr.t("None"), lit = b.cur == key, small = "Reset", tr = false)
                            "keep" -> {
                                // the word stops short of the check box (a long es / fr word ends "…", never under the box)
                                chip(c, cr, null, lit = b.cur == key)
                                val kp = UiKit.bodyPaint(26f, if (b.cur == key) Theme.gold else Theme.text)
                                c.drawText(nameFit(Tr.t("Keep on"), kp, cr.width() - 14f - 64f), cr.left + 14f,
                                    cr.centerY() - (kp.ascent() + kp.descent()) / 2f, kp)
                                UiKit.check(c, cr.right - 34f, cr.centerY(), 36f, b.bang)
                            }
                            "add" -> chip(c, cr, "+ Case", lit = b.cur == key)
                        }
                        chipAt[key] = cr
                        MacrosModel.chipCell(b, key)?.let { sk -> hit(cr, MacrosModel.pickCells(s, sk, act = true)) }
                        x = cr.right + 14f
                    }
                }
                y += h + 10f
            }
            // the band: Done (Select) and More (Y); a stored line's Remove asks with Cancel / Remove
            val (a, bb) = band(c, r)
            if (b.remArmed) { askPair(c, s, a, bb, "Remove"); return }
            button(c, a, "Done", if (s.held) selectGlyph() else null, if (b.hard != null || s.combat) UiKit.Button.DISABLED else UiKit.Button.UP)
            if (b.cur == "done") UiKit.focus(c, a)
            hit(a, MacrosModel.doneCells(s))
            chipAt["done"] = a
            moreButton(c, s, bb, b.cur == "more", b.ym, MacrosModel.chipCell(b, "more")?.let { MacrosModel.pickCells(s, it, act = true) })
        }

        /** the line's spell / item chip: its picture and name; a sequence's pictures; a unit's word; equipslot's slot and item */
        private fun argChip(c: Canvas, r: RectF, b: MacrosModel.Builder, lit: Boolean, bad: Boolean, warn: Boolean) {
            if (b.seq.isNotEmpty()) {
                chip(c, r, null, lit = lit, bad = bad)
                var x = r.left + 12f
                for (e in b.seq.take(5)) { UiKit.slot(c, icon(e.icon), x, r.top + 14f, r.height() - 28f); x += r.height() - 18f }
                return
            }
            val look = b.look
            if (b.cmd == "target" || b.cmd == "focus" || b.cmd == "assist" || b.cmd == "startattack" || b.cmd == "petattack") {
                chip(c, r, look?.name?.let { unitText(it) } ?: Tr.t("Target"), lit = lit, bad = bad, small = "On", tr = false)
                return
            }
            val name = look?.name?.takeIf { it.isNotEmpty() }?.let { (if (b.bang) "!" else "") + it }
            chip(c, r, name ?: Tr.t("Pick"), look?.icon ?: 0, lit = lit, bad = bad, warn = warn && name != null, tr = false,
                 small = b.slot?.let { Tr.t("Slot") + " $it" })
        }

        // ---- THE POP-OUTS: each slides out of its chip (UiKit.slideOut); a finger outside it closes it (B); A Choose / B Back under it
        private fun popOut(c: Canvas, s: State, p: MacrosModel.Pop, r: RectF) {
            val key = "${p.kind}:${p.case}:${p.forSeq}:${p.forPreset}"
            val now = SystemClock.uptimeMillis()
            if (key != popKey) { popKey = key; popAt = now }
            val t = if (dryRun) SLIDE_MS else (now - popAt).toFloat()
            val anchorKey = when (p.kind) {
                "cmd" -> "cmd"; "rank" -> "rank"; "reset" -> "reset"; "case" -> "add"; "seq" -> "arg"; "icon" -> "icon"; "more" -> "more"
                "arg" -> if (p.forPreset) (s.preset?.cur ?: "p1") else if (p.forSeq) "arg" else if ((p.case ?: 1) > 1) "c${p.case}a" else "arg"
                "on" -> if ((p.case ?: 1) >= 1 && s.builder?.cur?.endsWith("u") == true) "c${p.case}u" else "arg"
                "if" -> "c${p.case ?: 1}f"
                else -> "cmd"
            }
            val anchor = chipAt[anchorKey] ?: chipAt[s.builder?.cur ?: ""] ?: RectF(r.centerX() - 40f, r.centerY() - 40f, r.centerX() + 40f, r.centerY() + 40f)
            val cellW: Float; val cellH: Float
            when (p.kind) {
                "arg" -> { cellW = 116f; cellH = 124f }
                "icon" -> { cellW = 104f; cellH = 104f }
                "cmd" -> { cellW = 270f; cellH = 64f }
                "if" -> { cellW = 250f; cellH = 64f }
                "on", "reset" -> { cellW = 290f; cellH = 64f }
                "more" -> { cellW = 440f; cellH = 92f }
                else -> { cellW = 440f; cellH = 64f }
            }
            val rowsN = ((p.rows.size + p.cols - 1) / p.cols).coerceAtLeast(1)
            val head = if (p.kind == "arg") 166f else 20f
            // the panel's foot: the book's paging, then the hint row (A Choose / B Back, inside the panel as Calendar's and Group Finder's
            // lists have it: under a panel it stood on the chips the cover only dims) while the room holds the pad
            val pagesH = if (p.pages > 1) 96f else 0f
            val hintH = if (s.held) 64f else 0f
            val foot = maxOf(16f, pagesH + hintH)
            val pw = p.cols * cellW + 40f
            val ph = head + rowsN * cellH + foot + 10f
            var px = anchor.left; var py = anchor.bottom + 8f
            if (px + pw > r.right - 8f) px = r.right - 8f - pw
            if (px < r.left + 8f) px = r.left + 8f
            if (py + ph > r.bottom - 8f) py = (anchor.top - 8f - ph).coerceAtLeast(r.top + 8f)
            val to = RectF(px, py, px + pw, py + ph)
            // the ground around closes it (B), as the game's own menus do
            UiKit.cover(c, r, 0.35f)
            hit(RectF(r), MacrosModel.cell(s, "b"))
            var tip: JSONObject? = null
            UiKit.slideOut(c, anchor, to, t) { cv, box -> tip = popCells(cv, s, p, box, head, cellW, cellH, hintH) }
            if (!UiKit.sliding(t)) {
                // (s14 review) the spell's words OUTSIDE the slide's clip, once it is out: the game's tooltip beside the panel where there
                // is room, else under / over it (room_build_arg: drawn inside the clip it showed as a sliver over the Spells tab)
                if (p.kind == "arg") tip?.let { row ->
                    val id = row.optInt("id", 0)
                    val desc = if (id > 0) spellTip(id) else null
                    // clear of the action band at the pane's foot too (over the panel when under it would reach the band)
                    if (!desc.isNullOrEmpty()) tooltip(c, row.optString("n", ""), desc, to, RectF(r.left, r.top, r.right, r.bottom - BAND_H))
                }
                if (s.held) UiKit.hints(c, to.right - 24f, to.bottom - 10f - hintH / 2f, UiKit.okGlyph() to "Choose", UiKit.backGlyph() to "Back")
            }
            if (UiKit.sliding(t)) postInvalidateOnAnimation()
        }

        /** the Spell / Item pop-out's icon tabs (the Controller room's chooser): Spells, General (the class), Pet (the pet's own),
         *  Items, Slots; a tab with no picture yet (no spells document) says its word */
        private fun tabIcon(t: String): android.graphics.Bitmap? = when (t) {
            "sp" -> UiKit.tex("ricon_spellbook")
            "gen" -> book()?.lines?.firstOrNull { it.index == 1 }?.let { Icons.get(it.icon) }
            "pet" -> book()?.petIcon?.takeIf { it > 0 }?.let { Icons.get(it) }
            "it" -> UiKit.tex("ricon_bags")
            "sl" -> UiKit.tex("ricon_character")
            else -> null
        }

        /** the pop-out's cells; returns the glowed row (the arg pop-out's tooltip is drawn by [popOut], outside the slide's clip) */
        private fun popCells(c: Canvas, s: State, p: MacrosModel.Pop, box: RectF, head: Float, cellW: Float, cellH: Float, hintH: Float): JSONObject? {
            hits += RectF(box) to emptyList()                                 // a finger on the panel's ground does nothing
            if (p.kind == "arg") {
                // the tabs (s0..s4): the game's small icon tabs, the gaps round the cursor's (or the open) one, the gold arrows beside it
                val open = p.tabs.indexOf(p.tab).coerceAtLeast(0)
                val curT = p.cur?.takeIf { it.startsWith("t") }?.drop(1)?.toIntOrNull()?.minus(1)
                val chosen = curT ?: open
                val tr = popGaps.row(p.tabs.size, box.left + 20f - UiKit.TAB_END_W, box.right - 20f + UiKit.TAB_END_W, box.top + 14f, 72f, 72f,
                    chosen.coerceIn(0, (p.tabs.size - 1).coerceAtLeast(0)), this)
                for ((i, tk) in p.tabs.withIndex()) {
                    val rr = tr.getOrNull(i) ?: continue
                    val pic = tabIcon(tk)
                    UiKit.smallTab(c, rr, pic, i == open, cursor = curT == i)
                    if (pic == null) {
                        val wp = UiKit.bodyPaint(20f, if (i == open) Theme.gold else Theme.text).apply { textAlign = Paint.Align.CENTER }
                        c.drawText(nameFit(Tr.t(MacrosModel.tabWord(tk)), wp, rr.width() - 8f), rr.centerX(), rr.centerY() - (wp.ascent() + wp.descent()) / 2f, wp)
                    }
                    hit(rr, MacrosModel.pickCells(s, i, act = true))
                }
                if (curT != null && curT in tr.indices) UiKit.moveArrows(c, tr[curT], left = curT > 0, right = curT < p.tabs.size - 1, words = false)
                val sr = RectF(box.left + 20f, box.top + 98f, box.right - 20f, box.top + 152f)
                UiKit.inputBox(c, sr)
                val qp = UiKit.bodyPaint(24f, if (p.query != null) Color.WHITE else Theme.textDim, shadow = false)
                c.drawText(p.query ?: Tr.t("Search"), sr.left + 54f, sr.centerY() - (qp.ascent() + qp.descent()) / 2f, qp)
                UiKit.pad(c, UiKit.okGlyph(), sr.left + 26f, sr.centerY(), 30f)
                if (p.cur == "q") UiKit.focus(c, sr)
                hit(sr, MacrosModel.pickCells(s, 5, act = true))
            }
            val gx = box.left + 20f; val gy = box.top + head
            var tipFor: JSONObject? = null
            for ((i, row) in p.rows.withIndex()) {
                val x = gx + (i % p.cols) * cellW; val y = gy + (i / p.cols) * cellH
                val cr = RectF(x + 4f, y + 4f, x + cellW - 4f, y + cellH - 4f)
                val lit = p.cur == "r${i + 1}"
                if (lit) tipFor = row
                popCell(c, p, row, cr, lit)
                hit(cr, MacrosModel.pickCells(s, p.base + i, act = true))
            }
            if (p.rows.isEmpty()) say(c, RectF(gx, gy - 20f, box.right, box.bottom), "Nothing to pick", x = gx + 4f)
            if (p.pages > 1) {
                val py = box.bottom - hintH - 54f
                UiKit.bookPaging(c, box.centerX(), py, p.page, p.pages, onPaper = false)
                if (p.page > 1) hit(RectF(box.centerX() - 206f, py - 55f, box.centerX() - 94f, py + 56f), MacrosModel.cell(s, "pp"))
                if (p.page < p.pages) hit(RectF(box.centerX() + 94f, py - 55f, box.centerX() + 206f, py + 56f), MacrosModel.cell(s, "pn"))
            }
            return tipFor
        }

        /** a multi-column pop-out's cell (s15): the menus' look without their gutter - the word gold when lit, the gold box round it */
        private fun wordCell(c: Canvas, cr: RectF, text: String, lit: Boolean, iconId: Int? = null, dim: Boolean = false, endRoom: Float = 0f) {
            var x = cr.left + 16f
            if (iconId != null) {
                val sz = cr.height() - 20f
                UiKit.slot(c, icon(iconId), x, cr.centerY() - sz / 2f, sz)
                x += sz + 12f
            }
            val p = UiKit.bodyPaint(26f, when { dim -> Theme.dim; lit -> Theme.gold; else -> Theme.text })
            c.drawText(nameFit(text, p, cr.right - 12f - endRoom - x), x, cr.centerY() - (p.ascent() + p.descent()) / 2f, p)
            if (lit) UiKit.focus(c, cr)
        }

        /** one pop-out cell, by kind: the icon grids keep their slots; a one-column list is the game's menu rows; the rest plain words */
        private fun popCell(c: Canvas, p: MacrosModel.Pop, row: JSONObject, cr: RectF, lit: Boolean) {
            when (p.kind) {
                "arg", "icon" -> {
                    val ic = row.optInt("i", 0)
                    val sz = (cr.width() - 30f).coerceAtMost(cr.height() - 40f)
                    val sr = RectF(cr.centerX() - sz / 2f, cr.top + 6f, cr.centerX() + sz / 2f, cr.top + 6f + sz)
                    UiKit.slot(c, icon(ic), sr.left, sr.top, sz)
                    if (row.optBoolean("on", false)) UiKit.checkedGlow(c, sr)
                    val np = UiKit.bodyPaint(20f, if (lit) Theme.gold else Theme.text, shadow = false).apply { textAlign = Paint.Align.CENTER }
                    val name = when {
                        row.optBoolean("auto", false) -> Tr.t("Auto")
                        row.has("sl") && row.isNull("n") -> Tr.t("Slot") + " " + row.optInt("sl")
                        else -> row.optString("n", "")                          // the game's names: never Tr.t
                    }
                    if (p.kind == "arg" || row.optBoolean("auto", false)) c.drawText(nameFit(name, np, cr.width() - 4f), cr.centerX(), cr.bottom - 6f, np)
                    if (lit) UiKit.focus(c, cr)
                }
                "cmd" -> wordCell(c, cr, row.optString("n").takeIf { it.isNotEmpty() } ?: Tr.t(MacrosModel.cmdWord(row.optString("k"))), lit,
                    row.optInt("i", 0).takeIf { it > 0 })
                "on" -> wordCell(c, cr, unitText(row.optString("u", "")), lit)
                "if" -> {
                    val k = row.optString("k"); val st = row.optInt("s", 0)
                    condPill(c, cr.left + 14f, cr.centerY(), k, 40f)               // the grid names the condition; Yes / No / Off say its state
                    val sp = UiKit.bodyPaint(20f, if (st == 0) Theme.dim else Theme.gold, shadow = false).apply { textAlign = Paint.Align.RIGHT }
                    c.drawText(Tr.t(when (st) { 1 -> "Yes"; 2 -> "No"; else -> "Off" }), cr.right - 14f, cr.centerY() - (sp.ascent() + sp.descent()) / 2f, sp)
                    if (lit) UiKit.focus(c, cr)
                }
                "reset" -> {
                    val o = row.optString("o")
                    wordCell(c, cr, MacrosModel.resetWord(o)?.let { Tr.t(it) } ?: "$o s", lit, endRoom = 50f)
                    UiKit.check(c, cr.right - 30f, cr.centerY(), 32f, row.optBoolean("on", false))
                }
                "rank" -> {
                    val r = row.optInt("r", 0)
                    UiKit.menuRow(c, cr, if (r == 0) "Top" else "$r / ${p.ranks}", lit, size = 28f, mark = if (row.optBoolean("on", false)) "icon_check" else null)
                }
                "case" -> UiKit.menuRow(c, cr, MacrosModel.caseWord(row.optString("o")), lit, size = 28f)
                "more" -> UiKit.menuRow(c, cr, MacrosModel.moreWord(row.optString("o"), row.optBoolean("rv", false)), lit,
                    enabled = !row.optBoolean("off", false), size = 28f)
                "seq" -> if (row.optBoolean("add", false)) UiKit.menuRow(c, cr, "+ Spell", lit, size = 28f) else seqRow(c, cr, row, lit)
                else -> wordCell(c, cr, row.optString("n", ""), lit)
            }
        }

        /** a sequence's spell as the menu's row (its picture in the gutter, the game's name never through Tr.t, gold when lit) */
        private fun seqRow(c: Canvas, cr: RectF, row: JSONObject, lit: Boolean) {
            if (lit) UiKit.focus(c, RectF(cr.left + 6f, cr.top + 4f, cr.right - 6f, cr.bottom - 4f))
            val sz = cr.height() - 16f
            UiKit.slot(c, icon(row.optInt("i", 0)), cr.left + 48f - sz / 2f, cr.centerY() - sz / 2f, sz)
            val p = UiKit.bodyPaint(28f, if (lit) Theme.gold else Theme.text)
            c.drawText(nameFit(row.optString("n", ""), p, cr.right - 16f - (cr.left + 100f)), cr.left + 100f, cr.centerY() - 2f - (p.ascent() + p.descent()) / 2f, p)
        }

        /** a spell's description by the pop-out: THE GAME'S TOOLTIP (UiKit.tooltip, as Bags and Chat show one), beside [box] where
         *  [room] has space for it, else under it, else over it, never on the panel's cells unless nothing else fits */
        private fun tooltip(c: Canvas, name: String, desc: String, box: RectF, room: RectF) {
            val lines = listOf(UiKit.TipLine(desc, Theme.gold))
            fun tall(w: Float) = UiKit.tooltip(c, 0f, 0f, w, name, Color.WHITE, lines, alpha = 0).height()   // measured, nothing drawn
            val side = 400f
            var w = side
            var th = tall(w)
            val at: Pair<Float, Float> = when {
                box.right + 12f + side <= room.right - 8f ->
                    (box.right + 12f) to box.top.coerceAtMost(room.bottom - 8f - th).coerceAtLeast(room.top + 8f)
                box.left - 12f - side >= room.left + 8f ->
                    (box.left - 12f - side) to box.top.coerceAtMost(room.bottom - 8f - th).coerceAtLeast(room.top + 8f)
                else -> {
                    w = box.width().coerceAtMost(room.width() - 16f)
                    th = tall(w)
                    when {
                        box.bottom + 12f + th <= room.bottom - 8f -> box.left to (box.bottom + 12f)
                        box.top - 12f - th >= room.top + 8f -> box.left to (box.top - 12f - th)
                        else -> box.left to (room.bottom - 8f - th)                     // nothing fits: the room's foot
                    }
                }
            }
            UiKit.tooltip(c, at.first, at.second, w, name, Color.WHITE, lines)
        }

        /** the line over the keyboard (InteractView's): the field's word, what is typed, the caret */
        private fun typingLine(c: Canvas, r: RectF) {
            val kh = if (keyboard.height > 0) keyboard.height else keyboard.boardHeight()
            val box = RectF(r.left + 8f, r.bottom - kh - 86f, r.right - 8f, r.bottom - kh - 6f)
            UiKit.menu(c, box, solid = true)
            val lab = Tr.t(MacrosModel.kbdWord(state?.kbd)) + ":"
            val lp = UiKit.bodyPaint(26f, Theme.gold)
            c.drawText(lab, box.left + 22f, box.centerY() + 9f, lp)
            val x = box.left + 40f + lp.measureText(lab)
            val tp = UiKit.bodyPaint(28f, Color.WHITE)
            val maxW = box.right - 140f - x
            val drawn = if (tp.measureText(kbText) > maxW) "…" + kbText.takeLast(tp.breakText(kbText.reversed(), true, maxW - 24f, null)) else kbText
            c.drawText(drawn, x, box.centerY() + 10f, tp)                    // the player's text: never Tr.t
            val cp = UiKit.numberPaint(22f, Theme.textDim).apply { textAlign = Paint.Align.RIGHT }
            c.drawText("${kbText.length}/$kbMax", box.right - 20f, box.centerY() + 8f, cp)
            if ((SystemClock.uptimeMillis() / 500L) % 2L == 0L) {
                val cx = x + tp.measureText(drawn) + 3f
                pFill.color = Theme.gold
                c.drawRect(cx, box.top + 18f, cx + 3f, box.bottom - 18f, pFill)
            }
            if (!dryRun) postInvalidateDelayed(500L)
        }

        private var downAt: RectF? = null
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downAt = hits.lastOrNull { it.first.contains(e.x, e.y) }?.first; return true }
                MotionEvent.ACTION_UP -> {
                    val d = downAt; downAt = null
                    val h = hits.lastOrNull { it.first.contains(e.x, e.y) } ?: return true
                    if (d != null && d == h.first) h.second?.takeIf { it.isNotEmpty() }?.let { taps(it) }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> downAt = null
            }
            return true
        }
    }

    companion object {
        const val TAG = "WCMACROS"
        const val RETRY_MS = 1500L
        const val RETRY_MAX = 4
        private const val KB_REPEAT_FIRST_MS = 380L
        private const val KB_REPEAT_MS = 110L
        /** s15: the rooms' header (UiKit.header), Quests' action band, the panes' side padding, the grid's pane split */
        private const val HEADER = 62f
        private const val BAND_H = 112f
        private const val PAD = 20f
        private const val GRID_SPLIT = 720f
        /** the room a focused case keeps at its right end for its "Y Remove" (UiKit.hints: glyph and word) */
        private const val CASE_HINT_W = 190f
    }
}
