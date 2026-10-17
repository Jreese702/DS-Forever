package com.jrees.wowcompanion

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ceil

/**
 * THE RADIAL'S BAG PICKER ("Pin an item"; the user's sign-off 2026-10-06 via Home: "a + button to the bottom of the radial,
 * to basically be able to manually select something from my bag and then be prompted which slice to add it to"). UI Art's
 * PIN_SPEC state 3 / PIN_3_PICKER (PinSample.kt), drawn with the kit's own pieces:
 *  - the game's window "Pin an item", the Bags room's picture in the ring, "Usable items in your bags";
 *  - the USABLE items (a bag row with `use`: anything with a Use) in bag order, ONE tile per item id (a pin is an item, so
 *    its stacks are one tile with their total), packed 5 across at 104 px, no empty wells; a pinned item wears the small pin;
 *  - the gold-box cursor ([UiKit.focus]) and the game's tooltip beside the grid at the cursor's row ([TipStack]), from the
 *    bags doc's `ptips` (Bags.lua: one tap on bmvq 23 at open, 24 at close) or, until they come, the name and type;
 *  - the strip: Choose (A), a PRESS, and Back (B), which closes the picker.
 * An ALREADY-pinned item: A opens its two choices in the tooltip's place, Move (pick a new slice) / Unpin; A chooses, B
 * goes back to the grid (UI Art is drawing that row; the kit's menu rows until then).
 *
 * Home owns the flow round it (the wheel's "+" opens it, the slice chooser after it) and feeds it: [update] with every bags
 * doc, [press] with the controller's buttons (DirectPad or a guest, the picker does not care). Choose and Move close the
 * picker and then call onPick; Unpin calls onPick and stays (the pin goes from its tile at once); Back closes and calls
 * onClose. Its taps on bmvq 23 / 24 wait a moment after the press and are checked against the docs (see SETTLE_MS). The cursor stays on the same item from one open to the next, so a B in Home's chooser can bring the picker back
 * where it was. Pixel sizes are physical px on the 1240 px wide bottom screen, like every room.
 */
class BagsPinPicker(c: Context) : View(c) {

    enum class PinAction { PICK, MOVE, UNPIN }

    private class Entry(val id: Int, val row: JSONObject, val count: Int)

    private var shown = false
    val isOpen get() = shown
    private var pinned: Set<Int> = emptySet()
    private var onPick: ((Int, PinAction) -> Unit)? = null
    private var onClose: (() -> Unit)? = null
    private var entries = listOf<Entry>()
    private val ptips = HashMap<Int, JSONArray>()
    private var pad: JSONObject? = null
    private var lastJson = ""
    private var cur = 0                  // the cursor: an index into entries
    private var curId = -1               // the item it is on (kept across opens and bag changes)
    private var menu = -1                // -1 = the grid; 0 Move / 1 Unpin = a pinned item's two choices
    private var first = 0                // the first grid row in sight (the cursor's row is always in sight)
    // the tooltips' switch in Bags.lua (bmvq 23 on / 24 off), kept in step with what the bags docs say
    private var wantTips = false         // open = on, closed = off
    private var docTips = false          // the last bags doc carried ptips
    private var tapAt = 0L               // when the last 23 / 24 went out
    private var tries = 0                // taps since wantTips last changed
    private var forceNext = false        // the first tap of an open goes out even if the doc says on (it restarts the 300 s)
    private val sync = Runnable { syncTips() }
    private val tips = TipStack(this)
    private val bmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val flat = Paint(Paint.ANTI_ALIAS_FLAG)

    // what the last frame drew, for the finger
    private val tiles = ArrayList<Pair<RectF, Int>>()        // tile, entry index
    private val rows = ArrayList<Pair<RectF, Int>>()         // menu row, 0 Move / 1 Unpin
    private var chooseR = RectF(); private var backR = RectF(); private var gridR = RectF()
    private var rowsFit = 1

    init { visibility = GONE }

    companion object {
        const val S = 104f; const val GAP = 26f; const val STEP = S + GAP + 8f; const val COLS = 5
        const val TIP_W = 380f
        const val RETAP_MS = 240_000L        // Bags.lua keeps ptips on 300 s after a 23: tap again while still open
        // A TAP RIGHT AFTER A CONTROLLER PRESS CAN MISS: in freelook the game snaps its hidden cursor to the park point just
        // before the injected press, and the pad's sink there takes it ("padstray"; Home measured 4 of 10, and the Thor's b67
        // showed it on the picker's first 23, sent the instant the A that opened it). So each switch waits SETTLE_MS after
        // open / close, and the next docs are checked CHECK_MS later: no change = tap again, TRIES at most. 23 and 24 only set
        // a state, so a second one is harmless.
        const val SETTLE_MS = 350L; const val CHECK_MS = 1500L; const val TRIES = 4
        private const val ROW = "bmvq"; private const val TIPS_ON = 23; private const val TIPS_OFF = 24
        private fun real(s: String?) = !s.isNullOrEmpty() && s[0].code > 3
    }

    // ------------------------------------------------------------------ Home's API

    fun open(pinnedIds: Set<Int>, onPick: (itemId: Int, action: PinAction) -> Unit, onClose: () -> Unit) {
        pinned = pinnedIds.toSet(); this.onPick = onPick; this.onClose = onClose
        shown = true; menu = -1
        visibility = VISIBLE
        want(true)
        follow(); invalidate()
    }

    /** closes without a word to Home (Home's own route away); Back and Choose call this too */
    fun close() {
        if (!shown) return
        shown = false; menu = -1; pressed = null
        visibility = GONE
        want(false)
    }

    /** every bags doc (the same one the Bags room gets); cheap while closed */
    fun update(bags: JSONObject) {
        bags.optJSONObject("pad")?.let { pad = it }
        docTips = bags.has("ptips")
        if (wantTips && tapAt != 0L && SystemClock.uptimeMillis() - tapAt > RETAP_MS) { tries = 0; forceNext = true; syncTips() }
        val json = bags.toString()
        if (json == lastJson) return
        lastJson = json
        // bag order: the bags as the doc lists them, then the slot
        val order = HashMap<Int, Int>()
        bags.optJSONArray("bags")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { order[it.optInt("bag")] = i } }
        val its = bags.optJSONArray("items") ?: JSONArray()
        // the user's ruling on 1.0.9-test18: "Recipes should not be an option to assign to the quick radial" (Bags' cls 9 = Recipe)
        val usable = (0 until its.length()).mapNotNull { its.optJSONObject(it) }
            .filter { it.optBoolean("use") && it.has("id") && it.optInt("cls", -1) != RECIPE_CLASS }
            .sortedWith(compareBy({ order[it.optInt("bag")] ?: (100 + it.optInt("bag")) }, { it.optInt("slot") }))
        val byId = LinkedHashMap<Int, Pair<JSONObject, Int>>()
        for (r in usable) {
            val id = r.optInt("id"); val n = r.optInt("count", 1).coerceAtLeast(1)
            val had = byId[id]
            byId[id] = if (had == null) r to n else had.first to had.second + n
        }
        entries = byId.map { (id, p) -> Entry(id, p.first, p.second) }
        ptips.clear()
        bags.optJSONArray("ptips")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { o -> o.optJSONArray("lines")?.let { ptips[o.optInt("id")] = it } }
        }
        follow()
        if (shown) invalidate()
    }

    /** a controller button by its binding name (PADDUP .. PADDRIGHT, PAD1 = A, PAD2 = B); true = the picker used it */
    fun press(key: String): Boolean {
        if (!shown) return false
        if (menu >= 0) {
            when (key) {
                "PADDUP" -> menu = 0
                "PADDDOWN" -> menu = 1
                "PAD1" -> { entries.getOrNull(cur)?.let { choose(it, if (menu == 0) PinAction.MOVE else PinAction.UNPIN) }; return true }
                "PAD2" -> menu = -1
                "PADDLEFT", "PADDRIGHT" -> {}
                else -> return false
            }
            invalidate(); return true
        }
        when (key) {
            "PADDLEFT" -> step(-1, 0)
            "PADDRIGHT" -> step(1, 0)
            "PADDUP" -> step(0, -1)
            "PADDDOWN" -> step(0, 1)
            "PAD1" -> { a(); return true }
            "PAD2" -> { back(); return true }
            else -> return false
        }
        invalidate(); return true
    }

    // ------------------------------------------------------------------ what A / B do

    private fun want(on: Boolean) {
        removeCallbacks(sync)
        if (!on && !wantTips) return
        wantTips = on; tries = 0; forceNext = on
        postDelayed(sync, SETTLE_MS)
    }

    /** one tap toward [wantTips] unless the docs already say so, then a look again [CHECK_MS] later */
    private fun syncTips() {
        removeCallbacks(sync)
        if (!forceNext && docTips == wantTips) return
        if (tries >= TRIES) return                       // the tooltips stay name + type (on), or Bags.lua's 300 s end them (off)
        forceNext = false; tries++
        if (GameInput.tap(context, pad, ROW, if (wantTips) TIPS_ON else TIPS_OFF)) tapAt = SystemClock.uptimeMillis()
        postDelayed(sync, CHECK_MS)
    }

    override fun onDetachedFromWindow() { removeCallbacks(sync); super.onDetachedFromWindow() }

    /** the cursor back on the item it was on (its tile may have moved), else the nearest one */
    private fun follow() {
        val i = entries.indexOfFirst { it.id == curId }
        cur = if (i >= 0) i else cur.coerceIn(0, maxOf(0, entries.size - 1))
        curId = entries.getOrNull(cur)?.id ?: -1
        if (menu >= 0 && curId !in pinned) menu = -1
    }

    private fun step(dx: Int, dy: Int) {
        if (entries.isEmpty()) return
        val col = cur % COLS; val row = cur / COLS
        val last = entries.size - 1
        cur = when {
            dx < 0 -> if (col > 0) cur - 1 else cur
            dx > 0 -> if (col < COLS - 1 && cur < last) cur + 1 else cur
            dy < 0 -> if (row > 0) cur - COLS else cur
            else -> if ((row + 1) * COLS <= last) minOf(cur + COLS, last) else cur      // into a shorter last row: its end
        }
        curId = entries[cur].id
    }

    private fun a() {
        val e = entries.getOrNull(cur) ?: return
        if (e.id in pinned) { menu = 0; invalidate() } else choose(e, PinAction.PICK)
    }

    private fun choose(e: Entry, action: PinAction) {
        val cb = onPick
        if (action == PinAction.UNPIN) {
            pinned = pinned - e.id; menu = -1; invalidate()
        } else close()
        cb?.invoke(e.id, action)
    }

    private fun back() {
        if (menu >= 0) { menu = -1; invalidate(); return }
        val cb = onClose
        close()
        cb?.invoke()
    }

    // ------------------------------------------------------------------ drawing

    private fun nameOf(e: Entry) = e.row.optString("name").takeIf { real(it) } ?: "Loading…"
    private fun qualityOf(e: Entry) = if (e.row.has("quality")) e.row.optInt("quality") else 1

    /** the game's tooltip lines (ptips), or until they come the item's own name and type */
    private fun tipOf(e: Entry): JSONObject {
        ptips[e.id]?.let { return JSONObject().put("key", "pin ${e.id} t").put("lines", it) }
        val lines = JSONArray().put(JSONObject().put("t", nameOf(e)))
        val sub = listOf(e.row.optString("itype"), e.row.optString("isub")).filter { real(it) }.joinToString(" · ")
        if (sub.isNotEmpty()) lines.put(JSONObject().put("t", sub))
        if (e.count > 1) lines.put(JSONObject().put("t", "${e.count} in your bags"))
        return JSONObject().put("key", "pin ${e.id} f").put("lines", lines)
    }

    override fun onDraw(c: Canvas) {
        if (!shown) return
        tiles.clear(); rows.clear()
        val r = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val inner = UiKit.windowGround(c, r)
        val strip = UiKit.strip(c, inner)
        // test28 (the user: "when choosing the pin item all that it needs to say at the top is pin, the rest of the text can go away")
        val gx = inner.left + 150f; val gy = inner.top + 76f
        val gBottom = strip.top - 12f
        gridR = RectF(gx - 20f, gy - 20f, gx + COLS * S + (COLS - 1) * GAP + 20f, gBottom)
        rowsFit = maxOf(1, ((gBottom - gy + GAP + 8f) / STEP).toInt())
        val rowCount = ceil(entries.size / COLS.toDouble()).toInt()
        val curRow = cur / COLS
        if (curRow < first) first = curRow
        if (curRow > first + rowsFit - 1) first = curRow - rowsFit + 1
        first = first.coerceIn(0, maxOf(0, rowCount - rowsFit))

        var cursor: RectF? = null
        if (entries.isEmpty()) {
            c.drawText("No usable items in your bags", gx, gy + 50f, UiKit.bodyPaint(28f, Theme.dim))
        } else {
            c.save(); c.clipRect(gridR)
            for ((k, e) in entries.withIndex()) {
                val row = k / COLS - first
                if (row < 0 || row >= rowsFit) continue
                val x = gx + (k % COLS) * (S + GAP); val y = gy + row * STEP
                val q = qualityOf(e)
                UiKit.slot(c, Icons.getOrUnknown(if (e.row.has("icon")) e.row.optInt("icon") else null), x, y, S,
                    if (q >= 2) Theme.quality(q) else 0, e.count)
                if (e.id in pinned) pin(c, x, y)
                val box = RectF(x, y, x + S, y + S)
                tiles.add(box to k)
                if (k == cur) cursor = box
            }
            cursor?.let { UiKit.focus(c, it) }
            c.restore()
            if (rowCount > rowsFit)
                UiKit.scrollBar(c, RectF(gx - 64f, gy, gx - 40f, gy + rowsFit * STEP - GAP - 8f),
                    first / rowCount.toFloat(), (first + rowsFit) / rowCount.toFloat())
        }

        // the tooltip beside the grid, level with the cursor's row; a pinned item's two choices in its place
        val e = entries.getOrNull(cur)
        val tx = inner.right - 400f
        if (e != null && cursor != null) {
            if (menu >= 0) choices(c, e, tx, cursor.top - 6f, inner.top + 8f, strip.top - 8f)
            else tips.draw(c, tx, cursor.top - 6f, TIP_W, nameOf(e), qualityOf(e), tipOf(e), inner.top + 8f, strip.top - 8f,
                tx, false, 0, hint = false)
        }

        val bt = strip.top + 13f
        chooseR = RectF(inner.right - 8f - 340f - 24f - 340f, bt, inner.right - 8f - 340f - 24f, bt + 111f)
        backR = RectF(inner.right - 8f - 340f, bt, inner.right - 8f, bt + 111f)
        UiKit.button(c, chooseR, "Choose", "rev_a", when {
            e == null -> UiKit.Button.DISABLED
            pressed === chooseR -> UiKit.Button.DOWN
            else -> UiKit.Button.UP
        })
        UiKit.button(c, backR, "Back", UiKit.backGlyph(), if (pressed === backR) UiKit.Button.DOWN else UiKit.Button.UP)
        UiKit.windowFrame(c, r, "Pin", UiKit.tex("ricon_bags"), close = false)
    }

    /** the Friends list's gold pin over the tile's top-right corner (the count keeps the bottom-right) */
    private fun pin(c: Canvas, x: Float, y: Float) {
        val b = UiKit.tex("rwheel_pin")
        if (b != null) { c.drawBitmap(b, null, RectF(x + S - 30f, y - 8f, x + S + 12f, y + 34f), bmp); return }
        // the kit without the pin yet: a gold head on a dark rim, so a pinned item still shows
        flat.style = Paint.Style.FILL; flat.color = Color.BLACK; c.drawCircle(x + S - 9f, y + 11f, 13f, flat)
        flat.color = Theme.gold; c.drawCircle(x + S - 9f, y + 11f, 10f, flat)
    }

    /** a pinned item: Move / Unpin, in the tooltip's place (the Bags room's More menu, the kit's menu rows) */
    private fun choices(c: Canvas, e: Entry, x: Float, yRow: Float, top: Float, bottom: Float) {
        val h = 56f + 2 * 96f + 24f
        val y0 = maxOf(top, minOf(yRow, bottom - h))
        val box = RectF(x, y0, x + TIP_W, y0 + h)
        val inner = UiKit.menu(c, box, solid = true)
        val np = UiKit.bodyPaint(26f, Theme.quality(qualityOf(e)))
        val title = android.text.TextUtils.ellipsize(nameOf(e), android.text.TextPaint(np), inner.width() - 32f,
            android.text.TextUtils.TruncateAt.END).toString()
        c.drawText(title, inner.left + 16f, inner.top + 34f, np)
        for ((i, label) in listOf("Move", "Unpin").withIndex()) {
            val rr = RectF(inner.left, inner.top + 56f + i * 96f, inner.right, inner.top + 56f + i * 96f + 92f)
            UiKit.menuRow(c, rr, label, menu == i, size = 28f)
            rows.add(rr to i)
        }
    }

    // ------------------------------------------------------------------ the finger
    // a tap on a tile puts the cursor there, a tap on the cursor's tile chooses it (A); Choose / Back are A / B; a menu row
    // chooses that row; an up / down swipe over the grid scrolls it when the items fill more rows than it shows

    private var pressed: RectF? = null
    private var downX = 0f; private var downY = 0f; private var downFirst = 0
    private var swiping = false
    private val slop = ViewConfiguration.get(c).scaledTouchSlop

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!shown) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; downFirst = first; swiping = false
                pressed = listOf(chooseR, backR).firstOrNull { it.contains(e.x, e.y) }
                if (pressed === chooseR && entries.isEmpty()) pressed = null
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!swiping && gridR.contains(downX, downY) && abs(e.y - downY) > slop * 2) swiping = true
                if (swiping) {
                    val rowCount = ceil(entries.size / COLS.toDouble()).toInt()
                    first = (downFirst - ((e.y - downY) / STEP).toInt()).coerceIn(0, maxOf(0, rowCount - rowsFit))
                    // the cursor stays in sight: onto the nearest row shown
                    val row = cur / COLS
                    if (row < first) cur = minOf(entries.size - 1, first * COLS + cur % COLS)
                    if (row > first + rowsFit - 1) cur = minOf(entries.size - 1, (first + rowsFit - 1) * COLS + cur % COLS)
                    curId = entries.getOrNull(cur)?.id ?: -1
                    invalidate()
                }
                if (pressed != null && !pressed!!.contains(e.x, e.y)) { pressed = null; invalidate() }
            }
            MotionEvent.ACTION_UP -> {
                val was = pressed; pressed = null
                if (swiping) { invalidate(); return true }
                when {
                    was === chooseR && chooseR.contains(e.x, e.y) -> press("PAD1")
                    was === backR && backR.contains(e.x, e.y) -> press("PAD2")
                    else -> {
                        val row = rows.firstOrNull { it.first.contains(e.x, e.y) }
                        val tile = tiles.firstOrNull { it.first.contains(e.x, e.y) }
                        if (row != null) { menu = row.second; press("PAD1") }
                        else if (tile != null) {
                            if (tile.second == cur && menu < 0) a()
                            else { menu = -1; cur = tile.second; curId = entries[cur].id }
                        }
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { pressed = null; swiping = false; invalidate() }
        }
        return true
    }
}

/** Bags' numeric item class of a recipe (Enum.ItemClass.Recipe): never offered for the quick radial */
internal const val RECIPE_CLASS = 9
