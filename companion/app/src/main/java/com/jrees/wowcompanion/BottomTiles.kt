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


/**
 * SETTINGS > BOTTOM SCREEN AS PICTURE TILES (the beautify pass; the user, 2026-11-17: "I REALLY REALLY love what you did with the top screen
 * settings menu with the pictures and want to do something similar to the other menus"; the design's section 3, critic C5 / C6): 12
 * tiles in TWO groups - Home (9: Rest controller, Combat layout, Swing timer, Focused quest, Clock band, DS WoW toolbar, Hold to talk,
 * Tap-to-cast bar, Spell rank badges) and Map & chat (3: Minimap zoom, World map, Message input). The pictures are real miniatures of our own Home
 * ([BottomPictures]). The settings are written through the same HomeSettings / Settings setters as the rows were (same keys, same
 * per-character store for hold to talk); only where and how they are drawn changed.
 *
 * The model: which tiles a group shows (a fixed list; a tile that does not apply is not drawn - the DS WoW toolbar without the Spells
 * add-on's word, Hold to talk while the toolbar is off, as the rows did), each tile's band lines (a line that cannot act is not drawn:
 * On release while Hold to talk is Off, In combat while the bar is Off), its value word, its badge (Top's eye for the On / Off ones),
 * and ONE write per press.
 */
object BottomTiles {
    /** MOVABLE HOME (SPEC section 3 way 2, final_11; the user's D4): the FIRST tile, "Edit Home": its A opens edit mode (1518: no
     *  layouts, no band of presets / slots) */
    const val LAYOUT = "layout"
    /** 1518 (the user's ruling 2026-11-21: layouts removed, ONE reset kept): the LAST Home tile, "Reset Home" - its band's "Reset Home
     *  to Classic" (this character's) and "Use this Home for all characters" */
    const val HOME_RESET = "homereset"
    const val REST = "rest"
    const val COMBAT = "combat"
    const val SWING = "swing"
    const val QUEST = "quest"
    const val CLOCK = "clock"
    const val TOOLBAR = "toolbar"
    const val TALK = "talk"
    const val CASTBAR = "castbar"
    /** the user, 2026-11-20 ("put that as a toggle in the settings, off by default"): #471's "R3" chips on every ranked spell picture */
    const val RANK = "rank"
    /** 1518 build 2 (the room bar is no edit-mode widget any more, so its menu went): the XP track on Home, Show / Hide (this
     *  character's record, [HomeLayout.xpBar]: Home takes its 34 px; every room keeps it) */
    const val XP = "xpbar"
    const val ZOOM = "zoom"
    const val WORLD = "worldmap"
    const val INPUT = "input"

    /** what the tiles read besides the settings: Spells' D-pad state (the DS WoW toolbar; null = an older add-on), the character */
    data class Env(val dpad: PadMapModel.Dpad?, val who: String?)

    /** Home's 12 (critic C6: Tap-to-cast moved in, it lives on Home; 2026-11-20: Spell rank badges; 1518: Reset Home last, the XP bar
     *  before it) and Map & chat's 3 */
    val HOME_TILES = listOf(LAYOUT, REST, COMBAT, SWING, QUEST, CLOCK, TOOLBAR, TALK, CASTBAR, RANK, XP, HOME_RESET)
    val MAP_TILES = listOf(ZOOM, WORLD, INPUT)

    /** the group's fixed tile list. Until the core lands the Bottom screen's two groups (Home, Map & chat) the old four are mapped: Home
     *  draws Home's 8, Map draws Map & chat's 3; Tap-to-cast bar and Chat draw none (their headings are not shown) */
    fun tiles(g: SettingsTabs.Group): List<String> = when (g.name) {
        "HOME" -> HOME_TILES
        "MAP", "MAP_CHAT" -> MAP_TILES
        else -> emptyList()
    }

    /** the group's heading: Map's is "Map & chat" (critic C6: Message input lives there), the rest as SettingsTabs names them. INTERIM
     *  until the core's SettingsTabs has the Bottom screen's MAP_CHAT group */
    fun heading(g: SettingsTabs.Group): String = if (g.tab == SettingsTabs.Tab.BOTTOM && g.name == "MAP") "Map & chat" else g.heading

    /** every tile, both groups */
    val ALL: List<String> get() = HOME_TILES + MAP_TILES

    /** [g]'s tiles now: the fixed list, less a tile that does not apply */
    fun slots(g: SettingsTabs.Group, env: Env): List<String> = tiles(g).filter { applies(it, env) }

    fun applies(id: String, env: Env): Boolean = when (id) {
        TOOLBAR -> env.dpad != null
        TALK -> env.dpad?.want == true && env.dpad.colOn != null
        else -> true
    }

    /** a tile's name (display words, Tr.t'd by the drawer) */
    val NAMES: Map<String, String> = mapOf(LAYOUT to "Edit Home", HOME_RESET to "Reset Home", REST to "Rest controller", COMBAT to "In fights", SWING to "Swing timer",
        QUEST to "Focused quest", CLOCK to "Clock band", TOOLBAR to "DS WoW toolbar", TALK to "Hold to talk", CASTBAR to "Tap-to-cast bar",
        RANK to "Spell rank badges", XP to "XP bar", ZOOM to "Minimap zoom", WORLD to "World map", INPUT to "Message input")

    /** the clock band's lines: key, label, its icons.pak icon */
    val CLOCK_LINES = listOf(Triple("hearth", "Hearthstone", ItemCd.HEARTH_ICON), Triple("teleport", "Class teleport", 136010),
        Triple("gold", "Gold", 133784), Triple("bags", "Free bag slots", 133633), Triple("ammo", "Ammo", 132382))

    /** one line of a band: a choice ([words], [picked] lit; [modes] = Top's On / Off signs), or a stepper ([stepper]: [words] are its
     *  buttons, [value] its value) */
    class Line(val key: String, val label: String?, val words: List<String>, val picked: Int, val modes: List<UiMode>? = null,
               val value: String? = null, val icon: Int? = null, val enabled: Boolean = true, val stepper: Boolean = false)

    private val ONOFF = listOf("On", "Off")
    private val SHOW_HIDE = listOf("Show", "Hide")
    private val ONOFF_MODES = listOf(UiMode.ON, UiMode.OFF)
    private fun onOff(key: String, on: Boolean, label: String? = null, icon: Int? = null, enabled: Boolean = true) =
        Line(key, label, ONOFF, if (on) 0 else 1, ONOFF_MODES, icon = icon, enabled = enabled)

    fun clockOn(key: String): Boolean = when (key) {
        "hearth" -> HomeSettings.clockHearth; "teleport" -> HomeSettings.clockTeleport; "gold" -> HomeSettings.clockGold
        "bags" -> HomeSettings.clockBags; else -> HomeSettings.clockAmmo
    }

    /** [id]'s band: its choices, then its lines that can act now */
    fun lines(id: String, env: Env): List<Line> = when (id) {
        LAYOUT -> emptyList()
        HOME_RESET -> resetLines()
        REST -> listOf(Line(REST, null, listOf("Thin row", "Diamonds"), HomeRest.values().indexOf(HomeSettings.rest)))
        // movable Home phase 3 (SPEC section 11, final_11; the user's D1: Grow the default, Top screen opt-in): the old Combat layout tile
        COMBAT -> listOf(Line(COMBAT, null, FIGHT_WORDS, HomeLayout.FightBand.values().indexOf(HomeSettings.fightBand)))
        SWING -> listOf(onOff(SWING, HomeSettings.swingTimer))
        RANK -> listOf(onOff(RANK, HomeSettings.rankBadges))
        XP -> listOf(Line(XP, null, SHOW_HIDE, if (HomeSettings.layout.xpBar) 0 else 1, listOf(UiMode.ON, UiMode.OFF)))
        QUEST -> listOf(Line(QUEST, null, CARD_WORDS, CARD_DOCKS.indexOf(HomeSettings.layout.card)))
        CLOCK -> CLOCK_LINES.map { (k, label, icon) -> onOff(k, clockOn(k), label, icon) }
        TOOLBAR -> listOf(onOff(TOOLBAR, env.dpad?.want == true, enabled = env.dpad?.colOn != null))
        TALK -> {
            val on = HomeSettings.holdTalk(env.who)
            val out = arrayListOf(onOff(TALK, on, enabled = env.who != null))
            if (on && env.who != null) out += Line("release", "On release", listOf("Show, A sends", "Send now"),
                if (HomeSettings.talkSendNow(env.who)) 1 else 0)
            out
        }
        CASTBAR -> {
            // movable Home: the place in words that follow the widgets (SPEC P4 "meaning follows the widget"), and its slot size
            val cast = HomeLayoutRules.shown(HomeSettings.layout).cast
            val out = arrayListOf(Line(CASTBAR, null, CAST_WORDS, CAST_DOCKS.indexOf(cast.withTop(false))))
            // a column's bar: above or below that column (Across and Split stay at the foot)
            cast.column?.let { c -> out += Line(CAST_EDGE, if (c == HomeModule.CHAT) "Chat" else "Map", HomeWidgetMenus.EDGE_WORDS, if (cast.top) 0 else 1) }
            if (HomeSettings.castBarSide != CastStripModel.Side.OFF) {
                out += Line("castsize", "Size", SIZE_WORDS, HomeLayout.SlotSize.values().indexOf(HomeSettings.layout.castSize))
                val m = CastStripModel.Combat.values()
                // cb14 (D5b): the same setting as the Controller room's In combat > "Show tap-to-cast" check box (D4), its mirror here
                out += Line("castcombat", "Show in combat", m.map { it.label }, m.indexOf(HomeSettings.castBarCombat))
            }
            out
        }
        ZOOM -> listOf(Line("out", "Outdoors", listOf("−", "+"), -1, value = "${Settings.minimapYd} yd", stepper = true),
            Line("in", "Indoors", listOf("−", "+"), -1, value = "${Settings.minimapIndoorYd} yd", stepper = true))
        WORLD -> listOf(Line(WORLD, null, listOf("Show all", "Explored only"), if (Settings.worldMapFog == "all") 0 else 1))
        INPUT -> listOf(Line(INPUT, null, listOf("Quick chat", "Keyboard"), if (Settings.chatDefaultInput == "quick") 0 else 1))
        else -> emptyList()
    }

    /** the band's one status line: state only (never an explanation); null = none */
    fun status(id: String, env: Env): String? = when {
        id == TOOLBAR && env.dpad?.colOn == null -> "Needs the newer Spells add-on"
        id == TALK && env.who == null -> "Waiting for the game"
        id == COMBAT -> TopCombatBar.status(HomeSettings.layout, env.dpad?.ct,
            env.dpad?.let { CharKey.of(it.name, it.realm) != null } == true)     // the 1514 merge: no key = PadMap m181 sends no `ct` (not an older add-on)
        else -> null
    }

    /** the tile's value word */
    fun value(id: String, env: Env): String = when (id) {
        REST -> if (HomeSettings.rest == HomeRest.DIAMONDS) "Diamonds" else "Thin row"
        COMBAT -> FIGHT_WORDS[HomeLayout.FightBand.values().indexOf(HomeSettings.fightBand)]
        SWING -> if (HomeSettings.swingTimer) "On" else "Off"
        RANK -> if (HomeSettings.rankBadges) "On" else "Off"
        XP -> if (HomeSettings.layout.xpBar) "Show" else "Hide"
        QUEST -> CARD_WORDS[CARD_DOCKS.indexOf(HomeSettings.layout.card).coerceAtLeast(0)]
        CLOCK -> CLOCK_LINES.count { clockOn(it.first) }.let { n -> if (n == CLOCK_LINES.size) "On" else if (n == 0) "Off" else "$n / ${CLOCK_LINES.size}" }
        TOOLBAR -> if (env.dpad?.want == true) "On" else "Off"
        TALK -> if (HomeSettings.holdTalk(env.who)) "On" else "Off"
        CASTBAR -> castWord(HomeLayoutRules.shown(HomeSettings.layout).cast)
        ZOOM -> "${Settings.minimapYd} · ${Settings.minimapIndoorYd} yd"
        WORLD -> if (Settings.worldMapFog == "all") "Show all" else "Explored only"
        INPUT -> if (Settings.chatDefaultInput == "quick") "Quick chat" else "Keyboard"
        else -> ""
    }

    /** the 36 px badge: Top's eye for the On / Off tiles, none for the others (their value word says it) */
    fun badge(id: String, env: Env): GameUiTiles.Badge? = when (id) {
        SWING, TOOLBAR, TALK, RANK -> if (value(id, env) == "On") GameUiTiles.Badge.ON else GameUiTiles.Badge.OFF
        XP -> if (HomeSettings.layout.xpBar) GameUiTiles.Badge.ON else GameUiTiles.Badge.OFF
        else -> null
    }

    /** the picture dimmed: the choice is Off (or the add-on cannot do it yet) */
    fun dimmed(id: String, env: Env): Boolean = when (id) {
        SWING -> !HomeSettings.swingTimer
        RANK -> !HomeSettings.rankBadges
        XP -> !HomeSettings.layout.xpBar
        TOOLBAR -> env.dpad?.want != true || env.dpad.colOn == null
        TALK -> !HomeSettings.holdTalk(env.who)
        CASTBAR -> HomeSettings.castBarSide == CastStripModel.Side.OFF
        CLOCK -> CLOCK_LINES.none { clockOn(it.first) }
        else -> false
    }

    // ---------------------------------------------------------------- movable Home: the layout's words

    /** the card's docks in the tile's order, and their words (they follow the widgets: "On map", never "Right") */
    val CARD_DOCKS = listOf(HomeLayout.CardDock.ON_MAP, HomeLayout.CardDock.ABOVE_CHAT, HomeLayout.CardDock.OFF)
    val CARD_WORDS = listOf("On map", "Above chat", "Off")
    /** the bar's places offered in this build (Split is phase 3's) and their words */
    val CAST_DOCKS = listOf(HomeLayout.CastDock.OFF, HomeLayout.CastDock.UNDER_CHAT, HomeLayout.CastDock.UNDER_MAP, HomeLayout.CastDock.ACROSS,
        HomeLayout.CastDock.SPLIT)
    val CAST_WORDS = listOf("Off", "Chat", "Map", "Across", "Split")
    /** the bar's edge line (column docks only): Above / Below its column */
    const val CAST_EDGE = "castedge"
    /** the tile's value word for a dock */
    fun castWord(c: HomeLayout.CastDock): String = when (c) {
        HomeLayout.CastDock.OFF -> "Off"; HomeLayout.CastDock.UNDER_CHAT -> "Under chat"; HomeLayout.CastDock.UNDER_MAP -> "Under map"
        HomeLayout.CastDock.ABOVE_CHAT -> "Above chat"; HomeLayout.CastDock.ABOVE_MAP -> "Above map"
        HomeLayout.CastDock.ACROSS -> "Across"; HomeLayout.CastDock.SPLIT -> "Split"
    }

    /**
     * MOVABLE HOME P2 (SPEC section 3 way 2, final_11; the user's D4): A (or a finger) on the FIRST tile, "Edit Home", opens EDIT MODE
     * on Home (the host: Settings slides to Home with the room slide, the cursor on Chat). Null (the off-device renders, an older host) =
     * the tile opens its (empty) band. The tile wears the A glyph while it does.
     */
    @JvmStatic var editHome: (() -> Unit)? = null
    val SIZE_WORDS = listOf("S", "M", "L")
    /** "In fights" in [HomeLayout.FightBand]'s order */
    val FIGHT_WORDS = listOf("Grow", "Same size", "Top screen")

    /** the Reset Home band's buttons (1518): this character's Home back to Classic; this character's Home for every character. BOTH
     *  only once a WHOLE character key is known: the store writes no pending name's record (a reset held against the pending view
     *  would lay nothing on the real record when the key arrives - the review's finding), and "all" would skip this very character */
    const val HOME_CLASSIC = "homeclassic"
    const val ALL_CHARS = "allchars"
    fun resetLines(): List<Line> = listOf(
        Line(HOME_CLASSIC, null, listOf("Reset Home to Classic"), -1, enabled = HomeLayoutStore.who != null),
        Line(ALL_CHARS, null, listOf("Use this Home for all characters"), -1, enabled = HomeLayoutStore.who != null),
    )

    /** the band's picture moves (the open band only, up to 30 fps) */
    fun animated(id: String): Boolean = id == COMBAT || id == SWING || id == TALK || id == CASTBAR

    /** button [k] of line [key] of tile [id]: ONE player-pressed write, the rows' own setters; [onDpad] = the DS WoW toolbar's switch */
    fun press(id: String, key: String, k: Int, env: Env, onDpad: (Boolean) -> Unit) {
        when (key) {
            HOME_CLASSIC -> if (HomeLayoutStore.who != null) HomeSettings.resetHomeToClassic()
            ALL_CHARS -> if (HomeLayoutStore.who != null) HomeLayoutStore.set(HomeSettings.layout, all = true)
            "castsize" -> HomeLayoutStore.update { it.copy(castSize = HomeLayout.SlotSize.values()[k]) }
            REST -> HomeSettings.rest = HomeRest.values()[k]
            COMBAT -> HomeLayout.FightBand.values().getOrNull(k)?.let { HomeSettings.fightBand = it }
            SWING -> HomeSettings.swingTimer = k == 0
            RANK -> HomeSettings.rankBadges = k == 0
            XP -> HomeLayoutStore.update { it.copy(xpBar = k == 0) }
            QUEST -> CARD_DOCKS.getOrNull(k)?.let { d -> HomeLayoutStore.update { it.copy(card = d) } }
            "hearth" -> HomeSettings.clockHearth = k == 0
            "teleport" -> HomeSettings.clockTeleport = k == 0
            "gold" -> HomeSettings.clockGold = k == 0
            "bags" -> HomeSettings.clockBags = k == 0
            "ammo" -> HomeSettings.clockAmmo = k == 0
            TOOLBAR -> env.dpad?.let { d -> if (d.colOn != null) { val on = k == 0; if ((d.want == true) != on) onDpad(on) } }
            TALK -> env.who?.let { HomeSettings.setHoldTalk(it, k == 0) }
            "release" -> env.who?.let { HomeSettings.setTalkSendNow(it, k == 1) }
            // Chat / Map keep the bar's edge; the edge line moves it above / below its column
            CASTBAR -> CAST_DOCKS.getOrNull(k)?.let { d -> HomeSettings.castDock = d.column?.let { HomeSettings.castDock.withColumn(it) } ?: d }
            CAST_EDGE -> HomeSettings.castDock = HomeSettings.castDock.withTop(k == 0)
            "castcombat" -> HomeSettings.castBarCombat = CastStripModel.Combat.values()[k]
            "out" -> Settings.minimapYd = Settings.stepYd(Settings.minimapYd, if (k == 0) -1 else 1)
            "in" -> Settings.minimapIndoorYd = Settings.stepYd(Settings.minimapIndoorYd, if (k == 0) -1 else 1)
            WORLD -> Settings.worldMapFog = if (k == 0) "all" else "explored"
            INPUT -> { val v = if (k == 0) "quick" else "keys"; if (Settings.chatDefaultInput != v) Settings.chatDefaultInput = v }
        }
    }
}

/**
 * ONE BOTTOM SCREEN GROUP AS TILES (Home's 8, Map & chat's 3): Top screen's look exactly - the tile (ground, kit ring, the 216 x 108 well,
 * the badge, the name in Friz 25 and the gold value), one band under the open tile's row (the picture large, the choices as the game's red
 * buttons, Top's eye signs on the On / Off ones), the cursor's golden box over it all (SettingsPad). A tap or A on a tile opens its band
 * (another's closes), on a band button presses it; B closes the band with the cursor back on the tile. INTERIM drawing: the shared
 * core's TileDraw (lifted from GameUiSection) replaces the copies here.
 */
class BottomTilesView(context: Context, val group: SettingsTabs.Group, private val dpad: () -> PadMapModel.Dpad?,
                      private val onDpad: (Boolean) -> Unit, private val who: () -> String? = { GameUi.character }) : View(context), BandPart {
    /** a tile's band opened (or moved): the room closes the others and follows it */
    var onOpen: ((BottomTilesView) -> Unit)? = null
    /** the tiles came or went (the room shows / hides the group) */
    var onContent: (() -> Unit)? = null

    private var selected: String? = null
    /** the band line the cursor is on (Minimap zoom's picture follows Outdoors / Indoors) */
    private var lineFocus: String? = null
    /** the tile the cursor is on (the crops nearest it are made first) */
    private var focusIdx = 0
    private var downX = 0f
    private var downY = 0f
    /** when the open band opened: its animation starts there (it opens on the picture its tile shows) */
    private var openedAt = SystemClock.uptimeMillis()

    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val pName by themed { Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = NAME_SIZE; color = Theme.text } }
    private val pValue by themed { Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; color = Theme.gold } }
    private val pBandLabel by themed { Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 25f; color = Theme.text } }
    private val pSub by themed { Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; color = Theme.textDim } }
    private val pSeg = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 27f; typeface = Typeface.DEFAULT_BOLD }
    private val pIcon = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val tilePath = Path()

    private val changed: () -> Unit = { post { requestLayout(); invalidate(); onContent?.invoke() } }

    init {
        HomeSettings.onChange(changed); Settings.onChange(changed)
        Theme.onChange { postInvalidate() }
    }

    fun env() = BottomTiles.Env(dpad(), who())
    private fun slots() = BottomTiles.slots(group, env())

    /** this group has tiles to draw now */
    fun hasContent(): Boolean = slots().isNotEmpty()
    /** the tiles drawn now, in order (the room's tests) */
    fun tileIds(): List<String> = slots()
    /** the tile open now, null = none */
    val openTile: String? get() = selected

    override val hasSelection: Boolean get() = selected != null

    override fun closeSelection(): Boolean {
        if (selected == null) return false
        selected = null; lineFocus = null; requestLayout(); invalidate()
        return true
    }

    override fun selectionAnchor(): RectF? {
        val id = selected ?: return null
        val l = layout(width.toFloat())
        return l.slots.indexOf(id).takeIf { it >= 0 }?.let { l.grid.tiles[it].rect() }
    }

    override fun bandBox(): RectF? = if (selected == null) null else layout(width.toFloat()).grid.band?.rect()

    /** the cursor's targets: every tile, then the open band's buttons (its picture and status line are not stops) */
    fun focusTargets(): List<RectF> {
        val l = layout(width.toFloat())
        val out = ArrayList<RectF>()
        l.grid.tiles.forEach { out.add(it.rect()) }
        l.band?.lines?.forEach { line -> line.buttons.forEach { out.add(it.rect()) } }
        return out
    }

    /** the cursor is at ([x], [y]) (the room's onFocus): the band's line under it leads the picture, its tile's crops come first */
    fun focusAt(x: Float, y: Float) {
        val l = layout(width.toFloat())
        val i = l.grid.tiles.indexOfFirst { it.contains(x, y) }
        if (i >= 0) focusIdx = i
        val k = l.band?.lines?.indexOfFirst { line -> line.buttons.any { it.contains(x, y) } } ?: -1
        val key = if (k >= 0) l.lines.getOrNull(k)?.key else null
        if (key != null && key != lineFocus) { lineFocus = key; invalidate() }
    }

    // ---------------------------------------------------------------- layout

    private class Layout(val slots: List<String>, val grid: GameUiTiles.Grid, val band: GameUiTiles.Band?, val lines: List<BottomTiles.Line>)

    private fun layout(w: Float): Layout {
        val e = env()
        val ids = BottomTiles.slots(group, e)
        val open = selected?.let { ids.indexOf(it) } ?: -1
        val lines = if (open >= 0) BottomTiles.lines(ids[open], e) else emptyList()
        val gl = lines.map { GameUiTiles.Line(it.key, it.words.size, it.label != null) }
        val bw = w - 2f * GameUiTiles.MARGIN
        val bandH = if (open >= 0) GameUiTiles.band(GameUiTiles.MARGIN, 0f, bw, gl).height else 0f
        val grid = GameUiTiles.grid(ids.size, w, open, bandH)
        val band = grid.band?.let { GameUiTiles.band(it.l, it.t, it.w, gl) }
        return Layout(ids, grid, band, lines)
    }

    private fun GameUiTiles.Box.rect() = RectF(l, t, r, b)

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = getDefaultSize(suggestedMinimumWidth, widthSpec)
        // a tile that went (the toolbar's word) takes its band with it
        if (selected != null && selected !in slots()) { selected = null; lineFocus = null }
        setMeasuredDimension(w, resolveSize(layout(w.toFloat()).grid.height.toInt(), heightSpec))
    }

    // ---------------------------------------------------------------- touch (A is a touch at the button's centre)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
            MotionEvent.ACTION_UP -> {
                if (abs(e.x - downX) > SLOP || abs(e.y - downY) > SLOP) return true
                val l = layout(width.toFloat())
                l.band?.lines?.forEachIndexed { i, line ->
                    val k = line.buttons.indexOfFirst { it.contains(e.x, e.y) }
                    if (k >= 0) {
                        val ln = l.lines[i]
                        lineFocus = ln.key
                        if (ln.enabled) BottomTiles.press(selected ?: return true, ln.key, k, env(), onDpad)
                        requestLayout(); invalidate()
                        return true
                    }
                }
                val i = l.grid.tiles.indexOfFirst { it.contains(e.x, e.y) }
                if (i >= 0) {
                    val id = l.slots[i]
                    // P2: the Edit Home tile's A = edit mode (no band)
                    if (id == BottomTiles.LAYOUT) BottomTiles.editHome?.let { go -> focusIdx = i; selected = null; go(); return true }
                    focusIdx = i
                    selected = if (selected == id) null else id
                    lineFocus = null
                    openedAt = SystemClock.uptimeMillis()
                    requestLayout(); invalidate()
                    if (selected != null) onOpen?.invoke(this)
                }
            }
        }
        return true
    }

    // ---------------------------------------------------------------- drawing

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        BottomPictures.shown(System.identityHashCode(this), isVisible)
    }

    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); BottomPictures.shown(System.identityHashCode(this), false) }

    override fun onDraw(c: Canvas) {
        UiKit.asBody(pName); UiKit.asBody(pValue); UiKit.asBody(pBandLabel); UiKit.asBody(pSub); UiKit.asBody(pSeg)
        // feat/beautify (themes 8.4): no page ink - the room's RoomGround.settings (the dimmed rock) shows through
        val e = env()
        val l = layout(width.toFloat())
        val look = BottomPictures.Look(e, zoomIndoor = lineFocus == "in")
        BottomPictures.onArt = { postInvalidate() }
        for ((i, id) in l.slots.withIndex()) drawTile(c, l.grid.tiles[i], id, e, look, id == selected, abs(i - focusIdx) + 1)
        val open = l.slots.indexOf(selected)
        val band = l.band
        if (open >= 0 && band != null) drawBand(c, l.grid.band!!, band, l.grid.tiles[open], selected!!, e, look, l.lines)
        // the crops: one made per frame, the cursor's first; the open band's picture moves (up to 30 fps) while it shows
        // (a detached room - the renders - draws its crops too: only a hidden view in a window makes none)
        if ((isShown || !isAttachedToWindow) && BottomPictures.pump(context)) postInvalidateOnAnimation()
        else if (isShown && open >= 0 && BottomTiles.animated(selected!!)) postInvalidateDelayed(FRAME_MS)
    }

    /** the picture, dimmed when the choice is Off */
    private fun picture(c: Canvas, id: String, well: RectF, e: BottomTiles.Env, look: BottomPictures.Look, tMs: Long?, prio: Int) {
        BottomPictures.draw(c, context, id, well, look, tMs, prio)
        if (BottomTiles.dimmed(id, e)) { pFill.color = Theme.ink; pFill.alpha = 165; c.drawRoundRect(well, 8f, 8f, pFill); pFill.alpha = 255 }
        pStroke.color = Color.argb(200, 0, 0, 0); pStroke.strokeWidth = 2f
        c.drawRoundRect(well, 8f, 8f, pStroke)
    }

    /** one tile: Top screen's tile exactly (its ground, the lit ring while its band is open - never the cursor -, the picture, the badge,
     *  the name and the value word) */
    private fun drawTile(c: Canvas, box: GameUiTiles.Box, id: String, e: BottomTiles.Env, look: BottomPictures.Look, open: Boolean, prio: Int) {
        val rect = box.rect()
        pFill.color = if (open) Theme.selected else Theme.raised
        c.drawRoundRect(rect, 10f, 10f, pFill)
        picture(c, id, GameUiTiles.well(box).rect(), e, look, null, prio)
        BottomTiles.badge(id, e)?.let { TilePictures.badge(c, GameUiTiles.badgeBox(box).rect(), it) }
        // P2 (final_11): the Edit Home tile's A glyph - A here opens edit mode
        if (id == BottomTiles.LAYOUT && BottomTiles.editHome != null)
            UiKit.pad(c, UiKit.okGlyph(), box.r - GameUiTiles.WELL_PAD - 22f, box.t + GameUiTiles.WELL_PAD + GameUiTiles.WELL_H + 24f, 42f)
        edge(c, rect, open)
        val x = box.l + GameUiTiles.WELL_PAD + 2f
        val maxW = box.w - 2f * GameUiTiles.WELL_PAD - 4f
        pName.color = Theme.text
        val nameW = if (id == BottomTiles.LAYOUT && BottomTiles.editHome != null) maxW - 48f else maxW
        c.drawText(fitText(pName, Tr.t(BottomTiles.NAMES[id] ?: id), nameW, NAME_SIZE, NAME_MIN), x, box.t + GameUiTiles.WELL_PAD + GameUiTiles.WELL_H + 34f, pName)
        pValue.color = if (BottomTiles.dimmed(id, e)) Theme.textDim else Theme.gold
        c.drawText(fitText(pValue, Tr.t(BottomTiles.value(id, e)), maxW, 22f, 16f), x, box.b - 12f, pValue)
    }

    /** the game's metal ring (the kit's ring_square), lit for the open tile; the drawn ring without the kit */
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

    /**
     * the band's labels' ONE size (round 3, the verifier: "'Emplacements libres' is about 12 px ... while 'Or' and 'Pierre de foyer'
     * are full size"): the largest from 25 down to [LABEL_MIN] at which EVERY plain label of the band fits its room in up to three
     * whole-word lines. A label whose icon leaves too little room (a long French / Spanish word beside the clock band's 40 px icon)
     * stands UNDER its icon instead, the label box's full width; those lines' indexes come back as [LabelPlan.stacked]
     */
    class LabelPlan(val size: Float, val stacked: Set<Int>)

    private fun labelPlan(band: GameUiTiles.Band, lines: List<BottomTiles.Line>): LabelPlan {
        class Item(val i: Int, val s: String, val lb: GameUiTiles.Box, val icon: Boolean)
        val items = band.lines.withIndex().mapNotNull { (i, line) ->
            val ln = lines.getOrNull(i) ?: return@mapNotNull null
            val lb = line.label ?: return@mapNotNull null
            if (ln.value != null) return@mapNotNull null
            Item(i, Tr.t(ln.label ?: ""), lb, ln.icon?.let { Icons.get(it) } != null)
        }
        val p = pBandLabel
        var size = 25f
        while (size >= LABEL_MIN) {
            p.textSize = size
            val lh = size * 1.1f
            val stacked = HashSet<Int>()
            val ok = items.all { it ->
                val full = it.lb.w - 8f
                val side = wrapWords(it.s, full - (if (it.icon) ICON + 10f else 0f)) { w -> p.measureText(w) }
                if (side != null && side.size <= 3 && side.size * lh <= it.lb.h - 6f) true
                else if (it.icon) {
                    val under = wrapWords(it.s, full) { w -> p.measureText(w) }
                    (under != null && under.size <= 2 && ICON_STACK + 4f + under.size * lh <= it.lb.h - 4f).also { f -> if (f) stacked += it.i }
                } else false
            }
            if (ok) return LabelPlan(size, stacked)
            size -= 1f
        }
        return LabelPlan(LABEL_MIN, emptySet())
    }

    /** a band line's label at the plan's size in whole-word lines, centred on [cy] (or under [top]), never cut short unless one word alone
     *  is wider than the room even at the floor */
    private fun label(c: Canvas, s: String, x: Float, cy: Float, room: Float, size: Float, top: Float? = null) {
        val p = pBandLabel
        p.textSize = size
        val lines = wrapWords(s, room) { p.measureText(it) }
        if (lines == null) { c.drawText(fitText(p, s, room, size, LABEL_MIN), x, cy + size * 0.36f, p); p.textSize = 25f; return }
        val lh = size * 1.1f
        val t0 = top ?: (cy - lh * lines.size / 2f)
        for ((i, l) in lines.withIndex()) c.drawText(l, x, t0 + lh * i + lh * 0.78f, p)
        p.textSize = 25f
    }

    /** the open tile's band (Top's): under its row, a notch at the tile; the picture large, the lines, the one status line */
    private fun drawBand(c: Canvas, box: GameUiTiles.Box, band: GameUiTiles.Band, tile: GameUiTiles.Box, id: String, e: BottomTiles.Env,
                         look: BottomPictures.Look, lines: List<BottomTiles.Line>) {
        val rect = box.rect()
        pFill.color = Theme.panel
        c.drawRoundRect(rect, 12f, 12f, pFill)
        edge(c, rect, false)
        val nx = tile.cx
        tilePath.reset(); tilePath.moveTo(nx - 18f, rect.top + 3f); tilePath.lineTo(nx, rect.top - 13f); tilePath.lineTo(nx + 18f, rect.top + 3f); tilePath.close()
        c.drawPath(tilePath, pFill)
        pStroke.color = Theme.gold; pStroke.strokeWidth = 2.6f
        c.drawLine(nx - 18f, rect.top + 1f, nx, rect.top - 13f, pStroke); c.drawLine(nx, rect.top - 13f, nx + 18f, rect.top + 1f, pStroke)
        val tMs = if (BottomTiles.animated(id)) SystemClock.uptimeMillis() - openedAt else null
        picture(c, id, band.picture.rect(), e, look, tMs, 0)
        val plan = labelPlan(band, lines)
        for ((i, line) in band.lines.withIndex()) {
            val ln = lines.getOrNull(i) ?: continue
            line.label?.let { lb ->
                var lx = lb.l + 4f
                val stacked = i in plan.stacked
                var under: Float? = null
                ln.icon?.let { fid ->
                    Icons.get(fid)?.let { bmp ->
                        // the design's 3.2: the icons.pak icon at 40 px left of the label (a label too long beside it: under it)
                        val n = if (stacked) wrapWords(Tr.t(ln.label ?: ""), lb.w - 8f) { w -> pBandLabel.apply { textSize = plan.size }.measureText(w) }?.size ?: 1 else 0
                        val ib = if (stacked) {
                            val total = ICON_STACK + 4f + n * plan.size * 1.1f
                            val t = lb.cy - total / 2f
                            under = t + ICON_STACK + 4f
                            RectF(lx, t, lx + ICON_STACK, t + ICON_STACK)
                        } else RectF(lx, lb.cy - ICON / 2f, lx + ICON, lb.cy + ICON / 2f)
                        c.drawBitmap(bmp, null, ib, pIcon)
                        pStroke.color = Theme.bronzeDim; pStroke.strokeWidth = 1.5f; c.drawRect(ib, pStroke)
                        if (!stacked) lx += ICON + 10f
                    }
                }
                val room = lb.r - lx - 4f
                pBandLabel.color = Theme.text
                if (ln.value != null) {
                    c.drawText(fitText(pBandLabel, Tr.t(ln.label ?: ""), room, 25f, 18f), lx, lb.cy - 6f, pBandLabel)
                    pValue.color = Theme.gold
                    c.drawText(fitText(pValue, Tr.t(ln.value), room, 22f, 18f), lx, lb.cy + 26f, pValue)
                } else label(c, Tr.t(ln.label ?: ""), lx, lb.cy, room, plan.size, under)
            }
            for ((k, b) in line.buttons.withIndex()) {
                val m = ln.modes?.getOrNull(k)
                if (m != null) modeChoice(c, b.rect(), m, k == ln.picked, ln.enabled)
                else UiKit.choice(c, b.rect(), Tr.t(ln.words.getOrElse(k) { "" }), k == ln.picked, ln.enabled, textSize = if (ln.stepper) 40f else 27f)
            }
        }
        BottomTiles.status(id, e)?.let { say ->
            val st = band.status
            pSub.color = Theme.textDim
            c.drawText(fitText(pSub, Tr.t(say), st.w - 8f, 22f, 18f), st.l + 4f, st.cy + 8f, pSub)
        }
    }

    /** an On / Off choice: the game's red button, lit when chosen, Top's eye sign beside its word */
    private fun modeChoice(c: Canvas, b: RectF, m: UiMode, on: Boolean, enabled: Boolean) {
        UiKit.choice(c, b, "", on, enabled)
        val col = if (!enabled) Theme.dim else if (on) Theme.gold else Theme.textDim
        val w = Tr.t(if (m == UiMode.ON) "On" else "Off")
        pSeg.textSize = 27f
        while (pSeg.measureText(w) + 58f > b.width() - 44f && pSeg.textSize > 20f) pSeg.textSize -= 1f
        pSeg.color = col
        pStroke.color = col; pStroke.strokeWidth = 2.4f
        val total = 44f + 14f + pSeg.measureText(w)
        val ix = b.centerX() - total / 2f + 22f
        TilePictures.modeSign(c, m, ix, b.centerY(), 1.6f, pStroke)
        c.drawText(w, ix + 36f, b.centerY() + 10f, pSeg)
        pSeg.textSize = 27f
    }

    companion object {
        const val NAME_SIZE = 25f
        const val NAME_MIN = 18f
        const val SLOP = 24f
        /** the open band's animation: up to 30 fps */
        const val FRAME_MS = 33L
        /** a band line's icon (the clock band's: the design's 3.2) */
        const val ICON = 40f
        /** a label too long beside its icon stands under it (the same 40 px icon) */
        const val ICON_STACK = ICON
        /** the band labels' floor (one size for the whole band) */
        const val LABEL_MIN = 18f

        /** [s]'s words laid in lines no wider than [maxW] ([w] measures), null = one word alone is wider */
        fun wrapWords(s: String, maxW: Float, w: (String) -> Float): List<String>? {
            val out = ArrayList<String>()
            var cur = ""
            for (word in s.split(' ').filter { it.isNotEmpty() }) {
                if (w(word) > maxW) return null
                val next = if (cur.isEmpty()) word else "$cur $word"
                if (w(next) <= maxW) cur = next else { out += cur; cur = word }
            }
            if (cur.isNotEmpty()) out += cur
            return out
        }
    }
}
