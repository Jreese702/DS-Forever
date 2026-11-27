package com.jrees.wowcompanion

import java.util.concurrent.CopyOnWriteArrayList

/**
 * WHERE THE TWO SCREENS OF THE SETUP MEET (Track C, 2026-10-12). The launcher's top screen publishes what it knows ([publish]) and
 * registers what only it can do ([launcherActions]: the folder picker, the settings page, Play, the updates); the companion's bottom
 * screen ([SetupPanel]) reads [screen] and presses [press]. The sign-in check (M3) adds its own state ([signIn]); the login form is the
 * companion's own ([openLoginForm]). Main thread.
 */
object SetupBridge {
    /** null = not inside the launcher's setup (the standalone app, or the launcher not started yet): the panel stays hidden */
    @Volatile var inputs: SetupFlow.Inputs? = null
        private set
    /** the refused-login state, from the companion's sign-in check */
    @Volatile var signIn = SetupFlow.SignIn.NONE
        private set
    @Volatile var restartOffered = false
        private set

    /** the launcher's own actions (the folder picker, settings, Play, the updates, a game restart) */
    @Volatile var launcherActions: ((SetupFlow.Action) -> Unit)? = null
    /**
     * the Menu's region card asks (the user, 2026-11-17: "Switch to EU?"): would switching to the region downloads another build?
     * true = yes, false = this install is that build, null = not known (no network). Set by the launcher; blocking: off the main thread
     */
    @Volatile var regionCheck: ((code: String) -> Boolean?)? = null
    /** the companion's login form (HomeLoginPrompt) */
    @Volatile var openLoginForm: (() -> Unit)? = null
    /** the launcher re-reads what changed outside it (a login saved on the bottom screen) */
    @Volatile var launcherRefresh: (() -> Unit)? = null
    fun loginSaved() { launcherRefresh?.invoke() }
    /** "Update login" (M3): the form, then a fresh login.txt for the running game */
    @Volatile var updateLogin: (() -> Unit)? = null
    /** "Report a problem" on the setup screen (board f-problem-report): the companion opens its report screen */
    @Volatile var openReport: (() -> Unit)? = null
    /** the keyboard / trackpad on the bottom screen; null = no game or stream to send it to */
    @Volatile var openPcKeyboard: (() -> Unit)? = null
    /** the setup's one run of the game after a direct install (DataStaleness.firstRun): no sign-in */
    @Volatile var seeding = false
    /** the waiting screen's Menu > Exit game: the stream's end while one runs, else the local game's */
    @Volatile var exitStream: (() -> Unit)? = null
    /**
     * feat/pause-power: THE CLEAN DISCONNECT while a stream runs (StreamTop's; null = Local, or no stream): "end_session" to Desktop
     * (the PC's WoW closes - [quitAsked] = the game's own Exit Game was tapped first - and the PC is put back), the picture stopped, the
     * bridge disconnected once Desktop has answered; then [done] with Desktop's word. The pause menu's power button closes DS Forever
     * after it
     */
    @Volatile var endSession: ((why: String, quitAsked: Boolean, done: (PausePower.Ended) -> Unit) -> Unit)? = null
    /** feat/pause-power: the waiting screen's Menu > Exit game while streaming: the same clean disconnect, then back to the chooser */
    @Volatile var endStream: (() -> Unit)? = null
    /**
     * feat/exit-now: THE STREAM'S QUICK END as DS Forever closes at once ([ExitNow]; StreamTop's, null = no stream): "end_session" to
     * Desktop (it closes the PC's WoW and puts the PC back on its own), sent within [boundMs] or not at all, then the stream and the
     * bridge stopped - no wait for Desktop's word. Any thread; it blocks up to [boundMs]
     */
    @Volatile var quitStream: ((boundMs: Long) -> Unit)? = null
    /**
     * Streaming's settings while Streaming's top screen is open ([StreamSettingsLink], set by StreamTop): Settings > Performance >
     * Stream settings shows them in its band on the BOTTOM screen (the user, 2026-11-18: "the streaming settings appear on the top screen
     * and the controller inputs get all wacky, they should probably appear on the bottom screen in the stream settings box"); null = Local
     */
    @Volatile var streamSettings: StreamSettingsLink? = null
        set(v) { field = v; streamSettingsChanged() }
    private val streamWatchers = CopyOnWriteArrayList<() -> Unit>()
    /** [f] runs when the stream's settings or its test's rows change (any thread) */
    fun onStreamSettings(f: () -> Unit) { streamWatchers += f }
    fun streamSettingsChanged() { for (f in streamWatchers) f() }
    /** EVERY ROUTE to the stream's settings (the Performance tile, the waiting screen's Menu, the stream's own screens' Y): the Settings
     *  room on the Performance tab with the Stream settings band open (set by the companion's host) */
    @Volatile var openStreamSettings: (() -> Unit)? = null
    /** the band is open where no game holds the pad for the room (over the waiting screen, before the stream): the stream's top screen
     *  hands each press to [streamPad] and none to the PC */
    @Volatile var streamPadHolds: () -> Boolean = { false }
    @Volatile var streamPad: ((String) -> Unit)? = null
    @Volatile var exitGame: (() -> Unit)? = null
    /** the out-of-date folder's pop-up was OK'd: not again this run (the red check line stays) */
    @Volatile var staleOk = false
    /** the low storage pop-up was OK'd (this run) */
    @Volatile var spaceOk = false
    /** the saved report's note > Open GitHub issue */
    @Volatile var openSavedReport: (() -> Unit)? = null
    /** the report page takes the launcher's keys while it is open (before Play) */
    @Volatile var reportKey: ((keyCode: Int, down: Boolean) -> Boolean)? = null
    /** our own login form is open (Update login / Save login): the game's A comes back meanwhile (CompanionHost sets it every second) */
    @Volatile var loginFormOpen = false

    /** the launcher's pad path asks before giving A to the game (p-auth-keyboard: A does nothing while the code pop-up shows) */
    fun blockGameA(): Boolean = holdsGamePad()

    /** a pop-up of ours asks for a press with the game up: A, B and the D-pad are held back from the game (PopupHold) */
    fun holdsGamePad(): Boolean = PopupHold.holds(signIn, LauncherHooks.gameUp(), loginFormOpen, noticeAsking) || wheelHolds()

    /** the context wheel's Yes / No or rename pop-up is up (PopupHold.Wheel): A, B, X, Y, the D-pad, the shoulders, Start, Select held */
    fun wheelHolds(): Boolean = PopupHold.wheel.holds(android.os.SystemClock.uptimeMillis())

    /**
     * THE ROOMS' TABS ON TOP (the user, 2026-11-25): a tabbed room (Character, Group Finder, Professions, Social) owns L / R now - they
     * step its tab row from the app's own reader - so both bumpers are held back from the game (PopupHold.holdsKey's [room]). Set by the
     * companion's host (DirectPad's live registration); main thread, as the launcher's key callbacks
     */
    @Volatile var roomShoulders: () -> Boolean = { false }

    /**
     * The launcher's key path, for every pad key ([keyCode], [down], [repeat] = the event's repeat count): true = a tabbed room owns
     * L / R now, so a bumper's press is held from the game (PopupHold.holdsKey's room). A bumper's press is DECIDED here, once
     * (RoomTabsPad.decide): the room's own delivery acts on it only when it was held for the room. Never throws into the key path
     */
    fun roomShoulderPress(keyCode: Int, down: Boolean, repeat: Int): Boolean {
        val k = RoomTabsPad.keyName(keyCode) ?: return false
        val live = try { roomShoulders() } catch (_: Throwable) { false }
        if (down && repeat == 0) RoomTabsPad.decide(k, live, android.os.SystemClock.uptimeMillis())
        return live
    }

    /**
     * THE DS MENU'S ZL / ZR (single screen mode, the 1536 pass 2): a room on the menu's stage owns the triggers now (they step room to
     * room from the app's own reader), so both are held back from the game - their keys (PopupHold.holdsKey's [trigger]) and their pull
     * (WinHandler.holdOut). ONE decision: the companion's host sets it at every look (CompanionHost.menuTriggersSync) and its reader acts
     * on a trigger only while it is set; false the moment the room closes. Read from the launcher's key and pad threads
     */
    @Volatile var roomTriggers: Boolean = false

    /** the report's notice on Home asks for a press (Open issue / OK): the game's A is held back meanwhile (board f-problem-report) */
    @Volatile var noticeAsking = false

    /** "Restart the game to sign in": the launcher closes the game and starts it again (it outlives the launcher's setup screen) */
    @Volatile var restartGame: (() -> Unit)? = null
    /** a restart is under way (close -> old game gone -> launcher back -> Play -> game attached): each step logs a "restart:" line */
    @Volatile var restarting = false
    /** "Enter it on the game screen": the headline changes, the buttons stay; the keyboard opens on the top screen */
    @Volatile var enterOnGame: (() -> Unit)? = null
    /**
     * The Android keyboard for the GAME on the top screen (the user's ruling after test3: "selecting "Enter it on the game screen"
     * should up up the android keyboard for direct input. to the top screen"): set by the launcher's game screen (its own show-keyboard
     * path into the X server); true = show, false = hide. The user types; nothing typed passes through DS Forever.
     */
    @Volatile var gameKeyboard: ((show: Boolean) -> Unit)? = null
    /** the game keyboard was opened from the sign-in pop-up (it closes past the login screen, or on B) */
    @Volatile var keyboardOpen = false
        private set
    /**
     * true = the "Keyboard" press: a TOGGLE on the device (nobody can read whether it is up, test9), so [keyboardOpen] only means "it
     * may be up": B / past the login screen then send one plain hide (a hide never opens it).
     */
    fun keyboard(show: Boolean) {
        if (!show && !keyboardOpen) return
        keyboardOpen = show
        gameKeyboard?.invoke(show)
    }

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    fun onChange(l: () -> Unit) { listeners.add(l) }
    fun offChange(l: () -> Unit) { listeners.remove(l) }
    private fun changed() { for (l in listeners) runCatching { l() } }

    fun publish(i: SetupFlow.Inputs?) { if (i != inputs) { inputs = i; runCatching { StartupTrace.observe(i) }; changed() } }
    fun signIn(s: SetupFlow.SignIn, restart: Boolean = false) {
        if (s != signIn || restart != restartOffered) { signIn = s; restartOffered = restart; changed() }
    }

    /** what the bottom shows now; null = nothing (outside the launcher's setup) */
    fun screen(gameRunning: Boolean = LauncherHooks.gameUp()): SetupFlow.Screen? {
        val i = inputs ?: return null
        return SetupFlow.screen(i.copy(signIn = signIn, restartOffered = restartOffered, gameRunning = gameRunning, restarting = restarting,
            reportSaved = ReportFlow.pending?.let { ReportFlow.noteTitle(it) },
            staleData = DataStaleness.current()?.takeIf { !staleOk },
            lowSpace = i.lowSpace?.takeIf { !spaceOk },
            gameClosed = GameLife.note, lastCrash = LastRun.pending, polestarAsk = PolestarGuide.ask(Settings.polestarGuide),
            light8Tip = runCatching { DeviceTier.light8() }.getOrDefault(false)))
    }

    /** the start-up checks have passed (the chooser may show); false while the launcher hasn't published them */
    fun checksPass(): Boolean {
        val i = inputs ?: return false
        return SetupFlow.checksPass(i.copy(staleData = i.staleData?.takeIf { !staleOk }, lowSpace = i.lowSpace?.takeIf { !spaceOk }))
    }

    /** 1.0.17: the crash question's Send: the host builds and sends the problem report with these words */
    @Volatile var sendCrash: ((String) -> Unit)? = null

    /** the sign-in's lean log (NativeLogin.logEvent): each sign-in button, never anything typed */
    @Volatile var log: ((String) -> Unit)? = null

    fun press(a: SetupFlow.Action) {
        if (a in SIGN_IN_ACTIONS) log?.invoke("sign-in: pressed ${a.name.lowercase().replace('_', ' ')}")
        when (a) {
            SetupFlow.Action.ENTER_LOGIN -> openLoginForm?.invoke()
            SetupFlow.Action.UPDATE_LOGIN -> updateLogin?.invoke()
            SetupFlow.Action.ENTER_ON_GAME -> enterOnGame?.invoke()
            SetupFlow.Action.RESTART_GAME -> { signIn(SetupFlow.SignIn.NONE); restartGame?.invoke() }
            SetupFlow.Action.REPORT -> { GameLife.dismissNote(); openReport?.invoke() }
            SetupFlow.Action.GAME_CLOSED_OK -> { GameLife.dismissNote(); changed() }
            SetupFlow.Action.CRASH_SEND -> { val why = LastRun.pending; LastRun.dismiss(); changed(); why?.let { w -> sendCrash?.invoke(w) } }
            SetupFlow.Action.CRASH_DISMISS -> { LastRun.dismiss(); changed() }
            SetupFlow.Action.REPORT_OPEN -> openSavedReport?.invoke()
            SetupFlow.Action.STALE_OK -> { staleOk = true; changed() }
            SetupFlow.Action.SPACE_OK -> { spaceOk = true; changed() }
            SetupFlow.Action.REPORT_DISMISS -> { ReportFlow.clearPending(); changed() }
            // 2026-11-12: the Polestar quest guide's answer (Settings > Add-ons changes it later); the add-on moves at the next start
            SetupFlow.Action.POLESTAR_YES -> { PolestarGuide.set(true); log?.invoke("polestar guide: yes"); changed() }
            SetupFlow.Action.POLESTAR_NO -> { PolestarGuide.set(false); log?.invoke("polestar guide: no"); changed() }
            else -> launcherActions?.invoke(a)
        }
    }

    /**
     * The pad before Play (the launcher's activity gets every key while no game screen is up): the panel's D-pad / A / B. Set by the
     * panel while it shows a button; true = the key was the panel's (the launcher swallows it).
     */
    private val SIGN_IN_ACTIONS = setOf(SetupFlow.Action.UPDATE_LOGIN, SetupFlow.Action.ENTER_ON_GAME, SetupFlow.Action.RESTART_GAME)

    @Volatile var keyHandler: ((keyCode: Int, down: Boolean) -> Boolean)? = null
    fun key(keyCode: Int, down: Boolean): Boolean =
        reportKey?.invoke(keyCode, down)?.takeIf { it } ?: keyHandler?.invoke(keyCode, down)?.takeIf { it } ?: backKey(keyCode, down)

    /**
     * B that nothing above took (the report page, the panel): back to the start-up chooser (S1 of the streaming phase), when
     * [ModeBridge.canGoBack] allows it (no game, nothing busy, no login form). The release that follows is swallowed too.
     */
    private fun backKey(keyCode: Int, down: Boolean): Boolean {
        if (keyCode != android.view.KeyEvent.KEYCODE_BUTTON_B) return false
        if (!down) return backTaken.also { backTaken = false }
        backTaken = ModeBridge.back("B on the Local start screen")
        return backTaken
    }
    private var backTaken = false
}
