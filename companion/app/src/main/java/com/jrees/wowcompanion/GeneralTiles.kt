package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * A PART OF THE SETTINGS ROOM WITH A BAND (B closes it first, the cursor back on its tile): Top screen's GameUiSection parts and the
 * General tab's [GeneralTilesView]. The room keeps one band open at a time.
 */
interface BandPart {
    val hasSelection: Boolean
    /** the open band's tile, in the part's own coordinates; null = none open */
    fun selectionAnchor(): RectF?
    /** the open band's box, in the part's own coordinates; null = none open */
    fun bandBox(): RectF?
    /** closes the open band; false = none was open */
    fun closeSelection(): Boolean
}

/**
 * SETTINGS > GENERAL AS PICTURE TILES (the user, 2026-11-17: "I REALLY REALLY love what you did with the top screen settings menu with
 * the pictures and want to do something similar to the other menus ... an ultra code 'beautification' task"; the beautify design,
 * section 2): the Look group is ONE tile row - Theme (wide; A opens the theme maker), Swap screens, Voice typing - and Language as a
 * compact row; Account and Help are compact rows (88 px, the label and ONE control, no sub-caption: the terse-text rule).
 *
 * The model and the geometry, pure (no android.graphics), so the off-device tests measure them. The tiles are Top screen's: the same
 * 232 x 184 tile, 216 x 108 well, band and badge ([GameUiTiles]); the Theme tile spans two columns (a wide tile never starts in the last
 * column: it starts the next row). The settings, keys and channels are the rows' own (Settings, Tr, ScreenSwap, ReportFlow ...): only
 * where and how they are drawn changed.
 */
object GeneralTiles {
    const val THEME = "theme"
    const val SWAP = "swap"
    const val VOICE = "voice"

    /** a tile's slot: [wide] = it spans two columns (the design's three wide tiles: Theme here) */
    data class Slot(val id: String, val wide: Boolean = false)

    /** the Look row's tiles: Swap screens only with a second screen (a slot that does not apply is not drawn) */
    fun slots(swapOffered: Boolean): List<Slot> = listOfNotNull(Slot(THEME, wide = true), if (swapOffered) Slot(SWAP) else null, Slot(VOICE))

    /** a tile's name (display words; Tr.t by the drawer) */
    val NAMES = mapOf(THEME to "Theme", SWAP to "Swap screens", VOICE to "Voice typing")

    /** the compact rows, in their groups' order */
    enum class Row(val label: String) {
        LANGUAGE("Language"), LOGIN("Battle.net login"), GAME_ACCOUNT("Game account"), WINDOWS_ID(WindowsId.ROW_ID),
        WINDOWS_RESTART("Restart the game"), REPORT("Problem report"), RELOAD("Reload game UI"), STATUS("Status")
    }

    /** [g]'s compact rows now: the login row only when the host has it; Restart the game only while the running game used the other ID */
    fun rows(g: SettingsTabs.Group, loginRow: Boolean, winIdRestart: Boolean): List<Row> = when (g) {
        SettingsTabs.Group.LOOK -> listOf(Row.LANGUAGE)
        SettingsTabs.Group.ACCOUNT -> listOfNotNull(if (loginRow) Row.LOGIN else null, Row.GAME_ACCOUNT, Row.WINDOWS_ID,
            if (winIdRestart) Row.WINDOWS_RESTART else null)
        SettingsTabs.Group.HELP -> listOf(Row.REPORT, Row.RELOAD, Row.STATUS)
        else -> emptyList()
    }

    /** the Theme tile's page (themes' Theme maker, ThemeMakerPage): set by the room's host; null = the premade themes in its band */
    @Volatile var onOpenTheme: (() -> Boolean)? = null

    /** the most premade swatches on one band line (5 x 135 px beside the band's picture on the room's 1240 px page) */
    const val PRESETS_PER_LINE = 5

    /** the premades in the Theme tile's band while no maker page is wired: an even grid - as few lines as hold them at
     *  [PRESETS_PER_LINE] each, the same count on every line (the last may hold fewer) - Classic and the Dark ones first, then OLED */
    fun presetLines(ids: List<String>): List<List<String>> {
        if (ids.isEmpty()) return emptyList()
        val ordered = ids.filter { !it.startsWith("oled") } + ids.filter { it.startsWith("oled") }
        val lines = (ordered.size + PRESETS_PER_LINE - 1) / PRESETS_PER_LINE
        return ordered.chunked((ordered.size + lines - 1) / lines)
    }

    // ---------------------------------------------------------------- the geometry

    /** a compact row's height (the second line is gone) and the room between the tile grid and the first row under it */
    const val COMPACT_H = 88f
    /** the label's x inside a row, and the share of the row the control may take (right-aligned) */
    const val LABEL_X = 28f
    const val CONTROL_SHARE = 0.58f
    /** a part's foot */
    const val FOOT = 16f
    /** a choice segment's least width in a compact row, and its words' room */
    const val SEG_MIN = 176f
    const val SEG_PAD = 48f
    const val CONTROL_H = 68f
    const val BUTTON_W = 244f

    /**
     * [slots] across [width] with Top screen's tile size; a wide slot spans two columns and the gap between them; a wide one that would
     * start in the last column starts the next row. The band [bandH] tall under the row of tile [open] (-1 = none).
     */
    fun grid(slots: List<Slot>, width: Float, open: Int, bandH: Float): GameUiTiles.Grid {
        val cols = GameUiTiles.columns(width)
        val tw = (width - 2f * GameUiTiles.MARGIN - (cols - 1) * GameUiTiles.GAP) / cols
        // each slot's (row, column)
        val place = ArrayList<Pair<Int, Int>>()
        var row = 0; var col = 0
        for (s in slots) {
            val span = if (s.wide && cols >= 2) 2 else 1
            if (col + span > cols) { row++; col = 0 }
            place += row to col
            col += span
            if (col >= cols) { row++; col = 0 }
        }
        val rows = if (place.isEmpty()) 0 else place.last().first + 1
        val openRow = if (open in slots.indices) place[open].first else -1
        val push = if (openRow >= 0) bandH + GameUiTiles.GAP else 0f
        val tiles = slots.mapIndexed { i, s ->
            val (r, c) = place[i]
            val span = if (s.wide && cols >= 2) 2 else 1
            val y = GameUiTiles.TOP + r * (GameUiTiles.TILE_H + GameUiTiles.GAP) + (if (openRow in 0 until r) push else 0f)
            val x = GameUiTiles.MARGIN + c * (tw + GameUiTiles.GAP)
            GameUiTiles.Box(x, y, x + span * tw + (span - 1) * GameUiTiles.GAP, y + GameUiTiles.TILE_H)
        }
        val band = if (openRow >= 0) {
            val y = GameUiTiles.TOP + openRow * (GameUiTiles.TILE_H + GameUiTiles.GAP) + GameUiTiles.TILE_H + GameUiTiles.GAP
            GameUiTiles.Box(GameUiTiles.MARGIN, y, width - GameUiTiles.MARGIN, y + bandH)
        } else null
        val h = if (rows == 0) 0f else GameUiTiles.TOP + rows * GameUiTiles.TILE_H + (rows - 1) * GameUiTiles.GAP + push + GameUiTiles.BOTTOM
        return GameUiTiles.Grid(tiles, band, h)
    }

    /** a compact row's box at [top] across [width] */
    fun rowBox(top: Float, width: Float) = GameUiTiles.Box(GameUiTiles.MARGIN, top, width - GameUiTiles.MARGIN, top + COMPACT_H)

    /** the control's room in a row: the right [CONTROL_SHARE] of it, 16 px in from its right end */
    fun controlRoom(row: GameUiTiles.Box) = GameUiTiles.Box(row.r - row.w * CONTROL_SHARE, row.t, row.r - 16f, row.b)

    /** [n] segments of [segW] right-aligned in [room], [CONTROL_H] tall, centred on the row */
    fun segments(room: GameUiTiles.Box, n: Int, segW: Float): List<GameUiTiles.Box> {
        val w = minOf(segW, room.w / n)
        val l = room.r - n * w
        val t = room.cy - CONTROL_H / 2f
        return (0 until n).map { k -> GameUiTiles.Box(l + k * w, t, l + (k + 1) * w, t + CONTROL_H) }
    }

    /** one button right-aligned in [room] */
    fun button(room: GameUiTiles.Box, w: Float = BUTTON_W) =
        GameUiTiles.Box(room.r - w, room.cy - CONTROL_H / 2f, room.r, room.cy + CONTROL_H / 2f)
}

/**
 * ONE GROUP OF SETTINGS > GENERAL, drawn as [GeneralTiles] lays it out: the Look group's tile row (and its open band) and Language; the
 * Account and Help groups' compact rows. A part of the room like Top screen's GameUiSection parts: it measures its own height, its
 * buttons are the cursor's stops ([focusTargets]), a touch acts on RELEASE when the finger did not move (the page scrolls), and the
 * controller's A is a touch at a stop's centre. The host's wiring (the logins, the report, the restarts, Reload) is [host]'s.
 */
class GeneralTilesView(context: Context, private val host: SettingsView, val group: SettingsTabs.Group) : View(context), BandPart {

    /** a tile's band opened (or moved): the room closes any other band and the cursor follows */
    var onOpen: ((GeneralTilesView) -> Unit)? = null
    /** the tile whose band is open (session only, never saved) */
    var openTile: String? = null
        private set

    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val pName by themed { Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 25f; color = Theme.text } }
    private val pValue by themed { Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; color = Theme.gold } }
    private val pLabel by themed { Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 28f; color = Theme.text } }
    private val pStatus by themed { Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; color = Theme.textDim } }
    private val pSeg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tilePath = Path()

    private var downX = 0f
    private var downY = 0f
    private var reloadArmedAt = 0L
    private var reloadSentAt = 0L
    /** the saved report's Open issue was pressed and it is still there (no browser): the ONE button's second state is Dismiss */
    private var reportTried = false
    private var voiceAvailAt = -10_000L
    private var voiceNeeds = false

    init {
        Theme.onChange { postInvalidate() }
        Settings.onChange { post { relayout() } }
        ReportFlow.init(context)
        ReportFlow.onChange { postInvalidate() }
        WindowsId.onChange { post { relayout() } }
        if (group == SettingsTabs.Group.HELP) StatusLine.onChange = { post { invalidate() } }
    }

    /** the room left: the Swap screens picture's decoded crop goes (a later draw decodes it again) */
    override fun onDetachedFromWindow() {
        GeneralPictures.letGo()
        super.onDetachedFromWindow()
    }

    private var shape: List<String> = emptyList()
    private fun relayout() { if (shapeNow() != shape) requestLayout(); invalidate() }
    private fun shapeNow(): List<String> = slots().map { it.id } + listOf("|", openTile ?: "") + rows().map { it.name } +
        (openTile?.let { lines(it).map { l -> l.key } } ?: emptyList())

    // ---------------------------------------------------------------- the model now

    private fun slots(): List<GeneralTiles.Slot> = if (group == SettingsTabs.Group.LOOK) GeneralTiles.slots(ScreenSwap.offered) else emptyList()
    private fun rows(): List<GeneralTiles.Row> = GeneralTiles.rows(group, host.loginRow, winIdRestart())

    private fun winIdRestart(): Boolean = WindowsId.showRestart(host.gameRunning(), SetupBridge.restarting, WindowsId.launched, Settings.windowsId)
    private fun swapPending(): ScreenSwap.Pending =
        ScreenSwap.pending(Settings.swapScreens, ScreenSwap.inUseNow, host.gameRunning(), ScreenSwap.restart != null)

    /** the voice sheet would offer a model's download now, asked at most every 2 s (it lists the services) */
    private fun voiceNeedsModel(): Boolean {
        if (!VoiceModels.offered()) return false
        val now = SystemClock.elapsedRealtime()
        if (now - voiceAvailAt > 2000L) {
            voiceAvailAt = now
            voiceNeeds = runCatching { VoiceEngine.avail(context, Settings.voiceTyping) == Voice.Avail.NEED_MODEL }.getOrDefault(false)
        }
        return voiceNeeds
    }

    /** one line of a band: [words] choices (the chosen one [chosen], -1 = none lit), labelled when [label] is set */
    private class BandLine(val key: String, val label: String?, val words: List<String>, val chosen: Int, val action: Boolean = false)

    private fun lines(id: String): List<BandLine> = when (id) {
        GeneralTiles.SWAP -> listOfNotNull(
            BandLine("swap", null, listOf("On", "Off"), if (Settings.swapScreens) 0 else 1),
            // no dead stops: the Restart line only while a restart can apply the choice now
            if (swapPending() == ScreenSwap.Pending.RESTART) BandLine("restart", null, listOf("Restart DS Forever"), -1, action = true) else null)
        GeneralTiles.VOICE -> listOf(BandLine("voice", null, Voice.Mode.values().map { it.label }, Settings.voiceTyping.ordinal))
        GeneralTiles.THEME -> GeneralTiles.presetLines(Theme.presets.map { it.id }).mapIndexed { i, ids ->
            BandLine("theme$i", null, ids, ids.indexOf(Theme.cur.id)) }
        else -> emptyList()
    }

    /** the band's one status line: state only */
    private fun status(id: String): String = when (id) {
        GeneralTiles.SWAP -> if (swapPending() == ScreenSwap.Pending.AFTER_GAME) "After the game closes" else ""
        GeneralTiles.VOICE -> if (Settings.voiceTyping != Voice.Mode.OFF && voiceNeedsModel()) "Download a voice in Add-ons" else ""
        else -> ""
    }

    /** a band line's button words: the theme ids as their names */
    private fun word(line: BandLine, k: Int): String =
        if (line.key.startsWith("theme")) Theme.byId(line.words[k])?.name ?: line.words[k] else line.words[k]

    // ---------------------------------------------------------------- the layout

    private class Layout(val slots: List<GeneralTiles.Slot>, val grid: GameUiTiles.Grid?, val band: GameUiTiles.Band?, val lines: List<BandLine>,
                         val rows: List<Pair<GeneralTiles.Row, GameUiTiles.Box>>, val height: Float)

    private fun layout(w: Float): Layout {
        val ss = slots()
        val open = openTile?.let { o -> ss.indexOfFirst { it.id == o } } ?: -1
        val ls = if (open >= 0) lines(ss[open].id) else emptyList()
        val gl = ls.map { GameUiTiles.Line(it.key, it.words.size, it.label != null) }
        val full = if (open >= 0) GameUiTiles.band(GameUiTiles.MARGIN, 0f, w - 2f * GameUiTiles.MARGIN, gl) else null
        // the Theme band never has a status line: its foot (where the band puts one under the swatches) is not kept empty
        val bandH = when {
            full == null -> 0f
            ss[open].id == GeneralTiles.THEME && full.status.t >= (full.lines.flatMap { it.buttons }.maxOfOrNull { it.b } ?: 0f) ->
                full.height - GameUiTiles.STATUS_H - GameUiTiles.LINE_GAP
            else -> full.height
        }
        val grid = if (ss.isEmpty()) null else GeneralTiles.grid(ss, w, open, bandH)
        val band = grid?.band?.let { GameUiTiles.band(it.l, it.t, it.w, gl) }
        var y = grid?.height ?: GameUiTiles.TOP
        val rows = rows().map { r -> (r to GeneralTiles.rowBox(y, w)).also { y += GeneralTiles.COMPACT_H } }
        val h = if (ss.isEmpty() && rows.isEmpty()) 0f else y + (if (rows.isEmpty()) 0f else GeneralTiles.FOOT)
        return Layout(ss, grid, band, ls, rows, h)
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = getDefaultSize(suggestedMinimumWidth, widthSpec)
        shape = shapeNow()
        setMeasuredDimension(w, resolveSize(layout(w.toFloat()).height.toInt(), heightSpec))
    }

    /** this part has something to draw now */
    fun hasContent(): Boolean = slots().isNotEmpty() || rows().isNotEmpty()

    // ---------------------------------------------------------------- the controls' boxes (pure from the layout)

    private class Control(val row: GeneralTiles.Row, val box: RectF, val act: () -> Unit, val reload: Boolean = false)

    /** every control a compact row has now, in reading order */
    private fun controls(l: Layout): List<Control> {
        val out = ArrayList<Control>()
        for ((r, box) in l.rows) {
            val room = GeneralTiles.controlRoom(box)
            when (r) {
                GeneralTiles.Row.LANGUAGE -> segs(room, Tr.LANGS.map { langWord(it) }).forEachIndexed { k, b ->
                    out += Control(r, b, { Tr.setChoice(context, Tr.LANGS[k].code) }) }
                GeneralTiles.Row.LOGIN -> out += Control(r, GeneralTiles.button(room).rect(), { host.onLogin() })
                GeneralTiles.Row.GAME_ACCOUNT -> segs(room, GAME_ACCOUNT).forEachIndexed { k, b ->
                    out += Control(r, b, { Settings.gameAccountAsk = k == 0 }) }
                GeneralTiles.Row.WINDOWS_ID -> segs(room, WindowsId.SEGMENTS).forEachIndexed { k, b ->
                    out += Control(r, b, { Settings.windowsId = WindowsId.VALUES[k] }) }
                GeneralTiles.Row.WINDOWS_RESTART -> out += Control(r, GeneralTiles.button(room).rect(), { host.onRestart() })
                GeneralTiles.Row.REPORT -> out += Control(r, GeneralTiles.button(room).rect(), { reportPress() })
                GeneralTiles.Row.RELOAD -> out += Control(r, GeneralTiles.button(room).rect(), { reloadPress() }, reload = true)
                // read-only: a stop so the cursor reaches the page's last row (A there does nothing)
                GeneralTiles.Row.STATUS -> out += Control(r, box.rect(), {})
            }
        }
        return out
    }

    private fun langWord(l: Tr.Lang) = if (l.code == "auto") "Automatic" else l.label

    /** the segments for [words] in [room]: as wide as the widest word needs (the least [GeneralTiles.SEG_MIN]) */
    private fun segs(room: GameUiTiles.Box, words: List<String>): List<RectF> {
        UiKit.asBody(pSeg); pSeg.textSize = SEG_TEXT
        val w = max(GeneralTiles.SEG_MIN, words.maxOf { pSeg.measureText(Tr.t(it)) } + GeneralTiles.SEG_PAD)
        return GeneralTiles.segments(room, words.size, w).map { it.rect() }
    }

    /** the controller's stops: the tiles, the open band's buttons, then the rows' controls, in reading order */
    fun focusTargets(): List<RectF> {
        val l = layout(width.toFloat())
        val out = ArrayList<RectF>()
        l.grid?.tiles?.forEach { out.add(it.rect()) }
        l.band?.lines?.forEach { line -> line.buttons.forEach { out.add(it.rect()) } }
        controls(l).forEach { out.add(RectF(it.box)) }
        return out
    }

    /** [r] is Reload game UI's button (the cursor never starts there: the room's one destructive control) */
    fun isReload(r: RectF): Boolean = controls(layout(width.toFloat())).any { it.reload && it.box == r }

    /** [row]'s control boxes now, in this part's coordinates (the tests) */
    fun controlBoxes(row: GeneralTiles.Row): List<RectF> = controls(layout(width.toFloat())).filter { it.row == row }.map { RectF(it.box) }
    /** a tile's box (the tests); null = not drawn */
    fun tileBox(id: String): RectF? = layout(width.toFloat()).let { l -> l.slots.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { l.grid!!.tiles[it].rect() } }
    /** the open band's buttons, line by line (the tests) */
    fun bandButtons(): List<List<RectF>> = layout(width.toFloat()).band?.lines?.map { line -> line.buttons.map { it.rect() } } ?: emptyList()
    /** the open band's status words (the tests); "" = none */
    fun bandStatus(): String = openTile?.let { status(it) } ?: ""

    /** the tile ids drawn now, in order (the tests) */
    fun tileIds(): List<String> = slots().map { it.id }
    /** the compact rows drawn now, in order (the tests) */
    fun rowIds(): List<GeneralTiles.Row> = rows()

    override val hasSelection: Boolean get() = openTile != null
    override fun selectionAnchor(): RectF? {
        val id = openTile ?: return null
        val l = layout(width.toFloat())
        return l.slots.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { l.grid!!.tiles[it].rect() }
    }
    override fun bandBox(): RectF? = if (openTile == null) null else layout(width.toFloat()).grid?.band?.rect()
    override fun closeSelection(): Boolean {
        if (openTile == null) return false
        openTile = null; requestLayout(); invalidate()
        return true
    }

    // ---------------------------------------------------------------- touches

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
            MotionEvent.ACTION_UP -> {
                if (abs(e.x - downX) > SLOP || abs(e.y - downY) > SLOP) return true
                tap(e.x, e.y)
            }
        }
        return true
    }

    private fun tap(x: Float, y: Float) {
        val l = layout(width.toFloat())
        l.band?.lines?.forEachIndexed { i, line ->
            val k = line.buttons.indexOfFirst { it.contains(x, y) }
            if (k >= 0) { bandPress(l.lines[i], k); relayout(); return }
        }
        val ti = l.grid?.tiles?.indexOfFirst { it.contains(x, y) } ?: -1
        if (ti >= 0) { tilePress(l.slots[ti].id); return }
        controls(l).firstOrNull { it.box.contains(x, y) }?.let { it.act(); relayout(); return }
    }

    /** A or a tap on a tile: the Theme tile opens the theme maker (its band of premades while there is no page); the others open
     *  their band, or close it when it is the open one */
    private fun tilePress(id: String) {
        if (id == GeneralTiles.THEME && GeneralTiles.onOpenTheme?.invoke() == true) { closeSelection(); return }
        openTile = if (openTile == id) null else id
        requestLayout(); invalidate()
        if (openTile != null) onOpen?.invoke(this)
    }

    /** button [k] of band line [line]: ONE player-pressed write, the row's own */
    private fun bandPress(line: BandLine, k: Int) {
        when {
            line.key == "swap" -> {
                Settings.swapScreens = k == 0
                DiagLog.event("app", "Swap screens set ${if (k == 0) "on" else "off"} (in use: ${ScreenSwap.inUseNow})")
            }
            line.key == "restart" -> ScreenSwap.restart?.invoke()
            line.key == "voice" -> {
                Settings.voiceTyping = Voice.Mode.values()[k]
                DiagLog.event("voice", "Voice typing set ${Settings.voiceTyping.key}")
            }
            line.key.startsWith("theme") -> Theme.use(line.words[k])
        }
    }

    private fun reportPress() {
        val saved = ReportFlow.pending
        when {
            saved != null && reportTried -> { ReportFlow.clearPending(); reportTried = false }
            saved != null -> { reportTried = true; host.onReportOpenSaved() }
            DiagLog.recording() -> host.onReportSendNow()
            else -> host.onReport()
        }
    }

    private fun reloadPress() {
        val now = SystemClock.elapsedRealtime()
        if (reloadArmedAt != 0L && now - reloadArmedAt < ARM_MS) {
            reloadArmedAt = 0L; reloadSentAt = now
            host.onReload()
        } else {
            reloadArmedAt = now
            postDelayed({ invalidate() }, ARM_MS + 50)            // disarm visibly
        }
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(c: Canvas) {
        UiKit.asBody(pName); UiKit.asBody(pValue); UiKit.asBody(pLabel); UiKit.asBody(pStatus); UiKit.asBody(pSeg)
        c.drawColor(Theme.ink)
        val l = layout(width.toFloat())
        val grid = l.grid
        if (grid != null) {
            for ((i, s) in l.slots.withIndex()) drawTile(c, grid.tiles[i], s.id, s.id == openTile)
            val band = l.band
            val open = l.slots.indexOfFirst { it.id == openTile }
            if (band != null && open >= 0) drawBand(c, grid.band!!, band, grid.tiles[open], openTile!!, l.lines)
        }
        val ctl = controls(l)
        for ((i, rb) in l.rows.withIndex()) {
            val (r, box) = rb
            if (i > 0) { pFill.color = Theme.bronzeDim; c.drawRect(box.l, box.t, box.r, box.t + 1f, pFill) }
            pLabel.color = Theme.text; pLabel.textSize = 28f
            val room = GeneralTiles.controlRoom(box)
            val lw = room.l - box.l - GeneralTiles.LABEL_X - 16f
            val cs = ctl.filter { it.row == r }.map { it.box }
            c.drawText(fitText(pLabel, Tr.t(r.label), lw, 28f, 20f), box.l + GeneralTiles.LABEL_X, box.cy + 10f, pLabel)
            drawControl(c, r, box, room, cs)
        }
    }

    private fun drawControl(c: Canvas, r: GeneralTiles.Row, box: GameUiTiles.Box, room: GameUiTiles.Box, cs: List<RectF>) {
        when (r) {
            GeneralTiles.Row.LANGUAGE -> seg(c, cs, Tr.LANGS.map { langWord(it) }, Tr.LANGS.indexOfFirst { it.code == Tr.choice() })
            GeneralTiles.Row.GAME_ACCOUNT -> seg(c, cs, GAME_ACCOUNT, if (Settings.gameAccountAsk) 0 else 1)
            GeneralTiles.Row.WINDOWS_ID -> seg(c, cs, WindowsId.SEGMENTS, WindowsId.VALUES.indexOf(Settings.windowsId))
            GeneralTiles.Row.LOGIN -> UiKit.button(c, cs[0], if (host.loginSaved()) "Change" else "Save", UiKit.Button.UP)
            GeneralTiles.Row.WINDOWS_RESTART -> UiKit.button(c, cs[0], "Restart", UiKit.Button.UP)
            GeneralTiles.Row.REPORT -> {
                val saved = ReportFlow.pending
                val rec = DiagLog.recording()
                val word = when {
                    saved != null && reportTried -> "Dismiss"; saved != null -> "Open issue"; rec -> "Send report"; else -> "Send"
                }
                UiKit.button(c, cs[0], word, UiKit.Button.UP)
                // the state as the value word: the time left while recording, the saved report's time (or its issue number)
                val value = when {
                    saved != null && saved.sent > 0 -> "#${saved.sent}"
                    saved != null -> SimpleDateFormat("HH:mm", Locale.US).format(Date(saved.at))
                    rec -> (DiagLog.recordLeftMs() / 1000).let { "%d:%02d".format(it / 60, it % 60) }
                    else -> null
                }
                value?.let { valueWord(c, it, cs[0].left - 20f, box.cy) }
                if (rec) postInvalidateDelayed(1000)
            }
            GeneralTiles.Row.RELOAD -> {
                val now = SystemClock.elapsedRealtime()
                val armed = reloadArmedAt != 0L && now - reloadArmedAt < ARM_MS
                val sent = reloadSentAt != 0L && now - reloadSentAt < 2500L
                if (sent) { postInvalidateDelayed(300); valueWord(c, "Sent", cs[0].left - 20f, box.cy) }
                UiKit.button(c, cs[0], if (armed) "Tap again" else "Reload", if (armed) UiKit.Button.DOWN else UiKit.Button.UP)
            }
            GeneralTiles.Row.STATUS -> {
                pValue.color = Theme.gold
                val say = Tr.t(StatusLine.text.ifEmpty { "Nothing to report" })
                pValue.textAlign = Paint.Align.RIGHT
                c.drawText(fitText(pValue, say, room.w - 8f, 26f, 20f), room.r - 8f, box.cy + 9f, pValue)
                pValue.textAlign = Paint.Align.LEFT; pValue.textSize = 22f
            }
        }
    }

    private fun valueWord(c: Canvas, s: String, right: Float, cy: Float) {
        pValue.color = Theme.gold; pValue.textSize = 26f; pValue.textAlign = Paint.Align.RIGHT
        c.drawText(Tr.t(s), right, cy + 9f, pValue)
        pValue.textAlign = Paint.Align.LEFT; pValue.textSize = 22f
    }

    private fun seg(c: Canvas, boxes: List<RectF>, words: List<String>, sel: Int) {
        for ((k, b) in boxes.withIndex()) UiKit.choice(c, RectF(b.left + 2f, b.top, b.right - 2f, b.bottom), Tr.t(words[k]), k == sel, textSize = SEG_TEXT)
    }

    /** a tile: Top screen's anatomy (its ground, the kit's ring lit while its band is open, the picture well, the badge, the name and
     *  the value word) */
    private fun drawTile(c: Canvas, box: GameUiTiles.Box, id: String, open: Boolean) {
        val rect = box.rect()
        pFill.color = if (open) Theme.selected else Theme.raised
        c.drawRoundRect(rect, 10f, 10f, pFill)
        val well = GameUiTiles.well(box).rect()
        GeneralPictures.draw(c, context, id, well, look(id))
        if (dimmed(id)) { pFill.color = Theme.ink; pFill.alpha = 165; c.drawRoundRect(well, 8f, 8f, pFill); pFill.alpha = 255 }
        pStroke.color = android.graphics.Color.argb(200, 0, 0, 0); pStroke.strokeWidth = 2f
        c.drawRoundRect(well, 8f, 8f, pStroke)
        badge(id)?.let { TilePictures.badge(c, GameUiTiles.badgeBox(box).rect(), it) }
        edge(c, rect, open)
        val x = box.l + GameUiTiles.WELL_PAD + 2f
        val maxW = box.w - 2f * GameUiTiles.WELL_PAD - 4f
        pName.color = Theme.text
        c.drawText(fitText(pName, Tr.t(GeneralTiles.NAMES[id] ?: id), maxW, 25f, 19f), x, box.t + GameUiTiles.WELL_PAD + GameUiTiles.WELL_H + 34f, pName)
        pValue.color = Theme.gold                                       // the choice's word, as Top's (Off too)
        c.drawText(fitText(pValue, Tr.t(valueOf(id)), maxW, 22f, 18f), x, box.b - 12f, pValue)
    }

    /** what the picture shows: the choice now */
    private fun look(id: String): GeneralPictures.Look = GeneralPictures.Look(swap = Settings.swapScreens, voice = Settings.voiceTyping)

    private fun valueOf(id: String): String = when (id) {
        GeneralTiles.THEME -> Theme.cur.name
        GeneralTiles.SWAP -> if (Settings.swapScreens) "On" else "Off"
        GeneralTiles.VOICE -> Settings.voiceTyping.label
        else -> ""
    }

    /** Off is dimmed (Voice typing Off); Swap screens Off is the Thor's own arrangement, not "off": never dimmed */
    private fun dimmed(id: String): Boolean = id == GeneralTiles.VOICE && Settings.voiceTyping == Voice.Mode.OFF

    /** the choice's sign on the well's corner: Swap screens On = the two screens; the rest none */
    private fun badge(id: String): GameUiTiles.Badge? = if (id == GeneralTiles.SWAP && Settings.swapScreens) GameUiTiles.Badge.DYNAMIC else null

    private fun edge(c: Canvas, r: RectF, lit: Boolean) {
        if (UiKit.ringBox(c, r, 0.8f, lit = lit)) return
        val w = UiKit.RING_W * 0.8f
        tilePath.reset()
        tilePath.addRoundRect(RectF(r.left + w / 2f, r.top + w / 2f, r.right - w / 2f, r.bottom - w / 2f), 9f, 9f, Path.Direction.CW)
        UiKit.ringEdge(c, tilePath, w, lit = lit)
    }

    /** the open tile's band: Top screen's (the notch at the tile, the picture large on the left, the choices, one status line) */
    private fun drawBand(c: Canvas, box: GameUiTiles.Box, band: GameUiTiles.Band, tile: GameUiTiles.Box, id: String, lines: List<BandLine>) {
        val rect = box.rect()
        pFill.color = Theme.panel
        c.drawRoundRect(rect, 12f, 12f, pFill)
        edge(c, rect, false)
        val nx = tile.cx
        pFill.color = Theme.panel
        tilePath.reset(); tilePath.moveTo(nx - 18f, rect.top + 3f); tilePath.lineTo(nx, rect.top - 13f); tilePath.lineTo(nx + 18f, rect.top + 3f); tilePath.close()
        c.drawPath(tilePath, pFill)
        pStroke.color = Theme.gold; pStroke.strokeWidth = 2.6f
        c.drawLine(nx - 18f, rect.top + 1f, nx, rect.top - 13f, pStroke); c.drawLine(nx, rect.top - 13f, nx + 18f, rect.top + 1f, pStroke)
        val pr = band.picture.rect()
        // the Theme band's picture: the theme in use's room filling the picture (beside the swatches' two lines, no empty bands)
        if (id == GeneralTiles.THEME) {
            val save = c.save()
            tilePath.reset(); tilePath.addRoundRect(pr, 8f, 8f, Path.Direction.CW); c.clipPath(tilePath)
            GeneralPictures.miniRoom(c, pr)
            c.restoreToCount(save)
        } else GeneralPictures.draw(c, context, id, pr, look(id))
        if (dimmed(id)) { pFill.color = Theme.ink; pFill.alpha = 165; c.drawRoundRect(pr, 8f, 8f, pFill); pFill.alpha = 255 }
        pStroke.color = android.graphics.Color.argb(200, 0, 0, 0); pStroke.strokeWidth = 2f
        c.drawRoundRect(pr, 8f, 8f, pStroke)
        for ((i, line) in band.lines.withIndex()) {
            val bl = lines.getOrNull(i) ?: continue
            line.label?.let { lb ->
                pName.color = Theme.text
                c.drawText(fitText(pName, Tr.t(bl.label ?: ""), lb.w - 6f, 25f, 18f), lb.l + 4f, lb.cy + 9f, pName)
            }
            if (bl.key.startsWith("theme")) { swatchLine(c, line.buttons.map { it.rect() }, bl); continue }
            for ((k, b) in line.buttons.withIndex()) {
                if (bl.action) UiKit.button(c, b.rect(), word(bl, k), UiKit.Button.UP, textSize = 27f)
                else UiKit.choice(c, b.rect(), Tr.t(word(bl, k)), k == bl.chosen, textSize = 27f)
            }
        }
        val say = status(id)
        if (say.isNotEmpty()) {
            val st = band.status
            pStatus.color = Theme.textDim
            c.drawText(fitText(pStatus, Tr.t(say), st.w - 8f, 22f, 18f), st.l + 4f, st.cy + 8f, pStatus)
        }
    }

    /**
     * one line of premade swatches: each chip a tile in small (its plate, the premade's own mini room, its name under it, ONE size for
     * every name on the band); the theme in use lit (the selected plate, the lit ring, its name in the accent). The cursor's box is the
     * room's, as on every stop.
     */
    private fun swatchLine(c: Canvas, boxes: List<RectF>, bl: BandLine) {
        val names = Theme.presets.map { it.name }
        val size = swatchTextSize(names, boxes.firstOrNull()?.width() ?: return)
        for ((k, b) in boxes.withIndex()) {
            val t = Theme.byId(bl.words[k]) ?: continue
            val lit = k == bl.chosen
            pFill.color = if (lit) Theme.selected else Theme.raised
            c.drawRoundRect(b, 8f, 8f, pFill)
            val sw = RectF(b.left + 7f, b.top + 7f, b.right - 7f, b.bottom - 38f)
            GeneralPictures.swatch(c, sw, t)
            pStroke.color = android.graphics.Color.argb(200, 0, 0, 0); pStroke.strokeWidth = 1.5f
            c.drawRoundRect(sw, 6f, 6f, pStroke)
            edge(c, b, lit)
            pName.textSize = size; pName.color = if (lit) Theme.gold else Theme.text; pName.textAlign = Paint.Align.CENTER
            c.drawText(Tr.t(t.name), b.centerX(), b.bottom - 13f, pName)
            pName.textAlign = Paint.Align.LEFT; pName.textSize = 25f
        }
    }

    /** the one name size the band's swatches share: the largest (22 down to 15) at which the longest name fits a chip [w] wide */
    private fun swatchTextSize(names: List<String>, w: Float): Float {
        var s = 22f
        while (s > 15f) {
            pName.textSize = s
            if (names.all { pName.measureText(Tr.t(it)) <= w - 14f }) break
            s -= 1f
        }
        pName.textSize = 25f
        return s
    }

    private fun GameUiTiles.Box.rect() = RectF(l, t, r, b)

    /** [s] in [p] fitted to [maxW]: shrunk from [size] down to [min], then cut with an ellipsis (es / fr names run long) */
    private fun fitText(p: Paint, s: String, maxW: Float, size: Float, min: Float): String {
        p.textSize = size
        while (p.measureText(s) > maxW && p.textSize > min) p.textSize -= 1f
        if (p.measureText(s) <= maxW) return s
        val n = p.breakText(s, true, (maxW - p.measureText("…")).coerceAtLeast(0f), null)
        return s.substring(0, n).trimEnd() + "…"
    }

    private companion object {
        const val SLOP = 24f
        const val ARM_MS = 3000L
        const val SEG_TEXT = 24f
        val GAME_ACCOUNT = listOf("Ask each time", "Remembered")
    }
}
