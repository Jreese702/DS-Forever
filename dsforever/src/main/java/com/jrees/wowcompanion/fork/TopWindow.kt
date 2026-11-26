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
 * THE BOTTOM SCREEN COMES STRAIGHT BACK (BottomSync, 2026-11-25): a minimize or a sleep PARKS it as the bottom Presentation ([park]: the
 * decor and its small windows GONE whatever the look says, never touchable, nothing kept awake, the host away) and the return shows it
 * again with the look it had ([unpark]); an onStop without our [close] is Android's ([DsForeverScreen.lost]).
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
    /** our own close ([close]); kept while away ([park]) */
    private var closing = false
    private var parkedNow = false

    /** the activity's window size now (the host lays its views by the size made with: [stale] when it is no more this) */
    private fun liveSize(): IntArray =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.windowManager.currentWindowMetrics.bounds.let { intArrayOf(it.width(), it.height()) }
        else { val p = android.graphics.Point(); @Suppress("DEPRECATION") activity.windowManager.defaultDisplay.getRealSize(p); intArrayOf(p.x, p.y) }
    private val size: IntArray = liveSize()
    /** the display this window was made on */
    private val madeOn: Int = activity.display?.displayId ?: Display.DEFAULT_DISPLAY
    override val screenW: Int get() = size[0]
    override val screenH: Int get() = size[1]
    override val box: IntArray = SingleScreen.panelBox(size[0], size[1])
    override val otherDisplay: Int? get() = screen.otherScreen()

    /** the review (2026-11-25): the real display (a window made while the activity was on the bottom screen said 0) */
    override val displayId: Int get() = activity.display?.displayId ?: Display.DEFAULT_DISPLAY
    /** the activity moved to another display or another size since this window was made: DsForeverScreen makes it again (its host's
     *  box, docks and strips are laid for the size it was made at) */
    fun stale(): Boolean = displayId != madeOn || !liveSize().contentEquals(size)
    override fun close() { closing = true; runCatching { dismiss() } }
    override val alive: Boolean get() = up && !closing
    override val parked: Boolean get() = parkedNow
    /** up and attached (its decor may be GONE by the look: PLAY shows nothing of this window) */
    override val drawn: Boolean get() = up && window?.decorView?.isAttachedToWindow == true
    override fun snapshot(why: String) = host?.snapshot(why)

    override fun park(why: String): Boolean {
        if (!up || closing) return false
        if (parkedNow) return true
        if (typing) typer.focusable(false)
        parkedNow = true
        window?.setFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        touchNow = false
        window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // round 2 (the review: "Parking toggles the decor's View visibility, so rooms reset as if left"): the host away FIRST, then the
        // decor and its small windows GONE inside BottomSync.flip (a room's visibility handler keeps its state: a park is no room exit)
        host?.park(why)
        com.jrees.wowcompanion.BottomSync.flip { showDecor(false); for (s in subs.values) s.park(true) }
        return true
    }

    override fun unpark(why: String) {
        if (!parkedNow || !up) return
        // round 2: the host back FIRST (a look it asks for meanwhile waits: still parked), then the decor inside the flip
        host?.unpark(why)
        parkedNow = false
        com.jrees.wowcompanion.BottomSync.flip {
            for (s in subs.values) s.park(false)
            // the look it had (or the plain window, before any look): its decor, its dim, its touch
            val l = lookNow
            if (l != null) { lookNow = null; look(l) }
            else { showDecor(true); window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE); touchNow = true }
        }
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // the review: the browser sign-in page open at the park takes the keys again (the park made the window not focusable, and a touch
        // on the page never gives it back: BottomPageSlot), as the bottom Presentation's return does
        if (pageSlot.up) typer.focusable(true)
    }

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
        if (parkedNow && typing) typer.focusable(false)          // parked: never focusable (the page takes the keys at the return)
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
            // the review (2026-11-25): a touch on our never-focused window never moves Android's keys back to display 0 - while the other
            // screen has them, a touch here is the player's wish to play on this screen (DsForeverScreen.ourTouch: the game's task forward)
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) screen.ourTouch()
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
        // Android closed it by itself (never our close): a snapshot for the window made again, and the way back
        if (!closing) {
            if (com.jrees.wowcompanion.LauncherHooks.gameUp() || com.jrees.wowcompanion.BottomSync.relaunchPending(android.os.SystemClock.elapsedRealtime()))
                host?.let { com.jrees.wowcompanion.BottomSync.keep(it.snapshot("closed by Android")) }
            com.jrees.wowcompanion.DiagLog.event("app", "one screen window closed by Android")
            screen.lost(this, "closed by Android")
        }
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

    /** a [show] that failed after onCreate: its host goes with it (never a second one beside the next), its snapshot given back first
     *  (CompanionHost.abandon: the review) */
    fun abandon() {
        closing = true; up = false
        pageSlot.drop()
        runCatching { host?.abandon() }.onFailure { android.util.Log.w(DsForeverScreen.TAG, "the abandoned top window's host: ${it.message}", it) }
        host = null
        main.removeCallbacksAndMessages(null)
        for (s in subs.values) s.close()
        subs.clear()
    }

    // ---- HostShell
    override fun setHostContent(v: View) { hostSlot.removeAllViews(); hostSlot.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
    override fun hostGone(): Boolean = !up
    override fun recreateHost() = screen.rebuild()
    override fun hostWindow() = window
    override fun hostIntent(): Intent? = null
    override fun startHostActivity(i: Intent) = context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    override fun hostSingle(): SingleShell = this
    override fun hostRestore() = com.jrees.wowcompanion.BottomSync.take(android.os.SystemClock.elapsedRealtime(), com.jrees.wowcompanion.LauncherHooks.gameStartSec)

    // ---- SingleShell

    /** the decor's visibility IS the frame (HIDDEN = GONE: no surface); set before the window is added too (the first look comes from
     *  the host's onCreate, inside Dialog.show) */
    private fun showDecor(want: Boolean) {
        val d = window?.decorView ?: return
        val on = want && !parkedNow                                   // parked: GONE whatever asks (the look, the page, a field)
        val v = if (on) View.VISIBLE else View.GONE
        if (d.visibility != v) d.visibility = v
    }

    override fun look(l: SingleLook.Look) {
        if (lookNow == l) return
        lookNow = l
        if (parkedNow) return                                     // kept while away: the look is applied at the return ([unpark])
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

    override fun sub(id: String): SingleSub = subs.getOrPut(id) { SubWindow(activity, id) { screen.ourTouch() } }

    private val hintView by lazy { HintPlate(context) }
    /** the review (2026-11-25): one plate, two kinds of words - pinned (ms 0, "Tap the game for the controller") and timed notes over it;
     *  a timed note's end shows the pinned words again (SingleScreen.Plate) */
    private val plate = SingleScreen.Plate()
    private val hintOff = Runnable { showPlate(plate.timedEnd()) }
    override fun hint(text: String?, ms: Long) {
        if (!up) return
        val now = plate.set(text, ms)
        if (ms > 0L) { main.removeCallbacks(hintOff); if (text != null) main.postDelayed(hintOff, ms) }
        showPlate(now)
    }

    private fun showPlate(text: String?) {
        if (!up) return
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
