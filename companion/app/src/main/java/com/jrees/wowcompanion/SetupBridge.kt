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
    /** the companion's login form (HomeLoginPrompt) */
    @Volatile var openLoginForm: (() -> Unit)? = null
    /** the launcher re-reads what changed outside it (a login saved on the bottom screen) */
    @Volatile var launcherRefresh: (() -> Unit)? = null
    fun loginSaved() { launcherRefresh?.invoke() }
    /** "Update login" (M3): the form, then a fresh login.txt for the running game */
    @Volatile var updateLogin: (() -> Unit)? = null
    /** "Report a problem" on the setup screen (board f-problem-report): the companion opens its report screen */
    @Volatile var openReport: (() -> Unit)? = null
    /** the saved report's note > Open GitHub issue */
    @Volatile var openSavedReport: (() -> Unit)? = null
    /** the report page takes the launcher's keys while it is open (before Play) */
    @Volatile var reportKey: ((keyCode: Int, down: Boolean) -> Boolean)? = null
    /** our own login form is open (Update login / Save login): the game's A comes back meanwhile (CompanionHost sets it every second) */
    @Volatile var loginFormOpen = false

    /** the launcher's pad path asks before giving A to the game (p-auth-keyboard: A does nothing while the code pop-up shows) */
    fun blockGameA(): Boolean = SignInKeyboard.blocksA(signIn, LauncherHooks.gameUp(), loginFormOpen)

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

    fun publish(i: SetupFlow.Inputs?) { if (i != inputs) { inputs = i; changed() } }
    fun signIn(s: SetupFlow.SignIn, restart: Boolean = false) {
        if (s != signIn || restart != restartOffered) { signIn = s; restartOffered = restart; changed() }
    }

    /** what the bottom shows now; null = nothing (outside the launcher's setup) */
    fun screen(gameRunning: Boolean = LauncherHooks.gameUp()): SetupFlow.Screen? {
        val i = inputs ?: return null
        return SetupFlow.screen(i.copy(signIn = signIn, restartOffered = restartOffered, gameRunning = gameRunning, restarting = restarting,
            reportSaved = ReportFlow.pending?.let { ReportFlow.noteTitle(it) }))
    }

    /** the sign-in's lean log (NativeLogin.logEvent): each sign-in button, never anything typed */
    @Volatile var log: ((String) -> Unit)? = null

    fun press(a: SetupFlow.Action) {
        if (a in SIGN_IN_ACTIONS) log?.invoke("sign-in: pressed ${a.name.lowercase().replace('_', ' ')}")
        when (a) {
            SetupFlow.Action.ENTER_LOGIN -> openLoginForm?.invoke()
            SetupFlow.Action.UPDATE_LOGIN -> updateLogin?.invoke()
            SetupFlow.Action.ENTER_ON_GAME -> enterOnGame?.invoke()
            SetupFlow.Action.RESTART_GAME -> { signIn(SetupFlow.SignIn.NONE); restartGame?.invoke() }
            SetupFlow.Action.REPORT -> openReport?.invoke()
            SetupFlow.Action.REPORT_OPEN -> openSavedReport?.invoke()
            SetupFlow.Action.REPORT_DISMISS -> { ReportFlow.clearPending(); changed() }
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
        reportKey?.invoke(keyCode, down)?.takeIf { it } ?: (keyHandler?.invoke(keyCode, down) ?: false)
}
