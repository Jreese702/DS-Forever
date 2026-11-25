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
 *  K3 our Nintendo bar out of fights (ActionBar NB at the stock bar's centre, scale k x 1.322)                (650, 860, 1270, 1064)
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
    const val GAP = 8
    /** the chat keeps at least this (3 one-line messages) and this wide; the map's in-combat size */
    const val CHAT_MIN_H = 132
    const val CHAT_MIN_W = 300
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
    /** every dock window together at most this many px (about a fifth of the 1920 x 1080 top screen: the compositor's cost) */
    const val AREA_MAX = 400_000L
    /** the glide's times (Motion.COMBAT_GROW / COMBAT_SHRINK: SinglePlayLayoutTest keeps them equal) */
    const val GROW_MS = 240L
    const val SHRINK_MS = 200L

    /** the pieces, each in its own small window (SingleShell.sub ids) */
    enum class Piece(val sub: String) { MAP("dockR"), PARTY("dockP"), CHAT("dockL"), TALK("dockT") }

    data class KeepOut(val name: String, val box: Box)

    /** one arrangement: [party] null without Party mode; [talk] where hold to talk's pop-up stands while it is up */
    data class Plan(val arr: Arr, val map: Box, val party: Box?, val chat: Box, val talk: Box) {
        /** the chat lines that fit the chat's box */
        val chatLines: Int get() = maxOf(1, (chat.h - 2 * PANE_PAD + MSG_GAP) / LINE_H)
        fun box(p: Piece): Box? = when (p) { Piece.MAP -> map; Piece.PARTY -> party; Piece.CHAT -> chat; Piece.TALK -> talk }
        /** the pieces that stand while playing (the talk pop-up only while it is up) */
        fun boxes(): Map<Piece, Box?> = mapOf(Piece.MAP to map, Piece.PARTY to party, Piece.CHAT to chat)
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
    private val CHAT_OUT_PARTY = Box(16, 560, 556, 840)
    /** Party mode off: our party frames are back at K5, so the chat starts under them */
    private val CHAT_OUT_SOLO = Box(16, 520, 556, 840)
    private val CHAT_IN_PARTY = Box(16, 416, 456, 548)
    /** Party mode off in a fight: above K5 (the frames) and above K4 (the Wings' envelope): the 3 newest lines */
    private val CHAT_IN_SOLO = Box(16, 96, 456, 228)

    val K1 = Box(530, 0, 1390, 210)
    val K3 = Box(650, 860, 1270, 1064)
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
        if (arr == Arr.OUT) out += KeepOut("K3 Nintendo bar", sc.place(K3, Anchor.CENTRE))
        else out += KeepOut("K4 Wings", sc.place(K4, Anchor.CENTRE))
        if (!partyMode) out += KeepOut("K5 party frames", sc.place(K5, Anchor.LEFT))
        return out
    }

    /**
     * THE ARRANGEMENT for [arr] on a [w] x [h] screen: [partyMode] = Party mode's list shows (PartyModel: on and routing), [hud] the live
     * HUD. The map top right, the party list and the chat on the left; the chat shrinks (lines, then width) and the map shrinks (to 240)
     * to keep clear of every keep-out; the talk pop-up over the party list (or over the chat's top).
     */
    fun plan(arr: Arr, partyMode: Boolean, hud: Hud = Hud(), w: Int = BASE_W, h: Int = BASE_H, partyH: Int? = null): Plan {
        val sc = Screen(w, h)
        val keeps = virtualKeeps(sc, arr, partyMode, hud).map { it.box }
        var map = sc.place(if (arr == Arr.OUT) MAP_OUT_BOX else MAP_IN_BOX, Anchor.RIGHT)
        if (keeps.any { map.hits(it, GAP) }) map = Box(map.r - MAP_IN, map.t, map.r, map.t + MAP_IN)
        val party = if (!partyMode) null else sc.place(if (arr == Arr.OUT) PARTY_OUT else PARTY_IN, Anchor.LEFT).let { b ->
            // the window hugs the list as drawn (its rows: you, the tank, the healer, the paired damage), so no empty part of it takes a
            // finger meant for the game
            fitParty(partyH?.let { ph -> b.copy(b = b.t + ph.coerceIn(PARTY_MIN_H, b.h)) } ?: b, keeps)
        }
        var chat = sc.place(when {
            arr == Arr.OUT && partyMode -> CHAT_OUT_PARTY
            arr == Arr.OUT -> CHAT_OUT_SOLO
            partyMode -> CHAT_IN_PARTY
            else -> CHAT_IN_SOLO
        }, Anchor.LEFT)
        chat = fit(chat, keeps)
        var talk = party?.let { Box(it.l, it.t, it.l + TALK_W, it.t + TALK_H) } ?: Box(chat.l, chat.t, chat.l + TALK_W, chat.t + TALK_H)
        // the pop-up keeps clear too: narrower first, then up (it is over our own pieces while it lasts - the party frames K5 too)
        val solo = if (!partyMode) sc.place(K5, Anchor.LEFT) else null
        for (k in keeps) if (k != solo && talk.hits(k, GAP)) {
            val nr = k.l - GAP
            if (nr - talk.l >= TALK_MIN_W && k.l > talk.l) talk = talk.copy(r = minOf(talk.r, nr))
            if (talk.hits(k, GAP) && k.t > talk.t) talk = talk.shift(0, (k.t - GAP) - talk.b)
        }
        if (talk.t < 0) talk = talk.shift(0, -talk.t)
        return Plan(arr, sc.px(map), party?.let { sc.px(it) }, sc.px(chat), sc.px(talk))
    }

    /** the chat kept clear of [keeps]: its lines first (the bottom up), then its width, at last its top down under one */
    private fun fit(start: Box, keeps: List<Box>): Box {
        var c = start
        repeat(4) {
            val k = keeps.firstOrNull { c.hits(it, GAP) } ?: return c
            val nb = k.t - GAP
            val nr = k.l - GAP
            val nt = k.b + GAP
            c = when {
                k.t > c.t && nb - c.t >= CHAT_MIN_H -> c.copy(b = minOf(c.b, nb))
                k.l > c.l && nr - c.l >= CHAT_MIN_W -> c.copy(r = minOf(c.r, nr))
                k.b < c.b && c.b - nt >= CHAT_MIN_H -> c.copy(t = maxOf(c.t, nt))
                else -> return c
            }
        }
        return c
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
            val a = from[p]; val b = to[p]
            if (a == b) continue
            val win = when { a == null -> b; b == null -> a; else -> a.union(b) }
            out += Step.Place(p, win)
            out += Step.Glide(p, a, b, ms)
            out += Step.Place(p, b)
        }
        return out
    }

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
    fun hidden(strip: String?, fullFrame: Boolean, chatLaid: Boolean, mapLaid: Boolean): Set<Piece> {
        val h = HashSet<Piece>()
        if (!chatLaid) h += Piece.CHAT
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
