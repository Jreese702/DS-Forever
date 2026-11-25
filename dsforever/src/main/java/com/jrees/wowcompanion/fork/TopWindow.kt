package com.jrees.wowcompanion.fork

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import com.jrees.wowcompanion.CompanionHost
import com.jrees.wowcompanion.HostShell
import com.jrees.wowcompanion.SingleLook
import com.jrees.wowcompanion.SingleScreen
import com.jrees.wowcompanion.SingleShell
import com.jrees.wowcompanion.SingleSub

/**
 * SINGLE SCREEN MODE'S WINDOW ON THE TOP SCREEN (SingleScreen; the user, 2026-11-25: "We need to rebuild all of our menus and features to
 * work on the top screen when single screen mode is selected"): the very CompanionHost the bottom Presentation hosts, in a Dialog on the
 * launcher's activity - an app window on display 0 over the game's surface. NOT an activity (the #383 / #384 lesson: two activities
 * fought over display 0's keys), NOT an overlay window (that needs a system permission). Never focusable, as the Presentation (the game
 * keeps the controller), but while a text field types ([BottomTyping]). Never a display mode or a frame rate of its own (that switches
 * the game's panel: StreamRate's job only).
 *
 * Its content: the dim over the game (black, [SingleLook.Look.dim]; a touch on it the host's [CompanionHost.singleDimTap]), the host's
 * content over it - the rooms in the centred 1240 x 1080 box (the host lays them, CompanionHost.boxed). What shows is the host's
 * [SingleLook] ([look]): HIDDEN = the decor GONE (no surface, no layer, every touch the game's), FULL = shown, its dim, touchable or not.
 */
internal class TopWindow(private val activity: Activity, private val screen: DsForeverScreen) :
    android.app.Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen), HostShell, BottomWindow, SingleShell {
    private var host: CompanionHost? = null
    /** between onCreate and onStop (Dialog.isShowing is false while the decor is GONE: the host must not think it is gone then) */
    private var up = false
    private var board: com.jrees.wowcompanion.stream.SignInBoard? = null
    private val main = Handler(Looper.getMainLooper())
    private lateinit var frame: FrameLayout
    private lateinit var dim: View
    private lateinit var hostSlot: FrameLayout
    private val subs = LinkedHashMap<String, SubWindow>()
    private var lookNow: SingleLook.Look? = null
    private var touchNow = true

    private val size: IntArray = run {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.windowManager.currentWindowMetrics.bounds.let { intArrayOf(it.width(), it.height()) }
        else { val p = android.graphics.Point(); @Suppress("DEPRECATION") activity.windowManager.defaultDisplay.getRealSize(p); intArrayOf(p.x, p.y) }
    }
    override val screenW: Int get() = size[0]
    override val screenH: Int get() = size[1]
    override val box: IntArray = SingleScreen.panelBox(size[0], size[1])
    override val otherDisplay: Int? get() = screen.otherScreen()

    override val displayId: Int get() = Display.DEFAULT_DISPLAY
    override fun close() = dismiss()

    /** Streaming's Battle.net keyboard over the whole screen (as the Presentation's) */
    override fun board(on: Boolean) {
        if (on && board == null) board = com.jrees.wowcompanion.stream.SignInBoard(context) { DsForeverScreen.current?.signInClose?.invoke() }.also {
            addContentView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        } else if (!on) { board?.let { (it.parent as? ViewGroup)?.removeView(it) }; board = null }
    }
    override fun boardPress(button: String) { board?.press(button) }

    /** browser login's page ([DsForeverScreen.browserPage]): over everything, and this window holds the focus while it shows */
    private val pageSlot = BottomPageSlot({ v -> addContentView(v, BottomPageSlot.FILL) }, { typer.typing }, { on -> typer.focusable(on) })
    override fun page(make: ((Context) -> View)?) {
        pageSlot.show(context, make)
        if (pageSlot.up) showDecor(true)                            // the page is over everything: the window shows while it is up
        else lookNow?.let { l -> lookNow = null; look(l) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val w = window
        w?.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        )
        // a Dialog dims what is behind it by default: the game shows through, our own dim decides
        w?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        w?.setDimAmount(0f)
        w?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setCancelable(false)
        com.jrees.wowcompanion.WinTitle.apply(w, com.jrees.wowcompanion.WinTitle.SINGLE)
        frame = FrameLayout(context)
        dim = View(context).apply {
            setBackgroundColor(Color.BLACK); alpha = 0f
            setOnClickListener { host?.singleDimTap() }
        }
        hostSlot = FrameLayout(context)
        frame.addView(dim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(hostSlot, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(frame)
        w?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        w?.attributes = w?.attributes?.also { a ->
            a.windowAnimations = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) a.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        // display 0 has system bars: hidden, a swipe shows them for a moment (Swap screens' activity does the same)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            w?.setDecorFitsSystemWindows(false)
            w?.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        up = true
        host = CompanionHost(context, this).also { it.onCreate(savedInstanceState); it.onStart() }
        densityLine()                                            // after the host: its onCreate starts DiagLog on a first start
    }

    /** P0's density check: both screens' dpi, once per window (DiagLog) */
    private fun densityLine() = runCatching {
        val top = context.resources.displayMetrics.densityDpi
        val other = otherDisplay?.let { id ->
            context.getSystemService(android.hardware.display.DisplayManager::class.java)?.getDisplay(id)
                ?.let { d -> activity.createDisplayContext(d).resources.displayMetrics.densityDpi }
        }
        com.jrees.wowcompanion.DiagLog.event("app", "screens: one screen window up (${screenW} x ${screenH}, $top dpi; the other screen " +
            (other?.let { "$it dpi" } ?: "none") + ")")
    }

    /** the text fields' focus rules ([BottomTyping]): while one types, this window holds display 0's focus (the IME comes there) */
    private val typer = BottomTyping(context, { window }) { on -> if (on) showDecor(true) else lookNow?.let { l -> lookNow = null; look(l) } }
    override val typing: Boolean get() = typer.typing

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // #230: timed as the bottom window's touches (MemWatch's line format: this window is ours)
        val start = android.os.SystemClock.uptimeMillis()
        try {
            if (!pageSlot.up) typer.touch(ev)
            return super.dispatchTouchEvent(ev)
        } finally {
            runCatching { com.jrees.wowcompanion.TouchLag.note(com.jrees.wowcompanion.TouchLag.BOTTOM, ev.actionMasked, ev.eventTime, start, android.os.SystemClock.uptimeMillis()) }
        }
    }

    /** a pad key that reaches THIS window (it holds the focus while a text field types) goes to the start-up pages first, as there */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        com.jrees.wowcompanion.PadDiag.bottomKey(event, typing)
        if (com.jrees.wowcompanion.StartKeys.route(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean =
        com.jrees.wowcompanion.StartKeys.motion(ev) || super.dispatchGenericMotionEvent(ev)

    override fun onStop() {
        if (typing) typer.focusable(false)
        pageSlot.drop()
        host?.let { it.onStop(); it.onDestroy() }
        host = null
        up = false
        main.removeCallbacksAndMessages(null)
        for (s in subs.values) s.close()
        subs.clear()
        super.onStop()
    }

    // ---- HostShell
    override fun setHostContent(v: View) { hostSlot.removeAllViews(); hostSlot.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
    override fun hostGone(): Boolean = !up
    override fun recreateHost() = screen.rebuild()
    override fun hostWindow() = window
    override fun hostIntent(): Intent? = null
    override fun startHostActivity(i: Intent) = context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    override fun hostSingle(): SingleShell = this

    // ---- SingleShell

    /** the decor's visibility IS the frame (HIDDEN = GONE: no surface); set before the window is added too (the first look comes from
     *  the host's onCreate, inside Dialog.show) */
    private fun showDecor(on: Boolean) {
        val d = window?.decorView ?: return
        val v = if (on) View.VISIBLE else View.GONE
        if (d.visibility != v) d.visibility = v
    }

    override fun look(l: SingleLook.Look) {
        if (lookNow == l) return
        lookNow = l
        val full = l.frame == SingleLook.Frame.FULL
        // the browser page and a typing field keep the window up whatever the look says
        showDecor(full || pageSlot.up || typer.typing)
        if (::dim.isInitialized) dim.alpha = l.dim
        val touch = l.touchable || pageSlot.up || !full
        if (touch != touchNow) {
            touchNow = touch
            window?.setFlags(if (touch) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        }
    }

    override fun sub(id: String): SingleSub = subs.getOrPut(id) { SubWindow(activity, id) }

    private val hintView by lazy { HintPlate(context) }
    private val hintOff = Runnable { hint(null) }
    override fun hint(text: String?, ms: Long) {
        if (!up) return
        main.removeCallbacks(hintOff)
        val s = sub("hint")
        if (text == null) { s.shown(false); return }
        hintView.text = text
        hintView.invalidate()
        s.put(hintView)
        val k = screenH / 1080f
        val w = (HINT_W * k).toInt(); val h = (HINT_H * k).toInt()
        val l = (screenW - w) / 2; val t = (HINT_Y * k).toInt()
        s.place(l, t, l + w, t + h)
        s.touchable(false)
        s.shown(true)
        if (ms > 0L) main.postDelayed(hintOff, ms)
    }

    /** the hint's plate: the kit's own (UiKit.singleHint), the words Tr'd there */
    private class HintPlate(ctx: Context) : View(ctx) {
        var text: String = ""
        override fun onDraw(c: Canvas) { com.jrees.wowcompanion.UiKit.singleHint(c, RectF(0f, 0f, width.toFloat(), height.toFloat()), text) }
    }

    companion object {
        /** the hint plate: 640 x 72 at y 216 on a 1080-line screen (below Trail's compass, y 4..80, and Hub's target bar under it) */
        const val HINT_W = 640f
        const val HINT_H = 72f
        const val HINT_Y = 216f
    }
}
