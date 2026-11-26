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
import com.jrees.wowcompanion.SingleScreen

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
 * #383 / #384: that move is [toFront] (only the task asked for), never AppTask.moveToFront (it put display 0's home screen over the
 * bottom activity and Android closed it: a restart loop), and it is asked again whenever display 0 takes the keys back.
 * #384 recovery (the user, 2026-11-16: "make sure there IS a safe recovery path"): a Swap screens start whose bottom activity never
 * settles counts ([settleCheck]); ScreenSwap.MAX_FAILS in a row and the next start is Off, the start guard's give-up turns it Off at
 * once ([swapOff]), and a start with ScreenSwap.EXTRA_OFF is the by-hand way out ([moveToTop]).
 *
 * SINGLE SCREEN MODE (the user, 2026-11-25: "a single screen mode where the game shifts to the top screen when a button is pushed";
 * SingleScreen): the same CompanionHost in [TopWindow], a never-focused Dialog on the launcher's activity over the game on display 0 (no
 * second activity there: the #383 / #384 lesson; no overlay permission), the bottom Presentation closed so the bottom screen is
 * Android's launcher again (the pick "Back to Thor's launcher"). ONE host at a time: the switch is the resume path's teardown and
 * rebuild ([toggleSingle]). Every start is two screens; a device with no other screen starts in it ([singleStep]). A touch on the
 * bottom screen moves Android's keys there: [keysTop] brings the game's task back (the open ruling R1, built default A).
 *
 * THE BOTTOM SCREEN COMES STRAIGHT BACK (the user, 2026-11-25: "Coming back after a drop: When the bottom screen goes blank or restarts,
 * DS Forever should come straight back on it, in the same room, already caught up with the game, with no app restart"; "Like most of
 * the time the drops happen from minimizing the game or closing my thor to sleep"; BottomSync): a minimize or a sleep PARKS the bottom
 * window ([hide]: the same window and CompanionHost kept, hidden - no surface, no touch, no focus, no keep-screen-on; the host away) and
 * the return UNPARKS it ([bottomStep]), exactly as it was. The way back is always found: Android's own close of the window is noticed
 * ([lost]), a display state change is looked at ([displaySettle]), a failed open is tried again by a watchdog with a backoff and a
 * loop guard ([reopenLook], [CancelGuard]), a new window is checked a moment later ([verifyRun]); a parked host is let go only on
 * Android's real memory pressure ([trims], [parkReleaseAt]), and a host made again opens from the old one's snapshot. Never an automatic
 * single screen mode on a device that showed a second built-in screen ([TWO_SCREENS_SEEN]).
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
    /** Streaming's own DS Forever ([beginStream]): single screen mode is not offered there (the open ruling R2) */
    private var streamOwned = false
    private val listener = object : DisplayManager.DisplayListener {
        // the add-on screen can arrive or go at any time (USB-C): the cached panel size is read again (round 2: a display event is a new
        // reason to try a refused open again - one try, as before this branch: [openFails])
        override fun onDisplayAdded(displayId: Int) { openFails = 0; topFails = 0; com.jrees.wowcompanion.TapBridge.forgetPanel(); update() }
        override fun onDisplayRemoved(displayId: Int) { com.jrees.wowcompanion.TapBridge.forgetPanel(); update() }
        // the bottom screen comes straight back (F2): a display that comes back by a state / flags / mode change (no remove + add) is
        // looked at too, once its changes settle (a sleep's OFF / ON comes as several)
        override fun onDisplayChanged(displayId: Int) { openFails = 0; topFails = 0; main.removeCallbacks(displaySettle); main.postDelayed(displaySettle, DISPLAY_SETTLE_MS) }
    }
    private val displaySettle: Runnable = Runnable { update() }

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
        LauncherHooks.gameUp = gameUpHook
        LauncherHooks.inFront = inFrontHook
        running = true
        SingleScreen.request = singleHook                    // single screen mode: the wheel's button asks this DS Forever
        SingleScreen.keysAgain = keysAgainHook               // NPC: our activity on the other screen closed: the keys looked at again
        // F8: a second built-in screen seen by this app before (saved): a two-screen device, never single screen mode by itself (a crash
        // or a low-memory restart while the Thor's bottom screen is gone a moment)
        savedTwo = runCatching { context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(TWO_SCREENS_SEEN, false) }.getOrDefault(false)
        sawSecond = twoScreensSeen(sawSecond, savedTwo)
        // BottomSync: Android's own memory trims (a parked host is let go on real pressure only)
        runCatching { context.applicationContext.registerComponentCallbacks(trims) }
        dm?.registerDisplayListener(listener, main)
        // a configuration relaunch with the game running (a reopen from the bottom grid, F5): the bottom screen back sooner, restored
        val fresh = com.jrees.wowcompanion.BottomSync.fresh(com.jrees.wowcompanion.BottomSync.last, android.os.SystemClock.elapsedRealtime(),
            LauncherHooks.gameStartSec)
        main.postDelayed({ update() }, showAfter(fresh, LauncherHooks.gameStartSec > 0L))
        Log.i(TAG, "start (launcher on display ${gameDisplay()})")
    }

    /** the launcher's activity is being made again for a configuration change ([end]): the game keeps running, so the button's single
     *  screen mode stays (the review 2026-11-25: a reopen from the bottom screen's icon moved the activity between the two panels) */
    private var relaunching = false

    fun stop() {
        // BottomSync: a configuration relaunch (a reopen from the bottom grid moved the activity) - the new activity's host opens restored
        if (relaunching) keepSnapshot(shown, "a configuration relaunch")
        // (the review: the new activity's game screen stamps the game's start anew - the same game goes on, the snapshot stays its own)
        if (relaunching && gameOn) com.jrees.wowcompanion.BottomSync.relaunching(LauncherHooks.gameStartSec, android.os.SystemClock.elapsedRealtime())
        detachGame()
        running = false
        stopGiveBack()
        main.removeCallbacksAndMessages(null)
        frontLater = false                                   // #383 / #384: its waiting ask went with the queue
        try { dm?.unregisterDisplayListener(listener) } catch (_: Exception) {}
        runCatching { context.applicationContext.unregisterComponentCallbacks(trims) }
        shown?.close(); shown = null; companionGone()
        if (ScreenSwap.restart === restartHook) ScreenSwap.restart = null
        if (SingleScreen.request === singleHook) SingleScreen.request = null
        if (SingleScreen.keysAgain === keysAgainHook) SingleScreen.keysAgain = null
        // the leak fix's review: the launcher's two hooks go back to "no game, not in front" (what they answered by now anyway: the game
        // detached above, the activity going) while still this DS Forever's - they kept it, and the launcher's activity it was made on,
        // until the next start (a back-out with the process kept, Streaming's end)
        if (LauncherHooks.gameUp === gameUpHook) LauncherHooks.gameUp = { false }
        if (LauncherHooks.inFront === inFrontHook) LauncherHooks.inFront = { false }
        keysCancel()
        Log.i(TAG, "stop")
    }

    /** the launcher's hooks as this DS Forever sets them ([start], [reclaim]), taken off at its [stop] */
    private val gameUpHook: () -> Boolean = { gameOn }
    private val inFrontHook: () -> Boolean = { (context as? Activity)?.hasWindowFocus() == true }

    /** the game screen is up: its X server becomes DS Forever's input, the traffic measurement runs */
    fun attachGame(sink: InputSink.Sink) {
        input = sink
        // Local: the container's X server behind ContainerKeys (TapSinks; every call passes straight through, as before)
        val route = TapSinks.wire(PlayMode.LOCAL, sink, null)
        gameOn = true
        LauncherHooks.gameStartSec = System.currentTimeMillis() / 1000
        // BottomSync (the review): a configuration relaunch's game screen is the SAME game - the snapshot the old activity kept is re-keyed
        // to this stamp; a new game run keeps no document of before (DocMemory)
        if (!com.jrees.wowcompanion.BottomSync.attached(LauncherHooks.gameStartSec, android.os.SystemClock.elapsedRealtime()))
            com.jrees.wowcompanion.DocMemory.shared.clear()
        lastRx = -1L; note("game screen up"); main.removeCallbacks(traffic); main.post(traffic)
        com.jrees.wowcompanion.GameLife.started()             // issue #12: the game's life in DS Forever's own log
        runCatching { com.jrees.wowcompanion.IndoorKeepAlive.gameStarting() }   // issue 568: never the indoor maps' service beside a game
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
        runCatching { com.jrees.wowcompanion.IndoorKeepAlive.gameStarting() }   // issue 568: none while streaming either
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

    /**
     * BROWSER LOGIN'S PAGE ON THE BOTTOM SCREEN (the user, 2026-11-18: "open Blizzard's sign-in page on the Thor's BOTTOM screen (touch +
     * keyboard there; top stays the game)"): [make] builds the page (Blizzard's sign-in page, or the sign-in's status) for the bottom
     * window's own context; it covers the whole bottom screen and the window holds the focus while it shows, so the page's fields take
     * the keyboard. A new bottom window (a display change, Swap screens) builds it again with [make]; null = the page goes and the focus
     * goes back to the game. False = no bottom screen now (the caller shows the page on the game's screen instead). Main thread.
     */
    fun browserPage(make: ((Context) -> View)?): Boolean {
        pageWanted = make
        val w = shown ?: return false
        w.page(make)
        return true
    }
    /** the browser-login page wanted on the bottom screen (a new bottom window puts it up too) */
    internal var pageWanted: ((Context) -> View)? = null
        private set
    /** a bottom window is up now (the Presentation or the Swap screens activity) */
    val bottomUp: Boolean get() = shown != null

    /** the game screen went: no input until the next one */
    fun detachGame() {
        if (!gameOn) return
        gameOn = false; input = null; InputSink.sink = null
        com.jrees.wowcompanion.SetupBridge.openPcKeyboard = null
        signInBoard(false)
        com.jrees.wowcompanion.NativeLogin.end(context)        // the native sign-in's backstop: never a login.txt after the game screen; the probe stops
        main.removeCallbacks(traffic)
        note("game screen gone")
        // BottomSync: nothing of an ended game is restored (a configuration relaunch keeps it: the game runs on)
        if (!relaunching) { com.jrees.wowcompanion.BottomSync.drop(); com.jrees.wowcompanion.DocMemory.shared.clear() }
        // #568: the indoor maps may build from now on (once nobody looks at the launcher); a step that ended meanwhile starts again
        runCatching { com.jrees.wowcompanion.IndoorSchedulerStep.gameClosed(context) }
        Log.i(TAG, "game detached")
        // single screen mode ends with the game (every start is two screens): the start pages before Play are the bottom screen's again;
        // a configuration relaunch is no end of the game (the new activity's DS Forever opens the top window again)
        if (singleOffAtGameEnd(SingleScreen.why, relaunching)) {
            SingleScreen.set(false, null, android.os.SystemClock.uptimeMillis())
            com.jrees.wowcompanion.DiagLog.event("app", "screens: one screen off (the game ended)")
            keysCancel()
            rebuild()
        }
    }

    /** back from Streaming: the launcher's hooks and (a local game's) input are this screen's again; [show] comes with its resume */
    private fun reclaim() {
        LauncherHooks.pkg = context.packageName
        LauncherHooks.gameUp = gameUpHook
        LauncherHooks.inFront = inFrontHook
        RootPolicy.allowed = false
        TapSinks.wire(PlayMode.LOCAL, if (gameOn) input else null, null)
        SingleScreen.request = singleHook                    // the launcher's own DS Forever answers the wheel's button again
        SingleScreen.keysAgain = keysAgainHook
    }

    /** the launcher came back to the front: the bottom screen is DS Forever's again (round 2: a return is a new start for the watchdog -
     *  its fast steps first, and a window the window manager kept refusing is tried again) */
    fun show() {
        hidden = false
        reopenTries = 0; openFails = 0; topFails = 0
        update()
        // the int/1534 review: single screen mode's keys looked at once after the return ([keysAfterReturn])
        if (SingleScreen.inUse) { main.removeCallbacks(keysAfterReturn); main.postDelayed(keysAfterReturn, KEYS_RETURN_MS) }
    }
    /**
     * the launcher went away: Cocoon's bottom grid is the user's (the game itself is paused by the launcher then). THE BOTTOM SCREEN COMES
     * STRAIGHT BACK (BottomSync): the Presentation and single screen mode's top window are PARKED - the same window and host kept, hidden
     * (no surface, no touch, no focus, no keep-screen-on), the host away; [show] unparks them. Swap screens' activity is closed as before;
     * so is everything when [park] is false (Streaming's own Presentation takes the bottom screen: never two hosts)
     */
    fun hide(park: Boolean = true) {
        hidden = true
        keysCancel()
        stopGiveBack()
        main.removeCallbacks(reopenLook); main.removeCallbacks(verifyRun); main.removeCallbacks(displaySettle)
        val w = shown ?: return
        if (!w.alive) { shown = null; companionGone(); return }                // Android closed it already ([lost] is on its way)
        if (park) when (bottomStep(true, null, gameDisplay(), Win(w.displayId, w.alive, w.parked))) {
            BottomAct.PARK -> if (w.park("the launcher went to the background")) { pressureAtPark(); return }
            else -> return                                                     // parked already: a second pause / stop changes nothing
        }
        com.jrees.wowcompanion.DiagLog.event("app", "bottom screen hidden (the launcher went to the background)")   // #147
        shown?.close(); shown = null; companionGone()
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
        // F1: a window Android closed by itself is never kept ([lost] forgets it too; this is the safety net)
        shown?.takeIf { !it.alive }?.let { shown = null; companionGone() }
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
        // SINGLE SCREEN MODE, the review (2026-11-25): the launcher's activity off display 0 in the mode (a reopen from the bottom launcher's
        // icon: moveToTop's move to display 0 is under way) - nothing opened or changed now (a window made there takes the bottom screen's
        // size, the button would hide); the move's resume / relaunch runs update() again, and so does a short look again here
        if (singleWaitsForTop(SingleScreen.inUse, swap, game)) {
            if (shown != null) { keepSnapshot(shown, "the launcher on the bottom screen"); shown?.close(); shown = null; companionGone() }
            if (topTries++ < TOP_TRIES) { main.removeCallbacks(topRetry); main.postDelayed(topRetry, TOP_RETRY_MS) }
            else reopenSoon("the move to the top screen", top = true)        // F9: the watchdog's slow cadence after the last try
            Log.i(TAG, "one screen: the launcher is on display $game, waiting for its move to the top screen")
            return
        }
        topTries = 0
        // SINGLE SCREEN MODE (SingleScreen): a device with nothing but display 0 runs it by itself (the user's pick "Yes, automatically"),
        // a second screen that comes ends that (never in a fight: looked at again a moment later); the button shows with a second screen.
        // The review (2026-11-25): only a device that HAS one screen - a second screen seen once in this process (the Thor's bottom
        // screen, gone a moment: USB-C, a display restart) keeps the two-screen way (the bottom window closed, as in 566d3ece1), and a
        // one-screen start waits SingleScreen.ONE_GRACE_MS (a second screen listed late)
        if (pick != null) sawSecond = true
        // F8: the first second BUILT-IN screen seen is saved too (an app preference: the next process start reads it)
        if (!savedTwo && pick != null && screens.any { it.id == pick.id && it.builtIn == true }) saveTwoScreens()
        when (singleStep(pick != null, game, swap, SingleScreen.why, SingleScreen.fightNow(), sawSecond, oneForMs(pick == null, android.os.SystemClock.uptimeMillis()))) {
            SingleStep.DEVICE_ON -> {
                SingleScreen.set(true, SingleScreen.Why.DEVICE, android.os.SystemClock.uptimeMillis())
                com.jrees.wowcompanion.DiagLog.event("app", "screens: one screen on (no other screen than the game's)")
            }
            SingleStep.DEVICE_OFF -> {
                SingleScreen.set(false, null, android.os.SystemClock.uptimeMillis())
                com.jrees.wowcompanion.DiagLog.event("app", "screens: one screen off (a second screen came)")
            }
            SingleStep.RETRY -> { main.removeCallbacks(deviceRetry); main.postDelayed(deviceRetry, SingleScreen.DEVICE_RETRY_MS) }
            SingleStep.WAIT -> { main.removeCallbacks(deviceRetry); main.postDelayed(deviceRetry, SingleScreen.ONE_GRACE_MS) }
            SingleStep.NONE -> {}
        }
        SingleScreen.offered = pick != null
        if (SingleScreen.inUse) {
            // (the review: a top window made at another size or on another display than the activity's now is made again)
            if (shown !is TopWindow || (shown as TopWindow).stale()) {
                if (shown is TopWindow) keepSnapshot(shown, "the one screen window made again")    // BottomSync: opened restored
                shown?.close()
                shown = openTop()
                if (shown != null) Log.i(TAG, "DS Forever on the top screen (one screen, game on $game)")
            } else if (shown?.parked == true) { shown?.unpark("back"); verifySoon() }
            companionGone()
            return
        }
        twoScreens(pick, game, all)
    }

    /**
     * TWO SCREENS (the Thor): the bottom window as [bottomStep] decides it - parked on the picked display = shown again (the return), none
     * = opened, another id = moved there, no other screen = closed (a parked one with the launcher a moment on the bottom screen stays:
     * F5). Every way out with no window up asks the watchdog ([reopenSoon]). Swap screens' activity: as before
     */
    private fun twoScreens(pick: Bottom?, game: Int, all: List<Display>) {
        if (pick?.activity == true) {
            if (shown?.displayId == pick.id) return
            shown?.close()
            shown = openActivity(pick.id)
            // PR #256 review: the Presentation that was up is gone; on display 0 TapBridge treats our screen as not known
            com.jrees.wowcompanion.TapBridge.companionDisplayId = pick.id
            Log.i(TAG, "DS Forever on display ${pick.id} as an activity (game on $game, Swap screens)")
            return
        }
        val w = shown
        when (bottomStep(false, pick?.id, game, w?.let { Win(it.displayId, it.alive, it.parked) })) {
            BottomAct.NONE -> if (pick == null) { Log.i(TAG, "no other screen than the game's ($game)") }
            BottomAct.KEEP_PARKED -> Log.i(TAG, "the launcher is on display $game a moment: the bottom window stays parked")
            BottomAct.CLOSE -> {
                com.jrees.wowcompanion.DiagLog.event("app", "bottom screen gone: no other screen than the game's ($game)")   // #147
                keepSnapshot(w, "the bottom display went")
                shown?.close(); shown = null; companionGone(); Log.i(TAG, "no other screen than the game's ($game)")
            }
            BottomAct.UNPARK -> { shown?.unpark("back"); verifySoon() }
            BottomAct.MOVE -> {
                com.jrees.wowcompanion.DiagLog.event("app", "bottom screen moved: display ${w?.displayId} -> ${pick?.id}")
                keepSnapshot(w, "the bottom display came back as display ${pick?.id}")
                shown?.close(); shown = null; companionGone()
                all.firstOrNull { it.displayId == pick?.id }?.let { openBottom(it, game) }
            }
            BottomAct.OPEN -> all.firstOrNull { it.displayId == pick?.id }?.let { openBottom(it, game) }
            BottomAct.PARK -> {}
        }
        // round 2 (the review: "Watchdog polls update() every 5 s for the whole session while no second screen is listed"): the watchdog
        // only for a screen that is listed with no window up on it (a refused open, Android's close, the verify); with none listed the
        // display listener's onDisplayAdded / onDisplayChanged bring it back
        if (shown?.alive != true && pick != null) reopenSoon("no bottom window up")
        // round 2 (the review: "A parked host torn down while the user is away now taps the game's UI open"): a parked host that went
        // while away gave nothing back on the top screen; a return that finds no bottom window to show gives the game's own UI back now
        // (never the launcher's moment on the bottom screen: a reopen from Cocoon's grid, the relaunch's other half)
        if (shown == null && pick == null && game == Display.DEFAULT_DISPLAY) gameUiBack()
    }

    /** the game's own UI on the top screen again (GameUi: nothing of ours shows in its place); a tick reconciles it now - and when the
     *  input gate is not live (round 3: no host, so no reader made it live since the park), the give-back reader until it is done */
    private fun gameUiBack() {
        com.jrees.wowcompanion.GameUi.onVisible(false)
        com.jrees.wowcompanion.GameUi.tick()
        if (!com.jrees.wowcompanion.GameUi.inPlace()) giveBackRead()
    }

    /**
     * ROUND 3 (the review, 2026-11-26: "gameUiBack cannot give the game's UI back: GameInput is not live after a park longer than 25 s,
     * and with no host nothing ticks GameUi again"): a return with NO bottom window to show (the panel not listed, every open refused)
     * while the game's own bars and minimap are still hidden for ours. Nothing is ever tapped without the live gate, so a small reader
     * of its own goes on from the last host's place (its snapshot's) - only the input gate and the hub document (GameInput.note,
     * GameUi.onHub), once a second GameUi's reconcile - until the game's UI is as the modes want it with nothing of ours in view
     * ([GameUi.inPlace]), a window comes ([stopGiveBack]: a host reads again), the launcher goes ([hide]) or [GIVE_BACK_MS] passed.
     * The local game only (never Streaming's)
     */
    private var giveBack: com.jrees.wowcompanion.LogTail? = null
    private var giveBackUntil = 0L
    private fun giveBackRead() {
        if (giveBack != null || !running || hidden || !gameOn || com.jrees.wowcompanion.PlayModes.current == com.jrees.wowcompanion.PlayMode.STREAM) return
        val p = com.jrees.wowcompanion.BottomSync.last?.place ?: return
        giveBackUntil = android.os.SystemClock.uptimeMillis() + GIVE_BACK_MS
        com.jrees.wowcompanion.DiagLog.event("app", "no bottom window at the return: the game's own UI given back (its reader from byte ${p.offset})")
        giveBack = com.jrees.wowcompanion.LogTail(p.path, onMessage = { m -> main.post { giveBackDoc(m) } }, onStatus = {}, follow = listOf(p.path),
            resume = p).also { it.start() }
        main.removeCallbacks(giveBackTick); main.postDelayed(giveBackTick, 1_000L)
    }
    private fun giveBackDoc(m: org.json.JSONObject) {
        if (giveBack == null) return
        val current = com.jrees.wowcompanion.GameInput.note(m)
        if (m.optString("type") == "hub") com.jrees.wowcompanion.GameUi.onHub(m, current)
    }
    private val giveBackTick: Runnable = Runnable {
        if (giveBack == null) return@Runnable
        com.jrees.wowcompanion.GameUi.onVisible(false)
        com.jrees.wowcompanion.GameUi.tick()
        if (com.jrees.wowcompanion.GameUi.inPlace() || shown != null || hidden || !running || !gameOn ||
            android.os.SystemClock.uptimeMillis() >= giveBackUntil) stopGiveBack()
        else main.postDelayed(giveBackTick, 1_000L)
    }
    private fun stopGiveBack() {
        main.removeCallbacks(giveBackTick)
        giveBack?.stop(); giveBack = null
    }

    /**
     * the bottom Presentation on [target]; a failed open asks the watchdog (F3), an open one is checked a moment later (F4). Round 2 (the
     * review: "Each failed bottom open builds a full CompanionHost on the main thread and leaks the abandoned Presentation; the watchdog
     * repeats it every 5 s"): the host is made only once the window is up ([HostPresentation.begin]: a refused addView costs a Dialog and
     * nothing more, its display listener taken off), and [BottomSync.OPEN_FAILS_MAX] refusals in a row stop the watchdog until the next
     * display event or return
     */
    private fun openBottom(target: Display, game: Int) {
        var made: HostPresentation? = null
        var shownOk = false
        shown = try {
            HostPresentation(context, target, { rebuild() }, { w, why -> lost(w, why) }).also { made = it; it.show(); shownOk = true; it.begin() }
        } catch (t: Throwable) {
            made?.abandon(shownOk)                                  // the window manager refused it (or its host could not be made): never two
            Log.w(TAG, "presentation: ${t.message}", t)
            openFails++
            com.jrees.wowcompanion.BottomSync.openFailed()
            if (openFails <= 2 || openFails == com.jrees.wowcompanion.BottomSync.OPEN_FAILS_MAX)
                com.jrees.wowcompanion.DiagLog.event("app", "bottom screen failed to open on display ${target.displayId}: ${t.javaClass.simpleName}" +
                    if (!com.jrees.wowcompanion.BottomSync.opensWanted(openFails)) " ($openFails in a row: tried again at the next display change or return)" else "")   // #147
            null
        }
        if (shown != null) { openFails = 0; stopGiveBack() }
        // round 3 (the review: "Also call gameUiBack() when the watchdog stops at OPEN_FAILS_MAX"): every open refused - no window of
        // ours shows the bars and the minimap, so the game's own come back (one look again at the next display event or return)
        else if (!com.jrees.wowcompanion.BottomSync.opensWanted(openFails) && game == Display.DEFAULT_DISPLAY) gameUiBack()
        // the review: the keyboard and the browser page on their own - a page that cannot be made (a WebView provider update) never takes
        // the window down (the open's catch left a dead host's window on screen, and the watchdog stacked one more every 5 s)
        shown?.let { w -> addOns(w) }
        // TapBridge needs to know which display is OURS, so a read of display 0 that answers this
        // screen's size can be told apart from an honest one (it used to guess by panel size)
        com.jrees.wowcompanion.TapBridge.companionDisplayId =
            if (shown != null) target.displayId else Display.INVALID_DISPLAY
        if (shown == null) reopenSoon("the open failed") else { reopenTries = 0; verifySoon() }
        Log.i(TAG, "DS Forever on display ${target.displayId} (game on $game)")
    }

    // ---- THE WAY BACK (BottomSync): Android's own close, a failed open, a window that never drew

    /** Android closed a bottom window by itself (its onStop without our close: a display change or removal, a metrics change) */
    internal fun lost(w: BottomWindow, why: String) {
        // round 2: the host made after this is a rebuild in the report (with or without a snapshot: BottomSync.expectRebuild)
        com.jrees.wowcompanion.BottomSync.expectRebuild(why)
        main.post {
            if (shown === w) { shown = null; companionGone() }
            cancels.note(android.os.SystemClock.elapsedRealtime())
            reopenSoon(why, top = w is TopWindow)
        }
    }

    /** Android's closes lately (3 in 15 s = only the slow cadence: never an open loop) and the watchdog's looks since a window was up */
    private val cancels = CancelGuard()
    private var reopenTries = 0
    /** single screen mode's window: the looks again since it was last up ([reopenSoon] top) */
    private var topOpens = 0
    /** round 2: the opens the window manager refused in a row (the bottom Presentation / the top window): at BottomSync.OPEN_FAILS_MAX
     *  the watchdog stops until the next display event or return ([show], the display listener) */
    private var openFails = 0
    private var topFails = 0
    private fun lookWanted(): Boolean = reopenWanted(running, hidden, SingleScreen.inUse, ScreenSwap.inUse(context), shown?.alive == true)

    /** the watchdog looks again soon, while the bottom window should be up and is not ([top]: single screen mode's window) */
    private fun reopenSoon(why: String, top: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (top) {
            if (!running || hidden || !SingleScreen.inUse || main.hasCallbacks(topRetry)) return
            if (!com.jrees.wowcompanion.BottomSync.opensWanted(topFails)) return
            main.postDelayed(topRetry, reopenDelay(topOpens++, cancels.slow(now) || topTries >= TOP_TRIES))
            return
        }
        if (!com.jrees.wowcompanion.BottomSync.opensWanted(openFails)) return
        if (main.hasCallbacks(reopenLook) || !lookWanted()) return
        Log.i(TAG, "bottom screen: looked for again ($why)")
        main.postDelayed(reopenLook, reopenDelay(reopenTries, cancels.slow(now)))
    }

    private val reopenLook: Runnable = Runnable {
        if (!lookWanted()) { reopenTries = 0; return@Runnable }
        reopenTries++
        com.jrees.wowcompanion.BottomSync.reopenLook()
        if (reopenTries <= 5 || reopenTries % 60 == 0) com.jrees.wowcompanion.DiagLog.event("app", "bottom screen: reopen try $reopenTries")
        // its end asks again while still nothing is up on a listed screen (twoScreens -> reopenSoon, round 2: never with no screen listed,
        // never past BottomSync.OPEN_FAILS_MAX refusals in a row)
        update()
        if (shown?.alive == true) reopenTries = 0
    }

    /** F4: a window opened or shown again at a risky moment (a display still turning on) is looked at [VERIFY_MS] later */
    private fun verifySoon() { main.removeCallbacks(verifyRun); main.postDelayed(verifyRun, VERIFY_MS) }
    private val verifyRun: Runnable = Runnable {
        val w = shown ?: return@Runnable
        if (!running || hidden || w.parked) return@Runnable
        if (!w.alive || !w.drawn) {
            com.jrees.wowcompanion.DiagLog.event("app", "bottom screen not up $VERIFY_MS ms after it came (display ${w.displayId}): made again")
            cancels.note(android.os.SystemClock.elapsedRealtime())
            keepSnapshot(w, "not drawn after it came")
            w.close(); if (shown === w) { shown = null; companionGone() }
            reopenSoon("not drawn", top = w is TopWindow)
        }
    }

    /** BottomSync: the host's snapshot before its window goes (a move, a close, a relaunch, a rebuild, a memory release), the game up.
     *  [counts] false (round 3: the host's own start over - [rebuild]): laid back the same, never a rebuild in the report */
    private fun keepSnapshot(w: BottomWindow?, why: String, counts: Boolean = true) {
        if (w == null) return
        // round 2: a comeback path closes this window - the next host is a rebuild in the report, snapshot or not (BottomSync.expectRebuild)
        if (counts) com.jrees.wowcompanion.BottomSync.expectRebuild(why)
        if (!gameOn) return
        w.snapshot(why)?.let { com.jrees.wowcompanion.BottomSync.keep(it) }
    }

    /**
     * 4.6 (the user's pick Q2, 2026-11-25: kept "until Android signals real memory pressure"): Android's OWN trims (never MemWatch's
     * self-relief, which calls its trimmers at Play and at the world) - a parked host is let go at [parkReleaseAt]'s levels, never at the
     * UI_HIDDEN trim every minimize brings
     */
    private val trims = object : android.content.ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            if (parkReleaseAt(level, light8())) main.post { release("Android's memory trim $level") }
        }
        override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}
        @Deprecated("Deprecated in Java") override fun onLowMemory() { main.post { release("Android's low memory") } }
    }
    private fun light8(): Boolean = runCatching { com.jrees.wowcompanion.DeviceTier.light8() }.getOrDefault(false)

    /**
     * THE PRESSURE ANDROID REPORTED BEFORE THE PARK (the int/1534 review): a trim that came while the window was in use let nothing go
     * ([release]: not parked), and Android calls onTrimMemory again only when its level RISES - the launcher stays a foreground-service
     * process while away, so a pressure that stays as it was never reached the parked host (on Light 8 RUNNING_LOW's rule missed its main
     * case). At the park the level Android holds for this process NOW ([trimLevelNow]: 0 again once memory is normal) is looked at by the
     * same rule ([parkReleaseAt]): UI_HIDDEN, or no pressure, still keeps the host
     */
    private fun pressureAtPark() {
        val level = trimLevelNow()
        if (level == 0) return
        val go = parkReleaseAt(level, light8())
        Log.i(TAG, "parked with Android's memory trim $level ${if (go) "(let go)" else "(kept)"}")
        if (go) main.post { release("Android's memory trim $level (before the park)") }
    }
    /** the trim level Android holds for this process now (ActivityManager.getMyMemoryState's lastTrimLevel), 0 = none or unknown */
    private fun trimLevelNow(): Int = runCatching {
        android.app.ActivityManager.RunningAppProcessInfo().also { android.app.ActivityManager.getMyMemoryState(it) }.lastTrimLevel
    }.getOrDefault(0)

    /** a parked host let go: its snapshot first, then the old full teardown (the window closed); the return opens one restored */
    private fun release(why: String) {
        val w = shown ?: return
        if (!w.parked) return
        com.jrees.wowcompanion.DiagLog.event("app", "bottom screen let go while away ($why): made again at the return, caught up")
        com.jrees.wowcompanion.BottomSync.released()
        keepSnapshot(w, "let go for memory")
        shown?.close(); shown = null; companionGone()
    }

    /** F8: a second built-in screen was seen by this app ("settings" [TWO_SCREENS_SEEN], saved once) */
    private var savedTwo = false
    private fun saveTwoScreens() {
        savedTwo = true
        runCatching { context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean(TWO_SCREENS_SEEN, true).apply() }
        Log.i(TAG, "a second built-in screen: saved (no single screen mode by itself on this device)")
    }

    /** our window is down: TapBridge has no companion screen to exclude */
    private fun companionGone() { com.jrees.wowcompanion.TapBridge.companionDisplayId = Display.INVALID_DISPLAY }

    /** the host asked to start over (its recreate, e.g. after a permission): a fresh Presentation (or activity) */
    // (round 3, the review: "Report still counts non-drops as rebuilds: rebuild() marks expectRebuild for single screen mode ending with
    // the game and for the first-run unpack recreate" - no drop: never counted)
    internal fun rebuild() { main.post { keepSnapshot(shown, "the host asked to start over", counts = false); shown?.close(); shown = null; update() } }

    /** single screen mode: a second screen that came in a fight is looked at again ([SingleStep.RETRY]), a one-screen start's grace
     *  ([SingleStep.WAIT]) */
    private val deviceRetry = Runnable { update() }
    /** single screen mode with the launcher's activity off display 0: looked at again a few times ([singleWaitsForTop]) */
    private val topRetry = Runnable { update() }
    private var topTries = 0
    /** since when this DS Forever has seen nothing but display 0 (null = a second screen now) */
    private var oneSince: Long? = null
    /** how long nothing but display 0 has been seen, at [now] ([one] = so now) */
    private fun oneForMs(one: Boolean, now: Long): Long {
        if (!one) { oneSince = null; return 0L }
        val since = oneSince ?: now.also { oneSince = it }
        return now - since
    }

    /** single screen mode's window on the top screen ([TopWindow]), with what a new bottom window gets (the board, the browser page) */
    private fun openTop(): BottomWindow? {
        val a = context as? Activity ?: return null
        var made: TopWindow? = null
        val w = try { TopWindow(a, this).also { made = it; it.show(); topOpens = 0; topFails = 0 } } catch (t: Throwable) {
            made?.abandon()                                         // its host goes with it (never two)
            Log.w(TAG, "top window: ${t.message}", t)
            // round 2: BottomSync.OPEN_FAILS_MAX refusals in a row stop the looks again until the next display event or return
            topFails++
            com.jrees.wowcompanion.BottomSync.openFailed()
            if (topFails <= 2 || topFails == com.jrees.wowcompanion.BottomSync.OPEN_FAILS_MAX)
                com.jrees.wowcompanion.DiagLog.event("app", "one screen window failed to open: ${t.javaClass.simpleName}")
            reopenSoon("the one screen window failed to open", top = true)
            null
        }
        w?.let { addOns(it) }                                       // (the review: never inside the open's try, as the bottom one)
        return w
    }

    /** what a new window gets after it is up (Streaming's keyboard, the browser page), each on its own: a failure leaves the window up */
    private fun addOns(w: BottomWindow) {
        runCatching { w.board(boardWanted) }.onFailure { Log.w(TAG, "the window's keyboard: ${it.message}", it) }
        pageWanted?.let { m ->
            runCatching { w.page(m) }.onFailure {
                Log.w(TAG, "the window's sign-in page: ${it.message}", it)
                com.jrees.wowcompanion.DiagLog.event("app", "the sign-in page could not be made: ${it.javaClass.simpleName}")
            }
        }
    }

    /** the second screen a two-screen device has (where our activities go in single screen mode), null = none */
    internal fun otherScreen(): Int? = runCatching {
        val screens = dm?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)?.filter { it.isValid }
            ?.map { Screen(it.displayId, builtIn(it), it.name ?: "") }.orEmpty()
        pickScreen(screens, gameDisplay(), null)
    }.getOrNull()

    // ---- #232 SWAP SCREENS: DS Forever's activity on display 0

    /** the activity asked for, until it comes and while it is up ([adopt] / [released]); [close] finishes it */
    private inner class AskedActivity(override val displayId: Int) : BottomWindow {
        var activity: CompanionScreenActivity? = null
        var closed = false
        override val typing: Boolean get() = activity?.typing == true
        override fun board(on: Boolean) { activity?.board(on) }
        override fun boardPress(button: String) { activity?.boardPress(button) }
        override fun page(make: ((Context) -> View)?) { activity?.page(make) }
        override fun close() { closed = true; activity?.finish(); activity = null }
        /** BottomSync: asked or up (the ask that never came is [ADOPT_MS]'s); parking is not supported here (closed as before) */
        override val alive: Boolean get() = !closed
    }

    /** the activity's recent starts ([startAllowed]); [gaveUp] = it kept closing: not again this run */
    private val starts = ArrayList<Long>()
    private var gaveUp = false
    /** #384 recovery: this DS Forever's ask is counted (ScreenSwap.tryStart: once per run) / its activity settled (ScreenSwap.settled) */
    private var tried = false
    private var settled = false

    private fun openActivity(id: Int): BottomWindow? {
        if (gaveUp) return null
        // #384 recovery: the first ask counts as a Swap screens start; MAX_FAILS unsettled ones in a row already = Off now
        if (!tried) {
            tried = true
            if (!ScreenSwap.tryStart(context)) {
                gaveUp = true
                swapOff("the bottom screen did not settle on the last ${ScreenSwap.MAX_FAILS} starts")
                return null
            }
        }
        val now = android.os.SystemClock.elapsedRealtime()
        starts.removeAll { now - it >= START_GUARD_MS }
        if (!startAllowed(starts, now)) {
            gaveUp = true
            Log.w(TAG, "Swap screens: the bottom screen's activity was started ${starts.size} times in ${START_GUARD_MS / 1000} s: not again this run")
            com.jrees.wowcompanion.DiagLog.event("app", "Swap screens: the bottom screen kept closing on display $id")
            swapOff("the bottom screen kept closing")
            return null
        }
        // F6 (BottomSync): an ask is no longer counted by itself - only Android's closes and an ask that never came ([swapStartCounts]),
        // so three quick minimize / return cycles never give Swap screens up
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
                    starts.add(android.os.SystemClock.elapsedRealtime())
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
        if (!settled) main.postDelayed({ settleCheck(w, a) }, ScreenSwap.SETTLE_MS)
        return true
    }

    /**
     * #384 recovery: the bottom activity still up [ScreenSwap.SETTLE_MS] later with the controller at the game (not [holdsKeys]): Swap
     * screens works on this device, the unsettled-start count starts over. Holding the keys = not yet (looked at again a while later)
     */
    private fun settleCheck(w: AskedActivity, a: CompanionScreenActivity) {
        if (settled || !running || shown !== w || w.closed || w.activity !== a) return
        if (a.holdsKeys) { main.postDelayed({ settleCheck(w, a) }, ScreenSwap.SETTLE_MS); return }
        settled = true
        ScreenSwap.settled(context)
        Log.i(TAG, "Swap screens: the bottom screen settled on display ${w.displayId}")
    }

    /**
     * #384 recovery: Swap screens Off, saved (ScreenSwap.off). The launcher's own DS Forever with no game starts again at once in the
     * Thor's arrangement (the Restart button's route: nothing is lost); with the game up it applies from the next start
     */
    private fun swapOff(why: String) {
        ScreenSwap.off(context, why)
        if (!gameOn && ScreenSwap.restart === restartHook) restartHook()
        else Log.i(TAG, "Swap screens Off from the next start ($why)")
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
            if (swapStartCounts(closedByUs = false)) starts.add(android.os.SystemClock.elapsedRealtime())   // F6: Android's close counts
            com.jrees.wowcompanion.DiagLog.event("app", "Swap screens: the bottom screen's activity was closed by Android")
            main.post { update() }
        }
    }

    /**
     * the launcher's task (the game) to the front: its display becomes the top one again, so the controller's keys go to the game and
     * not to display 0 (a display whose top activity has no focusable window would hold them).
     * #232 review: never while the launcher is going away ([frontWanted]): the bottom activity's typing ends in its onStop, and that
     * moved the launcher back over the home screen the player had just pressed Home for.
     * #383 / #384 (a player on a Retroid Pocket Nova + Dual Screen Add-on, 1.0.32: "the bottom screen's activity restarts itself until
     * the guard gives up", then "input ANRs once the loop is stopped"): the move is [toFront] (never AppTask.moveToFront, which closed
     * the bottom activity each time), at most once a [FRONT_GAP_MS] counted HERE (each bottom activity is a new one: a field of its own
     * never held a second one back), and an ask inside the gap is not dropped: it runs once the gap is over if the bottom activity
     * still holds the keys ([CompanionScreenActivity.holdsKeys]; dropped, display 0 kept them and every press timed out)
     * Single screen mode is its second user ([keysTop]): the ask inside the gap runs after it while the other screen still has the
     * keys ([keysAway]). True = moved now
     */
    internal fun gameToFront(why: String): Boolean {
        val a = context as? Activity ?: return false
        if (!frontWanted(running, hidden, a.isFinishing)) { Log.i(TAG, "Swap screens: the game's task stays ($why; the launcher is away)"); return false }
        val now = android.os.SystemClock.elapsedRealtime()
        val wait = frontWait(now, frontAt)
        if (wait > 0L) {
            if (!frontLater) {
                frontLater = true
                main.postDelayed({
                    frontLater = false
                    if ((shown as? AskedActivity)?.activity?.holdsKeys == true || singleKeysAway()) gameToFront("$why, after the gap")
                }, wait)
            }
            return false
        }
        frontAt = now
        val moved = toFront(a, a.taskId, "game to the front")
        if (moved) Log.i(TAG, "Swap screens: the game's task to the front ($why)")
        return moved
    }
    /** the last [gameToFront] move (any bottom activity's) and whether an ask waits for the gap to end */
    private var frontAt = Long.MIN_VALUE / 2
    private var frontLater = false

    // ---- SINGLE SCREEN MODE: the switch and the controller's keys

    /** the wheel's button (SingleScreen.request) */
    private val singleHook: (Boolean, String) -> Boolean = { on, why -> toggleSingle(on, why) }

    /**
     * THE SWITCH (main thread; the host waited for the pause wheel's controller free): one screen [on] / off. GameUi's top-screen taps
     * wait meanwhile; the window that is up closes (its host's onStop / onDestroy run at once: never two CompanionHosts) and [update]
     * opens the other - the TopWindow, or the bottom Presentation on the screen pickScreen gives (the existing code). Posted, so the
     * old host's own call has returned before it goes. False = refused (not this DS Forever's, Streaming's, a one-screen device's off)
     */
    internal fun toggleSingle(on: Boolean, why: String): Boolean {
        if (!singleSwitchAllowed(running, hidden, current === this, streamOwned, SingleScreen.why, on)) {
            Log.i(TAG, "one screen ${if (on) "on" else "off"}: refused ($why)")
            return false
        }
        if (on == SingleScreen.inUse) return true
        com.jrees.wowcompanion.GameUi.holdTaps(System.currentTimeMillis() + SingleScreen.SWITCH_HOLD_MAX_MS)
        main.post {
            if (!running || hidden || on == SingleScreen.inUse) return@post
            // the review (2026-11-25): a fight that began since the host's look - never a teardown in a fight
            if (SingleScreen.fightNow()) {
                Log.i(TAG, "one screen ${if (on) "on" else "off"}: refused (a fight began) ($why)")
                com.jrees.wowcompanion.DiagLog.event("app", "screens: one screen ${if (on) "on" else "off"} refused (a fight began)")
                (shown as? TopWindow)?.hint(SingleScreen.NOT_FIGHT, SingleScreen.HINT_MS)
                return@post
            }
            SingleScreen.set(on, if (on) SingleScreen.Why.USER else null, android.os.SystemClock.uptimeMillis())
            com.jrees.wowcompanion.DiagLog.event("app", "screens: one screen ${if (on) "on" else "off"} ($why)")
            Log.i(TAG, "one screen ${if (on) "on" else "off"} ($why)")
            keysCancel()
            shown?.close(); shown = null; companionGone()
            update()
            // the review (2026-11-25): Android's keys were on the other screen at the switch (a two-screen activity of ours there left them):
            // no new top-resumed answer comes, so the last one is acted on now (the game's task forward)
            if (on && !topNow) keysTop(false)
        }
        return true
    }

    /** one of our activities is up on the other screen (the host's openOnBottom): its keys are not taken back meanwhile */
    private val keysPaused: Boolean get() = SingleScreen.keysPaused
    /** single screen mode: the other screen (Android's launcher, an app there) has the controller's keys now */
    private var keysAway = false
    private var keysHintStuck = false
    private fun singleKeysAway(): Boolean = SingleScreen.inUse && keysAway

    /**
     * THE CONTROLLER AFTER A TOUCH ON THE BOTTOM SCREEN (single screen mode; the open ruling R1, built default A "The game keeps the
     * controller"): Android's launcher on the bottom screen takes the keys at a touch (its window can take them; our Presentation never
     * could), and the launcher's activity is then no more the TOP resumed one (this device has per-display focus: its window focus stays).
     * [top] false: [SingleScreen.KEYS_BACK_MS] later the game's task is brought back to the front ([gameToFront]: moveTaskToFront only
     * the game's task, never AppTask's move, never a startActivity - that pauses the launcher), the plate "Controller back"; still away
     * [SingleScreen.KEYS_STUCK_MS] later, "Tap the game for the controller" until Android gives them back (a tap on the top screen does).
     * R1 B ([SingleScreen.KEYS_BACK] false): that hint at once, nothing moved. Two screens / Swap screens: nothing here runs
     */
    internal fun keysTop(top: Boolean) {
        topNow = top
        if (top) {
            if (!keysAway) return
            keysAway = false
            main.removeCallbacks(keysBackRun); main.removeCallbacks(keysStuckRun)
            if (keysHintStuck) { keysHintStuck = false; (shown as? TopWindow)?.hint(null) }
            com.jrees.wowcompanion.DiagLog.event("app", "keys: the game has the controller again")
            return
        }
        val a = context as? Activity ?: return
        if (!keysBackWanted(SingleScreen.inUse, gameOn, running, hidden, a.isFinishing, shown?.typing == true, keysPaused)) return
        if (keysAway) return
        keysAway = true
        SingleScreen.noteKeys(away = true)
        com.jrees.wowcompanion.DiagLog.event("app", "keys: the other screen took the controller")
        when (keysStep()) {
            KeysStep.MOVE_LATER -> {
                main.postDelayed(keysBackRun, SingleScreen.KEYS_BACK_MS)
                main.postDelayed(keysStuckRun, SingleScreen.KEYS_BACK_MS + SingleScreen.KEYS_STUCK_MS)
            }
            KeysStep.HINT_NOW -> { keysHintStuck = true; (shown as? TopWindow)?.hint(SingleScreen.TAP_FOR_KEYS) }
        }
    }
    /** the launcher's activity is the top resumed one (its last onTopResumedActivityChanged answer) */
    private var topNow = true

    /**
     * the review (2026-11-25): a touch DOWN on the top window or one of its small windows. They are never focusable, so Android never moves
     * its keys back to display 0 for it (WMS moves a display to the top only for a window that takes keys) - and a panel covers all of
     * display 0. While the other screen has the keys, the touch is the player's wish to play here: the game's task forward (the one-second
     * gap coalesces a palm's repeats)
     */
    internal fun ourTouch() {
        if (!singleKeysAway()) return
        if (gameToFront("one screen: a touch on our window")) SingleScreen.noteKeys(back = true)
    }
    /** NPC (spec 8.4): one of our activities on the other screen closed (SingleScreen.keysPaused off): Android leaves the keys where they
     *  were and sends no new answer, so the last one is acted on again - taken back only while the game is not on top */
    private val keysAgainHook: () -> Unit = { main.post { if (!topNow) keysTop(false) } }
    private val keysBackRun = Runnable {
        if (!keysAway || !SingleScreen.inUse) return@Runnable
        if (gameToFront("one screen: the other screen took the controller")) {
            SingleScreen.noteKeys(back = true)
            (shown as? TopWindow)?.hint(SingleScreen.CONTROLLER_BACK, SingleScreen.HINT_MS)
        }
    }
    private val keysStuckRun = Runnable {
        if (!keysAway || !SingleScreen.inUse) return@Runnable
        SingleScreen.noteKeys(stuck = true)
        com.jrees.wowcompanion.DiagLog.event("app", "keys: the controller stayed on the other screen")
        keysHintStuck = true
        (shown as? TopWindow)?.hint(SingleScreen.TAP_FOR_KEYS)
    }
    /**
     * THE KEYS AFTER A RETURN (the int/1534 review): Android's top-resumed answer comes right after the resume; none comes when the
     * other screen kept the controller's keys over the park or the sleep (no change, no call), and the hide dropped the stuck state - the
     * keys stayed there with no plate and nothing taking them back. [KEYS_RETURN_MS] after the return the last answer is
     * acted on as a switch does ([toggleSingle]); then the normal rules (the game's task forward, the plate only if they stay away)
     */
    private val keysAfterReturn = Runnable {
        if (SingleScreen.inUse && !hidden && !topNow && (shown as? TopWindow)?.alive == true) keysTop(false)
    }

    /** a switch, a hide, a stop: nothing waits any more. The int/1534 review: the pinned "Tap the game for the controller" goes with the
     *  stuck state - a hide now PARKS the same top window (its plate kept), and once the flags are dropped nothing else would ever clear
     *  it: at the return it stood over the game while the controller already played there (a switch, a stop: the window closes anyway) */
    private fun keysCancel() {
        if (keysHintStuck) (shown as? TopWindow)?.hint(null)
        keysAway = false; keysHintStuck = false
        main.removeCallbacks(keysBackRun); main.removeCallbacks(keysStuckRun); main.removeCallbacks(keysAfterReturn)
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
        // #384 review: this DS Forever goes BEFORE the new activity comes. Android destroys a finishing activity only once the next one
        // is up, so the new activity's begin() still found this one current and made none, then the old activity's end() stopped this
        // one: no DS Forever on either screen after a Restart, the recovery's own included. The old activity's pause / stop hide only
        // its own ([hideFor])
        stop()
        if (current === this) current = null
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

        /** #383 / #384, pure for the tests: 0 = [gameToFront] moves now; else how long until [FRONT_GAP_MS] has passed since [lastAt] */
        fun frontWait(now: Long, lastAt: Long): Long = (FRONT_GAP_MS - (now - lastAt)).coerceIn(0L, FRONT_GAP_MS)
        const val FRONT_GAP_MS = 1_000L

        /**
         * #383 / #384: a task of ours to the front of its display, that display on top (the controller's keys go there). NEVER
         * AppTask.moveToFront: that is Recents' route (startActivityFromRecents), which first puts display 0's HOME task in front - over
         * the bottom activity - and Android then trims a task that is excluded from Recents and behind Home on display 0: the bottom
         * activity closed, DS Forever opened it again, it asked again (the restart loop). ActivityManager.moveTaskToFront moves only the
         * task asked for (REORDER_TASKS: a normal permission, no prompt); MOVE_TASK_NO_USER_ACTION: no "user leaving" (the launcher's
         * onUserLeaveHint hides DS Forever). True = moved
         */
        internal fun toFront(ctx: Context, taskId: Int, what: String): Boolean = try {
            val am = ctx.getSystemService(android.app.ActivityManager::class.java)
            if (am == null) false else { am.moveTaskToFront(taskId, android.app.ActivityManager.MOVE_TASK_NO_USER_ACTION); true }
        } catch (t: Throwable) {
            Log.w(TAG, "Swap screens: $what: ${t.message}")
            com.jrees.wowcompanion.DiagLog.event("app", "Swap screens: $what failed: ${t.javaClass.simpleName}")
            false
        }

        /**
         * #232 review: the display whose panel sizes InputSink's tap pixels - display 0 ALWAYS, Swap screens or not. TapBridge (the
         * tap-pad cells) and HomeLoginWatch (the sign-in's fields) make a tap's pixels in display 0's space; InputSink turns them back
         * into a fraction of the game's screen over this same size
         */
        const val TAP_SPACE_DISPLAY = Display.DEFAULT_DISPLAY

        // ---- SINGLE SCREEN MODE, pure for the tests (SingleScreen)

        /** what [update] does about single screen mode ([singleStep]); WAIT = a one-screen start's grace (looked at again) */
        enum class SingleStep { NONE, DEVICE_ON, DEVICE_OFF, RETRY, WAIT }

        /**
         * the start rules (the user's picks 2026-11-25: "Two screen start however if its a single screen device then it starts in single
         * screen mode"; one-screen devices "Yes, automatically"): nothing but display 0 ([otherScreen] false, the game there, Swap screens
         * not in use) = single screen mode by itself unless the button already put it on; a second screen while it is the device's =
         * two screens again, never in a [fight] (looked at again: RETRY). The button's own choice stays until the game ends.
         * The review (2026-11-25): [sawSecond] = a second screen was seen in this process (a two-screen device: its loss is no single
         * screen mode, the base way), and the one-screen state must have lasted [oneForMs] >= SingleScreen.ONE_GRACE_MS (WAIT till then)
         */
        fun singleStep(otherScreen: Boolean, game: Int, swap: Boolean, why: SingleScreen.Why?, fight: Boolean, sawSecond: Boolean = false,
                       oneForMs: Long = SingleScreen.ONE_GRACE_MS): SingleStep {
            val one = !otherScreen && game == Display.DEFAULT_DISPLAY && !swap
            return when {
                one && why == null && sawSecond -> SingleStep.NONE
                one && why == null && oneForMs < SingleScreen.ONE_GRACE_MS -> SingleStep.WAIT
                one && why == null -> SingleStep.DEVICE_ON
                !one && why == SingleScreen.Why.DEVICE -> if (fight) SingleStep.RETRY else SingleStep.DEVICE_OFF
                else -> SingleStep.NONE
            }
        }

        /** single screen mode with the launcher's activity on another display than 0 (Swap screens off): its window waits for the move to
         *  the top screen (the review, 2026-11-25) */
        fun singleWaitsForTop(inUse: Boolean, swap: Boolean, game: Int): Boolean = inUse && !swap && game != Display.DEFAULT_DISPLAY
        const val TOP_RETRY_MS = 500L
        const val TOP_TRIES = 10

        /** a second presentation screen was seen in this process (the review, 2026-11-25: a two-screen device never turns single screen mode
         *  on by itself when that screen goes a moment) */
        @Volatile var sawSecond = false

        // ---- THE BOTTOM SCREEN COMES STRAIGHT BACK (2026-11-25; BottomSync), pure for the tests

        /** what [update] / [hide] do with the bottom window (two screens; single screen mode and Swap screens keep their own branches) */
        enum class BottomAct { NONE, OPEN, UNPARK, PARK, CLOSE, MOVE, KEEP_PARKED }
        /** the bottom window as the step sees it: its display, made and not dismissed ([BottomWindow.alive]), parked */
        data class Win(val display: Int, val alive: Boolean, val parked: Boolean)

        /**
         * [hidden] the launcher away: a window up = PARK (a parked one stays). Showing: a dead window counts as none (F1); no other screen
         * than the game's ([pick] null) = CLOSE, but a parked window on the game's display (the launcher there a moment: F5) is KEPT
         * parked; no window = OPEN; parked on the picked display = UNPARK; another display (a new id) = MOVE
         */
        fun bottomStep(hidden: Boolean, pick: Int?, game: Int, win: Win?): BottomAct {
            val w = win?.takeIf { it.alive }
            if (hidden) return if (w != null && !w.parked) BottomAct.PARK else BottomAct.NONE
            if (pick == null) return when {
                w == null -> BottomAct.NONE
                w.parked && w.display == game -> BottomAct.KEEP_PARKED
                else -> BottomAct.CLOSE
            }
            if (w == null) return BottomAct.OPEN
            if (w.display == pick) return if (w.parked) BottomAct.UNPARK else BottomAct.NONE
            return BottomAct.MOVE
        }

        /** the watchdog's looks: 250, 500, 1000, 2000 ms, then every [REOPEN_SLOW_MS] ([slow]: the guard tripped) */
        private val REOPEN_STEPS_MS = longArrayOf(250L, 500L, 1_000L, 2_000L)
        const val REOPEN_SLOW_MS = 5_000L
        fun reopenDelay(tries: Int, slow: Boolean): Long =
            if (slow || tries >= REOPEN_STEPS_MS.size) REOPEN_SLOW_MS else REOPEN_STEPS_MS[tries.coerceAtLeast(0)]

        /** Android closing the bottom window [CANCEL_GUARD_N] times in [CANCEL_GUARD_MS] = the slow cadence only (never an open loop) */
        const val CANCEL_GUARD_N = 3
        const val CANCEL_GUARD_MS = 15_000L
        class CancelGuard {
            private val at = ArrayList<Long>()
            fun note(now: Long) { at.removeAll { now - it >= CANCEL_GUARD_MS }; at.add(now) }
            fun slow(now: Long): Boolean = at.count { now - it < CANCEL_GUARD_MS } >= CANCEL_GUARD_N
        }

        /** the watchdog runs while the bottom window should be up and is not: running, showing, two screens, no Swap screens */
        fun reopenWanted(running: Boolean, hidden: Boolean, single: Boolean, swap: Boolean, alive: Boolean): Boolean =
            running && !hidden && !single && !swap && !alive

        /** the int/1534 review: single screen mode's one look at the keys after a return (keysAfterReturn) - well after Android's own
         *  top-resumed answer, which comes right after the resume (acted on before it, every return would log a false keys-away) */
        const val KEYS_RETURN_MS = 1_000L
        /** a start with a fresh snapshot and the game running (a configuration relaunch) shows the bottom screen sooner */
        const val SHOW_AGAIN_MS = 250L
        fun showAfter(fresh: Boolean, gameRunning: Boolean): Long = if (fresh && gameRunning) SHOW_AGAIN_MS else SHOW_AFTER_MS
        /** round 3: the give-back reader's longest run (a return with no bottom window: DsForeverScreen.giveBackRead) */
        const val GIVE_BACK_MS = 120_000L

        /**
         * 4.6 (Q2: kept "until Android signals real memory pressure"): Android's trim [level] lets a PARKED host go at RUNNING_CRITICAL
         * and from BACKGROUND up (the low-memory call too), RUNNING_LOW on Light 8 only; NEVER at UI_HIDDEN, the trim every minimize brings
         */
        fun parkReleaseAt(level: Int, light8: Boolean): Boolean = when {
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> false
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> true
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> true
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> light8
            else -> false
        }

        /**
         * F5's one look again (the review, 2026-11-26): only while that activity is still up off the game's display with its DS Forever
         * shown ([shownThere]: started and not hidden) - the user may have gone Home or slept meanwhile, and a move then brought the game
         * back to the front against it
         */
        fun moveAgainWanted(finishing: Boolean, destroyed: Boolean, display: Int?, target: Int, shownThere: Boolean): Boolean =
            !finishing && !destroyed && display != target && shownThere

        /** F6: a Swap screens close counts against the start guard only when it was Android's (or the ask never came), never ours */
        fun swapStartCounts(closedByUs: Boolean): Boolean = !closedByUs

        /** F8: a second built-in screen seen in this process, or saved by an earlier one */
        fun twoScreensSeen(process: Boolean, saved: Boolean): Boolean = process || saved
        const val TWO_SCREENS_SEEN = "two_screens_seen"

        /** a new or shown-again window is looked at this long after (F4); display changes settle this long first (F2) */
        const val VERIFY_MS = 700L
        const val DISPLAY_SETTLE_MS = 150L

        /** the game's end turns the button's single screen mode off (every start is two screens); a one-screen device's stays */
        fun singleOffAtGameEnd(why: SingleScreen.Why?, relaunch: Boolean = false): Boolean = why == SingleScreen.Why.USER && !relaunch

        /** the button's switch may run: this DS Forever runs, shows and is the current one, not Streaming's (R2), and a one-screen
         *  device never turns it off (no other screen to go to) */
        fun singleSwitchAllowed(running: Boolean, hidden: Boolean, current: Boolean, stream: Boolean, why: SingleScreen.Why?, on: Boolean): Boolean =
            running && !hidden && current && !stream && !(!on && why == SingleScreen.Why.DEVICE)

        /** the keys are taken back only in single screen mode, with the game up and the launcher showing, never while a field of ours
         *  types (the top window holds them on purpose) nor while an activity of ours is up on the other screen */
        fun keysBackWanted(single: Boolean, gameOn: Boolean, running: Boolean, hidden: Boolean, finishing: Boolean, typing: Boolean,
                           keysPaused: Boolean): Boolean =
            single && gameOn && frontWanted(running, hidden, finishing) && !typing && !keysPaused

        /** R1: A = the game's task back after [SingleScreen.KEYS_BACK_MS] (the hint only if that fails); B = the hint at once */
        enum class KeysStep { MOVE_LATER, HINT_NOW }
        fun keysStep(back: Boolean = SingleScreen.KEYS_BACK): KeysStep = if (back) KeysStep.MOVE_LATER else KeysStep.HINT_NOW

        /** the launcher's activity is Android's top resumed one again, or not (MainActivity.onTopResumedActivityChanged): only ITS OWN
         *  DS Forever hears it */
        fun topResumedFor(activity: Activity, top: Boolean) { current?.takeIf { it.context === activity }?.keysTop(top) }

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
        fun end(activity: Activity) {
            current?.takeIf { it.context === activity }?.let { it.relaunching = activity.isChangingConfigurations; it.stop() }
            if (current?.context === activity) current = null
        }
        /**
         * the launcher activity's pause / stop / leave hint: only ITS OWN DS Forever hides. #384 review: after a Restart the new
         * activity (and its DS Forever) is up before Android stops the old one, whose stop hid whichever was current - the new one's
         */
        fun hideFor(activity: Activity) { current?.takeIf { it.context === activity }?.hide() }

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
            prev?.hide(park = false)
            beforeStream = prev
            current = DsForeverScreen(activity).also { it.streamOwned = true; it.start() }
        }

        /**
         * feat/exit-now: DS Forever closes at once (ExitNow): its bottom screen closed for good - Streaming's and the launcher's own
         * one under it (never brought back by [endStream] as Streaming's activity goes), the Presentation or Swap screens' activity
         * on the other display finished. Main thread
         */
        fun closeAll() {
            val prev = beforeStream
            beforeStream = null
            prev?.stop()
            current?.stop()
            current = null
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
        /** F5: the guard's one look again was asked for this stay off the game's display */
        @Volatile private var movedAgain = false

        /** called on every start and resume; true = this activity is going away (the caller returns) */
        fun moveToTop(activity: Activity, gameRunning: Boolean): Boolean {
            val d = activity.display?.displayId ?: Display.DEFAULT_DISPLAY
            val now = android.os.SystemClock.elapsedRealtime()
            // #384 recovery by hand (adb, after a force stop: am start -n <app>/app.gamenative.MainActivity --ez dsforever_swap_off true):
            // Swap screens saved Off before this run takes it; the extra goes, so a restart's copy of the intent never repeats it
            if (activity.intent?.getBooleanExtra(ScreenSwap.EXTRA_OFF, false) == true) {
                activity.intent.removeExtra(ScreenSwap.EXTRA_OFF)
                ScreenSwap.off(activity, "the start asked for it")
            }
            // #232: the game's display (display 0 unless Swap screens is in use: then the second screen)
            val target = gameTarget(activity, d)
            val move = decide(d, gameRunning || up, now - lastMoveAt, target)
            Log.i(TAG, "resume: launcher on display $d (game running $gameRunning, up $up) -> $move" +
                if (target != Display.DEFAULT_DISPLAY) " (Swap screens: the game's display is $target)" else "")
            if (d == target) { up = true; movedAgain = false }
            // F5 (BottomSync): a move the guard skipped (a second reopen from the bottom grid within MOVE_GUARD_MS) is looked at ONCE more at
            // the guard's end, while the launcher is still off its display with the game running (never a loop: once per stay there)
            if (move == Move.NONE && d != target && (gameRunning || up) && !movedAgain) {
                movedAgain = true
                Handler(Looper.getMainLooper()).postDelayed({
                    // the review: never after the user left again (Home, the lid): a move then brought the game back over their Home press
                    val there = current?.takeIf { it.context === activity }?.let { it.running && !it.hidden } == true
                    if (moveAgainWanted(activity.isFinishing, activity.isDestroyed, activity.display?.displayId, target, there)) moveToTop(activity, gameRunning)
                    else movedAgain = false
                }, (MOVE_GUARD_MS - (now - lastMoveAt)).coerceIn(0L, MOVE_GUARD_MS) + 100L)
            }
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
    /** browser login's page over the whole screen ([DsForeverScreen.browserPage]); null = gone */
    fun page(make: ((Context) -> View)?)
    fun close()

    // ---- the bottom screen comes straight back (BottomSync, 2026-11-25)
    /** made and not dismissed (Android's own close included) */
    val alive: Boolean get() = true
    /** kept while away ([park]) */
    val parked: Boolean get() = false
    /** up with its decor attached (the look [DsForeverScreen.VERIFY_MS] after an open or a return) */
    val drawn: Boolean get() = alive
    /** away: nothing drawn, touchable, focusable or kept awake, the host away; false = not supported (the caller closes it) */
    fun park(why: String): Boolean = false
    /** back exactly as it was */
    fun unpark(why: String) {}
    /** the host's snapshot for a host made again ([com.jrees.wowcompanion.BottomSync]), null = none */
    fun snapshot(why: String): com.jrees.wowcompanion.BottomSync.Snapshot? = null
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

/**
 * BROWSER LOGIN'S PAGE IN A BOTTOM WINDOW (the user, 2026-11-18; [DsForeverScreen.browserPage]): one full-screen view over the window's
 * content ([add]); while it shows the window is FOCUSABLE ([focusable] true: Blizzard's fields take the keyboard on this screen), and
 * when it goes the focus goes back to the game ([focusable] false). The window's touch rule ([BottomTyping.touch]) stands aside while
 * it is [up] (a touch outside an EditText must not give the focus back mid-sign-in). Main thread.
 */
class BottomPageSlot(private val add: (View) -> Unit, private val typing: () -> Boolean, private val focusable: (Boolean) -> Unit) {
    var view: View? = null
        private set
    val up: Boolean get() = view != null

    /** [make]'s page for [ctx] in place of the one shown; null = none */
    fun show(ctx: Context, make: ((Context) -> View)?) {
        val had = up
        drop()
        view = make?.invoke(ctx)?.also(add)
        if (up && !typing()) focusable(true)
        else if (!up && had && typing()) focusable(false)
    }

    /** the page off its window (the window goes: its own onStop gives the focus back) */
    fun drop() { view?.let { (it.parent as? ViewGroup)?.removeView(it) }; view = null }

    companion object {
        val FILL get() = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
}

/**
 * the bottom screen's window: hosts the shared [CompanionHost] exactly as the app's MainActivity does. THE BOTTOM SCREEN COMES STRAIGHT
 * BACK (BottomSync): [park] keeps it while the launcher is away (decor GONE: no surface, no touch; never focusable; no keep-screen-on;
 * the host away), [unpark] shows it again as it was; an onStop without our [close] is Android's own ([onLost]: a snapshot first)
 */
private class HostPresentation(outer: Context, display: Display, private val onRecreate: () -> Unit,
                               private val onLost: (BottomWindow, String) -> Unit) : Presentation(outer, display), HostShell, BottomWindow {
    private var host: CompanionHost? = null
    private var board: com.jrees.wowcompanion.stream.SignInBoard? = null
    /** between onCreate and onStop (F11: Dialog.isShowing is false while the decor is GONE: a parked host must not think it is gone) */
    private var up = false
    /** our own close ([close]): an onStop without it is Android's (a display removal or change: Presentation's own listener) */
    private var closing = false
    private var parkedNow = false

    override val displayId: Int get() = display.displayId
    override fun close() { closing = true; runCatching { dismiss() } }
    override val alive: Boolean get() = up && !closing
    override val parked: Boolean get() = parkedNow
    override val drawn: Boolean get() = up && window?.decorView?.isAttachedToWindow == true
    override fun snapshot(why: String) = host?.snapshot(why)

    /**
     * away. Round 2 (the review: "Parking toggles the decor's View visibility, so rooms reset as if left"): the host goes away FIRST (its
     * taps held, GameInput.away), then the decor goes GONE inside [com.jrees.wowcompanion.BottomSync.flip], so a room's visibility
     * handler knows it is a park and keeps its state (Chat's keyboard and menus, Professions' search)
     */
    override fun park(why: String): Boolean {
        if (!up || closing) return false
        if (parkedNow) return true
        parkedNow = true
        if (typing) typer.focusable(false)
        window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        host?.park(why)
        com.jrees.wowcompanion.BottomSync.flip { window?.decorView?.visibility = View.GONE }
        return true
    }

    /** back: the host FIRST (its taps free, its ticks and reader on), then the decor VISIBLE inside the flip (round 2: no room reset) */
    override fun unpark(why: String) {
        if (!parkedNow || !up) return
        parkedNow = false
        // round 2 (the review: "held is cleared only when our activity is destroyed. A picker left in the background keeps the bottom
        // window hidden at every return"): an activity of ours keeps the window hidden only while it is still UP on a screen (its task
        // shows); one left behind by the Thor's Home (only stopped, out of Recents: the user cannot reach it) no longer does
        if (held && !heldUp()) held = false
        window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        host?.unpark(why)
        // (the review: an activity of ours still over this display - openOnBottom - keeps the window hidden until it closes)
        com.jrees.wowcompanion.BottomSync.flip { window?.decorView?.visibility = if (held) View.GONE else View.VISIBLE }
        if (pageSlot.up) typer.focusable(true)
    }

    /**
     * one of our activities over this display (CompanionHost.openOnBottom: the import picker, the DLL picker, the microphone question):
     * the window hidden until it closes. The review: its close while PARKED never shows the window over the Thor's bottom launcher (the
     * return shows it), and a return while it is still up keeps the window hidden behind it ([unpark], [heldUp])
     */
    private var held = false
    private var heldClass: String? = null
    override fun hostHeld(on: Boolean, cls: Class<*>?): Boolean {
        held = on
        heldClass = if (on) cls?.name else null
        if (on) HeldTasks.watch(context)
        if (up && !parkedNow) window?.decorView?.visibility = if (on) View.GONE else View.VISIBLE
        return true
    }

    /** round 2: the held activity is still up - alive, its task shown on a screen now ([HeldTasks.shows]); no class known = the old rule */
    private fun heldUp(): Boolean = heldClass?.let { HeldTasks.shows(context, it) } ?: true

    /** Streaming's Battle.net keyboard over the whole screen */
    override fun board(on: Boolean) {
        if (on && board == null) board = com.jrees.wowcompanion.stream.SignInBoard(context) { DsForeverScreen.current?.signInClose?.invoke() }.also {
            addContentView(it, android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
        } else if (!on) { board?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }; board = null }
    }
    override fun boardPress(button: String) { board?.press(button) }

    /** browser login's page ([DsForeverScreen.browserPage]): over everything, and this window holds the focus while it shows */
    private val pageSlot = BottomPageSlot({ v -> addContentView(v, BottomPageSlot.FILL) }, { typer.typing }, { on -> typer.focusable(on) })
    override fun page(make: ((Context) -> View)?) {
        pageSlot.show(context, make)
        if (parkedNow && typing) typer.focusable(false)          // parked: never focusable (the page shows, and takes the keys, at the return)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        )
        // #230: named, so an input ANR line says which window stopped answering (untitled = the package name, like the others)
        com.jrees.wowcompanion.WinTitle.apply(window, com.jrees.wowcompanion.WinTitle.BOTTOM)
        up = true
        // round 2: no host here - Dialog.show runs this BEFORE the window manager adds the window, and a refused add (a display still
        // turning on: InvalidDisplayException / BadTokenException) left a whole host built on the main thread for nothing ([begin])
    }

    /**
     * the host, once the window is really up (DsForeverScreen.openBottom right after [show] returned: the same main-thread turn, before
     * any frame is drawn). Round 2 (the review: "create the CompanionHost only after show() succeeds")
     */
    fun begin() {
        if (!up || closing || host != null) return
        host = CompanionHost.made(context, this, null)
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
        // browser login's page holds the focus for its own fields (a touch outside an EditText must not give it back to the game)
        if (!pageSlot.up) typer.touch(ev)
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
        val byAndroid = !closing
        if (byAndroid) {
            // Android closed this window by itself (a display removal or change): a snapshot for the host made again, and the way back
            // (round 2: a relaunch under way is the same game up)
            if (com.jrees.wowcompanion.LauncherHooks.gameUp() || com.jrees.wowcompanion.BottomSync.relaunchPending(android.os.SystemClock.elapsedRealtime()))
                host?.let { com.jrees.wowcompanion.BottomSync.keep(it.snapshot("closed by Android")) }
            com.jrees.wowcompanion.DiagLog.event("app", "bottom screen closed by Android (display ${display.displayId}, state ${stateName(display.state)}" +
                if (parkedNow) ", while away)" else ")")
            onLost(this, "closed by Android")
        }
        up = false
        if (typing) typer.focusable(false)
        pageSlot.drop()
        // round 3 (the review: "A parked host torn down while away leaves the game's own UI hidden when no new bottom window comes"):
        // Android's close of a PARKED window with its display gone (removed, no longer valid: no host is sure to follow there) gives
        // the game's UI back now, while the input gate may still be live; a metrics-only close, the relaunch and the memory release
        // keep the park's hold (a host follows on the same display)
        val giveBack = byAndroid && parkedNow && displayGone()
        host?.let { it.onStop(giveBack = giveBack); it.onDestroy() }
        host = null
        super.onStop()
    }

    /** round 3: this window's display is gone (removed, or no longer valid) */
    private fun displayGone(): Boolean = !display.isValid ||
        runCatching { context.getSystemService(android.hardware.display.DisplayManager::class.java)?.getDisplay(display.displayId) == null }.getOrDefault(false)

    /**
     * a [show] that failed after onCreate (the window manager refused the display), or a host that could not be made ([begin]): never a
     * second host beside the next. Round 2 (the review: "the catch calls abandon(), which tears the host down but never unregisters that
     * listener"): a window never shown is undone by hand - Presentation.onStart registered its display listener and dismiss() returns at
     * once for a window that never showed, so [super.onStop] takes it off (else DisplayManagerGlobal kept this window and its decor);
     * a window that did show closes as ours always does (its onStop)
     */
    fun abandon(shownOk: Boolean = false) {
        closing = true
        // (never the Dialog's own "showing" answer: it reads the decor's visibility, VISIBLE already when addView threw - and dismiss()
        // then did nothing)
        if (shownOk) { runCatching { dismiss() }; return }
        up = false
        pageSlot.drop()
        runCatching { host?.abandon() }.onFailure { Log.w(DsForeverScreen.TAG, "the abandoned window's host: ${it.message}", it) }
        host = null
        runCatching { super.onStop() }.onFailure { Log.w(DsForeverScreen.TAG, "the abandoned window's display listener: ${it.message}", it) }
    }

    private fun stateName(s: Int): String = when (s) {
        Display.STATE_OFF -> "off"; Display.STATE_ON -> "on"; Display.STATE_DOZE -> "doze"; Display.STATE_DOZE_SUSPEND -> "doze suspend"
        Display.STATE_ON_SUSPEND -> "on suspend"; Display.STATE_VR -> "vr"; else -> "unknown"
    }

    override fun setHostContent(v: View) = setContentView(v)
    override fun hostGone(): Boolean = !up
    override fun hostRestore() = com.jrees.wowcompanion.BottomSync.take(android.os.SystemClock.elapsedRealtime(), com.jrees.wowcompanion.LauncherHooks.gameStartSec,
        android.os.SystemClock.uptimeMillis())
    override fun recreateHost() = onRecreate()
    override fun hostWindow() = window
    override fun hostIntent(): Intent? = null
    override fun startHostActivity(i: Intent) = context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
