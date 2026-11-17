package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * THE PREMADE THEMES (beautify 9.6; the user, 2026-11-17: "... and then have an extra button to show the premade themes"): every theme as
 * a standard 232 x 184 picture tile, five across (the Top-screen tiles' anatomy), its well a DRAWN mini room in that theme's colours
 * ([miniRoom]: no live render, so a dozen of them cost nothing). The one in use carries the kit's tick badge. A on a tile applies it at
 * once (Theme.use) and the cursor stays; it never touches the custom slot (only the maker's Use writes that).
 */
object ThemeGallery {

    // ---------------------------------------------------------------- the mini room

    private val pF = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pS = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val pArt = Paint(Paint.FILTER_BITMAP_FLAG)
    private val path = Path()

    /**
     * A room in [spec]'s colours over [r], drawn (never [Theme.cur]'s): its ground the list pane's art at the theme's pane light under
     * its vignette; a header bar with a short accent stroke for its title; two rows (raised, then selected with an accent stroke); a
     * small framed tile in the theme's metal; one red cloth chip with the button words' stroke. Scales with [r]'s height.
     */
    fun miniRoom(c: Canvas, r: RectF, spec: ThemeSpec) {
        val k = r.height() / 108f
        val save = c.save()
        path.reset(); path.addRoundRect(r, 8f * k, 8f * k, Path.Direction.CW)
        c.clipPath(path)
        pF.style = Paint.Style.FILL; pF.color = spec.ink
        c.drawRect(r, pF)
        val art = UiKit.tex("pane_left")
        if (art != null) {
            pArt.colorFilter = ColorMatrixColorFilter(UiKit.groundMatrix(spec, 1.5f * spec.paneLight))
            c.drawBitmap(art, null, r, pArt)
            UiKit.vignette(c, r, spec)
        }
        // the header bar and its title stroke
        val hb = r.top + 22f * k
        pF.color = spec.panel
        c.drawRect(r.left, r.top, r.right, hb, pF)
        pF.color = spec.edgeDim
        c.drawRect(r.left, hb - 1.5f * k, r.right, hb, pF)
        stroke(c, r.left + 10f * k, r.top + 11f * k, r.left + 10f * k + r.width() * 0.28f, spec.accent, 5f * k)
        // the rows: raised, then selected with the accent stroke; their words in the theme's text colours
        val rl = r.left + 10f * k; val rr = r.left + r.width() * 0.62f
        for (i in 0..1) {
            val top = hb + 9f * k + i * 26f * k
            val row = RectF(rl, top, rr, top + 21f * k)
            pF.color = if (i == 1) spec.selected else spec.raised
            c.drawRoundRect(row, 4f * k, 4f * k, pF)
            if (i == 1) { pF.color = spec.accent; c.drawRect(row.left, row.top, row.left + 3f * k, row.bottom, pF) }
            stroke(c, row.left + 10f * k, row.centerY(), row.left + 10f * k + row.width() * (if (i == 0) 0.55f else 0.42f),
                if (i == 1) spec.text else spec.textDim, 4f * k)
        }
        // a small tile framed in the theme's metal
        val ts = 38f * k
        val tr = RectF(r.right - 12f * k - ts * 1.25f, hb + 9f * k, r.right - 12f * k, hb + 9f * k + ts)
        pF.color = spec.raised
        c.drawRoundRect(tr, 5f * k, 5f * k, pF)
        metalFrame(c, tr, spec, 3.2f * k)
        stroke(c, tr.left + 8f * k, tr.bottom - 9f * k, tr.left + tr.width() * 0.55f, spec.accent, 3.5f * k)
        // the red cloth chip and its words
        val cw = 60f * k; val ch = 22f * k
        val cr = RectF(r.right - 12f * k - cw, r.bottom - 10f * k - ch, r.right - 12f * k, r.bottom - 10f * k)
        cloth(c, cr, k)
        stroke(c, cr.left + cw * 0.25f, cr.centerY(), cr.right - cw * 0.25f, spec.btnLabel, 3.5f * k)
        c.restoreToCount(save)
    }

    private fun stroke(c: Canvas, x0: Float, y: Float, x1: Float, col: Int, w: Float) {
        pS.color = col; pS.strokeWidth = w
        c.drawLine(x0, y, x1, y, pS)
    }

    /** the game's red cloth, drawn (the kit's button plate is the theme-in-use's art; a picture of another theme draws its own) */
    fun cloth(c: Canvas, r: RectF, k: Float) {
        pF.style = Paint.Style.FILL
        pF.shader = android.graphics.LinearGradient(0f, r.top, 0f, r.bottom, Color.rgb(176, 22, 18), Color.rgb(98, 10, 8),
            android.graphics.Shader.TileMode.CLAMP)
        c.drawRoundRect(r, 4f * k, 4f * k, pF)
        pF.shader = null
        pS.color = Color.argb(200, 20, 6, 4); pS.strokeWidth = 1.5f * k
        c.drawRoundRect(r, 4f * k, 4f * k, pS)
    }

    // ---------------------------------------------------------------- the metal, per theme

    /** the kit's ring_square recoloured per metal (64 px), or null without the picture: a per-metal cache, about 16 KB each */
    private val ringCache = HashMap<String, Bitmap?>()
    private var ringRaw: Bitmap? = null
    private var ringTried = false

    private fun ringFor(m: Metal?): Bitmap? {
        val key = m?.toString() ?: "bronze"
        if (ringCache.containsKey(key)) return ringCache[key]
        if (!ringTried) {
            ringTried = true
            val f = ArtHarvest.file("ui/kit/ring_square.png")
            ringRaw = if (f.canRead()) runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull()?.let {
                Bitmap.createScaledBitmap(it, 64, 64, true)
            } else null
        }
        val raw = ringRaw ?: return null.also { ringCache[key] = null }
        val out = UiKit.recolour(raw.copy(Bitmap.Config.ARGB_8888, true), m)
        ringCache[key] = out
        return out
    }

    /** forget the cached rings (the kit came or went) */
    fun forget() { ringCache.clear(); ringRaw = null; ringTried = false }

    /**
     * a frame round [r] in [spec]'s metal: the kit's ring (recoloured for that metal), else the drawn ring (UiKit.ringEdge's three
     * strokes, its taupe through [spec]'s metal instead of the theme in use's)
     */
    fun metalFrame(c: Canvas, r: RectF, spec: ThemeSpec, w: Float) {
        val ring = ringFor(spec.metal)
        if (ring != null) { c.drawBitmap(ring, null, RectF(r.left - w * 0.4f, r.top - w * 0.4f, r.right + w * 0.4f, r.bottom + w * 0.4f), pArt); return }
        fun m(col: Int): Int { val mm = spec.metal ?: return col; val px = intArrayOf(col); mm.apply(px); return px[0] }
        val rad = minOf(r.width(), r.height()) * 0.14f
        pS.style = Paint.Style.STROKE
        pS.strokeWidth = w; pS.color = Color.argb(140, 28, 26, 23); c.drawRoundRect(r, rad, rad, pS)
        pS.strokeWidth = w * 0.72f; pS.color = m(Color.rgb(88, 72, 54)); c.drawRoundRect(r, rad, rad, pS)
        pS.strokeWidth = w * 0.44f; pS.color = m(Color.rgb(112, 91, 68)); c.drawRoundRect(r, rad, rad, pS)
        pS.strokeWidth = w * 0.18f; pS.color = spec.edge; pS.alpha = 150; c.drawRoundRect(r, rad, rad, pS); pS.alpha = 255
    }

    // ---------------------------------------------------------------- the gallery page

    const val COLS = 5

    /** tile [i]'s box on a page [w] wide whose tiles start at [top] (the Top-screen tiles' numbers) */
    fun tile(i: Int, w: Float, top: Float): RectF {
        val tw = GameUiTiles.TILE_W; val th = GameUiTiles.TILE_H; val g = GameUiTiles.GAP
        val total = COLS * tw + (COLS - 1) * g
        val left = maxOf(GameUiTiles.MARGIN, (w - total) / 2f)
        val col = i % COLS; val row = i / COLS
        val l = left + col * (tw + g); val t = top + row * (th + g)
        return RectF(l, t, l + tw, t + th)
    }

    private val pName = Paint(Paint.ANTI_ALIAS_FLAG)

    /** the gallery's tiles over a page [w] wide from [top]; [cursor] = the tile the controller is on (its golden box, drawn here) */
    fun draw(c: Canvas, w: Float, top: Float, list: List<ThemeSpec>, cursor: Int) {
        UiKit.asBody(pName)
        for ((i, t) in list.withIndex()) {
            val r = tile(i, w, top)
            val inUse = t === Theme.cur
            pF.style = Paint.Style.FILL; pF.color = if (inUse) Theme.selected else Theme.raised
            c.drawRoundRect(r, 10f, 10f, pF)
            val well = RectF(r.left + GameUiTiles.WELL_PAD, r.top + GameUiTiles.WELL_PAD, r.right - GameUiTiles.WELL_PAD,
                r.top + GameUiTiles.WELL_PAD + GameUiTiles.WELL_H)
            miniRoom(c, well, t)
            pS.style = Paint.Style.STROKE; pS.color = Color.argb(200, 0, 0, 0); pS.strokeWidth = 2f
            c.drawRoundRect(well, 8f, 8f, pS)
            if (inUse) tick(c, RectF(well.right - GameUiTiles.BADGE - 4f, well.top + 4f, well.right - 4f, well.top + 4f + GameUiTiles.BADGE))
            edge(c, r, inUse)
            pName.textSize = 25f; pName.color = if (inUse) Theme.text else Theme.textDim
            c.drawText(fit(pName, Tr.t(t.name), r.width() - 2f * GameUiTiles.WELL_PAD - 4f), r.left + GameUiTiles.WELL_PAD + 2f,
                well.bottom + 34f, pName)
            if (i == cursor) UiKit.focus(c, RectF(r.left - 4f, r.top - 4f, r.right + 4f, r.bottom + 4f))
        }
    }

    /** the theme in use's mark: the kit's tick on Top's badge disc (check_tick = "in use"), a drawn tick without it */
    private fun tick(c: Canvas, r: RectF) {
        val cx = r.centerX(); val cy = r.centerY(); val rad = r.width() / 2f
        pF.style = Paint.Style.FILL; pF.color = Color.argb(215, 10, 10, 12); c.drawCircle(cx, cy, rad, pF)
        pS.style = Paint.Style.STROKE; pS.strokeWidth = 2f; pS.color = Color.argb(220, 150, 126, 90); c.drawCircle(cx, cy, rad - 1f, pS)
        val t = UiKit.tex("check_tick")
        if (t != null) { c.drawBitmap(t, null, RectF(cx - rad * 0.85f, cy - rad * 0.85f, cx + rad * 0.85f, cy + rad * 0.85f), pArt); return }
        pS.color = Theme.gold; pS.strokeWidth = 3f
        path.reset(); path.moveTo(cx - rad * 0.4f, cy); path.lineTo(cx - rad * 0.1f, cy + rad * 0.32f); path.lineTo(cx + rad * 0.45f, cy - rad * 0.35f)
        c.drawPath(path, pS)
    }

    /** a tile's edge: the kit's ring (the theme in use's metal), lit for the one in use; the drawn ring without it (Top's [edge]) */
    fun edge(c: Canvas, r: RectF, lit: Boolean) {
        if (UiKit.ringBox(c, r, 0.8f, lit = lit)) return
        val w = UiKit.RING_W * 0.8f
        path.reset()
        path.addRoundRect(RectF(r.left + w / 2f, r.top + w / 2f, r.right - w / 2f, r.bottom - w / 2f), 9f, 9f, Path.Direction.CW)
        UiKit.ringEdge(c, path, w, lit = lit)
    }

    fun fit(p: Paint, s: String, maxW: Float): String {
        while (p.measureText(s) > maxW && p.textSize > 18f) p.textSize -= 1f
        if (p.measureText(s) <= maxW) return s
        val n = p.breakText(s, true, (maxW - p.measureText("…")).coerceAtLeast(0f), null)
        return s.substring(0, n).trimEnd() + "…"
    }
}
