package com.jrees.wowcompanion

import android.view.InputDevice
import android.view.KeyEvent

/**
 * THE CONTROLLER IN THE PROBLEM REPORT (issues #16 "no controller input", #84 "je ne peux pas controller l'ecran du bas avec les
 * boutons", #116 "joystick not working", #147 "no matter what i do i cant get the bottom screen to work", 2026-11-12). None of those
 * four reports could say whether a single press ever reached DS Forever: the pad's path (the launcher's game screen -> [PadFeed] -> the
 * rooms) logged nothing, and the logcat lines that were there were pushed out by the art step's file misses within minutes. This keeps,
 * in DiagLog's rolling FILE (logcat's eviction cannot touch it):
 * - the FIRST press from each input device on each route (the game screen, Streaming, the bottom window), with the launcher's verdict on it: "a controller: DS Forever reads it", or "not a controller
 *   to the launcher: DS Forever never reads it" (a pad the launcher's ExternalController.isGameController refuses never reaches PadFeed,
 *   nor the game as a pad);
 * - the game screen losing / getting back the window focus while the game runs (the Thor's dead-A trap: when another window holds the
 *   focus, every press goes there and neither the game nor the bottom screen hears it);
 * - one line for the report's "Settings and state": how many presses this run read, how long ago the last one was, the devices, the focus.
 * Device names are kept only when they look like a model name (a Bluetooth name the player chose, "Tom's pad", is not kept). Never a key's
 * meaning beyond its code; never text typed (keyboard letters are not counted).
 * [note] / [focusNote] / [line] are pure enough for the tests (DiagLog.useDir).
 */
object PadDiag {
    data class Dev(val id: Int, val name: String, val vendor: Int, val product: Int, val sources: Int)

    private val lock = Any()
    private val seen = LinkedHashMap<String, Pair<String, Boolean>>() // device id + route -> its label, controller?
    @Volatile var presses = 0; private set
    @Volatile var lastPressAt = 0L; private set
    /** null = never checked (Streaming, or before the game screen); else the game screen's window focus at the last check */
    @Volatile var focus: Boolean? = null; private set
    @Volatile private var focusSince = 0L

    /** a key event as the launcher's game screen (or Streaming's, or the bottom window) got it; [controller] = the launcher's verdict */
    fun key(e: KeyEvent, controller: Boolean, route: String) {
        if (e.action != KeyEvent.ACTION_DOWN || e.repeatCount > 0) return
        note(dev(e), e.keyCode, controller, route)
    }

    /**
     * a key event that reached the BOTTOM window (it holds the focus): the launcher never judged this device here, StartKeys reads the
     * press. Judged by the DEVICE's sources, not the event's (a D-pad press from the Thor's own pad can come as DPAD|KEYBOARD).
     */
    fun bottomKey(e: KeyEvent, typing: Boolean) {
        if (e.action != KeyEvent.ACTION_DOWN || e.repeatCount > 0) return
        val d = dev(e)
        note(d, e.keyCode, padDevice(d?.sources ?: e.source), "the bottom window${if (typing) " (typing)" else ""}", judged = false)
    }

    private fun dev(e: KeyEvent): Dev? {
        val d = e.device ?: return null
        return runCatching { Dev(d.id, d.name ?: "", d.vendorId, d.productId, d.sources) }.getOrNull()
    }

    /** a device whose sources say gamepad or joystick */
    fun padDevice(sources: Int): Boolean = sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
        sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK

    /** a controller's key code: the buttons and the D-pad (Android's own KeyEvent.isGamepadButton plus the D-pad) */
    fun padKey(code: Int): Boolean = code in KeyEvent.KEYCODE_BUTTON_A..KeyEvent.KEYCODE_BUTTON_MODE ||
        code in KeyEvent.KEYCODE_BUTTON_1..KeyEvent.KEYCODE_BUTTON_16 ||
        code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_LEFT ||
        code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_CENTER

    /**
     * one press (a key down, no repeat); the line it logged, null = nothing new to say. [judged] = [controller] is the launcher's verdict
     * (the game screen, Streaming); false = the bottom window, where [controller] only says what the device's sources are
     */
    fun note(dev: Dev?, code: Int, controller: Boolean, route: String, now: Long = System.currentTimeMillis(), judged: Boolean = true): String? {
        if (controller) { presses++; lastPressAt = now }
        if (!controller && !padKey(code)) return null                 // a keyboard's letters: not the controller's business
        val id = "${dev?.id ?: -1}|$route"
        val label = label(dev)
        synchronized(lock) {
            if (seen.containsKey(id)) return null
            if (seen.size >= MAX_DEVICES) return null
            seen[id] = label to (controller || !judged)
        }
        val line = "first press from $label (key $code, via $route): " + when {
            !judged -> "the start-up keys read it here, not the game" + if (controller) "" else " (not a pad device)"
            controller -> "a controller, DS Forever reads it"
            else -> "NOT a controller to the launcher: neither DS Forever nor the game reads it as a pad"
        }
        DiagLog.event("pad", line, now)
        return line
    }

    /** the game screen's window focus while the game runs (once a second); logs only a change */
    fun focusNote(has: Boolean, typing: Boolean, now: Long = System.currentTimeMillis()): String? {
        val was = focus
        if (was == has) return null
        focus = has; focusSince = now
        val line = when {
            has && was == null -> return null                          // the normal start: nothing to say
            has -> "the game screen has the focus again"
            typing -> "the game screen lost the focus to the bottom screen's text field (the controller types there)"
            else -> "the game screen lost the focus: the controller's presses go to another window (neither the game nor the bottom screen hears them)"
        }
        DiagLog.event("pad", line, now)
        return line
    }

    /** the report's one line: the presses, the devices, the focus */
    fun line(now: Long = System.currentTimeMillis()): String {
        val devs = synchronized(lock) { seen.values.map { (l, c) -> if (c) l else "$l (not a controller)" }.distinct() }
        val p = if (presses == 0) "no controller press read this run" else
            "$presses ${if (presses == 1) "press" else "presses"} read, the last ${GameLife.minutes((now - lastPressAt).coerceAtLeast(0L))} ago"
        val f = when (focus) { null -> "?"; true -> "yes"; false -> "NO for ${GameLife.minutes((now - focusSince).coerceAtLeast(0L))}" }
        return "Controller: $p; devices: ${if (devs.isEmpty()) "none seen" else devs.joinToString(", ")}; game screen focus: $f"
    }

    /** a model name as it is; a name the player gave it is left out (privacy) */
    fun label(dev: Dev?): String {
        if (dev == null) return "an unknown device"
        val n = dev.name.trim()
        val safe = n.isNotEmpty() && n.length <= 40 && n.none { it == '\'' || it == '’' || it == '@' } && !n.contains(" de ") && !n.contains(" di ")
        val ids = "%04x:%04x".format(dev.vendor and 0xffff, dev.product and 0xffff)
        val kind = when {
            dev.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD -> "gamepad"
            dev.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK -> "joystick"
            dev.sources and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD -> "keyboard"
            else -> "sources 0x${Integer.toHexString(dev.sources)}"
        }
        return "${if (safe) "'$n'" else "a named device"} ($ids, $kind)"
    }

    /** tests: a fresh run */
    fun reset() { synchronized(lock) { seen.clear() }; presses = 0; lastPressAt = 0L; focus = null; focusSince = 0L }

    private const val MAX_DEVICES = 8
}

/**
 * THE ADD-ONS' SILENCE (#147, 1.0.24: the game had started 12 minutes before, the player was asked for the authenticator twice, and in
 * the 2 minutes the bottom screen was up not one add-on document came: the rooms had nothing to work with, the report could not say so).
 * Pure: once, [AFTER_MS] after the bottom screen came up with the game up and no current document yet, the line saying so.
 */
object AddonSilence {
    const val AFTER_MS = 120_000L
    /** [detail] (issue #231): the reader's and the shadow folder's state, appended so the report tells "the game writes its log
     *  elsewhere" from "the log grows without our add-ons' lines" from "the log stands still" */
    fun line(docSeen: Boolean, gameUp: Boolean, hostUpMs: Long, gameUpMs: Long, detail: String? = null): String? {
        if (docSeen || !gameUp || hostUpMs < AFTER_MS) return null
        return "no current document from the game's add-ons ${GameLife.minutes(hostUpMs)} after the bottom screen came up " +
            "(the game up ${if (gameUpMs > 0) GameLife.minutes(gameUpMs) else "?"}): not in the world yet, or our add-ons did not load; the rooms wait for them" +
            (if (detail.isNullOrBlank()) "" else "; $detail")
    }

    /**
     * THE LIKELY CAUSE, for Home's add-ons notice (the user relaying players, 2026-11-13: "the pause radial is appearing on the top and
     * bottom ..." and "they let everything install and it keeps reinstalling, and the buttons and map show 'waiting for game'"): terse
     * words for the card, null = none known (then the card's default: enable them in AddOns). [setup] the add-on install's failure or
     * loop (LauncherSetup.problem); [shadowLogs] / [shadowIface] the shadow's entries ([shadowLogs]); [reader] the log reader's line.
     */
    fun cause(setup: String?, shadowLogs: String?, shadowIface: String?, reader: String?, off: List<String> = emptyList()): String? = when {
        !setup.isNullOrBlank() -> setup
        off.isNotEmpty() -> offCause(off)
        shadowIface?.contains("REAL dir") == true -> "The game loads an old private copy of the add-ons. Restart the game from DS Forever."
        shadowLogs?.contains("REAL dir") == true -> "The game's log went to a private folder. Restart the game from DS Forever."
        reader?.contains(" not there") == true -> "The game's log isn't there yet. Restart the game from DS Forever."
        else -> null
    }

    /**
     * Issue #278 (1.0.31: "says waitin on wow controller. i press start and the wheel only pops up on top screen"; its login probe:
     * "NOT loaded: WowCompanionSpells:DISABLED"): the add-ons the game did NOT load (the "ours" probe's `off`, "<folder>:<reason>") in
     * the game's own AddOns words, the first one named. Null = none.
     */
    fun offCause(off: List<String>): String? {
        val first = off.firstOrNull { it.isNotBlank() } ?: return null
        val name = TITLES[first.substringBefore(':')] ?: first.substringBefore(':')
        val more = if (off.size > 1) " (+${off.size - 1})" else ""
        return when (first.substringAfter(':', "")) {
            "INTERFACE_VERSION" -> "$name$more is out of date. Tick Load out of date AddOns at character select."
            "DEP_MISSING", "DEP_DISABLED", "DEP_DEMAND_LOADED" -> "$name$more needs DS Forever. Turn both on in AddOns at character select."
            "MISSING", "CORRUPT", "BANNED", "INSECURE" -> "$name$more is damaged. Restart the game from DS Forever."
            // switched on at this login (TapPad pad20) but the reload that loads it did not come
            "LOADABLE", "DEMAND_LOADED" -> "$name$more isn't loaded yet. Restart the game from DS Forever."
            else -> "$name$more is turned off in AddOns. Turn it on at character select."
        }
    }
    /** our folders by the names the game's AddOns list shows (their .toc Titles) */
    val TITLES = mapOf("WowCompanion" to "DS Forever", "WowCompanionSpells" to "DS Forever: Spells", "WowCompanionHub" to "DS Forever: Hub",
        "WowCompanionQuests" to "DS Forever: Quests", "WowCompanionInteract" to "DS Forever: Interact", "WowCompanionCharacter" to "DS Forever: Character",
        "WowCompanionSocial" to "DS Forever: Social", "WowCompanionCalendar" to "DS Forever: Calendar", "WowCompanionGroupFinder" to "DS Forever: Group Finder",
        "WowCompanionProfessions" to "DS Forever: Professions", "WowCompanionPvp" to "DS Forever: PvP")

    /** issue #231 (review): the report's bottom-screen line, when no document came, carries the reader's state at report time */
    fun atReport(docSeen: Boolean, detail: String?): String = if (docSeen || detail.isNullOrBlank()) "" else " ($detail)"

    // ------------------------------------------------------------------ the silence, per game run (the add-ons-silent triage, 2026-11-14)

    /** the re-check while the silence lasts */
    const val RECHECK_MS = 60_000L
    /** a "bye" (PLAYER_LOGOUT: a logout, a /reload, or the game's Disable + ReloadUI) with no word after it in this long = silence */
    const val BYE_HELLO_MS = 30_000L
    /** the logout's own last documents (written in the same moment as the bye) are no word after it; a hello always is */
    const val BYE_TRAIL_MS = 2_000L

    /**
     * THE ADD-ONS' SILENCE, PER GAME RUN (the add-ons-silent triage of the user's relays, 2026-11-13: "they let everything install and it
     * keeps reinstalling, and the buttons and map show 'waiting for game'"; issue #278). Up to 1.0.32 the check ran ONCE, 2 minutes after
     * the bottom screen came up - before Play, so with no game it said nothing and never looked again, and a game whose add-ons went quiet
     * later (the "has been blocked" pop-up's Disable: the game switches ours off and reloads) was never noticed at all. Now:
     *  - a clock per game run: from the game's start, or from past its login screen once that is known (the add-ons load in the world,
     *    never before), and never before this bottom screen could hear them ([hostUpMs]);
     *  - [AFTER_MS] into the clock with no current word: [Act.SAY] (the line, the likely cause); then [Act.RECHECK] every [RECHECK_MS]
     *    until a current document arrives;
     *  - a current "bye" with the game still running and NO word within [BYE_HELLO_MS] is silence too (a Disable + ReloadUI: the game
     *    stays in the world, the add-ons never say hello again): the clock starts at the bye and [Act.SAY] comes at once.
     * Wall-clock ms throughout; pure (dsforever's AddonsReenableTest drives it).
     */
    class Watch(private val hostUpMs: Long) {
        enum class Clock(val words: String) { GAME_START("the game's start"), PAST_LOGIN("past the login screen"),
            BYE("their logout word (no hello 30 s after it: switched off in AddOns and reloaded?)") }
        enum class Act { NONE, SAY, RECHECK }

        /** the game run the clock belongs to (its start, ms; 0 = no game) */
        var run = 0L; private set
        /** the clock's start (ms) and what it is */
        var from = 0L; private set
        var clock = Clock.GAME_START; private set
        /** the last current word of ours: its time (ms), one more than the word before at least (two in the same ms stay in order) */
        var wordAt = 0L; private set
        /** the pending bye's own word stamp (0 = none pending) and when it came (ms) */
        private var byeAt = 0L
        private var byeWall = 0L
        private var said = false
        private var nextAt = 0L

        /** any current document of ours, the bye too ([bye] follows it); [hello] = the add-ons' hello (a login / reload done) */
        fun word(now: Long, hello: Boolean = false) {
            // the logout's own trailing documents (the same flush as the bye) are not the add-ons back: only a hello, or a word later on
            if (!hello && byeWall > 0L && now - byeWall in 0..BYE_TRAIL_MS) return
            wordAt = maxOf(now, wordAt + 1)
        }
        /** a current "bye", right after its own [word] */
        fun bye(now: Long) { byeAt = wordAt.coerceAtLeast(1L); byeWall = now }

        /** no current word since the clock started (a game run only) */
        val silent: Boolean get() = run != 0L && wordAt < from
        /** silent since a bye with no hello after it (the Disable + ReloadUI case) */
        val silentByBye: Boolean get() = silent && clock == Clock.BYE
        fun silentMs(now: Long): Long = if (silent) (now - from).coerceAtLeast(0L) else 0L

        /** [gameUp] the game runs; [runStartMs] its start (ms); [pastLoginAtMs] past its login screen (ms, 0 = not this run) */
        fun tick(now: Long, gameUp: Boolean, runStartMs: Long, pastLoginAtMs: Long): Act {
            if (!gameUp || runStartMs <= 0L) { run = 0L; byeAt = 0L; byeWall = 0L; return Act.NONE }
            if (runStartMs != run) { run = runStartMs; byeAt = 0L; byeWall = 0L; restart(maxOf(runStartMs, hostUpMs), Clock.GAME_START, AFTER_MS) }
            // past the login screen later than the clock while still silent (the first sign-in, a re-login after a logout's bye): the
            // world is still to come. Not once the add-ons spoke: the world's first document marks past-login itself (NativeLogin.worldSeen)
            if (silent && pastLoginAtMs > from && pastLoginAtMs >= run && pastLoginAtMs <= now) restart(maxOf(pastLoginAtMs, hostUpMs), Clock.PAST_LOGIN, AFTER_MS)
            if (byeAt > 0L) {
                if (wordAt > byeAt) byeAt = 0L                                  // a word after it: a reload's hello, a new world
                else if (now - byeWall >= BYE_HELLO_MS) { restart(byeAt + 1, Clock.BYE, 0L); byeAt = 0L }
            }
            if (!silent || now < nextAt) return Act.NONE
            val a = if (said) Act.RECHECK else Act.SAY
            said = true; nextAt = now + RECHECK_MS
            return a
        }

        private fun restart(at: Long, c: Clock, after: Long) { from = at; clock = c; said = false; nextAt = at + after }
    }

    /** the report's / log's line for a silence of [silentMs] measured from [clock] */
    fun lineFor(silentMs: Long, clock: Watch.Clock, gameUpMs: Long, detail: String? = null): String =
        "no current document from the game's add-ons ${GameLife.minutes(silentMs)} after ${clock.words} " +
            "(the game up ${if (gameUpMs > 0) GameLife.minutes(gameUpMs) else "?"}): not in the world yet, or our add-ons did not load; the rooms wait for them" +
            (if (detail.isNullOrBlank()) "" else "; $detail")

    /** the game's memory (MB, all its processes) at which it looks to be in the world, and for how long */
    const val IN_WORLD_MB = 2_500L
    const val IN_WORLD_FOR_MS = 30_000L

    /**
     * THE GAME LOOKS TO BE IN THE WORLD (the triage: the add-ons say nothing, so their documents cannot tell): MemWatch's samples of the
     * game's memory (every 30 s) at least [IN_WORLD_MB] from one sample to a later one [IN_WORLD_FOR_MS] on, with none below between.
     * Character select and the login screen sit well under it; in the world the Thor's game is ~4.6 GB. [sample] takes (MB, its ms).
     */
    class WorldByMemory {
        private var highSince = -1L
        private var lastAt = -1L
        fun sample(mb: Long, at: Long) {
            if (at == lastAt) return                              // the same sample read again
            lastAt = at
            if (mb >= IN_WORLD_MB) { if (highSince < 0L) highSince = at } else highSince = -1L
        }
        val inWorld: Boolean get() = highSince >= 0L && lastAt - highSince >= IN_WORLD_FOR_MS
        fun reset() { highSince = -1L; lastAt = -1L }
    }

    /** the start screen's plain waiting lines: the only ones the add-ons' word takes over (never a reload's, a restart's, a sign-in's) */
    const val WORLD_LINE = "Waiting for the world."
    const val SIGNED_WORLD_LINE = "Signed in. Waiting for the world."
    val WAIT_LINES = setOf(PreLoginTraffic.WAITING_LINE, PreLoginTraffic.DOWNLOADING_LINE, WORLD_LINE, SIGNED_WORLD_LINE)

    enum class StartMode { KEEP, WORLD_LINE, ADDONS }

    /**
     * THE START SCREEN STILL UP AFTER THE SIGN-IN (the triage; Home's add-ons card never shows over it): [line] its line now; [rowUp]
     * the sign-in row shows; [signedIn] past the login screen; [downloading] the game fetches files; [silent] no word from the add-ons
     * this clock ([Watch]); [inWorld] the game's memory says the world ([WorldByMemory]). ADDONS = the line's panel says what the card
     * would (its text, with How); WORLD_LINE = the plain waiting line becomes the existing "Waiting for the world."; KEEP = as it is.
     * The review (2026-11-14): a normal logout to character select is a bye with no hello too, and the game's memory may stay over
     * [IN_WORLD_MB] there, so [trafficQuiet] (NativeLogin.trafficQuiet: the game's traffic stopped, which the world's never does) holds
     * a NEW word back; one already on show ([shown]) stays - character select is where the player switches them back on.
     */
    fun startMode(line: String, rowUp: Boolean, signedIn: Boolean, downloading: Boolean, silent: Boolean, inWorld: Boolean,
                  trafficQuiet: Boolean = false, shown: Boolean = false): StartMode = when {
        line !in WAIT_LINES || rowUp -> StartMode.KEEP
        silent && inWorld && (!trafficQuiet || shown) -> StartMode.ADDONS
        signedIn && !downloading && line == PreLoginTraffic.WAITING_LINE -> StartMode.WORLD_LINE
        else -> StartMode.KEEP
    }

    /** issue #231: the local reader in one line ([sinceFedMs] -1 = nothing read yet); only the file's name, never the folder's */
    fun reader(path: String, exists: Boolean, len: Long, mtime: Long, nowWall: Long, fed: Long, sinceFedMs: Long,
               lines: Long, magic: Long, docs: Long): String {
        val name = path.substringAfterLast('/').ifEmpty { path }
        val file = if (!exists) "$name not there" else "$name ${len / 1024} KB, written ${if (mtime > 0) GameLife.minutes((nowWall - mtime).coerceAtLeast(0L)) + " ago" else "?"}"
        val got = if (sinceFedMs < 0) "nothing read yet" else "read ${fed / 1024} KB (the last ${GameLife.minutes(sinceFedMs)} ago)"
        return "reader: $file; $got, $lines lines, $magic of ours, $docs documents"
    }

    /** issue #231: the private shadow folder's Logs entry (the client writes General.log there when it is a real folder, not a link) */
    fun shadowLogs(logs: java.io.File?, isLink: (java.io.File) -> Boolean, nowWall: Long): String {
        if (logs == null) return "shadow Logs: no shadow this run"
        return runCatching {
            when {
                isLink(logs) -> "shadow Logs: link"
                !logs.exists() -> "shadow Logs: missing"
                logs.isDirectory -> {
                    val g = java.io.File(logs, "General.log")
                    "shadow Logs: REAL dir (" + (if (g.exists()) "General.log ${g.length() / 1024} KB, written " +
                        GameLife.minutes((nowWall - g.lastModified()).coerceAtLeast(0L)) + " ago" else "no General.log") + ")"
                }
                else -> "shadow Logs: a file"
            }
        }.getOrElse { "shadow Logs: unreadable (${it.javaClass.simpleName})" }
    }
}
