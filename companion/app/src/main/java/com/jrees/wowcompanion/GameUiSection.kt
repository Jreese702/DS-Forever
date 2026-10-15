package com.jrees.wowcompanion

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.abs

/**
 * SettingsView's drawn depth: GAME + MAP + CONTROLLER at 96 px rows end at 936 px, plus its
 * 24 px bottom pad. Raise it when SettingsView gains a section.
 */
const val SETTINGS_H = 960

/**
 * The Settings room as the hub hosts it: ONE LONG PAGE, [settingsView] (map session) with the
 * GAME UI section as its last section, in a scroll container.
 *
 * SettingsView scrolls by itself when it is shorter than its content, and does not measure
 * itself. Two scrollers inside each other would fight over the same drag, so it is given its
 * whole depth here ([SETTINGS_H], as its MINIMUM height, which is what the default onMeasure
 * answers with): it then has nothing to scroll, and this container scrolls the page. The day
 * SettingsView measures its own content, that answer wins and the constant stops mattering.
 *
 * NOT fillViewport: that pass re-measures the column at the room's exact height, the default
 * onMeasure then answers with ALL of it, and the section below is pushed off the screen.
 */
fun settingsRoom(context: Context, settingsView: View): View = ScrollView(context).apply {
    setBackgroundColor(Theme.ink)
    addView(LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        settingsView.minimumHeight = SETTINGS_H
        addView(settingsView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(GameUiSection(context), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // Home's one setting, the bars at rest (home session; user 15:27: "In Settings")
        addView(HomeSettingsSection(context), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    })
}

/**
 * The Settings room's GAME UI section: which of the game's own elements stay on the top screen.
 *
 * LAYOUT (user ruling 2026-09-27): rows carry an icon, a name and the current mode, and NO
 * buttons. Tap a row to select it, and ONE three-way bar appears for it: On / Off / Dynamic.
 * On and Off speak about the game's element (On = the game keeps drawing it).
 *
 * A self-contained view, so SettingsView.kt (map session) is not edited: the hub stacks this
 * under it. It draws with the same metrics and colours as SettingsView's rows.
 *
 * Touches act on RELEASE, and only when the finger has not moved: the section sits in a scroll
 * container, and a drag must not pick a mode.
 */
class GameUiSection(context: Context) : View(context) {

    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val pSection = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 32f; color = Theme.gold }   // a heading, in Morpheus
    private val pLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 31f; color = Theme.text }
    private val pSub = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; color = Theme.textDim }
    private val pMode = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 27f; textAlign = Paint.Align.RIGHT; typeface = Typeface.DEFAULT_BOLD
    }
    private val pSeg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 27f; textAlign = Paint.Align.LEFT; typeface = Typeface.DEFAULT_BOLD
    }

    private var rows: List<GameUi.Row> = allRows()

    /**
     * THE LOOT WINDOW'S ROW (r-loot-on-top; the user, batch4: "loot window should be moved to the UI settings"): after the game's
     * elements, in their words: On = the game keeps its loot window on the top screen, Off = hidden, the pop-up on this screen (the
     * default). Its value is the Interact add-on's (HomeLootSwitch); null = an older add-on: no row.
     */
    private fun lootRow(): GameUi.Row? = HomeLootSwitch.state?.let { s ->
        GameUi.Row(LOOT_ID, "Loot window", null, if (s.top) UiMode.ON else UiMode.OFF, true, !s.top,
            if (s.top) "The game's own loot window" else "The loot pop-up on this screen")
    }
    private fun allRows(): List<GameUi.Row> = GameUi.rows() + listOfNotNull(GameUi.castBarRow(), lootRow())
    private var selected: String? = null
    private val rowBoxes = ArrayList<Pair<RectF, String>>()
    private val segBoxes = ArrayList<Pair<RectF, UiMode>>()

    private var downX = 0f
    private var downY = 0f

    init {
        GameUi.onChange { post { refresh() } }
        HomeLootSwitch.onChange { post { refresh() } }
        StatusLine.onChange = { post { invalidate() } }
    }

    private fun refresh() {
        val before = rows.size
        rows = allRows()
        if (selected != null && rows.none { it.id == selected }) selected = null
        if (rows.size != before) requestLayout()
        invalidate()
    }

    private fun wantedHeight(): Int {
        var h = HEAD_H + rows.size * (ROW_H + GAP) + BOTTOM + STATUS_H
        if (selected != null) h += BAR_H + GAP
        return h.toInt()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthSpec),
                             resolveSize(wantedHeight(), heightSpec))
    }

    /** the controller's targets (Home's Settings cursor, LATER item 5): every row, then the open bar's three modes, in reading order */
    fun focusTargets(): List<RectF> {
        val out = ArrayList<RectF>()
        for ((box, id) in rowBoxes) {
            out.add(RectF(box))
            if (id == selected) segBoxes.forEach { out.add(RectF(it.first)) }
        }
        return out
    }

    /** B in the Settings room closes an open On / Off / Dynamic bar first; false = nothing was open */
    fun closeSelection(): Boolean {
        if (selected == null) return false
        selected = null; requestLayout(); invalidate()
        return true
    }

    /** a bar is open (the next B closes it rather than leaving the room) */
    val hasSelection get() = selected != null

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
            MotionEvent.ACTION_UP -> {
                if (abs(e.x - downX) > SLOP || abs(e.y - downY) > SLOP) return true
                for ((box, m) in segBoxes) if (box.contains(e.x, e.y)) {
                    selected?.let {
                        when (it) {
                            LOOT_ID -> HomeLootSwitch.turn(context, m == UiMode.ON)
                            GameUi.CASTBAR_ID -> GameUi.setCastBar(m == UiMode.ON)
                            else -> GameUi.setMode(it, m)
                        }
                    }
                    refresh()
                    return true
                }
                for ((box, id) in rowBoxes) if (box.contains(e.x, e.y)) {
                    selected = if (selected == id) null else id
                    requestLayout(); invalidate()
                    return true
                }
            }
        }
        return true
    }

    /** the game's type (UI art, fonts step). Set where the paints are USED: the kit can reach the
     *  device after this view is built, and a paint keeps the font it was given. */
    private fun fonts() {
        UiKit.asTitle(pSection); UiKit.asBody(pLabel); UiKit.asBody(pSub)
        UiKit.asBody(pMode); UiKit.asBody(pSeg)
    }

    override fun onDraw(c: Canvas) {
        fonts()
        c.drawColor(Theme.ink)
        rowBoxes.clear(); segBoxes.clear()
        c.drawText("Game UI on the top screen", 28f, HEAD_TEXT + 4f, pSection)
        var y = HEAD_H
        for (r in rows) {
            val box = RectF(16f, y, width - 16f, y + ROW_H)
            val sel = r.id == selected
            pFill.color = if (sel) Theme.selected else Theme.raised
            c.drawRoundRect(box, 8f, 8f, pFill)
            if (sel) {
                pStroke.color = Theme.bronze; pStroke.strokeWidth = 2f
                c.drawRoundRect(box, 8f, 8f, pStroke)
            }
            pStroke.color = if (r.known) Theme.gold else Theme.dim; pStroke.strokeWidth = 2.4f
            elementIcon(c, r.id, box.left + 58f, box.centerY(), 1.7f)
            pLabel.color = if (r.known) Theme.text else Theme.dim
            c.drawText(r.label, box.left + 116f, box.top + 41f, pLabel)
            pSub.color = if (r.known) Theme.textDim else Theme.dim
            c.drawText(r.note, box.left + 116f, box.top + 75f, pSub)
            pMode.color = if (r.known) Theme.gold else Theme.dim
            c.drawText(word(r.mode), box.right - 24f, box.centerY() + 10f, pMode)
            rowBoxes.add(box to r.id)
            y += ROW_H + GAP

            if (sel) { bar(c, r, y); y += BAR_H + GAP }
        }

        // ---- what the status line under the strip used to say (user, 2026-09-28: it lives here now)
        y += COMPANION_TOP                 // the page's 90 px seam above a heading (Map's measure 2026-10-06: it was 52)
        c.drawText("Companion", 28f, y + HEAD_TEXT + 12f, pSection)
        val box = RectF(16f, y + HEAD_H + 8f, width - 16f, y + HEAD_H + 8f + ROW_H)
        pFill.color = Theme.raised
        c.drawRoundRect(box, 8f, 8f, pFill)
        pLabel.color = Theme.text
        c.drawText("Status", box.left + 22f, box.top + 41f, pLabel)
        pSub.color = Theme.textDim
        val say = StatusLine.text.ifEmpty { "Nothing to report" }
        val cut = pSub.breakText(say, true, box.width() - 44f, null)
        c.drawText(if (cut < say.length) say.substring(0, cut) else say, box.left + 22f, box.top + 75f, pSub)
    }

    /** the ONE contextual control: the selected row's modes (three; the minimap's four with "While tracking", LATER 25) */
    private fun bar(c: Canvas, r: GameUi.Row, top: Float) {
        val left = 16f; val right = width - 16f
        val gap = 10f
        val modes = if (r.id == LOOT_ID || r.id == GameUi.CASTBAR_ID) listOf(UiMode.ON, UiMode.OFF) else UiElements.modesFor(r.id)
        val sw = (right - left - gap * (modes.size - 1)) / modes.size
        var x = left
        for (m in modes) {
            val b = RectF(x, top, x + sw, top + SEG_H)
            val on = r.mode == m
            // one of three: the game's red button, the chosen one lit; the word and its sign are drawn over it
            UiKit.choice(c, b, "", on)
            val col = if (on) Theme.gold else Theme.textDim
            pSeg.color = col
            val tw = pSeg.measureText(word(m))
            val total = 44f + 14f + tw
            val ix = b.centerX() - total / 2f + 22f
            pStroke.color = col; pStroke.strokeWidth = 2.4f
            modeIcon(c, m, ix, b.centerY())
            c.drawText(word(m), ix + 22f + 14f, b.centerY() + 10f, pSeg)
            segBoxes.add(b to m)
            x += sw + gap
        }
        pSub.color = Theme.textDim
        c.drawText(explain(r), left + 6f, top + SEG_H + 34f, pSub)
    }

    private fun word(m: UiMode) = when (m) {
        UiMode.ON -> "On"; UiMode.OFF -> "Off"; UiMode.DYNAMIC -> "Dynamic"; UiMode.TRACKING -> "Tracking"
    }

    private fun explain(r: GameUi.Row): String {
        if (r.id == LOOT_ID) return if (r.mode == UiMode.ON) "The game's own loot window opens on the top screen, as usual."
            else "Hidden: the loot pop-up opens on this screen."
        if (r.id == GameUi.CASTBAR_ID) return if (r.mode == UiMode.ON) "The game's own cast bar stays on the top screen."
            else "Hidden while the companion is on this screen; Home's bar shows every cast."
        val room = r.home?.label?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "its"
        return when (r.mode) {
            UiMode.ON -> "The game keeps it on the top screen."
            UiMode.OFF -> "Hidden while the companion is on this screen."
            UiMode.DYNAMIC -> if (UiElements.of(r.id)?.onHome == true)
                "Hidden only while the $room room or Home is showing." else "Hidden only while the $room room is showing."
            UiMode.TRACKING -> "Shown while Find Herbs, Minerals or Treasure is on."
        }
    }

    // ------------------------------------------------------------------ glyphs

    /** the home room's glyph, on Strip's 24-unit grid, so an element reads as its room */
    private fun elementIcon(c: Canvas, id: String, cx: Float, cy: Float, s: Float) {
        fun X(v: Float) = cx + (v - 12f) * s
        fun Y(v: Float) = cy + (v - 12f) * s
        val p = pStroke
        when (id) {
            "minimap" -> {
                c.drawCircle(cx, cy, 10f * s, p)
                c.drawPath(Path().apply {
                    moveTo(X(15.4f), Y(8.6f)); lineTo(X(13.3f), Y(13.4f))
                    lineTo(X(8.5f), Y(15.5f)); lineTo(X(10.6f), Y(10.7f)); close()
                }, p)
            }
            "hud" -> c.drawPath(Path().apply {
                moveTo(X(13f), Y(2f)); lineTo(X(5f), Y(13f)); lineTo(X(10f), Y(13f))
                lineTo(X(9f), Y(22f)); lineTo(X(17f), Y(11f)); lineTo(X(12f), Y(11f)); close()
            }, p)
            "tracker" -> {
                c.drawPath(Path().apply {
                    moveTo(X(10f), Y(3f)); lineTo(X(14f), Y(3f)); lineTo(X(13f), Y(15f))
                    lineTo(X(11f), Y(15f)); close()
                }, p)
                c.drawCircle(X(12f), Y(19.5f), 1.9f * s, p)
            }
            "chat" -> c.drawPath(Path().apply {
                moveTo(X(20f), Y(11.5f))
                cubicTo(X(20f), Y(16f), X(16.5f), Y(18.5f), X(13f), Y(18.5f))
                lineTo(X(8.5f), Y(18.5f)); lineTo(X(4.5f), Y(21f)); lineTo(X(4.5f), Y(17.4f))
                cubicTo(X(2f), Y(15f), X(3f), Y(4.5f), X(8f), Y(4.5f))
                lineTo(X(13f), Y(4.5f)); cubicTo(X(17.5f), Y(4.5f), X(20f), Y(7f), X(20f), Y(11.5f))
                close()
            }, p)
            // the game's cast bar: a long bar, two thirds filled
            GameUi.CASTBAR_ID -> {
                c.drawRoundRect(RectF(X(2f), Y(9f), X(22f), Y(15f)), 3f, 3f, p)
                pFill.color = p.color
                c.drawRoundRect(RectF(X(3.5f), Y(10.5f), X(15f), Y(13.5f)), 1.5f, 1.5f, pFill)
            }
            // the loot window: a small chest, its lid line and clasp
            LOOT_ID -> {
                c.drawRoundRect(RectF(X(3f), Y(7f), X(21f), Y(19f)), 2.5f, 2.5f, p)
                c.drawLine(X(3f), Y(11.5f), X(21f), Y(11.5f), p)
                c.drawRect(RectF(X(10.5f), Y(10f), X(13.5f), Y(14f)), p)
            }
            else -> c.drawRoundRect(RectF(X(4f), Y(6f), X(20f), Y(18f)), 3f, 3f, p)
        }
    }

    /** On = an eye, Off = the eye struck through, Dynamic = the two screens, the lower one lit, Tracking = the eye with a small leaf */
    private fun modeIcon(c: Canvas, m: UiMode, cx: Float, cy: Float) {
        val s = 1.6f
        fun X(v: Float) = cx + (v - 12f) * s
        fun Y(v: Float) = cy + (v - 12f) * s
        val p = pStroke
        when (m) {
            UiMode.ON, UiMode.OFF -> {
                c.drawPath(Path().apply {
                    moveTo(X(2f), Y(12f))
                    cubicTo(X(6f), Y(5f), X(18f), Y(5f), X(22f), Y(12f))
                    cubicTo(X(18f), Y(19f), X(6f), Y(19f), X(2f), Y(12f)); close()
                }, p)
                c.drawCircle(cx, cy, 3.2f * s, p)
                if (m == UiMode.OFF) c.drawLine(X(4f), Y(20f), X(20f), Y(4f), p)
            }
            UiMode.TRACKING -> {
                c.drawPath(Path().apply {
                    moveTo(X(2f), Y(12f))
                    cubicTo(X(6f), Y(5f), X(18f), Y(5f), X(22f), Y(12f))
                    cubicTo(X(18f), Y(19f), X(6f), Y(19f), X(2f), Y(12f)); close()
                }, p)
                // the herb: a small leaf where the pupil was (the game's Find Herbs reads as a leaf)
                c.drawPath(Path().apply {
                    moveTo(X(9f), Y(15f)); cubicTo(X(9f), Y(9f), X(13f), Y(8f), X(16f), Y(8f))
                    cubicTo(X(16f), Y(12f), X(14f), Y(15f), X(9f), Y(15f)); close()
                }, p)
            }
            UiMode.DYNAMIC -> {
                c.drawRoundRect(RectF(X(3f), Y(3f), X(21f), Y(11f)), 2f, 2f, p)
                val lower = RectF(X(5f), Y(14f), X(19f), Y(21f))
                c.drawRoundRect(lower, 2f, 2f, p)
                pFill.color = p.color
                c.drawRoundRect(RectF(lower.left + 5f, lower.top + 4f, lower.right - 5f, lower.bottom - 4f),
                                1f, 1f, pFill)
            }
        }
    }

    companion object {
        /** the loot window's row: no hub element, the Interact add-on's own switch */
        const val LOOT_ID = "loot"
        // SettingsView's own metrics: a section heading 16 px above its first row, 96 px rows
        const val HEAD_TEXT = 26f
        const val HEAD_H = 42f
        const val ROW_H = 96f
        const val GAP = 10f
        const val SEG_H = 110f       // 48 dp on this panel: the touch floor
        const val BAR_H = SEG_H + 50f
        const val BOTTOM = 16f
        const val COMPANION_TOP = 38f
        const val STATUS_H = COMPANION_TOP + HEAD_H + 8f + ROW_H + 8f     // the "Companion" heading and its one row
        const val SLOP = 24f
    }
}
