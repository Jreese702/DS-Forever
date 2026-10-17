package com.jrees.wowcompanion

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * THE TRAVEL LAYER DRAWN (b-travel-layer): a [Travel.Scene] on a map, for the map module, Home's small map and the Map room. Faint
 * lines under small icons: a boat's crossing dashed blue, a zeppelin's dashed tan, the tram dashed pale, a known flight a thin gold
 * line; a dock a little boat or balloon, the tram a car, a flight master wings (gold when known, grey when not); a hostile town's
 * dimmed. The view gives the projection (world yards to its pixels) and the field.
 */
object TravelPaint {
    // ---- THE GAME'S OWN PICTURES (b-travel-game-icons; the user: "Can you like use better or in-game icons for that?"), harvested on
    // the device (MapHarvestSteps.TRAVEL_ICONS, ui/travel/): the world map's taxi node pins, the ferry pin, the cart, the flight map's
    // route line, the airship icon. Until they are there, the drawn ones below.
    const val DB_ATLAS = 897470
    const val DB_ATLAS_ELEMENT = 1989276
    const val DB_ATLAS_MEMBER = 897532
    /** file -> the game's atlas member (UiTextureAtlasMember) */
    val ART = mapOf(
        "fm_alliance" to "taxinode_alliance", "fm_horde" to "taxinode_horde", "fm_neutral" to "taxinode_neutral",
        "fm_unknown" to "taxi_frame_gray", "boat" to "flightmasterferry", "tram" to "vehicle-alliancecart", "line" to "!UI-Taxi-Line")
    /** file -> the game's picture by path (no map pin for a zeppelin: the airship's icon, cut round) */
    val ICONS = mapOf("air" to "Interface\\Icons\\achievement_dungeon_hordeairship.blp")

    private val art = HashMap<String, android.graphics.Bitmap?>()
    private var artAt = 0L
    /** a harvested picture, or null (read once; asked again every few seconds while the harvest has not written it) */
    private fun pic(name: String): android.graphics.Bitmap? {
        art[name]?.let { return it }
        val now = android.os.SystemClock.elapsedRealtime()
        if (art.containsKey(name) && now - artAt < 5_000L) return null
        artAt = now
        val f = ArtHarvest.file("ui/travel/$name.png")
        val b = if (f.isFile) runCatching { android.graphics.BitmapFactory.decodeFile(f.path) }.getOrNull() else null
        art[name] = b
        return b
    }
    private val pBmp by lazy { Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG) }
    private val dst = RectF()

    private val cBoat = 0xFF8EC9F0.toInt()
    private val cAir = 0xFFE2B866.toInt()
    private val cTram = 0xFFE8E4DA.toInt()
    private val cFly = 0xFFFFD100.toInt()
    private val cFlyDim = 0xFF8A8A8A.toInt()

    private val pLine by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND } }
    private val pFill by lazy { Paint(Paint.ANTI_ALIAS_FLAG) }
    private val pText by lazy { UiKit.bodyPaint(20f, 0xFFFFFFFF.toInt()).apply { textAlign = Paint.Align.CENTER } }
    private val path = Path()
    private val dashBoat by lazy { DashPathEffect(floatArrayOf(14f, 10f), 0f) }
    private val dashTram by lazy { DashPathEffect(floatArrayOf(8f, 8f), 0f) }

    private const val LABEL_W = 150f          // two names this close (px) would overlap: the later one is left out
    private const val LABEL_H = 40f
    /** the icons' half size, px */
    const val R = 14f

    private fun a(col: Int, k: Float) = (col and 0x00FFFFFF) or ((((col ushr 24) and 0xFF) * k).toInt().coerceIn(0, 255) shl 24)

    /**
     * [s]'s lines and points on continent [cont]: ([sx], [sy]) world yards (wx, wy) to pixels, inside [field]. [compact] (Home's small
     * map): no lines, only the points [near] says; [skip] a point the view already draws (the game's own flight point on the module);
     * [labels] the points' names under them. Returns the points drawn and where (for the taps).
     */
    fun draw(c: Canvas, s: Travel.Scene, cont: Int, sx: (Double, Double) -> Float, sy: (Double, Double) -> Float, field: RectF,
             compact: Boolean, labels: Boolean, near: (Travel.Point) -> Boolean = { true },
             skip: (Travel.Point, Float, Float) -> Boolean = { _, _, _ -> false }): List<Pair<Travel.Point, FloatArray>> {
        val save = c.save()
        c.clipRect(field)
        if (!compact) for (l in s.lines) {
            if (l.cont != cont) continue
            val x0 = sx(l.ax, l.ay); val y0 = sy(l.ax, l.ay); val x1 = sx(l.bx, l.by); val y1 = sy(l.bx, l.by)
            if (maxOf(x0, x1) < field.left || minOf(x0, x1) > field.right || maxOf(y0, y1) < field.top || minOf(y0, y1) > field.bottom) continue
            val k = if (l.lit) 1f else 0.4f
            if (texLine(c, l, x0, y0, x1, y1, k)) continue
            when (l.kind) {
                Travel.Kind.BOAT -> { pLine.pathEffect = dashBoat; pLine.strokeWidth = 3f; pLine.color = a(cBoat, 0.55f * k) }
                Travel.Kind.AIR -> { pLine.pathEffect = dashBoat; pLine.strokeWidth = 3f; pLine.color = a(cAir, 0.55f * k) }
                Travel.Kind.TRAM -> { pLine.pathEffect = dashTram; pLine.strokeWidth = 4f; pLine.color = a(cTram, 0.7f * k) }
                Travel.Kind.FLIGHT -> { pLine.pathEffect = null; pLine.strokeWidth = 2f; pLine.color = a(cFly, 0.3f * k) }
            }
            c.drawLine(x0, y0, x1, y1, pLine)
        }
        pLine.pathEffect = null
        val out = ArrayList<Pair<Travel.Point, FloatArray>>()
        val labelled = ArrayList<FloatArray>()                     // the names drawn: one place, one name (Menethil's two piers and its master)
        // the dim ones first, so a known flight master or a friendly dock is on top
        for (p in s.points.sortedBy { if (it.lit) 1 else 0 }) {
            if (p.cont != cont || !near(p)) continue
            val x = sx(p.wx, p.wy); val y = sy(p.wx, p.wy)
            if (x < field.left - R || x > field.right + R || y < field.top - R || y > field.bottom + R) continue
            if (skip(p, x, y)) continue
            icon(c, p.kind, x, y, p.lit, p.fac)
            if (labels && labelled.none { Math.abs(it[0] - x) < LABEL_W && Math.abs(it[1] - y) < LABEL_H }) {
                labelled.add(floatArrayOf(x, y))
                pText.color = if (p.lit) 0xFFF2E6C8.toInt() else 0xFFA09A8E.toInt()
                pText.setShadowLayer(3f, 0f, 0f, Color.BLACK)
                c.drawText(p.name, x, y + R + 20f, pText)
            }
            out.add(p to floatArrayOf(x, y))
        }
        c.restoreToCount(save)
        return out
    }

    /**
     * THE GAME'S ROUTE LINE (!UI-Taxi-Line, a vertical strip): laid along the segment, a known flight whole and faint, a crossing in
     * dashes tinted by its kind (a boat's blue, an airship's tan, the tram's pale); false = no picture yet (the drawn line instead)
     */
    private fun texLine(c: Canvas, l: Travel.Line, x0: Float, y0: Float, x1: Float, y1: Float, k: Float): Boolean {
        val line = pic("line") ?: return false
        val len = Math.hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()).toFloat()
        if (len < 2f) return true
        val w = if (l.kind == Travel.Kind.FLIGHT) 16f else 20f
        val save = c.save()
        c.translate(x0, y0); c.rotate(Math.toDegrees(Math.atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())).toFloat() - 90f)
        val tint = when (l.kind) { Travel.Kind.BOAT -> cBoat; Travel.Kind.AIR -> cAir; Travel.Kind.TRAM -> cTram; Travel.Kind.FLIGHT -> 0 }
        pBmp.colorFilter = if (tint != 0) android.graphics.PorterDuffColorFilter(tint, android.graphics.PorterDuff.Mode.MULTIPLY) else null
        pBmp.alpha = ((if (l.kind == Travel.Kind.FLIGHT) 175 else 235) * k).toInt()
        val src = android.graphics.Rect(0, 0, line.width, line.height)
        if (l.kind == Travel.Kind.FLIGHT) { dst.set(-w / 2, 0f, w / 2, len); c.drawBitmap(line, src, dst, pBmp) }
        else {
            var t = 0f
            while (t < len) { dst.set(-w / 2, t, w / 2, minOf(len, t + 18f)); c.drawBitmap(line, src, dst, pBmp); t += 28f }
        }
        pBmp.colorFilter = null; pBmp.alpha = 255
        c.restoreToCount(save)
        return true
    }

    /** the picture's size on the map, px */
    const val ART_PX = 34f

    /** one point's icon at ([x], [y]): the game's own pin ([fac] a flight master's side), else the drawn one */
    fun icon(c: Canvas, kind: Travel.Kind, x: Float, y: Float, lit: Boolean, fac: Int = 0) {
        val name = when (kind) {
            Travel.Kind.FLIGHT -> if (!lit) "fm_unknown" else when (fac) { TransportRoutes.ALLIANCE -> "fm_alliance"; TransportRoutes.HORDE -> "fm_horde"; else -> "fm_neutral" }
            Travel.Kind.BOAT -> "boat"; Travel.Kind.AIR -> "air"; Travel.Kind.TRAM -> "tram"
        }
        val b = pic(name)
        if (b != null) {
            pBmp.alpha = if (lit) 255 else if (kind == Travel.Kind.FLIGHT) 170 else 115
            if (kind == Travel.Kind.AIR) {
                // an icon, not a pin: cut round in a thin gold ring, a little smaller than the pins
                val r = ART_PX / 2f - 2f
                pFill.style = Paint.Style.FILL; pFill.color = Color.argb(pBmp.alpha, 12, 10, 8); c.drawCircle(x, y, r + 2f, pFill)
                val save = c.save()
                path.reset(); path.addCircle(x, y, r, Path.Direction.CW); c.clipPath(path)
                dst.set(x - r * 1.15f, y - r * 1.15f, x + r * 1.15f, y + r * 1.15f); c.drawBitmap(b, null, dst, pBmp)
                c.restoreToCount(save)
                pLine.pathEffect = null; pLine.strokeWidth = 2f; pLine.color = a(0xFFC9A14A.toInt(), pBmp.alpha / 255f); c.drawCircle(x, y, r, pLine)
            } else {
                val h = ART_PX / 2f
                dst.set(x - h, y - h * b.height / b.width.coerceAtLeast(1), x + h, y + h * b.height / b.width.coerceAtLeast(1))
                c.drawBitmap(b, null, dst, pBmp)
            }
            pBmp.alpha = 255
            return
        }
        drawn(c, kind, x, y, lit)
    }

    /** the drawn icon (before the harvest has the game's): a dark disc, the kind's glyph on it */
    private fun drawn(c: Canvas, kind: Travel.Kind, x: Float, y: Float, lit: Boolean) {
        val k = if (lit) 1f else 0.55f
        pFill.style = Paint.Style.FILL
        pFill.color = Color.argb((200 * k).toInt(), 12, 10, 8); c.drawCircle(x, y, R, pFill)
        pLine.strokeWidth = 1.5f; pLine.color = a(0xFF6E5A3A.toInt(), k); c.drawCircle(x, y, R, pLine)
        when (kind) {
            Travel.Kind.BOAT -> {
                pFill.color = a(cBoat, k)
                path.reset(); path.moveTo(x - 9f, y + 2f); path.lineTo(x + 9f, y + 2f); path.lineTo(x + 6f, y + 7f); path.lineTo(x - 6f, y + 7f); path.close()
                c.drawPath(path, pFill)
                path.reset(); path.moveTo(x, y - 9f); path.lineTo(x + 7f, y); path.lineTo(x, y); path.close(); c.drawPath(path, pFill)
                pLine.color = a(cBoat, k); c.drawLine(x, y - 9f, x, y + 2f, pLine)
            }
            Travel.Kind.AIR -> {
                pFill.color = a(cAir, k)
                c.drawOval(x - 10f, y - 8f, x + 10f, y + 2f, pFill)
                c.drawRect(x - 3f, y + 4f, x + 3f, y + 7f, pFill)
                pLine.color = a(cAir, k); c.drawLine(x - 2f, y + 2f, x - 2f, y + 4f, pLine); c.drawLine(x + 2f, y + 2f, x + 2f, y + 4f, pLine)
            }
            Travel.Kind.TRAM -> {
                pFill.color = a(cTram, k)
                c.drawRoundRect(x - 9f, y - 7f, x + 9f, y + 3f, 3f, 3f, pFill)
                pFill.color = Color.argb((220 * k).toInt(), 12, 10, 8); c.drawRect(x - 6f, y - 5f, x + 6f, y - 1f, pFill)
                pFill.color = a(cTram, k); c.drawCircle(x - 5f, y + 6f, 2.2f, pFill); c.drawCircle(x + 5f, y + 6f, 2.2f, pFill)
            }
            Travel.Kind.FLIGHT -> {
                pFill.color = if (lit) cFly else a(cFlyDim, 0.9f)
                // two wings
                path.reset(); path.moveTo(x - 1f, y + 2f); path.lineTo(x - 11f, y - 6f); path.lineTo(x - 8f, y + 1f); path.lineTo(x - 3f, y + 5f); path.close()
                c.drawPath(path, pFill)
                path.reset(); path.moveTo(x + 1f, y + 2f); path.lineTo(x + 11f, y - 6f); path.lineTo(x + 8f, y + 1f); path.lineTo(x + 3f, y + 5f); path.close()
                c.drawPath(path, pFill)
                c.drawCircle(x, y + 1f, 2.5f, pFill)
            }
        }
    }
}
