package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View

/**
 * THE TOP-SCREEN LAYOUT EDITOR'S PAGE (stage 1; the user, 2026-11-16: "we need to make a way to basically edit the UI locations on the
 * top screen from our UI menu"). Opened from Settings > Top screen > Layout > Edit layout, over the Settings room (as SafePath's review
 * and the report page are), the bar left below it. The pure part is [LayoutModel]; the add-on's part is Hub hub55 / TapPad pad22.
 *
 *   A 16:9 MINIATURE of the top screen: every piece at its rect (a schematic of it; dashed while it is not shown on the top screen), in
 *   Edit Mode's look (every piece highlighted, the selected one gold), the grid when it is on. The TILES beside it, grouped (Your HUD /
 *   Target / Party / Navigation / Bars). The FOOTER: Step, Size, Reset, Snap, Grid, Revert, Copy, Save (an icon and one word each).
 *   THE CONTROLLER (Edit Mode's highlight, then select, then move): the D-pad / stick browse the tiles, A picks; with a piece picked the
 *   D-pad moves it [LayoutModel.Editor.step] px (X: 1 / 10), the stick glides it, Y + D-pad up / down (or Size) sizes it, A drops it. B one
 *   step back (drop the piece), then out to Settings - asking Save / Discard / Cancel when there are unsaved changes, as the game does;
 *   B again (Settings' own) goes Home. LB is not used (no capture hears it). A FINGER: a tile picks, a drag on the miniature moves.
 *   Every move is the latest SPOT the piece should stand at; [LayoutModel.Editor.next] sends one tap at a time (a cell of the row "lay",
 *   or the spot itself on the top screen) - no tap while the row is not on the pad or the game is in a fight.
 *   Hub hub58 (the user, 2026-11-17: "the edit mode should include any UI element that is currently turned on, including the wow UI and
 *   polestar. the polestar ui should be broken into 2, one for the arrow and one for the actual guide."): only the LISTED pieces are
 *   drawn, tiled and hit ([LayoutModel.listed]); Polestar's arrow and guide are two tiles in a Polestar group; the tiles fit the column
 *   (smaller rows when the list is long).
 */
class LayoutView(context: Context) : View(context) {
    var isOpen = false; private set
    /** the page went: [toTabs] = by B (Settings' cursor goes to its tab row, so the next B goes Home) */
    var onClosed: (toTabs: Boolean) -> Unit = {}
    /** the render's harness: nothing is sent */
    var dryRun = false
    val editor = LayoutModel.Editor()

    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dash = DashPathEffect(floatArrayOf(10f, 7f), 0f)

    init {
        visibility = GONE
        isFocusable = false
        LayoutModel.Live.onChange { post { onDoc() } }
        Theme.onChange { postInvalidate() }
    }

    // ---------------------------------------------------------------- open / close
    fun open() {
        editor.reset()
        editor.wantOpen = true
        LayoutModel.Live.doc?.let { editor.onDoc(it) }
        isOpen = true; visibility = VISIBLE
        closingUntil = 0L
        startStick()
        pumpSoon()
        invalidate()
    }

    /** close the page (the add-on's editor closes too: its draft is dropped unless saved) */
    fun close(toTabs: Boolean = false) {
        if (!isOpen) return
        editor.leave()                                                  // (the prompt's Save, if asked, still goes before the close)
        isOpen = false; visibility = GONE
        stopStick()
        closingUntil = SystemClock.uptimeMillis() + CLOSE_WAIT_MS          // the close cell still goes out (the pump keeps running)
        pumpSoon()
        onClosed(toTabs)
    }

    fun onDoc() {
        val d = LayoutModel.Live.doc ?: return
        editor.onDoc(d)
        if (isOpen) invalidate()
    }

    // ---------------------------------------------------------------- the taps (one at a time, LayoutModel.Editor.next)
    private var closingUntil = 0L
    private val pump = Runnable { pumpTick() }
    private fun pumpSoon() { removeCallbacks(pump); postDelayed(pump, PUMP_MS) }
    private fun pumpTick() {
        val now = SystemClock.uptimeMillis()
        if (!isOpen && now > closingUntil) return
        val d = editor.doc
        if (!isOpen && d != null && !d.open) return                       // the close is done
        editor.next(now)?.let { send(it, d) }
        pumpSoon()
    }

    private fun send(s: LayoutModel.Send, d: LayoutModel.Doc?) {
        if (dryRun || d == null) return
        val pad = d.pad ?: return
        when (s) {
            is LayoutModel.Send.Cell -> TapBridge.taps(context, pad, listOf(LayoutModel.ROW to s.col)) { }
            is LayoutModel.Send.Spot -> {
                // 1080-line pixels -> the game's own (the pad's px), the same shape
                val pw = pad.optDouble("pw", 0.0); val ph = pad.optDouble("ph", 0.0)
                if (pw > 0 && ph > 0) TapBridge.tapGame(context, pad, s.x / d.w * pw, s.y / d.h * ph, "layout spot") { }
            }
        }
    }

    // ---------------------------------------------------------------- the controller
    private var yUsed = false

    fun padKey(k: String) {
        if (!isOpen) return
        val e = editor
        val d = e.doc
        when {
            e.prompt -> when (k) {
                "PADDLEFT" -> e.promptSel = (e.promptSel + 2) % 3
                "PADDRIGHT" -> e.promptSel = (e.promptSel + 1) % 3
                "PAD1" -> promptPick(e.promptSel)
                "PAD2" -> e.prompt = false
            }
            e.list -> when (k) {
                "PADDUP" -> e.listSel = (e.listSel - 1).coerceAtLeast(0)
                "PADDDOWN" -> e.listSel = (e.listSel + 1).coerceAtMost(((d?.chars?.size ?: 1) - 1).coerceAtLeast(0))
                "PAD1" -> listPick(e.listSel)
                "PAD2" -> e.list = false
            }
            else -> when (k) {
                "PADDUP", "PADDDOWN", "PADDLEFT", "PADDRIGHT" -> dpad(k)
                "PAD1" -> when (e.focus) {
                    LayoutModel.Focus.MOVING -> e.drop()
                    LayoutModel.Focus.TILES -> e.tilePiece()?.let { e.pick(it.id) }
                    LayoutModel.Focus.FOOTER -> press(LayoutModel.Btn.values()[e.footer])
                }
                "PAD3" -> e.step = if (e.step == 1) 10 else 1
                // Y alone toggles Size; Y held with the D-pad sized the piece already (its release is not a toggle)
                "PAD4" -> { if (yUsed) yUsed = false else if (e.picked != null) e.sizeMode = !e.sizeMode }
                "PAD2" -> back()
            }
        }
        invalidate()
    }

    private fun yHeld(): Boolean = (PadHub.buttons and (1 shl PadInput.SLOT_FACE_TOP)) != 0

    private fun dpad(k: String) {
        val e = editor
        val dx = when (k) { "PADDLEFT" -> -1; "PADDRIGHT" -> 1; else -> 0 }
        val dy = when (k) { "PADDUP" -> -1; "PADDDOWN" -> 1; else -> 0 }
        when (e.focus) {
            LayoutModel.Focus.MOVING -> {
                if (yHeld()) { yUsed = true; if (dy != 0) e.size(if (dy < 0) 1 else -1) } else e.nudge(dx, dy)
            }
            LayoutModel.Focus.TILES -> {
                val n = e.tiles().size
                if (dy < 0) e.tile = (e.tile - 1).coerceAtLeast(0)
                if (dy > 0) { if (e.tile >= n - 1) { e.focus = LayoutModel.Focus.FOOTER } else e.tile += 1 }
            }
            LayoutModel.Focus.FOOTER -> {
                val n = LayoutModel.Btn.values().size
                if (dx != 0) e.footer = (e.footer + dx).coerceIn(0, n - 1)
                if (dy < 0) e.focus = LayoutModel.Focus.TILES
            }
        }
    }

    private fun back() {
        val e = editor
        if (e.focus == LayoutModel.Focus.FOOTER && e.picked == null) { e.focus = LayoutModel.Focus.TILES; return }
        when (e.back()) {
            LayoutModel.Back.CLOSE_PROMPT -> e.prompt = false
            LayoutModel.Back.CLOSE_LIST -> e.list = false
            LayoutModel.Back.DESELECT -> e.drop()
            LayoutModel.Back.ASK -> { e.prompt = true; e.promptSel = 0 }
            LayoutModel.Back.LEAVE -> close(toTabs = true)
        }
    }

    /** the prompt's buttons: Save / Discard / Cancel */
    private fun promptPick(i: Int) {
        val e = editor
        e.prompt = false
        when (i) {
            0 -> { e.command(LayoutModel.C_SAVE); close(toTabs = true) }
            1 -> close(toTabs = true)
        }
    }

    private fun listPick(i: Int) {
        val e = editor
        e.list = false
        if (i in 0 until (e.doc?.chars?.size ?: 0) && i < LayoutModel.CHARS) { e.forget(); e.command(LayoutModel.C_CHAR + i); e.drop() }
    }

    /** a footer button (A on it, or a finger) */
    private fun press(b: LayoutModel.Btn) {
        val e = editor
        val d = e.doc
        when (b) {
            LayoutModel.Btn.STEP -> e.step = if (e.step == 1) 10 else 1
            LayoutModel.Btn.SIZE -> { if (e.picked == null) e.tilePiece()?.let { e.pick(it.id) }; e.sizeMode = !e.sizeMode }
            LayoutModel.Btn.RESET -> {
                val p = e.pickedPiece() ?: e.tilePiece()?.also { e.pick(it.id) }
                if (p != null) { e.forget(); e.pick(p.id); e.command(LayoutModel.C_RESET) }
            }
            LayoutModel.Btn.SNAP -> e.snapOn = !e.snapOn
            LayoutModel.Btn.GRID -> e.gridOn = !e.gridOn
            LayoutModel.Btn.REVERT -> { e.forget(); e.command(LayoutModel.C_REVERT); e.drop() }
            LayoutModel.Btn.COPY -> if (!d?.chars.isNullOrEmpty()) { e.list = true; e.listSel = 0 }
            LayoutModel.Btn.GAME -> if (d?.gset == true) { e.forget(); e.command(LayoutModel.C_RESET_GAME); e.drop() }
            LayoutModel.Btn.SAVE -> e.command(LayoutModel.C_SAVE)
        }
    }

    /** the B disc on the bar touched while the page shows: the page's own B (one step back, the prompt, then out to Settings), as the
     *  pad's B - the house rule "rooms the same on every route" (CompanionHost.swirlTouched, as the Character room's stepBackByTouch) */
    fun backByTouch() { if (isOpen) padKey("PAD2") }

    // ---------------------------------------------------------------- the stick (the left one glides the picked piece)
    private var reader: PadInput? = null
    private var stickX = 0f; private var stickY = 0f
    private var frameAt = 0L
    private var running = false
    private val frame = Runnable { stickFrame() }
    // with no piece picked the stick BROWSES as the D-pad does (the design: "D-pad or stick browses the tiles"): one step a push, then
    // the D-pad's own hold-repeat while it stays pushed (MapRoomKeys.StickSteps, as the World map's quest list)
    private val steps = MapRoomKeys.StickSteps()
    private val browseRepeat = Runnable { browseStep(); postBrowseRepeat(DirectPadKeys.REPEAT_MS) }
    private fun postBrowseRepeat(ms: Long) { removeCallbacks(browseRepeat); postDelayed(browseRepeat, ms) }
    private fun browseStep() {
        val d = steps.dir ?: return
        if (!isOpen || editor.picked != null) return
        val k = when {
            d.first == 0f && d.second < 0f -> "PADDUP"; d.first == 0f && d.second > 0f -> "PADDDOWN"
            d.second == 0f && d.first < 0f -> "PADDLEFT"; d.second == 0f && d.first > 0f -> "PADDRIGHT"
            else -> return                                              // (a diagonal: nothing, as the quest list)
        }
        padKey(k)
    }
    /** a stick vector (x right, y down): glide the picked piece, else browse */
    fun stickVector(x: Float, y: Float) {
        stickX = x; stickY = y
        val push = steps.update(x, y)
        if (editor.picked != null) {
            if (MapRoomKeys.stickMoves(x, y) && !running) { running = true; frameAt = 0L; postOnAnimation(frame) }
        } else if (push) { browseStep(); postBrowseRepeat(DirectPadKeys.REPEAT_FIRST_MS) }
        if (!steps.engaged) removeCallbacks(browseRepeat)
    }
    private fun startStick() {
        if (reader != null || dryRun) return
        reader = PadInput(onPad = { _, _, _, _ -> }, onStatus = { _, _ -> }, onStickVector = { stick, x, y ->
            if (stick != 0) return@PadInput
            stickVector(x, y)
        }).also { it.start() }
    }
    private fun stopStick() {
        reader?.stop(); reader = null; removeCallbacks(frame); running = false
        steps.update(0f, 0f); removeCallbacks(browseRepeat)
    }
    private fun stickFrame() {
        if (!isOpen || editor.picked == null || !MapRoomKeys.stickMoves(stickX, stickY)) { running = false; editor.glideEnd(); return }
        val now = SystemClock.uptimeMillis()
        val dt = if (frameAt == 0L) 1f / 60f else ((now - frameAt) / 1000f).coerceIn(0f, 0.05f)
        frameAt = now
        val m = Math.hypot(stickX.toDouble(), stickY.toDouble()).toFloat()
        val tilt = ((m - MapRoomKeys.STICK_DEAD) / (1f - MapRoomKeys.STICK_DEAD)).coerceIn(0f, 1f)
        editor.glide(stickX / m * tilt, stickY / m * tilt, dt)
        invalidate()
        postOnAnimation(frame)
    }

    // ---------------------------------------------------------------- the page's places
    private val mini = RectF()
    /** hub59: how far the tile column is scrolled (a long list) */
    private var tileScroll = 0f
    private val tileBoxes = ArrayList<Pair<RectF, String>>()
    private val footBoxes = ArrayList<Pair<RectF, LayoutModel.Btn>>()
    private val promptBoxes = ArrayList<RectF>()
    private val listBoxes = ArrayList<RectF>()
    private var k = 1f

    private fun mx(x: Double) = mini.left + (x * k).toFloat()
    private fun my(y: Double) = mini.top + (y * k).toFloat()
    private fun miniRect(r: LayoutModel.Rect) = RectF(mx(r.x), my(r.y), mx(r.right), my(r.bottom))

    override fun onDraw(c: Canvas) {
        tileBoxes.clear(); footBoxes.clear(); promptBoxes.clear(); listBoxes.clear()
        val w = width.toFloat(); val h = height.toFloat()
        val full = RectF(0f, 0f, w, h)
        val inner = UiKit.windowGround(c, full)
        val d = editor.doc
        // the miniature (16:9 of the game's screen) and the tiles beside it
        val mw = MINI_W
        val dw = d?.w ?: 1920.0; val dh = d?.h ?: 1080.0
        val mh = (mw * dh / dw).toFloat()
        mini.set(inner.left + 16f, inner.top + 18f, inner.left + 16f + mw, inner.top + 18f + mh)
        k = (mw / dw).toFloat()
        drawMini(c, d)
        drawTiles(c, d, RectF(mini.right + 22f, mini.top - 6f, inner.right - 6f, mini.bottom))
        drawInfo(c, d, RectF(mini.left, mini.bottom + 14f, inner.right - 6f, mini.bottom + 64f))
        drawFooter(c, d, RectF(mini.left, mini.bottom + 78f, inner.right - 6f, mini.bottom + 154f))
        drawLegend(c, RectF(mini.left, mini.bottom + 172f, inner.right - 6f, mini.bottom + 222f))
        drawStatus(c, d, RectF(mini.left, mini.bottom + 236f, inner.right - 6f, inner.bottom - 8f))
        if (editor.prompt) drawPrompt(c, full)
        if (editor.list) drawList(c, d, full)
        UiKit.windowFrame(c, full, "Layout", UiKit.tex("ricon_settings"), closeB = 1)
    }

    private fun drawMini(c: Canvas, d: LayoutModel.Doc?) {
        // the world: a dark ground, the frame round it
        pFill.color = Color.argb(255, 14, 16, 18); c.drawRect(mini, pFill)
        pFill.color = Color.argb(255, 24, 30, 26); c.drawRect(mini.left, mini.top + mini.height() * 0.55f, mini.right, mini.bottom, pFill)
        if (d == null) return
        c.save(); c.clipRect(mini)
        // the grid (the game's own spacing) and the centre lines
        if (editor.gridOn) {
            val sp = (d.gameGridSp ?: 100.0).coerceAtLeast(10.0)
            pLine.pathEffect = null; pLine.strokeWidth = 1f; pLine.color = Color.argb(70, 200, 200, 200)
            var off = sp
            while (off <= d.w / 2) { for (x in listOf(d.w / 2 + off, d.w / 2 - off)) c.drawLine(mx(x), mini.top, mx(x), mini.bottom, pLine); off += sp }
            off = sp
            while (off <= d.h / 2) { for (y in listOf(d.h / 2 + off, d.h / 2 - off)) c.drawLine(mini.left, my(y), mini.right, my(y), pLine); off += sp }
            pLine.color = Color.argb(130, 220, 220, 220)
            c.drawLine(mx(d.w / 2), mini.top, mx(d.w / 2), mini.bottom, pLine); c.drawLine(mini.left, my(d.h / 2), mini.right, my(d.h / 2), pLine)
        }
        val sel = editor.picked ?: if (editor.focus == LayoutModel.Focus.TILES) editor.tilePiece()?.id else null
        for (p in LayoutModel.listed(d)) {
            val r = miniRect(editor.previewRect(p))
            schematic(c, p, r)
            // Edit Mode's look: every piece highlighted (blue), the selected one gold; dashed while it is not on the top screen this moment
            pLine.strokeWidth = if (p.id == sel) 3f else 2f
            pLine.pathEffect = if (p.vis) null else dash
            pLine.color = if (p.id == sel) EM_SELECTED else EM_HIGHLIGHT
            pFill.color = (if (p.id == sel) EM_SELECTED else EM_HIGHLIGHT) and 0x00FFFFFF or (if (p.id == sel) 0x38000000 else 0x1C000000)
            c.drawRect(r, pFill)
            c.drawRect(r, pLine)
            pLine.pathEffect = null
        }
        c.restore()
        pLine.strokeWidth = 2f; pLine.color = Theme.bronze; c.drawRect(mini, pLine)
        // the fight: the page waits (the add-on takes no spot then)
        if (d.combat) { pFill.color = Color.argb(140, 0, 0, 0); c.drawRect(mini, pFill) }
    }

    /** a piece drawn small inside its rect (a stand-in until the menus' pictures come: the design's item 3) */
    private fun schematic(c: Canvas, p: LayoutModel.Piece, r: RectF) {
        val a = if (p.vis) 255 else 120
        when (p.id) {
            "hud" -> {
                val gap = r.height() * 0.18f
                pFill.color = Color.argb(a, 30, 200, 40); c.drawRoundRect(RectF(r.left, r.top, r.right, r.top + r.height() * 0.5f), 3f, 3f, pFill)
                pFill.color = Color.argb(a, 0, 110, 255); c.drawRoundRect(RectF(r.left, r.top + r.height() * 0.5f + gap, r.right, r.bottom), 3f, 3f, pFill)
            }
            "compass" -> {
                pFill.color = Color.argb((a * 0.7f).toInt(), 0, 0, 0); c.drawRect(r, pFill)
                pLine.pathEffect = null; pLine.strokeWidth = 1.5f; pLine.color = Color.argb(a, 200, 180, 120)
                val n = 12
                for (i in 0..n) { val x = r.left + r.width() * i / n; c.drawLine(x, r.centerY() - r.height() * 0.15f, x, r.centerY() + r.height() * 0.15f, pLine) }
            }
            "target" -> {
                val lv = r.height()
                pFill.color = Color.argb(a, 50, 40, 20); c.drawRect(RectF(r.left, r.top, r.left + lv, r.bottom), pFill)
                pFill.color = Color.argb(a, 210, 30, 30); c.drawRoundRect(RectF(r.left + lv + 2f, r.top + r.height() * 0.35f, r.right, r.bottom), 3f, 3f, pFill)
            }
            "party" -> {
                val rowH = r.height() / 4f
                for (i in 0 until 4) {
                    val t = r.top + i * rowH
                    pFill.color = Color.argb(a, 30, 200, 40); c.drawRect(RectF(r.left + r.width() * 0.25f, t + rowH * 0.35f, r.right - r.width() * 0.1f, t + rowH * 0.7f), pFill)
                }
            }
            "nbar" -> {
                val rr = r.height() * 0.16f
                for (side in listOf(0.25f, 0.75f)) {
                    val cx = r.left + r.width() * side; val cy = r.centerY()
                    for ((ox, oy) in listOf(-1f to 0f, 1f to 0f, 0f to -1f, 0f to 1f)) {
                        pFill.color = Color.argb(a, 70, 60, 45); c.drawCircle(cx + ox * rr * 2.1f, cy + oy * rr * 2.1f, rr, pFill)
                    }
                }
            }
            "chat" -> {
                pFill.color = Color.argb((a * 0.8f).toInt(), 14, 11, 8); c.drawRect(r, pFill)
                pLine.pathEffect = null; pLine.strokeWidth = 2f; pLine.color = Color.argb(a / 2, 220, 210, 190)
                for (i in 1..3) { val y = r.bottom - r.height() * 0.12f * i; c.drawLine(r.left + r.width() * 0.05f, y, r.left + r.width() * (0.9f - 0.15f * i), y, pLine) }
            }
            "psarrow" -> {
                // Polestar's arrow: a gold dart over its distance line
                val cx = r.centerX(); val top = r.top + r.height() * 0.08f; val dh = r.height() * 0.5f
                val path = android.graphics.Path().apply {
                    moveTo(cx, top); lineTo(cx + dh * 0.42f, top + dh); lineTo(cx, top + dh * 0.74f); lineTo(cx - dh * 0.42f, top + dh); close()
                }
                pFill.color = Color.argb(a, 230, 186, 102); c.drawPath(path, pFill)
                pLine.pathEffect = null; pLine.strokeWidth = 2f; pLine.color = Color.argb(a, 220, 210, 190)
                val ly = r.top + r.height() * 0.8f
                c.drawLine(r.left + r.width() * 0.3f, ly, r.right - r.width() * 0.3f, ly, pLine)
            }
            "psguide" -> {
                // Polestar's guide window: a dark panel, its band on top, the step's lines
                pFill.color = Color.argb((a * 0.85f).toInt(), 18, 20, 34); c.drawRect(r, pFill)
                pFill.color = Color.argb(a, 60, 70, 120); c.drawRect(RectF(r.left, r.top, r.right, r.top + r.height() * 0.16f), pFill)
                pLine.pathEffect = null; pLine.strokeWidth = 1.5f; pLine.color = Color.argb(a / 2 + 60, 220, 210, 190)
                for (i in 1..3) { val y = r.top + r.height() * (0.16f + 0.2f * i); c.drawLine(r.left + r.width() * 0.1f, y, r.right - r.width() * (0.1f + 0.12f * i), y, pLine) }
            }
            else -> {
                pFill.color = Color.argb(a / 2, 120, 120, 120); c.drawRect(r, pFill)
                // hub59: a game frame shows its name when its box has room for it
                if (p.game && r.width() > 60f && r.height() > 18f) {
                    val tp = UiKit.bodyPaint(13f, Color.argb(a, 230, 225, 210)).apply { textAlign = Paint.Align.CENTER }
                    c.save(); c.clipRect(r)
                    c.drawText(Tr.t(LayoutModel.labelOf(p.id)), r.centerX(), r.centerY() - (tp.ascent() + tp.descent()) / 2f, tp)
                    c.restore()
                }
            }
        }
    }

    private fun drawTiles(c: Canvas, d: LayoutModel.Doc?, r: RectF) {
        val tiles = editor.tiles()
        var y = r.top
        var group: LayoutModel.Group? = null
        val ph = UiKit.titlePaint(24f, Theme.gold)
        // hub58: the rows fit the column (more pieces listed: Polestar's two, later the game's)
        val groups = tiles.map { LayoutModel.groupOf(it) }.distinct().size
        var th = TILE_H; var gh = GROUP_H
        if (tiles.isNotEmpty() && tiles.size * th + groups * gh > r.height()) {
            gh = GROUP_H_MIN
            th = ((r.height() - groups * gh) / tiles.size).coerceIn(TILE_H_MIN, TILE_H)
        }
        // hub59: still longer than the column (the game's frames too): it scrolls so the highlighted / picked tile shows
        run {
            var yy = 0f; var gg: LayoutModel.Group? = null; var top = 0f
            val want = editor.picked ?: editor.tilePiece()?.id
            for (p in tiles) {
                val g = LayoutModel.groupOf(p)
                if (g != gg) { gg = g; yy += gh }
                if (p.id == want) top = yy
                yy += th
            }
            val total = yy
            if (total <= r.height()) tileScroll = 0f
            else {
                if (top - gh < tileScroll) tileScroll = (top - gh).coerceAtLeast(0f)
                if (top + th > tileScroll + r.height()) tileScroll = top + th - r.height()
                tileScroll = tileScroll.coerceIn(0f, total - r.height())
            }
        }
        y -= tileScroll
        c.save(); c.clipRect(r.left - 8f, r.top, r.right + 8f, r.bottom)
        for ((i, p) in tiles.withIndex()) {
            val g = LayoutModel.groupOf(p)
            if (g != group) {
                group = g
                c.drawText(Tr.t(g.label), r.left + 4f, y + gh - 8f, ph)
                y += gh
            }
            if (y + th < r.top || y > r.bottom) { y += th; continue }
            val box = RectF(r.left, y, r.right, y + th - 6f)
            val chosen = editor.picked == p.id
            UiKit.choice(c, box, "", chosen)
            // the tile's picture (the piece's schematic, small) and its name
            val pic = RectF(box.left + 12f, box.centerY() - 12f, box.left + 58f, box.centerY() + 12f)
            schematic(c, p, pic)
            val tp = UiKit.bodyPaint(24f, if (p.vis) Theme.text else Theme.textDim)
            c.drawText(Tr.t(LayoutModel.labelOf(p.id)), pic.right + 12f, box.centerY() - (tp.ascent() + tp.descent()) / 2f, tp)
            if (!p.atDefault) {
                pFill.color = Theme.gold; c.drawCircle(box.right - 16f, box.centerY(), 5f, pFill)       // moved from its default
            }
            if (editor.focus == LayoutModel.Focus.TILES && i == editor.tile && !editor.prompt && !editor.list) UiKit.focus(c, box)
            if (box.top >= r.top - 1f && box.bottom <= r.bottom + 1f) tileBoxes.add(RectF(box) to p.id)
            y += th
        }
        c.restore()
        if (tiles.isEmpty()) {
            val p = UiKit.bodyPaint(24f, Theme.textDim)
            c.drawText(Tr.t("Waiting for the game"), r.left + 4f, r.top + 30f, p)
        }
    }

    /** the picked piece's line: its name, its place and size, the step */
    private fun drawInfo(c: Canvas, d: LayoutModel.Doc?, r: RectF) {
        val p = editor.pickedPiece() ?: editor.tilePiece() ?: return
        val w = editor.want?.takeIf { editor.wantId == p.id }
        val ax = w?.first ?: p.ax; val ay = w?.second ?: p.ay
        val np = UiKit.bodyPaint(26f, Theme.gold)
        c.drawText(Tr.t(LayoutModel.labelOf(p.id)), r.left + 4f, r.centerY() + 9f, np)
        val vp = UiKit.numberPaint(24f, Theme.text)
        val scale = editor.wantScale.takeIf { editor.picked == p.id } ?: p.s
        val line = "x ${ax.toInt()}   y ${ay.toInt()}   ${scale}%   ${editor.step} px" + (if (editor.sizeMode && editor.picked == p.id) "   ↕" else "")
        c.drawText(line, r.left + 220f, r.centerY() + 9f, vp)
        if (d?.dirty == true) {
            val cp = UiKit.bodyPaint(22f, Theme.gold).apply { textAlign = Paint.Align.RIGHT }
            c.drawText(Tr.t("Unsaved"), r.right - 6f, r.centerY() + 8f, cp)
        }
    }

    private fun drawFooter(c: Canvas, d: LayoutModel.Doc?, r: RectF) {
        val btns = LayoutModel.Btn.values()
        val gap = 10f
        val bw = (r.width() - gap * (btns.size - 1)) / btns.size
        for ((i, b) in btns.withIndex()) {
            val box = RectF(r.left + i * (bw + gap), r.top, r.left + i * (bw + gap) + bw, r.bottom)
            val enabled = when (b) {
                LayoutModel.Btn.COPY -> !d?.chars.isNullOrEmpty()
                LayoutModel.Btn.GAME -> d?.gset == true
                LayoutModel.Btn.SAVE, LayoutModel.Btn.REVERT -> d?.dirty == true
                LayoutModel.Btn.RESET, LayoutModel.Btn.SIZE -> (editor.pickedPiece() ?: editor.tilePiece()) != null
                else -> true
            }
            val lit = when (b) {
                LayoutModel.Btn.SNAP -> editor.snapOn; LayoutModel.Btn.GRID -> editor.gridOn; LayoutModel.Btn.SIZE -> editor.sizeMode
                LayoutModel.Btn.STEP -> editor.step == 10; else -> false
            }
            UiKit.button(c, box, "", if (!enabled) UiKit.Button.DISABLED else if (lit) UiKit.Button.DOWN else UiKit.Button.UP)
            icon(c, b, RectF(box.left + 14f, box.centerY() - 14f, box.left + 42f, box.centerY() + 14f), enabled)
            val word = when (b) { LayoutModel.Btn.STEP -> "${editor.step} px"; else -> b.word }
            val tp = UiKit.bodyPaint(22f, if (enabled) Color.WHITE else Theme.dim).apply { textAlign = Paint.Align.CENTER }
            // (a longer word in a narrower button - nine of them since hub59's Game UI - gets a smaller size, never under the icon)
            val room = box.right - 8f - (box.left + 46f)
            val shown = if (b == LayoutModel.Btn.STEP) word else Tr.t(word)
            while (tp.textSize > 14f && tp.measureText(shown) > room) tp.textSize = tp.textSize - 1f
            c.drawText(if (b == LayoutModel.Btn.STEP) word else Tr.t(word), (box.left + 46f + box.right - 8f) / 2f,
                box.centerY() - (tp.ascent() + tp.descent()) / 2f, tp)
            if (editor.focus == LayoutModel.Focus.FOOTER && i == editor.footer && !editor.prompt && !editor.list) UiKit.focus(c, box)
            if (enabled) footBoxes.add(RectF(box) to b)
        }
    }

    /** a footer button's small picture (drawn: no kit art for these yet) */
    private fun icon(c: Canvas, b: LayoutModel.Btn, r: RectF, on: Boolean) {
        pLine.pathEffect = null; pLine.strokeWidth = 2.5f; pLine.color = if (on) Color.WHITE else Theme.dim
        pFill.color = pLine.color
        val cx = r.centerX(); val cy = r.centerY(); val s = r.width() / 2f
        when (b) {
            LayoutModel.Btn.STEP -> { c.drawLine(cx - s, cy, cx + s, cy, pLine); c.drawLine(cx + s * 0.4f, cy - s * 0.5f, cx + s, cy, pLine); c.drawLine(cx + s * 0.4f, cy + s * 0.5f, cx + s, cy, pLine) }
            LayoutModel.Btn.SIZE -> { c.drawRect(cx - s, cy - s * 0.2f, cx + s * 0.2f, cy + s, pLine); c.drawRect(cx - s * 0.2f, cy - s, cx + s, cy - s * 0.2f + 6f, pLine) }
            LayoutModel.Btn.RESET -> { c.drawArc(RectF(cx - s, cy - s, cx + s, cy + s), 40f, 290f, false, pLine); c.drawCircle(cx + s * 0.8f, cy - s * 0.55f, 3f, pFill) }
            LayoutModel.Btn.SNAP -> { c.drawLine(cx - s, cy + s, cx + s, cy + s, pLine); c.drawRect(cx - s * 0.5f, cy - s * 0.6f, cx + s * 0.5f, cy + s * 0.6f, pLine) }
            LayoutModel.Btn.GRID -> { for (i in -1..1) { c.drawLine(cx + i * s * 0.66f, cy - s, cx + i * s * 0.66f, cy + s, pLine); c.drawLine(cx - s, cy + i * s * 0.66f, cx + s, cy + i * s * 0.66f, pLine) } }
            LayoutModel.Btn.REVERT -> { c.drawLine(cx - s, cy, cx + s, cy, pLine); c.drawLine(cx - s, cy, cx - s * 0.4f, cy - s * 0.5f, pLine); c.drawLine(cx - s, cy, cx - s * 0.4f, cy + s * 0.5f, pLine) }
            LayoutModel.Btn.COPY -> { c.drawRect(cx - s, cy - s * 0.4f, cx + s * 0.4f, cy + s, pLine); c.drawRect(cx - s * 0.4f, cy - s, cx + s, cy + s * 0.4f, pLine) }
            LayoutModel.Btn.GAME -> {
                c.drawArc(RectF(cx - s, cy - s, cx + s, cy + s), 40f, 290f, false, pLine)
                c.drawRect(cx - s * 0.4f, cy - s * 0.3f, cx + s * 0.4f, cy + s * 0.3f, pLine)
            }
            LayoutModel.Btn.SAVE -> { c.drawLine(cx - s, cy, cx - s * 0.2f, cy + s * 0.8f, pLine); c.drawLine(cx - s * 0.2f, cy + s * 0.8f, cx + s, cy - s * 0.8f, pLine) }
        }
    }

    /** the buttons' glyphs and one word each */
    private fun drawLegend(c: Canvas, r: RectF) {
        val items = if (editor.picked != null) listOf(UiKit.okGlyph() to "Drop", UiKit.xGlyph() to "Step", yGlyph() to "Size", UiKit.backGlyph() to "Back")
            else listOf(UiKit.okGlyph() to "Pick", UiKit.xGlyph() to "Step", UiKit.backGlyph() to "Back")
        var x = r.left + 4f
        val tp = UiKit.bodyPaint(23f, Theme.textDim)
        for ((g, word) in items) {
            UiKit.pad(c, g, x + 20f, r.centerY(), 40f)
            c.drawText(Tr.t(word), x + 46f, r.centerY() - (tp.ascent() + tp.descent()) / 2f, tp)
            x += 64f + tp.measureText(Tr.t(word)) + 30f
        }
    }

    private fun yGlyph(): String = when (CastSettings.buttonLayout) {
        CastSettings.ButtonLayout.XBOX -> "ltr_y"
        CastSettings.ButtonLayout.PLAYSTATION -> "shp_triangle"
        else -> "rev_y"
    }

    /** why nothing moves now (terse) */
    private fun drawStatus(c: Canvas, d: LayoutModel.Doc?, r: RectF) {
        val why = LayoutModel.statusOf(d) ?: return
        val p = UiKit.bodyPaint(26f, if (d?.combat == true || (d?.queued ?: 0) > 0) Theme.gold else Theme.textDim)
        c.drawText(Tr.t(why), r.left + 4f, r.top + 30f, p)
    }

    private fun drawPrompt(c: Canvas, full: RectF) {
        pFill.color = Color.argb(170, 0, 0, 0); c.drawRect(full, pFill)
        val box = RectF(full.centerX() - 330f, full.centerY() - 150f, full.centerX() + 330f, full.centerY() + 120f)
        UiKit.pane(c, box)
        val tp = UiKit.titlePaint(32f, Theme.gold).apply { textAlign = Paint.Align.CENTER }
        c.drawText(Tr.t("Unsaved changes"), box.centerX(), box.top + 70f, tp)
        val words = listOf("Save", "Discard", "Cancel")
        val bw = (box.width() - 4 * 24f) / 3f
        for ((i, wd) in words.withIndex()) {
            val b = RectF(box.left + 24f + i * (bw + 24f), box.bottom - 110f, box.left + 24f + i * (bw + 24f) + bw, box.bottom - 30f)
            if (i == editor.promptSel) UiKit.button(c, b, wd, UiKit.okGlyph()) else UiKit.button(c, b, wd)
            if (i == editor.promptSel) UiKit.focus(c, b)
            promptBoxes.add(b)
        }
    }

    private fun drawList(c: Canvas, d: LayoutModel.Doc?, full: RectF) {
        val names = d?.chars ?: emptyList()
        pFill.color = Color.argb(170, 0, 0, 0); c.drawRect(full, pFill)
        val h = 110f + names.size * 70f
        val box = RectF(full.centerX() - 330f, full.centerY() - h / 2f, full.centerX() + 330f, full.centerY() + h / 2f)
        UiKit.pane(c, box)
        val tp = UiKit.titlePaint(30f, Theme.gold).apply { textAlign = Paint.Align.CENTER }
        c.drawText(Tr.t("Copy from"), box.centerX(), box.top + 56f, tp)
        for ((i, n) in names.withIndex()) {
            val row = RectF(box.left + 24f, box.top + 84f + i * 70f, box.right - 24f, box.top + 84f + i * 70f + 60f)
            UiKit.choice(c, row, "", i == editor.listSel)
            // a character's name is the player's own: never Tr.t
            val name = n.substringBefore('-'); val realm = n.substringAfter('-', "")
            val np = UiKit.bodyPaint(26f, Theme.text)
            c.drawText(name, row.left + 20f, row.centerY() + 9f, np)
            if (realm.isNotEmpty()) c.drawText(realm, row.left + 30f + np.measureText(name), row.centerY() + 9f, UiKit.bodyPaint(21f, Theme.textDim))
            if (i == editor.listSel) UiKit.focus(c, row)
            listBoxes.add(row)
        }
    }

    // ---------------------------------------------------------------- touch
    private var dragId: String? = null
    private var downX = 0f; private var downY = 0f
    private var dragAx = 0.0; private var dragAy = 0.0

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!isOpen) return false
        val x = ev.x; val y = ev.y
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y; dragId = null
                if (!editor.prompt && !editor.list && mini.contains(x, y)) {
                    val d = editor.doc
                    // under the finger: the smallest piece there (the HUD inside the chat strip's area is the HUD)
                    val hit = LayoutModel.listed(d).filter { p -> p.movable && miniRect(editor.previewRect(p)).contains(x, y) }
                        .minByOrNull { it.rect.w * it.rect.h }
                    if (hit != null) {
                        if (editor.picked != hit.id) editor.pick(hit.id)
                        val w = editor.want?.takeIf { editor.wantId == hit.id }
                        dragId = hit.id; dragAx = w?.first ?: hit.ax; dragAy = w?.second ?: hit.ay
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> dragId?.let {
                if (k > 0) { editor.dragTo(dragAx + (x - downX) / k, dragAy + (y - downY) / k); invalidate() }
            }
            MotionEvent.ACTION_UP -> {
                val moved = Math.hypot((x - downX).toDouble(), (y - downY).toDouble()) > SLOP
                if (dragId == null && !moved) tap(x, y)
                dragId = null
            }
            MotionEvent.ACTION_CANCEL -> dragId = null
        }
        return true
    }

    private fun tap(x: Float, y: Float) {
        val e = editor
        if (UiKit.windowClose(RectF(0f, 0f, width.toFloat(), height.toFloat())).contains(x, y)) {
            if (e.prompt) e.prompt = false else if (e.doc?.dirty == true) { e.prompt = true; e.promptSel = 0 } else close(toTabs = false)
            invalidate(); return
        }
        if (e.prompt) { promptBoxes.indexOfFirst { it.contains(x, y) }.takeIf { it >= 0 }?.let { promptPick(it) }; invalidate(); return }
        if (e.list) { val i = listBoxes.indexOfFirst { it.contains(x, y) }; if (i >= 0) listPick(i) else e.list = false; invalidate(); return }
        tileBoxes.firstOrNull { it.first.contains(x, y) }?.let { (_, id) -> if (e.picked == id) e.drop() else e.pick(id); invalidate(); return }
        footBoxes.firstOrNull { it.first.contains(x, y) }?.let { (_, b) -> press(b); invalidate(); return }
    }

    companion object {
        const val MINI_W = 872f
        const val TILE_H = 54f
        const val GROUP_H = 30f
        /** the smallest row and group line when the list must fit (hub58) */
        const val TILE_H_MIN = 40f
        const val GROUP_H_MIN = 26f
        const val PUMP_MS = 50L
        const val CLOSE_WAIT_MS = 3000L
        const val SLOP = 18f
        /** Edit Mode's highlight (blue) and selected (gold) looks, as the game's editmode-actionbar kits read */
        val EM_HIGHLIGHT = Color.rgb(64, 158, 255)
        val EM_SELECTED = Color.rgb(255, 209, 0)
    }
}
