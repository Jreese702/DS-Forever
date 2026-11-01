package com.jrees.wowcompanion.fork

import android.app.Activity
import android.app.ActivityOptions
import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.view.WindowManager
import com.jrees.wowcompanion.CompanionHost
import com.jrees.wowcompanion.HostShell
import com.jrees.wowcompanion.InputSink
import com.jrees.wowcompanion.LauncherHooks
import com.jrees.wowcompanion.PlayMode
import com.jrees.wowcompanion.TapSink
import com.jrees.wowcompanion.TapSinks
import com.jrees.wowcompanion.RootPolicy

/**
 * ALL OF DS FOREVER ON THE BOTTOM SCREEN, DRAWN BY THE LAUNCHER ITSELF (the private fork; the rooms refactor S2, 2026-10-08: the user
 * "(c) Rooms refactor", then "I kind of just want you to add it all in and I will test it all after"). While the launcher's game screen
 * is up, an android Presentation on the OTHER screen hosts [CompanionHost] - the very same piece the DS Forever app's MainActivity hosts:
 * Home, the bar, every room, the consumables wheel, the pause menu, the Chat room, the sign-in row.
 *
 * Inside the launcher there is NO ROOT (RootPolicy.allowed = false before the host exists): taps and keys go to the launcher's own X
 * server ([input] -> InputSink, 3b), the controller comes as a copy of the launcher's own pad events (PadFeed, 3c), the log is read with
 * plain IO, and the sign-in watcher asks the launcher instead of the system ([LauncherHooks], S4). One touch = one game action, as in
 * the app. No credential file, ever (the user's rule).
 *
 * The Presentation is NOT FOCUSABLE (the launcher's own pad's flags: the game keeps the focus; the P2 fix), never on the display the game
 * is on, and comes up a moment after the game screen; [moveToTop] puts a launcher opened from the bottom screen back on the top one.
 * S5 (the one app): DS Forever comes up with the launcher's activity ([begin] / [end] in its MainActivity; its start screens on the bottom
 * before Play), is hidden while the launcher is away ([hide] / [show] on pause / resume: Cocoon's bottom grid is the user's then), and
 * the game screen hands it the X server while the game runs ([attachGame] / [detachGame] in XServerScreen).
 */
class DsForeverScreen(private val context: Context) {
    /** the launcher's X server as DS Forever's input, while the game screen is up */
    private var input: InputSink.Sink? = null
    private var gameOn = false
    private val dm = context.getSystemService(DisplayManager::class.java)
    private var shown: HostPresentation? = null
    private val main = Handler(Looper.getMainLooper())
    private var running = false
    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = update()
        override fun onDisplayRemoved(displayId: Int) = update()
        override fun onDisplayChanged(displayId: Int) {}
    }

    fun start() {
        RootPolicy.allowed = false
        com.jrees.wowcompanion.LatLog.on = true          // smoothness A: the stage timestamps (lat.log), inside the launcher only
        // display 0's panel as TapBridge sizes it (landscape), so a tap's pixels turn back into a fraction of the game's screen
        dm?.getDisplay(Display.DEFAULT_DISPLAY)?.mode?.let { m ->
            InputSink.displayW = maxOf(m.physicalWidth, m.physicalHeight); InputSink.displayH = minOf(m.physicalWidth, m.physicalHeight)
        }
        // what the sign-in watcher would ask the system for, from the launcher itself (S4)
        LauncherHooks.pkg = context.packageName
        LauncherHooks.gameUp = { gameOn }
        LauncherHooks.inFront = { (context as? Activity)?.hasWindowFocus() == true }
        running = true
        dm?.registerDisplayListener(listener, main)
        main.postDelayed({ update() }, SHOW_AFTER_MS)
        Log.i(TAG, "start (launcher on display ${gameDisplay()})")
    }

    fun stop() {
        detachGame()
        running = false
        main.removeCallbacksAndMessages(null)
        try { dm?.unregisterDisplayListener(listener) } catch (_: Exception) {}
        shown?.dismiss(); shown = null
        Log.i(TAG, "stop")
    }

    /** the game screen is up: its X server becomes DS Forever's input, the traffic measurement runs */
    fun attachGame(sink: InputSink.Sink) {
        input = sink
        // Local: the container's X server behind ContainerKeys (TapSinks; every call passes straight through, as before)
        val route = TapSinks.wire(PlayMode.LOCAL, sink, null)
        gameOn = true
        LauncherHooks.gameStartSec = System.currentTimeMillis() / 1000
        lastRx = -1L; note("game screen up"); main.removeCallbacks(traffic); main.post(traffic)
        com.jrees.wowcompanion.GameLife.started()             // issue #12: the game's life in DS Forever's own log
        // the waiting screen's Keyboard, as in Streaming (the user, 2026-10-30: "On the local side I want that same keyboard button")
        signInClose = { signInBoard(false) }
        com.jrees.wowcompanion.SetupBridge.openPcKeyboard = { signInBoard(true) }
        Log.i(TAG, "game attached (input: $route)")
    }

    /**
     * STREAMING (S2): the stream to the PC is up: its keyboard + mouse channel (StreamKeys) becomes DS Forever's input. No traffic
     * measurement (that is the sign-in detector for the LOCAL game; here the bytes are the video stream's) and no native sign-in
     * (the player signs in on the PC).
     */
    fun attachStream(sink: TapSink) {
        input = sink
        val route = TapSinks.wire(PlayMode.STREAM, null, sink)
        gameOn = true
        LauncherHooks.gameStartSec = System.currentTimeMillis() / 1000
        note("stream up")
        Log.i(TAG, "stream attached (input: $route)")
    }

    /** Streaming's Battle.net keyboard on or off (it survives a new Presentation) */
    fun signInBoard(on: Boolean) { boardWanted = on; shown?.board(on) }
    var signInClose: (() -> Unit)? = null
    fun signInPress(button: String) { shown?.boardPress(button) }
    private var boardWanted = false

    /** the game screen went: no input until the next one */
    fun detachGame() {
        if (!gameOn) return
        gameOn = false; input = null; InputSink.sink = null
        com.jrees.wowcompanion.SetupBridge.openPcKeyboard = null
        signInBoard(false)
        com.jrees.wowcompanion.NativeLogin.end(context)        // the native sign-in's backstop: never a login.txt after the game screen; the probe stops
        main.removeCallbacks(traffic)
        note("game screen gone")
        Log.i(TAG, "game detached")
    }

    /** back from Streaming: the launcher's hooks and (a local game's) input are this screen's again; [show] comes with its resume */
    private fun reclaim() {
        LauncherHooks.pkg = context.packageName
        LauncherHooks.gameUp = { gameOn }
        LauncherHooks.inFront = { (context as? Activity)?.hasWindowFocus() == true }
        RootPolicy.allowed = false
        TapSinks.wire(PlayMode.LOCAL, if (gameOn) input else null, null)
    }

    /** the launcher came back to the front: the bottom screen is DS Forever's again */
    fun show() { hidden = false; update() }
    /** the launcher went away: Cocoon's bottom grid is the user's (the game itself is paused by the launcher then) */
    fun hide() { hidden = true; shown?.dismiss(); shown = null }
    private var hidden = false

    /**
     * THE NETWORK-TRAFFIC MEASUREMENT (the user 2026-10-08: "We need to investigate the network traffic thing like we did before to
     * detect the login screen"). Inside the launcher the game runs under the launcher's OWN uid, so android.net.TrafficStats gives the
     * game's bytes with no root (/proc/net is closed to every app since Android 10). Read-only: one log line per second with traffic
     * ("DSNET rx +N tx +M"), so the login screen / character select / world patterns can be measured before any signal is built on it.
     */
    private var lastRx = -1L
    private var lastTx = -1L
    private var quiet = 0
    private val traffic = object : Runnable {
        override fun run() {
            if (!running || !gameOn) return
            val uid = android.os.Process.myUid()
            val rx = android.net.TrafficStats.getUidRxBytes(uid); val tx = android.net.TrafficStats.getUidTxBytes(uid)
            if (lastRx >= 0 && rx >= 0) {
                val dr = rx - lastRx; val dt = tx - lastTx
                com.jrees.wowcompanion.NativeLogin.onTraffic(dr, dt)     // the sign-in's traffic = the client is done with login.txt
                if (dr > 0 || dt > 0 || ++quiet >= 10) { Log.i(TAG, "DSNET rx +$dr tx +$dt"); note("rx +$dr tx +$dt"); quiet = 0 }
            }
            lastRx = rx; lastTx = tx
            main.postDelayed(this, 1_000L)
        }
    }

    /**
     * the same lines kept in a small file of our own (/sdcard/WowCompanion/logs/dsnet.log, one rotation at 1 MB): logcat's ring buffer
     * had already dropped the sign-in part of the first measured session (2026-10-08 21:25)
     */
    private val netLog = java.io.File("/sdcard/WowCompanion/logs/dsnet.log")
    private val stamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.ROOT)
    private fun note(line: String) {
        try {
            netLog.parentFile?.mkdirs()
            if (netLog.length() > 1_000_000L) netLog.renameTo(java.io.File(netLog.path + ".1"))
            netLog.appendText("${stamp.format(java.util.Date())} $line\n")
        } catch (_: Throwable) {}
    }

    private fun gameDisplay(): Int = (context as? Activity)?.display?.displayId ?: Display.DEFAULT_DISPLAY

    private fun update() {
        if (!running || hidden) return
        val game = gameDisplay()
        val target = dm?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)?.firstOrNull { it.isValid && it.displayId != game }
        if (target == null) { shown?.dismiss(); shown = null; Log.i(TAG, "no other screen than the game's ($game)"); return }
        if (shown?.display?.displayId == target.displayId) return
        shown?.dismiss()
        shown = try { HostPresentation(context, target) { rebuild() }.also { it.show(); it.board(boardWanted) } } catch (t: Throwable) { Log.w(TAG, "presentation: ${t.message}"); null }
        Log.i(TAG, "DS Forever on display ${target.displayId} (game on $game)")
    }

    /** the host asked to start over (its recreate, e.g. after a permission): a fresh Presentation */
    private fun rebuild() { main.post { shown?.dismiss(); shown = null; update() } }

    companion object {
        const val TAG = "DSFORK"
        /** the one DS Forever of the launcher's activity (S5) */
        @Volatile var current: DsForeverScreen? = null; private set
        fun begin(activity: Activity) { if (current == null) current = DsForeverScreen(activity).also { it.start() } }
        fun end(activity: Activity) { current?.takeIf { it.context === activity }?.stop(); if (current?.context === activity) current = null }

        /** the launcher's own DS Forever while Streaming's activity has the screens (restored by [endStream]) */
        @Volatile private var beforeStream: DsForeverScreen? = null

        /**
         * STREAMING (S2): Streaming's activity on the top screen takes the bottom screen over. The launcher's DS Forever (if any) is
         * hidden, not stopped (its activity is paused under ours; a local game, if one ran, keeps its state), and comes back at
         * [endStream].
         */
        fun beginStream(activity: Activity) {
            val prev = current
            if (prev?.context === activity) return
            prev?.hide()
            beforeStream = prev
            current = DsForeverScreen(activity).also { it.start() }
        }

        fun endStream(activity: Activity) {
            val mine = current?.takeIf { it.context === activity } ?: return
            mine.stop()
            val prev = beforeStream
            beforeStream = null
            current = prev
            prev?.reclaim()
        }
        const val SHOW_AFTER_MS = 1_500L
        private const val EXTRA_MOVED = "dsforever_moved_to_top"

        /** what a start or a resume does about the display it landed on (pure: the tests drive it) */
        enum class Move { NONE, RELAUNCH, BRING_TO_TOP }

        /** a second move within this long is skipped (no loop if Android insists) */
        const val MOVE_GUARD_MS = 5_000L

        /**
         * b-resume-wrong-display (the user: "When I tab out of the game and reopen it, it reopens on the bottom screen"): Android brings
         * an existing task back on the display the icon was tapped on (the bottom screen's home grid). The game belongs on the TOP
         * screen (display 0), DS Forever's Presentation on the other one. Before: no move at all while a game ran, and only ONCE per
         * launch (the relaunched intent kept its "moved" mark, so a later resume on the bottom stayed there).
         * - on the top screen: nothing;
         * - no game: RELAUNCH (finish this task, start again on display 0: nothing is lost);
         * - a game running: BRING_TO_TOP (the SAME task brought to display 0, nothing finished: the game keeps running).
         */
        fun decide(display: Int, gameRunning: Boolean, sinceLastMoveMs: Long): Move = when {
            display == Display.DEFAULT_DISPLAY -> Move.NONE
            sinceLastMoveMs < MOVE_GUARD_MS -> Move.NONE
            gameRunning -> Move.BRING_TO_TOP
            else -> Move.RELAUNCH
        }

        @Volatile private var lastMoveAt = 0L
        /**
         * this run of the app is up: a reopen from the launcher, on the bottom screen, brings it back as it is. The user 2026-11-01 on
         * Cocoon: going Home and reopening started the app over (a RELAUNCH each time, a running stream with it)
         */
        @Volatile private var up = false

        /** called on every start and resume; true = this activity is going away (the caller returns) */
        fun moveToTop(activity: Activity, gameRunning: Boolean): Boolean {
            val d = activity.display?.displayId ?: Display.DEFAULT_DISPLAY
            val now = android.os.SystemClock.elapsedRealtime()
            val move = decide(d, true, now - lastMoveAt)      // the same task, always: Cocoon test, does the main icon then survive a reopen?
            Log.i(TAG, "resume: launcher on display $d (game running $gameRunning, up $up) -> $move")
            if (d == Display.DEFAULT_DISPLAY) up = true
            if (move == Move.NONE) return false
            lastMoveAt = now
            val top = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle()
            return try {
                if (move == Move.RELAUNCH) {
                    val again = Intent(activity.intent).putExtra(EXTRA_MOVED, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                    activity.finishAndRemoveTask()
                    activity.startActivity(again, top)
                    true
                } else {
                    // the existing task (the running game in it) to the top screen: no new task, nothing finished
                    activity.startActivity(Intent(activity, activity.javaClass)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP), top)
                    false
                }
            } catch (t: Throwable) { Log.w(TAG, "move to top: ${t.message}"); false }
        }
    }
}

/** the bottom screen's window: hosts the shared [CompanionHost] exactly as the app's MainActivity does */
private class HostPresentation(outer: Context, display: Display, private val onRecreate: () -> Unit) : Presentation(outer, display), HostShell {
    private var host: CompanionHost? = null
    private var board: com.jrees.wowcompanion.stream.SignInBoard? = null

    /** Streaming's Battle.net keyboard over the whole screen */
    fun board(on: Boolean) {
        if (on && board == null) board = com.jrees.wowcompanion.stream.SignInBoard(context) { DsForeverScreen.current?.signInClose?.invoke() }.also {
            addContentView(it, android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
        } else if (!on) { board?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }; board = null }
    }
    fun boardPress(button: String) { board?.press(button) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        )
        host = CompanionHost(context, this).also { it.onCreate(savedInstanceState); it.onStart() }
    }

    /**
     * TEXT FIELDS (the sign-in prompt, the Chat room's line, Bags / Professions search): an android text field needs a focusable window
     * for its keyboard, and this window is NOT focusable so the game keeps the focus. A touch on a text field makes it focusable for the
     * typing; a touch anywhere else gives the focus back to the game.
     */
    private var typing = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            // only a field that takes typing (Chat's bar is display-only: its own keyboard, and the game keeps the pad)
            val field = (window?.decorView?.let { hit(it, ev.rawX, ev.rawY) } as? EditText)?.takeIf { it.isFocusable && it.isEnabled }
            if ((field != null) != typing) focusable(field != null)
            // the keyboard on the FIRST tap, and it stays up after the finger lifts (until a touch outside a text field; Bags' note)
            field?.post {
                field.requestFocus()
                context.getSystemService(InputMethodManager::class.java)?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * A pad key that reaches THIS window (it holds the focus while a text field types) still goes to the start-up pages first, as the
     * launcher's activity sends them (the user on the Thor, 10-28: the chooser and Update did not answer the controller)
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (com.jrees.wowcompanion.StartKeys.route(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean =
        com.jrees.wowcompanion.StartKeys.motion(ev) || super.dispatchGenericMotionEvent(ev)

    private fun focusable(on: Boolean) {
        typing = on
        val w = window ?: return
        if (on) w.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        else {
            w.currentFocus?.let { f ->
                (context.getSystemService(InputMethodManager::class.java))?.hideSoftInputFromWindow(f.windowToken, 0)
                f.clearFocus()
            }
            w.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
    }

    /** the deepest shown view under a screen point */
    private fun hit(v: View, x: Float, y: Float): View? {
        if (v.visibility != View.VISIBLE) return null
        val at = IntArray(2); v.getLocationOnScreen(at)
        if (x < at[0] || y < at[1] || x >= at[0] + v.width || y >= at[1] + v.height) return null
        if (v is ViewGroup) for (i in v.childCount - 1 downTo 0) hit(v.getChildAt(i), x, y)?.let { return it }
        return v
    }

    override fun onStop() {
        if (typing) focusable(false)
        host?.let { it.onStop(); it.onDestroy() }
        host = null
        super.onStop()
    }

    override fun setHostContent(v: View) = setContentView(v)
    override fun hostGone(): Boolean = !isShowing
    override fun recreateHost() = onRecreate()
    override fun hostWindow() = window
    override fun hostIntent(): Intent? = null
    override fun startHostActivity(i: Intent) = context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
