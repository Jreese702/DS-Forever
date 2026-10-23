package com.jrees.wowcompanion.stream

import kotlin.math.abs
import kotlin.math.hypot

/**
 * "TOUCH = MOUSE" ON THE STREAMED PICTURE (the user's ask, 2026-10-23): a finger on the top screen, where no on-screen control is,
 * moves and clicks the PC's mouse. Absolute: the touch point on the view maps to the same point of the stream (the stream fills the
 * view; Sunshine maps the stream onto the PC's captured monitor).
 *  - tap = left click there;
 *  - hold still ([LONG_MS]) then lift = right click; or a two-finger tap = right click (at the first finger);
 *  - drag = left button held from where the finger started, the pointer following the finger, released on lift;
 *  - two-finger drag = the wheel (fingers down = scroll up, as content follows the fingers).
 * Pure: the view feeds [down] / [move] / [up] / [cancel] with view pixels and times; the output goes to [MoonInput].
 */
class StreamTouch(
    private val input: MoonInput,
    @Volatile var viewW: Int, @Volatile var viewH: Int,
    @Volatile var streamW: Int, @Volatile var streamH: Int,
    private val slopPx: Float,
    /** runs a click's release a moment later (the PC's game sees two events, as with StreamKeys' taps); the tests run it at once */
    private val later: (Long, () -> Unit) -> Unit = { _, r -> r() },
) {
    private class P(val x0: Float, val y0: Float, val t0: Long) { var x = x0; var y = y0 }

    private val pointers = LinkedHashMap<Int, P>()
    private enum class Mode { IDLE, ONE, DRAG, TWO, SCROLL, DONE }
    private var mode = Mode.IDLE
    private var first = -1
    private var secondAt = 0L
    private var scrollY = 0f
    private var scrollRest = 0f

    fun down(id: Int, x: Float, y: Float, t: Long) {
        pointers[id] = P(x, y, t)
        when (mode) {
            Mode.IDLE -> { mode = Mode.ONE; first = id }
            Mode.ONE -> { mode = Mode.TWO; secondAt = t; scrollY = centroidY(); scrollRest = 0f }
            else -> {}                                    // a third finger, or one during a drag: ignored
        }
    }

    fun move(id: Int, x: Float, y: Float, t: Long) {
        val p = pointers[id] ?: return
        p.x = x; p.y = y
        when (mode) {
            Mode.ONE -> if (id == first && hypot(x - p.x0, y - p.y0) > slopPx) {
                mode = Mode.DRAG
                position(p.x0, p.y0)
                input.mouseButton(true, StreamKeys.BUTTON_LEFT)
                position(x, y)
            }
            Mode.DRAG -> if (id == first) position(x, y)
            Mode.TWO, Mode.SCROLL -> {
                val cy = centroidY()
                if (mode == Mode.TWO && abs(cy - scrollY) > slopPx) { mode = Mode.SCROLL; scrollY = cy }
                if (mode == Mode.SCROLL) {
                    scrollRest += (cy - scrollY) * SCROLL_PER_PX
                    scrollY = cy
                    val amount = scrollRest.toInt()
                    if (amount != 0) { input.scroll(amount); scrollRest -= amount }
                }
            }
            else -> {}
        }
    }

    fun up(id: Int, t: Long) {
        val p = pointers.remove(id) ?: return
        when (mode) {
            Mode.ONE -> if (id == first) {
                position(p.x, p.y)
                click(if (t - p.t0 >= LONG_MS) StreamKeys.BUTTON_RIGHT else StreamKeys.BUTTON_LEFT)
                mode = Mode.IDLE
            }
            Mode.DRAG -> if (id == first) { position(p.x, p.y); input.mouseButton(false, StreamKeys.BUTTON_LEFT); mode = Mode.DONE }
            Mode.TWO -> {
                // the first finger to lift ends a two-finger tap (if quick): the right click at the first finger
                if (t - secondAt <= TAP_MS) { val f = if (id == first) p else pointers[first] ?: p; position(f.x, f.y); click(StreamKeys.BUTTON_RIGHT) }
                mode = Mode.DONE
            }
            Mode.SCROLL -> mode = Mode.DONE
            else -> {}
        }
        if (pointers.isEmpty()) { mode = Mode.IDLE; first = -1 }
    }

    /** the gesture was taken away (another view, a cancel): a held button is let go, nothing else happens */
    fun cancel() {
        if (mode == Mode.DRAG) input.mouseButton(false, StreamKeys.BUTTON_LEFT)
        pointers.clear(); mode = Mode.IDLE; first = -1
    }

    private fun centroidY(): Float = if (pointers.isEmpty()) 0f else pointers.values.sumOf { it.y.toDouble() }.toFloat() / pointers.size

    private fun click(button: Int) {
        input.mouseButton(true, button)
        later(StreamKeys.CLICK_MS) { input.mouseButton(false, button) }
    }

    /** a view point -> the same point of the stream */
    private fun position(x: Float, y: Float) {
        val w = streamW.coerceAtLeast(1); val h = streamH.coerceAtLeast(1)
        val fx = (x / viewW.coerceAtLeast(1)).coerceIn(0f, 1f); val fy = (y / viewH.coerceAtLeast(1)).coerceIn(0f, 1f)
        input.mousePosition((fx * (w - 1)).toInt(), (fy * (h - 1)).toInt(), w, h)
    }

    companion object {
        const val LONG_MS = 500L
        const val TAP_MS = 300L
        /** wheel units (120 = one notch) per pixel of two-finger travel */
        const val SCROLL_PER_PX = 3f
    }
}
