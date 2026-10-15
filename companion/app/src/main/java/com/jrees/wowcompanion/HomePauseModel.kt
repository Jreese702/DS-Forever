package com.jrees.wowcompanion

/**
 * THE RADIAL PAUSE MENU'S RULES (the user 2026-10-08, via tracking: "pressing the start button and an animation of all of the home
 * panels sliding out ... opening basically the exact radial controller menu that wow uses, including the extra pages and d pad options
 * for siting/standing and emotes"). The game's own menu is GamepadRadial (Controller UI read it from this client's source:
 * wow-companion-pulse/RADIAL_PAUSE_MENU.md); this is its rebuild on the bottom screen, no Android in it (tools/home_test runs it).
 *
 * THE USER'S ANSWERS (2026-10-08): a Home module that holds Start keeps it (map Filter, chat filter menu); it opens in fights too;
 * the slices we have no room for show GREYED ("coming later"); the Home disc is the hub (red swirl + B); the stick lights a wedge and
 * LETTING IT GO picks (no A), as the game does.
 *
 * SLICES are kept in the KIT's order (0 = up, clockwise: RadialModel.aimOf / UiKit's wheels). The game counts from EAST,
 * counter-clockwise ([fromGame]).
 */
object HomePauseModel {
    /** what a pick does: one of our rooms, a game action through the tap pad, Home's own buff list, or nothing yet */
    enum class Kind { ROOM, GAME, HOME, LATER }

    /**
     * One slice. [key] = the game's own key (gamepadradial.lua) and the kit's icon name (ricon_<key>); [label] = the English text
     * UI Art drew; [room] for a ROOM (the Room enum's name: Theme.kt is Android, the test runs without it); [cmd] for a GAME action (the tap row's cell name); [confirm] asks first (the PvP flag).
     */
    data class Slice(
        val key: String, val label: String, val kind: Kind,
        val room: String? = null, val cmd: String? = null, val confirm: Boolean = false, val tab: String? = null,
    ) {
        val enabled get() = kind != Kind.LATER
    }

    private fun room(key: String, label: String, r: String, tab: String? = null) = Slice(key, label, Kind.ROOM, room = r, tab = tab)
    private fun later(key: String, label: String) = Slice(key, label, Kind.LATER)
    private fun game(key: String, label: String, cmd: String, confirm: Boolean = false) = Slice(key, label, Kind.GAME, cmd = cmd, confirm = confirm)

    /** the game's slice [gameIndex] (0 = east, counter-clockwise) -> the kit's slot (0 = up, clockwise) */
    fun fromGame(gameIndex: Int): Int = ((2 - gameIndex) % 8 + 8) % 8

    private fun kitOrder(game: List<Slice?>): List<Slice?> {
        val out = MutableList<Slice?>(8) { null }
        game.forEachIndexed { i, s -> out[fromGame(i)] = s }
        return out
    }

    /** THE THREE MAIN PAGES, in the game's order per page (E, NE, N, NW, W, SW, S, SE) */
    val PAGES: List<List<Slice?>> = listOf(
        // page 1 (LEFT)
        kitOrder(listOf(
            room("lfd", "Group Finder", "GROUPFINDER"),
            later("legacy", "Legacy"),
            later("store", "Shop"),
            room("guild", "Communities", "SOCIAL", tab = "guild"),
            room("social", "Social", "SOCIAL", tab = "friends"),
            Slice("buffs", "View Buffs", Kind.HOME),
            later("tracking", "Minimap Settings"),
            later("collections", "Collections"),
        )),
        // page 2 (CENTRE, the one it opens on)
        kitOrder(listOf(
            room("bags", "Bags", "BAGS"),
            room("professions", "Professions", "PROFESSIONS"),
            room("character", "Character", "CHARACTER"),
            room("talents", "Talents", "TALENTS"),
            room("map", "Quest & Maps", "MAP"),
            room("chat", "Chat", "CHAT"),
            // ONE ESCAPE PRESS (the user 2026-10-09, "Escape opens it"; it replaced Spells' Start arm, which lost a race with the
            // wheel's own hold and needed a second Start): HomePauseView sends the game's Escape key, its own binding opens the menu
            game("menu", "Game Menu", "menu"),
            room("spellbook", "Spellbook", "SPELLBOOK"),
        )),
        // page 3 (RIGHT): three slices, the rest empty (hidden), as in the game
        kitOrder(listOf(
            later("time", "Clock"),
            room("calendar", "Calendar", "CALENDAR"),
            null, null,
            game("pvp", "PvP", "pvp", confirm = true),
            null, null, null,
        )),
    )

    /** f-trackpad-keyboard (the user's pick: "D-pad Left"): the Keyboard & Trackpad tool, Home's own (HomeView.openTypeTool) */
    val TOOL = Slice("typetool", "Keyboard & Trackpad", Kind.HOME)
    /** its word on the context band */
    const val TOOL_WORD = "Keyboard & Trackpad"

    /** D-pad UP's custom page: eight one-shot emotes (the game's order), header "Emotes" */
    val EMOTES: List<Slice?> = kitOrder(listOf("Dance", "Point", "Wave", "Kneel", "Train", "Chicken", "Angry", "Cheer")
        .map { game("emote", it, "emote_" + it.lowercase()) })

    const val PAGE_COUNT = 3
    /** the game's DEFAULT_PAGE_INDEX 2 (1-based): it always opens on the centre page */
    const val DEFAULT_PAGE = 1
    /** the custom page's index in [Flow.page] */
    const val EMOTE_PAGE = -1

    /** the line under the wheel for a greyed slice (the user's "coming later") */
    const val LATER_LINE = "Coming later"

    /** what the view must do after an input */
    sealed class Act {
        object None : Act()
        object Close : Act()
        /** a slice fired: the wheel closes and [slice] runs */
        data class Pick(val slice: Slice) : Act()
        /** a greyed slice was let go on: the wheel stays, its line shows */
        data class Refuse(val slice: Slice) : Act()
        /** D-pad Down's release: Sit / Stand, the wheel stays open (the game's) */
        object Sit : Act()
    }

    /**
     * THE WHEEL'S STATE: the page (0..2, or [EMOTE_PAGE]), the lit slice, and the stick's pick. The game's rules (gamepadradial.lua):
     * the stick past half lights a slice, falling back under a fifth FIRES it; a right-stick click cancels until the stick is centred
     * again; B on a main page closes, on the custom page goes back to the centre page; LB / RB step the main pages, wrapping; the
     * D-pad options fire on RELEASE (a release with no press seen is ignored).
     */
    class Flow {
        /**
         * THE ONE RADIAL BEHAVIOUR, TWO SETTINGS (the user 2026-10-09: "2 settings per radial. Tap VS hold and A to select vs release
         * select"; one pair for every wheel, defaults Tap + A; HomeSettings.radialTap / radialPickA), the consumables wheel's rules:
         * OPEN [holdOpen]: Tap = Start opens it and it stays (B / Start / the hub close); Hold = letting START go closes it, nothing picked.
         * PICK [pickA]: A = the stick only aims (the aim stays lit), A picks; Release = letting the STICK go picks (the spring-back latch),
         * A does nothing. One pick can never come twice: A never picks under Release, the stick never picks under A.
         */
        var holdOpen = false
        var pickA = false
        /** both at once (the older single setting): Tap + A, or Hold + Release */
        var tap: Boolean
            get() = !holdOpen && pickA
            set(v) { holdOpen = !v; pickA = v }
        var page = DEFAULT_PAGE; private set
        /** the lit slot (kit order), -1 = none */
        var pointed = -1; private set
        /** a stick click cancelled the pick: nothing lights until the stick is back in the middle */
        private var cancelled = false
        /** a stick is pushed at [pointed]; the slice it RESTED on before the current one, and since when (the spring-back latch) */
        private var aiming = false
        private var stable = -1
        private var since = 0L
        /** RELEASE arming (2026-10-09; the consumables wheel's rule): 0 = off (tests); the view sets ARM_MS and [openAt] at each open */
        var armMs = 0L
        var openAt = 0L
        private var sawNeutral = false
        private var aimArmed = true
        /** the D-pad option being held (0 up, 2 down; -1 none): lit while held, fires on release */
        var heldOption = -1; private set

        fun reset() {
            page = DEFAULT_PAGE; pointed = -1; cancelled = false; heldOption = -1; aiming = false; stable = -1; since = 0L
            sawNeutral = false; aimArmed = true
        }

        fun slices(): List<Slice?> = if (page == EMOTE_PAGE) EMOTES else PAGES[page]
        fun header(): String = if (page == EMOTE_PAGE) "Emotes" else "Main Menu"

        /** the stick: [x] right +, [y] UP +, -1..1, at [now] (ms; the spring-back latch, as the consumables wheel's) */
        fun vector(x: Double, y: Double, now: Long = 0L): Act {
            val m = kotlin.math.hypot(x, y)
            if (!aiming && m < RadialModel.NEUTRAL) sawNeutral = true
            if (cancelled) { if (m < RadialModel.DEAD_OUT) cancelled = false; return Act.None }
            val s = RadialModel.aimOf(x, y, if (aiming) pointed else -1)
            if (s >= 0) {
                if (!aiming) aimArmed = armMs <= 0L || sawNeutral || now - openAt >= armMs
                // an empty slot (page 3's holes) lights nothing; the last lit slice stays lit until the stick leaves it
                if (slices()[s] != null && (s != pointed || !aiming)) {
                    if (aiming && pointed >= 0 && now - since >= RadialModel.LATCH_MS) stable = pointed
                    pointed = s; since = now
                }
                if (slices()[s] != null) aiming = true
                return Act.None
            }
            if (!aiming) return Act.None
            // back in the middle: the slice the stick RESTED on (not one it swept through springing back)
            aiming = false
            val fired = if (now - since >= RadialModel.LATCH_MS || stable < 0) pointed else stable
            stable = -1
            if (pickA) { pointed = fired; return Act.None }        // A TO SELECT: the aim stays lit for A
            sawNeutral = true
            pointed = -1
            if (!aimArmed) return Act.None                       // RELEASE: an aim begun before the wheel was armed never picks
            val slice = slices().getOrNull(fired) ?: return Act.None
            return if (slice.enabled) Act.Pick(slice) else Act.Refuse(slice)
        }

        /** Start let go after it opened the wheel: HOLD = the wheel closes, nothing picked; TAP = nothing (the wheel stays) */
        fun startReleased(): Act = if (holdOpen) Act.Close else Act.None

        /**
         * A (the user 2026-10-09, "A picks too": as the consumables wheel): the lit slice fires, as letting the stick go does. The
         * stick then lights nothing until it is back in the middle, so its own release cannot pick a second time (PvP's confirm).
         */
        fun a(): Act {
            if (!pickA) return Act.None                              // RELEASE TO SELECT: A does nothing
            val slice = slices().getOrNull(pointed) ?: return Act.None
            pointed = -1; cancelled = aiming; aiming = false
            return if (slice.enabled) Act.Pick(slice) else Act.Refuse(slice)
        }

        /** the right stick's click: the pick is dropped */
        fun cancel() { if (pointed >= 0) { pointed = -1; cancelled = true } }

        /** B: back to the centre page from Emotes, else close */
        fun b(): Act {
            if (page == EMOTE_PAGE) { page = DEFAULT_PAGE; pointed = -1; return Act.None }
            return Act.Close
        }

        /** LB (-1) / RB (+1): the main pages, wrapping; nothing on the custom page (its dots hide) */
        fun turn(by: Int): Boolean {
            if (page == EMOTE_PAGE) return false
            page = ((page + by) % PAGE_COUNT + PAGE_COUNT) % PAGE_COUNT
            pointed = -1
            return true
        }

        /** a D-pad direction (0 up, 1 right, 2 down, 3 left) pressed or let go; f-trackpad-keyboard: LEFT = the Keyboard & Trackpad */
        fun dpad(dir: Int, down: Boolean): Act {
            if (dir != 0 && dir != 2 && dir != 3) return Act.None        // right: no option (kept free)
            if (down) { heldOption = dir; return Act.None }
            if (heldOption != dir) return Act.None                      // a release with no press seen
            heldOption = -1
            return when (dir) { 0 -> { page = EMOTE_PAGE; pointed = -1; Act.None }; 3 -> Act.Pick(TOOL); else -> Act.Sit }
        }

        /** a finger on slot [s] (the kit's hit test): a pick at once; [HUB] closes */
        fun touch(s: Int): Act {
            if (s == HUB) return Act.Close
            val slice = slices().getOrNull(s) ?: return Act.None
            pointed = s
            return if (slice.enabled) Act.Pick(slice) else Act.Refuse(slice)
        }
    }

    const val HUB = -2

    /** Spells' plain tap row for the game actions (asked 10-08), its columns in this order; the add-on answers `pauseack` */
    const val ROW = "pause"
    val COLS = listOf("sit", "pvp", "emote_dance", "emote_point", "emote_wave", "emote_kneel", "emote_train", "emote_chicken",
        "emote_angry", "emote_cheer", "menu")
    /** how long a line under the gone wheel stays (the Game Menu's Escape could not be sent) */
    const val MENU_ARM_MS = 4_000L
    fun col(cmd: String): Int = COLS.indexOf(cmd)
}
