package com.jrees.wowcompanion

/**
 * THE SETUP SCREEN, SPLIT ACROSS THE TWO SCREENS (the user's fresh-install test, 2026-10-12; Track C). Pure: what the launcher knows
 * ([Inputs]) in, what the two screens show out.
 *
 * - TOP = [checks]: the checks only, one line each (game folder, Battle.net login, game art, add-ons, indoor maps when that step is
 *   registered, the client build).
 * - BOTTOM = [screen]: Play, plus ONE pop-up at a time ([Prompt]), the most blocking first. Controller first (D-pad + A, the gold box),
 *   touch too ([SetupPanel]).
 * - The art builds BY ITSELF once the game folder is found (the user's pick "Automatic, no button"): there is no harvest button;
 *   "Try again" exists only on a failure.
 */
object SetupFlow {
    enum class Art { IDLE, RUNNING, READY, FAILED }
    /**
     * The game at its login screen (M3; the user: the buttons stay "until I get past the login screen"): SIGNING = it read the saved
     * login; FAILED = refused; RESTARTING = the game restarts with the updated login; RESTART = that restart did not happen; TYPING = the user types it on the
     * game screen (or no login is saved). NONE = not at the login screen.
     */
    enum class SignIn { NONE, SIGNING, FAILED, RESTARTING, RESTART, TYPING, AUTH, CHECK }

    data class Inputs(
        val path: String,
        val folderFound: Boolean,
        val wrongGame: Boolean = false,
        val product: String = "",
        val storage: Boolean = true,
        val loginSaved: Boolean = false,
        /** "Play without" was picked on the login pop-up (this launcher run) */
        val loginSkipped: Boolean = false,
        val art: Art = Art.IDLE,
        val artPercent: Int = 0,
        /** the art is being built again for a new game version (after an update): its row says so */
        val artRebuild: Boolean = false,
        /** a direct install's step under way: the bottom screen's one card ([Progress]); null = none */
        val progress: Progress? = null,
        /** the install's own bar on the Game folder row, 0..1; null = none */
        val installBar: Float? = null,
        val artWhat: String = "",
        val artWhy: String = "",
        /** our bundled add-ons are in the game's AddOns folder */
        val addonsInstalled: Boolean = false,
        /** the dependency add-ons' line (QuestieDB ...), null = not checked yet */
        val dependencies: String? = null,
        /** the optional interiors step's line, null = no such step registered (the release ships without it) */
        val indoor: String? = null,
        /** the client build line, null = not checked yet */
        val client: String? = null,
        /** the newer client build when the local one is out of date */
        val gameOutdated: String? = null,
        /** a newer app version, until "Later" */
        val appUpdate: String? = null,
        /** the app's own update line (AppUpdate.line: "DS Forever <ver>: up to date / update <ver> available / ... skipped (...)") */
        val appLine: String? = null,
        /** the background all-icons step's line (AllIcons.line): optional, never gates Play */
        val iconsLine: String? = null,
        /** the game ended abnormally (GameLife.note): the touch-only "The game closed" note; null = none */
        val gameClosed: String? = null,
        /** the game folder's Data is older than its build (DataStaleness), null = fine or not known; its plain message */
        val staleData: String? = null,
        /** a direct install short of room ([lowSpaceLine]); null = fine */
        val lowSpace: String? = null,
        /** a problem report saved by itself and not opened yet (ReportFlow.noteTitle), null = none */
        val reportSaved: String? = null,
        /** DS Forever runs from the SD card / adopted storage (InstallCheck): the container can't be made there */
        val installedExternal: Boolean = false,
        /** launching / updating / downloading: what is happening (Play waits) */
        val busy: String? = null,
        /** the game's own update is running ([busy] is its step's line) */
        val updating: Boolean = false,
        val signIn: SignIn = SignIn.NONE,
        /** "Restart the game to sign in" applied in this sign-in: its button stays until past the login screen */
        val restartOffered: Boolean = false,
        /** the game screen is up: only the sign-in pop-ups remain */
        val gameRunning: Boolean = false,
        /** a "Restart the game to sign in" is between the close and the new game (no Play meanwhile: one launch path only) */
        val restarting: Boolean = false,
    )

    enum class Action {
        CHOOSE_FOLDER, INSTALL_GAME, GRANT_STORAGE, ENTER_LOGIN, SKIP_LOGIN, RETRY_ART, UPDATE_GAME, UPDATE_APP, LATER_APP,
        UPDATE_LOGIN, ENTER_ON_GAME, RESTART_GAME, PLAY,
        /** Android's App info page for DS Forever (Storage > Change: move it to internal storage) */
        OPEN_APP_SETTINGS,
        /** the restart's own Play (never a button): it launches the same way and says so in the log */
        RELAUNCH,
        /** "Report a problem" (board f-problem-report): the report screen, before Play too */
        REPORT,
        /** the "The game closed" note: OK */
        GAME_CLOSED_OK,
        /** the low storage pop-up: OK (this run) */
        SPACE_OK,
        /** the out-of-date folder's pop-up: OK (this run) */
        STALE_OK,
        /** the saved report's note: open its GitHub issue, or dismiss it */
        REPORT_OPEN, REPORT_DISMISS
    }
    data class Button(val label: String, val action: Action, val enabled: Boolean = true)
    data class Prompt(val id: String, val title: String, val text: String, val buttons: List<Button>)
    /** [play] null = no Play (the game runs); [line] = one status line under it; [report] = the small "Report a problem" with Play */
    data class Screen(val prompt: Prompt?, val play: Button?, val line: String, val report: Button? = null)

    enum class Mark { OK, WAIT, BAD, OPTIONAL }
    data class Check(val label: String, val mark: Mark)

    /** the "DS Forever vX is out" pop-up holds Play until Update or Later (board b-update-blocks-play) */
    /**
     * The bottom screen's setup takes the pad (the gold box, the A glyph, D-pad / A) only before Play. In the game, at its login screen,
     * the pop-ups are touch only (b-login-a-input, the user 15 Oct: pressing A must never open "Update login" by accident).
     */
    fun padDriven(s: Screen): Boolean = s.play != null

    /**
     * A button the controller's cursor may land on. "Report a problem" never (b-setup-report-cursor, the user: "I keep accidentally
     * clicking the Send a problem at launch because its on the cursor. that should only be a touchable option"): touch only.
     */
    fun padTarget(b: Button): Boolean = b.enabled && b.action != Action.REPORT && b.action != Action.GAME_CLOSED_OK

    /**
     * THE START-UP CHOOSER WAITS FOR THE CHECKS (the user, 2026-10-28: "the App loading all needs to still be completed for the Streaming
     * option, so it almost doesn't make sense having that screen before the dependency checks"; "If a check fails ... the chooser waits
     * until every check passes"): a red row, the art not ready, something busy or an out-of-date game hold it. The Battle.net login
     * (asked at Play), our add-ons (installed with the first Play) and the optional rows (indoor maps, the app's own update, all icons)
     * never do. [i] = the inputs as the bottom screen sees them (an OK'd out-of-date folder no longer holds it: [SetupBridge.checksPass]).
     */
    fun checksPass(i: Inputs): Boolean =
        !i.installedExternal && i.folderFound && !i.wrongGame && i.storage && i.staleData == null && i.lowSpace == null && i.art == Art.READY &&
            i.busy == null && i.gameOutdated == null

    /**
     * WHAT A PRESS OF UPDATE (the game's) DOES NOW (the user on the Thor, 2026-10-28: "my update button is not working when I press A" -
     * the press reached the launcher, which returned without a word while a background art step ran). Never silent: [GO] starts it,
     * the WAIT_ ones queue it (it starts by itself when the step ends; the status line says so), the rest are refusals said plainly.
     */
    /** STOP_ART (2026-11-04, was WAIT_ART): an art run is stopped and the update goes at once (the art is built again after it) */
    enum class UpdateGate { GO, STOP_ART, WAIT_LAUNCH, BUSY, NO_STORAGE, NO_VERSION }

    fun updateGate(hasVersion: Boolean, updating: Boolean, launching: Boolean, harvesting: Boolean, storage: Boolean): UpdateGate = when {
        updating -> UpdateGate.BUSY
        !hasVersion -> UpdateGate.NO_VERSION
        !storage -> UpdateGate.NO_STORAGE
        launching -> UpdateGate.WAIT_LAUNCH
        harvesting -> UpdateGate.STOP_ART
        else -> UpdateGate.GO
    }

    /** the status line / log words for a gate ([artPercent] = the running art step's %, < 0 = unknown) */
    fun updateGateLine(g: UpdateGate, artPercent: Int): String = when (g) {
        UpdateGate.GO -> "Updating the game"
        UpdateGate.STOP_ART -> "Getting ready to update"
        UpdateGate.WAIT_LAUNCH -> "Update starts when the launch is done"
        UpdateGate.BUSY -> "Already updating"
        UpdateGate.NO_STORAGE -> "Update needs access to the game folder"
        UpdateGate.NO_VERSION -> "Checking the game's version - press Update again in a moment"
    }

    /**
     * THE STATUS LINE THAT IS ALWAYS THERE (the user, 2026-10-28: "there must always be a visible status line so it's clear it isn't
     * stuck"): the step under way ([line]: the setup's busy / art line), else "Ready" once the checks have passed ([pass]), else
     * "Loading" with dots that cycle . .. ... every half second ([t] = the scene's clock, s).
     */
    fun status(line: String?, pass: Boolean, t: Float): String = when {
        !line.isNullOrEmpty() -> line
        pass -> "Ready"
        else -> "Loading" + ".".repeat(((t / 0.5f).toInt() % 3 + 3) % 3 + 1)
    }

    /**
     * The checks as the top screen's box shows them (2026-10-28, terse: "verbiage ... too much"): an OK, waiting or optional row is its
     * name, the words before the first ':' ("Game folder", "Client build"); a red row keeps all its words (what is wrong).
     */
    /** a bar that moves without a known amount (the game's update before its files are counted) */
    const val BAR_MOVING = -1f

    /**
     * THE TOP BOX'S PROGRESS BARS (the user, 2026-10-28: "I still want the progress bars by the things on the top"): the bar of the check
     * [c] (the full, not terse, row), 0..1, [BAR_MOVING], or null = no bar. Only a step under way has one: the game art, the game's
     * update, the app's download, the background icons, the indoor maps.
     */
    fun bar(c: Check, i: Inputs): Float? {
        val l = c.label
        return when {
            l == "Game folder" -> i.installBar
            l.startsWith("Game art:") -> if (i.art == Art.RUNNING) (i.artPercent / 100f).coerceIn(0f, 1f) else null
            l.startsWith("Client build:") -> if (i.updating) fraction(i.busy) ?: BAR_MOVING else null
            l.startsWith("DS Forever") -> i.busy?.takeIf { it.startsWith("Downloading the update") }?.let { fraction(it) }
            l.startsWith("Icons:") -> if (c.mark == Mark.OK || l.contains("all icons ready")) null else fraction(l.substringAfter(':'))
            l.startsWith("Indoor maps") -> if (c.mark == Mark.OK) null else fraction(l.substringAfter(':'))
            else -> null
        }
    }

    /** "42%" -> 0.42, "(3/10)" or "300 / 1000" -> its share; null = no amount in [s] */
    fun fraction(s: String?): Float? {
        if (s == null) return null
        Regex("""(\d+)\s*%""").find(s)?.let { return (it.groupValues[1].toFloat() / 100f).coerceIn(0f, 1f) }
        Regex("""(\d+)\s*/\s*(\d+)""").find(s)?.let {
            val d = it.groupValues[2].toFloat()
            return if (d > 0f) (it.groupValues[1].toFloat() / d).coerceIn(0f, 1f) else null
        }
        return null
    }

    fun terse(checks: List<Check>): List<Check> =
        checks.map { if (it.mark == Mark.BAD) it else it.copy(label = it.label.substringBefore(':').trim()) }

    fun canPlay(i: Inputs): Boolean =
        i.folderFound && !i.wrongGame && i.storage && i.art == Art.READY && i.busy == null && !i.gameRunning && i.appUpdate == null

    /** A DIRECT INSTALL'S STEPS, their times as measured on the Thor on home Wi-Fi (2026-11-01, 1.60.1.70707) */
    enum class Step(val typicalS: Int) { INSTALL(525), PREPARE(80), ART(1280) }

    private fun aboutWords(s: Double) = if (s < 60) "under a minute" else "about ${kotlin.math.ceil(s / 60).toInt()} min"

    /** the card of a direct install's step, top to bottom (the user 2026-11-01: what it does, this step's time, the total) */
    data class Progress(val title: String, val doing: String, val thisStep: String, val total: String) {
        val text: String get() = "$doing\n$thisStep\n$total"
    }

    /** the two times as one line, for the one-line panel of the waiting screen */
    fun timesLine(step: Step): String = times(step, 0.0).let { "${it.first}   ${it.second}" }

    /** an install has no count of its own: its share of the usual time, never quite full */
    fun timeBar(step: Step, elapsedS: Double): Float = (elapsedS / step.typicalS).toFloat().coerceIn(0f, 0.95f)

    /** what is left of [step] and of the whole setup: from its own progress once [fraction] says enough, else its usual time */
    fun times(step: Step, elapsedS: Double, fraction: Float? = null): Pair<String, String> {
        val left = if (fraction != null && fraction >= 0.05f) elapsedS * (1f - fraction) / fraction
            else (step.typicalS - elapsedS).coerceAtLeast(30.0)
        val total = left + Step.values().filter { it.ordinal > step.ordinal }.sumOf { it.typicalS }
        return "This step: ${aboutWords(left)}" to "Total: ${aboutWords(total)}"
    }

    /** below this a direct install is warned: it keeps what the game streams, and a full device stops the game */
    const val LOW_SPACE_BYTES = 5_000_000_000L

    /** the low storage pop-up's words; null = room enough */
    fun lowSpaceLine(freeBytes: Long, limit: Long = LOW_SPACE_BYTES): String? =
        if (freeBytes >= limit) null
        else "Only ${String.format(java.util.Locale.US, "%.1f", freeBytes / 1_000_000_000.0)} GB free. WoW needs more room as you play. Free up some storage soon."

    /** a direct install grows to about 50 GB */
    const val INSTALL_FREE_BYTES = 60_000_000_000L

    /** why a direct install can't start; null = it can */
    fun installRefusal(freeBytes: Long, metered: Boolean): String? = when {
        freeBytes < INSTALL_FREE_BYTES -> "Installing needs 60 GB free"
        metered -> "Installing needs Wi-Fi"
        else -> null
    }

    fun screen(i: Inputs): Screen {
        if (i.gameRunning) return Screen(signInPrompt(i), null, "")
        // TEST8 13:24:46: during a restart the bottom's Play came back with the launcher and a launch started before the restart's own
        // (that one was refused as a duplicate). Between the close and the new game: the restart's line only, nothing to press
        if (i.restarting) return Screen(Prompt("signin", "Restarting the game to sign in…", "", emptyList()), null, "")
        val prompt = when {
            // first: nothing else works from the SD card (p-sd-card-install); Play stays, a device may still cope
            i.installedExternal -> Prompt("install", InstallCheck.TITLE, InstallCheck.TEXT,
                listOf(Button("Open app settings", Action.OPEN_APP_SETTINGS)))
            i.progress != null -> Prompt("progress", i.progress.title, i.progress.text, emptyList())
            !i.folderFound || i.wrongGame -> Prompt("folder", "Where is WoW Forever?",
                if (i.wrongGame) "The folder ${i.path} holds ${i.product.ifEmpty { "another game" }}, not WoW Forever. Choose the WoW Forever folder: the one with .build.info and Data."
                else "Install it (250 MB now, up to 50 GB as you play), or choose the folder with .build.info and Data.",
                if (i.wrongGame) listOf(Button("Choose folder", Action.CHOOSE_FOLDER))
                else listOf(Button("Install directly", Action.INSTALL_GAME), Button("Choose folder", Action.CHOOSE_FOLDER)))
            // issues #8 / #9 (the user's pick): an out-of-date Data folder, said plainly; Play stays (it may still start)
            i.staleData != null && i.folderFound && !i.wrongGame && i.storage -> Prompt("stale", "Your game folder is out of date",
                i.staleData, listOf(Button("OK", Action.STALE_OK)))
            i.lowSpace != null && i.folderFound && !i.wrongGame && i.storage -> Prompt("space", "Storage is low", i.lowSpace,
                listOf(Button("OK", Action.SPACE_OK)))
            !i.storage -> Prompt("storage", "Allow file access",
                "Android needs \"All files access\" so the game and DS Forever can read the game folder.",
                listOf(Button("Open settings", Action.GRANT_STORAGE)))
            i.art == Art.FAILED -> Prompt("art", "The game art couldn't be built",
                i.artWhy.ifEmpty { "The game files could not be read." }, listOf(Button("Try again", Action.RETRY_ART)))
            // before the client's pop-up: its "Play anyway" waits for this choice (canPlay)
            i.appUpdate != null -> Prompt("app", "DS Forever ${i.appUpdate} is out", "Update now, or later from this screen.",
                listOf(Button("Update", Action.UPDATE_APP, i.busy == null), Button("Later", Action.LATER_APP)))
            i.gameOutdated != null -> Prompt("client", "Game update needed",
                "The servers need build ${i.gameOutdated}. Signing in may fail with these files. Update on Wi-Fi: the game art then fetches about 180 MB of the game's tables.",
                listOf(Button("Update", Action.UPDATE_GAME, i.busy == null), Button("Play anyway", Action.PLAY, canPlay(i))))
            i.reportSaved != null && (i.loginSaved || i.loginSkipped) -> Prompt("report", i.reportSaved,
                if ("was sent" in i.reportSaved) "Open the issue to see it." else "It is in Documents/DS Forever/reports. Open the GitHub issue to post it.",
                listOf(Button("Open GitHub issue", Action.REPORT_OPEN), Button("Dismiss", Action.REPORT_DISMISS)))
            !i.loginSaved && !i.loginSkipped -> Prompt("login", "Your Battle.net login",
                "Save it once and the game signs you in by itself. It stays encrypted on this device.",
                listOf(Button("Enter login", Action.ENTER_LOGIN), Button("Play without", Action.SKIP_LOGIN)))
            else -> null
        }
        // the client prompt carries its own Play ("Play anyway"); everything else blocking hides behind a disabled Play
        val play = Button("Play", Action.PLAY, canPlay(i))
        val line = i.busy ?: when (i.art) {
            Art.RUNNING -> if (i.artWhat.startsWith(TablesProgress.PREFIX)) i.artWhat else "Preparing the game art: ${i.artPercent}%"
            Art.IDLE -> if (i.folderFound) "Checking the game art" else ""
            else -> ""
        }
        return Screen(prompt, play, if (i.progress != null) "" else line, Button("Report a problem", Action.REPORT))
    }

    /**
     * M3: the game's login screen. The SAME buttons the whole time, until past the login screen (the user's ruling); only the headline
     * follows the sign-in. DS Forever never types the password (the user's standing rule): "Keyboard" toggles the
     * keyboard on the top screen for the user.
     */
    private fun signInPrompt(i: Inputs): Prompt? {
        // the headline only (the user after test6: "the context text in that window is unnecessary"); one short line on a refusal
        val (title, text) = when (i.signIn) {
            SignIn.NONE -> return null
            // the user after test10: "I only really want to see those buttons if the login fails" - nothing while it signs in
            SignIn.SIGNING -> return null
            // never "wrong password": test8 (2026-10-12 13:56) was a Battle.net security check (a captcha after many test sign-ins) with
            // the same traffic as test3's bad password, and nothing in the logs tells them apart yet
            SignIn.FAILED -> "Sign-in didn't work" to ""
            // a moment only: the game closes and starts again by itself; no buttons to press meanwhile
            SignIn.RESTARTING -> return Prompt("signin", "Restarting the game to sign in…", "", emptyList())
            SignIn.RESTART -> "The game didn't restart" to ""
            SignIn.TYPING -> "Type it on the game screen" to ""
            // the game asks for the Battle.net Authenticator code (its log's authenticatorRequired="true"; a player's report 2026-10-13:
            // the keyboard did not open): the keyboard opens by itself, and "Keyboard" brings it back if it is closed
            SignIn.AUTH -> return Prompt("signin", "Enter your authenticator code", SignInKeyboard.HINT, listOf(Button("Keyboard", Action.ENTER_ON_GAME)))
            // p-auth-keyboard: the traffic says "not signed in" before the log says why (a code box, or a refusal): neutral until it does
            SignIn.CHECK -> return Prompt("signin", "Type your authenticator code (or password) on the game screen", SignInKeyboard.HINT,
                listOf(Button("Keyboard", Action.ENTER_ON_GAME),
                Button(if (i.loginSaved) "Update login" else "Save login", Action.UPDATE_LOGIN)))
        }
        val buttons = buildList {
            if (i.signIn == SignIn.RESTART || i.restartOffered) add(Button("Restart the game to sign in", Action.RESTART_GAME))
            add(Button(if (i.loginSaved) "Update login" else "Save login", Action.UPDATE_LOGIN))
            // the user after test9: "I feel like it should just be 'Keyboard'" - it toggles the top screen's keyboard
            add(Button("Keyboard", Action.ENTER_ON_GAME))
        }
        return Prompt("signin", title, text, buttons)
    }

    fun checks(i: Inputs): List<Check> = buildList {
        if (i.installedExternal) add(Check("DS Forever: on the SD card - move it to internal storage", Mark.BAD))
        add(when {
            !i.folderFound -> Check("Game folder", Mark.WAIT)            // not an error: the setup asks, or is installing it (the user 2026-11-01)
            i.wrongGame -> Check("Game folder: ${i.product.ifEmpty { "another game" }}, not WoW Forever", Mark.BAD)
            !i.storage -> Check("Game folder: ${i.path} (needs file access)", Mark.BAD)
            else -> Check("Game folder: ${i.path}", Mark.OK)
        })
        if (i.staleData != null) add(Check("Game folder: its Data is out of date - copy it again from your PC", Mark.BAD))
        add(if (i.loginSaved) Check("Battle.net login: saved", Mark.OK) else Check("Battle.net login: not saved", Mark.WAIT))
        add(when (i.art) {
            Art.IDLE -> Check(if (i.folderFound) "Game art: checking" else "Game art: waits for the game folder", Mark.WAIT)
            Art.RUNNING -> Check(if (i.artRebuild) "Game art: updating for the new game version: ${i.artPercent}%"
                else if (i.artWhat.startsWith(TablesProgress.PREFIX)) "Game art: ${i.artWhat}"
                else "Game art: ${i.artPercent}%${if (i.artWhat.isBlank()) "" else " (${i.artWhat})"}", Mark.WAIT)
            Art.READY -> Check("Game art: ready", Mark.OK)
            Art.FAILED -> Check("Game art: failed - ${i.artWhy.ifEmpty { "unknown reason" }}", Mark.BAD)
        })
        add(Check("Add-ons: ${if (i.addonsInstalled) "installed" else "installed with the first Play"}" +
            (i.dependencies?.let { "; $it" } ?: ""), if (i.addonsInstalled) Mark.OK else Mark.WAIT))
        i.indoor?.let { add(Check("Indoor maps (builds while playing): $it", Mark.OPTIONAL)) }
        i.client?.let { add(Check("Client build: $it", if (i.gameOutdated == null) Mark.OK else Mark.BAD)) }
        // the app's own update: optional, never a warning (an update waits for the player's press on the bottom screen)
        i.appLine?.let { add(Check(it, if (it.endsWith("up to date")) Mark.OK else Mark.OPTIONAL)) }
        i.iconsLine?.let { add(Check(it, if (it.endsWith("all icons ready")) Mark.OK else Mark.OPTIONAL)) }
    }
}
