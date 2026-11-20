package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * MOVABLE HOME, THE LAYOUT RECORD (SPEC.txt "Live docks", section 13; phase 1). Home is a set of DOCKS that this small record fills;
 * [HomeLayoutSolver] turns it into rects, [HomeView.apply]'s one motion glides between two of them. Pure (no Android types), so the JVM
 * tests enumerate every record against every state (HomeLayoutRulesTest).
 *
 * The rulings it carries (the user, 2026-11-20, on DECISIONS.txt):
 *  - D1: in fights the band GROWs by default (today's); "Top screen" is opt-in only, "Same size" stays.
 *  - D5: the active layout is PER CHARACTER, with an "All characters" button ([HomeLayoutStore]).
 *  - D8: after a swap LB+LT slides the LEFT column, whatever it holds (Home's slides are by position, see HomeView.pauseSlide).
 * P8 "Classic = today": the record migrated from today's keys ([HomeLayoutMigration]) lays Home exactly as before (HomePlacesGoldenTest).
 */
data class HomeLayout(
    val v: Int = VERSION,
    /** the preset or slot it was last saved as ("Classic", "Mirrored", ..., "1".."3"); informative only: [HomePresets.nameOf] names it */
    val name: String = HomePresets.CLASSIC,
    /** which column the chat takes; the map takes the other */
    val chatSide: Side = Side.LEFT,
    /** the chat column's share of the inner width (the map takes the rest) */
    val chatShare: Share = Share.HALF,
    /** the focused quest's card: over the map (under its zone band), at the top of the chat's column, or not on Home */
    val card: CardDock = CardDock.ON_MAP,
    /** the tap-to-cast bar: off, under the chat, under the map, one bar across both, or two halves (one under each column) */
    val cast: CastDock = CastDock.OFF,
    val castSize: SlotSize = SlotSize.L,
    /** the controller band at rest: the diamonds or the thin row */
    val rest: HomeRest = HomeRest.DIAMONDS,
    /** the band in fights: grow down (today's, D1's default), the rest size, or the top screen (phase 3: the bottom keeps its rest box
     *  as SAME does, a 1.5 s "up" cue, and the add-on's Wings on the top screen - TopCombatBar) */
    val fight: FightBand = FightBand.GROW,
    /** phase 4: the room bar's XP track (kept in the record now so a phase-4 build reads older records) */
    val xpBar: Boolean = true,
) {
    enum class Side { LEFT, RIGHT; fun other() = if (this == LEFT) RIGHT else LEFT }
    enum class Share(val num: Int, val den: Int) { THIRD(1, 3), HALF(1, 2), TWO_THIRDS(2, 3) }
    enum class CardDock { ON_MAP, ABOVE_CHAT, OFF }
    enum class CastDock { OFF, UNDER_CHAT, UNDER_MAP, ACROSS, SPLIT }
    /** the tap-to-cast slot's side (L = CastStripModel.MAX_SIDE, today's) */
    enum class SlotSize(val px: Float) { S(88f), M(100f), L(112f) }
    enum class FightBand { GROW, SAME, TOP }

    /** the map is left of the chat: the add-on's DS toolbar mirror bit (D-pad Left = the map, Right = the chat) */
    val mirrored: Boolean get() = chatSide == Side.RIGHT
    /** the same layout with chat and map swapped (Select in edit mode, the Home layout tile's swap) */
    fun swapped(): HomeLayout = copy(chatSide = chatSide.other())

    /** the tap-to-cast bar's GEOMETRIC side for CastStripModel / CastStripView (they keep screen sides: Right rests at its end) */
    val castSide: CastStripModel.Side get() = when (cast) {
        CastDock.OFF -> CastStripModel.Side.OFF
        CastDock.UNDER_CHAT -> if (chatSide == Side.LEFT) CastStripModel.Side.LEFT else CastStripModel.Side.RIGHT
        CastDock.UNDER_MAP -> if (chatSide == Side.LEFT) CastStripModel.Side.RIGHT else CastStripModel.Side.LEFT
        CastDock.ACROSS, CastDock.SPLIT -> CastStripModel.Side.BOTH
    }

    /** the same placement fields (the name ignored): a slot or a preset holds this layout */
    fun samePlace(o: HomeLayout): Boolean = copy(name = "", v = VERSION) == o.copy(name = "", v = VERSION)

    companion object {
        const val VERSION = 1
        /** a geometric side picked by an older route (Settings' old Left / Right, "Turn on" = Both) -> the dock it means NOW */
        fun dockOf(side: CastStripModel.Side, chatSide: Side): CastDock = when (side) {
            CastStripModel.Side.OFF -> CastDock.OFF
            CastStripModel.Side.BOTH -> CastDock.ACROSS
            CastStripModel.Side.LEFT -> if (chatSide == Side.LEFT) CastDock.UNDER_CHAT else CastDock.UNDER_MAP
            CastStripModel.Side.RIGHT -> if (chatSide == Side.LEFT) CastDock.UNDER_MAP else CastDock.UNDER_CHAT
        }
    }
}

/** the record as JSON in the "home" prefs; tolerant: anything unreadable (bad JSON, a future version, unknown words) -> [fallback] */
object HomeLayoutJson {
    fun json(l: HomeLayout): String = JSONObject()
        .put("v", l.v).put("name", l.name).put("chat", l.chatSide.name).put("share", l.chatShare.name).put("card", l.card.name)
        .put("cast", l.cast.name).put("size", l.castSize.name).put("rest", l.rest.name).put("fight", l.fight.name).put("xp", l.xpBar)
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
            )
        } catch (e: Exception) { fallback }
    }

    /** the three device slots ("homeLayoutSlots"): a JSON array of records or nulls, always 3 long */
    fun slots(s: String?): List<HomeLayout?> {
        val out = arrayOfNulls<HomeLayout>(HomePresets.SLOTS).toMutableList()
        if (s.isNullOrBlank()) return out
        try {
            val a = org.json.JSONArray(s)
            for (i in 0 until minOf(a.length(), HomePresets.SLOTS)) {
                val o = a.optJSONObject(i) ?: continue
                out[i] = parse(o.toString(), fallback = HomeLayout(name = "?")).takeIf { it.name != "?" }
            }
        } catch (_: Exception) {}
        return out
    }
    fun slotsJson(slots: List<HomeLayout?>): String {
        val a = org.json.JSONArray()
        for (i in 0 until HomePresets.SLOTS) a.put(slots.getOrNull(i)?.let { JSONObject(json(it)) } ?: JSONObject.NULL)
        return a.toString()
    }
}

/**
 * MIGRATION (SPEC section 9, the first run of the new build): the default and every character start as "Classic", built from today's
 * app-wide keys: rest -> rest; combatGrow true / false -> GROW / SAME; questOverMap -> ON_MAP / ABOVE_CHAT; castBarSide (the old
 * castStrip On = Left) -> UNDER_CHAT / UNDER_MAP / ACROSS / OFF (today's chat is on the left); slot size L; chat Left 1/2.
 */
object HomeLayoutMigration {
    fun fromToday(rest: HomeRest, combatGrow: Boolean, questOverMap: Boolean, castSide: CastStripModel.Side): HomeLayout = HomeLayout(
        name = HomePresets.CLASSIC, chatSide = HomeLayout.Side.LEFT, chatShare = HomeLayout.Share.HALF,
        card = if (questOverMap) HomeLayout.CardDock.ON_MAP else HomeLayout.CardDock.ABOVE_CHAT,
        cast = HomeLayout.dockOf(castSide, HomeLayout.Side.LEFT), castSize = HomeLayout.SlotSize.L, rest = rest,
        fight = if (combatGrow) HomeLayout.FightBand.GROW else HomeLayout.FightBand.SAME)
}

/**
 * THE PRESETS (SPEC section 9; code constants). Each one sets only its own fields on the current record ([apply]); the rest (the bar, the
 * rest look, the fight mode where it says nothing) stays the player's. [nameOf] names a record by the first preset whose fields it holds
 * (Classic last: chat left at half), else "Custom".
 */
object HomePresets {
    const val CLASSIC = "Classic"
    const val MIRRORED = "Mirrored"
    const val BIG_MAP = "Big map"
    const val BIG_CHAT = "Big chat"
    const val COMPACT = "Compact"
    const val FIGHTER = "Fighter"
    const val CUSTOM = "Custom"
    /** the device-wide slots of "Mine" (named "1", "2", "3"; typed names are v2) */
    const val SLOTS = 3

    /** [folded] = the card's fold (app-wide HomeSettings.questMin) the preset asks for, null = left as it is; [available] = selectable now
     *  (Fighter needs "In fights: Top screen", phase 3) */
    class Preset(val id: String, val folded: Boolean? = null, val available: Boolean = true,
                 val apply: (HomeLayout) -> HomeLayout, val holds: (HomeLayout) -> Boolean)

    private val L = HomeLayout.Side.LEFT
    private val R = HomeLayout.Side.RIGHT

    val ALL: List<Preset> = listOf(
        Preset(CLASSIC, apply = { it.copy(name = CLASSIC, chatSide = L, chatShare = HomeLayout.Share.HALF, card = HomeLayout.CardDock.ON_MAP) },
            holds = { it.chatSide == L && it.chatShare == HomeLayout.Share.HALF && it.cast != HomeLayout.CastDock.SPLIT }),
        Preset(MIRRORED, apply = { it.copy(name = MIRRORED, chatSide = R, chatShare = HomeLayout.Share.HALF, card = HomeLayout.CardDock.ON_MAP,
                cast = HomeLayout.CastDock.UNDER_CHAT) },
            holds = { it.chatSide == R && it.chatShare == HomeLayout.Share.HALF && it.card == HomeLayout.CardDock.ON_MAP && it.cast == HomeLayout.CastDock.UNDER_CHAT }),
        Preset(BIG_MAP, apply = { it.copy(name = BIG_MAP, chatSide = L, chatShare = HomeLayout.Share.THIRD, card = HomeLayout.CardDock.ABOVE_CHAT,
                cast = HomeLayout.CastDock.UNDER_MAP) },
            holds = { it.chatSide == L && it.chatShare == HomeLayout.Share.THIRD && it.card == HomeLayout.CardDock.ABOVE_CHAT && it.cast == HomeLayout.CastDock.UNDER_MAP }),
        Preset(BIG_CHAT, apply = { it.copy(name = BIG_CHAT, chatSide = L, chatShare = HomeLayout.Share.TWO_THIRDS, card = HomeLayout.CardDock.ON_MAP,
                cast = HomeLayout.CastDock.UNDER_CHAT) },
            holds = { it.chatSide == L && it.chatShare == HomeLayout.Share.TWO_THIRDS && it.card == HomeLayout.CardDock.ON_MAP && it.cast == HomeLayout.CastDock.UNDER_CHAT }),
        Preset(COMPACT, folded = true, apply = { it.copy(name = COMPACT, rest = HomeRest.ROW, cast = HomeLayout.CastDock.ACROSS, castSize = HomeLayout.SlotSize.M) },
            holds = { it.rest == HomeRest.ROW && it.cast == HomeLayout.CastDock.ACROSS && it.castSize == HomeLayout.SlotSize.M }),
        // phase 3: selectable now the top-screen combat bar ships (its Split shows as one bar across until Split ships)
        Preset(FIGHTER, folded = true,
            apply = { it.copy(name = FIGHTER, fight = HomeLayout.FightBand.TOP, cast = HomeLayout.CastDock.SPLIT) },
            holds = { it.fight == HomeLayout.FightBand.TOP && it.cast == HomeLayout.CastDock.SPLIT }),
    )
    /** the presets the picker offers in this build (phase 3: Fighter too, the player's own opt-in to the top-screen bar, D1) */
    val OFFERED: List<Preset> get() = ALL.filter { it.available }

    fun byId(id: String): Preset? = ALL.firstOrNull { it.id == id }

    /** the record's name: the first specific preset it holds, Classic, else Custom */
    fun nameOf(l: HomeLayout): String = (ALL.drop(1) + ALL.first()).firstOrNull { it.holds(l) }?.id ?: CUSTOM
}
