package com.jrees.wowcompanion

/**
 * SINGLE SCREEN MODE, THE NPC PART (SingleScreen; the user's picks 2026-11-25: "Ours over the game" for the NPC windows - vendors, quest
 * givers, trainers, mail, bank - "Centred panel" for the rooms, "Straight to the game" for B). What the host decides for everything that is
 * not a room nor the play docks, pure (no android.*: the JVM tests run it):
 *
 *  - THE STRIP WINDOW (SingleShell.sub([STRIP])): the one small window over the game for what must leave the rest of the top screen to the
 *    game (SingleLook rows 8-10): the Keyboard tool as a strip along the screen's foot ([keysRect], the game's text box above it), the Track
 *    Pad as a pad in the bottom-right corner ([padRect], fingers on the rest of the screen are the game's), the fight's small roll panel in
 *    the right dock's place ([rollsRect]), LB's party picture over the left dock's party place ([lbRect], display only); and the waiting
 *    page's row at the screen's top edge (row 2b, [startRect]: the user's ruling "Top of the screen").
 *  - WHERE OUR OWN ACTIVITIES OPEN (the add-on import, the frame-gen picker, the microphone question; [bottomRoute]): on the other screen
 *    of a two-screen device (Android's launcher is the bottom screen there: the top window stays), on display 0 on a one-screen device.
 *  - A TAP ON THE DIM ([dimTap]): a quick wheel closes; an NPC window, a dialog, the class wheel keep it (nothing happens).
 *  - Settings' tiles the one-screen preset sets ([setByOne]: "Set by One screen" on the tile; the saved choice is what the tile shows).
 *
 * Every size is the Thor's top screen (1920 x 1080); a smaller screen scales by its lines (h / 1080), the box by SingleScreen.panelBox.
 */
object SingleNpc {
    /** the strip window's sub id (SingleShell.sub) */
    const val STRIP = "strip"
    /** SingleLook.Look.strip's words */
    const val TOOL = "tool"
    const val ROLLS = "rolls"
    const val LB = "lb"
    /** the waiting page's row with the game screen up (SingleLook row 2b; the review 2026-11-25) */
    const val START = "start"

    /** the screen's edge kept free (the docks' own margin) */
    const val EDGE = 16
    /** the type tool's head in the strip: its two tabs (and the Keyboard's typed line beside them) */
    const val TOOL_HEAD = 72
    /** the Track Pad's corner pad: 600 x 360 at the bottom-right corner, (1304, 704)-(1904, 1064) on 1920 x 1080 */
    const val PAD_W = 600
    const val PAD_H = 360
    /** the fight's small roll panel: the right dock's out-of-combat place (PLAY's map dock), 320 x 320 - the panel is 300 wide at least */
    const val ROLLS_W = 320
    const val ROLLS_H = 320
    /** LB's picture over the left dock: its columns (16..556), from y 220 out of a fight; in one from y 96 (above the Wings' worst reach,
     *  y 550) and only to x 520 (the add-on's compass and target bar start at x 530 down to y 210) */
    const val LB_L = 16
    const val LB_R = 556
    const val LB_R_IN = 520
    const val LB_TOP_OUT = 220
    const val LB_TOP_IN = 96
    /** the picture's own frame: LbPartyView's bars (300 wide, the Left + Right row 2 x (300 + 8)) and its height (462), at scale 1 */
    const val LB_ROW_W = 616f
    const val LB_PICT_H = 462f
    /** the plate's inner margin round the picture */
    const val LB_PAD = 12

    private fun k(h: Int): Float = h / 1080f
    private fun px(v: Int, k: Float): Int = Math.round(v * k)

    /** the Keyboard strip: the centred box's width, at the screen's foot; [kbH] = the keyboard's own height at the box's scale */
    fun keysRect(w: Int, h: Int, kbH: Int): IntArray {
        val box = SingleScreen.panelBox(w, h)
        val bk = (box[2] - box[0]) / SingleScreen.PANEL_W.toFloat()
        val hh = (Math.round(TOOL_HEAD * bk) + kbH).coerceAtMost(h)
        return intArrayOf(box[0], h - hh, box[2], h)
    }

    /** the box's scale (1 on the Thor): the strip's keyboard is drawn at it (ChatKeyboard.scaleOverride: its window is not the screen) */
    fun boxScale(w: Int, h: Int): Float { val b = SingleScreen.panelBox(w, h); return (b[2] - b[0]) / SingleScreen.PANEL_W.toFloat() }

    /** the Track Pad's corner */
    fun padRect(w: Int, h: Int): IntArray {
        val k = k(h)
        val r = w - px(EDGE, k); val b = h - px(EDGE, k)
        return intArrayOf(r - px(PAD_W, k), b - px(PAD_H, k), r, b)
    }

    /** the fight's small roll panel: the right dock's place, top right */
    fun rollsRect(w: Int, h: Int): IntArray {
        val k = k(h)
        val r = w - px(EDGE, k); val t = px(EDGE, k)
        return intArrayOf(r - px(ROLLS_W, k), t, r, t + px(ROLLS_H, k))
    }

    /** the LB picture's scale inside the left dock's columns (never above 1) */
    fun lbScale(h: Int, fight: Boolean): Float {
        val k = k(h)
        val inner = ((if (fight) LB_R_IN else LB_R) - LB_L - 2 * LB_PAD) * k
        return minOf(k, inner / LB_ROW_W)
    }

    /** LB's party picture over the left dock's party place: out of a fight from y 220, in a fight from y 96 */
    fun lbRect(w: Int, h: Int, fight: Boolean): IntArray {
        val k = k(h)
        val s = lbScale(h, fight)
        val l = px(LB_L, k); val r = minOf(w, px(if (fight) LB_R_IN else LB_R, k))
        val t = px(if (fight) LB_TOP_IN else LB_TOP_OUT, k)
        val hh = Math.round(LB_PICT_H * s) + 2 * px(LB_PAD, k)
        return intArrayOf(l, t, r, t + hh)
    }

    /** the picture's place inside the strip window's picture [rw] wide (LbFit.Place's centre x, first bar's top, scale): the scale that
     *  fits [rw] (the window is [lbRect]'s, the plate's side margins off) */
    fun lbFit(rw: Int, h: Int): FloatArray {
        val s = minOf(k(h), rw / LB_ROW_W)
        return floatArrayOf(rw / 2f, LB_PAD * k(h), s)
    }

    /**
     * THE WAITING PAGE'S ROW over the game (SingleLook row 2b). THE USER'S RULING (2026-11-25, after build 1533), verbatim: "Top of the
     * screen (Recommended): Keeps character select's Enter World and the bottom of the login screen free for touch." The centred box's
     * width (the 1240 panel's: x 340..1580 on the Thor) from the top edge down, [need] high ([startNeed]: only what the row shows now,
     * so no empty strip over the game takes its touches; the window is touch-only on this rect, the rest of the screen is the game's).
     * StartView draws its row from its own top in its own px (the line's row [START_TOP] down, the sign-in row under it, the region
     * line under those: 334 px at most), so the height is never scaled down - only kept inside the screen. The game's own pieces the
     * code knows of stay clear: Enter World and the login screen's buttons at the foot, a loading screen's bar at the foot (a reload,
     * "Loading your character"); the box's column keeps off the screen's corners (the login screen's logo at the top left)
     */
    fun startRect(w: Int, h: Int, need: Int): IntArray {
        val box = SingleScreen.panelBox(w, h)
        return intArrayOf(box[0], 0, box[2], need.coerceIn(0, h))
    }
    /** the top edge kept free over the line's row (StartView bare: its row from here down) */
    const val START_TOP = 24
    /** under the row's lowest piece: the plates' metal and the outlined words' dark edge */
    const val START_FOOT = 16

    /**
     * the strip's height for what the row shows: the line's row (Menu | line | Keyboard) from [START_TOP]; [second] = the sign-in row
     * (or the add-ons' How in its place) under it; [region] = the region line under those. StartView's own px (its consts are inlined:
     * no android class at run time)
     */
    fun startNeed(second: Boolean, region: Boolean): Int {
        var y = START_TOP + StartView.BUTTON_H
        if (second) y += StartView.GAP + StartView.BUTTON_H
        if (region) y += StartView.REGION_GAP + StartView.REGION_SIZE
        return Math.ceil((y + START_FOOT).toDouble()).toInt()
    }

    /** the type tool's strip for its tab ([tab] 0 = the Track Pad, 1 = the Keyboard) */
    fun toolRect(tab: Int, w: Int, h: Int, kbH: Int): IntArray = if (tab == 1) keysRect(w, h, kbH) else padRect(w, h)

    // ---- our own activities (CompanionHost.openOnBottom)

    enum class Bottom {
        /** two screens: the old way (the activity on the bottom window's own display, that window hidden until it closes) */
        TWO_SCREENS,
        /** single screen mode on a two-screen device: the activity on the other screen (Android's launcher there), the top window stays,
         *  the controller's keys not taken back meanwhile (SingleScreen.keysPaused) and the plate "On the bottom screen" */
        OTHER,
        /** single screen mode on a one-screen device: on display 0 (the launcher pauses: the top window goes and comes back with it) */
        HERE,
    }

    fun bottomRoute(single: Boolean, other: Int?): Bottom = when {
        !single -> Bottom.TWO_SCREENS
        other != null && other != 0 -> Bottom.OTHER
        else -> Bottom.HERE
    }

    const val ON_BOTTOM = "On the bottom screen"
    /** that plate's time over the game */
    const val ON_BOTTOM_MS = 2_500L

    // ---- a tap on the dim beside the panel (TopWindow's dim -> CompanionHost.singleDimTap -> singleNpcDim)

    enum class Dim {
        /** not NPC's: CORE's own rule (a room -> the game, Home's glance off) */
        CORE,
        /** a quick wheel (the consumables / spell radials, Smart Y's OR, the target radial): it closes */
        CLOSE_QUICK,
        /** an NPC window, a game dialog, the class wheel, start pages, the report, the Bind box: the tap does nothing */
        KEEP,
    }

    fun dimTap(i: SingleLook.In): Dim = when {
        i.startPages || i.report || i.npc || (i.dialog && !i.fightRolls) || i.spells || i.waiting -> Dim.KEEP
        i.pause -> Dim.CORE                                      // the wheel reads its own (a tap beside it closes it)
        i.room != SingleLook.HOME -> Dim.CORE
        i.glanceHub || i.homeSub -> Dim.CORE
        i.quick -> Dim.CLOSE_QUICK
        i.classWheel -> Dim.KEEP
        else -> Dim.CORE
    }

    // ---- the fight's rolls (DialogView): small (in a fight: the panel over the minimap; out of one: the folded pill) = the strip

    /** the rolls show small with no pop-up as the box ([covers] false): the strip at the right dock's place, not the panel */
    fun rollsSmall(showing: Boolean, covers: Boolean, compact: Boolean, pill: Boolean): Boolean = showing && !covers && (compact || pill)

    // ---- Settings (GameUiSection's tiles)

    /** a Game UI tile whose element the one-screen preset decides (SingleUi): the tile keeps the SAVED choice, with "Set by One screen" */
    fun setByOne(id: String): Boolean =
        if (id == "castbar") SingleUi.castBarHide(true) != true || SingleUi.castBarHide(false) != false
        else SingleUi.mode(id, UiMode.ON) != UiMode.ON || SingleUi.mode(id, UiMode.OFF) != UiMode.OFF

    const val SET_BY_ONE = "Set by One screen"
}
