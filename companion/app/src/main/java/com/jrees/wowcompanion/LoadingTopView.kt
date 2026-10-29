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
 * much"): an OK row is its name only; a red row says what is wrong. The step under way in gold, then at the box's foot the problem
 * report as the kit's own button with the controller's - glyph ("make the report button consistent with the other junk and add it
 * there so its not sticking out somewhere else"); Select opens it too ([SetupPanel]).
 *
 * The checks are the launcher's ([SetupBridge]); [error] = the launcher's own error line (a failed update ...), null = none.
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

    override fun onDraw(c: Canvas) {
        area.set(0f, 0f, width.toFloat(), height.toFloat())
        LoadingScene.draw(c, area, top = true)
        postInvalidateDelayed(LoadingScene.FRAME_MS)
        val k = height / 1080f
        val inputs = SetupBridge.inputs
        val rows = inputs?.let { SetupFlow.terse(SetupFlow.checks(it)) } ?: emptyList()
        val screen = SetupBridge.screen()
        val line = listOfNotNull(screen?.line?.takeIf { it.isNotEmpty() }).firstOrNull()
        drawPanel(c, k, rows, line, error, screen?.report != null)
    }

    private fun drawPanel(c: Canvas, k: Float, rows: List<SetupFlow.Check>, line: String?, err: String?, report: Boolean) {
        val w = PANEL_W * k
        val rowP = UiKit.bodyPaint(32f * k, Theme.text)
        val textW = w - (ROW_X + 64f + 60f) * k
        // each row's lines (a red row wraps; an OK / waiting row is one line)
        val laid = rows.map { r -> r to if (r.mark == SetupFlow.Mark.BAD) SetupPanel.wrap(r.label, rowP, textW) else listOf(r.label) }
        val errLines = err?.let { SetupPanel.wrap("Error: $it", rowP, textW) } ?: emptyList()
        var h = TITLE_H * k + laid.sumOf { it.second.size } * ROW_H * k + 24f * k
        if (line != null) h += LINE_H * k
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
        for ((r, lines) in laid) {
            mark(c, r.mark, x, y, k)
            val p = UiKit.bodyPaint(32f * k, when (r.mark) {
                SetupFlow.Mark.OK -> Theme.text
                SetupFlow.Mark.BAD -> BAD
                else -> Theme.textDim
            })
            for (l in lines) { c.drawText(l, x + 64f * k, y, p); y += ROW_H * k }
        }
        for (l in errLines) { c.drawText(l, x + 64f * k, y, UiKit.bodyPaint(32f * k, BAD)); y += ROW_H * k }
        line?.let {
            val p = UiKit.bodyPaint(28f * k, Theme.gold).apply { textAlign = Paint.Align.CENTER }
            c.drawText(it, box.centerX(), y + 10f * k, p)
        }
        reportRect.setEmpty()
        if (report) {
            UiKit.divider(c, box.left + 70f * k, box.bottom - 150f * k, box.right - 70f * k, box.bottom - 150f * k)
            reportRect.set(box.centerX() - 230f * k, box.bottom - 124f * k, box.centerX() + 230f * k, box.bottom - 52f * k)
            UiKit.button(c, reportRect, REPORT, REPORT_GLYPH, if (reportDown) UiKit.Button.DOWN else UiKit.Button.UP)
        }
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
                if (was && inside) SetupBridge.screen()?.report?.takeIf { it.enabled }?.let { SetupBridge.press(it.action) }
                return was
            }
            MotionEvent.ACTION_CANCEL -> { reportDown = false; invalidate() }
        }
        return reportDown
    }

    companion object {
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
