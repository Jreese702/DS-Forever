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
    data class Line(val key: String, val label: String?, val value: String?, val words: List<String>, val chosen: Int, val kind: Kind)

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
                add(Line(L_FLOW, "Flow scale", FrameGen.flowLabel(s.flow), listOf("Faster", "Default", "Sharper"), -1, Kind.STEPPER))
                add(Line(L_PERF, "Performance mode", null, ON_OFF, if (s.perfMode) 0 else 1, Kind.CHOICE))
            }
            // the player's own copy: the one line before it is set (nothing else can act), a replacement while generation is on
            if (!s.hasDll || s.generating) add(Line(L_DLL, "Lossless.dll", if (s.hasDll) "Found" else "Not set", listOf("Choose"), -1, Kind.ACTION))
            if (s.limitWaits || s.fgWaits) add(restart())
        }
        MEM -> buildList {
            // the 8 / 12 GB Thors: always lighter (the model decides; nothing to press, the status line says so)
            // the line the picture shows first, beside it
            add(Line(L_MAP, "Home map: full detail", null, ON_OFF, if (s.mapFull) 0 else 1, Kind.CHOICE))
            if (!s.lighterFixed) add(Line(L_LIGHTER, "Lighter mode", null, ON_OFF, if (s.lighter) 0 else 1, Kind.CHOICE))
            add(Line(L_LOWGFX, "Low-memory graphics", null, ON_OFF, if (s.lowGfx) 0 else 1, Kind.CHOICE))
        }
        INDOOR -> buildList {
            add(Line(L_BUILD, null, null, ON_OFF, if (s.indoorOn) 0 else 1, Kind.CHOICE))
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

    /** the 36 px badge: Top's set - the hourglass while the running game waits for a restart, else On / Off where the tile is a switch */
    fun badge(id: String, s: State): GameUiTiles.Badge? = when (id) {
        LIMIT -> if (s.limitWaits) GameUiTiles.Badge.SENT else null
        FG -> when { s.fgWaits -> GameUiTiles.Badge.SENT; s.generating -> GameUiTiles.Badge.ON; else -> GameUiTiles.Badge.OFF }
        INDOOR -> if (s.indoorOn) GameUiTiles.Badge.ON else GameUiTiles.Badge.OFF
        else -> null
    }

    /** the picture dimmed: the choice is Off (frame generation, the indoor build) */
    fun dimmed(id: String, s: State): Boolean = when (id) { FG -> !s.generating; INDOOR -> !s.indoorOn; else -> false }

    /** the band's one status line: state only (never an explanation) */
    fun status(id: String, s: State): String = when (id) {
        LIMIT -> when {
            s.limitWaits -> "Applies when the game restarts"
            s.limit > 0 && s.limit !in FrameGen.LIMITS -> "${s.limit} fps, set in the launcher's menu"
            s.limitNote && s.limit == FrameGen.LIMIT_MIGRATED -> "Set to 40 for battery and heat"
            else -> ""
        }
        FG -> when {
            s.fgWaits -> "Applies when the game restarts"
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
     *  are longer than Top's: "Low-memory graphics", "Performance mode" were cut at Top's 26 %) */
    const val LABEL_MAX = 300f
    const val BTN_MIN = 150f

    /** the band's boxes for [lines] (GameUiTiles.band; a label widened while its buttons keep [BTN_MIN]; an action's one button
     *  [ACTION_W] wide after its label) */
    fun band(left: Float, top: Float, width: Float, lines: List<Line>): GameUiTiles.Band {
        val b = GameUiTiles.band(left, top, width, lines.map { GameUiTiles.Line(it.key, it.words.size, it.label != null) })
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
class PerformanceTiles(context: Context, private val host: SettingsView) : View(context) {
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
    /** Indoor maps' deferred list shown in its band's picture (Deferred > List) */
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
            indoorOn = Settings.indoorBuild, ready = p?.ready ?: 0, total = p?.total ?: 0, deferred = p?.list?.size ?: 0,
            progress = IndoorProgress.line(p, Settings.indoorBuild),
            terrain = Terrain.coverageNow()?.let { c -> val z = c.optInt("zones"); val t = c.optInt("total")
                if (z < t) "Route data $z / $t" else null },
            streamOn = SetupBridge.openStreamSettings != null)
    }

    // ---------------------------------------------------------------- layout

    private class Layout(val grid: GameUiTiles.Grid, val band: GameUiTiles.Band?, val lines: List<PerformanceTileModel.Line>,
                         val row: RectF, val s: PerformanceTileModel.State)

    private fun layout(w: Float = width.toFloat()): Layout {
        val s = state()
        val open = selected?.let { id -> M.SLOTS.indexOfFirst { it.id == id } } ?: -1
        val lines = if (open >= 0) M.lines(M.SLOTS[open].id, s, deferredOpen) else emptyList()
        val bw = w - 2f * GameUiTiles.MARGIN
        val bandH = if (open >= 0) M.band(GameUiTiles.MARGIN, 0f, bw, lines).height else 0f
        val grid = M.grid(M.SLOTS, w, open, bandH)
        val band = grid.band?.let { M.band(it.l, it.t, it.w, lines) }
        val row = RectF(GameUiTiles.MARGIN, grid.height, w - GameUiTiles.MARGIN, grid.height + M.ROW_H)
        return Layout(grid, band, lines, row, s)
    }

    /** what decides the height: the open tile and its lines */
    private var shape: List<String> = emptyList()
    private fun shapeNow(): List<String> = listOf(selected ?: "") + (selected?.let { M.lines(it, state(), deferredOpen).map { l -> l.key } } ?: emptyList())

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
        l.band?.lines?.forEach { line -> line.buttons.forEach { out.add(it.rect()) } }
        if (l.s.streamOn) out.add(streamBox(l.row))
        return out
    }

    val hasSelection get() = selected != null
    /** the tile open now (the tests) */
    val openTile: String? get() = selected
    /** the tile ids in order (the tests) */
    fun tileIds(): List<String> = M.SLOTS.map { it.id }

    /** B: the open band closes; false = none was open */
    fun closeSelection(): Boolean {
        if (selected == null) return false
        selected = null; deferredOpen = false; requestLayout(); invalidate()
        return true
    }

    /** the open band's tile in this view's coordinates (B puts the cursor back there) */
    fun selectionAnchor(): RectF? {
        val id = selected ?: return null
        val i = M.SLOTS.indexOfFirst { it.id == id }
        return if (i < 0) null else layout().grid.tiles[i].rect()
    }

    /** the open band's box (the room scrolls it into view), null = none */
    fun bandBox(): RectF? = if (selected == null) null else layout().grid.band?.rect()

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
            if (k >= 0) { l.lines.getOrNull(i)?.let { press(it, k) }; refresh(); return }
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
            M.L_FLOW -> FrameGen.flowScale = if (k == 1) FrameGen.FLOW_DEFAULT else FrameGen.stepFlow(FrameGen.flowScale, if (k == 0) -1 else 1)
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
        c.drawColor(Theme.ink)
        val l = layout()
        for ((i, slot) in M.SLOTS.withIndex()) drawTile(c, l.grid.tiles[i], slot.id, l.s, slot.id == selected)
        val open = selected?.let { id -> M.SLOTS.indexOfFirst { it.id == id } } ?: -1
        val band = l.band
        if (open >= 0 && band != null) {
            drawBand(c, l.grid.band!!, band, l.grid.tiles[open], selected!!, l.lines, l.s)
            // the open band's picture moves (up to 30 fps), only while the room shows it
            if (M.animated(selected!!) && isShown) postInvalidateDelayed(FRAME_MS)
        }
        drawRow(c, l.row, l.s)
    }

    private fun picture(c: Canvas, id: String, s: PerformanceTileModel.State, well: RectF, t: Float, list: List<String>? = null) {
        pFill.color = Color.rgb(14, 16, 18)
        c.drawRoundRect(well, 8f, 8f, pFill)
        if (list != null) PerformancePictures.list(c, well, list)
        else PerformancePictures.draw(c, context, id, well, s, t) { postInvalidate() }
        if (list == null && M.dimmed(id, s)) {
            pFill.color = Theme.ink; pFill.alpha = 165
            c.drawRoundRect(well, 8f, 8f, pFill)
            pFill.alpha = 255
        }
        pStroke.color = Color.argb(200, 0, 0, 0); pStroke.strokeWidth = 2f
        c.drawRoundRect(well, 8f, 8f, pStroke)
    }

    private fun drawTile(c: Canvas, box: GameUiTiles.Box, id: String, s: PerformanceTileModel.State, open: Boolean) {
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
        c.drawText(fitText(pName, Tr.t(M.NAMES[id] ?: id), maxW, GameUiSection.NAME_SIZE, GameUiSection.NAME_MIN), x,
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
        val list = if (id == M.INDOOR && deferredOpen) progress?.list?.takeIf { it.isNotEmpty() } else null
        picture(c, id, s, band.picture.rect(), t, list)
        for ((i, lb) in band.lines.withIndex()) {
            val line = lines.getOrNull(i) ?: continue
            lb.label?.let { lab -> label(c, lab, line) }
            for ((k, b) in lb.buttons.withIndex()) {
                val r = b.rect()
                when (line.kind) {
                    PerformanceTileModel.Kind.ACTION -> UiKit.button(c, r, Tr.t(line.words.getOrElse(k) { "" }), UiKit.Button.UP, textSize = 27f)
                    else -> UiKit.choice(c, r, Tr.t(line.words.getOrElse(k) { "" }), k == line.chosen, true, textSize = 27f)
                }
            }
        }
        val st = band.status
        val say = M.status(id, s)
        if (say.isNotEmpty()) {
            pSub.color = Theme.textDim
            c.drawText(fitText(pSub, Tr.t(say), st.w - 8f, 22f, 18f), st.l + 4f, st.cy + 8f, pSub)
        }
    }

    /** a band line's label column: its sign (Memory's pack: leaf / chip / map, so each line reads at a glance), the words, the value */
    private fun label(c: Canvas, lb: GameUiTiles.Box, line: PerformanceTileModel.Line) {
        var x = lb.l + 4f
        val sign = PerformancePictures.lineSign(line.key)
        if (sign) {
            PerformancePictures.drawLineSign(c, line.key, x + 16f, lb.cy, 30f)
            x += 40f
        }
        val room = lb.r - x - 6f
        pBandLabel.color = Theme.text
        val name = fitText(pBandLabel, Tr.t(line.label ?: ""), room, 25f, 17f)
        if (line.value != null) {
            c.drawText(name, x, lb.cy - 6f, pBandLabel)
            pValue.color = Theme.gold
            c.drawText(fitText(pValue, Tr.t(line.value), room, 22f, 18f), x, lb.cy + 26f, pValue)
        } else c.drawText(name, x, lb.cy + 9f, pBandLabel)
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
    }
}
