package com.jrees.wowcompanion

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import org.json.JSONObject

/**
 * PARTY MODE'S PLAYER MENU (PARTY_BUILD.md 4.6; SPEC_DRAFT v10 4, P6): the game's own menu for a party member, mirrored on the bottom
 * screen over the left column (Home lays it at x 24..600, y 236..914). The look is the portrait menu's (MapView.drawPlayerMenu):
 * `UiKit.menu(solid)`, the member's name as the heading (Friz 28 gold at +16, +40), rows 80 high, labels Friz 28 from x +100, section
 * titles Friz 22 dim, the chosen row `UiKit.focus` with its word gold (UiKit.menuRow), sub-menus "  >", more rows scroll with the
 * game's gold arrow at the foot. The model is [PlayerMenu] (Inspect greyed: the game's InspectUnit taints).
 *
 * Opened by a LONG PRESS on a cell (touch only) or by the PartyPad's Y (`partybox k = "menu"`: then the PartyPad's `k = "key"` presses
 * drive it, and its close taps MENU DONE). With Party mode off the controller stays the game's: touch only.
 */
@SuppressLint("ViewConstructor")
class PartyMenuView(context: Context) : View(context) {
    val menu = PlayerMenu(greyKeys = PlayerMenu.PARTY_GREY)
    /** the member's name (the heading until the game's own title row comes) */
    var name: String = ""
        private set
    /** the unit index the menu is for (0 = you) */
    var unit: Int = -1
        private set
    /** opened by the PartyPad's Y: its presses drive the menu, and its close taps MENU DONE */
    var byPad = false
        private set

    /** the menu's outcome: the host taps (qact digits / the secure row) or opens the Chat room; [byPad] the opener */
    var onDo: ((PlayerMenu.Do, Boolean) -> Unit)? = null

    val isOpen: Boolean get() = menu.open

    fun open(unit: Int, name: String, byPad: Boolean) {
        this.unit = unit; this.name = name; this.byPad = byPad
        menu.openReading()
        visibility = VISIBLE
        bringToFront()
        invalidate()
    }

    /** the game's `pmenu` with src = "party" (current only) */
    fun onDoc(d: JSONObject) {
        if (!menu.open) return
        d.optString("nm", "").takeIf { it.isNotEmpty() }?.let { name = it }
        menu.onDoc(d)
        invalidate()
    }

    /** one press (the PartyPad's `key`, or the app's own reader); the outcome goes to [onDo] */
    fun key(k: String) {
        if (!menu.open) return
        did(menu.key(k))
    }

    fun close() {
        if (!menu.open) { visibility = GONE; return }
        menu.close()
        did(PlayerMenu.Do.Close)
    }

    private fun did(d: PlayerMenu.Do) {
        val pad = byPad
        if (!menu.open) visibility = GONE
        if (d != PlayerMenu.Do.None) onDo?.invoke(d, pad)
        if (!menu.open) byPad = false
        invalidate()
    }

    // ------------------------------------------------------------------------------------------------ paint

    private val hits = ArrayList<Pair<RectF, Int>>()
    private val box = RectF()

    override fun onDraw(c: Canvas) {
        hits.clear()
        if (!menu.open) return
        box.set(0f, 0f, width.toFloat(), height.toFloat())
        val inner = UiKit.menu(c, box, solid = true)
        val hp = UiKit.bodyPaint(28f, Theme.gold)
        val heading = (if (menu.depth > 0) "< " else "") + (menu.heading() ?: name)
        c.drawText(TextUtils.ellipsize(heading, hp, inner.width() - 32f, TextUtils.TruncateAt.END).toString(), inner.left + 16f, inner.top + 40f, hp)
        val top0 = inner.top + HEAD
        if (menu.loading || menu.empty) {
            // the portrait menu's words (MapView.drawPlayerMenu)
            c.drawText(if (menu.loading) "Asking the game..." else "The game's menu could not be read here", inner.left + 16f, top0 + 46f,
                UiKit.bodyPaint(24f, Theme.textDim))
            return
        }
        val rows = menu.rows()
        val shown = rows.indices.filter { !(rows[it].title && rows[it].text == menu.heading()) }
        val fit = ((inner.bottom - top0 - ARROW) / ROW).toInt().coerceAtLeast(1)
        val first = (shown.indexOf(menu.sel) - fit + 1).coerceIn(0, maxOf(0, shown.size - fit))
        for (k in first until minOf(shown.size, first + fit)) {
            val i = shown[k]
            val e = rows[i]
            val r = RectF(inner.left, top0 + (k - first) * ROW, inner.right, top0 + (k - first + 1) * ROW)
            if (e.title) {
                val tp = UiKit.bodyPaint(22f, Theme.textDim)
                c.drawText(e.text, r.left + 16f, r.centerY() + 8f, tp)
                continue
            }
            UiKit.menuRow(c, r, e.text + (if (e.sub != null) "  >" else ""), i == menu.sel, enabled = e.enabled,
                mark = if (e.checked == true) "icon_check" else null, size = 28f)
            hits.add(r to i)
        }
        // more rows below: the game's gold arrow at the foot
        if (first + fit < shown.size) UiKit.moveArrowsUpDown(c, RectF(inner.left, inner.bottom - ARROW, inner.right, inner.bottom),
            up = false, down = true, around = true)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!menu.open) return false
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            val i = hits.firstOrNull { it.first.contains(e.x, e.y) }?.second
            if (i != null) did(menu.touch(i))
        }
        return true                                                  // the menu's box is the menu's (nothing under it is touched)
    }

    companion object {
        const val ROW = 80f
        const val HEAD = 64f
        const val ARROW = 40f
        /** where Home lays it: over the left column (the party module and chat) */
        val PLACE = RectF(24f, 236f, 600f, 914f)
    }
}
