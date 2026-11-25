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
 *  3 the pause wheel                                          FULL 0.55, touch, the same
 *  4 a room (not Home)                                        FULL 0.55, touch, ROOM
 *  5 Home with its page as a glance, or a Home sub-state      FULL 0.55, touch, HOME
 *  6 a quick wheel (consumables / target / spell radials)     FULL 0.25, touch, nothing under it          (NPC)
 *  7 the class wheel                                          FULL 0.25, touch, Home's layers only        (NPC)
 *  8 the Track Pad / Keyboard tool                            HIDDEN, the strip "tool"                     (NPC)
 *  9 the fight's loot rolls                                   HIDDEN, the left dock only, the strip "rolls" (NPC)
 * 10 LB's party picture                                       FULL 0, display only, the docks             (NPC)
 * 11 else = PLAY: the game, nothing of ours but the docks     HIDDEN
 *
 * Rows marked NPC are the NPC builder's to refine (CORE's defaults here).
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
    )

    /** what the top window does: [strip] = null, "tool" or "rolls" (NPC) */
    data class Look(val frame: Frame, val dim: Float, val touchable: Boolean, val root: Root, val docks: Boolean, val strip: String? = null) {
        /** the PLAY state: nothing of ours over the game */
        val play: Boolean get() = frame == Frame.HIDDEN && strip == null && root == Root.GONE && docks
    }

    const val DIM_PANEL = 0.55f
    const val DIM_QUICK = 0.25f
    const val HOME = "HOME"

    val PLAY = Look(Frame.HIDDEN, 0f, false, Root.GONE, docks = true)
    val COVERED = Look(Frame.HIDDEN, 0f, false, Root.GONE, docks = false)

    fun of(i: In): Look {
        val inRoom = i.room != HOME
        // pause / NPC over Home: Home's page shows only while it is up as a glance
        val under = if (inRoom) Root.ROOM else if (i.glanceHub || i.homeSub) Root.HOME else Root.GONE
        return when {
            i.launchCover -> COVERED                                                                          // 1
            i.startPages || i.report || i.npc || (i.dialog && !i.fightRolls) || i.spells ->
                Look(Frame.FULL, DIM_PANEL, true, under, docks = false)                                       // 2
            i.pause -> Look(Frame.FULL, DIM_PANEL, true, under, docks = false)                                // 3
            inRoom -> Look(Frame.FULL, DIM_PANEL, true, Root.ROOM, docks = false)                             // 4
            i.glanceHub || i.homeSub -> Look(Frame.FULL, DIM_PANEL, true, Root.HOME, docks = false)           // 5
            i.quick -> Look(Frame.FULL, DIM_QUICK, true, Root.GONE, docks = false)                            // 6 NPC
            i.classWheel -> Look(Frame.FULL, DIM_QUICK, true, Root.HOME_LAYERS, docks = false)                // 7 NPC
            i.tool >= 0 -> Look(Frame.HIDDEN, 0f, false, Root.GONE, docks = false, strip = "tool")            // 8 NPC
            i.fightRolls -> Look(Frame.HIDDEN, 0f, false, Root.GONE, docks = true, strip = "rolls")           // 9 NPC (the left dock only)
            i.lb -> Look(Frame.FULL, 0f, false, Root.GONE, docks = true)                                      // 10 NPC
            else -> PLAY                                                                                      // 11
        }
    }
}
