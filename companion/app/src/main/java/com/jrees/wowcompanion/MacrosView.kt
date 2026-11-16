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

    private inner class Pane(c: Context) : View(c) {
        private val hits = ArrayList<Pair<RectF, List<Pair<String, Int>>?>>()
        private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        /** s14: the open pop-out and when it opened (UiKit.slideOut's clock) */
        private var popKey: String? = null
        private var popAt = 0L
        /** s14: where each chip was drawn this frame (a pop-out slides out of its chip) */
        private val chipAt = HashMap<String, RectF>()

        override fun onDraw(c: Canvas) {
            hits.clear(); chipAt.clear()
            val w = width.toFloat(); val h = height.toFloat()
            val full = RectF(0f, 0f, w, h)
            val inner = UiKit.windowGround(c, full)
            val s = state
            UiKit.windowFrame(c, full, "Macros", Icons.get(Icons.QUESTION_MARK), closeB = if (bLeadsHome() && s?.held == true) 1 else 0)
            hit(UiKit.windowClose(full), MacrosModel.cell(s, "b"))
            when {
                s == null || !s.ready -> say(c, inner, "Loading")
                s.off -> lockScreen(c, inner, "Macros are off")
                else -> {
                    when (s.view) {
                        "edit", "move" -> editor(c, s, inner)
                        "build" -> builder(c, s, inner)
                        "pre" -> presets(c, s, inner)
                        "ps" -> preview(c, s, inner)
                        else -> grid(c, s, inner)
                    }
                    status(c, s, inner)
                    (s.builder?.pop ?: s.pop)?.let { popOut(c, s, it, inner) } ?: run { popKey = null }
                }
            }
            if (kbField != null) typingLine(c, full)
        }

        private fun hit(r: RectF, cells: List<Pair<String, Int>>?) { if (cells != null) hits += RectF(r) to cells }

        /** the game's macro text box: a dark well in the frame's bronze */
        private fun well(c: Canvas, r: RectF) {
            pFill.color = Color.argb(215, 6, 5, 4); c.drawRoundRect(r, 10f, 10f, pFill)
            pLine.color = Theme.bronzeDim; pLine.strokeWidth = 2f; c.drawRoundRect(r, 10f, 10f, pLine)
        }

        private fun say(c: Canvas, r: RectF, text: String) {
            val p = UiKit.bodyPaint(32f, Theme.textDim).apply { textAlign = Paint.Align.CENTER }
            c.drawText(Tr.t(text), r.centerX(), r.centerY(), p)
        }

        /** the game rule turns macros off (C_GameRules MacrosDisabled): the lock, and nothing to touch */
        private fun lockScreen(c: Canvas, r: RectF, text: String) {
            lock(c, r.centerX(), r.centerY() - 60f, 80f, Theme.textDim)
            val p = UiKit.bodyPaint(32f, Theme.textDim).apply { textAlign = Paint.Align.CENTER }
            c.drawText(Tr.t(text), r.centerX(), r.centerY() + 40f, p)
        }

        /** a padlock, [size] px, drawn in the kit's dim ink (no asset: a stand-in, D7) */
        private fun lock(c: Canvas, cx: Float, cy: Float, size: Float, colour: Int) {
            pLine.color = colour; pLine.strokeWidth = size * 0.11f
            val bw = size * 0.72f; val bh = size * 0.52f
            val body = RectF(cx - bw / 2f, cy - bh / 2f + size * 0.16f, cx + bw / 2f, cy + bh / 2f + size * 0.16f)
            pFill.color = colour; c.drawRoundRect(body, size * 0.08f, size * 0.08f, pFill)
            val arc = RectF(cx - bw * 0.32f, body.top - size * 0.42f, cx + bw * 0.32f, body.top + size * 0.2f)
            c.drawArc(arc, 180f, 180f, false, pLine)
        }

        private fun nameFit(text: String, p: Paint, maxW: Float): String {
            if (maxW <= 0f) return ""
            if (p.measureText(text) <= maxW) return text
            val n = p.breakText(text, true, (maxW - p.measureText("…")).coerceAtLeast(0f), null)
            return text.take(n) + "…"
        }

        private fun icon(id: Int): android.graphics.Bitmap? = Icons.get(id.takeIf { it > 0 } ?: Icons.QUESTION_MARK)

        // ---- the grid (the game's window: tabs, 6 wide, the selection beside it)
        private fun grid(c: Canvas, s: State, r: RectF) {
            // the tabs: General n/max, the character's n/max (the game's GENERAL_MACROS / CHARACTER_SPECIFIC_MACROS, terse)
            val tabs = listOf(RectF(r.left + 104f, r.top + 6f, r.left + 404f, r.top + 70f), RectF(r.left + 414f, r.top + 6f, r.left + 714f, r.top + 70f))
            for ((i, tr) in tabs.withIndex()) {
                val on = (i == 0) == (s.tab == "g")
                UiKit.tab(c, tr, "", on, onTop = true)
                val cnt = if (i == 0) s.general else s.char
                val label = if (i == 0) Tr.t("General") else s.me.ifEmpty { Tr.t("Character") }      // the character's name: never Tr.t
                val p = UiKit.bodyPaint(26f, if (on) Color.WHITE else Theme.gold)
                val np = UiKit.numberPaint(24f, Theme.textDim)
                val num = "${cnt.n}/${cnt.max}"
                val shown = nameFit(label, p, tr.width() - np.measureText(num) - 52f)
                val tw = p.measureText(shown) + 12f + np.measureText(num)
                val x0 = tr.centerX() - tw / 2f
                val ty = tr.centerY() + 8f - (p.ascent() + p.descent()) / 2f
                c.drawText(shown, x0, ty, p)
                c.drawText(num, x0 + p.measureText(shown) + 12f, ty, np)
                if (s.cur == -(i + 1)) UiKit.focus(c, RectF(tr.left + 8f, tr.top + 6f, tr.right - 8f, tr.bottom - 4f))
                hit(tr, MacrosModel.cell(s, if (i == 0) "tg" else "tc"))
            }
            // the cells: 6 x 4, an icon in its slot and the name under it
            val gx = r.left + 24f; val gy = r.top + 92f
            val cw = 112f; val ch = 142f
            for ((k, cell) in s.cells.withIndex()) {
                val col = k % 6; val row = k / 6
                val x = gx + col * cw; val y = gy + row * ch
                val icon = RectF(x + 14f, y + 6f, x + 98f, y + 90f)
                if (cell.plus) {
                    UiKit.slot(c, null, icon.left, icon.top, icon.width())
                    val pp = UiKit.titlePaint(64f, Theme.gold).apply { textAlign = Paint.Align.CENTER }
                    c.drawText("+", icon.centerX(), icon.centerY() - (pp.ascent() + pp.descent()) / 2f, pp)
                    val np = UiKit.bodyPaint(20f, Theme.gold).apply { textAlign = Paint.Align.CENTER }
                    c.drawText(Tr.t("New"), icon.centerX(), y + 120f, np)
                    hit(RectF(x, y, x + cw, y + ch), MacrosModel.pickCells(s, k, act = true))
                } else {
                    UiKit.slot(c, icon(cell.icon), icon.left, icon.top, icon.width())
                    if (cell.locked) lock(c, icon.right - 14f, icon.bottom - 16f, 26f, Theme.gold)         // s14 D6: the companion's own
                    val np = UiKit.bodyPaint(20f, Theme.text).apply { textAlign = Paint.Align.CENTER }
                    c.drawText(nameFit(cell.name, np, cw - 8f), icon.centerX(), y + 120f, np)     // the player's text: never Tr.t
                    hit(RectF(x, y, x + cw, y + ch), MacrosModel.pickCells(s, k, act = false))
                }
                if (k == s.cur) UiKit.focus(c, RectF(icon.left - 4f, icon.top - 4f, icon.right + 4f, icon.bottom + 4f))
            }
            // the pages: < n/m > under the grid
            val py = gy + 4 * ch + 34f
            val gridMid = gx + 3 * cw
            val canPrev = s.page > 1; val canNext = s.page < s.pages
            UiKit.pageArrow(c, false, gridMid - 150f, py, 60f, canPrev)
            UiKit.pageArrow(c, true, gridMid + 150f, py, 60f, canNext)
            if (canPrev) hit(RectF(gridMid - 200f, py - 50f, gridMid - 100f, py + 50f), MacrosModel.cell(s, "pp"))
            if (canNext) hit(RectF(gridMid + 100f, py - 50f, gridMid + 200f, py + 50f), MacrosModel.cell(s, "pn"))
            val pg = UiKit.numberPaint(28f, Theme.textDim).apply { textAlign = Paint.Align.CENTER }
            c.drawText("${s.page}/${s.pages}", gridMid, py - (pg.ascent() + pg.descent()) / 2f, pg)
            // the selection beside the grid (read only): its icon, name, lines, n/255; A Edit and X Bind under it
            val px = gx + 6 * cw + 30f
            val side = RectF(px, r.top + 6f, r.right - 10f, r.bottom - 6f)
            UiKit.pane(c, side, right = true)
            val m = s.mac.takeIf { s.cur >= 0 && s.cells.getOrNull(s.cur)?.plus == false }
            if (m == null) {
                val p = UiKit.bodyPaint(26f, Theme.textDim).apply { textAlign = Paint.Align.CENTER }
                c.drawText(Tr.t(if (s.cells.isEmpty()) "No macros" else ""), side.centerX(), side.top + 160f, p)
                return
            }
            macPreview(c, m, RectF(side.left + 24f, side.top + 24f, side.right - 24f, side.bottom - 140f), 8)
            val by = side.bottom - 110f
            val bw = (side.width() - 72f) / 2f
            val eb = RectF(side.left + 24f, by, side.left + 24f + bw, by + 84f)
            val xb = RectF(eb.right + 24f, by, eb.right + 24f + bw, by + 84f)
            val canEdit = !m.locked
            val canBind = !s.combat && !m.locked
            UiKit.button(c, eb, "Edit", UiKit.okGlyph(), if (canEdit) UiKit.Button.UP else UiKit.Button.DISABLED)
            UiKit.button(c, xb, "Bind", UiKit.xGlyph(), if (canBind) UiKit.Button.UP else UiKit.Button.DISABLED)
            if (canEdit) hit(eb, MacrosModel.cell(s, "a"))
            if (canBind) hit(xb, MacrosModel.cell(s, "x"))
        }

        /** a macro as the game's window shows it beside the grid: the icon, the name, its lines (the player's text), n/255 */
        private fun macPreview(c: Canvas, m: MacrosModel.Mac, r: RectF, maxLines: Int) {
            UiKit.slot(c, icon(m.icon), r.left + 6f, r.top + 6f, 84f)
            val np = UiKit.bodyPaint(32f, Theme.gold)
            c.drawText(nameFit(m.name, np, r.width() - 150f), r.left + 112f, r.top + 48f - (np.ascent() + np.descent()) / 2f, np)
            if (m.locked) lock(c, r.right - 24f, r.top + 40f, 36f, Theme.gold)                      // s14 D6: view only
            val lp = UiKit.bodyPaint(24f, Theme.text, shadow = false)
            var y = r.top + 132f
            for ((i, ln) in m.lines.withIndex()) {
                if (i >= maxLines) { c.drawText("…", r.left + 6f, y, lp); break }
                c.drawText(nameFit(ln, lp, r.width() - 12f), r.left + 6f, y, lp)
                y += 34f
            }
            val cp = UiKit.numberPaint(22f, Theme.textDim).apply { textAlign = Paint.Align.RIGHT }
            c.drawText(MacrosModel.counter(m), r.right, r.bottom, cp)
        }

        // ---- s14 chips: a rounded plate in the frame's bronze, an icon and a word; lit = the glow, warn = amber, bad = red
        private fun chip(c: Canvas, r: RectF, label: String?, iconId: Int? = null, lit: Boolean = false, warn: Boolean = false,
                         bad: Boolean = false, dim: Boolean = false, size: Float = 26f, tr: Boolean = true, small: String? = null) {
            pFill.color = Color.argb(200, 14, 11, 8); c.drawRoundRect(r, 12f, 12f, pFill)
            pLine.strokeWidth = if (bad || warn) 3f else 2f
            pLine.color = when { bad -> Color.rgb(220, 60, 50); warn -> Theme.warn; else -> Theme.bronzeDim }
            c.drawRoundRect(r, 12f, 12f, pLine)
            var x = r.left + 12f
            if (iconId != null) {
                val sz = (r.height() - 16f).coerceAtMost(56f)
                UiKit.slot(c, icon(iconId), x, r.centerY() - sz / 2f, sz)
                x += sz + 12f
            }
            if (small != null) {
                val sp = UiKit.bodyPaint(18f, Theme.textDim, shadow = false)
                c.drawText(Tr.t(small), x, r.top + 24f, sp)
            }
            if (label != null) {
                val p = UiKit.bodyPaint(size, when { dim -> Theme.dim; lit -> Color.WHITE; else -> Theme.text })
                val text = if (tr) Tr.t(label) else label
                val y = if (small != null) r.bottom - 14f else r.centerY() - (p.ascent() + p.descent()) / 2f
                c.drawText(nameFit(text, p, r.right - 10f - x), x, y, p)
            }
            if (lit) UiKit.focus(c, r)
        }

        /** one condition as a small pill: its word (or the game's own text, kept as written), struck through for its "no" form; the
         *  modifiers carry the player's own button glyph (ZL / LT / L2 = Shift, LB / L / L1 = Ctrl) with the word small */
        private fun condPill(c: Canvas, x: Float, cy: Float, k: String, h: Float = 40f): Float {
            val word = MacrosModel.condWord(k)
            val text = word?.let { Tr.t(it) } ?: k.removePrefix("~")
            val p = UiKit.bodyPaint(20f, Theme.text, shadow = false)
            val glyph = MacrosModel.condGlyph(k)
            val gw = if (glyph != null) h - 6f else 0f
            val w = p.measureText(text) + 22f + gw
            val r = RectF(x, cy - h / 2f, x + w, cy + h / 2f)
            pFill.color = Color.argb(160, 40, 32, 20); c.drawRoundRect(r, h / 2f, h / 2f, pFill)
            pLine.strokeWidth = 1.5f; pLine.color = Theme.bronzeDim; c.drawRoundRect(r, h / 2f, h / 2f, pLine)
            if (glyph != null) UiKit.pad(c, Glyphs.translate("rev_$glyph", CastSettings.buttonLayout), x + 6f + gw / 2f, cy, gw - 4f)
            val tx = x + 11f + gw
            c.drawText(text, tx, cy - (p.ascent() + p.descent()) / 2f, p)
            if (MacrosModel.condStruck(k)) {
                pLine.strokeWidth = 2.5f; pLine.color = Color.rgb(220, 70, 60)
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
            val sp = UiKit.bodyPaint(18f, Theme.textDim, shadow = false)
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

        /** D3: one line as a strip of icons and short tags, its code small under it */
        private fun strip(c: Canvas, r: RectF, st: MacrosModel.Strip, code: String, locked: Boolean, reserve: Float = 0f) {
            val cy = r.top + 30f
            if (st.kind == "text" || st.kind == "lock" || st.kind == "empty") {
                if (locked) lock(c, r.left + 24f, r.centerY(), 30f, Theme.textDim)
                val p = UiKit.bodyPaint(24f, if (locked) Theme.textDim else Color.WHITE, shadow = false)
                c.drawText(nameFit(code, p, r.width() - 70f), r.left + 50f, r.centerY() - (p.ascent() + p.descent()) / 2f, p)
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
                st.sub?.let { sub -> val sp = UiKit.bodyPaint(18f, Theme.textDim, shadow = false); c.drawText(sub, x, cy + 8f, sp); x += sp.measureText(sub) + 10f }
            }
            if (st.seq.isNotEmpty()) {
                val np = UiKit.bodyPaint(22f, Color.WHITE, shadow = false)
                val t = nameFit(st.seq.joinToString(", ") { it.name }, np, 320f); c.drawText(t, x, cy + 8f, np); x += np.measureText(t) + 10f
            }
            val warnW = MacrosModel.warnWord(st.warn)
            val maxX = r.right - reserve - (if (warnW != null) 200f else 16f)
            casesInline(c, x + 6f, cy, maxX, st.cases.takeIf { it.size > 1 || it.firstOrNull()?.let { f -> f.unit != null || f.conds.isNotEmpty() } == true } ?: emptyList())
            if (warnW != null) {
                val ap = UiKit.bodyPaint(20f, Theme.warn, shadow = false).apply { textAlign = Paint.Align.RIGHT }
                c.drawText(nameFit(Tr.t(warnW), ap, 190f), r.right - 12f, cy + 7f, ap)
            }
            val code0 = UiKit.bodyPaint(17f, Theme.textDim, shadow = false)
            c.drawText(nameFit(code, code0, r.width() - 72f - reserve), r.left + 62f, r.bottom - 10f, code0)
        }

        // ---- the editor: the name, the icon, the strips (or an empty macro's preset tiles), + Line / Presets / Delete
        private fun editor(c: Canvas, s: State, r: RectF) {
            val m = s.mac ?: return
            val left = r.left + 20f; val right = r.right - 20f
            // the icon chip and the name row
            val iconBox = RectF(left, r.top + 8f, left + 104f, r.top + 112f)
            UiKit.slot(c, icon(m.icon), iconBox.left + 10f, iconBox.top + 10f, 84f)
            if (m.icon == Icons.QUESTION_MARK || m.icon == 0) {
                val ap = UiKit.bodyPaint(18f, Theme.textDim).apply { textAlign = Paint.Align.CENTER }
                c.drawText(Tr.t("Auto"), iconBox.centerX(), iconBox.bottom + 18f, ap)
            }
            if (s.ef == "icon") UiKit.focus(c, iconBox)
            hit(iconBox, MacrosModel.pickCells(s, 1, act = true))
            chipAt["icon"] = iconBox
            val nameBox = RectF(left + 120f, r.top + 8f, right, r.top + 112f)
            UiKit.inputBox(c, nameBox)
            val np = UiKit.bodyPaint(36f, Theme.gold)
            c.drawText(nameFit(m.name, np, nameBox.width() - 200f), nameBox.left + 24f, nameBox.centerY() - (np.ascent() + np.descent()) / 2f, np)
            val cp = UiKit.numberPaint(26f, Theme.textDim).apply { textAlign = Paint.Align.RIGHT }
            c.drawText(MacrosModel.counter(m), nameBox.right - 20f, nameBox.centerY() + 9f, cp)
            if (s.ef == "name") UiKit.focus(c, nameBox)
            hit(nameBox, MacrosModel.pickCells(s, 0, act = true))
            val top = nameBox.bottom + 22f
            val btnTop = r.bottom - 100f
            if (m.lines.isEmpty() && s.tiles.isNotEmpty()) {
                // D2: an empty macro offers the presets right here (up to 8 tiles, four a row)
                tiles(c, s, RectF(left, top, right, btnTop - 16f), 2, s.ef)
            } else {
                val lineH = 74f
                val visible = ((btnTop - 16f - top) / lineH).toInt().coerceAtLeast(1)
                val n = minOf(m.lines.size, MacrosModel.LINE_ROWS)
                val focusIdx = (s.ef?.takeIf { it.startsWith("l") }?.drop(1)?.toIntOrNull() ?: s.moveAt ?: 1) - 1
                val first = if (focusIdx >= visible) focusIdx - visible + 1 else 0
                val box = RectF(left, top, right, top + visible * lineH + 8f)
                well(c, box)
                for (i in first until minOf(n, first + visible)) {
                    val y = top + 4f + (i - first) * lineH
                    val lr = RectF(left + 6f, y, right - 6f, y + lineH - 4f)
                    val st = s.strips.getOrNull(i) ?: MacrosModel.Strip("text", null, null, null, false, null, emptyList(), null, null, emptyList(), null)
                    val moving = s.view == "move" && s.moveAt == i + 1
                    if (moving) { pFill.color = Color.argb(70, 255, 210, 90); c.drawRoundRect(lr, 8f, 8f, pFill) }
                    val glow = s.ef == "l${i + 1}" && s.view != "move"
                    strip(c, lr, st, m.lines.getOrElse(i) { "" }, (i + 1) in m.lock, reserve = if (glow) 130f else 0f)
                    if (s.draftLine == i + 1 && s.view != "move") {
                        val dp = UiKit.bodyPaint(18f, Theme.warn, shadow = false).apply { textAlign = Paint.Align.RIGHT }
                        c.drawText(Tr.t("Draft"), lr.right - 10f, lr.bottom - 10f, dp)
                    }
                    if (moving) UiKit.moveArrowsUpDown(c, lr, up = i > 0, down = i < n - 1)
                    hit(lr, MacrosModel.pickCells(s, i + 2, act = true))
                    if (glow) {
                        UiKit.focus(c, lr)
                        // the Y glyph's Move by touch, on the line under the glow (its own hit, over the line's)
                        val mb = RectF(lr.right - 124f, lr.centerY() - 22f, lr.right - 8f, lr.centerY() + 22f)
                        if (st.kind != "lock") {
                            UiKit.pad(c, Glyphs.translate("rev_y", CastSettings.buttonLayout), mb.left + 18f, mb.centerY(), 32f)
                            val mp = UiKit.bodyPaint(20f, Theme.textDim, shadow = false)
                            c.drawText(Tr.t("Move"), mb.left + 40f, mb.centerY() + 7f, mp)
                            hit(mb, MacrosModel.cell(s, "y"))
                        }
                    }
                }
                if (m.lines.isEmpty()) {
                    val ep = UiKit.bodyPaint(26f, Theme.textDim, shadow = false)
                    c.drawText(Tr.t("Empty"), left + 54f, top + 46f, ep)
                }
            }
            // the buttons: + Line, Presets (a macro with lines), Delete
            val keys = if (m.lines.isEmpty()) listOf("add", "del") else MacrosModel.BUTTONS
            val bw = (right - left - (keys.size - 1) * 16f) / keys.size
            for ((i, key) in keys.withIndex()) {
                val b = RectF(left + i * (bw + 16f), btnTop, left + i * (bw + 16f) + bw, btnTop + 84f)
                val focused = s.ef == key
                val word = when (key) { "add" -> if (s.draftLine == null && s.draftAt != null) "+ Line (Draft)" else "+ Line"
                    "pre" -> "Presets"; else -> if (s.del) "Delete?" else "Delete" }
                val st = if (s.combat && key == "del") UiKit.Button.DISABLED else UiKit.Button.UP
                if (focused) UiKit.button(c, b, word, UiKit.okGlyph(), st) else UiKit.button(c, b, word, st)
                if (focused) UiKit.focus(c, b)
                hit(b, MacrosModel.pickCells(s, MacrosModel.editCell(key), act = true))
            }
        }

        /** the preset tiles: [cols] ... four a row; [ef] the glow's key ("t3"), or the presets view's own glow */
        private fun tiles(c: Canvas, s: State, area: RectF, sBase: Int, ef: String?) {
            val per = 4
            val gap = 16f
            val tw = (area.width() - (per - 1) * gap) / per
            val th = minOf(250f, (area.height() - gap) / 2f)
            for ((k, t) in s.tiles.withIndex()) {
                val x = area.left + (k % per) * (tw + gap); val y = area.top + (k / per) * (th + gap)
                val tr = RectF(x, y, x + tw, y + th)
                UiKit.card(c, tr)
                val lp = UiKit.bodyPaint(26f, Theme.gold).apply { textAlign = Paint.Align.CENTER }
                c.drawText(nameFit(Tr.t(MacrosModel.presetWord(t.key)), lp, tw - 24f), tr.centerX(), tr.top + 50f, lp)
                var ix = tr.centerX() - (t.icons.size.coerceAtMost(3) * 62f) / 2f
                for (ic in t.icons.take(3)) { UiKit.slot(c, icon(ic), ix + 4f, tr.top + 74f, 54f); ix += 62f }
                val sp = UiKit.bodyPaint(17f, Theme.textDim, shadow = false)
                var ly = tr.top + 160f
                for (ln in t.text.split("\n").take(3)) { c.drawText(nameFit(ln, sp, tw - 24f), tr.left + 14f, ly, sp); ly += 24f }
                val key = "t${k + 1}"
                if (ef == key || (ef == null && s.presetCur == k + 1)) UiKit.focus(c, RectF(tr.left + 6f, tr.top + 6f, tr.right - 6f, tr.bottom - 6f))
                hit(tr, MacrosModel.pickCells(s, sBase + k, act = true))
            }
        }

        // ---- the presets view (Presets on a macro with lines): the tiles, A replaces after a second A (D2)
        private fun presets(c: Canvas, s: State, r: RectF) {
            val hp = UiKit.bodyPaint(30f, Theme.gold)
            c.drawText(Tr.t("Presets"), r.left + 130f, r.top + 40f, hp)
            tiles(c, s, RectF(r.left + 20f, r.top + 70f, r.right - 20f, r.bottom - 20f), 0, null)
        }

        // ---- a preset's preview: its lines as strips, its picks, Done
        private fun preview(c: Canvas, s: State, r: RectF) {
            val p = s.preset ?: return
            val left = r.left + 20f; val right = r.right - 20f
            val hp = UiKit.bodyPaint(30f, Theme.gold)
            c.drawText(Tr.t(MacrosModel.presetWord(p.key)), left + 110f, r.top + 40f, hp)
            val cp = UiKit.numberPaint(24f, if (MacrosModel.tooLong(p.len)) Color.rgb(230, 70, 60) else Theme.textDim).apply { textAlign = Paint.Align.RIGHT }
            c.drawText("${p.len}/${MacrosModel.BODY_MAX}", right, r.top + 40f, cp)
            val top = r.top + 70f
            val box = RectF(left, top, right, top + p.strips.size.coerceAtLeast(1) * 74f + 8f)
            well(c, box)
            val lines = p.text.split("\n")
            for ((i, st) in p.strips.withIndex()) {
                val y = top + 4f + i * 74f
                strip(c, RectF(left + 6f, y, right - 6f, y + 70f), st, lines.getOrElse(i) { "" }, false)
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
            val db = RectF(right - 300f, r.bottom - 100f, right, r.bottom - 16f)
            val word = if (p.armed) "Replace?" else "Done"
            UiKit.button(c, db, word, selectGlyph(), if (p.hard != null || s.combat) UiKit.Button.DISABLED else UiKit.Button.UP)
            if (p.cur == "done") UiKit.focus(c, db)
            hit(db, MacrosModel.pickCells(s, 3, act = true))
        }

        private fun selectGlyph(): String = Glyphs.translate("rev_minus", CastSettings.buttonLayout)

        // ---- THE BUILDER (D1): the line's text, its action row, its cases, + Case, then Aa Text / Remove / Done
        private fun builder(c: Canvas, s: State, r: RectF) {
            val b = s.builder ?: return
            val left = r.left + 20f; val right = r.right - 20f
            // the title band: Line n (or New line), n/255, the status word
            val tp = UiKit.bodyPaint(30f, Theme.gold)
            val title = if (b.line == null) Tr.t("New line") else Tr.t("Line") + " ${b.line}"
            c.drawText(title, left + 110f, r.top + 40f, tp)
            val cp = UiKit.numberPaint(24f, if (MacrosModel.tooLong(b.len)) Color.rgb(230, 70, 60) else Theme.textDim).apply { textAlign = Paint.Align.RIGHT }
            c.drawText("${b.len}/${MacrosModel.BODY_MAX}", right, r.top + 40f, cp)
            // in a fight the room's status line says so (the chips stay neutral): nothing here then
            val word = if (s.combat) null else MacrosModel.hardWord(b.hard)
                ?: MacrosModel.warnWord(b.warns.firstOrNull()) ?: (if (b.draft) "Draft" else null)
            word?.let { w ->
                val bad = b.hard != null && !s.combat
                val sp = UiKit.bodyPaint(22f, when { bad -> Color.rgb(230, 80, 60); b.warns.isNotEmpty() && b.hard == null -> Theme.warn
                    else -> Theme.textDim }).apply { textAlign = Paint.Align.RIGHT }
                c.drawText(nameFit(Tr.t(w), sp, 300f), right - 110f, r.top + 40f, sp)
            }
            // the composed text in a dark well (red past 255): the player's own text, never Tr.t
            val wellR = RectF(left, r.top + 60f, right, r.top + 120f)
            well(c, wellR)
            val wp = UiKit.bodyPaint(26f, if (MacrosModel.tooLong(b.len)) Color.rgb(240, 90, 70) else Color.WHITE, shadow = false)
            c.drawText(nameFit(b.text, wp, wellR.width() - 32f), wellR.left + 16f, wellR.centerY() - (wp.ascent() + wp.descent()) / 2f, wp)
            // the chips, row by row as the add-on lays them
            var y = wellR.bottom + 18f
            val bad = if (s.combat) null else b.hardChip
            val warn = !s.combat && b.warns.isNotEmpty()
            for ((ri, row) in b.rows.withIndex()) {
                val isCase = row.firstOrNull()?.matches(Regex("^c\\d[ufa]$")) == true
                val isFooter = row.firstOrNull() == "text"
                val h = when { ri == 0 -> 96f; isCase -> 84f; isFooter -> 0f; else -> 70f }
                if (isFooter) break
                if (isCase) {
                    val n = row.first().substring(1, 2).toInt()
                    val card = RectF(left, y, right, y + h)
                    pFill.color = Color.argb(120, 30, 24, 16); c.drawRoundRect(card, 14f, 14f, pFill)
                    val tabW = 96f
                    val tabR = RectF(left + 8f, y + 8f, left + tabW, y + h - 8f)
                    val tl = UiKit.bodyPaint(22f, Theme.gold).apply { textAlign = Paint.Align.CENTER }
                    c.drawText(if (n == 1) "1" else Tr.t("else"), tabR.centerX(), tabR.centerY() - (tl.ascent() + tl.descent()) / 2f, tl)
                    var x = left + tabW + 12f
                    val cs = b.cases.getOrNull(n - 1)
                    for (key in row) {
                        val part = key.last()
                        val w = when (part) { 'u' -> 300f; 'f' -> 480f; else -> 300f }.coerceAtMost(right - x - 8f)
                        val cr = RectF(x, y + 8f, x + w, y + h - 8f)
                        when (part) {
                            'u' -> chip(c, cr, cs?.unit?.let { unitText(it) } ?: Tr.t("Target"), lit = b.cur == key, small = "On", tr = false)
                            'f' -> {
                                chip(c, cr, if (cs?.conds.isNullOrEmpty()) "Always" else null, lit = b.cur == key, small = "If")
                                var px = cr.left + 14f
                                for (k in cs?.conds.orEmpty()) { if (px > cr.right - 60f) break; px = condPill(c, px, cr.centerY() + 10f, k, 32f) }
                            }
                            else -> chip(c, cr, cs?.arg?.name?.takeIf { it.isNotEmpty() } ?: Tr.t("Pick"), cs?.arg?.icon ?: 0,
                                lit = b.cur == key, bad = bad == key, tr = false)
                        }
                        chipAt[key] = cr
                        MacrosModel.chipCell(b, key)?.let { sk -> hit(cr, MacrosModel.pickCells(s, sk, act = true)) }
                        x = cr.right + 12f
                    }
                    // Y's remove by touch: the case's small x (select its first chip, then Y)
                    if (b.cases.size > 1 || cs?.unit != null || cs?.conds?.isNotEmpty() == true) {
                        val xr = RectF(right - 64f, y + 18f, right - 12f, y + h - 18f)
                        UiKit.closeX(c, xr.centerX(), xr.centerY(), 40f)
                        val sk = MacrosModel.chipCell(b, row.first())
                        val yk = MacrosModel.cell(s, "y")
                        if (sk != null && yk != null) MacrosModel.pickCells(s, sk, act = false)?.let { hit(xr, it + yk) }
                    }
                } else {
                    var x = left
                    for (key in row) {
                        val w = when (key) { "cmd" -> 260f; "arg" -> 460f; "rank", "reset" -> 220f; "keep" -> 200f; "add" -> 260f; else -> 200f }
                        val cr = RectF(x, y, (x + w).coerceAtMost(right), y + h - 10f)
                        when (key) {
                            "cmd" -> chip(c, cr, b.cmdName ?: MacrosModel.cmdWord(b.cmd), lit = b.cur == key, tr = b.cmdName == null)
                            "arg" -> argChip(c, cr, b, lit = b.cur == key, bad = bad == key, warn = warn)
                            "rank" -> chip(c, cr, b.rank?.let { (at, m) -> if (at == 0) Tr.t("Top") else "$at / $m" } ?: Tr.t("Top"),
                                lit = b.cur == key, small = "Rank", tr = false)
                            "reset" -> chip(c, cr, b.reset?.split("/")?.joinToString(" ") { o -> MacrosModel.resetWord(o)?.let { Tr.t(it) } ?: "$o s" }
                                ?: Tr.t("None"), lit = b.cur == key, small = "Reset", tr = false)
                            "keep" -> {
                                chip(c, cr, "Keep on", lit = b.cur == key)
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
            // the footer: Aa Text, Remove (Revert while a draft), Done (Select)
            val fy = r.bottom - 100f
            val fw = (right - left - 2 * 16f) / 3f
            val keys = listOf("text", "rem", "done")
            for ((i, key) in keys.withIndex()) {
                val fr = RectF(left + i * (fw + 16f), fy, left + i * (fw + 16f) + fw, fy + 84f)
                val focused = b.cur == key
                when (key) {
                    "text" -> UiKit.button(c, fr, "Aa Text", if (focused) UiKit.Button.UP else UiKit.Button.UP)
                    "rem" -> UiKit.button(c, fr, MacrosModel.removeWord(b), UiKit.Button.UP)
                    else -> UiKit.button(c, fr, "Done", selectGlyph(), if (b.hard != null || s.combat) UiKit.Button.DISABLED else UiKit.Button.UP)
                }
                if (focused) UiKit.focus(c, fr)
                chipAt[key] = fr
                if (key == "done") hit(fr, MacrosModel.doneCells(s))
                else MacrosModel.chipCell(b, key)?.let { sk -> hit(fr, MacrosModel.pickCells(s, sk, act = true)) }
            }
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

        // ---- THE POP-OUTS: each slides out of its chip (UiKit.slideOut); a finger outside it closes it (B)
        private fun popOut(c: Canvas, s: State, p: MacrosModel.Pop, r: RectF) {
            val key = "${p.kind}:${p.case}:${p.forSeq}:${p.forPreset}"
            val now = SystemClock.uptimeMillis()
            if (key != popKey) { popKey = key; popAt = now }
            val t = if (dryRun) SLIDE_MS else (now - popAt).toFloat()
            val anchorKey = when (p.kind) {
                "cmd" -> "cmd"; "rank" -> "rank"; "reset" -> "reset"; "case" -> "add"; "seq" -> "arg"; "icon" -> "icon"
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
                "on", "reset" -> { cellW = 290f; cellH = 60f }
                else -> { cellW = 440f; cellH = 64f }
            }
            val rowsN = ((p.rows.size + p.cols - 1) / p.cols).coerceAtLeast(1)
            val head = if (p.kind == "arg") 150f else 20f
            val foot = if (p.pages > 1) 70f else 16f
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
            UiKit.slideOut(c, anchor, to, t) { cv, box -> popCells(cv, s, p, box, head, cellW, cellH) }
            if (UiKit.sliding(t)) postInvalidateOnAnimation()
        }

        private fun popCells(c: Canvas, s: State, p: MacrosModel.Pop, box: RectF, head: Float, cellW: Float, cellH: Float) {
            hits += RectF(box) to emptyList()                                 // a finger on the panel's ground does nothing
            if (p.kind == "arg") {
                // the tabs (s0..s4), the search (s5)
                val tw = (box.width() - 40f) / p.tabs.size.coerceAtLeast(1)
                for ((i, t) in p.tabs.withIndex()) {
                    val tr = RectF(box.left + 20f + i * tw, box.top + 14f, box.left + 20f + (i + 1) * tw - 8f, box.top + 70f)
                    UiKit.tab(c, tr, Tr.t(MacrosModel.tabWord(t)), t == p.tab, onTop = true, cursor = p.cur == "t${i + 1}")
                    if (p.cur == "t${i + 1}") UiKit.focus(c, tr)
                    hit(tr, MacrosModel.pickCells(s, i, act = true))
                }
                val sr = RectF(box.left + 20f, box.top + 80f, box.right - 20f, box.top + 136f)
                UiKit.inputBox(c, sr)
                val qp = UiKit.bodyPaint(22f, if (p.query != null) Color.WHITE else Theme.textDim, shadow = false)
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
                val ck = "r${i + 1}"
                val lit = p.cur == ck
                if (lit) tipFor = row
                popCell(c, p, row, cr, lit)
                if (lit) UiKit.focus(c, cr)
                hit(cr, MacrosModel.pickCells(s, p.base + i, act = true))
            }
            if (p.rows.isEmpty()) {
                val ep = UiKit.bodyPaint(24f, Theme.textDim)
                c.drawText(Tr.t("Nothing to pick"), gx + 8f, gy + 40f, ep)
            }
            if (p.pages > 1) {
                val py = box.bottom - 38f
                val canPrev = p.page > 1; val canNext = p.page < p.pages
                UiKit.pageArrow(c, false, box.centerX() - 120f, py, 50f, canPrev)
                UiKit.pageArrow(c, true, box.centerX() + 120f, py, 50f, canNext)
                if (canPrev) hit(RectF(box.centerX() - 170f, py - 40f, box.centerX() - 70f, py + 40f), MacrosModel.cell(s, "pp"))
                if (canNext) hit(RectF(box.centerX() + 70f, py - 40f, box.centerX() + 170f, py + 40f), MacrosModel.cell(s, "pn"))
                val pg = UiKit.numberPaint(24f, Theme.textDim).apply { textAlign = Paint.Align.CENTER }
                c.drawText("${p.page}/${p.pages}", box.centerX(), py - (pg.ascent() + pg.descent()) / 2f, pg)
            }
            // the spell's own words under the grid (the spells document's description), when the glow is on a spell
            if (p.kind == "arg") tipFor?.let { row ->
                val id = row.optInt("id", 0)
                val desc = if (id > 0) spellTip(id) else null
                if (!desc.isNullOrEmpty()) tooltip(c, row.optString("n", ""), desc, box)
            }
        }

        /** one pop-out cell, by kind */
        private fun popCell(c: Canvas, p: MacrosModel.Pop, row: JSONObject, cr: RectF, lit: Boolean) {
            when (p.kind) {
                "arg", "icon" -> {
                    val ic = row.optInt("i", 0)
                    val sz = (cr.width() - 30f).coerceAtMost(cr.height() - 40f)
                    UiKit.slot(c, icon(ic), cr.centerX() - sz / 2f, cr.top + 6f, sz)
                    if (row.optBoolean("on", false)) UiKit.checkedGlow(c, RectF(cr.centerX() - sz / 2f, cr.top + 6f, cr.centerX() + sz / 2f, cr.top + 6f + sz))
                    val np = UiKit.bodyPaint(17f, Theme.text, shadow = false).apply { textAlign = Paint.Align.CENTER }
                    val name = when {
                        row.optBoolean("auto", false) -> Tr.t("Auto")
                        row.has("sl") && row.isNull("n") -> Tr.t("Slot") + " " + row.optInt("sl")
                        else -> row.optString("n", "")                          // the game's names: never Tr.t
                    }
                    if (p.kind == "arg" || row.optBoolean("auto", false)) c.drawText(nameFit(name, np, cr.width() - 4f), cr.centerX(), cr.bottom - 6f, np)
                }
                "cmd" -> chip(c, cr, row.optString("n").takeIf { it.isNotEmpty() } ?: MacrosModel.cmdWord(row.optString("k")),
                    row.optInt("i", 0).takeIf { it > 0 }, lit = lit, tr = !row.has("n"))
                "on" -> {
                    val u = row.optString("u", "")
                    chip(c, cr, unitText(u), lit = lit, tr = false)
                }
                "if" -> {
                    val k = row.optString("k"); val st = row.optInt("s", 0)
                    chip(c, cr, null, lit = lit, dim = st == 0)
                    val shown = k                                             // the grid names the condition; Yes / No / Off say its state
                    condPill(c, cr.left + 14f, cr.centerY(), shown, 40f)
                    val sp = UiKit.bodyPaint(18f, if (st == 0) Theme.dim else Theme.gold, shadow = false).apply { textAlign = Paint.Align.RIGHT }
                    c.drawText(Tr.t(when (st) { 1 -> "Yes"; 2 -> "No"; else -> "Off" }), cr.right - 12f, cr.centerY() + 6f, sp)
                }
                "rank" -> {
                    val r = row.optInt("r", 0)
                    chip(c, cr, if (r == 0) Tr.t("Top") else "$r / ${p.ranks}", lit = lit, tr = false)
                    if (row.optBoolean("on", false)) UiKit.check(c, cr.right - 30f, cr.centerY(), 32f, true)
                }
                "case" -> chip(c, cr, MacrosModel.caseWord(row.optString("o")), lit = lit)
                "reset" -> {
                    val o = row.optString("o")
                    chip(c, cr, MacrosModel.resetWord(o)?.let { Tr.t(it) } ?: "$o s", lit = lit, tr = false)
                    UiKit.check(c, cr.right - 30f, cr.centerY(), 32f, row.optBoolean("on", false))
                }
                "seq" -> if (row.optBoolean("add", false)) chip(c, cr, "+ Spell", lit = lit)
                         else chip(c, cr, row.optString("n", ""), row.optInt("i", 0), lit = lit, tr = false)
                else -> chip(c, cr, row.optString("n", ""), lit = lit, tr = false)
            }
        }

        /** a spell's description beside the pop-out (the Spellbook's own words from the spells document; the game's text) */
        private fun tooltip(c: Canvas, name: String, desc: String, box: RectF) {
            val w = width.toFloat()
            val tw = 380f
            val x = if (box.right + 12f + tw < w - 8f) box.right + 12f else (box.left - 12f - tw).coerceAtLeast(8f)
            val tp = android.text.TextPaint(UiKit.bodyPaint(20f, Theme.text, shadow = false))
            val layout = android.text.StaticLayout.Builder.obtain(desc, 0, desc.length, tp, (tw - 32f).toInt()).build()
            val th = 64f + layout.height.coerceAtMost(360)
            val r = RectF(x, box.top, x + tw, box.top + th)
            UiKit.menu(c, r, solid = true)
            val np = UiKit.bodyPaint(24f, Color.WHITE)
            c.drawText(nameFit(name, np, tw - 32f), r.left + 16f, r.top + 40f, np)
            c.save(); c.translate(r.left + 16f, r.top + 56f); c.clipRect(0f, 0f, tw - 32f, 360f); layout.draw(c); c.restore()
        }

        /** the room's one status line: a refusal's word, the game's window, a fight, no pad row */
        private fun status(c: Canvas, s: State, r: RectF) {
            val word = when {
                s.noRow -> "No pad row"
                s.win -> "Game window open"
                s.view == "move" && s.note == null -> "Move"
                else -> MacrosModel.noteWord(s.note) ?: MacrosModel.hardWord(s.note)?.takeIf { s.note == "pick" }
                    ?: if (s.combat && s.view != "build") "In combat" else null
            } ?: return
            // in the title band, after the portrait (clear of the centred title and the close X)
            val p = UiKit.bodyPaint(24f, if (s.note != null) Theme.warn else Theme.textDim)
            c.drawText(nameFit(Tr.t(word), p, 300f), r.left + 130f, 62f, p)
        }

        /** the line over the keyboard (InteractView's): the field's word, what is typed, the caret */
        private fun typingLine(c: Canvas, r: RectF) {
            val kh = if (keyboard.height > 0) keyboard.height else ChatKeyboard.fullHeight()
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
    }
}
