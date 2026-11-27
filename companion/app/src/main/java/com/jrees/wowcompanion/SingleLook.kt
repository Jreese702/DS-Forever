package com.jrees.wowcompanion

/**
 * SINGLE SCREEN MODE: WHAT SHOWS ON THE TOP SCREEN (SingleScreen; the user's picks 2026-11-25: "Centred panel", "Straight to the game",
 * "Ours over the game"). ONE pure function decides it from what the host has open ([In]): the top window's frame (HIDDEN = no window
 * at all, every touch the game's; FULL = our window over the game), the dim over the game, whether our window takes touches, what the
 * rooms column shows ([Root]) and whether the play docks / a strip show (PLAY / NPC builders). Rules in order, the first that applies:
 *
 *  1 the launch swirl covers the screen                       HIDDEN
 *  2 start pages, the report, an NPC window, a game dialog,
 *    the Spellbook's Bind box                                 FULL 0.55, touch, the room column if a room is open (else Home's glance)
 *    - the loot window alone over the game                    FULL, NO dim ([DIM_LOOT]; the user on 1533, 2026-11-26: "When looting
 *                                                            the screen dims but thats excessive")
 *  2b the waiting page with the game screen up (a logout,
 *    a disconnect, a reload, "Loading your character")       HIDDEN, the strip "start": the page's own row (its line, Menu, Keyboard,
 *                                                            the sign-in row) at the screen's TOP - the game's login screen and
 *                                                            character select stay the player's (the review, 2026-11-25; the user's
 *                                                            ruling "Top of the screen (Recommended): Keeps character select's Enter
 *                                                            World and the bottom of the login screen free for touch.")
 *  3 the pause wheel                                          FULL 0.55, touch, the same
 *  4 the top-screen layout editor (over Settings)            FULL 0.25, touch, ROOM: the pieces it moves show at the sides (NPC)
 *  4 a room (not Home)                                        FULL 0.55, touch, ROOM
 *  5 Home with its page as a glance, or a Home sub-state      FULL 0.55, touch, HOME
 *  5b THE DS MENU'S ROW (the 1536 DS menu, SingleMenu: Down /
 *    the swirl button lifts the room row)                     FULL 0.55, touch, nothing of the rooms column ([Look.menu]: the row)
 *  5c the row dropping after its last B                       FULL, NO dim, no touch, [Look.menu] (the drop's frames; then PLAY)
 *  6 a quick wheel (consumables / target / spell radials)     FULL 0.25, touch, nothing under it          (NPC)
 *  7 the class wheel                                          FULL 0.25, touch, Home's layers only        (NPC)
 *  8 the Track Pad / Keyboard tool                            HIDDEN, the strip "tool"                     (NPC)
 *  9 LB's party picture                                       HIDDEN, the docks, the strip "lb" over the left dock's party place:
 *                                                            display only, no window over the whole screen for a held button; a
 *                                                            held button's moment wins over the rolls' strip (NPC)
 * 10 the fight's loot rolls                                   HIDDEN, the left dock only, the strip "rolls" (NPC)
 * 11 else = PLAY: the game, nothing of ours but the docks     HIDDEN
 *
 * Rows marked NPC are the NPC builder's to refine (CORE's defaults here).
 *
 * THE DS MENU (the 1536 pass 1, SingleMenu; the user: "I want it to be its own experience that feels like it was made for the top
 * screen"): [Look.menu] = the menu's frame shows - its row along the bottom and, with a room (rows 2-4 over a room) or Home's stage (the
 * glance opened from the row's swirl, [In.menuHome]), that page on the wide stage above it instead of the centred panel.
 */
object SingleLook {
    enum class Frame { HIDDEN, FULL }
    /** the rooms column: gone, a room, Home's page, or only HomeView's overlay layers (NPC: the class wheel) */
    enum class Root { GONE, ROOM, HOME, HOME_LAYERS }

    /** what the host has open now. [room] = the Room's name; [tool] = the type tool's tab (-1 none, 0 Track Pad, 1 Keyboard) */
    data class In(
        val room: String = "HOME", val launchCover: Boolean = false, val startPages: Boolean = false, val report: Boolean = false,
        val npc: Boolean = false, val dialog: Boolean = false, val fightRolls: Boolean = false, val spells: Boolean = false,
        val pause: Boolean = false, val glanceHub: Boolean = false, val homeSub: Boolean = false, val quick: Boolean = false,
        val classWheel: Boolean = false, val lb: Boolean = false, val tool: Int = -1,
        /** NPC: the top-screen layout editor (LayoutView) is open over the Settings room */
        val layoutEdit: Boolean = false,
        /** the waiting page (StartView) with the game screen up and its Menu shut (the host's singleWaiting) */
        val waiting: Boolean = false,
        /** the NPC window open is the LOOT window (InteractView.looting) */
        val loot: Boolean = false,
        /** the DS menu's row is up (the bar cursor in single screen mode: Down, the swirl button, a fold) */
        val menuRow: Boolean = false,
        /** Home's glance was opened from the row's swirl: Home's page on the stage */
        val menuHome: Boolean = false,
        /** the row is dropping after its last B (its frames, then PLAY) */
        val menuDrop: Boolean = false,
    )

    /** what the top window does: [strip] = null, "tool", "rolls", "lb" (NPC: SingleNpc's strip window) or "start" (the waiting page) */
    data class Look(val frame: Frame, val dim: Float, val touchable: Boolean, val root: Root, val docks: Boolean, val strip: String? = null,
                    /** the DS menu's frame shows (SingleMenu): its row, and the room / Home's stage over it */
                    val menu: Boolean = false) {
        /** the PLAY state: nothing of ours over the game */
        val play: Boolean get() = frame == Frame.HIDDEN && strip == null && root == Root.GONE && docks
    }

    const val DIM_PANEL = 0.55f
    const val DIM_QUICK = 0.25f
    /** the loot window alone over the game: no dim (the 1535 polish) */
    const val DIM_LOOT = 0f
    const val HOME = "HOME"

    val PLAY = Look(Frame.HIDDEN, 0f, false, Root.GONE, docks = true)
    val COVERED = Look(Frame.HIDDEN, 0f, false, Root.GONE, docks = false)

    /** the int/1533 review: a text field of the top window can type under [l] only while its window shows (FULL); HIDDEN draws none of
     *  its content (the strips are windows of their own), so a field typing then gives the keys back (TopWindow.look) */
    fun canType(l: Look): Boolean = l.frame == Frame.FULL

    fun of(i: In): Look {
        val inRoom = i.room != HOME
        // pause / NPC over Home: Home's page shows only while it is up as a glance
        val under = if (inRoom) Root.ROOM else if (i.glanceHub || i.homeSub) Root.HOME else Root.GONE
        // the DS menu's frame: a room is always on the stage; Home's page only when the row's swirl opened it (or the row is up over it)
        val menuUnder = under == Root.ROOM || (under == Root.HOME && (i.menuHome && i.glanceHub || i.menuRow))
        // the loot window with nothing else of ours open over the game (no room, no glance, no page, no pop-up): the game undimmed
        val lootOnly = i.npc && i.loot && !inRoom && under == Root.GONE && !i.startPages && !i.report && !(i.dialog && !i.fightRolls) &&
            !i.spells && !i.pause
        return when {
            i.launchCover -> COVERED                                                                          // 1
            i.startPages || i.report || i.npc || (i.dialog && !i.fightRolls) || i.spells ->
                Look(Frame.FULL, if (lootOnly) DIM_LOOT else DIM_PANEL, true, under, docks = false,
                    menu = menuUnder && !i.startPages && !i.report)                                           // 2
            i.waiting -> Look(Frame.HIDDEN, 0f, false, Root.GONE, docks = false, strip = SingleNpc.START)     // 2b
            i.pause -> Look(Frame.FULL, DIM_PANEL, true, under, docks = false, menu = menuUnder)              // 3
            inRoom && i.layoutEdit -> Look(Frame.FULL, DIM_QUICK, true, Root.ROOM, docks = false)             // 4 NPC (the layout editor)
            inRoom -> Look(Frame.FULL, DIM_PANEL, true, Root.ROOM, docks = false, menu = true)                // 4
            i.glanceHub || i.homeSub -> Look(Frame.FULL, DIM_PANEL, true, Root.HOME, docks = false, menu = menuUnder)  // 5
            i.menuRow -> Look(Frame.FULL, DIM_PANEL, true, Root.GONE, docks = false, menu = true)             // 5b the DS menu's row
            i.menuDrop -> Look(Frame.FULL, 0f, false, Root.GONE, docks = false, menu = true)                  // 5c its drop
            i.quick -> Look(Frame.FULL, DIM_QUICK, true, Root.GONE, docks = false)                            // 6 NPC
            i.classWheel -> Look(Frame.FULL, DIM_QUICK, true, Root.HOME_LAYERS, docks = false)                // 7 NPC
            i.tool >= 0 -> Look(Frame.HIDDEN, 0f, false, Root.GONE, docks = false, strip = "tool")            // 8 NPC
            i.lb -> Look(Frame.HIDDEN, 0f, false, Root.GONE, docks = true, strip = "lb")                      // 9 NPC (display only)
            i.fightRolls -> Look(Frame.HIDDEN, 0f, false, Root.GONE, docks = true, strip = "rolls")           // 10 NPC (the left dock only)
            else -> PLAY                                                                                      // 11
        }
    }
}
