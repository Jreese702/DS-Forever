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
 * THE CONTROLLER ROOM'S PARTY MODE TAB (PARTY_BUILD.md 4.4; SPEC_DRAFT v10, v9 trim; P4, P4Y, P4S). Drawn by [ControllerView] when
 * the tab on show is "party". The CURSOR is the add-on's (PadTabCtl: `ppos` 1..12, `pfocus` rows / right, `pright`, a chooser with
 * list "party"); a finger picks with `padtab` 27-36 (PartyTaps.pick), sets the no-spell value with 37, a spell with Book's two taps.
 *
 * Left page: two columns of six rows (111 high from y 188): LEFT 1 D-pad (fixed), 2 A, 3 X, 4 Y, 5 Touch, 6 (empty place); RIGHT
 * 7 ZL+A .. 12 ZR+Y. A row: the glyph, the spell in the book's frame, its name and ONE line; no button on a row; the chosen row is the
 * paper's gold. Right page: the titles, the icons and the current value, ONE red action (the user's v9 trim: no explanations).
 */
class PartyTabPage(
    private val host: View,
    /** taps on the add-on's pad rows (one batch); true = sent */
    private val tap: (List<Pair<String, Int>>) -> Boolean,
    /** Book's two taps for spell [id] (the `spells` row's vb / vo), null = not in the book now */
    private val spellCells: (Int) -> List<Pair<String, Int>>?,
) {
    /** ppos -> the slot it sets (null: the D-pad and the empty place) */
    private fun slotOf(pos: Int): String? = when (pos) {
        2 -> "a"; 3 -> "x"; 4 -> "yc"; 5 -> "touch"; 7 -> "zla"; 8 -> "zlx"; 9 -> "zly"; 10 -> "zra"; 11 -> "zrx"; 12 -> "zry"; else -> null
    }

    // a finger's own choices, shown at once; the add-on's echo (padtabctl) takes over after ECHO_MS
    private var localPos = 2
    private var localUntil = 0L
    private var localRight = 0
    /** the touch chooser (the controller's own is the add-on's, drawn from [ctl]) */
    private class Chooser(val slot: String?, val rows: List<Pair<String, PartyModel.Spell?>>, var sel: Int = 0, var top: Int = 0)
    private var chooser: Chooser? = null
    private var flash: String? = null
    private var flashUntil = 0L

    private fun live() = PartyModel.live

    /** the row the page shows as chosen: the controller's while it holds the tab, else the finger's */
    private fun pos(ctl: PadMapModel.Ctl?): Int =
        if (ctl != null && ctl.on && ctl.view == "party" && SystemClock.uptimeMillis() >= localUntil) ctl.ppos.coerceIn(1, 12) else localPos
    private fun rightRow(ctl: PadMapModel.Ctl?): Int =
        if (ctl != null && ctl.on && ctl.view == "party" && SystemClock.uptimeMillis() >= localUntil) ctl.pright.coerceAtLeast(0) else localRight
    private fun rightFocus(ctl: PadMapModel.Ctl?): Boolean = ctl != null && ctl.on && ctl.view == "party" && ctl.pfocus == "right"

    fun say(s: String) { flash = s; flashUntil = SystemClock.uptimeMillis() + 2500L; host.postInvalidateDelayed(2550L); host.invalidate() }

    fun onAck(a: PartyModel.Ack) {
        if (a.ok) { chooser = null; host.invalidate(); return }
        say(when (a.why) { "combat" -> "Not in a fight: try again after it"; null -> "The game did not take it"; else -> "The game did not take it" })
    }

    fun chooserOpen(ctl: PadMapModel.Ctl?): Boolean = chooser != null || ctl?.chooser?.list == "party"

    // ------------------------------------------------------------------------------------------------ geometry

    private fun rowRect(pos: Int): RectF {
        val col = if (pos <= 6) 0 else 1
        val k = (pos - 1) % 6
        val l = if (col == 0) 36f else 374f
        return RectF(l, ROW_TOP + k * ROW_H, l + 326f, ROW_TOP + (k + 1) * ROW_H)
    }
    private val act = RectF(790f, 804f, 1172f, 892f)
    private fun listRow(k: Int) = RectF(782f, LIST_TOP + k * ROW_H, 1192f, LIST_TOP + (k + 1) * ROW_H)

    // ------------------------------------------------------------------------------------------------ what a row says

    private class RowWords(val icon: android.graphics.Bitmap?, val name: String, val line: String, val dim: Boolean)

    private fun icon(s: PartyModel.Spell?) = s?.let { Icons.get(it.i) }

    private fun words(pos: Int, cfg: PartyModel.Cfg?): RowWords? {
        return when (pos) {
            1 -> RowWords(null, "Moves the box", "Fixed", false)
            6 -> null
            4 -> {
                val sy = cfg?.sy?.on == true
                RowWords(UiKit.tex("fixed_inspect"), if (sy) "Smart Y" else "Inspect", "In combat: ${cfg?.yc?.n ?: "Inspect"}", false)
            }
            5 -> cfg?.touch?.let { RowWords(icon(it), "Touch", it.n, !it.k) } ?: RowWords(null, "Touch", "Target", false)
            else -> {
                val s = slotOf(pos)?.let { cfg?.slot(it) }
                if (s == null) RowWords(null, "Empty", "Nothing on it", true)
                else RowWords(icon(s), s.n, if (s.res) "A dead member" else "Boxed member", !s.k)
            }
        }
    }

    private fun padSet(): String = when (CastSettings.buttonLayout) {
        CastSettings.ButtonLayout.XBOX -> "ltr"; CastSettings.ButtonLayout.PLAYSTATION -> "shp"; else -> "rev"
    }
    private fun face(f: String): String =
        if (padSet() == "shp") "shp_" + mapOf("x" to "square", "y" to "triangle", "b" to "circle", "a" to "cross").getValue(f) else "${padSet()}_$f"

    /** the row's glyph: a face, a chord (the trigger's 50 px over the face's 34 px), the D-pad's cross, the pointing gauntlet */
    private fun drawGlyph(c: Canvas, pos: Int, cx: Float, cy: Float) {
        when (pos) {
            1 -> dpadCross(c, cx, cy, 44f)
            2 -> UiKit.pad(c, face("a"), cx, cy, 44f)
            3 -> UiKit.pad(c, face("x"), cx, cy, 44f)
            4 -> UiKit.pad(c, face("y"), cx, cy, 44f)
            5 -> PartyArt.gauntlet(c, cx, cy, 52f)
            in 7..12 -> {
                val trig = if (pos <= 9) "ltrigger" else "rtrigger"
                val f = when ((pos - 7) % 3) { 0 -> "a"; 1 -> "x"; else -> "y" }
                UiKit.pad(c, "${padSet()}_$trig", cx, cy - 17f, 50f)
                UiKit.pad(c, face(f), cx, cy + 20f, 34f)
            }
        }
    }

    private val pFlat = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun dpadCross(c: Canvas, cx: Float, cy: Float, s: Float) {
        pFlat.style = Paint.Style.FILL; pFlat.color = Color.WHITE
        val a = s * 0.18f
        c.drawRoundRect(cx - a, cy - s / 2f, cx + a, cy + s / 2f, 4f, 4f, pFlat)
        c.drawRoundRect(cx - s / 2f, cy - a, cx + s / 2f, cy + a, 4f, 4f, pFlat)
    }

    /** the frame's content for rows with no spell: the D-pad's four gold arrows, Touch's target ring */
    private fun drawFrameArt(c: Canvas, pos: Int, x: Float, y: Float, size: Float) {
        if (pos == 1) {
            val cx = x + size / 2f; val cy = y + size / 2f
            pFlat.style = Paint.Style.FILL; pFlat.color = Theme.gold
            for (k in 0 until 4) {
                c.save(); c.rotate(90f * k, cx, cy)
                val p = android.graphics.Path(); p.moveTo(cx, cy - size * 0.36f); p.lineTo(cx - size * 0.12f, cy - size * 0.18f)
                p.lineTo(cx + size * 0.12f, cy - size * 0.18f); p.close(); c.drawPath(p, pFlat)
                c.restore()
            }
        } else if (pos == 5) PartyArt.targetRing(c, x + size / 2f, y + size / 2f, size * 0.36f)
    }

    // ------------------------------------------------------------------------------------------------ draw

    fun draw(c: Canvas, w: Float, h: Float, ctl: PadMapModel.Ctl?) {
        val cfg = live().cfg
        val chosen = pos(ctl)
        val cursorOnRows = ctl == null || !ctl.on || ctl.focus != "tabs"
        val pn = TextPaint(UiKit.inkPaint(27f))
        val pl = TextPaint(UiKit.inkPaint(22f).apply { color = Theme.parchmentInkDim })
        for (p in 1..12) {
            val r = rowRect(p)
            val wd = words(p, cfg) ?: continue
            if (p == chosen && cursorOnRows) UiKit.focus(c, r, onPaper = true)
            val cy = r.centerY()
            drawGlyph(c, p, r.left + 30f, cy)
            val ix = r.left + 96f; val iy = cy - 29f
            UiKit.spell(c, wd.icon, ix, iy, 58f, if (wd.dim) UiKit.Spell.LEVEL else UiKit.Spell.KNOWN)
            if (wd.icon == null) drawFrameArt(c, p, ix, iy, 58f)
            if (p == 1) UiKit.tex("talent_lock")?.let { c.drawBitmap(it, null, RectF(ix + 40f, iy + 34f, ix + 60f, iy + 62f), null) }
            val nx = r.left + 166f; val room = r.right - nx - 6f
            fitLine(c, wd.name, nx, cy - 6f, room, pn, 27f, 20f)
            fitLine(c, wd.line, nx, cy + 26f, room, pl, 22f, 16f)
        }
        drawRight(c, w, h, ctl, cfg, chosen)
        flash?.takeIf { SystemClock.uptimeMillis() < flashUntil }?.let {
            val pw = UiKit.inkPaint(24f).apply { color = Color.rgb(150, 30, 20); textAlign = Paint.Align.CENTER }
            c.drawText(it, act.centerX(), act.top - 24f, pw)
        }
    }

    private fun fitLine(c: Canvas, s: String, x: Float, y: Float, room: Float, p: TextPaint, size: Float, min: Float) {
        p.textSize = size
        while (p.textSize > min && p.measureText(s) > room) p.textSize -= 1f
        c.drawText(TextUtils.ellipsize(s, p, room, TextUtils.TruncateAt.END).toString(), x, y, p)
    }

    private fun title(c: Canvas, iconArt: () -> Unit, name: String, sub: String) {
        iconArt()
        val pt = TextPaint(UiKit.inkPaint(46f, heading = true))
        fitLine(c, name, 940f, 244f, 1192f - 940f, pt, 46f, 30f)
        c.drawText(sub, 942f, 290f, UiKit.inkPaint(25f).apply { color = Theme.parchmentInkDim })
        UiKit.tex("book_divider")?.let { c.drawBitmap(it, null, RectF(782f, 337f, 1192f, 348f), null) }
    }

    private fun section(c: Canvas, s: String, y: Float) = c.drawText(s, 790f, y, UiKit.inkPaint(32f, heading = true))

    private fun drawRight(c: Canvas, w: Float, h: Float, ctl: PadMapModel.Ctl?, cfg: PartyModel.Cfg?, pos: Int) {
        // the chooser (a finger's, or the controller's list "party") stands over the right page
        val ctlCh = ctl?.chooser?.takeIf { it.list == "party" && ctl.on }
        if (chooser != null || ctlCh != null) { drawChooser(c, ctlCh, cfg); return }
        if (cfg == null) {
            c.drawText("Waiting for the game", 790f, 400f, UiKit.inkPaint(27f).apply { color = Theme.parchmentInkDim })
            return
        }
        val wd = words(pos, cfg) ?: return
        val faceWord = when (pos) {
            1 -> "D-pad"; 2 -> Glyphs.name("a"); 3 -> Glyphs.name("x"); 4 -> Glyphs.name("y"); 5 -> "A party cell"
            in 7..9 -> "ZL + " + Glyphs.name(listOf("a", "x", "y")[pos - 7]); else -> "ZR + " + Glyphs.name(listOf("a", "x", "y")[(pos - 10).coerceIn(0, 2)])
        }
        title(c, {
            UiKit.spell(c, wd.icon, 820f, 206f - 0f, 96f, UiKit.Spell.KNOWN)
            if (wd.icon == null) drawFrameArt(c, pos, 820f, 206f, 96f)
        }, if (pos == 4) wd.name else if (pos == 5) "Touch" else wd.name, if (pos == 1) "D-pad, in Party mode" else "$faceWord, in Party mode")
        val ink = UiKit.inkPaint(27f)
        val dim = UiKit.inkPaint(21f).apply { color = Theme.parchmentInkDim }
        when (pos) {
            1 -> UiKit.button(c, act, "Change", face("a"), UiKit.Button.DISABLED)
            4 -> if (cfg.sy.on) drawSmartY(c, ctl, cfg) else {
                // P4Y (v9 trim): Out of combat = Inspect; In combat = the chosen spell (crossed swords); ONE red Change (the In combat action)
                section(c, "Out of combat", 380f)
                UiKit.spell(c, UiKit.tex("fixed_inspect"), 838f, 422f, 58f, UiKit.Spell.KNOWN)
                c.drawText("Inspect", 910f, 461f, ink)
                section(c, "In combat", 556f)
                UiKit.spell(c, icon(cfg.yc) ?: UiKit.tex("fixed_inspect"), 838f, 598f, 58f, UiKit.Spell.KNOWN)
                UiKit.tex("combat_swords")?.let { c.drawBitmap(it, null, RectF(880f, 580f, 918f, 618f), null) }
                c.drawText(cfg.yc?.n ?: "Inspect", 910f, 637f, ink)
                button(c, "Change", live().cfg?.pad?.slot0 != null)
            }
            5 -> {
                section(c, "Now", 380f)
                UiKit.spell(c, icon(cfg.touch), 838f, 422f, 58f, UiKit.Spell.KNOWN)
                if (cfg.touch == null) PartyArt.targetRing(c, 867f, 451f, 21f)
                c.drawText(cfg.touch?.n ?: "Target", 910f, 451f, ink)
                if (cfg.touch == null) c.drawText("Makes them your target", 910f, 480f, dim)
                button(c, "Change", true)
            }
            else -> button(c, "Change", true)
        }
    }

    private fun button(c: Canvas, label: String, enabled: Boolean) =
        UiKit.button(c, act, label, face("a"), if (!enabled || live().party?.fight == true) UiKit.Button.DISABLED else UiKit.Button.UP)

    /** the Y row's right page with Smart Y on (P4S): the party buffs, the Add row and (open question 2's default) In combat */
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
                    c.drawText("When it is not up", 910f, cy + 26f, dim)
                }
                "add" -> {
                    UiKit.spell(c, null, 838f, cy - 29f, 58f, UiKit.Spell.KNOWN)
                    UiKit.pad(c, "${padSet()}_plus", 867f, cy, 30f)
                    c.drawText("Add a party buff", 910f, cy - 6f, ink)
                    c.drawText("Yours, cast on others", 910f, cy + 26f, dim)
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
        val rows = ch?.rows ?: chooserRows(ctlSlot(), cfg)
        val sel = ch?.sel ?: ((ctlCh?.row ?: 1) - 1)
        section(c, if (ch?.slot == null && ch != null) "Add a party buff" else "Change", 240f)
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

    /** the slot the controller's chooser is for (the row its cursor stands on) */
    private var lastCtl: PadMapModel.Ctl? = null
    private fun ctlSlot(): String? = lastCtl?.let { slotOf(it.ppos) }

    fun onCtl(c: PadMapModel.Ctl) { lastCtl = c; if (c.chooser?.list == "party") chooser = null }

    // ------------------------------------------------------------------------------------------------ touch

    private var downRow = -1
    private var downAct = false
    private var downList = -1
    private var downChoose = -1

    fun onTouch(ev: MotionEvent, ctl: PadMapModel.Ctl?): Boolean {
        val cfg = live().cfg
        val x = ev.x; val y = ev.y
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRow = (1..12).firstOrNull { rowRect(it).contains(x, y) && words(it, cfg) != null } ?: -1
                downAct = act.contains(x, y)
                downList = if (pos(ctl) == 4 && cfg?.sy?.on == true) (0 until 6).firstOrNull { listRow(it).contains(x, y) } ?: -1 else -1
                downChoose = if (chooser != null && x > 760f && y >= CHOOSE_TOP) ((y - CHOOSE_TOP) / CHOOSE_H).toInt() else -1
            }
            MotionEvent.ACTION_UP -> {
                val ch = chooser
                if (ch != null) {
                    if (downChoose >= 0 && x > 760f) choose(ch, ch.top + downChoose, cfg)
                    else if (x < 740f) { chooser = null }                                  // a touch on the left page: the chooser goes
                    host.invalidate(); return true
                }
                if (downRow > 0 && rowRect(downRow).contains(x, y)) {
                    localPos = downRow; localRight = 0; localUntil = SystemClock.uptimeMillis() + ECHO_MS
                    slotOf(downRow)?.let { s -> PartyTaps.pick(cfg, s)?.let { tap(it) } }
                } else if (downList >= 0 && listRow(downList).contains(x, y)) {
                    val rows = cfg?.let { smartRows(it) } ?: emptyList()
                    val first = 0
                    localRight = (first + downList).coerceIn(0, maxOf(0, rows.size - 1)); localUntil = SystemClock.uptimeMillis() + ECHO_MS
                } else if (downAct && act.contains(x, y)) action(ctl, cfg)
                host.invalidate()
            }
        }
        return true
    }

    /** the ONE red action, by touch */
    private fun action(ctl: PadMapModel.Ctl?, cfg: PartyModel.Cfg?) {
        if (cfg == null) return
        if (live().party?.fight == true) { say("Not in a fight: try again after it"); return }
        val p = pos(ctl)
        if (p == 4 && cfg.sy.on) {
            val rows = smartRows(cfg)
            val k = rightRow(ctl).coerceIn(0, rows.size - 1)
            when (rows[k]) {
                "buff" -> PartyTaps.pbOp(cfg, k + 1, PartyTaps.PbOp.REMOVE)?.let { tap(it) } ?: say("Not on the tap pad yet")
                "add" -> chooser = Chooser(null, chooserRows(null, cfg)).takeIf { it.rows.isNotEmpty() }
                else -> chooser = Chooser("yc", chooserRows("yc", cfg))
            }
            return
        }
        val slot = slotOf(p) ?: return
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
        if (cells == null || !tap(cells)) say("$name: not on the tap pad now")
        else chooser = null
    }

    companion object {
        const val ROW_TOP = 188f
        const val ROW_H = 111f
        const val LIST_TOP = 410f
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
