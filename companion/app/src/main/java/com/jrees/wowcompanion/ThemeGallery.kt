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
     * A ROOM IN [spec] over [r] (review fix 2026-11-17: the drawn wireframe read as a diagram next to the Top-screen tiles' real pictures):
     * a REAL miniature - the Social room's own pieces (the header and its rule, the list and detail panes' art at the theme's pane light
     * under its vignette, two friend rows, a framed class tile, a red button; pictures and bars, no words) drawn by UiKit itself in [spec] ([Theme.preview]:
     * nothing saved, no listener told) into a small picture cached per theme and size, so a gallery of a dozen costs a dozen renders
     * once. Without the kit: [drawnRoom] (strokes in the theme's colours).
     */
    fun miniRoom(c: Canvas, r: RectF, spec: ThemeSpec, strip: Boolean = false) {
        val k = r.height() / 108f
        val pic = picture(spec, r.width().toInt(), r.height().toInt(), strip)
        if (pic == null) { drawnRoom(c, r, spec); return }
        val save = c.save()
        path.reset(); path.addRoundRect(r, 8f * k, 8f * k, Path.Direction.CW)
        c.clipPath(path)
        c.drawBitmap(pic, null, r, pArt)
        c.restoreToCount(save)
    }

    /**
     * the pictures, least recently drawn first, kept under [PICS_BYTES] in all (a gallery well is about 85 KB, the maker's two about 0.7 MB
     * a draft; the 1.0.29 - 1.0.31 low-memory cluster: nothing here grows without a cap)
     */
    private val pics = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Bitmap>): Boolean {
            if (values.sumOf { it.byteCount } <= PICS_BYTES) return false
            val it = entries.iterator()
            while (size > 1 && values.sumOf { b -> b.byteCount } > PICS_BYTES) { it.next(); it.remove() }
            return false
        }
    }
    private const val PICS_BYTES = 3 * 1024 * 1024

    /**
     * the miniature of [spec] at [w] x [h] px, null without the kit's panes (the drawn room stands in); [strip] = the maker's room strip:
     * the Settings page's own pieces instead of Social's (its stone, a framed tile, an On / Off pair, a red button)
     */
    fun picture(spec: ThemeSpec, w: Int, h: Int, strip: Boolean = false): Bitmap? {
        if (w < 8 || h < 8 || UiKit.tex("pane_left") == null) return null
        val key = "${spec.id}/${spec.hashCode()}/${w}x$h/$strip"
        pics[key]?.let { return it }
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val lw = if (strip) STRIP_W else ROOM_W
        val s = w / lw
        c.scale(s, s)
        val ok = runCatching { Theme.preview(spec) { if (strip) realStrip(c, lw, h / s) else realRoom(c, lw, h / s) } }.isSuccess
        if (!ok) return null
        pics[key] = b
        return b
    }

    /** the logical width the miniature is laid out at (its plates and pictures at a size that still reads in a 216 px well) */
    private const val ROOM_W = 360f
    /** the maker's room strip's logical width (the Settings pieces at their own sizes) */
    private const val STRIP_W = 600f

    /**
     * the Social room's pieces over [w] x [h] in the theme in use (inside [Theme.preview]), PICTURES ONLY (review fix 2026-11-17: its
     * names and zones were 8 - 10 px words in a gallery well; the Top-screen tiles the user loves carry pictures, never small print):
     * the header bar and its metal rule with a short accent stroke for the title, the list and detail panes' art (the theme's ground
     * light, tint and vignette), two friend rows (a plate, then the chosen one lit) each with its class picture and two bars where the
     * words go (the name in its class colour, the zone in the dim text), the chosen friend's class in a framed tile, and a red button
     */
    private fun realRoom(c: Canvas, w: Float, h: Float) {
        pF.style = Paint.Style.FILL; pF.color = Theme.ink
        c.drawRect(0f, 0f, w, h, pF)
        val top = 34f
        val split = w * 0.58f
        UiKit.pane(c, RectF(0f, top, split, h))
        UiKit.pane(c, RectF(split, top, w, h), right = true)
        UiKit.paneDivider(c, split, top, h)
        UiKit.header(c, w, "", height = top)
        bar(c, 16f, top / 2f - 5f, 96f, 10f, Theme.gold)
        // the friend rows: a plate, then the chosen one lit as the room lights it
        val rows = listOf("MAGE" to 0.62f, "SHAMAN" to 0.5f)
        val rh = ((h - top - 30f) / 2f).coerceAtMost(58f)
        var y = top + 12f
        for ((i, row) in rows.withIndex()) {
            val r = RectF(10f, y, split - 12f, y + rh)
            if (i == 1) UiKit.selectedRow(c, r, Theme.gold)
            else { pF.color = Theme.raised; if (Theme.cur.oled) pF.alpha = 209; c.drawRect(r, pF); pF.alpha = 255 }
            val ic = rh - 14f
            val ib = RectF(r.left + 10f, r.top + 7f, r.left + 10f + ic, r.top + 7f + ic)
            UiKit.classIcon(row.first)?.let { c.drawBitmap(it, null, ib, pArt) }
            val x0 = ib.right + 12f; val span = r.right - x0 - 12f
            bar(c, x0, r.centerY() - 13f, span * row.second, 11f, Theme.classColourOf(row.first))
            bar(c, x0, r.centerY() + 5f, span * row.second * 0.75f, 8f, Theme.textDim)
            y += rh + 8f
        }
        // the detail: the chosen friend's class in a framed tile, and the room's red button under it
        val bh = 34f
        val ts = minOf(78f, h - top - bh - 40f, (w - split) - 40f)
        val cx = (split + w) / 2f
        val tile = RectF(cx - ts / 2f, top + 12f, cx + ts / 2f, top + 12f + ts)
        pF.color = Theme.raised
        c.drawRoundRect(tile, 8f, 8f, pF)
        UiKit.classIcon("SHAMAN")?.let { c.drawBitmap(it, null, RectF(tile.left + 8f, tile.top + 8f, tile.right - 8f, tile.bottom - 8f), pArt) }
        if (!UiKit.ringBox(c, tile)) metalFrame(c, tile, Theme.cur, 5f)
        val bt = maxOf(tile.bottom + 10f, h - bh - 12f)
        UiKit.button(c, RectF(split + 16f, bt, w - 16f, bt + bh), "")
    }

    /** a rounded bar where words would go (the Top-screen pictures' "sample names as short bars") */
    private fun bar(c: Canvas, x: Float, y: Float, len: Float, th: Float, col: Int) {
        pF.style = Paint.Style.FILL; pF.color = col
        c.drawRoundRect(RectF(x, y, x + len, y + th), th / 2f, th / 2f, pF)
    }

    /** the Settings page's pieces over [w] x [h] in the theme in use: its stone, a framed picture tile, an On / Off pair, a red button */
    private fun realStrip(c: Canvas, w: Float, h: Float) {
        pF.style = Paint.Style.FILL; pF.color = Theme.ink
        c.drawRect(0f, 0f, w, h, pF)
        RoomGround.settings(c, RectF(0f, 0f, w, h))
        val ts = h - 48f
        val tile = RectF(24f, 24f, 24f + ts, 24f + ts)
        pF.color = Theme.raised
        c.drawRoundRect(tile, 10f, 10f, pF)
        UiKit.tex("ricon_chat")?.let { c.drawBitmap(it, null, RectF(tile.left + 16f, tile.top + 16f, tile.right - 16f, tile.bottom - 16f), pArt) }
        if (!UiKit.ringBox(c, tile)) metalFrame(c, tile, Theme.cur, 6f)
        val x0 = tile.right + 28f
        val cw = (w - 24f - x0 - 14f) / 2f
        val ch = (ts - 18f) / 2f
        UiKit.choice(c, RectF(x0, 24f, x0 + cw, 24f + ch), "On", true, textSize = 27f)
        UiKit.choice(c, RectF(x0 + cw + 14f, 24f, w - 24f, 24f + ch), "Off", false, textSize = 27f)
        UiKit.button(c, RectF(x0, tile.bottom - ch, w - 24f, tile.bottom), "Use", textSize = 27f)
    }

    /**
     * the miniature without the kit: [r] in [spec]'s colours, drawn: a header bar with a short accent stroke for its title; two rows
     * (raised, then selected with an accent stroke); a small framed tile in the theme's metal; one red cloth chip
     */
    fun drawnRoom(c: Canvas, r: RectF, spec: ThemeSpec) {
        val k = r.height() / 108f
        val save = c.save()
        path.reset(); path.addRoundRect(r, 8f * k, 8f * k, Path.Direction.CW)
        c.clipPath(path)
        pF.style = Paint.Style.FILL; pF.color = spec.ink
        c.drawRect(r, pF)
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
    // Light 8 (LIGHT8_PLAN.md change 1): the miniatures and metal rings counted on the 8 GB Thors' memory line ("themegallery");
    // registered only (read off the main thread: a read that meets a change says -1 on that line)
    init { MemWatch.source8("themegallery") { pics.values.sumOf { it.byteCount.toLong() } + ringCache.values.sumOf { it?.byteCount?.toLong() ?: 0L } } }
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
    fun forget() { ringCache.clear(); ringRaw = null; ringTried = false; pics.clear() }

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
