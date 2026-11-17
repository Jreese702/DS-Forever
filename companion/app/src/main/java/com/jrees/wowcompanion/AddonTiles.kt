package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * SETTINGS > ADD-ONS AS PICTURE CARDS (the beautify pass, 2026-11-17; the user: "I REALLY REALLY love what you did with the top screen
 * settings menu with the pictures and want to do something similar to the other menus"; the design's section 5, critic C2 / C3 / C10).
 *
 * The third-party add-ons are ONE row of standard tiles - Polestar, Spoken, Manners, and QuestTrail only while a copy is there (the
 * existing gate, SettingsTabs.shown) - in the Top screen tiles' language: the same 232 x 184 tile and 216 x 108 well ([GameUiTiles]'
 * geometry), the same ring edge, badge and words, ONE band under the row with the picture large and the lines. Select, then ONE action:
 * a tap or A on a card opens its band (another card's closes), B closes it with the cursor back on the card.
 *
 *  - The badge is Top's set (C2): installed = ON; on its way in or out (waiting, downloading, downloaded, moving out) = SENT; a move
 *    that failed = REFUSED; not installed = none, the well dimmed. The value word: Installed / the download's size / its % / Next start.
 *  - The band: the ONE action first (Install; Uninstall twice within 3 s, the Reload pattern; Cancel; Keep it; Try again; Restart game),
 *    then, only while installed, the add-on's own lines (Spoken's modules and stop rules; Polestar's quest guide and its QoL switches).
 *    A line that cannot act now is not drawn (C10): nothing to press while a download runs (its % is the status line), Restart only
 *    while the game runs, Polestar's per-character switches only with a character.
 *  - The status line says state only (the add-on's own status words, by-line and licence), never what a setting does.
 *
 * The writes are the old rows' own (ThirdPartyAddons.setCore / setModule / setOption / retry, PolestarGuide.set, PolestarQol.set, the
 * host's Restart and add-on settings): only where and how they are drawn changed. Voice models and Imported keep their compact rows
 * (SettingsView), as does the Import button.
 */
object AddonCards {
    /** what a card's add-on is doing (its core package): the badge, the value word and the band's action follow it */
    enum class State { NONE, WAITING, DOWNLOADING, DOWNLOADED, INSTALLED, REMOVING, FAILED }

    /** what the band's lines depend on beyond the add-on: the game is up (Restart), a restart was pressed (it runs ~20 s), the armed
     *  two-tap ("<addon>.uninstall"), the character (Polestar's per-character switches) */
    data class Env(val gameRunning: Boolean = false, val restarting: Boolean = false, val armed: String? = null, val who: String? = null)

    /** one line of a band: an [action] (ONE button, [words][0]) or a choice of [words] ([chosen] lit, -1 = none); [label] / [value] in
     *  the label column (null label = a line across, the card's own choice as Top's first line); [signs] = On / Off with the eye signs */
    class Line(val key: String, val label: String?, val value: String?, val words: List<String>, val chosen: Int = -1,
               val action: Boolean = false, val armed: Boolean = false, val signs: Boolean = false)

    /** the cards now, in the tab data's order (SettingsTabs.Group.CARDS; QuestTrail only while a copy is there) */
    fun slots(): List<ThirdPartyAddons.Addon> = SettingsTabs.blocks(SettingsTabs.Group.CARDS).mapNotNull { SettingsTabs.addonOf(it) }.distinct()

    fun state(a: ThirdPartyAddons.Addon): State {
        val st = ThirdPartyAddons.status(a, "core")
        return when (st.kind) {
            ThirdPartyAddons.Kind.NONE -> State.NONE
            ThirdPartyAddons.Kind.WAITING_WIFI, ThirdPartyAddons.Kind.NO_SPACE -> State.WAITING
            ThirdPartyAddons.Kind.DOWNLOADING -> if (st.pct >= 0) State.DOWNLOADING else State.WAITING
            ThirdPartyAddons.Kind.DOWNLOADED -> State.DOWNLOADED
            ThirdPartyAddons.Kind.INSTALLED -> State.INSTALLED
            ThirdPartyAddons.Kind.REMOVING -> State.REMOVING
            ThirdPartyAddons.Kind.FAILED -> State.FAILED
        }
    }

    /** Top's badge set (C2): installed = ON, on its way = SENT, failed = REFUSED, not installed = none */
    fun badge(a: ThirdPartyAddons.Addon): GameUiTiles.Badge? = when (state(a)) {
        State.INSTALLED -> GameUiTiles.Badge.ON
        State.FAILED -> GameUiTiles.Badge.REFUSED
        State.NONE -> null
        else -> GameUiTiles.Badge.SENT
    }

    /** the well dims while the add-on is not installed and not on its way */
    fun dimmed(a: ThirdPartyAddons.Addon): Boolean = state(a) == State.NONE

    /** the card's value word (Tr.t'd by the drawer): Installed, its size, its download's %, Next start */
    fun value(a: ThirdPartyAddons.Addon): String = when (state(a)) {
        State.INSTALLED -> "Installed"
        State.NONE -> ThirdPartyAddons.size(a.core.mb)
        State.DOWNLOADING -> "${ThirdPartyAddons.status(a, "core").pct}%"
        State.WAITING -> "Waiting"
        State.DOWNLOADED, State.REMOVING -> "Next start"
        State.FAILED -> "Failed"
    }

    /** a band line's short label for an add-on option (the option's own title is the old row's; the band's label column is narrow) */
    val OPTION_LABELS = mapOf("stopClose" to "Stop on B", "stopAccept" to "Stop on Accept")

    /** Polestar's QoL switches (per character: PolestarQol), in the old rows' order */
    val PQ = listOf(PolestarQol.QUEST to "Auto quest", PolestarQol.VENDOR to "Auto vendor", PolestarQol.REPAIR to "Auto repair")

    /** something of [a] is downloaded and waits for the game to start again */
    fun waitsForStart(a: ThirdPartyAddons.Addon): Boolean =
        a.pkgs().any { (k, _) -> ThirdPartyAddons.status(a, k).kind == ThirdPartyAddons.Kind.DOWNLOADED } ||
            (a.base && ThirdPartyAddons.status(a, "core").kind == ThirdPartyAddons.Kind.REMOVING)

    /** the open card's band now: the ONE action first, then (installed) its own lines; a line that cannot act now is not there */
    fun lines(a: ThirdPartyAddons.Addon, env: Env): List<Line> {
        val out = ArrayList<Line>()
        val installed = ThirdPartyAddons.coreInstalled(a)
        val wanted = ThirdPartyAddons.coreWanted(a)
        val s = state(a)
        if (a.base) {
            // Polestar: part of the base install, its Install / Uninstall IS the quest guide's On / Off (PolestarGuide), Quest tracking's
            // Vanilla / Polestar the same choice (so one line): the card's own choice, across, with Top's eye signs
            val g = Settings.polestarGuide
            out += Line("guide", null, null, listOf("On", "Off"), if (g == PolestarGuide.ON) 0 else if (g == PolestarGuide.OFF) 1 else -1, signs = true)
            when {
                wanted && ThirdPartyAddons.anyFailed(a) -> out += Line("retry", "Failed", null, listOf("Try again"), action = true)
                env.gameRunning && !env.restarting && waitsForStart(a) -> out += Line("restart", "Next start", null, listOf("Restart game"), action = true)
            }
            if (installed && g == PolestarGuide.ON && env.who != null)
                for ((bit, label) in PQ) out += Line("pq.$bit", label, null, listOf("On", "Off"), if (PolestarQol.on(env.who, bit)) 0 else 1)
            return out
        }
        val armedOut = env.armed == "${a.id}.uninstall"
        when {
            waitsForStart(a) && env.gameRunning -> if (!env.restarting) out += Line("restart", "Next start", null, listOf("Restart game"), action = true)
            wanted && ThirdPartyAddons.anyFailed(a) -> out += Line("core", "Failed", null, listOf("Try again"), action = true)
            s == State.DOWNLOADING -> {}                                        // its % is the status line: nothing to press
            installed && wanted -> out += Line("core", "Version", a.version, listOf(if (armedOut) "Tap again" else "Uninstall"), action = true, armed = armedOut)
            installed -> out += Line("core", "Next start", null, listOf("Keep it"), action = true)      // moving out at the next start
            wanted -> out += Line("core", "Waiting", null, listOf("Cancel"), action = true)            // Wi-Fi, room, a failure
            else -> out += Line("core", "Download", ThirdPartyAddons.size(a.core.mb), listOf("Install"), action = true)
        }
        if (!installed) return out
        for (m in a.modules) {
            val on = ThirdPartyAddons.moduleOn(a, m)
            val st = ThirdPartyAddons.status(a, m.id)
            val v = when {
                !on -> ThirdPartyAddons.size(m.pkg.mb)
                st.kind == ThirdPartyAddons.Kind.DOWNLOADING && st.pct >= 0 -> "${st.pct}%"
                st.kind == ThirdPartyAddons.Kind.INSTALLED -> null
                st.kind == ThirdPartyAddons.Kind.FAILED -> "Failed"
                st.kind == ThirdPartyAddons.Kind.DOWNLOADED -> "Next start"
                else -> "Waiting"
            }
            out += Line("m.${m.id}", m.title, v, listOf("On", "Off"), if (on) 0 else 1)
        }
        for (o in a.options) out += Line("o.${o.id}", OPTION_LABELS[o.id] ?: o.title, null, listOf("On", "Off"), if (ThirdPartyAddons.option(a, o)) 0 else 1)
        // its own settings view (QuestTrail's options pages, mirrored), only with the game up
        a.settings?.let { if (env.gameRunning) out += Line("settings", it, null, listOf("Open"), action = true) }
        return out
    }

    /** the band's one status line: state only - what the add-on is doing (its own words, through [tr]), else its by-line and licence
     *  (names, never translated; the version is the action line's value) */
    fun status(a: ThirdPartyAddons.Addon, env: Env, tr: (String) -> String = { it }): String {
        if (env.restarting) return tr("Restarting...")
        val st = ThirdPartyAddons.status(a, "core")
        return when (state(a)) {
            State.INSTALLED, State.NONE -> "${a.by} · ${a.license}"
            State.DOWNLOADING -> "${tr(st.line)} (${st.pct}%)"
            else -> tr(st.line)
        }
    }
}

/** a part with ONE band open at a time (the Top screen tiles' contract: GameUiSection has the same four), so the room's B closes it with
 *  the cursor back on its tile and the cursor follows a band that opens */
interface BandPart {
    val hasSelection: Boolean
    fun closeSelection(): Boolean
    fun selectionAnchor(): RectF?
    fun bandBox(): RectF?
}

/**
 * The cards' view: the Add-ons tab's CARDS group (headless, always open), between the Import button and the Voice / Imported lists.
 * [host] = the room's SettingsView, for its wiring (the game is up, Restart, an add-on's own settings).
 */
class AddonTiles(context: Context, private val host: SettingsView) : View(context), BandPart {
    /** a card's band opened (or moved to another card): the room closes any other band and brings the card and its band in view */
    var onOpen: ((AddonTiles) -> Unit)? = null
    /** the cards came or went (QuestTrail installed or removed): the room measures again */
    var onContent: (() -> Unit)? = null

    private var selected: String? = null
    private var armed: String? = null
    private var armedAt = 0L
    private var restartAt = -RESTART_MS
    private var downX = 0f
    private var downY = 0f

    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val pName by themed { Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = NAME_SIZE; color = Theme.text } }
    private val pValue by themed { Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; color = Theme.gold } }
    private val pBandLabel by themed { Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 25f; color = Theme.text } }
    private val pSub by themed { Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; color = Theme.textDim } }
    private val pSeg = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 27f; textAlign = Paint.Align.LEFT }
    private val tilePath = Path()

    init {
        val again = { post { refresh() } }
        ThirdPartyAddons.onChange { again() }
        Settings.onChange { again() }
        Theme.onChange { postInvalidate() }
    }

    // ---------------------------------------------------------------- the model as the band shows it now

    private fun env() = AddonCards.Env(host.gameRunning(), SystemClock.elapsedRealtime() - restartAt < RESTART_MS,
        armed?.takeIf { SystemClock.elapsedRealtime() - armedAt < ARM_MS }, GameUi.character)

    private fun slots() = AddonCards.slots()

    /** the cards' layout at [w]: the slots, the grid, the open band and its lines */
    private class Layout(val slots: List<ThirdPartyAddons.Addon>, val grid: GameUiTiles.Grid, val band: GameUiTiles.Band?,
                         val lines: List<AddonCards.Line>, val buttons: List<List<RectF>>)

    private fun layout(w: Float): Layout {
        val ids = slots()
        val open = selected?.let { s -> ids.indexOfFirst { it.id == s } } ?: -1
        val lines = if (open >= 0) AddonCards.lines(ids[open], env()) else emptyList()
        val gl = lines.map { GameUiTiles.Line(it.key, it.words.size, it.label != null) }
        val bw = w - 2f * GameUiTiles.MARGIN
        val bandH = if (open >= 0) GameUiTiles.band(GameUiTiles.MARGIN, 0f, bw, gl).height else 0f
        val grid = GameUiTiles.grid(ids.size, w, open, bandH)
        val band = grid.band?.let { GameUiTiles.band(it.l, it.t, it.w, gl) }
        // an action's ONE button keeps the red button's own proportions (a 600 px plate reads as a bar, not a button)
        val buttons = band?.lines?.mapIndexed { i, lb ->
            lb.buttons.map { b ->
                val r = RectF(b.l, b.t, b.r, b.b)
                if (lines.getOrNull(i)?.action == true && r.width() > ACTION_W) r.right = r.left + ACTION_W
                r
            }
        } ?: emptyList()
        return Layout(ids, grid, band, lines, buttons)
    }

    private fun GameUiTiles.Box.rect() = RectF(l, t, r, b)

    /** what decides the height: the cards, the open one and its band's lines */
    private fun shape(): List<String> = slots().map { it.id } + listOf("|", selected ?: "") +
        (selected?.let { s -> slots().firstOrNull { it.id == s }?.let { a -> AddonCards.lines(a, env()).map { it.key } } } ?: emptyList())
    private var drawnShape: List<String> = emptyList()

    private fun refresh() {
        if (selected != null && slots().none { it.id == selected }) selected = null
        val before = drawnShape
        if (shape() != before) { requestLayout(); onContent?.invoke() }
        invalidate()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = getDefaultSize(suggestedMinimumWidth, widthSpec)
        drawnShape = shape()
        setMeasuredDimension(w, resolveSize(layout(w.toFloat()).grid.height.toInt(), heightSpec))
    }

    /** the cards there are (Spoken and Manners always) */
    fun hasContent(): Boolean = slots().isNotEmpty()

    /** the add-on ids drawn now, in order (the room's tests) */
    fun cardIds(): List<String> = slots().map { it.id }
    /** the card open now (its band shown), null = none */
    val openCard: String? get() = selected
    /** the open band's lines' keys (the tests) */
    fun bandKeys(): List<String> = selected?.let { s -> slots().firstOrNull { it.id == s }?.let { a -> AddonCards.lines(a, env()).map { it.key } } } ?: emptyList()

    /** the controller's stops: every card, then the open band's buttons (its picture and status line are not stops) */
    fun focusTargets(): List<RectF> {
        val l = layout(width.toFloat())
        return l.grid.tiles.map { it.rect() } + l.buttons.flatten().map { RectF(it) }
    }

    override val hasSelection: Boolean get() = selected != null
    override fun closeSelection(): Boolean {
        if (selected == null) return false
        selected = null; armed = null; requestLayout(); invalidate()
        return true
    }
    override fun selectionAnchor(): RectF? {
        val s = selected ?: return null
        val l = layout(width.toFloat())
        return l.slots.indexOfFirst { it.id == s }.takeIf { it >= 0 }?.let { l.grid.tiles[it].rect() }
    }
    override fun bandBox(): RectF? = if (selected == null) null else layout(width.toFloat()).grid.band?.rect()

    // ---------------------------------------------------------------- touch (A is a touch at the button's centre: SettingsPad)

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
            MotionEvent.ACTION_UP -> {
                if (abs(e.x - downX) > SLOP || abs(e.y - downY) > SLOP) return true
                val l = layout(width.toFloat())
                for ((i, btns) in l.buttons.withIndex()) {
                    val k = btns.indexOfFirst { it.contains(e.x, e.y) }
                    if (k >= 0) { press(l.slots.first { it.id == selected }, l.lines[i], k); refresh(); return true }
                }
                val i = l.grid.tiles.indexOfFirst { it.contains(e.x, e.y) }
                if (i >= 0) {
                    val id = l.slots[i].id
                    selected = if (selected == id) null else id
                    armed = null
                    requestLayout(); invalidate()
                    if (selected != null) onOpen?.invoke(this)
                }
            }
        }
        return true
    }

    /** button [k] of [line] on [a]'s band: one player-pressed write, the old row's own */
    private fun press(a: ThirdPartyAddons.Addon, line: AddonCards.Line, k: Int) {
        val now = SystemClock.elapsedRealtime()
        val on = k == 0
        when {
            line.key == "guide" -> PolestarGuide.set(on)
            line.key == "retry" -> ThirdPartyAddons.retry(a)
            line.key == "restart" -> { restartAt = now; host.onRestart(); postDelayed({ refresh() }, RESTART_MS + 50) }
            line.key == "settings" -> host.onAddonSettings(a)
            line.key.startsWith("pq.") -> PolestarQol.set(GameUi.character, line.key.removePrefix("pq.").toInt(), on)
            line.key.startsWith("m.") -> a.modules.firstOrNull { it.id == line.key.removePrefix("m.") }?.let { ThirdPartyAddons.setModule(a, it, on) }
            line.key.startsWith("o.") -> a.options.firstOrNull { it.id == line.key.removePrefix("o.") }?.let { ThirdPartyAddons.setOption(a, it, on) }
            line.key == "core" -> {
                val installed = ThirdPartyAddons.coreInstalled(a)
                val wanted = ThirdPartyAddons.coreWanted(a)
                when {
                    wanted && ThirdPartyAddons.anyFailed(a) -> ThirdPartyAddons.retry(a)                 // its Try again (#135)
                    installed && wanted -> {
                        // Uninstall: twice within 3 s, as Reload (its folders go to the backup at the next start, never deleted)
                        if (armed == "${a.id}.uninstall" && now - armedAt < ARM_MS) { armed = null; ThirdPartyAddons.setCore(a, false) }
                        else { armed = "${a.id}.uninstall"; armedAt = now; postDelayed({ invalidate() }, ARM_MS + 50) }
                    }
                    else -> ThirdPartyAddons.setCore(a, !wanted)                                       // Install / Cancel / Keep it
                }
            }
        }
    }

    // ---------------------------------------------------------------- drawing (the Top screen tiles' look: GameUiSection's tiles)

    private fun fonts() { UiKit.asBody(pName); UiKit.asBody(pValue); UiKit.asBody(pBandLabel); UiKit.asBody(pSub); UiKit.asBody(pSeg) }

    override fun onDraw(c: Canvas) {
        fonts()
        c.drawColor(Theme.ink)
        if (shape() != drawnShape) requestLayout()
        val l = layout(width.toFloat())
        var busy = false
        for ((i, a) in l.slots.withIndex()) {
            drawTile(c, l.grid.tiles[i], a, a.id == selected)
            val s = AddonCards.state(a)
            if (s == AddonCards.State.DOWNLOADING || s == AddonCards.State.WAITING) busy = true
        }
        val band = l.band
        val open = l.slots.indexOfFirst { it.id == selected }
        if (band != null && open >= 0) drawBand(c, l.grid.band!!, band, l.grid.tiles[open], l.slots[open], l)
        // a download moves: its % and the band's line follow; an armed Uninstall disarms visibly
        if (busy || selected?.let { s -> l.slots.firstOrNull { it.id == s } }?.let { a -> a.modules.any {
                ThirdPartyAddons.status(a, it.id).kind == ThirdPartyAddons.Kind.DOWNLOADING } } == true) postInvalidateDelayed(1000)
    }

    /** the well: the world's colour, the picture, the dim while not installed, the dark edge */
    private fun picture(c: Canvas, a: ThirdPartyAddons.Addon, well: RectF) {
        pFill.color = Color.rgb(14, 16, 18)
        c.drawRoundRect(well, 8f, 8f, pFill)
        AddonPictures.draw(c, context, a, well, kit = kit) { postInvalidate() }
        if (AddonCards.dimmed(a)) {
            pFill.color = Theme.ink; pFill.alpha = 165
            c.drawRoundRect(well, 8f, 8f, pFill)
            pFill.alpha = 255
        }
        pStroke.color = Color.argb(200, 0, 0, 0); pStroke.strokeWidth = 2f
        c.drawRoundRect(well, 8f, 8f, pStroke)
    }

    /** one card: its ground and edge (lit while its band is open; the cursor's gold box is the cursor, never this), the picture, the
     *  badge on its corner, the name and the value word */
    private fun drawTile(c: Canvas, box: GameUiTiles.Box, a: ThirdPartyAddons.Addon, open: Boolean) {
        val rect = box.rect()
        pFill.color = if (open) Theme.selected else Theme.raised
        c.drawRoundRect(rect, 10f, 10f, pFill)
        picture(c, a, GameUiTiles.well(box).rect())
        AddonCards.badge(a)?.let { TilePictures.badge(c, GameUiTiles.badgeBox(box).rect(), it) }
        edge(c, rect, open)
        val x = box.l + GameUiTiles.WELL_PAD + 2f
        val maxW = box.w - 2f * GameUiTiles.WELL_PAD - 4f
        pName.color = Theme.text
        c.drawText(fitText(pName, a.title, maxW, NAME_SIZE, NAME_MIN), x, box.t + GameUiTiles.WELL_PAD + GameUiTiles.WELL_H + 34f, pName)
        pValue.color = if (AddonCards.state(a) == AddonCards.State.INSTALLED) Theme.gold else Theme.textDim
        c.drawText(fitText(pValue, Tr.t(AddonCards.value(a)), maxW, 22f, 18f), x, box.b - 12f, pValue)
    }

    /** a card's or the band's edge: the game's metal ring (the kit's ring_square), lit for the open card; without it, the drawn ring */
    private fun edge(c: Canvas, r: RectF, lit: Boolean) {
        if (kit && UiKit.ringBox(c, r, 0.8f, lit = lit)) return
        val w = UiKit.RING_W * 0.8f
        tilePath.reset()
        tilePath.addRoundRect(RectF(r.left + w / 2f, r.top + w / 2f, r.right - w / 2f, r.bottom - w / 2f), 9f, 9f, Path.Direction.CW)
        UiKit.ringEdge(c, tilePath, w, lit = lit)
    }

    /** the open card's band: under its row, full width, the notch at the card; the picture large on the left, the lines, one status */
    private fun drawBand(c: Canvas, box: GameUiTiles.Box, band: GameUiTiles.Band, tile: GameUiTiles.Box, a: ThirdPartyAddons.Addon, l: Layout) {
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
        picture(c, a, band.picture.rect())
        for ((i, lb) in band.lines.withIndex()) {
            val line = l.lines.getOrNull(i) ?: continue
            lb.label?.let { lab ->
                pBandLabel.color = Theme.text
                val name = fitText(pBandLabel, Tr.t(line.label ?: ""), lab.w - 6f, 25f, 18f)
                if (line.value != null) {
                    c.drawText(name, lab.l + 4f, lab.cy - 6f, pBandLabel)
                    pValue.color = Theme.gold
                    c.drawText(fitText(pValue, Tr.t(line.value), lab.w - 6f, 22f, 18f), lab.l + 4f, lab.cy + 26f, pValue)
                } else c.drawText(name, lab.l + 4f, lab.cy + 9f, pBandLabel)
            }
            for ((k, b) in l.buttons[i].withIndex()) {
                val w = line.words.getOrElse(k) { "" }
                when {
                    line.action -> UiKit.button(c, b, w, if (line.armed) UiKit.Button.DOWN else UiKit.Button.UP, textSize = 27f)
                    line.signs -> modeChoice(c, b, if (k == 0) UiMode.ON else UiMode.OFF, k == line.chosen)
                    else -> UiKit.choice(c, b, Tr.t(w), k == line.chosen, true, textSize = 27f)
                }
            }
        }
        val st = band.status
        pSub.color = Theme.textDim
        c.drawText(fitText(pSub, AddonCards.status(a, env()) { Tr.t(it) }, st.w - 8f, 22f, 18f), st.l + 4f, st.cy + 8f, pSub)
        pSub.textSize = 22f
        // a download under way: its fill under the words (the game's gold on the ink; not a stop)
        if (AddonCards.state(a) == AddonCards.State.DOWNLOADING) {
            val pct = ThirdPartyAddons.status(a, "core").pct.coerceIn(0, 100)
            val bar = RectF(st.l + 4f, st.b + 10f, minOf(st.r, st.l + 4f + 420f), st.b + 26f)
            pFill.color = Theme.ink; c.drawRoundRect(bar, 6f, 6f, pFill)
            pFill.color = Theme.gold
            if (pct > 0) c.drawRoundRect(RectF(bar.left + 3f, bar.top + 3f, bar.left + 3f + (bar.width() - 6f) * pct / 100f, bar.bottom - 3f), 4f, 4f, pFill)
            pStroke.color = Theme.bronze; pStroke.strokeWidth = 2f; c.drawRoundRect(bar, 6f, 6f, pStroke)
        }
    }

    /** the card's own On / Off: the game's red button, lit when chosen, the eye sign beside the word (Top's first line) */
    private fun modeChoice(c: Canvas, b: RectF, m: UiMode, on: Boolean) {
        UiKit.choice(c, b, "", on, true)
        val col = if (on) Theme.gold else Theme.textDim
        val w = Tr.t(if (m == UiMode.ON) "On" else "Off")
        pSeg.textSize = 27f
        pSeg.color = col
        pStroke.color = col; pStroke.strokeWidth = 2.4f
        val total = 44f + 14f + pSeg.measureText(w)
        val ix = b.centerX() - total / 2f + 22f
        TilePictures.modeSign(c, m, ix, b.centerY(), 1.6f, pStroke)
        c.drawText(w, ix + 22f + 14f, b.centerY() + 10f, pSeg)
    }

    /** [s] in [p] fitted to [maxW]: shrunk from [size] down to [min], then cut with an ellipsis (es / fr run long) */
    private fun fitText(p: Paint, s: String, maxW: Float, size: Float, min: Float): String {
        p.textSize = size
        while (p.measureText(s) > maxW && p.textSize > min) p.textSize -= 1f
        if (p.measureText(s) <= maxW) return s
        val n = p.breakText(s, true, (maxW - p.measureText("…")).coerceAtLeast(0f), null)
        return s.substring(0, n).trimEnd() + "…"
    }

    companion object {
        const val SLOP = 24f
        const val NAME_SIZE = 25f
        const val NAME_MIN = 18f
        /** an action's one button at most this wide */
        const val ACTION_W = 320f
        /** Uninstall's two-tap window (Reload's) */
        const val ARM_MS = 3000L
        /** a pressed Restart runs this long (the game's own Exit Game, ~20 s outside a rested area): its line stays away meanwhile */
        const val RESTART_MS = 60_000L
        /** the shots' no-kit pictures (*_nokit): the drawn fallbacks, never the kit */
        @JvmStatic var kit = true
    }
}
