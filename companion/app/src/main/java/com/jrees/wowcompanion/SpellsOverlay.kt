package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View

/**
 * What the Spellbook brings over the WHOLE screen (UI Art's SPEC 4 and 5): the game's "Bind to Action Bar" box, and the "New spell"
 * card. It lies over the rooms and the bar and UNDER the NPC windows (an NPC window covers everything).
 *
 * THE BIND BOX shows while the addon's Bind box is open (`spellsctl` mode "bind", then "result" for the answer). It is a dialog: it
 * takes every touch while it is up. The answer to the press comes from the addon (`spellsack`); B cancels on the controller.
 * The two groups of buttons are the Cast room's own drawing of that set ([drawSet], cast session `f5a0baa`), with the button that
 * took the spell lit; until the Cast room has its first layout they are stand-ins in the same rectangles.
 *
 * THE CARD comes for every spell the addon reports learned (`news`) after this app started: 4 s each, fading in 150 ms and out
 * 250 ms, the others waiting under it; never while [blocked] (an NPC window or a dialog) or the Bind box is up. A tap on it opens the
 * Spellbook at that spell ([onOpen]) and drops the ones that wait; a touch beside it is the room's.
 */
class SpellsOverlay(
    context: Context,
    private val blocked: () -> Boolean,
    private val onOpen: (spellId: Int) -> Unit,
    /** the Cast room's own drawing of one trigger set (CastView.drawSet, read-only); false = it has no layout yet */
    private val drawSet: (c: Canvas, set: Int, dpad: RectF, face: RectF, lit: Int?, litIcon: android.graphics.Bitmap?,
                          fight: ((Int) -> Int?)?) -> Boolean =
        { _, _, _, _, _, _, _ -> false },
    /** another controller reader is running (the Cast view's): then this box does not start a second one (home's finding) */
    private val otherReader: () -> Boolean = { false },
    /** b37: one tap on the `spells` row's column (the Bind box's Normal / In combat) */
    private val onTap: (Int) -> Unit = {},
) : View(context) {

    private var book: SpellsModel.Book? = null
    private var ctl: SpellsModel.Ctl? = null

    // the Bind box
    private var boxSpell: Int? = null               // the spell the open box is for
    private var result: SpellsModel.BindResult? = null
    private var resultAt = 0L
    private var lt = false; private var rt = false
    /** b37 (b-bind-combat-toggle): a finger's Normal / In combat, shown AT THE TAP until the addon's `bf` agrees or 1.5 s pass */
    private var fightWant: Boolean? = null
    private var fightUntil = 0L
    private var downFight = -1
    private fun fightShown(): Boolean {
        val doc = ctl?.bindFight == true
        val want = fightWant ?: return doc
        if (want == doc || SystemClock.uptimeMillis() >= fightUntil) { fightWant = null; return doc }
        return want
    }
    /** batch5 (the user: tabs "above where it says 'no triggers held' (that text can be removed)"): the two tabs where that line stood,
     *  side by side in the box's middle; [i] 0 Normal, 1 In combat */
    private fun fightRect(i: Int, w: Float): RectF {
        val cy = 84f + 104f + 206f
        val l = w / 2f - 330f + i * 336f
        return RectF(l, cy - 38f, l + 324f, cy + 38f)
    }
    /** LB / RB as the app's own reader sees them (on the Thor the game never hears LB: it is its emulated Ctrl): a NEW press moves the tab */
    private var lbWas = false
    private var rbWas = false
    private fun bumpers(lb: Boolean, rb: Boolean) {
        val lbNew = lb && !lbWas; val rbNew = rb && !rbWas
        lbWas = lb; rbWas = rb
        if (!lbNew && !rbNew) return
        if (ctl?.mode != "bind") return
        val want = rbNew                                               // LB = the left tab (Normal), RB = the right one (In combat)
        if (want == fightShown()) return
        pickFight(want)
    }
    /** ONE tap on the addon's column for [fight] (no-action, idempotent), shown at once */
    private fun pickFight(fight: Boolean) {
        val cols = fightCols() ?: return
        onTap(if (fight) cols.first else cols.second)
        fightWant = fight; fightUntil = SystemClock.uptimeMillis() + 1500L
        invalidate()
    }
    /** the addon can take the pair by touch (its columns are on the row) */
    private fun fightCols(): Pair<Int, Int>? {
        val win = book?.win ?: return null
        return (win["bfon"] ?: return null) to (win["bfoff"] ?: return null)
    }
    private var pad: PadInput? = null

    // the card
    private var seenNid = -1                        // -1 = no document yet: what is already there is not new
    private val queue = ArrayList<Int>()
    private var cardSpell: Int? = null
    private var cardAt = 0L
    private val cardRect = RectF()

    init { CastSettings.init(context) }

    private fun style(): SpellsModel.PadStyle = when (CastSettings.buttonLayout) {
        CastSettings.ButtonLayout.XBOX -> SpellsModel.PadStyle.LTR
        CastSettings.ButtonLayout.PLAYSTATION -> SpellsModel.PadStyle.SHP
        CastSettings.ButtonLayout.NINTENDO -> SpellsModel.PadStyle.REV
    }

    // ---------------------------------------------------------------- data in

    fun onBook(b: SpellsModel.Book) {
        book = b
        val top = b.news.maxOfOrNull { it.nid } ?: 0
        if (seenNid < 0) seenNid = top
        else {
            for (n in SpellsModel.newSince(b, seenNid)) if (n.spellId !in queue && n.spellId != cardSpell) queue += n.spellId
            seenNid = maxOf(seenNid, top)
        }
        tick()
    }

    fun onCtl(c: SpellsModel.Ctl) {
        val was = boxOpen()
        ctl = c
        if (c.on && c.bindOpen) {
            // a box that opens afresh forgets the last answer
            if (boxSpell != c.binding || c.note == "bind-wait") { boxSpell = c.binding; result = null }
        }
        if (!c.on) { boxSpell = null; result = null }
        if (boxOpen() && !was) startPad() else if (!boxOpen() && was) stopPad()
        tick()
    }

    /** batch8: the add-on's padmap (Spells' PadMap): each button's In combat spell, for the box's In combat tab */
    private var padMap: PadMapModel.Map? = null
    fun onPadMap(m: PadMapModel.Map) { padMap = m; if (boxOpen()) invalidate() }

    fun onBind(r: SpellsModel.BindResult) {
        result = r; resultAt = SystemClock.uptimeMillis()
        if (boxSpell == null) boxSpell = r.spellId
        tick()
    }

    /** the box is up: the addon's box waits, or its answer shows */
    fun boxOpen(): Boolean {
        val c = ctl ?: return false
        return c.on && (c.mode == "bind" || c.mode == "result")
    }

    private fun startPad() {
        if (pad != null || otherReader()) return
        pad = PadInput(onPad = { l, r, lb, rb -> lt = l; rt = r; bumpers(lb, rb); invalidate() }, onStatus = { _, _ -> }).also {
            it.triggerSwap = CastSettings.triggerSwap
            it.start()
        }
    }

    private fun stopPad() {
        pad?.stop(); pad = null; lt = false; rt = false
    }

    // everything that moves on its own: the card's life, the answer's 1.5 s
    private fun tick() {
        val now = SystemClock.uptimeMillis()
        val card = cardSpell
        if (card != null && now - cardAt > CARD_MS) { cardSpell = null }
        if (cardSpell == null && queue.isNotEmpty() && !blocked() && !boxOpen()) {
            cardSpell = queue.removeAt(0); cardAt = now
        }
        visibility = if (boxOpen() || cardSpell != null) VISIBLE else GONE
        invalidate()
        if (cardSpell != null || (result != null && now - resultAt < SHOW_MS) || queue.isNotEmpty())
            postDelayed({ tick() }, 50)
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (boxOpen()) { bindBox(c, w, h); return }
        cardSpell?.let { card(c, w, it) }
    }

    /** the game's "Bind to Action Bar": its box and words, the controller's buttons for the set held (SPEC 4) */
    private fun bindBox(c: Canvas, w: Float, h: Float) {
        val b = book
        val e = boxSpell?.let { id -> b?.let { SpellsModel.find(it, id) } }
        UiKit.cover(c, RectF(0f, 0f, w, h), 0.75f)
        val box = RectF(60f, 84f, w - 60f, 934f)
        UiKit.dialog(c, box)
        UiKit.dialogHeader(c, w / 2f, box.top - 34f, "Bind to Action Bar")
        val cy = box.top + 104f
        val st = style()
        val now = SystemClock.uptimeMillis()
        // the answer: while the addon shows it (mode "result"), or 1.5 s for one that keeps the box waiting (reserved, unsure)
        val r = result?.takeIf { it.spellId == boxSpell && (ctl?.mode == "result" || now - resultAt < SHOW_MS) }

        // the line: the instruction, or the answer
        if (r != null && r.ok && r.pad != null) {
            val p = UiKit.bodyPaint(32f, Color.rgb(30, 255, 0)).apply { textAlign = Paint.Align.RIGHT }
            c.drawText(if (r.fight) "In combat on" else "Bound to", w / 2f - 60f, cy - (p.ascent() + p.descent()) / 2f, p)
            drawCombo(c, SpellsModel.combo(r.pad, st), w / 2f - 40f, cy, 64f, Theme.text, true)
        } else if (r != null && !r.ok) {
            val (text, colour) = when (r.why) {
                "combat" -> "Not while in combat" to Theme.unusable
                "reserved" -> "That button cannot be changed" to Theme.unusable
                "bind" -> "A game key rides that button: clear it first" to Theme.unusable        // b37: In combat
                "dpad bar" -> "The DS toolbar has the plain D-pad: hold a trigger" to Theme.unusable
                "unsure" -> "Press it again" to Theme.warn
                else -> "That did not work" to Theme.unusable
            }
            val p = UiKit.bodyPaint(30f, colour).apply { textAlign = Paint.Align.CENTER }
            c.drawText(text, w / 2f, cy - (p.ascent() + p.descent()) / 2f, p)
        } else {
            val p = UiKit.bodyPaint(30f, Color.WHITE).apply { textAlign = Paint.Align.CENTER }
            c.drawText("Press the button combination you want to use for:", w / 2f, cy - (p.ascent() + p.descent()) / 2f, p)
        }

        // the spell
        if (e != null) {
            val pn = UiKit.bodyPaint(40f, Theme.gold)
            val bx = w / 2f - (pn.measureText(e.name) + 104f) / 2f
            UiKit.slot(c, Icons.get(e.icon), bx, cy + 46f, 84f)
            c.drawText(e.name, bx + 104f, cy + 88f - (pn.ascent() + pn.descent()) / 2f, pn)
        }
        UiKit.divider(c, box.left + 90f, cy + 166f, box.right - 90f, cy + 166f)

        // which set: the triggers held now (the app's own read-only controller reader, as the Cast room's)
        // once a button took the spell, the box shows THAT set with the button lit (SPEC 4: "ZR held" in the bound picture)
        val bound = r?.takeIf { it.ok }?.pad
        val set = bound?.bar ?: when { lt && rt -> 4; rt -> 3; lt -> 2; else -> 1 }
        // b-bind-combat-toggle, batch5 (the user's pick: "tabs ... instead of minus to flip make it l and r to toggle that tab left or right
        // and have the glyphs on the outsides of the buttons still in them"): Normal | In combat where "No trigger held" stood (removed),
        // the game's tab look, LB inside the left tab's outer edge and RB inside the right one's; LB / RB (the app's reader) or a tap. While
        // the answer shows, the tab it went on stays lit
        val f = if (r != null && r.ok) r.fight else fightShown()
        UiKit.tab(c, fightRect(0, w), "Normal", !f)
        UiKit.tab(c, fightRect(1, w), "In combat", f)
        if (r == null || !r.ok) {
            UiKit.pad(c, "${st.prefix}_lshoulder", fightRect(0, w).left + 40f, fightRect(0, w).centerY(), 44f)
            UiKit.pad(c, "${st.prefix}_rshoulder", fightRect(1, w).right - 40f, fightRect(1, w).centerY(), 44f)
        }

        // the D-pad's four and the face buttons' four: the Cast room's own drawing of that set; stand-ins until it has a layout
        val dpad = RectF(w / 2f - 270f - 240f, cy + 236f, w / 2f - 270f + 240f, cy + 236f + 340f)
        val face = RectF(w / 2f + 290f - 240f, cy + 236f, w / 2f + 290f + 240f, cy + 236f + 340f)
        val lit = r?.takeIf { it.ok }?.pad?.takeIf { it.bar == set }?.pos ?: -1
        val icon = e?.let { Icons.get(it.icon) }
        // batch8 (the user: "when on the in combat tab the spells should all change to show what's bound for in combat"): with In combat
        // on, each button shows its IN COMBAT spell for that set (the Controller room's In combat tab: the overlay's fight spell, a base X /
        // Y / A's; the gold swords on it; none = an empty slot), from the add-on's padmap; Normal shows the game's bar as before
        // Home's own drawing either way (tracking for the user: "identical look, only the spells and swords change"): CastView.drawSet
        // with each button's fight spell; the stand-ins only until the Cast room has its first layout
        val m = padMap
        val fight: ((Int) -> Int?)? = if (f && m != null) { p -> fightIcon(m, set, p) } else null
        val drawn = try { drawSet(c, set, dpad, face, lit.takeIf { it > 0 }, icon, fight) } catch (_: Throwable) { false }
        if (!drawn) standIns(c, dpad, face, lit, icon, st)

        // the way back: B (the user on batch4: "instead of showing the - button as back when no triggers are held it should show B"; a
        // plain B backs out, Book b39); the "-" flips Normal / In combat. The triggers' glyphs stand bare ("the text on the ZL and ZR can be
        // removed")
        // batch5 (the user: "remove the ZR/ZL glyphs in the bottom right and the B button glyph and text when holding a trigger at all"):
        // no trigger glyphs here; B / Back only while no trigger is held (with one, B is a place for the spell)
        // batch7 (the user: "we need to add ZL and ZR as glyphs to the bottom middle that get lit up when held like on the home screen"):
        // Home's trigger chips (CastView's strip: UiKit.triggerChip, its glyph set and size), lit while the app's reader sees them held
        val tset = when (CastSettings.buttonLayout) {
            CastSettings.ButtonLayout.XBOX -> "gen"; CastSettings.ButtonLayout.PLAYSTATION -> "shp"; else -> "rev"
        }
        val tcy = box.bottom - 92f
        UiKit.triggerChip(c, RectF(w / 2f - 64f - 42f, tcy - 28f, w / 2f - 64f + 42f, tcy + 28f), "${tset}_ltrigger", lt)
        UiKit.triggerChip(c, RectF(w / 2f + 64f - 42f, tcy - 28f, w / 2f + 64f + 42f, tcy + 28f), "${tset}_rtrigger", rt)
        val back = faceGlyph(st, "b")
        if (r != null && r.ok) UiKit.hints(c, box.right - 80f, box.bottom - 92f, back to "Close")
        else if (!lt && !rt) UiKit.hints(c, box.right - 80f, box.bottom - 92f, back to "Back")
    }

    /** empty slots with the glyphs, where the Cast room's drawing goes, for as long as it has none */
    private fun standIns(c: Canvas, dpad: RectF, face: RectF, lit: Int, icon: android.graphics.Bitmap?, st: SpellsModel.PadStyle) {
        cross(c, dpad, intArrayOf(1, 2, 3, 4), arrayOf("gen_left", "gen_up", "gen_right", "gen_down"), lit, icon)
        // face buttons by PHYSICAL place (left, top, right, bottom), as the Cast room draws them: on a Nintendo pad the game hears the
        // button printed Y (left) as its top key, X (top) as its left key, A (right) as its bottom key, B (bottom) as its right key
        val (positions, keys) = if (CastSettings.buttonLayout == CastSettings.ButtonLayout.NINTENDO)
            intArrayOf(6, 5, 8, 7) to arrayOf("y", "x", "a", "b") else intArrayOf(5, 6, 7, 8) to arrayOf("x", "y", "b", "a")
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

    /** the "New spell" card over the top of whatever room is open (SPEC 5) */
    private fun card(c: Canvas, w: Float, spellId: Int) {
        val b = book ?: return
        val e = SpellsModel.find(b, spellId) ?: return
        val age = SystemClock.uptimeMillis() - cardAt
        val a = when {
            age < 150 -> age / 150f
            age > CARD_MS - 250 -> ((CARD_MS - age) / 250f).coerceAtLeast(0f)
            else -> 1f
        }
        val layer = c.saveLayerAlpha(0f, 0f, w, 200f, (a * 255).toInt())
        val r = cardRect.apply { set((w - 820f) / 2f, 10f, (w + 820f) / 2f, 150f) }
        UiKit.card(c, r, queue.size)
        UiKit.spell(c, Icons.get(e.icon), r.left + 58f, r.top + 26f, 88f, onDark = true)
        val pt = UiKit.bodyPaint(24f, Theme.gold)
        c.drawText("New spell", r.left + 176f, r.top + 36f - (pt.ascent() + pt.descent()) / 2f, pt)
        val pn = UiKit.bodyPaint(34f, Color.WHITE)
        val name = TextUtils.ellipsize(e.name, android.text.TextPaint(pn), r.right - 250f - (r.left + 176f), TextUtils.TruncateAt.END).toString()
        c.drawText(name, r.left + 176f, r.top + 74f - (pn.ascent() + pn.descent()) / 2f, pn)
        e.rank?.let {
            val pr = UiKit.bodyPaint(22f, Theme.dim)
            c.drawText(it, r.left + 176f, r.top + 110f - (pr.ascent() + pr.descent()) / 2f, pr)
        }
        // where the game put it: the controller button that holds it now (read at every frame: the fly-in may land during the 4 s)
        val slot = e.pad.firstOrNull()
        if (slot != null) {
            val combo = SpellsModel.combo(slot, style())
            val n = 64f
            // SPEC 5: 64 wide for a button alone, 156 with a trigger; with both triggers the first stands 62 px before that
            val wide = when (combo.triggers.size) { 0 -> n; 1 -> 2f * n + 28f; else -> 62f + 2f * n + 28f }
            drawCombo(c, combo, r.right - 44f - wide, r.centerY(), n, Theme.text, true)
        } else {
            val pb = UiKit.bodyPaint(26f, Theme.notOnBar).apply { textAlign = Paint.Align.RIGHT }
            c.drawText("Not on a button", r.right - 40f, r.centerY() - 2f - (pb.ascent() + pb.descent()) / 2f, pb)
        }
        if (queue.isNotEmpty()) {
            val pc = UiKit.numberPaint(22f, Theme.dim).apply { textAlign = Paint.Align.RIGHT }
            c.drawText("1 of ${queue.size + 1}", r.right - 40f, r.bottom - 22f - (pc.ascent() + pc.descent()) / 2f, pc)
        }
        c.restoreToCount(layer)
    }

    // ---------------------------------------------------------------- touch

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (boxOpen()) {                                            // a dialog: every touch is its
            // b37: the Normal / In combat pair, while the box waits: ONE tap on its column (no-action, idempotent)
            val w = width.toFloat()
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> downFight = (0..1).firstOrNull { fightRect(it, w).contains(ev.x, ev.y) } ?: -1
                MotionEvent.ACTION_UP -> {
                    val i = downFight; downFight = -1
                    val cols = fightCols()
                    if (i >= 0 && fightRect(i, w).contains(ev.x, ev.y) && cols != null && ctl?.mode == "bind") pickFight(i == 1)
                }
                MotionEvent.ACTION_CANCEL -> downFight = -1
            }
            return true
        }
        val card = cardSpell ?: return false
        if (!cardRect.contains(ev.x, ev.y)) return false            // beside the card: the room's
        if (ev.actionMasked == MotionEvent.ACTION_UP) {
            queue.clear(); cardSpell = null
            onOpen(card)
            tick()
        }
        return true
    }

    override fun onDetachedFromWindow() {
        stopPad()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val CARD_MS = 4000L
        private const val SHOW_MS = 1500L
    }
}
