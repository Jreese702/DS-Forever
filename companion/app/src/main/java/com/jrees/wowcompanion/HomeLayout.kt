package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * MOVABLE HOME, THE LAYOUT RECORD (SPEC.txt "Live docks", section 13; phase 1). Home is a set of DOCKS that this small record fills;
 * [HomeLayoutSolver] turns it into rects, [HomeView.apply]'s one motion glides between two of them. Pure (no Android types), so the JVM
 * tests enumerate every record against every state (HomeLayoutRulesTest).
 *
 * The rulings it carries (the user, 2026-11-20, on DECISIONS.txt):
 *  - D1: in fights the band GROWs by default (today's); "Top screen" is opt-in only, "Same size" stays.
 *  - D5: the active layout is PER CHARACTER ([HomeLayoutStore]); Settings' "Use this Home for all characters" copies it (1518).
 *  - 2026-11-21 (after 1517): no layouts any more - no presets, no slots, no layout chip; each character keeps its own arrangement, and
 *    Settings' "Reset Home to Classic" ([classic]) puts this character's back.
 *  - D8: after a swap LB+LT slides the LEFT column, whatever it holds (Home's slides are by position, see HomeView.pauseSlide).
 * P8 "Classic = today": the record migrated from today's keys ([HomeLayoutMigration]) lays Home exactly as before (HomePlacesGoldenTest).
 */
data class HomeLayout(
    val v: Int = VERSION,
    /** LEGACY, informative only: the preset or slot 1517 last saved it as ("Classic", "Mirrored", ..., "1".."3"). Never shown and
     *  never re-derived since 1518 (no presets): read tolerantly and written back as read, so a record's text stays as it was (a
     *  Classic record byte-identical; a 1517 downgrade reads it). A record that names a preset already holds that preset's fields */
    val name: String = LEGACY_CLASSIC,
    /** which column the chat takes; the map takes the other */
    val chatSide: Side = Side.LEFT,
    /** the chat column's share of the inner width (the map takes the rest) */
    val chatShare: Share = Share.HALF,
    /** the focused quest's card: over the map (under its zone band), at the top of the chat's column, or not on Home */
    val card: CardDock = CardDock.ON_MAP,
    /** the tap-to-cast bar: off, under or above the chat, under or above the map (both follow a swap: they name a module, not a side),
     *  one bar across both, or two halves (one under each column); Across and Split stay at the foot */
    val cast: CastDock = CastDock.OFF,
    val castSize: SlotSize = SlotSize.L,
    /** the controller band at rest: the diamonds or the thin row */
    val rest: HomeRest = HomeRest.DIAMONDS,
    /** the band in fights: grow down (today's, D1's default), the rest size, or the top screen (phase 3: the bottom keeps its rest box
     *  as SAME does, a 1.5 s "up" cue, and the add-on's Wings on the top screen - TopCombatBar) */
    val fight: FightBand = FightBand.GROW,
    /** phase 4: the room bar's XP track on Home (false = hidden there: Home takes its 34 px; every room keeps it) */
    val xpBar: Boolean = true,
    /** phase 4: the room bar's WHOLE order, [Room] names left to right ([RoomBar]); a hidden room keeps its place here */
    val rooms: List<String> = RoomBar.DEFAULT,
    /** 1518 build 2 (the user's ruling 2026-11-21: each room icon is its own app): the rooms taken off the bar with their "-" badge
     *  (they wait in edit mode's "+" spot); never Settings ([RoomBar.hiddenOf]); empty = every room on the bar (Classic) */
    val hidden: List<String> = emptyList(),
    /** 1518 build 3 (the user's ruling 2026-11-21: the top bar's small icons are apps): what each bare D-pad direction of the DS toolbar
     *  opens, positions 1-4 (Left, Up, Right, Down), as SIDE-RELATIVE pieces - the left column's module, the radial, the right column's
     *  module, the Home bar - so a chat / map swap (Select) still carries the D-pad with the columns (SPEC 6 "SYNC"). Classic = [PAD_DEFAULT]
     *  (today's two tables, the mirror bit's off and on, both); a swap in edit mode reorders it ([HomeLayoutSolver.dpadDirs] resolves it) */
    val pad: List<PadKey> = PAD_DEFAULT,
    /** 1518 build 3: the time / battery / gold chip's spot along the band (the diamonds only; the extras row stays on the ZL + ZR join) */
    val clock: ClockPlace = ClockPlace.CENTRE,
) {
    /** a D-pad direction's piece ([pad]): the module in the LEFT / RIGHT column (chat or map, whichever is there), the radial, the Home bar */
    enum class PadKey { LEFT_COL, RADIAL, RIGHT_COL, HOME }
    /** the clock chip's spot: left of the band's centre, the centre (today's), right of it (CastBarPlace.clockChipCx) */
    enum class ClockPlace { LEFT, CENTRE, RIGHT }
    enum class Side { LEFT, RIGHT; fun other() = if (this == LEFT) RIGHT else LEFT }
    enum class Share(val num: Int, val den: Int) { THIRD(1, 3), HALF(1, 2), TWO_THIRDS(2, 3) }
    enum class CardDock { ON_MAP, ABOVE_CHAT, OFF }
    enum class CastDock {
        OFF, UNDER_CHAT, UNDER_MAP, ABOVE_CHAT, ABOVE_MAP, ACROSS, SPLIT;
        /** the bar at the TOP of its column (under the band), not the foot */
        val top: Boolean get() = this == ABOVE_CHAT || this == ABOVE_MAP
        /** the module whose column it sits in (null: off, across or split) */
        val column: HomeModule? get() = when (this) {
            UNDER_CHAT, ABOVE_CHAT -> HomeModule.CHAT
            UNDER_MAP, ABOVE_MAP -> HomeModule.MAP
            else -> null
        }
        /** the same column at the top ([b]) or the foot; a dock with no column is unchanged */
        fun withTop(b: Boolean): CastDock = when (column) {
            HomeModule.CHAT -> if (b) ABOVE_CHAT else UNDER_CHAT
            HomeModule.MAP -> if (b) ABOVE_MAP else UNDER_MAP
            else -> this
        }
        /** the bar in [c]'s column, keeping its edge (from Off, Across or Split: the foot) */
        fun withColumn(c: HomeModule): CastDock = when (c) {
            HomeModule.CHAT -> if (top) ABOVE_CHAT else UNDER_CHAT
            HomeModule.MAP -> if (top) ABOVE_MAP else UNDER_MAP
            else -> this
        }
    }
    /** the tap-to-cast slot's side (L = CastStripModel.MAX_SIDE, today's) */
    enum class SlotSize(val px: Float) { S(88f), M(100f), L(112f) }
    enum class FightBand { GROW, SAME, TOP }

    /** the map is left of the chat (the party module's edge; the D-pad's own order is [pad], resolved by HomeLayoutSolver.dpadDirs) */
    val mirrored: Boolean get() = chatSide == Side.RIGHT
    /** the same layout with chat and map swapped (Select in edit mode) */
    fun swapped(): HomeLayout = copy(chatSide = chatSide.other())

    /** the tap-to-cast bar's GEOMETRIC side for CastStripModel / CastStripView (they keep screen sides: Right rests at its end) */
    val castSide: CastStripModel.Side get() = when (cast) {
        CastDock.OFF -> CastStripModel.Side.OFF
        CastDock.UNDER_CHAT, CastDock.ABOVE_CHAT -> if (chatSide == Side.LEFT) CastStripModel.Side.LEFT else CastStripModel.Side.RIGHT
        CastDock.UNDER_MAP, CastDock.ABOVE_MAP -> if (chatSide == Side.LEFT) CastStripModel.Side.RIGHT else CastStripModel.Side.LEFT
        CastDock.ACROSS, CastDock.SPLIT -> CastStripModel.Side.BOTH
    }

    /** the same placement fields (the legacy name ignored) */
    fun samePlace(o: HomeLayout): Boolean = copy(name = "", v = VERSION) == o.copy(name = "", v = VERSION)

    /** Classic's PLACES (1517's Classic preset held these): chat left at half, no Split bar, no bar above its column, no top-screen fight.
     *  The LB picture and the D-pad toast keep 1516's spots on such a record (HomeView.classicLook), pixel for pixel */
    val classicPlace: Boolean get() = chatSide == Side.LEFT && chatShare == Share.HALF && cast != CastDock.SPLIT && !cast.top &&
        fight != FightBand.TOP

    /**
     * Settings' "Reset Home to Classic" (1518, this character): 1517's Classic preset's fields (chat left at half, the card on the map,
     * the diamonds, L slots, a Split bar back under the chat and a bar above its column back at its foot, a top-screen fight back to
     * Grow - the bar's own column and Same size stay the player's, as Classic always kept them), plus today's room bar (its order, every
     * room on it - none hidden - and the XP track shown)
     */
    fun classic(): HomeLayout = copy(name = LEGACY_CLASSIC, chatSide = Side.LEFT, chatShare = Share.HALF, card = CardDock.ON_MAP,
        rest = HomeRest.DIAMONDS, castSize = SlotSize.L,
        cast = if (cast == CastDock.SPLIT) CastDock.UNDER_CHAT else cast.withTop(false),
        fight = if (fight == FightBand.TOP) FightBand.GROW else fight, xpBar = true, rooms = RoomBar.DEFAULT, hidden = emptyList(),
        pad = PAD_DEFAULT, clock = ClockPlace.CENTRE)

    /** the D-pad order with the pieces at positions [a] and [b] (1-4) swapped (edit mode's drag / A-then-D-pad of a toolbar icon) */
    fun padSwapped(a: Int, b: Int): HomeLayout {
        if (a !in 1..4 || b !in 1..4 || a == b) return this
        val p = pad.toMutableList()
        val t = p[a - 1]; p[a - 1] = p[b - 1]; p[b - 1] = t
        return copy(pad = p)
    }
    /** the D-pad order is one the add-on's mirror bit says alone (today's two tables: page-3 codes 20 / 21 reach every add-on) */
    val padSimple: Boolean get() = pad == PAD_DEFAULT || pad == PAD_FLIPPED

    companion object {
        const val VERSION = 1
        /** the legacy [name] a new and a migrated record carry (1517's Classic preset's; the JSON text of a Classic record unchanged) */
        const val LEGACY_CLASSIC = "Classic"
        /** Classic's D-pad: Left = the left column's module, Up = the radial, Right = the right column's, Down = the Home bar */
        val PAD_DEFAULT = listOf(PadKey.LEFT_COL, PadKey.RADIAL, PadKey.RIGHT_COL, PadKey.HOME)
        /** Left and Right crossed (the mirror bit's other table whatever side the chat is on) */
        val PAD_FLIPPED = listOf(PadKey.RIGHT_COL, PadKey.RADIAL, PadKey.LEFT_COL, PadKey.HOME)
        /** a read D-pad order: the four pieces once each, else Classic's */
        fun padOf(words: List<String>): List<PadKey> {
            val p = words.mapNotNull { w -> PadKey.values().firstOrNull { it.name == w } }
            return if (p.size == 4 && p.toSet().size == 4) p else PAD_DEFAULT
        }
        /** a geometric side picked by an older route (Settings' old Left / Right, "Turn on" = Both) -> the dock it means NOW; [keepTop]
         *  keeps a bar that was above its column above the new one */
        fun dockOf(side: CastStripModel.Side, chatSide: Side, keepTop: Boolean = false): CastDock = when (side) {
            CastStripModel.Side.OFF -> CastDock.OFF
            CastStripModel.Side.BOTH -> CastDock.ACROSS
            CastStripModel.Side.LEFT -> if (chatSide == Side.LEFT) CastDock.UNDER_CHAT else CastDock.UNDER_MAP
            CastStripModel.Side.RIGHT -> if (chatSide == Side.LEFT) CastDock.UNDER_MAP else CastDock.UNDER_CHAT
        }.withTop(keepTop)
    }
}

/** the record as JSON in the "home" prefs; tolerant: anything unreadable (bad JSON, a future version, unknown words) -> [fallback] */
object HomeLayoutJson {
    fun json(l: HomeLayout): String = JSONObject()
        .put("v", l.v).put("name", l.name).put("chat", l.chatSide.name).put("share", l.chatShare.name).put("card", l.card.name)
        .put("cast", l.cast.name).put("size", l.castSize.name).put("rest", l.rest.name).put("fight", l.fight.name).put("xp", l.xpBar)
        // phase 4: the room bar's order only when it is not today's (a Classic record's text is unchanged)
        .also { o -> if (!RoomBar.isDefault(l.rooms)) o.put("rooms", org.json.JSONArray(RoomBar.normalize(l.rooms))) }
        // 1518 build 2: the hidden rooms only when there are some (a Classic record's text is unchanged)
        .also { o -> RoomBar.hiddenOf(l.hidden).takeIf { it.isNotEmpty() }?.let { o.put("hide", org.json.JSONArray(it)) } }
        // 1518 build 3: the D-pad order and the clock's spot only when they are not Classic's (a Classic record's text is unchanged)
        .also { o -> HomeLayout.padOf(l.pad.map { it.name }).takeIf { it != HomeLayout.PAD_DEFAULT }?.let { p -> o.put("pad", org.json.JSONArray(p.map { it.name })) } }
        .also { o -> if (l.clock != HomeLayout.ClockPlace.CENTRE) o.put("clock", l.clock.name) }
        .toString()

    fun parse(s: String?, fallback: HomeLayout = HomeLayout()): HomeLayout {
        if (s.isNullOrBlank()) return fallback
        return try {
            val o = JSONObject(s)
            val v = o.optInt("v", -1)
            if (v < 1 || v > HomeLayout.VERSION) return fallback
            fun <E : Enum<E>> en(values: Array<E>, key: String, def: E): E = o.optString(key, "").let { w -> values.firstOrNull { it.name == w } } ?: def
            val d = HomeLayout()
            HomeLayout(
                v = v,
                name = o.optString("name", d.name).take(24),
                chatSide = en(HomeLayout.Side.values(), "chat", d.chatSide),
                chatShare = en(HomeLayout.Share.values(), "share", d.chatShare),
                card = en(HomeLayout.CardDock.values(), "card", d.card),
                cast = en(HomeLayout.CastDock.values(), "cast", d.cast),
                castSize = en(HomeLayout.SlotSize.values(), "size", d.castSize),
                rest = en(HomeRest.values(), "rest", d.rest),
                fight = en(HomeLayout.FightBand.values(), "fight", d.fight),
                xpBar = o.optBoolean("xp", d.xpBar),
                rooms = o.optJSONArray("rooms")?.let { a -> RoomBar.normalize((0 until a.length()).map { a.optString(it, "") }) } ?: d.rooms,
                hidden = o.optJSONArray("hide")?.let { a -> RoomBar.hiddenOf((0 until a.length()).map { a.optString(it, "") }) } ?: d.hidden,
                pad = o.optJSONArray("pad")?.let { a -> HomeLayout.padOf((0 until a.length()).map { a.optString(it, "") }) } ?: d.pad,
                clock = en(HomeLayout.ClockPlace.values(), "clock", d.clock),
            )
        } catch (e: Exception) { fallback }
    }
}

/**
 * MIGRATION (SPEC section 9, the first run of the new build): the default and every character start as "Classic", built from today's
 * app-wide keys: rest -> rest; combatGrow true / false -> GROW / SAME; questOverMap -> ON_MAP / ABOVE_CHAT; castBarSide (the old
 * castStrip On = Left) -> UNDER_CHAT / UNDER_MAP / ACROSS / OFF (today's chat is on the left); slot size L; chat Left 1/2.
 */
object HomeLayoutMigration {
    fun fromToday(rest: HomeRest, combatGrow: Boolean, questOverMap: Boolean, castSide: CastStripModel.Side): HomeLayout = HomeLayout(
        name = HomeLayout.LEGACY_CLASSIC, chatSide = HomeLayout.Side.LEFT, chatShare = HomeLayout.Share.HALF,
        card = if (questOverMap) HomeLayout.CardDock.ON_MAP else HomeLayout.CardDock.ABOVE_CHAT,
        cast = HomeLayout.dockOf(castSide, HomeLayout.Side.LEFT), castSize = HomeLayout.SlotSize.L, rest = rest,
        fight = if (combatGrow) HomeLayout.FightBand.GROW else HomeLayout.FightBand.SAME)
}
