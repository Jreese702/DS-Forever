package com.jrees.wowcompanion

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Picture
import android.view.View
import android.view.ViewGroup

/**
 * SINGLE SCREEN MODE'S TRANSITIONS (the 1535 polish; the user on test 1533, 2026-11-26: "Right now everything kind of pops open, we need to
 * have smooth transitions for everything"). Every window of single screen mode fades: the top window (TopWindow: a room, the pause wheel,
 * an NPC window, Home's glance, a quick wheel, the start pages - [IN_MS] in with the panel rising [RISE] px, [OUT_MS] out), its dim
 * easing to each look's ([DIM_MS]), and every small window (fork/SubWindow: the play docks, the strips, the hint plate - [SUB_IN_MS] /
 * [SUB_OUT_MS]). A window going away fades its LAST PICTURE ([Ghost]: what it showed, recorded as it goes - the host has already given
 * the views to the next state by then, so the views themselves cannot fade), so nothing it held is kept from the next state.
 * One curve (Motion.curve). The times are short: a press is answered at once (the first frame is the window's), the fade only finishes it.
 */
object SingleFade {
    const val IN_MS = 180L
    const val OUT_MS = 150L
    const val DIM_MS = 180L
    const val SUB_IN_MS = 160L
    const val SUB_OUT_MS = 140L
    /** the panel rises this far (px on a 1080-line screen) as it fades in */
    const val RISE = 16f

    /**
     * a picture of [views] as they look now (each at its place in [root]: [offsetIn], its alpha), recorded (no pixels:
     * a Picture of the draw calls) - null when none has a size or recording fails. [ground] draws first (the rooms column's own ground)
     */
    fun record(root: View, views: List<View>, ground: View? = null): Picture? = try {
        val w = root.width; val h = root.height
        if (w <= 0 || h <= 0) null else {
            val p = Picture()
            val c = p.beginRecording(w, h)
            var any = false
            ground?.takeIf { it.width > 0 && it.height > 0 }?.background?.let { bg ->
                val at = offsetIn(root, ground)
                c.save(); c.translate(at[0], at[1])
                bg.setBounds(0, 0, ground.width, ground.height); bg.draw(c); c.restore()
            }
            for (v in views) {
                if (v.width <= 0 || v.height <= 0 || v.alpha <= 0.01f) continue
                val at = offsetIn(root, v)
                c.save()
                c.translate(at[0], at[1])
                if (v.scaleX != 1f || v.scaleY != 1f) c.scale(v.scaleX, v.scaleY)
                val layer = if (v.alpha < 1f) c.saveLayerAlpha(0f, 0f, v.width.toFloat(), v.height.toFloat(), (v.alpha * 255).toInt()) else -1
                v.draw(c)
                if (layer >= 0) c.restoreToCount(layer)
                c.restore()
                any = true
            }
            p.endRecording()
            if (any) p else null
        }
    } catch (t: Throwable) { null }

    /** [v]'s top-left in [root]'s px: its parents' places and moves up to [root] (a view the host set GONE keeps its last place) */
    fun offsetIn(root: View, v: View): FloatArray {
        var x = 0f; var y = 0f
        var c: View? = v
        while (c != null && c !== root) {
            x += c.left + c.translationX; y += c.top + c.translationY
            val p = c.parent as? View
            if (p != null) { x -= p.scrollX; y -= p.scrollY }
            c = p
        }
        return floatArrayOf(x, y)
    }

    /** a view as it looks now, recorded at its own origin (a small window's slot) */
    fun recordSelf(v: View): Picture? = try {
        if (v.width <= 0 || v.height <= 0) null else Picture().also { p ->
            val c = p.beginRecording(v.width, v.height); v.draw(c); p.endRecording()
        }
    } catch (t: Throwable) { null }

    /** one ease from [from] to [to] over [ms] on Motion's curve: [step] each frame (the eased value), [end] when it lands (not when cut) */
    fun run(from: Float, to: Float, ms: Long, step: (Float) -> Unit, end: () -> Unit = {}): ValueAnimator =
        ValueAnimator.ofFloat(from, to).apply {
            duration = ms
            interpolator = Motion.curve()
            addUpdateListener { a -> step(a.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                private var cut = false
                override fun onAnimationCancel(animation: Animator) { cut = true }
                override fun onAnimationEnd(animation: Animator) { if (!cut) end() }
            })
            start()
        }

    /**
     * THE DOCK ZOOM (the tidy pass; the user on the 1537 build: "The transitions from like using the chat and minimap suck. They should
     * zoom in just like on the bottom screen"): the bottom screen's chat glide (CompanionHost.glideInto) on the top window. The box at
     * [g] (0 = the dock's [dock], 1 = the panel's [panel]; l, t, r, b, screen px) is lerped; the panel's content is drawn into it at ONE
     * scale (text keeps its shape) just big enough to fill it, its bottom-left on the box's, and cut to the box. Returns the view's
     * scale, its translation x / y (pivot 0, 0) and the cut in the view's own px (l, t, r, b). At 1: scale 1, no move, the panel whole.
     */
    fun zoomFit(dock: FloatArray, panel: FloatArray, g: Float): FloatArray {
        val k = g.coerceIn(0f, 1f)
        fun m(i: Int) = dock[i] + (panel[i] - dock[i]) * k
        val bl = m(0); val bt = m(1); val br = m(2); val bb = m(3)
        val pw = (panel[2] - panel[0]).coerceAtLeast(1f); val ph = (panel[3] - panel[1]).coerceAtLeast(1f)
        val sc = maxOf((br - bl) / pw, (bb - bt) / ph).coerceAtLeast(0.05f)
        val tx = bl - panel[0] * sc; val ty = bb - panel[3] * sc
        return floatArrayOf(sc, tx, ty, (bl - tx) / sc, (bt - ty) / sc, (br - tx) / sc, (bb - ty) / sc)
    }

    /** the zoom's times and its fade: the bottom screen's (Motion.MODULE_OPEN / MODULE_CLOSE on Motion.curve; HomeView.FADE_MS) */
    const val ZOOM_IN_MS = Motion.MODULE_OPEN
    const val ZOOM_OUT_MS = Motion.MODULE_CLOSE
    const val ZOOM_FADE_MS = 120f

    /** THE GHOST: a recorded picture drawn where it was, over the window's own content, while it fades; touches pass under it */
    class Ghost(ctx: Context) : View(ctx) {
        var pic: Picture? = null
            set(v) { field = v; visibility = if (v != null) View.VISIBLE else View.GONE; invalidate() }

        init { visibility = View.GONE; isClickable = false; isFocusable = false }

        override fun onDraw(c: Canvas) {
            val p = pic ?: return
            try { c.drawPicture(p) } catch (t: Throwable) { pic = null }
        }

        companion object {
            val FILL = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }
}
