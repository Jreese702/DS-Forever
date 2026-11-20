package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View

/**
 * What the Spellbook brings over the WHOLE screen (UI Art's SPEC 4): the game's "Bind to Action Bar" box. It lies over the rooms and the
 * bar and UNDER the NPC windows (an NPC window covers everything).
 *
 * THE BIND BOX shows while the addon's Bind box is open (`spellsctl` mode "bind", then "result" for the answer). It is a dialog: it
 * takes every touch while it is up. The answer to the press comes from the addon (`spellsack`); B cancels on the controller.
 * The two groups of buttons are the Cast room's own drawing of that set ([drawSet], cast session `f5a0baa`), with the button that
 * took the spell lit; until the Cast room has its first layout they are stand-ins in the same rectangles.
 *
 * THE "New spell" CARD IS GONE (the user, 2026-11-12: "along with the Keep/undo I feel like the pop up toasts that appear before that are
 * redundant and should be scrapped"; the pick: scrap ONLY the 4 s card per learned spell): Home's one-at-a-time prompt
 * (CompanionHost.newSpellAsk) asks about each learned spell; the box's green "Bound to" line, its "Put back" line and every refusal stay.
 *
 * Book b57 / PadMap m157 L3 / R3 (the user, 2026-11-13: "looking good, except people also want to be able to bind any of their spells
 * and stuff to these, so any of those 5 controller tabs should be able to bind here"): on the Normal tab, bare or ZL, the box draws the
 * two sticks beside the trigger chips (what each holds now, from the padmap's `sticks`); pressing one puts the spell on it, lit as any
 * button. ZR / ZL + ZR or the In combat tab: no stick tiles (the add-on answers "layer" / "stick-normal" and the box keeps waiting).
 *
 * Book b59 A BAG ITEM (the user, 2026-11-13: "when doing bind to a button from bags it should open the normal spell binding menu"): the
 * Bags room's Bind to a Button opens THIS box for the item (`bitem`: its name and icon where the spell's stand), hosted by Bags (`via`
 * "bags"). The same tabs minus Cast bar (spells only), the same keep / undo; a press on the button the item is on already CLEARS it
 * (the answer's `clr`: "Cleared from", "Keep it cleared?"). A finger answers the hosted box's question as A / B would (Keep left, Undo
 * right; Back / Close at the foot): one tap on Bags' own row ([onHostKey]), which hands the press to the box.
 *
 * Book b70 CONTROLLER | TAP TO CAST (the user, 2026-11-19, the approved proposal D7-D9): TWO TAB ROWS, Out of combat | In combat (D7:
 * the old first tab renamed), then Controller | Tap to cast; LB / RB walk all four in reading order ([CastStripModel.stepBind]): Out + Controller
 * (bf-off), Out + Tap to cast (padtab 39 then 26), In + Controller (bf-on), In + Tap to cast (39 then 4: the bar's IN-COMBAT layer, its
 * set slots with the crossed swords, an empty one showing the slot's own thing faded). Macros and Bags get Tap to cast too (D8). Show in
 * combat off: In combat + Tap to cast is dimmed with a lock, its slots grey, "Hidden in fights" (the add-on refuses with one red pulse).
 * The bar Off in Settings: both Tap to cast tabs dimmed, "Bar is off" and "Turn on" (a finger, or the add-on's `cbon` for A: D2).
 */
class SpellsOverlay(
    context: Context,
    /** the Cast room's own drawing of one trigger set (CastView.drawSet, read-only); false = it has no layout yet */
    private val drawSet: (c: Canvas, set: Int, dpad: RectF, face: RectF, lit: Int?, litIcon: android.graphics.Bitmap?,
                          fight: ((Int) -> Int?)?) -> Boolean =
        { _, _, _, _, _, _, _ -> false },
    /** another controller reader is running (the Cast view's): then this box does not start a second one (home's finding) */
    private val otherReader: () -> Boolean = { false },
    /** b37: one tap on the `spells` row's column (the Bind box's Normal / In combat) */
    private val onTap: (Int) -> Unit = {},
    /** GitHub #43 cb3: the Bind box's Cast bar tab and its slots (padtab 39 then 26 / 20-25, 27-28 for slots 7-8: Book b56 / b57) */
    private val onCastBarTaps: (List<Pair<String, Int>>) -> Unit = {},
    /** Book b59: a finger's A ("a") / B ("b") for a HOSTED box (the Bags room's own row hands it to the box) */
    private val onHostKey: (String) -> Unit = {},
) : View(context) {

    private var book: SpellsModel.Book? = null
    private var ctl: SpellsModel.Ctl? = null

    // the Bind box
    private var boxSpell: Int? = null               // the spell the open box is for
    private var result: SpellsModel.BindResult? = null
    private var resultAt = 0L
    private var lt = false; private var rt = false
    /** a finger down on a situation (b71: 0 / 1 / 2), the controller's area (10), Turn on (20) */
    private var downFight = -1
    /** b71 (Option C, the user 2026-11-20): THE THREE SITUATIONS as ONE tab row under the thing being bound ([i] 0 Out of combat, 1 In
     *  combat, 2 Party mode), each with its picture; LB / RB on the row's outsides */
    private fun sitRect(i: Int, w: Float): RectF {
        val x0 = w / 2f - SIT_ROW / 2f
        val each = (SIT_ROW - 2 * SIT_GAP) / 3f
        val l = x0 + i * (each + SIT_GAP)
        return RectF(l, SIT_TOP, l + each, SIT_TOP + SIT_H)
    }
    /** the situations shown: Party mode while it is allowed (QOL > Party) */
    private fun sits(): Int = if (GameUi.partyModeOn()) 3 else 2
    /** b71: the controller's area (a finger there = the situation's controller stop) and the bar's */
    private fun ctlArea(w: Float) = RectF(w / 2f - 470f, CTL_HEAD - 26f, w / 2f + 470f, BAR_HEAD - 30f)

    /**
     * GitHub #43 cb3 THE CAST BAR TAB (the user, 2026-11-13: "to assign to the bar, in the bind menu, where there is the L and R tabs, we
     * will add a third tab if this setting is turned on, showing the 6 slots on that bar"); b70: four tabs; b71 (Option C): the bar is
     * always on show under the controller, each situation with ITS layer; the add-on takes a slot pick (ActionBar cb3, Book b56)
     */
    private fun three(): Boolean {                                      // b70 (D8): a bag item's box too; cb13 (#553): a macro's box too
        val bi = ctl?.bindItem
        return CastStripModel.bindTab(CastStripModel.Live.bar, macro = bi?.mac == true)
    }
    /** b70: the In combat + Tap to cast stop is there (the add-on has the in-combat layer) */
    private fun fightTap(): Boolean = CastStripModel.bindFightTab(CastStripModel.Live.bar, macro = ctl?.bindItem?.mac == true)
    /** b71: Party mode's bar stop (the add-on has the party layer) */
    private fun partyTap(): Boolean = CastStripModel.bindPartyTab(CastStripModel.Live.bar, macro = ctl?.bindItem?.mac == true)
    /** b70 (D2): the bar is Off in Settings: the bar veiled, "Turn on" */
    private fun barOff(): Boolean = CastStripModel.barOff(HomeSettings.castBarSide)
    /** b70 (D5): Show in combat off: In combat's bar locked */
    private fun fightHidden(): Boolean = !CastStripModel.showOf(HomeSettings.castBarCombat)
    /** b71: Bar in Party mode off: Party mode's bar locked */
    private fun partyHidden(): Boolean = !HomeSettings.castBarPartyShow
    /** a finger's (or LB / RB's) stop, shown AT THE TAP until the add-on's `bf` / `bcb` / `bcf` / `bpm` / `bcp` agree or 1.5 s pass */
    private var tabWant: CastStripModel.BindTab? = null
    private var tabUntil = 0L
    /** the stop the add-on says: its bar while `bcb` is there (its layer `bcf` / `bcp`), else its controller (`bf`, `bpm`) */
    private fun docTab(): CastStripModel.BindTab {
        val c = ctl
        val tap = c?.bindCb != null && three()
        val party = (c?.bindParty == true || (tap && c?.bindCbParty == true)) && GameUi.partyModeOn()
        return CastStripModel.BindTab(!party && (if (tap) c?.bindCbFight == true && fightTap() else c?.bindFight == true), tap, party)
    }
    /** the stop lit (one of the six) */
    private fun tabShown(): CastStripModel.BindTab {
        val doc = docTab()
        val want = tabWant ?: return doc
        if (want == doc || SystemClock.uptimeMillis() >= tabUntil) { tabWant = null; return doc }
        return want
    }
    private fun cbShown(): Boolean = tabShown().tap
    /** put the box on [t]: its taps (bf-on / bf-off, padtab 39 then 26 / 4, 39, 38 then 4 / 5), shown at once */
    private fun goTab(t: CastStripModel.BindTab, then: List<Pair<String, Int>> = emptyList()) {
        val cells = CastStripModel.bindTabCells(t, fightCols()) ?: return
        if (cells.first().first == "spells") cells.forEach { onTap(it.second) } else onCastBarTaps(cells)
        if (then.isNotEmpty()) onCastBarTaps(then)
        tabWant = t; tabUntil = SystemClock.uptimeMillis() + 1500L
        invalidate()
    }
    /** slot [i]'s box (0..7): one row of 8 in the bar's well under the controller */
    private fun cbSlotRect(i: Int, w: Float, cy: Float = 0f): RectF {
        val rowW = 8 * CB_SLOT + 7 * CB_GAP
        val l = w / 2f - rowW / 2f + i * (CB_SLOT + CB_GAP)
        return RectF(l, BAR_TOP, l + CB_SLOT, BAR_TOP + CB_SLOT)
    }
    private fun cbWell(w: Float): RectF = cbSlotRect(0, w).let { a -> cbSlotRect(7, w).let { b -> RectF(a.left - 18f, a.top - 18f, b.right + 18f, b.bottom + 18f) } }
    private var downSlot = -1
    /** b71: the box's paints (per view: a plain JVM test of the companion's words never builds them) */
    private val pCard = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(250, 30, 25, 20) }
    private val pCardEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.rgb(96, 80, 58) }
    private val pCardLit = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.rgb(255, 209, 0) }
    private val pRecede = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 8, 6, 4) }
    private val pWell = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(250, 24, 20, 17) }
    private val pWellEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.rgb(110, 92, 64) }
    private val pRule = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f }
    /** LB / RB as the app's own reader sees them (on the Thor the game never hears LB: it is its emulated Ctrl): a NEW press moves the tab */
    private var lbWas = false
    private var rbWas = false
    private fun bumpers(lb: Boolean, rb: Boolean) {
        val lbNew = lb && !lbWas; val rbNew = rb && !rbWas
        lbWas = lb; rbWas = rb
        if (!lbNew && !rbNew) return
        if (ctl?.mode != "bind") return
        // b70 (D9) / b71: LB = one stop back, RB = one on, in reading order - each situation's controller, then its bar; no wrap
        val from = tabShown()
        val to = CastStripModel.stepBind(from, if (rbNew) 1 else -1, three(), fightTap(), GameUi.partyModeOn(), partyTap())
        if (to != from) goTab(to)
    }
    /** the addon can take the pair by touch (its columns are on the row) */
    private fun fightCols(): Pair<Int, Int>? {
        val win = book?.win ?: return null
        return (win["bfon"] ?: return null) to (win["bfoff"] ?: return null)
    }
    private var pad: PadInput? = null

    init { CastSettings.init(context); HomeSettings.init(context); CastStripModel.Live.onChange { postInvalidate() } }

    private fun style(): SpellsModel.PadStyle = when (CastSettings.buttonLayout) {
        CastSettings.ButtonLayout.XBOX -> SpellsModel.PadStyle.LTR
        CastSettings.ButtonLayout.PLAYSTATION -> SpellsModel.PadStyle.SHP
        CastSettings.ButtonLayout.NINTENDO -> SpellsModel.PadStyle.REV
    }

    // ---------------------------------------------------------------- data in

    fun onBook(b: SpellsModel.Book) {
        book = b
        tick()
    }

    /** b70: the add-on's `cbon` last seen (A on a Tap to cast tab with the bar Off: the app turns it on) */
    private var lastCbOn: Int? = null

    fun onCtl(c: SpellsModel.Ctl) {
        val was = boxOpen()
        c.cbOn?.let { n -> val w0 = lastCbOn; if (w0 != null && n > w0) turnOn(); lastCbOn = n }
        ctl = c
        if (c.on && c.bindOpen) {
            // a box that opens afresh forgets the last answer
            if (boxSpell != c.binding || c.note == "bind-wait") { boxSpell = c.binding; result = null }
        }
        if (!c.on) { boxSpell = null; result = null }
        // b42: B took the bind back: the box waits for a button again, and says so for a moment
        if (c.on && c.note == "bind-undone") { result = null; undoneAt = SystemClock.uptimeMillis() }
        if (c.on && c.note?.startsWith("bind-undo-") == true) { undoFail = c.note.removePrefix("bind-undo-"); undoFailAt = SystemClock.uptimeMillis() }
        if (boxOpen() && !was) startPad() else if (!boxOpen() && was) stopPad()
        tick()
    }

    /** b70 (D2): "Turn on": the bar on (Settings > Bottom screen > Tap-to-cast bar), both columns */
    private fun turnOn() { if (barOff()) { HomeSettings.castBarSide = CastStripModel.turnOnSide; invalidate() } }
    /** b70: the "Turn on" button's place on a dimmed Tap to cast tab (this frame's) */
    private var turnOnRect: RectF? = null

    /** b42: when B last took a bind back (the box's line says so for [SHOW_MS]); a refused undo and when */
    private var undoneAt = 0L
    private var undoFail: String? = null
    private var undoFailAt = 0L

    /** batch8: the add-on's padmap (Spells' PadMap): each button's In combat spell, for the box's In combat tab */
    private var padMap: PadMapModel.Map? = null
    fun onPadMap(m: PadMapModel.Map) { padMap = m; if (boxOpen()) invalidate() }

    /** issues #18 / #20 / #31: when the app's own reader last saw LT / RT held (uptime ms; 0 = not since the reader started). The ack
     *  reaches the app after the press, often after the trigger is let go, so the log line reads the LATCH, not the state now */
    private var ltAt = 0L; private var rtAt = 0L
    fun triggerRead(now: Long = SystemClock.uptimeMillis()): SpellsModel.AppTrig =
        if (pad == null) SpellsModel.AppTrig(readerOn = false, ltAgoMs = null, rtAgoMs = null)
        else SpellsModel.AppTrig(true, if (lt) 0L else if (ltAt > 0) now - ltAt else null, if (rt) 0L else if (rtAt > 0) now - rtAt else null)

    fun onBind(r: SpellsModel.BindResult) {
        result = r; resultAt = SystemClock.uptimeMillis()
        if (boxSpell == null) boxSpell = r.spellId
        tick()
    }

    /** the box is up: the addon's box waits, or its answer shows */
    fun boxOpen(): Boolean {
        val c = ctl ?: return false
        return c.on && (c.mode == "bind" || c.mode == "result" || c.mode == "confirm")
    }

    private fun startPad() {
        if (pad != null || otherReader()) return
        pad = PadInput(onPad = { l, r, lb, rb ->
            val t = SystemClock.uptimeMillis(); if (lt || l) ltAt = t; if (rt || r) rtAt = t   // held now, or just let go: the last held moment
            lt = l; rt = r; bumpers(lb, rb); invalidate() }, onStatus = { _, _ -> }).also {
            it.triggerSwap = CastSettings.triggerSwap
            it.start()
        }
    }

    private fun stopPad() {
        pad?.stop(); pad = null; lt = false; rt = false; ltAt = 0L; rtAt = 0L
    }

    // everything that moves on its own: the answer's 1.5 s
    private fun tick() {
        val now = SystemClock.uptimeMillis()
        visibility = if (boxOpen()) VISIBLE else GONE
        invalidate()
        if ((result != null && now - resultAt < SHOW_MS) || now - undoneAt < SHOW_MS) postDelayed({ tick() }, 50)
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (boxOpen()) bindBox(c, w, h)
    }

    /** the game's "Bind to Action Bar": its box and words, the controller's buttons for the set held (SPEC 4).
     *  b71 (Option C, `ctldesign/optionC_5` / `_6`): the thing being bound, big; the three SITUATIONS as tabs (LB / RB); BOTH devices
     *  in the body - "Controller" (press the button you want; Party mode: A / X / Y only, the D-pad locked) and "Tap-to-cast bar" (the
     *  situation's layer: a finger on a slot, or the D-pad + A on the bar's stop). The device the box waits on is lit, the other recedes */
    private fun bindBox(c: Canvas, w: Float, h: Float) {
        formHits.clear()                                                // b45: only the row drawn this frame takes a finger
        hostHits.clear()                                                // b59: and only this frame's A / B places
        turnOnRect = null
        val b = book
        val item = ctl?.bindItem                                        // Book b59: a bag item's box
        val e = if (item != null) null else boxSpell?.let { id -> b?.let { SpellsModel.find(it, id) } }
        UiKit.cover(c, RectF(0f, 0f, w, h), 0.75f)
        val box = RectF(60f, 84f, w - 60f, 934f)
        UiKit.dialog(c, box)
        UiKit.dialogHeader(c, w / 2f, box.top - 34f, "Bind to Action Bar")
        val st = style()
        val now = SystemClock.uptimeMillis()
        val confirming = ctl?.mode == "confirm"
        val ly = ANSWER_Y
        val r = result?.takeIf { it.spellId == boxSpell && (ctl?.mode == "result" || confirming || now - resultAt < SHOW_MS) }

        // the line: the answer (no instruction)
        if (r != null && r.ok && r.pad != null) {
            val p = UiKit.bodyPaint(32f, Color.rgb(30, 255, 0)).apply { textAlign = Paint.Align.RIGHT }
            c.drawText(Tr.t(answerWord(r)), w / 2f - 60f, ly - (p.ascent() + p.descent()) / 2f, p)
            drawCombo(c, SpellsModel.combo(r.pad, st), w / 2f - 40f, ly, 64f, Theme.text, true)
        } else if (r != null && !r.ok) {
            val (text, colour) = when (r.why) {
                "combat" -> "Not while in combat" to Theme.unusable
                "reserved" -> "That button cannot be changed" to Theme.unusable
                "bind" -> "A game key rides that button: clear it first" to Theme.unusable        // b37: In combat
                "dpad bar" -> "The DS toolbar has the plain D-pad: hold a trigger" to Theme.unusable
                "layer" -> "Only on a button with no trigger, or with ${SpellsModel.triggerNames(st).first}" to Theme.warn
                "stick-normal" -> "L3 / R3: on the Out of combat tab" to Theme.warn       // b70 (D7)
                "unsure" -> "Press it again" to Theme.warn
                "form-unknown" -> "Shift into that form once so the game shows its bar" to Theme.warn       // b44
                "missing" -> "Missing" to Theme.unusable                     // Book b63: no macro has that name now
                "taken" -> "Name taken" to Theme.unusable                    // Book b63 review: another macro has that name too
                "cb-hidden" -> PadTabPage.HIDDEN_LINE to Theme.unusable          // b70 (D5)
                else -> "That did not work" to Theme.unusable
            }
            val p = UiKit.bodyPaint(30f, colour).apply { textAlign = Paint.Align.CENTER }
            c.drawText(Tr.t(text), w / 2f, ly - (p.ascent() + p.descent()) / 2f, p)
        } else if (now - undoneAt < SHOW_MS) {
            val p = UiKit.bodyPaint(30f, Theme.warn).apply { textAlign = Paint.Align.CENTER }
            c.drawText(Tr.t("Put back what was there. Press another button"), w / 2f, ly - (p.ascent() + p.descent()) / 2f, p)
        } else when (ctl?.note) {                                       // b71: Party mode's refusals, said once on the line
            "bind-party-locked" -> "Party mode: A, X or Y" to Theme.warn
            "bind-party-spells" -> "Party mode takes spells" to Theme.warn
            "bind-cb-phidden" -> PadTabPage.PARTY_OFF_LINE to Theme.unusable
            else -> null
        }?.let { (text, colour) ->
            val p = UiKit.bodyPaint(30f, colour).apply { textAlign = Paint.Align.CENTER }
            c.drawText(Tr.t(text), w / 2f, ly - (p.ascent() + p.descent()) / 2f, p)
        }

        // the thing being bound, big (the spell, or the bag item / macro, b59)
        val pn = UiKit.bodyPaint(40f, Theme.gold)
        val thingName = item?.name ?: e?.let { Tr.t(it.name) }              // a game name (an item / macro): never Tr.t
        val thingIcon = if (item != null) (if (item.mac) (item.icon.takeIf { it > 0 } ?: CastStripView.MACRO_QMARK).let { Icons.get(it) ?: UiKit.icon(it) }
            else Icons.get(item.icon.takeIf { it > 0 })) else e?.let { Icons.get(it.icon) }
        if (thingName != null) {
            while (pn.textSize > 28f && pn.measureText(thingName) + 104f > box.width() - 120f) pn.textSize -= 1f
            val bx = w / 2f - (pn.measureText(thingName) + 104f) / 2f
            UiKit.slot(c, thingIcon, bx, THING_Y - 41f, 82f)
            c.drawText(thingName, bx + 104f, THING_Y - (pn.ascent() + pn.descent()) / 2f, pn)
        }

        // the set the triggers hold now (once a button took the spell, THAT set with the button lit, SPEC 4)
        val bound = r?.takeIf { it.ok }?.pad
        val set = bound?.bar ?: when { lt && rt -> 4; rt -> 3; lt -> 2; else -> 1 }
        val tabNow = tabShown()
        val f = if (r != null && r.ok) r.fight else tabNow.fight
        val party = tabNow.party && !(r != null && r.ok)
        val sit = if (party) 2 else if (f) 1 else 0
        val cbOn = (r == null || !r.ok) && !confirming && tabNow.tap && three()

        // b71 THE SITUATIONS: one row, each with its picture; LB / RB on the outsides (they walk the six stops)
        val sitNames = listOf("Out of combat", "In combat", "Party mode")
        val sitIcons = listOf(SituationPage.Icon.OUT, SituationPage.Icon.FIGHT, SituationPage.Icon.PARTY)
        val ns = sits()
        val tp = UiKit.bodyPaint(28f)
        val room = sitRect(0, w).width() - 44f - 10f - 30f
        fun lines(word: String, p: Paint, two: Boolean): List<String> {
            if (!two || p.measureText(word) <= room || !word.contains(' ')) return listOf(word)
            val at = word.indices.filter { word[it] == ' ' }.minByOrNull { maxOf(p.measureText(word.substring(0, it)), p.measureText(word.substring(it + 1))) }!!
            return listOf(word.substring(0, at), word.substring(at + 1))
        }
        fun fits(size: Float, two: Boolean) = (0 until ns).all { i -> tp.textSize = size; lines(Tr.t(sitNames[i]), tp, two).all { tp.measureText(it) <= room } }
        // one size for all three: one line at 28 down to 24, else (es / fr) two lines at up to 24 rather than going small
        var ts = 28f
        while (ts > 24f && !fits(ts, false)) ts -= 1f
        val two = !fits(ts, false)
        if (two) { ts = 24f; while (ts > 15f && !fits(ts, true)) ts -= 1f }
        for (i in 0 until ns) {
            val sr = sitRect(i, w)
            // the situation's card: dark, its gold edge and glow when lit (the game's set card look, Mock C5 / C6)
            if (i == sit) UiKit.glow(c, RectF(sr).apply { inset(-6f, -6f) }, Color.rgb(255, 200, 40), 0.55f)
            c.drawRoundRect(sr, 8f, 8f, pCard)
            c.drawRoundRect(sr, 8f, 8f, if (i == sit) pCardLit else pCardEdge)
            val p = UiKit.bodyPaint(ts, if (i == sit) Color.WHITE else Theme.gold)
            val ls = lines(Tr.t(sitNames[i]), p, two)
            val tw = ls.maxOf { p.measureText(it) }
            val x0 = sr.centerX() - (44f + 10f + tw) / 2f
            Icons2.draw(c, sitIcons[i], x0 + 22f, sr.centerY(), 44f, lit = i == sit)
            for ((j, ln) in ls.withIndex())
                c.drawText(ln, x0 + 54f, sr.centerY() + (j - (ls.size - 1) / 2f) * ts * 1.02f - (p.ascent() + p.descent()) / 2f, p)
        }
        if (r == null || !r.ok) {
            UiKit.pad(c, "${st.prefix}_lshoulder", sitRect(0, w).left - 36f, sitRect(0, w).centerY(), 44f)
            UiKit.pad(c, "${st.prefix}_rshoulder", sitRect(ns - 1, w).right + 36f, sitRect(ns - 1, w).centerY(), 44f)
        }

        // b43 / b44: the form row (In combat, a character with forms; Out of combat while the game lays a form bar) on the Controller line
        val m = padMap
        val rowOn = !confirming && !party && m?.forms?.isNotEmpty() == true && (f || m.stance != null)
        val ruleEnd = if (rowOn && m != null) formRow(c, w, box, m, b, st) - 16f else box.right - 70f

        // CONTROLLER: the device's heading, then the D-pad's four and the face buttons' four (the Cast room's own drawing of that set,
        // stand-ins until it has a layout), at 0.6; the trigger chips and the sticks under it
        darkSection(c, "Controller", SituationPage.Icon.PAD, CTL_HEAD, box.left + 70f, ruleEnd, lit = !cbOn)
        val cy0 = box.top + 104f
        val dpad = RectF(w / 2f - 270f - 240f, cy0 + 236f, w / 2f - 270f + 240f, cy0 + 236f + 340f)
        val face = RectF(w / 2f + 290f - 240f, cy0 + 236f, w / 2f + 290f + 240f, cy0 + 236f + 340f)
        val lit = r?.takeIf { it.ok }?.pad?.takeIf { it.bar == set }?.pos ?: -1
        val icon = thingIcon
        // batch8: In combat shows each button's IN COMBAT spell for that set; b71: Party mode its A / X / Y (the layer held)
        val form = ctl?.bindForm?.takeIf { f && set == 1 && m?.forms?.any { fo -> fo.id == it } == true }
        val nForm = ctl?.bindForm?.takeIf { !f && m?.stance?.set == set && m.forms.any { fo -> fo.id == it } }
        val cfg = PartyModel.live.cfg
        val fight: ((Int) -> Int?)? = if (party) { p -> PartyModel.slotAt(set, p)?.let { s -> (if (s == "yc") cfg?.yc else cfg?.slot(s))?.i } }
        else if (f && m != null) { p ->
            if (form != null && p != 7) PadMapModel.formFight(m, form, p)?.icon else fightIcon(m, set, p)
        } else if (nForm != null && m != null) { p -> m.formBars.firstOrNull { it.form == nForm && it.pos == p }?.icon }
        else null
        c.save()
        c.translate(w / 2f, CTL_CY); c.scale(CTL_K, CTL_K); c.translate(-(w / 2f + 10f), -(cy0 + 406f))
        val drawn = try { drawSet(c, set, dpad, face, lit.takeIf { it in 1..8 }, icon, fight) } catch (_: Throwable) { false }
        if (!drawn) standIns(c, dpad, face, lit, icon, st)
        if (party) {                                                    // b71: the D-pad moves the pick: locked
            c.drawRoundRect(RectF(dpad.left + 40f, dpad.top + 10f, dpad.right - 40f, dpad.bottom - 10f), 24f, 24f, pRecede)
            UiKit.tex("talent_lock")?.let { c.drawBitmap(it, null, RectF(dpad.centerX() - 26f, dpad.centerY() - 36f, dpad.centerX() + 26f, dpad.centerY() + 36f), pBmp) }
        }
        c.restore()
        // the triggers' chips, lit while held; L3 / R3 beside them on Out of combat (b57)
        val tset = when (CastSettings.buttonLayout) {
            CastSettings.ButtonLayout.XBOX -> "gen"; CastSettings.ButtonLayout.PLAYSTATION -> "shp"; else -> "rev"
        }
        UiKit.triggerChip(c, RectF(w / 2f - 64f - 38f, CHIP_Y - 24f, w / 2f - 64f + 38f, CHIP_Y + 24f), "${tset}_ltrigger", lt)
        UiKit.triggerChip(c, RectF(w / 2f + 64f - 38f, CHIP_Y - 24f, w / 2f + 64f + 38f, CHIP_Y + 24f), "${tset}_rtrigger", rt)
        if (!f && !party && (set == 1 || set == 2)) sticks(c, w, CHIP_Y, m, set, lit, icon)
        if (cbOn) c.drawRect(RectF(box.left + 20f, CTL_HEAD + 22f, box.right - 20f, BAR_HEAD - 22f), pRecede)

        // TAP-TO-CAST BAR: the situation's layer (Out of combat the bar's own, In combat its fight layer, Party mode its party layer): a
        // set slot wears its badge, an empty one shows what it falls through to, faded; the stop's slot lit and showing the thing where
        // it would go, the slot it is on already glowing green (picking that one takes it off); locked: veiled with ONE word
        if (three()) {
            darkSection(c, "Tap-to-cast bar", SituationPage.Icon.TAP, BAR_HEAD, box.left + 70f, box.right - 70f, lit = cbOn)
            val bar = CastStripModel.Live.bar
            val at = (ctl?.bindCb ?: 1) - 1
            val layer = when (sit) { 1 -> bar?.fslots; 2 -> bar?.pslots; else -> bar?.slots }
            val locked = barOff() || (sit == 1 && fightHidden()) || (sit == 2 && partyHidden())
            val mac = item?.takeIf { it.mac }
            val bagItem = item?.takeIf { !it.mac }
            val here = when {
                mac != null -> layer?.indexOfFirst { it.kind == CastStripModel.Kind.MACRO && it.name == mac.name } ?: -1
                bagItem != null -> layer?.indexOfFirst { it.kind == CastStripModel.Kind.ITEM && it.id == bagItem.id } ?: -1
                else -> e?.let { en -> layer?.indexOfFirst { it.spellId != null && (it.spellId == en.actionId.takeIf { a -> a > 0 } || it.spellId == en.spellId) } } ?: -1
            }
            val well = cbWell(w)
            c.drawRoundRect(well, 14f, 14f, pWell); c.drawRoundRect(well, 14f, 14f, pWellEdge)
            for (i in 0 until CastStripModel.SLOTS) {
                val sr = cbSlotRect(i, w)
                val own = layer?.getOrNull(i)?.takeIf { it.filled }
                val under = if (sit != 0 && own == null) bar?.slots?.getOrNull(i)?.takeIf { it.filled } else null
                val sl = own ?: under
                val showThing = cbOn && i == at && here != i && !locked
                val bmp = if (showThing) thingIcon
                          else (sl?.icon ?: CastStripView.MACRO_QMARK.takeIf { sl?.kind == CastStripModel.Kind.MACRO })?.let { Icons.get(it) ?: UiKit.icon(it) }
                UiKit.slot(c, bmp, sr.left, sr.top, sr.width(), dim = locked || (under != null && !showThing))
                if (own != null && !locked && !showThing) {
                    if (sit == 1) UiKit.tex("combat_swords")?.let { sw -> c.drawBitmap(sw, null, RectF(sr.right - 22f, sr.top - 16f, sr.right + 18f, sr.top + 22f), pBmp) }
                    else if (sit == 2) Icons2.draw(c, SituationPage.Icon.PARTY, sr.right - 2f, sr.top + 2f, 34f)
                }
                if (i == here && !locked) UiKit.glow(c, RectF(sr).apply { inset(-10f, -10f) }, Color.rgb(60, 255, 60), 0.9f)
                if (cbOn && i == at) UiKit.focus(c, RectF(sr).apply { inset(-6f, -6f) })
            }
            if (locked) {
                SituationPage.veilAt(c, well, if (barOff()) "Bar is off" else if (sit == 2) PadTabPage.PARTY_OFF_LINE else PadTabPage.HIDDEN_LINE)
                if (barOff() && cbOn) {
                    val tr = RectF(w / 2f - 170f, HINT_Y - 40f, w / 2f + 170f, HINT_Y + 40f)          // the foot, under the well
                    UiKit.button(c, tr, "Turn on", UiKit.okGlyph(), UiKit.Button.UP)
                    turnOnRect = tr
                }
            }
            if (!cbOn) c.drawRoundRect(well, 14f, 14f, pRecede)
        }

        val back = faceGlyph(st, "b")
        if (confirming && r != null && r.ok) {
            // b42 (the user: "a confirm and it closes, b removes it and let's you keep binding"): the game's own question look (its
            // StaticPopup), over the box's foot: Keep / Undo
            val qb = RectF(w / 2f - 330f, box.bottom - 196f, w / 2f + 330f, box.bottom - 24f)
            UiKit.dialog(c, qb)
            val qp = UiKit.bodyPaint(32f, Color.WHITE).apply { textAlign = Paint.Align.CENTER }
            val failed = undoFail?.takeIf { now - undoFailAt < SHOW_MS }
            val q = when (failed) { null -> keepQuestion(r); "combat" -> "Can't undo in combat. Keep it?"; else -> "That couldn't be undone. Keep it?" }
            c.drawText(Tr.t(q), qb.centerX(), qb.top + 64f, qp)
            UiKit.hints(c, qb.centerX() + 180f, qb.bottom - 46f, UiKit.okGlyph() to "Keep", back to "Undo")
            if (ctl?.hosted == true) { hostHits.add(RectF(qb.left, qb.top, qb.centerX(), qb.bottom) to "a"); hostHits.add(RectF(qb.centerX(), qb.top, qb.right, qb.bottom) to "b") }
            if (failed != null) postInvalidateDelayed(250L)
            return
        }
        if (turnOnRect != null) return                                  // the bar Off: its one button stands at the foot
        val hx = when {
            r != null && r.ok -> UiKit.hints(c, box.right - 80f, HINT_Y, back to "Close")
            cbOn && !barLocked(sit) -> UiKit.hints(c, box.right - 80f, HINT_Y, UiKit.okGlyph() to "Place", back to "Back")
            cbOn -> UiKit.hints(c, box.right - 80f, HINT_Y, back to "Back")                          // locked: nothing to place
            !lt && !rt -> UiKit.hints(c, box.right - 80f, HINT_Y, back to "Back")
            else -> null
        }
        if (hx != null && ctl?.hosted == true) hostHits.add(RectF(hx - 24f, HINT_Y - 48f, box.right - 40f, HINT_Y + 48f) to "b")
    }

    /** b71: the situation's bar takes nothing now (the bar Off, Show in combat off, Bar in Party mode off) */
    private fun barLocked(sit: Int) = barOff() || (sit == 1 && fightHidden()) || (sit == 2 && partyHidden())

    /** b71: a section heading on the box's dark ground: its picture, its word (light), a gold rule to [x1]; [lit] = the device waited on */
    private fun darkSection(c: Canvas, label: String, icon: SituationPage.Icon, y: Float, x0: Float, x1: Float, lit: Boolean) {
        Icons2.draw(c, icon, x0 + 22f, y, 44f, lit = lit)
        val p = UiKit.bodyPaint(30f, if (lit) Theme.text else Theme.dim)
        val word = Tr.t(label)
        c.drawText(word, x0 + 56f, y - (p.ascent() + p.descent()) / 2f, p)
        val lx = x0 + 56f + p.measureText(word) + 18f
        pRule.color = if (lit) Color.rgb(200, 150, 60) else Color.rgb(110, 90, 60)
        if (lx < x1) c.drawLine(lx, y + 2f, x1, y + 2f, pRule)
    }

    /** Book b59: a hosted box's finger places as drawn this frame (A / B), see [onHostKey] */
    private val hostHits = ArrayList<Pair<RectF, String>>()

    /**
     * b43 (the user on test20: "We need to add this to the actual bind pop up in the spell book", "We can use the select button to cycle these
     * tabs"): the In combat tab's FORM ROW along the box's top: the class's icon (the caster's set), then each form's own icon; the chosen one
     * lit; Select (its glyph first) cycles them (Book b43). An In combat bind goes to the chosen form's own set
     */
    private fun formRow(c: Canvas, w: Float, box: RectF, m: PadMapModel.Map, b: SpellsModel.Book?, st: SpellsModel.PadStyle): Float {
        val list = listOf<PadMapModel.Form?>(null) + m.forms.take(9)
        formHits.clear()
        val chosen = ctl?.bindForm ?: 0
        val sz = 46f; val step = 54f
        val total = 56f + list.size * step
        var x = box.right - 70f - total                                 // b71: on the Controller heading's line, at its right end
        val start = x
        val cy = CTL_HEAD
        UiKit.pad(c, selectGlyph(st), x + 22f, cy, 40f)
        x += 56f
        val classBmp = b?.lines?.firstOrNull { it.index == 1 }?.let { Icons.get(it.icon) }
        for (fo in list) {
            val r = RectF(x, cy - sz / 2f, x + sz, cy + sz / 2f)
            UiKit.choice(c, r, if (fo == null && classBmp == null) "C" else "", (fo?.id ?: 0) == chosen)
            (if (fo == null) classBmp else Icons.get(fo.icon))?.let { bmp ->
                val i = sz - 10f
                c.drawBitmap(bmp, null, RectF(r.centerX() - i / 2f, r.centerY() - i / 2f, r.centerX() + i / 2f, r.centerY() + i / 2f), null)
            }
            if ((fo?.id ?: 0) == chosen) UiKit.focus(c, RectF(r).apply { inset(-3f, -3f) })     // the chosen set, plainly lit
            formHits.add(RectF(r).apply { inset(-5f, -5f) })
            x += step
        }
        return start
    }

    /** Book b57: stick [p]'s tile, beside the trigger chips on their line [cy] (L3 left of ZL, R3 right of ZR) */
    fun stickRect(p: Int, w: Float, cy: Float): RectF {
        val cx = if (p == PadMapModel.POS_L3) w / 2f - STICK_DX else w / 2f + STICK_DX
        return RectF(cx - STICK_S / 2f, cy - STICK_S / 2f, cx + STICK_S / 2f, cy + STICK_S / 2f)
    }

    /** Book b57: L3 / R3 in [set] (1 / 2): what each holds now (the padmap's sticks), the one that took the spell lit with it */
    private fun sticks(c: Canvas, w: Float, cy: Float, m: PadMapModel.Map?, set: Int, lit: Int, litIcon: android.graphics.Bitmap?) {
        for (p in PadMapModel.STICKS) {
            val r = stickRect(p, w, cy)
            val isLit = p == lit
            val sl = m?.let { PadMapModel.slot(it, set, p) }?.takeIf { it.kind != PadMapModel.Kind.NONE }
            UiKit.slot(c, if (isLit) litIcon else sl?.let { Icons.get(it.icon) }, r.left, r.top, r.width())
            if (isLit) UiKit.glow(c, RectF(r).apply { inset(-8f, -8f) }, Color.rgb(60, 255, 60), 0.9f)
            UiKit.pad(c, PadMapModel.stickGlyph(p), r.right - 8f, r.top + 8f, 32f)
        }
    }

    /** b45: the form row's places as drawn (index = 0 the class icon, k the k-th form), for a finger */
    private val formHits = ArrayList<RectF>()

    /** empty slots with the glyphs, where the Cast room's drawing goes, for as long as it has none */
    private fun standIns(c: Canvas, dpad: RectF, face: RectF, lit: Int, icon: android.graphics.Bitmap?, st: SpellsModel.PadStyle) {
        cross(c, dpad, intArrayOf(1, 2, 3, 4), arrayOf("gen_left", "gen_up", "gen_right", "gen_down"), lit, icon)
        // face buttons by PHYSICAL place (left, top, right, bottom), as the Cast room draws them: on a Nintendo pad the game hears the
        // button printed Y (left) as its top key, X (top) as its left key, A (right) as its bottom key, B (bottom) as its right key
        // f-abxy-split: the places follow the ABXY LOCATION; each wears its ACTION's glyph in the chosen set (faceGlyph)
        val positions = if (CastSettings.faceSwap) intArrayOf(6, 5, 8, 7) else intArrayOf(5, 6, 7, 8)
        val keys = Array(4) { mapOf(5 to "x", 6 to "y", 7 to "b", 8 to "a").getValue(positions[it]) }
        val faceGlyphs = Array(4) { faceGlyph(st, keys[it]) }
        cross(c, face, positions, faceGlyphs, lit, icon)
    }

    /** batch8: button [p]'s IN COMBAT spell icon in [set] (PadTabPage's In combat rule: the overlay's fight spell, a base X / Y / A's) */
    private fun fightIcon(m: PadMapModel.Map, set: Int, p: Int): Int? {
        val sl = PadMapModel.slot(m, set, p) ?: return null
        return when {
            sl.kind == PadMapModel.Kind.COMBAT && sl.ov -> sl.combat?.icon
            sl.fight != null && PadMapModel.isFace(sl) -> sl.fight.icon
            else -> null
        }
    }

    /** the Select button in the chosen set's look (UI Art: Nintendo minus, Xbox View, PlayStation Share / Create) */
    private fun selectGlyph(st: SpellsModel.PadStyle) = when (st) {
        SpellsModel.PadStyle.REV -> "rev_minus"
        SpellsModel.PadStyle.LTR -> "ltr_view"
        SpellsModel.PadStyle.SHP -> "shp_share"
    }

    private fun faceGlyph(st: SpellsModel.PadStyle, key: String) =
        if (st == SpellsModel.PadStyle.SHP) "shp_" + mapOf("x" to "square", "y" to "triangle", "b" to "circle", "a" to "cross").getValue(key)
        else "${st.prefix}_$key"

    /** four slots as a cross: left, top, right, bottom. [positions] = the game's position of each; [lit] = the one that took the spell */
    private fun cross(c: Canvas, r: RectF, positions: IntArray, glyphs: Array<String>, lit: Int, icon: android.graphics.Bitmap?) {
        val s = 124f
        val cx = r.centerX(); val cy = r.centerY()
        val at = arrayOf(floatArrayOf(cx - 1.5f * s - 8f, cy - s / 2f), floatArrayOf(cx - s / 2f, cy - s - 12f),
            floatArrayOf(cx + s / 2f + 8f, cy - s / 2f), floatArrayOf(cx - s / 2f, cy + 12f))
        for (i in 0..3) {
            val x = at[i][0]; val y = at[i][1]
            val isLit = positions[i] == lit
            UiKit.slot(c, if (isLit) icon else null, x, y, s)
            if (isLit) UiKit.glow(c, RectF(x - 10f, y - 10f, x + s + 10f, y + s + 10f), Color.rgb(60, 255, 60), 0.9f)
            UiKit.pad(c, glyphs[i], x + s - 14f, y + 14f, 50f)
        }
    }

    /** a combination with 0, 1 or 2 triggers (LT + RT: the first trigger drawn 62 px before the combination, SPEC 2.4) */
    private fun drawCombo(c: Canvas, combo: SpellsModel.Combo, x0: Float, cy: Float, size: Float, colour: Int, shadow: Boolean): Float {
        var x = x0
        val trigger = when (combo.triggers.size) {
            2 -> { UiKit.pad(c, combo.triggers[0], x + size / 2f, cy, size); x += 62f; combo.triggers[1] }
            1 -> combo.triggers[0]
            else -> null
        }
        return UiKit.padCombo(c, trigger, combo.button, x, cy, size, colour, shadow)
    }

    // ---------------------------------------------------------------- touch

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (boxOpen()) {                                            // a dialog: every touch is its
            val w = width.toFloat()
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // b71: 0 / 1 / 2 a situation, 10 the controller's area, 20 Turn on; a slot of the bar ([downSlot])
                    downFight = (0 until sits()).firstOrNull { sitRect(it, w).contains(ev.x, ev.y) }
                        ?: (if (turnOnRect?.contains(ev.x, ev.y) == true) 20 else null)
                        ?: (if (ctlArea(w).contains(ev.x, ev.y)) 10 else null) ?: -1
                    val t = tabShown()
                    val locked = barOff() || (t.fight && fightHidden()) || (t.party && partyHidden())
                    downSlot = if (three() && !locked) (0 until CastStripModel.SLOTS).firstOrNull { cbSlotRect(it, w).let { r -> RectF(r).apply { inset(-8f, -8f) } }.contains(ev.x, ev.y) } ?: -1 else -1
                    if (downSlot >= 0) downFight = -1
                }
                MotionEvent.ACTION_UP -> {
                    val i = downFight; downFight = -1
                    val k0 = downSlot; downSlot = -1
                    val now = tabShown()
                    if (ctl?.mode == "bind") {
                        // cb3 / b71: a finger on a slot of the situation's bar: the box's thing goes there (padtab 39 then 20 + slot, Book
                        // b56), the bar's stop taken first when the box waits on the controller
                        if (k0 >= 0 && cbSlotRect(k0, w).let { r -> RectF(r).apply { inset(-8f, -8f) } }.contains(ev.x, ev.y)) {
                            val cells = CastStripModel.slotCells(k0)
                            val barStop = now.copy(tap = true)
                            val ok = !now.party || partyTap()
                            if (cells != null && ok) { if (now.tap) onCastBarTaps(cells) else goTab(barStop, cells) }
                        }
                        when {
                            i in 0..2 && sitRect(i, w).contains(ev.x, ev.y) ->
                                goTab(CastStripModel.BindTab(i == 1, false, i == 2))       // a situation: its controller first
                            i == 10 && ctlArea(w).contains(ev.x, ev.y) && now.tap -> goTab(now.copy(tap = false))
                            i == 20 && turnOnRect?.contains(ev.x, ev.y) == true -> turnOn()
                        }
                    }
                    // b45 (a finger picks a form): the row's place k -> the spells row's "vo<k>" (Book b45 reads a lone one as the form)
                    val k = formHits.indexOfFirst { it.contains(ev.x, ev.y) }
                    if (k >= 0 && ctl?.mode == "bind") book?.win?.get("vo$k")?.let { onTap(it) }
                    // b59: a hosted box's Keep / Undo / Back by a finger (ONE tap on the host's row: its A / B)
                    else if (ctl?.hosted == true && i < 0 && k0 < 0) hostHits.firstOrNull { it.first.contains(ev.x, ev.y) }?.let { onHostKey(it.second) }
                }
                MotionEvent.ACTION_CANCEL -> { downFight = -1; downSlot = -1 }
            }
            return true
        }
        return false                                                // no box: the room's
    }

    override fun onDetachedFromWindow() {
        stopPad()
        super.onDetachedFromWindow()
    }

    companion object {
        /** the answer's line: where it went, or (Book b59, an item already there) where it came off */
        fun answerWord(r: SpellsModel.BindResult): String = when {
            r.cleared -> "Cleared from"
            r.fight -> "In combat on"
            else -> "Bound to"
        }
        /** Book b59: the box's question after a press that took (a clear asks to keep it cleared) */
        fun keepQuestion(r: SpellsModel.BindResult): String = if (r.cleared) "Keep it cleared?" else "Keep this binding?"
        private const val SHOW_MS = 1500L
        /** b71 (Option C) THE BOX, top to bottom: the thing's line, the situations' row, the answer's line, the Controller heading and its
         *  picture (at [CTL_K], centred on [CTL_CY]), the triggers' chips, the bar's heading and its 8 slots, the hints */
        private const val THING_Y = 180f
        private const val SIT_TOP = 244f
        private const val SIT_H = 60f
        private const val SIT_ROW = 880f
        private const val SIT_GAP = 22f
        private const val ANSWER_Y = 336f
        private const val CTL_HEAD = 378f
        private const val CTL_CY = 494f
        private const val CTL_K = 0.6f
        private const val CHIP_Y = 634f
        private const val BAR_HEAD = 698f
        private const val BAR_TOP = 734f
        private const val CB_SLOT = 80f
        private const val CB_GAP = 16f
        private const val HINT_Y = 878f
        private val pBmp = Paint(Paint.FILTER_BITMAP_FLAG)
        /** Book b57b: the stick tiles' side and their centres' distance from the box's middle (the chips reach 106 from it) */
        const val STICK_S = 64f
        const val STICK_DX = 158f
    }
}
