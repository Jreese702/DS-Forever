package com.jrees.wowcompanion

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View

/**
 * THE CONTROLLER ROOM'S PARTY MODE TAB (party_design.md B and the user's decisions 2026-11-11; PadTabCtl p37). Drawn by
 * [ControllerView] when the tab on show is "party", ON THE NORMAL LAYERS' OWN PICTURE ([PadDiamonds]): whatever is set here is exactly
 * what the pad does while Party mode is on.
 *
 * Left page: the SET ROW (ZL, ZR as on the normal page; ZL + ZR dimmed with a lock: nothing happens with both held), the TOUCH ROW
 * (Touch: the picked plate's second tap, Target by default; Hold: the player menu, locked), the D-PAD DIAMOND (all four: gold move
 * arrows, locked - they move the pick; p38, the user's adaptive rows 2026-11-11), the FACE DIAMOND (A, X, Y as set on the layer on
 * show; Y's base place split: out of combat Inspect / Smart Y, in combat `yc`; B the red Back, locked) and a FOOTER plate: Select, the swirl, B (the way in and out). Right page: the place's title, its icon,
 * "Now", ONE red Change. The CURSOR is the add-on's (PadTabCtl p37: ppos = the router's 8 positions, pset, ptouch, pfocus); a finger
 * picks with padtab 27-36 (PartyTaps.pick), sets the no-spell value with 37, a spell with Book's two taps.
 */
class PartyTabPage(
    private val host: View,
    /** taps on the add-on's pad rows (one batch); true = sent */
    private val tap: (List<Pair<String, Int>>) -> Boolean,
    /** Book's two taps for spell [id] (the `spells` row's vb / vo), null = not in the book now */
    private val spellCells: (Int) -> List<Pair<String, Int>>?,
) {
    // a finger's own choices, shown at once; the add-on's echo (padtabctl) takes over after ECHO_MS
    private var localPos = 8
    private var localSet = 1
    /** a finger on the Touch row (true) or the diamonds */
    private var localTouch = false
    private var localUntil = 0L
    private var localRight = 0
    /** the touch chooser (the controller's own is the add-on's, drawn from [ctl]) */
    private class Chooser(val slot: String?, val rows: List<Pair<String, PartyModel.Spell?>>, var sel: Int = 0, var top: Int = 0)
    private var chooser: Chooser? = null
    private var flash: String? = null
    private var flashUntil = 0L

    private fun live() = PartyModel.live

    private fun ctlHolds(ctl: PadMapModel.Ctl?) = ctl != null && ctl.on && ctl.view == "party" && SystemClock.uptimeMillis() >= localUntil
    /** the place the page shows as chosen: the controller's while it holds the tab, else the finger's */
    private fun pos(ctl: PadMapModel.Ctl?): Int = if (ctlHolds(ctl)) ctl!!.ppos.coerceIn(1, 8) else localPos
    private fun set(ctl: PadMapModel.Ctl?): Int = if (ctlHolds(ctl)) ctl!!.pset.coerceIn(1, 4) else localSet
    private fun onTouchRow(ctl: PadMapModel.Ctl?): Boolean =
        if (ctlHolds(ctl)) ctl!!.pfocus == "touch" || (ctl.pfocus == "right" && lastRightFromTouch) else localTouch
    private fun rightRow(ctl: PadMapModel.Ctl?): Int = if (ctlHolds(ctl)) ctl!!.pright.coerceAtLeast(0) else localRight
    private fun rightFocus(ctl: PadMapModel.Ctl?): Boolean = ctl != null && ctl.on && ctl.view == "party" && ctl.pfocus == "right"
    /** the controller went to the right page from the Touch row (pfocus touch, then right) */
    private var lastRightFromTouch = false

    /** the slot the page edits now: the Touch row's Touch, else the place on the layer on show (null: a locked place) */
    private fun curSlot(ctl: PadMapModel.Ctl?): String? =
        if (onTouchRow(ctl)) (if (ctlHolds(ctl) && ctl!!.ptouch == 2) null else "touch") else PartyModel.slotAt(set(ctl), pos(ctl))

    fun say(s: String) { flash = s; flashUntil = SystemClock.uptimeMillis() + 2500L; host.postInvalidateDelayed(2550L); host.invalidate() }

    fun onAck(a: PartyModel.Ack) {
        if (a.ok) { chooser = null; host.invalidate(); return }
        say(when (a.why) { "combat" -> "Not in a fight"; else -> "Not taken" })
    }

    fun chooserOpen(ctl: PadMapModel.Ctl?): Boolean = chooser != null || ctl?.chooser?.list in PartyModel.CTL_LISTS

    // ------------------------------------------------------------------------------------------------ geometry

    private fun swap() = CastSettings.faceSwap
    private fun sq(p: Int) = PadDiamonds.rect(PadDiamonds.square(p, swap()))
    private fun setR(i: Int) = PadDiamonds.rect(PadDiamonds.setRect(i))
    private fun touchR(i: Int) = PadDiamonds.rect(PadDiamonds.touchRect(i))
    private val act = RectF(790f, 804f, 1172f, 892f)
    private fun listRow(k: Int) = RectF(782f, LIST_TOP + k * ROW_H, 1192f, LIST_TOP + (k + 1) * ROW_H)

    private fun icon(s: PartyModel.Spell?) = s?.let { Icons.get(it.i) }

    private fun style(): SpellsModel.PadStyle = when (CastSettings.buttonLayout) {
        CastSettings.ButtonLayout.XBOX -> SpellsModel.PadStyle.LTR
        CastSettings.ButtonLayout.PLAYSTATION -> SpellsModel.PadStyle.SHP
        CastSettings.ButtonLayout.NINTENDO -> SpellsModel.PadStyle.REV
    }
    private fun glyph(p: Int) = SpellsModel.combo(SpellsModel.PadSlot(0, 1, 1, p), style()).button
    private fun padSet(): String = when (CastSettings.buttonLayout) {
        CastSettings.ButtonLayout.XBOX -> "ltr"; CastSettings.ButtonLayout.PLAYSTATION -> "shp"; else -> "rev"
    }
    private fun face(f: String): String = Glyphs.translate("rev_$f", CastSettings.buttonLayout)

    private val pFlat = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pBmp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pDim = Paint().apply { color = Color.argb(150, 14, 10, 6) }

    private fun lock(c: Canvas, r: RectF) =
        UiKit.tex("talent_lock")?.let { c.drawBitmap(it, null, RectF(r.right - 20f, r.bottom - 30f, r.right - 2f, r.bottom - 4f), pBmp) }

    /** a gold move arrow in [r]'s middle, pointing [deg] (0 = right, -90 = up) */
    private fun moveArrow(c: Canvas, r: RectF, deg: Float) {
        val a = UiKit.tex("move_arrow_right") ?: return
        c.save(); c.rotate(deg, r.centerX(), r.centerY())
        c.drawBitmap(a, null, RectF(r.centerX() - 22f, r.centerY() - 22f, r.centerX() + 22f, r.centerY() + 22f), pBmp)
        c.restore()
    }

    // ------------------------------------------------------------------------------------------------ draw

    fun draw(c: Canvas, w: Float, h: Float, ctl: PadMapModel.Ctl?) {
        lastCtl = ctl
        val cfg = live().cfg
        val s = set(ctl)
        val p = pos(ctl)
        val k = ctl?.takeIf { it.on && it.view == "party" }
        if (k?.pfocus == "touch") lastRightFromTouch = true else if (k?.pfocus == "diam" || k?.pfocus == "sets") lastRightFromTouch = false
        // THE SET ROW: ZL / ZR (the lit one is the layer on show), ZL + ZR dimmed with a lock
        val trig = SpellsModel.combo(SpellsModel.PadSlot(0, 4, 4, 1), style()).triggers
        for (i in 0..2) {
            val r = setR(i)
            UiKit.choice(c, r, "", i + 2 == s, enabled = i < 2)
            val g = when (i) { 0 -> listOf(trig[0]); 1 -> listOf(trig[1]); else -> trig }
            if (g.size == 1) UiKit.pad(c, g[0], r.centerX(), r.centerY(), 56f)
            else { UiKit.pad(c, g[0], r.centerX() - 32f, r.centerY(), 52f, 150); UiKit.pad(c, g[1], r.centerX() + 32f, r.centerY(), 52f, 150) }
            if (i == 2) lock(c, r)
        }
        // THE TOUCH ROW: Touch (the finger, then its action), Hold (the player menu, locked)
        for (i in 0..1) {
            val r = touchR(i)
            UiKit.choice(c, r, "", false)
            PartyArt.gauntlet(c, r.left + 36f, r.centerY(), 46f)
            val ix = r.left + 72f; val iy = r.centerY() - 26f
            val pn = TextPaint(UiKit.bodyPaint(25f, Theme.text))            // on the dark tile (UiKit.choice), as the set plates
            if (i == 0) {
                val t = cfg?.touch
                UiKit.spell(c, icon(t), ix, iy, 52f, if (t != null && !t.k) UiKit.Spell.LEVEL else UiKit.Spell.KNOWN)
                if (t == null) PartyArt.targetRing(c, ix + 26f, r.centerY(), 18f)
                fitLine(c, t?.n ?: "Target", ix + 64f, r.centerY() + 9f, r.right - ix - 70f, pn, 25f, 18f)
            } else {
                UiKit.spell(c, UiKit.tex("fixed_inspect"), ix, iy, 52f, UiKit.Spell.LEVEL)
                fitLine(c, "Menu", ix + 64f, r.centerY() + 9f, r.right - ix - 70f, pn, 25f, 18f)
                lock(c, r)
            }
        }
        // THE TWO DIAMONDS
        for (q in 1..8) drawPlace(c, q, s, cfg)
        for (q in 1..8) sq(q).let { r -> UiKit.pad(c, glyph(q), r.centerX() + 36f, r.centerY() - 36f, 40f) }
        // THE FOOTER: Select -> the swirl -> B (the way in and out of Party mode)
        val f = PadDiamonds.rect(PadDiamonds.FOOTER)
        UiKit.module(c, f)
        UiKit.pad(c, face("minus"), f.left + 80f, f.centerY(), 46f)
        UiKit.tex("home_swirl")?.let { c.drawBitmap(it, null, RectF(f.centerX() - 26f, f.centerY() - 26f, f.centerX() + 26f, f.centerY() + 26f), pBmp) }
        UiKit.pad(c, face("b"), f.right - 80f, f.centerY(), 46f)
        moveArrow(c, RectF(f.left + 130f, f.top, f.centerX() - 40f, f.bottom), 0f)
        moveArrow(c, RectF(f.centerX() + 40f, f.top, f.right - 130f, f.bottom), 0f)
        // ONE cursor (the controller's or a finger's): the set plate, the Touch tile, the place; on the right page the place wears the
        // checked mark (the normal page's rule)
        val quiet = SystemClock.uptimeMillis() >= localUntil
        when {
            k != null && quiet && k.pfocus == "tabs" -> {}
            k != null && quiet && k.pfocus == "sets" -> UiKit.focus(c, setR((k.scur - 2).coerceIn(0, 2)), onPaper = true)
            onTouchRow(ctl) -> {
                val r = touchR(if (ctlHolds(ctl)) (ctl!!.ptouch - 1).coerceIn(0, 1) else 0)
                if (rightFocus(ctl) || chooserOpen(ctl)) UiKit.checkedGlow(c, r) else UiKit.focus(c, r, onPaper = true)
            }
            rightFocus(ctl) || chooserOpen(ctl) -> UiKit.checkedGlow(c, sq(p))
            else -> UiKit.slotFocus(c, sq(p))
        }
        drawRight(c, ctl, cfg, s, p)
        flash?.takeIf { SystemClock.uptimeMillis() < flashUntil }?.let {
            val pw = UiKit.inkPaint(24f).apply { color = Color.rgb(150, 30, 20); textAlign = Paint.Align.CENTER }
            c.drawText(Tr.t(it), act.centerX(), act.top - 24f, pw)
        }
    }

    /** one place on the diamonds: the D-pad's locked arrows (all four move the pick), a face's spell on the layer on show, B the red Back */
    private fun drawPlace(c: Canvas, q: Int, s: Int, cfg: PartyModel.Cfg?) {
        val r = sq(q)
        when {
            // p38 (the user's adaptive rows, 2026-11-11): all four directions move the pick - gold arrows, locked
            PartyModel.movesPick(q) -> {
                UiKit.spell(c, null, r.left, r.top, 80f, UiKit.Spell.KNOWN)
                moveArrow(c, r, when (q) { 1 -> 180f; 2 -> -90f; 3 -> 0f; else -> 90f })
                lock(c, r)
            }
            q == 7 -> { UiKit.spell(c, UiKit.tex("square_exit_up"), r.left, r.top, 80f, UiKit.Spell.KNOWN); lock(c, r) }
            else -> {
                val slot = PartyModel.slotAt(s, q)
                if (slot == null) { UiKit.spell(c, null, r.left, r.top, 80f, UiKit.Spell.LEVEL); lock(c, r); return }
                if (slot == "yc") { drawSplitY(c, r, cfg); return }
                val sp = cfg?.slot(slot)
                UiKit.spell(c, icon(sp), r.left, r.top, 80f, if (sp != null && !sp.k) UiKit.Spell.LEVEL else UiKit.Spell.KNOWN)
                if (sp == null) c.drawRoundRect(RectF(r.left + 4f, r.top + 4f, r.right - 4f, r.bottom - 4f), 6f, 6f, pDim)
            }
        }
    }

    /** Y's base place, two parts as today: out of combat Inspect / Smart Y on top, in combat `yc` below with the crossed swords pip */
    private fun drawSplitY(c: Canvas, r: RectF, cfg: PartyModel.Cfg?) {
        UiKit.spell(c, null, r.left, r.top, 80f, UiKit.Spell.KNOWN)
        val top = if (cfg?.sy?.on == true) cfg.sy.list.firstOrNull()?.let { icon(it) } ?: UiKit.tex("fixed_inspect") else UiKit.tex("fixed_inspect")
        top?.let { c.drawBitmap(it, null, RectF(r.left + 6f, r.top + 4f, r.left + 42f, r.top + 40f), pBmp) }
        (icon(cfg?.yc) ?: UiKit.tex("fixed_inspect"))?.let { c.drawBitmap(it, null, RectF(r.right - 42f, r.bottom - 40f, r.right - 6f, r.bottom - 4f), pBmp) }
        UiKit.tex("combat_swords")?.let { c.drawBitmap(it, null, RectF(r.left + 6f, r.bottom - 26f, r.left + 30f, r.bottom - 4f), pBmp) }
    }

    private fun fitLine(c: Canvas, s: String, x: Float, y: Float, room: Float, p: TextPaint, size: Float, min: Float) {
        p.textSize = size
        while (p.textSize > min && p.measureText(s) > room) p.textSize -= 1f
        c.drawText(Tr.t(TextUtils.ellipsize(s, p, room, TextUtils.TruncateAt.END).toString()), x, y, p)
    }

    private fun title(c: Canvas, iconArt: () -> Unit, name: String, sub: String) {
        iconArt()
        val pt = TextPaint(UiKit.inkPaint(46f, heading = true))
        fitLine(c, name, 940f, 244f, 1192f - 940f, pt, 46f, 30f)
        c.drawText(Tr.t(sub), 942f, 290f, UiKit.inkPaint(25f).apply { color = Theme.parchmentInkDim })
        UiKit.tex("book_divider")?.let { c.drawBitmap(it, null, RectF(782f, 337f, 1192f, 348f), null) }
    }

    private fun section(c: Canvas, s: String, y: Float) = c.drawText(Tr.t(s), 790f, y, UiKit.inkPaint(32f, heading = true))

    /** the place's words: "ZL + A", "D-pad Up", "Touch" ... */
    private fun placeWord(s: Int, p: Int, touch: Boolean): String {
        if (touch) return "Touch"
        val trig = when (s) { 2 -> Glyphs.name("ltrigger") + " + "; 3 -> Glyphs.name("rtrigger") + " + "; else -> "" }
        return when (p) {
            1 -> "D-pad Left"; 2 -> "D-pad Up"; 3 -> "D-pad Right"; 4 -> "D-pad Down"
            5 -> trig + Glyphs.name("x"); 6 -> trig + Glyphs.name("y"); 7 -> Glyphs.name("b"); else -> trig + Glyphs.name("a")
        }
    }

    private fun drawRight(c: Canvas, ctl: PadMapModel.Ctl?, cfg: PartyModel.Cfg?, s: Int, p: Int) {
        // the chooser (a finger's, or the controller's list "party" / "pbuff") stands over the right page
        val ctlCh = ctl?.chooser?.takeIf { it.list in PartyModel.CTL_LISTS && ctl.on }
        if (chooser != null || ctlCh != null) { drawChooser(c, ctlCh, cfg); return }
        if (cfg == null) {
            c.drawText(Tr.t("Waiting for the game"), 790f, 400f, UiKit.inkPaint(27f).apply { color = Theme.parchmentInkDim })
            return
        }
        val touch = onTouchRow(ctl)
        val holdTile = touch && ctlHolds(ctl) && ctl!!.ptouch == 2
        val ink = UiKit.inkPaint(27f)
        val slot = curSlot(ctl)
        val word = if (holdTile) "Hold" else placeWord(s, p, touch)
        // a locked place: its title and what it does, Change disabled
        if (slot == null) {
            val what = when {
                holdTile -> "Player menu"
                touch -> "Touch"
                PartyModel.movesPick(p) -> "Moves the pick"
                p == 7 -> "Party mode off"
                else -> "Nothing (both held)"
            }
            title(c, { UiKit.spell(c, null, 820f, 206f, 96f, UiKit.Spell.LEVEL) }, word, what)
            UiKit.button(c, act, "Change", face("a"), UiKit.Button.DISABLED)
            return
        }
        val sp = when (slot) { "touch" -> cfg.touch; "yc" -> cfg.yc; else -> cfg.slot(slot) }
        title(c, {
            UiKit.spell(c, icon(sp) ?: if (slot == "yc") UiKit.tex("fixed_inspect") else null, 820f, 206f, 96f, UiKit.Spell.KNOWN)
            if (sp == null && slot == "touch") PartyArt.targetRing(c, 868f, 254f, 30f)
        }, word, "In Party mode")
        if (slot == "yc" && cfg.sy.on) { drawSmartY(c, ctl, cfg); return }
        if (slot == "yc") {
            // Y (two parts as today): out of combat Inspect; in combat the chosen spell (crossed swords); ONE red Change (the In combat action)
            section(c, "Out of combat", 380f)
            UiKit.spell(c, UiKit.tex("fixed_inspect"), 838f, 422f, 58f, UiKit.Spell.KNOWN)
            c.drawText(Tr.t("Inspect"), 910f, 461f, ink)
            section(c, "In combat", 556f)
            UiKit.spell(c, icon(cfg.yc) ?: UiKit.tex("fixed_inspect"), 838f, 598f, 58f, UiKit.Spell.KNOWN)
            UiKit.tex("combat_swords")?.let { c.drawBitmap(it, null, RectF(880f, 580f, 918f, 618f), null) }
            c.drawText(Tr.t(cfg.yc?.n ?: "Inspect"), 910f, 637f, ink)
            button(c, "Change", live().cfg?.pad?.slot0 != null)
            return
        }
        section(c, "Now", 380f)
        UiKit.spell(c, icon(sp), 838f, 422f, 58f, if (sp != null && !sp.k) UiKit.Spell.LEVEL else UiKit.Spell.KNOWN)
        if (sp == null && slot == "touch") PartyArt.targetRing(c, 867f, 451f, 21f)
        c.drawText(Tr.t(sp?.n ?: if (slot == "touch") "Target" else "Empty"), 910f, 461f, ink)
        button(c, "Change", true)
    }

    private fun button(c: Canvas, label: String, enabled: Boolean) =
        UiKit.button(c, act, label, face("a"), if (!enabled || live().party?.fight == true) UiKit.Button.DISABLED else UiKit.Button.UP)

    /** the Y place's right page with Smart Y on (P4S): the party buffs, the Add row and (open question 2's default) In combat */
    private fun smartRows(cfg: PartyModel.Cfg): List<String> {
        val rows = ArrayList<String>()
        for (k in cfg.sy.list.indices) rows.add("buff")
        rows.add("add")
        if (YC_ROW_IN_SMART_Y) rows.add("yc")
        return rows
    }

    private fun drawSmartY(c: Canvas, ctl: PadMapModel.Ctl?, cfg: PartyModel.Cfg) {
        section(c, "Party buffs", 380f)
        val rows = smartRows(cfg)
        val sel = rightRow(ctl).coerceIn(0, rows.size - 1)
        val fit = ((act.top - 12f - LIST_TOP) / ROW_H).toInt().coerceAtLeast(1)
        val first = (sel - fit + 1).coerceIn(0, maxOf(0, rows.size - fit))
        val ink = TextPaint(UiKit.inkPaint(27f))
        val dim = TextPaint(UiKit.inkPaint(22f).apply { color = Theme.parchmentInkDim })
        for (k in first until minOf(rows.size, first + fit)) {
            val r = listRow(k - first)
            if (k == sel && (rightFocus(ctl) || ctl == null || !ctl.on)) UiKit.focus(c, r, onPaper = true)
            val cy = r.centerY()
            when (rows[k]) {
                "buff" -> {
                    val b = cfg.sy.list[k]
                    UiKit.spell(c, icon(b), 838f, cy - 29f, 58f, if (b.k) UiKit.Spell.KNOWN else UiKit.Spell.LEVEL)
                    fitLine(c, "${k + 1}. ${b.n}", 910f, cy - 6f, r.right - 916f, ink, 27f, 20f)
                    c.drawText(Tr.t("When it is not up"), 910f, cy + 26f, dim)
                }
                "add" -> {
                    UiKit.spell(c, null, 838f, cy - 29f, 58f, UiKit.Spell.KNOWN)
                    UiKit.pad(c, "${padSet()}_plus", 867f, cy, 30f)
                    c.drawText(Tr.t("Add a party buff"), 910f, cy - 6f, ink)
                }
                else -> {
                    UiKit.spell(c, icon(cfg.yc) ?: UiKit.tex("fixed_inspect"), 838f, cy - 29f, 58f, UiKit.Spell.KNOWN)
                    UiKit.tex("combat_swords")?.let { c.drawBitmap(it, null, RectF(880f, cy - 47f, 914f, cy - 13f), null) }
                    fitLine(c, "In combat: ${cfg.yc?.n ?: "Inspect"}", 910f, cy - 6f, r.right - 916f, ink, 27f, 20f)
                }
            }
        }
        val label = when (rows[sel]) { "buff" -> "Remove"; "add" -> "Add"; else -> "Change" }
        button(c, label, rows[sel] != "add" || cfg.sy.known.any { k -> cfg.sy.list.none { it.id == k.id } })
    }

    /** the chooser's rows: the pseudo row first ("Empty" / "Inspect" / "Target"), then partycfg.spells (for a Smart Y Add: sy.known) */
    private fun chooserRows(slot: String?, cfg: PartyModel.Cfg?): List<Pair<String, PartyModel.Spell?>> {
        if (cfg == null) return emptyList()
        if (slot == null) return cfg.sy.known.filter { k -> cfg.sy.list.none { it.id == k.id } }.map { it.n to it }
        val none = when (slot) { "yc" -> "Inspect"; "touch" -> "Target"; else -> "Empty" }
        return listOf(none to null) + cfg.spells.map { it.n to it }
    }

    private fun drawChooser(c: Canvas, ctlCh: PadMapModel.Chooser?, cfg: PartyModel.Cfg?) {
        val ch = chooser
        // the controller's "pbuff" (Smart Y's Add): sy.known minus the list, no pseudo row; "party": the pseudo row first
        val pbuff = ch == null && ctlCh?.list == "pbuff"
        val rows = ch?.rows ?: chooserRows(if (pbuff) null else curSlot(lastCtl), cfg)
        val sel = ch?.sel ?: ((ctlCh?.row ?: 1) - 1)
        section(c, if ((ch?.slot == null && ch != null) || pbuff) "Add a party buff" else "Change", 240f)
        val fit = ((900f - CHOOSE_TOP) / CHOOSE_H).toInt()
        val first = (sel - fit + 1).coerceIn(0, maxOf(0, rows.size - fit))
        ch?.top = first
        val ink = TextPaint(UiKit.inkPaint(27f))
        for (k in first until minOf(rows.size, first + fit)) {
            val r = RectF(782f, CHOOSE_TOP + (k - first) * CHOOSE_H, 1192f, CHOOSE_TOP + (k - first + 1) * CHOOSE_H)
            if (k == sel) UiKit.focus(c, r, onPaper = true)
            val (name, s) = rows[k]
            UiKit.spell(c, icon(s) ?: if (name == "Inspect") UiKit.tex("fixed_inspect") else null, 800f, r.centerY() - 29f, 58f, UiKit.Spell.KNOWN)
            if (s == null && name == "Target") PartyArt.targetRing(c, 829f, r.centerY(), 21f)
            fitLine(c, name, 876f, r.centerY() + 10f, r.right - 882f, ink, 27f, 20f)
        }
    }

    /** the controller's last document (the chooser's slot is the place its cursor stands on) */
    private var lastCtl: PadMapModel.Ctl? = null

    fun onCtl(c: PadMapModel.Ctl) {
        lastCtl = c
        if (c.chooser?.list in PartyModel.CTL_LISTS) chooser = null
        if (c.view == "party" && c.pfocus == "touch") lastRightFromTouch = true
        else if (c.view == "party" && (c.pfocus == "diam" || c.pfocus == "sets")) lastRightFromTouch = false
    }

    // ------------------------------------------------------------------------------------------------ touch

    private var downPlace = -1
    private var downSet = -1
    private var downTouch = -1
    private var downAct = false
    private var downList = -1
    private var downChoose = -1

    fun onTouch(ev: MotionEvent, ctl: PadMapModel.Ctl?): Boolean {
        val cfg = live().cfg
        val x = ev.x; val y = ev.y
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downPlace = (1..8).firstOrNull { sq(it).contains(x, y) } ?: -1
                downSet = (0..2).firstOrNull { setR(it).contains(x, y) } ?: -1
                downTouch = (0..1).firstOrNull { touchR(it).contains(x, y) } ?: -1
                downAct = act.contains(x, y)
                downList = if (curSlot(ctl) == "yc" && cfg?.sy?.on == true) (0 until 6).firstOrNull { listRow(it).contains(x, y) } ?: -1 else -1
                downChoose = if (chooser != null && x > 760f && y >= CHOOSE_TOP) ((y - CHOOSE_TOP) / CHOOSE_H).toInt() else -1
            }
            MotionEvent.ACTION_UP -> {
                val ch = chooser
                if (ch != null) {
                    if (downChoose >= 0 && x > 760f) choose(ch, ch.top + downChoose, cfg)
                    else if (x < 740f) { chooser = null }                                  // a touch on the left page: the chooser goes
                    host.invalidate(); return true
                }
                val now = SystemClock.uptimeMillis()
                when {
                    downSet >= 0 && setR(downSet).contains(x, y) -> {
                        // ZL / ZR shown by a finger (A again: the base layer); ZL + ZR locked
                        if (downSet == 2) say("Both held: nothing")
                        else { localSet = if (set(ctl) == downSet + 2) 1 else downSet + 2; localPos = pos(ctl); localTouch = false; localUntil = now + ECHO_MS }
                    }
                    downTouch >= 0 && touchR(downTouch).contains(x, y) -> {
                        if (downTouch == 1) say("The player menu: fixed")
                        else { localTouch = true; localRight = 0; localUntil = now + ECHO_MS; PartyTaps.pick(cfg, "touch")?.let { tap(it) } }
                    }
                    downPlace > 0 && sq(downPlace).contains(x, y) -> {
                        val s = set(ctl)
                        localPos = downPlace; localSet = s; localTouch = false; localRight = 0; localUntil = now + ECHO_MS
                        PartyModel.slotAt(s, downPlace)?.let { slot -> PartyTaps.pick(cfg, slot)?.let { tap(it) } }
                    }
                    downList >= 0 && listRow(downList).contains(x, y) -> {
                        val rows = cfg?.let { smartRows(it) } ?: emptyList()
                        localPos = pos(ctl); localSet = set(ctl); localTouch = false
                        localRight = downList.coerceIn(0, maxOf(0, rows.size - 1)); localUntil = now + ECHO_MS
                    }
                    downAct && act.contains(x, y) -> action(ctl, cfg)
                }
                host.invalidate()
            }
        }
        return true
    }

    /** the ONE red action, by touch */
    private fun action(ctl: PadMapModel.Ctl?, cfg: PartyModel.Cfg?) {
        if (cfg == null) return
        if (live().party?.fight == true) { say("Not in a fight"); return }
        val slot = curSlot(ctl) ?: return
        if (slot == "yc" && cfg.sy.on) {
            val rows = smartRows(cfg)
            val k = rightRow(ctl).coerceIn(0, rows.size - 1)
            when (rows[k]) {
                "buff" -> PartyTaps.pbOp(cfg, k + 1, PartyTaps.PbOp.REMOVE)?.let { tap(it) } ?: say("Not on the tap pad yet")
                "add" -> chooser = Chooser(null, chooserRows(null, cfg)).takeIf { it.rows.isNotEmpty() }
                else -> chooser = Chooser("yc", chooserRows("yc", cfg))
            }
            return
        }
        chooser = Chooser(slot, chooserRows(slot, cfg))
    }

    private fun choose(ch: Chooser, k: Int, cfg: PartyModel.Cfg?) {
        val (name, s) = ch.rows.getOrNull(k) ?: return
        ch.sel = k
        val cells = when {
            ch.slot == null -> s?.let { PartyTaps.pbAdd(cfg, spellCells(it.id)) }
            s == null -> PartyTaps.setNone(cfg, ch.slot)
            else -> PartyTaps.setSpell(cfg, ch.slot, s.id, spellCells(s.id))
        }
        if (cells == null || !tap(cells)) say("$name: not on the tap pad")
        else chooser = null
    }

    companion object {
        const val LIST_TOP = 410f
        const val ROW_H = 111f
        const val CHOOSE_TOP = 280f
        const val CHOOSE_H = 96f
        const val ECHO_MS = 1500L

        /**
         * Open question 2's switch (the user's default 2026-10-28): with Smart Y on, Y's In-combat action is changed from a LAST ROW of
         * the right page ("In combat: <yc>") with the one red Change. false = no such row (Y's in-combat action only with Smart Y off).
         */
        const val YC_ROW_IN_SMART_Y = true
    }
}
