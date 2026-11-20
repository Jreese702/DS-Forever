package com.jrees.wowcompanion

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * OPTION C, SITUATIONS FIRST (the user, 2026-11-20: the Controller room's redesign, `ctldesign/optionC_*`): the shared picture of one
 * situation's page and the drawn icons the kit has no picture for.
 *
 * THE PAGE (1240 x 960, the left page 8..740): a compact row of the situation's own settings (chips, [CHIP_TOP]); the section
 * "Controller" (a gamepad, [CTL_HEAD_Y]) with the controller drawn at [CTL_S] (0.8: the squares about 64 px, still finger-sized) by the
 * normal layers' own geometry ([PadDiamonds]) through [ctl] / [toLocal]; the section "Tap-to-cast bar" (a finger on a bar,
 * [BAR_HEAD_Y]) with the bar's 8 slots in one row ([tile], [well]). The right page stays the thing under the one cursor.
 *
 * THE ICONS ([Icon]): each situation always wears the same picture (a sheathed sword in a green ring, the game's crossed swords,
 * three heads, the radial), and each device too (a gamepad, a finger tapping a bar) - the comic-ink look (thick dark outline, cream /
 * gold fills), drawn as vectors so they stay sharp at any size.
 */
object SituationPage {
    /** the chips' row (one compact row of the situation's settings) */
    const val CHIP_TOP = 170f
    const val CHIP_H = 48f
    const val CHIP_X0 = 40f
    const val CHIP_X1 = 712f
    const val CHIP_GAP = 12f
    /** the "Controller" section's line, and the controller's own picture: [CTL_S] of the normal layers' geometry, its set row's top
     *  (local [LOCAL_TOP]) at [CTL_TOP] on screen, centred on the left page's middle [CX] */
    const val CTL_HEAD_Y = 246f
    const val CTL_S = 0.8f
    const val CX = 374f
    const val LOCAL_TOP = 280f
    const val CTL_TOP = 270f
    /** the "Tap-to-cast bar" section's line and the bar's 8 tiles */
    const val BAR_HEAD_Y = 738f
    const val TILE = 70f
    const val GAP = 12f
    const val BAR_Y = 776f
    val BAR_X0 = CX - (8 * TILE + 7 * GAP) / 2f
    /** the slots' numbers under the well */
    const val NUM_Y = 892f
    /** the left page's own right edge for the sections' rules */
    const val RULE_X1 = 712f

    /** the controller's picture: local (the normal layers' coordinates) -> screen */
    fun ctl(c: Canvas) {
        c.translate(CX, CTL_TOP); c.scale(CTL_S, CTL_S); c.translate(-CX, -LOCAL_TOP)
    }
    fun toLocalX(x: Float) = CX + (x - CX) / CTL_S
    fun toLocalY(y: Float) = LOCAL_TOP + (y - CTL_TOP) / CTL_S
    fun toScreen(r: RectF) = RectF(CX + (r.left - CX) * CTL_S, CTL_TOP + (r.top - LOCAL_TOP) * CTL_S,
        CX + (r.right - CX) * CTL_S, CTL_TOP + (r.bottom - LOCAL_TOP) * CTL_S)

    /** bar slot [p] (1-8) */
    fun tile(p: Int): RectF {
        val x = BAR_X0 + (p - 1).coerceIn(0, 7) * (TILE + GAP)
        return RectF(x, BAR_Y, x + TILE, BAR_Y + TILE)
    }
    fun well(): RectF = RectF(BAR_X0 - 18f, BAR_Y - 18f, BAR_X0 + 8 * TILE + 7 * GAP + 18f, BAR_Y + TILE + 18f)
    /** the bar's area for a finger (the well, its numbers) */
    fun barHit(x: Float, y: Float): Int {
        if (y < BAR_Y - 24f || y > BAR_Y + TILE + 30f) return -1
        for (p in 1..8) { val r = tile(p); if (x >= r.left - GAP / 2f && x <= r.right + GAP / 2f) return p }
        return -1
    }

    private val pRule = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f; color = Color.argb(200, 120, 80, 40) }
    private val pRuleHi = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1f; color = Color.argb(90, 255, 235, 200) }

    /** a page section heading: its picture, its word (ink), the book's rule to [x1]; returns where the rule starts */
    fun section(c: Canvas, label: String, icon: Icon?, y: Float, x0: Float = CHIP_X0, x1: Float = RULE_X1, lit: Boolean = false): Float {
        var tx = x0
        if (icon != null) { Icons2.draw(c, icon, x0 + 22f, y, 44f); tx = x0 + 54f }
        val p = UiKit.inkPaint(28f).apply { if (lit) color = Theme.parchmentInk }
        val word = Tr.t(label)
        c.drawText(word, tx, y - (p.ascent() + p.descent()) / 2f, p)
        val lx = tx + p.measureText(word) + 16f
        if (lx < x1) { c.drawLine(lx, y + 2f, x1, y + 2f, pRule); c.drawLine(lx, y + 4f, x1, y + 4f, pRuleHi) }
        return lx
    }

    private val pVeil = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 20, 16, 12) }
    private val pPlate = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 16, 12, 10) }
    private val pPlateEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.rgb(150, 120, 70) }
    private val pBmp = Paint(Paint.FILTER_BITMAP_FLAG)

    /** the bar's well veiled (locked): dimmed, a plate with the lock and ONE word over it */
    fun veil(c: Canvas, word: String) = veilAt(c, well(), word)
    fun veilAt(c: Canvas, w: RectF, word: String) {
        c.drawRoundRect(w, 14f, 14f, pVeil)
        val p = UiKit.bodyPaint(30f, Color.rgb(230, 215, 185))
        val t = Tr.t(word)
        while (p.textSize > 20f && p.measureText(t) > w.width() - 120f) p.textSize -= 1f
        val tw = p.measureText(t)
        val plate = RectF(w.centerX() - tw / 2f - 46f, w.centerY() - 28f, w.centerX() + tw / 2f + 22f, w.centerY() + 28f)
        c.drawRoundRect(plate, 12f, 12f, pPlate)
        c.drawRoundRect(plate, 12f, 12f, pPlateEdge)
        UiKit.tex("talent_lock")?.let { c.drawBitmap(it, null, RectF(plate.left + 14f, plate.centerY() - 17f, plate.left + 38f, plate.centerY() + 17f), pBmp) }
        c.drawText(t, plate.left + 48f, plate.centerY() - (p.ascent() + p.descent()) / 2f, p)
    }

    /** the pictures (situations, devices) */
    enum class Icon { OUT, FIGHT, PARTY, RADIAL, PAD, TAP }

    /** a situation view's picture */
    fun iconOf(top: String): Icon = when (top) { "combat" -> Icon.FIGHT; "party" -> Icon.PARTY; "radial" -> Icon.RADIAL; else -> Icon.OUT }
}

/** THE DRAWN ICONS (Option C): comic ink, vector. [draw] centres [icon] on (cx, cy) in a [size] box; [lit] = full colour, else muted */
object Icons2 {
    private val INK = Color.rgb(32, 20, 10)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pBmp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val path = Path()

    fun draw(c: Canvas, icon: SituationPage.Icon, cx: Float, cy: Float, size: Float, lit: Boolean = true, alpha: Int = 255) {
        c.save()
        c.translate(cx - size / 2f, cy - size / 2f)
        val s = size
        p.alpha = 255
        when (icon) {
            SituationPage.Icon.OUT -> peace(c, s, lit)
            SituationPage.Icon.FIGHT -> swords(c, s, lit)
            SituationPage.Icon.PARTY -> party(c, s, lit)
            SituationPage.Icon.RADIAL -> radial(c, s, lit)
            SituationPage.Icon.PAD -> pad(c, s, lit)
            SituationPage.Icon.TAP -> tap(c, s, lit)
        }
        c.restore()
        if (alpha < 255) {}                                                  // (the callers dim by drawing over: kept for symmetry)
    }

    private fun fill(col: Int) { p.style = Paint.Style.FILL; p.color = col }
    private fun rr(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float, col: Int) { fill(col); c.drawRoundRect(l, t, r, b, rad, rad, p) }
    private fun oval(c: Canvas, l: Float, t: Float, r: Float, b: Float, col: Int) { fill(col); c.drawOval(l, t, r, b, p) }

    /** Out of combat: a sword point-down in its sheath over a leaf-green ring */
    private fun peace(c: Canvas, s: Float, lit: Boolean) {
        val lw = s * 0.05f
        p.style = Paint.Style.STROKE; p.strokeWidth = lw * 3f; p.color = INK
        c.drawOval(s * 0.12f, s * 0.12f, s * 0.88f, s * 0.88f, p)
        p.strokeWidth = lw; p.color = if (lit) Color.rgb(120, 200, 110) else Color.rgb(160, 160, 150)
        c.drawOval(s * 0.12f + lw, s * 0.12f + lw, s * 0.88f - lw, s * 0.88f - lw, p)
        rr(c, s * 0.44f - lw, s * 0.30f - lw, s * 0.56f + lw, s * 0.84f + lw, s * 0.05f, INK)
        rr(c, s * 0.44f, s * 0.30f, s * 0.56f, s * 0.84f, s * 0.05f, if (lit) Color.rgb(150, 95, 50) else Color.rgb(140, 130, 120))
        rr(c, s * 0.30f - lw, s * 0.24f - lw, s * 0.70f + lw, s * 0.31f + lw, 0f, INK)
        rr(c, s * 0.30f, s * 0.24f, s * 0.70f, s * 0.31f, 0f, if (lit) Color.rgb(255, 210, 80) else Color.rgb(190, 180, 160))
        rr(c, s * 0.46f - lw, s * 0.08f - lw, s * 0.54f + lw, s * 0.25f + lw, s * 0.03f, INK)
        rr(c, s * 0.46f, s * 0.08f, s * 0.54f, s * 0.25f, s * 0.03f, if (lit) Color.rgb(200, 160, 100) else Color.rgb(170, 160, 150))
    }

    /** In combat: the game's own gold crossed swords (the kit), else a drawn pair */
    private fun swords(c: Canvas, s: Float, lit: Boolean) {
        val b = UiKit.tex("combat_swords")
        if (b != null) {
            pBmp.alpha = if (lit) 255 else 170
            c.drawBitmap(b, null, RectF(0f, 0f, s, s), pBmp)
            pBmp.alpha = 255
            return
        }
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
        for (k in 0..1) {
            val (x0, x1) = if (k == 0) 0.18f to 0.82f else 0.82f to 0.18f
            p.strokeWidth = s * 0.16f; p.color = INK; c.drawLine(s * x0, s * 0.18f, s * x1, s * 0.82f, p)
            p.strokeWidth = s * 0.08f; p.color = if (lit) Color.rgb(255, 210, 80) else Color.rgb(190, 180, 160)
            c.drawLine(s * x0, s * 0.18f, s * x1, s * 0.82f, p)
        }
        p.strokeCap = Paint.Cap.BUTT
    }

    /** Party mode: three heads and shoulders, the front one gold */
    private fun party(c: Canvas, s: Float, lit: Boolean) {
        val lw = s * 0.05f
        val cols = if (lit) intArrayOf(Color.rgb(150, 200, 255), Color.rgb(130, 230, 140), Color.rgb(255, 214, 90))
                   else IntArray(3) { Color.rgb(170, 165, 150) }
        val at = arrayOf(floatArrayOf(0.26f, 0.40f, 0.8f), floatArrayOf(0.74f, 0.40f, 0.8f), floatArrayOf(0.50f, 0.50f, 1.0f))
        for (i in 0..2) {
            val (cx, cy, k) = Triple(at[i][0] * s, at[i][1] * s, at[i][2])
            val hr = 0.12f * k * s; val sw = 0.24f * k * s; val sh = 0.20f * k * s
            oval(c, cx - hr - lw, cy - hr - lw, cx + hr + lw, cy + hr + lw, INK)
            fill(INK); c.drawArc(cx - sw - lw, cy + hr * 0.9f - lw, cx + sw + lw, cy + hr * 0.9f + 2 * sh + lw, 180f, 180f, true, p)
            oval(c, cx - hr, cy - hr, cx + hr, cy + hr, cols[i])
            fill(cols[i]); c.drawArc(cx - sw, cy + hr * 0.9f, cx + sw, cy + hr * 0.9f + 2 * sh, 180f, 180f, true, p)
        }
    }

    /** Spell radial: a dark wheel with eight places, the top one lit */
    private fun radial(c: Canvas, s: Float, lit: Boolean) {
        val lw = s * 0.05f
        val col = if (lit) Color.rgb(250, 210, 90) else Color.rgb(180, 170, 150)
        oval(c, s * 0.06f, s * 0.06f, s * 0.94f, s * 0.94f, INK)
        oval(c, s * 0.06f + lw, s * 0.06f + lw, s * 0.94f - lw, s * 0.94f - lw, Color.rgb(70, 55, 40))
        for (k in 0..7) {
            val a = Math.toRadians(k * 45.0 - 90.0)
            val x = s / 2f + Math.cos(a).toFloat() * s * 0.31f; val y = s / 2f + Math.sin(a).toFloat() * s * 0.31f
            val r = s * 0.075f
            oval(c, x - r - lw / 2f, y - r - lw / 2f, x + r + lw / 2f, y + r + lw / 2f, INK)
            oval(c, x - r, y - r, x + r, y + r, if (k == 0) col else if (lit) Color.rgb(200, 180, 140) else Color.rgb(150, 140, 130))
        }
        oval(c, s * 0.40f, s * 0.40f, s * 0.60f, s * 0.60f, col)
    }

    /** the Controller: a gamepad, ink outline, cream body, the D-pad and the four face buttons */
    private fun pad(c: Canvas, s: Float, lit: Boolean) {
        val body = if (lit) Color.rgb(250, 222, 140) else Color.rgb(190, 172, 140)
        val i = s * 0.06f
        rr(c, s * 0.10f, s * 0.26f, s * 0.90f, s * 0.62f, s * 0.17f, INK)
        oval(c, s * 0.06f, s * 0.36f, s * 0.40f, s * 0.86f, INK)
        oval(c, s * 0.60f, s * 0.36f, s * 0.94f, s * 0.86f, INK)
        rr(c, s * 0.10f + i, s * 0.26f + i, s * 0.90f - i, s * 0.62f - i, s * 0.17f - i, body)
        oval(c, s * 0.06f + i, s * 0.36f + i, s * 0.40f - i, s * 0.86f - i, body)
        oval(c, s * 0.60f + i, s * 0.36f + i, s * 0.94f - i, s * 0.86f - i, body)
        val cx = s * 0.29f; val cy = s * 0.47f; val a = s * 0.035f; val b = s * 0.10f
        rr(c, cx - a, cy - b, cx + a, cy + b, 0f, INK); rr(c, cx - b, cy - a, cx + b, cy + a, 0f, INK)
        val fx = s * 0.71f; val fy = s * 0.47f; val r = s * 0.045f
        for ((dx, dy) in listOf(0f to -0.085f, 0f to 0.085f, -0.085f to 0f, 0.085f to 0f))
            oval(c, fx + dx * s - r, fy + dy * s - r, fx + dx * s + r, fy + dy * s + r, INK)
    }

    /** Tap to cast: a dark bar of three slots (the middle one lit), a pointing hand pressing it, ripples */
    private fun tap(c: Canvas, s: Float, lit: Boolean) {
        val lw = s * 0.045f
        val by0 = s * 0.06f; val by1 = s * 0.36f
        rr(c, s * 0.02f, by0, s * 0.98f, by1, s * 0.06f, INK)
        for (k in 0..2) {
            val x0 = s * (0.07f + k * 0.31f)
            rr(c, x0, by0 + s * 0.05f, x0 + s * 0.24f, by1 - s * 0.05f, s * 0.03f,
                if (k == 1 && lit) Color.rgb(255, 205, 60) else if (lit) Color.rgb(125, 105, 85) else Color.rgb(115, 105, 95))
        }
        // the hand: index finger up into the lit slot, palm, curled fingers, thumb
        val hs = s * 0.66f; val hx = s * 0.50f - hs * 0.48f; val hy = by1 - s * 0.08f
        val skin = if (lit) Color.rgb(255, 236, 205) else Color.rgb(200, 188, 170)
        fun hand(col: Int, g: Float) {
            rr(c, hx + hs * 0.40f - g, hy + hs * 0.04f - g, hx + hs * 0.56f + g, hy + hs * 0.62f + g, hs * 0.08f + g, col)
            rr(c, hx + hs * 0.30f - g, hy + hs * 0.44f - g, hx + hs * 0.86f + g, hy + hs * 0.96f + g, hs * 0.16f + g, col)
            for (k in 0..2) { val x = hx + hs * (0.56f + k * 0.105f); rr(c, x - g, hy + hs * 0.40f - g, x + hs * 0.12f + g, hy + hs * 0.66f + g, hs * 0.06f + g, col) }
            rr(c, hx + hs * 0.12f - g, hy + hs * 0.50f - g, hx + hs * 0.42f + g, hy + hs * 0.66f + g, hs * 0.08f + g, col)
        }
        hand(INK, lw); hand(skin, 0f)
        p.style = Paint.Style.STROKE; p.strokeWidth = lw * 0.5f; p.color = INK
        for (k in 0..2) { val x = hx + hs * (0.56f + k * 0.105f); c.drawLine(x, hy + hs * 0.47f, x, hy + hs * 0.62f, p) }
    }
}
