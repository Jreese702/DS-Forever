package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock

/**
 * THE THEME MAKER (beautify 9.1 / 9.2; the user, 2026-11-17: "lets add a custom theme maker where people can choose their pallets with an
 * oled toggle, and then have an extra button to show the premade themes"). A sub-page off Settings > General > Theme, drawn in the tab
 * body under a 64 px strip (the back chip, touch only, and the title); its one red cloth button top-right opens the PREMADE THEMES
 * ([ThemeGallery]) as a second step of the same page. B: the gallery back to the maker, the maker back to its tile.
 *
 *  - LEFT 460: the live mini Home in the DRAFT theme (core's PreviewStage.renderWith through [homeRender], at most once per 120 ms of
 *    input; the drawn mini room until the game's first document) and the room strip ([ThemeGallery.miniRoom]).
 *  - RIGHT 740: one row a pick, a 140 px label then the swatches (52 px at a 60 px pitch), each swatch showing what it changes:
 *    Accent (a disc), Metal (a frame in that metal), Ground (the stone tinted, its real dark value in the middle), Shade (Deep / Mid / Lift,
 *    gone while OLED is on: no dead stops), Text ("Aa" on the draft's raised plate), OLED (On / Off), Art (Low / Mid / High). The pick is a
 *    10 px notch in the draft's metal under the swatch, never a ring (a ring round a gold swatch would read as the gold outline cursor:
 *    the cursor-look ruling). A row the contrast guard had to move carries the drawn half-filled contrast sign beside its label.
 *  - USE (red cloth, under the rows): the ONE write: the custom slot saved, then the custom theme in use ([Theme.saveCustom]).
 *
 * The draft lives for the session (B / L / R keep it, nothing is saved); the maker starts from the theme in use (a premade's own picks),
 * so "start from a premade" is: pick it in the gallery, then edit. Controller-first: D-pad between rows and across swatches, A picks;
 * Up from Accent reaches Premade themes, Down from the last row reaches Use. The page lands on the picked Accent swatch.
 *
 * Core's SubPage adapter calls [open], [draw], [move], [press], [back], [tap] and [cursorRect]; this class holds no View.
 */
class ThemeMakerPage {

    enum class Row(val label: String) { ACCENT("Accent"), METAL("Metal"), GROUND("Ground"), SHADE("Shade"), TEXT("Text"), OLED("OLED"), ART("Art") }

    /** a stop: a row's swatch / choice [i], or the two buttons */
    sealed class Stop {
        data class Pick(val row: Row, val i: Int) : Stop()
        object Premade : Stop()
        object Use : Stop()
        data class Tile(val i: Int) : Stop()
    }

    /** the gallery step is open */
    var gallery = false
        private set
    var cursor: Stop = Stop.Pick(Row.ACCENT, 0)
        private set
    /** the page's size (the tab body), set by [draw] */
    private var w = 1240f
    private var h = 760f

    /** the host's repaint (now) and its repaint after a delay (the throttled preview) */
    var onChange: () -> Unit = {}
    var later: (Long) -> Unit = {}
    /** the back chip was tapped on the maker: the host closes the page (as B does) */
    var onClose: () -> Unit = {}

    val title: String get() = if (gallery) "Premade themes" else "Theme"

    // ---------------------------------------------------------------- the draft (per session)

    val draft: ThemeMaker.Picks get() = state.draft ?: ThemeMaker.startOf(Theme.cur)
    val result: ThemeMaker.Result get() = state.result(draft)

    /** lands: the maker (never the gallery), the draft kept unless the theme in use changed since it was made, the cursor on the picked Accent */
    fun open() {
        if (state.draft == null || state.draftFor !== Theme.cur) { state.draft = ThemeMaker.startOf(Theme.cur); state.draftFor = Theme.cur }
        gallery = false
        cursor = Stop.Pick(Row.ACCENT, picked(Row.ACCENT).coerceAtLeast(0))
    }

    // ---------------------------------------------------------------- geometry

    private val rowTop = 76f
    private val rowH = 88f
    private val labelX = 490f
    private val swX = 624f
    private val sw = 52f
    private val pitch = 60f
    private val choiceW = 180f
    private val choiceH = 64f

    fun rows(): List<Row> = Row.values().filter { it != Row.SHADE || !draft.oled }

    private fun count(r: Row) = when (r) {
        Row.ACCENT -> ThemeMaker.ACCENTS.size; Row.METAL -> ThemeMaker.METALS.size; Row.GROUND -> ThemeMaker.TINTS.size
        Row.SHADE -> ThemeMaker.SHADES.size; Row.TEXT -> ThemeMaker.TEXTS.size; Row.OLED -> 2; Row.ART -> ThemeMaker.Art.values().size
    }
    private fun isChoice(r: Row) = r == Row.SHADE || r == Row.OLED || r == Row.ART

    private fun rowY(r: Row): Float = rowTop + rows().indexOf(r) * rowH

    fun stopRect(s: Stop): RectF? = when (s) {
        is Stop.Pick -> {
            if (s.row !in rows()) null else {
                val y = rowY(s.row)
                if (isChoice(s.row)) RectF(swX + s.i * (choiceW + 12f), y + (rowH - choiceH) / 2f - 4f, swX + s.i * (choiceW + 12f) + choiceW,
                    y + (rowH - choiceH) / 2f - 4f + choiceH)
                else RectF(swX + s.i * pitch, y + 12f, swX + s.i * pitch + sw, y + 12f + sw)
            }
        }
        Stop.Premade -> RectF(w - 16f - 300f, 6f, w - 16f, 60f)
        Stop.Use -> { val y = rowTop + rows().size * rowH + 8f; RectF(w - 16f - 260f, y, w - 16f, y + 70f) }
        is Stop.Tile -> ThemeGallery.tile(s.i, w, rowTop)
    }

    /** the back chip (touch only: B does the same) */
    val backChip get() = RectF(16f, 8f, 72f, 56f)
    val homeBox get() = RectF(26f, rowTop + 4f, 466f, rowTop + 252f)
    val stripBox get() = RectF(26f, rowTop + 266f, 466f, rowTop + 416f)

    fun cursorRect(): RectF? = stopRect(cursor)

    /** every pad stop on the page now (the back chip never is one) */
    fun stops(): List<Stop> = if (gallery) Theme.all.indices.map { Stop.Tile(it) } else {
        val out = ArrayList<Stop>()
        out.add(Stop.Premade)
        for (r in rows()) for (i in 0 until count(r)) out.add(Stop.Pick(r, i))
        out.add(Stop.Use)
        out
    }

    // ---------------------------------------------------------------- the picks

    /** the swatch / choice of [r] the draft holds, -1 = none (a value between swatches) */
    fun picked(r: Row): Int {
        val p = draft
        return when (r) {
            Row.ACCENT -> ThemeMaker.ACCENTS.indexOfFirst { it.colour == p.accent }
            Row.METAL -> ThemeMaker.METALS.indexOfFirst { it.id == p.metal }
            Row.GROUND -> ThemeMaker.TINTS.indexOfFirst { it.hue == p.tintHue && it.sat == p.tintSat }
            Row.SHADE -> ThemeMaker.SHADES.indexOfFirst { it.l == p.tintL }
            Row.TEXT -> ThemeMaker.TEXTS.indexOfFirst { it.colour == p.text }
            Row.OLED -> if (p.oled) 0 else 1
            Row.ART -> p.art.ordinal
        }
    }

    // ---------------------------------------------------------------- the pad

    /** the D-pad: [dx] / [dy] one step; false = nothing there (the host may hand it on) */
    fun move(dx: Int, dy: Int): Boolean {
        val next = step(cursor, dx, dy) ?: return false
        if (next == cursor) return false
        cursor = next; onChange(); return true
    }

    private fun step(s: Stop, dx: Int, dy: Int): Stop? {
        if (gallery) {
            val n = Theme.all.size
            val i = (s as? Stop.Tile)?.i ?: 0
            val j = when {
                dx != 0 -> i + dx
                dy > 0 -> if (i + ThemeGallery.COLS < n) i + ThemeGallery.COLS else if (i / ThemeGallery.COLS < (n - 1) / ThemeGallery.COLS) n - 1 else i
                dy < 0 -> i - ThemeGallery.COLS
                else -> i
            }
            return if (j in 0 until n) Stop.Tile(j) else null
        }
        val rs = rows()
        val cx = stopRect(s)?.centerX() ?: 0f
        fun nearest(r: Row): Stop {
            var best = 0; var bd = Float.MAX_VALUE
            for (i in 0 until count(r)) { val d = Math.abs((stopRect(Stop.Pick(r, i))?.centerX() ?: 0f) - cx); if (d < bd) { bd = d; best = i } }
            return Stop.Pick(r, best)
        }
        return when (s) {
            Stop.Premade -> if (dy > 0) nearest(rs.first()) else null
            Stop.Use -> if (dy < 0) nearest(rs.last()) else null
            is Stop.Pick -> {
                val k = rs.indexOf(s.row)
                when {
                    dx != 0 -> Stop.Pick(s.row, s.i + dx).takeIf { s.i + dx in 0 until count(s.row) }
                    dy < 0 -> if (k <= 0) Stop.Premade else nearest(rs[k - 1])
                    dy > 0 -> if (k >= rs.size - 1) Stop.Use else nearest(rs[k + 1])
                    else -> null
                }
            }
            is Stop.Tile -> null
        }
    }

    /** A on the cursor's stop */
    fun press(): Boolean = act(cursor)

    private fun act(s: Stop): Boolean {
        when (s) {
            Stop.Premade -> { gallery = true; cursor = Stop.Tile(Theme.all.indexOf(Theme.cur).coerceAtLeast(0)) }
            Stop.Use -> { Theme.saveCustom(draft); state.draftFor = Theme.cur }
            is Stop.Tile -> {
                val t = Theme.all.getOrNull(s.i) ?: return false
                Theme.use(t.id)
                // the maker starts from the theme in use: a premade picked here is where the next edit begins
                state.draft = ThemeMaker.startOf(Theme.cur); state.draftFor = Theme.cur
            }
            is Stop.Pick -> {
                val p = draft
                state.draft = when (s.row) {
                    Row.ACCENT -> p.copy(accent = ThemeMaker.ACCENTS[s.i].colour)
                    Row.METAL -> p.copy(metal = ThemeMaker.METALS[s.i].id)
                    Row.GROUND -> p.copy(tintHue = ThemeMaker.TINTS[s.i].hue, tintSat = ThemeMaker.TINTS[s.i].sat)
                    Row.SHADE -> p.copy(tintL = ThemeMaker.SHADES[s.i].l)
                    Row.TEXT -> p.copy(text = ThemeMaker.TEXTS[s.i].colour)
                    Row.OLED -> p.copy(oled = s.i == 0)
                    Row.ART -> p.copy(art = ThemeMaker.Art.values()[s.i])
                }
            }
        }
        onChange()
        return true
    }

    /** B: the gallery back to the maker (the cursor on its button); false on the maker = the host closes the page (the draft is kept) */
    fun back(): Boolean {
        if (!gallery) return false
        gallery = false; cursor = Stop.Premade; onChange()
        return true
    }

    /** a finger: the back chip, then any stop (a tap is A on it: rooms the same on every route) */
    fun tap(x: Float, y: Float): Boolean {
        if (backChip.contains(x, y)) { if (!back()) onClose(); return true }
        val s = stops().firstOrNull { stopRect(it)?.contains(x, y) == true } ?: return false
        cursor = s
        return act(s)
    }

    // ---------------------------------------------------------------- drawing

    private val pF = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pS = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pLabel = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pTitle = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pAa = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val pBmp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val path = Path()

    /** the page over [pw] x [ph] (the tab body; the host has drawn its ground) */
    fun draw(c: Canvas, pw: Float, ph: Float) {
        w = pw; h = ph
        UiKit.asBody(pLabel); UiKit.asBody(pTitle); UiKit.asBody(pAa)
        strip(c)
        if (gallery) {
            ThemeGallery.draw(c, w, rowTop, Theme.all, (cursor as? Stop.Tile)?.i ?: -1)
            return
        }
        UiKit.button(c, stopRect(Stop.Premade)!!, "Premade themes", textSize = 26f)
        val res = result
        preview(c, res.spec)
        for (r in rows()) drawRow(c, r, res)
        UiKit.button(c, stopRect(Stop.Use)!!, "Use", textSize = 30f)
        cursorRect()?.let { cr -> UiKit.focus(c, RectF(cr.left - 6f, cr.top - 6f, cr.right + 6f, cr.bottom + 6f)) }
    }

    /** the 64 px strip: the back chip (the kit's left arrow) and the title */
    private fun strip(c: Canvas) {
        val b = backChip
        val arrow = UiKit.tex("move_arrow_left")
        if (arrow != null) c.drawBitmap(arrow, null, b, pBmp)
        else {
            pS.color = Theme.gold; pS.strokeWidth = 5f; pS.strokeCap = Paint.Cap.ROUND
            path.reset(); path.moveTo(b.right - 14f, b.top + 10f); path.lineTo(b.left + 12f, b.centerY()); path.lineTo(b.right - 14f, b.bottom - 10f)
            c.drawPath(path, pS)
        }
        pTitle.textSize = 30f; pTitle.color = Theme.gold
        c.drawText(Tr.t(title), b.right + 14f, 44f, pTitle)
        pF.style = Paint.Style.FILL; pF.color = Theme.bronzeDim
        c.drawRect(16f, 64f, w - 16f, 65.5f, pF)
    }

    // ---- the live preview (left column)

    private var homeBmp: Bitmap? = null
    private var homeFor: ThemeSpec? = null
    /** the last spec a render was tried for (a failed one is not tried again every frame) */
    private var triedFor: ThemeSpec? = null
    private var renderedAt = -THROTTLE_MS
    private var pending = false

    private fun preview(c: Canvas, spec: ThemeSpec) {
        val hb = homeBox
        val hook = homeRender
        var drawn = false
        if (hook != null) {
            val now = SystemClock.uptimeMillis()
            if (homeFor != spec && triedFor != spec) {
                val wait = THROTTLE_MS - (now - renderedAt)
                if (wait <= 0L || homeBmp == null) {
                    // one render at most every 120 ms of input; the picture before it stays up meanwhile
                    val b = homeBmp ?: Bitmap.createBitmap(hb.width().toInt(), hb.height().toInt(), Bitmap.Config.ARGB_8888).also { homeBmp = it }
                    b.eraseColor(0)
                    val ok = runCatching { hook(spec, b) }.getOrDefault(false)
                    homeFor = if (ok) spec else null
                    triedFor = spec
                    renderedAt = now; pending = false
                } else if (!pending) { pending = true; later(wait) }
            }
            homeBmp?.takeIf { homeFor != null }?.let {
                val save = c.save(); path.reset(); path.addRoundRect(hb, 10f, 10f, Path.Direction.CW); c.clipPath(path)
                c.drawBitmap(it, null, hb, pBmp); c.restoreToCount(save); drawn = true
            }
        }
        if (!drawn) ThemeGallery.miniRoom(c, hb, spec)
        frame(c, hb)
        ThemeGallery.miniRoom(c, stripBox, spec)
        frame(c, stripBox)
    }

    private fun frame(c: Canvas, r: RectF) {
        pS.color = Color.argb(200, 0, 0, 0); pS.strokeWidth = 2f
        c.drawRoundRect(r, 10f, 10f, pS)
        ThemeGallery.edge(c, RectF(r.left - 6f, r.top - 6f, r.right + 6f, r.bottom + 6f), false)
    }

    // ---- the rows (right column)

    private fun drawRow(c: Canvas, r: Row, res: ThemeMaker.Result) {
        val y = rowY(r)
        pLabel.textSize = 25f; pLabel.color = Theme.text
        c.drawText(Tr.t(r.label), labelX, y + rowH / 2f + 4f, pLabel)
        if ((r == Row.ACCENT && res.accentMoved) || (r == Row.TEXT && res.textMoved)) autoMark(c, swX - 22f, y + 38f)
        val pick = picked(r)
        val spec = res.spec
        when (r) {
            Row.SHADE -> words(c, r, ThemeMaker.SHADES.map { it.name }, pick)
            Row.OLED -> words(c, r, listOf("On", "Off"), pick)
            Row.ART -> words(c, r, listOf("Low", "Mid", "High"), pick)
            else -> for (i in 0 until count(r)) {
                val b = stopRect(Stop.Pick(r, i)) ?: continue
                when (r) {
                    Row.ACCENT -> disc(c, b, ThemeMaker.ACCENTS[i].colour)
                    Row.METAL -> metalChip(c, b, ThemeMaker.METALS[i], spec)
                    Row.GROUND -> stoneChip(c, b, ThemeMaker.TINTS[i])
                    Row.TEXT -> textChip(c, b, ThemeMaker.TEXTS[i].colour, spec)
                    else -> {}
                }
                if (i == pick) notch(c, b, spec)
            }
        }
    }

    /** a choice row: the game's red buttons, the picked one lit (Top's band look) */
    private fun words(c: Canvas, r: Row, ws: List<String>, pick: Int) {
        for ((i, wd) in ws.withIndex()) stopRect(Stop.Pick(r, i))?.let { UiKit.choice(c, it, wd, i == pick, textSize = 27f) }
    }

    private fun disc(c: Canvas, b: RectF, col: Int) {
        pF.style = Paint.Style.FILL; pF.color = col
        c.drawCircle(b.centerX(), b.centerY(), b.width() / 2f - 2f, pF)
        pS.color = Color.argb(170, 0, 0, 0); pS.strokeWidth = 2f
        c.drawCircle(b.centerX(), b.centerY(), b.width() / 2f - 2f, pS)
    }

    private fun metalChip(c: Canvas, b: RectF, m: ThemeMaker.MetalPick, draft: ThemeSpec) {
        pF.style = Paint.Style.FILL; pF.color = draft.raised
        c.drawRoundRect(b, 7f, 7f, pF)
        val inner = RectF(b.left + 5f, b.top + 5f, b.right - 5f, b.bottom - 5f)
        ThemeGallery.metalFrame(c, inner, draft.copy(metal = m.metal, edge = m.edge, edgeDim = m.edgeDim), 7f)
        // its edge colour as the small bar the rows' rules use
        pF.color = m.edge
        c.drawRect(b.left + 16f, b.centerY() - 2f, b.right - 16f, b.centerY() + 2f, pF)
    }

    private var rockShader: BitmapShader? = null
    private var rockFrom: Bitmap? = null

    /** the stone tinted at the tint's hue and shown at L 0.18 (exaggerated so the tints read), its real dark value as a 20 px core */
    private fun stoneChip(c: Canvas, b: RectF, t: ThemeMaker.Tint) {
        val save = c.save()
        path.reset(); path.addRoundRect(b, 7f, 7f, Path.Direction.CW); c.clipPath(path)
        val show = ThemeMaker.hsl(t.hue, t.sat, 0.18f)
        pF.style = Paint.Style.FILL; pF.color = show
        c.drawRect(b, pF)
        val rock = UiKit.tex("ground_rock")
        if (rock != null) {
            if (rockFrom !== rock) { rockFrom = rock; rockShader = BitmapShader(rock, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
            // the rock's light (grey, about a quarter of white) times the tint's colour, lifted so the texture sits at about L 0.18 in
            // the tint's own colour
            val r = Color.red(show) / 255f * 4.2f; val g = Color.green(show) / 255f * 4.2f; val bl = Color.blue(show) / 255f * 4.2f
            val cm = ColorMatrix().apply { setSaturation(0f) }
            cm.postConcat(ColorMatrix(floatArrayOf(r, 0f, 0f, 0f, 0f, 0f, g, 0f, 0f, 0f, 0f, 0f, bl, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
            val p = Paint(Paint.FILTER_BITMAP_FLAG).apply { shader = rockShader; colorFilter = ColorMatrixColorFilter(cm) }
            c.drawRect(b, p)
        }
        // the real ground at the draft's shade (OLED: its raised plate, the ground being black)
        val d = draft
        pF.color = if (d.oled) ThemeMaker.hsl(t.hue, t.sat, ThemeMaker.OLED_RAISED_L) else ThemeMaker.hsl(t.hue, t.sat, d.tintL)
        c.drawRoundRect(RectF(b.centerX() - 10f, b.centerY() - 10f, b.centerX() + 10f, b.centerY() + 10f), 3f, 3f, pF)
        c.restoreToCount(save)
        pS.color = Color.argb(170, 0, 0, 0); pS.strokeWidth = 2f
        c.drawRoundRect(b, 7f, 7f, pS)
    }

    /** "Aa" in the colour on the draft's raised plate: a sample, not a word */
    private fun textChip(c: Canvas, b: RectF, col: Int, draft: ThemeSpec) {
        pF.style = Paint.Style.FILL; pF.color = draft.raised
        c.drawRoundRect(b, 7f, 7f, pF)
        pAa.textSize = 30f; pAa.color = col
        c.drawText("Aa", b.centerX(), b.centerY() + 11f, pAa)
        pS.color = draft.edgeDim; pS.strokeWidth = 2f
        c.drawRoundRect(b, 7f, 7f, pS)
    }

    /** the pick: a 10 px down-pointing notch under the swatch in the draft's metal (the tab notch's shape; never a ring) */
    private fun notch(c: Canvas, b: RectF, draft: ThemeSpec) {
        val cx = b.centerX(); val top = b.bottom + 5f
        path.reset(); path.moveTo(cx - 10f, top); path.lineTo(cx + 10f, top); path.lineTo(cx, top + 10f); path.close()
        pF.style = Paint.Style.FILL; pF.color = draft.edge
        c.drawPath(path, pF)
    }

    /** the guard moved this row's colour: a drawn half-filled circle (the contrast sign), 20 px, in the dim words' colour */
    private fun autoMark(c: Canvas, cx: Float, cy: Float) {
        val r = 10f
        pS.color = Theme.dim; pS.strokeWidth = 2f
        c.drawCircle(cx, cy, r, pS)
        pF.style = Paint.Style.FILL; pF.color = Theme.dim
        c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), 90f, 180f, true, pF)
    }

    companion object {
        const val THROTTLE_MS = 120L

        /**
         * the live mini Home in a theme: core's `PreviewStage.renderWith(spec, look, scale, into)` (wired by the integrator); false / null =
         * nothing to show yet (before the game's first document), and the drawn mini room stands in
         */
        @Volatile var homeRender: ((ThemeSpec, Bitmap) -> Boolean)? = null

        /** the session's draft: kept across B / L / R, never saved (only Use writes) */
        private class State {
            var draft: ThemeMaker.Picks? = null
            var draftFor: ThemeSpec? = null
            private var resFor: ThemeMaker.Picks? = null
            private var res: ThemeMaker.Result? = null
            fun result(p: ThemeMaker.Picks): ThemeMaker.Result {
                if (resFor != p || res == null) { res = ThemeMaker.derive(p); resFor = p }
                return res!!
            }
        }
        private val state = State()

        /** a fresh session (the off-device tests; a process start) */
        fun forget() { state.draft = null; state.draftFor = null }
    }
}
