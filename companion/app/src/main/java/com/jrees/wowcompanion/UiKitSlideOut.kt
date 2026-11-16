package com.jrees.wowcompanion

import android.graphics.Canvas
import android.graphics.RectF

/**
 * UiKit.slideOut: A PANEL THAT SLIDES OUT OF THE THING IT IS FOR (the Macros room's builder, Spells s14: each pop-out comes out of its
 * chip; the design's "build UiKit.slideOut once and share it with the equip slide-out"). The panel grows from [anchor] (the chip's own
 * rectangle) to [to] over [SLIDE_MS], easing out, on the kit's solid menu ground, its content clipped to what is out so far. [t] = ms since
 * it opened (past SLIDE_MS = fully out). Returns the rectangle drawn now; [draw] gets the canvas and the FINAL rectangle (the content is laid
 * where it ends up and only uncovered by the slide, so nothing moves under the player's eye).
 */
const val SLIDE_MS = 170f

fun UiKit.slideOutAt(anchor: RectF, to: RectF, t: Float): RectF {
    val k = (t / SLIDE_MS).coerceIn(0f, 1f)
    val e = 1f - (1f - k) * (1f - k) * (1f - k)
    return RectF(anchor.left + (to.left - anchor.left) * e, anchor.top + (to.top - anchor.top) * e,
        anchor.right + (to.right - anchor.right) * e, anchor.bottom + (to.bottom - anchor.bottom) * e)
}

fun UiKit.slideOut(c: Canvas, anchor: RectF, to: RectF, t: Float, draw: (Canvas, RectF) -> Unit): RectF {
    val now = slideOutAt(anchor, to, t)
    menu(c, now, solid = true)
    c.save()
    c.clipRect(now)
    draw(c, to)
    c.restore()
    return now
}

/** the slide is still moving (the view asks for another frame) */
fun UiKit.sliding(t: Float): Boolean = t < SLIDE_MS
