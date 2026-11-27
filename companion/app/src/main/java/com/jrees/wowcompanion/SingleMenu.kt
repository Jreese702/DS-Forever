package com.jrees.wowcompanion

/**
 * SINGLE SCREEN MODE'S DS MENU, MADE FOR THE TOP SCREEN (the user on test 1533, 2026-11-26: "When pressing down to open the ds menu in full
 * screen its like opening the ds menu bottom screen on the top screen ... I don't like that it is just popping the bottome screen up top for
 * that. I want it to be its own experience that feels like it was made for the top screen"). The design study's direction C, "the row
 * rises" (C:/dsf-work/design-single-menu/STUDY.md), with the user's picks:
 *  - Down (or the bottom-right swirl button) lifts the room row into a fan of the bottom screen's round icons round Home's swirl, the
 *    cursor ON the swirl ("start it on the spiral like in the ds veiw");
 *  - A on a room unfolds it upward from its disc into a wide 16:9 STAGE; B folds it back into its disc; a second B drops the row;
 *  - L / R: the room's tabs (the row: the cursor); ZL / ZR: the room before / after it on the row (not in the Controller room, whose pages
 *    read held ZL / ZR, nor on a Spellbook page that holds them);
 *  - "Ship in passes": a room with a 16:9 layout of its own ([WIDE]) is laid across the stage; every other room stands in the stage at the
 *    fallback size (its bottom-screen page, scaled to the stage's height);
 *  - in a fight (the user's pick 3: "I like 3 because it works like in dual screen mode, I just want to make sure that the home spiral has
 *    the red with the B to return to home so its easy to engage in combat. Also the edges of the screen should have the red flash"): rooms
 *    open in full; the swirl shows red with B, and B goes straight back to the game; the screen's edges flash red;
 *  - every open, close and room change moves between two real places on the screen ([Move], [ms]: 180-250 ms).
 * The defaults the study recommended: dim behind the menu (SingleLook.DIM_PANEL), no blur; the bar's own stand-in pictures for the rooms
 * without a round icon.
 *
 * Pure (no android.*): SingleMenuTest. The boxes are 1080-line px on a 1920-wide screen ([BASE_W] x [BASE_H]); another screen scales them
 * by h / 1080 about the centre.
 */
object SingleMenu {
    const val BASE_W = 1920
    const val BASE_H = 1080

    /** a box on the screen, px (left, top, right, bottom) */
    data class Box(val l: Float, val t: Float, val r: Float, val b: Float) {
        val w: Float get() = r - l
        val h: Float get() = b - t
        val cx: Float get() = (l + r) / 2f
        val cy: Float get() = (t + b) / 2f
        fun contains(x: Float, y: Float): Boolean = x >= l && x < r && y >= t && y < b
        fun inside(o: Box): Boolean = l >= o.l - 0.5f && t >= o.t - 0.5f && r <= o.r + 0.5f && b <= o.b + 0.5f
        fun hits(o: Box): Boolean = l < o.r && o.l < r && t < o.b && o.t < b
    }

    // ---------------------------------------------------------------- the stage (where a room stands)

    /** the stage's frame on the 1920 x 1080 screen (the study's 1848 x 772), its inside margin. No header band of its own: every room
     *  draws its own title row at its top (a stage title said it twice); a fight's "In a fight" plate sits on the frame's foot */
    val STAGE = Box(36f, 28f, 1884f, 800f)
    const val HEAD_H = 0f
    const val INSET = 12f
    /** a room's own page height on the bottom screen: the 1080 panel less the bar (Strip.HEIGHT 145) plus the track it overlaps
     *  (Strip.TRACKS 34), less the rooms' own bottom margin (TRACKS): 1080 - 111 - 34 */
    const val ROOM_H = 935
    /** the rooms column's laid height (the panel's, its bar kept in the layout but not drawn: every room keeps its 935) */
    const val COLUMN_H = 1080
    /** the bottom screen's width: the fallback's column, and a wide room's left column */
    const val PANEL_W = 1240

    /**
     * THE ROOMS WITH A 16:9 LAYOUT OF THEIR OWN. Pass 1: Bags - the grid on the left, the tooltip and its compare on the right, never
     * over the grid. Pass 2 (the user's order: "Character, then the Spellbook"): Character - its page, and beside it the next page (its
     * Reputation on the Character tab, the character's stats beside the others); the Spellbook - the open book, and a third page beside it
     * (the line's next page of spells). Every other room stands in the stage at the fallback size until its pass.
     */
    val WIDE: Set<Room> = setOf(Room.BAGS, Room.CHARACTER, Room.SPELLBOOK)
    fun wide(r: Room): Boolean = r in WIDE

    /** the screen [w] x [h] as the base sees it */
    private class Screen(w: Int, h: Int) {
        val k = if (h > 0) h / BASE_H.toFloat() else 1f
        val dx = (w - BASE_W * k) / 2f
        fun px(b: Box) = Box(b.l * k + dx, b.t * k, b.r * k + dx, b.b * k)
    }

    fun stage(w: Int = BASE_W, h: Int = BASE_H): Box = Screen(w, h).px(STAGE)

    /** inside the stage under its header: where a room's page stands */
    fun body(w: Int = BASE_W, h: Int = BASE_H): Box {
        val s = Screen(w, h)
        return s.px(Box(STAGE.l + INSET, STAGE.t + INSET + HEAD_H, STAGE.r - INSET, STAGE.b - INSET))
    }

    /**
     * WHERE THE ROOMS COLUMN STANDS for room [r] in the stage: laid [colW] x [COLUMN_H] (its own px, as on the bottom screen), drawn at [k]
     * from ([x], [y]), cut at [ROOM_H] (the bar under it is the row's place). A wide room ([wide]) is laid as wide as the body at that
     * scale; the fallback is the bottom screen's 1240, centred. The same [k] for both: every room's words read at one size.
     */
    data class Place(val colW: Int, val colH: Int, val k: Float, val x: Float, val y: Float, val wide: Boolean) {
        /** where it is drawn on the screen (its [ROOM_H] high page) */
        val drawn: Box get() = Box(x, y, x + colW * k, y + ROOM_H * k)
    }

    fun place(r: Room, w: Int = BASE_W, h: Int = BASE_H): Place {
        val b = body(w, h)
        val k = b.h / ROOM_H
        return if (wide(r)) {
            val cw = Math.round(b.w / k)
            Place(cw, COLUMN_H, k, b.l, b.t, true)
        } else Place(PANEL_W, COLUMN_H, k, b.l + (b.w - PANEL_W * k) / 2f, b.t, false)
    }

    // ---------------------------------------------------------------- the row (the fan of round icons round Home's swirl)

    /** Home's swirl: its centre and size; the rooms' discs (the lit one larger), their icons, the pitch, the space each side of the swirl,
     *  the arc's bend; the lit room's name under it; the band the row stands on */
    const val HUB_X = 960f
    const val HUB_Y = 968f
    const val HUB_D = 168f
    const val DISC_D = 96f
    const val DISC_ON = 112f
    const val ICON = 62f
    const val ICON_ON = 76f
    const val PITCH = 128f
    const val HUB_GAP = 40f
    const val ARC = 0.00011f
    const val NAME_DY = 70f
    const val BAND_TOP = 820f
    /** the hint row and the ZL / ZR neighbours' line */
    const val HINT_Y = 846f
    const val ZL_X = 70f

    /** the row's order, left to right: the bar's rooms ([bar], RoomBar's shown order) with Home's swirl in the middle (RoomBar.leftCount) */
    fun order(bar: List<Room>): List<Room> {
        val rooms = bar.filter { it != Room.HOME }
        val left = (rooms.size + 1) / 2
        return rooms.take(left) + Room.HOME + rooms.drop(left)
    }

    /** one disc of the row: its room, its centre, its plate's size */
    data class Disc(val room: Room, val cx: Float, val cy: Float, val d: Float) {
        val box: Box get() = Box(cx - d / 2f, cy - d / 2f, cx + d / 2f, cy + d / 2f)
    }

    /** the row's discs for [order] ([lit] = the cursor's / the open room's, drawn larger) on a [w] x [h] screen */
    fun discs(order: List<Room>, lit: Room? = null, w: Int = BASE_W, h: Int = BASE_H): List<Disc> {
        val s = Screen(w, h)
        val hub = order.indexOf(Room.HOME).coerceAtLeast(0)
        return order.mapIndexed { i, r ->
            if (r == Room.HOME) Disc(r, HUB_X * s.k + s.dx, HUB_Y * s.k, HUB_D * s.k)
            else {
                val n = i - hub
                val x = HUB_X + n * PITCH + (if (n > 0) HUB_GAP else -HUB_GAP)
                val y = HUB_Y + 8f - ARC * (x - HUB_X) * (x - HUB_X)
                Disc(r, x * s.k + s.dx, y * s.k, (if (r == lit) DISC_ON else DISC_D) * s.k)
            }
        }
    }

    /** where a disc comes from as the row rises: the docked row's own place when it was up ([dock]), else just under the screen's edge */
    fun riseFrom(d: Disc, dock: Box?, w: Int = BASE_W, h: Int = BASE_H): Pair<Float, Float> =
        if (dock != null) Pair(dock.l + (d.cx / w.coerceAtLeast(1)) * dock.w, dock.cy)
        else Pair(d.cx, h + d.d)

    /** the room before / after [r] on the row ([dir] -1 / +1), Home's swirl jumped over; null at an end (no wrap: the bar's cursor rule) */
    fun step(order: List<Room>, r: Room, dir: Int): Room? {
        val rooms = order.filter { it != Room.HOME }
        val i = rooms.indexOf(r)
        if (i < 0) return null
        return rooms.getOrNull(i + (if (dir < 0) -1 else 1))
    }

    // ---------------------------------------------------------------- the state machine

    /** what shows: nothing (the game), the row alone, a room on the stage over the row */
    enum class Phase { CLOSED, ROW, ROOM }

    fun phase(rowUp: Boolean, roomShown: Boolean): Phase = when {
        roomShown -> Phase.ROOM
        rowUp -> Phase.ROW
        else -> Phase.CLOSED
    }

    /** the motion between two states: each between two real places on the screen */
    enum class Move { NONE, RISE, DROP, UNFOLD, FOLD, SLIDE, RISE_UNFOLD, FOLD_DROP }

    /** the times (the user: "smooth transitions for everything"; the task: about 180-250 ms each); the fan's stagger is inside its time */
    const val RISE_MS = 200L
    const val DROP_MS = 180L
    const val UNFOLD_MS = 240L
    const val FOLD_MS = 200L
    const val SLIDE_MS = 220L
    const val STAGGER_MS = 12L
    /** the lit place's glide from disc to disc (the bar cursor's MOVE_MS) */
    const val CURSOR_MS = 140L
    /** a room change slides the pages this share of the stage's width (the old one out, the new one in, crossing) */
    const val SLIDE_SHARE = 0.12f
    /** an unfolding room's page shows from this share of the motion (the frame first, then its content) */
    const val CONTENT_FROM = 0.35f
    /** a fold's row hold: the bar's hold is asked once the room has folded (the game's own B lets the room's capture go first) */
    const val FOLD_HOLD_MS = FOLD_MS + 60L

    fun ms(m: Move): Long = when (m) {
        Move.NONE -> 0L
        Move.RISE -> RISE_MS
        Move.DROP -> DROP_MS
        Move.UNFOLD, Move.RISE_UNFOLD -> UNFOLD_MS
        Move.FOLD -> FOLD_MS
        Move.SLIDE -> SLIDE_MS
        Move.FOLD_DROP -> FOLD_MS + 20L
    }

    fun move(from: Phase, fromRoom: Room?, to: Phase, toRoom: Room?): Move = when (from) {
        Phase.CLOSED -> when (to) { Phase.CLOSED -> Move.NONE; Phase.ROW -> Move.RISE; Phase.ROOM -> Move.RISE_UNFOLD }
        Phase.ROW -> when (to) { Phase.CLOSED -> Move.DROP; Phase.ROW -> Move.NONE; Phase.ROOM -> Move.UNFOLD }
        Phase.ROOM -> when (to) {
            Phase.CLOSED -> Move.FOLD_DROP
            Phase.ROW -> Move.FOLD
            Phase.ROOM -> if (fromRoom != toRoom) Move.SLIDE else Move.NONE
        }
    }

    /** a room change's side: +1 = the new room comes in from the right (it is after the old one on the row) */
    fun slideDir(order: List<Room>, from: Room?, to: Room?): Int {
        val a = from?.let { order.indexOf(it) } ?: -1
        val b = to?.let { order.indexOf(it) } ?: -1
        return if (a < 0 || b < 0 || b >= a) 1 else -1
    }

    /**
     * the state machine the host follows (CompanionHost.singleMenuSync): one sync a look, [Move] the motion to play. The cursor is the bar
     * cursor's (HomeBarSelect: its D-pad, L / R, A, B), so the row's controls are the bottom screen's; [room] = the room on the stage
     */
    class Machine {
        var phase = Phase.CLOSED; private set
        var room: Room? = null; private set
        /** the last motion, for the log and the tests */
        var last = Move.NONE; private set

        fun sync(rowUp: Boolean, shown: Room?): Move {
            val to = phase(rowUp, shown != null)
            val m = move(phase, room, to, shown)
            phase = to; room = shown
            if (m != Move.NONE) last = m
            return m
        }
    }

    // ---------------------------------------------------------------- the controls

    /** where B at a room's top level goes: back into the row (its disc), or straight to the game (a fight: the user's pick 3, "the red
     *  with the B to return to home so its easy to engage in combat"; a room not on the row: nothing to fold into) */
    enum class Back { ROW, GAME }

    fun back(fight: Boolean, r: Room, order: List<Room>): Back = if (!fight && r != Room.HOME && r in order) Back.ROW else Back.GAME

    /** what A on the row's cursor opens: the room, or on the swirl the Home stage (Home's page as a glance in the stage) */
    enum class Go { ROOM, HOME_STAGE }

    fun a(cursor: Room): Go = if (cursor == Room.HOME) Go.HOME_STAGE else Go.ROOM

    /** the row's hint says "L R Tabs" for a room whose L / R step tabs (not Bags, the Map's zoom, the Calendar, Home's stage) */
    fun tabsHint(r: Room): Boolean = r !in setOf(Room.HOME, Room.BAGS, Room.MAP, Room.CALENDAR)

    /**
     * ZL / ZR step to the next room from inside [r] (the user's default "ZL/ZR move to the next or previous room from inside a room"),
     * except where a page reads them HELD: the Controller room (its bar-binding pages show that set while ZL / ZR are held), and the
     * Spellbook while one of its own pages takes the shoulders ([bookHoldsTriggers]); never without the room holding the controller
     * ([held]: the press is ours, not the game's)
     */
    fun triggersStep(r: Room, held: Boolean, bookHoldsTriggers: Boolean): Boolean =
        held && r != Room.HOME && r != Room.CONTROLLER && !(r == Room.SPELLBOOK && bookHoldsTriggers)

    /**
     * ZL / ZR HELD BACK FROM THE GAME (the 1536 pass 2, the user: "HOLD THEM BACK from the game while a room is open, so they only switch
     * rooms, the way L / R only switch tabs ... Give them back the moment the room closes"): a room on the stage ([phase] ROOM) whose
     * triggers step rooms ([triggersStep]) with nothing of ours over it ([covered]: a game window, a wheel, the pause wheel). The ONE
     * decision: the launcher holds the triggers back exactly while it is true, and the room's reader steps rooms exactly then
     */
    fun triggersHeld(phase: Phase, r: Room, held: Boolean, bookHoldsTriggers: Boolean, covered: Boolean): Boolean =
        phase == Phase.ROOM && !covered && triggersStep(r, held, bookHoldsTriggers)

    /** the swirl's look: true = red with B (a fight: B goes straight back to the game), false = its blue at rest (a room open out of a
     *  fight: B folds into the row), null = the bar cursor's own rule (the row out of a fight: as on the bottom screen) */
    fun swirlRed(fight: Boolean, phase: Phase): Boolean? = when {
        fight && phase != Phase.CLOSED -> true
        phase == Phase.ROOM -> false
        else -> null
    }

    /** the screen's edges flash red while the menu shows in a fight */
    fun edgeFlash(fight: Boolean, phase: Phase): Boolean = fight && phase != Phase.CLOSED

    /** the flash's pulse: [PULSE_MS] a beat, its alpha between [EDGE_MIN] and [EDGE_MAX]; [EDGE_W] px deep at the 1080 line */
    const val PULSE_MS = 900L
    const val EDGE_MIN = 0.30f
    const val EDGE_MAX = 0.85f
    const val EDGE_W = 46f

    fun edgeAlpha(tMs: Long): Float {
        val ph = (tMs % PULSE_MS).toFloat() / PULSE_MS
        val k = 0.5f - 0.5f * Math.cos(2.0 * Math.PI * ph).toFloat()
        return EDGE_MIN + (EDGE_MAX - EDGE_MIN) * k
    }

    // ---------------------------------------------------------------- the motions' places

    /** a box between [a] and [b] at [k] (0..1) */
    fun lerp(a: Box, b: Box, k: Float): Box {
        val t = k.coerceIn(0f, 1f)
        return Box(a.l + (b.l - a.l) * t, a.t + (b.t - a.t) * t, a.r + (b.r - a.r) * t, a.b + (b.b - a.b) * t)
    }

    /** a room's page folded into its disc: the page's shape at the disc's width x [FOLD_W], centred on the disc */
    const val FOLD_W = 1.6f
    fun folded(d: Disc, page: Box): Box {
        val w = d.d * FOLD_W
        val h = w * page.h / page.w
        return Box(d.cx - w / 2f, d.cy - h / 2f, d.cx + w / 2f, d.cy + h / 2f)
    }

    /** the fan's share for disc [i] of [n] (its distance from the swirl staggers it) at [tMs] of a [totalMs] rise */
    fun fanK(i: Int, hub: Int, tMs: Long, totalMs: Long): Float {
        val far = kotlin.math.abs(i - hub)
        val most = 6
        val own = (totalMs - most * STAGGER_MS).coerceAtLeast(1L)
        return ((tMs - far * STAGGER_MS).toFloat() / own).coerceIn(0f, 1f)
    }
}
