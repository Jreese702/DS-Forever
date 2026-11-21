package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * THE TAP-TO-CAST BAR (GitHub #43; the add-on half is WowCompanion/ActionBar.lua cb1-cb3 and WowCompanionSpells/Book.lua b53-b56): a tap
 * is ONE pad tap on the add-on's secure row "castbar" (cell = the slot's index), the game's own cast on DS Forever's own button. Spells
 * only. An edit made in a fight is queued by the add-on until the fight ends; its slot comes dimmed ([Slot.queued]) and with no cell, so
 * it cannot be tapped meanwhile. The cooldowns come from the add-on's `castcd` (facecd's shape: only what the game leaves plain, the
 * length estimated in a fight), never read off the screen.
 * cb2 THE LAZY ROW (1.0.30 code 1454): the add-on registers the castbar row only once the bar has a spell (TapPad's 56 rows are
 * over-subscribed), so a pick goes by the padtab row instead: the document's [Bar.padPick] (pp = [39, 19]: the padtab one-shot pick,
 * then the cast bar's code; Book b55).
 * cb3 SIX SLOTS ON ITS OWN SETTING (the user, 2026-11-13: "the tap to cast bar should get its own bottom screen section. the buttons
 * inside of it are too small, so I want to propose this- for options to turn it on, left right or both, which basically makes it a
 * setting that can be on the left side under chat the right side under the mini map, or sitting under both (or off by default). the bar
 * itself should have 6 slots for abilities. if its sitting on the right or left, the player should be able to swipe on the bar to move it
 * to the other [side]. to assign to the bar, in the bind menu, where there is the L and R tabs, we will add a third tab if this setting
 * is turned on, showing the 6 slots on that bar."; the pick: 6 abilities in all, the Bind tab shows only those 6): ONE row of 6
 * ([CastStripView]) placed by [Side] ([place]); the Bind box's Cast bar tab
 * ([tabCells], [slotCells]: padtab 39 then 26 / 20-25, Book b56). The Spellbook's own "Cast bar" button is gone (the tab replaces it).
 * cb4 THREE AT A TIME (the user, 2026-11-13: "make them only show 3 abilities at a time for left or right, and swipe to the other 3. they
 * should be the same size as the both option"): LEFT / RIGHT were two pages of 3 at BOTH's size ([slotSide]), with page dots and a
 * swipe that flipped them; the swipe stopped moving the bar (the side is set in Settings only). Replaced by cb5's scroll.
 * cb5 A SCROLL BAR, NOT PAGES (the user, 2026-11-13: "I dont want them shown as pages, I want it to be a literal scroll bar that can stop
 * in any position. do we have space to add more? it looks like there are gaps"; the picks: 4 in view, "4 with an arrow on the sides
 * indicating if there are abilities that way for the left and right options", 6 in all): LEFT / RIGHT are a strip of all 6 at BOTH's
 * size, 4 in view spread evenly ([visible], [pitch], [slotBox] with a scroll), that follows the finger and stops anywhere ([dragScroll],
 * [clampScroll]; a light fling with friction: [velocity], [flingAt], [flingDone]; no snapping), clipped to the well ([window]). The
 * page dots are gone; an edge arrow shows (fading with the scroll) where slots are hidden that way ([arrows], [arrowBox]). A tap casts
 * the slot under the finger at the scroll it is at ([slotAt]). The scroll is kept for the app's run ([Live.pos]). BOTH is unchanged.
 * cb6 EIGHT SLOTS, FOUR PER SIDE (the user, 2026-11-13: "only see 6 slots instead of 8 when using both, I think that you misunderstood
 * and kept 6 total slots on both side even with the slide for more."; the pick: "8 total, 4 per side"): [SLOTS] = 8 everywhere. LEFT /
 * RIGHT: the strip of 8 scrolls under 4 in view (as cb5). BOTH: two wells (cb8: now one bar), slots 1-4 under the chat and 5-8 under the
 * minimap, each well exactly where LEFT's / RIGHT's bar lies and each slot where LEFT's / RIGHT's slot at rest lies, no scroll. The Bind
 * box's slots 7-8: padtab 39 then 27-28 ([slotCode]; 20-25 stay slots 1-6, 26 the tab; Book b57, ActionBar cb6).
 * cb8 BOTH IS ONE BAR, RIGHT RESTS ON THE RIGHT (the user, 2026-11-13: "small tap to cast changes- I want on both the UI is split down
 * the middle. I want it to be one full bar. on Right, I want it to show the right 4 abilities and scroll left at its resting
 * position"): BOTH is ONE well across the whole width under both columns (no split, no gap at the middle), all 8 slots at the one size
 * spread evenly across it, no scroll (cb6's two wells are gone). RIGHT's strip rests at its END ([rest]: slots 5-8 in view, the left
 * arrow showing); the player scrolls left for 1-4. LEFT rests at the start. The scroll kept for the run is per side ([Live.side]): a
 * side change starts that side at its rest ([Live.laid]).
 * cb9 ANY CHOOSER KIND (the user, 2026-11-13: "in the controller menu we need a Tab for the cast bar if its enabled, where people can
 * assign anything from the 5 tabs"; ActionBar cb9, PadTabCtl p53): a slot holds a spell, an item, a mount or a command ([Slot.kind]);
 * the document says the kind (`k`, none = a spell), the name (`n`), an item's count (`ct`, `cons` a consumable) and `any` = 1 (the bar
 * takes every kind: [Bar.anyKind], the Controller room's Cast bar tab then shows). [Slot.id] is the spell / item / mount id; the
 * Spellbook's reads ([Bar.has], [Bar.slotOf]) count spells only.
 * cb13 A PLAYER'S MACRO (GitHub #553, a player: "I cannot assign macros to the bottom screen cast bar"): a slot can hold one of the
 * player's own saved macros, by NAME ([Kind.MACRO], `k` "macro", `n` its name, `i` its icon; `miss` = 1 when no macro of that name
 * has text now: no cell, drawn dimmed). It is put there from the Macros room's Bind box, whose Cast bar tab now shows for a macro too
 * ([bindTab] with [macro] = true: only while the add-on says `mac` = 1, [Bar.macros]; Book b69). The add-on's cell runs the macro's
 * own text on OUR button (ActionBar cb13); nothing of the player's is written.
 * cb14 CONTROLLER | TAP TO CAST (the user, 2026-11-19, the approved proposal D1-D10; ActionBar cb14, PadTabCtl p55, Book b70): the bar
 * has an IN-COMBAT LAYER of 8 per-slot overrides ([Bar.fslots], the document's `f`, their cells 9-16): an empty fight slot keeps the
 * slot's own thing, a set one replaces it in fights. The add-on arms it only while the app's "Show in combat" is on ([Bar.show], told
 * by [showCells]); the switch in a fight is the add-on's secure snippet, and the app taps the fight cell while Home shows a fight with
 * Show on ([tapCell] / [shownSlot] with fight = true). "Show in combat" IS [HomeSettings.castBarCombat] (D4: STAY = on): the room's
 * In combat check box and Settings' row are the same setting. The Controller room's Tap to cast pages are sub-tabs (WoW Defaults >
 * Tap to cast = padtab 39 then 29, In combat > Tap to cast = 39 then 1); the Bind box has four tabs (Out of combat | In combat, then
 * Controller | Tap to cast: [BindTab], [stepBind]; 39 then 26 / 4), Macros and Bags too (D8). The bar Off no longer hides them: they
 * show dimmed with "Turn on" (D2: [turnOnSide]).
 * The logic, without the look (unit-tested in dsforever: CastStripModelTest).
 */
object CastStripModel {
    const val ROW = "castbar"
    /** cb2: the row the picks go by (Book b55 / b56) */
    const val PADTAB = "padtab"
    /** cb6: eight slots in all, four per side (the user's pick: "8 total, 4 per side"; cb3's was six) */
    const val SLOTS = 8
    /** cb2: padtab 39 = the one-shot pick (PadTabCtl p46; its old codes 0-4, Smart Range's, stay unused: PadTabCtl p54), then the cast bar's code */
    const val PICK = 39
    /** b56: the code after [PICK] that opens the Bind box's Cast bar tab */
    const val TAB_CODE = 26
    /** b56: the codes after [PICK] for slots 1-6 (20 = slot 1) */
    const val SLOT_CODE0 = 20
    /** b57 (cb6): the code after [PICK] for slot 7 (28 = slot 8): past [TAB_CODE], which keeps its code */
    const val SLOT_CODE7 = 27

    /** cb6: the padtab code after [PICK] for slot [i] (0..7): 20-25 for slots 1-6, 27-28 for slots 7-8; -1 = no such slot */
    fun slotCode(i: Int): Int = when (i) { in 0..5 -> SLOT_CODE0 + i; 6, 7 -> SLOT_CODE7 + (i - 6); else -> -1 }

    /** THE SETTING (Settings > Bottom screen > Tap-to-cast bar): OFF (the default), under the chat (LEFT), under the minimap (RIGHT),
     *  or one bar under BOTH columns */
    enum class Side(val label: String) { OFF("Off"), LEFT("Left"), RIGHT("Right"), BOTH("Both") }

    /** cb9: what a slot holds */
    enum class Kind { SPELL, ITEM, MOUNT, COMMAND, MACRO }

    /** one slot: [index] 0..7, the spell [id] (cb9: the item's / mount's for those kinds), its [icon] file id, the pad [cell] while the
     *  add-on's cell casts it (null = do not tap), [queued] = a fight's edit waits for the fight's end (drawn dimmed); cb9: [kind], a
     *  command's [key], the [name] (not for a spell: the book names it), an item's [count] (null = not said) and [consumable] */
    data class Slot(val index: Int, val id: Int?, val icon: Int?, val cell: Int?, val queued: Boolean, val kind: Kind = Kind.SPELL,
                    val key: String? = null, val name: String? = null, val count: Int? = null, val consumable: Boolean = false,
                    /** cb13: a macro slot whose macro is gone (or has no text): no cell, drawn dimmed */
                    val missing: Boolean = false) {
        /** something is on the slot (any kind; cb13: a macro by its [name]) */
        val filled: Boolean get() = id != null || key != null || (kind == Kind.MACRO && name != null)
        /** the slot's spell id (a spell slot only) */
        val spellId: Int? get() = if (kind == Kind.SPELL) id else null
        /** cb9: the count drawn on the tile: a consumable's, or any item's past 1 (none for a single non-consumable: a Hearthstone) */
        val countShown: Int? get() = count?.takeIf { kind == Kind.ITEM && (consumable || it != 1) }
    }

    /** the `castbar` document: [slots] always [SLOTS] (empty ones have no id), [pick] the row's pick cell (null = no pad row),
     *  [fight] the add-on's lockdown now, [padPick] cb2's pick on the padtab row (its columns in order; null = an older add-on),
     *  [n] the add-on's slot count (cb6 = 8; cb3's 6 and an older add-on's 12 have no slot pick here: their codes differ) */
    data class Bar(val slots: List<Slot>, val pick: Int?, val fight: Boolean, val version: String, val padPick: List<Int>? = null,
                   val n: Int = SLOTS, val anyKind: Boolean = false,
                   /** cb13: the add-on takes a player's macro on a slot (`mac` = 1) */
                   val macros: Boolean = false,
                   /** cb14: the in-combat layer's 8 (empty = the slot's own thing in fights); [Slot.cell] = its fight cell (9-16) */
                   val fslots: List<Slot> = List(SLOTS) { Slot(it, null, null, null, false) },
                   /** cb14: the add-on arms the in-combat layer (the app's Show in combat on, `show` = 1) */
                   val show: Boolean = false,
                   /** cb14: this add-on has the in-combat layer (`fl` = 1) */
                   val fightLayer: Boolean = false,
                   /** cb15: Party mode's layer's 8 (empty = falls through); [Slot.cell] = its party cell (17-24) */
                   val pslots: List<Slot> = List(SLOTS) { Slot(it, null, null, null, false) },
                   /** cb15: the add-on arms Party mode's layer (the app's Bar in Party mode on, `ps` = 1) */
                   val pshow: Boolean = false,
                   /** cb15: this add-on has Party mode's layer (`pl` = 1) */
                   val partyLayer: Boolean = false) {
        /** a pick can be sent: the padtab pick (cb2) or the row's own pick cell */
        val pickable: Boolean get() = padPick != null || pick != null
        /** cb3: the add-on takes a pick for one slot (the Bind box's Cast bar tab, Book b56) */
        val slotPick: Boolean get() = padPick?.firstOrNull() == PICK && n == SLOTS
        /** something is on the bar (cb9: any kind) */
        val hasSpells: Boolean get() = slots.any { it.filled }
        fun has(spellId: Int?): Boolean = spellId != null && slots.any { it.spellId == spellId }
        val full: Boolean get() = slots.all { it.filled }
        /** a Spellbook entry is on the bar: the add-on keeps the id Book's taps hand it (the book item's action id, Spells `a`),
         *  which is not the entry's [SpellsModel.Entry.spellId] for an overridden spell, so either one counts */
        fun hasEntry(actionId: Int?, spellId: Int?): Boolean = has(actionId?.takeIf { it > 0 }) || has(spellId)
        /** the slot an entry is on (0..7), or -1 */
        fun slotOf(actionId: Int?, spellId: Int?): Int = slots.indexOfFirst { s ->
            s.spellId != null && (s.spellId == actionId?.takeIf { it > 0 } || s.spellId == spellId) }
    }

    /** the id the add-on stores for a Spellbook entry: Book's taps carry the book item's action id (Spells `a`), else its spell id */
    fun entryId(actionId: Int?, spellId: Int?): Int? = actionId?.takeIf { it > 0 } ?: spellId

    fun parse(msg: JSONObject): Bar? {
        if (msg.optString("type") != "castbar") return null
        val slots = slotsOf(msg.optJSONArray("e"), 0 until SLOTS)
        val fslots = slotsOf(msg.optJSONArray("f"), FIGHT_CELL0 + 1..FIGHT_CELL0 + SLOTS)      // cb14: cells 9-16
        val pslots = slotsOf(msg.optJSONArray("p"), PARTY_CELL0 + 1..PARTY_CELL0 + SLOTS)      // cb15: cells 17-24
        val pick = if (msg.has("pick")) msg.optInt("pick", -1).takeIf { it >= 0 } else null
        val pp = msg.optJSONArray("pp")?.let { a -> (0 until a.length()).map { a.optInt(it, -1) } }
            ?.takeIf { l -> l.isNotEmpty() && l.size <= 4 && l.all { it in 0 until 40 } }
        return Bar(slots, pick, msg.optBoolean("fight", false), msg.optString("v", ""), pp, msg.optInt("n", 12), msg.optInt("any", 0) == 1,
            msg.optInt("mac", 0) == 1, fslots, msg.optInt("show", 0) == 1, msg.optInt("fl", 0) == 1,
            pslots, msg.optInt("ps", 0) == 1, msg.optInt("pl", 0) == 1)
    }

    /** cb14: a fight slot's cell is the pick cell's next: fight slot i (0..7) = cell [FIGHT_CELL0] + 1 + i */
    const val FIGHT_CELL0 = SLOTS
    /** cb15: party slot i (0..7) = cell [PARTY_CELL0] + 1 + i (17-24) */
    const val PARTY_CELL0 = SLOTS + SLOTS

    /** one list of the document's slots ([cells] = the cell numbers a built slot may carry) */
    private fun slotsOf(a: org.json.JSONArray?, cells: IntRange): List<Slot> {
        val bySlot = HashMap<Int, Slot>()
        a?.let { a ->
            for (k in 0 until a.length()) {
                val o = a.optJSONObject(k) ?: continue
                val s = o.optInt("s", 0)
                if (s !in 1..SLOTS) continue
                val kind = when (o.optString("k", "")) { "item" -> Kind.ITEM; "mount" -> Kind.MOUNT; "command" -> Kind.COMMAND; "macro" -> Kind.MACRO; else -> Kind.SPELL }
                val key = o.optString("key", "").takeIf { kind == Kind.COMMAND && it.isNotEmpty() }
                val id = if (kind == Kind.COMMAND || kind == Kind.MACRO) null else o.optInt("id", 0).takeIf { it > 0 }
                val macName = o.optString("n", "").takeIf { kind == Kind.MACRO && it.isNotEmpty() }
                val missing = macName != null && o.optInt("miss", 0) == 1
                val cell = if (o.has("c") && !missing) o.optInt("c", -1).takeIf { it in cells } else null
                val filled = id != null || key != null || macName != null
                bySlot[s - 1] = Slot(s - 1, id, o.optInt("i", 0).takeIf { it > 0 }, cell.takeIf { filled }, o.optInt("q", 0) == 1,
                    if (filled) kind else Kind.SPELL, key, o.optString("n", "").takeIf { filled && it.isNotEmpty() },
                    if (kind == Kind.ITEM && o.has("ct")) o.optInt("ct", 0).coerceAtLeast(0) else null, kind == Kind.ITEM && o.optInt("cons", 0) == 1,
                    missing)
            }
        }
        return (0 until SLOTS).map { bySlot[it] ?: Slot(it, null, null, null, false) }
    }

    /** the `castcd` message: slot index (0..7) -> its running cooldown; EMPTY = none runs. Null when it is not one. */
    fun parseCd(msg: JSONObject): Map<Int, PadMapModel.FaceCd>? {
        if (msg.optString("type") != "castcd") return null
        val k = msg.optJSONObject("k") ?: return emptyMap()
        val out = HashMap<Int, PadMapModel.FaceCd>()
        for (s in 1..SLOTS) {
            val o = k.optJSONObject(s.toString()) ?: continue
            val d = o.optDouble("d", 0.0); val l = o.optDouble("l", 0.0)
            if (d > 0 && l > 0 && l <= d + 0.001) out[s - 1] = PadMapModel.FaceCd(d, l, o.optBoolean("g", false), o.optBoolean("e", false))
        }
        return out
    }

    /** a finger on slot [i]: the ONE tap (row, cell), or null (empty, queued, not built); cb14: [fight] = Home shows a fight with
     *  Show in combat on: a set fight slot's own cell (9-16) */
    fun tapCell(bar: Bar?, i: Int, fight: Boolean = false, party: Boolean = false): Pair<String, Int>? {
        val s = shownSlot(bar, i, fight, party) ?: return null
        if (!s.filled || s.queued || s.missing) return null
        return s.cell?.let { ROW to it }
    }

    /** cb14: what slot [i] shows and casts now: in a fight ([fight], Show on and armed: [Bar.show]) a set fight slot, else the slot.
     *  cb15 THE RULE PER SLOT: Party ([party] = Party mode on, Bar in Party mode armed: [Bar.pshow]) > In combat > the slot's own; an
     *  empty slot falls through (the add-on decides the same at the press, securely: any of the slot's cells casts the right one) */
    fun shownSlot(bar: Bar?, i: Int, fight: Boolean = false, party: Boolean = false): Slot? {
        val b = bar ?: return null
        val p = b.pslots.getOrNull(i)
        if (party && b.pshow && p != null && p.filled) return p
        val f = b.fslots.getOrNull(i)
        if (fight && b.show && f != null && f.filled) return f
        return b.slots.getOrNull(i)
    }
    /** cb15: which layer slot [i] comes from now ("p" / "f" / null = its own): the strip's badge */
    fun shownLayer(bar: Bar?, i: Int, fight: Boolean = false, party: Boolean = false): String? {
        val b = bar ?: return null
        if (party && b.pshow && b.pslots.getOrNull(i)?.filled == true) return "p"
        if (fight && b.show && b.fslots.getOrNull(i)?.filled == true) return "f"
        return null
    }
    /** cb15: the bar in a fight is hidden by [castBarCombat] HIDE unless Party mode is on with Bar in Party mode on (heals reachable) */
    fun hiddenIn(mode: Combat, fighting: Boolean, partyOn: Boolean, partyShow: Boolean): Boolean =
        hiddenIn(mode, fighting) && !(partyOn && partyShow)

    /** cb14 (D4): Show in combat IS castBarCombat: STAY = on (ticked), HIDE = off */
    fun showOf(mode: Combat): Boolean = mode == Combat.STAY
    fun combatFor(show: Boolean): Combat = if (show) Combat.STAY else Combat.HIDE

    // ---------------------------------------------------------------- cb3: the setting

    /** the saved setting: its name, else (never chosen) the old Home row's On -> LEFT (cb3's migration), else OFF (the default) */
    fun sideOf(saved: String?, oldStrip: Boolean?): Side =
        saved?.let { n -> Side.values().firstOrNull { it.name == n } } ?: if (oldStrip == true) Side.LEFT else Side.OFF

    fun shown(side: Side): Boolean = side != Side.OFF

    // ---------------------------------------------------------------- the bar in a fight

    /** THE BAR IN A FIGHT (the user, 2026-11-13: "there should be a settings toggle to make them Hide/stay in combat"; the picks: the
     *  Tap-to-cast bar's own section, "Hide in combat" the default): HIDE = the bar goes (cb7: slides away) while Home shows a fight, back when it
     *  ends, taking no touches while hidden; STAY = today's (seen and tappable in a fight, Party mode on or off) */
    enum class Combat(val label: String) { HIDE("Off"), STAY("On") }     // cb14 (D5b): the row reads "Show in combat: Off | On"

    /** the saved choice by name; never chosen (or unknown) = HIDE, the default */
    fun combatOf(saved: String?): Combat = saved?.let { n -> Combat.values().firstOrNull { it.name == n } } ?: Combat.HIDE

    /** the bar is hidden now: HIDE and Home shows a fight ([fighting] = HomeView.fightShown(): the takeover or the grow's fight) */
    fun hiddenIn(mode: Combat, fighting: Boolean): Boolean = mode == Combat.HIDE && fighting

    /**
     * cb7 THE BAR SLIDES AWAY (the user, 2026-11-13: "the tap to cast bar on the bottom currently fades out, but it should slide down
     * behind the home bar out of view, not fade out and give that space back to the map or chat"): HIDE no longer fades. In a fight the
     * bar's box goes down below Home's page ([hiddenBox]: under Home's bottom bar, out of view) and the column(s) it sat under (the chat
     * on LEFT, the minimap on RIGHT, both on BOTH) end at the page's foot again ([columnBottom]); the fight's end brings both back. Home
     * lays the two places and its ONE motion (the page's ValueAnimator, Motion.curve, 200-240 ms) carries the bar's box and the columns'
     * bottoms on the same progress, so they stay together ([slideAt] / [columnAt] are that motion's frames, for the tests). No fade.
     *
     * The gate, off-device: [note] the choice and the fight on every tick (true = hidden changed: Home lays the page again), [sliding]
     * while the bar's box is on its way (Home sets it for the motion), [takesTouch] false while hidden or sliding.
     */
    class FightGate {
        var hidden = false
            private set
        var sliding = false
        fun note(mode: Combat, fighting: Boolean): Boolean {
            val h = hiddenIn(mode, fighting)
            if (h == hidden) return false
            hidden = h
            return true
        }
        fun takesTouch(): Boolean = !hidden && !sliding
        /** nothing to draw: hidden and landed (below the page) */
        val gone: Boolean get() = hidden && !sliding
    }

    /** cb7: the bar's box hidden in a fight: the same box moved down until its top is the page's foot [h] (behind Home's bottom bar) */
    fun hiddenBox(b: Box, h: Float): Box = Box(b.l, h, b.r, h + b.h)

    /** a top bar (Above chat / Above map) hidden: up behind the band, its foot on the band's foot ([g] over the columns' top); the
     *  solver lays it with this (HomeLayoutSolver.places), Home's one motion carries it there */
    fun hiddenAbove(b: Box, g: Float): Box = Box(b.l, b.t - b.h - g, b.r, b.t - g)

    /** cb7: a column's bottom under the bar: [cut] = where it ends above the laid bar, [full] = its own bottom with no bar; hidden = full */
    fun columnBottom(cut: Float, full: Float, hidden: Boolean): Float = if (hidden) full else cut

    /** cb7: the motion's frame at progress [k] (0 = laid, 1 = hidden; the fight's end runs it back): the bar's box ... */
    fun slideAt(b: Box, h: Float, k: Float): Box {
        val d = (h - b.t) * k.coerceIn(0f, 1f)
        return Box(b.l, b.t + d, b.r, b.b + d)
    }

    /** ... and a column's bottom on the same [k] (between where it ends above the bar and its own bottom) */
    fun columnAt(cut: Float, full: Float, k: Float): Float = cut + (full - cut) * k.coerceIn(0f, 1f)

    // ---------------------------------------------------------------- cb3: the place on Home

    /** a box in Home's px (plain numbers: the off-device tests have no android.graphics) */
    data class Box(val l: Float, val t: Float, val r: Float, val b: Float) {
        val w: Float get() = r - l
        val h: Float get() = b - t
    }

    // ---------------------------------------------------------------- cb5: the scroll (Left / Right: 4 in view, the strip scrolls freely)

    /** cb5: the slots in view at once: LEFT / RIGHT 4 (the strip of all 8 scrolls under them), BOTH all 8 (cb8: one bar), OFF none */
    fun visible(side: Side): Int = when (side) { Side.LEFT, Side.RIGHT -> VISIBLE; Side.BOTH -> SLOTS; Side.OFF -> 0 }

    /** cb5: the strip scrolls (more slots than fit in view: LEFT / RIGHT) */
    fun scrolls(side: Side): Boolean = visible(side) in 1 until SLOTS

    /** the slot side for [side] at a bar [barW] wide: every mode the same size (cb4, the user: "they should be the same size as the both
     *  option"), at most [MAX_SIDE], smaller only when the bar is too narrow for the slots in view */
    fun slotSide(side: Side, barW: Float): Float = slotSide(maxOf(1, visible(side)), barW, MAX_SIDE)   // cb8: BOTH's 8 across, LEFT / RIGHT 4
    /** movable Home: [n] slots in view across a bar [barW] wide, at most [max] (the layout's slot size S / M / L) */
    fun slotSide(n: Int, barW: Float, max: Float): Float = ((barW - 2 * PAD - GAP * (n - 1)) / maxOf(1, n)).coerceIn(MIN_SIDE, max)

    /** the bar's height for [side] on a page [w] wide (0 = none): the slots' row (cb5: no dots band) */
    fun height(side: Side, w: Float, gap: Float): Float {
        if (side == Side.OFF) return 0f
        return slotSide(side, barBox(side, w, 1000f, gap).w) + 2 * PAD
    }

    /** the bar's box: [side]'s column (LEFT under the chat, RIGHT under the minimap, BOTH under both) above the page's foot */
    private fun barBox(side: Side, w: Float, h: Float, gap: Float): Box {
        val c = w / 2f
        return when (side) {
            Side.LEFT -> Box(gap, 0f, c - 4f, h - gap)
            Side.RIGHT -> Box(c + 4f, 0f, w - gap, h - gap)
            else -> Box(gap, 0f, w - gap, h - gap)
        }
    }

    /** the bar's place on a page [w] x [h] with Home's module [gap]; null = OFF. The columns it lies under end a gap above it */
    fun place(side: Side, w: Float, h: Float, gap: Float): Box? {
        if (side == Side.OFF) return null
        val col = barBox(side, w, h, gap)
        val bh = height(side, w, gap)
        return Box(col.l, h - gap - bh, col.r, h - gap)
    }

    /** the left column (chat, the quest card above chat, the party module) ends above the bar */
    fun cutsLeft(side: Side): Boolean = side == Side.LEFT || side == Side.BOTH
    /** the right column (the minimap, its auras, the quest card over it) ends above the bar */
    fun cutsRight(side: Side): Boolean = side == Side.RIGHT || side == Side.BOTH

    /** the slot side inside a bar [bw] x [bh] */
    private fun sideIn(side: Side, bw: Float, bh: Float): Float = minOf(slotSide(side, bw), bh - 2 * PAD).coerceAtLeast(MIN_SIDE)

    /** cb5: one slot's share across a bar [bw] wide: the slot and an even gap (the slots in view spread evenly, the same room at each
     *  end as half the room between two: BOTH's look, LEFT / RIGHT alike with 4 in view). cb8: BOTH's 8 across the one bar */
    fun pitch(side: Side, bw: Float): Float = (bw - 2 * PAD) / maxOf(1, visible(side))

    /** cb5: the furthest the strip scrolls (px): the hidden slots' shares; 0 = it does not scroll (BOTH, OFF) */
    fun maxScroll(side: Side, bw: Float): Float = if (!scrolls(side)) 0f else maxOf(0f, (SLOTS - VISIBLE) * pitch(side, bw))

    /** cb8: the strip's rest in slots' shares (what a run, or a side change, starts at): RIGHT at its END (slots 5-8 in view, more
     *  to the left: the user's "show the right 4 abilities and scroll left at its resting position"), every other side the start */
    fun rest(side: Side): Float = if (side == Side.RIGHT) (SLOTS - VISIBLE).toFloat() else 0f

    /** cb5: a scroll held to the strip's ends (any place between: no snapping) */
    fun clampScroll(side: Side, bw: Float, o: Float): Float = o.coerceIn(0f, maxScroll(side, bw))

    /** slot [i]'s box (0..7) inside a bar [bw] x [bh] (the bar's own px) with the strip scrolled [scroll] px (0 = slot 1 leftmost);
     *  cb8: BOTH lays all 8 across its one bar */
    fun slotBox(side: Side, i: Int, bw: Float, bh: Float, scroll: Float = 0f): Box {
        val s = sideIn(side, bw, bh)
        val p = pitch(side, bw)
        val y0 = (bh - s) / 2f
        val x = PAD + (p - s) / 2f + i * p - clampScroll(side, bw, scroll)
        return Box(x, y0, x + s, y0 + s)
    }

    /** cb5: the strip's window across (the slots are clipped to it): the well inside its ring edge */
    fun window(bw: Float): Pair<Float, Float> = PAD / 2f to bw - PAD / 2f

    /** cb5: the slot (0..7) under a finger at [x], [y] in a bar [bw] x [bh] scrolled [scroll]: the slot's share across, inside the
     *  window, and the slot's row (half a [GAP] of slack); -1 = none */
    fun slotAt(side: Side, x: Float, y: Float, bw: Float, bh: Float, scroll: Float): Int {
        if (visible(side) == 0) return -1
        val (wl, wr) = window(bw)
        if (x < wl || x > wr) return -1
        val p = pitch(side, bw)
        val i = kotlin.math.floor((x - PAD + clampScroll(side, bw, scroll)) / p).toInt().coerceIn(0, SLOTS - 1)
        val r = slotBox(side, i, bw, bh, scroll)
        val m = GAP / 2f
        if (y < r.t - m || y > r.b + m) return -1
        return i
    }

    /** cb5: the slots at least partly in view at [scroll] (their indexes, leftmost first) */
    fun inView(side: Side, bw: Float, bh: Float, scroll: Float): List<Int> {
        val (wl, wr) = window(bw)
        return (0 until SLOTS).filter { i -> slotBox(side, i, bw, bh, scroll).let { it.r > wl && it.l < wr } }
    }

    /** cb5: the edge arrows' strength (0 hidden .. 1 shown), left then right: a slot is hidden that way; each fades in over the first
     *  [ARROW_FADE] of a slot's share of scroll away from its end (none on a bar that does not scroll) */
    fun arrows(side: Side, bw: Float, scroll: Float): Pair<Float, Float> {
        if (!scrolls(side)) return 0f to 0f
        val f = pitch(side, bw) * ARROW_FADE
        val o = clampScroll(side, bw, scroll)
        return (o / f).coerceIn(0f, 1f) to ((maxScroll(side, bw) - o) / f).coerceIn(0f, 1f)
    }

    /** cb5: the arrow's box at the bar's [left] or right edge: in the room beside the end slot in view, on the slots' middle */
    fun arrowBox(side: Side, left: Boolean, bw: Float, bh: Float): Box {
        val s = sideIn(side, bw, bh)
        val end = PAD + (pitch(side, bw) - s) / 2f                          // the well's edge to the first slot at rest
        val cx = (PAD / 2f + end) / 2f
        val x = if (left) cx else bw - cx
        return Box(x - ARROW_W / 2f, bh / 2f - ARROW_H / 2f, x + ARROW_W / 2f, bh / 2f + ARROW_H / 2f)
    }

    // ---------------------------------------------------------------- cb5: the finger (drag, fling, tap)

    /** the finger is not a tap any more (the slot's cast is not sent on its release) */
    fun leftTap(dx: Float, dy: Float): Boolean = kotlin.math.hypot(dx, dy) > TAP_SLOP_PX

    /** cb5: the strip under a finger that went [dx] since it held the strip at [start]: it follows the finger (a finger going left
     *  brings the slots on the right into view), held to the ends */
    fun dragScroll(side: Side, bw: Float, start: Float, dx: Float): Float = clampScroll(side, bw, start - dx)

    /** cb5: a finger's speed across (px/ms, + = rightward) from its recent samples ([ts] ms, [xs] px, oldest first): the move over the
     *  last [VELOCITY_MS]; 0 for fewer than two */
    fun velocity(ts: List<Long>, xs: List<Float>): Float {
        if (ts.size < 2 || ts.size != xs.size) return 0f
        val tEnd = ts.last()
        var k = ts.size - 2
        while (k > 0 && tEnd - ts[k - 1] <= VELOCITY_MS) k--
        val dt = (tEnd - ts[k]).toFloat()
        return if (dt <= 0f) 0f else (xs.last() - xs[k]) / dt
    }

    /** cb5: a release at finger speed [v] (px/ms) flings the strip (light momentum; a slow release just stops where it is) */
    fun flings(v: Float): Boolean = kotlin.math.abs(v) * FLING_GAIN >= FLING_MIN

    /** cb5, the fling: the strip's scroll [tMs] after a release at [start] with finger speed [v]: friction-damped, the strip goes on
     *  the way the finger did (the scroll the other way), travel = v * GAIN * TAU * (1 - e^(-t / TAU)); held to the ends */
    fun flingAt(side: Side, bw: Float, start: Float, v: Float, tMs: Float): Float {
        val travel = v * FLING_GAIN * FLING_TAU_MS * (1f - kotlin.math.exp(-tMs / FLING_TAU_MS))
        return clampScroll(side, bw, start - travel)
    }

    /** cb5, the fling is over at [tMs]: its speed fell under [FLING_STOP], or the strip reached the end it was going to */
    fun flingDone(side: Side, bw: Float, start: Float, v: Float, tMs: Float): Boolean {
        if (kotlin.math.abs(v) * FLING_GAIN * kotlin.math.exp(-tMs / FLING_TAU_MS) < FLING_STOP) return true
        val o = flingAt(side, bw, start, v, tMs)
        return (o <= 0f && v > 0f) || (o >= maxScroll(side, bw) && v < 0f)
    }

    /** cb5: the scroll [o] px as slots' shares (kept for the app's run in [Live.pos]: it survives a new layout) and back */
    fun toPos(side: Side, bw: Float, o: Float): Float = pitch(side, bw).let { p -> if (p <= 0f) 0f else clampScroll(side, bw, o) / p }
    fun fromPos(side: Side, bw: Float, pos: Float): Float = clampScroll(side, bw, pos * pitch(side, bw))

    // ---------------------------------------------------------------- cb3: the Bind box's Cast bar tab (Book b56)

    // ---------------------------------------------------------------- cb9: the Controller room's Cast bar tab (PadTabCtl p53, Book b60)

    /** b60: padtab 39 then 29 = the room's Cast bar tab; 30 / 31 = the bar setting on / off (the add-on shows the tab or not) */
    const val CTL_TAB_CODE = 29
    const val CTL_ON_CODE = 30
    const val CTL_OFF_CODE = 31

    /** the Controller room has its Tap to cast sub-tabs: the add-on's bar takes every kind (cb9). cb14 (D2): the setting Off no longer
     *  hides them ([barOff]: dimmed, "Turn on") */
    fun ctlTab(bar: Bar?): Boolean = bar?.anyKind == true
    /** cb14 (D2): the bar is Off in Settings: the Tap to cast pages / tabs show dimmed with "Turn on" */
    fun barOff(side: Side): Boolean = side == Side.OFF
    /** cb14 (D2): what "Turn on" sets (Settings > Bottom screen > Tap-to-cast bar): one bar under both columns */
    val turnOnSide: Side = Side.BOTH

    /** cb14 (Book b70): padtab 39 then 1 = the room's In combat > Tap to cast; 2 / 3 = Show in combat on / off; 4 = the Bind box's In
     *  combat + Tap to cast tab (the retired Smart Range codes; 0 is B: pet recall's since b68) */
    const val CTL_FIGHT_CODE = 1
    const val SHOW_ON_CODE = 2
    const val SHOW_OFF_CODE = 3
    const val BIND_FIGHT_CODE = 4
    /** the taps that open the room's In combat > Tap to cast: padtab 39 then 1 */
    fun ctlFightCells(): List<Pair<String, Int>> = listOf(PADTAB to PICK, PADTAB to CTL_FIGHT_CODE)
    /** the taps that tell the add-on Show in combat ([show] = STAY): padtab 39 then 2 / 3 (no action, no room needed) */
    fun showCells(show: Boolean): List<Pair<String, Int>> = listOf(PADTAB to PICK, PADTAB to if (show) SHOW_ON_CODE else SHOW_OFF_CODE)

    /** cb15 / Book b71 THE SECOND PAGE OF ONE-SHOT CODES: padtab 39, then 38, then the code (39's own codes are all taken) */
    const val PAGE2 = 38
    const val P2_PARTY_BAR = 1
    const val P2_PSHOW_ON = 2
    const val P2_PSHOW_OFF = 3
    const val P2_BIND_PARTY = 4
    const val P2_BIND_PARTY_TAP = 5
    /** Book b72: 6-10 = the Controller room's chooser tab 1-5 by touch (ChooserTabs.touchCells), 11-34 = the Spellbook room's tab of skill
     *  line 1-24, 35 its Professions tab, 36 its Pet tab ([bookTabCells]): a finger's tab is the controller's tab too */
    const val P2_CHOOSER_TAB0 = 5
    const val P2_BOOK_LINE0 = 10
    const val P2_BOOK_PROF = 35
    const val P2_BOOK_PET = 36
    /** the Spellbook room's tab of skill line [line] by touch (100 = Professions, 101 = the pet's book); null = no code for it */
    fun bookTabCells(line: Int): List<Pair<String, Int>>? = when (line) {
        in 1..24 -> page2(P2_BOOK_LINE0 + line)
        100 -> page2(P2_BOOK_PROF)
        101 -> page2(P2_BOOK_PET)
        else -> null
    }
    fun page2(code: Int): List<Pair<String, Int>> = listOf(PADTAB to PICK, PADTAB to PAGE2, PADTAB to code)
    /** the taps that open the room's Party mode bar (the room open): padtab 39, 38 then 1 */
    fun ctlPartyCells(): List<Pair<String, Int>> = page2(P2_PARTY_BAR)
    /** the taps that tell the add-on Bar in Party mode ([on]): padtab 39, 38 then 2 / 3 (no action, no room needed) */
    fun partyShowCells(on: Boolean): List<Pair<String, Int>> = page2(if (on) P2_PSHOW_ON else P2_PSHOW_OFF)

    /** the taps that open the room's Cast bar tab (the room open): padtab 39 then 29 */
    fun ctlTabCells(): List<Pair<String, Int>> = listOf(PADTAB to PICK, PADTAB to CTL_TAB_CODE)

    /** the taps that tell the add-on the setting ([on] = not Off): padtab 39 then 30 / 31 (no action, no room needed) */
    fun ctlSettingCells(on: Boolean): List<Pair<String, Int>> = listOf(PADTAB to PICK, PADTAB to if (on) CTL_ON_CODE else CTL_OFF_CODE)

    /** the Bind box has its Tap to cast tabs: the add-on takes a slot pick; cb13: a macro's box ([macro]) only while the add-on takes
     *  macros ([Bar.macros]); cb14 (D8): a bag item's box too (the bar takes items since cb9); (D2) the setting Off shows them dimmed */
    fun bindTab(bar: Bar?, macro: Boolean = false): Boolean = bar?.slotPick == true && (!macro || bar.macros)
    /** cb14: the box's In combat + Tap to cast tab: the add-on has the in-combat layer */
    fun bindFightTab(bar: Bar?, macro: Boolean = false): Boolean = bindTab(bar, macro) && bar?.fightLayer == true

    /** cb14 (D9) THE BIND BOX'S STOPS; cb15 (Option C, the user 2026-11-20): three SITUATIONS (Out of combat, In combat, Party mode),
     *  each with BOTH devices on show, in reading order: a situation's controller, then its bar. LB / RB walk them ([stepBind]); the
     *  tab row lights the situation, the gold box the device */
    data class BindTab(val fight: Boolean, val tap: Boolean, val party: Boolean = false) {
        val index: Int get() = (if (party) 4 else if (fight) 2 else 0) + (if (tap) 1 else 0)
        /** 0 Out of combat, 1 In combat, 2 Party mode */
        val situation: Int get() = if (party) 2 else if (fight) 1 else 0
    }
    val BIND_TABS = listOf(BindTab(false, false), BindTab(false, true), BindTab(true, false), BindTab(true, true),
        BindTab(false, false, true), BindTab(false, true, true))
    /** the stop after LB ([dir] -1) or RB (+1) from [from]; [tap] = the bar's stops are there, [fightTap] = In combat's is, [party] =
     *  Party mode's situation is (Party mode allowed), [partyTap] = its bar (the add-on's party layer); no wrap */
    fun stepBind(from: BindTab, dir: Int, tap: Boolean, fightTap: Boolean = tap, party: Boolean = false, partyTap: Boolean = false): BindTab {
        val list = BIND_TABS.filter { t ->
            (!t.party || party) && (!t.tap || (tap && (if (t.party) partyTap else !t.fight || fightTap)))
        }
        val i = list.indexOf(from).let { if (it < 0) 0 else it }
        return list[(i + dir).coerceIn(0, list.size - 1)]
    }
    /** the taps that put the box on [t]: Out of combat / In combat + Controller = the spells row's bf-off / bf-on ([cols]: on, off);
     *  Tap to cast = padtab 39 then 26 / 4; cb15: Party mode = padtab 39, 38 then 4 (its controller) / 5 (its bar) */
    fun bindTabCells(t: BindTab, cols: Pair<Int, Int>?): List<Pair<String, Int>>? = when {
        t.party -> page2(if (t.tap) P2_BIND_PARTY_TAP else P2_BIND_PARTY)
        t.tap && t.fight -> listOf(PADTAB to PICK, PADTAB to BIND_FIGHT_CODE)
        t.tap -> tabCells()
        cols == null -> null
        else -> listOf("spells" to if (t.fight) cols.first else cols.second)
    }
    /** cb15: the box's Party mode bar stop: the add-on has Party mode's layer */
    fun bindPartyTab(bar: Bar?, macro: Boolean = false): Boolean = bindTab(bar, macro) && bar?.partyLayer == true

    /** the taps that open the box's Cast bar tab: padtab 39 then 26 */
    fun tabCells(): List<Pair<String, Int>> = listOf(PADTAB to PICK, PADTAB to TAB_CODE)

    /** the taps that put the box's spell on slot [i] (0..7): padtab 39 then [slotCode] (20-25, 27-28); null = no such slot */
    fun slotCells(i: Int): List<Pair<String, Int>>? = if (i in 0 until SLOTS) listOf(PADTAB to PICK, PADTAB to slotCode(i)) else null

    /** the cooldown sweep's part still to run at [nowMs] (elapsedRealtime) for a cooldown that arrived at [arrivalMs]: 1 = all, 0 = done */
    fun sweepLeft(cd: PadMapModel.FaceCd, arrivalMs: Long, nowMs: Long): Float {
        val start = PadMapModel.cdStartMs(cd, arrivalMs); val dur = PadMapModel.cdDurMs(cd)
        if (dur <= 0) return 0f
        return (1f - (nowMs - start).toFloat() / dur.toFloat()).coerceIn(0f, 1f)
    }

    /** cb5: the slots in view at once on a LEFT / RIGHT bar (the user's pick: "4 with an arrow on the sides") */
    const val VISIBLE = 4
    /** every mode's slot side (cb4: LEFT / RIGHT the same as BOTH's; cb3's LEFT / RIGHT were 84 at most, 78 on the Thor) */
    const val MAX_SIDE = 112f
    const val MIN_SIDE = 8f
    const val GAP = 8f
    const val PAD = 10f
    const val TAP_SLOP_PX = 24f
    /** cb5: the edge arrow (px) and its fade: full once this part of a slot's share is hidden that way */
    const val ARROW_W = 12f
    const val ARROW_H = 28f
    const val ARROW_FADE = 0.5f
    /** cb5, the fling: light momentum (the finger's speed times [FLING_GAIN]), friction time [FLING_TAU_MS]; it starts at [FLING_MIN]
     *  px/ms and ends under [FLING_STOP]; the finger's speed is read over its last [VELOCITY_MS] */
    const val FLING_GAIN = 0.7f
    const val FLING_TAU_MS = 260f
    const val FLING_MIN = 0.15f
    const val FLING_STOP = 0.02f
    const val VELOCITY_MS = 100L

    /** the live bar (the host sets it from every `castbar` document); listeners hear each change */
    object Live {
        /** cb5: the LEFT / RIGHT strip's scroll in slots' shares (0 = slot 1 leftmost, 4 = slot 8 rightmost, any place between), kept
         *  for the app's run (not saved: a new run starts at its side's [rest]); cb8: it is [side]'s scroll */
        @Volatile var pos: Float = 0f
        /** cb8: the scrolling side [pos] belongs to (null = none laid yet this run) */
        @Volatile var side: Side? = null
        /** cb8: the scroll for [s] now: its kept one, or its [rest] when [pos] is another side's (or none) */
        fun posFor(s: Side): Float = if (side == s) pos else rest(s)
        /** cb8: a bar laid on [s]: a scrolling side other than the kept one starts at its [rest] (BOTH / OFF do not scroll: kept) */
        fun laid(s: Side) { if (scrolls(s) && side != s) { side = s; pos = rest(s) } }
        /** cb8: keep [p] as [s]'s scroll */
        fun keep(s: Side, p: Float) { side = s; pos = p }
        @Volatile var bar: Bar? = null
            private set
        private val listeners = ArrayList<() -> Unit>()
        fun onChange(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
        fun set(b: Bar) {
            if (b == bar) return
            bar = b
            val ls = synchronized(listeners) { listeners.toList() }
            for (l in ls) l()
        }
    }
}
