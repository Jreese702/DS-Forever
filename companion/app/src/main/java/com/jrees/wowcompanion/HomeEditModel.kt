package com.jrees.wowcompanion

import kotlin.math.abs

/**
 * MOVABLE HOME, PHASE 2: EDIT MODE'S PURE STATE MACHINE (SPEC.txt "Live docks" sections 3, 4, 7, 8, 12 and 13; the user's rulings on
 * DECISIONS.txt, 2026-11-20: D4 the three ways in, D6 full size and live with the hints in the XP bar's place, D2 / D3 both touch bars
 * editable). The shape of LayoutModel.Editor (the top-screen editor the user signed off): one draft, a cursor, a lifted widget, a menu, a
 * sheet, the Save / Discard / Keep editing prompt; every input returns the [Fx] effects the view carries out. No Android types: the JVM
 * tests drive it press by press (HomeEditModelTest).
 *
 * THE DRAFT: a [HomeLayout] Home is laid with while editing (HomeView.layoutNow); every change glides there on the one motion. Nothing is
 * written or sent to the add-on until Save (the DS toolbar's On / Off and "All characters" wait in the draft too). The menus' BEHAVIOUR
 * rows (the swing line, the clock's extras, hold to talk, the zoom ...) write at once through the same setters as Settings > Bottom screen
 * ([Fx.Press] -> BottomTiles.press): they are app-wide settings, not the layout.
 *
 * THE BUTTONS (section 4): D-pad = the cursor between stops (spatial), with a widget lifted its next dock; A lift / drop (the band and the
 * clock: their menu; the layout chip: the Layouts sheet; "+": the Add tray); X the widget's menu; Y its size; Select = swap chat and map
 * (from any stop); Start = save and leave; LB / RB flip the layouts; B one step back: put back, close the menu / sheet / tray, then leave
 * (asking Save / Discard / Keep editing when something changed, Save highlighted).
 */
enum class EditStop {
    /** [ROOMS] = phase 4's room bar (D3: reorder only): A lifts the room under its own cursor ([HomeEditModel.barSel]), never the bar */
    BAND, CLOCK, CHAT, MAP, QUEST, DIVIDER, CAST, CHIP, PLUS, ROOMS;
    /** a widget a finger or A can lift and dock elsewhere */
    val movable: Boolean get() = this == CHAT || this == MAP || this == QUEST || this == CAST
    /** a widget with the red X chip (it can be taken off Home and brought back with "+") */
    val removable: Boolean get() = this == QUEST || this == CAST
    /** a widget with a menu (X) */
    val hasMenu: Boolean get() = this != DIVIDER && this != CHIP && this != PLUS
    /** the tag drawn on its dashed frame (terse: the widget's name) */
    val tag: String get() = when (this) {
        BAND -> "Controller"; CLOCK -> "Clock"; CHAT -> "Chat"; MAP -> "Map"; QUEST -> "Quest"; CAST -> "Tap-to-cast"
        ROOMS -> "Room bar"
        DIVIDER -> ""; CHIP -> ""; PLUS -> ""
    }
}

class HomeEditModel(
    /** the layout Home showed when edit mode opened (the active record): Discard goes back to it */
    val saved: HomeLayout,
    /** where the cursor starts: Chat from Settings and Y on the disc, the held widget from a hold */
    start: EditStop = EditStop.CHAT,
    /** the layout chip's list (the presets on offer, then the device slots that hold a layout): a name and the layout it makes of [saved] */
    val flips: List<Flip> = emptyList(),
    /** the chip's name for [saved] (a preset's, a slot's, else Custom) */
    val savedName: String = HomePresets.CLASSIC,
    /** the DS toolbar's switch as the add-on says it now (null = no Spells word: the row is not offered) */
    val toolbarNow: Boolean? = null,
    /** the device slots NOW (a slot kept by "Save here" this session is used at once, on every route: A, a finger, the chip) */
    val slotNow: (Int) -> HomeLayout? = { null },
    /** the quest card's fold when edit mode opened (true = folded; null = not known: a landing never puts it back). Fix round 2: a
     *  landing with no fold of its own (Classic, Big chat, a slot) puts this fold back after a passed-over Compact / Fighter pressed one */
    val foldOpen: Boolean? = null,
) {
    /** a layout the chip offers; [can] = selectable now (a device slot: while it holds a layout); [folded] = the quest card's fold the
     *  preset sets when it is PICKED (Compact, Fighter: folded; null = the fold is left alone), applied live as the Settings tile does */
    class Flip(val name: String, val can: () -> Boolean, val folded: Boolean? = null, val make: (HomeLayout) -> HomeLayout) {
        constructor(name: String, available: Boolean = true, folded: Boolean? = null, make: (HomeLayout) -> HomeLayout) :
            this(name, { available }, folded, make)
        val available: Boolean get() = can()
    }

    // ---------------------------------------------------------------- the state
    var draft: HomeLayout = saved
        private set
    var cursor: EditStop = start
        private set
    /** the widget lifted now (A, a drag or the entry hold), null = none */
    var lifted: EditStop? = null
        private set
    /** the draft when the lift began: B puts it back */
    private var liftFrom: HomeLayout? = null
    /** the open menu's widget and its row */
    var menu: EditStop? = null
        private set
    var menuRow = 0
    /** the open sheet: the Layouts sheet (A on the chip) or the Add tray ("+") */
    enum class Sheet { LAYOUTS, ADD }
    var sheet: Sheet? = null
        private set
    var sheetSel = 0
    /** the Save / Discard / Keep editing prompt (B with a changed draft) */
    var prompt = false
        private set
    var promptSel = 0
    /** the red X chip's "Remove?" pill on this widget (touch: the first tap asks, the second removes) */
    var removeAsk: EditStop? = null
        private set
    /** a fight came: the chrome is away, Home plays the saved layout's fight, the draft waits ([park] / [resume]) */
    var parked = false
        private set
    /** "All characters" in the Layouts sheet: Save writes the draft to the default and every character (D5) */
    var allChars = false
        private set
    /** a preset with a fold was PICKED this session (the chip, LB / RB, the Layouts sheet): its fold was pressed live; Discard puts the
     *  fold back. A preset the saved record merely still holds never touches the fold */
    var foldPicked = false
        private set
    /** a preset's fold is showing now (pressed by the last landing): the next landing without a fold of its own puts [foldOpen] back */
    private var foldPressed = false
    /** the DS toolbar's On / Off picked in the band's menu, sent at Save (null = unchanged) */
    var toolbarWant: Boolean? = null
        private set
    /** the bar's / the card's last dock, for "+" (they come back where they were) */
    private var lastCast = if (saved.cast != HomeLayout.CastDock.OFF) saved.cast else HomeLayout.CastDock.UNDER_CHAT
    private var lastCard = if (saved.card != HomeLayout.CardDock.OFF) saved.card else HomeLayout.CardDock.ON_MAP
    /** the flip the chip names (-1 = none yet; [flips].size = the player's own [handMade]) */
    private var flipIdx = flips.indexOfFirst { it.available && it.make(saved).samePlace(saved) }
    /**
     * fix round 3: the player's OWN layout in the flip cycle (the chip's Left / Right, LB / RB) - the draft as it was when a flip first
     * left it (a hand-made "Edited", or a saved layout no preset names). It sits right after the flip it was made from ([handAfter]; -1 =
     * before the first), so Right still goes on to the next layout as before and the press back the other way always finds it: a flip
     * never throws the player's changes away. Null = the draft was a named layout (nothing to keep)
     */
    private var handMade: HomeLayout? = null
    private var handAfter = -1
    /** the draft is the layout [flipIdx] names (a flip of it), not the player's own */
    private fun onFlip(): Boolean = flipIdx in flips.indices && flips[flipIdx].available && draft.samePlace(flips[flipIdx].make(saved))
    /** before a flip or a sheet pick leaves the draft: a draft no flip names is kept as the cycle's own entry ([handMade]) */
    private fun keepHand() {
        if (onFlip()) return
        handMade = draft
        if (flipIdx != flips.size) handAfter = flipIdx
        flipIdx = flips.size
    }
    /** PHASE 4, THE ROOM BAR: its own cursor (an index into the draft's [HomeLayout.rooms]; Left / Right while the cursor is on the bar) */
    var barSel = 0
        private set
    /** the room lifted on the bar (A, a finger): Left / Right move it, B puts the order back ([liftFrom]) */
    var barLifted: String? = null
        private set

    /** something would be written by Save */
    val dirty: Boolean get() = !draft.samePlace(saved) || allChars || (toolbarWant != null && toolbarWant != toolbarNow)

    /** the layout chip's word: the flip it names, the saved name while nothing changed, else "Edited" */
    val chipName: String get() {
        val f = flips.getOrNull(flipIdx)
        // a device slot reads "1" .. "3" as the Settings tile does (its flip is named with SLOT_MARK)
        if (f != null && f.available && draft.samePlace(f.make(saved))) return f.name.removePrefix(SLOT_MARK)
        return if (draft.samePlace(saved)) savedName else EDITED
    }

    /** "+" shows: the bar or the card is off Home */
    val plusShown: Boolean get() = draft.cast == HomeLayout.CastDock.OFF || draft.card == HomeLayout.CardDock.OFF
    /** what the Add tray lists */
    val removed: List<EditStop> get() = buildList {
        if (draft.cast == HomeLayout.CastDock.OFF) add(EditStop.CAST)
        if (draft.card == HomeLayout.CardDock.OFF) add(EditStop.QUEST)
    }

    /** the effects of one input, for the view (HomeView's edit controller) */
    sealed class Fx {
        /** the draft changed: lay Home again, gliding [ms] (EDIT_STEP 120 a lifted step, MODULE_OPEN 240 a change, MODULE_CLOSE 200 a settle) */
        data class Relay(val ms: Long) : Fx()
        /** write the draft (+ All characters, + the toolbar's switch) and leave */
        object Save : Fx()
        /** leave without writing: Home glides back to [saved] */
        object Leave : Fx()
        /** a behaviour row: BottomTiles.press(key, k) or the view's own setter ("fold", "castparty") */
        data class Press(val key: String, val k: Int) : Fx()
        /** keep the draft in device slot [i] ("Save here") */
        data class SaveSlot(val i: Int) : Fx()
        /** the bar's slots: Save, then the Controller room's Tap-to-cast tab (padtab 39 then 29) */
        object EditSlots : Fx()
        /** the chat's filters: Save, then the Chat room's checklist */
        object Filters : Fx()
        /** refused (a dimmed choice, Fighter before phase 3): one red pulse on the stop */
        data class Refuse(val stop: EditStop?) : Fx()
        /** the lift changed (scale 1.0 <-> 1.03, the shadow): LIFT_MS */
        object Lift : Fx()
    }

    // ---------------------------------------------------------------- the controller

    /**
     * one press of the guest "home" (its key names: PADDLEFT .. PAD4 (A = PAD1, B = PAD2, X = PAD3, Y = PAD4), PADBACK = Select, PADFORWARD
     * = Start, the shoulders); [stops] = where each stop is now in Home's px (the cursor moves spatially between them); [menuRows] the open
     * menu's rows now
     */
    fun key(k: String, stops: Map<EditStop, HomeLayoutSolver.Box>, menuRows: (EditStop) -> List<MenuRow> = { emptyList() }): List<Fx> {
        if (parked) return emptyList()
        removeAsk = null
        if (prompt) return promptKey(k)
        sheet?.let { return sheetKey(it, k) }
        menu?.let { return menuKey(it, k, menuRows(it)) }
        lifted?.let { return liftedKey(it, k) }
        return when (k) {
            "PADDLEFT", "PADDRIGHT", "PADDUP", "PADDDOWN" -> {
                val dx = when (k) { "PADDLEFT" -> -1; "PADDRIGHT" -> 1; else -> 0 }
                val dy = when (k) { "PADDUP" -> -1; "PADDDOWN" -> 1; else -> 0 }
                // on the divider Left / Right step the width; on the chip they flip the layouts (landing applies, the tabs' rule)
                if (cursor == EditStop.DIVIDER && dx != 0) return widthStep(dx)
                if (cursor == EditStop.CHIP && dx != 0) return flip(dx)
                // on the room bar Left / Right walk its rooms (the Home disc is no stop: the cursor steps over it)
                if (cursor == EditStop.ROOMS && dx != 0) { barSel = (barSel + dx).coerceIn(0, (draft.rooms.size - 1).coerceAtLeast(0)); return emptyList() }
                nearest(stops, cursor, dx, dy)?.let { cursor = it }
                emptyList()
            }
            "PAD1" -> a()
            "PAD3" -> if (cursor.hasMenu && cursor in stops) openMenu(cursor) else emptyList()
            "PAD4" -> sizeStep(cursor)
            "PADBACK" -> swap()
            "PADFORWARD" -> listOf(Fx.Save)
            "PADLSHOULDER" -> flip(-1)
            "PADRSHOULDER" -> flip(1)
            "PAD2" -> back()
            else -> emptyList()
        }
    }

    private fun a(): List<Fx> = when (cursor) {
        EditStop.CHIP -> { sheet = Sheet.LAYOUTS; sheetSel = flipIdx.takeIf { it in flips.indices } ?: 0; emptyList() }
        EditStop.PLUS -> if (plusShown) { sheet = Sheet.ADD; sheetSel = 0; emptyList() } else emptyList()
        EditStop.BAND, EditStop.CLOCK -> openMenu(cursor)
        EditStop.DIVIDER -> emptyList()
        else -> lift(cursor)
    }

    /** B one step back at the top level: leave, asking first when something changed */
    private fun back(): List<Fx> {
        if (!dirty) return listOf(Fx.Leave)
        prompt = true; promptSel = 0                                   // Save highlighted (LayoutView's signed-off prompt)
        return emptyList()
    }

    fun lift(s: EditStop): List<Fx> {
        if (s == EditStop.ROOMS) {
            // the room under the bar's cursor (the bar itself never moves: D3)
            val r = draft.rooms.getOrNull(barSel) ?: return emptyList()
            cursor = s; lifted = s; liftFrom = draft; menu = null; barLifted = r
            return listOf(Fx.Lift)
        }
        if (!s.movable) return emptyList()
        cursor = s; lifted = s; liftFrom = draft; menu = null
        return listOf(Fx.Lift)
    }

    /** A with a widget lifted: it stays where the draft put it */
    fun drop(): List<Fx> {
        if (lifted == null) return emptyList()
        lifted = null; liftFrom = null; barLifted = null
        return listOf(Fx.Lift)
    }

    private fun putBack(): List<Fx> {
        val from = liftFrom ?: draft
        val room = barLifted
        lifted = null; liftFrom = null; barLifted = null
        val moved = !from.samePlace(draft)
        draft = from
        // a room put back: the bar's cursor stays on it, where it stood
        if (room != null) from.rooms.indexOf(room).takeIf { it >= 0 }?.let { barSel = it }
        return if (moved) listOf(Fx.Lift, Fx.Relay(Motion.MODULE_CLOSE)) else listOf(Fx.Lift)
    }

    /** the lifted room one place left / right on the bar (EDIT_STEP: the others glide; across the Home disc too) */
    fun moveRoom(d: Int): List<Fx> {
        val r = barLifted ?: return emptyList()
        val i = draft.rooms.indexOf(r)
        val j = i + d
        if (i < 0 || j !in draft.rooms.indices) return emptyList()
        return change(draft.copy(rooms = RoomBar.move(draft.rooms, i, j)), EDIT_STEP)
    }

    private fun liftedKey(s: EditStop, k: String): List<Fx> = if (s == EditStop.ROOMS) when (k) {
        "PADDLEFT", "PADDRIGHT" -> moveRoom(if (k == "PADDLEFT") -1 else 1)
        "PAD1" -> drop()
        "PAD2" -> putBack()
        "PADBACK" -> swap()
        "PADFORWARD" -> { drop(); listOf(Fx.Save) }
        else -> emptyList()
    } else when (k) {
        "PADDLEFT", "PADDRIGHT" -> step(s, if (k == "PADDLEFT") -1 else 1, horizontal = true)
        "PADDUP", "PADDDOWN" -> step(s, if (k == "PADDUP") -1 else 1, horizontal = false)
        "PAD1" -> drop()
        "PAD2" -> putBack()
        "PAD4" -> sizeStep(s)
        "PADBACK" -> swap()
        "PADFORWARD" -> { drop(); listOf(Fx.Save) }
        else -> emptyList()
    }

    /** a lifted widget's next dock with the D-pad (EDIT_STEP: the glide of one controller step) */
    private fun step(s: EditStop, d: Int, horizontal: Boolean): List<Fx> {
        val to = nextDock(s, draft, d, horizontal) ?: return emptyList()
        return change(to, EDIT_STEP)
    }

    /** the dock a lifted widget goes to: Left / Right = the column on that side (chat and map swap; the bar keeps its edge); Up / Down =
     *  the next dock (the card On map <-> Above chat; the bar's ladder, no wrap: Above its column <-> Under it <-> Across <-> Split) */
    fun nextDock(s: EditStop, l: HomeLayout, d: Int, horizontal: Boolean): HomeLayout? {
        val side = if (d < 0) HomeLayout.Side.LEFT else HomeLayout.Side.RIGHT
        return when (s) {
            EditStop.CHAT -> if (horizontal && l.chatSide != side) l.copy(chatSide = side) else null
            EditStop.MAP -> if (horizontal && l.chatSide.other() != side) l.copy(chatSide = side.other()) else null
            EditStop.QUEST -> {
                if (l.card == HomeLayout.CardDock.OFF) return null
                val to = if (horizontal) (if (side == l.chatSide) HomeLayout.CardDock.ABOVE_CHAT else HomeLayout.CardDock.ON_MAP)
                    else (if (l.card == HomeLayout.CardDock.ON_MAP) HomeLayout.CardDock.ABOVE_CHAT else HomeLayout.CardDock.ON_MAP)
                if (to == l.card) null else l.copy(card = to)
            }
            EditStop.CAST -> {
                if (l.cast == HomeLayout.CastDock.OFF) return null
                val to = if (horizontal) l.cast.withColumn(if (side == l.chatSide) HomeModule.CHAT else HomeModule.MAP)
                    else castLadder(l.cast, d < 0)
                if (to == null || to == l.cast) null else l.copy(cast = to)
            }
            else -> null
        }
    }

    /** the bar's Up / Down ladder (no wrap): Above x <-> Under x <-> Across <-> Split; Up from Across goes under the chat */
    private fun castLadder(c: HomeLayout.CastDock, up: Boolean): HomeLayout.CastDock? = when (c) {
        HomeLayout.CastDock.ABOVE_CHAT, HomeLayout.CastDock.ABOVE_MAP -> if (up) null else c.withTop(false)
        HomeLayout.CastDock.UNDER_CHAT, HomeLayout.CastDock.UNDER_MAP -> if (up) c.withTop(true) else HomeLayout.CastDock.ACROSS
        HomeLayout.CastDock.ACROSS -> if (up) HomeLayout.CastDock.UNDER_CHAT else HomeLayout.CastDock.SPLIT
        HomeLayout.CastDock.SPLIT -> if (up) HomeLayout.CastDock.ACROSS else null
        HomeLayout.CastDock.OFF -> null
    }

    /** Y: the widget's size step (chat / map width, the band's rest look, the card folded, the bar's slots) */
    fun sizeStep(s: EditStop): List<Fx> = when (s) {
        EditStop.CHAT -> change(draft.copy(chatShare = SHARES[(SHARES.indexOf(draft.chatShare) + 1) % SHARES.size]), Motion.MODULE_OPEN)
        EditStop.MAP -> change(draft.copy(chatShare = SHARES[(SHARES.indexOf(draft.chatShare) + SHARES.size - 1) % SHARES.size]), Motion.MODULE_OPEN)
        EditStop.BAND -> change(draft.copy(rest = if (draft.rest == HomeRest.DIAMONDS) HomeRest.ROW else HomeRest.DIAMONDS), Motion.MODULE_OPEN)
        EditStop.QUEST -> listOf(Fx.Press(K_FOLD, -1))                 // the fold is app-wide (the card's own tap): -1 = toggle
        EditStop.CAST -> if (draft.cast == HomeLayout.CastDock.OFF) emptyList()
            else change(draft.copy(castSize = SIZES[(SIZES.indexOf(draft.castSize) + 1) % SIZES.size]), Motion.MODULE_OPEN)
        else -> emptyList()
    }

    /** Select: chat and map swap (#552's one press), from any stop */
    fun swap(): List<Fx> = change(draft.swapped(), Motion.MODULE_OPEN)

    /** the divider's Left / Right: the LEFT column one step narrower / wider */
    fun widthStep(d: Int): List<Fx> {
        val leftIsChat = draft.chatSide == HomeLayout.Side.LEFT
        val i = SHARES.indexOf(draft.chatShare) + if (leftIsChat) d else -d
        if (i !in SHARES.indices) return emptyList()
        return change(draft.copy(chatShare = SHARES[i]), Motion.MODULE_OPEN)
    }

    /** the chip's arrows, LB / RB: the next layout, live (each one made from the layout Home had when edit mode opened); the player's own
     *  layout ([handMade]) is one more stop of the cycle, after the last flip (fix round 3) */
    fun flip(d: Int): List<Fx> {
        if (parked || flips.isEmpty()) return emptyList()
        keepHand()
        val hand = handMade
        val own = flips.size                                              // the player's own stop in [order]
        val order = ArrayList<Int>()
        if (hand != null && handAfter !in flips.indices) order += own
        for (i in flips.indices) { order += i; if (hand != null && i == handAfter) order += own }
        val n = order.size
        var at = order.indexOf(flipIdx)
        repeat(n) {
            at = if (at < 0) (if (d > 0) 0 else n - 1) else (at + d + n) % n
            val e = order[at]
            if (e == own && hand != null) {
                flipIdx = own
                return change(hand, Motion.MODULE_OPEN) + foldOf(null)
            }
            if (e != own && flips[e].available) {
                flipIdx = e
                return change(flips[e].make(saved), Motion.MODULE_OPEN) + foldOf(flips[e])
            }
        }
        return emptyList()
    }

    /** a landing's fold, pressed live (K_FOLD 0 = folded, 1 = open): the preset's own (Compact, Fighter); a landing with none ([f] null
     *  or its folded null: Classic, Big chat, a slot) after one that pressed a fold = the fold edit mode opened with ([foldOpen]), so
     *  browsing past Compact never leaves the card folded for a layout that never asked for it (fix round 2) */
    private fun foldOf(f: Flip?): List<Fx> {
        val fo = f?.folded
        if (fo == null) {
            val open = foldOpen
            if (!foldPressed || open == null) return emptyList()
            foldPressed = false
            return listOf(Fx.Press(K_FOLD, if (open) 0 else 1))
        }
        foldPicked = true
        foldPressed = true
        return listOf(Fx.Press(K_FOLD, if (fo) 0 else 1))
    }

    /** one draft change: the effects ([ms] its glide); nothing when it changes nothing */
    private fun change(to: HomeLayout, ms: Long): List<Fx> {
        if (to == draft) return emptyList()
        draft = to
        if (to.cast != HomeLayout.CastDock.OFF) lastCast = to.cast
        if (to.card != HomeLayout.CardDock.OFF) lastCard = to.card
        // the bar's cursor rides with the lifted room (and stays on the bar whatever the order)
        barLifted?.let { r -> to.rooms.indexOf(r).takeIf { it >= 0 }?.let { barSel = it } }
        barSel = barSel.coerceIn(0, (to.rooms.size - 1).coerceAtLeast(0))
        return listOf(Fx.Relay(ms))
    }

    // ---------------------------------------------------------------- the menus (section 7)

    fun openMenu(s: EditStop): List<Fx> {
        if (!s.hasMenu) return emptyList()
        if (lifted != null) drop()
        cursor = s; menu = s; menuRow = 0
        return emptyList()
    }

    fun closeMenu() { menu = null }

    private fun menuKey(s: EditStop, k: String, rows: List<MenuRow>): List<Fx> {
        if (rows.isEmpty()) { menu = null; return emptyList() }
        menuRow = menuRow.coerceIn(0, rows.size - 1)
        val r = rows[menuRow]
        return when (k) {
            "PADDUP" -> { menuRow = (menuRow - 1).coerceAtLeast(0); emptyList() }
            "PADDDOWN" -> { menuRow = (menuRow + 1).coerceAtMost(rows.size - 1); emptyList() }
            "PADDLEFT", "PADDRIGHT" -> {
                val d = if (k == "PADDLEFT") -1 else 1
                when (r.kind) {
                    MenuRow.Kind.SEG -> (r.picked + d).let { to -> if (to in r.words.indices && to != r.picked) row(s, r, to) else emptyList() }
                    MenuRow.Kind.STEP -> row(s, r, if (d < 0) 0 else 1)
                    MenuRow.Kind.BUTTON -> emptyList()
                }
            }
            "PAD1" -> when (r.kind) {
                MenuRow.Kind.SEG -> row(s, r, (r.picked + 1).mod(r.words.size))
                MenuRow.Kind.STEP -> emptyList()
                MenuRow.Kind.BUTTON -> row(s, r, 0)
            }
            "PAD2", "PAD3" -> { menu = null; emptyList() }
            "PADFORWARD" -> { menu = null; listOf(Fx.Save) }
            "PADBACK" -> swap()
            else -> emptyList()
        }
    }

    /** a finger on a menu's button: row [i]'s choice [k] */
    fun menuTouch(i: Int, k: Int, rows: List<MenuRow>): List<Fx> {
        val s = menu ?: return emptyList()
        val r = rows.getOrNull(i) ?: return emptyList()
        menuRow = i
        return row(s, r, k)
    }

    /** row [r] set to choice [k]: a layout row changes the draft (live, the glide), a behaviour row is one press of its setter */
    private fun row(s: EditStop, r: MenuRow, k: Int): List<Fx> {
        if (!r.enabled) return listOf(Fx.Refuse(s))
        val l = draft
        return when (r.key) {
            K_REST -> change(l.copy(rest = if (k == 0) HomeRest.DIAMONDS else HomeRest.ROW), Motion.MODULE_OPEN)
            K_FIGHT -> HomeWidgetMenus.FIGHTS.getOrNull(k)?.let { change(l.copy(fight = it), Motion.MODULE_OPEN) } ?: emptyList()
            K_CHAT_SIDE -> change(l.copy(chatSide = HomeLayout.Side.values()[k]), Motion.MODULE_OPEN)
            K_MAP_SIDE -> change(l.copy(chatSide = HomeLayout.Side.values()[k].other()), Motion.MODULE_OPEN)
            K_CHAT_WIDTH -> change(l.copy(chatShare = SHARES[k]), Motion.MODULE_OPEN)
            K_MAP_WIDTH -> change(l.copy(chatShare = SHARES[SHARES.size - 1 - k]), Motion.MODULE_OPEN)
            K_CARD -> change(l.copy(card = HomeWidgetMenus.CARD_DOCKS[k]), Motion.MODULE_OPEN).also { offed(s, EditStop.QUEST, draft.card == HomeLayout.CardDock.OFF) }
            // Chat / Map keep the bar's edge (above or under that column); Off, Across and Split lie at the foot
            K_PLACE -> HomeWidgetMenus.CAST_DOCKS[k].let { c -> change(l.copy(cast = c.column?.let { l.cast.withColumn(it) } ?: c), Motion.MODULE_OPEN) }
                .also { offed(s, EditStop.CAST, draft.cast == HomeLayout.CastDock.OFF) }
            K_CAST_EDGE -> change(l.copy(cast = l.cast.withTop(k == 0)), Motion.MODULE_OPEN)
            K_SIZE -> change(l.copy(castSize = SIZES[k]), Motion.MODULE_OPEN)
            K_XP -> change(l.copy(xpBar = k == 0), Motion.MODULE_OPEN)
            // Order: Reset = today's order (the cursor stays on its room)
            K_ORDER -> { val r = l.rooms.getOrNull(barSel); val fx = change(l.copy(rooms = RoomBar.DEFAULT), Motion.MODULE_OPEN)
                r?.let { RoomBar.DEFAULT.indexOf(it).takeIf { i -> i >= 0 }?.let { i -> barSel = i } }; fx }
            BottomTiles.TOOLBAR -> { toolbarWant = k == 0; emptyList() }
            K_SLOTS -> { menu = null; listOf(Fx.EditSlots) }
            K_FILTERS -> { menu = null; listOf(Fx.Filters) }
            else -> listOf(Fx.Press(r.key, k))
        }
    }

    /** fix round 1: the widget [w] turned Off from its OWN menu ([s] = w): the menu closes and the cursor goes where remove() puts it
     *  ("+" now shown, else the chip) - never left on a widget with no box */
    private fun offed(s: EditStop, w: EditStop, off: Boolean) {
        if (!off || s != w) return
        menu = null
        if (cursor == w) cursor = if (plusShown) EditStop.PLUS else EditStop.CHIP
    }

    /**
     * fix round 1: the cursor's widget has NO box now ([stops]: the card or the bar off, Party mode's Hide sliding the bar away, F2):
     * its menu closes, its lift ends, the cursor goes to "+" when shown (and drawn), else the chip. True = the cursor moved
     */
    fun cursorCheck(stops: Map<EditStop, HomeLayoutSolver.Box>): Boolean {
        if (parked || sheet != null || prompt) return false
        val c = cursor
        if (c !in GONE_CHECKED) return false
        if (c in stops || EditStop.CHIP !in stops) return false
        if (menu == c) menu = null
        if (lifted == c) { lifted = null; liftFrom = null }
        // fix round 2: the clock (only on the small-diamonds rest: LB / RB to a thin-row layout takes it away) falls back to the band
        // it sat in; the card and the bar to "+" when shown (and drawn), else the chip
        cursor = when {
            c == EditStop.CLOCK && EditStop.BAND in stops -> EditStop.BAND
            plusShown && EditStop.PLUS in stops -> EditStop.PLUS
            else -> EditStop.CHIP
        }
        return true
    }

    // ---------------------------------------------------------------- the sheets (the Layouts sheet, the Add tray)

    /** the Layouts sheet's stops: every flip, then the three slots ("Mine"), then "All characters" */
    fun sheetCount(slots: Int = HomePresets.SLOTS): Int = presetCount + slots + 1
    private val presetCount: Int get() = flips.count { !it.name.startsWith(SLOT_MARK) }

    private fun sheetKey(s: Sheet, k: String): List<Fx> {
        val n = if (s == Sheet.LAYOUTS) sheetCount() else removed.size
        if (n == 0) { sheet = null; return emptyList() }
        when (k) {
            "PADDLEFT" -> sheetSel = (sheetSel - 1).coerceAtLeast(0)
            "PADDRIGHT" -> sheetSel = (sheetSel + 1).coerceAtMost(n - 1)
            "PADDUP", "PADDDOWN" -> sheetSel = if (s == Sheet.LAYOUTS) sheetVertical(sheetSel, if (k == "PADDUP") -1 else 1)
                else (sheetSel + if (k == "PADDUP") -1 else 1).coerceIn(0, n - 1)
            "PAD1" -> return if (s == Sheet.LAYOUTS) sheetUse(sheetSel) else addBack(removed.getOrNull(sheetSel))
            "PAD3" -> if (s == Sheet.LAYOUTS) return sheetSaveHere(sheetSel)
            "PAD2" -> sheet = null
            "PADFORWARD" -> { sheet = null; return listOf(Fx.Save) }
        }
        return emptyList()
    }

    /**
     * the Layouts sheet's Up / Down as it is DRAWN: the presets in rows of [SHEET_COLS], then "Mine" (the slots and All characters) as one
     * row of its own; Down on Mine stays, Up from Mine goes to the preset above it (its column, the last row's nearest)
     */
    fun sheetVertical(i: Int, d: Int): Int {
        val p = presetCount
        if (i >= p) {                                                           // the Mine row
            if (d > 0 || p == 0) return i
            val lastRow = (p - 1) / SHEET_COLS
            return (lastRow * SHEET_COLS + (i - p).coerceAtMost(SHEET_COLS - 1)).coerceAtMost(p - 1)
        }
        val to = i + d * SHEET_COLS
        return when {
            to < 0 -> i
            to < p -> to
            i / SHEET_COLS == (p - 1) / SHEET_COLS -> p + (i % SHEET_COLS)          // the last presets' row: down to Mine, same column
            else -> p - 1                                                            // a short last row: its last preset
        }
    }

    /** A (or a finger) on sheet item [i]: a preset = use it on the draft; a slot = its layout; the last = All characters on / off */
    fun sheetUse(i: Int, slot: (Int) -> HomeLayout? = slotNow): List<Fx> {
        if (parked) return emptyList()
        sheetSel = i
        if (i < presetCount) {
            val f = flips.filter { !it.name.startsWith(SLOT_MARK) }[i]
            if (!f.available) return listOf(Fx.Refuse(EditStop.CHIP))
            keepHand()
            flipIdx = flips.indexOf(f)
            sheet = null
            return change(f.make(saved), Motion.MODULE_OPEN) + foldOf(f)
        }
        val si = i - presetCount
        if (si < HomePresets.SLOTS) {
            val l = slot(si) ?: return listOf(Fx.Refuse(EditStop.CHIP))
            keepHand()
            flipIdx = flips.indexOfFirst { it.name == "$SLOT_MARK${si + 1}" }
            sheet = null
            return change(l.copy(name = saved.name), Motion.MODULE_OPEN) + foldOf(null)
        }
        allChars = !allChars
        return emptyList()
    }

    /** X on a slot: keep the draft there now (a device slot is not the active layout: it needs no Save) */
    fun sheetSaveHere(i: Int): List<Fx> {
        if (parked) return emptyList()
        sheetSel = i
        val si = i - presetCount
        return if (si in 0 until HomePresets.SLOTS) listOf(Fx.SaveSlot(si)) else emptyList()
    }

    fun closeSheet() { sheet = null }

    /** "+" tray's A: the widget back in its last dock (today's slide in: the part comes from 80 % and fades in) */
    fun addBack(s: EditStop?): List<Fx> {
        sheet = null
        return when (s) {
            EditStop.CAST -> { cursor = EditStop.CAST; change(draft.copy(cast = lastCast), Motion.MODULE_OPEN) }
            EditStop.QUEST -> { cursor = EditStop.QUEST; change(draft.copy(card = lastCard), Motion.MODULE_OPEN) }
            else -> emptyList()
        }
    }

    // ---------------------------------------------------------------- remove (the red X chip, touch)

    /** the chip's first tap asks ("Remove?"), the pill's tap removes */
    fun removeChip(s: EditStop): List<Fx> {
        if (!s.removable) return emptyList()
        if (removeAsk != s) { removeAsk = s; return emptyList() }
        removeAsk = null
        return remove(s)
    }
    fun cancelRemove() { removeAsk = null }

    fun remove(s: EditStop): List<Fx> {
        lifted = null; liftFrom = null; menu = null
        val fx = when (s) {
            EditStop.CAST -> change(draft.copy(cast = HomeLayout.CastDock.OFF), Motion.MODULE_OPEN)
            EditStop.QUEST -> change(draft.copy(card = HomeLayout.CardDock.OFF), Motion.MODULE_OPEN)
            else -> emptyList()
        }
        if (cursor == s) cursor = EditStop.CHIP
        return fx
    }

    // ---------------------------------------------------------------- the prompt

    private fun promptKey(k: String): List<Fx> = when (k) {
        "PADDLEFT" -> { promptSel = (promptSel + PROMPT.size - 1) % PROMPT.size; emptyList() }
        "PADDRIGHT" -> { promptSel = (promptSel + 1) % PROMPT.size; emptyList() }
        "PAD1" -> promptPick(promptSel)
        "PAD2" -> { prompt = false; emptyList() }
        "PADFORWARD" -> promptPick(0)                                         // Start = Save, in every edit context (section 12)
        else -> emptyList()
    }

    /** Save / Discard / Keep editing */
    fun promptPick(i: Int): List<Fx> {
        prompt = false
        return when (i) {
            0 -> listOf(Fx.Save)
            1 -> { draft = saved; allChars = false; toolbarWant = null; listOf(Fx.Leave) }
            else -> emptyList()
        }
    }

    /** the B disc on the bar, the Home disc's gold tick, Start: save and leave */
    fun saveLeave(): List<Fx> { lifted = null; barLifted = null; menu = null; sheet = null; prompt = false; return listOf(Fx.Save) }

    // ---------------------------------------------------------------- touch: select, then menu; drag a widget by its body

    /** a tap on a widget: an unselected one is selected, the selected one opens its menu */
    fun tap(s: EditStop): List<Fx> {
        if (parked) return emptyList()
        removeAsk = null
        if (menu != null) { menu = null; if (s == cursor) return emptyList() }
        if (lifted != null && lifted != s) drop()
        if (s == EditStop.CHIP) { sheet = Sheet.LAYOUTS; sheetSel = flipIdx.takeIf { it in flips.indices } ?: 0; cursor = s; return emptyList() }
        if (s == EditStop.PLUS) { cursor = s; return if (plusShown) { sheet = Sheet.ADD; sheetSel = 0; emptyList() } else emptyList() }
        if (s != cursor) { cursor = s; return emptyList() }
        if (lifted == s) return drop()
        return openMenu(s)
    }

    /** a tap on room [i] of the room bar: the bar selected with its cursor there; on the room the cursor is on already: the bar's menu */
    fun barTap(i: Int): List<Fx> {
        removeAsk = null
        if (i !in draft.rooms.indices) return emptyList()
        if (menu != null) { menu = null; if (cursor == EditStop.ROOMS && barSel == i) return emptyList() }
        if (lifted != null) drop()
        if (cursor == EditStop.ROOMS && barSel == i) return openMenu(EditStop.ROOMS)
        cursor = EditStop.ROOMS; barSel = i
        return emptyList()
    }

    /** a finger lifts room [i] of the bar ([box] = its slot, in the drag's x): it follows the finger, the others make way ([dockUnder]) */
    fun barDragStart(i: Int, x: Float, y: Float, box: HomeLayoutSolver.Box): List<Fx> {
        if (parked || i !in draft.rooms.indices) return emptyList()
        barSel = i
        return dragStart(EditStop.ROOMS, x, y, box)
    }

    /** a drag: [Drag] keeps the finger; the draft follows the dock under it ([HYSTERESIS_PX] past the divider, after [HOVER_MS]) */
    class Drag(val stop: EditStop, val downX: Float, val downY: Float, val box0: HomeLayoutSolver.Box, val midDock: HomeLayout.CastDock) {
        var x = downX
        var y = downY
        /** the layout the finger asks for now (null = the draft's own) and since when */
        var cand: HomeLayout? = null
        var candAt = 0L
        /** the candidate breaks a size rule: the well shows red, it is never laid */
        var bad = false
        var moved = false
        /** where the widget's box stands now under the finger (its grab kept) */
        fun at(): HomeLayoutSolver.Box = HomeLayoutSolver.Box(box0.l + x - downX, box0.t + y - downY, box0.r + x - downX, box0.b + y - downY)
        val cx: Float get() = (box0.l + box0.r) / 2f + x - downX
        val cy: Float get() = (box0.t + box0.b) / 2f + y - downY
    }
    var drag: Drag? = null
        private set

    /** what a drag needs of the page: the columns of a layout, and whether it is laid validly in the state now */
    interface Geo {
        fun columns(l: HomeLayout): Pair<Pair<Float, Float>, Pair<Float, Float>>
        fun valid(l: HomeLayout): Boolean
        val w: Float
        /** the columns' top and foot now (the bar's drag: its upper half lays it above its column); Home's rest page by default */
        val colTop: Float get() = 234f
        val colBottom: Float get() = 927f
        /** phase 4: the room bar's slots' centres left to right (the drag's x), empty = no bar */
        fun barSlots(): List<Float> = emptyList()
    }

    fun dragStart(s: EditStop, x: Float, y: Float, box: HomeLayoutSolver.Box): List<Fx> {
        if (parked || (!s.movable && s != EditStop.DIVIDER && s != EditStop.ROOMS)) return emptyList()
        menu = null; sheet = null; removeAsk = null
        val mid = if (draft.cast == HomeLayout.CastDock.SPLIT) HomeLayout.CastDock.SPLIT else HomeLayout.CastDock.ACROSS
        drag = Drag(s, x, y, box, mid)
        cursor = s
        return if (s == EditStop.DIVIDER) emptyList() else lift(s)
    }

    /** the finger moved: the dock under it; a new dock is laid once it has been asked for [HOVER_MS] (the divider snaps at once) */
    fun dragMove(x: Float, y: Float, now: Long, geo: Geo): List<Fx> {
        val d = drag ?: return emptyList()
        d.x = x; d.y = y
        if (!d.moved && (abs(x - d.downX) > DRAG_SLOP || abs(y - d.downY) > DRAG_SLOP)) d.moved = true
        val want = dockUnder(d, draft, geo)
        if (want == null || want == draft) { d.cand = null; d.bad = false; return emptyList() }
        d.bad = !geo.valid(want)
        if (d.bad) { d.cand = want; return emptyList() }
        if (d.stop == EditStop.DIVIDER) { d.cand = null; return change(want, Motion.MODULE_OPEN) }
        if (d.cand != want) { d.cand = want; d.candAt = now; return emptyList() }
        return dragTick(now)
    }

    /** the hover delay ran out with the finger still asking (the view ticks while a drag runs) */
    fun dragTick(now: Long): List<Fx> {
        val d = drag ?: return emptyList()
        val c = d.cand ?: return emptyList()
        if (d.bad || now - d.candAt < HOVER_MS) return emptyList()
        d.cand = null
        return change(c, Motion.MODULE_OPEN)                              // the other widget glides out of the way at once (240)
    }

    /** the finger let go: a dock asked for and valid is laid now (no hover needed); the widget settles (MODULE_CLOSE), selected */
    fun dragEnd(x: Float, y: Float, now: Long, geo: Geo): List<Fx> {
        val d = drag ?: return emptyList()
        d.x = x; d.y = y
        drag = null
        val out = ArrayList<Fx>()
        val want = if (d.moved) dockUnder(d, draft, geo) else null
        if (want != null && want != draft && geo.valid(want)) out += change(want, Motion.MODULE_CLOSE)
        else out += Fx.Relay(Motion.MODULE_CLOSE)                          // the widget slides back into its dock
        if (d.stop != EditStop.DIVIDER) { lifted = null; liftFrom = null; barLifted = null; out += Fx.Lift }
        return out
    }

    fun dragCancel(): List<Fx> {
        if (drag == null) return emptyList()
        drag = null
        return putBack() + Fx.Relay(Motion.MODULE_CLOSE)
    }

    /**
     * the layout the dragged widget's CENTRE asks for (the finger keeps its grab): chat / map swap sides once the centre is
     * [HYSTERESIS_PX] beyond the divider; the card docks to the column under it; the bar to the left / right column (above it in the
     * columns' upper half, under it in the lower), or across (Split when it was split) in the middle third of the lower half; the divider snaps the chat's width to the nearest third / half / two thirds
     */
    fun dockUnder(d: Drag, l: HomeLayout, geo: Geo): HomeLayout? {
        if (d.stop == EditStop.ROOMS) {
            // the lifted room goes to the slot its centre is over (the others close up; the disc is no slot)
            val r = barLifted ?: return null
            val slots = geo.barSlots()
            val from = l.rooms.indexOf(r)
            if (slots.size != l.rooms.size || from < 0) return null
            val to = slots.indices.minByOrNull { abs(slots[it] - d.cx) } ?: return null
            return l.copy(rooms = RoomBar.move(l.rooms, from, to))
        }
        val (lc, rc) = geo.columns(l)
        val div = (lc.second + rc.first) / 2f
        val cx = d.cx
        fun sideOver(cur: HomeLayout.Side): HomeLayout.Side = when {
            cur == HomeLayout.Side.LEFT && cx > div + HYSTERESIS_PX -> HomeLayout.Side.RIGHT
            cur == HomeLayout.Side.RIGHT && cx < div - HYSTERESIS_PX -> HomeLayout.Side.LEFT
            else -> cur
        }
        val mapSide = l.chatSide.other()
        return when (d.stop) {
            EditStop.CHAT -> l.copy(chatSide = sideOver(l.chatSide))
            EditStop.MAP -> l.copy(chatSide = sideOver(mapSide).other())
            EditStop.QUEST -> {
                if (l.card == HomeLayout.CardDock.OFF) return null
                val cur = if (l.card == HomeLayout.CardDock.ON_MAP) mapSide else l.chatSide
                val to = sideOver(cur)
                l.copy(card = if (to == mapSide) HomeLayout.CardDock.ON_MAP else HomeLayout.CardDock.ABOVE_CHAT)
            }
            EditStop.CAST -> {
                if (l.cast == HomeLayout.CastDock.OFF) return null
                val third = geo.w / 6f
                val b1 = div - third; val b2 = div + third
                val zone = when (l.cast.column) {
                    HomeModule.CHAT -> if (l.chatSide == HomeLayout.Side.LEFT) -1 else 1
                    HomeModule.MAP -> if (mapSide == HomeLayout.Side.LEFT) -1 else 1
                    else -> 0
                }
                val h = HYSTERESIS_PX
                // the edge: the columns' upper half = above, the lower = under (the edge kept within the hysteresis)
                val midY = (geo.colTop + geo.colBottom) / 2f
                val up = when { d.cy < midY - h -> true; d.cy > midY + h -> false; else -> l.cast.top }
                val to = when (zone) {
                    -1 -> if (cx > b2 + h) 1 else if (cx > b1 + h) 0 else -1
                    1 -> if (cx < b1 - h) -1 else if (cx < b2 - h) 0 else 1
                    else -> if (cx < b1 - h) -1 else if (cx > b2 + h) 1 else 0
                }
                val dock = when (to) {
                    // across (or split) only from the lower half: dragging along the top never drops the bar at the foot
                    0 -> if (!up) d.midDock else if (l.cast.column != null) l.cast.withTop(true) else l.cast
                    -1 -> (if (l.chatSide == HomeLayout.Side.LEFT) HomeLayout.CastDock.UNDER_CHAT else HomeLayout.CastDock.UNDER_MAP).withTop(up)
                    else -> (if (l.chatSide == HomeLayout.Side.RIGHT) HomeLayout.CastDock.UNDER_CHAT else HomeLayout.CastDock.UNDER_MAP).withTop(up)
                }
                l.copy(cast = dock)
            }
            EditStop.DIVIDER -> {
                val gap = lc.first
                val inner = geo.w - 2f * gap - HomeLayoutSolver.COLUMN_GAP
                val leftW = d.x - gap - HomeLayoutSolver.COLUMN_GAP / 2f
                val chatW = if (l.chatSide == HomeLayout.Side.LEFT) leftW else inner - leftW
                val share = SHARES.minByOrNull { abs(inner * it.num / it.den - chatW) } ?: l.chatShare
                l.copy(chatShare = share)
            }
            else -> null
        }
    }

    // ---------------------------------------------------------------- a fight mid-edit (section 4)

    /** a fight started: the chrome goes (120 ms), Home plays the saved layout's fight; the draft and the cursor wait */
    fun park() { parked = true; drag = null; if (lifted != null) { lifted = null; liftFrom = null; barLifted = null }; removeAsk = null }
    /** the fight ended with Home on screen: edit mode where it was left */
    fun resume() { parked = false }

    /** the cursor's start (a hold lifts the held widget; Settings and the disc start on Chat) */
    fun startOn(s: EditStop, lift: Boolean): List<Fx> { cursor = s; return if (lift) lift(s) else emptyList() }

    /** what a hold that reached [HOLD_MS] does ([holdFire]) */
    enum class HoldFire { REFUSE, LET_GO, OPEN }

    companion object {
        /** the long hold that opens edit mode on the chat's or the map's body (CLASS_LONG_MS, PartyLayout.HOLD_MS) */
        const val HOLD_MS = 450L
        /** the gold ring shows from here (a normal tap never flashes it) */
        const val RING_FROM_MS = 150L
        /** a finger moving this far (px) cancels the hold */
        const val HOLD_SLOP = 24f

        /** what a hold that reached [HOLD_MS] does: [REFUSE] the red edge and the rest of the gesture swallowed (no Chat room, no map
         *  module on the lift), [LET_GO] nothing opens and the gesture stays the child's (Home left or covered meanwhile), [OPEN] edit
         *  mode is asked to open (a refusal there is swallowed too) */
        /** [fight] Home shows a fight, [blocked] edit mode may not open now (the 1515 merge's key question, shown or waiting for the
         *  controller: KeyPick.editMayOpen), [stillOk] Home is still on screen, uncovered, under the finger's widget */
        fun holdFire(fight: Boolean, blocked: Boolean, stillOk: Boolean): HoldFire = when {
            fight || blocked -> HoldFire.REFUSE
            !stillOk -> HoldFire.LET_GO
            else -> HoldFire.OPEN
        }
        /** a drag starts past this (px) */
        const val DRAG_SLOP = 12f
        /** a widget's centre must pass the divider by this much to swap sides */
        const val HYSTERESIS_PX = 48f
        /** a dock under the finger is laid after this long */
        const val HOVER_MS = 80L
        /** one controller step of a lifted widget (SPEC section 10, the new Motion constant) */
        const val EDIT_STEP = 120L
        /** the edit chrome's fade in (dim, dashes, tags, ribbon, the disc's tick); out = MODULE_CLOSE */
        const val CHROME_IN = 120L
        /** the lift: 1.0 -> 1.03 and the shadow */
        const val LIFT_MS = 160L
        const val LIFT_SCALE = 1.03f
        /** a request in a fight: the widget's edge red this long, nothing else */
        const val FLASH_MS = 300L
        /** the Layouts sheet's columns */
        const val SHEET_COLS = 3
        /** the chip's word once the draft is none of the named layouts */
        const val EDITED = "Edited"
        /** a slot's flip is named "#1".."#3" in [flips] (the chip shows "1".."3") */
        const val SLOT_MARK = "#"
        val PROMPT = listOf("Save", "Discard", "Keep editing")

        val SHARES = HomeLayout.Share.values().toList()
        val SIZES = HomeLayout.SlotSize.values().toList()
        /** the bar's Above / Below row (column docks only) */
        const val K_CAST_EDGE = "castedge"

        // the menus' layout rows (the draft) and the view's own behaviour keys; every other key is BottomTiles.press's
        const val K_REST = "rest"
        const val K_FIGHT = "fight"
        const val K_CHAT_SIDE = "chatside"
        const val K_MAP_SIDE = "mapside"
        const val K_CHAT_WIDTH = "chatwidth"
        const val K_MAP_WIDTH = "mapwidth"
        const val K_CARD = "card"
        const val K_PLACE = "place"
        const val K_SIZE = "size"
        const val K_FOLD = "fold"
        /** the stops whose box can go while edit mode is open (the cursor on one is moved by [cursorCheck]) */
        val GONE_CHECKED = setOf(EditStop.QUEST, EditStop.CAST, EditStop.CLOCK)
        const val K_CAST_FIGHT = "castfight"
        const val K_CAST_PARTY = "castparty"
        const val K_SLOTS = "slots"
        const val K_FILTERS = "filters"
        /** phase 4, the room bar's menu: the XP track on Home (layout), the order's Reset (layout) */
        const val K_XP = "xpbar"
        const val K_ORDER = "order"

        /**
         * the stop the cursor goes to from [from] pressing (dx, dy): the nearest stop whose centre lies that way, the distance along the
         * press counted once and the offset across it twice - none when the two boxes overlap across the press (a widget straight that
         * way) - the centres' offset breaking a tie; null = none that way. The divider is no stop for Left / Right (they step its width):
         * the D-pad goes from one column straight to the other, and reaches the divider from above (its stop is the columns' top)
         */
        fun nearest(stops: Map<EditStop, HomeLayoutSolver.Box>, from: EditStop, dx: Int, dy: Int): EditStop? {
            val a = stops[from] ?: return stops.keys.firstOrNull()
            val acx = a.cx; val acy = (a.t + a.b) / 2f
            var best: EditStop? = null; var bestScore = Float.MAX_VALUE
            for ((s, b) in stops) {
                if (s == from || (s == EditStop.DIVIDER && dx != 0)) continue
                val cx = b.cx; val cy = (b.t + b.b) / 2f
                val along = (cx - acx) * dx + (cy - acy) * dy
                if (along <= 1f) continue
                // that way for real: its centre past this stop's edge, or its near edge past this stop's centre (no going "left" from the
                // leftmost column up into the band)
                val rowMate = b.t < a.b && a.t < b.b                    // Left / Right stay in the row (never down to the ribbon)
                val beyond = when {
                    dx > 0 -> rowMate && (cx > a.r - 1f || b.l >= acx)
                    dx < 0 -> rowMate && (cx < a.l + 1f || b.r <= acx)
                    dy > 0 -> cy > a.b - 1f || b.t >= acy
                    else -> cy < a.t + 1f || b.b <= acy
                }
                if (!beyond) continue
                val centres = if (dx != 0) abs(cy - acy) else abs(cx - acx)
                val overlap = if (dx != 0) b.t < a.b && a.t < b.b else b.l < a.r && a.l < b.r
                val score = along + (if (overlap) 0f else 2f * centres) + 0.01f * centres
                if (score < bestScore) { bestScore = score; best = s }
            }
            return best
        }
    }
}

/** one row of a widget's menu: a segmented choice (SEG: [words], [picked] lit), a stepper (STEP: [value] between − and +), a button */
data class MenuRow(val key: String, val label: String, val words: List<String>, val picked: Int, val kind: Kind = Kind.SEG,
                   val value: String? = null, val enabled: Boolean = true) {
    enum class Kind { SEG, STEP, BUTTON }
}

/**
 * THE WIDGETS' MENUS (SPEC section 7; final_04, final_06): X (or a tap on the selected widget) opens its menu over the other column, every
 * change previewing live behind it. The LAYOUT rows edit the draft (written at Save by the same store write as Settings > Bottom screen's
 * Home layout tile); the BEHAVIOUR rows are the very keys BottomTiles.press writes (one setter, so the two never disagree). Pure: the
 * behaviour values come in [Values] (HomeView reads them from HomeSettings / Settings).
 */
object HomeWidgetMenus {
    data class Values(
        val swing: Boolean = true, val rank: Boolean = false,
        /** the DS toolbar On / Off (null = no Spells word: the row is not offered), [toolbarCan] = the add-on can switch it */
        val toolbar: Boolean? = null, val toolbarCan: Boolean = false,
        val clock: Map<String, Boolean> = emptyMap(),
        val quickInput: Boolean = false,
        /** hold to talk for this character (null = no character / the toolbar off: the row is not offered), and On release's Send now */
        val talk: Boolean? = null, val talkSendNow: Boolean = false,
        val folded: Boolean = false,
        val zoomOut: Int = 80, val zoomIn: Int = 60,
        val castShowInFight: Boolean = false, val castParty: Boolean = true,
    )

    val CARD_DOCKS = listOf(HomeLayout.CardDock.ON_MAP, HomeLayout.CardDock.ABOVE_CHAT, HomeLayout.CardDock.OFF)
    val CARD_WORDS = listOf("On map", "Above chat", "Off")
    val CAST_DOCKS = listOf(HomeLayout.CastDock.OFF, HomeLayout.CastDock.UNDER_CHAT, HomeLayout.CastDock.UNDER_MAP, HomeLayout.CastDock.ACROSS,
        HomeLayout.CastDock.SPLIT)
    val CAST_WORDS = listOf("Off", "Chat", "Map", "Across", "Split")
    /** the bar's edge in its column ([HomeEditModel.K_CAST_EDGE]) */
    val EDGE_WORDS = listOf("Above", "Below")
    /** In fights: Grow (D1's default) and Same size; "Top screen" is phase 3's, opt-in only ([TOP_OFFERED]) */
    val FIGHTS: List<HomeLayout.FightBand> get() = if (TOP_OFFERED) listOf(HomeLayout.FightBand.GROW, HomeLayout.FightBand.SAME, HomeLayout.FightBand.TOP)
        else listOf(HomeLayout.FightBand.GROW, HomeLayout.FightBand.SAME)
    val FIGHT_WORDS: List<String> get() = if (TOP_OFFERED) listOf("Grow", "Same size", "Top screen") else listOf("Grow", "Same size")
    /** phase 3 (the top-screen combat bar) ships: "Top screen" is offered, opt-in only (D1: Grow stays the default) */
    const val TOP_OFFERED = true
    private val ON_OFF = listOf("On", "Off")
    private val SHOW_HIDE = listOf("Show", "Hide")
    private fun onOff(key: String, label: String, on: Boolean, enabled: Boolean = true) = MenuRow(key, label, ON_OFF, if (on) 0 else 1, enabled = enabled)

    /** [s]'s rows for the draft [l] */
    fun rows(s: EditStop, l: HomeLayout, v: Values, toolbarWant: Boolean? = null): List<MenuRow> = when (s) {
        EditStop.BAND -> buildList {
            add(MenuRow(HomeEditModel.K_REST, "Rest", listOf("Diamonds", "Thin row"), if (l.rest == HomeRest.DIAMONDS) 0 else 1))
            add(MenuRow(HomeEditModel.K_FIGHT, "In fights", FIGHT_WORDS, FIGHTS.indexOf(l.fight).let { if (it < 0) 1 else it }))
            add(onOff(BottomTiles.SWING, "Swing timer", v.swing))
            add(onOff(BottomTiles.RANK, "Rank badges", v.rank))
            v.toolbar?.let { add(onOff(BottomTiles.TOOLBAR, "DS WoW toolbar", toolbarWant ?: it, enabled = v.toolbarCan)) }
        }
        EditStop.CLOCK -> BottomTiles.CLOCK_LINES.map { (k, label, _) -> onOff(k, label, v.clock[k] ?: true) }
        EditStop.CHAT -> buildList {
            add(MenuRow(HomeEditModel.K_CHAT_SIDE, "Side", listOf("Left", "Right"), HomeLayout.Side.values().indexOf(l.chatSide)))
            add(MenuRow(HomeEditModel.K_CHAT_WIDTH, "Width", listOf("1/3", "1/2", "2/3"), HomeEditModel.SHARES.indexOf(l.chatShare)))
            add(MenuRow(BottomTiles.INPUT, "Message input", listOf("Quick chat", "Keyboard"), if (v.quickInput) 0 else 1))
            v.talk?.let { on ->
                add(onOff(BottomTiles.TALK, "Hold to talk", on))
                if (on) add(MenuRow("release", "On release", listOf("Show, A sends", "Send now"), if (v.talkSendNow) 1 else 0))
            }
            add(MenuRow(HomeEditModel.K_FILTERS, "Filters", listOf("Filters"), -1, MenuRow.Kind.BUTTON))
        }
        EditStop.MAP -> listOf(
            MenuRow(HomeEditModel.K_MAP_SIDE, "Side", listOf("Left", "Right"), HomeLayout.Side.values().indexOf(l.chatSide.other())),
            MenuRow(HomeEditModel.K_MAP_WIDTH, "Width", listOf("1/3", "1/2", "2/3"), HomeEditModel.SHARES.size - 1 - HomeEditModel.SHARES.indexOf(l.chatShare)),
            MenuRow(HomeEditModel.K_CARD, "Quest card", CARD_WORDS, CARD_DOCKS.indexOf(l.card)),
            onOff(HomeEditModel.K_FOLD, "Card folded", v.folded),
            MenuRow("out", "Zoom out", listOf("−", "+"), -1, MenuRow.Kind.STEP, "${v.zoomOut} yd"),
            MenuRow("in", "Zoom in", listOf("−", "+"), -1, MenuRow.Kind.STEP, "${v.zoomIn} yd"),
        )
        EditStop.QUEST -> listOf(
            MenuRow(HomeEditModel.K_CARD, "Place", CARD_WORDS, CARD_DOCKS.indexOf(l.card)),
            onOff(HomeEditModel.K_FOLD, "Folded", v.folded),
        )
        EditStop.CAST -> buildList {
            add(MenuRow(HomeEditModel.K_PLACE, "Place", CAST_WORDS, CAST_DOCKS.indexOf(l.cast.withTop(false))))
            // a column's bar: above or below that column (Across and Split stay at the foot)
            l.cast.column?.let { c -> add(MenuRow(HomeEditModel.K_CAST_EDGE, if (c == HomeModule.CHAT) "Chat" else "Map", EDGE_WORDS, if (l.cast.top) 0 else 1)) }
            if (l.cast != HomeLayout.CastDock.OFF) {
                add(MenuRow(HomeEditModel.K_SIZE, "Size", listOf("S", "M", "L"), HomeEditModel.SIZES.indexOf(l.castSize)))
                add(MenuRow(HomeEditModel.K_CAST_FIGHT, "In fights", SHOW_HIDE, if (v.castShowInFight) 0 else 1))
                add(MenuRow(HomeEditModel.K_CAST_PARTY, "Party mode", SHOW_HIDE, if (v.castParty) 0 else 1))
            }
            add(MenuRow(HomeEditModel.K_SLOTS, "Edit slots", listOf("Edit slots"), -1, MenuRow.Kind.BUTTON))
        }
        // phase 4 (SPEC section 7; final_08): the XP track on Home, the order's Reset. No "off the bar" row: D3 keeps every room on it
        EditStop.ROOMS -> listOf(
            MenuRow(HomeEditModel.K_XP, "XP bar", SHOW_HIDE, if (l.xpBar) 0 else 1),
            MenuRow(HomeEditModel.K_ORDER, "Order", listOf("Reset"), -1, MenuRow.Kind.BUTTON),
        )
        else -> emptyList()
    }

    /** the menu's title */
    fun title(s: EditStop): String = when (s) {
        EditStop.BAND -> "Controller"; EditStop.CLOCK -> "Clock"; EditStop.CHAT -> "Chat"; EditStop.MAP -> "Map"
        EditStop.QUEST -> "Quest card"; EditStop.CAST -> "Tap-to-cast bar"; EditStop.ROOMS -> "Room bar"; else -> ""
    }
}
