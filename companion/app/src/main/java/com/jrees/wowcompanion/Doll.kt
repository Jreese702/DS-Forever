package com.jrees.wowcompanion

import android.graphics.*
import org.json.JSONObject

/**
 * THE CHARACTER PAGE'S DOLL, shared: every worn slot where the game's character frame puts it, the
 * game's own empty-slot art for a blank, the worn item's icon with its quality frame, and the game's
 * red for badly worn gear. ICONS ONLY (user ruling 2026-09-28).
 *
 * OWNER: the bags session. The Bags room draws it in its item panel (and adds its own drag glows on
 * top); the Character room draws it in its left pane (display + select only). Both give it the
 * rectangle; the doll knows only the game's order.
 *
 *   val l = Doll.layout(leftX, rightX, top, slot, pitch, weaponsY, weaponsCx, weaponGap, ammo = eq.ammoNeeded)
 *   Doll.draw(c, l, worn, ammo = eq.ammo, relic = eq.relic)
 *   val slot = Doll.hit(l, x, y)          // -1 = none, 0 = the ammo slot
 *
 * [worn] maps an inventory slot (1..19) to its row of `bags.equipped.slots` (id, icon, quality, dur).
 * The class-only parts (user ruling 22:15, "factor in class exclusive stuff") come from the same document:
 * `ammoNeeded` shows the small ammo slot beside the ranged slot, `relic` gives the ranged slot the relic's
 * empty art, `rangedShown == false` leaves the ranged slot out, as the game's own paper doll does.
 */
object Doll {

    /** inventory slot -> (column 0 left / 1 right / 2 the weapons row, index), the game's character frame */
    val SLOTS = listOf(
        1 to (0 to 0), 2 to (0 to 1), 3 to (0 to 2), 15 to (0 to 3), 5 to (0 to 4), 4 to (0 to 5), 19 to (0 to 6), 9 to (0 to 7),
        10 to (1 to 0), 6 to (1 to 1), 7 to (1 to 2), 8 to (1 to 3), 11 to (1 to 4), 12 to (1 to 5), 13 to (1 to 6), 14 to (1 to 7),
        16 to (2 to 0), 17 to (2 to 1), 18 to (2 to 2),
    )

    /** the game's empty-slot pictures (`ui-paperdoll-slot-<name>`, in the icon art folder) */
    val EMPTY_ART = mapOf(
        1 to "head", 2 to "neck", 3 to "shoulder", 15 to "rear", 5 to "chest", 4 to "shirt", 19 to "tabard",
        9 to "wrists", 10 to "hands", 6 to "waist", 7 to "legs", 8 to "feet", 11 to "finger", 12 to "rfinger",
        13 to "trinket", 14 to "trinket", 16 to "mainhand", 17 to "secondaryhand", 18 to "ranged", 0 to "ranged",
    )

    /** the slot names the game shows for an empty slot (a card, a hint) */
    val NAMES = mapOf(
        0 to "Ammo", 1 to "Head", 2 to "Neck", 3 to "Shoulder", 4 to "Shirt", 5 to "Chest", 6 to "Waist", 7 to "Legs",
        8 to "Feet", 9 to "Wrist", 10 to "Hands", 11 to "Finger", 12 to "Finger", 13 to "Trinket", 14 to "Trinket",
        15 to "Back", 16 to "Main Hand", 17 to "Off Hand", 18 to "Ranged", 19 to "Tabard",
    )

    /** where every slot is; [boxes] holds the ammo slot under key 0 when it is shown */
    class Layout(val boxes: Map<Int, RectF>, val bottom: Float)

    /**
     * The two columns start at x [leftX] and [rightX] (a slot's left edge), rows [pitch] apart from [top],
     * slots [s] square. The weapons row stands at y [weaponsY], centred on [weaponsCx], [weaponGap] apart.
     * [ammo] adds the game's small ammo slot (0.42 of a slot) to the right of the ranged slot;
     * [ranged] = false leaves the ranged slot out.
     */
    fun layout(leftX: Float, rightX: Float, top: Float, s: Float, pitch: Float,
               weaponsY: Float, weaponsCx: Float, weaponGap: Float,
               ammo: Boolean = false, ranged: Boolean = true): Layout {
        val boxes = LinkedHashMap<Int, RectF>()
        val weapons = if (ranged) 3 else 2
        val x0 = weaponsCx - (weapons * s + (weapons - 1) * weaponGap) / 2f
        var bottom = 0f
        for ((slot, pos) in SLOTS) {
            val (col, row) = pos
            if (slot == 18 && !ranged) continue
            val r = when (col) {
                0 -> RectF(leftX, top + row * pitch, leftX + s, top + row * pitch + s)
                1 -> RectF(rightX, top + row * pitch, rightX + s, top + row * pitch + s)
                else -> { val x = x0 + row * (s + weaponGap); RectF(x, weaponsY, x + s, weaponsY + s) }
            }
            boxes[slot] = r
            bottom = maxOf(bottom, r.bottom)
        }
        if (ammo && ranged) boxes[18]?.let { rg ->
            val a = s * 0.42f
            boxes[0] = RectF(rg.right + weaponGap * 0.5f, rg.bottom - a, rg.right + weaponGap * 0.5f + a, rg.bottom)
        }
        return Layout(boxes, bottom)
    }

    private val pBmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val pDim = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = 60 }
    private val pTint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val pBroken = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = PorterDuffColorFilter(Color.rgb(255, 70, 70), PorterDuff.Mode.MULTIPLY) }
    private val pEmpty = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.raised }
    private val pCount = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }

    /**
     * Every slot of [l]: the worn item (icon, quality frame, the game's red at 20 % durability or less), or
     * the game's empty art. [dimmed] greys a slot (the Bags room: the one being dragged). [ammo] is
     * `bags.equipped.ammo` (its icon and count); [relic] gives the empty ranged slot the relic's art.
     */
    fun draw(c: Canvas, l: Layout, worn: Map<Int, JSONObject>, ammo: JSONObject? = null, relic: Boolean = false,
             dimmed: (Int) -> Boolean = { false }, alertColours: org.json.JSONArray? = null) {
        for ((slot, box) in l.boxes) {
            val w = if (slot == 0) ammo else worn[slot]
            if (w != null && w.has("id")) {
                val inset = RectF(box.left + 4f, box.top + 4f, box.right - 4f, box.bottom - 4f)
                val icon = Icons.getOrUnknown(if (w.has("icon")) w.optInt("icon") else null)
                val dim = dimmed(slot)
                if (icon != null) c.drawBitmap(icon, null, inset, if (dim) pDim else pBmp)
                else c.drawRoundRect(inset, 6f, 6f, pEmpty)
                val q = if (w.has("quality")) w.optInt("quality", -1) else -1
                if (!dim && q >= 2) Icons.ui("whiteiconframe")?.let { b ->     // the game borders uncommon and better
                    pTint.colorFilter = PorterDuffColorFilter(Theme.quality(q), PorterDuff.Mode.MULTIPLY)
                    c.drawBitmap(b, null, box, pTint)
                }
                val dur = if (w.has("dur")) w.optInt("dur") else 100
                if (!dim && dur in 0..20 && icon != null) c.drawBitmap(icon, null, inset, pBroken)
                if (!dim) durability(c, box, w, alertColours)
                val n = if (slot == 0 && w.has("count")) w.optInt("count") else 0
                if (n > 1) {
                    // the game's outlined count, bottom right
                    pCount.textSize = box.width() * 0.34f
                    UiKit.asNumber(pCount, shadow = false)
                    val tx = box.right - 3f; val ty = box.bottom - 3f
                    pCount.style = Paint.Style.STROKE; pCount.strokeWidth = 4f; pCount.color = Color.BLACK
                    c.drawText(n.toString(), tx, ty, pCount)
                    pCount.style = Paint.Style.FILL; pCount.color = Color.WHITE
                    c.drawText(n.toString(), tx, ty, pCount)
                }
            } else {
                val name = if (slot == 18 && relic) "relic" else (EMPTY_ART[slot] ?: "generic")
                val art = Icons.ui("ui-paperdoll-slot-$name")
                if (art != null) c.drawBitmap(art, null, box, pBmp) else c.drawRoundRect(box, 6f, 6f, pEmpty)
            }
        }
    }

    // ---------------------------------------------------------------- DURABILITY (f-durability)

    /** the game's colours for a damaged (1) / broken (2) piece: INVENTORY_ALERT_COLORS as the add-on read them, else the game's values */
    private val ALERT_DEFAULT = intArrayOf(Color.rgb(255, 209, 46), Color.rgb(237, 18, 18))
    fun alertColour(alert: Int, sent: org.json.JSONArray?): Int {
        if (alert !in 1..2) return Color.WHITE
        val hex = sent?.optString(alert - 1, "")?.takeIf { it.length == 6 }
        return hex?.let { runCatching { Color.parseColor("#$it") }.getOrNull() } ?: ALERT_DEFAULT[alert - 1]
    }

    private val pDur = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    /**
     * A PIECE'S DURABILITY on its slot (the user's pick, 15 Oct: "per-item percentages"): its percentage along the slot's bottom
     * edge, outlined as the game's counts are, whenever it is below 100 %; in the game's yellow / red when the game itself calls
     * it damaged / broken ([w]'s `alert`), else white (a piece in the bags: red at 0 %, the one fact the game shows there).
     * Nothing for a piece without durability (a ring, a trinket).
     */
    fun durability(c: Canvas, box: RectF, w: JSONObject, alertColours: org.json.JSONArray? = null) {
        if (!w.has("dur")) return
        val dur = w.optInt("dur", 100)
        if (dur >= 100) return
        val alert = if (w.has("alert")) w.optInt("alert") else if (dur <= 0) 2 else 0
        pDur.textSize = box.width() * 0.27f
        UiKit.asNumber(pDur, shadow = false)
        val t = "$dur%"
        val tx = box.centerX(); val ty = box.bottom - box.height() * 0.07f
        pDur.style = Paint.Style.STROKE; pDur.strokeWidth = 4f; pDur.color = Color.BLACK
        c.drawText(t, tx, ty, pDur)
        pDur.style = Paint.Style.FILL; pDur.color = alertColour(alert, alertColours)
        c.drawText(t, tx, ty, pDur)
    }

    private val pPart = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val srcR = Rect()
    private val dstR = RectF()

    /**
     * THE GAME'S DURABILITY WARNING, the little armoured figure (DurabilityFrame) the game shows while a piece is damaged or broken:
     * drawn from [durw] (`bags.equipped.durw`: the pieces exactly as the game drew them, each its texture, crop, place and the colour
     * the game gave it), fitted into [box] keeping its shape, against [box]'s top-right corner as the game keeps it by the minimap.
     * Draws nothing (returns false) while the game does not show it, or before its art is on the device.
     */
    fun warning(c: Canvas, box: RectF, durw: JSONObject?): Boolean {
        if (durw == null || !durw.optBoolean("show")) return false
        val parts = durw.optJSONArray("parts") ?: return false
        val fw = durw.optDouble("w", 0.0).toFloat(); val fh = durw.optDouble("h", 0.0).toFloat()
        if (fw <= 0f || fh <= 0f) return false
        val k = minOf(box.width() / fw, box.height() / fh)
        val ox = box.right - fw * k; val oy = box.top
        var drew = false
        for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i) ?: continue
            val bmp = Icons.get(p.optInt("f")) ?: continue
            val tc = p.optJSONArray("tc")
            if (tc != null && tc.length() == 4) {
                srcR.set((tc.optDouble(0) * bmp.width).toInt(), (tc.optDouble(2) * bmp.height).toInt(),
                    (tc.optDouble(1) * bmp.width).toInt(), (tc.optDouble(3) * bmp.height).toInt())
            } else srcR.set(0, 0, bmp.width, bmp.height)
            if (srcR.width() <= 0 || srcR.height() <= 0) continue
            val x = p.optDouble("x").toFloat(); val y = p.optDouble("y").toFloat()
            dstR.set(ox + x * k, oy + y * k, ox + (x + p.optDouble("w").toFloat()) * k, oy + (y + p.optDouble("h").toFloat()) * k)
            val hex = p.optString("c", "")
            pPart.colorFilter = if (hex.length == 6) runCatching {
                PorterDuffColorFilter(Color.parseColor("#$hex"), PorterDuff.Mode.MULTIPLY) }.getOrNull() else null
            c.drawBitmap(bmp, srcR, dstR, pPart)
            drew = true
        }
        return drew
    }

    /** the slot under ([x], [y]) in the layout's own coordinates: -1 = none, 0 = the ammo slot */
    fun hit(l: Layout, x: Float, y: Float): Int {
        for ((slot, box) in l.boxes) if (box.contains(x, y)) return slot
        return -1
    }
}
