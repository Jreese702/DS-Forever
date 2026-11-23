package com.jrees.wowcompanion

/**
 * MOVABLE HOME, THE SOLVER (SPEC.txt sections 2, 5, 6 and 13; phase 1): a [HomeLayout] and the page's state now -> every part's box.
 * It replaces HomeView's placesBase / withParty / withStrip: the same arithmetic, with the chat's and the map's columns, the card's dock,
 * the party module's edge and the tap-to-cast bar's dock taken from the record instead of "chat = left half". Pure (no Android types:
 * [Box]); HomeView hands the result to its one motion (apply), so any layout change glides rect-to-rect on today's timings.
 *
 * P8: Classic (chat left at 1/2) lays every state exactly as before the refactor (HomePlacesGoldenTest pins 630 states).
 */
object HomeLayoutSolver {
    data class Box(val l: Float, val t: Float, val r: Float, val b: Float) {
        val w: Float get() = r - l
        val h: Float get() = b - t
        val cx: Float get() = (l + r) / 2f
        fun overlaps(o: Box): Boolean = l < o.r && o.l < r && t < o.b && o.t < b
    }

    /** the party module's input (null in [State] = no module): its row mode, the drawn entries, your pet beside you */
    data class Party(val mode: PartyLayout.Mode, val n: Int, val pet: Boolean)

    /**
     * the page now. [w] x [h] the frame (Home's 1240 x 935 on the Thor), [gap] UiKit.MODULE_GAP; [full] a module full screen (with no
     * takeover); [takeover] the fight's takeover; [card] a focused quest to show ([cardH] its height at Home's scale); [auraOpen] the
     * buff / debuff list open over the map (no card on the map then, b128); [party] the module; [partyOn] Party mode live (R0, F1, F2);
     * [castHidden] the bar slid away for a fight (cb7: Hide in combat); [partyFight] the fight with Party mode on (the add-on's fight word:
     * In fights = Top screen then lays the band away and the party module fills the strip, [away])
     */
    data class State(val w: Float = 1240f, val h: Float = 935f, val gap: Float = 8f, val full: HomeModule? = null, val takeover: Boolean = false,
                     val card: Boolean = false, val cardH: Float = 0f, val auraOpen: Boolean = false, val party: Party? = null,
                     val partyOn: Boolean = false, val castHidden: Boolean = false, val partyFight: Boolean = false)

    /** the laid page: [boxes] every part, [castSide] the bar's geometric side (CastStripView), [partyTabRight] the party list's Select
     *  tab on the right screen edge (its column on the right: the chat's on the right for Classic's dock), [card] the dock used (F1 may
     *  move "Above chat" onto the map), [castHidden] the bar slid away (cb7, or F2 / F3), [f1] / [f2] / [f3] which fallback fired,
     *  [castTop] the bar laid at the top of its column (hidden = up behind the band), [away] the band laid above the page (In fights =
     *  Top screen live, the fight's takeover or Party mode's fight: [away]) */
    data class Result(val boxes: Map<HomePart, Box>, val castSide: CastStripModel.Side, val partyTabRight: Boolean,
                      val card: HomeLayout.CardDock, val castHidden: Boolean, val f1: Boolean = false, val f2: Boolean = false,
                      val barsBottom: Float = 0f, val castTop: Boolean = false, val f3: Boolean = false, val away: Boolean = false)

    /** the band's bottom: the fight's grow (only GROW, never with Party mode on: R0), the diamonds or the thin row at rest */
    fun big(l: HomeLayout, s: State): Boolean = s.takeover && l.fight == HomeLayout.FightBand.GROW && !s.partyOn
    /**
     * IN FIGHTS = TOP SCREEN (TopSlide; the user 2026-11-22: "slides up to the top screen", the pick "Chat and map grow up"): the fight's
     * takeover with Top screen lays the band ABOVE the page - its rest size, its bottom 16 px over the frame's top edge - and the columns
     * start at the frame's top gap. TOP reaches the solver only while the add-on's Wings are on (HomeView.layoutNow: TopCombatBar.honoured
     * lays Grow otherwise). PARTY MODE (the user's ruling C, 11-22: "It should stay at the top in party mode but the party bars should
     * fill the area"): Party mode has no takeover (R0), so its FIGHT ([State.partyFight]) lays the band away with Top screen too
     * (TopCombatBar.partyTop) - the Wings carry the Party-mode layer up top - and the party module grows up into the freed strip
     * ([partyAway]); a module full screen lays its own frame
     */
    fun away(l: HomeLayout, s: State): Boolean = l.fight == HomeLayout.FightBand.TOP &&
        ((s.takeover && !s.partyOn) || (s.partyOn && s.partyFight && !s.takeover && s.full == null))
    /** the Party-mode fight's away page ([away] without the takeover) */
    fun partyAway(l: HomeLayout, s: State): Boolean = away(l, s) && s.partyOn && !s.takeover
    /** the band's rest bottom for [l] (the diamonds or the thin row with its cast strip) */
    fun restBottom(l: HomeLayout): Float =
        if (l.rest == HomeRest.DIAMONDS) 8f + HomeView.REST_DIAMONDS_BARS + CastBarPlace.REST_STRIP else 120f + CastBarPlace.REST_STRIP
    fun barsBottom(l: HomeLayout, s: State): Float = when {
        away(l, s) -> 0f
        big(l, s) -> 10f + HomeView.COMBAT_BARS + CastBarPlace.TOP_STRIP
        l.rest == HomeRest.DIAMONDS -> 8f + HomeView.REST_DIAMONDS_BARS + CastBarPlace.REST_STRIP
        else -> 120f + CastBarPlace.REST_STRIP
    }

    /** the two columns' x ranges (left, right) for the record on a page [w] wide: they tile the inner width with 8 px between */
    fun columns(l: HomeLayout, w: Float, gap: Float): Pair<Pair<Float, Float>, Pair<Float, Float>> {
        // HALF exactly as today's c - 4 / c + 4 (the golden's floats); a third / two thirds of the inner width otherwise
        if (l.chatShare == HomeLayout.Share.HALF) return (gap to w / 2f - COLUMN_GAP / 2f) to (w / 2f + COLUMN_GAP / 2f to w - gap)
        val inner = w - 2f * gap - COLUMN_GAP
        val chatW = inner * l.chatShare.num / l.chatShare.den
        val leftR = gap + if (l.chatSide == HomeLayout.Side.LEFT) chatW else inner - chatW
        return (gap to leftR) to (leftR + COLUMN_GAP to w - gap)
    }

    fun places(l0: HomeLayout, s: State): Result {
        // THE PARTY BARS FILL THE STRIP (ruling C; the 1519 integration: for EVERY party dock): the Party-mode fight's page with the
        // band on it first (b1e28506's: the module's rows FIGHT_ON high, its F1 / F2 / F3), then the band away - the strip the band freed
        // goes to the party list in ITS column (the chat's or the map's, either side), the column's other part keeps the height it had
        // there: at the top of the column (Above chat / Above map) the well runs from the column's new top down to the very foot it had;
        // at the foot (Under chat / Under map) the chat / map rides up into the strip and the well's top follows it up the same distance,
        // its foot on the column's foot. The rows share the height (PartyLayout.fillH: no shorter than FIGHT_H, no taller than FILL_MAX_H
        // - a solo or two-row party gives the rest to the column's other part); the other column grows up as in any Top fight
        if (partyAway(l0, s) && s.party != null) {
            val ref = places(l0, s.copy(partyFight = false, party = s.party.copy(mode = PartyLayout.Mode.FIGHT_ON)))
            return solve(l0, s, ref.f1, ref.f2, ref.f3, fill = ref)
        }
        val r = placesF12(l0, s)
        // F3 (after 1518: the party list in the map's column): the map under its 240 minimum (the takeover's party rows over a bar shown
        // in combat in the map's column, or across): the bar's part in the map's column slides away until the fight ends. Never with the
        // party list in the chat's column (Classic: the golden's 630 states)
        if (!HomeLayoutRules.mapShort(l0, r, s)) return r
        return solve(l0, s, f1 = r.f1, f2 = r.f2, f3 = true)
    }

    private fun placesF12(l0: HomeLayout, s: State): Result {
        val first = solve(l0, s, f1 = false, f2 = false)
        if (!HomeLayoutRules.cramped(first, s)) return first
        // F1: an "Above chat" card shows on the map while Party mode is on and the chat's column is too short
        val withF1 = if (l0.card == HomeLayout.CardDock.ABOVE_CHAT) solve(l0, s, f1 = true, f2 = false) else first
        if (!HomeLayoutRules.cramped(withF1, s)) return withF1
        // F2: still too short: the bar's part in the chat's column slides away (under it: behind the room bar, today's hide motion; above
        // it: up behind the band) until Party mode ends
        return if (HomeLayoutRules.cutsChat(l0)) solve(l0, s, f1 = withF1.f1, f2 = true) else withF1
    }

    /** the party list's column is the right one ([l]'s dock: the chat's or the map's column) */
    fun partyRight(l: HomeLayout): Boolean = (l.party.column == HomeModule.CHAT) == (l.chatSide == HomeLayout.Side.RIGHT)

    /** [fill] = the Party-mode Top fight's reference page (the band on it): the party list fills the strip the band freed (ruling C) */
    private fun solve(l: HomeLayout, s: State, f1: Boolean, f2: Boolean, f3: Boolean = false, fill: Result? = null): Result {
        val w = s.w; val h = s.h; val g = s.gap
        val chatLeft = l.chatSide == HomeLayout.Side.LEFT
        val pRight = partyRight(l)
        // a module full screen (not the takeover): the frame is its own (no bar, no party, no card)
        if (!s.takeover) s.full?.let { m ->
            val all = Box(0f, 0f, w, h)
            val b = when (m) {
                HomeModule.CHAT -> mapOf(HomePart.CHAT_FULL to all)
                HomeModule.MAP -> mapOf(HomePart.MAP to all, HomePart.AURAS to all)
                HomeModule.BARS -> mapOf(HomePart.BARS to all)
            }
            return Result(b, CastStripModel.Side.OFF, pRight, l.card, false, barsBottom = 0f)
        }
        val bb = barsBottom(l, s)
        val top = bb + g
        val (lc, rc) = columns(l, w, g)
        val chatCol = if (chatLeft) lc else rc
        val mapCol = if (chatLeft) rc else lc
        val card = if (f1) HomeLayout.CardDock.ON_MAP else l.card
        val out = LinkedHashMap<HomePart, Box>()
        val away = away(l, s)
        // Top screen's fight: the band's rest box moved up past the frame's top edge by its own bottom + 16 (the same size: no re-measure)
        out[HomePart.BARS] = if (away) restBottom(l).let { rb -> Box(0f, 8f - (rb + AWAY_PAST), w, -AWAY_PAST) }
            else Box(0f, if (big(l, s)) 10f else 8f, w, bb)
        val hidden = s.castHidden || f2 || f3
        // THE TOP BAR (Above chat / Above map): first under the band in its column, the column's parts stacked under it (the card, the
        // party module, the chat); hidden in a fight (or by F2 / F3) it slides up behind the band and the column keeps the band's top
        val aboveBar = if (l.cast.top) castBoxes(l, w, h, g, top).firstOrNull() else null
        val aboveCol = l.cast.column
        val lift = if (aboveBar != null && !hidden) aboveBar.h + g else 0f
        var map = Box(mapCol.first, top + if (aboveCol == HomeModule.MAP) lift else 0f, mapCol.second, h - g)
        var chat = Box(chatCol.first, top + if (aboveCol == HomeModule.CHAT) lift else 0f, chatCol.second, h - g)
        val party = s.party
        // after 1518: the party list at the TOP of the map's column - under a bar above the map, over the map (the card's zone band
        // follows the map's top down)
        if (party != null && l.party == HomeLayout.PartyDock.ABOVE_MAP) {
            val (pb, m) = partyAt(party, map, map.t, pRight, w, topFillH(party, map.t, fill))
            out[HomePart.PARTY] = pb
            map = map.copy(t = minOf(m.chatTop, map.b - 1f))
        }
        val partyInMap = party != null && l.party.column == HomeModule.MAP
        when (card) {
            HomeLayout.CardDock.ON_MAP -> if (s.card && !s.auraOpen) {
                // b121: the card over the map under its zone band, inset as the band (map.t == top unless a bar sits above the map)
                val qTop = map.t + HomeView.ZONE_BAND_BOTTOM + 4f
                // a bar above the map pushes the card down: it ends at the map's foot as a bar under the map would cut it (the party
                // list in the map's column too)
                val qBottom = if (aboveCol == HomeModule.MAP && lift > 0f || partyInMap) maxOf(qTop + 1f, minOf(qTop + s.cardH, map.b))
                    else qTop + s.cardH
                out[HomePart.QUESTS] = Box(map.l + 8f, qTop, map.r - 8f, qBottom)
            }
            HomeLayout.CardDock.ABOVE_CHAT -> if (s.card) {
                val cardBottom = chat.t + s.cardH
                out[HomePart.QUESTS] = Box(chat.l, chat.t, chat.r, cardBottom)
                chat = chat.copy(t = cardBottom + g)
            }
            HomeLayout.CardDock.OFF -> {}
        }
        out[HomePart.CHAT] = chat
        out[HomePart.MAP] = map
        out[HomePart.AURAS] = map
        // THE PARTY MODULE (m134): the top of the chat's column (Classic's dock), its Select tab flush on the screen's OUTER edge of that
        // column
        if (l.party == HomeLayout.PartyDock.ABOVE_CHAT) party?.let { p ->
            val c = out.getValue(HomePart.CHAT)
            // the Party-mode Top fight: the rows fill from the column's top to the reference page's well foot (ruling C)
            val (pb, m) = partyAt(p, c, c.t, pRight, w, topFillH(p, c.t, fill))
            out[HomePart.PARTY] = pb
            out[HomePart.CHAT] = c.copy(t = minOf(m.chatTop, c.b - 1f))
        }
        // THE TAP-TO-CAST BAR (cb3 / cb7): above the page's foot under its column(s); the parts above it end a gap over it; hidden in a
        // fight (or by F2 / F3) it lies below the page and no column is cut
        val side = l.castSide
        if (aboveBar != null) {
            out[HomePart.STRIP] = if (hidden) CastStripModel.hiddenAbove(CastStripModel.Box(aboveBar.l, aboveBar.t, aboveBar.r, aboveBar.b), g)
                .let { Box(it.l, it.t, it.r, it.b) } else aboveBar
            partyUnder(l, s, out, pRight, w, fill)
            return Result(out, side, pRight, card, hidden, f1, f2, bb, castTop = true, f3 = f3, away = away)
        }
        val bars = castBoxes(l, w, h, g)
        if (bars.isEmpty()) { partyUnder(l, s, out, pRight, w, fill); return Result(out, side, pRight, card, false, f1, f2, bb, f3 = f3, away = away) }
        val base = LinkedHashMap(out)
        for ((i, b0) in bars.withIndex()) {
            val b = if (hidden) Box(b0.l, h, b0.r, h + b0.h) else b0
            out[if (i == 0) HomePart.STRIP else HomePart.CAST2] = b
            val top0 = b0.t
            for (p in CUT_ORDER) {
                val r = base[p] ?: continue
                if (r.cx < b0.l || r.cx > b0.r) continue                 // not this bar's column
                if (r.b > top0 - g) out[p] = r.copy(b = CastStripModel.columnBottom(maxOf(r.t + 1f, top0 - g), r.b, hidden))
            }
        }
        partyUnder(l, s, out, pRight, w, fill)
        return Result(out, side, pRight, card, hidden, f1, f2, bb, f3 = f3, away = away)
    }

    /** the party list's box and module with its well's top at [top] in column [col], its Select tab on that column's outer screen edge
     *  (x 0 on the left, [w] on the right: [right] = the right column) - Classic's arithmetic for either column */
    private fun partyAt(p: Party, col: Box, top: Float, right: Boolean, w: Float, rowH: Float = PartyLayout.plateH(p.mode)):
        Pair<Box, PartyLayout.Module> = if (!right) {
        val r = col.r - 1f
        val m = PartyLayout.module(p.mode, p.n, pet = p.pet, top = top, left = PartyLayout.EDGE, right = r, rowH = rowH)
        Box(PartyLayout.EDGE, top - PartyModuleView.RISE, r, m.well.b) to m
    } else {
        val left = col.l + 1f
        val m = PartyLayout.module(p.mode, p.n, pet = p.pet, top = top, left = left, right = w, tabRight = true, rowH = rowH)
        Box(left, top - PartyModuleView.RISE, w, m.well.b) to m
    }

    /** the party list's drawn rows (you, the pet beside you, the members) */
    private fun rowsOf(p: Party): Int = PartyModel.rows(p.n.coerceIn(1, PartyModel.MAX_N), p.pet).size
    /** a top dock's row height: [fill]'s (ruling C) - the well from [top] down to the reference page's well foot - else the mode's */
    private fun topFillH(p: Party, top: Float, fill: Result?): Float {
        val foot = fill?.boxes?.get(HomePart.PARTY)?.b ?: return PartyLayout.plateH(p.mode)
        return PartyLayout.fillH(rowsOf(p), top, foot)
    }

    /** after 1518: the party list at the FOOT of its column (Under chat / Under map), laid after the bar cut the columns - its well ends
     *  at the column's foot (over a foot bar; the page's foot with none, or with the bar slid away), the module ends
     *  [PartyLayout.CHAT_GAP] over it: moved, never squashed; a card on the map ends over it */
    private fun partyUnder(l: HomeLayout, s: State, out: LinkedHashMap<HomePart, Box>, right: Boolean, w: Float, fill: Result? = null) {
        val p = s.party ?: return
        if (l.party.top) return
        val part = if (l.party.column == HomeModule.CHAT) HomePart.CHAT else HomePart.MAP
        val c = out.getValue(part)
        // ruling C at the foot (the 1519 integration): the column's part rides up by the strip the band freed (its reference top - its
        // top now) and keeps its height; the well's top follows it up as far, its foot stays on the column's foot - rows FILL_MAX_H at most
        val rowH = fill?.let { ref ->
            val rp = ref.boxes[HomePart.PARTY]; val rc = ref.boxes[part]
            if (rp == null || rc == null) null
            else PartyLayout.fillH(rowsOf(p), rp.t + PartyModuleView.RISE - (rc.t - c.t), c.b)
        } ?: PartyLayout.plateH(p.mode)
        val wellH = PartyLayout.module(p.mode, p.n, pet = p.pet, top = 0f, rowH = rowH).well.b
        val t = maxOf(c.t + 1f, c.b - wellH)
        out[HomePart.PARTY] = partyAt(p, c, t, right, w, rowH).first
        val col = c.copy(b = maxOf(c.t + 1f, t - PartyLayout.CHAT_GAP))
        out[part] = col
        if (part == HomePart.MAP) {
            out[HomePart.AURAS] = col
            out[HomePart.QUESTS]?.let { q -> if (q.b > col.b) out[HomePart.QUESTS] = q.copy(b = maxOf(q.t + 1f, col.b)) }
        }
    }

    /** the bar's box(es) laid (not hidden): one under its column, one across, or Split's two (slots 1-4 left, 5-8 right); a top dock's
     *  one box starts at [top] (the columns' top: the band's bottom + a gap) */
    fun castBoxes(l: HomeLayout, w: Float, h: Float, g: Float, top: Float = 0f): List<Box> {
        val (lc, rc) = columns(l, w, g)
        val chatLeft = l.chatSide == HomeLayout.Side.LEFT
        fun bar(col: Pair<Float, Float>, n: Int): Box {
            val bh = CastStripModel.slotSide(n, col.second - col.first, l.castSize.px) + 2 * CastStripModel.PAD
            return Box(col.first, h - g - bh, col.second, h - g)
        }
        fun barTop(col: Pair<Float, Float>): Box {
            val bh = CastStripModel.slotSide(CastStripModel.VISIBLE, col.second - col.first, l.castSize.px) + 2 * CastStripModel.PAD
            return Box(col.first, top, col.second, top + bh)
        }
        return when (l.cast) {
            HomeLayout.CastDock.OFF -> emptyList()
            HomeLayout.CastDock.UNDER_CHAT -> listOf(bar(if (chatLeft) lc else rc, CastStripModel.VISIBLE))
            HomeLayout.CastDock.UNDER_MAP -> listOf(bar(if (chatLeft) rc else lc, CastStripModel.VISIBLE))
            HomeLayout.CastDock.ABOVE_CHAT -> listOf(barTop(if (chatLeft) lc else rc))
            HomeLayout.CastDock.ABOVE_MAP -> listOf(barTop(if (chatLeft) rc else lc))
            HomeLayout.CastDock.ACROSS -> listOf(bar(g to w - g, CastStripModel.SLOTS))
            HomeLayout.CastDock.SPLIT -> {
                val a = bar(lc, CastStripModel.VISIBLE); val b = bar(rc, CastStripModel.VISIBLE)
                val bh = maxOf(a.h, b.h)                                  // one row height for both halves
                listOf(a.copy(t = h - g - bh), b.copy(t = h - g - bh))
            }
        }
    }

    /** the outer screen edge the party module's Select tab stands on (and PartyMenuView's side): its own column's (the chat's for
     *  Classic's dock) */
    fun partyEdgeRight(l: HomeLayout): Boolean = partyRight(l)
    /** the add-on's DS toolbar mirror bit: 1 = the map is left of the chat (D-pad Left = map, Right = chat) */
    fun mirrorBit(l: HomeLayout): Int = if (l.mirrored) 1 else 0

    /** the order withStrip cut the columns in (kept: the golden's boxes) */
    private val CUT_ORDER = listOf(HomePart.CHAT, HomePart.QUESTS, HomePart.PARTY, HomePart.MAP, HomePart.AURAS)
    const val COLUMN_GAP = 8f
    /** Top screen's fight: how far past the frame's top edge the band's bottom lies (wholly off the page) */
    const val AWAY_PAST = 16f
}

/** Home's parts by name (HomeView's own Part enum maps to these one to one; CAST2 = Split's second half, phase 3's view) */
enum class HomePart { BARS, QUESTS, CHAT, PARTY, MAP, AURAS, STRIP, CHAT_FULL, CAST2 }

/**
 * THE RULES (SPEC section 5): R0 (no fight grow while Party mode is on) is in [HomeLayoutSolver.big]; F1 / F2 fire only while Party mode
 * is on and the chat's column would be shorter than [CHAT_HARD_MIN]. [valid] is the size check HomeLayoutRulesTest runs over every record
 * x state. The minimums are the spec's (chat 180, map 240, a column 404 wide at the narrowest share); today's Classic is itself under the
 * chat's 180 in some states (the takeover's party rows with the card above the chat), so the tests hold every layout to "never shorter
 * than Classic in the same state" there, and F1 / F2 guard the harder floor without moving any of today's 630 golden states.
 */
object HomeLayoutRules {
    /** the chat's column: about 5 lines at 25 px Friz */
    const val CHAT_MIN = 180f
    /** F1 / F2's floor: 4 lines (today's tightest Party-mode page is 114 px: never moved by a fallback, P8) */
    const val CHAT_HARD_MIN = 100f
    const val MAP_MIN = 240f
    const val COL_MIN_W = 400f

    fun cutsChat(l: HomeLayout): Boolean = l.cast == HomeLayout.CastDock.UNDER_CHAT || l.cast == HomeLayout.CastDock.ABOVE_CHAT ||
        l.cast == HomeLayout.CastDock.ACROSS || l.cast == HomeLayout.CastDock.SPLIT
    fun cutsMap(l: HomeLayout): Boolean = l.cast == HomeLayout.CastDock.UNDER_MAP || l.cast == HomeLayout.CastDock.ABOVE_MAP ||
        l.cast == HomeLayout.CastDock.ACROSS || l.cast == HomeLayout.CastDock.SPLIT

    /** F3's trigger (after 1518): the party list in the map's column, a bar shown in that column and the map under [MAP_MIN] (any
     *  state: the takeover's party rows come with Party mode off) */
    fun mapShort(l: HomeLayout, r: HomeLayoutSolver.Result, s: HomeLayoutSolver.State): Boolean {
        if (s.party == null || l.party.column != HomeModule.MAP || !cutsMap(l) || r.castHidden) return false
        if (s.full != null && !s.takeover) return false
        val m = r.boxes[HomePart.MAP] ?: return false
        return m.h < MAP_MIN
    }

    /** F1 / F2's trigger: Party mode on and the chat's column under the hard floor */
    fun cramped(r: HomeLayoutSolver.Result, s: HomeLayoutSolver.State): Boolean {
        if (!s.partyOn || s.full != null && !s.takeover) return false
        val c = r.boxes[HomePart.CHAT] ?: return false
        return c.h < CHAT_HARD_MIN
    }

    /** what [HomeLayoutSolver.places] laid breaks a hard rule (overlap, out of the frame, a missing part, columns not tiling): the
     *  messages, empty = valid. The height minimums are the tests' (see the object's note) */
    fun problems(l: HomeLayout, s: HomeLayoutSolver.State, r: HomeLayoutSolver.Result): List<String> {
        val out = ArrayList<String>()
        val b = r.boxes
        if (s.full != null && !s.takeover) return out
        for (p in listOf(HomePart.BARS, HomePart.CHAT, HomePart.MAP, HomePart.AURAS)) if (p !in b) out += "$p not placed"
        if (l.cast != HomeLayout.CastDock.OFF && HomePart.STRIP !in b) out += "STRIP not placed"
        if (l.cast == HomeLayout.CastDock.SPLIT && HomePart.CAST2 !in b) out += "CAST2 not placed"
        if (s.party != null && HomePart.PARTY !in b) out += "PARTY not placed"
        // (Top screen's fight: the band laid above the page is not on it)
        val shown = b.filter { (p, x) -> p != HomePart.AURAS && !(r.castHidden && (p == HomePart.STRIP || p == HomePart.CAST2))
            && !(r.away && p == HomePart.BARS) }
        for ((p, x) in shown) {
            if (x.l < -0.01f || x.r > s.w + 0.01f || x.t < -0.01f || x.b > s.h + 0.01f) out += "$p outside the frame: $x"
            if (x.w <= 0f || x.h <= 0f) out += "$p empty: $x"
        }
        // no two parts overlap, except the card ON the map (it lies over it by design) and the map's aura pane (the map's own box)
        val list = shown.entries.toList()
        for (i in list.indices) for (j in i + 1 until list.size) {
            val (p, a) = list[i]; val (q, c) = list[j]
            val pair = setOf(p, q)
            if (pair == setOf(HomePart.QUESTS, HomePart.MAP) && r.card == HomeLayout.CardDock.ON_MAP) continue
            if (a.overlaps(c)) out += "$p overlaps $q: $a / $c"
        }
        // the columns tile the inner width exactly
        val chat = b[HomePart.CHAT]; val map = b[HomePart.MAP]
        if (chat != null && map != null) {
            val left = if (chat.l < map.l) chat else map; val right = if (left === chat) map else chat
            if (kotlin.math.abs(left.l - s.gap) > 0.01f || kotlin.math.abs(right.r - (s.w - s.gap)) > 0.01f ||
                kotlin.math.abs(right.l - left.r - HomeLayoutSolver.COLUMN_GAP) > 0.01f) out += "columns do not tile: $left / $right"
            if (chat.w < COL_MIN_W || map.w < COL_MIN_W) out += "a column narrower than $COL_MIN_W: chat ${chat.w} map ${map.w}"
            if (map.h < MAP_MIN) out += "map shorter than $MAP_MIN: ${map.h}"
            if (s.partyOn && chat.h < CHAT_HARD_MIN) out += "chat under the hard floor with Party mode on: ${chat.h}"
        }
        return out
    }

    fun valid(l: HomeLayout, s: HomeLayoutSolver.State): Boolean = problems(l, s, HomeLayoutSolver.places(l, s)).isEmpty()

    /** every record the editor can make (SPEC section 2: 2 sides x 3 widths x 3 card docks x 7 bar docks x 3 slot sizes x 2 rests x
     *  3 fight modes = 2,268; the 7 docks count Above chat / Above map; after 1518 x the party list's 4 docks = 9,072) */
    fun all(): List<HomeLayout> {
        val out = ArrayList<HomeLayout>(9072)
        for (side in HomeLayout.Side.values()) for (share in HomeLayout.Share.values()) for (card in HomeLayout.CardDock.values())
            for (cast in HomeLayout.CastDock.values()) for (size in HomeLayout.SlotSize.values()) for (rest in HomeRest.values())
                for (fight in HomeLayout.FightBand.values()) for (party in HomeLayout.PartyDock.values())
                    out += HomeLayout(chatSide = side, chatShare = share, card = card, cast = cast, castSize = size, rest = rest, fight = fight,
                        party = party)
        return out
    }

    /** what this build can show (TOP: the band slides above the page in a fight, [HomeLayoutSolver.away]);
     *  Split's second half ships with phase 2's edit mode ([SPLIT_SHIPS]; P1 laid SPLIT as one bar across) */
    fun shown(l: HomeLayout): HomeLayout = if (l.cast == HomeLayout.CastDock.SPLIT && !SPLIT_SHIPS) l.copy(cast = HomeLayout.CastDock.ACROSS) else l
    const val SPLIT_SHIPS = true                 // P2: the bar's second half is laid (HomeView's castStrip2)
}
