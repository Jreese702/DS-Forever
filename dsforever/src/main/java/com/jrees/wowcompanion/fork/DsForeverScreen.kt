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
import com.jrees.wowcompanion.ScreenSwap

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
 *
 * SWAP SCREENS (GitHub #232, the user 2026-11-14: build it, untested - no such device here; ScreenSwap): with Settings > Swap screens On
 * the launcher's activity (the game) goes to the SECOND screen ([gameScreen]: [moveToTop]'s target) and DS Forever to display 0
 * ([bottomScreen]). Display 0, the default display, takes no Presentation (Android adds presentation windows only to displays flagged
 * for them), so there DS Forever's window is our [CompanionScreenActivity] instead: not focusable either, and the game's task is
 * brought back to the front after it comes ([gameToFront]) so the controller's keys stay the game's. Off (the default) = all as before.
 */
class DsForeverScreen(private val context: Context) {
    /** the launcher's X server as DS Forever's input, while the game screen is up */
    private var input: InputSink.Sink? = null
    @Volatile private var gameOn = false                 // issue #139 (2026-11-11): read off the main thread (the add-ons step)
    private val dm = context.getSystemService(DisplayManager::class.java)
    /** the bottom screen's window: the Presentation, or (Swap screens) the activity on display 0 */
    private var shown: BottomWindow? = null
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
        // display 0's panel as TapBridge sizes it (landscape), so a tap's pixels turn back into a fraction of the game's screen.
        // #232 review: display 0 ALWAYS, Swap screens or not ([TAP_SPACE_DISPLAY]): TapBridge (the tap-pad cells) and HomeLoginWatch
        // (the sign-in's fields) make a tap's pixels in display 0's space, so the fraction is right only over that same size. Sized from
        // the game's own screen, every cell missed when the panels differ (the Thor: 1920 wide pixels over 1240 = 0.77 for the middle)
        dm?.getDisplay(TAP_SPACE_DISPLAY)?.mode?.let { m ->
            InputSink.displayW = maxOf(m.physicalWidth, m.physicalHeight); InputSink.displayH = minOf(m.physicalWidth, m.physicalHeight)
        }
        // #232: Streaming and the report's browser open on the game's screen from the start (update() keeps it, a moment later)
        ScreenSwap.gameDisplay = if (ScreenSwap.inUse(context)) gameDisplay() else Display.DEFAULT_DISPLAY
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
        shown?.close(); shown = null
        if (ScreenSwap.restart === restartHook) ScreenSwap.restart = null
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
    /** Streaming's keyboard is wanted (a new bottom window puts it up: the Presentation, or the activity once it has its content) */
    internal var boardWanted = false
        private set

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
    fun hide() {
        hidden = true
        if (shown != null) com.jrees.wowcompanion.DiagLog.event("app", "bottom screen hidden (the launcher went to the background)")   // #147
        shown?.close(); shown = null
    }
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
            // issues #16 / #84 / #147: does the game screen hold the window focus (else every press goes to another window)
            (context as? Activity)?.let { a -> com.jrees.wowcompanion.PadDiag.focusNote(a.hasWindowFocus(), shown?.typing == true) }
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
    // 1.0.29 key-lag review: this ran on the MAIN thread every second the game had traffic (every 10 s when quiet), an append through
    // FUSE in the same queue as the game's key presses; now queued and written by the background log writer (BgAppend)
    private val netLog = com.jrees.wowcompanion.BgAppend({ java.io.File("/sdcard/WowCompanion/logs/dsnet.log") })
    private val stamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.ROOT)
    private fun note(line: String) { runCatching { netLog.add("${stamp.format(java.util.Date())} $line") } }

    private fun gameDisplay(): Int = (context as? Activity)?.display?.displayId ?: Display.DEFAULT_DISPLAY

    private fun update() {
        if (!running || hidden) return
        val game = gameDisplay()
        val all = dm?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)?.filter { it.isValid }.orEmpty()
        val screens = all.map { Screen(it.displayId, builtIn(it), it.name ?: "") }
        // #232: Swap screens as this run uses it (latched at the first start / resume: moveToTop); a second screen = the row shows
        val swap = ScreenSwap.inUse(context)
        ScreenSwap.offered = pickScreen(screens, Display.DEFAULT_DISPLAY, null) != null
        ScreenSwap.gameDisplay = if (swap) game else Display.DEFAULT_DISPLAY
        val pick = bottomScreen(screens, game, swap, shown?.displayId)
        val pickId = pick?.id
        // issue #196: every screen seen, so a docked report shows what Android offered and what was picked
        val seen = screens.joinToString { "${it.id}:${if (it.builtIn == null) "?" else if (it.builtIn) "in" else "ext"}:${it.name}" }
        Log.i(TAG, "screens $seen game $game -> $pickId" + if (swap) " (Swap screens)" else "")
        // #232: the display ids chosen, in the problem report (ScreenSwap.line) and once per change in DS Forever's own log
        if (ScreenSwap.note(ScreenSwap.placement(game, pickId, pick?.activity == true, seen)))
            com.jrees.wowcompanion.DiagLog.event("app", "screens: ${ScreenSwap.last}" + if (swap) " (Swap screens)" else "")
        if (pick == null) {
            if (shown != null) com.jrees.wowcompanion.DiagLog.event("app", "bottom screen gone: no other screen than the game's ($game)")   // #147
            shown?.close(); shown = null; Log.i(TAG, "no other screen than the game's ($game)"); return
        }
        if (shown?.displayId == pick.id) return
        shown?.close()
        if (pick.activity) {
            shown = openActivity(pick.id)
            Log.i(TAG, "DS Forever on display ${pick.id} as an activity (game on $game, Swap screens)")
            return
        }
        val target = all.first { it.displayId == pick.id }
        shown = try { HostPresentation(context, target) { rebuild() }.also { it.show(); it.board(boardWanted) } } catch (t: Throwable) {
            Log.w(TAG, "presentation: ${t.message}")
            com.jrees.wowcompanion.DiagLog.event("app", "bottom screen failed to open on display ${target.displayId}: ${t.javaClass.simpleName}")   // #147
            null
        }
        Log.i(TAG, "DS Forever on display ${target.displayId} (game on $game)")
    }

    /** the host asked to start over (its recreate, e.g. after a permission): a fresh Presentation (or activity) */
    internal fun rebuild() { main.post { shown?.close(); shown = null; update() } }

    // ---- #232 SWAP SCREENS: DS Forever's activity on display 0

    /** the activity asked for, until it comes and while it is up ([adopt] / [released]); [close] finishes it */
    private inner class AskedActivity(override val displayId: Int) : BottomWindow {
        var activity: CompanionScreenActivity? = null
        var closed = false
        override val typing: Boolean get() = activity?.typing == true
        override fun board(on: Boolean) { activity?.board(on) }
        override fun boardPress(button: String) { activity?.boardPress(button) }
        override fun close() { closed = true; activity?.finish(); activity = null }
    }

    /** the activity's recent starts ([startAllowed]); [gaveUp] = it kept closing: not again this run */
    private val starts = ArrayList<Long>()
    private var gaveUp = false

    private fun openActivity(id: Int): BottomWindow? {
        if (gaveUp) return null
        val now = android.os.SystemClock.elapsedRealtime()
        starts.removeAll { now - it >= START_GUARD_MS }
        if (!startAllowed(starts, now)) {
            gaveUp = true
            Log.w(TAG, "Swap screens: the bottom screen's activity was started ${starts.size} times in ${START_GUARD_MS / 1000} s: not again this run")
            com.jrees.wowcompanion.DiagLog.event("app", "Swap screens: the bottom screen kept closing; DS Forever stays off display $id until the next start")
            return null
        }
        starts.add(now)
        val w = AskedActivity(id)
        return try {
            context.startActivity(Intent(context, CompanionScreenActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION),
                ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle())
            // it never came (Android refused it without a word): nothing waits for it, the next show / screen change asks again
            main.postDelayed({
                if (shown === w && w.activity == null && !w.closed) {
                    shown = null
                    com.jrees.wowcompanion.DiagLog.event("app", "Swap screens: the bottom screen's activity never came on display $id")
                }
            }, ADOPT_MS)
            w
        } catch (t: Throwable) {
            Log.w(TAG, "bottom screen activity: ${t.message}")
            com.jrees.wowcompanion.DiagLog.event("app", "bottom screen failed to open on display $id: ${t.javaClass.simpleName}")
            null
        }
    }

    /** the activity is being made: true = it is the one asked for (on its display), so it takes the bottom screen; false = it closes */
    internal fun adopt(a: CompanionScreenActivity, display: Int): Boolean {
        val w = shown as? AskedActivity
        if (w == null || w.closed || w.activity != null) return false
        if (display != w.displayId) {
            Log.w(TAG, "Swap screens: the bottom screen's activity came on display $display, not ${w.displayId}")
            com.jrees.wowcompanion.DiagLog.event("app", "Swap screens: Android put the bottom screen on display $display, not ${w.displayId}")
            return false
        }
        w.activity = a
        return true
    }

    /**
     * the activity went: by our [AskedActivity.close], or by Android. #232 review: Android's close (a configuration change it rebuilt the
     * activity for, a Back it took) is asked again at once - the launcher stays resumed while the game runs, so waiting for its next
     * resume left display 0 without DS Forever for the whole game. The start guard ([startAllowed]) stops a loop.
     */
    internal fun released(a: CompanionScreenActivity) {
        val w = shown as? AskedActivity ?: return
        if (w.activity !== a) return
        w.activity = null
        if (!w.closed) {
            shown = null
            com.jrees.wowcompanion.DiagLog.event("app", "Swap screens: the bottom screen's activity was closed by Android")
            main.post { update() }
        }
    }

    /**
     * the launcher's task (the game) to the front: its display becomes the top one again, so the controller's keys go to the game and
     * not to display 0 (a display whose top activity has no focusable window would hold them). AppTask: our own task, no permission.
     * #232 review: never while the launcher is going away ([frontWanted]): the bottom activity's typing ends in its onStop, and that
     * moved the launcher back over the home screen the player had just pressed Home for
     */
    internal fun gameToFront(why: String) {
        val a = context as? Activity ?: return
        if (!frontWanted(running, hidden, a.isFinishing)) { Log.i(TAG, "Swap screens: the game's task stays ($why; the launcher is away)"); return }
        runCatching {
            a.getSystemService(android.app.ActivityManager::class.java)?.appTasks?.firstOrNull { it.taskInfo.taskId == a.taskId }?.moveToFront()
        }.onFailure { Log.w(TAG, "game to the front: ${it.message}") }
        Log.i(TAG, "Swap screens: the game's task to the front ($why)")
    }

    /** Settings > Swap screens > Restart (ScreenSwap.restart): the launcher started again on the screen the saved choice gives it */
    private val restartHook: () -> Unit = { main.post { restartForSwap() } }

    private fun restartForSwap() {
        val a = context as? Activity ?: return
        if (gameOn) { Log.i(TAG, "Swap screens restart: not while the game runs"); return }
        val saved = a.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(ScreenSwap.PREF, false)
        ScreenSwap.take(saved)
        val target = gameTarget(a, gameDisplay())
        com.jrees.wowcompanion.DiagLog.event("app", "Swap screens ${if (saved) "on" else "off"}: DS Forever starts again, the game on display $target")
        Log.i(TAG, "Swap screens restart: ${if (saved) "on" else "off"}, the launcher to display $target")
        lastMoveAt = android.os.SystemClock.elapsedRealtime()
        val again = Intent(a.intent).putExtra(EXTRA_MOVED, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        try {
            a.finishAndRemoveTask()
            a.startActivity(again, ActivityOptions.makeBasic().setLaunchDisplayId(target).toBundle())
        } catch (t: Throwable) { Log.w(TAG, "Swap screens restart: ${t.message}") }
    }

    /**
     * issue #196 ("When I connect the Thor to the TV ... it moves the bottom screen to the TV"): since Android 12 the PRESENTATION
     * list puts EXTERNAL displays (HDMI / USB-C) BEFORE the built-in ones, so the first entry was the TV once one was plugged in, and
     * the display listener moved DS Forever there. A built-in panel's type through Display.getType() (hidden, read by reflection);
     * null = unknown, then the name decides (an "HDMI" / "external" / "DisplayPort" / "USB" / "wireless" name = not built in).
     */
    private fun builtIn(d: Display): Boolean? = typeBuiltIn(d)

    companion object {
        const val TAG = "DSFORK"
        /** android.view.Display.TYPE_INTERNAL (hidden constant) */
        const val TYPE_INTERNAL = 1
        private val EXTERNAL_NAME = Regex("hdmi|external|displayport|usb|wireless|wifi|cast", RegexOption.IGNORE_CASE)

        /** one presentation display as [pickScreen] sees it; builtIn null = unknown */
        data class Screen(val id: Int, val builtIn: Boolean?, val name: String)

        /**
         * the bottom screen's display (issue #196), pure for the tests: never the game's; the one already showing DS Forever while it is
         * still there (a TV plugged in later never takes it away); else a built-in panel, then an unknown one, an external one last.
         */
        fun pickScreen(screens: List<Screen>, game: Int, current: Int?): Int? {
            val others = screens.filter { it.id != game }
            if (current != null && others.any { it.id == current }) return current
            return others.minWithOrNull(compareBy<Screen> { when (it.builtIn) { true -> 0; null -> 1; false -> 2 } }.thenBy { it.id })?.id
        }

        /**
         * #232 SWAP SCREENS, pure for the tests: the display the launcher's activity (the game) belongs on. Off = display 0, as always.
         * On = the second screen [pickScreen] would give DS Forever (built-in first: a TV never takes the game from a built-in second
         * screen, #196; the one the game is on already kept; a TV only when it is the only other screen, as it takes DS Forever with Off),
         * display 0 when there is none (a single screen: nothing to swap).
         */
        fun gameScreen(screens: List<Screen>, swap: Boolean, current: Int? = null): Int =
            if (!swap) Display.DEFAULT_DISPLAY else pickScreen(screens, Display.DEFAULT_DISPLAY, current) ?: Display.DEFAULT_DISPLAY

        /** where DS Forever goes; [activity] = as [CompanionScreenActivity] (display 0 takes no Presentation) */
        data class Bottom(val id: Int, val activity: Boolean)

        /**
         * #232, pure for the tests: DS Forever's display with the game on [game]. With Swap screens in use and the game off display 0:
         * display 0, as the activity (unless display 0 were a presentation display, which no device's default display is). Otherwise
         * exactly as before: [pickScreen]'s other screen, a Presentation.
         */
        fun bottomScreen(screens: List<Screen>, game: Int, swap: Boolean, current: Int?): Bottom? {
            if (swap && game != Display.DEFAULT_DISPLAY)
                return Bottom(Display.DEFAULT_DISPLAY, activity = screens.none { it.id == Display.DEFAULT_DISPLAY })
            return pickScreen(screens, game, current)?.let { Bottom(it, activity = false) }
        }

        /**
         * #232 review, pure for the tests: the game's task is brought to the front only while this DS Forever runs and shows, never while
         * the launcher is hidden (its pause / stop / Home) or finishing
         */
        fun frontWanted(running: Boolean, hidden: Boolean, launcherFinishing: Boolean): Boolean = running && !hidden && !launcherFinishing

        /**
         * #232 review: the display whose panel sizes InputSink's tap pixels - display 0 ALWAYS, Swap screens or not. TapBridge (the
         * tap-pad cells) and HomeLoginWatch (the sign-in's fields) make a tap's pixels in display 0's space; InputSink turns them back
         * into a fraction of the game's screen over this same size
         */
        const val TAP_SPACE_DISPLAY = Display.DEFAULT_DISPLAY

        /** #232: the bottom screen's activity is started at most [START_GUARD_N] times in [START_GUARD_MS] (no loop if it keeps closing) */
        fun startAllowed(recent: List<Long>, now: Long): Boolean = recent.count { now - it < START_GUARD_MS } < START_GUARD_N
        const val START_GUARD_N = 3
        const val START_GUARD_MS = 15_000L
        /** the asked activity has this long to come */
        const val ADOPT_MS = 5_000L

        /** the game's display for [activity] (on [now]): display 0 unless Swap screens is in use (then [gameScreen] over the screens seen) */
        private fun gameTarget(activity: Activity, now: Int): Int {
            if (!ScreenSwap.inUse(activity)) return Display.DEFAULT_DISPLAY
            val dm = activity.getSystemService(DisplayManager::class.java) ?: return Display.DEFAULT_DISPLAY
            val screens = runCatching {
                dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).filter { it.isValid }.map { Screen(it.displayId, typeBuiltIn(it), it.name ?: "") }
            }.getOrDefault(emptyList())
            return gameScreen(screens, true, now)
        }

        /** [builtIn]'s rule for a display outside an instance (the start's move) */
        private fun typeBuiltIn(d: Display): Boolean? {
            val type = runCatching { Display::class.java.getMethod("getType").invoke(d) as Int }.getOrNull()
            if (type != null) return type == TYPE_INTERNAL
            return if (EXTERNAL_NAME.containsMatchIn(d.name ?: "")) false else null
        }

        /** the one DS Forever of the launcher's activity (S5) */
        @Volatile var current: DsForeverScreen? = null; private set
        fun begin(activity: Activity) {
            if (current == null) current = DsForeverScreen(activity).also { it.start(); ScreenSwap.restart = it.restartHook }
        }
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
        fun decide(display: Int, gameRunning: Boolean, sinceLastMoveMs: Long, target: Int = Display.DEFAULT_DISPLAY): Move = when {
            // #232: [target] = the game's display (display 0; the second screen with Swap screens in use: [gameScreen])
            display == target -> Move.NONE
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
            // #232: the game's display (display 0 unless Swap screens is in use: then the second screen)
            val target = gameTarget(activity, d)
            val move = decide(d, gameRunning || up, now - lastMoveAt, target)
            Log.i(TAG, "resume: launcher on display $d (game running $gameRunning, up $up) -> $move" +
                if (target != Display.DEFAULT_DISPLAY) " (Swap screens: the game's display is $target)" else "")
            if (d == target) up = true
            if (move == Move.NONE) return false
            lastMoveAt = now
            val top = ActivityOptions.makeBasic().setLaunchDisplayId(target).toBundle()
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

/**
 * #232: the bottom screen's window as [DsForeverScreen] holds it: the [HostPresentation] (the Thor's bottom screen), or with Swap screens
 * the [CompanionScreenActivity] on display 0
 */
internal interface BottomWindow {
    val displayId: Int
    /** a text field types: the window holds the focus */
    val typing: Boolean
    /** Streaming's Battle.net keyboard over the whole screen */
    fun board(on: Boolean)
    fun boardPress(button: String)
    fun close()
}

/**
 * TEXT FIELDS (the sign-in prompt, the Chat room's line, Bags / Professions search): an android text field needs a focusable window
 * for its keyboard, and the bottom window is NOT focusable so the game keeps the focus. A touch on a text field makes it focusable for
 * the typing; a touch anywhere else gives the focus back to the game. (#232: moved here unchanged from [HostPresentation], so the
 * Swap screens activity types the same way; [onFocusable] = what the window does more when it changes.)
 */
internal class BottomTyping(private val ctx: Context, private val window: () -> android.view.Window?,
                            private val onFocusable: (Boolean) -> Unit = {}) {
    var typing = false
        private set

    /** every touch, before the window's own dispatch */
    fun touch(ev: MotionEvent) {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            // only a field that takes typing (Chat's bar is display-only: its own keyboard, and the game keeps the pad)
            val field = (window()?.decorView?.let { hit(it, ev.rawX, ev.rawY) } as? EditText)?.takeIf { it.isFocusable && it.isEnabled }
            if ((field != null) != typing) focusable(field != null)
            // the keyboard on the FIRST tap, and it stays up after the finger lifts (until a touch outside a text field; Bags' note)
            field?.post {
                field.requestFocus()
                ctx.getSystemService(InputMethodManager::class.java)?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    fun focusable(on: Boolean) {
        typing = on
        // #230 open lead: the ANR came ~6-11 s after this window gave the focus back (sign-in board); the switches are logged
        runCatching { com.jrees.wowcompanion.DiagLog.event("touch", "bottom window focusable ${if (on) "on" else "off"}") }
        val w = window() ?: return
        if (on) w.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        else {
            w.currentFocus?.let { f ->
                (ctx.getSystemService(InputMethodManager::class.java))?.hideSoftInputFromWindow(f.windowToken, 0)
                f.clearFocus()
            }
            w.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
        onFocusable(on)
    }

    /** the deepest shown view under a screen point */
    private fun hit(v: View, x: Float, y: Float): View? {
        if (v.visibility != View.VISIBLE) return null
        val at = IntArray(2); v.getLocationOnScreen(at)
        if (x < at[0] || y < at[1] || x >= at[0] + v.width || y >= at[1] + v.height) return null
        if (v is ViewGroup) for (i in v.childCount - 1 downTo 0) hit(v.getChildAt(i), x, y)?.let { return it }
        return v
    }
}

/** the bottom screen's window: hosts the shared [CompanionHost] exactly as the app's MainActivity does */
private class HostPresentation(outer: Context, display: Display, private val onRecreate: () -> Unit) : Presentation(outer, display), HostShell,
    BottomWindow {
    private var host: CompanionHost? = null
    private var board: com.jrees.wowcompanion.stream.SignInBoard? = null

    override val displayId: Int get() = display.displayId
    override fun close() = dismiss()

    /** Streaming's Battle.net keyboard over the whole screen */
    override fun board(on: Boolean) {
        if (on && board == null) board = com.jrees.wowcompanion.stream.SignInBoard(context) { DsForeverScreen.current?.signInClose?.invoke() }.also {
            addContentView(it, android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
        } else if (!on) { board?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }; board = null }
    }
    override fun boardPress(button: String) { board?.press(button) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        )
        // #230: named, so an input ANR line says which window stopped answering (untitled = the package name, like the others)
        com.jrees.wowcompanion.WinTitle.apply(window, com.jrees.wowcompanion.WinTitle.BOTTOM)
        host = CompanionHost(context, this).also { it.onCreate(savedInstanceState); it.onStart() }
    }

    /** the text fields' focus rules ([BottomTyping]) */
    private val typer = BottomTyping(context, { window })
    override val typing: Boolean get() = typer.typing

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // #230: this window's touches timed like the top screen's (age on arrival, handling time)
        val start = android.os.SystemClock.uptimeMillis()
        try { return dispatchTouchEventTimed(ev) }
        finally {
            runCatching { com.jrees.wowcompanion.TouchLag.note(com.jrees.wowcompanion.TouchLag.BOTTOM, ev.actionMasked, ev.eventTime, start, android.os.SystemClock.uptimeMillis()) }
        }
    }

    private fun dispatchTouchEventTimed(ev: MotionEvent): Boolean {
        typer.touch(ev)
        return super.dispatchTouchEvent(ev)
    }

    /**
     * A pad key that reaches THIS window (it holds the focus while a text field types) still goes to the start-up pages first, as the
     * launcher's activity sends them (the user on the Thor, 10-28: the chooser and Update did not answer the controller)
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        // #84 / #147: a press that reaches the bottom window (it holds the focus) never reaches PadFeed or the game
        com.jrees.wowcompanion.PadDiag.bottomKey(event, typing)
        if (com.jrees.wowcompanion.StartKeys.route(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean =
        com.jrees.wowcompanion.StartKeys.motion(ev) || super.dispatchGenericMotionEvent(ev)

    override fun onStop() {
        if (typing) typer.focusable(false)
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
