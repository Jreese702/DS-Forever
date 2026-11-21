package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader

/**
 * SETTINGS > GENERAL'S TILE PICTURES (the user, 2026-11-17: "I REALLY REALLY love what you did with the top screen settings menu with the
 * pictures and want to do something similar to the other menus"; the beautify design, section 2): Top screen's picture language
 * ([TilePictures]: drawn in a fixed unit space - 216 x 108, the wide Theme tile's 460 x 108 - scaled into the box given, so the band's
 * large picture is the same drawing) for the Look row's three tiles:
 *  - Theme: the theme IN USE - a drawn mini room in its colours and art (its rock ground, header, rows, the red cloth, the ring in its
 *    metal); the Theme band's premade gallery tiles are the same room, each in its own colours, light and metal
 *  - Swap screens: the Thor's two screens, the game (a crop of one of the game's own loading pictures, the kit's scene art) and our Home;
 *    On = they trade places
 *  - Voice typing: the microphone; This device = the Thor under it, Online = a cloud, Off = the microphone struck through
 * Every picture has a drawing without the kit (the kit's pieces are only the scene crop, the red cloth and the ring). Sample data only.
 */
object GeneralPictures {
    const val W = 216f
    const val H = 108f
    /** the wide Theme tile's unit space (its well: two columns and the gap, less the padding) */
    const val WIDE_W = 460f

    /** the choices a picture shows */
    data class Look(val swap: Boolean = false, val voice: Voice.Mode = Voice.Mode.DEVICE)

    private val pF = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pS = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val pB = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    /** [id]'s picture into [r] (its own rounded clip; the canvas left as it was); false = no picture of its own */
    fun draw(c: Canvas, ctx: Context?, id: String, r: RectF, look: Look): Boolean {
        val uw = when (id) { GeneralTiles.THEME -> WIDE_W; GeneralTiles.SWAP, GeneralTiles.VOICE -> W; else -> return false }
        val save = c.save()
        val k = minOf(r.width() / uw, r.height() / H)
        val rad = 8f * k
        path.reset(); path.addRoundRect(r, rad, rad, Path.Direction.CW)
        c.clipPath(path)
        pF.shader = null; pF.color = WORLD; c.drawRect(r, pF)
        // the Theme tile: the real Home miniature in the theme in use (BottomPictures.themeHome), the drawn room until the game's first
        // document ([themeWaiting]: the view pumps the crop queue)
        if (id == GeneralTiles.THEME && ctx != null) {
            val hb = runCatching { BottomPictures.themeHome(r.width().toInt(), r.height().toInt()) }.getOrNull()
            themeWaiting = hb == null
            if (hb != null) {
                c.drawBitmap(hb, null, r, pB)
                c.restoreToCount(save)
                return true
            }
        }
        c.translate(r.centerX() - uw * k / 2f, r.centerY() - H * k / 2f)
        c.scale(k, k)
        runCatching {
            when (id) {
                GeneralTiles.THEME -> theme(c)
                GeneralTiles.SWAP -> swap(c, ctx, look.swap)
                GeneralTiles.VOICE -> voice(c, look.voice)
            }
        }
        c.restoreToCount(save)
        pF.shader = null
        return true
    }

    /** the last Theme tile drawn had no Home crop yet (the drawn mini room stood in): its view pumps BottomPictures' queue */
    @Volatile var themeWaiting = false

    /** the world's stand-in colour (Top screen's wells: the game's world, not a theme token) */
    private val WORLD = Color.rgb(14, 16, 18)

    // ---------------------------------------------------------------- Theme

    /**
     * The theme in use, before the game's first document: a drawn mini room in its colours (the ground, the header, the rows, the cloth
     * and the metal). With a document the tile shows the REAL Home miniature instead (the user's ruling, 2026-11-17; [draw]): a crop of
     * Home's whole width with the panes' header bars whole (the 2026-11-17 review's crop had cut the map's header in half).
     */
    private fun theme(c: Canvas) = miniRoom(c, RectF(0f, 0f, WIDE_W, H), Theme.cur)

    /**
     * A ROOM IN MINIATURE, in [t]'s colours and art (the beautify design 9.6; 2026-11-17 review: "a flat line diagram ... reads as a
     * placeholder skeleton", "Classic Bronze looks the same as OLED Bronze"): what tells the themes apart is drawn as the theme draws it -
     *  - the ground: the kit's rock (the game's window ground) at the theme's own light and saturation (Classic as harvested, a Dark theme dimmed and greyed;
     *    OLED none: its page's black, as its rooms draw it today), a soft vignette at the edges
     *  - the header bar (panel) over a hairline in the theme's edge, the title in the accent, two tabs (the lit one selected)
     *  - two compact rows (raised, then selected with its accent mark), the words' strokes in the text colours; the first row's ONE
     *    red cloth button with its label stroke in btnLabel
     *  - a picture tile framed by the kit's ring_square recoloured with the theme's Metal (a per-theme 64 px copy): the game's scene
     *    art in its well (as Settings' tiles hold pictures), its value stroke in the accent
     * Without the kit: the same room with a flat ground, a drawn cloth and a ring stroked in the theme's edge.
     */
    fun miniRoom(c: Canvas, b: RectF, t: ThemeSpec = Theme.cur, kitCloth: Boolean = t === Theme.cur) {
        val w = b.width(); val h = b.height()
        pF.shader = null; pF.color = t.ink; c.drawRect(b, pF)
        val hh = h * 0.24f
        val body = RectF(b.left, b.top + hh, b.right, b.bottom)
        // the ground: the kit's rock as the theme lights its window grounds (OLED keeps its art now, darkened: themes' 8.1)
        val rock = rockArt()
        if (rock != null && t.stoneLight > 0f) {
            val sh = BitmapShader(rock, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            sh.setLocalMatrix(Matrix().apply { setScale(0.5f, 0.5f); postTranslate(b.left, b.top) })
            pB.shader = sh; pB.colorFilter = groundFilter(t)
            c.drawRect(body, pB)
            pB.shader = null; pB.colorFilter = null
        } else {
            pF.shader = LinearGradient(0f, body.top, 0f, body.bottom, t.field, t.ink, Shader.TileMode.CLAMP)
            c.drawRect(body, pF); pF.shader = null
        }
        // the vignette: the edges toward the ink
        pF.shader = RadialGradient(b.centerX(), b.centerY(), maxOf(w, h) * 0.62f,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, (t.ink and 0x00FFFFFF) or (0x70 shl 24)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(b, pF); pF.shader = null
        // the header, its hairline, the title and two tabs
        pF.color = t.panel; c.drawRect(b.left, b.top, b.right, b.top + hh, pF)
        pF.color = t.edgeDim; c.drawRect(b.left, b.top + hh - 2.5f, b.right, b.top + hh, pF)
        pF.color = t.edge; c.drawRect(b.left, b.top + hh - 1.2f, b.right, b.top + hh, pF)
        stroke(c, b.left + 10f, b.top + hh / 2f, minOf(64f, w * 0.26f), 5f, t.accent)
        val tabW = minOf(40f, w * 0.16f)
        for (i in 0..1) {
            val tl = b.right - 10f - (2 - i) * (tabW + 6f)
            val tr = RectF(tl, b.top + 5f, tl + tabW, b.top + hh - 4f)
            pF.color = if (i == 0) t.selected else t.raised; c.drawRoundRect(tr, 3f, 3f, pF)
            if (i == 0) { pF.color = t.accent; c.drawRect(tr.left + 3f, tr.bottom - 2f, tr.right - 3f, tr.bottom, pF) }
            stroke(c, tr.left + 7f, tr.centerY() - 1f, tr.width() - 14f, 3f, if (i == 0) t.accent else t.textDim)
        }
        // the body: two compact rows on the left (the first with its ONE red cloth button), a picture tile on the right
        val top = body.top + 8f
        val split = b.left + w * 0.6f
        val rowH = (b.bottom - 8f - top - 6f) / 2f
        for (i in 0..1) {
            val rr = RectF(b.left + 10f, top + i * (rowH + 6f), split - 8f, top + i * (rowH + 6f) + rowH)
            pF.color = if (i == 1) t.selected else t.raised; pF.alpha = 215; c.drawRoundRect(rr, 4f, 4f, pF); pF.alpha = 255
            if (i == 1) { pF.color = t.accent; c.drawRect(rr.left, rr.top + 3f, rr.left + 3f, rr.bottom - 3f, pF) }
            val words = if (i == 0) rr.width() * 0.5f else rr.width() * 0.62f
            stroke(c, rr.left + 10f, rr.centerY() - 4f, words * 0.7f, 3.5f, if (i == 1) t.accent else t.text)
            stroke(c, rr.left + 10f, rr.centerY() + 5f, words * 0.45f, 2.5f, t.textDim)
            if (i == 0) {
                // the red cloth: the kit's on the Theme tile ([kitCloth]: its trim is the theme in use's metal); the gallery's drawn alike
                val btn = RectF(rr.right - rr.width() * 0.4f, rr.top + 3f, rr.right - 4f, rr.bottom - 3f)
                if (kitCloth && UiKit.tex("button_up_center") != null) UiKit.button(c, btn, "", UiKit.Button.UP)
                else cloth(c, btn, t)
                stroke(c, btn.centerX() - btn.width() * 0.22f, btn.centerY(), btn.width() * 0.44f, 2.5f, t.btnLabel)
            }
        }
        // a picture tile in the theme's metal: the game's own scene art in its well, its value word's stroke in the accent
        val tile = RectF(split, top, b.right - 10f, b.bottom - 8f)
        pF.color = t.raised; c.drawRoundRect(tile, 4f, 4f, pF)
        val well = RectF(tile.left + 4f, tile.top + 4f, tile.right - 4f, tile.bottom - 13f)
        val save = c.save()
        c.clipRect(well)
        game(c, null, well)
        c.restoreToCount(save)
        stroke(c, tile.left + 7f, tile.bottom - 7f, tile.width() * 0.36f, 2.5f, t.accent)
        val ring = ringArt(t)
        if (ring != null) nine(c, ring, tile, RING_CAP, 7f)
        else { pS.shader = null; pS.color = t.edge; pS.strokeWidth = 2.5f; c.drawRoundRect(RectF(tile.left + 1f, tile.top + 1f, tile.right - 1f, tile.bottom - 1f), 3f, 3f, pS) }
    }

    /** a drawn red cloth: the game's red, darker below, a dark rim and a hairline of [t]'s edge */
    private fun cloth(c: Canvas, r: RectF, t: ThemeSpec) {
        pF.shader = LinearGradient(0f, r.top, 0f, r.bottom, Color.rgb(150, 20, 16), Color.rgb(74, 6, 6), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, 4f, 4f, pF); pF.shader = null
        pS.shader = null; pS.strokeWidth = 2.5f; pS.color = Color.argb(220, 20, 4, 4); c.drawRoundRect(r, 4f, 4f, pS)
        pS.strokeWidth = 1.2f; pS.color = t.edge; c.drawRoundRect(RectF(r.left + 1.5f, r.top + 1.5f, r.right - 1.5f, r.bottom - 1.5f), 3f, 3f, pS)
    }

    /** the ring art's corner, in its own px (UiKit's RING_C) */
    private const val RING_CAP = 14

    /** [art] nine-sliced round [r]: its [cap] px corners drawn [capPx] wide */
    private fun nine(c: Canvas, art: Bitmap, r: RectF, cap: Int, capPx: Float) {
        val s = art.width; val e = s - cap
        val xs = floatArrayOf(r.left, r.left + capPx, r.right - capPx, r.right)
        val ys = floatArrayOf(r.top, r.top + capPx, r.bottom - capPx, r.bottom)
        val sx = intArrayOf(0, cap, e, s); val sy = intArrayOf(0, cap, art.height - cap, art.height)
        for (i in 0..2) for (j in 0..2) {
            if (i == 1 && j == 1) continue
            c.drawBitmap(art, Rect(sx[i], sy[j], sx[i + 1], sy[j + 1]), RectF(xs[i], ys[j], xs[i + 1], ys[j + 1]), pB)
        }
    }

    /** a premade's picture (the gallery's wells): its mini room in Top's 216 x 108 unit space, scaled into [r] */
    fun premade(c: Canvas, r: RectF, t: ThemeSpec) {
        val save = c.save()
        val k = minOf(r.width() / W, r.height() / H)
        path.reset(); path.addRoundRect(r, 8f, 8f, Path.Direction.CW)
        c.clipPath(path)
        pF.shader = null; pF.color = WORLD; c.drawRect(r, pF)
        c.translate(r.centerX() - W * k / 2f, r.centerY() - H * k / 2f)
        c.scale(k, k)
        runCatching { miniRoom(c, RectF(0f, 0f, W, H), t, kitCloth = false) }
        c.restoreToCount(save)
        pF.shader = null
    }

    /** the gallery's in-use badge (design 9.6: the game's check_tick, else a drawn tick) on Top's badge disc */
    fun tick(c: Canvas, r: RectF) {
        val cx = r.centerX(); val cy = r.centerY(); val rad = r.width() / 2f
        pF.shader = null; pF.color = Color.argb(215, 10, 10, 12); c.drawCircle(cx, cy, rad, pF)
        pS.shader = null; pS.strokeWidth = 2f; pS.color = Color.argb(220, 150, 126, 90); c.drawCircle(cx, cy, rad - 1f, pS)
        val tk = UiKit.tex("check_tick")
        if (tk != null) { val q = rad * 0.78f; c.drawBitmap(tk, null, RectF(cx - q, cy - q, cx + q, cy + q), pB); return }
        pS.color = Theme.gold; pS.strokeWidth = 3.2f
        path.reset(); path.moveTo(cx - rad * 0.42f, cy + rad * 0.02f); path.lineTo(cx - rad * 0.1f, cy + rad * 0.34f); path.lineTo(cx + rad * 0.45f, cy - rad * 0.32f)
        c.drawPath(path, pS)
    }

    // the kit art the mini rooms draw, decoded small and held until [letGo]
    private var rockBmp: Bitmap? = null
    private var rockMissing = false
    private val ringBmps = HashMap<String, Bitmap>()
    // Light 8 (LIGHT8_PLAN.md change 1): the scene, rock and rings counted on the 8 GB Thors' memory line ("generalpics"); registered only
    init { MemWatch.source8("generalpics") { bytes() } }
    private val ringMissing = HashSet<String>()
    private val groundFilters = HashMap<String, ColorMatrixColorFilter>()

    /** the kit's ground_rock at a quarter size, 256 px (one copy for every theme: the light is a colour filter) */
    @Synchronized private fun rockArt(): Bitmap? {
        rockBmp?.let { return it }
        if (rockMissing) return null
        val b = runCatching {
            val f = ArtHarvest.file("ui/kit/ground_rock.png")
            if (f.canRead()) BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 4 }) else null
        }.getOrNull()
        if (b == null) rockMissing = true else rockBmp = b
        return b
    }

    /** the rock as [t] lights its grounds (feat/beautify: themes' ONE ground matrix, UiKit.groundMatrix - its stoneLight, artSat, tint
     *  and OLED floor), lifted to 0.9 so the stone reads at a tile's size (the rooms: 0.5) */
    private fun groundFilter(t: ThemeSpec): ColorMatrixColorFilter = groundFilters.getOrPut(t.id) {
        ColorMatrixColorFilter(UiKit.groundMatrix(t, 0.9f * t.stoneLight))
    }

    /** the kit's ring_square in [t]'s metal (the raw art, recoloured once per theme: 64 px, 16 KB) */
    @Synchronized private fun ringArt(t: ThemeSpec): Bitmap? {
        ringBmps[t.id]?.let { return it }
        if (t.id in ringMissing) return null
        val b = runCatching {
            val f = ArtHarvest.file("ui/kit/ring_square.png")
            if (f.canRead()) BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inMutable = true })?.let { UiKit.recolour(it, t.metal) } else null
        }.getOrNull()
        if (b == null) ringMissing += t.id else ringBmps[t.id] = b
        return b
    }

    /** a word's stand-in: a rounded stroke [len] long, [th] thick, from ([x], [cy]) */
    private fun stroke(c: Canvas, x: Float, cy: Float, len: Float, th: Float, col: Int) {
        pF.color = col
        c.drawRoundRect(RectF(x, cy - th / 2f, x + len, cy + th / 2f), th / 2f, th / 2f, pF)
    }

    // ---------------------------------------------------------------- Swap screens

    /** the Thor's two screens: the top one (16:9, larger) over the bottom one; the game and our Home, traded when [swapped] */
    private fun swap(c: Canvas, ctx: Context?, swapped: Boolean) {
        val top = RectF(34f, 6f, 138f, 6f + 104f * 9f / 16f)            // 104 x 58.5
        val bottom = RectF(48f, 69f, 124f, 103f)                       // 76 x 34 (the Thor's lower panel's shape)
        // the shells: a dark body round each screen, the hinge between
        pF.color = Color.rgb(34, 36, 40)
        c.drawRoundRect(RectF(top.left - 5f, top.top - 4f, top.right + 5f, top.bottom + 4f), 6f, 6f, pF)
        c.drawRoundRect(RectF(bottom.left - 18f, bottom.top - 3f, bottom.right + 18f, bottom.bottom + 4f), 7f, 7f, pF)
        pF.color = Color.rgb(24, 25, 28); c.drawRect(top.left + 10f, top.bottom + 4f, top.right - 10f, bottom.top - 3f, pF)
        // the bottom shell's sticks and buttons (where the controller is)
        pF.color = Color.rgb(64, 66, 72)
        c.drawCircle(bottom.left - 10f, bottom.top + 9f, 4f, pF); c.drawCircle(bottom.right + 10f, bottom.top + 9f, 4f, pF)
        c.drawCircle(bottom.left - 10f, bottom.bottom - 8f, 2.6f, pF); c.drawCircle(bottom.right + 10f, bottom.bottom - 8f, 2.6f, pF)
        val gameIn = if (swapped) bottom else top
        val homeIn = if (swapped) top else bottom
        game(c, ctx, gameIn)
        home(c, homeIn)
        pS.shader = null; pS.color = Color.argb(220, 0, 0, 0); pS.strokeWidth = 1.5f
        c.drawRect(top, pS); c.drawRect(bottom, pS)
        // the trade's two arrows on the right (gold while they are traded)
        val col = if (swapped) Theme.gold else Theme.textDim
        arrows(c, 154f, 50f, 100f, col)          // under the badge's corner (36 px, top-right)
    }

    /** two curved arrows between ([x], [y0]) and ([x], [y1]): one down, one up */
    private fun arrows(c: Canvas, x: Float, y0: Float, y1: Float, col: Int) {
        pS.color = col; pS.strokeWidth = 3f
        path.reset(); path.moveTo(x - 6f, y0); path.cubicTo(x + 10f, y0 + 10f, x + 10f, y1 - 18f, x - 2f, y1 - 6f); c.drawPath(path, pS)
        head(c, x - 2f, y1 - 6f, -0.9f, 0.6f, col)
        path.reset(); path.moveTo(x + 18f, y1); path.cubicTo(x + 2f, y1 - 10f, x + 2f, y0 + 18f, x + 14f, y0 + 6f); c.drawPath(path, pS)
        head(c, x + 14f, y0 + 6f, 0.9f, -0.6f, col)
    }

    private fun head(c: Canvas, x: Float, y: Float, dx: Float, dy: Float, col: Int) {
        val l = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val ux = dx / l; val uy = dy / l
        pF.color = col
        path.reset()
        path.moveTo(x + ux * 6f, y + uy * 6f)
        path.lineTo(x - ux * 2f - uy * 5f, y - uy * 2f + ux * 5f)
        path.lineTo(x - ux * 2f + uy * 5f, y - uy * 2f - ux * 5f)
        path.close(); c.drawPath(path, pF)
    }

    /** the game's screen: a crop of one of its loading pictures (the kit's scene art); a drawn dusk and hills without it */
    private fun game(c: Canvas, ctx: Context?, r: RectF) {
        val pic = scene()
        if (pic != null) {
            // the crop keeps [r]'s shape, from the picture's middle band
            val sw = pic.width; val sh = (sw * r.height() / r.width()).toInt().coerceAtMost(pic.height)
            val st = ((pic.height - sh) * 0.45f).toInt()
            c.drawBitmap(pic, Rect(0, st, sw, st + sh), r, pB)
            return
        }
        pF.shader = LinearGradient(0f, r.top, 0f, r.bottom, Color.rgb(222, 150, 84), Color.rgb(96, 52, 34), Shader.TileMode.CLAMP)
        c.drawRect(r, pF); pF.shader = null
        pF.color = Color.rgb(58, 36, 26)
        path.reset(); path.moveTo(r.left, r.bottom)
        path.lineTo(r.left, r.top + r.height() * 0.62f)
        path.lineTo(r.left + r.width() * 0.28f, r.top + r.height() * 0.42f)
        path.lineTo(r.left + r.width() * 0.5f, r.top + r.height() * 0.6f)
        path.lineTo(r.left + r.width() * 0.76f, r.top + r.height() * 0.36f)
        path.lineTo(r.right, r.top + r.height() * 0.56f); path.lineTo(r.right, r.bottom); path.close()
        c.drawPath(path, pF)
        pF.color = Color.rgb(255, 228, 160); c.drawCircle(r.left + r.width() * 0.72f, r.top + r.height() * 0.24f, r.height() * 0.08f, pF)
    }

    /** our Home: the map (a green field, the player's arrow), the controller's ring over it and the bar at the foot with its swirl */
    private fun home(c: Canvas, r: RectF) {
        pF.shader = RadialGradient(r.centerX(), r.top + r.height() * 0.4f, r.width() * 0.7f, Color.rgb(86, 112, 58), Color.rgb(44, 62, 34),
            Shader.TileMode.CLAMP)
        c.drawRect(r, pF); pF.shader = null
        // a road and a lake
        pS.color = Color.rgb(150, 132, 96); pS.strokeWidth = maxOf(1f, r.height() * 0.04f)
        c.drawLine(r.left + r.width() * 0.1f, r.bottom - r.height() * 0.2f, r.right - r.width() * 0.2f, r.top + r.height() * 0.15f, pS)
        pF.color = Color.rgb(48, 82, 110)
        c.drawOval(RectF(r.left + r.width() * 0.62f, r.top + r.height() * 0.5f, r.left + r.width() * 0.9f, r.top + r.height() * 0.72f), pF)
        // the quest card (the theme's raised plate, the accent's line)
        val card = RectF(r.left + 3f, r.top + 3f, r.left + r.width() * 0.42f, r.top + r.height() * 0.24f)
        pF.color = Theme.raised; c.drawRoundRect(card, 2f, 2f, pF)
        pF.color = Theme.gold; c.drawRect(card.left + 3f, card.centerY() - 1f, card.right - 4f, card.centerY() + 1f, pF)
        // the bar at the foot
        val bar = RectF(r.left, r.bottom - r.height() * 0.2f, r.right, r.bottom)
        pF.color = Theme.panel; c.drawRect(bar, pF)
        pF.color = Color.rgb(80, 150, 230); c.drawCircle(bar.centerX(), bar.centerY(), bar.height() * 0.42f, pF)
        pF.color = Theme.bronze
        for (i in listOf(-3, -2, -1, 1, 2, 3)) c.drawCircle(bar.centerX() + i * r.width() * 0.13f, bar.centerY(), bar.height() * 0.2f, pF)
        // the player's arrow
        pF.color = Color.WHITE
        val ax = r.centerX(); val ay = r.top + r.height() * 0.5f; val s = r.height() * 0.08f
        path.reset(); path.moveTo(ax, ay - s * 1.4f); path.lineTo(ax + s, ay + s); path.lineTo(ax, ay + s * 0.4f); path.lineTo(ax - s, ay + s); path.close()
        c.drawPath(path, pF)
    }

    // ---------------------------------------------------------------- Voice typing

    /** the microphone, its sound, and where it listens: the Thor under it (This device), a cloud (Online), struck through (Off) */
    private fun voice(c: Canvas, mode: Voice.Mode) {
        val off = mode == Voice.Mode.OFF
        // a soft light behind the microphone
        pF.shader = RadialGradient(108f, 40f, 54f, if (off) Color.argb(0, 0, 0, 0) else (Theme.gold and 0xFFFFFF) or (0x30 shl 24),
            Color.argb(0, 0, 0, 0), Shader.TileMode.CLAMP)
        c.drawCircle(108f, 40f, 54f, pF); pF.shader = null
        val col = if (off) Theme.textDim else Theme.gold
        mic(c, 108f, 36f, col)
        if (!off) {
            // the sound: two arcs each side
            pS.color = (col and 0xFFFFFF) or (0xB0 shl 24); pS.strokeWidth = 2.6f
            for (k in 1..2) {
                val rr = 18f + k * 9f
                c.drawArc(RectF(108f - rr, 32f - rr, 108f + rr, 32f + rr), -35f, 70f, false, pS)
                c.drawArc(RectF(108f - rr, 32f - rr, 108f + rr, 32f + rr), 145f, 70f, false, pS)
            }
        }
        when (mode) {
            Voice.Mode.DEVICE -> thor(c, 108f, 88f, Theme.text)
            Voice.Mode.ONLINE -> cloud(c, 108f, 89f, Theme.text)
            Voice.Mode.OFF -> {
                pS.color = Theme.textDim; pS.strokeWidth = 3.4f
                c.drawLine(88f, 66f, 128f, 8f, pS)
                pS.color = WORLD; pS.strokeWidth = 2f
                c.drawLine(91f, 68f, 131f, 10f, pS)
            }
        }
    }

    /** the microphone: its capsule with the grill, the holder, the stand and its foot; centred on ([cx], [cy]) */
    private fun mic(c: Canvas, cx: Float, cy: Float, col: Int) {
        val cap = RectF(cx - 10f, cy - 26f, cx + 10f, cy + 8f)
        pF.color = (col and 0xFFFFFF) or (0x40 shl 24); c.drawRoundRect(cap, 10f, 10f, pF)
        pS.color = col; pS.strokeWidth = 3f; c.drawRoundRect(cap, 10f, 10f, pS)
        pS.strokeWidth = 1.6f
        for (i in 0..2) c.drawLine(cx - 5f, cy - 16f + i * 7f, cx + 5f, cy - 16f + i * 7f, pS)
        pS.strokeWidth = 3f
        c.drawArc(RectF(cx - 17f, cy - 14f, cx + 17f, cy + 18f), 0f, 180f, false, pS)
        c.drawLine(cx, cy + 18f, cx, cy + 26f, pS)
        c.drawLine(cx - 10f, cy + 27f, cx + 10f, cy + 27f, pS)
    }

    /** a small Thor, as the Swap screens picture draws it: the top screen in its shell, the hinge, the lower shell with its screen and
     *  sticks; the screens lit in [col] */
    private fun thor(c: Canvas, cx: Float, cy: Float, col: Int) {
        pF.color = Color.rgb(52, 55, 62)
        c.drawRoundRect(RectF(cx - 18f, cy - 16f, cx + 18f, cy + 1f), 3f, 3f, pF)
        c.drawRoundRect(RectF(cx - 25f, cy + 3f, cx + 25f, cy + 17f), 4f, 4f, pF)
        pF.color = (col and 0xFFFFFF) or (0xD0 shl 24)
        c.drawRect(cx - 15f, cy - 13.5f, cx + 15f, cy - 1.5f, pF)
        c.drawRect(cx - 11f, cy + 5.5f, cx + 11f, cy + 14.5f, pF)
        pF.color = Color.rgb(96, 100, 110)
        c.drawCircle(cx - 18f, cy + 8f, 2.6f, pF); c.drawCircle(cx + 18f, cy + 8f, 2.6f, pF)
    }

    /** a cloud: three humps on a flat foot */
    private fun cloud(c: Canvas, cx: Float, cy: Float, col: Int) {
        pS.color = col; pS.strokeWidth = 2.4f
        path.reset()
        path.moveTo(cx - 20f, cy + 9f)
        path.cubicTo(cx - 30f, cy + 9f, cx - 28f, cy - 5f, cx - 17f, cy - 3f)
        path.cubicTo(cx - 15f, cy - 15f, cx + 3f, cy - 17f, cx + 6f, cy - 6f)
        path.cubicTo(cx + 12f, cy - 12f, cx + 26f, cy - 7f, cx + 22f, cy + 2f)
        path.cubicTo(cx + 30f, cy + 4f, cx + 28f, cy + 9f, cx + 20f, cy + 9f)
        path.close()
        c.drawPath(path, pS)
    }

    // ---------------------------------------------------------------- the scene crop (decoded small, let go at a trim)

    /** the loading picture the Swap screens tile shows (Durotar's canyon: warm, open sky); the kit's, never packed into the app */
    private const val SCENE_FID = 7963777
    private var sceneBmp: Bitmap? = null
    private var sceneMissing = false

    init {
        MemWatch.onTrim("genpics") { level ->
            if (!PreviewStage.cacheLetsGo(level, MemWatch.room)) 0L else letGo()
        }
    }

    /** the picture at a quarter of its size (310 x 270, ~0.3 MB), or null without the kit */
    @Synchronized private fun scene(): Bitmap? {
        sceneBmp?.let { return it }
        if (sceneMissing) return null
        val f = ArtHarvest.file("ui/kit/scene_$SCENE_FID.png")
        val b = if (f.canRead()) runCatching {
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 4 })
        }.getOrNull() else null
        if (b == null) sceneMissing = true else sceneBmp = b
        return b
    }

    /** every decoded picture goes (a trim; Settings closing); their bytes */
    @Synchronized fun letGo(): Long {
        val n = bytes()
        sceneBmp = null; sceneMissing = false
        rockBmp = null; rockMissing = false; ringBmps.clear(); ringMissing.clear(); groundFilters.clear()
        return n
    }

    /** the bytes held now (the tests) */
    @Synchronized fun bytes(): Long = (sceneBmp?.byteCount?.toLong() ?: 0L) + (rockBmp?.byteCount?.toLong() ?: 0L) +
        ringBmps.values.sumOf { it.byteCount.toLong() }
}
