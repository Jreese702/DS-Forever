package com.jrees.wowcompanion

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * THE TOP-SCREEN LAYOUT EDITOR, STAGE 1 - the pure part (no Android types; LayoutModelTest). The user, 2026-11-16: "we need to make a
 * way to basically edit the UI locations on the top screen from our UI menu"; the design's picks, all the recommended ones: DS Forever's
 * OWN top-screen pieces first (the game's Edit Mode systems are stage 2, after the probes and the user's yes - [Piece.game] is the hook);
 * taps that set an EXACT spot, for the D-pad, the stick and a finger on the miniature alike (the latest spot wins, a reach switch for
 * the spots under the tap band, the pieces ease between taps in the game); a linked piece follows its parent until it is moved (Reset
 * re-attaches it); one saved layout per character plus "Copy from character"; a Layout group first on Settings > Top screen.
 *
 * The add-on (Hub hub55, TapPad pad22) keeps the draft and the pieces; it publishes the "lay" document ([parse]) and takes three kinds
 * of tap: a cell of its row "lay" ([C_OPEN_GRID] ...), and a SPOT anywhere on the top screen (its catcher): the selected piece's anchor
 * goes there. Everything here is in 1080-line pixels from the top screen's top-left. [Editor] is the page's state: what the D-pad / the
 * stick / a finger asked for, and [Editor.next] decides the ONE tap to send now (the selection first, the reach switch, then the latest
 * spot; at most one spot every [SPOT_MS] - 4 a second until the device probe says TapBridge keeps up with more).
 *
 * Hub hub58 (the user, 2026-11-17: "the edit mode should include any UI element that is currently turned on, including the wow UI and
 * polestar. the polestar ui should be broken into 2, one for the arrow and one for the actual guide."): the page lists ONLY the pieces
 * the add-on says are on ([Piece.on]: turned on; [Piece.vis]: shown this moment, else drawn dashed), so a piece turned off or hidden
 * leaves the tiles, the miniature and the snap lines as the next document comes; Polestar's arrow and guide window are two pieces of
 * their own ("psarrow", "psguide": moved only, an offset from Polestar's own place, in the Polestar group).
 *
 * Hub hub59 (the user's stage-2 picks, 2026-11-17: "Our own offsets", "Every one that's on", "Move them out of combat", "Move and
 * resize"): the GAME'S OWN frames are pieces too ("g.<frame name>", [Piece.game]; their kind u / b / o = the Unit frames, Action bars and
 * Game UI groups, after ours; select columns 8-23 handed out by the add-on to the ones shown now). Nothing goes into the game: the
 * add-on keeps per-character offsets. A protected frame's change in a fight waits for its end ([Doc.queued]: "Moves after the fight").
 * Reset game UI ([C_RESET_GAME]) drops every game frame's offset in the draft (Save writes it).
 */
object LayoutModel {
    // ---------------------------------------------------------------- the row (Hub hub55's LAY columns)
    const val ROW = "lay"
    const val C_CHAR = 24
    /** hub59: five characters (24-28); 29 is Reset game UI */
    const val CHARS = 5
    const val C_RESET_GAME = 29
    const val C_REACH_OFF = 30
    const val C_REACH_ON = 31
    const val C_SMALLER = 32
    const val C_BIGGER = 33
    const val C_RESET = 34
    const val C_REVERT = 35
    const val C_SAVE = 36
    const val C_OPEN_GRID = 37
    const val C_OPEN = 38
    const val C_CLOSE = 39

    /** the least time between two spot taps (4 a second: TapPad's pad5b would cap one cell at that; the catcher's own guard is 60 ms, so
     *  10 a second is the design's goal once the device probe shows TapBridge carries it) */
    const val SPOT_MS = 250L
    /** a tap with no answer since is sent again after this */
    const val RETRY_MS = 700L
    /** an anchor this close to the one wanted is there (the add-on rounds to whole pixels from a fractional base) */
    const val NEAR_PX = 0.6
    /** snapping: an edge within this of a line goes onto it */
    const val SNAP_PX = 8.0
    /** how far a piece may stand past the screen's edges in the app's own keep (the add-on keeps the exact rule) */
    const val EDGE_SLACK = 24.0
    /** the stick at full tilt (1080-line px a second) */
    const val GLIDE_PX_S = 520.0
    /** an open / close the add-on did not take is asked again after this */
    const val OPEN_RETRY_MS = 1500L
    /** split-hud: GameUi's words on "lay" wait this long after the editor last wanted to be open (Live.editorBusy) */
    const val EDITOR_HOLD_MS = 2000L
    /** the commands that act on the add-on's SELECTED piece (sent only once it is the picked one) */
    val PIECE_CELLS = setOf(C_SMALLER, C_BIGGER, C_RESET)

    // ---------------------------------------------------------------- the document
    data class Rect(val x: Double, val y: Double, val w: Double, val h: Double) {
        val right get() = x + w
        val bottom get() = y + h
        val cx get() = x + w / 2
        val cy get() = y + h / 2
        fun moved(dx: Double, dy: Double) = Rect(x + dx, y + dy, w, h)
        fun contains(px: Double, py: Double) = px >= x && px <= right && py >= y && py <= bottom
    }

    data class Piece(
        val id: String,
        /** stage 2's hook: one of the game's own Edit Mode systems (none today) */
        val game: Boolean,
        /** its select column on the row */
        val col: Int,
        val rect: Rect,
        /** its anchor (the point a spot sets) */
        val ax: Double, val ay: Double,
        val dx: Int, val dy: Int, val s: Int,
        /** no entry of its own: at its default (a linked piece: following its parent) */
        val atDefault: Boolean,
        val smin: Int, val smax: Int, val sstep: Int,
        /** the piece it follows until moved (the target bar: the compass), or null */
        val link: String?,
        val movable: Boolean,
        /** hub58: turned on, so LISTED (an unlisted piece is left out of the page altogether) */
        val on: Boolean,
        /** hub58: shown on the top screen this moment (off = drawn dashed: the party block solo, the chat strip between messages) */
        val vis: Boolean = on,
        /** hub59: a game frame's kind ("u" unit frame, "b" bar, "o" the rest) */
        val kind: String? = null,
        /** hub59: the game says it is protected (it moves only out of a fight) */
        val prot: Boolean = false,
        /** hub59: its change waits for the fight's end */
        val queued: Boolean = false,
    ) {
        val sizable get() = smax > smin
        /** the rect's top-left from the anchor */
        val offX get() = rect.x - ax
        val offY get() = rect.y - ay
    }

    data class Zone(val x: Double, val y: Double, val w: Double, val h: Double, val why: String) {
        fun contains(px: Double, py: Double) = px >= x && px <= x + w && py >= y && py <= y + h
    }

    data class Doc(
        val open: Boolean, val sel: String?, val dirty: Boolean, val combat: Boolean, val reach: Boolean, val grid: Boolean,
        val w: Double, val h: Double, val n: Int, val row: String?, val held: Boolean, val why: String?, val saves: Int,
        /** the catcher's spots and the row's cells the add-on has handled (an answer to a tap: the counter moved past its send) */
        val taps: Int, val cells: Int,
        val pieces: List<Piece>, val chars: List<String>, val gameGridOn: Boolean?, val gameGridSp: Double?,
        val zones: List<Zone>, val reachPx: Double, val pad: JSONObject?,
        /** hub59: the game frames' changes waiting for the fight's end; any game frame with an offset (Reset game UI can act) */
        val queued: Int = 0, val gset: Boolean = false,
    ) {
        fun piece(id: String?): Piece? = pieces.firstOrNull { it.id == id }
        /** the editor can be used now: the row on the pad (the Settings room holds) */
        val ready get() = row != null && pad?.optJSONObject("rows")?.has(row) == true
    }

    private fun d(o: JSONObject, k: String, def: Double = 0.0) = if (o.has(k)) o.optDouble(k, def) else def

    fun parsePiece(o: JSONObject): Piece? {
        val id = o.optString("id", "")
        if (id.isEmpty()) return null
        return Piece(id, o.optBoolean("g", false), o.optInt("c", -1),
            Rect(d(o, "x"), d(o, "y"), d(o, "w"), d(o, "h")), d(o, "ax"), d(o, "ay"),
            o.optInt("dx", 0), o.optInt("dy", 0), o.optInt("s", 100), o.optBoolean("d", true),
            o.optInt("sn", 100), o.optInt("sx", 100), o.optInt("ss", 10).coerceAtLeast(1),
            o.optString("l", "").ifEmpty { null }, o.optBoolean("m", true), o.optBoolean("on", false),
            o.optBoolean("vis", o.optBoolean("on", false)), o.optString("k", "").ifEmpty { null }, o.optBoolean("pr", false),
            o.optBoolean("q", false))
    }

    /** a "lay" document; a part document (`part`: the selected piece alone) merges into [prev] */
    fun parse(o: JSONObject, prev: Doc? = null): Doc? {
        if (o.optString("type") != "lay") return null
        val got = ArrayList<Piece>()
        o.optJSONArray("p")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { p -> parsePiece(p)?.let { got.add(it) } } }
        val part = o.optBoolean("part", false)
        val pieces = if (part && prev != null) prev.pieces.map { old -> got.firstOrNull { it.id == old.id } ?: old } else got
        val zones = ArrayList<Zone>()
        val catch = o.optJSONObject("catch")
        catch?.optJSONArray("no")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { z -> zones.add(Zone(d(z, "x"), d(z, "y"), d(z, "w"), d(z, "h"), z.optString("why"))) }
        }
        val chars = ArrayList<String>()
        o.optJSONArray("chars")?.let { a -> for (i in 0 until a.length()) a.optString(i, "").takeIf { it.isNotEmpty() }?.let { chars.add(it) } }
        val gg = o.optJSONObject("gg")
        return Doc(
            open = o.optBoolean("open", false), sel = o.optString("sel", "").ifEmpty { null }, dirty = o.optBoolean("dirty", false),
            combat = o.optBoolean("combat", false), reach = o.optBoolean("reach", false), grid = o.optBoolean("grid", false),
            w = d(o, "w", 1920.0).takeIf { it > 0 } ?: 1920.0, h = d(o, "h", 1080.0).takeIf { it > 0 } ?: 1080.0,
            n = o.optInt("n", 0), row = o.optString("row", "").ifEmpty { null }, held = o.optBoolean("held", false),
            why = o.optString("why", "").ifEmpty { null }, saves = o.optInt("saves", 0),
            taps = o.optInt("taps", prev?.taps ?: 0), cells = o.optInt("cells", prev?.cells ?: 0), pieces = pieces,
            chars = if (part && prev != null) prev.chars else chars,
            gameGridOn = if (gg != null && gg.has("on")) gg.optBoolean("on") else if (part) prev?.gameGridOn else null,
            gameGridSp = if (gg != null && gg.has("sp")) gg.optDouble("sp") else if (part) prev?.gameGridSp else null,
            zones = if (part && prev != null && catch == null) prev.zones else zones,
            reachPx = catch?.let { d(it, "reach", 160.0) } ?: prev?.reachPx ?: 160.0,
            pad = o.optJSONObject("pad") ?: if (part) prev?.pad else null,
            queued = o.optInt("queued", prev?.queued ?: 0), gset = if (o.has("gset")) o.optBoolean("gset") else prev?.gset ?: false,
        )
    }

    // ---------------------------------------------------------------- the tiles (the design's groups)
    enum class Group(val label: String) {
        HUD("Your HUD"), TARGET("Target"), PARTY("Party"), NAVIGATION("Navigation"), POLESTAR("Polestar"), BARS("Bars"),
        /** hub59: the game's own frames, unit frames and bars first */
        UNITS("Unit frames"), GAME_BARS("Action bars"), GAME("Game UI")
    }

    fun groupOf(p: Piece): Group = if (p.game) when (p.kind) { "u" -> Group.UNITS; "b" -> Group.GAME_BARS; else -> Group.GAME } else when (p.id) {
        "hud" -> Group.HUD; "target" -> Group.TARGET; "party" -> Group.PARTY; "compass" -> Group.NAVIGATION
        "psarrow", "psguide" -> Group.POLESTAR
        "nbar", "chat" -> Group.BARS; else -> Group.GAME
    }

    /** hub59: the game frames' names (the add-on's G.REG; the app's words) */
    val GAME_LABELS = mapOf(
        "PlayerFrame" to "Player", "TargetFrame" to "Target", "FocusFrame" to "Focus", "PetFrame" to "Pet", "PartyFrame" to "Party",
        "CompactRaidFrameContainer" to "Raid", "BossTargetFrameContainer" to "Bosses",
        "GamepadMainActionBarFrame" to "Controller bar", "GamepadPersistentInputLegend" to "Button legend", "MainActionBar" to "Action bar 1",
        "MultiBarBottomLeft" to "Action bar 2", "MultiBarBottomRight" to "Action bar 3", "MultiBarRight" to "Action bar 4",
        "MultiBarLeft" to "Action bar 5", "MultiBar5" to "Action bar 6", "MultiBar6" to "Action bar 7", "MultiBar7" to "Action bar 8",
        "StanceBar" to "Stance bar", "PetActionBar" to "Pet bar", "PossessActionBar" to "Possess bar", "MultiCastActionBarFrame" to "Totem bar",
        "MinimapCluster" to "Minimap", "BuffFrame" to "Buffs", "DebuffFrame" to "Debuffs", "ChatFrame1" to "Chat",
        "ObjectiveTrackerFrame" to "Quest tracker", "PlayerCastingBarFrame" to "Cast bar", "GamepadPlayerCastingBarFrame" to "Controller cast bar",
        "DamageMeter" to "Damage meter", "EssentialCooldownViewer" to "Essential cooldowns", "UtilityCooldownViewer" to "Utility cooldowns",
        "BuffIconCooldownViewer" to "Buff icons", "BuffBarCooldownViewer" to "Buff bars", "MicroMenuContainer" to "Micro menu",
        "BagsBar" to "Bag bar", "MainStatusTrackingBarContainer" to "XP bar", "SecondaryStatusTrackingBarContainer" to "Second status bar",
        "DurabilityFrame" to "Durability", "MirrorTimerContainer" to "Timers", "QueueStatusButton" to "Queue", "ExtraAbilityContainer" to "Extra ability",
        "SwingTimerMainHandFrame" to "Swing timer", "SwingTimerOffHandFrame" to "Off-hand swing", "SwingTimerRangedFrame" to "Ranged swing",
    )

    /** the piece's name (the app's words: Tr.t at the draw) */
    fun labelOf(id: String): String = if (id.startsWith("g.")) GAME_LABELS[id.substring(2)] ?: id.substring(2) else when (id) {
        "hud" -> "HUD"; "compass" -> "Compass"; "target" -> "Target bar"; "party" -> "Party frames"; "nbar" -> "Combat bar"
        "chat" -> "Top chat"; "psarrow" -> "Polestar arrow"; "psguide" -> "Polestar guide"; else -> id
    }

    /** hub58: the pieces on the page (the miniature, the snap lines, a finger's pick): only the listed ones */
    fun listed(doc: Doc?): List<Piece> = doc?.pieces?.filter { it.on } ?: emptyList()

    /** the tiles in the page's order: the LISTED pieces (hub58), by group, each group's pieces in the document's order */
    fun tiles(doc: Doc?): List<Piece> =
        doc?.pieces?.filter { it.on && (it.movable || it.game) }?.sortedWith(compareBy({ groupOf(it).ordinal }, { doc.pieces.indexOf(it) }))
            ?: emptyList()

    // ---------------------------------------------------------------- geometry
    /** where to tap for an anchor at ([x], [y]): the spot itself, or REACH lower with the reach switch (the spot inside the pad band or
     *  the sink); null = it cannot be reached (both in a zone, or the reach point off the screen). An anchor OFF the screen (the app's
     *  [EDGE_SLACK] lets a piece stand a little past an edge) is tapped at the nearest point on it: the add-on keeps the piece on the screen
     *  and its answer becomes the place ([Editor.onDoc]), so no wish is left waiting for a tap that can never go (the review) */
    data class Tap(val x: Double, val y: Double, val reach: Boolean)

    fun tapFor(x: Double, y: Double, doc: Doc): Tap? {
        val onScreen = { px: Double, py: Double -> px >= 0 && py >= 0 && px <= doc.w && py <= doc.h }
        val blocked = { px: Double, py: Double -> doc.zones.any { it.contains(px, py) } }
        val cx = x.coerceIn(0.0, doc.w); val cy = y.coerceIn(0.0, doc.h)
        if (!blocked(cx, cy)) return Tap(cx, cy, false)
        val ry = cy + doc.reachPx
        if (onScreen(cx, ry) && !blocked(cx, ry)) return Tap(cx, ry, true)
        return null
    }

    /** the lines a moved rect snaps to: the screen's edges and centre lines, the grid's lines (from the centre, [grid] apart), and every
     *  other piece's edges and centre */
    fun snapLines(doc: Doc, others: List<Rect>, grid: Double?): Pair<List<Double>, List<Double>> {
        val xs = arrayListOf(0.0, doc.w / 2, doc.w)
        val ys = arrayListOf(0.0, doc.h / 2, doc.h)
        if (grid != null && grid >= 10) {
            var k = grid
            while (k <= doc.w / 2) { xs.add(doc.w / 2 + k); xs.add(doc.w / 2 - k); k += grid }
            k = grid
            while (k <= doc.h / 2) { ys.add(doc.h / 2 + k); ys.add(doc.h / 2 - k); k += grid }
        }
        for (o in others) { xs.add(o.x); xs.add(o.cx); xs.add(o.right); ys.add(o.y); ys.add(o.cy); ys.add(o.bottom) }
        return xs to ys
    }

    /** the shift (dx, dy) that puts [r]'s nearest edge / centre onto a line within [threshold] (0 where none is near) */
    fun snap(r: Rect, lines: Pair<List<Double>, List<Double>>, threshold: Double = SNAP_PX): Pair<Double, Double> {
        fun best(edges: List<Double>, ls: List<Double>): Double {
            var b = 0.0; var bd = threshold + 1e-9
            for (e in edges) for (l in ls) { val dd = l - e; if (abs(dd) < bd) { bd = abs(dd); b = dd } }
            return b
        }
        return best(listOf(r.x, r.cx, r.right), lines.first) to best(listOf(r.y, r.cy, r.bottom), lines.second)
    }

    /** [r] kept on the screen (plus [EDGE_SLACK]; the add-on keeps its exact rule): the shift to apply */
    fun keepOn(r: Rect, w: Double, h: Double, slack: Double = EDGE_SLACK): Pair<Double, Double> {
        fun one(a: Double, len: Double, max: Double): Double {
            if (len >= max + 2 * slack) return -slack - a
            return when { a < -slack -> -slack - a; a + len > max + slack -> max + slack - (a + len); else -> 0.0 }
        }
        return one(r.x, r.w, w) to one(r.y, r.h, h)
    }

    /** the cell that steps the size from [now] toward [want] (one step a tap), or null when it is there */
    fun sizeCell(now: Int, want: Int?): Int? = when {
        want == null || want == now -> null
        want > now -> C_BIGGER
        else -> C_SMALLER
    }

    // ---------------------------------------------------------------- the page's state
    enum class Focus { TILES, FOOTER, MOVING }

    /** the footer's buttons, in order (icons plus one word each, the design's footer; Copy for "Copy from character") */
    enum class Btn(val word: String) {
        STEP("Step"), SIZE("Size"), RESET("Reset"), SNAP("Snap"), GRID("Grid"), REVERT("Revert"), COPY("Copy"),
        /** hub59: Reset game UI (every game frame's offset dropped in the draft) */
        GAME("Game UI"), SAVE("Save")
    }

    /** the status line's words for [d] (terse; null = none): why nothing moves now, or a game frame's change waiting for the fight */
    fun statusOf(d: Doc?): String? = when {
        d == null || !d.ready -> "Waiting for the game"
        d.why == "editmode" && !d.open -> "Game's Edit Mode is open"
        d.why == "norow" -> "No pad row"
        d.queued > 0 -> "Moves after the fight"
        d.combat -> "In combat"
        else -> null
    }

    /** what B does now (the house rule: one step back, then out; Settings' own B then goes Home) */
    enum class Back { CLOSE_PROMPT, CLOSE_LIST, DESELECT, ASK, LEAVE }

    sealed class Send {
        data class Cell(val col: Int) : Send()
        data class Spot(val x: Double, val y: Double) : Send()
    }

    class Editor {
        var doc: Doc? = null; private set
        var focus = Focus.TILES
        var tile = 0
        var footer = 0
        /** the piece being moved (A picked it; A again drops it) */
        var picked: String? = null; private set
        /** 1 or 10 px a D-pad press (X) */
        var step = 1
        var snapOn = true
        var gridOn = false
        /** the D-pad sizes the piece instead of moving it (Size, or Y held) */
        var sizeMode = false
        /** the anchor wanted for the picked piece (the latest), or null */
        var want: Pair<Double, Double>? = null; private set
        var wantScale: Int? = null; private set
        /** the piece [want] / [wantScale] are for: it stays after A drops the piece, until the add-on has it there (a drop right after a
         *  move never loses the move) */
        var wantId: String? = null; private set
        /** the Save / Discard / Cancel prompt (leaving with unsaved changes), its button */
        var prompt = false
        var promptSel = 0
        /** the Copy from character list, its row */
        var list = false
        var listSel = 0
        /** the editor should be open in the add-on (the page shows); split-hud: wanting it marks [Live.markEditor] (GameUi holds the
         *  parts' and meters' taps on the same row then, so none lands after the open as an editor command) */
        var wantOpen = false
            set(v) { field = v; if (v) Live.markEditor() }
        private var lastSpot: Pair<Double, Double>? = null
        private var lastSpotAt = Long.MIN_VALUE / 4
        /** the add-on's spot / cell counters when the last one went (its answer moves them past) */
        private var lastSpotTaps = -1
        private var lastCell: Int? = null
        private var lastCellAt = Long.MIN_VALUE / 4
        private var lastCellCells = -1
        private val queued = ArrayList<Int>()
        /** the first queued command went at this time (null = not sent yet), when the add-on's cell counter read [cmdCells] */
        private var cmdAt: Long? = null
        private var cmdCells = -1

        fun onDoc(d: Doc) {
            val first = doc == null
            val focusedId = tilePiece()?.id
            doc = d
            if (first) gridOn = d.gameGridOn ?: false
            // hub58: a picked piece that is gone or no longer listed (turned off, hidden) is dropped, its wish with it
            val p = picked?.let { d.piece(it) }
            if (picked != null && (p == null || !p.on)) { picked = null; sizeMode = false; if (focus == Focus.MOVING) focus = Focus.TILES }
            val q = subject()
            if (wantId != null && (q == null || !q.on)) clearWant()
            // a spot the add-on ANSWERED (its spot counter moved past our send) but placed elsewhere (kept on the screen, whole pixels):
            // its answer is the place (never a document from before the tap: that one has the old counter)
            val w = want
            if (q != null && w != null && lastSpot == w && d.taps > lastSpotTaps && !near(q.ax, q.ay, w)) want = q.ax to q.ay
            // a size past the piece's limits (another piece's limits picked): the piece's own word
            if (q != null && wantScale != null && (wantScale!! < q.smin || wantScale!! > q.smax)) wantScale = q.s
            // a dropped piece's wish fulfilled: nothing more to send for it
            if (q != null && picked != q.id && (want == null || near(q.ax, q.ay, want!!)) && (wantScale == null || wantScale == q.s)) clearWant()
            if (!d.open && !wantOpen) { picked = null; clearWant() }
            // the highlight stays on the same piece when the list changes round it (hub58: pieces come and go live)
            val ts = tiles(d)
            val at = focusedId?.let { id -> ts.indexOfFirst { it.id == id } } ?: -1
            tile = if (at >= 0) at else tile.coerceIn(0, (ts.size - 1).coerceAtLeast(0))
        }

        private fun near(x: Double, y: Double, w: Pair<Double, Double>) = abs(x - w.first) <= NEAR_PX && abs(y - w.second) <= NEAR_PX

        fun tiles(): List<Piece> = tiles(doc)
        fun pickedPiece(): Piece? = doc?.piece(picked)
        /** the piece the next taps are for: the one whose wish is pending, else the picked one */
        fun subject(): Piece? = doc?.piece(wantId ?: picked)
        private fun clearWant() { want = null; wantScale = null; wantId = null }
        /** a command that changes the add-on's draft wholesale (Reset, Revert, Copy): no older wish is sent after it */
        fun forget() = clearWant()
        fun tilePiece(): Piece? = tiles().getOrNull(tile)

        /** the piece's rect as it is going to stand (the wanted anchor and size), for the miniature: the size grows round the anchor, as
         *  the add-on's owners scale their frames */
        fun previewRect(p: Piece): Rect {
            if (p.id != wantId) return p.rect
            val w = want
            val f = wantScale?.takeIf { p.s > 0 }?.let { it.toDouble() / p.s } ?: 1.0
            if (w == null && f == 1.0) return p.rect
            val ax = w?.first ?: p.ax; val ay = w?.second ?: p.ay
            return Rect(ax + p.offX * f, ay + p.offY * f, p.rect.w * f, p.rect.h * f)
        }

        /** A on a tile / a finger on a piece: pick it (the next moves go to it) */
        fun pick(id: String) {
            val p = doc?.piece(id) ?: return
            if (!p.movable || !p.on) return
            if (id != wantId) clearWant()
            picked = id; focus = Focus.MOVING
            tiles().indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { tile = it }
        }
        /** A again: drop it (it stays where it is) */
        fun drop() { picked = null; sizeMode = false; focus = Focus.TILES }

        private fun base(p: Piece): Pair<Double, Double> = (if (wantId == p.id) want else null) ?: (p.ax to p.ay)

        /** the anchor [a] for the picked piece kept on the screen (the app's slack; the add-on decides exactly) */
        private fun kept(p: Piece, a: Pair<Double, Double>, snapIt: Boolean): Pair<Double, Double> {
            val d = doc ?: return a
            var r = p.rect.moved(a.first - p.ax, a.second - p.ay)
            var x = a.first; var y = a.second
            if (snapIt && snapOn) {
                val others = listed(d).filter { it.id != p.id && !it.game }.map { it.rect }
                val (sx, sy) = snap(r, snapLines(d, others, if (gridOn) d.gameGridSp ?: 100.0 else null))
                x += sx; y += sy; r = r.moved(sx, sy)
            }
            val (kx, ky) = keepOn(r, d.w, d.h)
            return (x + kx) to (y + ky)
        }

        /** the D-pad: one step ([step] px) of the picked piece, exact (no snap: the D-pad is the fine control) */
        fun nudge(dx: Int, dy: Int) {
            val p = pickedPiece() ?: return
            if (sizeMode) { if (dy != 0) size(if (dy < 0) 1 else -1); return }
            val (x, y) = base(p)
            want = kept(p, (x + dx * step).roundToInt().toDouble() to (y + dy * step).roundToInt().toDouble(), snapIt = false)
            wantId = p.id
        }

        /** the stick: [vx], [vy] in -1..1 for [dtS] seconds (it snaps when Snap is on) */
        fun glide(vx: Float, vy: Float, dtS: Float) {
            val p = pickedPiece() ?: return
            if (sizeMode) return
            val (x, y) = base(p)
            raw = (raw ?: (x to y)).let { (rx, ry) -> (rx + vx * GLIDE_PX_S * dtS) to (ry + vy * GLIDE_PX_S * dtS) }
            val k = kept(p, raw!!, snapIt = true)
            want = k.first.roundToInt().toDouble() to k.second.roundToInt().toDouble()
            wantId = p.id
        }
        /** the unsnapped glide (so a snap holds until the stick has carried the piece past it) */
        private var raw: Pair<Double, Double>? = null
        fun glideEnd() { raw = null }

        /** a finger on the miniature: the anchor where the drag has carried it (absolute), snapped when Snap is on */
        fun dragTo(ax: Double, ay: Double) {
            val p = pickedPiece() ?: return
            val k = kept(p, ax to ay, snapIt = true)
            want = k.first.roundToInt().toDouble() to k.second.roundToInt().toDouble()
            wantId = p.id
        }

        /** Size: one step bigger (+1) / smaller (-1) of the picked piece, within its limits */
        fun size(dir: Int) {
            val p = pickedPiece() ?: return
            if (!p.sizable) return
            val now = (if (wantId == p.id) wantScale else null) ?: p.s
            if (wantId != p.id) { want = null; wantId = p.id }
            wantScale = (now + dir * p.sstep).coerceIn(p.smin, p.smax)
        }

        /** B now */
        fun back(): Back = when {
            prompt -> Back.CLOSE_PROMPT
            list -> Back.CLOSE_LIST
            picked != null || focus == Focus.MOVING -> Back.DESELECT
            doc?.dirty == true -> Back.ASK
            else -> Back.LEAVE
        }

        /** a cell to send once (a footer command): queued, sent in order by [next] */
        fun command(col: Int) { queued.add(col) }

        /**
         * THE ONE TAP TO SEND NOW ([now] ms), or null: the open first, a queued command, the selection (the spot goes to the add-on's
         * selected piece, so the add-on's selection must be the picked one first), the reach switch the next spot needs, the size, then
         * the latest spot (at most every [SPOT_MS]); a tap with no answer is sent again after [RETRY_MS]. Nothing while the document says
         * a fight (the add-on takes no spot then) or before the row is on the pad.
         */
        fun next(now: Long): Send? {
            if (wantOpen || doc?.open == true) Live.markEditor()
            val d = doc ?: return null
            if (!d.ready) return null
            // a command first (Save before the close that follows it); one for the picked piece only once the add-on has it selected.
            // It stays first until the add-on ANSWERS it (its cell counter past the send) and goes again after RETRY_MS without one -
            // every command is the same twice (Save, Revert, Reset, Copy) - so a lost tap never lets the close behind it drop the
            // draft the prompt's Save was meant to keep (the review)
            if (d.open && queued.isNotEmpty()) {
                if (cmdAt != null && d.cells > cmdCells) { queued.removeAt(0); cmdAt = null }
            }
            if (d.open && queued.isNotEmpty()) {
                val col = queued.first()
                val at = cmdAt
                if (at != null) {
                    if (now - at < RETRY_MS) return null
                    cmdAt = now; cmdCells = d.cells
                    lastCell = col; lastCellAt = now; lastCellCells = d.cells
                    return Send.Cell(col)
                }
                val pp = pickedPiece()
                if (col in PIECE_CELLS && pp != null && d.sel != pp.id) return cellOnce(pp.col, now, d)
                // (a cell still on its way - a selection, the reach switch - is answered first, so the counter moving past is THIS one's)
                if (lastCell != null && d.cells <= lastCellCells && now - lastCellAt < RETRY_MS) return null
                cmdAt = now; cmdCells = d.cells
                lastCell = col; lastCellAt = now; lastCellCells = d.cells
                return Send.Cell(col)
            }
            if (wantOpen != d.open) {
                val col = if (wantOpen) (if (gridOn) C_OPEN_GRID else C_OPEN) else C_CLOSE
                // (an open the add-on refused - the game's Edit Mode up, a fight - is asked again only every OPEN_RETRY_MS)
                if (lastCell == col && now - lastCellAt < OPEN_RETRY_MS) return null
                lastCell = col; lastCellAt = now; lastCellCells = d.cells
                return Send.Cell(col)
            }
            if (!d.open) return null
            if (d.grid != gridOn) return cellOnce(if (gridOn) C_OPEN_GRID else C_OPEN, now, d)
            val p = subject() ?: return null
            if (d.sel != p.id) return cellOnce(p.col, now, d)
            if (d.combat) return null
            sizeCell(p.s, wantScale)?.let { return cellOnce(it, now, d) }
            val w = want ?: return null
            if (near(p.ax, p.ay, w)) return null
            val t = tapFor(w.first, w.second, d) ?: return null
            if (t.reach != d.reach) return cellOnce(if (t.reach) C_REACH_ON else C_REACH_OFF, now, d)
            if (now - lastSpotAt < SPOT_MS) return null
            if (lastSpot == w && now - lastSpotAt < RETRY_MS) return null      // the same spot again only when its answer never came
            lastSpot = w; lastSpotAt = now; lastSpotTaps = d.taps
            return Send.Spot(t.x, t.y)
        }

        /** one cell; the same one again only after its answer (the add-on's cell counter past the send) or [RETRY_MS] */
        private fun cellOnce(col: Int, now: Long, d: Doc): Send? {
            if (lastCell == col && d.cells <= lastCellCells && now - lastCellAt < RETRY_MS) return null
            lastCell = col; lastCellAt = now; lastCellCells = d.cells
            return Send.Cell(col)
        }

        /** the page closes: no piece, no wish, no prompt; a command already asked (the prompt's Save) still goes, before the close */
        fun leave() {
            picked = null; clearWant(); sizeMode = false; prompt = false; list = false; focus = Focus.TILES
            wantOpen = false; raw = null
        }

        /** the page opens afresh: nothing is wanted */
        fun reset() {
            picked = null; clearWant(); sizeMode = false; prompt = false; list = false; focus = Focus.TILES
            queued.clear(); cmdAt = null; lastSpot = null; lastCell = null; raw = null
        }
    }

    // ---------------------------------------------------------------- the latest document (CompanionHost feeds it)
    object Live {
        @Volatile var doc: Doc? = null; private set
        private val listeners = ArrayList<() -> Unit>()
        fun note(msg: JSONObject) {
            val d = parse(msg, doc) ?: return
            doc = d
            for (l in listeners.toList()) l()
        }
        fun onChange(l: () -> Unit) { listeners.add(l) }
        /** split-hud (the review): the wall clock for [editorBusy] (tests set their own) */
        @Volatile var clock: () -> Long = { System.currentTimeMillis() }
        @Volatile private var editorAt = Long.MIN_VALUE / 4
        /** the editor wants to be open, or is sending (its open, its close): marked on every pump */
        fun markEditor() { editorAt = clock() }
        /**
         * the editor is open or on its way, or was within [EDITOR_HOLD_MS] at wall time [now]: columns 24-32 of "lay" are its Copy from /
         * Reset game / Reach / Smaller then, so GameUi sends no part's (or meter's) word - a word retried just after an open would read
         * as one of them (the add-on drops one inside HS_GRACE_S of the open as well)
         */
        fun editorBusy(now: Long): Boolean = doc?.open == true || now - editorAt < EDITOR_HOLD_MS
        /** the Settings row shows: the add-on set has the editor (a "lay" document came) */
        fun available(): Boolean = doc?.row != null
        fun clearForTest() { doc = null; editorAt = Long.MIN_VALUE / 4; clock = { System.currentTimeMillis() } }
    }
}
