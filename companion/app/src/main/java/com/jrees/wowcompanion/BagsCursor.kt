package com.jrees.wowcompanion

/**
 * THE BAGS ROOM'S CURSOR, AHEAD OF THE GAME (the user: "when I look around the bags at different items it lags a lot ... if I push
 * two fast it gets a little lost and inconsistent"; tracking's yes to (a)-(d), 2026-10-22). The add-on still owns the cursor and acts
 * on every press; the app reads the same presses (PadInput, read only: the game still gets every press) and moves its OWN copy at
 * once by the add-on's own rules ([step] = Bags.lua moveCursor), so the gold frame and the item's detail never wait for the round trip.
 * The add-on's documents reconcile it by bags.ctl.seq: each D-pad press on the grid is one report (seq + 1), so the presses the game
 * has not answered yet are replayed from ITS cursor, and on any mismatch the game wins. Pure.
 */
object BagsCursor {
    /** the grid's width: Bags.lua COLS, BagsView.Grid.COLS */
    const val COLS = 10
    /** no answer from the game this long after a press: the guess is dropped and the game's cursor shows */
    const val STALE_MS = 900L

    data class Bag(val id: Int, val slots: Int)

    val DPAD = setOf("PADDUP", "PADDDOWN", "PADDLEFT", "PADDRIGHT")

    /**
     * One D-pad step from [at] over the OPEN bags in order (Bags.lua moveCursor, line for line). null = the cursor leaves the grid
     * (Up from the top row onto the room's strip, nothing in the hand); the app does not guess the strip.
     */
    fun step(open: List<Bag>, at: Pair<Int, Int>, key: String, pick: Boolean): Pair<Int, Int>? {
        if (open.isEmpty()) return null
        var i = open.indexOfFirst { it.id == at.first }
        var slot = at.second
        if (i < 0) { i = 0; slot = 1 }
        val e = open[i]
        var s = slot.coerceIn(1, maxOf(1, e.slots))
        val row = (s - 1) / COLS; val col = (s - 1) % COLS
        val lastRow = (e.slots - 1) / COLS
        when (key) {
            "PADDLEFT" -> if (s > 1) s -= 1 else if (i > 0) { i -= 1; s = open[i].slots }
            "PADDRIGHT" -> if (s < e.slots) s += 1 else if (i < open.size - 1) { i += 1; s = 1 }
            "PADDUP" -> when {
                row > 0 -> s -= COLS
                i > 0 -> { i -= 1; val p = open[i]; s = minOf(((p.slots - 1) / COLS) * COLS + col + 1, p.slots) }
                !pick -> return null
            }
            "PADDDOWN" -> when {
                row < lastRow -> s = minOf(s + COLS, e.slots)
                i < open.size - 1 -> { i += 1; s = minOf(col + 1, open[i].slots) }
            }
        }
        return open[i].id to s
    }

    /**
     * The app's copy of the cursor. [game] takes each bags.ctl the add-on sends; [press] each press read from the controller.
     * [shown] = the cursor to draw (null = the game's own).
     */
    class Predictor {
        private val moves = ArrayList<String>()
        private var baseSeq = -1
        private var gameSeq = -1
        private var gameCur: Pair<Int, Int>? = null
        private var open: List<Bag> = emptyList()
        private var pick = false
        private var ok = false
        /** a press the app does not guess (A / B / X / Y, Up onto the strip): no more guesses until the game has caught up */
        private var blocked = false
        private var lastAt = 0L
        var shown: Pair<Int, Int>? = null
            private set

        /** the presses not yet answered by the game */
        val pending get() = moves.size

        private fun clear() { moves.clear(); blocked = false; shown = null }

        private fun replay(): Pair<Int, Int>? {
            var c = gameCur ?: return null
            for (k in moves) c = step(open, c, k, pick) ?: return c
            return c
        }

        /**
         * A document from the add-on: [grid] = it holds the controller on the grid (mode "grid", not on the strip, not equipping),
         * [seq] its ctl.seq, [cur] its cursor, [open] the open bags in order, [pick] something in the hand.
         */
        fun game(grid: Boolean, seq: Int, cur: Pair<Int, Int>?, open: List<Bag>, pick: Boolean, now: Long) {
            val was = gameSeq
            gameSeq = seq; gameCur = cur; this.open = open; this.pick = pick; ok = grid && cur != null
            if (!ok) { clear(); return }
            if (moves.isEmpty()) { shown = null; return }
            val done = seq - baseSeq
            if (done < 0 || done >= moves.size) { clear(); return }      // all answered (or a new session): the game's cursor
            if (seq != was) lastAt = now
            repeat(done) { moves.removeAt(0) }
            baseSeq = seq
            shown = replay()
        }

        /** a press read from the controller; true when the drawn cursor moved */
        fun press(key: String, now: Long): Boolean {
            if (!ok) return false
            if (key !in DPAD) { if (moves.isNotEmpty()) blocked = true; return false }
            if (blocked) return false
            if (moves.isEmpty()) { baseSeq = gameSeq; lastAt = now }
            val before = shown ?: gameCur
            moves.add(key)
            val c = gameCur ?: return false
            var at = c
            for (k in moves) { val n = step(open, at, k, pick); if (n == null) { blocked = true; break }; at = n }
            shown = at
            return at != before
        }

        /** no answer in [STALE_MS]: the guess goes (true when it went) */
        fun expire(now: Long): Boolean {
            if (moves.isEmpty() || now - lastAt < STALE_MS) return false
            clear(); return true
        }
    }

    /**
     * THE HOVER WAIT ONCE (d): the game's quarter second before the FIRST tooltip, then each new item's at once while the player keeps
     * looking (an empty slot between two items keeps it warm for [WARM_MS]). [alpha] = 0..255 for [key] (null = none this frame).
     */
    class WarmFade(private val delay: Long = UiKit.TipFade.DELAY, private val fade: Long = UiKit.TipFade.FADE) {
        private var key: Any? = null
        private var since = 0L
        private var lastShown = Long.MIN_VALUE / 2
        private var lastA = 0
        var animating = false
            private set

        fun alpha(key: Any?, now: Long): Int {
            if (key == null) { if (lastA > 0) lastShown = now; this.key = null; lastA = 0; animating = false; return 0 }
            if (key != this.key) {
                // a tooltip up now (however long ago it was drawn), or one gone only a moment ago: the new one at once
                val warm = lastA > 0 || now - lastShown <= WARM_MS
                this.key = key
                since = if (warm) now - delay - fade else now
            }
            val t = now - since - delay
            val a = if (t <= 0) 0 else if (t >= fade) 255 else (255 * t / fade).toInt()
            animating = a < 255
            lastA = a
            if (a > 0) lastShown = now
            return a
        }

        /** the room left / the controller handed back: the next tooltip waits again */
        fun cold() { key = null; lastA = 0; lastShown = Long.MIN_VALUE / 2; animating = false }

        companion object { const val WARM_MS = 1500L }
    }

    /**
     * THE ACTION ROW ALWAYS SHOWS (the user: "the tooltip on some of the gear in my bag is too big, so I can't see the button glyph
     * options"). The tooltip and its row of glyphs (A Move / X ... / Y More) stand in the room from [top] to [bottom]; [height] measures
     * the tooltip (and what is stacked under its row) at a scale. A tooltip that fits stays at full size; a taller one SHRINKS (down to
     * [MIN_SCALE]); one still too tall is cut at the room's foot with its row pinned there, and scrolls inside its box. The row is
     * never covered and never leaves the room.
     *
     * Returns the scale, the tooltip's top, its drawn height (cut or whole) and how far it can scroll.
     */
    data class Fit(val scale: Float, val y: Float, val h: Float, val scrollMax: Float)

    /**
     * THE RIGHT STICK SCROLLS A CUT TOOLTIP (the user's answer, 2026-10-22: "Right stick"; read only, the game still turns its camera):
     * past [STICK_DEADZONE] the vertical deflection [y] (down +, evdev's) scrolls smoothly, proportional, [STICK_SPEED] px a second at
     * full; [dtMs] since the last step; clamped to 0..[max]. A tooltip that fits ([max] 0) never scrolls.
     */
    const val STICK_DEADZONE = 0.25f
    const val STICK_SPEED = 1200f
    fun stickScroll(scrolled: Float, max: Float, y: Float, dtMs: Long): Float {
        if (max <= 0f) return 0f
        val a = kotlin.math.abs(y)
        if (a <= STICK_DEADZONE) return scrolled.coerceIn(0f, max)
        val k = (a - STICK_DEADZONE) / (1f - STICK_DEADZONE) * kotlin.math.sign(y)
        return (scrolled + k * STICK_SPEED * dtMs / 1000f).coerceIn(0f, max)
    }

    const val MIN_SCALE = 0.62f
    private const val SCALE_STEP = 0.04f

    fun fit(top: Float, bottom: Float, rowY: Float, plateH: Float, height: (Float) -> Float): Fit {
        val room = bottom - top
        var s = 1f
        while (true) {
            val h = height(s)
            if (h + plateH <= room) return Fit(s, maxOf(top, minOf(rowY, bottom - plateH - h)), h, 0f)
            if (s <= MIN_SCALE + 1e-4f) return Fit(s, top, room - plateH, h - (room - plateH))
            s = maxOf(MIN_SCALE, s - SCALE_STEP)
        }
    }
}
