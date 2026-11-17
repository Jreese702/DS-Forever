package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max

/**
 * A part of the Settings room that opens ONE band under a tile (Top screen's GameUiSection tiles, the Controller tab's [ControllerTiles]):
 * the room's pad closes it with B and puts the cursor back on its tile.
 */
interface SettingsBands {
    val hasSelection: Boolean
    fun selectionAnchor(): RectF?
    fun closeSelection(): Boolean
    fun bandBox(): RectF?
}

/**
 * THE CONTROLLER TAB AS PICTURE TILES (the beautify pass; the user, 2026-11-17: "I REALLY REALLY love what you did with the top screen
 * settings menu with the pictures and want to do something similar to the other menus where we can in general I want this to be an ultra
 * code 'beautification' task"). The design (res/beautify_design.md section 4, critic C6): THREE tile rows - Buttons (ABXY location, Button
 * glyphs, B: pet follow for a pet class, Smart Y as the one WIDE tile), Wheels (one tile per wheel block: Pause, Item, Spell, Left and
 * Right class), Combat helpers (Party mode, Smart Res, Start attack, Attack on cast, Auto Shot) - drawn in Top screen's tile language
 * (GameUiTiles' 232 x 184 tiles, the kit's ring edge, the 36 px badge, the band under the row with the picture large and the choices
 * as the game's red buttons).
 *
 * A tap or A on a tile opens its band (another tile's closes); a band button applies ONE player-pressed choice through the same setter /
 * channel its old row used (CastSettings, HomeSettings, GameUi's switches, HomeAttackSwitch, Smart Y's taps); B closes the band with the
 * cursor back on the tile. Smart Y's band carries its On / Off and a "List" action that opens the Smart Y list as a sub-page
 * ([SmartYPage]: the list moved as it is). No sub-captions (the terse rule): the picture says what it does. Wheels are app-wide;
 * the pet's B, Smart Y and the GameUi helpers are per character (their stores, unchanged). Combat helpers keep Top's game-state badges
 * (ON / OFF / SENT / REFUSED / WAITING); the app-local tiles carry none. A slot that does not apply is not drawn (B: pet follow on a
 * class without a pet, Smart Res on a class without a res). Auto Shot shows for every class, as its old row did (bows, guns, wands).
 *
 * The tile engine of the shared core (TileSource / TileDraw) had not landed when this was built (core and the areas ran side by side):
 * this view keeps the TileSource shape ([slots] / [name] / [value] / [lines] / [badge] / [dimmed] / [press] / the sub-page) so the
 * integrator can move the drawing onto TileDraw without touching the model.
 */
class ControllerTiles(context: Context, val group: SettingsTabs.Group, private val hub: SettingsView) : View(context), SettingsBands {

    // ---------------------------------------------------------------- the model (TileSource's shape)

    data class Slot(val id: String, val wide: Boolean = false)

    /** one line of a band: its choices ([words], [modes] = the On / Off eye signs, [glyphs] = a pad glyph per choice), the chosen one
     *  ([chosen], -1 = none lit), a label column ([label]), greyed while it cannot act yet ([enabled]); a choice with a glyph and no
     *  word is the glyph alone (the wheels' A) */
    class Line(val key: String, val label: String?, val words: List<String>, val chosen: Int, val enabled: Boolean = true,
               val modes: List<UiMode>? = null, val glyphs: List<String?>? = null)

    /** the tiles this group shows now, in order (fixed per character: the add-on's word never reflows them) */
    fun slots(): List<Slot> = when (group) {
        SettingsTabs.Group.BUTTONS -> listOfNotNull(Slot(ControllerPictures.ABXY), Slot(ControllerPictures.GLYPHS),
            Slot(ControllerPictures.PET_B).takeIf { PetFollowB.shown(hub.petB()) }, Slot(ControllerPictures.SMART_Y, wide = true))
        // one tile per wheel block (SettingsTabs.Block: a later radial adds a tile, not a redesign)
        SettingsTabs.Group.WHEELS -> SettingsTabs.blocks(SettingsTabs.Group.WHEELS).mapNotNull { WHEELS[it] }.map { Slot(it) }
        SettingsTabs.Group.COMBAT -> listOfNotNull(Slot(GameUi.PMODE_ID),
            Slot(GameUi.SRES_ID).takeIf { GameUi.sresClass(GameUi.playerClass) != false },
            Slot(GameUi.ATK_ID), Slot(ControllerPictures.ATTACK_CAST),
            Slot(GameUi.SHOT_ID))     // every class, as the old row: bows, guns and wands (a Warrior's bow, a Mage's wand)
        else -> emptyList()
    }

    /** the tile's name (display: Tr.t'd where drawn) */
    fun name(id: String): String = NAMES[id] ?: id

    /** the choice's word (Tr.t'd where drawn; "..." while the game has not said) */
    fun value(id: String): String = when (id) {
        ControllerPictures.ABXY -> CastSettings.abxyLocation.label
        ControllerPictures.GLYPHS -> CastSettings.buttonLayout.label
        ControllerPictures.PET_B -> onOff(HomeSettings.petFollowB(GameUi.character))
        ControllerPictures.SMART_Y -> hub.smartDoc()?.let { onOff(it.on) } ?: WAIT
        ControllerPictures.ATTACK_CAST -> HomeAttackSwitch.state?.let { onOff(it.on) } ?: WAIT
        GameUi.SHOT_ID -> if (rowOf(id) == null) WAIT else GameUi.SHOT_WORDS[GameUi.shotMode().coerceIn(0, 2)]
        in WHEEL_IDS -> "${if (wheelTap(id)) "Tap" else "Hold"} · ${if (wheelPickA(id)) Glyphs.name("a") else "Release"}"
        else -> rowOf(id)?.let { onOff(it.mode != UiMode.OFF) } ?: WAIT
    }

    /** the open tile's band: its own choices first, then its other lines (a line that cannot act right now is not drawn) */
    fun lines(id: String): List<Line> = when (id) {
        ControllerPictures.ABXY -> listOf(Line(id, null, LOCATIONS.map { it.label }, LOCATIONS.indexOf(CastSettings.abxyLocation)))
        ControllerPictures.GLYPHS -> listOf(Line(id, null, LAYOUTS.map { it.label }, LAYOUTS.indexOf(CastSettings.buttonLayout)))
        ControllerPictures.PET_B -> listOf(onOffLine(id, HomeSettings.petFollowB(GameUi.character), GameUi.character != null))
        ControllerPictures.SMART_Y -> hub.smartDoc().let { d -> listOf(onOffLine(id, d?.on == true, d != null), Line(LIST, null, listOf("List"), -1)) }
        ControllerPictures.ATTACK_CAST -> HomeAttackSwitch.state.let { s -> listOf(onOffLine(id, s?.on == true, s != null && !s.paused).let {
            if (s == null || s.paused) Line(it.key, null, it.words, -1, false, it.modes) else it }) }
        GameUi.SHOT_ID -> rowOf(id).let { r ->
            if (GameUi.shotThree()) listOf(Line(id, null, GameUi.SHOT_WORDS, if (r == null) -1 else GameUi.shotMode(), r != null))
            else listOf(onOffLine(id, r?.mode == UiMode.ON, r != null).let { if (r == null) Line(it.key, null, it.words, -1, false, it.modes) else it }) }
        in WHEEL_IDS -> listOfNotNull(
            Line(OPEN, "Open", listOf("Tap", "Hold"), if (wheelTap(id)) 0 else 1),
            Line(PICK, "Pick", listOf("", "Release"), if (wheelPickA(id)) 0 else 1, glyphs = listOf("a", null)),
            if (id == ControllerPictures.PAUSE) Line(OVER, "Over windows", PauseOver.SEGMENTS, if (HomeSettings.pauseOverGame) 1 else 0) else null)
        else -> rowOf(id).let { r -> listOf(onOffLine(id, r?.mode == UiMode.ON, r != null).let {
            if (r == null) Line(it.key, null, it.words, -1, false, it.modes) else it }) }
    }

    /** the 36 px badge: the game's state for the add-on's switches (Top's set), none for the app's own settings */
    fun badge(id: String): GameUiTiles.Badge? = when (id) {
        ControllerPictures.SMART_Y -> hub.smartDoc()?.let { if (it.on) GameUiTiles.Badge.ON else GameUiTiles.Badge.OFF } ?: GameUiTiles.Badge.WAITING
        in GAME_ROWS -> GameUiTiles.badge(rowOf(id))
        else -> null
    }

    /** the game has not said yet (a dim "..." tile, its choices greyed) */
    fun waiting(id: String): Boolean = when (id) {
        ControllerPictures.SMART_Y -> hub.smartDoc() == null
        ControllerPictures.ATTACK_CAST -> HomeAttackSwitch.state == null
        in GAME_ROWS -> rowOf(id).let { it == null || !it.known }
        else -> false
    }

    /** the picture is dimmed: the choice is Off, or the game has not said */
    fun dimmed(id: String): Boolean = waiting(id) || when (id) {
        ControllerPictures.PET_B -> !HomeSettings.petFollowB(GameUi.character)
        ControllerPictures.SMART_Y -> hub.smartDoc()?.on != true
        ControllerPictures.ATTACK_CAST -> HomeAttackSwitch.state?.let { !it.on || it.paused } ?: true
        GameUi.SHOT_ID -> GameUi.shotMode() == 0
        in GAME_ROWS -> rowOf(id)?.mode == UiMode.OFF
        else -> false
    }

    /** the band's one status line: state only (sent, refused, waiting, paused), never an explanation; null = none */
    fun status(id: String): String? = when (id) {
        ControllerPictures.SMART_Y -> if (hub.smartDoc() == null) "Waiting for the game" else smartNote
        ControllerPictures.ATTACK_CAST -> HomeAttackSwitch.state.let { s -> when {
            s == null -> "Waiting for the game"; s.paused -> "Coming back later"; s.pending -> "Sent"; else -> null } }
        in GAME_ROWS -> rowOf(id).let { r ->
            when (GameUiTiles.badge(r)) {
                GameUiTiles.Badge.WAITING -> if (r == null || !r.known) "Waiting for the game" else r.note
                GameUiTiles.Badge.SENT, GameUiTiles.Badge.REFUSED -> r?.note
                else -> null
            }
        }
        else -> null
    }

    /** button [k] of line [line] on tile [id]: ONE player-pressed write, the channel the old row used */
    fun press(id: String, line: String, k: Int) {
        when (line) {
            LIST -> { onSubPage?.invoke(this); return }
            OPEN -> setWheelTap(id, k == 0)
            PICK -> setWheelPickA(id, k == 0)
            OVER -> HomeSettings.pauseOverGame = k == 1
            else -> when (id) {
                ControllerPictures.ABXY -> LOCATIONS.getOrNull(k)?.let { if (CastSettings.abxyLocation != it) { glideFrom = CastSettings.faceSwap
                    glideAt = SystemClock.uptimeMillis(); CastSettings.abxyLocation = it } }
                ControllerPictures.GLYPHS -> LAYOUTS.getOrNull(k)?.let { if (CastSettings.buttonLayout != it) CastSettings.buttonLayout = it }
                ControllerPictures.PET_B -> HomeSettings.setPetFollowB(GameUi.character, k == 0)
                ControllerPictures.SMART_Y -> hub.smartDoc()?.let { d -> val on = k == 0; if (d.on != on) smartSend(SmartYModel.toggleTaps(d, on)) }
                ControllerPictures.ATTACK_CAST -> HomeAttackSwitch.state?.let { s -> if (!s.paused) HomeAttackSwitch.turn(context, k == 0) }
                GameUi.SHOT_ID -> if (GameUi.shotThree()) GameUi.setShotMode(k) else GameUi.setSwitch(GameUi.SW_SHOT, k == 0)
                GameUi.PMODE_ID -> GameUi.setSwitch(GameUi.SW_PMODE, k == 0)
                GameUi.SRES_ID -> GameUi.setSwitch(GameUi.SW_SRES, k == 0)
                GameUi.ATK_ID -> GameUi.setSwitch(GameUi.SW_ATK, k == 0)
            }
        }
    }

    /** the Smart Y list's "List" was pressed: the room opens the list as a sub-page (SmartYPage) */
    var onSubPage: ((ControllerTiles) -> Unit)? = null
    /** a tile's band opened (or moved): the room closes any other band and brings the cursor and the band in view */
    var onOpen: ((ControllerTiles) -> Unit)? = null

    /** the Smart Y row's notes (SettingsView.sbSend's words): the taps not known yet, or the game not taking them; null = sent */
    private var smartNote: String? = null
    private fun smartSend(taps: List<Pair<String, Int>>?) {
        smartNote = when {
            taps == null -> "Open the Spellbook once, then again"
            hub.smartTaps(taps) -> null
            else -> "Game not live"
        }
    }

    private fun onOff(on: Boolean) = if (on) "On" else "Off"
    private fun onOffLine(id: String, on: Boolean, enabled: Boolean) =
        Line(id, null, listOf("On", "Off"), if (on) 0 else 1, enabled, listOf(UiMode.ON, UiMode.OFF))

    private fun rowOf(id: String): GameUi.Row? = when (id) {
        GameUi.PMODE_ID -> GameUi.partyModeRow(); GameUi.SRES_ID -> GameUi.smartResRow(); GameUi.ATK_ID -> GameUi.attackRow()
        GameUi.SHOT_ID -> GameUi.autoShotRow(); else -> null
    }

    private fun wheelTap(id: String) = when (id) {
        ControllerPictures.PAUSE -> HomeSettings.pauseTap; ControllerPictures.ITEM -> HomeSettings.radialTap
        ControllerPictures.SPELL -> HomeSettings.spellTap; ControllerPictures.CLASS_L -> HomeSettings.classTapL; else -> HomeSettings.classTapR
    }
    private fun wheelPickA(id: String) = when (id) {
        ControllerPictures.PAUSE -> HomeSettings.pausePickA; ControllerPictures.ITEM -> HomeSettings.radialPickA
        ControllerPictures.SPELL -> HomeSettings.spellPickA; ControllerPictures.CLASS_L -> HomeSettings.classPickAL; else -> HomeSettings.classPickAR
    }
    private fun setWheelTap(id: String, v: Boolean) = when (id) {
        ControllerPictures.PAUSE -> HomeSettings.pauseTap = v; ControllerPictures.ITEM -> HomeSettings.radialTap = v
        ControllerPictures.SPELL -> HomeSettings.spellTap = v; ControllerPictures.CLASS_L -> HomeSettings.classTapL = v
        else -> HomeSettings.classTapR = v
    }
    private fun setWheelPickA(id: String, v: Boolean) = when (id) {
        ControllerPictures.PAUSE -> HomeSettings.pausePickA = v; ControllerPictures.ITEM -> HomeSettings.radialPickA = v
        ControllerPictures.SPELL -> HomeSettings.spellPickA = v; ControllerPictures.CLASS_L -> HomeSettings.classPickAL = v
        else -> HomeSettings.classPickAR = v
    }

    /** what [id]'s picture shows now (the band's clock [t]) */
    private fun look(id: String, t: Float): ControllerPictures.Look {
        val glide = ((SystemClock.uptimeMillis() - glideAt) / 500f).coerceIn(0f, 1f)
        val d = hub.smartDoc()
        return when (id) {
            ControllerPictures.ABXY -> ControllerPictures.Look(nintendo = CastSettings.faceSwap, prevNintendo = if (glide < 1f) glideFrom else CastSettings.faceSwap,
                glide = glide, t = t)
            ControllerPictures.PET_B -> ControllerPictures.Look(on = HomeSettings.petFollowB(GameUi.character), t = t)
            ControllerPictures.SMART_Y -> ControllerPictures.Look(on = d?.on ?: true, t = t,
                smartIcons = d?.let { doc -> doc.rows.indices.mapNotNull { SmartYModel.icons(doc, it).firstOrNull() } } ?: emptyList(),
                smartDim = d?.let { doc -> doc.rows.mapIndexed { r, row -> r >= doc.max || (row.isBuff && row.ids.any { NoMana.has(it) }) } } ?: emptyList())
            in WHEEL_IDS -> ControllerPictures.Look(tap = wheelTap(id), pickA = wheelPickA(id), gameWheel = id == ControllerPictures.PAUSE && HomeSettings.pauseOverGame,
                items = if (id == ControllerPictures.ITEM || id == ControllerPictures.SPELL) runCatching { PreviewStage.wheelItems(id == ControllerPictures.SPELL) }
                    .getOrDefault(emptyList()) else emptyList(), t = t)
            ControllerPictures.ATTACK_CAST -> ControllerPictures.Look(on = HomeAttackSwitch.state?.on ?: true, t = t)
            GameUi.SHOT_ID -> ControllerPictures.Look(shot = if (rowOf(id) == null) 1 else GameUi.shotMode(), t = t)
            else -> ControllerPictures.Look(on = rowOf(id)?.mode != UiMode.OFF, t = t)
        }
    }

    /** the ABXY change's glide: where the buttons were, and when it started */
    private var glideFrom = CastSettings.faceSwap
    private var glideAt = 0L

    // ---------------------------------------------------------------- the view

    private var selected: String? = null
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val pName by themed { Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = NAME_SIZE; color = Theme.text } }
    private val pValue by themed { Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; color = Theme.gold } }
    private val pBandLabel by themed { Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 25f; color = Theme.text } }
    private val pSub by themed { Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; color = Theme.textDim } }
    private val pSeg = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = SEG_SIZE; typeface = Typeface.DEFAULT_BOLD }
    private val tilePath = Path()
    private var downX = 0f
    private var downY = 0f
    private val t0 = SystemClock.uptimeMillis()

    private val changed: () -> Unit = { post { refresh() } }

    init {
        HomeSettings.onChange(changed); CastSettings.onChange(changed); GameUi.onChange(changed); HomeAttackSwitch.onChange(changed)
        Theme.onChange { postInvalidate() }
    }

    /** the tiles' shape as last measured: a change asks for a new layout */
    private var shape: List<String> = emptyList()
    private fun shapeNow(): List<String> = slots().map { it.id } + listOf("|", selected ?: "") + (selected?.let { lines(it).map { l -> l.key } } ?: emptyList())

    /** what the add-on's documents decide here (Smart Y's list, the pet class), checked once a second while the tab shows: the
     *  room's host redraws its SettingsView on a padmap, which does not reach this view */
    private var docSig: Any? = null
    private val watch = object : Runnable {
        override fun run() {
            val sig = listOf(hub.smartDoc(), hub.petB()?.cls, GameUi.playerClass)
            if (sig != docSig) { docSig = sig; refresh() }
            if (isAttachedToWindow && isShown) postDelayed(this, 1000L)
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); removeCallbacks(watch); post(watch) }
    override fun onDetachedFromWindow() { removeCallbacks(watch); super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        removeCallbacks(watch); if (visibility == VISIBLE && isAttachedToWindow) post(watch)
    }

    fun refresh() {
        if (selected != null && slots().none { it.id == selected }) selected = null
        if (shapeNow() != shape) requestLayout()
        invalidate()
    }

    /** this part has tiles (the room shows its group): always - the slots are fixed */
    fun hasContent(): Boolean = slots().isNotEmpty()

    /** the tiles' layout at [w]: the slots, the grid, the open band */
    private class Layout(val slots: List<Slot>, val grid: GameUiTiles.Grid, val band: GameUiTiles.Band?, val lines: List<Line>)

    private fun layout(w: Float): Layout {
        val s = slots()
        val open = selected?.let { id -> s.indexOfFirst { it.id == id } } ?: -1
        val lines = if (open >= 0) lines(s[open].id) else emptyList()
        val gl = lines.map { GameUiTiles.Line(it.key, it.words.size, it.label != null) }
        val bw = w - 2f * GameUiTiles.MARGIN
        val bandH = if (open >= 0) GameUiTiles.band(GameUiTiles.MARGIN, 0f, bw, gl).height else 0f
        val grid = grid(s.map { it.wide }, w, open, bandH)
        val band = grid.band?.let { GameUiTiles.band(it.l, it.t, it.w, gl) }
        return Layout(s, grid, band, lines)
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = getDefaultSize(suggestedMinimumWidth, widthSpec)
        shape = shapeNow()
        setMeasuredDimension(w, resolveSize(layout(w.toFloat()).grid.height.toInt(), heightSpec))
    }

    private fun GameUiTiles.Box.rect() = RectF(l, t, r, b)

    /** the pad's stops: every tile, then the open band's buttons (its picture and status line are not stops) */
    fun focusTargets(): List<RectF> {
        val l = layout(width.toFloat())
        val out = ArrayList<RectF>()
        l.grid.tiles.forEach { out.add(it.rect()) }
        l.band?.lines?.forEach { line -> line.buttons.forEach { out.add(it.rect()) } }
        return out
    }

    override val hasSelection: Boolean get() = selected != null
    override fun closeSelection(): Boolean {
        if (selected == null) return false
        selected = null; requestLayout(); invalidate(); return true
    }
    override fun selectionAnchor(): RectF? = selected?.let { tileBox(it) }
    override fun bandBox(): RectF? = if (selected == null) null else layout(width.toFloat()).grid.band?.rect()

    /** [id]'s tile box in this part's coordinates, null = not drawn */
    fun tileBox(id: String): RectF? { val l = layout(width.toFloat()); return l.slots.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { l.grid.tiles[it].rect() } }
    /** the tile ids drawn now, in order (the tests) */
    fun tileIds(): List<String> = slots().map { it.id }
    /** the tile whose band is open, null = none */
    val openTile: String? get() = selected
    /** open [id]'s band as a tap would (the tests and the renders) */
    fun open(id: String) { if (slots().any { it.id == id }) { selected = id; requestLayout(); invalidate(); onOpen?.invoke(this) } }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
            MotionEvent.ACTION_UP -> {
                if (abs(e.x - downX) > SLOP || abs(e.y - downY) > SLOP) return true
                val l = layout(width.toFloat())
                val open = selected
                if (open != null) l.band?.lines?.forEachIndexed { i, line ->
                    val k = line.buttons.indexOfFirst { it.contains(e.x, e.y) }
                    val ln = l.lines.getOrNull(i)
                    if (k >= 0 && ln != null) { if (ln.enabled) press(open, ln.key, k); refresh(); return true }
                }
                val i = l.grid.tiles.indexOfFirst { it.contains(e.x, e.y) }
                if (i >= 0) {
                    val id = l.slots[i].id
                    selected = if (selected == id) null else id
                    requestLayout(); invalidate()
                    if (selected != null) onOpen?.invoke(this)
                } else if (open != null && l.grid.band?.contains(e.x, e.y) != true) closeSelection()   // a tap outside the band closes it
            }
        }
        return true
    }

    // ---------------------------------------------------------------- drawing (Top screen's tile look)

    override fun onDraw(c: Canvas) {
        UiKit.asBody(pName); UiKit.asBody(pValue); UiKit.asBody(pBandLabel); UiKit.asBody(pSub); UiKit.asBody(pSeg)
        c.drawColor(Theme.ink)
        val l = layout(width.toFloat())
        for ((i, s) in l.slots.withIndex()) drawTile(c, l.grid.tiles[i], s.id, s.id == selected)
        val band = l.band ?: return
        val open = l.slots.indexOfFirst { it.id == selected }
        if (open < 0) return
        val t = (SystemClock.uptimeMillis() - t0) / 1000f
        drawBand(c, l.grid.band!!, band, l.grid.tiles[open], selected!!, l.lines, t)
        // the open band's picture moves (up to 30 fps, only while the room shows)
        if (isShown && selected in ANIMATED) postInvalidateDelayed(33L)
        else if (isShown && selected == ControllerPictures.ABXY && SystemClock.uptimeMillis() - glideAt < 520L) postInvalidateDelayed(16L)
    }

    /** a picture in its well: the world's colour, the drawing (frame 0 on a tile; [t] in the band), the dim when Off or waiting */
    private fun picture(c: Canvas, id: String, well: RectF, t: Float) {
        pFill.color = Color.rgb(14, 16, 18)
        c.drawRoundRect(well, 8f, 8f, pFill)
        ControllerPictures.draw(c, context, id, well, look(id, t))
        if (dimmed(id)) { pFill.color = Theme.ink; pFill.alpha = 165; c.drawRoundRect(well, 8f, 8f, pFill); pFill.alpha = 255 }
        pStroke.color = Color.argb(200, 0, 0, 0); pStroke.strokeWidth = 2f
        c.drawRoundRect(well, 8f, 8f, pStroke)
    }

    /** one tile: ground (selected while open), picture, badge, the edge (lit while open: the cursor's gold box is the cursor), name, value */
    private fun drawTile(c: Canvas, box: GameUiTiles.Box, id: String, open: Boolean) {
        val rect = box.rect()
        pFill.color = if (open) Theme.selected else Theme.raised
        c.drawRoundRect(rect, 10f, 10f, pFill)
        picture(c, id, GameUiTiles.well(box).rect(), 0f)
        badge(id)?.let { TilePictures.badge(c, GameUiTiles.badgeBox(box).rect(), it) }
        edge(c, rect, open)
        val wait = waiting(id)
        val x = box.l + GameUiTiles.WELL_PAD + 2f
        val maxW = box.w - 2f * GameUiTiles.WELL_PAD - 4f
        pName.color = if (wait) Theme.textDim else Theme.text
        c.drawText(fitText(pName, Tr.t(name(id)), maxW, NAME_SIZE, NAME_MIN), x, box.t + GameUiTiles.WELL_PAD + GameUiTiles.WELL_H + 34f, pName)
        pValue.color = if (wait || dimmed(id)) Theme.textDim else Theme.gold
        c.drawText(fitText(pValue, valueWords(id), maxW, 22f, 18f), x, box.b - 12f, pValue)
    }

    /** the value line in the reader's language: each word through Tr.t (the wheels' two words joined) */
    private fun valueWords(id: String): String = value(id).split(" · ").joinToString(" · ") { Tr.t(it) }

    private fun edge(c: Canvas, r: RectF, lit: Boolean) {
        if (UiKit.ringBox(c, r, 0.8f, lit = lit)) return
        val w = UiKit.RING_W * 0.8f
        tilePath.reset()
        tilePath.addRoundRect(RectF(r.left + w / 2f, r.top + w / 2f, r.right - w / 2f, r.bottom - w / 2f), 9f, 9f, Path.Direction.CW)
        UiKit.ringEdge(c, tilePath, w, lit = lit)
    }

    private fun fitText(p: Paint, s: String, maxW: Float, size: Float, min: Float): String {
        p.textSize = size
        while (p.measureText(s) > maxW && p.textSize > min) p.textSize -= 1f
        if (p.measureText(s) <= maxW) return s
        val n = p.breakText(s, true, (maxW - p.measureText("…")).coerceAtLeast(0f), null)
        return s.substring(0, n).trimEnd() + "…"
    }

    /** the open tile's band: under its row, full width, the notch at the tile; the picture large, the lines, one status line */
    private fun drawBand(c: Canvas, box: GameUiTiles.Box, band: GameUiTiles.Band, tile: GameUiTiles.Box, id: String, lines: List<Line>, t: Float) {
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
        picture(c, id, band.picture.rect(), max(t, 0.001f))
        // ONE label size for the band: every line's label at the size the narrowest fits (a line under the picture has a narrower column)
        val labelSize = band.lines.withIndex().mapNotNull { (i, lb) -> lb.label?.let { lab -> lines.getOrNull(i)?.label?.let { w ->
            pBandLabel.textSize = 25f
            while (pBandLabel.measureText(Tr.t(w)) > lab.w - 6f && pBandLabel.textSize > 18f) pBandLabel.textSize -= 1f
            pBandLabel.textSize } } }.minOrNull() ?: 25f
        for ((i, lb) in band.lines.withIndex()) {
            val ln = lines.getOrNull(i) ?: continue
            lb.label?.let { lab ->
                pBandLabel.color = if (ln.enabled) Theme.text else Theme.textDim
                c.drawText(fitText(pBandLabel, Tr.t(ln.label ?: ""), lab.w - 6f, labelSize, 18f), lab.l + 4f, lab.cy + 9f, pBandLabel)
            }
            // ONE word size per line: every button of a line at the size its longest word fits (no "Game's wheel" smaller than "Our wheel")
            val size = lb.buttons.withIndex().minOfOrNull { (k, b) ->
                val sign = if (ln.modes?.getOrNull(k) != null) 58f else 0f
                wordSize(Tr.t(ln.words.getOrElse(k) { "" }), b.rect().width() - 44f - sign)
            } ?: SEG_SIZE
            for ((k, b) in lb.buttons.withIndex()) {
                val r = b.rect()
                val m = ln.modes?.getOrNull(k)
                val gl = ln.glyphs?.getOrNull(k)
                val word = ln.words.getOrElse(k) { "" }
                when {
                    m != null -> modeChoice(c, r, m, word, k == ln.chosen, ln.enabled, size)
                    gl != null -> glyphChoice(c, r, gl, k == ln.chosen, ln.enabled)
                    else -> wordChoice(c, r, word, k == ln.chosen, ln.enabled, size)
                }
            }
        }
        status(id)?.let { say ->
            val st = band.status
            pSub.color = Theme.textDim
            c.drawText(fitText(pSub, Tr.t(say), st.w - 8f, 22f, 18f), st.l + 4f, st.cy + 8f, pSub)
        }
    }

    /** the size (27 down to 20) at which [w] fits [room] px */
    private fun wordSize(w: String, room: Float): Float {
        pSeg.textSize = SEG_SIZE
        while (pSeg.measureText(w) > room && pSeg.textSize > 20f) pSeg.textSize -= 1f
        return pSeg.textSize.also { pSeg.textSize = SEG_SIZE }
    }

    private fun choiceColour(on: Boolean, enabled: Boolean) = if (!enabled) Theme.dim else if (on) Theme.gold else Theme.textDim

    /** an On / Off choice as Top's: the game's red button, the eye sign beside the word, lit when chosen */
    private fun modeChoice(c: Canvas, b: RectF, m: UiMode, word: String, on: Boolean, enabled: Boolean, size: Float) {
        UiKit.choice(c, b, "", on, enabled)
        val col = choiceColour(on, enabled)
        val w = Tr.t(word)
        pSeg.textSize = size
        val total = 44f + 14f + pSeg.measureText(w)
        val ix = b.centerX() - total / 2f + 22f
        pStroke.color = col; pStroke.strokeWidth = 2.4f
        TilePictures.modeSign(c, m, ix, b.centerY(), 1.6f, pStroke)
        pSeg.color = col; pSeg.textAlign = Paint.Align.LEFT
        c.drawText(w, ix + 36f, b.centerY() + 10f, pSeg)
        pSeg.textSize = SEG_SIZE
    }

    /** a plain choice: the game's red button, its word centred at the line's size */
    private fun wordChoice(c: Canvas, b: RectF, word: String, on: Boolean, enabled: Boolean, size: Float) {
        UiKit.choice(c, b, "", on, enabled)
        pSeg.textSize = size; pSeg.color = choiceColour(on, enabled); pSeg.textAlign = Paint.Align.CENTER
        c.drawText(Tr.t(word), b.centerX(), b.centerY() + size * 0.36f, pSeg)
        pSeg.textAlign = Paint.Align.LEFT; pSeg.textSize = SEG_SIZE
    }

    /** a choice that is a face button alone (the wheels' Pick: A), in the chosen set; its letter on a disc without the kit */
    private fun glyphChoice(c: Canvas, b: RectF, action: String, on: Boolean, enabled: Boolean) {
        UiKit.choice(c, b, "", on, enabled)
        val save = if (enabled) -1 else c.saveLayerAlpha(RectF(b), 120)
        ControllerPictures.glyph(c, action, b.centerX(), b.centerY(), 48f)
        if (save >= 0) c.restoreToCount(save)
    }

    companion object {
        const val SLOP = 24f
        const val NAME_SIZE = 25f
        const val NAME_MIN = 18f
        const val SEG_SIZE = 27f
        const val WAIT = "..."
        const val LIST = "list"; const val OPEN = "open"; const val PICK = "pick"; const val OVER = "over"

        private val LOCATIONS = CastSettings.AbxyLocation.values().toList()
        private val LAYOUTS = CastSettings.ButtonLayout.values().toList()

        /** the wheel each Wheels block draws (SettingsTabs' blocks, so a later radial adds a tile) */
        val WHEELS: Map<SettingsTabs.Block, String> = mapOf(
            SettingsTabs.Block.PAUSE_WHEEL to ControllerPictures.PAUSE, SettingsTabs.Block.ITEM_WHEEL to ControllerPictures.ITEM,
            SettingsTabs.Block.SPELL_WHEEL to ControllerPictures.SPELL, SettingsTabs.Block.CLASS_LEFT to ControllerPictures.CLASS_L,
            SettingsTabs.Block.CLASS_RIGHT to ControllerPictures.CLASS_R)
        private val WHEEL_IDS = WHEELS.values.toSet()
        /** the add-on's switches (their badges are the game's state, as on Top screen) */
        private val GAME_ROWS = setOf(GameUi.PMODE_ID, GameUi.SRES_ID, GameUi.ATK_ID, GameUi.SHOT_ID)
        /** the band pictures that move */
        private val ANIMATED = WHEEL_IDS + setOf(ControllerPictures.PET_B, ControllerPictures.ATTACK_CAST, GameUi.SHOT_ID)

        val NAMES: Map<String, String> = mapOf(
            ControllerPictures.ABXY to "ABXY location", ControllerPictures.GLYPHS to "Button glyphs", ControllerPictures.PET_B to PetFollowB.ROW_ID,
            ControllerPictures.SMART_Y to "Smart Y", ControllerPictures.PAUSE to "Pause menu", ControllerPictures.ITEM to "Item wheel",
            ControllerPictures.SPELL to "Spell wheel", ControllerPictures.CLASS_L to "Left class wheel", ControllerPictures.CLASS_R to "Right class wheel",
            GameUi.PMODE_ID to "Party mode", GameUi.SRES_ID to "Smart Res", GameUi.ATK_ID to "Start attack",
            ControllerPictures.ATTACK_CAST to "Attack on cast", GameUi.SHOT_ID to "Auto Shot")

        /** the Controller tab's part for group [g] (Smart Y's own group is gone: its tile is in Buttons), null = none */
        fun forGroup(context: Context, g: SettingsTabs.Group, hub: SettingsView): ControllerTiles? = when (g) {
            SettingsTabs.Group.BUTTONS, SettingsTabs.Group.WHEELS, SettingsTabs.Group.COMBAT -> ControllerTiles(context, g, hub)
            else -> null
        }

        /**
         * THE GRID WITH A WIDE TILE (the design's Slot.wide): [wide] per tile, in order, across [width]; a wide tile spans two columns
         * and the gap, and one that would start in the last column starts the next row; the band [bandH] tall under the row of tile
         * [open] (-1 = none), the rows after it moved down. Pure: GameUiTiles' numbers, the tests measure it.
         */
        fun grid(wide: List<Boolean>, width: Float, open: Int, bandH: Float): GameUiTiles.Grid {
            val cols = GameUiTiles.columns(width)
            val tw = (width - 2f * GameUiTiles.MARGIN - (cols - 1) * GameUiTiles.GAP) / cols
            // each tile's row and column
            val place = ArrayList<Pair<Int, Int>>()
            var row = 0; var col = 0
            for (w in wide) {
                val span = if (w && cols >= 2) 2 else 1
                if (col + span > cols) { row++; col = 0 }
                place += row to col
                col += span
                if (col >= cols) { row++; col = 0 }
            }
            val rows = if (place.isEmpty()) 0 else place.last().first + 1
            val openRow = if (open in place.indices) place[open].first else -1
            val push = if (openRow >= 0) bandH + GameUiTiles.GAP else 0f
            val tiles = place.mapIndexed { i, (r, c) ->
                val y = GameUiTiles.TOP + r * (GameUiTiles.TILE_H + GameUiTiles.GAP) + (if (openRow in 0 until r) push else 0f)
                val x = GameUiTiles.MARGIN + c * (tw + GameUiTiles.GAP)
                val span = if (wide[i] && cols >= 2) 2 else 1
                GameUiTiles.Box(x, y, x + span * tw + (span - 1) * GameUiTiles.GAP, y + GameUiTiles.TILE_H)
            }
            val band = if (openRow >= 0) {
                val y = GameUiTiles.TOP + openRow * (GameUiTiles.TILE_H + GameUiTiles.GAP) + GameUiTiles.TILE_H + GameUiTiles.GAP
                GameUiTiles.Box(GameUiTiles.MARGIN, y, width - GameUiTiles.MARGIN, y + bandH)
            } else null
            val h = if (rows == 0) 0f else GameUiTiles.TOP + rows * GameUiTiles.TILE_H + (rows - 1) * GameUiTiles.GAP + push + GameUiTiles.BOTTOM
            return GameUiTiles.Grid(tiles, band, h)
        }
    }
}
