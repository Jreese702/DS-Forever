package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * THE COMPANION'S OWN KEYBOARD, drawn on the bottom screen (the user's pick 2026-09-30: "A keyboard drawn on
 * the bottom screen: D-pad over the keys, A types, B closes. Focus never leaves the game, so nothing can
 * ever be typed into the game by accident"). For the Chat room's message bar and Home's chat module, and
 * later anything else that needs a line typed (Send Mail).
 *
 * It types into a TEXT it is given ([text] / [onText]) and never into the game: what is written goes to the
 * game only when the owner sends it. The rules are [ChatKeys] (tested off the device); this draws them
 * with the kit's pieces (the game's tooltip border on each key, its glyphs, the gold box cursor) and takes
 * a finger on a key. The look is UI Art's to refine.
 */
class ChatKeyboard(c: Context, swap: Boolean = true) : View(c) {

    /** [swap] false: no "(X) Quick chat" key (Space takes its width), for a box that only wants a line (Bags' search) */
    val keys = ChatKeys(if (swap) ChatKeys.Layout.DEFAULT else ChatKeys.Layout.NO_SWAP)

    /** the Send key's word (Start / the Enter key): Bags' search says "Search" */
    var sendWord = "Send"
        set(v) { if (field != v) { field = v; invalidate() } }

    /** the text being written (read on every key) */
    var text: () -> String = { "" }
    /** the new text after a key */
    var onText: (String) -> Unit = {}
    var onSend: () -> Unit = {}
    var onClose: () -> Unit = {}
    /** the Quick chat key: the room turns to quick chat */
    var onSwap: () -> Unit = {}
    /** the height the room gives the board at rest (the input screen fills the room: UI Art's SHEET_chat_input); 0 = its own */
    var fillHeight = 0
        set(v) { if (field != v) { field = v; invalidate() } }
    /** Send is drawn faint while the game cannot take a message; a press still asks the owner */
    var sendEnabled = true
        set(v) { if (field != v) { field = v; invalidate() } }

    private val places = ArrayList<Triple<Int, Int, RectF>>()   // row, col, rect: the keys as last drawn
    private val fill = Paint().apply { style = Paint.Style.FILL }

    /** the keyboard opens: the cursor on the first letter, shift off */
    fun open() { keys.open(); invalidate() }

    /**
     * One controller button (the room's guest names: PADDUP .. PAD4, the shoulders, the triggers, PADFORWARD). Returns false for a
     * button that is not the keyboard's (X, LB, RB: [ChatKeys.Act.Pass]), which the room then handles.
     */
    fun press(button: String): Boolean {
        // A types the cursor's key: its letter pops up over it, as a finger's does (shown a moment, then fading)
        if (button == "PAD1") popFor(keys.row, keys.col, held = false)
        return apply(keys.press(button, text()))
    }

    /**
     * THE KEY POP-UP (the user: "The keyboard needs some button feedback with actually showing the button's letter that you are pressing
     * above your press so you know which letter you are hitting"): a phone keyboard's bubble - the key's character, enlarged on the
     * board's own keycap with a gold rim, ABOVE the key - while it is pressed (a finger held on it; A: [POP_SHOW_MS]), the key itself
     * pressed in; it fades over [POP_FADE_MS] after. Letters, digits and signs only (the word keys say what they are). Kept inside the
     * board at its edges, so a top-row or end key's bubble stays on screen.
     */
    private var popRow = -1
    private var popCol = -1
    private var popLabel = ""
    private var popHeld = false
    private var popAt = 0L
    private var popUpAt = 0L

    private fun popFor(r: Int, c: Int, held: Boolean) {
        val k = keys.layoutRows().getOrNull(r)?.cells?.getOrNull(c)?.key
        if (k !is ChatKeys.Key.Ch) { popRow = -1; return }
        popRow = r; popCol = c; popLabel = keys.charOf(k).toString()
        val now = android.os.SystemClock.uptimeMillis()
        popAt = now; popHeld = held; popUpAt = if (held) 0L else now + POP_SHOW_MS
        invalidate()
    }

    /** the pop-up's alpha now, 0..1 (0 = none) */
    private fun popAlpha(now: Long): Float {
        if (popRow < 0) return 0f
        if (popHeld) return 1f
        val dt = now - popUpAt
        return if (dt < 0) 1f else (1f - dt.toFloat() / POP_FADE_MS).coerceIn(0f, 1f)
    }

    private fun apply(a: ChatKeys.Act): Boolean {
        when (a) {
            is ChatKeys.Act.Text -> onText(a.text)
            ChatKeys.Act.Send -> onSend()
            ChatKeys.Act.Close -> onClose()
            ChatKeys.Act.Pass -> return false
            ChatKeys.Act.Swap -> onSwap()
            ChatKeys.Act.Glow, ChatKeys.Act.Refused -> {}
        }
        invalidate()
        return true
    }

    // UI Art's geometry (KeyboardSample.kt on art-kit 8e9a6676): a solid kit menu panel 6 px in from the sides and
    // 4 px over the bottom; inside it 16 px, rows of 92 px keys 8 px apart, ten units across; the hints under the keys
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(wSpec), resolveSize(fullHeight(), hSpec))
    }

    private val board = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        c.drawColor(Theme.ink)
        // drawn at the FULL height even while the room gives it less: sliding in, its top edge carries the keys up
        // from below (the room clips the rest), so the board slides and is never squeezed
        val kb = RectF(6f, 0f, width - 6f, (if (fillHeight > 0) fillHeight else fullHeight()) - 4f)
        // look 1, the user's pick: the keys on a black board with a thin bronze-brown edge (UI Art's sample)
        board.style = Paint.Style.FILL; board.color = Color.rgb(6, 6, 6)
        c.drawRoundRect(kb, 14f, 14f, board)
        board.style = Paint.Style.STROKE; board.strokeWidth = 2f; board.color = Color.rgb(60, 52, 40)
        c.drawRoundRect(kb, 14f, 14f, board)
        places.clear()
        val rows = keys.layoutRows()
        val unit = (kb.width() - INSET * 2 - 9 * GAP) / 10f
        // the keys as tall as the board allows (the input screen fills the room: ~120 px keys), never shorter than KEY_H
        val keyH = maxOf(KEY_H, (kb.height() - INSET * 2 - (rows.size - 1) * GAP) / rows.size)
        var y = kb.top + INSET
        for ((ri, row) in rows.withIndex()) {
            var x = kb.left + INSET + row.lead * (unit + GAP)          // the A-L row half a key in
            for ((ci, cell) in row.cells.withIndex()) {
                val w = unit * cell.w + GAP * (cell.w - 1f)
                val r = RectF(x, y, x + w, y + keyH)
                drawKey(c, r, cell.key, down = ri == popRow && ci == popCol && popAlpha(android.os.SystemClock.uptimeMillis()) > 0.5f)
                places += Triple(ri, ci, r)
                if (ri == keys.row && ci == keys.col) UiKit.focus(c, r)       // the gold box: the cursor
                x += w + GAP
            }
            y += keyH + GAP
        }
        drawPop(c, kb)
        // what the controller does here, the game's way: glyph and word, under the keys
        // NO glyph legend under the keys (the user, LATER 27: "the button glyph legend at the bottom of the keyboard is redundant
        // and should be removed"): the keys that have a button carry its glyph themselves
    }

    /** the pressed key's bubble over it (see [popFor]), inside the board [kb] */
    private fun drawPop(c: Canvas, kb: RectF) {
        val now = android.os.SystemClock.uptimeMillis()
        val a = popAlpha(now)
        if (a <= 0f) { popRow = -1; return }
        val key = places.firstOrNull { it.first == popRow && it.second == popCol }?.third ?: return
        val w = maxOf(key.width() * 1.45f, key.height() * 1.1f); val h = key.height() * 1.35f
        var left = key.centerX() - w / 2f
        left = left.coerceIn(kb.left + 4f, kb.right - 4f - w)
        // over the key, its foot overlapping the key's top; never above the board (a top-row key's bubble stays on screen)
        val top = (key.top - h + key.height() * 0.30f).coerceAtLeast(kb.top + 2f)
        val bub = RectF(left, top, left + w, top + h)
        val layer = c.saveLayerAlpha(bub.left - 8f, bub.top - 8f, bub.right + 8f, bub.bottom + 8f, (255 * a).toInt())
        shadow.color = Color.argb(150, 0, 0, 0)
        c.drawRoundRect(bub.left + 3f, bub.top + 6f, bub.right + 3f, bub.bottom + 6f, h * 0.16f, h * 0.16f, shadow)
        UiKit.keyCap(c, bub, "", LOOK)
        rim.color = Theme.gold
        c.drawRoundRect(bub, h * 0.14f, h * 0.14f, rim)
        val p = UiKit.bodyPaint(h * 0.52f, Color.WHITE).apply { textAlign = Paint.Align.CENTER }
        c.drawText(popLabel, bub.centerX(), bub.centerY() - (p.ascent() + p.descent()) / 2f, p)
        c.restoreToCount(layer)
        if (!popHeld) postInvalidateOnAnimation()
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }

    /** one key: the kit's keycap, LOOK 1 (dark), as the user picked on SHEET_keyboard_v2.png; [down] = pressed now (its pop-up shows) */
    private fun drawKey(c: Canvas, r: RectF, k: ChatKeys.Key, down: Boolean = false) {
        when (k) {
            is ChatKeys.Key.Ch -> UiKit.keyCap(c, r, keys.charOf(k).toString(), LOOK, down = down)
            ChatKeys.Key.Shift -> {
                UiKit.keyCap(c, r, "Shift", LOOK, mod = true, on = keys.shift != ChatKeys.Shift.OFF, glyph = "rev_rtrigger")
                if (keys.shift == ChatKeys.Shift.LOCK) capsLine(c, r, "Shift")      // locked: a 3 px gold line under the word
            }
            ChatKeys.Key.Page -> UiKit.keyCap(c, r, if (keys.page == 0) "?123" else "abc", LOOK, mod = true, on = keys.page != 0)
            ChatKeys.Key.Space -> UiKit.keyCap(c, r, "Space", LOOK, glyph = "rev_y")
            ChatKeys.Key.Back -> UiKit.keyCap(c, r, "", LOOK, mod = true, glyph = "rev_ltrigger", icon = "backspace")
            ChatKeys.Key.Swap -> UiKit.keyCap(c, r, "Quick chat", LOOK, mod = true, glyph = "rev_x")
            ChatKeys.Key.Send -> {
                // the ONE send while the keyboard is up; faint while the game cannot take it or nothing is written
                val can = sendEnabled && text().isNotBlank()
                val layer = if (can) -1 else c.saveLayerAlpha(r.left - 2f, r.top - 2f, r.right + 2f, r.bottom + 2f, 110)
                UiKit.keyCap(c, r, sendWord, LOOK, mod = true, glyph = "gen_forward", icon = "enter")
                if (layer >= 0) c.restoreToCount(layer)
            }
        }
    }

    /** under a glyph-and-word key's word, where UiKit.keyCap sets it: the locked Shift's gold line (the SPEC) */
    private fun capsLine(c: Canvas, r: RectF, label: String) {
        val gw = r.height() * 0.40f
        val p = UiKit.bodyPaint(r.height() * 0.30f, Theme.gold)
        while (p.measureText(label) > r.width() - 24f - gw && p.textSize > 14f) p.textSize -= 1f
        val tw = p.measureText(label) + gw + 8f
        val x = r.centerX() - tw / 2f + gw + 8f
        val base = r.centerY() - (p.ascent() + p.descent()) / 2f
        underline.color = Theme.gold
        c.drawRect(x, base + 5f, x + p.measureText(label), base + 8f, underline)
    }

    private val underline = Paint().apply { style = Paint.Style.FILL }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val at = places.firstOrNull { it.third.contains(e.x, e.y) }
        when (e.actionMasked) {
            // the finger's key pops up while held, follows the finger across the keys, and types on the lift (as before)
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (at == null) { if (popHeld) { popHeld = false; popUpAt = android.os.SystemClock.uptimeMillis(); invalidate() } }
                else if (!popHeld || at.first != popRow || at.second != popCol) popFor(at.first, at.second, held = true)
            }
            MotionEvent.ACTION_UP -> {
                if (popHeld) { popHeld = false; popUpAt = android.os.SystemClock.uptimeMillis(); invalidate() }
                at?.let { (r, col, _) -> apply(keys.touch(r, col, text())) }
            }
            MotionEvent.ACTION_CANCEL -> { popRow = -1; popHeld = false; invalidate() }
        }
        return true
    }

    companion object {
        /** the keyboard's whole height: the rows, the hints under them, the board's margins */
        fun fullHeight(): Int {
            val rows = ChatKeys.Layout.DEFAULT.pages[0].size
            // the rows and the board's margins (the legend under them is gone: LATER 27)
            return (INSET + rows * KEY_H + (rows - 1) * GAP + INSET + 4f).toInt()
        }

        /** the user's pick on SHEET_keyboard_v2.png: 1 = dark keycaps */
        const val LOOK = 1
        const val KEY_H = 92f
        const val GAP = 8f
        const val INSET = 16f
        /** the key pop-up: how long A's shows before it fades, and the fade (a finger's shows while held) */
        const val POP_SHOW_MS = 160L
        const val POP_FADE_MS = 180f
    }
}
