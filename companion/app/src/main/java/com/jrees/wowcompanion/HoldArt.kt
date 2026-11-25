package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * HOLD TO CAST'S PICTURES (the user's pick 2026-11-24, "A: corner badge (Recommended)"; the study's mockups C:/dsf-work/design-hold-cast
 * A_states_x3 / A_hold_progress): one look everywhere a button with a hold spell is drawn (Home's band and diamonds, the Controller room's
 * left page, the Bind box's foot).
 *   - [badge]: the hold spell as a small round badge on the OUTER side of its cluster ([outer]: never over a neighbour's glyph), with its
 *     OWN cooldown sweep (the same dark pie as the tap spell's, clockwise from 12 o'clock);
 *   - [chargeRing] (m195, the user: "after like .25s of holding I want a charge up circle to appear around the little hold icon"):
 *     round the BADGE, from 0.25 s held filling clockwise to the hold point, then bright ("charged"); the big button wears no arc now;
 *   - [bigFace] (m195 THE TAKEOVER): when the hold fires the hold spell takes the big face until its cast and the GCD after it end
 *     (HoldCast.Takeover), easing back; round 1533: in the game's own gold CHECKED ring (CastView draws the face as a checked slot: the
 *     art Raptor Strike wears when it is queued normally - the user: "a great example of this is raptor strikes normal behavior"), no
 *     ring of our own any more;
 *   - [badge]'s states (m195; round 1533, the user: "The red outline is an eyesore we should just shade the ability like we do with the
 *     glyphs"): ONE shade of the hold spell's icon ([stateFilter]: grey not usable, RED out of range, blue not enough power), the brown
 *     border always (gold while held);
 *   - [chargeRing] (round 1533, the user: "same with the charge up dots, it should be a glowing ring around the skill"): a glowing gold
 *     ring filling round the badge to the fire point (the top screen draws the same ring, ActionBar nbar25);
 *   - [mark]: the hold mark (a ring three quarters filled: the progress arc in miniature) for the room's Hold row and the Bind box.
 * Pure drawing, no allocation per frame beyond the first.
 */
object HoldArt {

    private val GOLD_HI = Color.rgb(255, 226, 140)
    private val GOLD_GLOW = Color.argb(170, 255, 200, 80)
    private val BADGE_FILL = Color.rgb(40, 34, 26)
    private val BADGE_RING = Color.rgb(110, 89, 66)
    private val SWEEP = Color.argb(200, 0, 0, 0)
    private val CHARGE = Color.rgb(255, 196, 80)
    private val CHARGED = Color.rgb(255, 240, 170)
    private val CHARGE_HEAD = Color.rgb(255, 236, 170)

    /** round 1533: the badge icon's shades - the game's own unusable (0.4) and not enough power (0.5, 0.5, 1) (CastView's own values),
     *  and out of range RED (1.0, 0.32, 0.30: the icon still readable; the glyph's red on the big buttons) */
    val cfUnusable = android.graphics.LightingColorFilter(0x666666, 0)
    val cfRange = android.graphics.LightingColorFilter(0xFF524C, 0)
    val cfMana = android.graphics.LightingColorFilter(0x8080FF, 0)
    /** the hold spell's state ([u] usable, [m] not enough power, [r] in range: the add-on's hudS.fs) as the badge icon's one shade
     *  (HoldCast.badgeShade's order: grey, then red, then blue), null = none */
    fun stateFilter(u: Int, m: Int, r: Int): ColorFilter? = when (HoldCast.badgeShade(u, m, r)) {
        HoldCast.Shade.UNUSABLE -> cfUnusable
        HoldCast.Shade.RANGE -> cfRange
        HoldCast.Shade.MANA -> cfMana
        HoldCast.Shade.NONE -> null
    }

    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    private val pGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.argb(150, 0, 0, 0) }
    private val pBmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val clip = Path()

    /**
     * Where the badge sits for a button DRAWN at [drawn] (0..3 the D-pad's left / up / right / down, 4..7 the face diamond's left / top /
     * right / bottom), as a unit offset (x, y; y down) of the button's glyph-corner distance: the outer side of its cluster - the top
     * button's upper left, the left and bottom buttons' lower left, the right button's lower right (the glyph keeps the upper right).
     */
    fun outer(drawn: Int): Pair<Float, Float> = HoldCast.badgeSide(drawn)

    /** the hold mark: a ring with three quarters of it filled from 12 o'clock, a dot at its centre */
    fun mark(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        pStroke.color = Color.argb(160, Color.red(color), Color.green(color), Color.blue(color)); pStroke.strokeWidth = r * 0.16f
        c.drawCircle(cx, cy, r, pStroke)
        pStroke.color = color; pStroke.strokeWidth = r * 0.34f
        rect.set(cx - r, cy - r, cx + r, cy + r)
        c.drawArc(rect, -90f, 270f, false, pStroke)
        pFill.color = color
        c.drawCircle(cx, cy, r * 0.18f, pFill)
    }

    /**
     * The corner badge at ([cx], [cy]), radius [r]: its shadow, a dark disc, a brown ring, the hold spell's [icon] round (shaded by
     * [filter]: [stateFilter]), its cooldown ([sweep] = the remaining fraction 0..1, < 0 none) as a dark pie, and the ring lit gold
     * while the button is held ([lit]).
     */
    fun badge(c: Canvas, cx: Float, cy: Float, r: Float, icon: Bitmap?, sweep: Float, lit: Boolean, filter: ColorFilter? = null) {
        val s = c.save()
        c.drawCircle(cx + r * 0.05f, cy + r * 0.08f, r * 1.07f, pShadow)
        pFill.color = BADGE_FILL
        c.drawCircle(cx, cy, r, pFill)
        val ir = r * 0.87f
        if (icon != null) {
            clip.reset(); clip.addCircle(cx, cy, ir, Path.Direction.CW)
            c.save(); c.clipPath(clip)
            rect.set(cx - ir * 1.08f, cy - ir * 1.08f, cx + ir * 1.08f, cy + ir * 1.08f)      // the icon's own dark rim cropped
            pBmp.colorFilter = filter
            c.drawBitmap(icon, null, rect, pBmp)
            pBmp.colorFilter = null
            if (sweep > 0f) {
                pFill.color = SWEEP
                rect.set(cx - ir * 1.5f, cy - ir * 1.5f, cx + ir * 1.5f, cy + ir * 1.5f)
                c.drawArc(rect, -90f + 360f * (1f - sweep), 360f * sweep, true, pFill)
            }
            c.restore()
        }
        pStroke.color = if (lit) GOLD_HI else BADGE_RING; pStroke.strokeWidth = r * 0.13f
        c.drawCircle(cx, cy, r - pStroke.strokeWidth / 2f, pStroke)
        c.restoreToCount(s)
    }

    /**
     * The hold's progress round the button: a gold arc from 12 o'clock over [frac] (0..1) of a ring between [rIn] and [rOut] (a circle,
     * or a rounded square of those half sides when not [circle]), with its soft glow.
     */
    fun progress(c: Canvas, cx: Float, cy: Float, rIn: Float, rOut: Float, frac: Float, circle: Boolean) {
        if (frac <= 0f) return
        val w = rOut - rIn
        val rm = (rIn + rOut) / 2f
        val sweep = 360f * frac.coerceIn(0f, 1f)
        if (circle) {
            rect.set(cx - rm, cy - rm, cx + rm, cy + rm)
            pGlow.color = GOLD_GLOW; pGlow.strokeWidth = w * 1.8f; pGlow.maskFilter = BlurMaskFilter(w, BlurMaskFilter.Blur.NORMAL)
            c.drawArc(rect, -90f, sweep, false, pGlow)
            pGlow.maskFilter = null
            pStroke.color = GOLD_HI; pStroke.strokeWidth = w
            c.drawArc(rect, -90f, sweep, false, pStroke)
        } else {
            // a square ring: the pie of the sweep clipped to the ring between the two rounded squares
            val s = c.save()
            clip.reset()
            clip.addRoundRect(RectF(cx - rOut, cy - rOut, cx + rOut, cy + rOut), rOut * 0.2f, rOut * 0.2f, Path.Direction.CW)
            clip.addRoundRect(RectF(cx - rIn, cy - rIn, cx + rIn, cy + rIn), rIn * 0.15f, rIn * 0.15f, Path.Direction.CCW)
            c.clipPath(clip)
            pFill.color = GOLD_HI
            val big = rOut * 1.6f
            rect.set(cx - big, cy - big, cx + big, cy + big)
            c.drawArc(rect, -90f, sweep, true, pFill)
            c.restoreToCount(s)
        }
    }

    /** the big face while the hold fires: the hold spell's [icon] over the button's own (a circle, or a rounded square) */
    fun bigFace(c: Canvas, cx: Float, cy: Float, half: Float, icon: Bitmap?, circle: Boolean, alpha: Float = 1f, sweep: Float = -1f) {
        if (icon == null) return
        val s = c.save()
        clip.reset()
        if (circle) clip.addCircle(cx, cy, half, Path.Direction.CW)
        else clip.addRoundRect(RectF(cx - half, cy - half, cx + half, cy + half), half * 0.15f, half * 0.15f, Path.Direction.CW)
        c.clipPath(clip)
        rect.set(cx - half, cy - half, cx + half, cy + half)
        pBmp.alpha = (255 * alpha.coerceIn(0f, 1f)).toInt()
        c.drawBitmap(icon, null, rect, pBmp)
        pBmp.alpha = 255
        // m195: its own cooldown / the GCD after it, as the face's dark pie (clockwise from 12 o'clock)
        if (sweep > 0f) {
            pFill.color = Color.argb((200 * alpha.coerceIn(0f, 1f)).toInt(), 0, 0, 0)
            rect.set(cx - half * 1.5f, cy - half * 1.5f, cx + half * 1.5f, cy + half * 1.5f)
            c.drawArc(rect, -90f + 360f * (1f - sweep), 360f * sweep, true, pFill)
        }
        c.restoreToCount(s)
    }

    /**
     * m195 THE CHARGE RING round the badge at ([cx], [cy]) of radius [r] (round 1533: it GLOWS - the user: "it should be a glowing ring
     * around the skill"): [frac] 0..1 filled clockwise from 12 o'clock over a faint track, a soft gold glow under the filled arc that
     * brightens as it fills and a bright head at its end; [charged] = all of it, bright, in a full glow (the fire waits for the add-on's
     * keep word, or there is no early fire: let go now and it is the hold). The same ring as the top screen's (ActionBar nbar25).
     */
    fun chargeRing(c: Canvas, cx: Float, cy: Float, r: Float, frac: Float, charged: Boolean) {
        if (frac < 0f) return
        val w = r * 0.26f
        val rm = r + w * 0.9f
        rect.set(cx - rm, cy - rm, cx + rm, cy + rm)
        pStroke.color = Color.argb(115, 30, 22, 12); pStroke.strokeWidth = w
        c.drawCircle(cx, cy, rm, pStroke)
        if (charged) {
            pGlow.color = Color.argb(220, 255, 200, 80); pGlow.strokeWidth = w * 2.4f
            pGlow.maskFilter = blur(w * 1.1f)
            c.drawCircle(cx, cy, rm, pGlow)
            pGlow.maskFilter = null
            pStroke.color = CHARGED; pStroke.strokeWidth = w
            c.drawCircle(cx, cy, rm, pStroke)
            return
        }
        val f = frac.coerceIn(0f, 1f)
        val sweep = 360f * f
        if (f > 0f) {
            pGlow.color = Color.argb((90 + 130 * f).toInt(), 255, 200, 80); pGlow.strokeWidth = w * 2.2f
            pGlow.maskFilter = blur(w * 1.1f)
            c.drawArc(rect, -90f, sweep, false, pGlow)
            pGlow.maskFilter = null
            pStroke.color = CHARGE; pStroke.strokeWidth = w
            c.drawArc(rect, -90f, sweep, false, pStroke)
        }
        if (f > 0.02f) {
            val a = Math.toRadians((-90.0 + sweep))
            pFill.color = CHARGE_HEAD
            c.drawCircle(cx + rm * Math.cos(a).toFloat(), cy + rm * Math.sin(a).toFloat(), w * 0.6f, pFill)
        }
    }

    /** the glow's blur, one per radius (no allocation per frame) */
    private var blurR = -1f
    private var blurF: BlurMaskFilter? = null
    private fun blur(rad: Float): BlurMaskFilter {
        val b = blurF
        if (b != null && rad == blurR) return b
        return BlurMaskFilter(rad.coerceAtLeast(0.5f), BlurMaskFilter.Blur.NORMAL).also { blurF = it; blurR = rad }
    }
}
