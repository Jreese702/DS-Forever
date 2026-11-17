package com.jrees.wowcompanion

import android.graphics.Canvas
import android.graphics.RectF
import kotlin.math.max

/**
 * pm3: THE NEXT / PREVIOUS STEP CHOICE (Y on a Polestar row), one for the Map room and Home's map alike. The user's picks, 2026-11-16:
 * "Yes, match the Map room" (Home's map Polestar row gets the Map room's buttons: X the Polestar menu, Y this choice) and "Close after
 * one press" (a row pressed closes the choice; a dim row only says why and stays). Its rows are [MapRoomKeys.PsSkip]: Polestar's own
 * Engine.Next / Engine.Prev (pg2 framed ops 24 / 25, a look that skips nothing) through the one [PolestarMenu] (one frame in flight).
 * Skip for good and Undo skip (pk1) stay the Polestar menu's own X / Y.
 */
class PolestarStepChoice(private val menu: () -> PolestarMenu?, private val say: (String) -> Unit) {
    var open = false
        private set
    var sel = 0
    val hits = ArrayList<Pair<RectF, Int>>()
    val box = RectF()

    /** Y can open it: Polestar has a guide */
    fun can(): Boolean = menu()?.canStep() == true
    /** Polestar's shown step and the guide's step count (the dim rows) */
    private fun shown(): Pair<Int?, Int> = menu()?.book?.guide.let { g -> g?.shown to (g?.count ?: 0) }
    fun on(s: MapRoomKeys.PsSkip): Boolean = shown().let { (sh, n) -> MapRoomKeys.psSkipOn(s, sh, n) }

    /** Y: open on Next step (on Previous when only that can be pressed); false = it cannot open */
    fun open(): Boolean {
        if (!can()) return false
        sel = if (!on(MapRoomKeys.PsSkip.NEXT) && on(MapRoomKeys.PsSkip.PREV)) 1 else 0
        open = true
        return true
    }
    fun close() { open = false; hits.clear(); box.setEmpty() }

    /** one press while it shows: D-pad moves, A presses the row, B or Y closes */
    fun key(k: String) {
        when (val d = MapRoomKeys.psSkipKey(k, sel)) {
            MapRoomKeys.SkipDo.Close -> close()
            is MapRoomKeys.SkipDo.Move -> sel = d.sel
            is MapRoomKeys.SkipDo.Run -> run(d.skip)
            MapRoomKeys.SkipDo.None -> {}
        }
    }

    /** one row pressed: a dim one says why (the choice stays); else Polestar's own Next / Previous step, and the choice closes (pm3) */
    fun run(s: MapRoomKeys.PsSkip) {
        val m = menu()?.takeIf { it.canStep() } ?: run { close(); say(PolestarSkip.NO_STEP_LINE); return }
        if (!on(s)) { say(PolestarTab.NOTHING_LINE); return }
        if (m.shownIn == null) m.note = { say(it) }                  // (a refusal's line here, unless a room shows the menu)
        close()
        if (!m.step(s == MapRoomKeys.PsSkip.NEXT)) say(PolestarSkip.NO_STEP_LINE)
    }

    /** a touch while it shows: a row presses it, anywhere else closes it */
    fun touch(x: Float, y: Float) {
        hits.firstOrNull { it.first.contains(x, y) }?.let { (_, i) -> sel = i; run(MapRoomKeys.PsSkip.values()[i]) } ?: close()
    }

    /** under the row [a] (above it when it would pass [bottom], never over [top]), the row's width, the kit's menu rows */
    fun draw(c: Canvas, a: RectF, top: Float, bottom: Float, rowH: Float, size: Float = 28f) {
        hits.clear()
        if (!open) { box.setEmpty(); return }
        val rows = MapRoomKeys.PsSkip.values()
        val h = 16f + rows.size * rowH
        var y = a.bottom + 2f
        if (y + h > bottom) y = max(top, a.top - 2f - h)
        box.set(a.left, y, a.right, y + h)
        UiKit.menu(c, box, solid = true)
        for ((i, s) in rows.withIndex()) {
            val r = RectF(box.left + 8f, box.top + 8f + i * rowH, box.right - 8f, box.top + 8f + (i + 1) * rowH)
            UiKit.menuRow(c, r, s.word, i == sel, enabled = on(s), size = size)
            hits.add(r to i)
        }
    }

    companion object {
        /** a row (the Map room's quest options' row) */
        const val ROW = 50f
        /** a long press on a Polestar row opens the choice (the Map room's long press) */
        const val LONG_PRESS_MS = 500L
    }
}
