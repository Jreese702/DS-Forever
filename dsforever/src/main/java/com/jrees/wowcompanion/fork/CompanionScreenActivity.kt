package com.jrees.wowcompanion.fork

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import com.jrees.wowcompanion.CompanionHost
import com.jrees.wowcompanion.HostShell

/**
 * SWAP SCREENS' BOTTOM WINDOW (GitHub #232; the user, 2026-11-14: "build it", untested - no such device here). With Settings > Swap
 * screens On the game is on the second screen and DS Forever on display 0; display 0, the default display, takes no Presentation
 * (Android adds presentation windows only to displays flagged for them), so DS Forever's window there is this activity. It hosts the
 * shared [CompanionHost] exactly as the Presentation does ([HostPresentation]): not focusable (the game keeps the controller), the same
 * text-field typing ([BottomTyping]), the same start-up key routes.
 *
 * Only [DsForeverScreen] opens it ([DsForeverScreen.adopt]: one that was not asked for closes at once) and closes it (the launcher's
 * pause / stop, as the Presentation). An activity is a focused APP on its display even when its window takes no focus, and a display
 * whose top activity has no focusable window would hold the controller's keys, so the game's task is brought back to the front each
 * time this one resumes and when typing ends ([DsForeverScreen.gameToFront]); typing brings this task to the front so the keyboard
 * gets the focus.
 */
class CompanionScreenActivity : Activity(), HostShell {
    private var host: CompanionHost? = null
    private var screen: DsForeverScreen? = null
    private var board: com.jrees.wowcompanion.stream.SignInBoard? = null
    private var frontAt = Long.MIN_VALUE / 2

    private val typer by lazy { BottomTyping(this, { window }) { on -> typingChanged(on) } }
    val typing: Boolean get() = screen != null && typer.typing

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(null)                     // never a saved state: DsForeverScreen opens a fresh one each time
        val s = DsForeverScreen.current
        @Suppress("DEPRECATION") val here = windowManager.defaultDisplay.displayId
        if (s == null || !s.adopt(this, here)) {
            Log.i(DsForeverScreen.TAG, "Swap screens: a bottom screen activity nobody asked for (display $here) closes")
            finish()
            return
        }
        screen = s
        window.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        )
        // #230: named as the Presentation is, so an input ANR line says which window stopped answering
        com.jrees.wowcompanion.WinTitle.apply(window, com.jrees.wowcompanion.WinTitle.BOTTOM)
        host = CompanionHost(this, this).also { it.onCreate(null) }
        // display 0 has system bars (the Presentation's screen has none): hidden, a swipe shows them for a moment
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        board(s.boardWanted)
        Log.i(DsForeverScreen.TAG, "Swap screens: DS Forever's activity up on display $here")
    }

    override fun onStart() { super.onStart(); host?.onStart() }

    override fun onResume() {
        super.onResume()
        // the game's display the top one again (its keys), unless a text field types here; at most once a second (no ping-pong)
        val now = SystemClock.elapsedRealtime()
        if (host != null && !typer.typing && now - frontAt >= 1_000L) { frontAt = now; screen?.gameToFront("bottom screen resumed") }
    }

    override fun onStop() {
        if (host != null && typer.typing) typer.focusable(false)
        host?.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        host?.onDestroy(); host = null
        board = null
        screen?.released(this)
        super.onDestroy()
    }

    /** B and Back are DS Forever's own (StartKeys, the rooms): the window stays until DsForeverScreen closes it */
    @Deprecated("Activity.onBackPressed")
    override fun onBackPressed() {}

    /** typing on: this task to the front (display 0 takes the focus, the keyboard comes); off: the game's task back to the front */
    private fun typingChanged(on: Boolean) {
        if (on) runCatching {
            getSystemService(android.app.ActivityManager::class.java)?.appTasks?.firstOrNull { it.taskInfo.taskId == taskId }?.moveToFront()
        }.onFailure { Log.w(DsForeverScreen.TAG, "bottom screen to the front: ${it.message}") }
        else screen?.gameToFront("typing done")
    }

    /** Streaming's Battle.net keyboard over the whole screen (as the Presentation's) */
    fun board(on: Boolean) {
        if (host == null) return
        if (on && board == null) board = com.jrees.wowcompanion.stream.SignInBoard(this) { DsForeverScreen.current?.signInClose?.invoke() }.also {
            addContentView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        } else if (!on) { board?.let { (it.parent as? ViewGroup)?.removeView(it) }; board = null }
    }
    fun boardPress(button: String) { board?.press(button) }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // #230: timed as the Presentation's touches (the bottom window's)
        val start = SystemClock.uptimeMillis()
        try {
            if (host != null) typer.touch(ev)
            return super.dispatchTouchEvent(ev)
        } finally {
            runCatching { com.jrees.wowcompanion.TouchLag.note(com.jrees.wowcompanion.TouchLag.BOTTOM, ev.actionMasked, ev.eventTime, start, SystemClock.uptimeMillis()) }
        }
    }

    /** a pad key that reaches this window (it holds the focus while a text field types) goes to the start-up pages first, as there */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        com.jrees.wowcompanion.PadDiag.bottomKey(event, typing)
        if (com.jrees.wowcompanion.StartKeys.route(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean =
        com.jrees.wowcompanion.StartKeys.motion(ev) || super.dispatchGenericMotionEvent(ev)

    override fun setHostContent(v: View) = setContentView(v)
    override fun hostGone(): Boolean = isFinishing || isDestroyed
    override fun recreateHost() { screen?.rebuild() }
    override fun hostWindow() = window
    override fun hostIntent(): Intent? = null
    override fun startHostActivity(i: Intent) = startActivity(i)
}
