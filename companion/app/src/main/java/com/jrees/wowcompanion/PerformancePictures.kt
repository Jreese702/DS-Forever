package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * THE PERFORMANCE TILES' PICTURES (the beautify design, section 6; the Top screen's picture contract, TilePictures): each drawn in a
 * space [H] = 108 units tall and as wide as the box's shape (216 for a tile's well and for the band's 438 x 220, 460 for the wide Frame
 * generation well), on the same dark stand-in of the game's world as Top's.
 *
 * - Frame limit: the game's frame lane (the kit's bar ground) with a gem per game frame in a quarter second at the limit, the mover
 *   stepping at that rate, and the heat bar under it (a thermometer at its start), cooler with a lower limit. The band's picture
 *   ([big]) puts the three choices side by side, one lane each with its own heat, the chosen one lit: 40 / 60 / Off read as the
 *   buttons beside them.
 * - Frame generation: a film strip; the game's frames solid, the made ones hatched in the accent at 40 %, one in every [multiplier] the
 *   game's; Off = the game's frames alone with the gaps empty. No copy of the DLL yet: what 2x would make (the view dims it).
 * - Memory: Home's map module at the chosen detail, from the device's own minimap tiles (MinimapTiles, the art harvest's): Full detail
 *   zoomed out over many zones with the zone framed, Zone map the zone alone; a drawn map where there are no tiles.
 * - Indoor maps: a building's floor plan being built room by room, the build's own ready / total as a ring.
 * - Stream settings: the PC's screen sending the game to the two-screen handheld, the signal's arcs between them in the accent.
 *
 * Animated pictures move only in the open band ([t] > 0); a tile draws frame 0. Sample fills only (no game values); the indoor ring and
 * the lane's rate are our own settings and our own bake's count.
 */
object PerformancePictures {
    const val H = 108f

    private val pF = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pS = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val pB = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val dash = DashPathEffect(floatArrayOf(4f, 3f), 0f)

    /** [id]'s picture into [r] (its own rounded clip), for [s] at [t] s (0 = the tile's still frame); [ready] = a map tile landed */
    fun draw(c: Canvas, ctx: Context?, id: String, r: RectF, s: PerformanceTileModel.State, t: Float, big: Boolean = false, ready: () -> Unit = {}) {
        val save = c.save()
        val rad = 8f * r.height() / H
        path.reset(); path.addRoundRect(r, rad, rad, Path.Direction.CW)
        c.clipPath(path)
        val k = r.height() / H
        val w = r.width() / k
        c.translate(r.left, r.top)
        c.scale(k, k)
        world(c, w)
        runCatching {
            when (id) {
                PerformanceTileModel.LIMIT -> if (big) frameLimits(c, w, s.limit, t) else frameLimit(c, w, s.limit, t)
                PerformanceTileModel.FG -> if (s.hasDll) frameGen(c, w, s.generating, s.multiplier, t) else frameGen(c, w, true, 2, t)
                PerformanceTileModel.MEM -> memory(c, w, s.mapFull, ready)
                PerformanceTileModel.INDOOR -> indoor(c, w, s.indoorOn, if (s.total > 0) s.ready / s.total.toFloat() else 0f, t)
                PerformanceTileModel.STREAM -> stream(c, w)
            }
        }
        c.restoreToCount(save)
    }

    /** nothing is held here beyond the shared map-tile cache (MinimapTiles keeps its own bound); kept for the room's close */
    fun drop() {}

    /** the dark stand-in of the game's world (TilePictures' fills: a night sky over dark ground, the edges darker) */
    private fun world(c: Canvas, w: Float) {
        pF.shader = LinearGradient(0f, 0f, 0f, H, intArrayOf(Color.rgb(36, 50, 62), Color.rgb(30, 40, 34), Color.rgb(20, 26, 18)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, H, pF)
        pF.shader = RadialGradient(w / 2f, H * 0.55f, max(w, H) * 0.62f, intArrayOf(Color.argb(0, 0, 0, 0), Color.argb(120, 0, 0, 0)),
            floatArrayOf(0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, H, pF)
        pF.shader = null
    }

    // ---------------------------------------------------------------- Frame limit

    private val HOT = Color.rgb(210, 80, 50)

    private fun frameLimit(c: Canvas, w: Float, limit: Int, t: Float) {
        val fps = if (limit > 0) limit else 120
        val col = Theme.gold
        val y = 50f
        lane(c, 12f, w - 12f, y, fps, t, col)
        // the heat: the game's standing bar, lower with the limit (40 cool, 60 warm, none hot)
        heatBar(c, RectF(12f, 80f, w - 12f, 98f), limit, 1f)
    }

    /** the heat at [limit]: the game's standing bar after a thermometer, lower with the limit (40 cool, 60 warm, none hot) */
    private fun heatBar(c: Canvas, r: RectF, limit: Int, alpha: Float) {
        val heat = when { limit in 1..40 -> 0.42f; limit in 41..60 -> 0.68f; else -> 0.94f }
        val hc = when { heat < 0.6f -> Theme.ok; heat < 0.85f -> Theme.warn; else -> HOT }
        val tw = r.height() * 0.9f
        thermometer(c, r.left + tw / 2f, r.centerY(), r.height() * 1.3f, hc, alpha)
        UiKit.standing(c, RectF(r.left + tw + 6f, r.top, r.right, r.bottom), withAlpha(hc, alpha), heat, "", 1f)
    }

    /** a thermometer [h] tall at ([cx], [cy]): bulb and stem in [col] */
    private fun thermometer(c: Canvas, cx: Float, cy: Float, h: Float, col: Int, alpha: Float) {
        val br = h * 0.24f
        val by = cy + h / 2f - br
        val sw = br * 0.9f
        pF.color = withAlpha(Color.rgb(20, 20, 22), alpha)
        c.drawRoundRect(RectF(cx - sw / 2f - 1.5f, cy - h / 2f - 1.5f, cx + sw / 2f + 1.5f, by), sw, sw, pF)
        c.drawCircle(cx, by, br + 1.5f, pF)
        pF.color = withAlpha(col, alpha)
        c.drawCircle(cx, by, br - 0.5f, pF)
        c.drawRoundRect(RectF(cx - sw / 2f + 1.2f, cy - h * 0.15f, cx + sw / 2f - 1.2f, by), sw, sw, pF)
    }

    private fun withAlpha(col: Int, a: Float): Int = Color.argb((Color.alpha(col) * a).toInt(), Color.red(col), Color.green(col), Color.blue(col))

    /**
     * the band's picture: the three choices side by side (40 / 60 / Off, the band's buttons' order), each its own lane and heat, the
     * chosen one lit in the accent and the others dim; the number over each (the limit itself, not words)
     */
    private fun frameLimits(c: Canvas, w: Float, limit: Int, t: Float) {
        val limits = FrameGen.LIMITS
        val n = limits.size
        val gap = 8f
        val cw = (w - 16f - (n - 1) * gap) / n
        val big = UiKit.numberPaint(17f, Theme.text, shadow = false).apply { textAlign = Paint.Align.CENTER }
        for ((i, lim) in limits.withIndex()) {
            val x0 = 8f + i * (cw + gap)
            val lit = lim == limit
            val a = if (lit) 1f else 0.42f
            val col = if (lit) Theme.gold else Color.rgb(150, 140, 120)
            if (lit) {
                pF.color = Color.argb(36, Color.red(Theme.gold), Color.green(Theme.gold), Color.blue(Theme.gold))
                c.drawRoundRect(RectF(x0, 4f, x0 + cw, H - 4f), 5f, 5f, pF)
                pS.color = Theme.gold; pS.strokeWidth = 1.4f
                c.drawRoundRect(RectF(x0, 4f, x0 + cw, H - 4f), 5f, 5f, pS)
            }
            big.color = withAlpha(if (lit) Theme.gold else Theme.text, if (lit) 1f else 0.6f)
            c.drawText(Tr.t(FrameGen.limitLabel(lim)), x0 + cw / 2f, 22f, big)
            val save = c.save()
            c.clipRect(x0, 26f, x0 + cw, H)
            lane(c, x0 + 4f, x0 + cw - 4f, 62f, if (lim > 0) lim else 120, t, withAlpha(col, a), win = 0.12f)
            c.restoreToCount(save)
            heatBar(c, RectF(x0 + 6f, 84f, x0 + cw - 6f, 96f), lim, a)
        }
    }

    /**
     * one lane of frames (SettingsPreview's lane, re-framed): the last quarter second at [fps] scrolling with [t]; a game frame a gem,
     * a made one ([every] > 1: all but every [every]th) a ring; the mover above at the place each frame put it
     */
    private fun lane(c: Canvas, x0: Float, x1: Float, y: Float, fps: Int, t: Float, col: Int, every: Int = 1, win: Float = 0.25f) {
        UiKit.standing(c, RectF(x0, y - 11f, x1, y + 11f), col, 0f, "", 1f)
        val a = x0 + 10f; val b = x1 - 10f
        val dt = 1f / fps
        var k = floor((t - win) / dt).toInt() + 1
        val k1 = floor(t / dt).toInt()
        while (k <= k1) {
            val x = a + (k * dt - (t - win)) / win * (b - a)
            if (every <= 1 || Math.floorMod(k, every) == 0) {
                pF.color = col; c.drawRect(x - 2.2f, y - 5.5f, x + 2.2f, y + 5.5f, pF)
                pF.color = Color.argb(150, 255, 250, 225); c.drawRect(x - 2.2f, y - 5.5f, x + 2.2f, y - 3.5f, pF)
            } else { pS.color = col; pS.strokeWidth = 1.4f; c.drawCircle(x, y, 3f, pS) }
            k++
        }
        // the mover steps once a frame: choppier at a lower rate
        val tq = floor(t * fps) / fps
        val period = 1.6f
        val bx = a + 8f + (((tq + 0.35f) % period) / period) * (b - a - 16f)
        val by = y - 26f
        pF.color = Color.argb(70, Color.red(col), Color.green(col), Color.blue(col)); c.drawCircle(bx, by, 11f, pF)
        pF.color = col; c.drawCircle(bx, by, 7f, pF)
        pF.color = Color.argb(170, 255, 250, 225); c.drawCircle(bx - 2f, by - 2f, 2.4f, pF)
    }

    // ---------------------------------------------------------------- Frame generation

    private fun frameGen(c: Canvas, w: Float, on: Boolean, multiplier: Int, t: Float) {
        // one row on the wide well, two in a 2:1 box (the band's picture)
        val rows = if (w >= 300f) 1 else 2
        val gap = 6f
        val ch = if (rows == 1) 66f else 40f
        val cw = ch * 0.78f
        val n = floor((w - 24f + gap) / (cw + gap)).toInt().coerceAtLeast(2)
        val perRow = n
        val every = if (on) multiplier.coerceAtLeast(2) else 2
        val total = perRow * rows
        val left0 = (w - (perRow * cw + (perRow - 1) * gap)) / 2f
        val top0 = (H - (rows * ch + (rows - 1) * 10f)) / 2f
        // the strip runs on in the band: every frame steps one place a tick
        val step = (t * 6f).toInt()
        val accent = Theme.gold
        for (i in 0 until total) {
            val row = i / perRow; val col = i % perRow
            val x = left0 + col * (cw + gap)
            val y = top0 + row * (ch + 10f)
            val r = RectF(x, y, x + cw, y + ch)
            val k = i + step
            val real = Math.floorMod(k, every) == 0
            if (!real && !on) {
                // Off: no frame made here - an empty slot
                pS.color = Color.argb(110, 150, 140, 120); pS.strokeWidth = 1.2f; pS.pathEffect = dash
                c.drawRoundRect(r, 3f, 3f, pS); pS.pathEffect = null
                continue
            }
            card(c, r, (k % total) / total.toFloat())
            if (real) {
                pS.color = Color.rgb(176, 150, 104); pS.strokeWidth = 1.8f
                c.drawRoundRect(r, 3f, 3f, pS)
            } else {
                hatch(c, r, accent)
                pS.color = accent; pS.strokeWidth = 1.6f; pS.pathEffect = dash
                c.drawRoundRect(r, 3f, 3f, pS); pS.pathEffect = null
            }
        }
    }

    /** one frame: a small scene (sky, ground, the mover at [u] across it) */
    private fun card(c: Canvas, r: RectF, u: Float) {
        pF.shader = LinearGradient(0f, r.top, 0f, r.bottom, intArrayOf(Color.rgb(58, 80, 98), Color.rgb(40, 56, 46), Color.rgb(28, 36, 24)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, 3f, 3f, pF); pF.shader = null
        val gx = r.left + 4f + u * (r.width() - 8f)
        val gy = r.top + r.height() * 0.42f
        pF.color = Color.rgb(230, 200, 120); c.drawCircle(gx, gy, r.width() * 0.13f, pF)
        pF.color = Color.argb(120, 20, 26, 18); c.drawRect(r.left + 1f, r.top + r.height() * 0.7f, r.right - 1f, r.bottom - 1f, pF)
    }

    /** a made frame: diagonal lines in the accent at 40 % over it */
    private fun hatch(c: Canvas, r: RectF, col: Int) {
        val save = c.save()
        c.clipRect(r)
        pS.color = Color.argb(102, Color.red(col), Color.green(col), Color.blue(col)); pS.strokeWidth = 2f
        var x = r.left - r.height()
        while (x < r.right) { c.drawLine(x, r.bottom, x + r.height(), r.top, pS); x += 6f }
        pF.color = Color.argb(46, Color.red(col), Color.green(col), Color.blue(col)); c.drawRect(r, pF)
        c.restoreToCount(save)
    }

    // ---------------------------------------------------------------- Memory: Home's map at full detail or the zone alone

    /** the sample place: Elwynn's tiles on the Eastern Kingdoms (continent 0; MinimapTiles' col_row names) */
    private const val CONT = 0
    private const val ZONE_COL = 32
    private const val ZONE_ROW = 48

    private fun memory(c: Canvas, w: Float, full: Boolean, ready: () -> Unit) {
        val frame = RectF(10f, 8f, w - 10f, H - 8f)
        val save = c.save()
        c.clipRect(frame)
        pF.color = Color.rgb(16, 28, 40); c.drawRect(frame, pF)                 // the sea where no tile is
        // the zone: 3 x 2 tiles round (ZONE_COL, ZONE_ROW), centred; full detail: 9 tiles across, the zone in the middle
        val across = if (full) 9 else 3
        val ts = frame.width() / across
        val zoneL = frame.left + (if (full) 3 else 0) * ts
        val zoneT = frame.centerY() - ts
        val zone = RectF(zoneL, zoneT, zoneL + 3 * ts, zoneT + 2 * ts)
        val c0 = ZONE_COL - 1 - (if (full) 3 else 0)
        val above = kotlin.math.ceil((zoneT - frame.top) / ts).toInt()
        val below = kotlin.math.ceil((frame.bottom - zone.bottom) / ts).toInt()
        if (MinimapTiles.has(CONT)) {
            for (j in -above until 2 + below) for (i in 0 until across) {
                val bmp = MinimapTiles.get(CONT, c0 + i, ZONE_ROW + j, if (full) 8 else 4, ready) ?: continue
                val dst = RectF(frame.left + i * ts, zoneT + j * ts, frame.left + (i + 1) * ts + 0.5f, zoneT + (j + 1) * ts + 0.5f)
                c.drawBitmap(bmp, null, dst, pB)
            }
        } else drawnMap(c, frame, full)
        if (full) {
            // where the zone map stops: its frame in the accent
            pS.color = Theme.gold; pS.strokeWidth = 1.6f
            c.drawRect(zone, pS)
        } else {
            // the zone alone: the world past it is not drawn (the module's own dark round its edge)
            pF.shader = RadialGradient(frame.centerX(), frame.centerY(), frame.width() * 0.62f,
                intArrayOf(Color.argb(0, 0, 0, 0), Color.argb(200, 6, 8, 10)), floatArrayOf(0.62f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(frame, pF); pF.shader = null
        }
        player(c, zone.centerX(), zone.centerY(), if (full) 5f else 7f)
        c.restoreToCount(save)
        // the module's edge: the game's bronze
        pS.color = Color.rgb(150, 126, 90); pS.strokeWidth = 2f
        c.drawRoundRect(frame, 4f, 4f, pS)
    }

    /** the player's arrow: the kit's minimap arrow, a drawn one without it */
    private fun player(c: Canvas, x: Float, y: Float, s: Float) {
        val bmp = UiKit.tex("mm_player")
        if (bmp != null) { c.drawBitmap(bmp, null, RectF(x - s * 1.6f, y - s * 1.6f, x + s * 1.6f, y + s * 1.6f), pB); return }
        pF.color = Color.rgb(255, 230, 120)
        path.reset(); path.moveTo(x, y - s * 1.4f); path.lineTo(x + s, y + s); path.lineTo(x, y + s * 0.4f); path.lineTo(x - s, y + s); path.close()
        c.drawPath(path, pF)
    }

    /** no tiles on the device yet: a drawn map (land, roads), the zone alone or zoomed out over the coast */
    private fun drawnMap(c: Canvas, f: RectF, full: Boolean) {
        val land = Color.rgb(62, 82, 50)
        val land2 = Color.rgb(78, 96, 58)
        if (full) {
            pF.color = land
            path.reset()
            path.moveTo(f.left + f.width() * 0.18f, f.top)
            path.cubicTo(f.left + f.width() * 0.05f, f.top + f.height() * 0.4f, f.left + f.width() * 0.2f, f.bottom - f.height() * 0.1f, f.left + f.width() * 0.35f, f.bottom)
            path.lineTo(f.right, f.bottom); path.lineTo(f.right, f.top); path.close()
            c.drawPath(path, pF)
            pF.color = land2
            for (i in 0 until 5) c.drawCircle(f.left + f.width() * (0.4f + 0.12f * i), f.top + f.height() * (0.3f + 0.1f * (i % 3)), f.height() * 0.18f, pF)
        } else {
            pF.color = land; c.drawRect(f, pF)
            pF.color = land2
            c.drawCircle(f.left + f.width() * 0.3f, f.top + f.height() * 0.35f, f.height() * 0.3f, pF)
            c.drawCircle(f.left + f.width() * 0.75f, f.top + f.height() * 0.7f, f.height() * 0.35f, pF)
        }
        pS.color = Color.rgb(150, 128, 92); pS.strokeWidth = if (full) 1f else 2f
        c.drawLine(f.left, f.centerY() + 6f, f.right, f.centerY() - 8f, pS)
        c.drawLine(f.centerX() - 10f, f.top, f.centerX() + 14f, f.bottom, pS)
    }

    // ---------------------------------------------------------------- Indoor maps

    private fun indoor(c: Canvas, w: Float, on: Boolean, done: Float, t: Float) {
        // the plan, left; the ring, right
        val ringR = 30f
        val ringX = w - 18f - ringR
        val plan = RectF(14f, 12f, ringX - ringR - 16f, H - 12f)
        val rooms = listOf(RectF(0f, 0f, 0.45f, 0.55f), RectF(0.45f, 0f, 1f, 0.38f), RectF(0.45f, 0.38f, 1f, 1f), RectF(0f, 0.55f, 0.45f, 1f))
        // building room by room: the bake's share lit, and in the band the next room filling in
        val lit = if (!on) 0f else (max(done, 0.25f) * rooms.size + (if (t > 0f) (t % 3f) / 3f else 0f)).coerceAtMost(rooms.size.toFloat())
        for ((i, q) in rooms.withIndex()) {
            val r = RectF(plan.left + q.left * plan.width(), plan.top + q.top * plan.height(), plan.left + q.right * plan.width(), plan.top + q.bottom * plan.height())
            val k = (lit - i).coerceIn(0f, 1f)
            pF.color = blend(Color.rgb(46, 42, 36), Color.rgb(176, 150, 104), k)
            c.drawRect(r, pF)
        }
        // the walls, with doorways
        pS.color = Color.rgb(28, 22, 16); pS.strokeWidth = 3f
        c.drawRect(plan, pS)
        val mx = plan.left + 0.45f * plan.width(); val my1 = plan.top + 0.55f * plan.height(); val my2 = plan.top + 0.38f * plan.height()
        c.drawLine(mx, plan.top, mx, plan.top + plan.height() * 0.24f, pS); c.drawLine(mx, plan.top + plan.height() * 0.4f, mx, plan.bottom, pS)
        c.drawLine(plan.left, my1, plan.left + plan.width() * 0.16f, my1, pS); c.drawLine(plan.left + plan.width() * 0.3f, my1, mx, my1, pS)
        c.drawLine(mx, my2, mx + plan.width() * 0.2f, my2, pS); c.drawLine(mx + plan.width() * 0.36f, my2, plan.right, my2, pS)
        pS.color = Color.rgb(150, 126, 90); pS.strokeWidth = 1.2f; c.drawRect(plan, pS)
        player(c, plan.left + plan.width() * 0.25f, plan.top + plan.height() * 0.3f, 4.5f)
        // the ring: the bake's ready / total
        pS.color = Color.argb(200, 10, 10, 12); pS.strokeWidth = 9f
        c.drawCircle(ringX, H / 2f, ringR, pS)
        pS.color = Color.rgb(90, 76, 52); pS.strokeWidth = 1.2f
        c.drawCircle(ringX, H / 2f, ringR + 5f, pS); c.drawCircle(ringX, H / 2f, ringR - 5f, pS)
        val f = if (t > 0f && on) (done + 0.03f * (0.5f + 0.5f * sin(t * 2f * PI.toFloat()))).coerceIn(0f, 1f) else done
        if (f > 0f) {
            pS.color = Theme.gold; pS.strokeWidth = 7f
            c.drawArc(RectF(ringX - ringR, H / 2f - ringR, ringX + ringR, H / 2f + ringR), -90f, 360f * f, false, pS)
        }
        val p = UiKit.numberPaint(17f, Theme.text, shadow = false).apply { textAlign = Paint.Align.CENTER }
        c.drawText("${Math.round(done * 100)}%", ringX, H / 2f + 6f, p)
    }

    // ---------------------------------------------------------------- Stream settings

    /** the PC's screen (left) sending the game to the handheld's two screens (right), the signal's three arcs between, in the accent */
    private fun stream(c: Canvas, w: Float) {
        // the PC: a screen with the game's sky and ground, on its stand
        val pc = RectF(14f, 16f, 14f + w * 0.38f, 74f)
        pF.color = Color.rgb(18, 18, 22); c.drawRoundRect(RectF(pc.left - 4f, pc.top - 4f, pc.right + 4f, pc.bottom + 4f), 5f, 5f, pF)
        screen(c, pc)
        pF.color = Color.rgb(60, 58, 54)
        c.drawRect(pc.centerX() - 4f, pc.bottom + 4f, pc.centerX() + 4f, pc.bottom + 16f, pF)
        c.drawRoundRect(RectF(pc.centerX() - 20f, pc.bottom + 14f, pc.centerX() + 20f, pc.bottom + 19f), 2f, 2f, pF)
        // the handheld: the game on the top screen, our Home's strip on the bottom one
        val dw = w * 0.24f
        val dx = w - 14f - dw
        val top = RectF(dx, 12f, dx + dw, 12f + dw * 0.56f)
        val bot = RectF(dx + dw * 0.08f, top.bottom + 10f, dx + dw * 0.92f, top.bottom + 10f + dw * 0.5f)
        pF.color = Color.rgb(30, 30, 34)
        c.drawRoundRect(RectF(top.left - 4f, top.top - 4f, top.right + 4f, top.bottom + 4f), 5f, 5f, pF)
        c.drawRoundRect(RectF(bot.left - 4f, bot.top - 4f, bot.right + 4f, bot.bottom + 4f), 5f, 5f, pF)
        screen(c, top)
        pF.color = Color.rgb(24, 20, 14); c.drawRect(bot, pF)
        pF.color = Theme.gold
        for (i in 0..2) c.drawCircle(bot.left + bot.width() * (0.25f + 0.25f * i), bot.centerY(), 3f, pF)
        // the signal: three arcs opening toward the handheld
        val cx = pc.right + 10f
        val cy = (pc.top + pc.bottom) / 2f
        pS.strokeWidth = 3.2f
        for (i in 0..2) {
            val r = 12f + i * 11f
            pS.color = withAlpha(Theme.gold, 1f - i * 0.25f)
            c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -35f, 70f, false, pS)
        }
    }

    /** a small picture of the game on a screen: sky over ground, a sun */
    private fun screen(c: Canvas, r: RectF) {
        pF.shader = LinearGradient(0f, r.top, 0f, r.bottom, intArrayOf(Color.rgb(70, 110, 150), Color.rgb(150, 170, 160), Color.rgb(60, 90, 40)),
            floatArrayOf(0f, 0.55f, 0.56f), Shader.TileMode.CLAMP)
        c.drawRect(r, pF)
        pF.shader = null
        pF.color = Color.rgb(250, 226, 150)
        c.drawCircle(r.left + r.width() * 0.75f, r.top + r.height() * 0.28f, r.height() * 0.1f, pF)
    }

    private fun blend(a: Int, b: Int, k: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(), (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt())

    // ---------------------------------------------------------------- the signs on Memory's lines and its badge

    /** [key]'s line carries a sign before its words (Memory's pack) */
    fun lineSign(key: String): Boolean = key == PerformanceTileModel.L_LIGHTER || key == PerformanceTileModel.L_LOWGFX || key == PerformanceTileModel.L_MAP

    /** the sign of a Memory line at ([cx], [cy]), [size] px: Lighter mode a leaf, Low-memory graphics a chip, Home map a folded map */
    fun drawLineSign(c: Canvas, key: String, cx: Float, cy: Float, size: Float) {
        val s = size / 24f
        pS.color = Theme.gold; pS.strokeWidth = 2.2f
        when (key) {
            PerformanceTileModel.L_LIGHTER -> leaf(c, cx, cy, s, pS)
            PerformanceTileModel.L_LOWGFX -> chip(c, cx, cy, s, pS)
            PerformanceTileModel.L_MAP -> {
                // a folded map, in the same gold line as the leaf and the chip (the kit's map icon read as a red cross at this size)
                path.reset()
                path.moveTo(cx - 10f * s, cy - 7f * s); path.lineTo(cx - 3.5f * s, cy - 9f * s); path.lineTo(cx + 3.5f * s, cy - 7f * s)
                path.lineTo(cx + 10f * s, cy - 9f * s); path.lineTo(cx + 10f * s, cy + 7f * s); path.lineTo(cx + 3.5f * s, cy + 9f * s)
                path.lineTo(cx - 3.5f * s, cy + 7f * s); path.lineTo(cx - 10f * s, cy + 9f * s); path.close()
                c.drawPath(path, pS)
                c.drawLine(cx - 3.5f * s, cy - 9f * s, cx - 3.5f * s, cy + 7f * s, pS); c.drawLine(cx + 3.5f * s, cy - 7f * s, cx + 3.5f * s, cy + 9f * s, pS)
            }
        }
    }

    /** the leaf (Lighter mode's sign) on Strip's 24-unit grid */
    fun leaf(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        path.reset()
        path.moveTo(cx - 8f * s, cy + 8f * s)
        path.cubicTo(cx - 9f * s, cy - 4f * s, cx - 1f * s, cy - 9f * s, cx + 9f * s, cy - 9f * s)
        path.cubicTo(cx + 9f * s, cy + 1f * s, cx + 4f * s, cy + 9f * s, cx - 8f * s, cy + 8f * s)
        path.close()
        c.drawPath(path, p)
        c.drawLine(cx - 8f * s, cy + 8f * s, cx + 4f * s, cy - 4f * s, p)
    }

    /** a memory chip: a square with its pins */
    private fun chip(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        c.drawRoundRect(RectF(cx - 6.5f * s, cy - 6.5f * s, cx + 6.5f * s, cy + 6.5f * s), 1.5f * s, 1.5f * s, p)
        c.drawRect(RectF(cx - 2.5f * s, cy - 2.5f * s, cx + 2.5f * s, cy + 2.5f * s), p)
        for (i in -1..1) {
            val o = i * 4f * s
            c.drawLine(cx + o, cy - 6.5f * s, cx + o, cy - 10f * s, p); c.drawLine(cx + o, cy + 6.5f * s, cx + o, cy + 10f * s, p)
            c.drawLine(cx - 6.5f * s, cy + o, cx - 10f * s, cy + o, p); c.drawLine(cx + 6.5f * s, cy + o, cx + 10f * s, cy + o, p)
        }
    }

    /** Memory's badge while Lighter mode is on: Top's badge disc with the leaf (the design: the choice's sign as the badge) */
    fun leafBadge(c: Canvas, r: RectF) {
        val cx = r.centerX(); val cy = r.centerY(); val rad = r.width() / 2f
        pF.color = Color.argb(215, 10, 10, 12); c.drawCircle(cx, cy, rad, pF)
        pS.strokeWidth = 2f; pS.color = Color.argb(220, 150, 126, 90); c.drawCircle(cx, cy, rad - 1f, pS)
        pS.color = Theme.gold; pS.strokeWidth = 2.2f
        leaf(c, cx, cy, rad / 14f, pS)
    }

}
