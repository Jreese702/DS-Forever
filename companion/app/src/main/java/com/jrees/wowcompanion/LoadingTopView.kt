package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * THE TOP SCREEN WHILE DS FOREVER LOADS (the user, 2026-10-28): the loading reveal's top part ([LoadingScene]) behind the start-up
 * checks, in ONE framed box CENTRED on the screen ("the Text centered on the top screen"), in the game's own look instead of the old
 * card ("can we make that Wow install screen look a little more like..... formal instead of like AI?"): the gold border, the Morpheus
 * title, a divider, one row per check with the kit's tick, a dim diamond while it waits, red when it is wrong. Terse ("verbiage ... too
 * much"): an OK row is its name only; a red row says what is wrong. The status line in gold, ALWAYS (the step, "Ready", or "Loading"
 * with moving dots: "so it's clear it isn't stuck"), then at the box's foot the problem
 * report as the kit's own button with the controller's - glyph ("make the report button consistent with the other junk and add it
 * there so its not sticking out somewhere else"); Select opens it too ([SetupPanel]).
 *
 * The checks are the launcher's ([SetupBridge]); [error] = the launcher's own error line (a failed update ...), null = none. A step under
 * way has a thin gold bar under its row ([SetupFlow.bar]: "I still want the progress bars by the things on the top"); the report's
 * press shows by the pad (Select) as by a finger ([reportPress]).
 */
class LoadingTopView(context: Context) : View(context) {
    var error: String? = null
        set(v) { if (field != v) { field = v; invalidate() } }

    private val area = RectF()
    private val reportRect = RectF()
    private var reportDown = false
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pBmp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val refresh: () -> Unit = { post { invalidate() } }

    init { contentDescription = "DS Forever is loading: the start-up checks" }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); SetupBridge.onChange(refresh) }
    override fun onDetachedFromWindow() { SetupBridge.offChange(refresh); super.onDetachedFromWindow() }

    /** the box's own rows and status line instead of the start-up checks (Streaming's curtain over the stream: StreamCurtain); no
     *  report button then. null = the start-up checks */
    var curtain: (() -> Pair<List<SetupFlow.Check>, String>?)? = null

    override fun onDraw(c: Canvas) {
        area.set(0f, 0f, width.toFloat(), height.toFloat())
        LoadingScene.draw(c, area, top = true)
        postInvalidateDelayed(LoadingScene.FRAME_MS)
        val k = height / 1080f
        curtain?.invoke()?.let { (rows, line) -> drawPanel(c, k, rows, line, null, false); return }
        val inputs = SetupBridge.inputs
        val full = inputs?.let { SetupFlow.checks(it) } ?: emptyList()
        val rows = SetupFlow.terse(full)
        val bars = full.map { ch -> inputs?.let { SetupFlow.bar(ch, it) } }
        val screen = SetupBridge.screen()
        // the status line is ALWAYS there, just above the report (the user, 2026-10-28): the step, "Ready", or "Loading" with dots
        val line = SetupFlow.status(screen?.line, SetupBridge.checksPass(), LoadingScene.now())
        // the report is on the chooser's foot now (the user 2026-11-01), Select still opens it while the checks run
        drawPanel(c, k, rows, line, error, false, bars)
    }

    private fun drawPanel(c: Canvas, k: Float, rows: List<SetupFlow.Check>, line: String, err: String?, report: Boolean,
                          bars: List<Float?> = emptyList()) {
        val w = PANEL_W * k
        val rowP = UiKit.bodyPaint(32f * k, Theme.text)
        val textW = w - (ROW_X + 64f + 60f) * k
        // each row's lines (a red row wraps; an OK / waiting row is one line)
        val laid = rows.map { r -> r to if (r.mark == SetupFlow.Mark.BAD) SetupPanel.wrap(r.label, rowP, textW) else listOf(r.label) }
        val errLines = err?.let { SetupPanel.wrap("Error: $it", rowP, textW) } ?: emptyList()
        var h = TITLE_H * k + laid.sumOf { it.second.size } * ROW_H * k + 24f * k + bars.count { it != null } * BAR_ROW * k
        h += LINE_H * k
        h += errLines.size * ROW_H * k
        if (report) h += FOOT_H * k
        h += 30f * k
        h = h.coerceAtMost(height - 24f * k)
        val box = RectF((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
        pFill.style = Paint.Style.FILL; pFill.color = Color.argb(232, 14, 11, 8)
        c.drawRoundRect(RectF(box.left + 6f * k, box.top + 6f * k, box.right - 6f * k, box.bottom - 6f * k), 10f * k, 10f * k, pFill)
        UiKit.border(c, box, "border_gold", 34f * k)
        val title = UiKit.titlePaint(52f * k).apply { textAlign = Paint.Align.CENTER }
        c.drawText(TITLE, box.centerX(), box.top + 92f * k, title)
        UiKit.divider(c, box.left + 70f * k, box.top + 124f * k, box.right - 70f * k, box.top + 124f * k)
        var y = box.top + 186f * k
        val x = box.left + ROW_X * k
        for ((n, rl) in laid.withIndex()) {
            val (r, lines) = rl
            mark(c, r.mark, x, y, k)
            val p = UiKit.bodyPaint(32f * k, when (r.mark) {
                SetupFlow.Mark.OK -> Theme.text
                SetupFlow.Mark.BAD -> BAD
                else -> Theme.textDim
            })
            for (l in lines) { c.drawText(l, x + 64f * k, y, p); y += ROW_H * k }
            bars.getOrNull(n)?.let { f ->
                bar(c, RectF(x + 64f * k, y - ROW_H * k + 18f * k, box.right - 90f * k, y - ROW_H * k + 30f * k), f)
                y += BAR_ROW * k
            }
        }
        for (l in errLines) { c.drawText(l, x + 64f * k, y, UiKit.bodyPaint(32f * k, BAD)); y += ROW_H * k }
        val lp = UiKit.bodyPaint(28f * k, Theme.gold).apply { textAlign = Paint.Align.CENTER }
        c.drawText(line, box.centerX(), y + 10f * k, lp)
        reportRect.setEmpty()
        if (report) {
            UiKit.divider(c, box.left + 70f * k, box.bottom - 150f * k, box.right - 70f * k, box.bottom - 150f * k)
            reportRect.set(box.centerX() - 230f * k, box.bottom - 124f * k, box.centerX() + 230f * k, box.bottom - 52f * k)
            val now = android.os.SystemClock.uptimeMillis()
            UiKit.button(c, reportRect, REPORT, REPORT_GLYPH,
                if (reportDown || reportPress.down(REPORT, now)) UiKit.Button.DOWN else UiKit.Button.UP, reportPress.waiting(REPORT, now, false))
        }
    }

    private val pBar = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * A row's thin bar, gold (the user: the game's look, not Material): the kit's bar ground, the filled share in gold, the kit's
     * border; [f] = [SetupFlow.BAR_MOVING] = a gold stretch running along it (an amount not known yet).
     */
    private fun bar(c: Canvas, r: RectF, f: Float) {
        pBar.style = Paint.Style.FILL; pBar.color = Color.argb(220, 6, 5, 4)
        c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, pBar)
        val inner = RectF(r.left + 2f, r.top + 2f, r.right - 2f, r.bottom - 2f)
        pBar.shader = android.graphics.LinearGradient(0f, inner.top, 0f, inner.bottom, Color.rgb(255, 222, 120), Color.rgb(196, 140, 20),
            android.graphics.Shader.TileMode.CLAMP)
        if (f == SetupFlow.BAR_MOVING) {
            val ph = (android.os.SystemClock.uptimeMillis() % 1600L) / 1600f
            val w = inner.width() * 0.25f
            val l = inner.left - w + (inner.width() + w) * ph
            c.save(); c.clipRect(inner)
            c.drawRoundRect(RectF(l, inner.top, l + w, inner.bottom), inner.height() / 2f, inner.height() / 2f, pBar)
            c.restore()
        } else if (f > 0f) {
            c.drawRoundRect(RectF(inner.left, inner.top, inner.left + inner.width() * f.coerceIn(0f, 1f), inner.bottom),
                inner.height() / 2f, inner.height() / 2f, pBar)
        }
        pBar.shader = null
        pBar.style = Paint.Style.STROKE; pBar.strokeWidth = 2f; pBar.color = Theme.bronze
        c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, pBar)
    }

    /** a row's mark: the kit's tick (OK), a dim diamond (waiting / optional), a red disc (wrong) */
    private fun mark(c: Canvas, m: SetupFlow.Mark, x: Float, y: Float, k: Float) {
        when (m) {
            SetupFlow.Mark.OK -> {
                val t = UiKit.tex("check_tick")
                if (t != null) c.drawBitmap(t, null, RectF(x - 6f * k, y - 40f * k, x + 42f * k, y + 8f * k), pBmp)
                else {
                    pFill.style = Paint.Style.STROKE; pFill.strokeWidth = 5f * k; pFill.color = Theme.gold
                    c.drawLine(x + 2f * k, y - 16f * k, x + 14f * k, y - 4f * k, pFill); c.drawLine(x + 14f * k, y - 4f * k, x + 36f * k, y - 32f * k, pFill)
                    pFill.style = Paint.Style.FILL
                }
            }
            SetupFlow.Mark.BAD -> { pFill.style = Paint.Style.FILL; pFill.color = BAD; c.drawCircle(x + 18f * k, y - 16f * k, 10f * k, pFill) }
            else -> {
                pFill.style = Paint.Style.FILL; pFill.color = Theme.textDim
                c.save(); c.rotate(45f, x + 18f * k, y - 16f * k)
                c.drawRect(x + 11f * k, y - 23f * k, x + 25f * k, y - 9f * k, pFill)
                c.restore()
            }
        }
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val inside = !reportRect.isEmpty && reportRect.contains(e.x, e.y)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { reportDown = inside; invalidate(); return inside }
            MotionEvent.ACTION_UP -> {
                val was = reportDown; reportDown = false; invalidate()
                if (was && inside) SetupBridge.screen()?.report?.takeIf { it.enabled }?.let {
                    reportPress.press(REPORT, android.os.SystemClock.uptimeMillis()); SetupBridge.press(it.action)
                }
                return was
            }
            MotionEvent.ACTION_CANCEL -> { reportDown = false; invalidate() }
        }
        return reportDown
    }

    companion object {
        /** the report's last press, by a finger here or Select on the bottom (SetupPanel): its pushed-in look, then a short wait */
        val reportPress = StartPress()
        const val BAR_ROW = 22f
        const val TITLE = "World of Warcraft: Forever"
        const val REPORT = "Report a problem"
        const val REPORT_GLYPH = "rev_minus"
        val BAD = Color.rgb(255, 120, 100)
        /** the box at 1080 px high (the design's loading_panel.png: 900 wide; a little wider for a red row's words) */
        const val PANEL_W = 1000f
        const val TITLE_H = 150f
        const val ROW_H = 62f
        const val ROW_X = 120f
        const val LINE_H = 56f
        const val FOOT_H = 160f
    }
}
