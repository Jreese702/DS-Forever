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
    /** EDIT TABS (the user, 2026-11-24: "I dont like the sizes for the tap to cast bar and I kind of want tor remove that but in party and
     *  combat mode I DO want an option to gro those options to fill the space of the bar better"; the pick "8 slots, fill the width"):
     *  the S / M / L sizes are gone (every bar draws today's L); a FIGHT tab's "Slots: Grow" fills the bar's width instead ([growSide]).
     *  The PAGE's value ([forTab]): the Out of combat tab never grows; the record keeps each fight tab's own ([combat] / [partyTab]) */
    val grow: Boolean = false,
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
    /** feat/extras-polish (the user, 2026-11-23: "I also want to be able to change the order on these"): the clock's extras row's WHOLE
     *  order, ClockBand.Kind keys left to right (ClockBand.order: a hidden or data-less extra keeps its place in it); dragged in Edit
     *  Home as the room bar's icons are. Which extras are ON stays Settings' (app-wide); the order is this character's. Classic = today's */
    val extras: List<String> = ClockBand.DEFAULT,
    /** THE CAST BAR IN A TOP-SCREEN FIGHT (the user's ruling 2026-11-23: "When the controller moves to the top, the cast bar should still
     *  show across the top of the bottom screen. We can add a setting ... to have the cast bar be on the top or the bottom ... Same thing
     *  with the swing timer"): with In fights = Top screen, where the cast bar and the swing timer go in the fight - BOTTOM (the default):
     *  our strip across the top of the bottom screen (HomeLayoutSolver.fightStrip); TOP: the game's own casting bar shown again on the
     *  top screen and the Wings' swing lines (the add-on, TopCombatBar.castTop). Meaningless with Grow / Same size */
    val fightCast: FightCast = FightCast.BOTTOM,
    /** PARTY SIZING (the user, 2026-11-23: "We should add some dynamic sizing options to the party mode windows, so if it goes while in
     *  combat like the combat bar or only in party mode and then if the controlls are on the top or bottom screens"; the picks: Player
     *  picks, all three; Settings + Edit Home): when the party list grows ([partyGrow]: Off / In fights - today's / Whenever Party mode is
     *  on) and its size in a fight ([partySize]). Per character (the record). The defaults lay every page exactly as before:
     *  PartyLayout.sizedH */
    val partyGrow: PartyGrow = PartyGrow.FIGHTS,
    /** ONE SETTING (the user, test 1523, 2026-11-23: "I dont understand why there is controller on top vs controller on bottom settings,
     *  just consolidate that into one setting"; the pick "Grow / Same size"): "Size in fights" - GROW (today's): the list uses whatever
     *  room the fight frees - all of the strip the band frees with the controller on the top screen (ruling C's fill), a Party fight's
     *  88 px rows with it staying on the bottom screen; SAME: the list keeps its rest size in every fight. (1523's two rows "Controller
     *  on top / on bottom (fights)" - their Grow a bit / Shrink to make room gone - are read once into it: [HomeLayoutJson.parse]) */
    val partySize: PartySize = PartySize.GROW,
    /** EDIT TABS (the user, 2026-11-24: "tabs to the top of edit mode for out of combat, in combat and party mode ... have custom layouts
     *  for each mode"; the pick "Fully separate": each tab keeps its own places, changing one never changes another): the In combat tab's
     *  own places ([TabPlaces]; null = still a copy of the Out of combat places above - nothing of its own yet). Its Combat bars and Cast
     *  bar are [fight] / [fightCast] (today's In fights, so an older build reads them) */
    val combat: TabPlaces? = null,
    /** the Party mode tab's own places (null = a copy of the Out of combat places) */
    val partyTab: TabPlaces? = null,
    /** the Party mode tab's Combat bars (the user, 2026-11-24: "the party mode tab should kind of operate similar to the combat mode tab
     *  ... having settings to like grow or got to top screen"); null = what Party mode did before the tabs: Top screen when In fights is
     *  Top screen, else No grow (R0: Party mode never grew the band) */
    val partyFight: FightBand? = null,
    /** the Party mode tab's Cast bar (Top screen fights); null = the In combat tab's ([fightCast]) */
    val partyCast: FightCast? = null,
    /** a PAGE's flag (never in a record: [forTab] sets it, [canonical] clears it, JSON never writes it): the Party mode tab's Combat bars
     *  is its own Bottom screen - its fight grows the band (HomeLayoutSolver.big: R0 lifted for this pick only; a record's own Grow laid
     *  with Party mode on keeps R0, as before the tabs) */
    val partyBand: Boolean = false,
    /** RULING B (the user's pick 2026-11-25, "Keep my pick here too (Recommended)": anything set on the Party mode tab stays its own,
     *  even when it matches Out of combat): the Party mode tab's places ([partyTab]) were SET on that tab (Edit Home's Party mode tab,
     *  [withTab] of that tab with its places changed) - Party mode's own from then on, never folded into "follows Out of combat"
     *  ([canonical]) even when they equal Out of combat's, never carried along by Settings' bar (HomeSettings.barEverywhere). A copy
     *  another tab's change made ("Fully separate": [withTab] keeps a following tab where it was) is not a pick: it folds back into
     *  following once it equals Out of combat's places again, as before. False with [partyTab] null; Reset Home to Classic clears it */
    val partyPicked: Boolean = false,
) {
    /**
     * A FIGHT TAB'S OWN PLACES (edit tabs, 2026-11-24): the page's places (chat side and width, the quest card, the tap-to-cast bar and
     * the party list's docks), the bar's "Slots: Grow" ([grow]) and the clock's badges shown on that tab ([badges]: the extras' keys,
     * ClockBand's and "gold"; null = the ones Settings > Clock band turns on, as the Out of combat tab shows them)
     */
    data class TabPlaces(val chatSide: Side = Side.LEFT, val chatShare: Share = Share.HALF, val card: CardDock = CardDock.ON_MAP,
                         val cast: CastDock = CastDock.OFF, val party: PartyDock = PartyDock.ABOVE_CHAT, val grow: Boolean = false,
                         val badges: List<String>? = null) {
        companion object {
            /** [l]'s Out of combat places as a fight tab's (a fresh copy: no Grow, the badges as Settings has them) */
            fun of(l: HomeLayout): TabPlaces = TabPlaces(l.chatSide, l.chatShare, l.card, l.cast, l.party)
        }
    }

    /** what one tab owns ([own] / [withOwn]): its places, and (the fight tabs) its Combat bars and Cast bar */
    data class Own(val places: TabPlaces, val fight: FightBand?, val fightCast: FightCast?)

    /** the Party mode tab's Combat bars as the page lays them */
    val partyFightNow: FightBand get() = partyFight ?: if (fight == FightBand.TOP) FightBand.TOP else FightBand.SAME
    /** the Party mode tab's Cast bar */
    val partyCastNow: FightCast get() = partyCast ?: fightCast

    /** [t]'s own values (a null copy read as the Out of combat places it still is) */
    fun own(t: HomeTab): Own = when (t) {
        HomeTab.OUT -> Own(TabPlaces.of(this), null, null)
        HomeTab.COMBAT -> Own(combat ?: TabPlaces.of(this), fight, fightCast)
        HomeTab.PARTY -> Own(partyTab ?: TabPlaces.of(this), partyFightNow, partyCastNow)
    }

    /** [t]'s own values set to [o] (raw: nothing else pinned; [withTab] is the editor's way) */
    fun withOwn(t: HomeTab, o: Own): HomeLayout = when (t) {
        HomeTab.OUT -> copy(chatSide = o.places.chatSide, chatShare = o.places.chatShare, card = o.places.card, cast = o.places.cast,
            party = o.places.party)
        HomeTab.COMBAT -> copy(combat = o.places, fight = o.fight ?: fight, fightCast = o.fightCast ?: fightCast)
        // "KEEP MY PICK" (the user's ruling, 2026-11-25): a Combat bars / Cast bar that still follows In combat (null) stays following
        // when [o] carries what it follows now (every road that writes the tab's places back reads it through [own]); anything else is
        // Party mode's own - kept, even when it equals In combat's ([canonical] never folds it)
        // (its places: [canonical] folds a copy equal to Out of combat's back into following - never a pick, [partyPicked])
        HomeTab.PARTY -> copy(partyTab = o.places,
            partyFight = if (o.fight == null || (partyFight == null && o.fight == partyFightNow)) partyFight else o.fight,
            partyCast = if (o.fightCast == null || (partyCast == null && o.fightCast == partyCastNow)) partyCast else o.fightCast)
    }.canonical()

    /** the Party mode tab's Combat bars ([fight]) and / or Cast bar ([cast]) as they show now, made Party mode's own (Edit Home's pick on
     *  that tab: a pick of the value it followed is a pick too - "Keep my pick") */
    fun pinParty(fight: Boolean, cast: Boolean): HomeLayout =
        copy(partyFight = if (fight) partyFightNow else partyFight, partyCast = if (cast) partyCastNow else partyCast)

    /**
     * THE PAGE OF TAB [t]: the record as the solver lays it while that tab is Home's - the shared fields as they are, the tab's own places
     * over them (the Out of combat tab: the record's own; it never grows its slots), the Party mode tab's Combat bars and Cast bar as
     * [fight] / [fightCast]. The page's [combat] / [partyTab] stay the record's (a page written back is [withTab]'s)
     */
    fun forTab(t: HomeTab): HomeLayout {
        if (t == HomeTab.OUT) return if (grow || partyBand) copy(grow = false, partyBand = false) else this
        val o = own(t)
        val p = o.places
        return copy(chatSide = p.chatSide, chatShare = p.chatShare, card = p.card, cast = p.cast, party = p.party, grow = p.grow,
            fight = o.fight ?: fight, fightCast = o.fightCast ?: fightCast, partyBand = t == HomeTab.PARTY && o.fight == FightBand.GROW)
    }

    /**
     * EDIT TAB [t]'s PAGE [page] WRITTEN BACK (the user's pick 2026-11-24, "Fully separate": a change on one tab never changes another):
     * the shared fields from [page] (the rest look, the room bar, the extras' order, the party list's sizing), [t]'s own from [page]; any
     * OTHER tab whose values that would change (a fight tab still a copy of the Out of combat places, or Party mode's Combat bars still
     * following In combat's) keeps what it had - its copy is made now
     */
    fun withTab(t: HomeTab, page: HomeLayout): HomeLayout {
        val before = HomeTab.values().associateWith { own(it) }
        val mine = Own(TabPlaces(page.chatSide, page.chatShare, page.card, page.cast, page.party, page.grow && t != HomeTab.OUT,
            (if (t == HomeTab.OUT) null else own(t).places.badges)),
            if (t == HomeTab.OUT) null else page.fight, if (t == HomeTab.OUT) null else page.fightCast)
        // RULING B (2026-11-25, "Keep my pick here too"): the Party mode tab's places changed on that tab are its pick ([partyPicked])
        val picked = partyPicked || (t == HomeTab.PARTY && mine.places != before.getValue(HomeTab.PARTY).places)
        var r = copy(rest = page.rest, xpBar = page.xpBar, rooms = page.rooms, hidden = page.hidden, extras = page.extras,
            partyGrow = page.partyGrow, partySize = page.partySize, partyPicked = picked).withOwn(t, mine)
        for (o in HomeTab.values()) if (o != t && r.own(o) != before.getValue(o)) r = r.withOwn(o, before.getValue(o))
        return r
    }

    /** [f] applied to tab [t]'s page and written back ([withTab]) */
    fun onTab(t: HomeTab, f: (HomeLayout) -> HomeLayout): HomeLayout = withTab(t, f(forTab(t)))

    /** tab [t]'s badges (null = the ones Settings > Clock band turns on: the Out of combat tab's always) */
    fun badges(t: HomeTab): List<String>? = if (t == HomeTab.OUT) null else own(t).places.badges
    /** tab [t]'s badges set to [b] (a fight tab only; nothing else changes) */
    fun withBadges(t: HomeTab, b: List<String>?): HomeLayout =
        if (t == HomeTab.OUT) this else withOwn(t, own(t).let { it.copy(places = it.places.copy(badges = b)) })

    /** one form for one meaning: the In combat tab that is the Out of combat places' own copy (nothing of its own) is null. Party
     *  mode's Combat bars / Cast bar are NOT folded: one set on its tab is Party mode's own even when it equals what it would follow
     *  (the user's ruling 2026-11-25, "Keep my pick": "Anything you set on the Party mode tab stays its own, even when it happens to
     *  match In combat. Settings > Home changes In combat only." - folded, a later Settings > Home change carried it along); null =
     *  never set, follows In combat. RULING B (the user's pick 2026-11-25, "Keep my pick here too (Recommended)"): nor are its PLACES
     *  once SET on its tab ([partyPicked]) - Party mode's own even when they equal Out of combat's (folded, a later Out of combat change
     *  in Settings carried them along); a copy another tab's change made folds as before. null = follows Out of combat */
    fun canonical(): HomeLayout {
        val base = TabPlaces.of(this)
        val c = combat?.takeIf { it != base }
        val p = partyTab?.takeIf { it != base || partyPicked }
        val pk = partyPicked && p != null
        return if (c === combat && p === partyTab && pk == partyPicked && !grow && !partyBand) this
            else copy(combat = c, partyTab = p, partyPicked = pk, grow = false, partyBand = false)
    }

    /** every tab's bar at dock [d] (the one-time question's Yes, the Controller room's Turn on: "the bar on in all three tabs at the same
     *  place") */
    fun castEverywhere(d: CastDock): HomeLayout = HomeTab.values().fold(this) { r, t ->
        r.withOwn(t, r.own(t).let { it.copy(places = it.places.copy(cast = d)) }) }
    /** "Grows": never (the rows keep the rest's 64 px - a shrink still shrinks), in fights (today's), whenever Party mode is on (the
     *  fight's 88 px rows at rest too) */
    enum class PartyGrow { OFF, FIGHTS, ALWAYS }
    /** "Size in fights": grow into the room the fight frees (today's), or the size out of it */
    enum class PartySize { GROW, SAME }
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
    enum class FightBand { GROW, SAME, TOP }
    /** "Cast bar in fights" (Top-screen fights only): the bottom screen's strip, or the top screen (the game's own bar) */
    enum class FightCast { BOTTOM, TOP }

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
     * room on it - none hidden - and the XP track shown), the party list back above the chat and the clock's extras in today's order
     * (feat/extras-polish; which of them are on is Settings', untouched)
     */
    fun classic(): HomeLayout {
        // EDIT TABS (2026-11-24): all three tabs back to Classic - each fight tab's own bar kept in its column (Off stays Off), its
        // Slots back to Same size, its badges back to Settings' own; Party mode's Combat bars / Cast bar follow In combat's again
        fun bar(c: CastDock) = if (c == CastDock.SPLIT) CastDock.UNDER_CHAT else c.withTop(false)
        fun tab(p: TabPlaces?) = p?.let { TabPlaces(cast = bar(it.cast)) }
        return copy(name = LEGACY_CLASSIC, chatSide = Side.LEFT, chatShare = Share.HALF, card = CardDock.ON_MAP,
            rest = HomeRest.DIAMONDS, cast = bar(cast),
            fight = if (fight == FightBand.TOP) FightBand.GROW else fight, xpBar = true, rooms = RoomBar.DEFAULT, hidden = emptyList(),
            party = PartyDock.ABOVE_CHAT, extras = ClockBand.DEFAULT, fightCast = FightCast.BOTTOM, partyGrow = PartyGrow.FIGHTS,
            partySize = PartySize.GROW, grow = false, combat = tab(combat), partyTab = tab(partyTab), partyFight = null, partyCast = null,
            partyPicked = false)                                          // (ruling B: no pick after a reset - a tab equal to it follows)
            .canonical()
    }

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
        /** EDIT TABS (the review): clock badge [key] shown on a tab whose own list is [mine] - exactly that list when it has one (Edit Home
         *  shows a fight tab's own list whatever Settings says, so the fight shows the same), else Settings' row ([settingsOn]: the Out
         *  of combat tab's badges) */
        fun badgeOn(key: String, mine: List<String>?, settingsOn: Boolean): Boolean = mine?.contains(key) ?: settingsOn
    }
}

/** the record as JSON in the "home" prefs; tolerant: anything unreadable (bad JSON, a future version, unknown words) -> [fallback] */
object HomeLayoutJson {
    fun json(l: HomeLayout, full: Boolean = false): String = JSONObject()
        .put("v", l.v).put("name", l.name).put("chat", l.chatSide.name).put("share", l.chatShare.name).put("card", l.card.name)
        // (the tap-to-cast sizes are gone - edit tabs, 2026-11-24 - and every bar draws L: "size" is still written as L, so a Classic
        // record's text is unchanged and an older build reads L)
        .put("cast", l.cast.name).put("size", "L").put("rest", l.rest.name).put("fight", l.fight.name).put("xp", l.xpBar)
        // phase 4: the room bar's order only when it is not today's (a Classic record's text is unchanged)
        .also { o -> if (!RoomBar.isDefault(l.rooms)) o.put("rooms", org.json.JSONArray(RoomBar.normalize(l.rooms))) }
        // 1518 build 2: the hidden rooms only when there are some (a Classic record's text is unchanged)
        .also { o -> RoomBar.hiddenOf(l.hidden).takeIf { it.isNotEmpty() }?.let { o.put("hide", org.json.JSONArray(it)) } }
        // after 1518: the party list's dock only when it is not above the chat (a Classic record's text is unchanged)
        .also { o -> if (l.party != HomeLayout.PartyDock.ABOVE_CHAT) o.put("party", l.party.name) }
        // feat/extras-polish: the extras' order only when it is not today's (a Classic record's text is unchanged)
        .also { o -> if (!ClockBand.isDefault(l.extras)) o.put("extras", org.json.JSONArray(ClockBand.order(l.extras))) }
        // the cast bar's screen in a Top-screen fight only when it is not the bottom's (a Classic record's text is unchanged)
        .also { o -> if (l.fightCast != HomeLayout.FightCast.BOTTOM) o.put("fcast", l.fightCast.name) }
        // party sizing (2026-11-23) only where it is not today's (a Classic record's text is unchanged)
        .also { o -> if (l.partyGrow != HomeLayout.PartyGrow.FIGHTS) o.put("pgrow", l.partyGrow.name) }
        // (the one "Size in fights"; 1523's "ptop" / "pbot" are never written again: [partySizeOf])
        .also { o -> if (l.partySize != HomeLayout.PartySize.GROW) o.put("psize", l.partySize.name) }
        // EDIT TABS (2026-11-24): each fight tab's own values, flat keys ("c.chat", "p.cast" ...) written only where they differ from
        // what they would be with nothing of their own - a Classic record's text is unchanged, an older build reads the Out of combat
        // tab as the record it always was; [full] writes them all (the store's field-by-field lay-on: HomeLayoutStore.layOn)
        .also { o -> tabs(o, l, full) }
        .toString()

    /** the tabs' flat keys (see [json]). THE DOWNGRADE (the review): an older build reads the Out of combat tab and In combat's Combat bars
     *  / Cast bar as it always did and ignores these keys, but its next save of the record writes them away - the fight tabs read as
     *  copies of the Out of combat places again after an upgrade (the one-time migration does not run twice: HomeLayoutStore's
     *  KEY_TABS_MIGRATED), with Party mode's Combat bars following In combat's (R0). Nothing else of the record is lost */
    private fun tabs(o: JSONObject, l: HomeLayout, full: Boolean) {
        for ((pre, t) in listOf("c." to HomeTab.COMBAT, "p." to HomeTab.PARTY)) {
            // RULING B (2026-11-25, "Keep my pick here too"): a Party mode pick ([HomeLayout.partyPicked]) writes all its places (one
            // equal to Out of combat's has no key that differs) and "p.pick"; a copy only the keys that differ, as before
            val all = full || (t == HomeTab.PARTY && l.partyPicked)
            val own = l.own(t).places
            val base = HomeLayout.TabPlaces.of(l)
            if (all || own.chatSide != base.chatSide) o.put(pre + "chat", own.chatSide.name)
            if (all || own.chatShare != base.chatShare) o.put(pre + "share", own.chatShare.name)
            if (all || own.card != base.card) o.put(pre + "card", own.card.name)
            if (all || own.cast != base.cast) o.put(pre + "cast", own.cast.name)
            if (all || own.party != base.party) o.put(pre + "party", own.party.name)
            if (full || own.grow) o.put(pre + "grow", own.grow)
            own.badges?.let { b -> o.put(pre + "badges", org.json.JSONArray(b)) } ?: run { if (full) o.put(pre + "badges", "") }
        }
        // ("Keep my pick", 2026-11-25: written only when Party mode's own - in the full form too: its absence IS "follows In combat",
        // so the store's lay-on never turns a following tab into a pick, and lays an edit's pick on or a reset's follow back)
        l.partyFight?.let { o.put("p.fight", it.name) }
        l.partyCast?.let { o.put("p.fcast", it.name) }
        if (l.partyPicked) o.put("p.pick", true)
    }

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
                rest = en(HomeRest.values(), "rest", d.rest),
                fight = en(HomeLayout.FightBand.values(), "fight", d.fight),
                xpBar = o.optBoolean("xp", d.xpBar),
                rooms = o.optJSONArray("rooms")?.let { a -> RoomBar.normalize((0 until a.length()).map { a.optString(it, "") }) } ?: d.rooms,
                hidden = o.optJSONArray("hide")?.let { a -> RoomBar.hiddenOf((0 until a.length()).map { a.optString(it, "") }) } ?: d.hidden,
                party = en(HomeLayout.PartyDock.values(), "party", d.party),
                extras = o.optJSONArray("extras")?.let { a -> ClockBand.order((0 until a.length()).map { a.optString(it, "") }) } ?: d.extras,
                fightCast = en(HomeLayout.FightCast.values(), "fcast", d.fightCast),
                partyGrow = en(HomeLayout.PartyGrow.values(), "pgrow", d.partyGrow),
                partySize = partySizeOf(o),
            ).let { base -> tabsOf(o, base) }
        } catch (e: Exception) { fallback }
    }

    /** EDIT TABS: the fight tabs' own values over [base] (a tab with no key of its own: still the Out of combat places' copy) */
    private fun tabsOf(o: JSONObject, base: HomeLayout): HomeLayout {
        fun <E : Enum<E>> en(values: Array<E>, key: String, def: E): E = o.optString(key, "").let { w -> values.firstOrNull { it.name == w } } ?: def
        fun places(pre: String): HomeLayout.TabPlaces? {
            if (o.keys().asSequence().none { it.startsWith(pre) && it != "p.fight" && it != "p.fcast" && it != "p.pick" }) return null
            val b = HomeLayout.TabPlaces.of(base)
            val badges = o.optJSONArray(pre + "badges")?.let { a -> (0 until a.length()).map { a.optString(it, "") }
                .filter { it in HomeEditModel.EXTRA_KEYS }.distinct() }
            return HomeLayout.TabPlaces(en(HomeLayout.Side.values(), pre + "chat", b.chatSide), en(HomeLayout.Share.values(), pre + "share", b.chatShare),
                en(HomeLayout.CardDock.values(), pre + "card", b.card), en(HomeLayout.CastDock.values(), pre + "cast", b.cast),
                en(HomeLayout.PartyDock.values(), pre + "party", b.party), o.optBoolean(pre + "grow", false), badges)
        }
        val pt = places("p.")
        return base.copy(combat = places("c."), partyTab = pt, partyPicked = pt != null && o.optBoolean("p.pick", false),
            partyFight = if (o.has("p.fight")) en(HomeLayout.FightBand.values(), "p.fight", base.partyFightNow) else null,
            partyCast = if (o.has("p.fcast")) en(HomeLayout.FightCast.values(), "p.fcast", base.partyCastNow) else null).canonical()
    }

    /** "Size in fights" from the record: its own "psize" when it has one; else, ONCE, test 1523's two picks - Same size or Shrink to make
     *  room in either of "ptop" (Controller on top) / "pbot" (Controller on bottom) -> Same size, anything else (Fill the freed space,
     *  Grow a bit, Grow, none) -> Grow. The old keys are never written back ([json]), so the record's next save drops them and only
     *  "psize" is read from then on */
    internal fun partySizeOf(o: JSONObject): HomeLayout.PartySize {
        if (o.has("psize")) return HomeLayout.PartySize.values().firstOrNull { it.name == o.optString("psize", "") } ?: HomeLayout.PartySize.GROW
        val old = o.optString("ptop", "") == "SAME" || o.optString("pbot", "") in setOf("SAME", "SHRINK")
        return if (old) HomeLayout.PartySize.SAME else HomeLayout.PartySize.GROW
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
        cast = HomeLayout.dockOf(castSide, HomeLayout.Side.LEFT), rest = rest,
        fight = if (combatGrow) HomeLayout.FightBand.GROW else HomeLayout.FightBand.SAME)
}

/**
 * EDIT HOME'S THREE TABS (the user, 2026-11-24: "I want to add tabs to the top of edit mode for out of combat, in combat and party mode,
 * so that we can consolidate some of our edit mode settings into each tab as well as have custom layouts for each mode"; the picks:
 * "Fully separate", per tab the places + the fight rows + the clock's badges, the Party mode layout "Whenever Party mode is on"). Not
 * presets (the 11-21 "no layouts" ruling stands): no names, no slots, no picking - each tab is tied to its situation ([active]).
 */
enum class HomeTab(val word: String, val view: String) {
    OUT("Out of combat", "normal"), COMBAT("In combat", "combat"), PARTY("Party mode", "party");

    /** the Controller room's bar of this situation (its Edit slots opens it: ControllerView.openOnCastBar) */
    val bar: String get() = PadMapModel.barOf(view) ?: "castbar"

    /** a fight tab (the Combat bars, Cast bar and Slots rows; its own badges) */
    val fight: Boolean get() = this != OUT

    companion object {
        /**
         * WHICH TAB'S LAYOUT HOME LAYS NOW: Party mode's whenever Party mode is on (solo or grouped, at rest and in its fights: the user's
         * pick "Whenever Party mode is on"); In combat's on OUR takeover (the same trigger as Grow / Top screen: up on the takeover, B on
         * Home brings the Out of combat layout back mid-fight, the next attack re-arms it - never the game's combat flag); else Out of
         * combat's
         */
        fun active(partyOn: Boolean, takeover: Boolean): HomeTab = when {
            partyOn -> PARTY
            takeover -> COMBAT
            else -> OUT
        }

        /**
         * THE D-PAD'S SIDES (the user's pick 2026-11-24, "Follow the layout on screen"): the tab whose chat / map sides the DS toolbar's
         * D-pad Left / Right (and their pictures) follow - the page Home lays OUT OF A FIGHT: Party mode's whenever Party mode is on, else
         * Out of combat's. The add-on re-binds the D-pad out of combat only (PadMap m178) and the app sends the sides only while Home shows
         * no fight (ToolbarMirror.decide; HomeView.inFight = our takeover or Party mode's fight), so a switch - Party mode on / off, a
         * change of either tab's sides - lands at once at rest and waits for a fight's end otherwise. A fight keeps the sides it began
         * with: In combat's page (laid only in a fight) never re-binds the D-pad, its pictures say what the keys do (P5: the pictures
         * never lie)
         */
        fun dpad(partyOn: Boolean): HomeTab = if (partyOn) PARTY else OUT
        /** the add-on's mirror bit record [l] asks for now (HomeLayoutSolver.mirrorBit of [dpad]'s page) */
        fun mirrorBit(l: HomeLayout, partyOn: Boolean): Int = HomeLayoutSolver.mirrorBit(l.forTab(dpad(partyOn)))
        /** the D-pad sides Edit Home previews on tab [t]: its own on Out of combat and Party mode; on In combat the sides its fights keep
         *  (Out of combat's: the page they begin from) - In combat's own sides cannot re-bind the D-pad in a fight */
        fun dpadPreview(t: HomeTab): HomeTab = if (t == COMBAT) OUT else t

        /** the tab Edit Home opens on: what Home shows now (Party mode on = Party mode; it never opens in a fight) */
        fun opening(partyOn: Boolean): HomeTab = if (partyOn) PARTY else OUT

        /** the tabs Edit Home offers: Party mode's only while Party mode is allowed (QOL > Party), as the Controller room's */
        fun shown(partyAllowed: Boolean): List<HomeTab> = if (partyAllowed) values().toList() else listOf(OUT, COMBAT)

        /**
         * THE PAGE HOME LAYS AT RUNTIME for record [l] on tab [t], and whether its tap-to-cast bar lies slid away: a fight tab whose bar
         * is Off while the Out of combat tab has one keeps that bar's dock HIDDEN (cb7's slide behind the room bar, or up behind the band
         * for a top dock: "a bar that is Off on the new tab slides down behind the room bar"), so the switch glides it away and back
         */
        fun page(l: HomeLayout, t: HomeTab): Pair<HomeLayout, Boolean> {
            val p = l.forTab(t)
            if (t == OUT || p.cast != HomeLayout.CastDock.OFF || l.cast == HomeLayout.CastDock.OFF) return p to false
            // the Out of combat bar's dock in this tab's columns (a column's bar follows its module; Across / Split stay at the foot)
            return p.copy(cast = l.cast, grow = false) to true
        }
    }
}

/**
 * EDIT TABS' MIGRATION (PROPOSAL section 2 "Migration"; nothing changes on screen on day one): a record of before the tabs gets its In
 * combat and Party mode tabs as COPIES of its places with the app-wide fight settings mapped on: In combat's Combat bars = today's In
 * fights (the same field: Grow = Bottom screen, Same size = No grow, Top screen = Top screen), its bar Off when "Show in combat" was Off
 * - except with Top screen, where the bar stayed (the user's "Always stay" pick); Party mode's Combat bars = Top screen when In fights is
 * Top screen, else No grow (R0: [HomeLayout.partyFightNow]), its bar Off when "Bar in Party mode" was Off; Slots = Same size everywhere.
 * The app-wide keys are read once here and become each character's (HomeSettings' castBarCombat / castBarPartyShow are facades now)
 */
object HomeTabsMigration {
    /** HomeSettings' old app-wide keys (still written by the facades for an older build) */
    const val KEY_CAST_COMBAT = "castBarCombat"
    const val KEY_CAST_PARTY = "castBarPartyShow"

    /** [l] with its fight tabs mapped from [showInCombat] / [barInParty] (a record that already has a tab of its own keeps it) */
    fun tabs(l: HomeLayout, showInCombat: Boolean, barInParty: Boolean): HomeLayout {
        if (l.cast == HomeLayout.CastDock.OFF) return l
        var r = l
        if (l.combat == null && !showInCombat && l.fight != HomeLayout.FightBand.TOP)
            r = r.withOwn(HomeTab.COMBAT, r.own(HomeTab.COMBAT).let { it.copy(places = it.places.copy(cast = HomeLayout.CastDock.OFF)) })
        if (l.partyTab == null && !barInParty)
            r = r.withOwn(HomeTab.PARTY, r.own(HomeTab.PARTY).let { it.copy(places = it.places.copy(cast = HomeLayout.CastDock.OFF)) })
        return r
    }
}
