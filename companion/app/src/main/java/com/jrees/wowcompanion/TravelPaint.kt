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
            icon(c, p.kind, x, y, p.lit)
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

    /** one point's icon at ([x], [y]): a dark disc, the kind's glyph on it */
    fun icon(c: Canvas, kind: Travel.Kind, x: Float, y: Float, lit: Boolean) {
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
