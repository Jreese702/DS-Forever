package com.jrees.wowcompanion

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * THE QUEST PANEL (the map module's Y panel, shared with the Map room: b-maproom-quests; the user on test61: "Selecting a quest with A
 * opens that quest in the quest panel"): a quest's title, level, objectives and description in a scrolling box with Share / Abandon /
 * Close, the cursor able to go into it (Right; Up / Down scroll the text, then the buttons; Left / B back out). Moved out of MapView
 * as it was; the view hosts it ([Host]: its quests document, its notes, its tap-pad actions, its redraw).
 */
class QuestPanel(private val host: Host) {
    interface Host {
        fun quest(id: Int): JSONObject?
        fun tracked(id: Int): Boolean
        fun colour(q: JSONObject): Int
        fun party(): Boolean
        fun note(text: String)
        fun share(id: Int)
        fun abandonArm(id: Int)
        fun abandonConfirm(id: Int)
        /** the panel changed: the view syncs its hints and redraws */
        fun changed()
        fun redrawIn(ms: Long)
    }
    private val cGold = Color.parseColor("#FFD100")
    private val cDim = Color.parseColor("#8F8168")
    private fun ellipsize(p: Paint, t: String, w: Float): String {
        if (p.measureText(t) <= w) return t
        var n = t.length
        while (n > 1 && p.measureText(t, 0, n) + p.measureText("...") > w) n--
        return t.substring(0, n).trimEnd() + "..."
    }

    val isOpen get() = id != 0
    fun open(id: Int) { this.id = id; armedId = 0; scroll = 0f; contentH = 0f; focus = -1; host.changed() }
    fun close() { id = 0; armedId = 0; focus = -1; hits.clear() }

    var id = 0
    var armedId = 0
    var armedTitle = ""
    var armedAt = 0L
    var scroll = 0f
    var contentH = 0f
    var viewH = 0f
    val box = RectF()
    val hits = ArrayList<Pair<RectF, String>>()
    private val ARM_MS = 10_000L                     // Quests.lua ARM_SECONDS: a confirm after it is refused


    var focus = -1
    fun buttons(): List<String> = if (armedNow()) listOf("cancel", "abandon!") else listOf("share", "abandon", "close")
    fun armedNow(): Boolean = armedId != 0 && armedId == id && android.os.SystemClock.elapsedRealtime() - armedAt < ARM_MS

    /** one key while the panel is open: true = the panel took it (the cursor is in the box, or the key moves it in / closes) */
    fun key(k: String): Boolean {
        if (focus < 0) {
            // the cursor is on the list: Right goes into the box, B closes the panel; the rest is the list's (the panel follows it)
            when (k) {
                "PADDRIGHT" -> { focus = 0; host.changed(); return true }
                "PAD2" -> { close(); host.changed(); return true }
            }
            return false
        }
        val buttons = buttons()
        if (armedNow() && focus > buttons.size) focus = buttons.size
        val moreText = contentH - scroll > viewH + 1f
        when (k) {
            "PAD2" -> if (armedNow()) armedId = 0 else focus = -1
            "PADDLEFT" -> focus = if (focus <= 1) -1 else focus - 1
            "PADDRIGHT" -> if (focus in 1 until buttons.size) focus++
            "PADDUP" -> if (focus == 0) scroll = (scroll - 90f).coerceAtLeast(0f) else focus = 0
            "PADDDOWN" -> if (focus == 0) {
                if (moreText) scroll = (scroll + 90f).coerceAtMost(max(0f, contentH - viewH)) else focus = 1
            }
            "PAD1" -> if (focus >= 1) act(buttons[focus - 1])
        }
        host.changed()
        return true
    }

    fun act(act: String) {
        val id = id
        if (id == 0) return
        when (act) {
            "share" -> {
                val q = host.quest(id) ?: return
                if (!host.party()) { host.note("You are not in a party."); return }          // the game's ERR_QUEST_PUSH_NOT_IN_PARTY_S
                if (!q.optBoolean("push")) { host.note("That quest cannot be shared."); return }
                host.share(id)
            }
            "abandon" -> {
                if (host.quest(id)?.optBoolean("canAbandon", true) == false) { host.note("That quest cannot be abandoned."); return }
                host.abandonArm(id)
            }
            "abandon!" -> if (armedNow()) { armedId = 0; host.abandonConfirm(id) }
            "close" -> { close(); return }
            "cancel" -> armedId = 0
        }
        host.changed()
    }

    /** {"type":"questack"}: Abandon's arm opens the question; a share / abandon answer is said */
    fun onAck(msg: JSONObject) {
        val op = msg.optString("op")
        val id = msg.optInt("id", 0)
        if (id == 0 || (id != 0 && id != id)) return
        val ok = msg.optBoolean("ok")
        when (op) {
            "arm" -> if (ok) { armedId = id; armedTitle = msg.optString("title", "this quest"); armedAt = android.os.SystemClock.elapsedRealtime() }
                else host.note(msg.optString("why", "").ifEmpty { "The game did not abandon it" })
            "abandon" -> if (ok) { close(); host.note("Abandoned") } else host.note(msg.optString("why", "").ifEmpty { "The game did not abandon it" })
            "share" -> host.note(if (ok) "Shared" else msg.optString("why", "").ifEmpty { "The game did not share it" })
            else -> return
        }
        host.changed()
    }

    private fun wrap(text: String, p: Paint, w: Float): android.text.StaticLayout =
        android.text.StaticLayout.Builder.obtain(text, 0, text.length, android.text.TextPaint(p), w.toInt().coerceAtLeast(10)).setLineSpacing(0f, 1.05f).build()

    fun draw(c: Canvas, x: Float, y0: Float, yMax: Float, maxW: Float) {
        hits.clear()
        val q = host.quest(id)
        if (q == null) { close(); return }                     // it left the log
        val w = min(680f, maxW)
        if (w < 260f || yMax - y0 < 260f) return
        val pad = 20f
        val btnH = 84f
        val armed = armedNow()
        // as tall as its text needs (a tooltip, not a pane: the user), at most down to the field's bottom
        val want = if (contentH > 0f) 10f + 16f + contentH + 8f + (if (armed) 46f else 0f) + 16f + btnH + 10f else Float.MAX_VALUE
        box.set(x, y0, x + w, min(yMax, y0 + max(want, 360f)))
        val inner = UiKit.menu(c, box, solid = true)
        val tw = inner.width() - 2f * pad
        val actTop = inner.bottom - btnH - 16f - (if (armed) 46f else 0f)
        // the text, scrolled, clipped above the buttons
        viewH = actTop - inner.top - 8f
        scroll = scroll.coerceIn(0f, max(0f, contentH - viewH))
        c.save()
        c.clipRect(inner.left, inner.top, inner.right, actTop - 8f)
        c.translate(inner.left + pad, inner.top + 16f - scroll)
        var y = 0f
        val tl = wrap(q.optString("title", "Quest"), UiKit.bodyPaint(32f, host.colour(q)), tw); tl.draw(c); y += tl.height + 8f
        val meta = listOfNotNull(if (q.has("lv")) "Level ${q.optInt("lv")}" else null, q.optString("zone", "").ifEmpty { null },
            if (q.optInt("grp", 0) > 1) "Group ${q.optInt("grp")}" else null,
            if (host.tracked(id)) "Tracked" else "Not tracked").joinToString("  ·  ")
        c.drawText(ellipsize(UiKit.bodyPaint(22f, cDim), meta, tw), 0f, y + 22f, UiKit.bodyPaint(22f, cDim)); y += 38f
        when {
            q.optBoolean("failed") -> { c.drawText("Failed", 0f, y + 24f, UiKit.bodyPaint(25f, Color.rgb(230, 70, 50))); y += 38f }
            q.optBoolean("ready") || q.optBoolean("done") -> { c.drawText("Ready to turn in", 0f, y + 24f, UiKit.bodyPaint(25f, Theme.ok)); y += 38f }
        }
        q.optString("wp", "").takeIf { it.isNotEmpty() }?.let {
            val l = wrap("Next: $it", UiKit.bodyPaint(24f, Theme.text), tw); c.save(); c.translate(0f, y); l.draw(c); c.restore(); y += l.height + 10f
        }
        val objs = q.optJSONArray("obj")
        if (objs != null && objs.length() > 0) {
            y += 6f
            c.drawText("Objectives", 0f, y + 26f, UiKit.bodyPaint(27f, cGold)); y += 42f
            for (i in 0 until objs.length()) {
                val o = objs.optJSONObject(i) ?: continue
                val fin = o.optBoolean("f")
                val t = o.optString("t", "").ifEmpty { if (o.has("need")) "${o.optInt("have")}/${o.optInt("need")}" else "objective" }
                val l = wrap(t, UiKit.bodyPaint(24f, if (fin) Theme.dim else Theme.text), tw - 34f)
                UiKit.objectiveMark(c, 13f, y + 15f, 22f, done = fin)
                c.save(); c.translate(34f, y); l.draw(c); c.restore()
                y += l.height + 10f
            }
        }
        q.optString("desc", "").takeIf { it.isNotEmpty() }?.let { desc ->
            y += 14f
            val edge = 28f
            val l = wrap(desc, UiKit.bodyPaint(23f, Color.rgb(48, 32, 16)), tw + 16f - edge * 2.4f)
            val box = RectF(-8f, y, tw + 8f, y + l.height + 44f + edge * 2.2f + 8f)
            val pin = UiKit.parchment(c, box, edge)
            c.drawText("Description", pin.left, pin.top + 26f, UiKit.bodyPaint(26f, Color.rgb(64, 40, 16)))
            c.save(); c.translate(pin.left, pin.top + 44f); l.draw(c); c.restore()
            y = box.bottom + 12f
        }
        contentH = y + 24f
        c.restore()
        if (contentH - scroll > viewH + 1f) {
            val cue = RectF(inner.left, inner.top, inner.right, actTop - 14f)
            UiKit.moveArrowsUpDown(c, cue, up = false, down = true, size = 18f, width = 26f, around = true)
        }
        // the buttons: Share, Abandon and Close (Cancel and Abandon while the game asks)
        val gap = 10f
        val nb = if (armed) 2 else 3
        val bw = (inner.width() - 2f * pad - gap * (nb - 1)) / nb
        val b1 = RectF(inner.left + pad, inner.bottom - btnH - 12f, inner.left + pad + bw, inner.bottom - 12f)
        val b2 = RectF(b1.right + gap, b1.top, b1.right + gap + bw, b1.bottom)
        val b3 = RectF(b2.right + gap, b1.top, b2.right + gap + bw, b1.bottom)
        if (armed) {
            val qp = UiKit.bodyPaint(25f, Theme.text)
            c.drawText(ellipsize(qp, "Abandon \"$armedTitle\"?", tw), inner.left + pad, b1.top - 16f, qp)
            UiKit.button(c, b1, "Cancel")
            UiKit.button(c, b2, "Abandon")
            hits.add(RectF(b1) to "cancel"); hits.add(RectF(b2) to "abandon!")
        } else {
            val shareOk = host.party() && q.optBoolean("push")
            val abandonOk = q.optBoolean("canAbandon", true)
            val s1 = if (shareOk) UiKit.Button.UP else UiKit.Button.DISABLED
            val s2 = if (abandonOk) UiKit.Button.UP else UiKit.Button.DISABLED
            UiKit.button(c, b1, "Share", s1)
            UiKit.button(c, b2, "Abandon", s2)
            UiKit.button(c, b3, "Close")
            hits.add(RectF(b1) to "share"); hits.add(RectF(b2) to "abandon"); hits.add(RectF(b3) to "close")
        }
        // the cursor in the box: on the text (with its up / down arrows) or on a button
        when {
            focus == 0 -> {
                val tb = RectF(inner.left + 4f, inner.top + 4f, inner.right - 4f, actTop - 10f)
                UiKit.focus(c, tb)
            }
            focus >= 1 -> listOf(b1, b2, b3).getOrNull(focus - 1)?.let { UiKit.focus(c, it) }
        }
        if (armedId != 0 && !armed) { armedId = 0 }                   // the game's question ran out
        else if (armed) host.redrawIn(500)
    }


}
