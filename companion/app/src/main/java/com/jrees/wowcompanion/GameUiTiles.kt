package com.jrees.wowcompanion

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * THE TOP SCREEN AS PICTURE TILES (the user, 2026-11-16: "VISUAL overhaul of UI menus (picture beside each game-UI element option)"; the
 * design's recommended picks, accepted the same day: a grid with an inline band under the tile's row, 19 tiles with the Pet bar inside the
 * Resource bar's band, Kotlin pictures from pieces already on the phone, Off = a dimmed picture plus the badge and the word, the five
 * headings with no folding on this tab).
 *
 * The model and the geometry, pure (no android.graphics), so the off-device tests measure them: which tiles each Top screen group shows
 * (a FIXED slot list, so a switch the add-on reports late never reflows the tiles after it: until the game says, the tile is a dim
 * "..." one), which rows each tile's band carries, the badge a row earns, the grid and the band's boxes. Ids, taps, channels and saved
 * values are the rows' own (GameUi / GameUiSection): only where and how they are drawn changed.
 */
object GameUiTiles {
    /** a box in a part's own coordinates */
    data class Box(val l: Float, val t: Float, val r: Float, val b: Float) {
        val w get() = r - l
        val h get() = b - t
        val cx get() = (l + r) / 2f
        val cy get() = (t + b) / 2f
        fun contains(x: Float, y: Float) = x >= l && x < r && y >= t && y < b
    }

    /** Polestar's own frame on the top screen (hub31; it was SettingsView's row): Navigation's third tile */
    const val PS_ID = "polestartop"

    /** hub61 (the beautify design 7, the user, 2026-11-17): the layout editor's row as a tile ("Edit layout": its A opens LayoutView,
     *  no band), the Layout group's one */
    const val LAYOUT_ID = "layout"

    /** the tiles of each Top screen group, in order (the Game windows' order is the user's approved one; the damage meter and the
     *  Cooldown Manager after the controller bars, the user, 2026-11-17: "DPS meters and also the cooldown manager ... we need to make
     *  sure that its consistent with the other picture options") */
    val TILES: Map<SettingsTabs.Group, List<String>> = linkedMapOf(
        SettingsTabs.Group.LAYOUT to listOf(LAYOUT_ID),
        SettingsTabs.Group.HUD to listOf(GameUi.HUD_ID),
        SettingsTabs.Group.TARGET to listOf(GameUi.UFT_ID, GameUi.NP_ID, GameUi.NPF_ID),
        SettingsTabs.Group.PARTY to listOf(GameUi.UFP_ID, GameUi.PARTY_ID),
        SettingsTabs.Group.NAVIGATION to listOf(GameUiSection.COMPASS_ID, GameUi.WM_ID, PS_ID),
        SettingsTabs.Group.WINDOWS to GameUiSection.WINDOWS_ORDER)

    /** the rows a tile's band carries under its own choices (each only while its row is there: the add-on's word, the resource bar on) */
    val EXTRAS: Map<String, List<String>> = mapOf(
        // (11-16 merge: HUD height and HUD scale are the layout editor's now - feat/big-layout's pick - so the band keeps Pet bar + Width)
        GameUi.HUD_ID to listOf(GameUi.PET_ID, GameUi.HUD_W_ID),
        GameUi.UFT_ID to listOf(GameUi.UFHP_ID, GameUi.UFTOT_ID),
        GameUi.NP_ID to listOf(GameUiSection.RINGS_ID),
        GameUiSection.COMPASS_ID to listOf(GameUiSection.COMPASS_CAM_ID),
        // hub61: the Cooldown Manager's four parts (each only while the tile is On)
        GameUi.CDM_ID to GameUi.CDM_PARTS)

    /** a tile's name (display only: the rows' own words stay the data the tests key on) */
    val NAMES: Map<String, String> = mapOf(
        GameUi.HUD_ID to "Resource bar", GameUi.UFT_ID to "Target bar", GameUi.NP_ID to "Enemy nameplates", GameUi.NPF_ID to "Friendly plates",
        GameUi.UFP_ID to "DS party frames", GameUi.PARTY_ID to "Party frames", GameUiSection.COMPASS_ID to "Compass bar",
        GameUi.WM_ID to "World markers", PS_ID to "Polestar", "minimap" to "Minimap", "tracker" to "Quest tracker", "chat" to "Chat window",
        "dialogs" to "NPC windows", GameUiSection.LOOT_ID to "Loot window", GameUiSection.TIPS_ID to "Tooltips", GameUi.CASTBAR_ID to "Cast bar",
        GameUi.PLAYER_ID to "Player frame", GameUi.TARGET_ID to "Target frame", "hud" to "Controller UI",
        // split-hud (the user, 2026-11-17: "we need to split out the Buffs controller UI and XP bar"): the old tile's riders, tiles of their own
        HudSplit.BUFFS_ID to "Buffs", HudSplit.XP_ID to "XP bar",
        GameUi.DMG_ID to "Damage meter", GameUi.CDM_ID to "Cooldown Manager", LAYOUT_ID to "Edit layout")

    /** a band row's label (short: the tile above names the element) */
    val EXTRA_NAMES: Map<String, String> = mapOf(
        GameUi.PET_ID to "Pet bar", GameUi.HUD_H_ID to "Height", GameUi.HUD_W_ID to "Width", GameUi.HUD_S_ID to "Scale",
        GameUi.UFHP_ID to "Health text", GameUi.UFTOT_ID to "Target of target", GameUiSection.RINGS_ID to "Aggro tint",
        GameUiSection.COMPASS_CAM_ID to "Follows the camera",
        GameUi.CDM_ESS_ID to "Essential", GameUi.CDM_UTIL_ID to "Utility", GameUi.CDM_BICON_ID to "Buff icons", GameUi.CDM_BBAR_ID to "Buff bars")

    /** THE TILES' PICTURES' REGISTRY (TilePictures draws each; its own registry, not SettingsPreview.ROWS: that one shows the "What it
     *  does" panel beside the page, which would squeeze the grid): every tile has one */
    val PICTURED: Set<String> = setOf(GameUi.HUD_ID, GameUi.UFT_ID, GameUi.NP_ID, GameUi.NPF_ID, GameUi.UFP_ID, GameUi.PARTY_ID,
        GameUiSection.COMPASS_ID, GameUi.WM_ID, PS_ID, "minimap", "tracker", "chat", "dialogs", GameUiSection.LOOT_ID,
        GameUiSection.TIPS_ID, GameUi.CASTBAR_ID, GameUi.PLAYER_ID, GameUi.TARGET_ID, "hud", HudSplit.BUFFS_ID, HudSplit.XP_ID,
        GameUi.DMG_ID, GameUi.CDM_ID, LAYOUT_ID)

    /** every tile, every group (24 with Polestar in use: 19 + the damage meter, the Cooldown Manager, Edit layout, and split-hud's
     *  Buffs and XP bar) */
    val ALL: List<String> get() = TILES.values.flatten()

    /** the groups drawn as tiles (the Top screen tab's) */
    fun tiled(g: SettingsTabs.Group?): Boolean = g != null && g in TILES

    /** [g]'s tiles now: the fixed slots; Polestar's only while Polestar is in use (an add-on rules it out, never the game's silence) */
    fun slots(g: SettingsTabs.Group, polestar: Boolean): List<String> = (TILES[g] ?: emptyList()).filter { it != PS_ID || polestar }

    // ---------------------------------------------------------------- the badge

    /** the 36 px mark on a tile: its mode, or the game's state when it differs from the choice */
    enum class Badge { ON, OFF, DYNAMIC, TRACKING, NINTENDO, SENT, REFUSED, WAITING,
        /** hub61: the game's own option is off (the kit's alert_gear: one sign, one meaning - not WAITING's dots); the choice waits */
        GAME_OFF }

    /** the game's elements (Hub.lua's layout bits): their notes say the game's state */
    private val ELEMENTS = setOf("minimap", "hud", "tracker", "chat", "dialogs", HudSplit.BUFFS_ID, HudSplit.XP_ID)
    /** an element's notes when the game shows what was chosen */
    private val STEADY = setOf("Hidden now", "On the top screen now", "DS Forever's bar on the top screen now")
    /** a switch's notes while the game has not caught up with the choice */
    private val PENDING = setOf("Sent", "Waits for the layout first", "Changes when combat ends")

    /**
     * the badge [r] earns: "..." until the game reports it; "!" when the game refused it; the hourglass while the game's state is not the
     * choice yet (sent, held for combat, loading); otherwise its mode. The picture follows the choice at once; only the badge waits.
     */
    fun badge(r: GameUi.Row?): Badge = when {
        r == null || !r.known || r.note == "The game does not offer this yet" -> Badge.WAITING
        r.note.startsWith("The game refused") -> Badge.REFUSED
        r.note == PadFit.NO_ROW_NOTE -> Badge.REFUSED            // #550: its pad row is not on the game's screen: "!", never the hourglass
        r.note == GameUi.GAME_OFF_NOTE -> Badge.GAME_OFF
        r.note in PENDING -> Badge.SENT
        r.id in ELEMENTS && r.note !in STEADY -> Badge.SENT
        else -> when (r.mode) {
            UiMode.ON -> Badge.ON; UiMode.OFF -> Badge.OFF; UiMode.DYNAMIC -> Badge.DYNAMIC
            UiMode.TRACKING -> Badge.TRACKING; UiMode.NINTENDO -> Badge.NINTENDO
        }
    }

    /** the picture is dimmed: the choice is Off, or the game has not reported the element */
    fun dimmed(r: GameUi.Row?): Boolean = r == null || !r.known || r.mode == UiMode.OFF || r.note == GameUi.GAME_OFF_NOTE

    // ---------------------------------------------------------------- the grid

    /** a tile and its picture well (the design's 232 x 184 tile, 216 x 108 well), the gaps and the page's margins */
    const val TILE_W = 232f
    const val TILE_H = 184f
    const val GAP = 12f
    const val MARGIN = 16f
    const val WELL_PAD = 8f
    const val WELL_H = 108f
    const val BADGE = 36f
    /** the grid's top under its group's heading, and its foot */
    const val TOP = 4f
    const val BOTTOM = 16f

    /** the columns [width] holds (5 on the room's 1240 px page) */
    fun columns(width: Float): Int = max(1, floor((width - 2f * MARGIN + GAP) / (TILE_W + GAP)).toInt())

    /** the tiles' boxes, the band's (under the row of tile [open]; null = none open) and the part's height */
    class Grid(val tiles: List<Box>, val band: Box?, val height: Float)

    /** [n] tiles across [width]; the band [bandH] tall under the row of tile [open] (-1 = none), the rows after it moved down */
    fun grid(n: Int, width: Float, open: Int, bandH: Float): Grid {
        val cols = columns(width)
        val tw = (width - 2f * MARGIN - (cols - 1) * GAP) / cols
        val rows = if (n <= 0) 0 else ceil(n / cols.toFloat()).toInt()
        val openRow = if (open in 0 until n) open / cols else -1
        val push = if (openRow >= 0) bandH + GAP else 0f
        val tiles = (0 until n).map { i ->
            val row = i / cols; val col = i % cols
            val y = TOP + row * (TILE_H + GAP) + (if (openRow in 0 until row) push else 0f)
            val x = MARGIN + col * (tw + GAP)
            Box(x, y, x + tw, y + TILE_H)
        }
        val band = if (openRow >= 0) {
            val y = TOP + openRow * (TILE_H + GAP) + TILE_H + GAP
            Box(MARGIN, y, width - MARGIN, y + bandH)
        } else null
        val h = if (rows == 0) 0f else TOP + rows * TILE_H + (rows - 1) * GAP + push + BOTTOM
        return Grid(tiles, band, h)
    }

    /** a tile's picture well */
    fun well(tile: Box): Box = Box(tile.l + WELL_PAD, tile.t + WELL_PAD, tile.r - WELL_PAD, tile.t + WELL_PAD + WELL_H)

    /** the badge's box: the well's top-right corner */
    fun badgeBox(tile: Box): Box { val w = well(tile); return Box(w.r - 4f - BADGE, w.t + 4f, w.r - 4f, w.t + 4f + BADGE) }

    // ---------------------------------------------------------------- the band

    /** the band's picture (the design's 438 x 220), its lines (the touch floor: 110 px), the status line */
    const val PIC_W = 438f
    const val PIC_H = 220f
    const val BAND_PAD = 16f
    const val LINE_H = 110f
    const val LINE_GAP = 10f
    const val STATUS_H = 40f
    const val LABEL_W = 220f
    const val BTN_GAP = 10f
    /** the narrowest half row: a label and three buttons of 120 px */
    const val PAIR_MIN = 560f

    /** one line of a band: [buttons] choices, with a label column before them ([labelled]: a band row) or across ([labelled] false) */
    class Line(val key: String, val buttons: Int, val labelled: Boolean)

    /** a line's boxes: its label column (null = none) and its buttons, left to right */
    class LineBox(val key: String, val label: Box?, val buttons: List<Box>)

    class Band(val picture: Box, val lines: List<LineBox>, val status: Box, val height: Float)

    /**
     * a band [width] wide at ([left], [top]): the picture on the left; the lines beside it while they fit its height, then full width
     * under it two to a row; the one status line where there is room (beside the picture, beside a lone last line, else at the foot)
     */
    fun band(left: Float, top: Float, width: Float, lines: List<Line>): Band {
        val l = left + BAND_PAD
        val r = left + width - BAND_PAD
        val pw = if (width >= 1100f) PIC_W else (r - l) * 0.36f
        val pic = Box(l, top + BAND_PAD, l + pw, top + BAND_PAD + pw * PIC_H / PIC_W)
        val colL = pic.r + 24f
        val out = ArrayList<LineBox>()
        var y = top + BAND_PAD
        var i = 0
        while (i < lines.size && y + LINE_H <= pic.b + 20f) { out += lineBox(lines[i], colL, y, r); y += LINE_H + LINE_GAP; i++ }
        val beside = if (i > 0) y - LINE_GAP else top + BAND_PAD
        var status: Box? = null
        if (i == lines.size && beside + LINE_GAP + STATUS_H <= pic.b) status = Box(colL, beside + LINE_GAP, r, beside + LINE_GAP + STATUS_H)
        var by = max(pic.b, beside) + LINE_GAP
        var bottom = max(pic.b, beside)
        // two to a row while a half still holds a label and three buttons (the room's full page); one to a row on a narrower one
        val half = (r - l - GAP) / 2f
        val pairs = half >= PAIR_MIN
        while (i < lines.size) {
            val x1 = if (pairs) l + half else r
            out += lineBox(lines[i], l, by, x1)
            if (pairs && i + 1 < lines.size) out += lineBox(lines[i + 1], l + half + GAP, by, r)
            else if (pairs && status == null) status = Box(l + half + GAP + 12f, by + (LINE_H - STATUS_H) / 2f, r, by + (LINE_H + STATUS_H) / 2f)
            bottom = by + LINE_H
            by += LINE_H + LINE_GAP
            i += if (pairs) 2 else 1
        }
        if (status == null) { status = Box(l, bottom + LINE_GAP, r, bottom + LINE_GAP + STATUS_H); bottom = status.b }
        return Band(pic, out, status, bottom + BAND_PAD - top)
    }

    /** the page's scroll that brings [from]..[to] (a tile and its band, content px) into a view [vh] tall now at [y0]: the least move;
     *  when they do not fit together, the tile at the top (its band runs on under it); null = in view already */
    fun reveal(y0: Float, vh: Float, from: Float, to: Float): Float? = when {
        to - from > vh -> if (from == y0) null else from
        from < y0 -> from
        to > y0 + vh -> to - vh
        else -> null
    }

    private fun lineBox(line: Line, x0: Float, y: Float, x1: Float): LineBox {
        val lw = if (line.labelled) min(LABEL_W, (x1 - x0) * 0.26f) else 0f
        val label = if (line.labelled) Box(x0, y, x0 + lw, y + LINE_H) else null
        val bx = if (line.labelled) x0 + lw + 12f else x0
        val n = max(1, line.buttons)
        val bw = (x1 - bx - (n - 1) * BTN_GAP) / n
        return LineBox(line.key, label, (0 until n).map { k -> Box(bx + k * (bw + BTN_GAP), y, bx + k * (bw + BTN_GAP) + bw, y + LINE_H) })
    }
}
