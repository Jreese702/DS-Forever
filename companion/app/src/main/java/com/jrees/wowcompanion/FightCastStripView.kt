package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.view.View

/**
 * THE CAST BAR IN A TOP-SCREEN FIGHT, ON THE BOTTOM SCREEN (the user's ruling 2026-11-23: "When the controller moves to the top, the cast
 * bar should still show across the top of the bottom screen ... Same thing with the swing timer"). With In fights = Top screen and Cast bar
 * in fights = Bottom screen (the default) Home lays this strip across its very top while the band is away (HomeLayoutSolver.hasFightStrip,
 * HomePart.FIGHT_CAST); it paints the Cast view's own line into its box ([CastView.drawFightStrip]: the same state, art and code - the
 * cast bar with its name inside, the swing timer, both hands while dual wielding, the finishes and flashes), so it shows exactly when the
 * band's line would: a cast or a swing running, nothing otherwise. [on] while it carries the line (Home: away and through the slide);
 * draw only, no touch.
 */
class FightCastStripView(context: Context, private val cast: CastView) : View(context) {
    /** the strip carries the band's line now (CastView.lineAway) */
    var on = false
        set(v) { if (field != v) { field = v; invalidate() } }
    private val box = RectF()

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(c: Canvas) {
        if (!on || width <= 0 || height <= 0) return
        box.set(0f, 0f, width.toFloat(), height.toFloat())
        val next = cast.drawFightStrip(c, box)
        if (next == 0L) postInvalidateOnAnimation() else if (next != Long.MAX_VALUE) postInvalidateDelayed(next)
    }
}
