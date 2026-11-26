package com.jrees.wowcompanion.fork

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import com.jrees.wowcompanion.SingleSub

/**
 * SINGLE SCREEN MODE'S SMALL WINDOWS (SingleShell.sub: PLAY's docks, NPC's strip, CORE's hint plate): one more never-focused Dialog on
 * the launcher's activity (display 0, over the top window: made later, stacked above it), WRAP-sized, placed by [place] in top-screen
 * px with ONE window update, shown / gone by its decor's visibility (gone = no surface). It hosts one view ([put]). Never a display mode
 * or a frame rate of its own (the game's panel mode is StreamRate's alone).
 */
internal class SubWindow(activity: Activity, private val id: String, private val onDown: () -> Unit = {}) :
    android.app.Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen), SingleSub {
    private val slot = FrameLayout(activity)
    private var bounds = intArrayOf(0, 0, 0, 0)
    private var on = false
    private var touch = true
    /** the top window kept while away (TopWindow.park, BottomSync): GONE whatever [shown] says */
    private var parked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val w = window ?: return
        w.setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        w.setDimAmount(0f)
        w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setCancelable(false)
        setContentView(slot, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        w.attributes = w.attributes.also { a ->
            a.gravity = Gravity.TOP or Gravity.START
            a.windowAnimations = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) a.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            a.x = bounds[0]; a.y = bounds[1]
            a.width = (bounds[2] - bounds[0]).coerceAtLeast(1); a.height = (bounds[3] - bounds[1]).coerceAtLeast(1)
            a.title = "DSF one screen $id"
        }
        if (!touch) w.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        w.decorView.visibility = if (on) View.VISIBLE else View.GONE
    }

    /** the review (2026-11-25): a touch DOWN here is the top window's too ([onDown]: DsForeverScreen.ourTouch, the keys back) */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) onDown()
        return super.dispatchTouchEvent(ev)
    }

    override fun put(v: View?) {
        if (v != null && v.parent === slot) return
        slot.removeAllViews()
        if (v == null) return
        (v.parent as? ViewGroup)?.removeView(v)
        slot.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun place(l: Int, t: Int, r: Int, b: Int) {
        if (bounds[0] == l && bounds[1] == t && bounds[2] == r && bounds[3] == b) return
        bounds = intArrayOf(l, t, r, b)
        val w = window ?: return
        w.attributes = w.attributes.also { a -> a.x = l; a.y = t; a.width = (r - l).coerceAtLeast(1); a.height = (b - t).coerceAtLeast(1) }
    }

    /** added to the window manager on the first [shown] true; after that its decor's visibility alone */
    private var added = false

    override fun shown(on: Boolean) {
        this.on = on
        if (!added) { if (on && !parked) { added = true; show() }; return }
        window?.decorView?.visibility = if (on && !parked) View.VISIBLE else View.GONE
    }

    /** kept while away ([on]) or back: back as [shown] last said */
    fun park(on: Boolean) {
        parked = on
        if (!added) { if (!on && this.on) { added = true; show() }; return }
        window?.decorView?.visibility = if (!on && this.on) View.VISIBLE else View.GONE
    }

    override fun touchable(on: Boolean) {
        if (touch == on) return
        touch = on
        if (added) window?.setFlags(if (on) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
    }

    /** the top window went: this one with it */
    fun close() { if (added) runCatching { dismiss() }; added = false }
}
