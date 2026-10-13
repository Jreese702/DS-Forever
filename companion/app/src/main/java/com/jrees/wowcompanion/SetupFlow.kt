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
    enum class SignIn { NONE, SIGNING, FAILED, RESTARTING, RESTART, TYPING, AUTH }

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
        /** launching / updating / downloading: what is happening (Play waits) */
        val busy: String? = null,
        val signIn: SignIn = SignIn.NONE,
        /** "Restart the game to sign in" applied in this sign-in: its button stays until past the login screen */
        val restartOffered: Boolean = false,
        /** the game screen is up: only the sign-in pop-ups remain */
        val gameRunning: Boolean = false,
        /** a "Restart the game to sign in" is between the close and the new game (no Play meanwhile: one launch path only) */
        val restarting: Boolean = false,
    )

    enum class Action {
        CHOOSE_FOLDER, GRANT_STORAGE, ENTER_LOGIN, SKIP_LOGIN, RETRY_ART, UPDATE_GAME, UPDATE_APP, LATER_APP,
        UPDATE_LOGIN, ENTER_ON_GAME, RESTART_GAME, PLAY,
        /** the restart's own Play (never a button): it launches the same way and says so in the log */
        RELAUNCH
    }
    data class Button(val label: String, val action: Action, val enabled: Boolean = true)
    data class Prompt(val id: String, val title: String, val text: String, val buttons: List<Button>)
    /** [play] null = no Play (the game runs); [line] = one status line under it */
    data class Screen(val prompt: Prompt?, val play: Button?, val line: String)

    enum class Mark { OK, WAIT, BAD, OPTIONAL }
    data class Check(val label: String, val mark: Mark)

    /** the "DS Forever vX is out" pop-up holds Play until Update or Later (board b-update-blocks-play) */
    fun canPlay(i: Inputs): Boolean =
        i.folderFound && !i.wrongGame && i.storage && i.art == Art.READY && i.busy == null && !i.gameRunning && i.appUpdate == null

    fun screen(i: Inputs): Screen {
        if (i.gameRunning) return Screen(signInPrompt(i), null, "")
        // TEST8 13:24:46: during a restart the bottom's Play came back with the launcher and a launch started before the restart's own
        // (that one was refused as a duplicate). Between the close and the new game: the restart's line only, nothing to press
        if (i.restarting) return Screen(Prompt("signin", "Restarting the game to sign in…", "", emptyList()), null, "")
        val prompt = when {
            !i.folderFound || i.wrongGame -> Prompt("folder", "Where is WoW Forever?",
                if (i.wrongGame) "The folder ${i.path} holds ${i.product.ifEmpty { "another game" }}, not WoW Forever. Choose the WoW Forever folder: the one with .build.info and Data."
                else "It isn't in ${i.path}. Choose the folder with .build.info and Data (for example Documents/WoW Forever).",
                listOf(Button("Choose folder", Action.CHOOSE_FOLDER)))
            !i.storage -> Prompt("storage", "Allow file access",
                "Android needs \"All files access\" so the game and DS Forever can read the game folder.",
                listOf(Button("Open settings", Action.GRANT_STORAGE)))
            i.art == Art.FAILED -> Prompt("art", "The game art couldn't be built",
                i.artWhy.ifEmpty { "The game files could not be read." }, listOf(Button("Try again", Action.RETRY_ART)))
            // before the client's pop-up: its "Play anyway" waits for this choice (canPlay)
            i.appUpdate != null -> Prompt("app", "DS Forever ${i.appUpdate} is out", "Update now, or later from this screen.",
                listOf(Button("Update", Action.UPDATE_APP, i.busy == null), Button("Later", Action.LATER_APP)))
            i.gameOutdated != null -> Prompt("client", "Game update needed",
                "The servers need build ${i.gameOutdated}. Signing in may fail with these files.",
                listOf(Button("Update", Action.UPDATE_GAME, i.busy == null), Button("Play anyway", Action.PLAY, canPlay(i))))
            !i.loginSaved && !i.loginSkipped -> Prompt("login", "Your Battle.net login",
                "Save it once and the game signs you in by itself. It stays encrypted on this device.",
                listOf(Button("Enter login", Action.ENTER_LOGIN), Button("Play without", Action.SKIP_LOGIN)))
            else -> null
        }
        // the client prompt carries its own Play ("Play anyway"); everything else blocking hides behind a disabled Play
        val play = Button("Play", Action.PLAY, canPlay(i))
        val line = i.busy ?: when (i.art) {
            Art.RUNNING -> "Preparing the game art: ${i.artPercent}%"
            Art.IDLE -> if (i.folderFound) "Checking the game art" else ""
            else -> ""
        }
        return Screen(prompt, play, line)
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
            SignIn.AUTH -> return Prompt("signin", "Enter your authenticator code", "", listOf(Button("Keyboard", Action.ENTER_ON_GAME)))
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
        add(when {
            !i.folderFound -> Check("Game folder: not found (${i.path})", Mark.BAD)
            i.wrongGame -> Check("Game folder: ${i.product.ifEmpty { "another game" }}, not WoW Forever", Mark.BAD)
            !i.storage -> Check("Game folder: ${i.path} (needs file access)", Mark.BAD)
            else -> Check("Game folder: ${i.path}", Mark.OK)
        })
        add(if (i.loginSaved) Check("Battle.net login: saved", Mark.OK) else Check("Battle.net login: not saved", Mark.WAIT))
        add(when (i.art) {
            Art.IDLE -> Check(if (i.folderFound) "Game art: checking" else "Game art: waits for the game folder", Mark.WAIT)
            Art.RUNNING -> Check("Game art: ${i.artPercent}%${if (i.artWhat.isBlank()) "" else " (${i.artWhat})"}", Mark.WAIT)
            Art.READY -> Check("Game art: ready", Mark.OK)
            Art.FAILED -> Check("Game art: failed - ${i.artWhy.ifEmpty { "unknown reason" }}", Mark.BAD)
        })
        add(Check("Add-ons: ${if (i.addonsInstalled) "installed" else "installed with the first Play"}" +
            (i.dependencies?.let { "; $it" } ?: ""), if (i.addonsInstalled) Mark.OK else Mark.WAIT))
        i.indoor?.let { add(Check("Indoor maps (optional): $it", Mark.OPTIONAL)) }
        i.client?.let { add(Check("Client build: $it", if (i.gameOutdated == null) Mark.OK else Mark.BAD)) }
        // the app's own update: optional, never a warning (an update waits for the player's press on the bottom screen)
        i.appLine?.let { add(Check(it, if (it.endsWith("up to date")) Mark.OK else Mark.OPTIONAL)) }
        i.iconsLine?.let { add(Check(it, if (it.endsWith("all icons ready")) Mark.OK else Mark.OPTIONAL)) }
    }
}
