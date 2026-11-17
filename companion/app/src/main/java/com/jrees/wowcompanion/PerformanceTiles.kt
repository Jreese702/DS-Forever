package com.jrees.wowcompanion

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/**
 * SETTINGS > PERFORMANCE AS PICTURE TILES (the user, 2026-11-17: "I REALLY REALLY love what you did with the top screen settings menu
 * with the pictures and want to do something similar to the other menus ... an ultra code 'beautification' task"; the beautify design's
 * section 6 with critic C6 / C10): ONE HEADLESS ROW of exactly five columns - Frame limit, Frame generation (wide), Memory (a pack of
 * three On / Off lines), Indoor maps - then the Stream settings compact row. The Top screen's tile language unchanged: a tap or A on a
 * tile opens ONE band under the row (the picture large, the tile's lines, one status line); B closes it, the cursor back on the tile.
 *
 * The model, pure (no android.graphics), so the off-device tests measure it: the fixed slots, the lines each band carries NOW (a line
 * that cannot act is not drawn: Restart only while a change waits, Lossless.dll only while generation is on or before a copy is set,
 * the deferred list only with deferred buildings), the tile's words, its badge, the grid with the wide slot. The writes are the old
 * rows' own (FrameGen, Settings, the host's Choose / Restart): only where and how they are drawn changed.
 */
object PerformanceTileModel {
    const val LIMIT = "frame_limit"
    const val FG = "frame_gen"
    const val MEM = "memory"
    const val INDOOR = "indoor"

    /** a slot: [wide] spans two columns (the design's three wide tiles: Theme, Smart Y, Frame generation) */
    data class Slot(val id: String, val wide: Boolean = false)

    /** the tab's slots, fixed: 1 + 2 + 1 + 1 = the room's five columns */
    val SLOTS = listOf(Slot(LIMIT), Slot(FG, wide = true), Slot(MEM), Slot(INDOOR))

    /** a tile's name (display: Tr.t by the drawer) */
    val NAMES = mapOf(LIMIT to "Frame limit", FG to "Frame generation", MEM to "Memory", INDOOR to "Indoor maps")

    /** what the tiles show and their lines offer, read once per draw from FrameGen / Settings / the bake's status */
    data class State(
        val limit: Int = 40,
        /** a restart would change the running game's frame limit / frame generation (the game runs, no restart under way) */
        val limitWaits: Boolean = false, val fgWaits: Boolean = false,
        val hasDll: Boolean = false, val fgOn: Boolean = false, val multiplier: Int = 2, val flow: Float = FrameGen.FLOW_DEFAULT,
        val perfMode: Boolean = true, val lastPick: String = "", val limitNote: Boolean = false,
        val lighter: Boolean = false, val lighterFixed: Boolean = false, val lowGfx: Boolean = false, val mapFull: Boolean = false,
        /** Low-memory graphics was changed in this visit (it applies from the next game start) */
        val lowGfxChanged: Boolean = false,
        /** [progress] the bake's phase only (the count is the tile's value and the ring, the deferred count its own line) */
        val indoorOn: Boolean = true, val ready: Int = 0, val total: Int = 0, val deferred: Int = 0, val progress: String = "",
        val terrain: String? = null,
        val streamOn: Boolean = false,
    ) {
        /** frame generation is really on (a copy of the DLL and the switch) */
        val generating get() = fgOn && hasDll
    }

    enum class Kind { CHOICE, STEPPER, ACTION }

    /**
     * one line of a band: [label] null = the tile's own choice (no label column, as Top's first line); [value] under the label (a
     * stepper's value, the DLL's state, the deferred count); [words] the buttons; [chosen] the lit one (-1 = none)
     */
    data class Line(val key: String, val label: String?, val value: String?, val words: List<String>, val chosen: Int, val kind: Kind,
                    /** buttons that cannot act now (a stepper at its bound): drawn disabled, no cursor stop, a tap does nothing */
                    val off: Set<Int> = emptySet())

    // the lines' keys (the view's presses)
    const val L_LIMIT = "limit"; const val L_RESTART = "restart"
    const val L_FG = "fg"; const val L_MULT = "mult"; const val L_FLOW = "flow"; const val L_PERF = "perf"; const val L_DLL = "dll"
    const val L_LIGHTER = "lighter"; const val L_LOWGFX = "lowgfx"; const val L_MAP = "homemap"
    const val L_BUILD = "build"; const val L_DEFERRED = "deferred"

    private val ON_OFF = listOf("On", "Off")

    /** [id]'s band lines now, for [s] (no dead stops: a line that cannot act is not there) */
    fun lines(id: String, s: State, deferredOpen: Boolean = false): List<Line> = when (id) {
        LIMIT -> buildList {
            add(Line(L_LIMIT, null, null, FrameGen.LIMITS.map { FrameGen.limitLabel(it) }, FrameGen.LIMITS.indexOf(s.limit), Kind.CHOICE))
            if (s.limitWaits || s.fgWaits) add(restart())
        }
        FG -> buildList {
            if (s.hasDll) add(Line(L_FG, null, null, ON_OFF, if (s.fgOn) 0 else 1, Kind.CHOICE))
            if (s.generating) {
                add(Line(L_MULT, "Multiplier", null, FrameGen.MULTIPLIERS.map { "${it}x" }, FrameGen.MULTIPLIERS.indexOf(s.multiplier), Kind.CHOICE))
                // the old row's - / + (no reset: the same two writes), each disabled at its bound as the old squares were
                add(Line(L_FLOW, "Flow scale", FrameGen.flowLabel(s.flow), listOf("Faster", "Sharper"), -1, Kind.STEPPER,
                    off = setOfNotNull(0.takeIf { s.flow <= FrameGen.FLOW_MIN + 1e-4f }, 1.takeIf { s.flow >= FrameGen.FLOW_MAX - 1e-4f })))
                add(Line(L_PERF, "Performance mode", null, ON_OFF, if (s.perfMode) 0 else 1, Kind.CHOICE))
            }
            // the player's own copy: the one line before it is set (nothing else can act); after, always (the old row's: a new copy
            // can be chosen with generation Off too)
            add(Line(L_DLL, "Lossless.dll", if (s.hasDll) "Found" else "Not set", listOf("Choose"), -1, Kind.ACTION))
            if (s.limitWaits || s.fgWaits) add(restart())
        }
        MEM -> buildList {
            // the design's order (section 6), all three stacked beside the picture ([band])
            // the 8 / 12 GB Thors: always lighter (the model decides; nothing to press, the status line says so)
            if (!s.lighterFixed) add(Line(L_LIGHTER, "Lighter mode", null, ON_OFF, if (s.lighter) 0 else 1, Kind.CHOICE))
            add(Line(L_LOWGFX, "Low-memory graphics", null, ON_OFF, if (s.lowGfx) 0 else 1, Kind.CHOICE))
            add(Line(L_MAP, "Home map: full detail", null, ON_OFF, if (s.mapFull) 0 else 1, Kind.CHOICE))
        }
        INDOOR -> buildList {
            add(Line(L_BUILD, "Build in the background", null, ON_OFF, if (s.indoorOn) 0 else 1, Kind.CHOICE))
            if (s.deferred > 0) add(Line(L_DEFERRED, "Deferred", "${s.deferred}", listOf(if (deferredOpen) "Hide" else "List"), -1, Kind.ACTION))
        }
        else -> emptyList()
    }

    private fun restart() = Line(L_RESTART, "Restart the game", null, listOf("Restart"), -1, Kind.ACTION)

    /** the tile's value word (Friz 22, gold) */
    fun value(id: String, s: State): String = when (id) {
        LIMIT -> if (s.limit <= 0) "Off" else "${s.limit} fps"
        FG -> when { !s.hasDll -> "Not set"; s.fgOn -> "${s.multiplier}x"; else -> "Off" }
        MEM -> when { s.lighter || s.lighterFixed -> "Lighter"; s.mapFull -> "Full map"; else -> "Zone map" }
        INDOOR -> when { !s.indoorOn -> "Off"; s.total > 0 -> "${s.ready} / ${s.total}"; else -> "On" }
        else -> ""
    }

    /**
     * the 36 px badge, Top's meanings only: the hourglass while the running game waits for a restart. Top's eye means "shown in the
     * game", which none of these tiles is: Off reads from the dimmed picture and the value word instead (Memory's leaf: the view's)
     */
    fun badge(id: String, s: State): GameUiTiles.Badge? = when (id) {
        LIMIT -> if (s.limitWaits) GameUiTiles.Badge.SENT else null
        FG -> if (s.fgWaits) GameUiTiles.Badge.SENT else null
        else -> null
    }

    /** the picture dimmed: the choice is Off (frame generation, the indoor build) */
    fun dimmed(id: String, s: State): Boolean = when (id) { FG -> !s.generating; INDOOR -> !s.indoorOn; else -> false }

    /** the band's one status line: state only (never an explanation) */
    fun status(id: String, s: State): String = when (id) {
        // a waiting change: the Restart line and the hourglass say it, no line under them
        LIMIT -> when {
            s.limitWaits || s.fgWaits -> ""
            s.limit > 0 && s.limit !in FrameGen.LIMITS -> "${s.limit} fps, set in the launcher's menu"
            s.limitNote && s.limit == FrameGen.LIMIT_MIGRATED -> "Set to 40 for battery and heat"
            else -> ""
        }
        FG -> when {
            s.limitWaits || s.fgWaits -> ""
            s.lastPick.isNotEmpty() -> s.lastPick
            else -> ""
        }
        MEM -> when {
            s.lighterFixed -> "Lighter mode: always on for this Thor"
            s.lowGfxChanged -> "Low-memory graphics: from the next game start"
            else -> ""
        }
        INDOOR -> listOfNotNull(s.progress.takeIf { it.isNotEmpty() }, s.terrain).joinToString(" · ")
        else -> ""
    }

    /** the status line's parts, each its own words (Tr.t per part: a joined line would never match a template) */
    fun statusParts(id: String, s: State): List<String> =
        if (id == INDOOR) listOfNotNull(s.progress.takeIf { it.isNotEmpty() }, s.terrain) else listOf(status(id, s)).filter { it.isNotEmpty() }

    // ---------------------------------------------------------------- Indoor maps' deferred list (Deferred > List)

    /** the list across the band's width under its lines: one line per reason with its count (every deferred building counted) */
    const val LIST_LINE = 40f
    const val LIST_PAD = 18f

    /** the list's height for [shown] lines and a "+ n" line when the bake deferred more than the list carries */
    fun listH(shown: Int, more: Int): Float = if (shown <= 0) 0f else 2f * LIST_PAD + (shown + (if (more > 0) 1 else 0)) * LIST_LINE + GameUiTiles.BAND_PAD

    /**
     * the deferred lines ("key: why", IndoorProgress.deferredLine) as the player reads them (verifier 11-18: the buildings' hex ids were
     * debug clutter): the reasons, each once, in the bake's order, with how many buildings it held back. The key never reaches the screen
     */
    fun deferredRows(lines: List<String>): List<Pair<String, Int>> {
        val out = LinkedHashMap<String, Int>()
        for (line in lines) {
            val i = line.indexOf(": ")
            val why = (if (i > 0) line.substring(i + 2) else line).trim().ifEmpty { "deferred" }
            out[why] = (out[why] ?: 0) + 1
        }
        return out.entries.map { it.key to it.value }
    }

    /**
     * button [k] of [line] drawn LIT (the red cloth's highlight, gold word): only a choice's picked button, as on Top. A stepper's and
     * an action's buttons are presses, not states: always the quiet plate, as Top's Narrower / Default / Wider (verifier 11-18: lit
     * Faster AND Sharper read as both picked)
     */
    fun lit(line: Line, k: Int): Boolean = line.kind == Kind.CHOICE && k == line.chosen

    /** the tile animates in its open band (the frame lanes, the build) */
    fun animated(id: String): Boolean = id == LIMIT || id == FG || id == INDOOR

    // ---------------------------------------------------------------- the grid (the Top screen's numbers; a wide slot spans two)

    /** the tiles' top on the page (the tab has no heading over its row) and the compact row's height */
    const val TOP = 20f
    const val ROW_H = 88f

    /**
     * [slots] across [width]: a wide slot spans two columns and the gap between; one that would start in the last column starts the next
     * row. The band [bandH] tall under the row of slot [open] (-1 = none), the rows after it moved down.
     */
    fun grid(slots: List<Slot>, width: Float, open: Int, bandH: Float): GameUiTiles.Grid {
        val cols = GameUiTiles.columns(width)
        val tw = (width - 2f * GameUiTiles.MARGIN - (cols - 1) * GameUiTiles.GAP) / cols
        // the (row, col) of each slot
        val at = ArrayList<Pair<Int, Int>>()
        var row = 0; var col = 0
        for (s in slots) {
            val span = if (s.wide && cols >= 2) 2 else 1
            if (col + span > cols) { row++; col = 0 }
            at += row to col
            col += span
            if (col >= cols) { row++; col = 0 }
        }
        val rows = if (slots.isEmpty()) 0 else at.maxOf { it.first } + 1
        val openRow = if (open in slots.indices) at[open].first else -1
        val push = if (openRow >= 0) bandH + GameUiTiles.GAP else 0f
        val tiles = slots.mapIndexed { i, s ->
            val (r, c) = at[i]
            val span = if (s.wide && cols >= 2) 2 else 1
            val y = TOP + r * (GameUiTiles.TILE_H + GameUiTiles.GAP) + (if (openRow in 0 until r) push else 0f)
            val x = GameUiTiles.MARGIN + c * (tw + GameUiTiles.GAP)
            GameUiTiles.Box(x, y, x + span * tw + (span - 1) * GameUiTiles.GAP, y + GameUiTiles.TILE_H)
        }
        val band = if (openRow >= 0) {
            val y = TOP + openRow * (GameUiTiles.TILE_H + GameUiTiles.GAP) + GameUiTiles.TILE_H + GameUiTiles.GAP
            GameUiTiles.Box(GameUiTiles.MARGIN, y, width - GameUiTiles.MARGIN, y + bandH)
        } else null
        val h = if (rows == 0) 0f else TOP + rows * GameUiTiles.TILE_H + (rows - 1) * GameUiTiles.GAP + push + GameUiTiles.BOTTOM
        return GameUiTiles.Grid(tiles, band, h)
    }

    /** an action line's one button: from the label column, at most this wide (a full-width red bar reads as a heading) */
    const val ACTION_W = 300f

    /** a labelled line's label column at most this wide, while its buttons keep [BTN_MIN] each (Memory's and Frame generation's labels
     *  are longer than Top's: "Low-memory graphics", "Performance mode" were cut at Top's 26 %; "Home map: full detail" on one line) */
    const val LABEL_MAX = 360f
    const val BTN_MIN = 150f

    /** the band's boxes for [lines] (GameUiTiles.band; a label widened while its buttons keep [BTN_MIN]; an action's one button
     *  [ACTION_W] wide after its label) */
    fun band(left: Float, top: Float, width: Float, lines: List<Line>): GameUiTiles.Band {
        val b = if (lines.size == STACK) stacked(left, top, width, lines)
            else GameUiTiles.band(left, top, width, lines.map { GameUiTiles.Line(it.key, it.words.size, it.label != null) })
        val out = b.lines.mapIndexed { i, lb ->
            val line = lines.getOrNull(i)
            val lab = lb.label
            if (line == null || lab == null) return@mapIndexed lb
            val r = lb.buttons.last().r
            val n = lb.buttons.size
            val action = line.kind == Kind.ACTION
            val room = r - lab.l - GameUiTiles.BTN_GAP * (n - 1) - 12f - (if (action) ACTION_W else n * BTN_MIN)
            val lw = maxOf(lab.w, minOf(LABEL_MAX, room))
            val label = GameUiTiles.Box(lab.l, lab.t, lab.l + lw, lab.b)
            val x0 = label.r + 12f
            val buttons = if (action) listOf(GameUiTiles.Box(x0, lab.t, min(r, x0 + ACTION_W), lab.b))
                else { val bw = (r - x0 - (n - 1) * GameUiTiles.BTN_GAP) / n
                    (0 until n).map { k -> GameUiTiles.Box(x0 + k * (bw + GameUiTiles.BTN_GAP), lab.t, x0 + k * (bw + GameUiTiles.BTN_GAP) + bw, lab.b) } }
            GameUiTiles.LineBox(lb.key, label, buttons)
        }
        return GameUiTiles.Band(b.picture, out, b.status, b.height)
    }

    /** a band of exactly this many lines stacks them all beside the picture (Top's layout would drop the third under the picture,
     *  alone on the left: verifier 11-18, "Memory band layout is uneven") */
    const val STACK = 3

    /** [lines] in ONE column beside the picture, the picture centred on them, the status line at the foot (Top's numbers) */
    private fun stacked(left: Float, top: Float, width: Float, lines: List<Line>): GameUiTiles.Band {
        val l = left + GameUiTiles.BAND_PAD
        val r = left + width - GameUiTiles.BAND_PAD
        val pw = if (width >= 1100f) GameUiTiles.PIC_W else (r - l) * 0.36f
        val ph = pw * GameUiTiles.PIC_H / GameUiTiles.PIC_W
        val linesH = lines.size * GameUiTiles.LINE_H + (lines.size - 1) * GameUiTiles.LINE_GAP
        val y0 = top + GameUiTiles.BAND_PAD
        val py = y0 + maxOf(0f, (linesH - ph) / 2f)
        val pic = GameUiTiles.Box(l, py, l + pw, py + ph)
        val colL = pic.r + 24f
        val out = lines.mapIndexed { i, line ->
            val y = y0 + i * (GameUiTiles.LINE_H + GameUiTiles.LINE_GAP)
            val n = maxOf(1, line.words.size)
            val lw = if (line.label != null) min(GameUiTiles.LABEL_W, (r - colL) * 0.26f) else 0f
            val label = if (line.label != null) GameUiTiles.Box(colL, y, colL + lw, y + GameUiTiles.LINE_H) else null
            val bx = if (line.label != null) colL + lw + 12f else colL
            val bw = (r - bx - (n - 1) * GameUiTiles.BTN_GAP) / n
            GameUiTiles.LineBox(line.key, label, (0 until n).map { k -> GameUiTiles.Box(bx + k * (bw + GameUiTiles.BTN_GAP), y, bx + k * (bw + GameUiTiles.BTN_GAP) + bw, y + GameUiTiles.LINE_H) })
        }
        val bottom = maxOf(pic.b, y0 + linesH)
        val status = GameUiTiles.Box(l, bottom + GameUiTiles.LINE_GAP, r, bottom + GameUiTiles.LINE_GAP + GameUiTiles.STATUS_H)
        return GameUiTiles.Band(pic, out, status, status.b + GameUiTiles.BAND_PAD - top)
    }

    /** the part's height: the grid (with the band) and the compact row under it */
    fun height(gridH: Float): Float = gridH + ROW_H + GameUiTiles.BOTTOM

    /** the row count of [slots] across [width] (the tests) */
    fun rows(slots: List<Slot>, width: Float): Int = grid(slots, width, -1, 0f).tiles.map { it.t }.distinct().size

    /** columns a slot list fills (the tests: the row is exactly five) */
    fun span(slots: List<Slot>): Int = slots.count() + slots.count { it.wide }
}

/**
 * The Performance tab's body as the room hosts it (the room's only part on the tab, under no heading): the tiles, the open band and the
 * compact Stream settings row. The host's wiring ([host]: Choose for the DLL, Restart, the game running) is the SettingsView's, as the
 * old rows used it. Touches act on RELEASE and only for a finger that did not move (the page scrolls).
 */
class PerformanceTiles(context: Context, private val host: SettingsView) : View(context), BandPart {
    private val M = PerformanceTileModel

    /** a tile's band opened (or moved): the room brings the cursor and the band in view */
    var onOpen: ((PerformanceTiles) -> Unit)? = null

    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val pName by themed { Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = GameUiSection.NAME_SIZE; color = Theme.text } }
    private val pValue by themed { Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; color = Theme.gold } }
    private val pBandLabel by themed { Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 25f; color = Theme.text } }
    private val pSub by themed { Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; color = Theme.textDim } }
    private val pRow by themed { Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 31f; color = Theme.text } }
    private val tilePath = Path()

    /** the open tile, null = none (the session's only: never saved) */
    private var selected: String? = null
    /** Indoor maps' deferred list shown under its band's lines (Deferred > List) */
    private var deferredOpen = false
    /** Low-memory graphics pressed this visit (its status line: from the next game start) */
    private var lowGfxChanged = false
    private var progress: IndoorScheduler.Status? = null
    private var downX = 0f
    private var downY = 0f

    /** the main thread's handler, not View.post: a change while the room is not attached must not wait for it to be */
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private val progressListener: () -> Unit = {
        val s = IndoorSchedulerStep.status(context)
        main.post { progress = s; refresh() }
    }

    init {
        Theme.init(context)
        Theme.onChange { postInvalidate() }
        FrameGen.init(context)
        FrameGen.onChange { main.post { refresh() } }
        Settings.onChange { main.post { refresh() } }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        IndoorSchedulerStep.onChange(progressListener)
        progress = runCatching { IndoorSchedulerStep.status(context) }.getOrNull()
        refresh()
    }

    override fun onDetachedFromWindow() {
        IndoorSchedulerStep.offChange(progressListener)
        PerformancePictures.drop()
        super.onDetachedFromWindow()
    }

    /** the state the tiles show now */
    fun state(): PerformanceTileModel.State {
        val up = runCatching { host.gameRunning() }.getOrDefault(false)
        val restarting = SetupBridge.restarting
        val p = progress
        val lim = FrameGen.fpsLimit
        return PerformanceTileModel.State(
            limit = lim,
            limitWaits = up && !restarting && FrameGen.limitNeedsRestart(FrameGen.launchedLimit, lim),
            fgWaits = FrameGen.showRestart(up, restarting, FrameGen.launched, FrameGen.current()),
            hasDll = FrameGen.hasDll(), fgOn = FrameGen.enabled, multiplier = FrameGen.multiplier, flow = FrameGen.flowScale,
            perfMode = FrameGen.performance, lastPick = FrameGen.lastPick, limitNote = FrameGen.limitNote,
            lighter = Settings.lighterMode, lighterFixed = DeviceTier.smallModel(), lowGfx = Settings.lowMemGraphics,
            mapFull = Settings.homeMapFull, lowGfxChanged = lowGfxChanged,
            indoorOn = Settings.indoorBuild, ready = p?.ready ?: 0, total = p?.total ?: 0,
            deferred = if (p == null || p.list.isEmpty()) 0 else maxOf(p.deferred, p.list.size),
            progress = phase(p, Settings.indoorBuild),
            terrain = Terrain.coverageNow()?.let { c -> val z = c.optInt("zones"); val t = c.optInt("total")
                if (z < t) "Route data $z / $t" else null },
            streamOn = SetupBridge.openStreamSettings != null)
    }

    /**
     * the bake's phase alone (IndoorProgress.line without its count and deferred parts: the tile's value, the ring and the Deferred line
     * carry those), "" while there is nothing to add
     */
    private fun phase(s: IndoorScheduler.Status?, buildOn: Boolean): String {
        if (s == null || !buildOn) return ""
        return when (s.phase) {
            IndoorScheduler.Phase.CATALOGUE -> "listing buildings"
            IndoorScheduler.Phase.BAKING -> if (s.now.isNotEmpty()) "building ${s.now}" else "building"
            IndoorScheduler.Phase.PAUSED -> "paused: ${s.note.ifEmpty { "waiting" }}"
            IndoorScheduler.Phase.DONE -> "all built"
            IndoorScheduler.Phase.DEFERRED -> "finished"
            IndoorScheduler.Phase.STOPPED -> s.note.ifEmpty { "stopped" }
        }
    }

    /** the deferred list shown now (Deferred > List): its reasons with their counts, and how many more the bake deferred than its list
     *  carries */
    private fun deferredShown(): Pair<List<Pair<String, Int>>, Int> {
        val p = progress
        if (!deferredOpen || p == null || p.list.isEmpty()) return emptyList<Pair<String, Int>>() to 0
        return M.deferredRows(p.list) to (p.deferred - p.list.size).coerceAtLeast(0)
    }

    // ---------------------------------------------------------------- layout

    private class Layout(val grid: GameUiTiles.Grid, val band: GameUiTiles.Band?, val lines: List<PerformanceTileModel.Line>,
                         val row: RectF, val s: PerformanceTileModel.State)

    private fun layout(w: Float = width.toFloat()): Layout {
        val s = state()
        val open = selected?.let { id -> M.SLOTS.indexOfFirst { it.id == id } } ?: -1
        val lines = if (open >= 0) M.lines(M.SLOTS[open].id, s, deferredOpen) else emptyList()
        val bw = w - 2f * GameUiTiles.MARGIN
        val (shown, more) = if (open >= 0 && M.SLOTS[open].id == M.INDOOR) deferredShown() else emptyList<Pair<String, Int>>() to 0
        val bandH = if (open >= 0) M.band(GameUiTiles.MARGIN, 0f, bw, lines).height + M.listH(shown.size, more) else 0f
        val grid = M.grid(M.SLOTS, w, open, bandH)
        val band = grid.band?.let { M.band(it.l, it.t, it.w, lines) }
        val row = RectF(GameUiTiles.MARGIN, grid.height, w - GameUiTiles.MARGIN, grid.height + M.ROW_H)
        return Layout(grid, band, lines, row, s)
    }

    /** what decides the height: the open tile and its lines */
    private var shape: List<String> = emptyList()
    private fun shapeNow(): List<String> = listOf(selected ?: "", deferredShown().let { "${it.first.size}+${it.second}" }) +
        (selected?.let { M.lines(it, state(), deferredOpen).map { l -> l.key } } ?: emptyList())

    private fun refresh() {
        if (shapeNow() != shape) requestLayout()
        invalidate()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = getDefaultSize(suggestedMinimumWidth, widthSpec)
        shape = shapeNow()
        setMeasuredDimension(w, M.height(layout(w.toFloat()).grid.height).toInt())
    }

    private fun GameUiTiles.Box.rect() = RectF(l, t, r, b)

    // ---------------------------------------------------------------- the room's hooks (SettingsPad)

    /** the cursor's stops: the tiles in reading order, the open band's buttons, the Stream settings button (while it can act) */
    fun focusTargets(): List<RectF> {
        val l = layout()
        val out = ArrayList<RectF>()
        l.grid.tiles.forEach { out.add(it.rect()) }
        l.band?.lines?.forEachIndexed { i, line ->
            val off = l.lines.getOrNull(i)?.off.orEmpty()
            line.buttons.forEachIndexed { k, b -> if (k !in off) out.add(b.rect()) }
        }
        if (l.s.streamOn) out.add(streamBox(l.row))
        return out
    }

    override val hasSelection get() = selected != null
    /** the tile open now (the tests) */
    val openTile: String? get() = selected
    /** the tile ids in order (the tests) */
    fun tileIds(): List<String> = M.SLOTS.map { it.id }

    /** B: the open band closes; false = none was open */
    override fun closeSelection(): Boolean {
        if (selected == null) return false
        selected = null; deferredOpen = false; requestLayout(); invalidate()
        return true
    }

    /** the open band's tile in this view's coordinates (B puts the cursor back there) */
    override fun selectionAnchor(): RectF? {
        val id = selected ?: return null
        val i = M.SLOTS.indexOfFirst { it.id == id }
        return if (i < 0) null else layout().grid.tiles[i].rect()
    }

    /** the open band's box (the room scrolls it into view), null = none */
    override fun bandBox(): RectF? = if (selected == null) null else layout().grid.band?.rect()

    // ---------------------------------------------------------------- touch

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
            MotionEvent.ACTION_UP -> {
                if (abs(e.x - downX) > GameUiSection.SLOP || abs(e.y - downY) > GameUiSection.SLOP) return true
                tap(e.x, e.y)
            }
        }
        return true
    }

    private fun tap(x: Float, y: Float) {
        val l = layout()
        l.band?.lines?.forEachIndexed { i, line ->
            val k = line.buttons.indexOfFirst { it.contains(x, y) }
            if (k >= 0) { l.lines.getOrNull(i)?.let { if (k !in it.off) press(it, k) }; refresh(); return }
        }
        val i = l.grid.tiles.indexOfFirst { it.contains(x, y) }
        if (i >= 0) {
            val id = M.SLOTS[i].id
            selected = if (selected == id) null else id
            deferredOpen = false
            requestLayout(); invalidate()
            if (selected != null) onOpen?.invoke(this)
            return
        }
        if (l.s.streamOn && streamBox(l.row).contains(x, y)) { SetupBridge.openStreamSettings?.invoke(); return }
        // a tap outside the band (and its tiles) closes it, as B does
        val band = l.grid.band
        if (band != null && !band.contains(x, y)) closeSelection()
    }

    /** button [k] of [line]: one player-pressed write, the old rows' own */
    private fun press(line: PerformanceTileModel.Line, k: Int) {
        when (line.key) {
            M.L_LIMIT -> FrameGen.askLimit(FrameGen.LIMITS[k])
            M.L_FG -> if (k == 0) { if (FrameGen.hasDll()) FrameGen.enabled = true } else FrameGen.enabled = false
            M.L_MULT -> FrameGen.multiplier = FrameGen.MULTIPLIERS[k]
            M.L_FLOW -> FrameGen.flowScale = FrameGen.stepFlow(FrameGen.flowScale, if (k == 0) -1 else 1)
            M.L_PERF -> FrameGen.performance = k == 0
            M.L_DLL -> host.onChooseDll()
            M.L_RESTART -> host.onRestart()
            M.L_LIGHTER -> if (!DeviceTier.smallModel()) Settings.lighterMode = k == 0
            M.L_LOWGFX -> { Settings.lowMemGraphics = k == 0; lowGfxChanged = true }
            M.L_MAP -> Settings.homeMapFull = k == 0
            M.L_BUILD -> if (k == 0) { Settings.indoorBuild = true; IndoorSchedulerStep.settingChanged() } else Settings.indoorBuild = false
            M.L_DEFERRED -> deferredOpen = !deferredOpen
        }
    }

    // ---------------------------------------------------------------- drawing

    private fun fonts() {
        UiKit.asBody(pName); UiKit.asBody(pValue); UiKit.asBody(pBandLabel); UiKit.asBody(pSub); UiKit.asBody(pRow)
    }

    override fun onDraw(c: Canvas) {
        fonts()
        // feat/beautify (themes 8.4): no page ink - the room's RoomGround.settings (the dimmed rock) shows through
        val l = layout()
        // one name size for the row (a long es / fr name shrinks every name with it, never one alone)
        val nameSize = M.SLOTS.withIndex().minOf { (i, slot) ->
            fitSize(pName, Tr.t(M.NAMES[slot.id] ?: slot.id), l.grid.tiles[i].w - 2f * GameUiTiles.WELL_PAD - 4f, GameUiSection.NAME_SIZE, GameUiSection.NAME_MIN) }
        for ((i, slot) in M.SLOTS.withIndex()) drawTile(c, l.grid.tiles[i], slot.id, l.s, slot.id == selected, nameSize)
        val open = selected?.let { id -> M.SLOTS.indexOfFirst { it.id == id } } ?: -1
        val band = l.band
        if (open >= 0 && band != null) {
            drawBand(c, l.grid.band!!, band, l.grid.tiles[open], selected!!, l.lines, l.s)
            // the open band's picture moves (up to 30 fps), only while the room shows it
            if (M.animated(selected!!) && isShown) postInvalidateDelayed(FRAME_MS)
        }
        drawRow(c, l.row, l.s)
    }

    private fun picture(c: Canvas, id: String, s: PerformanceTileModel.State, well: RectF, t: Float, big: Boolean = false) {
        pFill.color = Color.rgb(14, 16, 18)
        c.drawRoundRect(well, 8f, 8f, pFill)
        PerformancePictures.draw(c, context, id, well, s, t, big) { postInvalidate() }
        if (M.dimmed(id, s)) {
            pFill.color = Theme.ink; pFill.alpha = 165
            c.drawRoundRect(well, 8f, 8f, pFill)
            pFill.alpha = 255
        }
        pStroke.color = Color.argb(200, 0, 0, 0); pStroke.strokeWidth = 2f
        c.drawRoundRect(well, 8f, 8f, pStroke)
    }

    private fun drawTile(c: Canvas, box: GameUiTiles.Box, id: String, s: PerformanceTileModel.State, open: Boolean, nameSize: Float) {
        val rect = box.rect()
        pFill.color = if (open) Theme.selected else Theme.raised
        c.drawRoundRect(rect, 10f, 10f, pFill)
        picture(c, id, s, GameUiTiles.well(box).rect(), 0f)
        M.badge(id, s)?.let { TilePictures.badge(c, GameUiTiles.badgeBox(box).rect(), it) }
        if (id == M.MEM && (s.lighter || s.lighterFixed)) PerformancePictures.leafBadge(c, GameUiTiles.badgeBox(box).rect())
        edge(c, rect, open)
        val x = box.l + GameUiTiles.WELL_PAD + 2f
        val maxW = box.w - 2f * GameUiTiles.WELL_PAD - 4f
        pName.color = Theme.text
        c.drawText(fitText(pName, Tr.t(M.NAMES[id] ?: id), maxW, nameSize, GameUiSection.NAME_MIN), x,
            box.t + GameUiTiles.WELL_PAD + GameUiTiles.WELL_H + 34f, pName)
        pValue.color = if (M.dimmed(id, s)) Theme.textDim else Theme.gold
        c.drawText(fitText(pValue, Tr.t(M.value(id, s)), maxW, 22f, 18f), x, box.b - 12f, pValue)
    }

    /** the game's metal ring (the kit's ring_square), lit for the open tile; the drawn ring without it (GameUiSection's) */
    private fun edge(c: Canvas, r: RectF, lit: Boolean) {
        if (UiKit.ringBox(c, r, 0.8f, lit = lit)) return
        val w = UiKit.RING_W * 0.8f
        tilePath.reset()
        tilePath.addRoundRect(RectF(r.left + w / 2f, r.top + w / 2f, r.right - w / 2f, r.bottom - w / 2f), 9f, 9f, Path.Direction.CW)
        UiKit.ringEdge(c, tilePath, w, lit = lit)
    }

    private fun drawBand(c: Canvas, box: GameUiTiles.Box, band: GameUiTiles.Band, tile: GameUiTiles.Box, id: String,
                         lines: List<PerformanceTileModel.Line>, s: PerformanceTileModel.State) {
        val rect = box.rect()
        pFill.color = Theme.panel
        c.drawRoundRect(rect, 12f, 12f, pFill)
        edge(c, rect, false)
        // the notch under the open tile, so the band reads as that tile's
        val nx = tile.cx
        tilePath.reset(); tilePath.moveTo(nx - 18f, rect.top + 3f); tilePath.lineTo(nx, rect.top - 13f); tilePath.lineTo(nx + 18f, rect.top + 3f); tilePath.close()
        c.drawPath(tilePath, pFill)
        pStroke.color = Theme.gold; pStroke.strokeWidth = 2.6f
        c.drawLine(nx - 18f, rect.top + 1f, nx, rect.top - 13f, pStroke); c.drawLine(nx, rect.top - 13f, nx + 18f, rect.top + 1f, pStroke)
        val t = SystemClock.uptimeMillis() / 1000f
        picture(c, id, s, band.picture.rect(), t, big = true)
        // one label size for the whole band (the longest es / fr label sets it); the buttons' words Top's 27 px, ONE size per line: a
        // two-word button too long for its plate wraps onto two lines, a one-word one shrinks its line only (verifier 11-18: one long
        // word must not shrink the whole band)
        val labelSize = labelSize(band, lines)
        for ((i, lb) in band.lines.withIndex()) {
            val line = lines.getOrNull(i) ?: continue
            lb.label?.let { lab -> label(c, lab, line, labelSize) }
            val words = lb.buttons.indices.map { k -> Tr.t(line.words.getOrElse(k) { "" }) }
            val size = lb.buttons.withIndex().minOfOrNull { (k, b) -> wordFit(words[k], plateRoom(b.rect())) } ?: WORD_SIZE
            for ((k, b) in lb.buttons.withIndex()) plate(c, b.rect(), words[k], M.lit(line, k), k !in line.off, size)
        }
        val st = band.status
        val say = M.statusParts(id, s).joinToString(" · ") { cap(Tr.t(it)) }
        if (say.isNotEmpty()) {
            pSub.color = Theme.textDim
            c.drawText(fitText(pSub, say, st.w - 8f, 22f, 18f), st.l + 4f, st.cy + 8f, pSub)
        }
        if (id == M.INDOOR) deferredList(c, rect)
    }

    /** Deferred > List: across the band's width under its lines, one line per reason (capitalised, Tr.t) with its count right-aligned
     *  in gold; no building ids (debug words, never the player's) */
    private fun deferredList(c: Canvas, band: RectF) {
        val (shown, more) = deferredShown()
        if (shown.isEmpty()) return
        val h = M.listH(shown.size, more) - GameUiTiles.BAND_PAD
        val r = RectF(band.left + GameUiTiles.BAND_PAD, band.bottom - GameUiTiles.BAND_PAD - h, band.right - GameUiTiles.BAND_PAD, band.bottom - GameUiTiles.BAND_PAD)
        pFill.color = Theme.raised
        c.drawRoundRect(r, 8f, 8f, pFill)
        pStroke.color = Theme.bronzeDim; pStroke.strokeWidth = 1.5f
        c.drawRoundRect(r, 8f, 8f, pStroke)
        val nP = UiKit.numberPaint(24f, Theme.gold, shadow = false).apply { textAlign = Paint.Align.RIGHT }
        val nW = nP.measureText("000") + 16f
        for ((i, row) in shown.withIndex()) {
            val (why, n) = row
            val y = r.top + M.LIST_PAD + (i + 1) * M.LIST_LINE - 11f
            pBandLabel.color = Theme.text
            c.drawText(fitText(pBandLabel, cap(Tr.t(why)), r.width() - 2f * M.LIST_PAD - nW, 24f, 18f), r.left + M.LIST_PAD, y, pBandLabel)
            c.drawText("$n", r.right - M.LIST_PAD, y, nP)
            if (i < shown.size - 1 || more > 0) {
                pFill.color = Theme.bronzeDim; pFill.alpha = 90
                c.drawRect(r.left + M.LIST_PAD, y + 13f, r.right - M.LIST_PAD, y + 14f, pFill)
                pFill.alpha = 255
            }
        }
        if (more > 0) {
            pSub.color = Theme.textDim
            c.drawText("+ $more", r.left + M.LIST_PAD, r.top + M.LIST_PAD + (shown.size + 1) * M.LIST_LINE - 11f, pSub)
        }
    }

    /** [s] with its first letter a capital (the bake's reasons and phases are lower-case words; the page's labels are not) */
    private fun cap(s: String): String = s.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    private val pWord = Paint(Paint.ANTI_ALIAS_FLAG)

    /** the room a word has on plate [r] (UiKit.choice's own: the plate's ends) */
    private fun plateRoom(r: RectF) = r.width() - r.height() * 0.5f

    /** the largest size from Top's 27 px at which [word] fits [room]: on one line, or (two words or more) on two lines down to 22 px;
     *  else one line shrunk down to 16 px */
    private fun wordFit(word: String, room: Float): Float {
        UiKit.asBody(pWord)
        var size = WORD_SIZE
        pWord.textSize = size
        if (word.isEmpty() || pWord.measureText(word) <= room) return size
        if (word.contains(' ')) {
            while (size >= WORD_WRAP_MIN) {
                pWord.textSize = size
                val rows = wrap(pWord, word, room)
                if (rows.size == 2 && rows.all { pWord.measureText(it) <= room }) return size
                size -= 1f
            }
            size = WORD_SIZE
        }
        pWord.textSize = size
        while (pWord.measureText(word) > room && size > WORD_MIN) { size -= 1f; pWord.textSize = size }
        return size
    }

    /**
     * one band button: the game's red cloth, LIT only for a choice's picked button ([PerformanceTileModel.lit]); the word at the line's
     * one [size] in the plate's own colours (UiKit.choice's), on two lines when it does not fit one at that size
     */
    private fun plate(c: Canvas, r: RectF, word: String, lit: Boolean, enabled: Boolean, size: Float) {
        UiKit.choice(c, r, "", lit, enabled)
        if (word.isEmpty()) return
        val p = UiKit.bodyPaint(size, if (!enabled) Theme.dim else if (lit) Theme.btnLabel else Theme.textDim).apply { textAlign = Paint.Align.CENTER }
        val room = plateRoom(r)
        val cy = r.centerY() - 2f
        val rows = if (p.measureText(word) <= room || !word.contains(' ')) listOf(word) else wrap(p, word, room).let {
            if (it.size <= 2) it else listOf(it[0], it.drop(1).joinToString(" ")) }
        val lh = size * 1.12f
        var y = cy - (rows.size - 1) * lh / 2f - (p.ascent() + p.descent()) / 2f
        for (row in rows) { c.drawText(row, r.centerX(), y, p); y += lh }
    }

    /** the room a line's words have in its label column (after its sign) */
    private fun labelRoom(lb: GameUiTiles.Box, line: PerformanceTileModel.Line): Float =
        lb.r - lb.l - 10f - (if (PerformancePictures.lineSign(line.key)) 40f else 0f)

    /** the band's label size: the largest from 25 down to 17 at which every label fits its column in two lines at most */
    private fun labelSize(band: GameUiTiles.Band, lines: List<PerformanceTileModel.Line>): Float {
        var size = LABEL_SIZE
        for ((i, lb) in band.lines.withIndex()) {
            val lab = lb.label ?: continue
            val line = lines.getOrNull(i) ?: continue
            val words = Tr.t(line.label ?: "")
            val room = labelRoom(lab, line)
            while (size > LABEL_MIN) {
                pBandLabel.textSize = size
                if (wrap(pBandLabel, words, room).let { it.size <= 2 && it.all { w -> pBandLabel.measureText(w) <= room } }) break
                size -= 1f
            }
        }
        return size
    }

    /** [s] in lines no wider than [w] (word by word; a word wider than [w] stays whole: the caller's fit check sees it) */
    private fun wrap(p: Paint, s: String, w: Float): List<String> {
        val out = ArrayList<String>()
        var cur = ""
        for (word in s.split(' ').filter { it.isNotEmpty() }) {
            val next = if (cur.isEmpty()) word else "$cur $word"
            if (cur.isEmpty() || p.measureText(next) <= w) cur = next else { out += cur; cur = word }
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    /** a band line's label column: its sign (Memory's pack: leaf / chip / map, so each line reads at a glance), the words (two lines at
     *  most, at the band's one [size]), the value under them */
    private fun label(c: Canvas, lb: GameUiTiles.Box, line: PerformanceTileModel.Line, size: Float) {
        var x = lb.l + 4f
        if (PerformancePictures.lineSign(line.key)) {
            PerformancePictures.drawLineSign(c, line.key, x + 16f, lb.cy, 30f)
            x += 40f
        }
        val room = labelRoom(lb, line)
        pBandLabel.color = Theme.text
        pBandLabel.textSize = size
        var rows = wrap(pBandLabel, Tr.t(line.label ?: ""), room)
        if (rows.size > 2) rows = listOf(rows[0], rows.drop(1).joinToString(" "))
        rows = rows.map { if (pBandLabel.measureText(it) > room) fitText(pBandLabel, it, room, size, size) else it }
        val lh = size * 1.18f
        val valueH = if (line.value != null) 28f else 0f
        val total = rows.size * lh + valueH
        var y = lb.cy - total / 2f + lh * 0.8f
        for (row in rows) { c.drawText(row, x, y, pBandLabel); y += lh }
        if (line.value != null) {
            pValue.color = Theme.gold
            c.drawText(fitText(pValue, Tr.t(line.value), room, 22f, 18f), x, y - lh * 0.8f + 24f, pValue)
        }
    }

    /** the compact row: a hairline over it, the label, ONE button right-aligned */
    private fun drawRow(c: Canvas, row: RectF, s: PerformanceTileModel.State) {
        pFill.color = Theme.bronzeDim
        c.drawRect(row.left, row.top, row.right, row.top + 1f, pFill)
        pRow.color = if (s.streamOn) Theme.text else Theme.textDim
        c.drawText(Tr.t("Stream settings"), row.left + 28f, row.centerY() + 11f, pRow)
        val b = streamBox(row)
        UiKit.button(c, b, "Open", if (s.streamOn) UiKit.Button.UP else UiKit.Button.DISABLED)
    }

    private fun streamBox(row: RectF) = RectF(row.right - 12f - STREAM_W, row.centerY() - 34f, row.right - 12f, row.centerY() + 34f)

    /** the size [s] fits [maxW] at, from [size] down to [min] */
    private fun fitSize(p: Paint, s: String, maxW: Float, size: Float, min: Float): Float {
        p.textSize = size
        while (p.measureText(s) > maxW && p.textSize > min) p.textSize -= 1f
        return p.textSize
    }

    /** [s] fitted to [maxW]: shrunk from [size] to [min], then cut with an ellipsis (es / fr run long) */
    private fun fitText(p: Paint, s: String, maxW: Float, size: Float, min: Float): String {
        p.textSize = size
        while (p.measureText(s) > maxW && p.textSize > min) p.textSize -= 1f
        if (p.measureText(s) <= maxW) return s
        val n = p.breakText(s, true, (maxW - p.measureText("…")).coerceAtLeast(0f), null)
        return s.substring(0, n).trimEnd() + "…"
    }

    companion object {
        /** the open band's frame time: 30 fps at most */
        const val FRAME_MS = 33L
        const val STREAM_W = 260f
        const val LABEL_SIZE = 25f
        const val LABEL_MIN = 17f
        const val WORD_SIZE = 27f
        const val WORD_WRAP_MIN = 22f
        const val WORD_MIN = 16f
    }
}
