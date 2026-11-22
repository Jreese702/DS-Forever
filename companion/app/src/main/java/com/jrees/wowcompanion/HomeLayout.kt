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
    /** after 1518, the user's ruling 2026-11-22 ("Why can't I move the party mode or the tap to cast to the top?"): the party list (Party
     *  mode's module) at the top or the foot of the chat's or the map's column (it names a module, so a swap carries it); Above chat =
     *  today's (Classic) */
    val party: PartyDock = PartyDock.ABOVE_CHAT,
) {
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
    /** the party list's dock: the top or the foot of the chat's or the map's column (its Select tab on that column's outer screen edge) */
    enum class PartyDock {
        ABOVE_CHAT, UNDER_CHAT, ABOVE_MAP, UNDER_MAP;
        /** at the TOP of its column (under the band, and under a bar above that column), not the foot */
        val top: Boolean get() = this == ABOVE_CHAT || this == ABOVE_MAP
        /** the module whose column it sits in */
        val column: HomeModule get() = if (this == ABOVE_CHAT || this == UNDER_CHAT) HomeModule.CHAT else HomeModule.MAP
        /** the same column at the top ([b]) or the foot */
        fun withTop(b: Boolean): PartyDock = of(column, b)
        /** [c]'s column (the chat's or the map's; the band: unchanged), keeping its edge */
        fun withColumn(c: HomeModule): PartyDock = if (c == HomeModule.BARS) this else of(c, top)
        companion object {
            fun of(c: HomeModule, top: Boolean): PartyDock = if (c == HomeModule.MAP) (if (top) ABOVE_MAP else UNDER_MAP)
                else (if (top) ABOVE_CHAT else UNDER_CHAT)
        }
    }
    /** the tap-to-cast slot's side (L = CastStripModel.MAX_SIDE, today's) */
    enum class SlotSize(val px: Float) { S(88f), M(100f), L(112f) }
    enum class FightBand { GROW, SAME, TOP }

    /** the map is left of the chat: the add-on's DS toolbar mirror bit (D-pad Left = the map, Right = the chat) */
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

    /** Classic's PLACES (1517's Classic preset held these): chat left at half, no Split bar, no bar above its column, no top-screen fight,
     *  the party list above the chat. The LB picture and the D-pad toast keep 1516's spots on such a record (HomeView.classicLook), pixel
     *  for pixel */
    val classicPlace: Boolean get() = chatSide == Side.LEFT && chatShare == Share.HALF && cast != CastDock.SPLIT && !cast.top &&
        fight != FightBand.TOP && party == PartyDock.ABOVE_CHAT

    /**
     * Settings' "Reset Home to Classic" (1518, this character): 1517's Classic preset's fields (chat left at half, the card on the map,
     * the diamonds, L slots, a Split bar back under the chat and a bar above its column back at its foot, a top-screen fight back to
     * Grow - the bar's own column and Same size stay the player's, as Classic always kept them), plus today's room bar (its order, every
     * room on it - none hidden - and the XP track shown) and the party list back above the chat
     */
    fun classic(): HomeLayout = copy(name = LEGACY_CLASSIC, chatSide = Side.LEFT, chatShare = Share.HALF, card = CardDock.ON_MAP,
        rest = HomeRest.DIAMONDS, castSize = SlotSize.L,
        cast = if (cast == CastDock.SPLIT) CastDock.UNDER_CHAT else cast.withTop(false),
        fight = if (fight == FightBand.TOP) FightBand.GROW else fight, xpBar = true, rooms = RoomBar.DEFAULT, hidden = emptyList(),
        party = PartyDock.ABOVE_CHAT)

    companion object {
        const val VERSION = 1
        /** the legacy [name] a new and a migrated record carry (1517's Classic preset's; the JSON text of a Classic record unchanged) */
        const val LEGACY_CLASSIC = "Classic"
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
        // after 1518: the party list's dock only when it is not above the chat (a Classic record's text is unchanged)
        .also { o -> if (l.party != HomeLayout.PartyDock.ABOVE_CHAT) o.put("party", l.party.name) }
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
                party = en(HomeLayout.PartyDock.values(), "party", d.party),
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
