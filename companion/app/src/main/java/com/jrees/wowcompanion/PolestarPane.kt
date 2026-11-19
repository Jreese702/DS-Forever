package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * THE POLESTAR TAB, DRAWN ([PolestarTab] is its model and controller; QuestsView owns the room). The Quests room's layout: the list on
 * the left (57 %), the selection's details on the right with its buttons pinned at the bottom; Change guide and Choose your path are
 * the same two panes; Reset's question is the game's dialog over the room. Polestar's own words (titles, steps, goals, pitch, pros,
 * cons, folders) are drawn as Polestar gives them, never through Tr.t; ours go through Tr.t. The zone tile is our world map art
 * (ArtHarvest's worldmap/<uiMap>/base.webp), else a night band.
 */
class PolestarPane(private val invalidate: () -> Unit) {
    /** what a touch reaches: list rows ([rowHits]: a row key, a pick key "p:<key>", a path option "o:<i>", a goal "g:<i>") and the
     *  buttons ([btnHits]: the key they press, PAD1 / PAD2 / PAD3 / PAD4) */
    val rowHits = ArrayList<Pair<RectF, String>>()
    val btnHits = ArrayList<Pair<RectF, String>>()
    /** glyph -> key of the enabled button drawn with it (the press feedback) */
    val glyphHits = HashMap<String, String>()
    private var listScroll = 0f
    private var listContentH = 0f
    private var pickScroll = 0f
    private var pickContentH = 0f
    private var lastView = PolestarTab.View.LIST

    // ---------------------------------------------------------------- paints
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; strokeJoin = Paint.Join.ROUND }
    private val pBmp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pFade = Paint()
    private fun body(size: Float, colour: Int) = TextPaint(UiKit.bodyPaint(size, colour))
    private fun title(size: Float, colour: Int) = TextPaint(UiKit.titlePaint(size, colour))

    /** Polestar's words: ellipsized, never Tr.t */
    private fun theirs(c: Canvas, s: String, x: Float, y: Float, p: TextPaint, w: Float) {
        c.drawText(TextUtils.ellipsize(s, p, max(10f, w), TextUtils.TruncateAt.END).toString(), x, y, p)
    }
    /** our words: Tr.t, ellipsized */
    private fun ours(c: Canvas, s: String, x: Float, y: Float, p: TextPaint, w: Float = 2000f) {
        c.drawText(TextUtils.ellipsize(Tr.t(s), p, max(10f, w), TextUtils.TruncateAt.END).toString(), x, y, p)
    }
    /** a block of Polestar's words, wrapped, at most [lines] lines; returns its height */
    private fun wrap(c: Canvas, s: String, x: Float, y: Float, p: TextPaint, w: Float, lines: Int = 3): Float {
        val l = StaticLayout.Builder.obtain(s, 0, s.length, p, max(10, w.toInt())).setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(2f, 1f).build()
        c.save(); c.translate(x, y); l.draw(c); c.restore()
        return l.height.toFloat()
    }

    // ---------------------------------------------------------------- the zone tile (our art, decoded off the UI thread)
    private val tiles = HashMap<Int, Bitmap?>()
    private val loading = HashSet<Int>()
    private val io = Executors.newSingleThreadExecutor()
    private fun tile(zm: Int): Bitmap? {
        if (zm <= 0) return null
        if (tiles.containsKey(zm)) return tiles[zm]
        if (zm in loading) return null
        loading.add(zm)
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        io.execute {
            val f = File(ArtHarvest.dir("worldmap"), "$zm/base.webp")
            val b = try { if (f.isFile) BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 4 }) else null } catch (_: Throwable) { null }
            main.post { loading.remove(zm); if (tiles.size > 12) tiles.clear(); tiles[zm] = b; invalidate() }
        }
        return null
    }
    /** the zone tile with [name] on it (the picture's middle band; the night band without art) */
    private fun drawTile(c: Canvas, r: RectF, zm: Int, name: String?) {
        val b = tile(zm)
        c.save(); c.clipRect(r)
        if (b != null) {
            val k = max(r.width() / b.width, r.height() / b.height)
            val w = b.width * k; val h = b.height * k
            c.drawBitmap(b, null, RectF(r.centerX() - w / 2f, r.centerY() - h / 2f, r.centerX() + w / 2f, r.centerY() + h / 2f), pBmp)
        } else {
            pFill.shader = LinearGradient(0f, r.top, 0f, r.bottom, Color.rgb(16, 26, 44), Color.rgb(8, 10, 18), Shader.TileMode.CLAMP)
            c.drawRect(r, pFill); pFill.shader = null
            pFill.color = Color.argb(70, 120, 200, 255)
            drawStar(c, r.right - 46f, r.top + 40f, 22f, Color.argb(120, 120, 200, 255))
        }
        pFade.shader = LinearGradient(0f, r.bottom - 64f, 0f, r.bottom, Color.argb(0, 0, 0, 0), Color.argb(200, 0, 0, 0), Shader.TileMode.CLAMP)
        c.drawRect(r.left, r.bottom - 64f, r.right, r.bottom, pFade); pFade.shader = null
        c.restore()
        pStroke.color = Theme.bronzeDim; pStroke.strokeWidth = 1.6f; c.drawRect(r, pStroke)
        name?.let { theirs(c, it, r.left + 16f, r.bottom - 16f, title(34f, Theme.gold), r.width() - 32f) }
    }
    /** Polestar's star (Home's ribbon's): four long points and four short */
    private val starPath = Path()
    private fun drawStar(c: Canvas, cx: Float, cy: Float, rr: Float, colour: Int) {
        starPath.reset()
        for (k in 0 until 8) {
            val a = Math.PI / 4.0 * k - Math.PI / 2.0
            val r = if (k % 2 == 0) rr else rr * 0.36f
            val px = cx + (r * Math.cos(a)).toFloat(); val py = cy + (r * Math.sin(a)).toFloat()
            if (k == 0) starPath.moveTo(px, py) else starPath.lineTo(px, py)
        }
        starPath.close()
        pFill.color = colour; c.drawPath(starPath, pFill)
    }

    // ---------------------------------------------------------------- the marks
    private fun diamond(c: Canvas, cx: Float, cy: Float, r: Float, fill: Boolean, colour: Int) {
        val p = Path().apply { moveTo(cx, cy - r); lineTo(cx + r, cy); lineTo(cx, cy + r); lineTo(cx - r, cy); close() }
        if (fill) { pFill.color = colour; c.drawPath(p, pFill); pStroke.color = Color.argb(200, 0, 0, 0); pStroke.strokeWidth = 1.6f; c.drawPath(p, pStroke) }
        else { pStroke.color = colour; pStroke.strokeWidth = 2.6f; c.drawPath(p, pStroke) }
    }
    private fun tick(c: Canvas, cx: Float, cy: Float, colour: Int) {
        pStroke.color = colour; pStroke.strokeWidth = 3.2f
        c.drawPath(Path().apply { moveTo(cx - 9f, cy); lineTo(cx - 3f, cy + 7f); lineTo(cx + 10f, cy - 8f) }, pStroke)
    }
    /** the skip mark: two small forward chevrons */
    private fun skipMark(c: Canvas, cx: Float, cy: Float, colour: Int) {
        pStroke.color = colour; pStroke.strokeWidth = 2.6f
        for (dx in floatArrayOf(-6f, 4f)) c.drawPath(Path().apply { moveTo(cx + dx - 4f, cy - 8f); lineTo(cx + dx + 4f, cy); lineTo(cx + dx - 4f, cy + 8f) }, pStroke)
    }
    private fun chip(c: Canvas, x: Float, cy: Float, word: String, colour: Int, ourWord: Boolean = true): Float {
        val p = body(20f, colour)
        val s = if (ourWord) Tr.t(word) else word
        val w = p.measureText(s) + 20f
        val r = RectF(x, cy - 15f, x + w, cy + 15f)
        pFill.color = Color.argb(150, 0, 0, 0); c.drawRoundRect(r, 8f, 8f, pFill)
        pStroke.color = Color.argb(160, Color.red(colour), Color.green(colour), Color.blue(colour)); pStroke.strokeWidth = 1.4f
        c.drawRoundRect(r, 8f, 8f, pStroke)
        c.drawText(s, x + 10f, cy - (p.ascent() + p.descent()) / 2f, p)
        return w + 8f
    }

    // ---------------------------------------------------------------- the whole tab
    class Frame(val book: PolestarTab.Book, val cur: PolestarTab.Cursor, val rows: List<PolestarTab.Row>, val picks: List<PolestarTab.PickRow>,
                val fight: Boolean, val glyphs: Boolean, val pressed: String, val waiting: Boolean, val quiet: Boolean = false)

    fun draw(c: Canvas, w: Float, h: Float, top: Float, f: Frame) {
        rowHits.clear(); btnHits.clear(); glyphHits.clear()
        val g = f.book.guide ?: return
        val split = kotlin.math.floor(w * 0.57f)
        if (f.cur.view != lastView) { if (f.cur.view == PolestarTab.View.PICKER || f.cur.view == PolestarTab.View.PATH) pickScroll = 0f; lastView = f.cur.view }
        // under Reset's question the room stays drawn, quiet: no glyphs, nothing to touch but the question's buttons
        val under = if (f.cur.view == PolestarTab.View.CONFIRM) Frame(f.book, f.cur, f.rows, f.picks, f.fight, false, "", f.waiting) else f
        when (f.cur.view) {
            PolestarTab.View.PICKER -> { drawPicks(c, 0f, top, split, h, f); divider(c, split, top, h); drawPickDetail(c, split + 1f, top, w, h, f) }
            PolestarTab.View.PATH -> { drawPath(c, 0f, top, split, h, f); divider(c, split, top, h); drawPathDetail(c, split + 1f, top, w, h, f) }
            else -> { drawList(c, 0f, top, split, h, under, g); divider(c, split, top, h); drawDetail(c, split + 1f, top, w, h, under, g) }
        }
        if (f.cur.view == PolestarTab.View.CONFIRM) { rowHits.clear(); btnHits.clear(); glyphHits.clear(); drawConfirm(c, w, h, top, f) }
    }
    private fun divider(c: Canvas, x: Float, top: Float, h: Float) { pFill.color = Theme.bronzeDim; c.drawRect(x, top, x + 1f, h, pFill) }

    // ---------------------------------------------------------------- the list
    private fun rowH(r: PolestarTab.Row): Float = when (r) {
        PolestarTab.Row.GuideRow -> GUIDE_H
        is PolestarTab.Row.Note -> NOTE_H
        is PolestarTab.Row.DoneFold -> FOLD_H
        is PolestarTab.Row.Step -> STEP_H
        PolestarTab.Row.ResetRow -> STEP_H
    }

    private fun drawList(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, f: Frame, g: PolestarTab.Guide) {
        val rows = f.rows
        val sel = f.cur.sel
        // the selected row in view, with its arrows' gaps - fix/desk-click-switch (the user, 2026-11-19: "when opening it I STILL cant
        // see all of the steps"): only when the selection moved or the list was not dragged, so a finger can scroll the whole guide
        val follow = sel != followSel || !dragged
        if (sel != followSel) { followSel = sel; dragged = false }
        var y = 0f
        for (r in rows) {
            val hh = rowH(r) + (if (r.key == sel) 2 * SEL_GAP else 0f)
            if (follow && r.key == sel && f.cur.view != PolestarTab.View.GOALS) {
                if (y < listScroll) listScroll = y
                if (y + hh > listScroll + (y1 - y0)) listScroll = y + hh - (y1 - y0)
            }
            y += hh
        }
        listContentH = y + 12f
        listScroll = listScroll.coerceIn(0f, max(0f, listContentH - (y1 - y0)))
        c.save(); c.clipRect(x0, y0, x1, y1)
        y = y0 - listScroll
        for ((i, r) in rows.withIndex()) {
            val isSel = r.key == sel
            if (isSel) y += SEL_GAP
            val box = RectF(x0 + 8f, y + 3f, x1 - 8f, y + rowH(r) - 3f)
            if (box.bottom > y0 - SEL_GAP && box.top < y1 + SEL_GAP) {
                if (isSel) {
                    // (the cursor on the tab row: the row is only the selection - one selector)
                    if (f.quiet) UiKit.selectedRow(c, box) else UiKit.focus(c, box)
                    if (f.glyphs && f.cur.view == PolestarTab.View.LIST)
                        UiKit.moveArrowsUpDown(c, box, up = true, down = i < rows.size - 1, size = 22f, width = 34f, around = true, gap = SEL_GAP)
                }
                drawRow(c, r, box, g, f, isSel)
                rowHits.add(RectF(box.left, max(box.top, y0), box.right, min(box.bottom, y1)) to r.key)
            }
            y += rowH(r)
            if (isSel) y += SEL_GAP
        }
        c.restore()
    }

    private fun drawRow(c: Canvas, r: PolestarTab.Row, box: RectF, g: PolestarTab.Guide, f: Frame, sel: Boolean) {
        when (r) {
            PolestarTab.Row.GuideRow -> {
                if (g.none) {
                    drawStar(c, box.left + 34f, box.centerY(), 18f, PS_BLUE)
                    ours(c, PolestarTab.NO_GUIDE, box.left + 66f, box.centerY() + 10f, body(28f, Theme.textDim)); return
                }
                drawStar(c, box.left + 34f, box.top + 38f, 18f, PS_BLUE)
                theirs(c, PolestarTab.titled(g.title, g.lo, g.hi), box.left + 66f, box.top + 46f, body(28f, Theme.gold), box.width() - 84f)
                val sub = when {
                    g.complete -> Tr.t(PolestarTab.COMPLETE)
                    g.shown != null -> Tr.t(PolestarTab.fill(PolestarTab.STEP_OF, g.shown, g.count))
                    else -> ""
                }
                c.drawText(sub, box.left + 66f, box.top + 80f, body(21f, Theme.textDim))
                val tt = g.total ?: 0; val dn = g.done ?: 0
                if (tt > 0) {
                    val bx0 = box.left + 66f; val bx1 = box.right - 18f; val by = box.bottom - 18f
                    pFill.color = Theme.bronzeDim; c.drawRect(bx0, by, bx1, by + 4f, pFill)
                    pFill.color = PS_BLUE; c.drawRect(bx0, by, bx0 + (bx1 - bx0) * (dn.toFloat() / tt).coerceIn(0f, 1f), by + 4f, pFill)
                    val np = UiKit.numberPaint(20f, Theme.textDim)
                    val s = "$dn / $tt"
                    c.drawText(s, bx1 - np.measureText(s), box.top + 80f, np)
                }
            }
            is PolestarTab.Row.Note -> {
                pFill.color = Theme.gold; c.drawRoundRect(RectF(box.left + 14f, box.top + 14f, box.left + 20f, box.bottom - 14f), 3f, 3f, pFill)
                val words = PolestarTab.noteWords(r.kind, g)
                ours(c, words, box.left + 36f, box.centerY() + 9f, body(26f, Theme.gold), box.width() - 52f)
            }
            is PolestarTab.Row.DoneFold -> {
                UiKit.tex(if (r.open) "list_minus" else "list_plus")?.let { b ->
                    val bw = 26f; val bh = bw * b.height / b.width
                    c.drawBitmap(b, null, RectF(box.left + 22f, box.centerY() - bh / 2f, box.left + 22f + bw, box.centerY() + bh / 2f), pBmp)
                } ?: ours(c, if (r.open) "-" else "+", box.left + 26f, box.centerY() + 9f, body(26f, Theme.gold))
                ours(c, PolestarTab.fill(PolestarTab.DONE_N, r.count), box.left + 66f, box.centerY() + 9f, body(25f, Theme.textDim))
            }
            is PolestarTab.Row.Step -> {
                // (a step not for this character, now listed too, reads as dim as a done one)
                val done = r.status == 'd' || r.status == 's' || r.status == 'n'
                val cx = box.left + 32f; val cy = box.centerY()
                when {
                    r.shown -> diamond(c, cx, cy, 12f, fill = true, colour = PS_BLUE)
                    r.live && g.shown != g.live -> diamond(c, cx, cy, 11f, fill = false, colour = PS_BLUE)
                    r.status == 'd' -> tick(c, cx, cy, Color.argb(150, 143, 196, 122))
                    r.status == 's' || r.status == 'k' -> skipMark(c, cx, cy, Theme.textDim)
                    else -> { pFill.color = Theme.bronzeDim; c.drawCircle(cx, cy, 4f, pFill) }
                }
                val np = UiKit.numberPaint(22f, if (done) Theme.dim else Theme.textDim)
                c.drawText("${r.i}", box.left + 58f, cy + 8f, np)
                val tx = box.left + 106f
                val t = f.book.title(r.i)
                val tp = body(24f, when { r.shown -> PS_BLUE; done -> Theme.dim; else -> Theme.text })
                if (t != null) theirs(c, t, tx, cy + 9f, tp, box.right - 14f - tx)
                else ours(c, PolestarTab.fill(PolestarTab.STEP_N, r.i), tx, cy + 9f, tp, box.right - 14f - tx)
            }
            PolestarTab.Row.ResetRow -> ours(c, PolestarTab.RESET_WORD, box.left + 36f, box.centerY() + 9f, body(24f, Color.rgb(230, 120, 100)))
        }
    }

    // ---------------------------------------------------------------- the details (and the buttons)
    private fun drawDetail(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, f: Frame, g: PolestarTab.Guide) {
        val pad = 22f
        val row = f.rows.firstOrNull { it.key == f.cur.sel }
        // A over X / Y (two rows) where all three are drawn: each keeps its words beside its glyph
        val actTop = y1 - (if (aWord(row, g, f) != null && f.cur.view == PolestarTab.View.LIST && PolestarTab.canXY(row, g)) ACTIONS_2_H else ACTIONS_H)
        c.save(); c.clipRect(x0, y0, x1, actTop)
        var y = y0 + 16f
        val tw = x1 - x0 - pad * 2
        if (row is PolestarTab.Row.Step) {
            val d = f.book.step(row.i)
            ours(c, PolestarTab.fill(PolestarTab.STEP_N, row.i), x0 + pad, y + 24f, body(22f, if (row.shown) PS_BLUE else Theme.dim)); y += 36f
            val t = d?.title ?: f.book.title(row.i)
            if (t != null) y += wrap(c, t, x0 + pad, y, title(32f, Theme.gold), tw, 2) + 10f
            d?.note?.let { y += wrap(c, it, x0 + pad, y, body(20f, Theme.textDim), tw, 3) + 12f }
            if (d == null) { ours(c, PolestarTab.WAITING, x0 + pad, y + 26f, body(21f, Theme.dim)); y += 40f }
            else {
                val tickable = d.tickable
                val inGoals = f.cur.view == PolestarTab.View.GOALS
                if (d.goals.isNotEmpty()) { ours(c, PolestarTab.GOALS_WORD, x0 + pad, y + 26f, title(26f, Theme.gold)); y += 40f }
                for (gl in d.goals) {
                    val gi = tickable.indexOf(gl)
                    val gr = RectF(x0 + pad - 6f, y, x1 - pad + 6f, y + GOAL_H)
                    if (inGoals && gi == f.cur.goal) UiKit.focus(c, gr)
                    if (gl.tickable) UiKit.check(c, gr.left + 26f, gr.centerY(), 30f, gl.done, enabled = true)
                    else { pFill.color = Theme.bronzeDim; c.drawCircle(gr.left + 26f, gr.centerY(), 4f, pFill) }
                    val tp = body(22f, if (gl.done) Theme.dim else Theme.text)
                    val prog = if (gl.cur != null && gl.max != null) " ${gl.cur}/${gl.max}" else ""
                    val words = (gl.text ?: gl.item ?: "—") + prog
                    theirs(c, words, gr.left + 56f, gr.centerY() + 8f, tp, gr.width() - 66f)
                    if (gi >= 0) rowHits.add(RectF(gr) to "g:$gi")
                    y += GOAL_H + 4f
                }
            }
        } else {
            // the guide (the guide row, a notice, the fold, Reset): its tile, levels, progress, the live step
            if (g.none) {
                drawTile(c, RectF(x0 + pad, y, x1 - pad, y + TILE_H), 0, null)
                ours(c, PolestarTab.NO_GUIDE, x0 + pad + 16f, y + TILE_H - 16f, title(34f, Theme.gold)); y += TILE_H + 16f
            } else {
                drawTile(c, RectF(x0 + pad, y, x1 - pad, y + TILE_H), g.zoneMap, g.title)
                y += TILE_H + 18f
                var cx = x0 + pad
                PolestarTab.levels(g.lo, g.hi)?.let { cx += chip(c, cx, y + 16f, it, Theme.gold, ourWord = false) }
                g.gate?.let { cx += chip(c, cx, y + 16f, PolestarTab.fill(PolestarTab.LEVEL_N, it), Color.rgb(255, 120, 90)) }
                if (g.total != null && g.total > 0) cx += chip(c, cx, y + 16f, "${g.done ?: 0} / ${g.total}", PS_BLUE, ourWord = false)
                y += 46f
                g.zone?.let { theirs(c, it, x0 + pad, y + 22f, body(22f, Theme.textDim), tw); y += 34f }
                val live = g.live
                if (live != null) {
                    ours(c, PolestarTab.fill(PolestarTab.STEP_N, live), x0 + pad, y + 24f, body(21f, PS_BLUE)); y += 32f
                    f.book.title(live)?.let { y += wrap(c, it, x0 + pad, y, body(24f, Theme.text), tw, 2) + 8f }
                } else if (g.complete) { ours(c, PolestarTab.COMPLETE, x0 + pad, y + 24f, body(22f, Theme.ok)); y += 34f }
            }
        }
        c.restore()
        drawActions(c, x0, actTop, x1, y1, f, g, row)
    }

    /** the A button's words for [row] (null: no A here) */
    private fun aWord(row: PolestarTab.Row?, g: PolestarTab.Guide, f: Frame): String? = when {
        f.cur.view == PolestarTab.View.GOALS -> {
            val d = (row as? PolestarTab.Row.Step)?.let { f.book.step(it.i) }
            val x = d?.tickable?.getOrNull(f.cur.goal)
            if (x == null) null else if (x.done) PolestarTab.UNTICK_WORD else PolestarTab.TICK_WORD
        }
        else -> when (row) {
            PolestarTab.Row.GuideRow -> PolestarTab.CHANGE_GUIDE
            is PolestarTab.Row.Note -> PolestarTab.noteAction(row.kind, g)
            is PolestarTab.Row.DoneFold -> if (row.open) "Close" else PolestarTab.OPEN_WORD
            is PolestarTab.Row.Step -> PolestarTab.LOOK_WORD
            PolestarTab.Row.ResetRow -> PolestarTab.RESET_WORD
            null -> null
        }
    }
    private fun aFightOnly(row: PolestarTab.Row?, g: PolestarTab.Guide, f: Frame): Boolean = f.cur.view == PolestarTab.View.LIST && when (row) {
        PolestarTab.Row.GuideRow, PolestarTab.Row.ResetRow -> true
        is PolestarTab.Row.Note -> PolestarTab.fightOnly(PolestarTab.noteDo(row.kind, g))
        else -> false
    }

    private fun bar(c: Canvas, x0: Float, actTop: Float, x1: Float, y1: Float) {
        pFill.color = Theme.panel; c.drawRect(x0, actTop, x1, y1, pFill)
        pFill.color = Theme.bronzeDim; c.drawRect(x0, actTop, x1, actTop + 1f, pFill)
    }

    private fun button(c: Canvas, r: RectF, label: String, key: String, enabled: Boolean, glyph: String?, f: Frame) {
        val state = when { !enabled -> UiKit.Button.DISABLED; f.pressed == key -> UiKit.Button.DOWN; else -> UiKit.Button.UP }
        if (glyph != null) UiKit.button(c, r, label, glyph, state) else UiKit.button(c, r, label, state)
        if (glyph != null && enabled) glyphHits[glyph] = key
        btnHits.add(r to key)
    }

    private fun drawActions(c: Canvas, x0: Float, actTop: Float, x1: Float, y1: Float, f: Frame, g: PolestarTab.Guide, row: PolestarTab.Row?) {
        bar(c, x0, actTop, x1, y1)
        val pad = 22f; val gap = 10f
        val bTop = actTop + 12f; val bBot = y1 - 12f
        val glyphs = f.glyphs
        val xy = f.cur.view == PolestarTab.View.LIST && PolestarTab.canXY(row, g)
        val words = aWord(row, g, f)
        val fightGrey = f.fight && aFightOnly(row, g, f)
        val aOn = words != null && !fightGrey && !f.waiting && !(row is PolestarTab.Row.Step && row.shown && f.cur.view == PolestarTab.View.LIST)
        if (words == null && !xy) return
        var top = bTop
        if (words != null) {
            val bot = if (xy) bTop + BTN_H else bBot
            button(c, RectF(x0 + pad, top, x1 - pad, bot), words, "PAD1", aOn, if (glyphs) "rev_a" else null, f)
            top = bot + gap
        }
        if (xy) {
            val bw = ((x1 - x0) - pad * 2 - gap) / 2f
            button(c, RectF(x0 + pad, top, x0 + pad + bw, bBot), PolestarSkip.SKIP_WORD, "PAD3", !f.waiting, if (glyphs) "rev_x" else null, f)
            button(c, RectF(x0 + pad + bw + gap, top, x1 - pad, bBot), PolestarSkip.PREV_WORD, "PAD4", g.undo > 0 && !f.waiting,
                if (glyphs) "rev_y" else null, f)
        }
        if (fightGrey) ours(c, PolestarTab.FIGHT_LINE, x0 + pad, actTop - 12f, body(20f, Color.rgb(255, 90, 70)))
    }

    // ---------------------------------------------------------------- Change guide (D5)
    private fun pickH(r: PolestarTab.PickRow): Float = when (r) {
        is PolestarTab.PickRow.Head -> HEAD_H
        is PolestarTab.PickRow.Folder -> FOLD_H
        else -> PICK_H
    }

    private fun drawPicks(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, f: Frame) {
        val rows = f.picks
        val sel = f.cur.pick
        ours(c, PolestarTab.CHANGE_GUIDE, x0 + 22f, y0 + 40f, title(30f, Theme.gold))
        val top = y0 + 58f
        var y = 0f
        for (r in rows) {
            val hh = pickH(r) + (if (r.key == sel) 2 * SEL_GAP else 0f)
            if (r.key == sel) {
                if (y < pickScroll) pickScroll = y
                if (y + hh > pickScroll + (y1 - top)) pickScroll = y + hh - (y1 - top)
            }
            y += hh
        }
        pickContentH = y + 12f
        pickScroll = pickScroll.coerceIn(0f, max(0f, pickContentH - (y1 - top)))
        if (rows.isEmpty()) { ours(c, PolestarTab.WAITING, x0 + 22f, top + 36f, body(22f, Theme.dim)); return }
        c.save(); c.clipRect(x0, top, x1, y1)
        y = top - pickScroll
        val stops = rows.filter { it.stop }
        for (r in rows) {
            val isSel = r.key == sel
            if (isSel) y += SEL_GAP
            val box = RectF(x0 + 8f, y + 3f, x1 - 8f, y + pickH(r) - 3f)
            if (box.bottom > top - SEL_GAP && box.top < y1 + SEL_GAP) {
                if (isSel) {
                    UiKit.focus(c, box)
                    if (f.glyphs) { val si = stops.indexOf(r)
                        UiKit.moveArrowsUpDown(c, box, up = si > 0, down = si < stops.size - 1, size = 22f, width = 34f, around = true, gap = SEL_GAP) }
                }
                when (r) {
                    is PolestarTab.PickRow.Head -> {
                        ours(c, r.word, box.left + 14f, box.bottom - 12f, body(21f, Theme.dim))
                    }
                    is PolestarTab.PickRow.Folder -> {
                        UiKit.tex(if (r.open) "list_minus" else "list_plus")?.let { b ->
                            val bw = 26f; val bh = bw * b.height / b.width
                            c.drawBitmap(b, null, RectF(box.left + 22f, box.centerY() - bh / 2f, box.left + 22f + bw, box.centerY() + bh / 2f), pBmp)
                        }
                        theirs(c, r.word, box.left + 66f, box.centerY() + 9f, body(25f, Theme.gold), box.width() - 140f)
                        val np = UiKit.numberPaint(20f, Theme.dim); val s = "${r.count}"
                        c.drawText(s, box.right - 18f - np.measureText(s), box.centerY() + 8f, np)
                    }
                    is PolestarTab.PickRow.OpenTab -> {
                        if (r.active) diamond(c, box.left + 32f, box.centerY(), 11f, true, PS_BLUE)
                        val mark = Tr.t(PolestarTab.fill(PolestarTab.TAB_N, r.t))
                        val mp = body(20f, Theme.dim)
                        val mw = mp.measureText(mark)
                        theirs(c, r.tab.title, box.left + 60f, box.centerY() + 9f, body(25f, if (r.active) PS_BLUE else Theme.text), box.width() - 90f - mw)
                        c.drawText(mark, box.right - 16f - mw, box.centerY() + 8f, mp)
                    }
                    is PolestarTab.PickRow.Pick -> {
                        val x = r.g
                        if (x.state == 'd') tick(c, box.left + 32f, box.centerY(), Color.argb(170, 143, 196, 122))
                        var right = box.right - 14f
                        if (x.tab != null) { val m = Tr.t(PolestarTab.fill(PolestarTab.TAB_N, x.tab)); val mp = body(20f, Theme.dim); right -= mp.measureText(m); c.drawText(m, right, box.centerY() + 8f, mp); right -= 12f }
                        if (r.sugg && x.here) { val m = Tr.t(PolestarTab.HERE); val mp = body(20f, PS_BLUE); right -= mp.measureText(m); c.drawText(m, right, box.centerY() + 8f, mp); right -= 12f }
                        theirs(c, PolestarTab.titled(x.title, x.lo, x.hi), box.left + 60f, box.centerY() + 9f,
                            body(25f, if (x.state == 'd') Theme.dim else Theme.text), right - box.left - 66f)
                    }
                }
                if (r.stop) rowHits.add(RectF(box.left, max(box.top, top), box.right, min(box.bottom, y1)) to "p:${r.key}")
            }
            y += pickH(r)
            if (isSel) y += SEL_GAP
        }
        c.restore()
    }

    private fun drawPickDetail(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, f: Frame) {
        val pad = 22f
        val actTop = y1 - ACTIONS_H
        val row = f.picks.firstOrNull { it.key == f.cur.pick }
        var y = y0 + 16f
        c.save(); c.clipRect(x0, y0, x1, actTop)
        var word: String? = null
        when (row) {
            is PolestarTab.PickRow.OpenTab -> {
                drawTile(c, RectF(x0 + pad, y, x1 - pad, y + TILE_H), 0, row.tab.title); y += TILE_H + 18f
                var cx = x0 + pad
                cx += chip(c, cx, y + 16f, PolestarTab.fill(PolestarTab.TAB_N, row.t), PS_BLUE)
                if (row.tab.total != null && row.tab.total > 0) chip(c, cx, y + 16f, "${row.tab.done ?: 0} / ${row.tab.total}", Theme.gold, ourWord = false)
                y += 46f
                row.tab.step?.let { ours(c, PolestarTab.fill(PolestarTab.STEP_OF, it, row.tab.count), x0 + pad, y + 24f, body(22f, Theme.textDim)) }
                word = if (row.active) PolestarTab.CONTINUE_WORD else PolestarTab.CONTINUE_WORD
            }
            is PolestarTab.PickRow.Pick -> {
                val x = row.g
                drawTile(c, RectF(x0 + pad, y, x1 - pad, y + TILE_H), x.zoneMap, x.title); y += TILE_H + 18f
                var cx = x0 + pad
                PolestarTab.levels(x.lo, x.hi)?.let { cx += chip(c, cx, y + 16f, it, Theme.gold, ourWord = false) }
                if (x.group) cx += chip(c, cx, y + 16f, PolestarTab.GROUP, Color.rgb(255, 170, 60))
                x.gate?.let { cx += chip(c, cx, y + 16f, PolestarTab.fill(PolestarTab.LEVEL_N, it), Color.rgb(255, 120, 90)) }
                cx += when (x.state) {
                    'd' -> chip(c, cx, y + 16f, PolestarTab.DONE_WORD, Theme.ok)
                    's' -> chip(c, cx, y + 16f, if (x.done != null && x.total != null) PolestarTab.fill(PolestarTab.STARTED_N, x.done, x.total) else "Started", PS_BLUE)
                    else -> chip(c, cx, y + 16f, PolestarTab.NEW, Theme.textDim)
                }
                if (x.here) chip(c, cx, y + 16f, PolestarTab.HERE, PS_BLUE)
                y += 46f
                x.steps?.let { ours(c, PolestarTab.fill(PolestarTab.STEPS_N, it), x0 + pad, y + 24f, body(22f, Theme.textDim)) }
                word = if (x.tab != null) PolestarTab.CONTINUE_WORD else PolestarTab.LOAD_WORD
            }
            is PolestarTab.PickRow.Folder -> word = if (row.open) "Close" else PolestarTab.OPEN_WORD
            else -> {}
        }
        c.restore()
        bar(c, x0, actTop, x1, y1)
        val fightGrey = f.fight && (row is PolestarTab.PickRow.OpenTab || row is PolestarTab.PickRow.Pick)
        if (word != null) {
            val bw = ((x1 - x0) - pad * 2 - 10f) / 2f
            button(c, RectF(x0 + pad, actTop + 12f, x0 + pad + bw, y1 - 12f), word, "PAD1", !fightGrey && !f.waiting, if (f.glyphs) "rev_a" else null, f)
            button(c, RectF(x0 + pad + bw + 10f, actTop + 12f, x1 - pad, y1 - 12f), "Back", "PAD2", true, if (f.glyphs) UiKit.backGlyph() else null, f)
        }
        if (fightGrey) ours(c, PolestarTab.FIGHT_LINE, x0 + pad, actTop - 12f, body(20f, Color.rgb(255, 90, 70)))
    }

    // ---------------------------------------------------------------- Choose your path
    private fun drawPath(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, f: Frame) {
        val route = f.book.route
        ours(c, PolestarTab.CHOOSE_PATH, x0 + 22f, y0 + 40f, title(30f, Theme.gold))
        val top = y0 + 58f
        if (route == null || route.options.isEmpty()) { ours(c, PolestarTab.WAITING, x0 + 22f, top + 36f, body(22f, Theme.dim)); return }
        route.fromTitle?.let { theirs(c, it, x0 + 22f + title(30f, Theme.gold).measureText(Tr.t(PolestarTab.CHOOSE_PATH)) + 18f, y0 + 38f, body(20f, Theme.dim), x1 - x0 - 300f) }
        var y = top
        for ((i, o) in route.options.withIndex()) {
            val isSel = i == f.cur.path
            if (isSel) y += SEL_GAP
            val box = RectF(x0 + 8f, y + 3f, x1 - 8f, y + PATH_H - 3f)
            if (isSel) {
                UiKit.focus(c, box)
                if (f.glyphs) UiKit.moveArrowsUpDown(c, box, up = i > 0, down = i < route.options.size - 1, size = 22f, width = 34f, around = true, gap = SEL_GAP)
            }
            theirs(c, o.title, box.left + 24f, box.top + 40f, body(27f, if (o.outlevelled) Theme.dim else Theme.gold), box.width() - 48f)
            val parts = listOfNotNull(PolestarTab.levels(o.lo, o.hi), o.seconds?.let { Tr.t(PolestarTab.fill(PolestarTab.MINUTES_N, max(1, (it + 30) / 60))) },
                o.quests?.let { Tr.t(PolestarTab.fill(PolestarTab.QUESTS_N, it)) })
            c.drawText(parts.joinToString(" · "), box.left + 24f, box.top + 74f, body(21f, Theme.textDim))
            var cx = box.left + 24f
            val cy = box.bottom - 22f
            if (o.recommended) cx += chip(c, cx, cy, PolestarTab.RECOMMENDED, PS_BLUE)
            if (o.chosen) cx += chip(c, cx, cy, PolestarTab.CHOSEN, Theme.ok)
            if (o.outlevelled) chip(c, cx, cy, PolestarTab.OUTLEVELLED, Theme.dim)
            rowHits.add(RectF(box) to "o:$i")
            y += PATH_H
            if (isSel) y += SEL_GAP
        }
    }

    private fun drawPathDetail(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, f: Frame) {
        val pad = 22f
        val actTop = y1 - ACTIONS_H
        val o = f.book.route?.options?.getOrNull(f.cur.path)
        c.save(); c.clipRect(x0, y0, x1, actTop)
        var y = y0 + 16f
        val tw = x1 - x0 - pad * 2
        if (o != null) {
            drawTile(c, RectF(x0 + pad, y, x1 - pad, y + TILE_H), o.zoneMap, o.zone ?: o.title); y += TILE_H + 18f
            o.pitch?.let { y += wrap(c, it, x0 + pad, y, body(22f, Theme.text), tw, 4) + 14f }
            for (p in o.pros) { pFill.color = Theme.ok; c.drawText("+", x0 + pad, y + 22f, body(24f, Theme.ok)); y += wrap(c, p, x0 + pad + 26f, y, body(21f, Theme.text), tw - 26f, 2) + 8f }
            for (p in o.cons) { c.drawText("−", x0 + pad, y + 22f, body(24f, Color.rgb(255, 120, 90))); y += wrap(c, p, x0 + pad + 26f, y, body(21f, Theme.textDim), tw - 26f, 2) + 8f }
        }
        c.restore()
        bar(c, x0, actTop, x1, y1)
        val bw = ((x1 - x0) - pad * 2 - 10f) / 2f
        button(c, RectF(x0 + pad, actTop + 12f, x0 + pad + bw, y1 - 12f), PolestarTab.CHOOSE_WORD, "PAD1", o != null && !f.fight && !f.waiting,
            if (f.glyphs) "rev_a" else null, f)
        button(c, RectF(x0 + pad + bw + 10f, actTop + 12f, x1 - pad, y1 - 12f), "Back", "PAD2", true, if (f.glyphs) UiKit.backGlyph() else null, f)
        if (f.fight) ours(c, PolestarTab.FIGHT_LINE, x0 + pad, actTop - 12f, body(20f, Color.rgb(255, 90, 70)))
    }

    // ---------------------------------------------------------------- Reset's question
    private fun drawConfirm(c: Canvas, w: Float, h: Float, top: Float, f: Frame) {
        UiKit.cover(c, RectF(0f, top, w, h))
        val bw = 760f; val bh = 300f
        val r = RectF((w - bw) / 2f, top + (h - top - bh) / 2f, (w + bw) / 2f, top + (h - top + bh) / 2f)
        val inner = UiKit.dialog(c, r)
        val g = f.book.guide
        g?.title?.let { theirs(c, it, inner.left + 24f, inner.top + 46f, title(30f, Theme.gold), inner.width() - 48f) }
        ours(c, PolestarTab.RESET_ASK, inner.left + 24f, inner.top + 96f, body(25f, Theme.text), inner.width() - 48f)
        val bt = inner.bottom - 86f
        val half = (inner.width() - 48f - 12f) / 2f
        button(c, RectF(inner.left + 24f, bt, inner.left + 24f + half, inner.bottom - 16f), PolestarTab.CANCEL, "PAD2", true,
            if (f.glyphs) UiKit.backGlyph() else null, f)
        button(c, RectF(inner.left + 36f + half, bt, inner.right - 24f, inner.bottom - 16f), PolestarTab.RESET_YES, "PAD1", !f.fight && !f.waiting,
            if (f.glyphs) "rev_a" else null, f)
        if (f.fight) ours(c, PolestarTab.FIGHT_LINE, inner.left + 24f, bt - 12f, body(20f, Color.rgb(255, 90, 70)))
    }

    /** a drag on the list scrolls it (the selection no longer pulls it back until the selection moves) */
    fun scrollBy(dy: Float, view: PolestarTab.View) {
        if (view == PolestarTab.View.PICKER || view == PolestarTab.View.PATH) pickScroll += dy else { listScroll += dy; dragged = true }
    }
    private var followSel: String? = null
    private var dragged = false

    companion object {
        val PS_BLUE = Color.rgb(120, 200, 255)
        const val GUIDE_H = 120f
        const val NOTE_H = 70f
        const val FOLD_H = 62f
        const val STEP_H = 66f
        const val HEAD_H = 44f
        const val PICK_H = 70f
        const val PATH_H = 140f
        const val GOAL_H = 52f
        const val SEL_GAP = 28f
        const val TILE_H = 170f
        const val ACTIONS_H = 112f
        const val BTN_H = 82f
        const val ACTIONS_2_H = 12f + BTN_H + 10f + BTN_H + 12f
    }
}
