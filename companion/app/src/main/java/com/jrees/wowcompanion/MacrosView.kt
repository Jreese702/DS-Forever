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
 * keyboard, the add-on's borrowed field), a picked spell / item's line, six recipe tiles; D7 stand-in art (the question-mark icon).
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
    private var winWas = false
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
                if (!s.win) { winWas = false; wxSent = false }
                if (live && shown) {
                    if (!holdSent && s.keys != null) sendHold(if (hadKeys) "on a document" else "the row is made")
                    // D2: the game's own window is open while the room holds: its own close button, ONCE per opening
                    if (s.win && !winWas && s.held && !wxSent) { wxSent = tap("wx"); winWas = wxSent; Log.i(TAG, "the game's window: close ${if (wxSent) "sent" else "not sent"}") }
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
        if (state?.keys == null) askRow("shown") else sendHold("shown")
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

        override fun onDraw(c: Canvas) {
            hits.clear()
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
                        "edit" -> editor(c, s, inner)
                        "pick" -> picker(c, s, inner)
                        "rec" -> recipes(c, s, inner)
                        else -> grid(c, s, inner)
                    }
                    status(c, s, inner)
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
            if (p.measureText(text) <= maxW) return text
            val n = p.breakText(text, true, (maxW - p.measureText("\u2026")).coerceAtLeast(0f), null)
            return text.take(n) + "\u2026"
        }

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
                    UiKit.slot(c, Icons.get(cell.icon.takeIf { it > 0 } ?: Icons.QUESTION_MARK), icon.left, icon.top, icon.width())
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
            preview(c, m, RectF(side.left + 24f, side.top + 24f, side.right - 24f, side.bottom - 140f), 8)
            val by = side.bottom - 110f
            val bw = (side.width() - 72f) / 2f
            val eb = RectF(side.left + 24f, by, side.left + 24f + bw, by + 84f)
            val xb = RectF(eb.right + 24f, by, eb.right + 24f + bw, by + 84f)
            val canBind = !s.combat
            UiKit.button(c, eb, "Edit", UiKit.okGlyph())
            UiKit.button(c, xb, "Bind", UiKit.xGlyph(), if (canBind) UiKit.Button.UP else UiKit.Button.DISABLED)
            hit(eb, MacrosModel.cell(s, "a"))
            if (canBind) hit(xb, MacrosModel.cell(s, "x"))
        }

        /** a macro as the game's window shows it beside the grid: the icon, the name, its lines (the player's text), n/255 */
        private fun preview(c: Canvas, m: MacrosModel.Mac, r: RectF, maxLines: Int) {
            UiKit.slot(c, Icons.get(m.icon.takeIf { it > 0 } ?: Icons.QUESTION_MARK), r.left + 6f, r.top + 6f, 84f)
            val np = UiKit.bodyPaint(32f, Theme.gold)
            c.drawText(nameFit(m.name, np, r.width() - 120f), r.left + 112f, r.top + 48f - (np.ascent() + np.descent()) / 2f, np)
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

        // ---- the editor: the name, the lines, the four buttons
        private fun editor(c: Canvas, s: State, r: RectF) {
            val m = s.mac ?: return
            val rows = MacrosModel.editRows(m)
            val left = r.left + 20f; val right = r.right - 20f
            // the name row
            val nameBox = RectF(left + 100f, r.top + 8f, right, r.top + 112f)
            UiKit.inputBox(c, nameBox)
            UiKit.slot(c, Icons.get(m.icon.takeIf { it > 0 } ?: Icons.QUESTION_MARK), nameBox.left + 12f, nameBox.top + 10f, 84f)
            val np = UiKit.bodyPaint(36f, Theme.gold)
            c.drawText(nameFit(m.name, np, nameBox.width() - 260f), nameBox.left + 116f, nameBox.centerY() - (np.ascent() + np.descent()) / 2f, np)
            val cp = UiKit.numberPaint(26f, Theme.textDim).apply { textAlign = Paint.Align.RIGHT }
            c.drawText(MacrosModel.counter(m), nameBox.right - 20f, nameBox.centerY() + 9f, cp)
            if (s.ef == "name") UiKit.focus(c, nameBox)
            hit(nameBox, MacrosModel.pickCells(s, 0, act = true))
            // the lines (each one row of the game's text box; a "|" line is locked: the keyboard cannot type it)
            val top = nameBox.bottom + 14f
            val lineH = 52f
            val btnTop = r.bottom - 100f
            val visible = ((btnTop - 16f - top) / lineH).toInt().coerceAtLeast(1)
            val lineRows = rows.filter { it.startsWith("l") }
            val focusIdx = lineRows.indexOf(s.ef ?: "")
            val first = if (focusIdx >= visible) focusIdx - visible + 1 else 0
            val box = RectF(left, top, right, top + visible * lineH + 8f)
            well(c, box)
            val lp = UiKit.bodyPaint(28f, Color.WHITE, shadow = false)
            for (i in first until minOf(lineRows.size, first + visible)) {
                val row = lineRows[i]
                val n = MacrosModel.editCell(row)
                val y = top + 4f + (i - first) * lineH
                val lr = RectF(left + 6f, y, right - 6f, y + lineH)
                val text = m.lines.getOrElse(n - 1) { "" }
                val locked = n in m.lock
                if (locked) lock(c, lr.left + 22f, lr.centerY(), 30f, Theme.textDim)
                c.drawText(nameFit(text, lp, lr.width() - 64f), lr.left + 48f, lr.centerY() - (lp.ascent() + lp.descent()) / 2f,
                    if (locked) UiKit.bodyPaint(28f, Theme.textDim, shadow = false) else lp)
                if (s.ef == row) UiKit.focus(c, lr)
                hit(lr, MacrosModel.pickCells(s, n, act = true))
            }
            if (m.lines.isEmpty()) {
                val ep = UiKit.bodyPaint(26f, Theme.textDim, shadow = false)
                c.drawText(Tr.t("Empty"), left + 54f, top + 36f, ep)
            }
            // the four buttons
            val words = listOf("+ Line", "Spell/Item", "Recipes", if (s.del) "Delete?" else "Delete")
            val bw = (right - left - 3 * 16f) / 4f
            for ((i, key) in MacrosModel.BUTTONS.withIndex()) {
                val b = RectF(left + i * (bw + 16f), btnTop, left + i * (bw + 16f) + bw, btnTop + 84f)
                val focused = s.ef == key
                val st = if (s.combat && key != "rec" && key != "pick") UiKit.Button.DISABLED else UiKit.Button.UP
                if (focused) UiKit.button(c, b, words[i], UiKit.okGlyph(), st) else UiKit.button(c, b, words[i], st)
                if (focused) UiKit.focus(c, b)
                hit(b, MacrosModel.pickCells(s, MacrosModel.editCell(key), act = true))
            }
        }

        // ---- + Spell/Item and a recipe's picks: one page of rows
        private fun picker(c: Canvas, s: State, r: RectF) {
            val p = s.pick ?: return
            val left = r.left + 20f; val right = r.right - 20f
            val hp = UiKit.bodyPaint(30f, Theme.gold)
            val head = p.rec?.let { id -> Tr.t(MacrosModel.RECIPE_WORDS[id] ?: "Recipes") + "  ${p.step}/${p.of}" } ?: Tr.t("Spell/Item")
            c.drawText(head, left + 110f, r.top + 40f, hp)
            val rowH = 64f
            val top = r.top + 64f
            for ((k, row) in p.rows.withIndex()) {
                val y = top + k * rowH
                val rr = RectF(left, y, right, y + rowH - 4f)
                UiKit.slot(c, Icons.get(row.icon.takeIf { it > 0 } ?: Icons.QUESTION_MARK), rr.left + 8f, rr.top + 4f, 52f)
                val np = UiKit.bodyPaint(28f, Theme.text)
                c.drawText(nameFit(row.name, np, 520f), rr.left + 80f, rr.centerY() - (np.ascent() + np.descent()) / 2f, np)
                val lp = UiKit.bodyPaint(22f, Theme.textDim, shadow = false).apply { textAlign = Paint.Align.RIGHT }
                if (p.rec == null) c.drawText(nameFit(MacrosModel.lineOf(row), lp, 440f), rr.right - 12f, rr.centerY() + 8f, lp)
                if (k == p.cur) UiKit.focus(c, rr)
                hit(rr, MacrosModel.pickCells(s, k, act = true))
            }
            if (p.rows.isEmpty()) {
                val ep = UiKit.bodyPaint(26f, Theme.textDim)
                c.drawText(Tr.t("Nothing to pick"), left + 8f, top + 40f, ep)
            }
            val py = r.bottom - 40f
            val canPrev = p.page > 1; val canNext = p.page < p.pages
            UiKit.pageArrow(c, false, r.centerX() - 150f, py, 56f, canPrev)
            UiKit.pageArrow(c, true, r.centerX() + 150f, py, 56f, canNext)
            if (canPrev) hit(RectF(r.centerX() - 200f, py - 46f, r.centerX() - 100f, py + 46f), MacrosModel.cell(s, "pp"))
            if (canNext) hit(RectF(r.centerX() + 100f, py - 46f, r.centerX() + 200f, py + 46f), MacrosModel.cell(s, "pn"))
            val pg = UiKit.numberPaint(26f, Theme.textDim).apply { textAlign = Paint.Align.CENTER }
            c.drawText("${p.page}/${p.pages}", r.centerX(), py - (pg.ascent() + pg.descent()) / 2f, pg)
        }

        // ---- the recipe tiles (3 x 2): the label, the line it adds
        private fun recipes(c: Canvas, s: State, r: RectF) {
            val left = r.left + 20f; val right = r.right - 20f
            val tw = (right - left - 2 * 20f) / 3f; val th = (r.height() - 60f) / 2f
            for ((k, id) in MacrosModel.RECIPES.withIndex()) {
                val x = left + (k % 3) * (tw + 20f); val y = r.top + 20f + (k / 3) * (th + 20f)
                val t = RectF(x, y, x + tw, y + th)
                UiKit.card(c, t)
                val lp = UiKit.bodyPaint(30f, Theme.gold).apply { textAlign = Paint.Align.CENTER }
                c.drawText(Tr.t(MacrosModel.RECIPE_WORDS[id] ?: id), t.centerX(), t.top + 70f, lp)
                val sp = UiKit.bodyPaint(20f, Theme.textDim, shadow = false).apply { textAlign = Paint.Align.CENTER }
                val ex = MacrosModel.RECIPE_LINES[id].orEmpty()
                c.drawText(nameFit(ex, sp, t.width() - 24f), t.centerX(), t.top + 130f, sp)
                if (k == (s.recCur ?: 0)) UiKit.focus(c, RectF(t.left + 6f, t.top + 6f, t.right - 6f, t.bottom - 6f))
                hit(t, MacrosModel.pickCells(s, k, act = true))
            }
        }

        /** the room's one status line: a refusal's word, the game's window, a fight, no pad row */
        private fun status(c: Canvas, s: State, r: RectF) {
            val word = when {
                s.noRow -> "No pad row"
                s.win -> "Game window open"
                else -> MacrosModel.noteWord(s.note) ?: if (s.combat) "In combat" else null
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
                    if (d != null && d == h.first) taps(h.second)
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
