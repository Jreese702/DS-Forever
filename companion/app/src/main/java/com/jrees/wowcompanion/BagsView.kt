package com.jrees.wowcompanion

import android.content.Context
import android.graphics.*
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

private fun lp(w: Int, h: Int, weight: Float = 0f) =
    LinearLayout.LayoutParams(w, h).apply { if (weight > 0f) this.weight = weight }

/** sentinels are control characters \1 \2 \3 */
private fun real(s: String?) = !s.isNullOrEmpty() && s[0].code > 3

private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)


/**
 * The BAGS room: the bags exactly as the game draws them.
 *
 * USER RULINGS (2026-09-27):
 *  - a GRID, one tile per STACK, in BAG ORDER, empty slots included. Sorting and a merged
 *    "clean" view may come later as toggles.
 *  - the game's own art everywhere: item icons from [Icons], and the slot well, quality frame,
 *    selected glow, quest border, coins, bag bar and search magnifier are the game's textures
 *    (Icons.ui). A missing texture falls back to a plain stand-in, never to an error.
 *  - v2 layout: a BAG BAR across the top (the backpack + each bag's own icon, a free-slot badge,
 *    tap to open / close that bag, all open by default); the grid on the left with a LINE between
 *    bags instead of a label; the ITEM PANEL on the right. For gear the panel shows what is worn
 *    in that slot underneath, with full stat differences.
 *  - v4, USER 2026-09-30 (the final word, SHEET_bags_v4 "proceed with recommended"): NO right panel. The grid takes the
 *    room's width (10 to a row); the highlighted item shows the game's own tooltip on the other half ([TipStack], the NPC
 *    windows' piece), its actions under it as glyph + word ([UiKit.tooltipActions], each a finger's button): A Move,
 *    X Use / Equip, Y More. GEAR ALWAYS COMPARES ("instead of having to hold a button"): the item and "Currently Equipped"
 *    side by side under the grid, or stacked on the half away from the cursor when the bags fill the room ("cover as
 *    little of the grid as possible"). With an item in the hand the tooltip's place shows it with Put Down / Cancel; More
 *    opens there too, unclipped.
 *  - the ONE action says what a right-click will actually do: Use / Equip / Read, or Sell / Deposit / Attach / Trade /
 *    Auction while that window is open in the game (the game's case, UI Art 09-30).
 *  - search DIMS what does not match, the way the game's own bag search does; a slot never moves.
 *
 * Pixel sizes are physical px on the 1240 x 1080 bottom screen, like every other room.
 */
class BagsView(c: Context) : FrameLayout(c), BLeadsHome {

    private val column = LinearLayout(c)
    private val head = Header(c)
    private val search = EditText(c).apply {
        hint = "Search"
        setHintTextColor(Theme.dim)
        setTextColor(Theme.text)
        textSize = 15f
        setSingleLine()
        inputType = InputType.TYPE_CLASS_TEXT
        setBackgroundColor(Theme.raised)
        setPadding(20, 14, 16, 14)
        UiKit.asBody(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        UiKit.asBody(search)             // the game's type (UI Art's fonts step)
    }
    private val state = State()
    private val bar = BagBar(c, state)
    private val grid = Grid(c, state)
    private val gridScroll = ScrollView(c).apply { addView(grid); isFillViewport = true }
    private val dragLayer = DragLayer(c, state)
    /** THE COMPANION'S CONTROLLER KEYBOARD (Chat's, reused as it is) for the search box: A on the strip's Search (Bags.lua BAR) */
    // no "(X) Quick chat" key (Chat's swap = false: Space takes its width); its Send key says Search (Chat's chat-s3 87fb90b4)
    private val kb = ChatKeyboard(c, swap = false).apply { visibility = View.GONE; sendWord = "Search" }
    private val ctlLayer = CtlLayer(c, state, grid, gridScroll, bar) { search }

    init {
        setBackgroundColor(Theme.ink)
        column.orientation = LinearLayout.VERTICAL
        super.addView(column, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // over the room: what the controller shows (menu, dialogs, the bind sheet, the button hints)
        super.addView(ctlLayer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // the search keyboard, along the room's bottom (it measures itself)
        super.addView(kb, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM))
        // on top of everything: the item under the finger while it is dragged
        super.addView(dragLayer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        kb.text = { search.text?.toString() ?: "" }
        kb.onText = { t -> search.setText(t); search.setSelection(t.length) }
        kb.onSend = { closeKeyboard(true) }
        kb.onClose = { closeKeyboard(true) }
        addView(head, lp(ViewGroup.LayoutParams.MATCH_PARENT, 92))
        addView(rule(Theme.bronze, c), lp(ViewGroup.LayoutParams.MATCH_PARENT, 1))

        // the game's own magnifier, inside the box (an overlay View sized MATCH_PARENT in a
        // wrap-content row stretched the row and squeezed the grid to zero height -- measured)
        Icons.ui("ui-searchbox-icon")?.let { b ->
            val d = android.graphics.drawable.BitmapDrawable(resources, b).apply { setBounds(0, 0, 36, 36) }
            search.setCompoundDrawables(d, null, null, null)
            search.compoundDrawablePadding = 16
        }
        val top = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 8, 24, 8)
        }
        top.addView(bar, lp(ViewGroup.LayoutParams.WRAP_CONTENT, 112))
        top.addView(search, lp(0, 80, 1f))
        addView(top, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(rule(Theme.bronzeDim, c), lp(ViewGroup.LayoutParams.MATCH_PARENT, 1))

        // v3 (the user 2026-09-30): no right panel, the grid takes the room's width; the tooltip and its actions ride over
        // the room in the CtlLayer, and move with a scrolled slot
        gridScroll.setOnScrollChangeListener { _, _, _, _, _ -> ctlLayer.invalidate() }
        addView(gridScroll, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { state.query = s.toString().trim().lowercase(); grid.invalidate() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
        })
        state.onChange = { layoutAll() }
        state.onSlotTap = { bag, slot -> touchSlot(bag, slot) }
        state.onCard = { col -> cardPress(col) }
        state.onEquipSlot = { id -> equipTap(id) }
        state.onShutTap = { id -> GameInput.tap(context, state.pad, "bmvq", 27) && GameInput.tap(context, state.pad, "bmvq", id) }
        state.onDragMove = { x, y -> dragMove(x, y) }
        state.onDragEnd = { x, y -> dragEnd(x, y) }
    }

    /** everything built in init goes into the column; only the drag layer sits on top of it */
    private fun addView(v: View, p: LinearLayout.LayoutParams) = column.addView(v, p)

    // ------------------------------------------------------------------ drag & drop
    // USER RULINGS 2026-09-28: bag slot -> bag slot (the worn-slot drags went with the character page, 2026-09-30).
    // A long press lifts the item; letting go over a target sends the addon ONE no-action "pick"
    // tap and then the action tap (the lock, amended for drags). The addon moves the item in one
    // handler on the second tap, so nothing is left on the game's cursor if a tap goes astray.

    private fun dragMove(rx: Float, ry: Float) {
        val d = state.drag ?: return
        d.x = rx; d.y = ry
        val (hb, hs) = grid.hitRaw(rx, ry) ?: (-1 to -1)
        state.hoverBag = hb; state.hoverSlot = hs
        dragLayer.invalidate(); grid.invalidate()
    }

    private fun dragEnd(rx: Float, ry: Float) {
        val d = state.drag ?: return
        dragMove(rx, ry)
        val pad = state.pad
        if (state.hoverBag >= 0 && state.canDropBag(state.hoverBag, state.hoverSlot) &&
            GameInput.tap(context, pad, "bmv${d.fromBag}", d.fromSlot - 1))
            GameInput.tap(context, pad, "bmv${state.hoverBag}", state.hoverSlot - 1)
        state.endDrag()
        dragLayer.invalidate(); layoutAll()
    }

    /** the lifted item, drawn under the finger */
    private class DragLayer(c: Context, private val st: State) : View(c) {
        private val p = Paints()
        private val at = IntArray(2)
        override fun onDraw(cv: Canvas) {
            val d = st.drag ?: return
            UiKit.asNumber(p.count, shadow = false)          // the count has its own black edge
            getLocationOnScreen(at)
            val s = 116f
            val x = d.x - at[0]; val y = d.y - at[1]
            // above the finger, so the finger does not hide what is being carried
            drawSlot(cv, RectF(x - s / 2, y - s - 30f, x + s / 2, y - 30f), d.item, p)
        }
    }

    // ------------------------------------------------------------------ what the controller shows
    // UI Art's BUILD SPEC part 2 (wow-companion-design/dialogs/SPEC.md), from the user's picks of
    // 2026-09-28 17:05: the Y menu by the slot, destroy and split as the game's dialog, the bind
    // sheet sliding down from the top, and the controller's words along the bottom. All of it is
    // drawn from bags.ctl; the addon holds the controller, so nothing here sends anything.

    private class CtlLayer(c: Context, private val st: State, private val grid: Grid, private val scroll: View,
                           private val bar: BagBar, private val searchBox: () -> View) : View(c) {
        private val at = IntArray(2); private val gat = IntArray(2); private val sat = IntArray(2)
        private var bindAt = -1L; private var unbindAt = -1L
        private var lastBind: JSONObject? = null; private var lastBindItem: JSONObject? = null
        private val tips = TipStack(this)
        private val gearFade = UiKit.TipFade()               // gear's pair has its own quarter second (not TipStack's)

        companion object {
            const val OPEN_MS = 220L; const val CLOSE_MS = 160L
            /** the bindable buttons: glyph + the name the sheet's line uses (Nintendo layout, as the NPC windows). NOT Start / Plus: the
             *  user's ruling 2026-10-09, Start opens the pause wheel from every room (Bags.lua no longer offers it) */
            val KEY_GLYPH = mapOf("PADRSHOULDER" to "rev_rshoulder", "PADRTRIGGER" to "rev_rtrigger",
                "PADLSTICK" to "gen_lstickin", "PADRSTICK" to "gen_rstickin", "PADBACK" to "rev_minus")
            fun word(s: String) = s.lowercase().replaceFirstChar { it.uppercase() }
            const val USE = -1           // a finger's Use / Equip: the slot's own secure cell (the game's right-click)
            const val MOVE = -2          // a finger's Move: the cursor to the shown slot, then A
            const val MORE = -3          // a finger's More: the cursor to the shown slot, then Y
            const val EQUIP = -4         // a finger's Equip on a BAG: the cursor to the shown slot, then bmvq 22 (= X: into the hand)
            const val EQUIP_OFF = -5     // the same cell, faint (a fight): nothing
            const val PRESS_MIN_MS = 160L                       // a press stays visibly in at least this long (Interact's recipe)
            val PRESS_KEYS = setOf("PAD1", "PAD2", "PAD3", "PAD4")
        }

        private fun mode(): String? = if (st.ctlOn) st.ctl?.optString("mode") else null
        private fun curItem(): JSONObject? = st.ctlCur()?.let { st.items[st.key(it.first, it.second)] }
        /** the slot the tooltip is about: the controller's cursor, or by touch alone the selected slot */
        private fun shownSlot(): Pair<Int, Int>? = st.ctlCur() ?: if (st.selBag >= 0) st.selBag to st.selSlot else null
        private fun iconOf(it: JSONObject?) = Icons.getOrUnknown(it?.let { o -> if (o.has("icon")) o.optInt("icon") else null })
        private fun colourOf(it: JSONObject?) = it?.let { o -> if (o.has("quality")) Theme.quality(o.optInt("quality")) else null } ?: Color.WHITE

        // what a finger can press here, as drawn last: (rect, what). what = a bmvq col (31 A, 33 B, 34 / 35 left / right,
        // 19-21 the menu's rows, 39 drop what is held) or USE / MOVE / MORE (the tooltip's cells)
        private val hits = ArrayList<Pair<RectF, Int>>()
        // where this layer takes a finger (the action plates, the menu); elsewhere the room below gets it
        private val zones = ArrayList<RectF>()
        private var useAt: Pair<Int, Int>? = null           // the slot the drawn Use cell is about

        // ---- PRESS FEEDBACK (the user after b100: "Popups working, all other context buttons should have similar feedback";
        // Interact's recipe): a touched button stays pushed in (the kit's press) at least PRESS_MIN_MS; the controller's A / B / X / Y push
        // in the button drawn for that key AT ONCE (padKey, from Home's reader), while Bags holds the controller
        private val held = HashSet<String>()
        private val downUntil = HashMap<String, Long>()
        private fun clock() = android.os.SystemClock.uptimeMillis()
        fun padKey(k: String, down: Boolean) {
            if (k !in PRESS_KEYS) return
            if (down) {
                // the controller is Bags' (or about to be: the hold was asked a moment ago and its document is on its way, b116)
                if (!st.ctlOn && !st.holdAsked()) return
                held += k; downUntil[k] = clock() + PRESS_MIN_MS
            } else if (!held.remove(k)) return
            invalidate(); postInvalidateDelayed(PRESS_MIN_MS + 10L)
        }
        private fun linger(k: String?) {
            if (k == null) return
            downUntil[k] = clock() + PRESS_MIN_MS
            invalidate(); postInvalidateDelayed(PRESS_MIN_MS + 10L)
        }
        private fun isDown(k: String?) = k != null && (k in held || (downUntil[k] ?: 0L) > clock())
        /** a press is showing now (the tooltip's fade-in must not hide it: the plate goes to full at once) */
        private fun anyDown() = pressX >= 0f || held.isNotEmpty() || downUntil.values.any { it > clock() }
        /** the controller key a drawn button stands for (its glyph) */
        private fun keyOf(what: Int): String? = when (what) {
            MOVE, 31 -> "PAD1"; USE, EQUIP -> "PAD3"; MORE, 32 -> "PAD4"; 33, 39 -> "PAD2"; else -> null
        }
        /** which cell is down: the one under the finger, else the one whose key is pressed / still lingering */
        private fun downIn(cells: List<RectF>, keys: List<String?>): Int {
            val f = pressedIn(cells)
            return if (f >= 0) f else keys.indexOfFirst { k -> isDown(k) }
        }
        private val pPlate = Paint().apply { color = Color.argb(250, 6, 6, 10) }
        /** the plate's cell [i] pushed in (UiKit.pressed over the kit's own down cell, inside that cell only: tooltipActions does not push) */
        private fun pushCell(c: Canvas, box: RectF, acts: List<Pair<String?, String>>, cells: List<RectF>, i: Int) {
            if (i !in cells.indices) return
            val r = cells[i]
            c.save(); c.clipRect(r)
            c.drawRect(r, pPlate)
            UiKit.pressed(c, r, true) { UiKit.tooltipActions(c, box, acts, 255, i) }
            c.restore()
        }
        /** one of the game's red buttons, DOWN while touched / pressed (the kit pushes a DOWN button in itself) */
        private fun button(c: Canvas, r: RectF, label: String, glyph: String, key: String) {
            val down = pressedIn(listOf(r)) == 0 || isDown(key)
            UiKit.button(c, r, label, glyph, if (down) UiKit.Button.DOWN else UiKit.Button.UP)
        }
        private var owning = false
        private var pressX = -1f; private var pressY = -1f
        private var tapAt = 0L

        /** a dialog's cover takes every touch; the plates and the menu take those inside them; their cells answer */
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val m = mode()
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                val blocking = m == "destroy" || m == "split" || m == "bind" || unbindAt >= 0
                owning = blocking || zones.any { it.contains(e.x, e.y) }
                if (owning) { pressX = e.x; pressY = e.y; invalidate() }
            }
            if (!owning) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_UP -> {
                    val now = android.os.SystemClock.uptimeMillis()
                    val hit = hits.firstOrNull { it.first.contains(e.x, e.y) && it.first.contains(pressX, pressY) }
                    if (hit != null && now - tapAt > 400) { tapAt = now; act(hit.second) }
                    owning = false; pressX = -1f; pressY = -1f; invalidate()
                }
                MotionEvent.ACTION_CANCEL -> { owning = false; pressX = -1f; pressY = -1f; invalidate() }
            }
            return true
        }

        /** one touch = one press for the addon (Move / More on the slot the tooltip shows), or Use = the game's right-click */
        private fun act(what: Int) {
            linger(keyOf(what))
            when (what) {
                USE -> useAt?.let { (bag, slot) -> GameInput.tap(context, st.pad, "bag$bag", slot - 1) }
                MOVE -> st.onCard(31)                        // the cursor goes to the shown slot first (BagsView.cardPress)
                MORE -> st.onCard(32)
                EQUIP -> st.onCard(22)
                EQUIP_OFF -> {}
                else -> GameInput.tap(context, st.pad, "bmvq", what)
            }
        }

        /** the index of the cell under the finger while it is down (the plate's pressed look) */
        private fun pressedIn(cells: List<RectF>) = if (pressX < 0f) -1 else cells.indexOfFirst { it.contains(pressX, pressY) }

        override fun onDraw(c: Canvas) {
            hits.clear(); zones.clear(); useAt = null
            val m = mode()
            val now = android.os.SystemClock.uptimeMillis()
            // the bind sheet's slide: in over 220 ms, out over 160 ms (drawn from its last state)
            if (m == "bind") {
                if (bindAt < 0) { bindAt = now; unbindAt = -1 }
                lastBind = st.ctl; lastBindItem = curItem()
            } else if (bindAt >= 0) { bindAt = -1; unbindAt = now }
            getLocationOnScreen(at); grid.getLocationOnScreen(gat); scroll.getLocationOnScreen(sat)
            val gx = (gat[0] - at[0]).toFloat(); val gy = (gat[1] - at[1]).toFloat()
            // the tooltip's room: the grid's visible part
            val top = (sat[1] - at[1]).toFloat() + 8f
            val bottom = (sat[1] - at[1] + scroll.height).toFloat() - 8f
            val screen = RectF(0f, 0f, width.toFloat(), height.toFloat())
            when (m) {
                null, "grid" -> {
                    if (st.onBar) barCursor(c)
                    else if (st.ctlPick() != null) hand(c, gx, gy, top, bottom) else tooltip(c, gx, gy, top, bottom)
                    carried(c, gx, gy)
                }
                "menu" -> menu(c, gx, gy, top, bottom)
                "equip", "equipping", "bindask" -> equip(c, m ?: "")
                "barmenu" -> barMenu(c)
                "destroy" -> destroy(c, screen)
                "split" -> split(c, screen)
            }
            val f = when {
                bindAt >= 0 -> { val t = ((now - bindAt) / OPEN_MS.toFloat()).coerceIn(0f, 1f); 1f - (1f - t) * (1f - t) * (1f - t) }
                unbindAt >= 0 -> { val t = ((now - unbindAt) / CLOSE_MS.toFloat()).coerceIn(0f, 1f); (1f - t) * (1f - t) }
                else -> 0f
            }
            if (bindAt >= 0 || unbindAt >= 0) {
                lastBind?.let { bind(c, screen, it, lastBindItem, f) }
                val moving = (bindAt >= 0 && now - bindAt < OPEN_MS) || (unbindAt >= 0 && now - unbindAt < CLOSE_MS)
                if (moving) postInvalidateOnAnimation()
                else if (unbindAt >= 0) { unbindAt = -1; lastBind = null }
            }
        }

        /** the slot's square in this layer's coordinates (null = scrolled out of sight) */
        private fun slotBox(bag: Int, slot: Int, gx: Float, gy: Float): RectF? =
            grid.tileRect(bag, slot)?.let { t -> RectF(t.left + gx, t.top + gy, t.right + gx, t.bottom + gy) }

        /**
         * WHERE THE TOOLTIP GOES: BY THE CURSOR (the user, 2026-10-09: "tooltip should stay by the cursor when looking at items in the
         * bag"; it was on the other half of the room, SHEET_bags_v3): beside the cursor's tile, on the side with room (right first),
         * two thirds of a half wide, level with the cursor's row; the compare ("Currently Equipped") beyond it on the same side when it
         * fits, else on the cursor's other side. The compare is [cmpScale] of the item's width (the game's smaller compare
         * tooltip, [UiKit.COMPARE_SCALE]). Returns (x, width, compare x).
         */
        private fun place(cur: RectF, cmpScale: Float = 1f): Triple<Float, Float, Float> {
            val half = width / 2f
            val tw = (half - 30f) * 0.9f
            val cw = tw * cmpScale
            val gap = 18f
            val right = cur.right + gap; val left = cur.left - gap - tw
            val onRight = right + tw <= width - 16f || left < 16f
            val x = (if (onRight) right else left).coerceIn(16f, maxOf(16f, width - tw - 16f))
            val cmpX = if (onRight) { if (x + tw + 6f + cw <= width - 16f) x + tw + 6f else cur.left - gap - cw }
                       else { if (x - 6f - cw >= 16f) x - 6f - cw else cur.right + gap }
            return Triple(x, tw, cmpX.coerceIn(16f, maxOf(16f, width - cw - 16f)))
        }

        /** an addon without Interact's WC.tipOf: the item's own name and type, so the tooltip is never empty */
        private fun fallbackTip(key: String, it: JSONObject): JSONObject {
            val lines = JSONArray().put(JSONObject().put("t", it.optString("name")))
            val sub = listOf(it.optString("itype"), it.optString("isub")).filter { s -> real(s) }.joinToString(" · ")
            if (sub.isNotEmpty()) lines.put(JSONObject().put("t", sub))
            val n = it.optInt("count", 1)
            if (n > 1) lines.put(JSONObject().put("t", "Stack of $n"))
            return JSONObject().put("key", key).put("lines", lines)
        }

        /** the cells under a tooltip: A Move, X the item's action (Use / Equip / ...), Y More; glyphs while Bags holds the pad */
        private fun actions(it: JSONObject, bag: Int, slot: Int): Pair<List<Pair<String?, String>>, List<Int>> {
            val glyphs = st.ctlOn
            val acts = ArrayList<Pair<String?, String>>(); val what = ArrayList<Int>()
            val rows = st.pad?.optJSONObject("rows")
            val moves = rows?.has("bmvq") == true
            if (moves) { acts.add((if (glyphs) "rev_a" else null) to "Move"); what.add(MOVE) }
            actionOf(it, st.ctx)?.also { (word, allowed) ->
                if (st.ctx == null && isBag(it) && moves) {
                    // a BAG: Equip puts it in the hand and the cursor goes up to the bag bar (Bags.lua EQ, the user 2026-10-06), not
                    // the game's right-click (that fills only a free bag slot); faint in a fight (the game changes no equipment then)
                    acts.add((if (glyphs) "rev_x" else null) to word); what.add(if (st.combat) EQUIP_OFF else EQUIP)
                } else if (allowed && rows?.has("bag$bag") == true) {
                    acts.add((if (glyphs) "rev_x" else null) to word); what.add(USE); useAt = bag to slot
                }
            }
            if (moves) { acts.add((if (glyphs) "rev_y" else null) to "More"); what.add(MORE) }
            return acts to what
        }

        /** draw the cells under [box] at [alpha] and make them the finger's buttons */
        private fun plate(c: Canvas, box: RectF, acts: List<Pair<String?, String>>, what: List<Int>, alpha: Int): List<RectF> {
            if (acts.isEmpty()) return emptyList()
            val probe = UiKit.tooltipActions(c, box, acts, alpha = 0)           // the cells, to know which one is pressed
            val dn = downIn(probe, what.map { w -> if (w == EQUIP_OFF) null else keyOf(w) })
            // b116 ("the button push is delayed a moment"): a press during the tooltip's fade-in showed nothing until the room
            // changed; a pressed plate is at full strength at once
            val a = if (dn >= 0) 255 else alpha
            val cells = UiKit.tooltipActions(c, box, acts, a)
            if (a >= 255) pushCell(c, box, acts, cells, dn)
            if (a > 0) {
                for ((i, r) in cells.withIndex()) if (what[i] == EQUIP_OFF) faint(c, r) else hits.add(r to what[i])
                zones.add(RectF(cells.first().left, cells.first().top, cells.last().right, cells.last().bottom))
            }
            return cells
        }

        /** the highlighted item's tooltip and its actions under it; gear also shows what it would replace, always */
        private fun tooltip(c: Canvas, gx: Float, gy: Float, top: Float, bottom: Float) {
            // THE GAME'S HOVER WAIT every time (b-bag-tooltip-instant): a frame with no tooltip forgets both fades, so coming
            // back to an item (from an empty slot, the bar, a menu, a non-gear item) waits out the quarter second again
            if (!tooltipShown(c, gx, gy, top, bottom)) { tips.forget(); gearFade.alpha(null) }
        }
        private fun tooltipShown(c: Canvas, gx: Float, gy: Float, top: Float, bottom: Float): Boolean {
            val (bag, slot) = shownSlot() ?: return false
            val it = st.items[st.key(bag, slot)] ?: return false
            if (st.viewClean) return false                    // the merged view: a tile is many slots, no one tooltip
            val cur = slotBox(bag, slot, gx, gy) ?: return false
            val name = it.optString("name").takeIf { s -> real(s) } ?: "Loading…"
            val q = if (it.has("quality")) it.optInt("quality") else 1
            val key = "bag $bag $slot ${it.optInt("id")}"
            val tip = st.ctl?.optJSONObject("tip")?.takeIf { j -> st.ctlOn && j.optString("key") == key } ?: fallbackTip(key, it)
            val (acts, what) = actions(it, bag, slot)
            val cmp = tip.optJSONArray("cmp")
            if (cmp != null && cmp.length() > 0) { tips.forget(); gear(c, cur, name, q, tip, cmp, acts, what, gy, top, bottom); return true }
            gearFade.alpha(null)
            val (tx, tw, cmpX) = place(cur)
            // not gear: TipStack, its own Compare hint off (Bags compares by itself); the plate's 62 px stay in the room
            val d = tips.draw(c, tx, maxOf(top, cur.top), tw, name, q, tip, top, bottom - 64f, cmpX, false, 0, hint = false)
            if (d.alpha > 0 || anyDown()) plate(c, RectF(d.box.left, d.box.top, d.box.right, d.bottom), acts, what, d.alpha)
            return true
        }

        /**
         * GEAR (the user: "it should just always auto compare"; UI Art's rule, SHEET_bags_v4: "cover as little of the grid
         * as possible"). The item (its cells under it) and each worn item it would replace ("Currently Equipped", the game's
         * stat-change lines last):
         *  - the bags leave room under the grid: SIDE BY SIDE there, each (w - 56) / 2 wide, rising only as far as they must;
         *  - the bags fill the room (the band under the grid is less than half the pair): STACKED on the half away from the
         *    cursor, (half - 30) wide, the item and its cells, then Currently Equipped 6 px under.
         */
        private fun gear(c: Canvas, cur: RectF, name: String, q: Int, tip: JSONObject, cmp: JSONArray,
                         acts: List<Pair<String?, String>>, what: List<Int>, gy: Float, top: Float, bottom: Float) {
            val fade = gearFade.alpha(tip.optString("key"))
            if (gearFade.animating) postInvalidateOnAnimation()
            val lines = tip.optJSONArray("lines")
            val first = lines?.optJSONObject(0)
            val body = TipStack.lines(lines, first != null && first.optString("t") == name)
            data class Eq(val name: String, val colour: Int, val lines: List<UiKit.TipLine>)
            val eq = (0 until cmp.length()).mapNotNull { n ->
                val el = cmp.optJSONObject(n)?.optJSONArray("lines") ?: return@mapNotNull null
                val head = el.optJSONObject(0) ?: return@mapNotNull null
                Eq(TipStack.strip(head.optString("t")), TipStack.colour(head.optString("c").takeIf { s -> s.isNotEmpty() }, Color.WHITE),
                    TipStack.lines(el, true))
            }
            // BY THE CURSOR too (the user, 2026-10-09): the item beside the cursor's tile with its cells under it, Currently Equipped
            // beside it (place's compare x), both level with the cursor's row, lifted only as far as they must to stay in the room
            // Currently Equipped at the game's compare size (b-bag-tooltip-size: it was the item's own full size): its shopping
            // tooltip's smaller fonts, and as much narrower
            val sc = UiKit.COMPARE_SCALE
            val plateH = if (acts.isEmpty()) 0f else 62f
            val (x, tw, _) = place(cur, sc)
            val cw = tw * sc
            val itemH = UiKit.tooltip(c, 0f, -9999f, tw, name, Theme.quality(q), body, alpha = 0).height() + plateH
            val eqH = eq.sumOf { e ->
                UiKit.tooltip(c, 0f, -9999f, cw, e.name, e.colour, e.lines, header = "Currently Equipped", alpha = 0, scale = sc).height().toDouble() + 6.0
            }.toFloat()
            // CURRENTLY EQUIPPED NEVER OVER THE CURSOR (Thor batch2, the user: "I dont want it to cover the cursor. it would be nice below
            // or beside the text of the Item I am looking at"): beside the item's tooltip on the side AWAY from the cursor, as the game's
            // compare stands beside its main tooltip; where that side has no room, under the item's tooltip and its cells
            val itemRight = x >= cur.right
            val besideX = if (itemRight) x + tw + 6f else x - 6f - cw
            val beside = besideX >= 16f && besideX + cw <= width - 16f
            val cmpX = if (beside) besideX else x
            val needH = if (beside) maxOf(itemH, eqH) else itemH + 6f + eqH
            val y0 = maxOf(top - 90f, minOf(maxOf(top, cur.top), bottom - needH))   // up over the bag bar only if it must
            // a press on the pair's OWN cells shows it at once (b116); any other press, or the finger choosing the slot, waits
            // out the quarter second like the game's hover (it was any press at all, so a tapped gear item showed at once)
            val probe = UiKit.tooltip(c, x, y0, tw, name, Theme.quality(q), body, alpha = 0)
            val pressed = acts.isNotEmpty() &&
                downIn(UiKit.tooltipActions(c, probe, acts, alpha = 0), what.map { w -> if (w == EQUIP_OFF) null else keyOf(w) }) >= 0
            val a = if (pressed) 255 else fade
            if (a <= 0) return                                // the quarter second: measured, nothing shown yet
            val box = UiKit.tooltip(c, x, y0, tw, name, Theme.quality(q), body, alpha = a)
            plate(c, box, acts, what, a)
            var y = if (beside) y0 else y0 + itemH + 6f
            for (e in eq) y = UiKit.tooltip(c, cmpX, y, cw, e.name, e.colour, e.lines, header = "Currently Equipped", alpha = a, scale = sc).bottom + 6f
        }

        private val bat = IntArray(2)
        private val pFaint = Paint().apply { color = Color.argb(160, 6, 6, 10) }
        /** a cell that cannot be pressed now: dimmed over the kit's plate */
        private fun faint(c: Canvas, r: RectF) = c.drawRect(r.left + 4f, r.top + 4f, r.right - 4f, r.bottom - 4f, pFaint)

        /**
         * EQUIP A BAG ON THE BAG BAR (the user's pick 2026-10-06, EQUIP_A_bagbar): the bag in the hand rides on the chosen bag slot
         * (the kit's slotFocus + carried), the game-style tooltip under the bar says what that slot would do, and its plate is
         * A Equip Here (faint where the game would refuse) / B Cancel. All of it from bags.ctl.eq (Bags.lua EQ); "equipping" = the
         * game is working, "bindask" = the game's own "bind it to you?" has A / B (Bags passes them to the game).
         */
        private fun equip(c: Canvas, m: String) {
            val eq = st.ctlEq() ?: return
            val item = st.items[st.key(eq.optInt("bag", -1), eq.optInt("slot", -1))]
            val move = eq.optBoolean("move")                  // a worn bag to another slot (its strip More): it is not in the grid
            val newName = if (move) (st.bags.firstOrNull { b -> b.id == eq.optInt("from", -1) }?.name ?: "bag")
                          else item?.optString("name")?.takeIf { s -> real(s) } ?: "bag"
            val tg = eq.optJSONArray("targets")?.optJSONObject(eq.optInt("at", 1) - 1)
            val slotId = if (m == "equip") (tg?.optInt("bag", -1) ?: -1) else eq.optInt("to", -1)
            bar.getLocationOnScreen(bat)
            val dx = (bat[0] - at[0]).toFloat(); val dy = (bat[1] - at[1]).toFloat()
            val r = bar.slotRect(slotId)?.let { b -> RectF(b.left + dx, b.top + dy, b.right + dx, b.bottom + dy) }
            val inHand = if (move) Icons.getOrUnknown(if (eq.has("icon")) eq.optInt("icon") else null) else iconOf(item)
            if (r != null) { UiKit.slotFocus(c, r); UiKit.carried(c, inHand, r) }
            val w = 540f
            val x = ((r?.left ?: 48f) - 10f).coerceIn(16f, maxOf(16f, width - w - 16f))
            val y = (r?.bottom ?: (dy + bar.height)) + 22f
            val lines = ArrayList<UiKit.TipLine>()
            var title = newName; var titleColour = colourOf(item)
            var acts: List<Pair<String?, String>> = emptyList(); var ok = false
            when {
                m == "equipping" -> lines.add(UiKit.TipLine("Equipping\u2026", Theme.dim))
                m == "bindask" -> {
                    lines.add(UiKit.TipLine("The game asks: bind it to you?"))
                    lines.add(UiKit.TipLine("A yes, B no (the game's own question)", Theme.dim))
                }
                tg != null -> {
                    // SHORT (the user after b84: "listing the items currently in the bags is too much, remove that"): the slot as
                    // the title, then only what the player must know: a SPECIAL new bag's kind (a profession bag or quiver takes
                    // only its own kind of item), or why the game would refuse
                    ok = tg.optBoolean("ok")
                    val old = tg.optString("name").takeIf { s -> real(s) }
                    title = if (move) (if (tg.has("id")) "Swap with " + (old ?: "this bag") else "Move here")
                            else if (tg.has("id")) "Replace " + (old ?: "this bag") else "Empty bag slot"
                    titleColour = Color.WHITE
                    val kind = eq.optString("kind").takeIf { s -> real(s) }
                    if (!move && eq.optInt("fam") != 0 && kind != null) lines.add(UiKit.TipLine("$kind: holds only its own kind of items", Theme.dim))
                    when (tg.optString("why")) {
                        "too-small" -> lines.add(UiKit.TipLine("Too small for what's inside: empty it first", Theme.unusable))
                        "wrong-type" -> lines.add(UiKit.TipLine("What's inside can't go in a ${kind ?: "bag like this"}: empty it first",
                            Theme.unusable))
                        "no-room" -> lines.add(UiKit.TipLine("No room to take the $newName out of it", Theme.unusable))
                        "combat" -> lines.add(UiKit.TipLine("Not in a fight", Theme.unusable))
                        "no-slot" -> lines.add(UiKit.TipLine("This bag slot can't be used", Theme.unusable))
                    }
                    val glyphs = st.ctlOn
                    acts = listOf((if (glyphs) "rev_a" else null) to (if (move) "Move Here" else "Equip Here"),
                        (if (glyphs) UiKit.backGlyph() else null) to "Cancel")
                }
            }
            val box = UiKit.tooltip(c, x, y, w, title, titleColour, lines)
            zones.add(RectF(box))
            if (acts.isNotEmpty()) {
                val probe = UiKit.tooltipActions(c, box, acts, alpha = 0)
                val cells = UiKit.tooltipActions(c, box, acts, 255)
                pushCell(c, box, acts, cells, downIn(probe, listOf(if (ok) "PAD1" else null, "PAD2")).takeIf { i -> ok || i != 0 } ?: -1)
                if (ok) hits.add(cells[0] to 31) else faint(c, cells[0])          // A: Equip Here
                hits.add(cells[1] to 33)                                          // B: Cancel
                zones.add(RectF(cells[0].left, cells[0].top, cells[1].right, cells[1].bottom))
            }
        }

        private val sat2 = IntArray(2)
        /**
         * THE CURSOR ON THE ROOM'S TOP STRIP (the user, 2026-10-09: "I am not able to move my cursor up to the actual bags to open or
         * close them, same with the cleanup button and search bar"; Bags.lua BAR): the gold box on the bag slot / Clean Up / the search
         * box under it (bags.ctl.bar), and a small tooltip under the strip saying what A does there (its one cell also a finger's A).
         */
        private fun barCursor(c: Canvas) {
            val it = st.ctl?.optJSONObject("bar") ?: return
            bar.getLocationOnScreen(bat)
            val dx = (bat[0] - at[0]).toFloat(); val dy = (bat[1] - at[1]).toFloat()
            fun inLayer(b: RectF) = RectF(b.left + dx, b.top + dy, b.right + dx, b.bottom + dy)
            val r: RectF
            val title: String; val word: String
            when (it.optString("k")) {
                "bag" -> {
                    val id = it.optInt("id", -1)
                    r = bar.slotRect(id)?.let { b -> inLayer(b) } ?: return
                    UiKit.slotFocus(c, r)
                    title = st.bags.firstOrNull { b -> b.id == id }?.name ?: if (id == 0) "Backpack" else "Bag"
                    word = if (st.shut(id)) "Open" else "Close"
                }
                "sort" -> {
                    r = inLayer(bar.sortRect())
                    UiKit.slotFocus(c, r)
                    title = "Clean Up Bags"; word = "Clean Up"
                }
                "search" -> {
                    val v = searchBox(); v.getLocationOnScreen(sat2)
                    r = RectF((sat2[0] - at[0]).toFloat(), (sat2[1] - at[1]).toFloat(),
                        (sat2[0] - at[0] + v.width).toFloat(), (sat2[1] - at[1] + v.height).toFloat())
                    UiKit.focus(c, r)
                    title = "Search"; word = "Search"
                }
                else -> return
            }
            val w = 360f
            val x = r.left.coerceIn(16f, maxOf(16f, width - w - 16f))
            val box = UiKit.tooltip(c, x, r.bottom + 22f, w, title, Color.WHITE, emptyList())
            zones.add(RectF(box))
            // a worn bag (not the backpack) has its own More (Bags.lua BAR: Move to Another Slot / Unequip; the user, 2026-10-09)
            val worn = it.optString("k") == "bag" && it.optInt("id", 0) != 0
            val acts = listOf((if (st.ctlOn) "rev_a" else null) to word) +
                (if (worn) listOf((if (st.ctlOn) "rev_y" else null) to "More") else emptyList())
            val probe = UiKit.tooltipActions(c, box, acts, alpha = 0)
            val cells = UiKit.tooltipActions(c, box, acts, 255)
            pushCell(c, box, acts, cells, downIn(probe, if (worn) listOf("PAD1", "PAD4") else listOf("PAD1")))
            hits.add(cells[0] to 31)
            if (worn) hits.add(cells[1] to 32)
            zones.add(RectF(cells.first().left, cells.first().top, cells.last().right, cells.last().bottom))
        }

        /** a worn bag's own More (bags.ctl.barmenu): Move to Another Slot / Unequip under its slot, a row the game would refuse says why */
        private fun barMenu(c: Canvas) {
            val bm = st.ctl?.optJSONObject("barmenu") ?: return
            val id = bm.optInt("bag", -1)
            bar.getLocationOnScreen(bat)
            val dx = (bat[0] - at[0]).toFloat(); val dy = (bat[1] - at[1]).toFloat()
            val r = bar.slotRect(id)?.let { b -> RectF(b.left + dx, b.top + dy, b.right + dx, b.bottom + dy) } ?: return
            UiKit.slotFocus(c, r)
            val ok = bm.optJSONArray("ok"); val why = bm.optJSONArray("why")
            fun reason(i: Int) = when (why?.optString(i)) {
                "not-empty" -> " (empty it first)"; "no-room" -> " (no room in your bags)"; "combat" -> " (not in a fight)"
                "no-slot" -> " (no other slot)"; else -> ""
            }
            val labels = listOf("Move to Another Slot", "Unequip")
            val w = 460f; val h = 56f + labels.size * 96f + 24f
            val x = (r.left - 10f).coerceIn(16f, maxOf(16f, width - w - 16f))
            val box = RectF(x, r.bottom + 22f, x + w, r.bottom + 22f + h)
            val inner = UiKit.menu(c, box, solid = true)
            val name = st.bags.firstOrNull { b -> b.id == id }?.name ?: "Bag"
            c.drawText(name, inner.left + 16f, inner.top + 34f, UiKit.bodyPaint(26f, Color.WHITE))
            val sel = bm.optInt("at", 1)
            for ((i, label) in labels.withIndex()) {
                val rr = RectF(inner.left, inner.top + 56f + i * 96f, inner.right, inner.top + 56f + i * 96f + 92f)
                val on = ok?.optBoolean(i, false) == true
                UiKit.pressed(c, rr, on && pressedIn(listOf(rr)) == 0) {
                    UiKit.menuRow(c, rr, label + (if (on) "" else reason(i)), sel == i + 1, enabled = on, size = 28f)
                }
                if (on) hits.add(RectF(rr) to (19 + i))
            }
            zones.add(RectF(box))
            val hy = box.bottom + 40f
            val hs = UiKit.hints(c, box.right - 16f, hy, "rev_a" to "Choose", UiKit.backGlyph() to "Back")
            val back = RectF((hs + box.right) / 2f, hy - 36f, box.right, hy + 36f)
            hits.add(back to 33); zones.add(back)
        }

        /** an item in the hand (A, Move): in the tooltip's place, what it is ("Moving 7") with Put Down (A) / Cancel (B) */
        private fun hand(c: Canvas, gx: Float, gy: Float, top: Float, bottom: Float) {
            val pk = st.ctlPick() ?: return
            val cur = st.ctlCur()?.let { slotBox(it.first, it.second, gx, gy) } ?: return
            val it = st.items[st.key(pk.first, pk.second)] ?: return
            val (tx, tw, _) = place(cur)
            val name = it.optString("name").takeIf { s -> real(s) } ?: "…"
            val lines = listOf(UiKit.TipLine("Moving ${it.optInt("count", 1)}"))
            val h = UiKit.tooltip(c, tx, 0f, tw, name, colourOf(it), lines, alpha = 0).height()
            val box = UiKit.tooltip(c, tx, minOf(maxOf(top, cur.top), bottom - h - 64f), tw, name, colourOf(it), lines)
            val acts = listOf("rev_a" to "Put Down", UiKit.backGlyph() to "Cancel")
            val probe = UiKit.tooltipActions(c, box, acts, alpha = 0)
            val cells = UiKit.tooltipActions(c, box, acts, 255)
            pushCell(c, box, acts, cells, downIn(probe, listOf("PAD1", "PAD2")))
            // Cancel = col 39 (drop what is held, ONLY that): B with nothing held would leave the room, a late tap must not
            hits.add(cells[0] to 31); hits.add(cells[1] to 39)
            zones.add(RectF(cells[0].left, cells[0].top, cells[1].right, cells[1].bottom))
        }

        /** the item in the hand, on the focused slot's lower right corner, drawn last (UiKit.carried) */
        private fun carried(c: Canvas, gx: Float, gy: Float) {
            val pk = st.ctlPick() ?: return
            val cur = st.ctlCur() ?: return
            val held = st.items[st.key(pk.first, pk.second)] ?: return
            val t = slotBox(cur.first, cur.second, gx, gy) ?: return
            UiKit.carried(c, iconOf(held), t, held.optInt("count", 1))
        }

        /**
         * Y: the item's menu IN THE TOOLTIP'S PLACE (SHEET_bags_v3 / v4; the user 2026-09-28: "not as a tooltip that clips off
         * the screen": it is lifted to fit), the cursor's glow stays on the slot. Split Stack / Bind to a Button / Destroy,
         * A Choose / B Back under it.
         */
        private fun menu(c: Canvas, gx: Float, gy: Float, top: Float, bottom: Float) {
            val (bag, slot) = st.ctlCur() ?: return
            val cur = slotBox(bag, slot, gx, gy) ?: return
            val (tx, tw, _) = place(cur)
            val rows = listOf("split" to "Split Stack", "bind" to "Bind to a Button", "destroy" to "Destroy")
            val h = 56f + rows.size * 96f + 24f
            val y0 = minOf(maxOf(top, cur.top), bottom - h - 64f)
            val box = RectF(tx, y0, tx + tw, y0 + h)
            val inner = UiKit.menu(c, box, solid = true)
            val it = curItem()
            val np = UiKit.bodyPaint(26f, colourOf(it))
            val shown = android.text.TextUtils.ellipsize(it?.optString("name") ?: "", android.text.TextPaint(np), inner.width() - 32f,
                android.text.TextUtils.TruncateAt.END).toString()
            c.drawText(shown, inner.left + 16f, inner.top + 34f, np)
            val usable = st.ctl?.optJSONArray("usable")
            val sel = st.ctl?.optString("menu")
            for ((i, row) in rows.withIndex()) {
                val r = RectF(inner.left, inner.top + 56f + i * 96f, inner.right, inner.top + 56f + i * 96f + 92f)
                val on = usable?.optBoolean(i, true) ?: true
                if (on) hits.add(RectF(r) to (19 + i))
                UiKit.pressed(c, r, on && pressedIn(listOf(r)) == 0) {
                    UiKit.menuRow(c, r, row.second, sel == row.first, if (row.first == "destroy") Theme.unusable else Theme.text,
                        enabled = on, size = 28f)
                }
            }
            zones.add(RectF(box))
            val hy = box.bottom + 40f
            val hs = UiKit.hints(c, box.right - 16f, hy, "rev_a" to "Choose", UiKit.backGlyph() to "Back")
            // the "Back" words: the right half of the hint row
            val back = RectF((hs + box.right) / 2f, hy - 36f, box.right, hy + 36f)
            hits.add(back to 33); zones.add(back)
        }

        /** THE game dialog: Yes (A) left, No (B) right, as the game has it; for rare and better, why not, and Okay */
        private fun destroy(c: Canvas, screen: RectF) {
            UiKit.cover(c, screen)
            val w = screen.width(); val cx = w / 2f
            val it = curItem()
            val rare = st.ctl?.optBoolean("rare") == true
            val bh = if (rare) 500f else 440f
            val r = RectF(cx - 520f, (screen.height() - bh) / 2f, cx + 520f, (screen.height() + bh) / 2f)
            UiKit.dialog(c, r)
            UiKit.tex("alert")?.let { c.drawBitmap(it, null, RectF(r.left + 66f, r.top + 60f, r.left + 186f, r.top + 180f), null) }
            val text = UiKit.bodyPaint(if (rare) 36f else 42f, Color.WHITE).apply { textAlign = Paint.Align.CENTER }
            if (rare) {
                c.drawText("This item cannot be destroyed", cx + 70f, r.top + 104f, text)
                c.drawText("with the controller.", cx + 70f, r.top + 152f, text)
            } else c.drawText("Do you want to destroy", cx + 70f, r.top + 118f, text)
            val py = r.top + if (rare) 184f else 150f
            UiKit.itemPlate(c, RectF(cx - 200f, py, cx + 340f, py + 108f),
                iconOf(it), it?.optString("name") ?: "", colourOf(it), count = it?.optInt("count", 1) ?: 0)
            val by = r.bottom - 60f - 111f
            if (rare) {
                val ok = RectF(cx - 220f, by, cx + 220f, by + 111f)
                button(c, ok, "Okay", "rev_a", "PAD1"); hits.add(ok to 31)
            } else {
                // USER RULING 2026-09-28 17:50 (relayed): where the game has them, Yes (A) left, No (B) right
                val yes = RectF(cx - 14f - 380f, by, cx - 14f, by + 111f); val no = RectF(cx + 14f, by, cx + 14f + 380f, by + 111f)
                button(c, yes, "Yes", "rev_a", "PAD1"); button(c, no, "No", "rev_b", "PAD2")
                hits.add(yes to 31); hits.add(no to 33)
            }
        }

        /** Split Stack: the D-pad sets the amount (the addon keeps it), A = Okay, B = Cancel */
        private fun split(c: Canvas, screen: RectF) {
            UiKit.cover(c, screen)
            val w = screen.width(); val cx = w / 2f
            val it = curItem()
            val n = it?.optInt("count", 1) ?: 1
            val amount = st.ctl?.optInt("amount", 1) ?: 1
            val r = RectF(cx - 410f, (screen.height() - 600f) / 2f, cx + 410f, (screen.height() + 600f) / 2f)
            UiKit.dialog(c, r)
            UiKit.dialogHeader(c, cx, r.top - 34f, "Split Stack")
            UiKit.slot(c, iconOf(it), cx - 190f, r.top + 76f, 84f)
            c.drawText(it?.optString("name") ?: "", cx - 86f, r.top + 130f, UiKit.bodyPaint(36f, colourOf(it)))
            c.drawText("a stack of $n", cx - 86f, r.top + 170f, UiKit.bodyPaint(26f, Theme.dim))
            val ny = r.top + 262f
            val field = RectF(cx - 128f, ny - 66f, cx + 128f, ny + 66f)
            c.drawRect(field.left + 8f, field.top + 8f, field.right - 8f, field.bottom - 8f, Paint().apply { color = Color.BLACK })
            UiKit.border(c, field, "border_tooltip", 20f, Theme.bronzeDim)
            val num = UiKit.numberPaint(96f, Color.WHITE).apply { textAlign = Paint.Align.CENTER }
            c.drawText(amount.toString(), cx, ny - (num.ascent() + num.descent()) / 2f, num)
            UiKit.arrow(c, "left", cx - 208f, ny, 116f, if (amount <= 1) UiKit.Button.DISABLED else UiKit.Button.UP)
            UiKit.arrow(c, "right", cx + 208f, ny, 116f, if (amount >= n - 1) UiKit.Button.DISABLED else UiKit.Button.UP)
            // a tap on an arrow is -1 / +1 (the SPEC); its touch box is its 116 px square
            if (amount > 1) hits.add(RectF(cx - 266f, ny - 58f, cx - 150f, ny + 58f) to 34)
            if (amount < n - 1) hits.add(RectF(cx + 150f, ny - 58f, cx + 266f, ny + 58f) to 35)
            if (st.ctl?.optString("note") == "no-space") {
                // in the D-pad row's place: no amount helps, and the SPEC's y 440 falls on the buttons
                val p = UiKit.bodyPaint(30f, Theme.unusable).apply { textAlign = Paint.Align.CENTER }
                c.drawText("There is no empty slot for the new stack", cx, ny + 104f - (p.ascent() + p.descent()) / 2f, p)
            } else {
                var x = cx - 250f
                val hint = UiKit.numberPaint(32f, Theme.dim)
                for ((glyph, wd) in listOf("gen_left" to "-1", "gen_right" to "+1", "gen_down" to "-10", "gen_up" to "+10")) {
                    UiKit.pad(c, glyph, x + 26f, ny + 104f, 52f)
                    c.drawText(wd, x + 60f, ny + 104f - (hint.ascent() + hint.descent()) / 2f, hint)
                    x += 130f
                }
            }
            val by = r.bottom - 70f - 111f
            // USER RULING 2026-09-28 17:50 (relayed): where the game has them, Okay (A) left, Cancel (B) right
            val okay = RectF(cx - 350f, by, cx - 14f, by + 111f); val cancel = RectF(cx + 14f, by, cx + 350f, by + 111f)
            button(c, okay, "Okay", "rev_a", "PAD1"); button(c, cancel, "Cancel", "rev_b", "PAD2")
            hits.add(okay to 31); hits.add(cancel to 33)
        }

        /** the bind sheet, hanging from the top edge; [f] 0..1 = how far it has slid in (and how dark the cover is) */
        private fun bind(c: Canvas, screen: RectF, ctl: JSONObject, it: JSONObject?, f: Float) {
            UiKit.cover(c, screen, 0.70f * f)
            val w = screen.width(); val cx = w / 2f
            val top = -800f + (740f * f)                          // -800 (out of sight) .. -60 (at rest)
            c.save(); c.translate(0f, top + 60f)                  // everything below is drawn at its resting place
            val r = RectF(cx - 550f, -60f, cx + 550f, 740f)
            UiKit.dialog(c, r)
            c.drawText("Bind a Button", cx, 56f, UiKit.titlePaint(46f).apply { textAlign = Paint.Align.CENTER })
            UiKit.slot(c, iconOf(it), cx - 170f, 94f, 84f)
            c.drawText(it?.optString("name") ?: "", cx - 66f, 150f, UiKit.bodyPaint(38f, colourOf(it)))
            val note = ctl.optString("note").takeIf { n -> n.isNotEmpty() }
            val chord = ctl.optString("chord").takeIf { n -> n.isNotEmpty() }
            val keyName = chord?.let { k -> bindKeyName(k) ?: k }
            val line = when (note) {
                "bind-taken" -> "$keyName is taken by " + (ctl.optString("takenByName").takeIf { n -> n.isNotEmpty() } ?: ctl.optString("takenBy"))
                "bind-modifier" -> "Combinations cannot be bound yet"
                "bind-unheard" -> "That button cannot be bound"
                "bind-notready", "bind-failed" -> "Not ready yet, press it again"
                "bound" -> "Bound to " + (bindKeyName(ctl.optString("bound")) ?: ctl.optString("bound"))
                else -> "Press the button you want for this item"
            }
            val colour = when (note) { null -> Color.WHITE; "bound" -> Theme.questGreen; else -> Theme.unusable }
            c.drawText(line, cx, 232f, UiKit.bodyPaint(36f, colour).apply { textAlign = Paint.Align.CENTER })
            val keys = ctl.optJSONArray("keys")
            val bound = ctl.optString("bound")
            for (i in 0 until (keys?.length() ?: 0)) {
                val k = keys!!.optJSONObject(i) ?: continue
                val name = k.optString("k")
                val x = cx - 512f + (i % 2) * 524f
                val y = 276f + (i / 2) * 118f
                val a = k.optString("a").takeIf { s -> s.isNotEmpty() }
                val label = k.optString("n").takeIf { s -> s.isNotEmpty() } ?: a ?: "Free"
                val mark = when {
                    note == "bound" && name == bound -> 2
                    note != null && note.startsWith("bind-") && name == chord -> 1
                    else -> 0
                }
                UiKit.keyChip(c, RectF(x, y, x + 500f, y + 104f), KEY_GLYPH[name] ?: "gen_rstickin", label, a == null, mark)
            }
            c.drawText("A, B, X and Y cannot be bound.", cx, 660f, UiKit.bodyPaint(26f, Theme.dim).apply { textAlign = Paint.Align.CENTER })
            val hs = UiKit.hints(c, r.right - 70f, 654f, "rev_b" to if (note == "bound") "Close" else "Cancel")
            c.restore()
            if (f >= 1f) hits.add(RectF(hs - 20f, 654f - 44f + top + 60f, r.right - 50f, 654f + 44f + top + 60f) to 33)
        }
    }

    /**
     * [current] = the document is live (GameInput's gate), not the log replayed at app start. An OLD document says
     * nothing about the controller NOW (Home's finding, AGENT_LOG 9596d1a): it neither switches the room nor counts
     * toward a tap, and the cursor is not drawn from it. The "tap once per use request" baseline is taken on the first
     * CURRENT document, so a request served before an app restart is never tapped again.
     */
    fun update(bags: JSONObject, current: Boolean = true) {
        head.set(bags.optInt("free", 0), bags.optInt("total", 0),
            if (bags.has("money")) bags.optLong("money") else null)
        // a bags section WITHOUT ctl (an older addon's heartbeat) says nothing about the controller:
        // keep the last state rather than read it as "off" (that flipped the room every 15 s)
        val c = if (current && bags.has("ctl")) bags.optJSONObject("ctl") else state.ctl
        state.ctl = c
        val changed = state.set(bags)
        if (state.ctlOn) state.follow()          // the item panel shows what the cursor is on
        if (changed) layoutAll()
        if (current) onCtl(c)
    }

    // ------------------------------------------------------------------ controller mode
    // USER RULINGS 2026-09-28: the game's bag button hands the controller to this room. The addon
    // holds it (interact's shared capture), moves the cursor and acts inside the button press, and
    // reports bags.ctl. X = use: the game's use call is protected, so the addon ASKS and this taps
    // the slot's secure cell, once per request, keyed on (sid, use.seq). The first ctl seen after the
    // app starts is only a baseline, so an old request is never replayed.

    // ------------------------------------------------------------------ touch drives the controller's cursor
    // USER 2026-09-29 10:03: "always show" (Move and More, by touch too). So the addon must act on the slot the finger
    // chose: a tap on a slot moves the controller's cursor there (bmvq col 36, then that slot's bmv cell: no game action),
    // and with an item in the hand the tap puts it down there (then A: the one move). The clean view stays touch-only.
    private fun rowsHave(vararg names: String): Boolean {
        val rows = state.pad?.optJSONObject("rows") ?: return false
        return names.all { rows.has(it) }
    }

    private fun cursorTo(bag: Int, slot: Int): Boolean {
        if (state.ctlCur() == (bag to slot)) return true
        if (!rowsHave("bmvq", "bmv$bag")) return false
        return GameInput.tap(context, state.pad, "bmvq", 36) && GameInput.tap(context, state.pad, "bmv$bag", slot - 1)
    }

    private fun touchSlot(bag: Int, slot: Int) {
        if (state.viewClean) return
        val carrying = state.ctlPick() != null
        if (!cursorTo(bag, slot)) return
        if (carrying) GameInput.tap(context, state.pad, "bmvq", 31)       // put it down here: A
    }

    private fun cardPress(col: Int) {
        val bag = state.selBag; val slot = state.selSlot
        if (bag >= 0 && slot >= 1 && !cursorTo(bag, slot)) return          // act on the slot the card shows
        GameInput.tap(context, state.pad, "bmvq", col)
    }

    /**
     * A finger on bag slot [id] of the bar while a bag is in the hand (Bags.lua EQ): the slot chosen already = Equip Here (A,
     * bmvq 31); another = that slot chosen (bmvq 26, then its column = the bag id). One touch = one press of the controller's.
     */
    private fun equipTap(id: Int) {
        if (state.ctl?.optString("mode") != "equip") return
        val eq = state.ctlEq() ?: return
        val targets = eq.optJSONArray("targets") ?: return
        val i = (0 until targets.length()).firstOrNull { targets.optJSONObject(it)?.optInt("bag", -1) == id } ?: return
        if (i + 1 == eq.optInt("at", 1)) GameInput.tap(context, state.pad, "bmvq", 31)
        else if (GameInput.tap(context, state.pad, "bmvq", 26)) GameInput.tap(context, state.pad, "bmvq", id)
    }

    /**
     * PRESS FEEDBACK from Home's controller reader (DirectPad's presses no room claims, routed here while this room shows): the drawn
     * button of [k] (PAD1 A, PAD2 B, PAD3 X, PAD4 Y) pushes in AT ONCE, before the game's answer. Display only: the addon still acts
     * on the game's own press.
     */
    fun onPadKey(k: String, down: Boolean) {
        if (kbOpen) { keyboardKey(k, down); return }
        ctlLayer.padKey(k, down)
    }

    // ------------------------------------------------------------------ the search keyboard
    // The user, 2026-10-09: "Yes, controller keyboard": A on the strip's Search opens Chat's controller keyboard (ChatKeyboard, its keys
    // and look as Chat has them), typing into the search box (the filter dims what does not match, as by touch). The add-on stands
    // still meanwhile (mode "search") and holds Start from the game; the presses come from Home's reader (onPadKey), the D-pad repeating
    // as DirectPad's rooms do. B / the Search key close it (the words stay); the add-on is told (bmvq 27, then col 17).
    private var kbOpen = false
    /**
     * Start is the pause wheel's from every room (the user's ruling 2026-10-09) EXCEPT while the Search keyboard shows: it is that
     * keyboard's Search key then. Home's Start route asks this and gives Start to [onPadKey] only while it is true.
     */
    fun ownsStart(): Boolean = kbOpen
    private var kbRepeat: String? = null
    private val kbTick = object : Runnable {
        override fun run() { val k = kbRepeat ?: return; kb.press(k); postDelayed(this, KB_REPEAT_MS) }
    }
    private fun keyboardKey(k: String, down: Boolean) {
        val dpad = k == "PADDUP" || k == "PADDDOWN" || k == "PADDLEFT" || k == "PADDRIGHT"
        if (!down) { if (kbRepeat == k) { kbRepeat = null; removeCallbacks(kbTick) }; return }
        kb.press(k)
        if (dpad && kbOpen) { kbRepeat = k; removeCallbacks(kbTick); postDelayed(kbTick, KB_REPEAT_FIRST_MS) }
    }
    private fun openKeyboard() {
        if (kbOpen) return
        kbOpen = true
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
            ?.hideSoftInputFromWindow(search.windowToken, 0)
        kb.open(); kb.visibility = View.VISIBLE
    }
    /** [tell] = the player closed it here: the add-on goes back to its own presses (else it already has, or the room was left) */
    private fun closeKeyboard(tell: Boolean) {
        if (!kbOpen) return
        kbOpen = false; kbRepeat = null; removeCallbacks(kbTick)
        kb.visibility = View.GONE
        if (tell && GameInput.tap(context, state.pad, "bmvq", 27)) GameInput.tap(context, state.pad, "bmvq", 17)
    }

    /** the hub shows this room while the addon holds the controller, and goes back after */
    var onController: ((Boolean, String?) -> Unit)? = null      // (on, why it went off: "B", "room", "closed", ...)

    /**
     * The room entered / left from the app (touch, the bar; Home calls these on EVERY route into and out of Bags).
     * The user 2026-10-01 (via Home): every room opens the GAME'S OWN WINDOW behind it, "act the EXACT same for all rooms".
     * So, while the addon says its doors are live (bags.gameDoors: built, and not in a fight), ONE tap on the backpack's
     * secure row: col 39 = the game's own open of its bags (whose OnShow then hands the controller over, exactly as the
     * bag key does), col 38 = the game's own close (whose OnHide hands it back, as a non-B leave). Each door is a no-op
     * when it would toggle the wrong way. Otherwise (a fight, an older addon) the old columns: bmvq 29 takes the
     * controller without a window, 30 hands it back.
     */
    fun touchedIn() {
        state.holdAskedAt = android.os.SystemClock.uptimeMillis()
        removeCallbacks(takeCheck)
        wantIn = true; takeRetried = false
        if (state.ctlOn) return
        // the game's bags already open (Bags let go, e.g. freed): the OPEN door would do nothing, so take with col 29
        val open = state.ctl?.optBoolean("open") == true
        if (state.gameDoors && !open) GameInput.tap(context, state.pad, "bag0", 39)
        else GameInput.tap(context, state.pad, "bmvq", 29)
        postDelayed(takeCheck, TAKE_CHECK_MS)
    }

    /**
     * THE TAKE IS CHECKED (Home's consistency pass, 2026-10-06; the user: a room holds the controller the WHOLE time it shows).
     * b74 measured a D-pad Down in this room reaching Home's bar through Spells' router, which steps aside only while the capture
     * has a holder: the room was on screen and nobody held. So when no bags.ctl says on (taken, queued or pending all say on)
     * [TAKE_CHECK_MS] after the take, ONE more take on bmvq 29: Bags' tryTake returns at once when it already holds, and it never
     * opens a game window. Never the bag0 door twice: the door is a toggle, so a late first click plus a second would close it.
     */
    private var wantIn = false
    private var takeRetried = false
    private val takeCheck = Runnable {
        if (!wantIn || state.ctlOn || takeRetried) return@Runnable
        takeRetried = true
        val sent = GameInput.tap(context, state.pad, "bmvq", 29)
        android.util.Log.i("WCBAGS", "touchedIn: no ctl.on after ${TAKE_CHECK_MS} ms (note=${state.ctl?.optString("note")}): " +
            "one more take, bmvq col 29 sent=$sent")
    }

    override fun onDetachedFromWindow() { removeCallbacks(takeCheck); super.onDetachedFromWindow() }

    /**
     * RC3 (Bags session's condition): B leaves for Home only in mode "grid" with nothing in the hand, no touch drag, no keyboard,
     * while Bags holds the controller; every other mode is a step back (an older add-on without `mode`: false, the old echo route)
     */
    override fun bLeadsHome(): Boolean =
        state.ctlOn && !kbOpen && state.drag == null && state.ctl?.optString("mode") == "grid" && state.ctlPick() == null

    fun touchedOut() {
        state.holdAskedAt = 0L
        removeCallbacks(takeCheck)
        wantIn = false
        if (!state.ctlOn) return
        if (state.gameDoors && state.ctl?.optBoolean("open") == true) GameInput.tap(context, state.pad, "bag0", 38)
        else GameInput.tap(context, state.pad, "bmvq", 30)
    }

    /**
     * Home's generic rule (2026-09-30, tracking's yes under the user's delegation): a CURRENT interact doc names "bags" as
     * the shared capture's holder while this room is not on screen, so Home calls this (at most once per 2 s). ONE tap on
     * Bags' row "bmvq" col 28: the addon lets go of the capture with NO game action -- it never closes the game's bags and
     * never presses anything in the game -- whatever this view's own flags say. True when the tap went out.
     */
    fun freeAgain(): Boolean {
        val sent = GameInput.tap(context, state.pad, "bmvq", 28)
        android.util.Log.i("WCBAGS", "freeAgain: bmvq col 28 (let go, no game action) sent=$sent")
        return sent
    }
    private var ctlOn = false
    private var ctlBaseline = false
    private var useSeen: String? = null
    private var actSeen: String? = null

    private fun onCtl(c: JSONObject?) {
        val on = c?.optBoolean("on") == true
        if (on != ctlOn) {
            ctlOn = on
            // the addon says why it handed back ("left-B" = the player left with B: the user's ruling, B goes Home)
            val why = c?.optString("note")?.takeIf { it.startsWith("left-") }?.removePrefix("left-")
            onController?.invoke(on, why)
        }
        val u = c?.optJSONObject("use")
        val key = if (c != null && u != null) "${c.optLong("sid")}:${u.optInt("seq")}" else null
        val a = c?.optJSONObject("act")
        val akey = if (c != null && a != null) "${c.optLong("sid")}:${a.optInt("seq")}" else null
        if (!ctlBaseline) { ctlBaseline = true; useSeen = key; actSeen = akey; return }
        if (on && a != null && akey != actSeen) {
            actSeen = akey
            if (a.optString("k") == "search") openKeyboard()
        }
        // the add-on stopped typing (the room left, its 5-minute end): the keyboard goes too
        if (kbOpen && (!on || c?.optString("mode") != "search")) closeKeyboard(false)
        if (!on || u == null || key == useSeen) return
        useSeen = key
        val bag = u.optInt("bag", -1); val slot = u.optInt("slot", 0)
        if (bag >= 0 && slot >= 1) GameInput.tap(context, state.pad, "bag$bag", slot - 1)
    }

    private fun layoutAll() {
        bar.requestLayout(); bar.invalidate()
        grid.requestLayout(); grid.invalidate()
        ctlLayer.invalidate()
    }

    companion object {
        /** a bindable button's word in the chosen glyph set (Glyphs.name); null = not one the sheet binds */
        private val KEY_ACTION = mapOf("PADRSHOULDER" to "rshoulder", "PADRTRIGGER" to "rtrigger", "PADLSTICK" to "lstick",
            "PADRSTICK" to "rstick", "PADBACK" to "select")
        fun bindKeyName(k: String): String? = KEY_ACTION[k]?.let { Glyphs.name(it) }
        private fun rule(colour: Int, c: Context) = View(c).apply { setBackgroundColor(colour) }
        const val TAKE_CHECK_MS = 1500L          // touchedIn's take, checked against bags.ctl this long after
        const val KB_REPEAT_FIRST_MS = 280L      // the search keyboard's D-pad repeat, as DirectPad's rooms (DirectPadKeys)
        const val KB_REPEAT_MS = 70L
        const val HOLD_GRACE_MS = 2000L          // a press this soon after touchedIn shows pushed before ctl.on arrives

        /**
         * What a right-click on this item does in the game right now -- the ONE action the panel
         * offers. null = no right-click action, so the panel shows no button at all.
         * Pair.second = false when the game would refuse (a vendor will not buy a no-value item).
         */
        fun actionOf(it: JSONObject, ctx: String?): Pair<String, Boolean>? = when (ctx) {
            "merchant" -> if (it.optBoolean("noValue")) "No Sale" to false else "Sell" to true
            "bank", "guildbank" -> "Deposit" to true
            "mail" -> "Attach" to true
            "trade" -> "Trade" to true
            "auction" -> "Auction" to true
            else -> when {
                it.optBoolean("readable") -> "Read" to true
                it.optBoolean("use") -> "Use" to true
                // an addon older than 46b0d1c sends "INVTYPE_NON_EQUIP_IGNORE" for food and quest
                // items (measured): that is not a slot
                real(it.optString("equip")) && it.optString("equip") != "INVTYPE_NON_EQUIP_IGNORE" -> "Equip" to true
                else -> null
            }
        }

        /** a bag (or quiver) item: X equips it on the bag bar (Bags.lua EQ) */
        fun isBag(it: JSONObject) = it.optString("equip").let { e -> e == "INVTYPE_BAG" || e == "INVTYPE_QUIVER" }

        /** the game-art slot tile, shared by the grid, the bag bar and the panel */
        fun drawSlot(cv: Canvas, box: RectF, it: JSONObject?, p: Paints,
                     selected: Boolean = false, dimmed: Boolean = false, iconOverride: Int? = null,
                     emptyTexture: String = "ui-backpack-emptyslot") {
            if (it == null && iconOverride == null) {
                val well = Icons.ui(emptyTexture)
                if (well != null) cv.drawBitmap(well, null, box, bmpPaint)
                else cv.drawRoundRect(box, 6f, 6f, p.empty)
                return
            }
            val inset = RectF(box.left + 4f, box.top + 4f, box.right - 4f, box.bottom - 4f)
            val fid = iconOverride ?: it?.let { o -> if (o.has("icon")) o.optInt("icon") else null }
            val icon = Icons.getOrUnknown(fid)
            val iconPaint = when {
                dimmed -> p.dim
                it?.optBoolean("locked") == true -> p.locked   // the game greys an item being moved
                else -> bmpPaint
            }
            if (icon != null) cv.drawBitmap(icon, null, inset, iconPaint)
            else cv.drawRoundRect(inset, 6f, 6f, p.empty)
            if (!dimmed && it != null) {
                val q = if (it.has("quality")) it.optInt("quality", -1) else -1
                if (q >= 2) Icons.ui("whiteiconframe")?.let { b ->       // the game borders uncommon+
                    p.tint.colorFilter = PorterDuffColorFilter(Theme.quality(q), PorterDuff.Mode.MULTIPLY)
                    cv.drawBitmap(b, null, box, p.tint)
                }
                if (it.optString("itype") == "Quest") Icons.ui("ui-icon-questborder")?.let { b ->
                    cv.drawBitmap(b, null, box, bmpPaint)
                }
            }
            if (selected) drawGlow(cv, box, p)
            val n = it?.optInt("count", 1) ?: 1
            if (n > 1) drawCount(cv, box, n, p)
            else if (!dimmed && it != null && icon != null) Doll.durability(cv, icon, inset, it)   // a worn-down piece in the bags: its colour
        }

        fun drawGlow(cv: Canvas, box: RectF, p: Paints) {
            Icons.ui("checkbuttonhilight")?.let { b -> cv.drawBitmap(b, null, box, p.add) }
                ?: cv.drawRect(box, p.selRim)
        }

        /** the game's outlined stack count, bottom right */
        fun drawCount(cv: Canvas, box: RectF, n: Int, p: Paints) {
            p.count.textSize = box.width() * 0.28f
            val tx = box.right - 7f; val ty = box.bottom - 9f
            p.count.style = Paint.Style.STROKE; p.count.strokeWidth = 6f; p.count.color = Color.BLACK
            cv.drawText(n.toString(), tx, ty, p.count)
            p.count.style = Paint.Style.FILL; p.count.color = Color.WHITE
            cv.drawText(n.toString(), tx, ty, p.count)
        }
    }

    /** the paints every drawing piece shares */
    class Paints {
        val empty = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.raised }
        val dim = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = 60 }
        val locked = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }
        val tint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val add = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
        val selRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Theme.gold; style = Paint.Style.STROKE; strokeWidth = 5f }
        val count = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.RIGHT }
    }

    // ------------------------------------------------------------------ shared state

    class State {
        data class Bag(val id: Int, val slots: Int, val free: Int?, val icon: Int?, val name: String? = null)
        var bags = listOf<Bag>(); var items = HashMap<Long, JSONObject>()
        var equipped = HashMap<Int, JSONObject>()     // worn slot 1..19 -> row
        var equippedDoc: JSONObject? = null           // the whole bags.equipped (ammo + the class flags)
        var ctx: String? = null
        var pad: JSONObject? = null                   // the shared tap pad, as the addon published it
        var combat = false                            // Clean Up is refused in combat
        var gameDoors = false                         // the game's own open / close of the bags can be tapped (bag0 39 / 38)
        var clean = false                             // the "clean view": merged + grouped (off by default)

        /** one tile of the clean view: an item with every stack of it merged */
        data class Merged(val first: JSONObject, val total: Int, val stacks: Int)
        /** clean view groups, in the order they are drawn; junk (poor quality) always last */
        val GROUPS = listOf("Consumables", "Quest", "Trade Goods", "Reagents", "Gear",
                            "Recipes", "Containers", "Other", "Junk")

        fun groupOf(it: JSONObject): String {
            if (it.has("quality") && it.optInt("quality", 1) == 0) return "Junk"
            return when (it.optString("itype")) {
                "Consumable" -> "Consumables"
                "Quest" -> "Quest"
                "Trade Goods", "Tradeskill" -> "Trade Goods"
                "Reagent" -> "Reagents"
                "Armor", "Weapon" -> "Gear"
                "Recipe" -> "Recipes"
                "Container", "Quiver" -> "Containers"
                else -> "Other"
            }
        }

        /** the clean view, from the OPEN bags, each group in bag order of an item's first stack */
        fun merged(): List<Pair<String, List<Merged>>> {
            val byId = LinkedHashMap<Int, Merged>()
            for (b in bags) {
                if (shut(b.id)) continue
                for (s in 1..b.slots) {
                    val it = items[key(b.id, s)] ?: continue
                    val id = it.optInt("id")
                    val n = it.optInt("count", 1).coerceAtLeast(1)
                    val m = byId[id]
                    byId[id] = if (m == null) Merged(it, n, 1) else m.copy(total = m.total + n, stacks = m.stacks + 1)
                }
            }
            val grouped = byId.values.groupBy { groupOf(it.first) }
            return GROUPS.mapNotNull { g -> grouped[g]?.let { g to it } }
        }

        fun mergedOf(id: Int): Merged? {
            var total = 0; var stacks = 0; var first: JSONObject? = null
            for (b in bags) {
                if (shut(b.id)) continue
                for (s in 1..b.slots) {
                    val it = items[key(b.id, s)] ?: continue
                    if (it.optInt("id") != id) continue
                    if (first == null) first = it
                    total += it.optInt("count", 1).coerceAtLeast(1); stacks++
                }
            }
            return first?.let { Merged(it, total, stacks) }
        }

        fun toggleClean() { clean = !clean; onChange() }

        // ---- drag & drop
        /** what is being carried: a bag stack */
        class Drag(val fromBag: Int, val fromSlot: Int, val item: JSONObject, var x: Float, var y: Float)
        var drag: Drag? = null
        var hoverBag = -1; var hoverSlot = -1
        var onDragMove: (Float, Float) -> Unit = { _, _ -> }
        var onDragEnd: (Float, Float) -> Unit = { _, _ -> }

        private fun hasRow(name: String) = pad?.optJSONObject("rows")?.has(name) == true

        /** a drag can only start when the addon has published the rows it needs */
        fun canDragBag(bag: Int) = !clean && hasRow("bmv$bag") && hasRow("bmvq")

        fun canDropBag(bag: Int, slot: Int): Boolean {
            val d = drag ?: return false
            if (!hasRow("bmv$bag")) return false
            return !(d.fromBag == bag && d.fromSlot == slot)
        }

        fun beginDrag(d: Drag) { drag = d; hoverBag = -1; hoverSlot = -1 }
        fun endDrag() { drag = null; hoverBag = -1; hoverSlot = -1 }

        var query = ""
        val closed = HashSet<Int>()                   // bag ids the player closed; all open by default

        /** the addon's controller state (bags.ctl); off = touch only */
        var ctl: JSONObject? = null
        val ctlOn get() = ctl?.optBoolean("on") == true
        /**
         * While the controller holds the bags, the grid shows them as the game has them: every bag,
         * in bag order, one tile per stack -- the order the addon's D-pad walks. The player's own
         * clean view and closed bags come back when the controller is handed back.
         */
        val viewClean get() = clean && !ctlOn
        /**
         * A bag closed in the room. Since 2026-10-09 the ADD-ON keeps the set (bags.ctl.shut, Bags.lua BAR) for every route, the
         * controller's A on the strip and a finger on the bar alike; an older add-on without it: the app's own set, touch only.
         */
        fun shut(id: Int): Boolean {
            val a = ctl?.optJSONArray("shut") ?: return !ctlOn && closed.contains(id)
            for (i in 0 until a.length()) if (a.optInt(i, -1) == id) return true
            return false
        }
        /** the cursor is on the room's top strip (bags.ctl.bar): no grid cursor then */
        val onBar get() = ctlOn && ctl?.optJSONObject("bar") != null
        /** a finger on bag slot (id): the add-on's set (BagsView: bmvq 27, then the bag's column); false = not sent */
        var onShutTap: (Int) -> Boolean = { false }
        private fun at(o: JSONObject?) = o?.let { it.optInt("bag", -1) to it.optInt("slot", -1) }
        /** a bag is being equipped (Bags.lua EQ): choosing its slot, the game working, or the game's bind question */
        val equipping get() = ctlOn && ctl?.optString("mode").let { m -> m == "equip" || m == "equipping" || m == "bindask" }
        fun ctlEq(): JSONObject? = if (equipping) ctl?.optJSONObject("eq") else null
        /** the grid's cursor; none while equipping (it is on the bag bar then) */
        fun ctlCur(): Pair<Int, Int>? = if (ctlOn && !equipping && !onBar) at(ctl?.optJSONObject("cur")) else null
        fun ctlPick(): Pair<Int, Int>? = if (ctlOn) at(ctl?.optJSONObject("pick")) else null
        /** select whatever the controller's cursor is on (an empty slot = nothing selected) */
        fun follow() {
            val (b, s) = ctlCur() ?: return
            val it = items[key(b, s)]
            if (it == null) { selBag = -1; selSlot = -1; selId = -1 } else { selBag = b; selSlot = s; selId = it.optInt("id") }
        }
        var selBag = -1; var selSlot = -1; var selId = -1
        var onChange: () -> Unit = {}
        /** a TAP on a bag slot (not a drag): the controller's cursor follows the finger (BagsView sends it) */
        var onSlotTap: (Int, Int) -> Unit = { _, _ -> }
        /** the panel's buttons by touch: [col] on Bags' row "bmvq" (31 A, 32 Y, 39 drop what is held) */
        var onCard: (Int) -> Unit = {}
        /** a TAP on bag slot (id) of the bar while a bag is in the hand (BagsView.equipTap) */
        var onEquipSlot: (Int) -> Unit = {}
        /** when the room asked for the controller (touchedIn); a press within HOLD_GRACE_MS shows before the hold's document */
        var holdAskedAt = 0L
        fun holdAsked() = holdAskedAt > 0L && android.os.SystemClock.uptimeMillis() - holdAskedAt < HOLD_GRACE_MS
        private var lastJson = ""

        fun key(bag: Int, slot: Int) = (bag.toLong() shl 16) or slot.toLong()
        fun selected(): JSONObject? = items[key(selBag, selSlot)]

        /** true when anything changed (the 15 s heartbeat re-sends identical bags) */
        fun set(b: JSONObject): Boolean {
            val json = b.toString()
            if (json == lastJson) return false
            lastJson = json
            val list = ArrayList<Bag>()
            val arr = b.optJSONArray("bags") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(Bag(o.optInt("bag"), o.optInt("slots"),
                    if (o.has("free")) o.optInt("free") else null,
                    if (o.has("icon")) o.optInt("icon") else null,
                    o.optString("name").takeIf { n -> real(n) }))
            }
            val map = HashMap<Long, JSONObject>()
            val its = b.optJSONArray("items") ?: JSONArray()
            for (i in 0 until its.length()) {
                val o = its.optJSONObject(i) ?: continue
                map[key(o.optInt("bag"), o.optInt("slot"))] = o
            }
            // an addon older than the per-bag list: one block, sized to what we know
            if (list.isEmpty() && map.isNotEmpty()) list.add(Bag(0, b.optInt("total", 16), null, null))
            val eq = HashMap<Int, JSONObject>()
            b.optJSONObject("equipped")?.optJSONArray("slots")?.let { s ->
                for (i in 0 until s.length()) s.optJSONObject(i)?.let { eq[it.optInt("slot")] = it }
            }
            bags = list; items = map; equipped = eq; equippedDoc = b.optJSONObject("equipped")
            ctx = b.optString("ctx").takeIf { real(it) }
            pad = b.optJSONObject("pad")
            combat = b.optBoolean("combat", false)
            gameDoors = b.optBoolean("gameDoors", false)
            val sel = selected()
            if (sel == null || sel.optInt("id") != selId) { selBag = -1; selSlot = -1; selId = -1 }
            return true
        }

        fun select(bag: Int, slot: Int) {
            val it = items[key(bag, slot)]
            if (it == null || (bag == selBag && slot == selSlot)) { selBag = -1; selSlot = -1; selId = -1 }
            else { selBag = bag; selSlot = slot; selId = it.optInt("id") }
            onChange()
        }

        fun toggle(bag: Int) {
            // the add-on keeps the closed bags (every route the same): the tap goes there, its document redraws the room
            if (ctl?.has("shut") == true && hasRow("bmvq") && onShutTap(bag)) return
            if (!closed.remove(bag)) closed.add(bag)
            if (selBag == bag && closed.contains(bag)) { selBag = -1; selSlot = -1; selId = -1 }
            onChange()
        }
    }

    // ------------------------------------------------------------------ header

    private class Header(c: Context) : View(c) {
        private var free = 0; private var total = 0; private var money: Long? = null
        private val pTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Theme.gold; textSize = 48f; typeface = Typeface.DEFAULT_BOLD }
        private val pSub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.textDim; textSize = 34f }
        private val pMoney = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Theme.text; textSize = 36f; textAlign = Paint.Align.RIGHT }

        fun set(free: Int, total: Int, money: Long?) {
            if (free == this.free && total == this.total && money == this.money) return
            this.free = free; this.total = total; this.money = money; invalidate()
        }

        private fun fonts() {
            UiKit.asTitle(pTitle); UiKit.asBody(pSub); UiKit.asNumber(pMoney)
        }

        override fun onDraw(cv: Canvas) {
            fonts()
            cv.drawColor(Theme.panel)
            val mid = height / 2f
            cv.drawText("Bags", 22f, mid + 17f, pTitle)
            cv.drawText("$free free of $total", 22f + pTitle.measureText("Bags") + 36f, mid + 12f, pSub)
            // money the way the game writes it: 2 [silver] 6 [copper], with the game's coins
            val m = money ?: return
            val parts = ArrayList<Pair<Long, String>>()
            val g = m / 10000; val s = (m / 100) % 100; val cp = m % 100
            if (g > 0) parts.add(g to "ui-goldicon")
            if (g > 0 || s > 0) parts.add(s to "ui-silvericon")
            parts.add(cp to "ui-coppericon")
            var x = width - 24f
            for ((amt, coin) in parts.asReversed()) {
                val bmp = Icons.ui(coin)
                if (bmp != null) {
                    x -= 34f; cv.drawBitmap(bmp, null, RectF(x, mid - 17f, x + 34f, mid + 17f), bmpPaint)
                    x -= 8f
                } else {
                    pMoney.color = Theme.dim; cv.drawText(coin.substring(3, 4), x, mid + 12f, pMoney)
                    x -= 24f; pMoney.color = Theme.text
                }
                cv.drawText(amt.toString(), x, mid + 13f, pMoney)
                x -= pMoney.measureText(amt.toString()) + 18f
            }
        }
    }

    // ------------------------------------------------------------------ bag bar

    /** the game's bag bar: the backpack + 4 bag slots. Tap an equipped bag to open / close it. */
    private class BagBar(c: Context, private val st: State) : View(c) {
        private val p = Paints()
        private val boxes = ArrayList<Pair<RectF, Int>>()
        private val slotIds = listOf(0, 1, 2, 3, 4)
        private val B = 96f; private val G = 12f; private val M = 48f; private val CTRL_GAP = 24f
        private var sortBox = RectF(); private var viewBox = RectF()
        private var sortedAt = 0L
        private val pGlyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 5f; strokeCap = Paint.Cap.ROUND }

        private val slots = HashMap<Int, RectF>()                   // every bag slot drawn, empty ones too (the equip cursor)
        // + the reagent bag; + a slot a bag in the hand could go in (the reagent slot shows while a reagent bag is equipped)
        private fun ids() = (slotIds + st.bags.map { it.id }.filter { it > 4 } + eqIds()).distinct()
        private fun eqIds(): List<Int> {
            val t = st.ctlEq()?.optJSONArray("targets") ?: return emptyList()
            return (0 until t.length()).mapNotNull { t.optJSONObject(it)?.optInt("bag", -1)?.takeIf { b -> b >= 0 } }
        }
        /** a bag slot's square in this view's coordinates, as last drawn */
        fun slotRect(id: Int): RectF? = slots[id]
        /** Clean Up's square, as last drawn */
        fun sortRect(): RectF = RectF(sortBox)

        // a finger on a slot / button pushes it in at once (the kit's press), and it stays in 160 ms after the lift (b116)
        private var pressedId = NONE
        private fun idAt(x: Float, y: Float): Int = when {
            st.equipping -> slots.entries.firstOrNull { it.value.contains(x, y) && it.key in eqIds() }?.key ?: NONE
            sortBox.contains(x, y) -> if (sortLive()) SORT else NONE
            viewBox.contains(x, y) -> VIEW
            else -> boxes.firstOrNull { it.first.contains(x, y) }?.second ?: NONE
        }
        private companion object { const val NONE = Int.MIN_VALUE; const val SORT = -100; const val VIEW = -101 }
        private fun sortLive() = st.pad?.optJSONObject("rows")?.has("bagsort") == true && !st.combat &&
            android.os.SystemClock.uptimeMillis() - sortedAt > 1500

        override fun onMeasure(wSpec: Int, hSpec: Int) {
            val w = M + ids().size * (B + G) + CTRL_GAP + 2 * (B + G) + G
            setMeasuredDimension(w.toInt(), MeasureSpec.getSize(hSpec))
        }

        override fun onDraw(cv: Canvas) {
            UiKit.asNumber(p.count, shadow = false)          // pGlyph stays: it stands in for button art
            boxes.clear(); slots.clear()
            val y = (height - B) / 2f
            var x = M
            for (id in ids()) {
                val box = RectF(x, y, x + B, y + B); x += B + G
                slots[id] = box
                val bag = st.bags.firstOrNull { it.id == id }
                UiKit.pressed(cv, box, pressedId == id) {
                    if (bag == null) drawSlot(cv, box, null, p, emptyTexture = "ui-paperdoll-slot-bag")   // the game's empty bag slot
                    else {
                        val open = !st.shut(id)
                        // the backpack button is the game's own texture; a bag shows its item's icon
                        val backpack = if (id == 0 && bag.icon == null) Icons.ui("button-backpack-up") else null
                        if (backpack != null) cv.drawBitmap(backpack, null, box, if (open) bmpPaint else p.dim)
                        else Icons.getOrUnknown(bag.icon ?: 133633)?.let { b ->
                            cv.drawBitmap(b, null, RectF(box.left + 4f, box.top + 4f, box.right - 4f, box.bottom - 4f),
                                if (open) bmpPaint else p.dim)
                        }
                        if (open) drawGlow(cv, box, p)
                        bag.free?.let { drawCount(cv, box, it, p) }     // free slots, like the game's backpack button
                    }
                }
                if (bag != null) boxes.add(box to id)
            }
            x += CTRL_GAP - G

            // the game's own CLEAN UP button: runs the game's sort, so the real bags reorder
            sortBox = RectF(x, y, x + B, y + B); x += B + G
            val live = sortLive()
            val art = Icons.ui(if (live) "bags-button-autosort-up" else "bags-button-autosort-down")
            UiKit.pressed(cv, sortBox, pressedId == SORT) {
                if (art != null) cv.drawBitmap(art, null, sortBox, if (live) bmpPaint else p.dim)
                else drawSlot(cv, sortBox, null, p, emptyTexture = "ui-emptyslot")
            }

            // the view toggle: bag order (off) <-> the clean view, merged + grouped (on)
            viewBox = RectF(x, y, x + B, y + B)
            UiKit.pressed(cv, viewBox, pressedId == VIEW) {
                drawSlot(cv, viewBox, null, p, emptyTexture = "ui-emptyslot")
                pGlyph.color = if (st.clean) Theme.gold else Theme.textDim
                val cx = viewBox.centerX(); val cy = viewBox.centerY()
                for (k in -1..1) {                             // a list: a square and a line per row
                    val ly = cy + k * 20f
                    cv.drawRect(cx - 26f, ly - 6f, cx - 14f, ly + 6f, pGlyph)
                    cv.drawLine(cx - 4f, ly, cx + 26f, ly, pGlyph)
                }
            }
            if (st.clean) drawGlow(cv, viewBox, p)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { pressedId = idAt(e.x, e.y); invalidate(); return true }
                MotionEvent.ACTION_MOVE -> { if (pressedId != NONE && idAt(e.x, e.y) != pressedId) { pressedId = NONE; invalidate() }; return true }
                MotionEvent.ACTION_CANCEL -> { pressedId = NONE; invalidate(); return true }
            }
            if (e.actionMasked != MotionEvent.ACTION_UP) return true
            val was = pressedId
            if (was != NONE) postDelayed({ if (pressedId == was) { pressedId = NONE; invalidate() } }, 160L)
            if (st.equipping) {                              // a bag in the hand: a bag slot is its target (BagsView.equipTap)
                slots.entries.firstOrNull { it.value.contains(e.x, e.y) }?.let { st.onEquipSlot(it.key) }
                return true
            }
            when {
                sortBox.contains(e.x, e.y) -> {
                    val row = st.pad?.optJSONObject("rows")?.has("bagsort") == true
                    val live = sortLive()
                    val sent = live && GameInput.tap(context, st.pad, "bagsort", 0)
                    if (sent) {
                        sortedAt = android.os.SystemClock.uptimeMillis()   // 1.5 s: one tap, one sort
                        invalidate(); postDelayed({ invalidate() }, 1550)
                    }
                }
                viewBox.contains(e.x, e.y) -> st.toggleClean()
                else -> for ((box, id) in boxes) if (box.contains(e.x, e.y)) { st.toggle(id); break }
            }
            return true
        }
    }

    // ------------------------------------------------------------------ grid

    private class Grid(c: Context, private val st: State) : View(c) {
        private val p = Paints()
        private val tiles = ArrayList<Triple<RectF, Int, Int>>()     // hit box, bag, slot
        private val pRule = Paint().apply { color = Theme.bronzeDim; strokeWidth = 2f }
        private val pDimOverlay = Paint().apply { color = Color.argb(150, 14, 11, 8) }
        private val pLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.textDim; textSize = 30f }

        companion object {
            const val COLS = 10          // v3: the room's full width (the addon's D-pad walks the same 10)
            const val M = 48f            // left: clear of the 41 px back-gesture edge (measured)
            const val MR = 20f
            const val GAP = 10f
            const val RULE_H = 22f
            const val GROUP_H = 48f
        }
        private val pGroup = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Theme.textDim; textSize = 26f; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.04f }

        private fun tileSize(w: Int) = (w - M - MR - (COLS - 1) * GAP) / COLS
        private fun rowsOf(b: State.Bag) = ceil(b.slots / COLS.toDouble()).toInt()
        private fun openBags() = st.bags.filter { !st.shut(it.id) }

        override fun onMeasure(wSpec: Int, hSpec: Int) {
            val w = MeasureSpec.getSize(wSpec)
            val t = tileSize(w)
            var h = 12f
            if (st.viewClean) {
                for ((_, list) in st.merged())
                    h += GROUP_H + ceil(list.size / COLS.toDouble()).toInt() * (t + GAP)
            } else {
                for ((i, b) in openBags().withIndex()) {
                    if (i > 0) h += RULE_H
                    h += rowsOf(b) * (t + GAP)
                }
            }
            setMeasuredDimension(w, maxOf(h.toInt() + 24, MeasureSpec.getSize(hSpec)))
        }

        /**
         * The CLEAN VIEW (user ruling 2026-09-27): one tile per item with every stack merged into
         * the count, no empty slots, grouped with junk last. A tile acts on the item's FIRST stack
         * in bag order -- the game still does everything one stack at a time.
         */
        private fun drawClean(cv: Canvas, t: Float) {
            var y = 12f
            var selBox: RectF? = null
            val groups = st.merged()
            if (groups.isEmpty()) { cv.drawText("nothing in the open bags", M, y + 50f, pLabel); return }
            for ((name, list) in groups) {
                // a group needs a word: without one the grouping is invisible
                cv.drawText(name, M, y + 30f, pGroup)
                val lx = M + pGroup.measureText(name) + 16f
                cv.drawLine(lx, y + 21f, width - MR, y + 21f, pRule)
                y += GROUP_H
                for ((k, m) in list.withIndex()) {
                    val x = M + (k % COLS) * (t + GAP)
                    val yy = y + (k / COLS) * (t + GAP)
                    val box = RectF(x, yy, x + t, yy + t)
                    val shown = JSONObject(m.first.toString()).put("count", m.total)
                    val miss = st.query.isNotEmpty() && !m.first.optString("name").lowercase().contains(st.query)
                    drawSlot(cv, box, shown, p, dimmed = miss)
                    if (m.first.optInt("id") == st.selId && st.selBag >= 0) selBox = box
                    tiles.add(Triple(box, m.first.optInt("bag"), m.first.optInt("slot")))
                }
                y += ceil(list.size / COLS.toDouble()).toInt() * (t + GAP)
            }
            selBox?.let { UiKit.slotFocus(cv, it) }
        }

        private var focusSeen: Pair<Int, Int>? = null

        private fun fonts() {
            UiKit.asBody(pLabel); UiKit.asBody(pGroup); UiKit.asNumber(p.count, shadow = false)
        }

        override fun onDraw(cv: Canvas) {
            fonts()
            tiles.clear()
            val cur = st.ctlCur(); val pick = st.ctlPick()
            val inHand = st.ctlEq()?.let { it.optInt("bag", -1) to it.optInt("slot", -1) }     // a bag being equipped
            var curBox: RectF? = null
            val t = tileSize(width)
            var y = 12f
            if (st.bags.isEmpty()) { cv.drawText("waiting for your bags", M, y + 50f, pLabel); return }
            if (st.viewClean) { drawClean(cv, t); return }
            val open = openBags()
            if (open.isEmpty()) { cv.drawText("all bags closed", M, y + 50f, pLabel); return }
            for ((i, b) in open.withIndex()) {
                if (i > 0) {                                    // a line between bags, not a label
                    cv.drawLine(M, y + RULE_H / 2 - GAP / 2, width - MR, y + RULE_H / 2 - GAP / 2, pRule)
                    y += RULE_H
                }
                for (s in 1..b.slots) {
                    val k = s - 1
                    val x = M + (k % COLS) * (t + GAP)
                    val yy = y + (k / COLS) * (t + GAP)
                    val box = RectF(x, yy, x + t, yy + t)
                    val it = st.items[st.key(b.id, s)]
                    val miss = st.query.isNotEmpty() &&
                        (it == null || !it.optString("name").lowercase().contains(st.query))
                    val d = st.drag
                    val lifted = d != null && d.fromBag == b.id && d.fromSlot == s
                    val picked = (pick != null && pick.first == b.id && pick.second == s) || inHand == (b.id to s)
                    // the slot an item was picked from: the game's locked look (grey), as the SPEC says
                    val shown = if (picked && it != null) JSONObject(it.toString()).put("locked", true) else it
                    // ONE highlight (LATER 19): a touch selection is the controller's gold focus too; with the controller
                    // the cursor is the one frame
                    drawSlot(cv, box, shown, p, dimmed = (miss && it != null) || lifted)
                    if (b.id == st.selBag && s == st.selSlot && d == null && cur == null && inHand == null) curBox = box
                    if (cur != null && cur.first == b.id && cur.second == s) {
                        curBox = box
                        if (cur != focusSeen) {
                            focusSeen = cur
                            val r = android.graphics.Rect(box.left.toInt(), (box.top - GAP).toInt(), box.right.toInt(), (box.bottom + GAP).toInt())
                            post { requestRectangleOnScreen(r, false) }     // the ScrollView brings it into view
                        }
                    }
                    if (miss && it == null) cv.drawRect(box, pDimOverlay)
                    // while dragging: the slot under the finger glows if the item can go there
                    if (d != null && st.hoverBag == b.id && st.hoverSlot == s && st.canDropBag(b.id, s))
                        drawGlow(cv, box, p)
                    tiles.add(Triple(box, b.id, s))
                }
                y += rowsOf(b) * (t + GAP)
            }
            if (cur == null) focusSeen = null
            // UI Art's SPEC: the glow lies outside the slot, so it goes on AFTER the slots round it,
            // and the item in the hand rides on the focused slot's lower right corner, drawn last
            // (the item in the hand is the layer over the room's: on the rightmost column it leaves this view)
            curBox?.let { fb -> UiKit.slotFocus(cv, fb) }
        }

        /** where the drawn slots end, in this view's coordinates (gear's compare goes under it when there is room) */
        fun contentBottom(): Float = tiles.maxOfOrNull { it.first.bottom } ?: 0f

        /** a slot's own square in this view's coordinates, as last drawn (null = not on screen) */
        fun tileRect(bag: Int, slot: Int): RectF? = tiles.firstOrNull { it.second == bag && it.third == slot }?.first

        /** the bag slot under a SCREEN point (drags cross views, so they speak screen coordinates) */
        fun hitRaw(rx: Float, ry: Float): Pair<Int, Int>? {
            if (st.viewClean) return null
            val at = IntArray(2); getLocationOnScreen(at)
            val lx = rx - at[0]; val ly = ry - at[1]
            if (lx < 0 || ly < 0 || lx > width || ly > height) return null
            return tiles.firstOrNull { it.first.contains(lx, ly) }?.let { it.second to it.third }
        }

        // a TAP selects; a swipe scrolls (the ScrollView takes it); a LONG PRESS lifts the item
        private var downX = 0f; private var downY = 0f
        private var rawX = 0f; private var rawY = 0f
        private val slop = ViewConfiguration.get(c).scaledTouchSlop
        private var pressed: Triple<RectF, Int, Int>? = null
        private val lift = Runnable {
            val hit = pressed ?: return@Runnable
            val it = st.items[st.key(hit.second, hit.third)] ?: return@Runnable
            if (!st.canDragBag(hit.second)) {
                return@Runnable
            }
            st.beginDrag(State.Drag(hit.second, hit.third, it, rawX, rawY))
            parent?.requestDisallowInterceptTouchEvent(true)     // the ScrollView must not take it now
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            st.onDragMove(rawX, rawY)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            rawX = e.rawX; rawY = e.rawY
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y
                    pressed = tiles.firstOrNull { it.first.contains(e.x, e.y) }
                    if (pressed != null && !st.viewClean) postDelayed(lift, 380)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (st.drag != null) st.onDragMove(e.rawX, e.rawY)
                    else if (abs(e.x - downX) > slop || abs(e.y - downY) > slop) {
                        removeCallbacks(lift)
                        // a SIDEWAYS move off an item lifts it at once (the grid only scrolls
                        // vertically); an up/down move still scrolls
                        val dx = abs(e.x - downX); val dy = abs(e.y - downY)
                        if (pressed != null && !st.viewClean && dx > dy * 1.5f) lift.run()
                        else pressed = null
                    }
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(lift)
                    if (st.drag != null) { st.onDragEnd(e.rawX, e.rawY); return true }
                    if (abs(e.x - downX) <= slop && abs(e.y - downY) <= slop) {
                        val hit = tiles.firstOrNull { it.first.contains(e.x, e.y) }
                        if (hit != null) { st.select(hit.second, hit.third); st.onSlotTap(hit.second, hit.third) }
                        else if (st.selBag >= 0) st.select(st.selBag, st.selSlot)   // tap off a tile: close
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(lift)
                    if (st.drag != null) { st.endDrag(); st.onChange() }
                }
            }
            return true
        }
    }
}
