package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * THE SPELLBOOK'S CONTROLLER TAB (the user 2026-09-30: "Pick a button, then what goes on it"; the game's commands "as small macros the
 * companion makes", General macros; Clear at the end of the Change list; ZL / ZR held shows that set, and the set buttons work by touch).
 * Every size is UI Art's `wow-companion-design/spells/PADTAB_SPEC.md` (the sample `tools/kitpreview/PadTabSample.kt`). [SpellbookView] draws
 * the book's frame and the sixth tab, and hands its pages to this while the tab is open.
 *
 * THE DATA: what is on every button comes from the addon's `padmap` ([PadMapModel.Map]); where the controller is, from `padtabctl`
 * ([PadMapModel.Ctl]). The controller wins: a controller document puts the chosen set and button where the addon has them, and closes the
 * app's own chooser or box. A finger's pick is sent at once as a no-action tap (the addon then acts on the same button), and a short echo
 * guard keeps an older document from moving the choice back.
 *
 * ONE ACTION: Change opens the chooser (app only); a row asks its box first (Replace, then Make or "full"; Clear), and the box's first button
 * is the ONE game action: a command or Clear is one tap on the `talents` row, a spell one tap on its own Bind column. A spell on an empty
 * button goes straight on. In combat nothing changes and no box opens.
 *
 * THE TRIGGERS: while the tab shows, the Thor's controller is READ (PadInput, read-only, one reader at a time) so that holding ZL / ZR shows
 * that set, as the game's own bar does.
 *
 * THE COMBAT PAGE (the user 2026-10-01: "Per button, as small character macros"; UI Art's padtab_combat / padtab_normal): an "Out of combat /
 * In combat" row over the sets. In combat, a button may get a combat spell: the addon makes ONE character macro "/cast [combat] X; Y" and the
 * GAME swaps at the press. Buttons that change carry the game's gold crossed swords; in the combat view the others are dimmed (stays the same).
 * A touch in the combat view sends the one-shot "combat layer" pick (talentn 39) before its ONE action.
 *
 * THE BASE X / Y / A IN FIGHTS (the user 2026-10-01 "Yes, per button"; PadMap m7): in the combat view the base set's Interact / Inspect / Jump
 * take a spell for fights, with no macro (the companion's own secure button, bound only while the game says [combat]); ZL / ZR + the button
 * keep their own layers; B stays the game's Back. Their box says it plainly ("In a fight, A will cast X instead of Jump").
 *
 * p9 THE CONTROLLER ROOM'S TABS AND MOVE (the user 2026-10-07; PadTabCtl p9 / PadMap m32): the tab (WoW Defaults "normal" / "ds" / In
 * combat "combat") is the addon's, drawn by [ControllerView] (the DS tab's pages too); this page draws WoW Defaults and In combat. No views
 * row, no legend; the set row is ZL / ZR / ZL + ZR with their glyphs (no "None": the lit one again = the base set). On a button: an empty one
 * A = Choose, one that holds something A = Change and X = MOVE; carrying (the addon's `carry`), A places, or SWAPS with what is there (a
 * short swap animation at the cursor), B puts it back. A combat spell may go on any button (m32): a spell or an item stays for outside
 * fights, anything else is replaced after its Replace box.
 *
 * p16 THE SPELL RADIAL's TAB (the user's picks 2026-10-07: "A: You choose" -- up to 8 per page, 3 pages, filled in the Controller room like buttons): UI Art's design (MOCK_radial_editor): the page plates "Page 1 / 2 / 3"
 * where the set plates are (the open page raised), under them THE WHEEL ITSELF (UiKit.radialPreview: the wheel the player opens, the
 * cursor's slice lit; on the plates no slice is lit: one gold box); the right page the slice's spell, "Slice 3, page 1", X Move, A Change.
 * The chooser has Spells / General / Professions; Clear in it. The slices are the addon's (SpellRadial.lua through PadTabCtl p16: `rpage`,
 * `rad`); a finger's slice / page is the same talents picks as a button / a bar page, a spell the same Bind column (Book: touchSpell).
 */
class PadTabPage(
    private val host: View,
    /** the tab's cells on spells' own rows, in order: no-action picks, then at most ONE action */
    private val tap: (List<Pair<String, Int>>) -> Unit,
    /** a spell's Bind column (the tab open: the spell goes onto the chosen button) */
    private val tapSpell: (SpellsModel.Entry) -> Unit,
    /** another view reads the controller now (one reader at a time) */
    private val otherReader: () -> Boolean,
    /** the controller's cursor stands on the book's tab row (item 19: where it starts): no cursor is drawn on this page then */
    private val cursorOnTabs: () -> Boolean = { false },
    /** b-druid-forms-bind: a spell's Bind taps on the `spells` row (Book b38: its group, then its offset); null = no touch way */
    private val cellsOf: (SpellsModel.Entry) -> List<Pair<String, Int>>? = { e -> e.padColumn?.let { listOf("spells" to it) } },
) {
    private sealed class Thing {
        class Spell(val e: SpellsModel.Entry) : Thing()
        class Command(val c: PadMapModel.Command, val index: Int) : Thing()
        // p12 the Extra tab's mounts and bag items ([index] = 0-based in the whole Extra list), and its group headings (not a choice)
        class Mount(val x: PadMapModel.Extra, val index: Int) : Thing()
        class Item(val x: PadMapModel.Extra, val index: Int) : Thing()
        class Header(val text: String) : Thing()
        class Binding(val b: PadMapModel.Bind, val index: Int) : Thing()   // m34: a game binding (Jump, Interact, Auto Run, Walk / Run)
        object Clear : Thing()
        object Uncombat : Thing()       // take a button's combat spell away (its Otherwise spell comes back)
        object Unface : Thing()         // take a base X / Y / A's fight spell away (it is the game's own again in fights)
    }

    private class Box(val kind: String, val thing: Thing)

    private var map: PadMapModel.Map? = null
    private var ctl: PadMapModel.Ctl? = null
    private var set = 1
    private var pos = 1
    private var pickUntil = 0L                  // the echo guard: a finger's pick wins over documents older than it
    private var pageWant: Int? = null
    private var pageUntil = 0L
    /** p16 the spell radial's page on show (1-3): the addon's; a finger's plate wins for [ECHO_MS] */
    private var rpage = 1
    private var rpageUntil = 0L
    /** p16 a slice chosen or cleared by touch, shown AT THE TAP (page, slice, spell id; 0 = cleared) until the addon's `rad` agrees */
    private var radWant: Triple<Int, Int, Int>? = null
    private var radWantUntil = 0L
    /** p16 the spell the carried slice holds (its page may not be on show) */
    private var radCarryId = 0
    private var lt = false
    private var rt = false
    private var reader: PadInput? = null

    // the app's own chooser and box (touch); the controller's come from `ctl`
    private var tChooser: String? = null       // p11: "spells" / "general" / "extra"
    /** b-bind-hold-triggers (the user 2026-10-13): the set a finger's chooser was opened on (a trigger held then), kept until it is done */
    private var tLock: Int? = null
    /** p11 the chooser's tabs, the gaps (and LB / RB) round the open one (UI Art's TabGaps) */
    private val cGaps = UiKit.TabGaps(vertical = false, gap = 46f)          // the menu is narrow: a slimmer gap, the tabs keep their words
    private var tBox: Box? = null
    private var scroll = 0f
    private var scrollMax = 0f
    private var lastCtlRow: String? = null
    private var flash: String? = null
    private var flashUntil = 0L
    /** the TAB on show (p9): "normal" (WoW Defaults), "ds" or "combat" (In combat): the addon's; a finger's tab pick wins for [ECHO_MS] */
    private var view = "normal"
    private var viewUntil = 0L
    /** the swap / move animation (p9): two pictures crossing, from the addon's "moved" / "swapped" */
    private class Fly(val a: Bitmap?, val fromA: RectF, val toA: RectF, val b: Bitmap?, val fromB: RectF?, val toB: RectF?, val start: Long)
    private var fly: Fly? = null

    /** PRESS FEEDBACK (the user 2026-10-07, Interact's recipe): a finger's tap keeps its button DOWN 160 ms; the controller's A / B / X
     *  pushes the button it works AT ONCE (the trigger reader below also hands over the held face buttons; display only) */
    private val press = PressFeedback(host).also { it.padTarget = { key -> padButton(key) } }
    private fun isDown(k: Int) = down == k || press.isDown(k)

    /** LOCAL FIRST (the user on b116, 2026-10-07: "the button push is delayed a moment after I push it, its working but it feels slow and laggy"): Move and Cancel are picks (no game action),
     *  so the room shows what they do AT THE PRESS, a finger's or the controller's; the addon's `carry` takes over at its echo (it agrees, or its
     *  note says what became of the carry: a refusal shows at once), or after [ECHO_MS] */
    private var carryLocal: PadMapModel.Carry? = null
    private var carryUntil = 0L
    private fun carryNow(): PadMapModel.Carry? =
        if (SystemClock.uptimeMillis() < carryUntil) carryLocal else ctl?.takeIf { it.on }?.carry

    private fun localFirst(k: Int) {
        if (ctl?.on != true || map?.combat != false) return                // closed, or a fight (the addon refuses there)
        when (k) {
            DOWN_MOVE -> {
                carryLocal = if (view == "radial") PadMapModel.Carry(1, pos, rpage) else PadMapModel.Carry(shown(), pos, page())
                if (view == "radial") radCarryId = radId(pos)
            }
            DOWN_CANCEL -> carryLocal = null
            else -> return
        }
        carryUntil = SystemClock.uptimeMillis() + ECHO_MS
        host.invalidate()
    }

    /** the controller's X / B, NEW presses only, after [press] has pushed the button in: the same local first as a finger's */
    private var padHeld = 0
    private fun padFirst(mask: Int) {
        val fresh = mask and padHeld.inv()
        padHeld = mask
        if (PadInput.isHeld(fresh, PadInput.SLOT_FACE_LEFT) && padButton("PAD3") == DOWN_MOVE) localFirst(DOWN_MOVE)
        if (PadInput.isHeld(fresh, PadInput.SLOT_FACE_RIGHT) && padButton("PAD2") == DOWN_CANCEL) localFirst(DOWN_CANCEL)
    }

    /** the button controller key [key] presses right now (the addon's state: its box, its carry, its focus), or null */
    private fun padButton(key: String): Int? {
        val k = ctl?.takeIf { it.on } ?: return null
        val m = map ?: return null
        if (tBox != null || ctlBox() != null) {
            val single = (tBox?.kind ?: ctlBox()?.kind).let { it == "full" || it == "cfull" }
            return when (key) { "PAD1" -> DOWN_YES; "PAD2" -> if (single) null else DOWN_NO; else -> null }
        }
        if (chooserList() != null || view == "ds") return null
        if (carryNow() != null) return when (key) { "PAD1" -> DOWN_PLACE; "PAD2" -> DOWN_CANCEL; else -> null }
        if (k.focus != "buttons" || m.combat) return null
        if (view == "radial") return when (key) { "PAD1" -> DOWN_CHANGE; "PAD3" -> if (radId(pos) != 0) DOWN_MOVE else null; else -> null }   // p16
        val sl = slotOf(m, shown(), pos) ?: return null
        val fixed = sl.kind == PadMapModel.Kind.FIXED
        val filled = sl.kind != PadMapModel.Kind.NONE && !fixed && sl.kind != PadMapModel.Kind.BIND     // m34: a binding never moves
        return when (key) {
            "PAD1" -> if (!fixed || (PadMapModel.isFace(sl) && view == "combat")) DOWN_CHANGE else null
            "PAD3" -> if (filled) DOWN_MOVE else null
            else -> null
        }
    }


    /** the tab on show, for [ControllerView] */
    fun currentView(): String = view
    /** a finger chose a tab: shown at once; the addon's echo (its padtab pick) follows */
    fun showTabLocal(v: String) { view = v; viewUntil = SystemClock.uptimeMillis() + ECHO_MS; tChooser = null; tBox = null; host.invalidate() }

    // ---------------------------------------------------------------- data in

    fun onMap(m: PadMapModel.Map) {
        map = m
        if (pageWant == m.page) pageWant = null
        host.invalidate()
    }

    fun onCtl(c: PadMapModel.Ctl) {
        val now = SystemClock.uptimeMillis()
        val was = ctl
        // p9: the carried button landed -- the two pictures cross (the map is still the old one: it follows the move)
        val from = was?.carry ?: carryLocal?.takeIf { now < carryUntil }
        if ((c.note == "moved" || c.note == "swapped") && from != null && c.view != "radial") startFly(from, c.pos, c.shown, c.note == "swapped")
        ctl = c
        // a finger's (or the controller's) Move / Cancel shown at the press: the addon's word ends it
        if (now >= carryUntil || (c.carry == null) == (carryLocal == null) || c.note in CARRY_NOTES || !c.on) { carryUntil = 0L; carryLocal = null }
        if (now >= pickUntil || (c.set == set && c.pos == pos)) { set = c.set; pos = c.pos; pickUntil = 0L }
        if (c.view == "radial") {                                           // p16: the radial's page, its slices, the carried spell
            if (now >= rpageUntil || c.rpage == rpage) { rpage = c.rpage; rpageUntil = 0L }
            radWant?.let { (pg, p, id) -> if (now >= radWantUntil || (c.rpage == pg && c.rad.getOrNull(p - 1) == id)) radWant = null }
            val cr = c.carry
            if (cr == null) radCarryId = 0
            else if (radCarryId == 0 && cr.page == c.rpage) radCarryId = c.rad.getOrNull(cr.pos - 1) ?: 0
        }
        if (!PadMapModel.touchNote(c.note)) { tChooser = null; tBox = null }
        // the tab is the addon's (p9); a finger's own pick wins until its echo
        if (now >= viewUntil || c.view == view) { view = c.view; viewUntil = 0L }
        if (c.note == "combat") say(COMBAT)
        if (c.note == "spell-not-in-book") say("That spell is not in the book now")
        if (c.note == "stays-back") say("${buttonWord(7)} stays the game's Back")
        if (c.note == "fixed") say("The game keeps that button for itself out of combat")
        if (c.note == "locked") say("The game keeps that button for itself")
        if (c.note == "nothing-to-move") say("Nothing on this button to move")
        // m34 / p15: a binding where the router cannot reach, and a binding never moves
        if (c.note == "bind-layer") say("Only on a button with no trigger, or with ${setWord(2)}")
        if (c.note == "bind-dpad bar") say("The DS WoW toolbar has the plain D-pad")
        if (c.note == "bind-fixed") say("The game keeps that button for itself")
        if (c.note == "bind-move") say("It rides the key, not the bar: clear it and choose it where you want it")
        if (c.note == "move-refused") say("The game did not move it")
        if (c.note == "not-on-radial") say("The spell radial takes spells only")
        if (c.note == "paused") say(PAUSED_LINE)                            // m40 / p18
        host.invalidate()
    }

    /** the carried button [from] went onto [to] (in set [toSet]); [swap]: what was there went back to [from]'s place */
    private fun startFly(from: PadMapModel.Carry, to: Int, toSet: Int, swap: Boolean) {
        val m = map ?: return
        val a = viewIcon(m, PadMapModel.slot(m, from.set, from.pos), from.pos)
        val toR = square(to)
        val fromA = carriedRect(toR)
        val b = if (swap) viewIcon(m, PadMapModel.slot(m, toSet, to), to) else null
        val back = if (swap && from.set == toSet) square(from.pos) else null
        fly = Fly(a, fromA, toR, b, if (swap) toR else null, back, SystemClock.uptimeMillis())
        host.invalidate()
    }

    /** p12: what the last Extra touch meant (a command key or an id), to check the addon's answer against (the pad owner's rule) */
    private var extraSent: String? = null

    fun onAck(a: PadMapModel.Ack) {
        if (a.op == "extra") {
            val meant = extraSent
            extraSent = null
            if (a.ok && meant != null && a.what != null && a.what != meant) say("Put ${a.name ?: "something else"} instead: check the button")
            else if (!a.ok) say(when (a.why) {
                "combat" -> COMBAT
                "fixed" -> "The game keeps that button for itself"
                "no page picked" -> "The tap did not arrive: tap it again"
                "no entry" -> "That one is gone from the list: look again"
                "room not open" -> "Open the Controller room first"
                "paused" -> PAUSED_LINE
                else -> "The game did not take it" + (a.why?.let { " ($it)" } ?: "")
            })
            host.invalidate()
            return
        }
        if (!a.ok) say(when (a.why) {
            "combat" -> COMBAT
            "fixed" -> "The game keeps that button for itself"
            "no macro slot" -> "All General Macros are in use"
            "no char macro slot" -> "All Character Macros are in use"
            "not a spell" -> "Put a spell on this button first (Out of combat)"
            "not a face button" -> "${buttonWord(7)} stays the game's Back"
            "paused" -> PAUSED_LINE                                         // m40
            // m41 / m43: the plain D-pad is the DS toolbar's while it is on; a game binding rides that key
            "dpad bar" -> "The DS toolbar has the plain D-pad: hold a trigger, or turn the toolbar off"
            "bind" -> "A game key rides this button: clear it first"
            else -> "The game did not take it" + (a.why?.let { " ($it)" } ?: "")
        })
        host.invalidate()
    }

    /** the layout the addon moves by is not the app's: the next touch-open tells it (a no-action pick) */
    fun needsLayout(): Boolean = ctl?.swap != swap()
    fun layoutCell(): Pair<String, Int> = PadMapModel.ROW_LAYOUT to PadMapModel.colLayout(swap())

    /** the tab is shown (true) or not: the triggers are read only while it is, and nothing of the app's own stays open after it */
    fun setActive(on: Boolean) {
        if (on && reader == null && !otherReader()) {
            reader = PadInput(onPad = { l, r, lb, _ ->
                if (l != lt || r != rt) { lt = l; rt = r; host.invalidate() }
                lbEdge(lb)
            }, onStatus = { _, _ -> }, onButtons = { m -> press.onHeld(m); padFirst(m) }).also {
                it.triggerSwap = CastSettings.triggerSwap
                it.start()
            }
        } else if (!on) {
            reader?.stop(); reader = null; lt = false; rt = false
            tChooser = null; tBox = null
        }
    }

    /** p13 / b27 LB FROM THE APP (the user on b124: "the main tabs in the controller room move right with R but wont move back left with L"):
     *  on this device LB is the game's emulated Ctrl (GamePadEmulateCtrl = PADLSHOULDER, measured 2026-09-28), so the capture never hears it.
     *  The shared reader sees the bumper (read-only); on its RELEASE (the game's Ctrl up again), while the addon's room holds the
     *  controller, ONE tap on padtab 9 does what LB does (the chooser's tab before, or the room's). The room's tab shows at once. */
    private var lbHeld = false
    private fun lbEdge(lb: Boolean) {
        if (lb == lbHeld) return
        lbHeld = lb
        if (lb || ctl?.on != true) return
        if (boxOpenNow() == null) {                                     // p30: the chooser open too (LB is the room's tab before)
            val i = PadMapModel.tabOf(view) - 1
            if (i >= 1) showTabLocal(PadMapModel.TABS[i - 1])
        }
        // the tap-timing rule: no tap within ~150 ms of a controller change
        host.postDelayed({ if (ctl?.on == true && host.isShown) tap(listOf(PadMapModel.ROW_PADTAB to PadMapModel.COL_LB)) }, LB_TAP_MS)
    }

    // ---------------------------------------------------------------- what is shown

    private fun style(): SpellsModel.PadStyle = when (CastSettings.buttonLayout) {
        CastSettings.ButtonLayout.XBOX -> SpellsModel.PadStyle.LTR
        CastSettings.ButtonLayout.PLAYSTATION -> SpellsModel.PadStyle.SHP
        CastSettings.ButtonLayout.NINTENDO -> SpellsModel.PadStyle.REV
    }
    private fun swap() = CastSettings.faceSwap                          // f-abxy-split: the ABXY LOCATION, not the glyphs

    /** the set on show: a held trigger's (as the game's bar), else the one chosen; while a chooser or its box is up, the set it opened on */
    private fun shown(): Int = lockedSet() ?: when { lt && rt -> 4; lt -> 2; rt -> 3; else -> set }
    /** b-bind-hold-triggers (the user 2026-10-13: "the layer should stay held until the bind completes, even if the triggers are released"):
     *  the controller's chooser is latched by the add-on (PadTabCtl p26, its `shown`); a finger's by [tLock] */
    private fun lockedSet(): Int? = when {
        view == "radial" -> null
        tChooser != null || tBox != null -> tLock
        ctlChooser() != null || ctlBox() != null -> ctl?.shown
        else -> null
    }
    /** the chooser's layout: the tab's (In combat = fight spells). batch5, the user's pick "No flip in the chooser": its Normal | In
     *  combat pair is gone (the Spellbook's Bind box has the switch) */
    private fun fight(): Boolean = view == "combat"

    // ---------------------------------------------------------------- p29 (f-combat-forms, PadMap m55): the In combat tab's FORM row
    /** the form whose own set the bare set shows now (the In combat tab, a form chosen, no trigger set); null = the caster's */
    private fun formNow(): Int? = ctl?.form?.takeIf { view == "combat" && shown() == 1 && (map?.forms?.any { f -> f.id == it } == true) }
    /** the button as the room shows it: with a form chosen, the bare set's positions are that form's own In-combat spells (empty = the
     *  caster's spell fights there); B (7) stays the game's Back */
    private fun slotOf(m: PadMapModel.Map, s: Int, p: Int): PadMapModel.Slot? {
        val f = formNow()
        if (f == null || s != 1 || p == 7) return PadMapModel.slot(m, s, p)
        val r = PadMapModel.formFight(m, f, p)
        return if (r != null) PadMapModel.Slot(p, null, PadMapModel.Kind.COMBAT, r.id, r.name, r.icon, combat = r, ov = true)
               else PadMapModel.Slot(p, null, PadMapModel.Kind.NONE, null, null, 0)
    }
    /** the form row: Caster, then the forms (between the set row and the buttons) */
    private fun formRect(i: Int) = RectF(40f + i * 112f, 364f, 40f + i * 112f + 100f, 434f)
    /** the form row shows: In combat (a character with forms), or out of combat while the game lays a form bar (PadMap m57, the user's A) */
    private fun formsShown(m: PadMapModel.Map) = m.forms.isNotEmpty() && (view == "combat" || (view == "normal" && m.stance != null))
    private fun drawForms(c: Canvas, m: PadMapModel.Map, book: SpellsModel.Book?) {
        if (!formsShown(m)) return
        val chosen = ctl?.form ?: 0
        val list = listOf<PadMapModel.Form?>(null) + m.forms.take(10)
        for ((i, f) in list.withIndex()) {
            val r = formRect(i)
            if (r.right > SPLIT - 20f) break
            UiKit.choice(c, r, "", (f?.id ?: 0) == chosen)
            // the user on test20: "I don't like the *caster* for forms, it should just be the class Icon": the caster's tab wears the
            // class's own icon (the book's class line, as the chooser's General tab), no word; the word only until the book has come
            val classBmp = if (f == null) book?.lines?.firstOrNull { it.index == 1 }?.let { Icons.get(it.icon) } else null
            if (f == null && classBmp == null) UiKit.choice(c, r, "Caster", 0 == chosen)
            (if (f != null) Icons.get(f.icon) else classBmp)?.let { bmp ->
                val sz = 54f
                c.drawBitmap(bmp, null, RectF(r.centerX() - sz / 2f, r.centerY() - sz / 2f, r.centerX() + sz / 2f, r.centerY() + sz / 2f), PBMP)
            }
        }
        val k = ctl?.takeIf { it.on }
        if (k != null && k.focus == "forms" && SystemClock.uptimeMillis() >= pickUntil) UiKit.focus(c, formRect((k.fcur ?: 0).coerceIn(0, list.size - 1)), onPaper = true)
        // out of combat: the game's own form bar is known only once the player has been in that form (PadMap m57 learns it there)
        val fo = m.forms.firstOrNull { it.id == chosen }
        if (view == "normal" && fo != null && !fo.bar) {
            val p = UiKit.inkPaint(24f).apply { color = Theme.parchmentInkDim }
            p.textAlign = Paint.Align.CENTER
            c.drawText("Shift into ${fo.name} once: the game then shows its bar here", SPLIT / 2f, 806f, p)
        }
    }
    private fun page(): Int {
        val want = pageWant
        return if (want != null && SystemClock.uptimeMillis() < pageUntil) want else map?.page ?: 1
    }

    private fun triggers(): Pair<String, String> = SpellsModel.triggerNames(style())
    private fun setWord(s: Int): String = triggers().let { (l, r) -> when (s) { 2 -> l; 3 -> r; 4 -> "$l + $r"; else -> "None" } }
    private fun buttonWord(p: Int): String = when (p) {
        1 -> "D-pad Left"; 2 -> "D-pad Up"; 3 -> "D-pad Right"; 4 -> "D-pad Down"
        else -> {
            val key = when (p) { 5 -> "x"; 6 -> "y"; 7 -> "b"; else -> "a" }
            if (style() == SpellsModel.PadStyle.SHP)
                mapOf("x" to "Square", "y" to "Triangle", "b" to "Circle", "a" to "Cross").getValue(key)
            else key.uppercase()
        }
    }
    /** a button's words ("LT + X"); the Controller room's clean-up box names its buttons with it */
    fun comboWord(s: Int, p: Int) = (if (s == 1) "" else setWord(s) + " + ") + buttonWord(p)
    private fun glyph(p: Int) = SpellsModel.combo(SpellsModel.PadSlot(0, 1, 1, p), style()).button


    private fun chooserList(): String? = tChooser ?: ctl?.takeIf { it.on }?.chooser?.list
    private fun ctlChooser(): PadMapModel.Chooser? = if (tChooser != null) null else ctl?.takeIf { it.on }?.chooser
    private fun ctlBox(): PadMapModel.Box? = if (tBox != null) null else ctl?.takeIf { it.on }?.box

    private fun say(text: String) { flash = text; flashUntil = SystemClock.uptimeMillis() + FLASH_MS; host.postDelayed({ host.invalidate() }, FLASH_MS + 50) }

    /** m34: Jump and Interact wear the room's own fixed pictures (the game's gamepad art, UI Art), the others their fileID */
    private fun bindIcon(key: String?, icon: Int) = when (key) {
        "JUMP" -> UiKit.tex("fixed_jump"); "INTERACTTARGET" -> UiKit.tex("fixed_interact"); else -> Icons.get(icon)
    }

    /** m34 why the chosen button cannot take a game binding (PadMap's rule), or null when it can */
    private fun bindWhy(m: PadMapModel.Map): String? {
        val s = shown()
        return when {
            s == 1 && pos >= 5 -> "The game keeps that button for itself"
            s == 3 || s == 4 -> "Only on a button with no trigger, or with ${setWord(2)}: the game keeps ${setWord(3)} to itself"
            s == 1 && m.dpad?.want == true -> "The DS WoW toolbar has the plain D-pad: turn it off, or use ${setWord(2)}"
            else -> null
        }
    }

    /** m34 the same, as the end of one short line under the chooser ("Jump" + it) */
    private fun bindShort(m: PadMapModel.Map): String? {
        val s = shown()
        return when {
            s == 1 && pos >= 5 -> "cannot go on the game's own button"
            s == 3 || s == 4 -> "works on buttons without a trigger, or with ${setWord(2)}"
            s == 1 && m.dpad?.want == true -> "needs the DS WoW toolbar off, or ${setWord(2)}"
            else -> null
        }
    }

    private fun iconOf(m: PadMapModel.Map, sl: PadMapModel.Slot?, p: Int) = when {
        sl == null || sl.kind == PadMapModel.Kind.NONE -> null
        sl.kind == PadMapModel.Kind.BIND -> bindIcon(sl.key, sl.icon)
        sl.kind == PadMapModel.Kind.FIXED -> UiKit.tex(FIXED_ART[(p - 5).coerceIn(0, 3)])
        else -> PadMapModel.commandOn(m, sl)?.let { Icons.get(it.icon) } ?: Icons.get(sl.icon)
    }

    private fun nameOf(m: PadMapModel.Map, sl: PadMapModel.Slot?): String = when {
        sl == null || sl.kind == PadMapModel.Kind.NONE -> "Empty"
        else -> PadMapModel.commandOn(m, sl)?.name ?: sl.name ?: "Set in the game"
    }

    /** the combat view, or a combat button out of combat: spells only (the addon's rows do the same) */
    private fun onlySpells(m: PadMapModel.Map) = fight() || view == "radial" ||                  // p16: a radial casts spells
        slotOf(m, shown(), pos)?.kind == PadMapModel.Kind.COMBAT

    /** p11 the chooser's tabs (the user 2026-10-07: "in the controller menu, when assigning a spell, the spell menu needs some tabs. start on spells, then general (including professions), then extra"); a fight spell's chooser has no Extra */
    private fun chooserTabs(m: PadMapModel.Map) =
        if (onlySpells(m)) listOf("spells", "general", "professions") else listOf("spells", "general", "professions", "extra")   // p14

    private fun rowsOf(list: String, book: SpellsModel.Book?, m: PadMapModel.Map): List<Thing> {
        if (list != "extra" || onlySpells(m)) {
            val which = if (list == "extra") "spells" else list
            // the combat view leaves out the spells already on the button (the same spell in and out of a fight only spends a macro)
            val skip = if (fight()) PadMapModel.onButton(slotOf(m, shown(), pos)) else emptySet()
            val spells = book?.let { b -> SpellsModel.tabSpells(b, which).filter { (it.spellId ?: it.actionId) !in skip }.map { Thing.Spell(it) } }
                ?: emptyList()
            // (m45 / p24: no "suggestion" row - the user: nothing is ever suggested; the add-on's chooser has none either, so rows line up)
            return spells
        }
        // p12 (the user's picks 2026-10-07: the ready-made commands, "Any Item from a bag (mount hearthstone etc)"): the commands, then the mounts, then the bags'
        // items, each group under its heading once there is more than one group
        val cmds = m.commands.mapIndexed { i, c -> Thing.Command(c, i + 1) }
        if (m.binds.isEmpty() && m.mounts.isEmpty() && m.items.isEmpty()) return cmds
        val out = ArrayList<Thing>()
        if (cmds.isNotEmpty()) { out += Thing.Header("Commands"); out += cmds }             // m40: none while paused
        // m34: the game's bindings (the router points the button's key at them), then m33's mounts and items
        if (m.binds.isNotEmpty()) { out += Thing.Header("Controller"); m.binds.forEachIndexed { j, b -> out += Thing.Binding(b, m.commands.size + j) } }
        val n0 = m.commands.size + m.binds.size
        if (m.mounts.isNotEmpty()) { out += Thing.Header("Mounts"); m.mounts.forEachIndexed { j, x -> out += Thing.Mount(x, n0 + j) } }
        if (m.items.isNotEmpty()) {
            out += Thing.Header("Items")
            m.items.forEachIndexed { j, x -> out += Thing.Item(x, n0 + m.mounts.size + j) }
        }
        return out
    }
    /** the row pinned under the list: "Clear combat spell" (combat view, a combat button), "Clear this button" (out of combat, filled) */
    private fun pinned(m: PadMapModel.Map): Thing? {
        if (view == "radial") return if (radId(pos) != 0) Thing.Clear else null            // p16: "Clear this slice"
        val sl = slotOf(m, shown(), pos)
        return if (fight()) when {
            sl?.kind == PadMapModel.Kind.COMBAT -> Thing.Uncombat
            PadMapModel.isFace(sl) && sl!!.fight != null -> Thing.Unface
            else -> null
        }
        else if (PadMapModel.replaces(m, shown(), pos) != null) Thing.Clear else null
    }

    private fun idOf(t: Thing): String = when (t) {
        is Thing.Spell -> (t.e.spellId ?: t.e.actionId).toString()
        is Thing.Command -> t.c.key
        is Thing.Mount -> "m:" + t.x.id
        is Thing.Item -> "i:" + t.x.id
        is Thing.Header -> "h:" + t.text
        is Thing.Binding -> t.b.key
        Thing.Clear -> "clear"
        Thing.Uncombat -> "uncombat"
        Thing.Unface -> "uncface"
    }

    private fun thingName(t: Thing): String = when (t) {
        is Thing.Spell -> t.e.name
        is Thing.Command -> t.c.name
        is Thing.Mount -> t.x.name
        is Thing.Item -> t.x.name
        is Thing.Header -> t.text
        is Thing.Binding -> t.b.name
        Thing.Clear -> "Clear"
        Thing.Uncombat, Thing.Unface -> "Clear combat spell"
    }

    /** the thing a CONTROLLER box is about, found in the app's own lists */
    private fun thingOf(b: PadMapModel.Box, book: SpellsModel.Book?, m: PadMapModel.Map): Thing? = when (b.thing) {
        "clear" -> Thing.Clear
        "uncombat" -> Thing.Uncombat
        "uncface" -> Thing.Unface
        "combat", "otherwise", "cface" -> book?.let { bk -> SpellsModel.tabSpells(bk).firstOrNull { idOf(Thing.Spell(it)) == b.what } }?.let { Thing.Spell(it) }
        "command" -> m.commands.indexOfFirst { it.key == b.what }.takeIf { it >= 0 }?.let { Thing.Command(m.commands[it], it + 1) }
        "mount", "item", "bind" -> rowsOf("extra", book, m).firstOrNull { idOf(it) == b.what }
        "spell", "rspell" -> book?.let { bk -> SpellsModel.tabSpells(bk).firstOrNull { idOf(Thing.Spell(it)) == b.what } }?.let { Thing.Spell(it) }
        else -> null
    }

    // ---------------------------------------------------------------- geometry (SPEC 3-6)

    /** p9: the set row is ZL / ZR / ZL + ZR (sets 2-4; [i] 0-2), their glyphs, no "None" */
    private fun setRect(i: Int) = RectF(40f + i * 222f, 280f, 40f + i * 222f + 208f, 350f)
    private fun centre(p: Int): Pair<Float, Float> {
        val cx = if (p <= 4) 190f else 556f; val cy = 590f
        return when (PadMapModel.drawnSpot(p, swap())) { 0 -> cx - 100f to cy; 1 -> cx to cy - 100f; 2 -> cx + 100f to cy; else -> cx to cy + 100f }
    }
    private fun square(p: Int) = centre(p).let { (x, y) -> RectF(x - 40f, y - 40f, x + 40f, y + 40f) }
    private fun actRect(w: Float, h: Float) = RectF(SPLIT + 40f, h - 141f, w - 56f, h - 30f)
    /** p9: the second button, over the first: X = Move (or, carrying, B = Cancel) */
    private fun moveRect(w: Float, h: Float) = RectF(SPLIT + 40f, h - 252f, w - 56f, h - 160f)
    private fun chooserBox(w: Float, h: Float) = RectF(SPLIT + 10f, 166f, w - 16f, h - 12f)
    /** p16 the radial's page plates (where the switches / set plates stand) and its wheel under them, as large as the page allows (UI Art: 540) */
    private fun plateRect(i: Int) = RectF(40f + i * 222f, 196f, 40f + i * 222f + 208f, 266f)
    private fun wheelD(h: Float) = minOf(540f, h - 300f - 24f)
    private fun wheelCy(h: Float) = 296f + wheelD(h) / 2f
    /** where slice [p] (1-8) stands on the wheel: a button-sized square on its picture (the carried spell rides it) */
    private fun sliceRect(p: Int, h: Float): RectF {
        val d = wheelD(h); val r = 161.5f * d / 540f; val half = 40f * d / 540f
        val a = Math.toRadians((p - 1) * 45.0)
        val x = WHEEL_CX + r * kotlin.math.sin(a).toFloat(); val y = wheelCy(h) - r * kotlin.math.cos(a).toFloat()
        return RectF(x - half, y - half, x + half, y + half)
    }
    private fun dialogRect(w: Float) = RectF((w - 1040f) / 2f, 250f, (w + 1040f) / 2f, 640f)
    private fun yesRect(w: Float, r: RectF, single: Boolean) =
        if (single) RectF(w / 2f - 190f, r.bottom - 161f, w / 2f + 190f, r.bottom - 50f)
        else RectF(w / 2f - 14f - 380f, r.bottom - 161f, w / 2f - 14f, r.bottom - 50f)
    private fun noRect(w: Float, r: RectF) = RectF(w / 2f + 14f, r.bottom - 161f, w / 2f + 14f + 380f, r.bottom - 50f)
    private fun listTop(box: RectF) = box.top + TABS_TOP + 100f
    private fun clearTop(box: RectF) = box.bottom - 196f
    private fun listBottom(box: RectF, withClear: Boolean) = if (withClear) clearTop(box) - 8f else box.bottom - 112f

    // ---------------------------------------------------------------- drawing

    fun draw(c: Canvas, w: Float, h: Float, book: SpellsModel.Book?) {
        val m = map
        if (m == null) {
            val p = UiKit.inkPaint(28f).apply { textAlign = Paint.Align.CENTER }
            c.drawText("Waiting for the game's buttons", SPLIT / 2f, 520f, p)
            return
        }
        if (view == "radial") { drawRadial(c, m, w, h, book); return }
        val s = shown()
        val k = ctl?.takeIf { it.on }
        val open = chooserList() != null || tBox != null || ctlBox() != null
        // p9: ZL / ZR / ZL + ZR, the trigger glyphs on the game's buttons (no words); the lit one is the set on show
        val trig = SpellsModel.combo(SpellsModel.PadSlot(0, 4, 4, 1), style()).triggers
        for (i in 0..2) {
            val r = setRect(i)
            UiKit.choice(c, r, "", i + 2 == s)
            val g = when (i) { 0 -> listOf(trig[0]); 1 -> listOf(trig[1]); else -> trig }
            if (g.size == 1) UiKit.pad(c, g[0], r.centerX(), r.centerY(), 56f)
            else { UiKit.pad(c, g[0], r.centerX() - 32f, r.centerY(), 52f); UiKit.pad(c, g[1], r.centerX() + 32f, r.centerY(), 52f) }
        }
        drawForms(c, m, book)
        val carry = carryNow()
        for (p in 1..8) drawButton(c, m, s, p)
        // the glyphs over ALL the buttons: drawn with its own button, the bottom face button's was half hidden by the next one's frame
        // (it read as a lowercase "b"; UI Art 2026-10-01)
        for (p in 1..8) square(p).let { r -> UiKit.pad(c, glyph(p), r.centerX() + 36f, r.centerY() - 36f, 40f) }
        // p9 CARRYING: the place it left, a faint ghost; what is under the cursor shows the game's swap mark when it is taken
        if (carry != null && carry.set == s) {
            val r = square(carry.pos)
            c.drawRoundRect(RectF(r.left + 4f, r.top + 4f, r.right - 4f, r.bottom - 4f), 6f, 6f, PGHOST)
        }
        if (carry != null && !(carry.set == s && carry.pos == pos)) {
            val under = PadMapModel.slot(m, s, pos)
            if (under != null && under.kind != PadMapModel.Kind.NONE && under.kind != PadMapModel.Kind.FIXED) swapMark(c, square(pos))
        }
        // ONE cursor: on the set buttons, or on the chosen button (the controller's or a finger's); the tabs and the switches are the room's
        val quiet = SystemClock.uptimeMillis() >= pickUntil
        if (cursorOnTabs() && !open) {}
        else if (k != null && k.focus == "sets" && !open && quiet) UiKit.focus(c, setRect((k.scur - 2).coerceIn(0, 2)), onPaper = true)
        else if (k != null && (k.focus == "tabs" || k.focus == "switches") && !open && quiet) {}
        else if (chooserList() != null) {}                                  // the edited button wears its checked mark (drawButton)
        else UiKit.slotFocus(c, square(pos))
        // the carried picture rides the cursor
        if (carry != null) UiKit.carried(c, viewIcon(m, PadMapModel.slot(m, carry.set, carry.pos), carry.pos), square(pos))
        drawFly(c)
        UiKit.bookPaging(c, 330f, h - 70f, page(), m.pages.coerceIn(1, 3))
        drawRight(c, m, s, w, h)
        chooserList()?.let { drawChooser(c, m, book, s, it, w, h) }
        drawBox(c, m, book, s, w)
    }

    // ---------------------------------------------------------------- p16 the spell radial's tab

    /** the spell on slice [p] (1-8) of the radial's page on show, 0 = empty: a touch's choice at once, else the addon's `rad` */
    private fun radId(p: Int): Int {
        radWant?.takeIf { SystemClock.uptimeMillis() < radWantUntil && it.first == rpage }?.let { (_, wp, wid) ->
            if (wp == p) return wid
            if (wid != 0 && ctl?.rad?.getOrNull(p - 1) == wid) return 0                      // one place per spell: it left this slice
        }
        return ctl?.takeIf { it.view == "radial" && it.rpage == rpage }?.rad?.getOrNull(p - 1) ?: 0
    }

    private var radBook: SpellsModel.Book? = null
    private var radIndex: Map<Int, SpellsModel.Entry> = emptyMap()
    /** a spell of the book by its id (a lower rank the picker showed too), for a slice's name and picture */
    private fun radEntry(book: SpellsModel.Book?, id: Int): SpellsModel.Entry? {
        if (book == null || id == 0) return null
        if (book !== radBook) {
            val all = SpellsModel.tabSpells(book)
            radIndex = (all + all.flatMap { SpellsModel.ranksOf(book, it) }).associateBy { it.spellId ?: it.actionId }
            radBook = book
        }
        return radIndex[id]
    }
    private fun radIcon(book: SpellsModel.Book?, id: Int) = radEntry(book, id)?.let { Icons.get(it.icon) }
    private fun radName(book: SpellsModel.Book?, id: Int) = radEntry(book, id)?.name ?: if (id == 0) "Empty" else "A spell not in the book now"
    private fun sliceWord(p: Int, pg: Int) = "Slice $p, page $pg"

    /** UI Art's MOCK_radial_editor: the page plates, the wheel itself (the cursor's slice lit), the slice's page on the right */
    private fun drawRadial(c: Canvas, m: PadMapModel.Map, w: Float, h: Float, book: SpellsModel.Book?) {
        val k = ctl?.takeIf { it.on }
        val open = chooserList() != null || tBox != null || ctlBox() != null
        for (i in 0..2) UiKit.button(c, plateRect(i), "Page ${i + 1}", if (i + 1 == rpage) UiKit.Button.UP else UiKit.Button.DOWN)
        val carry = carryNow()
        val onPlates = k != null && k.focus == "rpages" && !open
        val offWheel = onPlates || (k != null && (k.focus == "tabs" || k.focus == "switches") && !open)
        val items = (1..8).map { p ->
            val id = radId(p)
            // the carried slice rides the cursor: its own place shows empty
            if (id == 0 || (carry != null && carry.page == rpage && carry.pos == p)) null else UiKit.RadialItem(radIcon(book, id), radName(book, id), -1)
        }
        UiKit.radialPreview(c, WHEEL_CX, wheelCy(h), wheelD(h), items, if (offWheel) -1 else pos - 1)
        if (onPlates) UiKit.focus(c, plateRect(rpage - 1), onPaper = true)
        if (carry != null && !offWheel) UiKit.carried(c, radIcon(book, radCarryId), sliceRect(pos, h))
        if (carry != null) drawRadialMove(c, m, carry, w, h, book) else drawRadialRight(c, m, w, h, book)
        chooserList()?.let { drawChooser(c, m, book, 1, it, w, h) }
        drawBox(c, m, book, 1, w)
    }

    /** the right page: the slice's spell, "Slice 3, page 1", X Move, A Change ("Choose a spell for this slice." when empty) */
    private fun drawRadialRight(c: Canvas, m: PadMapModel.Map, w: Float, h: Float, book: SpellsModel.Book?) {
        val x0 = SPLIT + 40f; val x1 = w - 56f; val top = 188f
        val id = radId(pos)
        UiKit.spell(c, radIcon(book, id), x0 + 36f, top + 16f, 96f, UiKit.Spell.KNOWN)
        val name = radName(book, id)
        val nameLeft = x0 + 158f; val nameW = x1 - nameLeft
        val pn = UiKit.inkPaint(40f, heading = true)
        while (pn.textSize > 28f && pn.measureText(name) > nameW) pn.textSize -= 1f
        c.drawText(TextUtils.ellipsize(name, TextPaint(pn), nameW, TextUtils.TruncateAt.END).toString(), nameLeft, top + 30f - pn.ascent() - 4f, pn)
        val dim = UiKit.inkPaint(26f).apply { color = Theme.parchmentInkDim }
        c.drawText(sliceWord(pos, rpage), nameLeft + 2f, top + 30f + pn.textSize + 34f, dim)
        UiKit.tex("book_divider")?.let { c.drawBitmap(it, null, RectF(x0, top + 150f, x1, top + 161f), PBMP) }
        // the wheels' behaviour is Home's one pair for every wheel (Home 2026-10-09: open Tap / Hold, pick A / Release; the DS tab mirrors it)
        val way = when {
            HomeSettings.radialTap && HomeSettings.radialPickA -> "Tap it to open the wheel; A casts."
            HomeSettings.radialTap -> "Tap it to open the wheel; point, then let the stick go to cast."
            HomeSettings.radialPickA -> "Hold it to keep the wheel open; A casts."
            else -> "Hold it to keep the wheel open; point, then let the stick go to cast."
        }
        val help = (if (id == 0) "Choose a spell for this slice. " else "") +
            "Put the Spell Radial command on a button (WoW Defaults: Change, Extra). $way"
        val pd = TextPaint(UiKit.inkPaint(24f))
        val lay = StaticLayout.Builder.obtain(help, 0, help.length, pd, (x1 - x0 - 16f).toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(4f, 1f).setMaxLines(5).build()
        c.save(); c.translate(x0 + 8f, top + 186f); lay.draw(c); c.restore()
        val act = actRect(w, h)
        val filled = id != 0
        val above = if (filled) moveRect(w, h).top else act.top
        val note = flash?.takeIf { SystemClock.uptimeMillis() < flashUntil } ?: if (m.combat) COMBAT else null
        note?.let {
            val pw = UiKit.inkPaint(24f).apply { color = Color.rgb(150, 30, 20); textAlign = Paint.Align.CENTER }
            c.drawText(it, act.centerX(), above - 30f, pw)
        }
        if (filled) UiKit.button(c, moveRect(w, h), "Move", glyph(5), when {
            m.combat -> UiKit.Button.DISABLED; isDown(DOWN_MOVE) -> UiKit.Button.DOWN; else -> UiKit.Button.UP })
        UiKit.button(c, act, if (filled) "Change" else "Choose", glyph(8), when {
            m.combat || !changeHere() -> UiKit.Button.DISABLED; isDown(DOWN_CHANGE) -> UiKit.Button.DOWN; else -> UiKit.Button.UP })
    }

    /** carrying a slice (the button room's Move page): what moves, from where; what goes back on a swap; A Place / Swap, B Cancel */
    private fun drawRadialMove(c: Canvas, m: PadMapModel.Map, carry: PadMapModel.Carry, w: Float, h: Float, book: SpellsModel.Book?) {
        val x0 = SPLIT + 40f; val x1 = w - 56f; val top = 188f
        val pt = UiKit.inkPaint(40f, heading = true)
        c.drawText("Move", x0 + 8f, top + 30f - pt.ascent() - 4f, pt)
        val same = carry.page == rpage && carry.pos == pos
        val under = if (same) 0 else radId(pos)
        val pl = UiKit.inkPaint(22f).apply { color = Theme.parchmentInkDim }
        val ps = UiKit.inkPaint(30f)
        val rows = mutableListOf(Triple(radIcon(book, radCarryId), radName(book, radCarryId), "from " + sliceWord(carry.pos, carry.page).lowercase()))
        if (under != 0) rows += Triple(radIcon(book, under), radName(book, under), "goes to " + sliceWord(carry.pos, carry.page).lowercase())
        for ((i, row) in rows.withIndex()) {
            val y = top + 96f + i * 100f
            UiKit.spell(c, row.first, x0 + 8f, y + 8f, 72f, UiKit.Spell.KNOWN)
            c.drawText(TextUtils.ellipsize(row.second, TextPaint(ps), x1 - x0 - 110f, TextUtils.TruncateAt.END).toString(), x0 + 100f, y + 38f, ps)
            c.drawText(row.third, x0 + 100f, y + 70f, pl)
        }
        val help = when { same -> "Choose the slice it goes on (Up: another page)."; under != 0 -> "The two swap places."; else -> "On an empty slice it just moves." }
        val lay = StaticLayout.Builder.obtain(help, 0, help.length, TextPaint(UiKit.inkPaint(23f)), (x1 - x0 - 16f).toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(4f, 1f).setMaxLines(3).build()
        c.save(); c.translate(x0 + 8f, top + 316f); lay.draw(c); c.restore()
        flash?.takeIf { SystemClock.uptimeMillis() < flashUntil }?.let {
            val pw = UiKit.inkPaint(24f).apply { color = Color.rgb(150, 30, 20); textAlign = Paint.Align.CENTER }
            c.drawText(it, actRect(w, h).centerX(), moveRect(w, h).top - 26f, pw)
        }
        UiKit.button(c, moveRect(w, h), "Cancel", glyph(7), if (isDown(DOWN_CANCEL)) UiKit.Button.DOWN else UiKit.Button.UP)
        UiKit.button(c, actRect(w, h), if (under != 0) "Swap" else "Place", glyph(8), when {
            m.combat || same -> UiKit.Button.DISABLED; isDown(DOWN_PLACE) -> UiKit.Button.DOWN; else -> UiKit.Button.UP })
    }

    /**
     * What a button shows in the view on show. IN COMBAT ONLY THE PLAYER'S OWN IN-COMBAT SETTINGS (the user after test14: "if I was
     * someone logging in new this should be blank"): an overlay fight spell (m43 `ov`) or a base X / Y / A's fight spell - never a game
     * action slot's contents, never an old macro's spell: anything else is BLANK. Out of combat: a combat button's Otherwise, else the slot.
     */
    private fun viewIcon(m: PadMapModel.Map, sl: PadMapModel.Slot?, p: Int) = when {
        view == "combat" -> when {
            sl?.kind == PadMapModel.Kind.COMBAT && sl.ov -> sl.combat?.let { Icons.get(it.icon) }
            sl?.fight != null && PadMapModel.isFace(sl) -> Icons.get(sl.fight.icon)
            else -> null
        }
        sl?.kind == PadMapModel.Kind.COMBAT -> sl.otherwise?.let { Icons.get(it.icon) }
        else -> iconOf(m, sl, p)
    }
    /** the button changes in a fight: the player's own overlay fight spell, or a base X / Y / A with a fight spell (an old macro: no) */
    private fun changesInFight(sl: PadMapModel.Slot?) = (sl?.kind == PadMapModel.Kind.COMBAT && sl.ov) || (PadMapModel.isFace(sl) && sl!!.fight != null)

    private fun drawButton(c: Canvas, m: PadMapModel.Map, s: Int, p: Int) {
        val sl = slotOf(m, s, p)
        val r = square(p)
        val fixed = sl?.kind == PadMapModel.Kind.FIXED
        val changes = changesInFight(sl)
        // the base X / Y / A can change in the combat view: no lock there (B keeps it: it stays the game's Back)
        val locked = fixed && !(view == "combat" && PadMapModel.isFace(sl))
        val icon = viewIcon(m, sl, p)
        UiKit.spell(c, icon, r.left, r.top, 80f, if (fixed && !(view == "combat" && changes)) UiKit.Spell.LEVEL else UiKit.Spell.KNOWN)
        // UI Art (the b128 frames): ONE gold box -- while the chooser is open it is the chooser's; the button being edited wears the game's
        // CHECKED action-button mark round its frame (UiKit.checkedGlow, kit picture slot_checked), not a box
        if (p == pos && chooserList() != null) UiKit.checkedGlow(c, RectF(r.left, r.top, r.left + 80f, r.top + 80f))
        // in combat, a button without a combat spell keeps its normal one: dimmed (UI Art: ink 150 / 255 over it)
        if (view == "combat" && !changes && icon != null) c.drawRoundRect(RectF(r.left + 4f, r.top + 4f, r.right - 4f, r.bottom - 4f), 6f, 6f, PDIM)
        if (locked) UiKit.tex("talent_lock")?.let { c.drawBitmap(it, null, RectF(r.right - 20f, r.bottom - 30f, r.right - 2f, r.bottom - 4f), PBMP) }
        if (changes) swords(c, r.left + 8f, r.bottom - 10f, 34f)
    }

    /** where [UiKit.carried] draws a carried picture over [slot] (the same numbers) */
    private fun carriedRect(slot: RectF): RectF {
        val sz = slot.width() * 0.74f
        val x = slot.left + slot.width() * 0.52f; val y = slot.top + slot.height() * 0.52f
        return RectF(x, y, x + sz, y + sz)
    }

    /** the game's swap mark on a taken target (UI Art's kit picture; two gold arrows until it is on the device) */
    private fun swapMark(c: Canvas, r: RectF) {
        val t = UiKit.tex("ctl_swap") ?: UiKit.tex("battlebar_swappeticon")
        if (t != null) { c.drawBitmap(t, null, RectF(r.right - 34f, r.top - 6f, r.right + 6f, r.top + 34f), PBMP); return }
        // two gold arrows drawn as lines (no font glyph: the game's font may not have one)
        val cx = r.right - 14f; val cy = r.top + 14f
        PSWAP.color = Theme.gold
        c.drawLine(cx - 12f, cy - 5f, cx + 12f, cy - 5f, PSWAP); c.drawLine(cx + 12f, cy - 5f, cx + 6f, cy - 11f, PSWAP)
        c.drawLine(cx + 12f, cy + 5f, cx - 12f, cy + 5f, PSWAP); c.drawLine(cx - 12f, cy + 5f, cx - 6f, cy + 11f, PSWAP)
    }

    /** the two pictures crossing after a move / swap (FLY_MS), then gone */
    private fun drawFly(c: Canvas) {
        val f = fly ?: return
        val t = ((SystemClock.uptimeMillis() - f.start).toFloat() / FLY_MS).coerceIn(0f, 1f)
        if (t >= 1f) { fly = null; return }
        val e = 1f - (1f - t) * (1f - t)                                    // ease out
        fun lerp(a: RectF, b: RectF) = RectF(a.left + (b.left - a.left) * e, a.top + (b.top - a.top) * e,
                                              a.right + (b.right - a.right) * e, a.bottom + (b.bottom - a.bottom) * e)
        f.a?.let { c.drawBitmap(it, null, lerp(f.fromA, f.toA), PBMP) }
        if (f.b != null && f.fromB != null) {
            val to = f.toB ?: carriedRect(f.fromB)
            PBMP.alpha = if (f.toB == null) (255 * (1f - e)).toInt() else 255
            c.drawBitmap(f.b, null, lerp(f.fromB, to), PBMP)
            PBMP.alpha = 255
        }
        host.postInvalidateOnAnimation()
    }

    /** the game's gold crossed swords (UI Art's kit picture combat_swords), centred at ([cx], [cy]) */
    private fun swords(c: Canvas, cx: Float, cy: Float, size: Float) {
        UiKit.tex("combat_swords")?.let { c.drawBitmap(it, null, RectF(cx - size / 2f, cy - size * 0.47f, cx + size / 2f, cy + size * 0.47f), PBMP) }
    }

    private fun drawRight(c: Canvas, m: PadMapModel.Map, s: Int, w: Float, h: Float) {
        val slc = slotOf(m, s, pos)
        carryNow()?.let { drawMoveRight(c, m, s, it, w, h); return }
        if (view == "combat" || slc?.kind == PadMapModel.Kind.COMBAT) { drawCombatRight(c, m, s, slc, w, h); return }
        val x0 = SPLIT + 40f; val x1 = w - 56f; val top = 188f
        val sl = slc
        val fixed = sl?.kind == PadMapModel.Kind.FIXED
        val cmd = sl?.let { PadMapModel.commandOn(m, it) }
        UiKit.spell(c, iconOf(m, sl, pos), x0 + 36f, top + 16f, 96f, if (fixed) UiKit.Spell.LEVEL else UiKit.Spell.KNOWN)

        // the name: 40 px, smaller down to 28 to stay on one line, then two lines (as the book's own right page)
        val name = nameOf(m, sl)
        val nameLeft = x0 + 158f; val nameW = x1 - nameLeft
        val pn = UiKit.inkPaint(40f, heading = true)
        while (pn.textSize > 28f && pn.measureText(name) > nameW) pn.textSize -= 1f
        val nameBottom: Float
        if (pn.measureText(name) <= nameW) {
            c.drawText(name, nameLeft, top + 30f - pn.ascent() - 4f, pn)
            nameBottom = top + 30f + pn.textSize
        } else {
            val lay = StaticLayout.Builder.obtain(name, 0, name.length, TextPaint(pn), nameW.toInt())
                .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).build()
            c.save(); c.translate(nameLeft, top + 30f - 4f); lay.draw(c); c.restore()
            nameBottom = top + 30f - 4f + lay.height
        }
        val kind = when (sl?.kind) {
            PadMapModel.Kind.SPELL -> if (sl.startsAttack) "Spell, starts the attack" else "Spell"
            PadMapModel.Kind.ITEM -> "Item"
            PadMapModel.Kind.MACRO -> cmd?.body?.let { "Macro: $it" } ?: "Macro"
            PadMapModel.Kind.FIXED -> "Set by the game"
            PadMapModel.Kind.OTHER -> "Set in the game"
            PadMapModel.Kind.BIND -> "The game's own key"
            PadMapModel.Kind.COMMAND -> "Command (no macro)"                 // m41: the overlay
            else -> null
        }
        val dim = UiKit.inkPaint(24f).apply { color = Theme.parchmentInkDim }
        // smaller to fit the page ("Spell, starts the attack", a command's line): never past the page's edge
        kind?.let { while (dim.measureText(it) > x1 - x0 - 160f && dim.textSize > 18f) dim.textSize -= 1f }
        kind?.let { c.drawText(TextUtils.ellipsize(it, TextPaint(dim), x1 - x0 - 160f, TextUtils.TruncateAt.END).toString(), x0 + 160f, nameBottom + 28f, dim) }

        // On: the set's trigger(s) + the button, as the game names it
        val cy = top + 166f
        val pOn = UiKit.inkPaint(28f).apply { color = Theme.parchmentInkDim }
        c.drawText("On", x0 + 8f, cy - 2f - (pOn.ascent() + pOn.descent()) / 2f, pOn)
        val combo = SpellsModel.combo(SpellsModel.PadSlot(0, 1, s, pos), style())
        var x = x0 + 66f
        val trigger = when (combo.triggers.size) {
            2 -> { UiKit.pad(c, combo.triggers[0], x + 28f, cy, 56f); x += 62f; combo.triggers[1] }
            1 -> combo.triggers[0]
            else -> null
        }
        UiKit.padCombo(c, trigger, combo.button, x, cy, 56f, Theme.parchmentInk, shadow = false)
        UiKit.tex("book_divider")?.let { c.drawBitmap(it, null, RectF(x0, top + 204f, x1, top + 215f), PBMP) }

        // one line of help
        val (l, r) = triggers()
        val help = when {
            fixed && sl?.fight != null -> "Out of combat the game keeps this button for itself. In a fight it casts ${sl.fight.name} (In combat, above)."
            PadMapModel.isFace(sl) -> "Out of combat the game keeps this button for itself. In combat (above) it can cast a spell in fights."
            fixed -> "The game keeps this button for itself in the base set: it cannot change. Hold $l or $r for buttons you can set."
            else -> "Change puts a spell from your book, or one of the game's commands, on this button."
        }
        val pd = TextPaint(UiKit.inkPaint(24f))
        val lay = StaticLayout.Builder.obtain(help, 0, help.length, pd, (x1 - x0 - 16f).toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(4f, 1f).build()
        c.save(); c.translate(x0 + 8f, top + 236f); lay.draw(c); c.restore()

        // p9 THE VERBS: an empty button A = Choose; one that holds something A = Change and X = Move; and the macro slots (always in sight)
        val act = actRect(w, h)
        val filled = sl != null && sl.kind != PadMapModel.Kind.NONE && !fixed
        val above = if (filled) moveRect(w, h).top else act.top
        // m41: no macro count any more: nothing here spends one of the player's macros (the user's no-trace ruling 2026-10-09)
        val note = flash?.takeIf { SystemClock.uptimeMillis() < flashUntil } ?: if (m.combat && !fixed) COMBAT else null
        note?.let {
            val pw = UiKit.inkPaint(24f).apply { color = Color.rgb(150, 30, 20); textAlign = Paint.Align.CENTER }
            c.drawText(it, act.centerX(), above - 64f, pw)
        }
        val off = m.combat || sl?.storage == null
        val moveOff = off || sl?.kind == PadMapModel.Kind.BIND                              // m34: a binding rides the key, not the bar
        if (filled) UiKit.button(c, moveRect(w, h), "Move", glyph(5), when {
            moveOff -> UiKit.Button.DISABLED; isDown(DOWN_MOVE) -> UiKit.Button.DOWN; else -> UiKit.Button.UP })
        if (!fixed) UiKit.button(c, act, if (filled) "Change" else "Choose", glyph(8), when {
            off || !changeHere() -> UiKit.Button.DISABLED
            isDown(DOWN_CHANGE) -> UiKit.Button.DOWN
            else -> UiKit.Button.UP
        })
    }

    /** p9 CARRYING (UI Art's ctl_move): what moves, from where; what goes back on a swap; A Place (or Swap), B Cancel */
    private fun drawMoveRight(c: Canvas, m: PadMapModel.Map, s: Int, carry: PadMapModel.Carry, w: Float, h: Float) {
        val x0 = SPLIT + 40f; val x1 = w - 56f; val top = 188f
        val pt = UiKit.inkPaint(40f, heading = true)
        c.drawText("Move", x0 + 8f, top + 30f - pt.ascent() - 4f, pt)
        val src = PadMapModel.slot(m, carry.set, carry.pos)
        val under = PadMapModel.slot(m, s, pos)
        val same = carry.set == s && carry.pos == pos
        val swap = !same && under != null && under.kind != PadMapModel.Kind.NONE && under.kind != PadMapModel.Kind.FIXED
        val pl = UiKit.inkPaint(22f).apply { color = Theme.parchmentInkDim }
        val ps = UiKit.inkPaint(30f)
        val rows = mutableListOf(Triple(viewIcon(m, src, carry.pos), nameOf(m, src), "from " + comboWord(carry.set, carry.pos)))
        if (swap) rows += Triple(viewIcon(m, under, pos), nameOf(m, under), "goes to " + comboWord(carry.set, carry.pos))
        for ((k, row) in rows.withIndex()) {
            val y = top + 96f + k * 100f
            UiKit.spell(c, row.first, x0 + 8f, y + 8f, 72f, UiKit.Spell.KNOWN)
            c.drawText(TextUtils.ellipsize(row.second, TextPaint(ps), x1 - x0 - 110f, TextUtils.TruncateAt.END).toString(), x0 + 100f, y + 38f, ps)
            c.drawText(row.third, x0 + 100f, y + 70f, pl)
        }
        val help = when {
            same -> "Choose the button it goes on."
            under?.kind == PadMapModel.Kind.FIXED -> "The game keeps that button for itself: choose another."
            swap -> "The two swap places."
            else -> "On an empty button it just moves."
        }
        val pd = TextPaint(UiKit.inkPaint(23f))
        val lay = StaticLayout.Builder.obtain(help, 0, help.length, pd, (x1 - x0 - 16f).toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(4f, 1f).setMaxLines(3).build()
        c.save(); c.translate(x0 + 8f, top + 316f); lay.draw(c); c.restore()
        flash?.takeIf { SystemClock.uptimeMillis() < flashUntil }?.let {
            val pw = UiKit.inkPaint(24f).apply { color = Color.rgb(150, 30, 20); textAlign = Paint.Align.CENTER }
            c.drawText(it, actRect(w, h).centerX(), moveRect(w, h).top - 26f, pw)
        }
        UiKit.button(c, moveRect(w, h), "Cancel", glyph(7), if (isDown(DOWN_CANCEL)) UiKit.Button.DOWN else UiKit.Button.UP)
        UiKit.button(c, actRect(w, h), if (swap) "Swap" else "Place", glyph(8), when {
            m.combat || same || under?.kind == PadMapModel.Kind.FIXED -> UiKit.Button.DISABLED
            isDown(DOWN_PLACE) -> UiKit.Button.DOWN
            else -> UiKit.Button.UP
        })
    }

    /** UI Art's combat right page: the spell of the view, "ZR + Up, in combat", then In combat / Otherwise and the macro that makes it */
    private fun drawCombatRight(c: Canvas, m: PadMapModel.Map, s: Int, sl: PadMapModel.Slot?, w: Float, h: Float) {
        val x0 = SPLIT + 40f; val x1 = w - 56f; val top = 188f
        val inCombat = view == "combat"
        val isCombat = sl?.kind == PadMapModel.Kind.COMBAT
        val fixed = sl?.kind == PadMapModel.Kind.FIXED
        val face = PadMapModel.isFace(sl)
        val fight = if (face) sl!!.fight else null
        val ref = if (isCombat) (if (inCombat) sl!!.combat else sl!!.otherwise) else null
        val bigIcon = when { isCombat -> ref?.let { Icons.get(it.icon) }; inCombat && fight != null -> Icons.get(fight.icon); else -> iconOf(m, sl, pos) }
        UiKit.spell(c, bigIcon, x0 + 36f, top + 16f, 96f, if (fixed && !(inCombat && fight != null)) UiKit.Spell.LEVEL else UiKit.Spell.KNOWN)
        val name = when { isCombat -> ref?.name ?: "Nothing"; inCombat && fight != null -> fight.name; else -> nameOf(m, sl) }
        val nameLeft = x0 + 158f; val nameW = x1 - nameLeft
        val pn = UiKit.inkPaint(40f, heading = true)
        while (pn.textSize > 28f && pn.measureText(name) > nameW) pn.textSize -= 1f
        // one line when it fits; else two (the game's long words, "Interact With Target"), as the normal page does
        var nameH = pn.textSize
        if (pn.measureText(name) <= nameW) c.drawText(name, nameLeft, top + 30f - pn.ascent() - 4f, pn)
        else {
            val lay = StaticLayout.Builder.obtain(name, 0, name.length, TextPaint(pn), nameW.toInt())
                .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).build()
            c.save(); c.translate(nameLeft, top + 30f - 4f); lay.draw(c); c.restore()
            nameH = lay.height - 4f
        }
        // "ZR + Up, in combat" (UI Art's words: the D-pad's short names here, the line set smaller to fit; the help says what stays)
        val dim = UiKit.inkPaint(24f).apply { color = Theme.parchmentInkDim }
        val shortButton = if (pos <= 4) arrayOf("Left", "Up", "Right", "Down")[pos - 1] else buttonWord(pos)
        val sub = (if (s == 1) "" else setWord(s) + " + ") + shortButton + ", " + (if (inCombat) "in combat" else "out of combat")
        while (dim.measureText(sub) > nameW && dim.textSize > 18f) dim.textSize -= 1f
        c.drawText(TextUtils.ellipsize(sub, TextPaint(dim), nameW, TextUtils.TruncateAt.END).toString(), x0 + 160f, top + 34f + nameH + 24f, dim)
        UiKit.tex("book_divider")?.let { c.drawBitmap(it, null, RectF(x0, top + 150f, x1, top + 161f), PBMP) }
        val pd = TextPaint(UiKit.inkPaint(23f))
        if (face && fight != null) {
            // In combat: the fight spell / Otherwise: the game's own (Jump), its picture
            val pl = UiKit.inkPaint(22f).apply { color = Theme.parchmentInkDim }
            val ps = UiKit.inkPaint(30f)
            for (k in 0..1) {
                val y = top + 186f + k * 96f
                val label = if (k == 0) "In combat" else "Otherwise"
                UiKit.spell(c, if (k == 0) Icons.get(fight.icon) else iconOf(m, sl, pos), x0 + 8f, y + 8f, 64f,
                    if (k == 0) UiKit.Spell.KNOWN else UiKit.Spell.LEVEL)
                c.drawText(label, x0 + 92f, y + 28f, pl)
                if (k == 0) swords(c, x0 + 92f + pl.measureText(label) + 22f, y + 20f, 28f)
                val word = if (k == 0) fight.name else nameOf(m, sl)
                c.drawText(TextUtils.ellipsize(word, TextPaint(ps), x1 - x0 - 100f, TextUtils.TruncateAt.END).toString(), x0 + 92f, y + 66f, ps)
            }
            // m43 (the user 2026-10-09: "the description under the spell in the combat tab feels unnecessary and confusing"): no
            // description; only a real problem is said (m16: the game refusing key changes; no key on the button)
            val why = when {
                m.faceRefused -> "Fight buttons unavailable: the game is not taking key changes right now, so ${buttonWord(pos)} stays ${nameOf(m, sl)} in fights."
                PadMapModel.faceUnbound(m, sl) -> "The game has no key on ${buttonWord(pos)} now, so this spell will not cast. Check the game's controller keys."
                else -> null
            }
            why?.let {
                val prose = StaticLayout.Builder.obtain(it, 0, it.length, pd, (x1 - x0 - 16f).toInt())
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(4f, 1f).setMaxLines(3).build()
                c.save(); c.translate(x0 + 8f, top + 386f); prose.draw(c); c.restore()
            }
        } else if (isCombat) {
            // the two lines: In combat / Otherwise, each with its spell
            val pl = UiKit.inkPaint(22f).apply { color = Theme.parchmentInkDim }
            val ps = UiKit.inkPaint(30f)
            for ((k, row) in listOf("In combat" to sl!!.combat, "Otherwise" to sl.otherwise).withIndex()) {
                val y = top + 186f + k * 96f
                UiKit.spell(c, row.second?.let { Icons.get(it.icon) }, x0 + 8f, y + 8f, 64f, UiKit.Spell.KNOWN)
                c.drawText(row.first, x0 + 92f, y + 28f, pl)
                if (k == 0) swords(c, x0 + 92f + pl.measureText(row.first) + 22f, y + 20f, 28f)
                c.drawText(TextUtils.ellipsize(row.second?.name ?: "Nothing", TextPaint(ps), x1 - x0 - 100f, TextUtils.TruncateAt.END).toString(),
                    x0 + 92f, y + 66f, ps)
            }
            // m43: the two rows say it all (no description); the plain D-pad under the DS toolbar is refused when chosen
        }
        // m43: any other button: no description under the spell (the user 2026-10-09)
        val act = actRect(w, h)
        val filled = sl != null && sl.kind != PadMapModel.Kind.NONE && !fixed
        val above = if (filled) moveRect(w, h).top else act.top
        val note = flash?.takeIf { SystemClock.uptimeMillis() < flashUntil } ?: if (m.combat && (!fixed || face)) COMBAT else null
        note?.let {
            val pw = UiKit.inkPaint(24f).apply { color = Color.rgb(150, 30, 20); textAlign = Paint.Align.CENTER }
            c.drawText(it, act.centerX(), above - 64f, pw)
        }
        // m32: any button takes a combat spell (no "spell first" gate any more)
        val canChange = if (face) inCombat && !m.combat else !fixed && sl?.storage != null && !m.combat && !m.paused   // m40
        if (filled) UiKit.button(c, moveRect(w, h), "Move", glyph(5), when {
            m.combat || sl?.storage == null -> UiKit.Button.DISABLED; isDown(DOWN_MOVE) -> UiKit.Button.DOWN; else -> UiKit.Button.UP })
        if (!fixed || (face && inCombat)) UiKit.button(c, act, if (inCombat) "Change combat spell" else "Change", glyph(8), when {
            !canChange || !changeHere() -> UiKit.Button.DISABLED
            isDown(DOWN_CHANGE) -> UiKit.Button.DOWN
            else -> UiKit.Button.UP
        })
    }

    private fun drawChooser(c: Canvas, m: PadMapModel.Map, book: SpellsModel.Book?, s: Int, list: String, w: Float, h: Float) {
        c.drawRect(SPLIT + 2f, 158f, w - 8f, h - 4f, PFILL)
        val box = chooserBox(w, h)
        val inner = UiKit.menu(c, box, solid = true)
        val shownList = if (list == "extra" && onlySpells(m)) "spells" else list
        // p17 (the user 2026-10-07: "the "Combat spells" text and glyph at the top can be removed"): no header line in any chooser; the
        // icon tabs say which list is open
        // p11 / p14: the tabs, Spells | General | Professions | Extra, the game's SMALL ICON tabs (UI Art: the book's own language; the
        // words cannot fit four in the narrow menu), the gaps (LB / RB while the controller holds) round the open one
        val keys = chooserTabs(m)
        val cur = keys.indexOf(shownList).coerceAtLeast(0)
        val ccTabs = ctlChooser()?.tabs == true
        val classIcon = book?.lines?.firstOrNull { it.index == 1 }?.let { Icons.get(it.icon) }
        val tr = cGaps.row(keys.size, inner.left + 8f, inner.right - 8f, box.top + TABS_TOP, 84f, 84f, cur, host)
        for (i in keys.indices) {
            val icon = when (keys[i]) {
                "spells" -> UiKit.tex("ricon_spellbook"); "general" -> classIcon
                "professions" -> UiKit.tex("ricon_professions"); else -> UiKit.tex("ricon_controller")
            }
            UiKit.smallTab(c, tr[i], icon, i == cur, cursor = ccTabs && i == cur)
        }
        // the game's gold arrows beside the open tab: D-pad Left / Right page these tabs (PadTabCtl p30; the user on 1.0.9-test19: "those
        // control the tabs at the top of the screen and they should stay that way. Instead they should have the arrows side to side").
        // No L / R glyphs here: LB / RB stay the room's own tabs. Dimmer while the cursor is down in the list
        UiKit.moveArrows(c, tr[cur], left = cur > 0, right = cur < keys.size - 1, alpha = if (ccTabs) 255 else 150, words = false)

        val rows = rowsOf(list, book, m)
        val pin = pinned(m)
        val withClear = pin != null
        val top = listTop(box); val bottom = listBottom(box, withClear)
        scrollMax = maxOf(0f, rows.size * ROW_H - (bottom - top))
        // the controller's row, kept in sight when it moves
        val cc = ctlChooser()
        val sel = cc?.id?.takeIf { !cc.tabs }                    // p11: ONE gold box: on the tab row the rows show none
        if (cc != null && sel != lastCtlRow) {
            val i = rows.indexOfFirst { idOf(it) == sel }
            if (i >= 0) {
                if (i * ROW_H < scroll) scroll = i * ROW_H
                if ((i + 1) * ROW_H > scroll + (bottom - top)) scroll = (i + 1) * ROW_H - (bottom - top)
            }
        }
        lastCtlRow = sel
        scroll = scroll.coerceIn(0f, scrollMax)
        c.save(); c.clipRect(inner.left, top, inner.right, bottom)
        var y = top - scroll
        for (t in rows) {
            if (t is Thing.Header) {
                if (y + ROW_H >= top && y <= bottom) {
                    val ph = UiKit.bodyPaint(26f, Theme.gold)
                    c.drawText(t.text, inner.left + 24f, y + ROW_H - 26f - (ph.ascent() + ph.descent()) / 2f, ph)
                    UiKit.divider(c, inner.left + 16f, y + ROW_H - 8f, inner.right - 16f, y + ROW_H - 8f)
                }
                y += ROW_H
                continue
            }
            if (y + ROW_H >= top && y <= bottom) {
                val rr = RectF(inner.left, y, inner.right, y + ROW_H)
                val made = t is Thing.Command && t.c.made
                UiKit.menuRow(c, rr, thingName(t), sel != null && idOf(t) == sel, enabled = !m.combat && (t !is Thing.Binding || bindWhy(m) == null),
                    mark = if (made) "icon_check" else null, size = ROW_TEXT)
                val icon = when (t) { is Thing.Spell -> Icons.get(t.e.icon); is Thing.Command -> Icons.get(t.c.icon)
                    is Thing.Mount -> Icons.get(t.x.icon); is Thing.Item -> Icons.get(t.x.icon)
                    is Thing.Binding -> bindIcon(t.b.key, t.b.icon)
                    else -> null }
                icon?.let {
                    PBMP.alpha = if (m.combat) 110 else 255
                    c.drawBitmap(it, null, RectF(rr.left + 18f, rr.centerY() - 30f, rr.left + 78f, rr.centerY() + 30f), PBMP)
                    PBMP.alpha = 255
                }
            }
            y += ROW_H
        }
        c.restore()
        if (rows.isEmpty()) {
            val p = UiKit.bodyPaint(28f, Theme.dim).apply { textAlign = Paint.Align.CENTER }
            c.drawText(if (list != "extra") "No spell to put on a button yet" else "No commands", box.centerX(), top + 60f, p)
        }
        if (withClear) {
            val ct = clearTop(box)
            UiKit.divider(c, inner.left + 16f, ct - 4f, inner.right - 16f, ct - 4f)
            UiKit.menuRow(c, RectF(inner.left, ct, inner.right, ct + ROW_H), thingName(if (pin == Thing.Uncombat || pin == Thing.Unface) pin else Thing.Clear)
                .let { if (it == "Clear") (if (view == "radial") "Clear this slice" else "Clear this button") else it }, sel == idOf(pin!!), Theme.unusable,
                art = "icon_redx", enabled = !m.combat, size = ROW_TEXT)
        }
        // m34 (UI Art): the controller resting on a DIM binding row says why, one short line over the hints (the game's greyed entries say it
        // in their tooltip)
        rows.firstOrNull { it is Thing.Binding && sel != null && idOf(it) == sel }?.let { t ->
            bindShort(m)?.let { why ->
                val pw = UiKit.bodyPaint(22f, Theme.dim)
                c.drawText(TextUtils.ellipsize("${thingName(t)} $why", TextPaint(pw), inner.width() - 48f, TextUtils.TruncateAt.END).toString(),
                    inner.left + 24f, box.bottom - 84f, pw)
            }
        }
        UiKit.hints(c, box.right - 28f, box.bottom - 50f, glyph(8) to "Choose", glyph(7) to "Back")
    }

    private fun drawBox(c: Canvas, m: PadMapModel.Map, book: SpellsModel.Book?, s: Int, w: Float) {
        val (kind, thing) = tBox?.let { it.kind to it.thing } ?: ctlBox()?.let { b -> thingOf(b, book, m)?.let { b.kind to it } } ?: return
        val sl = slotOf(m, s, pos)
        val isCombat = sl?.kind == PadMapModel.Kind.COMBAT
        val face = PadMapModel.isFace(sl)
        val here = if (isCombat) "${sl!!.combat?.name ?: "?"} / ${sl.otherwise?.name ?: "Nothing"}" else nameOf(m, sl)
        val g = m.general
        val ch = m.character
        val (line1, line2, yes) = when {
            view == "radial" && kind == "replace" -> Triple("${radName(book, radId(pos))} is on ${sliceWord(pos, rpage).lowercase()}.",
                "Replace it with ${thingName(thing)}?", "Replace")
            view == "radial" && kind == "clear" -> Triple("${radName(book, radId(pos))} is on ${sliceWord(pos, rpage).lowercase()}.",
                "Clear this slice?", "Clear")
            kind == "cface" -> Triple("In a fight, ${buttonWord(pos)} will cast ${thingName(thing)} instead of ${nameOf(m, sl)}.",
                "Out of combat it stays ${nameOf(m, sl)}. Set it?", "Set")
            kind == "replace" && face -> Triple("${sl!!.fight?.name ?: "?"} is ${buttonWord(pos)}'s combat spell.", "Replace it with ${thingName(thing)}?", "Replace")
            kind == "clear" && thing == Thing.Unface -> Triple("${sl?.fight?.name ?: "?"} is ${buttonWord(pos)}'s combat spell.",
                "Clear it? ${buttonWord(pos)} is ${nameOf(m, sl)} again in fights.", "Clear")
            kind == "make" -> Triple("This uses one of your General Macros (${(g?.n ?: 0) + 1} of ${g?.max ?: "?"}).", "Make ${thingName(thing)}?", "Make")
            kind == "cmake" -> Triple("This uses one of your Character Macros (${(ch?.n ?: 0) + 1} of ${ch?.max ?: "?"}).",
                "Make ${thingName(thing)} this button's combat spell?", "Make")
            kind == "replace" && isCombat && fight() -> Triple("${sl!!.combat?.name ?: "?"} is this button's combat spell.",
                "Replace it with ${thingName(thing)}?", "Replace")
            kind == "replace" && isCombat -> Triple("${sl!!.otherwise?.name ?: "Nothing"} is on ${comboWord(s, pos)} out of combat.",
                "Replace it with ${thingName(thing)}?", "Replace")
            kind == "replace" && fight() && !isCombat && !face ->
                Triple("$here is on ${comboWord(s, pos)}. It cannot stay with a fight spell.", "Replace it with ${thingName(thing)}?", "Replace")
            kind == "replace" -> Triple("$here is on ${comboWord(s, pos)}.", "Replace it with ${thingName(thing)}?", "Replace")
            kind == "clear" && thing == Thing.Uncombat -> Triple("${sl?.combat?.name ?: "?"} is this button's combat spell.",
                "Clear it?" + (sl?.otherwise?.let { " ${it.name} stays out of combat." } ?: ""), "Clear")
            kind == "clear" -> Triple("$here is on ${comboWord(s, pos)}.", "Clear this button?", "Clear")
            kind == "cfull" -> Triple("All ${ch?.max ?: 30} Character Macros are in use.", "Delete one in the game's macro window first.", "Okay")
            else -> Triple("All ${g?.max ?: 120} General Macros are in use.", "Delete one in the game's macro window first.", "Okay")
        }
        UiKit.cover(c, RectF(0f, 0f, w, host.height.toFloat()))
        val r = dialogRect(w)
        UiKit.dialog(c, r)
        val p1 = UiKit.bodyPaint(28f, Theme.dim).apply { textAlign = Paint.Align.CENTER }
        while (p1.measureText(line1) > r.width() - 80f && p1.textSize > 20f) p1.textSize -= 1f
        c.drawText(line1, w / 2f, r.top + 100f, p1)
        val p2 = UiKit.bodyPaint(34f, Color.WHITE).apply { textAlign = Paint.Align.CENTER }
        while (p2.measureText(line2) > r.width() - 80f && p2.textSize > 24f) p2.textSize -= 1f
        c.drawText(line2, w / 2f, r.top + 160f, p2)
        val single = kind == "full" || kind == "cfull"
        UiKit.button(c, yesRect(w, r, single), yes, glyph(8), if (isDown(DOWN_YES)) UiKit.Button.DOWN else UiKit.Button.UP)
        if (!single) UiKit.button(c, noRect(w, r), "Cancel", glyph(7), if (isDown(DOWN_NO)) UiKit.Button.DOWN else UiKit.Button.UP)
    }

    // ---------------------------------------------------------------- touch (SPEC 8)

    private var downX = 0f; private var downY = 0f; private var lastY = 0f
    private var dragging = false
    private var down = DOWN_NONE

    /** a touch on the tab's pages (the tab row is SpellbookView's) */
    fun onTouch(ev: MotionEvent, w: Float, h: Float, book: SpellsModel.Book?): Boolean {
        val m = map
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; lastY = ev.y; dragging = false
                down = pressedAt(ev.x, ev.y, w, h, m)
                press.finger(if (down != DOWN_NONE) down else null)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && (abs(ev.x - downX) > SLOP || abs(ev.y - downY) > SLOP)) {
                    dragging = true
                    if (down != DOWN_NONE) { down = DOWN_NONE; host.invalidate() }
                    press.finger(null)
                }
                if (dragging && chooserList() != null && boxOpenNow() == null && chooserBox(w, h).contains(downX, downY)) {
                    scroll = (scroll - (ev.y - lastY)).coerceIn(0f, scrollMax)
                    lastY = ev.y
                    host.invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                val was = down
                down = DOWN_NONE
                press.lift(!dragging && was != DOWN_NONE)
                if (!dragging && m != null) tapAt(ev.x, ev.y, w, h, m, book, was)
                dragging = false
                host.invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { down = DOWN_NONE; dragging = false; press.finger(null); host.invalidate() }
        }
        return true
    }

    private fun boxOpenNow(): Any? = tBox ?: ctlBox()
    /** p11: the chooser is open (LB / RB are its tabs then, not the room's: [ControllerView] hides the room's badges) */
    fun chooserOpen(): Boolean = chooserList() != null

    private fun pressedAt(x: Float, y: Float, w: Float, h: Float, m: PadMapModel.Map?): Int {
        if (m == null) return DOWN_NONE
        if (boxOpenNow() != null) {
            val r = dialogRect(w)
            val single = (tBox?.kind ?: ctlBox()?.kind).let { it == "full" || it == "cfull" }
            return when {
                yesRect(w, r, single).contains(x, y) -> DOWN_YES
                !single && noRect(w, r).contains(x, y) -> DOWN_NO
                else -> DOWN_NONE
            }
        }
        if (chooserList() == null) {
            val carrying = carryNow() != null
            if (actRect(w, h).contains(x, y)) return if (carrying) DOWN_PLACE else if (changeHere()) DOWN_CHANGE else DOWN_NONE
            if (moveRect(w, h).contains(x, y)) return if (carrying) DOWN_CANCEL else DOWN_MOVE
        }
        return DOWN_NONE
    }

    private fun tapAt(x: Float, y: Float, w: Float, h: Float, m: PadMapModel.Map, book: SpellsModel.Book?, was: Int) {
        // a box: only its buttons answer
        tBox?.let { b ->
            if (was == DOWN_YES && yesRect(w, dialogRect(w), b.kind == "full" || b.kind == "cfull").contains(x, y)) confirm(b, m)
            else if (was == DOWN_NO && noRect(w, dialogRect(w)).contains(x, y)) tBox = null
            return
        }
        ctlBox()?.let { b ->
            val single = b.kind == "full" || b.kind == "cfull"
            if (was == DOWN_YES && yesRect(w, dialogRect(w), single).contains(x, y)) ctlYes(b, m, book)
            else if (was == DOWN_NO && noRect(w, dialogRect(w)).contains(x, y)) closeController()
            return
        }
        // the chooser, over the right page
        val list = chooserList()
        if (list != null) {
            val box = chooserBox(w, h)
            if (!box.contains(x, y)) {
                // outside it: closed (the controller's too), then the touch counts on the left page
                if (tChooser != null) tChooser = null else closeController()
                tapLeft(x, y, h, m)
                return
            }
            val inner = RectF(box.left + 20f, box.top, box.right - 20f, box.bottom)
            if (y >= box.top + TABS_TOP - 8f && y <= box.top + TABS_TOP + 92f) {    // p14: the small icon tabs
                // p11: the tab under the finger (its rect from the last frame)
                val keys = chooserTabs(m)
                cGaps.rects.indexOfFirst { x >= it.left && x <= it.right }.takeIf { it in keys.indices }?.let { takeOver(); tChooser = keys[it]; scroll = 0f }
                return
            }
            val pin = pinned(m)
            val withClear = pin != null
            if (pin != null && y >= clearTop(box) && y <= clearTop(box) + ROW_H) { choose(pin, m); return }
            val top = listTop(box); val bottom = listBottom(box, withClear)
            if (y in top..bottom) {
                val i = ((y - top + scroll) / ROW_H).toInt()
                rowsOf(list, book, m).getOrNull(i)?.let { choose(it, m) }
            }
            return
        }
        if (x < SPLIT) { tapLeft(x, y, h, m); return }
        // p9 carrying: Place / Swap (the ONE game action, on the addon's chosen button = this one) or Cancel (a pick)
        if (was == DOWN_PLACE && actRect(w, h).contains(x, y)) {
            if (m.combat) say(COMBAT) else tap(listOf(PadMapModel.ROW_PADTAB to PadMapModel.COL_PLACE))
            return
        }
        if (was == DOWN_CANCEL && moveRect(w, h).contains(x, y)) {
            tap(listOf(PadMapModel.ROW_PADTAB to PadMapModel.COL_CANCEL)); localFirst(DOWN_CANCEL); return
        }
        // Move: pick it up (a pick; the addon carries the chosen button)
        if (view == "radial" && was == DOWN_MOVE && radId(pos) == 0) return                     // p16: an empty slice has no Move
        if (view == "radial" && was == DOWN_CHANGE && actRect(w, h).contains(x, y)) {
            if (m.combat) say(COMBAT) else { tLock = null; tChooser = "spells"; scroll = 0f }
            return
        }
        if (was == DOWN_MOVE && moveRect(w, h).contains(x, y) && slotOf(m, shown(), pos)?.kind == PadMapModel.Kind.BIND) {
            say("It rides the key, not the bar: clear it and choose it where you want it"); return
        }
        if (was == DOWN_MOVE && moveRect(w, h).contains(x, y)) {
            if (m.combat) say(COMBAT) else { tap(listOf(PadMapModel.ROW_PADTAB to PadMapModel.COL_CARRY)); localFirst(DOWN_MOVE) }
            return
        }
        // Choose / Change
        if (was == DOWN_CHANGE && actRect(w, h).contains(x, y)) {
            val sl = slotOf(m, shown(), pos)
            when {
                sl == null -> {}
                sl.kind == PadMapModel.Kind.FIXED && !(view == "combat" && PadMapModel.isFace(sl)) -> {}
                m.combat -> say(COMBAT)
                sl.kind != PadMapModel.Kind.FIXED && sl.storage == null -> {}
                view == "combat" && m.paused && !PadMapModel.isFace(sl) -> say(PAUSED_LINE)          // m40
                // b-bind-hold-triggers: the set on show NOW (a trigger held) is the one it goes on, released or not
                else -> { tLock = shown(); tChooser = "spells"; scroll = 0f }
            }
        }
    }

    /** the left page: a set, a button, the page arrows (picks: no game action) */
    private fun tapLeft(x: Float, y: Float, h: Float, m: PadMapModel.Map) {
        if (view == "radial") {
            // p16: a page plate (the bar page's pick: the addon opens that radial page), a slice of the wheel (the button's pick)
            for (i in 0..2) if (plateRect(i).contains(x, y)) {
                rpage = i + 1; rpageUntil = SystemClock.uptimeMillis() + ECHO_MS
                tap(listOf(PadMapModel.ROW to PadMapModel.colPage(i + 1))); host.invalidate(); return
            }
            val sl = UiKit.radialPreviewHit(WHEEL_CX, wheelCy(h), wheelD(h), x, y)
            if (sl >= 0) { fingerOnButton = true; pick(set, sl + 1); tap(listOf(PadMapModel.ROW to PadMapModel.colPos(sl + 1))) }
            return
        }
        // p29: the form row (In combat): Caster, then the forms; a pick (padtab 15 + k)
        if (formsShown(m)) for (i in 0..minOf(10, m.forms.size)) if (formRect(i).contains(x, y)) {
            fingerOnButton = false
            tap(listOf(PadMapModel.ROW_PADTAB to PadMapModel.colForm(i))); host.invalidate(); return
        }
        // p9: ZL / ZR / ZL + ZR; the lit one again = the base set (no "None")
        for (i in 0..2) if (setRect(i).contains(x, y)) {
            val to = if (set == i + 2) 1 else i + 2
            fingerOnButton = false
            pick(to, pos); tap(listOf(PadMapModel.ROW to PadMapModel.colSet(to))); return
        }
        for (p in 1..8) {
            val r = square(p)
            if (x >= r.left - 10f && x <= r.right + 10f && y >= r.top - 10f && y <= r.bottom + 10f) {
                fingerOnButton = true
                pick(set, p); tap(listOf(PadMapModel.ROW to PadMapModel.colPos(p))); return
            }
        }
        val cy = h - 70f
        if (abs(y - cy) <= 56f) {
            val pg = page()
            val to = when {
                abs(x - (330f - 150f)) <= 56f -> pg - 1
                abs(x - (330f + 150f)) <= 56f -> pg + 1
                else -> return
            }
            if (to in 1..m.pages.coerceIn(1, 3)) {
                pageWant = to; pageUntil = SystemClock.uptimeMillis() + ECHO_MS
                tap(listOf(PadMapModel.ROW to PadMapModel.colPage(to)))
            }
        }
    }

    /** L (the user on batch2: "the change button should only be selectable when highlighting a button to change"): Choose / Change
     *  works only while the cursor stands on a button (or a slice): the controller's focus, or a finger's last pick of a button */
    private var fingerOnButton = false
    private fun changeHere(): Boolean {
        val k = ctl?.takeIf { it.on } ?: return true                    // touch only: a button is always the chosen one
        return k.focus == "buttons" || (fingerOnButton && SystemClock.uptimeMillis() < pickUntil)
    }

    private fun pick(s: Int, p: Int) {
        set = s; pos = p
        pickUntil = SystemClock.uptimeMillis() + ECHO_MS
    }

    /** a row chosen by touch: its box first (SPEC 6), or straight on */
    private fun choose(t: Thing, m: PadMapModel.Map) {
        if (t is Thing.Header) return                                       // p12: a heading is no choice
        if (t is Thing.Binding) bindWhy(m)?.let { say(it); return }          // m34: where the router cannot reach, said
        if (m.combat) { say(COMBAT); return }
        val k = boxFor(t, m, false)
        if (k != null) tBox = Box(k, t) else act(t)
    }

    private fun boxFor(t: Thing, m: PadMapModel.Map, afterReplace: Boolean): String? {
        if (t is Thing.Clear || t is Thing.Uncombat || t is Thing.Unface) return "clear"
        if (view == "radial") return if (!afterReplace && radId(pos) != 0) "replace" else null       // p16: PadTabCtl's boxFor
        val here = slotOf(m, shown(), pos)
        if (fight() && t is Thing.Spell && PadMapModel.isFace(here)) {
            // a base X / Y / A: its own box the first time (it changes every fight), Replace after; no macro
            return if (here!!.fight != null) (if (!afterReplace) "replace" else null) else "cface"
        }
        if (fight() && t is Thing.Spell) {
            // m43 (PadTabCtl p22): the overlay: no macro is made and nothing leaves the button; Replace only names a fight spell there
            val k = slotOf(m, shown(), pos)?.kind
            return if (k == PadMapModel.Kind.COMBAT && !afterReplace) "replace" else null
        }
        if (!afterReplace && PadMapModel.replaces(m, shown(), pos) != null) return "replace"
        if (t is Thing.Command && !t.c.made) return if (PadMapModel.macrosFull(m)) "full" else "make"
        return null
    }

    private fun confirm(b: Box, m: PadMapModel.Map) {
        if (b.kind == "full" || b.kind == "cfull") { tBox = null; return }
        if (b.kind == "replace") boxFor(b.thing, m, true)?.let { tBox = Box(it, b.thing); return }
        // (a face's "cface" box: its Set is the ONE action)
        act(b.thing)
    }

    /** A in a box the CONTROLLER opened, by touch: the same answer the addon would give */
    private fun ctlYes(b: PadMapModel.Box, m: PadMapModel.Map, book: SpellsModel.Book?) {
        val t = thingOf(b, book, m)
        when {
            b.kind == "full" || b.kind == "cfull" || t == null -> closeController()
            b.kind == "replace" -> {
                val next = boxFor(t, m, true)
                if (next != null) { val s = shown(); closeController(); tLock = s; tBox = Box(next, t) } else act(t)
            }
            else -> act(t)
        }
    }

    /** THE ONE GAME ACTION (the addon acts on its chosen button, which the picks keep equal to this one); its own box closes with it */
    private fun act(t: Thing) {
        // b-bind-hold-triggers / b-bind-combat-toggle: read before the chooser closes. A finger's latched set the add-on does not know
        // goes first as a set pick (no action), so a released trigger cannot send it to the base set
        val f = fight()
        val pre = tLock?.takeIf { view != "radial" && (tChooser != null || tBox != null) && it != set }
            ?.let { s -> pick(s, pos); listOf(PadMapModel.ROW to PadMapModel.colSet(s)) } ?: emptyList()
        tBox = null; tChooser = null; tLock = null
        if (view == "radial") {                                             // p16: the slice shows it at the tap; the addon's `rad` follows
            val id = when { t is Thing.Spell && cellsOf(t.e) != null -> t.e.spellId ?: t.e.actionId; t == Thing.Clear -> 0; else -> null }
            if (id != null) { radWant = Triple(rpage, pos, id); radWantUntil = SystemClock.uptimeMillis() + ECHO_MS }
        }
        val combatPick = PadMapModel.ROW_LAYOUT to PadMapModel.COL_COMBAT_LAYER
        when (t) {
            // in the combat view the spell's own Bind column follows the one-shot "combat layer" pick, in the same touch
            is Thing.Spell -> when {
                cellsOf(t.e) == null -> say("This spell has no touch cell yet: use the controller")
                f -> tap(pre + combatPick + cellsOf(t.e)!!)
                pre.isNotEmpty() -> tap(pre + cellsOf(t.e)!!)
                else -> tapSpell(t.e)
            }
            // p12: every Extra entry by the "extra" row, its page pick first (the pad owner's rule); the answer is checked (onAck)
            is Thing.Command -> { extraSent = t.c.key; tap(pre + PadMapModel.extraCells(t.index - 1)) }
            is Thing.Mount -> { extraSent = t.x.id.toString(); tap(pre + PadMapModel.extraCells(t.index)) }
            is Thing.Item -> { extraSent = t.x.id.toString(); tap(pre + PadMapModel.extraCells(t.index)) }
            is Thing.Binding -> { extraSent = t.b.key; tap(pre + PadMapModel.extraCells(t.index)) }
            is Thing.Header -> {}
            Thing.Clear -> tap(pre + listOf(PadMapModel.ROW to PadMapModel.COL_CLEAR))
            Thing.Uncombat, Thing.Unface -> tap(pre + listOf(combatPick, PadMapModel.ROW to PadMapModel.COL_CLEAR))
                ?: say("Use the controller: X on A, then the first row")
        }
    }

    /** a finger takes the controller's chooser over (its tabs): its latched set comes along */
    private fun takeOver() {
        if (tChooser != null) return
        val cc = ctlChooser() ?: return
        tLock = ctl?.shown; tChooser = cc.list
    }

    /** close what the controller has open (its chooser and box): leave the tab and come back in, two no-action picks */
    private fun closeController() {
        tap(listOf(PadMapModel.ROW to PadMapModel.COL_LEFT, PadMapModel.ROW to PadMapModel.COL_OPENED))
    }

    companion object {
        private const val SPLIT = 740f
        /** p16 the wheel's centre: the left page's middle */
        private const val WHEEL_CX = (8f + SPLIT) / 2f
        private const val ROW_H = 92f
        /** p17: the chooser's icon tabs from its top (the header line above them is gone) */
        private const val TABS_TOP = 30f
        /** every chooser row one size, Clear included; a long command name takes two lines (UI Art's menuRow `size`, PADTAB_SPEC 5) */
        private const val ROW_TEXT = 30f
        private const val SLOP = 18f
        private const val ECHO_MS = 1500L
        private const val LB_TAP_MS = 180L
        /** the addon's notes that say what became of a carry (PadTabCtl p9 / p10): each ends a Move / Cancel shown at the press */
        private val CARRY_NOTES = setOf("carry", "carry-cancel", "moved", "swapped", "move-refused", "nothing-to-move", "nothing-carried",
                                        "locked", "combat", "tab", "enter", "leave")
        private const val FLASH_MS = 3000L
        private const val COMBAT = "Not while in combat"
        /** m40 S0: what a paused feature says (the user's ruling 2026-10-09: no trace in the player's setup) */
        private const val PAUSED_LINE = "Paused: this comes back with the new controller layer"
        private const val DOWN_NONE = 0; private const val DOWN_CHANGE = 1; private const val DOWN_YES = 2; private const val DOWN_NO = 3
        private const val DOWN_MOVE = 4; private const val DOWN_PLACE = 5; private const val DOWN_CANCEL = 6
        private const val FLY_MS = 280L
        /** the base set's fixed face buttons (positions 5-8: the game's PAD3 / PAD4 / PAD2 / PAD1), the game's own pictures */
        private val FIXED_ART = arrayOf("fixed_interact", "fixed_inspect", "square_exit_up", "fixed_jump")
        private val PBMP = Paint(Paint.FILTER_BITMAP_FLAG)
        private val PFILL = Paint().apply { color = Color.rgb(18, 14, 9) }
        /** UI Art's "stays the same" dimming, ink 150 / 255 */
        private val PDIM = Paint().apply { color = Color.argb(150, 14, 10, 6) }
        /** p9: the swap mark's stand-in while the game's picture is not on the device */
        private val PSWAP = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 4f; strokeCap = Paint.Cap.ROUND }
        /** p9: the carried button's empty place, a faint ghost */
        private val PGHOST = Paint().apply { color = Color.argb(170, 40, 30, 18) }
    }
}
