package com.jrees.wowcompanion

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * THE ITEM TOOLTIP, shared by every window that shows one (Context Menus' NPC windows, the Bags room's cursor; owner:
 * Context Menus). One instance per view.
 *
 * What it draws, from the addon's tip document ({key, lines, cmp?}; WC.tipOf / N.tipOf): UI Art's kit tooltip
 * ([UiKit.tooltip]) after the 250 ms wait and fade-in ([UiKit.TipFade]); for a comparable item, the "Compare" hint under
 * it ([UiKit.tooltipHint]) or, while X is held ([compareHeld]), each worn item beside it in the column at [cmpX]
 * ("Currently Equipped", the stat-change lines last). It starts level with the highlighted row ([yRow]), lifted only as
 * far as it must to fit between [top] and [bottom]. A stack taller than that room scrolls by the right stick's [steps]
 * (or the D-pad's while X is held), clipped to the room.
 *
 * The game's own colour codes never reach the page: a line coloured inside ("|cff00ff00+129|r Armor") takes that colour.
 * Issue #233: a line with "m" (the game's sell price, Interact i160) shows that copper as the game's coins after its words.
 */
class TipStack(private val view: View) {

    private val fade = UiKit.TipFade()

    /** nothing shown this frame: the next tooltip waits out the quarter second again (even the same one coming back) */
    fun forget() { fade.alpha(null) }

    /** what was drawn: the main tooltip's box, the foot under its Compare hint (else the box's), this frame's fade alpha, and how many
     *  [STEP]s of scrolling reach the stack's end (0 = it fits: nothing to scroll) */
    class Drawn(val box: RectF, val bottom: Float, val alpha: Int, val maxSteps: Int = 0)

    /**
     * Draws the stack; returns what it drew, so a caller can put its own piece under it (Bags: UI Art's action plate) at
     * [Drawn.bottom] with [Drawn.alpha]. [hint] = false leaves the "Compare" hint out (the caller says it its own way).
     */
    fun draw(c: Canvas, x: Float, yRow: Float, w: Float, name: String, quality: Int, tip: JSONObject,
             top: Float, bottom: Float, cmpX: Float, compareHeld: Boolean, steps: Int = 0, hint: Boolean = true): Drawn {
        val key = tip.optString("key")
        val a = fade.alpha(key)
        if (fade.animating) view.postInvalidateOnAnimation()
        val lines = tip.optJSONArray("lines")
        val first = lines?.optJSONObject(0)
        val body = lines(lines, first != null && str(first, "t") == name)
        val cmp = tip.optJSONArray("cmp")
        val comparable = cmp != null && cmp.length() > 0
        val held = comparable && compareHeld
        // measure first (alpha 0 draws nothing), then place
        val h1 = UiKit.tooltip(c, x, 0f, w, name, Theme.quality(quality), body, alpha = 0).height()
        data class Eq(val name: String, val colour: Int, val lines: List<UiKit.TipLine>)
        val eq = ArrayList<Eq>()
        if (held) for (n in 0 until cmp!!.length()) {
            val el = cmp.optJSONObject(n)?.optJSONArray("lines") ?: continue
            val head = el.optJSONObject(0) ?: continue
            eq.add(Eq(str(head, "t") ?: "", colour(str(head, "c"), Color.WHITE), lines(el, true)))
        }
        var hc = 0f
        for ((n, e) in eq.withIndex()) {
            hc += UiKit.tooltip(c, cmpX, 0f, w, e.name, e.colour, e.lines, header = "Currently Equipped", alpha = 0).height()
            if (n > 0) hc += 2f
        }
        val withHint = comparable && !held && hint
        val tallest = if (held) max(h1, hc) else h1 + (if (withHint) 64f else 0f)
        val room = bottom - top
        // too tall for the room: it scrolls by the steps, from its top, never past its end
        val over = max(0f, tallest - room + 4f)
        val lift = min(over, max(0, steps) * STEP)
        val y = if (over > 0f) top - lift else max(top, min(yRow, bottom - tallest - 4f))
        c.save(); c.clipRect(min(x, cmpX) - 4f, top, max(x, cmpX) + w + 4f, bottom)
        val box = UiKit.tooltip(c, x, y, w, name, Theme.quality(quality), body, alpha = a)
        val foot = if (withHint) UiKit.tooltipHint(c, box, "rev_x", "Compare", alpha = a).bottom else box.bottom
        var by = y
        for (e in eq) {
            val b = UiKit.tooltip(c, cmpX, by, w, e.name, e.colour, e.lines, header = "Currently Equipped", alpha = a)
            by = b.bottom + 2f
        }
        c.restore()
        return Drawn(RectF(box), foot, a, kotlin.math.ceil(over / STEP).toInt())
    }

    /**
     * ISSUE #233 (a player at a vendor: "when I try to read the information for the items at the bottom of the screen, the tooltip
     * disappears"): a tooltip never took a touch, so a finger on it landed on the item tile UNDER it (the vendor's other column; for a
     * bottom row the tooltip is lifted over several tiles) and selected that item instead - the tooltip went, and a cut-off tooltip could
     * only be scrolled by the right stick. Now a finger that lands on a shown tooltip is the tooltip's: nothing under it is pressed, and
     * dragging it up / down scrolls it ([STEP] px per step, added to the right stick's). Pure: the view tells it where the tooltip was
     * drawn this frame ([shown]; [clear] each frame), the touch goes through [down] / [move] / [up]; a new tooltip starts unscrolled.
     */
    class Touch {
        private var on = false
        private var l = 0f; private var t = 0f; private var r = 0f; private var b = 0f
        private var key: String? = null
        private var downY = 0f
        private var from = 0
        /** the finger's own steps for the tooltip shown now */
        var steps = 0
            private set
        /** a finger is on the tooltip (from its down to its up) */
        var held = false
            private set

        /** a new frame: no tooltip taken a touch until one is drawn again */
        fun clear() { on = false }

        /** the steps to draw [k] with: 0 again for a new tooltip */
        fun stepsFor(k: String?): Int {
            if (k != key) { key = k; steps = 0 }
            return steps
        }

        /** the most steps a drag can add for the tooltip shown now (its end reached: dragging back moves it at once) */
        private var most = MAX_STEPS

        /** the tooltip [k] was drawn at [left]..[right] x [top]..[bottom] this frame; [maxSteps] = the steps that reach its end */
        fun shown(k: String?, left: Float, top: Float, right: Float, bottom: Float, maxSteps: Int = MAX_STEPS) {
            stepsFor(k)
            most = maxSteps.coerceIn(0, MAX_STEPS)
            if (steps > most) steps = most
            on = right > left && bottom > top
            l = left; t = top; r = right; b = bottom
        }

        /** a finger came down at ([x], [y]): true = it is on the tooltip (the tooltip's touch: nothing under it is pressed) */
        fun down(x: Float, y: Float): Boolean {
            held = on && x >= l && x <= r && y >= t && y <= b
            if (held) { downY = y; from = steps }
            return held
        }

        /** the finger moved to [y]: true while it is the tooltip's (dragging up reads further down) */
        fun move(y: Float): Boolean {
            if (!held) return false
            steps = (from + ((downY - y) / STEP).toInt()).coerceIn(0, most)
            return true
        }

        /** the finger left: true when it was the tooltip's (no tap for what is under it) */
        fun up(): Boolean { val h = held; held = false; return h }

        companion object { const val MAX_STEPS = 60 }
    }

    companion object {
        const val STEP = 60f                     // one step of the stick: about two lines
        private val CODES = Regex("""\|c[0-9a-fA-F]{8}|\|r""")
        private val CODE_COLOUR = Regex("""\|c[0-9a-fA-F]{2}([0-9a-fA-F]{6})""")

        private fun str(o: JSONObject, key: String): String? = if (o.has(key) && !o.isNull(key)) o.optString(key) else null

        fun colour(hex: String?, def: Int): Int =
            if (hex != null && hex.length == 6) runCatching { Color.parseColor("#$hex") }.getOrDefault(def) else def

        private val PLURAL_N = Regex("""(\d+)(\s*)\|4([^:;]*):([^;]*);""")
        private val PLURAL = Regex("""\|4[^:;]*:([^;]*);""")

        /** the game's codes out: colour codes, and its plural escape "|4one:many;" resolved by the number before it */
        fun strip(s: String) = s.replace(CODES, "")
            .replace(PLURAL_N) { m -> m.groupValues[1] + m.groupValues[2] + (if (m.groupValues[1] == "1") m.groupValues[3] else m.groupValues[4]) }
            .replace(PLURAL) { m -> m.groupValues[1] }

        /** a tooltip's lines as the kit draws them; the first (the name) left out when the box shows the name itself */
        fun lines(a: JSONArray?, skipFirst: Boolean): List<UiKit.TipLine> {
            val out = ArrayList<UiKit.TipLine>()
            if (a == null) return out
            for (n in (if (skipFirst) 1 else 0) until a.length()) {
                val l = a.optJSONObject(n) ?: continue
                val raw = str(l, "t") ?: continue
                val inner = CODE_COLOUR.find(raw)?.groupValues?.get(1)
                val c = str(l, "c")?.takeUnless { inner != null && it.equals("ffffff", true) } ?: inner
                val r = str(l, "r")?.let { strip(it) }
                // issue #233 (Interact i160): the game's sell price line carries its copper in "m", drawn as coins after the words
                val m = if (l.has("m")) l.optLong("m", -1L) else -1L
                out.add(UiKit.TipLine(strip(raw), colour(c, Color.WHITE), r, colour(str(l, "rc"), Color.WHITE), money = m))
            }
            return out
        }
    }
}
