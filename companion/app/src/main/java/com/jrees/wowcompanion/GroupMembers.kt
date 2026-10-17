package com.jrees.wowcompanion

import org.json.JSONArray

/**
 * THE GROUP ON THE MAP (b-group-map; the user: party / raid members on the Map room, the map module and Home's small map, in their
 * class colours, names in the module and the room). Map.lua sends the members once a second on the position tick: map.grp = [{n, c,
 * col, wx, wy, cont, dead, off}], grpN = their count (0 once when the group goes). [col] is the game's own RAID_CLASS_COLORS colorStr
 * ("aarrggbb"), so the app keeps no colour table. Pure: the document in, the members out.
 */
object GroupMembers {
    class Member(val name: String, val cls: String?, val color: Int, val wx: Double, val wy: Double, val cont: Int?, val dead: Boolean,
                 val offline: Boolean)

    /** the members of a position document's `grp` (null / empty = no group) */
    fun parse(a: JSONArray?): List<Member> {
        if (a == null) return emptyList()
        val out = ArrayList<Member>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val wx = o.optDouble("wx", Double.NaN); val wy = o.optDouble("wy", Double.NaN)
            if (wx.isNaN() || wy.isNaN()) continue
            out.add(Member(o.optString("n", "?"), o.optString("c", "").ifEmpty { null }, color(o.optString("col", "")), wx, wy,
                if (o.has("cont")) o.optInt("cont") else null, o.optBoolean("dead", false), o.optBoolean("off", false)))
        }
        return out
    }

    private val pDot by lazy { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
    private val pText by lazy { UiKit.bodyPaint(20f, 0xFFFFFFFF.toInt()).apply { textAlign = android.graphics.Paint.Align.CENTER } }

    /**
     * one member at ([x], [y]) on the screen: a dot in the class colour with a dark ring (dimmed when dead, hollow when offline), its
     * name above it when [named]; off the field ([inside] false: ([x], [y]) is then the rim point and [ang] the way on) a small chevron
     */
    fun draw(c: android.graphics.Canvas, m: Member, x: Float, y: Float, named: Boolean, inside: Boolean = true, ang: Float = 0f) {
        val a = if (m.dead) 0.45f else 1f
        val col = (m.color and 0x00FFFFFF) or ((255 * a).toInt() shl 24)
        if (!inside) {
            c.save(); c.rotate(Math.toDegrees(ang.toDouble()).toFloat(), x, y)
            val p = android.graphics.Path().apply { moveTo(x + 13f, y); lineTo(x - 7f, y - 10f); lineTo(x - 7f, y + 10f); close() }
            pDot.style = android.graphics.Paint.Style.FILL; pDot.color = col; c.drawPath(p, pDot)
            pDot.style = android.graphics.Paint.Style.STROKE; pDot.strokeWidth = 2f; pDot.color = (0xCC shl 24); c.drawPath(p, pDot)
            c.restore(); return
        }
        pDot.style = android.graphics.Paint.Style.FILL
        pDot.color = ((210 * a).toInt() shl 24); c.drawCircle(x, y, 11f, pDot)                 // the dark ring
        if (m.offline) { pDot.style = android.graphics.Paint.Style.STROKE; pDot.strokeWidth = 3f }
        pDot.color = col; c.drawCircle(x, y, if (m.offline) 6.5f else 8f, pDot)
        pDot.style = android.graphics.Paint.Style.FILL
        if (named) {
            pText.color = col; pText.setShadowLayer(3f, 0f, 0f, android.graphics.Color.BLACK)
            c.drawText(if (m.dead) "${m.name} (dead)" else m.name, x, y - 17f, pText)
        }
    }

    /** "aarrggbb" (the game's colorStr) as a colour; a soft grey when it is missing or malformed */
    fun color(s: String): Int = s.takeIf { it.length == 8 }?.toLongOrNull(16)?.toInt()?.let { it or (0xFF shl 24) } ?: 0xFFB0B0B0.toInt()
}
