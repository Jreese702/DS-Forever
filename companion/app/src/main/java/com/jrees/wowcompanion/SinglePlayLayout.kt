package com.jrees.wowcompanion

/**
 * SINGLE SCREEN MODE WHILE PLAYING: WHERE THE PLAY DOCKS STAND (SingleScreen; the user's pick for "While playing", 2026-11-25: "1, 2, and
 * also the chat on the left thr ds toolbar should have the same functionality in single screen mode. We can shift those panels ine and
 * out of combat to all fit better"). Over the running game, in the PLAY state (SingleLook: nothing of ours but these), Home's own views
 * stand in small windows of their own: the live map (its trail, the Polestar arrows) top right, Party mode's list and the chat on the
 * left - one arrangement OUT of combat, a tighter one IN combat (HomeView.fightShown: the takeover or Party mode's fight, the same word
 * that slides the Wings up), so nothing covers what the game and our add-ons draw.
 *
 * THE KEEP-OUTS (1080-line px on a 1920-wide screen, read off the add-ons' own layout code):
 *  K1 Trail's compass (SPEC w 860, h 76, top 4) and Hub's target bar under it           (530, 0, 1390, 210)
 *  K2 the HUD block (Trail P.lay.base: the bars' top at 540 - y, W x s), our top cast bar over it (nbar18's room), Party mode's rows
 *     under it (Hub PF COL_W 300): at the default HUD (640, 780, 1280, 1080), moving and growing with the live HUD (the hub's prd y/w/s)
 *  K3 the Wings AT REST out of fights (ActionBar nbar28; the 1535 polish - no Nintendo bar in single mode)   (290, 760, 1630, 966)
 *  K4 the Wings in fights, their worst crowded envelope (ActionBar NB.TOP: RAISE_MAX 300, PUSH_MAX 228)       (152, 550, 1768, 1080)
 *  K5 our party frames while Party mode is OFF (Hub PF.X 24, PF.Y 250, 4 x 64)                                (24, 250, 324, 506)
 * The rule: no dock piece meets one of them (OUT: K1 K2 K3 K5; IN: K1 K2 K4 K5); a HUD moved or grown shrinks the chat (its lines
 * first, then its width) and then the map (to 240) rather than overlap. A smaller (or wider) screen: every box x h / 1080, the left
 * pieces from the left edge, the map from the right one, the keep-outs from the centre.
 *
 * THE SHIFT between the two ([steps]): each piece's window grows to the union of where it is and where it goes (ONE window update),
 * the piece glides inside it (Motion's COMBAT_GROW 240 ms into the fight, COMBAT_SHRINK 200 ms out of it), the window shrinks to the
 * new box (ONE update) - two window updates a piece a fight edge, none per frame.
 *
 * THE 1535 POLISH (the user on test 1533, 2026-11-26): "The chat can fit in the bottom right I think" - the chat stands on the RIGHT,
 * under the map and the focused-quest card, its foot just over the Wings' resting reach; "The normal quest list should stay hidden and
 * instead it should show our focused quest box like it does on the bottom screen" - Home's own quest card ([Piece.QUEST], lent as the
 * map is) under the map (folded to its title row in a fight); "We need to hide the top screen xp bar so that we can recreate our home
 * screen row buttons. These hide in combat and out of combat it just shifts up the controller buttons a bit and moves them closer
 * together and makes them smaller when the Healthbars hide and slides up the home row from the bottom" - the room row ([Piece.ROW], the
 * host's own Strip lent at [ROW_K]) at the bottom centre OUT of combat only (and not in Party mode: Hub's rows), sliding up from under the screen's edge as the fight ends
 * and back down as one starts ([steps]: [SLIDES]); our Nintendo bar is gone (SingleUi: the controller UI off), so K3 is now the WINGS AT
 * REST (ActionBar nbar28: the fight spot x [WINGS_REST_K], [WINGS_REST_IN] closer, [WINGS_REST_UP] up, never under the row's top).
 *
 * Pure (no android.*): SinglePlayLayoutTest.
 */
object SinglePlayLayout {
    /** a box on the top screen, px (left, top, right, bottom) */
    data class Box(val l: Int, val t: Int, val r: Int, val b: Int) {
        val w: Int get() = r - l
        val h: Int get() = b - t
        val area: Long get() = w.toLong() * h
        /** the two meet ([gap] px apart counts as meeting) */
        fun hits(o: Box, gap: Int = 0): Boolean = l < o.r + gap && o.l - gap < r && t < o.b + gap && o.t - gap < b
        fun union(o: Box): Box = Box(minOf(l, o.l), minOf(t, o.t), maxOf(r, o.r), maxOf(b, o.b))
        fun shift(dx: Int, dy: Int): Box = Box(l + dx, t + dy, r + dx, b + dy)
        fun inside(w: Int, h: Int): Boolean = l >= 0 && t >= 0 && r <= w && b <= h
    }

    /** out of combat / in combat */
    enum class Arr { OUT, IN }

    /** the live HUD (the hub document's prd: y px under the centre, the width px, the scale %) */
    data class Hud(val y: Int = Y_DEF, val w: Int = W_DEF, val s: Int = S_DEF)
    const val Y_DEF = -350
    const val W_DEF = 280
    const val S_DEF = 100

    /** the screen the boxes are written for */
    const val BASE_W = 1920
    const val BASE_H = 1080
    /** between a piece and a keep-out */
    const val GAP = SingleType.GAP
    /** the chat keeps at least this (3 one-line messages) and this wide; the map's in-combat size */
    const val CHAT_MIN_H = 132
    const val CHAT_MIN_W = 300
    /** test 1536: the left chat keeps at least one line (a HUD grown far over Party mode's list); two to seven as laid by default */
    const val CHAT_LEFT_MIN_H = 64
    const val MAP_OUT = 320
    const val MAP_IN = 240
    /** one chat line: Home's chat pane's 27 px text x 1.12 and its 6 px between messages; the pane's padding */
    const val LINE_H = 36
    const val PANE_PAD = 14
    const val MSG_GAP = 6
    /** hold to talk's pop-up (CastView.TALK_PANEL_H 360 + its margins), its width and the least it keeps */
    const val TALK_H = 380
    const val TALK_W = 540
    const val TALK_MIN_W = 360
    /** Party mode's list at least this tall (two rows) and this wide (the tab and one plate a row) */
    const val PARTY_MIN_H = 140
    const val PARTY_MIN_W = 320
    /** every dock window together at most this many px (about a quarter of the 1920 x 1080 top screen: the compositor's cost; the 1535
     *  polish added the quest card, the room row, the corner buttons and the tap-to-cast bar, each small) */
    const val AREA_MAX = 660_000L
    /** the glide's times (Motion.COMBAT_GROW / COMBAT_SHRINK: SinglePlayLayoutTest keeps them equal) */
    const val GROW_MS = 240L
    const val SHRINK_MS = 200L

    /** the pieces, each in its own small window (SingleShell.sub ids) */
    enum class Piece(val sub: String) {
        MAP("dockR"), PARTY("dockP"), CHAT("dockL"), TALK("dockT"), QUEST("dockQ"), ROW("dockH"), CAST("dockC"), BTN_L("dockBL"), BTN_R("dockBR")
    }

    /** the pieces that SLIDE instead of fading where they stand: the room row up from under the bottom edge, the tap-to-cast bar in
     *  from the left edge (each back out the same way: [away]) */
    val SLIDES = setOf(Piece.ROW, Piece.CAST)

    // ---- the corner buttons (the 1535 polish, the user: "I was thinking maybe we could add some touch buttons in the bottome left and
    // right. In the bottom left it should slide open our touch to cast menu and then the bottom right we could have our spiral button and
    // slide open the home menu bar when touched"): two small round buttons in the bottom corners, out of every keep-out (left of the
    // Wings' widest reach K4 x 152, right of its x 1768); the tap-to-cast bar (Home's own CastStripView, lent) opens over the left one
    /** a corner button's size and its margin from the screen's edges (1080-line px) */
    const val BTN = 72
    /** the tidy pass (SingleType's grid): [SingleType.EDGE] from the side edges as every dock, the foot on the row's line ([ROW_FOOT]) */
    const val BTN_MARGIN = SingleType.EDGE
    const val BTN_FOOT = SingleType.FOOT
    /** the tap-to-cast bar's box over the left button: its width (Home's LEFT bar: four slots in view, a scroll), its foot */
    const val CAST_W = 560
    const val CAST_FOOT = 966
    const val CAST_H_DEF = 120

    // ---- the 1535 polish
    /** the focused-quest card: the map dock's own box under the map (test 1536); a tall card's height asked in the tests */
    const val QUEST_MAX_H = 260
    /** the room row: the host's Strip (Strip.HEIGHT 145, laid 1240 wide as in the rooms column) at [ROW_K], centred, [ROW_FOOT] over
     *  the bottom edge */
    const val ROW_SRC_W = 1240
    const val ROW_SRC_H = 145
    const val ROW_K = 0.7f
    const val ROW_FOOT = SingleType.FOOT
    /** the docks' grounds over the game (the user: "The party mode box and text box bot have solid backgrounds and it needs to be way
     *  more see through so its not as intrusive"): the wells' fill, their metal edge, a party plate's ground (Home: 255 / 255 / 230) */
    const val GROUND_ALPHA = 56
    const val EDGE_ALPHA = 150
    const val PLATE_ALPHA = 96
    /** the focused-quest card's plate in its dock (Home's card over the map: UiKit.CARD_OVER_MAP_ALPHA 140) */
    const val CARD_ALPHA = 110
    /** ActionBar nbar28's Wings at rest (the add-on's NB.TOP.REST_*: test_wings_rest.py keeps them equal to the file's) */
    const val WINGS_REST_K = 0.8f
    const val WINGS_REST_IN = 60
    const val WINGS_REST_UP = 42

    data class KeepOut(val name: String, val box: Box)

    /** one arrangement: [party] null without Party mode; [talk] where hold to talk's pop-up stands while it is up; [quest] the focused
     *  quest's card (null: no focused quest); [row] the room row (out of a fight only) */
    data class Plan(val arr: Arr, val map: Box, val party: Box?, val chat: Box, val talk: Box, val quest: Box? = null, val row: Box? = null,
                    val cast: Box? = null, val btnL: Box? = null, val btnR: Box? = null) {
        /** the chat lines that fit the chat's box */
        val chatLines: Int get() = maxOf(1, (chat.h - 2 * PANE_PAD + MSG_GAP) / LINE_H)
        fun box(p: Piece): Box? = when (p) {
            Piece.MAP -> map; Piece.PARTY -> party; Piece.CHAT -> chat; Piece.TALK -> talk; Piece.QUEST -> quest; Piece.ROW -> row
            Piece.CAST -> cast; Piece.BTN_L -> btnL; Piece.BTN_R -> btnR
        }
        /** the pieces that stand while playing (the talk pop-up only while it is up) */
        fun boxes(): Map<Piece, Box?> = mapOf(Piece.MAP to map, Piece.PARTY to party, Piece.CHAT to chat, Piece.QUEST to quest, Piece.ROW to row,
            Piece.CAST to cast, Piece.BTN_L to btnL, Piece.BTN_R to btnR)
    }

    /** the arrangement Home's fight word asks for */
    fun arr(fightShown: Boolean): Arr = if (fightShown) Arr.IN else Arr.OUT

    // ---------------------------------------------------------------- the base boxes (1920 x 1080)

    private enum class Anchor { LEFT, CENTRE, RIGHT }

    private val MAP_OUT_BOX = Box(1584, 16, 1904, 336)
    private val MAP_IN_BOX = Box(1664, 16, 1904, 256)
    /** Party mode's list: 440 wide (the paired rows' names whole), at most 320 tall (its own height: [plan]'s partyH) */
    private val PARTY_OUT = Box(16, 220, 456, 540)
    private val PARTY_IN = Box(16, 88, 456, 408)
    /** the 1535 polish: the chat on the RIGHT, its foot over the Wings at rest (K3) out of a fight / over their widest reach (K4) in
     *  one; its top under the quest card (else this tall) */
    private val CHAT_OUT = Box(16, 220, 456, 752)
    private val CHAT_IN = Box(16, 88, 456, 542)
    /** test 1536: the chat's place when the left column has no room under the party frames (top left, left of the compass K1) */
    private val CHAT_TOP = Box(16, 16, 456, 242)

    val K1 = Box(530, 0, 1390, 210)
    /** the 1535 polish: our Wings AT REST out of fights (no Nintendo bar in single screen mode any more) */
    val K3 = Box(290, 776, 1630, 966)
    val K4 = Box(152, 550, 1768, 1080)
    val K5 = Box(24, 250, 324, 506)

    /** K2 for [hud]: the bars' top at 540 - y, our cast bar's room (110 px x s) over it, at least 640 wide (the cast bar, the pips);
     *  [partyMode]: Hub's rows under the HUD reach further right (two 300 px columns and the Select tab: to x 1380 at the default HUD) */
    fun k2(hud: Hud, partyMode: Boolean = false): Box {
        val s = hud.s.coerceIn(10, 400) / 100f
        val top = (540 - hud.y) - Math.round(110 * s)
        val half = maxOf(320, Math.round(hud.w.coerceAtLeast(0) * s / 2f + 180f))
        val right = if (partyMode) maxOf(960 + half, PARTY_ROWS_R) else 960 + half
        return Box(960 - half, top.coerceIn(0, BASE_H), right, BASE_H)
    }
    /** Party mode's rows under the HUD: their right edge (Hub PF COL_W 300 x 2 + the tab, from the HUD's left) */
    const val PARTY_ROWS_R = 1380

    // ---------------------------------------------------------------- the screen

    /** the screen [w] x [h] as the base sees it: the scale (h / 1080) and the virtual width */
    private class Screen(w: Int, h: Int) {
        val k = if (h > 0) h / BASE_H.toFloat() else 1f
        val vw = if (k > 0f) Math.round(w / k) else BASE_W
        fun place(b: Box, a: Anchor): Box {
            val dx = when (a) { Anchor.LEFT -> 0; Anchor.CENTRE -> (vw - BASE_W) / 2; Anchor.RIGHT -> vw - BASE_W }
            return b.shift(dx, 0)
        }
        fun px(b: Box): Box = Box(Math.round(b.l * k), Math.round(b.t * k), Math.round(b.r * k), Math.round(b.b * k))
    }

    /** the keep-outs for [arr] on a [w] x [h] screen, px */
    fun keepOuts(arr: Arr, partyMode: Boolean, hud: Hud = Hud(), w: Int = BASE_W, h: Int = BASE_H): List<KeepOut> {
        val sc = Screen(w, h)
        return virtualKeeps(sc, arr, partyMode, hud).map { KeepOut(it.name, sc.px(it.box)) }
    }

    private fun virtualKeeps(sc: Screen, arr: Arr, partyMode: Boolean, hud: Hud): List<KeepOut> {
        val out = ArrayList<KeepOut>(5)
        out += KeepOut("K1 compass + target bar", sc.place(K1, Anchor.CENTRE))
        out += KeepOut("K2 HUD block", sc.place(k2(hud, partyMode), Anchor.CENTRE))
        if (arr == Arr.OUT) out += KeepOut("K3 Wings at rest", sc.place(K3, Anchor.CENTRE))
        else out += KeepOut("K4 Wings", sc.place(K4, Anchor.CENTRE))
        if (!partyMode) out += KeepOut("K5 party frames", sc.place(K5, Anchor.LEFT))
        return out
    }

    /**
     * THE ARRANGEMENT for [arr] on a [w] x [h] screen: [partyMode] = Party mode's list shows (PartyModel: on and routing), [hud] the live
     * HUD, [partyH] the list's own height, [questH] the focused quest's card's (null = no focused quest: no card). The map top right, the
     * quest card under it, the chat under that on the right (the 1535 polish); the party list on the left; the room row at the bottom
     * centre out of a fight. The chat shrinks (lines, then width) and the map shrinks (to 240) to keep clear of every keep-out; the talk
     * pop-up over the party list (or over the chat).
     */
    fun plan(arr: Arr, partyMode: Boolean, hud: Hud = Hud(), w: Int = BASE_W, h: Int = BASE_H, partyH: Int? = null, questH: Int? = null,
             castH: Int? = null, rowAsk: Boolean? = null, castBtn: Boolean = true): Plan {
        val sc = Screen(w, h)
        val keeps = virtualKeeps(sc, arr, partyMode, hud).map { it.box }
        var map = sc.place(if (arr == Arr.OUT) MAP_OUT_BOX else MAP_IN_BOX, Anchor.RIGHT)
        if (keeps.any { map.hits(it, GAP) }) map = Box(map.r - MAP_IN, map.t, map.r, map.t + MAP_IN)
        var party = if (!partyMode) null else sc.place(if (arr == Arr.OUT) PARTY_OUT else PARTY_IN, Anchor.LEFT).let { b ->
            // the window hugs the list as drawn (its rows: you, the tank, the healer, the paired damage), so no empty part of it takes a
            // finger meant for the game
            fitParty(partyH?.let { ph -> b.copy(b = b.t + ph.coerceIn(PARTY_MIN_H, b.h)) } ?: b, keeps)
        }
        // test 1536 (the user: "I meant the left side not the right for chat its way too congested"): the chat on the LEFT, under the
        // party list (Party mode) or our party frames (K5), its foot over the Wings (K3 / K4) and the corner's tap-to-cast bar; no room
        // there (a fight without Party mode: K5 reaches K4) = over the party frames, top left
        val quest = questH?.let { fitRight(Box(map.l, map.b + GAP, map.r, map.b + GAP + map.h), keeps, map.w, CHAT_MIN_H) }
        val chatStart = sc.place(if (arr == Arr.OUT) CHAT_OUT else CHAT_IN, Anchor.LEFT)
        var chat = fitLeft(chatStart, party, keeps, sc.place(CHAT_TOP, Anchor.LEFT))
        // a HUD grown far up over Party mode's list: the list gives up its foot (down to two rows) so the chat keeps a line
        party?.let { pt -> if (chat.h < CHAT_LEFT_MIN_H) {
            party = pt.copy(b = maxOf(pt.t + PARTY_MIN_H, chat.b - CHAT_LEFT_MIN_H - GAP))
            chat = fitLeft(chatStart, party, keeps, sc.place(CHAT_TOP, Anchor.LEFT))
        } }
        // hold to talk's pop-up over the party list (or over the chat: both on the left now): narrower first, then up
        val under = party ?: chat
        var talk = Box(under.l, under.t, under.l + TALK_W, under.t + TALK_H)
        for (k in keeps) if (talk.hits(k, GAP)) {
            val nr = k.l - GAP
            if (nr - talk.l >= TALK_MIN_W && k.l > talk.l) talk = talk.copy(r = minOf(talk.r, nr))
            if (talk.hits(k, GAP) && k.t > talk.t) talk = talk.shift(0, (k.t - GAP) - talk.b)
        }
        if (talk.t < 0) talk = talk.shift(0, -talk.t)
        // the room row: out of a fight, centred at the bottom (the HUD's place - the bars hide out of combat - under the Wings at rest);
        // not while Party mode is on (Hub's party rows stand under the HUD there out of combat too: the open question's default)
        // ... and the swirl button's ask ([rowAsk]: true = up on demand - in a fight, in Party mode too - false = down) over the rule
        val row = if (rowAsk ?: (arr == Arr.OUT && !partyMode)) {
            val rw = Math.round(ROW_SRC_W * ROW_K); val rh = Math.round(ROW_SRC_H * ROW_K)
            val l = (BASE_W - rw) / 2
            sc.place(Box(l, BASE_H - ROW_FOOT - rh, l + rw, BASE_H - ROW_FOOT), Anchor.CENTRE)
        } else null
        // the corner buttons (the left one only with a tap-to-cast bar to open: [castBtn]) and the bar over the left one while open
        val y0 = BASE_H - BTN_FOOT - BTN
        val btnL = if (castBtn) sc.place(Box(BTN_MARGIN, y0, BTN_MARGIN + BTN, y0 + BTN), Anchor.LEFT) else null
        val btnR = sc.place(Box(BASE_W - BTN_MARGIN - BTN, y0, BASE_W - BTN_MARGIN, y0 + BTN), Anchor.RIGHT)
        val cast = if (castBtn && castH != null) {
            val ch = castH.coerceIn(60, 200)
            sc.place(Box(BTN_MARGIN, CAST_FOOT - ch, BTN_MARGIN + CAST_W, CAST_FOOT), Anchor.LEFT)
        } else null
        return Plan(arr, sc.px(map), party?.let { sc.px(it) }, sc.px(chat), sc.px(talk), quest?.let { sc.px(it) }, row?.let { sc.px(it) },
            cast?.let { sc.px(it) }, btnL?.let { sc.px(it) }, sc.px(btnR))
    }

    /** whether piece [p] is held clear of the keep-out named [name]: the room row stands in the HUD's place under the Wings at rest by
     *  design (the user's words: the bars hide out of combat and the row slides up there), so K2 / K3 are not its */
    fun keptFrom(p: Piece, name: String): Boolean = when (p) {
        Piece.ROW -> !(name.startsWith("K2") || name.startsWith("K3"))
        // the tap-to-cast bar is opened on demand by the player's own tap: over the Wings' corner while it is open
        Piece.CAST -> false
        else -> true
    }

    /** a right-anchored piece kept clear of [keeps]: its bottom up first, then narrower from the left, at last its top down */
    private fun fitRight(start: Box, keeps: List<Box>, minW: Int, minH: Int): Box {
        var c = start
        repeat(4) {
            val k = keeps.firstOrNull { c.hits(it, GAP) } ?: return c
            val nb = k.t - GAP
            val nl = k.r + GAP
            val nt = k.b + GAP
            c = when {
                k.t > c.t && nb - c.t >= minH -> c.copy(b = minOf(c.b, nb))
                k.r < c.r && c.r - nl >= minW -> c.copy(l = maxOf(c.l, nl))
                k.b < c.b && c.b - nt >= minH -> c.copy(t = maxOf(c.t, nt))
                else -> return c
            }
        }
        return c
    }

    /** test 1536: the left chat under [party] (Party mode's list) and clear of [keeps]: its top under any keep-out above, its foot over
     *  any below; too short = [fallback] */
    private fun fitLeft(start: Box, party: Box?, keeps: List<Box>, fallback: Box): Box {
        var c = if (party != null) start.copy(t = maxOf(start.t, party.b + GAP)) else start
        for (k in keeps.sortedBy { it.t }) if (c.hits(k, GAP)) {
            // one over the chat's first lines pushes its top down; one further down lifts its foot
            c = if (k.t - GAP - c.t < CHAT_LEFT_MIN_H && k.b + GAP + CHAT_LEFT_MIN_H <= c.b) c.copy(t = maxOf(c.t, k.b + GAP)) else c.copy(b = minOf(c.b, k.t - GAP))
        }
        if (c.h >= CHAT_LEFT_MIN_H && keeps.none { c.hits(it, GAP) }) return c
        return if (party == null || !fallback.hits(party, GAP)) fallback else c
    }

    /** Party mode's list kept clear of [keeps]: narrower first (its rows stay whole), then shorter (down to two rows) */
    private fun fitParty(start: Box, keeps: List<Box>): Box {
        var c = start
        repeat(4) {
            val k = keeps.firstOrNull { c.hits(it, GAP) } ?: return c
            val nr = k.l - GAP
            val nb = k.t - GAP
            c = when {
                k.l > c.l && nr - c.l >= PARTY_MIN_W -> c.copy(r = minOf(c.r, nr))
                k.t > c.t && nb - c.t >= PARTY_MIN_H -> c.copy(b = minOf(c.b, nb))
                else -> return c
            }
        }
        return c
    }

    // ---------------------------------------------------------------- the shift

    /** one step of a shift: a window update ([Place]) or a glide inside the window ([Glide]) */
    sealed class Step {
        abstract val piece: Piece
        /** the piece's window to [box] (null = gone): ONE window update */
        data class Place(override val piece: Piece, val box: Box?) : Step()
        /** the piece from [from] to [to] (null = fading out / in) inside its window, [ms] long */
        data class Glide(override val piece: Piece, val from: Box?, val to: Box?, val ms: Long) : Step()
    }

    /**
     * the shift from the boxes [from] to [to] (each piece's box, null = not shown), into [arr]: for each piece that moves, its window to
     * the union of both (one update), the glide inside it, its window to the new box (one update). A piece that stays: nothing
     */
    fun steps(from: Map<Piece, Box?>, to: Map<Piece, Box?>, arr: Arr): List<Step> {
        val ms = if (arr == Arr.IN) GROW_MS else SHRINK_MS
        val out = ArrayList<Step>()
        for (p in Piece.values()) {
            if (p == Piece.TALK) continue
            val a0 = from[p]; val b0 = to[p]
            if (a0 == b0) continue
            // a sliding piece (the room row) comes up from under the bottom edge and goes back down there, whole (no fade)
            val a = if (p in SLIDES && a0 == null) away(p, b0!!) else a0
            val b = if (p in SLIDES && b0 == null) away(p, a0!!) else b0
            val win = when { a == null -> b; b == null -> a; else -> a.union(b) }
            out += Step.Place(p, win)
            out += Step.Glide(p, a, b, ms)
            out += Step.Place(p, b0)
        }
        return out
    }

    /** [b] moved down just past the screen's bottom edge (its height and a gap): where a sliding piece comes from / goes to */
    fun below(b: Box): Box = b.shift(0, b.h + GAP + ROW_FOOT)

    /** where a sliding piece comes from / goes to: the room row under the bottom edge, the tap-to-cast bar past the left edge */
    fun away(p: Piece, b: Box): Box = if (p == Piece.CAST) b.shift(-(b.r + GAP), 0) else below(b)

    /** a glide's box at [k] (0..1, the curve applied) between [a] and [b] (either null: the other, faded by the caller) */
    fun at(a: Box?, b: Box?, k: Float): Box? {
        if (a == null) return b
        if (b == null) return a
        fun m(x: Int, y: Int) = x + Math.round((y - x) * k.coerceIn(0f, 1f))
        return Box(m(a.l, b.l), m(a.t, b.t), m(a.r, b.r), m(a.b, b.b))
    }

    // ---------------------------------------------------------------- what the docks leave out now

    /**
     * the dock pieces not shown now: what Home does not lay ([chatLaid] / [mapLaid] false), the map while the fight's rolls stand in its
     * place (NPC's strip [SingleNpc.ROLLS]), the party list while LB's party picture stands over it (NPC's strip [SingleNpc.LB], display
     * only) or while our window covers the screen ([fullFrame])
     */
    fun hidden(strip: String?, fullFrame: Boolean, chatLaid: Boolean, mapLaid: Boolean, questLaid: Boolean = true): Set<Piece> {
        val h = HashSet<Piece>()
        if (!chatLaid) h += Piece.CHAT
        if (!questLaid) h += Piece.QUEST
        if (fullFrame) { h += Piece.ROW; h += Piece.CAST }
        if (!mapLaid || strip == SingleNpc.ROLLS) h += Piece.MAP
        if (strip == SingleNpc.LB || fullFrame) h += Piece.PARTY
        return h
    }

    // ---------------------------------------------------------------- the frame pace (FramePace.probe in single screen mode)

    /**
     * 1529's performance mode in single screen mode: Home's views (the docked map above all) are paced to ~30 Hz while the player moves
     * or fights with Home on show - or Home's rest being the game itself ([restElsewhere], SingleLook's PLAY state, the docks up). A
     * panel over the game (a room, the wheel, Home's glance) runs as Home does on two screens.
     */
    fun paced(onHome: Boolean, homeShown: Boolean, restElsewhere: Boolean, fight: Boolean, moving: Boolean): Boolean =
        onHome && (homeShown || restElsewhere) && (fight || moving)
}
