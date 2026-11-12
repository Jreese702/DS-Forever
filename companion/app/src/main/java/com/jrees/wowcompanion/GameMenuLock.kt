package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/**
 * THE GAME MENU HAS THE PAD (the user's decision 1, 2026-11-11: "If we are in the game menu though ... it should be locked until the game
 * menu closes. That's the only valid example"). The game menu open at a pull keeps the game's own controller for that fight (PadMap m134
 * leaves the router aside); while it still shows (Interact's doc.gmLock) the bottom screen is dimmed and takes no touch, and it is
 * released the moment the menu closes. Label only (terse page text).
 */
class GameMenuLock(context: Context) : View(context) {
    private val scrim = Paint().apply { color = Color.argb(200, 0, 0, 0) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 209, 0); textAlign = Paint.Align.CENTER }

    /** set from every current interact document */
    var locked = false
        set(v) {
            if (field == v) return
            field = v
            visibility = if (v) VISIBLE else GONE
            if (v) bringToFront()
            invalidate()
        }

    init {
        setWillNotDraw(false)
        visibility = GONE
    }

    /** every touch is swallowed while locked: the game menu on the top screen is the game's */
    override fun onTouchEvent(e: MotionEvent): Boolean = locked

    override fun onDraw(c: Canvas) {
        if (!locked) return
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
        text.typeface = UiKit.title
        text.textSize = height * 0.06f
        c.drawText(LABEL, width / 2f, height / 2f, text)
    }

    companion object {
        const val LABEL = "Game Menu"
    }
}
