package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View

/**
 * THE THEME MAKER'S HOST (feat/beautify integration, 2026-11-18): the View that shows themes' [ThemeMakerPage] in the Settings tab body
 * (over the page, under the tab row) when General's Theme tile is pressed. It stands in for core's SubPage adapter until core lands: it
 * draws the page on the room's ground, takes a finger (a tap is A on a stop, the back chip closes), and [key] takes the pad's presses
 * while it shows (D-pad moves, A picks, B steps back - the gallery to the maker, the maker to its tile; L / R close it and step the tab).
 * The cursor is the room's golden box ([UiKit.focus]) on the page's [ThemeMakerPage.cursorRect], drawn only while the pad holds the room.
 */
class ThemeMakerHost(context: Context, private val held: () -> Boolean) : View(context) {
    val page = ThemeMakerPage()
    /** the page closed (B on the maker, the back chip): the room puts the cursor back on the Theme tile */
    var onClosed: () -> Unit = {}
    private var downX = 0f
    private var downY = 0f

    val isOpen: Boolean get() = visibility == VISIBLE

    init {
        visibility = GONE
        page.onChange = { postInvalidate() }
        page.later = { ms -> postDelayed({ invalidate() }, ms) }
        page.onClose = { close() }
        Theme.onChange { postInvalidate() }
    }

    fun open() {
        page.open()
        visibility = VISIBLE
        invalidate()
    }

    /** closes the page (the draft is kept for the session); false = it was not open */
    fun close(): Boolean {
        if (!isOpen) return false
        visibility = GONE
        onClosed()
        return true
    }

    /** one pad press while the page shows: true = taken */
    fun key(k: String): Boolean {
        if (!isOpen) return false
        when (k) {
            "PADDUP" -> page.move(0, -1)
            "PADDDOWN" -> page.move(0, 1)
            "PADDLEFT" -> page.move(-1, 0)
            "PADDRIGHT" -> page.move(1, 0)
            "PAD1" -> page.press()
            "PAD2" -> if (!page.back()) close()
            // L / R: the page closes and the room steps the tab as it always does
            "PADLSHOULDER", "PADRSHOULDER" -> { visibility = GONE; return false }
            else -> return true
        }
        invalidate()
        return true
    }

    override fun onDraw(c: Canvas) {
        RoomGround.settings(c, android.graphics.RectF(0f, 0f, width.toFloat(), height.toFloat()))
        page.draw(c, width.toFloat(), height.toFloat())
        if (held()) page.cursorRect()?.let { UiKit.focus(c, it) }
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
            MotionEvent.ACTION_UP -> if (Math.abs(e.x - downX) <= SLOP && Math.abs(e.y - downY) <= SLOP) {
                if (page.tap(e.x, e.y)) invalidate()
            }
        }
        return true
    }

    private companion object { const val SLOP = 24f }
}
