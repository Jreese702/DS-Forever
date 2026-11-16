package com.jrees.wowcompanion

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.abs

/**
 * SettingsView's drawn depth: GAME + MAP + CONTROLLER at 96 px rows end at 936 px, plus its
 * 24 px bottom pad. Raise it when SettingsView gains a section.
 */
const val SETTINGS_H = 960

/**
 * The Settings room as the hub hosts it: ONE LONG PAGE, [settingsView] (map session) with the
 * GAME UI section as its last section, in a scroll container.
 *
 * SettingsView scrolls by itself when it is shorter than its content, and does not measure
 * itself. Two scrollers inside each other would fight over the same drag, so it is given its
 * whole depth here ([SETTINGS_H], as its MINIMUM height, which is what the default onMeasure
 * answers with): it then has nothing to scroll, and this container scrolls the page. The day
 * SettingsView measures its own content, that answer wins and the constant stops mattering.
 *
 * NOT fillViewport: that pass re-measures the column at the room's exact height, the default
 * onMeasure then answers with ALL of it, and the section below is pushed off the screen.
 */
fun settingsRoom(context: Context, settingsView: View): View = ScrollView(context).apply {
    ThemeLive.bg(this, Tok.INK)
    addView(LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        settingsView.minimumHeight = SETTINGS_H
        addView(settingsView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(GameUiSection(context), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // Home's one setting, the bars at rest (home session; user 15:27: "In Settings")
        addView(HomeSettingsSection(context), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    })
}

/**
 * The Settings room's GAME UI section: which of the game's own elements stay on the top screen.
 *
 * LAYOUT (user ruling 2026-09-27): rows carry an icon, a name and the current mode, and NO
 * buttons. Tap a row to select it, and ONE three-way bar appears for it: On / Off / Dynamic.
 * On and Off speak about the game's element (On = the game keeps drawing it).
 *
 * A self-contained view, so SettingsView.kt (map session) is not edited: the hub stacks this
 * under it. It draws with the same metrics and colours as SettingsView's rows.
 *
 * Touches act on RELEASE, and only when the finger has not moved: the section sits in a scroll
 * container, and a drag must not pick a mode.
 */
class GameUiSection(context: Context, val part: Part = Part.ALL) : View(context) {

    /**
     * THE SIX TABS (the user's approved layout, 2026-11-12): one part per group's rows, drawn under the room's folding heading (no title
     * of its own) - Top screen's [HUD] / [TARGET] / [PARTY] / [NAVIGATION] / [WINDOWS], Controller > Combat helpers' [COMBAT] and
     * [AUTO_SHOT], General > Help's [COMPANION] status. The rows, their ids, taps and channels are the ones they had.
     */
    enum class Part { ALL, HUD, TARGET, PARTY, NAVIGATION, WINDOWS, COMBAT, AUTO_SHOT, COMPANION }
    private val hasUi get() = part != Part.COMPANION
    private val hasStatus get() = part == Part.ALL || part == Part.COMPANION
    /** a group's part: no title, its rows under the room's heading */
    private val grouped get() = part != Part.ALL

    /** the room's group this part draws (Top screen's five), null for the others */
    private val group: SettingsTabs.Group? = when (part) {
        Part.HUD -> SettingsTabs.Group.HUD; Part.TARGET -> SettingsTabs.Group.TARGET; Part.PARTY -> SettingsTabs.Group.PARTY
        Part.NAVIGATION -> SettingsTabs.Group.NAVIGATION; Part.WINDOWS -> SettingsTabs.Group.WINDOWS; else -> null
    }
    /**
     * THE TILES (the user, 2026-11-16: "VISUAL overhaul of UI menus (picture beside each game-UI element option)"; the design's picks,
     * accepted the same day): Top screen's parts draw a grid of picture tiles ([GameUiTiles], [TilePictures]) instead of rows. A tap or A
     * on a tile opens ONE band under its row - the picture large, the element's choices, its own extra rows, one status line - today's
     * "tap a row, then one bar" model; B closes it with the cursor back on the tile. Controller > Combat helpers and General's status keep
     * their rows.
     */
    val tiled: Boolean get() = GameUiTiles.tiled(group)
    /** a tile's band opened (or moved to another tile): the room closes any other part's band and brings the cursor and the band in view */
    var onOpen: ((GameUiSection) -> Unit)? = null

    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val pSection by themed { Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 32f; color = Theme.gold } }   // a heading, in Morpheus
    private val pLabel by themed { Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 31f; color = Theme.text } }
    private val pSub by themed { Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; color = Theme.textDim } }
    private val pMode = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 27f; textAlign = Paint.Align.RIGHT; typeface = Typeface.DEFAULT_BOLD
    }
    private val pSeg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 27f; textAlign = Paint.Align.LEFT; typeface = Typeface.DEFAULT_BOLD
    }
    /** the tiles' words: a tile's name (Friz 25), its value, a band row's label and value */
    private val pName by themed { Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = NAME_SIZE; color = Theme.text } }
    private val pValue by themed { Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; color = Theme.gold } }
    private val pBandLabel by themed { Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 25f; color = Theme.text } }

    private var rows: List<GameUi.Row> = allRows()

    /**
     * THE LOOT WINDOW'S ROW (r-loot-on-top; the user, batch4: "loot window should be moved to the UI settings"): after the game's
     * elements, in their words: On = the game keeps its loot window on the top screen, Off = hidden, the pop-up on this screen (the
     * default). Its value is the Interact add-on's (HomeLootSwitch); null = an older add-on: no row.
     */
    private fun lootRow(): GameUi.Row? = HomeLootSwitch.state?.let { s ->
        GameUi.Row(LOOT_ID, "Loot window", null, if (s.top) UiMode.ON else UiMode.OFF, true, !s.top,
            if (s.top) "The game's own loot window" else "The loot pop-up on this screen")
    }
    /** THE COMPASS BAR'S ROW (live layer part 1): our own bar at the top of the game screen; its value is the add-on's (CompassSwitch) */
    private fun compassRow(): GameUi.Row? = CompassSwitch.state?.let { s ->
        GameUi.Row(COMPASS_ID, "Compass bar", null, if (s.show) UiMode.ON else UiMode.OFF, true, !s.show,
            if (s.show) "Heading, next checkpoint and quest at the top" else "Off")
    }
    /** cp7: the compass follows the camera (an experiment), shown while the compass bar is on and the add-on set has it */
    private fun compassCamRow(): GameUi.Row? = CompassSwitch.state?.takeIf { it.show && it.cam != null && it.camOn >= 0 }?.let { s ->
        GameUi.Row(COMPASS_CAM_ID, "Compass follows the camera", null, if (s.cam == true) UiMode.ON else UiMode.OFF, true, s.cam != true,
            if (s.cam == true) "Experiment: while the quest's marker is on screen" else "Off: it follows your character")
    }
    /** tt1: the game's tooltips, shown once the add-on set has the switch. tt2 (the user, 2026-11-10: "the wording for the tooltips being
     *  on or off is inconsistent with the other settings"): named for the thing as every other row is, On = they show (the default),
     *  Off = hidden (the add-on's "hide") */
    private fun tipsRow(): GameUi.Row? = TipsSwitch.state?.let { s ->
        GameUi.Row(TIPS_ID, "Game tooltips", null, if (s.hide) UiMode.OFF else UiMode.ON, true, s.hide,
            if (s.hide) "Off: the game's tooltips are hidden on the top screen" else "The game's tooltips on the top screen")
    }
    /** at1: "Aggro tint" (default on; ar1's "Aggro rings" switch), shown once the add-on set has it */
    private fun ringsRow(): GameUi.Row? = RingsSwitch.state?.let { s ->
        GameUi.Row(RINGS_ID, "Aggro tint", null, if (s.show) UiMode.ON else UiMode.OFF, true, !s.show,
            if (s.show) "On: hostile nameplates glow yellow, orange, then red as you near their aggro range" else "Off: no tint on the nameplates")
    }
    /** hub31 POLESTAR ON THE TOP SCREEN (it was SettingsView's Show / Hide row; a tile since 2026-11-16): On = Polestar's own windows
     *  shown (the switch's "ps" = hidden); the row while Polestar is installed and the add-on has the switch, as before */
    private fun polestarRow(): GameUi.Row? {
        if (!polestarInUse() || ThirdPartyAddons.status(ThirdPartyAddons.POLESTAR, "core").kind != ThirdPartyAddons.Kind.INSTALLED) return null
        val now = GameUi.swNow(GameUi.SW_PS) ?: return null
        val hide = GameUi.swWant(GameUi.SW_PS)
        return GameUi.Row(GameUiTiles.PS_ID, "Polestar on the top screen", null, if (hide) UiMode.OFF else UiMode.ON, true, now,
            if (now != hide) "Sent" else if (now) "Hidden now" else "On the top screen now")
    }
    /** Polestar is in use (the guide on and its core installed): Navigation keeps a tile for it (the add-on rules it in or out) */
    private fun polestarInUse(): Boolean = Settings.polestarGuide == PolestarGuide.ON && ThirdPartyAddons.coreInstalled(ThirdPartyAddons.POLESTAR)

    /** a tile's or a band row's row now, by its id: an element's even before the game reports it (known = false); null = not reported */
    private fun rowOf(id: String): GameUi.Row? = when (id) {
        "minimap", "hud", "tracker", "chat", "dialogs" -> GameUi.elementRow(id)
        LOOT_ID -> lootRow(); COMPASS_ID -> compassRow(); COMPASS_CAM_ID -> compassCamRow(); TIPS_ID -> tipsRow(); RINGS_ID -> ringsRow()
        GameUiTiles.PS_ID -> polestarRow()
        GameUi.CASTBAR_ID -> GameUi.castBarRow(); GameUi.HUD_ID -> GameUi.hudRow(); GameUi.PET_ID -> GameUi.petRow()
        GameUi.HUD_H_ID -> GameUi.hudHeightRow(); GameUi.HUD_W_ID -> GameUi.hudWidthRow(); GameUi.HUD_S_ID -> GameUi.hudScaleRow()
        GameUi.UFT_ID -> GameUi.ufTargetRow(); GameUi.UFHP_ID -> GameUi.ufHpRow(); GameUi.UFTOT_ID -> GameUi.ufTotRow()
        GameUi.NP_ID -> GameUi.nameplatesRow(); GameUi.NPF_ID -> GameUi.friendlyPlatesRow()
        GameUi.UFP_ID -> GameUi.ufPartyRow(); GameUi.PARTY_ID -> GameUi.partyRow()
        GameUi.WM_ID -> GameUi.worldMarkersRow(); GameUi.PLAYER_ID -> GameUi.playerRow(); GameUi.TARGET_ID -> GameUi.targetRow()
        else -> null
    }
    private fun allRows(): List<GameUi.Row> = when (part) {
        Part.HUD -> listOfNotNull(GameUi.hudRow(), GameUi.petRow(), GameUi.hudHeightRow(), GameUi.hudWidthRow(), GameUi.hudScaleRow())
        Part.TARGET -> listOfNotNull(GameUi.ufTargetRow(), GameUi.ufHpRow(), GameUi.ufTotRow(), ringsRow(), GameUi.nameplatesRow(),
            GameUi.friendlyPlatesRow())
        Part.PARTY -> listOfNotNull(GameUi.ufPartyRow(), GameUi.partyRow())
        // "Avoid dangerous areas" (SafePath's) left with SafePath (the user, 2026-11-12: "remove safe path ... from the options")
        // 2026-11-16 (the tiles): Polestar's own frame is Navigation's third tile (it was SettingsView's row)
        Part.NAVIGATION -> listOfNotNull(compassRow(), compassCamRow(), GameUi.worldMarkersRow(), polestarRow())
        // the game's own windows, in the approved order (the game's elements by their ids; the controller bars last)
        Part.WINDOWS -> (GameUi.rows() + listOfNotNull(lootRow(), tipsRow(), GameUi.castBarRow(), GameUi.playerRow(), GameUi.targetRow()))
            .sortedBy { r -> WINDOWS_ORDER.indexOf(r.id).let { if (it < 0) WINDOWS_ORDER.size else it } }
        // Party mode out of Experimental, on by default (the user, 2026-11-12)
        // hub47 Smart Res under Party mode (the user, 2026-11-13: "an enable smart res toggle setting for party mode")
        Part.COMBAT -> listOfNotNull(GameUi.partyModeRow(), GameUi.smartResRow(), GameUi.attackRow())
        Part.AUTO_SHOT -> listOfNotNull(GameUi.autoShotRow())
        Part.COMPANION -> emptyList()
        else -> GameUi.rows() + listOfNotNull(GameUi.castBarRow(), GameUi.hudRow(), GameUi.petRow(), GameUi.hudHeightRow(), GameUi.hudWidthRow(), GameUi.hudScaleRow(), lootRow(), compassRow(), compassCamRow(), ringsRow(), tipsRow())
    }
    /** the row drawn at ([x], [y]), by its words (the Settings room's "What it does" panel follows it), or null */
    fun rowAt(x: Float, y: Float): String? {
        if (tiled) {
            val l = tileLayout(width.toFloat())
            val i = l.grid.tiles.indexOfFirst { it.contains(x, y) }
            return if (i < 0) null else rowOf(l.slots[i])?.label
        }
        return rowBoxes.firstOrNull { it.first.contains(x, y) }?.second?.let { id -> rows.firstOrNull { it.id == id }?.label }
    }
    /** the rows this part draws now, by their words (the room's tests) */
    fun rowLabels(): List<String> = rows.filter { !it.id.startsWith("#") }.map { it.label }
    /** a heading row ("#" + its words) over [rows], none when the group is empty */
    private fun group(heading: String, rows: List<GameUi.Row>): List<GameUi.Row> =
        if (rows.isEmpty()) emptyList() else listOf(GameUi.Row("#$heading", heading, null, UiMode.ON, true, false, "")) + rows
    /** SafePath's "Avoid dangerous areas" (it was under Map, then QOL > Navigation: the same Settings.avoidDanger), Off / Low / High.
     *  NOT SHOWN since 2026-11-12 (SafePath is no longer a Quest tracking option); kept with SafePath's code */
    private fun dangerRow(): GameUi.Row {
        val safe = Settings.ROUTE_MODES_ON && Settings.questTracking == "safepath"
        val note = when {
            !Settings.ROUTE_MODES_ON -> "Comes with SafePath in a later update"
            Settings.questTracking != "safepath" -> "SafePath only: choose SafePath in Quest tracking"
            Settings.avoidDanger == "off" -> "The line goes straight, through hostile mobs"
            Settings.avoidDanger == "low" -> "The line bends round dense or high-level hostile mobs"
            else -> "The line bends round hostile mobs at or above your level"
        }
        return GameUi.Row(DANGER_ID, "Avoid dangerous areas", null, UiMode.ON, safe, false, note)
    }
    /** the status row as last drawn (a stop for the controller's cursor: A there does nothing) */
    private val statusBox = RectF()
    private var selected: String? = null
    private val rowBoxes = ArrayList<Pair<RectF, String>>()
    private val segBoxes = ArrayList<Pair<RectF, UiMode>>()
    /** hub15 / hub16: the HUD height / width / scale rows' buttons (-1 / default 0 / +1) */
    private val stepBoxes = ArrayList<Pair<RectF, Pair<String, Int>>>()

    private var downX = 0f
    private var downY = 0f

    init {
        GameUi.onChange { post { refresh() } }
        HomeLootSwitch.onChange { post { refresh() } }
        CompassSwitch.onChange { post { refresh() } }
        TipsSwitch.onChange { post { refresh() } }
        RingsSwitch.onChange { post { refresh() } }
        // Navigation's Polestar tile comes and goes with Polestar (the guide's setting, its install)
        if (part == Part.NAVIGATION) { Settings.onChange { post { refresh() } }; ThirdPartyAddons.onChange { post { refresh() } } }
        if (hasStatus) StatusLine.onChange = { post { invalidate() } }
    }

    /** the tiles' shape as last measured (the slots and the open band's rows): a change asks for a new layout */
    private var tileShape: List<String> = emptyList()

    private fun refresh() {
        val before = rows.size
        rows = allRows()
        if (tiled) {
            // the tiles keep their places (fixed slots); only Polestar's slot or the open band's rows change the height
            if (selected != null && selected !in slots()) selected = null
            if (shape() != tileShape) requestLayout()
            invalidate()
            return
        }
        if (selected != null && rows.none { it.id == selected }) selected = null
        if (rows.size != before) { requestLayout(); if ((before == 0) != rows.isEmpty()) onContent?.invoke() }
        invalidate()
    }

    private fun wantedHeight(width: Float = this.width.toFloat()): Int {
        if (tiled) return tileLayout(width).grid.height.toInt()
        val heads = rows.count { it.id.startsWith("#") }
        if (grouped && hasUi && rows.isEmpty()) return 0                  // nothing to draw: the group's heading alone
        var h = (if (hasUi) (if (grouped) QOL_TOP else HEAD_H) + (rows.size - heads) * (ROW_H + GAP) + heads * SUBHEAD_H else 0f) +
            BOTTOM + (if (hasStatus) (if (grouped) STATUS_ROW_H else STATUS_H) else 0f)
        if (hasUi && selected != null) h += BAR_H + GAP
        return h.toInt()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = getDefaultSize(suggestedMinimumWidth, widthSpec)
        val h = wantedHeight(w.toFloat())
        // a part shrunk to nothing is never drawn again (a 0 px child is skipped): its last boxes must not stay pressable
        if (h == 0) { rowBoxes.clear(); segBoxes.clear(); stepBoxes.clear(); statusBox.setEmpty() }
        if (tiled) tileShape = shape()
        setMeasuredDimension(w, resolveSize(h, heightSpec))
    }

    /** this part has rows to draw now (the room hides a group whose parts have none: the switches come with the add-on's word). The
     *  tiles: always (a fixed slot list; a tile the game has not reported yet is a dim "..." one) */
    fun hasContent(): Boolean = !hasUi || part == Part.ALL || (if (tiled) slots().isNotEmpty() else allRows().isNotEmpty())
    /** the rows came or went (the room shows / hides the group's heading) */
    var onContent: (() -> Unit)? = null

    /** the controller's targets (Home's Settings cursor, LATER item 5): every row, then the open bar's three modes, in reading order.
     *  The tiles: every tile, then the open band's buttons (its picture and status line are not stops) */
    fun focusTargets(): List<RectF> {
        val out = ArrayList<RectF>()
        if (tiled) {
            val l = tileLayout(width.toFloat())
            l.grid.tiles.forEach { out.add(it.rect()) }
            l.band?.lines?.forEach { line -> line.buttons.forEach { out.add(it.rect()) } }
            return out
        }
        for ((box, id) in rowBoxes) {
            out.add(RectF(box))
            if (id == selected) { segBoxes.forEach { out.add(RectF(it.first)) }; stepBoxes.forEach { out.add(RectF(it.first)) } }
        }
        if (part == Part.COMPANION && !statusBox.isEmpty) out.add(RectF(statusBox))
        return out
    }

    /** B in the Settings room closes an open On / Off / Dynamic bar first; false = nothing was open */
    fun closeSelection(): Boolean {
        if (selected == null) return false
        selected = null; requestLayout(); invalidate()
        return true
    }

    /** a bar is open (the next B closes it rather than leaving the room) */
    val hasSelection get() = selected != null

    /** the open bar's own row, or the open band's tile, in this part's coordinates (B closes it and the cursor goes back there); null =
     *  none open. The tiles' is laid out afresh, so it is right at once after a tap moved the band (no draw needed) */
    fun selectionAnchor(): RectF? {
        val id = selected ?: return null
        if (tiled) { val l = tileLayout(width.toFloat()); return l.slots.indexOf(id).takeIf { it >= 0 }?.let { l.grid.tiles[it].rect() } }
        return rowBoxes.firstOrNull { it.second == id }?.first?.let { RectF(it) }
    }

    /** the open band's box in this part's coordinates (the room scrolls it into view), null = none */
    fun bandBox(): RectF? = if (!tiled || selected == null) null else tileLayout(width.toFloat()).grid.band?.rect()

    /** the tile ids drawn now, in order (the room's tests) */
    fun tileIds(): List<String> = if (tiled) slots() else emptyList()
    /** the tile open now (its band shown), null = none */
    val openTile: String? get() = if (tiled) selected else null

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (tiled) return tileTouch(e)
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
            MotionEvent.ACTION_UP -> {
                if (abs(e.x - downX) > SLOP || abs(e.y - downY) > SLOP) return true
                for ((box, sd) in stepBoxes) if (box.contains(e.x, e.y)) {
                    when (sd.first) {
                        // the same Settings.avoidDanger the old Map row set (SafePath only)
                        DANGER_ID -> if (Settings.ROUTE_MODES_ON && Settings.questTracking == "safepath")
                            Settings.avoidDanger = Settings.AVOID_DANGER[sd.second]
                        GameUi.UFHP_ID -> GameUi.setHpText(sd.second)
                        GameUi.SHOT_ID -> GameUi.setShotMode(sd.second)
                        GameUi.HUD_W_ID -> GameUi.stepHudWidth(sd.second)
                        GameUi.HUD_S_ID -> GameUi.stepHudScale(sd.second)
                        else -> GameUi.stepHudHeight(sd.second)
                    }
                    refresh(); return true
                }
                for ((box, m) in segBoxes) if (box.contains(e.x, e.y)) {
                    selected?.let { applyMode(it, m) }
                    refresh()
                    return true
                }
                for ((box, id) in rowBoxes) if (box.contains(e.x, e.y)) {
                    selected = if (selected == id) null else id
                    requestLayout(); invalidate()
                    return true
                }
            }
        }
        return true
    }

    /** the player picked mode [m] for [id]: one write, the same channel each row always had (a player-pressed choice) */
    private fun applyMode(id: String, m: UiMode) {
        when (id) {
            LOOT_ID -> HomeLootSwitch.turn(context, m == UiMode.ON)
            COMPASS_ID -> CompassSwitch.turn(context, m == UiMode.ON)
            COMPASS_CAM_ID -> CompassSwitch.turnCam(context, m == UiMode.ON)
            TIPS_ID -> TipsSwitch.turn(context, m != UiMode.ON)          // tt2: On = they show (turn() takes "hide")
            RINGS_ID -> RingsSwitch.turn(context, m == UiMode.ON)
            GameUi.CASTBAR_ID -> GameUi.setCastBar(m == UiMode.ON)
            GameUi.HUD_ID -> GameUi.setHud(m == UiMode.ON)
            GameUi.PET_ID -> GameUi.setSwitch(GameUi.SW_PET, m == UiMode.ON)
            GameUi.PLAYER_ID -> GameUi.setSwitch(GameUi.SW_PLAYER, m == UiMode.ON)
            GameUi.TARGET_ID -> GameUi.setSwitch(GameUi.SW_TARGET, m == UiMode.ON)
            GameUi.PARTY_ID -> GameUi.setSwitch(GameUi.SW_PARTY, m == UiMode.ON)
            GameUi.UFP_ID -> GameUi.setSwitch(GameUi.SW_UFP, m == UiMode.ON)
            GameUi.UFT_ID -> GameUi.setSwitch(GameUi.SW_UFT, m == UiMode.ON)
            GameUi.UFTOT_ID -> GameUi.setSwitch(GameUi.SW_UFTOT, m == UiMode.ON)
            GameUi.NP_ID -> GameUi.setSwitch(GameUi.SW_NP, m == UiMode.ON)
            GameUi.WM_ID -> GameUi.setSwitch(GameUi.SW_WM, m == UiMode.ON)
            GameUi.ATK_ID -> GameUi.setSwitch(GameUi.SW_ATK, m == UiMode.ON)
            GameUi.SHOT_ID -> GameUi.setSwitch(GameUi.SW_SHOT, m == UiMode.ON)
            GameUi.PMODE_ID -> GameUi.setSwitch(GameUi.SW_PMODE, m == UiMode.ON)
            GameUi.SRES_ID -> GameUi.setSwitch(GameUi.SW_SRES, m == UiMode.ON)
            GameUi.NPF_ID -> GameUi.setSwitch(GameUi.SW_NPF, m == UiMode.ON)
            // hub31: the switch says "hidden": On = Polestar's windows shown
            GameUiTiles.PS_ID -> GameUi.setSwitch(GameUi.SW_PS, m != UiMode.ON)
            else -> GameUi.setMode(id, m)
        }
    }

    // ------------------------------------------------------------------ the tiles (2026-11-16)

    /** this part's tiles now (the fixed slots; Navigation's Polestar only while Polestar is in use) */
    private fun slots(): List<String> = group?.let { GameUiTiles.slots(it, polestarInUse()) } ?: emptyList()

    /** what decides the part's height: the slots, the open tile and its band's rows */
    private fun shape(): List<String> = slots() + listOf("|", selected ?: "") + (selected?.let { bandRows(it).map { r -> r.key } } ?: emptyList())

    /** the choices a tile's own line offers: the game's elements their modes (the minimap's While tracking, the bars' Nintendo), the
     *  rest On / Off */
    private fun modesOf(id: String): List<UiMode> = if (UiElements.of(id) != null) UiElements.modesFor(id) else listOf(UiMode.ON, UiMode.OFF)

    /** one line of a band: the tile's own choices ([modes]), or a band row's ([words]), the chosen one ([chosen], -1 = none lit) */
    private class BandRow(val key: String, val label: String?, val value: String?, val words: List<String>, val modes: List<UiMode>?,
                          val chosen: Int, val enabled: Boolean)

    /** the open tile [id]'s band: its own choices, then its rows that are there now (the pet strip and the steppers only while the
     *  resource bar is on; the health text and the target of target with an add-on that has them...) */
    private fun bandRows(id: String): List<BandRow> {
        val r = rowOf(id)
        val modes = modesOf(id)
        val out = arrayListOf(BandRow(id, null, null, modes.map { word(it) }, modes, if (r == null) -1 else modes.indexOf(r.mode), r != null))
        for (e in GameUiTiles.EXTRAS[id] ?: emptyList()) {
            val er = rowOf(e) ?: continue
            val name = GameUiTiles.EXTRA_NAMES[e] ?: er.label
            out += when (e) {
                GameUi.HUD_H_ID -> BandRow(e, name, GameUi.hudValue(e), listOf("Lower", "Default", "Higher"), null, -1, true)
                GameUi.HUD_W_ID -> BandRow(e, name, GameUi.hudValue(e), listOf("Narrower", "Default", "Wider"), null, -1, true)
                GameUi.HUD_S_ID -> BandRow(e, name, GameUi.hudValue(e), listOf("Smaller", "Default", "Larger"), null, -1, true)
                GameUi.UFHP_ID -> BandRow(e, name, null, GameUi.HP_WORDS, null, GameUi.hpText(), true)
                else -> BandRow(e, name, null, listOf("On", "Off"), null, if (er.mode == UiMode.ON) 0 else 1, true)
            }
        }
        return out
    }

    /** the tiles' layout at [w]: the slots, the grid, the open band (its rows and boxes) */
    private class TileLayout(val slots: List<String>, val grid: GameUiTiles.Grid, val band: GameUiTiles.Band?, val rows: List<BandRow>)

    private fun tileLayout(w: Float): TileLayout {
        val ids = slots()
        val open = selected?.let { ids.indexOf(it) } ?: -1
        val rows = if (open >= 0) bandRows(ids[open]) else emptyList()
        val lines = rows.map { GameUiTiles.Line(it.key, it.words.size, it.label != null) }
        val bw = w - 2f * GameUiTiles.MARGIN
        val bandH = if (open >= 0) GameUiTiles.band(GameUiTiles.MARGIN, 0f, bw, lines).height else 0f
        val grid = GameUiTiles.grid(ids.size, w, open, bandH)
        val band = grid.band?.let { GameUiTiles.band(it.l, it.t, it.w, lines) }
        return TileLayout(ids, grid, band, rows)
    }

    private fun GameUiTiles.Box.rect() = RectF(l, t, r, b)

    /** a tap: a band's button applies its choice (one player-pressed write); a tile opens its band, moves it, or closes it */
    private fun tileTouch(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
            MotionEvent.ACTION_UP -> {
                if (abs(e.x - downX) > SLOP || abs(e.y - downY) > SLOP) return true
                val l = tileLayout(width.toFloat())
                l.band?.lines?.forEachIndexed { i, line ->
                    val k = line.buttons.indexOfFirst { it.contains(e.x, e.y) }
                    if (k >= 0) { bandPress(l.rows[i], k); refresh(); return true }
                }
                val i = l.grid.tiles.indexOfFirst { it.contains(e.x, e.y) }
                if (i >= 0) {
                    val id = l.slots[i]
                    selected = if (selected == id) null else id
                    requestLayout(); invalidate()
                    if (selected != null) onOpen?.invoke(this)
                }
            }
        }
        return true
    }

    /** button [k] of band row [r] */
    private fun bandPress(r: BandRow, k: Int) {
        if (!r.enabled) return
        r.modes?.let { m -> applyMode(r.key, m[k]); return }
        when (r.key) {
            GameUi.HUD_H_ID -> GameUi.stepHudHeight(k - 1)
            GameUi.HUD_W_ID -> GameUi.stepHudWidth(k - 1)
            GameUi.HUD_S_ID -> GameUi.stepHudScale(k - 1)
            GameUi.UFHP_ID -> GameUi.setHpText(k)
            else -> applyMode(r.key, if (k == 0) UiMode.ON else UiMode.OFF)
        }
    }

    /** the game's type (UI art, fonts step). Set where the paints are USED: the kit can reach the
     *  device after this view is built, and a paint keeps the font it was given. */
    private fun fonts() {
        UiKit.asTitle(pSection); UiKit.asBody(pLabel); UiKit.asBody(pSub)
        UiKit.asBody(pMode); UiKit.asBody(pSeg)
        UiKit.asBody(pName); UiKit.asBody(pValue); UiKit.asBody(pBandLabel)
    }

    // ------------------------------------------------------------------ the tiles' drawing

    private fun drawTiles(c: Canvas) {
        val l = tileLayout(width.toFloat())
        for ((i, id) in l.slots.withIndex()) drawTile(c, l.grid.tiles[i], id, rowOf(id), id == selected)
        val band = l.band ?: return
        val open = l.slots.indexOf(selected)
        if (open >= 0) drawBand(c, l.grid.band!!, band, l.grid.tiles[open], selected!!, l.rows)
    }

    /** what [id]'s picture shows besides the element: the choices that change its look (now, as chosen) */
    private fun look(id: String, r: GameUi.Row?) = TilePictures.Look(mode = r?.mode ?: UiMode.ON, pet = GameUi.swWant(GameUi.SW_PET),
        hpText = GameUi.hpText(), tot = GameUi.swWant(GameUi.SW_UFTOT), aggro = RingsSwitch.state?.show ?: true, cls = GameUi.playerClass)

    /** the picture (our glyph where it has none), dimmed when the choice is Off or the game has not reported the element */
    private fun picture(c: Canvas, id: String, r: GameUi.Row?, well: RectF) {
        pFill.color = Color.rgb(14, 16, 18)
        c.drawRoundRect(well, 8f, 8f, pFill)
        if (!TilePictures.draw(c, context, id, well, look(id, r))) {
            pStroke.color = Theme.gold; pStroke.strokeWidth = 2.4f
            elementIcon(c, id, well.centerX(), well.centerY(), well.height() / 40f)
        }
        if (GameUiTiles.dimmed(r)) {
            pFill.color = Theme.ink; pFill.alpha = 165
            c.drawRoundRect(well, 8f, 8f, pFill)
            pFill.alpha = 255
        }
        pStroke.color = Color.argb(200, 0, 0, 0); pStroke.strokeWidth = 2f
        c.drawRoundRect(well, 8f, 8f, pStroke)
    }

    /** one tile: its ground and edge (lit while its band is open: the cursor's gold box is the cursor, never this), the picture, the
     *  badge on the picture's corner, the name and the value word */
    private fun drawTile(c: Canvas, box: GameUiTiles.Box, id: String, r: GameUi.Row?, open: Boolean) {
        val rect = box.rect()
        pFill.color = if (open) Theme.selected else Theme.raised
        c.drawRoundRect(rect, 10f, 10f, pFill)
        picture(c, id, r, GameUiTiles.well(box).rect())
        TilePictures.badge(c, GameUiTiles.badgeBox(box).rect(), GameUiTiles.badge(r))
        edge(c, rect, open)
        val waiting = r == null || !r.known
        val x = box.l + GameUiTiles.WELL_PAD + 2f
        val maxW = box.w - 2f * GameUiTiles.WELL_PAD - 4f
        pName.color = if (waiting) Theme.textDim else Theme.text
        val name = fitText(pName, Tr.t(GameUiTiles.NAMES[id] ?: r?.label ?: id), maxW, NAME_SIZE, NAME_MIN)
        c.drawText(name, x, box.t + GameUiTiles.WELL_PAD + GameUiTiles.WELL_H + 34f, pName)
        if (r != null) {
            pValue.color = if (waiting || r.mode == UiMode.OFF) Theme.textDim else Theme.gold
            c.drawText(fitText(pValue, Tr.t(word(r.mode)), maxW, 22f, 18f), x, box.b - 12f, pValue)
        }
    }

    /** a tile's or the band's edge: the game's metal ring (the kit's ring_square), lit gold for the open tile; without it, the drawn ring */
    private fun edge(c: Canvas, r: RectF, lit: Boolean) {
        if (UiKit.ringBox(c, r, 0.8f, lit = lit)) return
        val w = UiKit.RING_W * 0.8f
        tilePath.reset()
        tilePath.addRoundRect(RectF(r.left + w / 2f, r.top + w / 2f, r.right - w / 2f, r.bottom - w / 2f), 9f, 9f, Path.Direction.CW)
        UiKit.ringEdge(c, tilePath, w, lit = lit)
    }
    private val tilePath = Path()

    /** [s] in [p] fitted to [maxW]: shrunk from [size] down to [min], then cut with an ellipsis (es / fr names run long) */
    private fun fitText(p: Paint, s: String, maxW: Float, size: Float, min: Float): String {
        p.textSize = size
        while (p.measureText(s) > maxW && p.textSize > min) p.textSize -= 1f
        if (p.measureText(s) <= maxW) return s
        val n = p.breakText(s, true, (maxW - p.measureText("…")).coerceAtLeast(0f), null)
        return s.substring(0, n).trimEnd() + "…"
    }

    /** the open tile's band: under its row, full width, a notch pointing up at the tile; the picture large on the left, the tile's own
     *  choices and its rows, one status line */
    private fun drawBand(c: Canvas, box: GameUiTiles.Box, band: GameUiTiles.Band, tile: GameUiTiles.Box, id: String, rows: List<BandRow>) {
        val rect = box.rect()
        pFill.color = Theme.panel
        c.drawRoundRect(rect, 12f, 12f, pFill)
        edge(c, rect, false)
        // the notch under the open tile, so the band reads as that tile's
        val nx = tile.cx
        pFill.color = Theme.panel
        tilePath.reset(); tilePath.moveTo(nx - 18f, rect.top + 3f); tilePath.lineTo(nx, rect.top - 13f); tilePath.lineTo(nx + 18f, rect.top + 3f); tilePath.close()
        c.drawPath(tilePath, pFill)
        pStroke.color = Theme.gold; pStroke.strokeWidth = 2.6f
        c.drawLine(nx - 18f, rect.top + 1f, nx, rect.top - 13f, pStroke); c.drawLine(nx, rect.top - 13f, nx + 18f, rect.top + 1f, pStroke)
        val r = rowOf(id)
        picture(c, id, r, band.picture.rect())
        for ((i, line) in band.lines.withIndex()) {
            val br = rows.getOrNull(i) ?: continue
            line.label?.let { lb ->
                pBandLabel.color = Theme.text
                val name = fitText(pBandLabel, Tr.t(br.label ?: ""), lb.w - 6f, 25f, 18f)
                if (br.value != null) {
                    c.drawText(name, lb.l + 4f, lb.cy - 6f, pBandLabel)
                    pValue.color = Theme.gold
                    c.drawText(fitText(pValue, br.value, lb.w - 6f, 22f, 18f), lb.l + 4f, lb.cy + 26f, pValue)
                } else c.drawText(name, lb.l + 4f, lb.cy + 9f, pBandLabel)
            }
            for ((k, b) in line.buttons.withIndex()) {
                val m = br.modes?.getOrNull(k)
                if (m != null) modeChoice(c, b.rect(), m, k == br.chosen, br.enabled)
                else UiKit.choice(c, b.rect(), Tr.t(br.words.getOrElse(k) { "" }), k == br.chosen, br.enabled, textSize = 27f)
            }
        }
        // the one status line: the row's own words (what the game shows now, or that it was sent / is waiting)
        val st = band.status
        pSub.color = Theme.textDim
        val say = Tr.t(r?.note ?: "Waiting for the game")
        c.drawText(fitText(pSub, say, st.w - 8f, 22f, 18f), st.l + 4f, st.cy + 8f, pSub)
        pSub.textSize = 22f
    }

    /** one of the tile's own choices: the game's red button, lit when chosen, the mode's sign and word over it (both fitted) */
    private fun modeChoice(c: Canvas, b: RectF, m: UiMode, on: Boolean, enabled: Boolean) {
        UiKit.choice(c, b, "", on, enabled)
        val col = if (!enabled) Theme.dim else if (on) Theme.gold else Theme.textDim
        val w = Tr.t(word(m))
        pSeg.textSize = 27f
        // the button art's thick ends: the sign and the word keep clear of them; the sign goes first when the word would get too small
        val room = b.width() - 44f
        while (pSeg.measureText(w) + 58f > room && pSeg.textSize > 22f) pSeg.textSize -= 1f
        val sign = pSeg.measureText(w) + 58f <= room
        if (!sign) pSeg.textSize = 27f
        pSeg.color = col
        if (sign) {
            val total = 44f + 14f + pSeg.measureText(w)
            val ix = b.centerX() - total / 2f + 22f
            pStroke.color = col; pStroke.strokeWidth = 2.4f
            modeIcon(c, m, ix, b.centerY())
            c.drawText(w, ix + 22f + 14f, b.centerY() + 10f, pSeg)
        } else {
            pSeg.textAlign = Paint.Align.CENTER
            c.drawText(fitText(pSeg, w, room, pSeg.textSize, 16f), b.centerX(), b.centerY() + 10f, pSeg)
            pSeg.textAlign = Paint.Align.LEFT
        }
        pSeg.textSize = 27f
    }

    override fun onDraw(c: Canvas) {
        fonts()
        c.drawColor(Theme.ink)
        rowBoxes.clear(); segBoxes.clear(); stepBoxes.clear()
        if (tiled) { drawTiles(c); return }
        var y = 0f
        if (hasUi) {
        if (grouped) y = QOL_TOP
        else { c.drawText(Tr.t("Game UI on the top screen"), 28f, HEAD_TEXT + 4f, pSection); y = HEAD_H }
        for (r in rows) {
            if (r.id.startsWith("#")) {
                // a QOL group's heading (the page's block headings' look)
                c.drawText(Tr.t(r.label), 28f, y + SUBHEAD_TEXT, pSection)
                y += SUBHEAD_H
                continue
            }
            val box = RectF(16f, y, width - 16f, y + ROW_H)
            val sel = r.id == selected
            pFill.color = if (sel) Theme.selected else Theme.raised
            c.drawRoundRect(box, 8f, 8f, pFill)
            if (sel) {
                pStroke.color = Theme.bronze; pStroke.strokeWidth = 2f
                c.drawRoundRect(box, 8f, 8f, pStroke)
            }
            pStroke.color = if (r.known) Theme.gold else Theme.dim; pStroke.strokeWidth = 2.4f
            elementIcon(c, r.id, box.left + 58f, box.centerY(), 1.7f)
            pLabel.color = if (r.known) Theme.text else Theme.dim
            c.drawText(Tr.t(r.label), box.left + 116f, box.top + 41f, pLabel)
            pSub.color = if (r.known) Theme.textDim else Theme.dim
            c.drawText(Tr.t(r.note), box.left + 116f, box.top + 75f, pSub)
            pMode.color = if (r.known) Theme.gold else Theme.dim
            c.drawText(Tr.t(if (r.id == GameUi.HUD_H_ID || r.id == GameUi.HUD_W_ID || r.id == GameUi.HUD_S_ID) GameUi.hudValue(r.id)
                else if (r.id == DANGER_ID) Settings.avoidDanger.replaceFirstChar { it.uppercase() }
                else if (r.id == GameUi.UFHP_ID) GameUi.HP_WORDS[GameUi.hpText()]
                else if (r.id == GameUi.SHOT_ID && GameUi.shotThree()) GameUi.SHOT_WORDS[GameUi.shotMode()] else word(r.mode)),
                box.right - 24f, box.centerY() + 10f, pMode)
            rowBoxes.add(box to r.id)
            y += ROW_H + GAP

            if (sel) { bar(c, r, y); y += BAR_H + GAP }
        }
        }
        if (!hasStatus) return

        // ---- what the status line under the strip used to say (user, 2026-09-28: it lives here now)
        val box = if (grouped) RectF(16f, y + STATUS_TOP, width - 16f, y + STATUS_TOP + ROW_H) else {
            y += COMPANION_TOP                 // the page's 90 px seam above a heading (Map's measure 2026-10-06: it was 52)
            c.drawText(Tr.t("Companion"), 28f, y + HEAD_TEXT + 12f, pSection)
            RectF(16f, y + HEAD_H + 8f, width - 16f, y + HEAD_H + 8f + ROW_H)
        }
        statusBox.set(box)
        pFill.color = Theme.raised
        c.drawRoundRect(box, 8f, 8f, pFill)
        pLabel.color = Theme.text
        c.drawText(Tr.t("Status"), box.left + 22f, box.top + 41f, pLabel)
        pSub.color = Theme.textDim
        val say = StatusLine.text.ifEmpty { "Nothing to report" }
        val cut = pSub.breakText(say, true, box.width() - 44f, null)
        c.drawText(Tr.t(if (cut < say.length) say.substring(0, cut) else say), box.left + 22f, box.top + 75f, pSub)
    }

    /** the ONE contextual control: the selected row's modes (three; the minimap's four with "While tracking", LATER 25) */
    private fun bar(c: Canvas, r: GameUi.Row, top: Float) {
        val left = 16f; val right = width - 16f
        val gap = 10f
        if (r.id == DANGER_ID) {
            // Off / Low / High, the chosen one lit (greyed outside SafePath)
            val sw = (right - left - 2f * gap) / 3f
            val sel = Settings.AVOID_DANGER.indexOf(Settings.avoidDanger)
            for ((k, w) in listOf("Off", "Low", "High").withIndex()) {
                val b = RectF(left + k * (sw + gap), top, left + k * (sw + gap) + sw, top + SEG_H)
                UiKit.choice(c, b, w, k == sel, enabled = r.known)
                stepBoxes.add(b to (DANGER_ID to k))
            }
            pSub.color = Theme.textDim
            c.drawText(Tr.t("How hard SafePath's line bends round hostile mobs."), left + 6f, top + SEG_H + 34f, pSub)
            return
        }
        if (r.id == GameUi.UFHP_ID) {
            // uf11: Off / Percent / Total, the chosen one lit
            val sw = (right - left - 2f * gap) / 3f
            val sel = GameUi.hpText()
            for ((k, w) in GameUi.HP_WORDS.withIndex()) {
                val b = RectF(left + k * (sw + gap), top, left + k * (sw + gap) + sw, top + SEG_H)
                UiKit.choice(c, b, w, k == sel)
                stepBoxes.add(b to (GameUi.UFHP_ID to k))
            }
            pSub.color = Theme.textDim
            c.drawText(Tr.t("What the target bar writes on its health. For this character."), left + 6f, top + SEG_H + 34f, pSub)
            return
        }
        if (r.id == GameUi.SHOT_ID && GameUi.shotThree()) {
            // hub28: Off / On / In combat, the chosen one lit
            val sw = (right - left - 2f * gap) / 3f
            val sel = GameUi.shotMode()
            for ((k, w) in GameUi.SHOT_WORDS.withIndex()) {
                val b = RectF(left + k * (sw + gap), top, left + k * (sw + gap) + sw, top + SEG_H)
                UiKit.choice(c, b, w, k == sel)
                stepBoxes.add(b to (GameUi.SHOT_ID to k))
            }
            pSub.color = Theme.textDim
            c.drawText(Tr.t(listOf("The game's switching between Auto Shot and auto attack, off.",
                "Auto Shot at range, auto attack up close.", "On; a plain attack you start turns it off, an ability back on.")[sel]), left + 6f, top + SEG_H + 34f, pSub)
            return
        }
        if (r.id == GameUi.HUD_H_ID || r.id == GameUi.HUD_W_ID || r.id == GameUi.HUD_S_ID) {
            // hub15 / hub16: one step each way and the default (the game's red buttons); the value shown on the row, the bar redrawn live
            val (words, hint) = when (r.id) {
                GameUi.HUD_W_ID -> listOf("Narrower", "Default", "Wider") to "How wide HUD mode's own bar is, for this character."
                GameUi.HUD_S_ID -> listOf("Smaller", "Default", "Larger") to "How large HUD mode's own bar is drawn, for this character."
                else -> listOf("Lower", "Default", "Higher") to "Where HUD mode's own bar sits under your character, for this character."
            }
            val sw = (right - left - 2f * gap) / 3f
            for ((k, d) in listOf(-1, 0, 1).withIndex()) {
                val b = RectF(left + k * (sw + gap), top, left + k * (sw + gap) + sw, top + SEG_H)
                UiKit.choice(c, b, words[k], false)
                stepBoxes.add(b to (r.id to d))
            }
            pSub.color = Theme.textDim
            c.drawText(Tr.t(hint), left + 6f, top + SEG_H + 34f, pSub)
            return
        }
        val modes = if (r.id == LOOT_ID || r.id == COMPASS_ID || r.id == COMPASS_CAM_ID || r.id == TIPS_ID || r.id == RINGS_ID || r.id == GameUi.CASTBAR_ID || r.id == GameUi.HUD_ID ||
            r.id == GameUi.PLAYER_ID || r.id == GameUi.TARGET_ID || r.id == GameUi.PARTY_ID || r.id == GameUi.NP_ID || r.id == GameUi.WM_ID ||
            r.id == GameUi.ATK_ID || r.id == GameUi.SHOT_ID || r.id == GameUi.UFP_ID || r.id == GameUi.UFT_ID || r.id == GameUi.UFTOT_ID || r.id == GameUi.PET_ID || r.id == GameUi.SRES_ID ||
            r.id == GameUi.NPF_ID) listOf(UiMode.ON, UiMode.OFF) else UiElements.modesFor(r.id)
        val sw = (right - left - gap * (modes.size - 1)) / modes.size
        var x = left
        for (m in modes) {
            val b = RectF(x, top, x + sw, top + SEG_H)
            val on = r.mode == m
            // one of three: the game's red button, the chosen one lit; the word and its sign are drawn over it
            UiKit.choice(c, b, "", on)
            val col = if (on) Theme.gold else Theme.textDim
            pSeg.color = col
            val tw = pSeg.measureText(word(m))
            val total = 44f + 14f + tw
            val ix = b.centerX() - total / 2f + 22f
            pStroke.color = col; pStroke.strokeWidth = 2.4f
            modeIcon(c, m, ix, b.centerY())
            c.drawText(Tr.t(word(m)), ix + 22f + 14f, b.centerY() + 10f, pSeg)
            segBoxes.add(b to m)
            x += sw + gap
        }
        pSub.color = Theme.textDim
        c.drawText(Tr.t(explain(r)), left + 6f, top + SEG_H + 34f, pSub)
    }

    private fun word(m: UiMode) = when (m) {
        UiMode.ON -> "On"; UiMode.OFF -> "Off"; UiMode.DYNAMIC -> "Dynamic"; UiMode.TRACKING -> "Tracking"
        UiMode.NINTENDO -> "Nintendo"                                     // issue #195
    }

    private fun explain(r: GameUi.Row): String {
        if (r.id == LOOT_ID) return if (r.mode == UiMode.ON) "The game's own loot window opens on the top screen, as usual."
            else "Hidden: the loot pop-up opens on this screen."
        if (r.id == COMPASS_CAM_ID) return if (r.mode == UiMode.ON)
            "An experiment: while the focused quest's or the route's marker is on the screen, the compass turns with the camera; " +
            "otherwise it keeps the last angle and settles back to your character as you move."
            else "The compass turns with your character."
        if (r.id == COMPASS_ID) return if (r.mode == UiMode.ON)
            "A compass along the top of the game screen: your heading, the next checkpoint, the focused quest and your corpse, with distances."
            else "No compass bar on the top screen."
        if (r.id == GameUi.HUD_ID) return if (r.mode == UiMode.ON)
            "The resource bar under your character: health, power, buffs and debuffs. For this character."
            else "No resource bar under your character."
        if (r.id == GameUi.PET_ID) return if (r.mode == UiMode.ON) "Your pet's health and power under the resource bar. For this character."
            else "No pet bar. For this character."
        if (r.id == GameUi.WM_ID) return if (r.mode == UiMode.ON) "Markers stand in the 3D world on the next checkpoint and the quest. For this character."
            else "No markers in the 3D world; the compass bar still shows them. For this character."
        if (r.id == GameUi.PLAYER_ID) return if (r.mode == UiMode.ON) "The game's player frame stays on the top screen. For this character."
            else "The game's player frame (and its pet frame) is hidden. For this character."
        if (r.id == GameUi.SRES_ID) return if (r.mode == UiMode.ON) "A tap on a dead party member casts your res. Rebirth and Revive Pet in combat too. For this character."
            else "A tap on a dead member works as on any member. For this character."
        if (r.id == GameUi.ATK_ID) return if (r.mode == UiMode.ON) "Targeting an enemy (RB) starts your auto attack. For this character."
            else "Targeting an enemy only targets it. For this character."
        if (r.id == GameUi.SHOT_ID && GameUi.shotMode() == 2) return "The game's own option, Smart: on by default; a plain attack you start out of a fight turns it off, your next ability (not Hunter's Mark) turns it back on. For this character."
        if (r.id == GameUi.SHOT_ID) return if (r.mode == UiMode.ON) "The game's own option: Auto Shot (bows, guns, wands) at range, auto attack up close."
            else "The game's own option, off: no switching between Auto Shot and auto attack."
        if (r.id == GameUi.UFP_ID) return if (r.mode == UiMode.ON) "DS Forever's party frames: real health, Party mode's pick and LB buttons. The game's are hidden. For this character."
            else "The game's own party frames, by their switch below. For this character."
        if (r.id == GameUi.UFT_ID) return if (r.mode == UiMode.ON) "A target bar under the compass, in the nameplates' look: health, cast, auras, combo points, its target. The game's target frame is hidden. For this character."
            else "The game's own target frame, by its switch below. For this character."
        if (r.id == GameUi.UFTOT_ID) return if (r.mode == UiMode.ON) "The target's target beside the target bar, in its nameplate colour. For this character."
            else "The target bar alone. For this character."
        if (r.id == GameUi.PARTY_ID) return if (r.mode == UiMode.ON) "The game's party frames stay on the top screen. For this character."
            else "The game's party frames are hidden. For this character."
        if (r.id == GameUi.TARGET_ID) return if (r.mode == UiMode.ON) "The game's target frame stays on the top screen. For this character."
            else "The game's target frame (and the target's target) is hidden. For this character."
        if (r.id == GameUi.NPF_ID) return if (r.mode == UiMode.ON) "Friendly nameplates while a friend is targeted, out of combat. For this character."
            else "No friendly nameplates. For this character."
        if (r.id == GameUi.NP_ID) return if (r.mode == UiMode.ON) "The bars over hostile mobs are on. Off gives the game's own setting back."
            else "The game's own nameplate setting, as you had it."
        if (r.id == GameUi.CASTBAR_ID) return if (r.mode == UiMode.ON) "The game's own cast bar stays on the top screen."
            else "Hidden while the companion is on this screen; Home's bar shows every cast."
        val room = r.home?.label?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "its"
        return when (r.mode) {
            UiMode.ON -> "The game keeps it on the top screen."
            UiMode.OFF -> "Hidden while the companion is on this screen."
            UiMode.DYNAMIC -> if (UiElements.of(r.id)?.onHome == true)
                "Hidden only while the $room room or Home is showing." else "Hidden only while the $room room is showing."
            UiMode.TRACKING -> "Shown while Find Herbs, Minerals or Treasure is on."
            UiMode.NINTENDO -> "DS Forever's bar replaces the game's on the top screen: the layer you hold, Nintendo buttons, cooldowns."
        }
    }

    // ------------------------------------------------------------------ glyphs

    /** the home room's glyph, on Strip's 24-unit grid, so an element reads as its room */
    private fun elementIcon(c: Canvas, id: String, cx: Float, cy: Float, s: Float) {
        fun X(v: Float) = cx + (v - 12f) * s
        fun Y(v: Float) = cy + (v - 12f) * s
        val p = pStroke
        when (id) {
            "minimap" -> {
                c.drawCircle(cx, cy, 10f * s, p)
                c.drawPath(Path().apply {
                    moveTo(X(15.4f), Y(8.6f)); lineTo(X(13.3f), Y(13.4f))
                    lineTo(X(8.5f), Y(15.5f)); lineTo(X(10.6f), Y(10.7f)); close()
                }, p)
            }
            "hud" -> c.drawPath(Path().apply {
                moveTo(X(13f), Y(2f)); lineTo(X(5f), Y(13f)); lineTo(X(10f), Y(13f))
                lineTo(X(9f), Y(22f)); lineTo(X(17f), Y(11f)); lineTo(X(12f), Y(11f)); close()
            }, p)
            "tracker" -> {
                c.drawPath(Path().apply {
                    moveTo(X(10f), Y(3f)); lineTo(X(14f), Y(3f)); lineTo(X(13f), Y(15f))
                    lineTo(X(11f), Y(15f)); close()
                }, p)
                c.drawCircle(X(12f), Y(19.5f), 1.9f * s, p)
            }
            "chat" -> c.drawPath(Path().apply {
                moveTo(X(20f), Y(11.5f))
                cubicTo(X(20f), Y(16f), X(16.5f), Y(18.5f), X(13f), Y(18.5f))
                lineTo(X(8.5f), Y(18.5f)); lineTo(X(4.5f), Y(21f)); lineTo(X(4.5f), Y(17.4f))
                cubicTo(X(2f), Y(15f), X(3f), Y(4.5f), X(8f), Y(4.5f))
                lineTo(X(13f), Y(4.5f)); cubicTo(X(17.5f), Y(4.5f), X(20f), Y(7f), X(20f), Y(11.5f))
                close()
            }, p)
            // the game's cast bar: a long bar, two thirds filled
            GameUi.CASTBAR_ID -> {
                c.drawRoundRect(RectF(X(2f), Y(9f), X(22f), Y(15f)), 3f, 3f, p)
                pFill.color = p.color
                c.drawRoundRect(RectF(X(3.5f), Y(10.5f), X(15f), Y(13.5f)), 1.5f, 1.5f, pFill)
            }
            // HUD width: a left / right arrow pair
            GameUi.HUD_W_ID -> {
                c.drawLine(X(3f), Y(12f), X(21f), Y(12f), p)
                c.drawLine(X(7f), Y(8f), X(3f), Y(12f), p); c.drawLine(X(7f), Y(16f), X(3f), Y(12f), p)
                c.drawLine(X(17f), Y(8f), X(21f), Y(12f), p); c.drawLine(X(17f), Y(16f), X(21f), Y(12f), p)
            }
            // HUD scale: a small box inside a large one, a diagonal arrow
            GameUi.HUD_S_ID -> {
                c.drawRoundRect(RectF(X(3f), Y(3f), X(21f), Y(21f)), 2.5f, 2.5f, p)
                c.drawRoundRect(RectF(X(5.5f), Y(13f), X(11f), Y(18.5f)), 1.5f, 1.5f, p)
                c.drawLine(X(12f), Y(12f), X(18f), Y(6f), p)
                c.drawLine(X(14f), Y(6f), X(18f), Y(6f), p); c.drawLine(X(18f), Y(10f), X(18f), Y(6f), p)
            }
            // HUD height: an up / down arrow pair
            GameUi.HUD_H_ID -> {
                c.drawLine(X(12f), Y(3f), X(12f), Y(21f), p)
                c.drawLine(X(8f), Y(7f), X(12f), Y(3f), p); c.drawLine(X(16f), Y(7f), X(12f), Y(3f), p)
                c.drawLine(X(8f), Y(17f), X(12f), Y(21f), p); c.drawLine(X(16f), Y(17f), X(12f), Y(21f), p)
            }
            // HUD mode: a portrait frame crossed out
            GameUi.HUD_ID -> {
                c.drawRoundRect(RectF(X(3f), Y(6f), X(21f), Y(18f)), 3f, 3f, p)
                c.drawCircle(X(8.5f), Y(12f), 3f * s, p)
                c.drawLine(X(13f), Y(10f), X(19f), Y(10f), p); c.drawLine(X(13f), Y(14f), X(17f), Y(14f), p)
            }
            // hub33: the pet bar: a round portrait and a short bar beside it
            GameUi.PET_ID -> {
                c.drawCircle(X(7f), Y(12f), 4f * s, p)
                c.drawRoundRect(RectF(X(13f), Y(9.5f), X(21f), Y(14.5f)), 1.5f, 1.5f, p)
            }
            // the compass bar: a ring and its needle
            COMPASS_ID, COMPASS_CAM_ID -> {
                c.drawCircle(X(12f), Y(12f), 8.5f * s, p)
                c.drawLine(X(12f), Y(6f), X(14f), Y(12f), p); c.drawLine(X(14f), Y(12f), X(12f), Y(18f), p)
                c.drawLine(X(12f), Y(18f), X(10f), Y(12f), p); c.drawLine(X(10f), Y(12f), X(12f), Y(6f), p)
            }
            // the loot window: a small chest, its lid line and clasp
            LOOT_ID -> {
                c.drawRoundRect(RectF(X(3f), Y(7f), X(21f), Y(19f)), 2.5f, 2.5f, p)
                c.drawLine(X(3f), Y(11.5f), X(21f), Y(11.5f), p)
                c.drawRect(RectF(X(10.5f), Y(10f), X(13.5f), Y(14f)), p)
            }
            else -> c.drawRoundRect(RectF(X(4f), Y(6f), X(20f), Y(18f)), 3f, 3f, p)
        }
    }

    /** On = an eye, Off = the eye struck through, Dynamic = the two screens, the lower one lit, Tracking = the eye with a small leaf,
     *  Nintendo = the four buttons (the signs the tiles' badges draw too: TilePictures.modeSign) */
    private fun modeIcon(c: Canvas, m: UiMode, cx: Float, cy: Float) = TilePictures.modeSign(c, m, cx, cy, 1.6f, pStroke)

    companion object {
        /** the loot window's row: no hub element, the Interact add-on's own switch */
        const val LOOT_ID = "loot"
        /** the compass bar's row: our own bar (Trail.lua), the add-on's own switch */
        const val COMPASS_ID = "compass"
        /** cp7: the compass's camera experiment */
        const val COMPASS_CAM_ID = "compasscam"
        const val TIPS_ID = "hidetips"
        /** QOL > Navigation: Avoid dangerous areas (Settings.avoidDanger) */
        const val DANGER_ID = "avoiddanger"
        /** QOL: the page's top before its first group, a group heading's height and its text's baseline */
        const val QOL_TOP = 20f
        const val SUBHEAD_H = 64f
        const val SUBHEAD_TEXT = 44f
        const val RINGS_ID = "aggrorings"
        // SettingsView's own metrics: a section heading 16 px above its first row, 96 px rows
        const val HEAD_TEXT = 26f
        const val HEAD_H = 42f
        const val ROW_H = 96f
        const val GAP = 10f
        const val SEG_H = 110f       // 48 dp on this panel: the touch floor
        const val BAR_H = SEG_H + 50f
        const val BOTTOM = 16f
        const val COMPANION_TOP = 38f
        const val STATUS_H = COMPANION_TOP + HEAD_H + 8f + ROW_H + 8f     // the "Companion" heading and its one row
        /** General > Help's status row under Reload (no heading of its own): its top inside the part, and the part's depth */
        const val STATUS_TOP = 2f
        const val STATUS_ROW_H = STATUS_TOP + ROW_H
        /** Top screen > Game windows' order (the user's approved list): the game's elements and our switches by id */
        val WINDOWS_ORDER = listOf("minimap", "tracker", "chat", "dialogs", LOOT_ID, TIPS_ID, GameUi.CASTBAR_ID, GameUi.PLAYER_ID,
            GameUi.TARGET_ID, "hud")
        const val SLOP = 24f
        /** a tile's name: Friz 25, shrunk to fit down to 18, then cut */
        const val NAME_SIZE = 25f
        const val NAME_MIN = 18f
    }
}
