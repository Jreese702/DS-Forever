package com.jrees.wowcompanion

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ForegroundColorSpan

/**
 * TAB 4 OF THE CHARACTER ROOM: the PvP rank, as the game's `PVPRankFrame` shows it (the game has no PvP window of its
 * own; the user's answer 2026-09-28: "PvP tab in Character room"). The Character room owns the room, the frame, the
 * six side tabs and the controller; it calls [draw] with its two panes while tab 4 shows and sets [onChange] to its
 * `invalidate()` (the hand-off agreed in AGENT_LOG, character's "tab 4 HAND-OFF AGREED"). Read-only: nothing here is
 * touched or pressed, and nothing is sent to the game.
 *
 * Built to UI Art's SPEC (`wow-companion-design/character/SPEC.md`, sections 5 and 6; the picture `KIT_pvp.png`).
 * Every position is measured from the rectangles the room passes; every word is the game's own ([PvpModel]), drawn in the
 * game's own colours: its strings carry colour codes (the numbers in white inside a gold sentence), read by [PvpModel.spans].
 */
object PvpRankTab {

    /** Set by the Character room to its `invalidate()`: called when the PvP data changed. */
    var onChange: (() -> Unit)? = null

    /** Before the first document (the app's own wording, as the other rooms say it). */
    private const val WAITING = "Waiting for the game"

    init { PvpModel.listen { onChange?.invoke() } }

    private val pBmp = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Draw the tab into the room's list pane ([list], left) and detail pane ([detail], right). */
    fun draw(c: Canvas, list: RectF, detail: RectF) {
        val r = PvpModel.rank
        when {
            // WAITING and UNAVAILABLE (SPEC 6): the left pane empty, the right pane one sentence alone
            r == null -> sentence(c, detail, WAITING)
            !r.ok -> sentence(c, detail, r.unavailable)
            else -> {
                c.save(); c.clipRect(list); drawList(c, list, r); c.restore()
                c.save(); c.clipRect(detail); drawDetail(c, detail, r); c.restore()
            }
        }
    }

    // ------------------------------------------------------------------ the left pane
    private fun drawList(c: Canvas, l: RectF, r: PvpModel.Rank) {
        if (r.countdown.isNotEmpty())
            line(c, r.countdown, l.right - 24f, l.top + 40f, UiKit.bodyPaint(24f, Theme.dim), Paint.Align.RIGHT)
        if (r.season.isNotEmpty())
            line(c, r.season, l.centerX(), l.top + 96f, UiKit.bodyPaint(27f, Theme.gold), Paint.Align.CENTER)
        if (r.rankLine.isNotEmpty()) {
            // 38 px, set smaller down to 28 for the longest ("Rank 10: Lieutenant Commander")
            val p = UiKit.bodyPaint(38f, Color.WHITE)
            shrink(p, r.rankLine, l.width() - 48f, 28f)
            line(c, r.rankLine, l.centerX(), l.top + 150f, p, Paint.Align.CENTER)
        }
        UiKit.tex("rank_line")?.let {
            c.drawBitmap(it, null, RectF(l.centerX() - 300f, l.top + 160f, l.centerX() + 300f, l.top + 266f), pBmp)
        }
        // rank 0 draws the faction's badge and no number (rankBadge's own rule)
        UiKit.rankBadge(c, l.centerX(), l.top + 500f, 460f, r.level, r.faction, r.fill)
        // The game's line is words AND numbers ("Rank Points: 0 / 750", the numbers in white; measured on the Thor), not
        // the bare "250 / 1000" SPEC 6 was drawn for, so it takes the body type the kit gives words (told to UI Art).
        if (r.progress.isNotEmpty()) {
            val p = UiKit.bodyPaint(30f, Theme.gold)
            shrink(p, r.progress, l.width() - 48f, 22f)
            line(c, r.progress, l.centerX(), l.top + 790f, p, Paint.Align.CENTER)
        }
    }

    // ------------------------------------------------------------------ the right pane
    private fun drawDetail(c: Canvas, d: RectF, r: PvpModel.Rank) {
        var y = title(c, d, r.title.ifEmpty { r.rankLine }, r.number)
        if (r.desc.isNotEmpty()) y = prose(c, d, y, r.desc, Theme.gold) + 14f
        if (r.seasonProgress.isNotEmpty()) y = prose(c, d, y, r.seasonProgress, Color.WHITE) + 14f
        if (r.weeklyCap.isNotEmpty()) y = prose(c, d, y, r.weeklyCap, Theme.gold) + 14f

        // the vendor line stands at the pane's foot; the rewards stop above it
        val vendor = if (r.vendor.isEmpty()) null else layout(r.vendor, UiKit.bodyPaint(24f, Theme.gold), d.width() - 12f)
        val foot = if (vendor == null) d.bottom else d.bottom - vendor.height - 8f

        r.next?.let { n ->
            if (n.header.isNotEmpty() && y + 58f <= foot) {
                UiKit.sectionTitle(c, RectF(d.left, y, d.right, y + 58f), PvpModel.plain(n.header))
                y += 70f
            }
            for (rw in n.rewards.take(4)) {
                if (y + 64f > foot) break
                UiKit.slot(c, Icons.get(rw.icon.takeIf { it > 0 }), d.left + 6f, y, 64f)
                val sl = layout(rw.text, UiKit.bodyPaint(24f, Theme.gold), d.right - (d.left + 86f), maxLines = 2)
                c.save(); c.translate(d.left + 86f, y + 32f - sl.height / 2f); sl.draw(c); c.restore()
                y += 78f
            }
        }
        if (vendor != null) { c.save(); c.translate(d.left + 6f, foot); vendor.draw(c); c.restore() }
    }

    // ------------------------------------------------------------------ the detail pane's parts (SPEC 5)
    /** The title (34 px gold, set smaller down to 26 to stay on one line), the subtitle, the rule. Returns where content starts. */
    private fun title(c: Canvas, d: RectF, name: String, sub: String): Float {
        if (name.isNotEmpty()) {
            val pt = UiKit.bodyPaint(34f, Theme.gold)
            shrink(pt, name, d.width() - 12f, 26f)
            line(c, name, d.centerX(), d.top + 44f - (pt.ascent() + pt.descent()) / 2f, pt, Paint.Align.CENTER)
        }
        if (sub.isNotEmpty()) {
            val ps = UiKit.bodyPaint(25f, Color.WHITE)
            line(c, sub, d.centerX(), d.top + 86f - (ps.ascent() + ps.descent()) / 2f, ps, Paint.Align.CENTER)
        }
        UiKit.paneRule(c, d.left, d.top + 108f, d.right)
        return d.top + 132f
    }

    /** A paragraph, 24 px, line spacing + 6, from `d.left + 6`. Returns its bottom. */
    private fun prose(c: Canvas, d: RectF, y: Float, text: String, colour: Int): Float {
        val sl = layout(text, UiKit.bodyPaint(24f, colour), d.width() - 12f)
        c.save(); c.translate(d.left + 6f, y); sl.draw(c); c.restore()
        return y + sl.height
    }

    /** WAITING / UNAVAILABLE: one sentence, 25 px dim, centred at `detail.top + 300`, wrapped to the pane. */
    private fun sentence(c: Canvas, d: RectF, text: String) {
        if (text.isEmpty()) return
        val sl = layout(text, UiKit.bodyPaint(25f, Theme.dim), d.width() - 24f, align = Layout.Alignment.ALIGN_CENTER)
        c.save(); c.translate(d.left + 12f, d.top + 300f - sl.height / 2f); sl.draw(c); c.restore()
    }

    /** The game's text wrapped to [width], each run in its own colour ([p]'s colour for the rest). */
    private fun layout(text: String, p: Paint, width: Float, maxLines: Int = Int.MAX_VALUE,
                       align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL): StaticLayout =
        spanned(text).let { sb -> StaticLayout.Builder.obtain(sb, 0, sb.length, TextPaint(p), width.toInt().coerceAtLeast(1)) }
            .setAlignment(align).setLineSpacing(6f, 1f)
            .setMaxLines(maxLines).setEllipsize(if (maxLines == Int.MAX_VALUE) null else TextUtils.TruncateAt.END)
            .build()

    private fun spanned(text: String): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        for (run in PvpModel.spans(text)) {
            val a = sb.length
            sb.append(run.text)
            run.colour?.let { sb.setSpan(ForegroundColorSpan(it), a, sb.length, 0) }
        }
        return sb
    }

    /** One line of the game's text, baseline [y], aligned to [x] by [align]; each run in its own colour. */
    private fun line(c: Canvas, text: String, x: Float, y: Float, p: Paint, align: Paint.Align) {
        val runs = PvpModel.spans(text)
        val base = p.color
        p.textAlign = Paint.Align.LEFT
        val w = runs.fold(0f) { acc, r -> acc + p.measureText(r.text) }
        var cx = when (align) { Paint.Align.CENTER -> x - w / 2f; Paint.Align.RIGHT -> x - w; else -> x }
        for (r in runs) {
            p.color = r.colour ?: base
            c.drawText(r.text, cx, y, p)
            cx += p.measureText(r.text)
        }
        p.color = base
    }

    /** Set [p] smaller, one px at a time down to [min], until [text]'s words fit [width]. */
    private fun shrink(p: Paint, text: String, width: Float, min: Float) {
        val words = PvpModel.plain(text)
        while (p.textSize > min && p.measureText(words) > width) p.textSize -= 1f
    }
}
