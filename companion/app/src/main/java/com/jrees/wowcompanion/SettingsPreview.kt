package com.jrees.wowcompanion

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * "WHAT IT DOES" (board f-settings-tabs, phase 2; the user's approved plan and mocks, 2026-10-15): a small picture beside the
 * Settings room's focused row showing what that setting does, at its CURRENT value; animated where movement says it better (the
 * swing line, the frame pacing, the wheels...). The user's rule: the bottom screen's settings get one, the top screen's Show / Hide
 * do not, nor rows whose words say it all. Our own drawing (no game art): shapes in Theme's colours, the button pictures through
 * [Glyph] (the app gives UiKit.pad, so they are the chosen set's).
 *
 * Pure drawing on android.graphics: [draw] paints row [id]'s preview into [r] for time [tMs]; the panel view calls it each frame
 * while [animated], once otherwise. Keyed by the row's own words (SettingsTabs' rows).
 */
object SettingsPreview {
    /** the setting values the pictures show (the panel snapshots them from the settings objects) */
    data class State(
        val diamonds: Boolean = true, val combatGrow: Boolean = true, val swing: Boolean = true, val questOverMap: Boolean = true,
        val startAttack: Boolean? = null, val toolbar: Boolean? = null,
        val abxyNintendo: Boolean = true, val glyphSet: Int = 0,
        val pauseTap: Boolean = true, val pausePickA: Boolean = true, val radialTap: Boolean = true, val radialPickA: Boolean = true,
        val miniOutYd: Int = 80, val miniInYd: Int = 60, val quickChat: Boolean = true,
        val fpsLimit: Int = 40, val frameGen: Boolean = false, val multiplier: Int = 2, val flow: Float = 0.8f, val indoorBuild: Boolean = true,
    )

    /** a button picture: [action] "a" "b" "x" "y" "start", in glyph set [set] (0 Nintendo, 1 Xbox, 2 PlayStation), centred, [size] px */
    fun interface Glyph { fun draw(c: Canvas, action: String, set: Int, cx: Float, cy: Float, size: Float) }

    /** the rows with a picture, in the tabs' order; the value says whether it moves */
    val ROWS: Map<String, Boolean> = linkedMapOf(
        "Controller on Home" to false, "Combat layout" to true, "Swing timer" to true, "Focused quest" to false,
        "Start the attack when a spell is cast" to true, "DS WoW toolbar" to false,
        "ABXY location" to true, "Button glyphs" to false,
        "Pause wheel: open" to true, "Pause wheel: pick" to true, "Item & spell wheels: open" to true, "Item & spell wheels: pick" to true,
        "Minimap zoom outdoors" to false, "Minimap zoom indoors" to false, "Message input" to false,
        "Frame limit" to true, "Frame generation" to true, "Multiplier" to true, "Flow scale" to true, "Build in the background" to true)

    fun has(id: String?): Boolean = id != null && id in ROWS
    fun animated(id: String?): Boolean = ROWS[id] == true

    /** the picture's title: the row and its value now */
    fun title(id: String, s: State): String = when (id) {
        "Controller on Home" -> if (s.diamonds) "Diamonds" else "Thin row"
        "Combat layout" -> if (s.combatGrow) "Grow" else "Same size"
        "Swing timer" -> onOff(s.swing)
        "Focused quest" -> if (s.questOverMap) "Above Minimap" else "Above Chat"
        "Start the attack when a spell is cast" -> s.startAttack?.let { onOff(it) } ?: "-"
        "DS WoW toolbar" -> s.toolbar?.let { onOff(it) } ?: "-"
        "ABXY location" -> if (s.abxyNintendo) "Nintendo" else "Xbox"
        "Button glyphs" -> SET_NAMES[s.glyphSet.coerceIn(0, 2)]
        "Pause wheel: open" -> if (s.pauseTap) "Tap" else "Hold"
        "Item & spell wheels: open" -> "open with " + if (s.radialTap) "Tap" else "Hold"
        "Pause wheel: pick" -> if (s.pausePickA) "A" else "Release"
        "Item & spell wheels: pick" -> "pick with " + if (s.radialPickA) "A" else "Release"
        "Minimap zoom outdoors" -> "${s.miniOutYd} yd"
        "Minimap zoom indoors" -> "${s.miniInYd} yd"
        "Message input" -> if (s.quickChat) "Quick chat" else "Keyboard"
        "Frame limit" -> if (s.fpsLimit > 0) "${s.fpsLimit}" else "Off"
        "Frame generation" -> onOff(s.frameGen)
        "Multiplier" -> "${s.multiplier}x"
        "Flow scale" -> "${Math.round(s.flow * 100)}%"
        "Build in the background" -> onOff(s.indoorBuild)
        else -> ""
    }.let { v -> val n = SHORT[id] ?: id; if (v.isEmpty()) n else "$n: $v" }

    /** a row's name in the picture's title when the row's own words are too long for the panel */
    private val SHORT = mapOf("Start the attack when a spell is cast" to "Start the attack", "Item & spell wheels: open" to "Item & spell wheels",
        "Item & spell wheels: pick" to "Item & spell wheels")

    /** two or three short lines under the picture */
    fun caption(id: String, s: State): List<String> = when (id) {
        "Controller on Home" -> if (s.diamonds) listOf("Home shows your buttons as the four", "diamonds, as on the controller.")
            else listOf("Home shows your buttons in one thin row;", "in a fight the diamonds take over.")
        "Combat layout" -> if (s.combatGrow) listOf("In a fight the controller grows", "so the buttons are easier to read.")
            else listOf("In a fight the controller keeps", "its size and place.")
        "Swing timer" -> if (s.swing) listOf("A thin line under your buttons fills", "with each auto-attack swing, in a fight.")
            else listOf("No swing line. The cast bar stays.")
        "Focused quest" -> listOf("Where the quest you focus shows on Home:", if (s.questOverMap) "over the minimap." else "at the top of the chat.")
        "Start the attack when a spell is cast" -> if (s.startAttack == true) listOf("A harmful spell from your controller", "also starts your auto-attack.")
            else listOf("Spells do not start your auto-attack;", "start it yourself.")
        "DS WoW toolbar" -> if (s.toolbar == true) listOf("The plain D-pad opens the map, chat,", "the bar and the wheel.")
            else listOf("The D-pad keeps your own slots.")
        "ABXY location" -> listOf("Which face button does what (for button", "mods). The pictures follow \"Button glyphs\".")
        "Button glyphs" -> listOf("The button pictures shown everywhere.", "Where they sit is \"ABXY location\".")
        "Pause wheel: open", "Item & spell wheels: open" -> if (tapOf(id, s)) listOf("A press opens the wheel;", "it stays open until you pick or press B.")
            else listOf("Hold the button: the wheel is open", "while you hold it, and closes when you let go.")
        "Pause wheel: pick", "Item & spell wheels: pick" -> if (pickAOf(id, s)) listOf("Aim with the stick, then press A to pick.")
            else listOf("Aim with the stick; letting go of", "the stick picks what it points at.")
        "Minimap zoom outdoors" -> listOf("How many yards across Home's small map", "shows outside.")
        "Minimap zoom indoors" -> listOf("How many yards across Home's small map", "shows inside buildings.")
        "Message input" -> if (s.quickChat) listOf("Going into a message opens the", "quick-chat phrases first.")
            else listOf("Going into a message opens the", "keyboard first.")
        "Frame limit" -> if (s.fpsLimit > 0) listOf("Each dot is a frame the game draws:", "fewer frames run cooler and last longer.")
            else listOf("No limit: the game draws as many", "frames as it can (warmer).")
        "Frame generation" -> if (s.frameGen) listOf("Filled: the game's frames. Rings: frames", "made between them, for smoother motion.")
            else listOf("Only the game's own frames.")
        "Multiplier" -> listOf("Frames shown for each game frame:", "${s.multiplier}x = ${s.multiplier - 1} made between each pair.")
        "Flow scale" -> listOf("How much detail the made frames keep:", "lower is faster, higher is sharper.")
        "Build in the background" -> if (s.indoorBuild) listOf("Indoor maps are built while you stand", "still and the device is cool; walking pauses.")
            else listOf("Nothing is built; ready maps still show.")
        else -> emptyList()
    }

    // ------------------------------------------------------------------ drawing

    private val SET_NAMES = listOf("Nintendo", "Xbox", "PlayStation")
    // FrameGen's values (kept here so this file draws on its own, for the off-device renders)
    private val MULTIPLIERS = listOf(2, 3, 4)
    private const val FLOW_MIN = 0.25f
    private const val FLOW_MAX = 1.0f
    private fun onOff(b: Boolean) = if (b) "On" else "Off"
    private fun tapOf(id: String, s: State) = if (id.startsWith("Pause")) s.pauseTap else s.radialTap
    private fun pickAOf(id: String, s: State) = if (id.startsWith("Pause")) s.pausePickA else s.radialPickA

    // the drawing's paints and colours, made at the first picture (the text and the rows above stay plain data: the JVM tests read them)
    private val pFill by lazy { Paint(Paint.ANTI_ALIAS_FLAG) }
    private val pStroke by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND } }
    private val pText by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 20f } }

    private val GOLD get() = Theme.gold
    private val BRONZE get() = Theme.bronze
    private val DIMC get() = Theme.dim
    private val TEXT get() = Theme.text
    private val GROUND get() = Color.rgb(48, 38, 26)
    private val FLIGHT get() = Theme.flight
    private val SPELLS by lazy { intArrayOf(Color.rgb(150, 100, 40), Color.rgb(50, 95, 160), Color.rgb(150, 45, 40), Color.rgb(60, 130, 60),
        Color.rgb(120, 70, 150), Color.rgb(170, 140, 50)) }

    /** row [id]'s picture in [r] at [tMs] for state [s]; [prev] + [sinceMs]: the state before the last change (a change glides) */
    fun draw(c: Canvas, r: RectF, id: String, s: State, tMs: Long, glyph: Glyph, prev: State? = null, sinceMs: Long = Long.MAX_VALUE) {
        val t = tMs / 1000f
        when (id) {
            "Controller on Home" -> homeLayout(c, r, s.diamonds)
            "Combat layout" -> combat(c, r, s.combatGrow, t)
            "Swing timer" -> swing(c, r, s.swing, t)
            "Focused quest" -> focusedQuest(c, r, s.questOverMap)
            "Start the attack when a spell is cast" -> startAttack(c, r, s.startAttack == true, t)
            "DS WoW toolbar" -> toolbar(c, r, s.toolbar == true)
            "ABXY location" -> abxy(c, r, s, prev, sinceMs, glyph)
            "Button glyphs" -> glyphSets(c, r, s.glyphSet, glyph)
            "Pause wheel: open", "Item & spell wheels: open" -> wheelOpen(c, r, tapOf(id, s), t, id.startsWith("Pause"))
            "Pause wheel: pick", "Item & spell wheels: pick" -> wheelPick(c, r, pickAOf(id, s), t, glyph, s.glyphSet)
            "Minimap zoom outdoors" -> minimap(c, r, s.miniOutYd, false)
            "Minimap zoom indoors" -> minimap(c, r, s.miniInYd, true)
            "Message input" -> chatInput(c, r, s.quickChat)
            "Frame limit" -> frameLimit(c, r, s, t)
            "Frame generation" -> frameGen(c, r, s, t)
            "Multiplier" -> multiplier(c, r, s, t)
            "Flow scale" -> flow(c, r, s.flow, t)
            "Build in the background" -> indoor(c, r, s.indoorBuild, t)
        }
    }

    // ---- small parts

    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float = 20f, col: Int = DIMC, align: Paint.Align = Paint.Align.CENTER) {
        pText.textSize = size; pText.color = col; pText.textAlign = align
        c.drawText(s, x, y, pText)
    }

    /** one controller button: a bronze ring round a spell square */
    private fun button(c: Canvas, cx: Float, cy: Float, rad: Float, i: Int, lit: Float = 0f) {
        pFill.color = GROUND; c.drawCircle(cx, cy, rad, pFill)
        pStroke.color = BRONZE; pStroke.strokeWidth = max(2f, rad * 0.08f); c.drawCircle(cx, cy, rad, pStroke)
        pFill.color = SPELLS[i % SPELLS.size]
        val q = rad * 0.52f
        c.drawRect(cx - q, cy - q, cx + q, cy + q, pFill)
        if (lit > 0f) { pFill.color = Color.argb((160 * lit).toInt(), 255, 240, 180); c.drawCircle(cx, cy, rad, pFill) }
    }

    private fun diamond(c: Canvas, cx: Float, cy: Float, rad: Float, gap: Float, lit: Int = -1, litK: Float = 0f) {
        val pts = listOf(0f to -gap, gap to 0f, 0f to gap, -gap to 0f)
        for ((i, p) in pts.withIndex()) button(c, cx + p.first, cy + p.second, rad, i, if (i == lit) litK else 0f)
    }

    private fun thinRow(c: Canvas, cx: Float, cy: Float, w: Float) {
        val n = 6; val step = w / n; val rad = min(step * 0.42f, 26f)
        for (i in 0 until n) button(c, cx - w / 2f + step * (i + 0.5f), cy, rad, i)
    }

    private fun frame(c: Canvas, r: RectF, col: Int = BRONZE, w: Float = 2f, fill: Int? = null) {
        if (fill != null) { pFill.color = fill; c.drawRoundRect(r, 8f, 8f, pFill) }
        pStroke.color = col; pStroke.strokeWidth = w; c.drawRoundRect(r, 8f, 8f, pStroke)
    }

    /** a mini Home: the small map's circle top-right, the chat bottom-left, the controller bottom-right */
    private fun miniHome(c: Canvas, h: RectF): Triple<RectF, RectF, RectF> {
        frame(c, h, BRONZE, 2f, Color.rgb(20, 16, 10))
        val map = RectF(h.centerX() + 4f, h.top + 12f, h.right - 12f, h.top + h.height() * 0.55f)
        val chat = RectF(h.left + 12f, h.top + h.height() * 0.42f, h.centerX() - 8f, h.bottom - 12f)
        val ctl = RectF(h.centerX() + 4f, h.top + h.height() * 0.6f, h.right - 12f, h.bottom - 12f)
        val mr = min(map.width(), map.height()) / 2f
        pFill.color = Color.rgb(60, 82, 52); c.drawCircle(map.centerX(), map.centerY(), mr, pFill)
        pStroke.color = BRONZE; pStroke.strokeWidth = 2f; c.drawCircle(map.centerX(), map.centerY(), mr, pStroke)
        frame(c, chat, Theme.bronzeDim, 2f, Color.rgb(28, 22, 15))
        pFill.color = DIMC
        for (i in 0 until 3) c.drawRect(chat.left + 10f, chat.bottom - 18f - i * 16f, chat.right - 10f - i * 18f, chat.bottom - 12f - i * 16f, pFill)
        return Triple(map, chat, ctl)
    }

    // ---- Home

    private fun homeLayout(c: Canvas, r: RectF, diamonds: Boolean) {
        val h = RectF(r.left + 10f, r.top + 10f, r.right - 10f, r.bottom - 10f)
        val (_, _, ctl) = miniHome(c, h)
        if (diamonds) diamond(c, ctl.centerX(), ctl.centerY(), min(ctl.height() / 6.5f, 22f), min(ctl.height() / 3.2f, 44f))
        else thinRow(c, ctl.centerX(), ctl.centerY(), ctl.width())
    }

    private fun combat(c: Canvas, r: RectF, grow: Boolean, t: Float) {
        val inFight = (t % 4f) >= 2f
        val k = ((t % 2f) / 0.4f).coerceIn(0f, 1f)               // the change eases in over 0.4 s
        val sc = if (!grow) 1f else if (inFight) 1f + 0.4f * k else 1.4f - 0.4f * k
        val cx = r.centerX(); val cy = r.centerY() - 10f
        if (inFight) { pStroke.color = Color.argb((120 * k).toInt(), 200, 30, 30); pStroke.strokeWidth = 10f; c.drawRoundRect(r, 10f, 10f, pStroke) }
        diamond(c, cx, cy, 26f * sc, 58f * sc)
        text(c, if (inFight) "In a fight" else "Out of combat", cx, r.bottom - 18f, 22f, if (inFight) Color.rgb(230, 120, 100) else DIMC)
    }

    private fun swing(c: Canvas, r: RectF, on: Boolean, t: Float) {
        val cx = r.centerX(); val cy = r.top + r.height() * 0.36f
        diamond(c, cx, cy, 30f, 66f)
        val bar = RectF(r.left + 30f, cy + 120f, r.right - 30f, cy + 136f)
        if (on) {
            val period = 2.4f; val k = (t % period) / period
            pFill.color = Color.rgb(30, 24, 16); c.drawRect(bar, pFill)
            pFill.color = Color.rgb(230, 200, 120); c.drawRect(bar.left, bar.top, bar.left + bar.width() * k, bar.bottom, pFill)
            if (k > 0.94f) { pStroke.color = Color.rgb(255, 240, 180); pStroke.strokeWidth = 2f; c.drawRect(bar.left - 3f, bar.top - 3f, bar.right + 3f, bar.bottom + 3f, pStroke) }
            text(c, "swing", bar.left, bar.bottom + 24f, 18f, DIMC, Paint.Align.LEFT)
        }
        val cb = RectF(r.left + 30f, cy + 168f, r.right - 30f, cy + 190f)
        pFill.color = Color.rgb(30, 24, 16); c.drawRect(cb, pFill)
        pFill.color = Color.rgb(200, 160, 40); c.drawRect(cb.left, cb.top, cb.left + cb.width() * 0.55f, cb.bottom, pFill)
        text(c, "cast bar (always)", cb.left, cb.bottom + 24f, 18f, DIMC, Paint.Align.LEFT)
    }

    private fun focusedQuest(c: Canvas, r: RectF, overMap: Boolean) {
        val h = RectF(r.left + 10f, r.top + 10f, r.right - 10f, r.bottom - 10f)
        val (map, chat, ctl) = miniHome(c, h)
        diamond(c, ctl.centerX(), ctl.centerY(), min(ctl.height() / 6.5f, 18f), min(ctl.height() / 3.2f, 36f))
        val card = if (overMap) RectF(map.left - 6f, map.top - 2f, map.right + 2f, map.top + 46f)
                   else RectF(chat.left, chat.top - 4f, chat.right, chat.top + 44f)
        frame(c, card, GOLD, 3f, Color.rgb(46, 36, 17))
        pFill.color = GOLD; c.drawCircle(card.left + 18f, card.centerY(), 7f, pFill)
        pFill.color = TEXT; c.drawRect(card.left + 34f, card.centerY() - 8f, card.right - 20f, card.centerY() - 3f, pFill)
        pFill.color = DIMC; c.drawRect(card.left + 34f, card.centerY() + 4f, card.right - 50f, card.centerY() + 8f, pFill)
    }

    private fun startAttack(c: Canvas, r: RectF, on: Boolean, t: Float) {
        val k = t % 2.4f
        val press = if (k < 0.25f) 1f - k / 0.25f else 0f
        val cx = r.centerX()
        button(c, cx - 90f, r.centerY() - 20f, 40f, 2, press)
        text(c, "a harmful spell", cx - 90f, r.centerY() + 52f, 18f)
        // the arrow, then the crossed swords (the auto-attack) lighting when On
        pStroke.color = DIMC; pStroke.strokeWidth = 3f
        c.drawLine(cx - 36f, r.centerY() - 20f, cx + 30f, r.centerY() - 20f, pStroke)
        c.drawLine(cx + 30f, r.centerY() - 20f, cx + 18f, r.centerY() - 30f, pStroke)
        c.drawLine(cx + 30f, r.centerY() - 20f, cx + 18f, r.centerY() - 10f, pStroke)
        val lit = on && k in 0.3f..2.0f
        swords(c, cx + 90f, r.centerY() - 20f, 38f, if (lit) GOLD else Theme.bronzeDim)
        text(c, if (on) "your attack starts" else "no auto-attack", cx + 90f, r.centerY() + 52f, 18f, if (lit) GOLD else DIMC)
    }

    private fun swords(c: Canvas, cx: Float, cy: Float, s: Float, col: Int) {
        pStroke.color = col; pStroke.strokeWidth = 6f
        c.drawLine(cx - s, cy + s, cx + s, cy - s, pStroke); c.drawLine(cx - s, cy - s, cx + s, cy + s, pStroke)
        pStroke.strokeWidth = 4f
        c.drawLine(cx - s * 0.7f, cy + s * 0.3f, cx - s * 0.3f, cy + s * 0.7f, pStroke)
        c.drawLine(cx + s * 0.7f, cy + s * 0.3f, cx + s * 0.3f, cy + s * 0.7f, pStroke)
    }

    private fun toolbar(c: Canvas, r: RectF, on: Boolean) {
        val cx = r.centerX(); val cy = r.centerY() - 10f; val a = 54f; val w = 40f
        // the D-pad cross
        pFill.color = Color.rgb(60, 52, 42)
        c.drawRoundRect(cx - w / 2f, cy - a - w / 2f, cx + w / 2f, cy + a + w / 2f, 8f, 8f, pFill)
        c.drawRoundRect(cx - a - w / 2f, cy - w / 2f, cx + a + w / 2f, cy + w / 2f, 8f, 8f, pFill)
        val ends = listOf(Triple(0f, -1f, "Map"), Triple(-1f, 0f, "Chat"), Triple(1f, 0f, "Bar"), Triple(0f, 1f, "Wheel"))
        for ((i, e) in ends.withIndex()) {
            val ex = cx + e.first * (a + 64f); val ey = cy + e.second * (a + 46f)
            if (on) text(c, e.third, ex, ey + 8f, 22f, GOLD)
            else button(c, ex, ey, 22f, i)
        }
    }

    // ---- Controller

    private fun abxy(c: Canvas, r: RectF, s: State, prev: State?, sinceMs: Long, glyph: Glyph) {
        val pos = mapOf("top" to (0f to -1f), "left" to (-1f to 0f), "right" to (1f to 0f), "bottom" to (0f to 1f))
        val xbox = mapOf("a" to "bottom", "b" to "right", "x" to "left", "y" to "top")
        val nin = mapOf("a" to "right", "b" to "bottom", "x" to "top", "y" to "left")
        val to = if (s.abxyNintendo) nin else xbox
        val from = if (prev != null && prev.abxyNintendo != s.abxyNintendo) (if (prev.abxyNintendo) nin else xbox) else to
        var e = (sinceMs / 500f).coerceIn(0f, 1f); e = 1f - (1f - e) * (1f - e) * (1f - e)
        val cx = r.centerX(); val cy = r.centerY() - 16f; val gap = 84f
        val words = mapOf("a" to "confirm", "b" to "back", "x" to "use", "y" to "more")
        for (k in listOf("a", "b", "x", "y")) {
            val (ax, ay) = pos.getValue(from.getValue(k)); val (bx, by) = pos.getValue(to.getValue(k))
            val px = cx + (ax + (bx - ax) * e) * gap; val py = cy + (ay + (by - ay) * e) * gap
            pFill.color = GROUND; c.drawCircle(px, py, 40f, pFill)
            glyph.draw(c, k, s.glyphSet, px, py, 62f)
            text(c, words.getValue(k), px, py + 60f, 18f)
        }
    }

    private fun glyphSets(c: Canvas, r: RectF, set: Int, glyph: Glyph) {
        val cx = r.centerX(); val cy = r.top + r.height() * 0.36f; val gap = 70f
        val pts = listOf("y" to (0f to -1f), "x" to (-1f to 0f), "b" to (1f to 0f), "a" to (0f to 1f))
        for ((k, p) in pts) glyph.draw(c, k, set, cx + p.first * gap, cy + p.second * gap, 66f)
        // the three sets, small, the chosen one framed
        val y = r.top + r.height() * 0.78f; val step = r.width() / 3f
        for (i in 0..2) {
            val x = r.left + step * (i + 0.5f)
            glyph.draw(c, "a", i, x - 20f, y, 36f); glyph.draw(c, "b", i, x + 20f, y, 36f)
            text(c, SET_NAMES[i], x, y + 42f, 18f, if (i == set) GOLD else DIMC)
            if (i == set) frame(c, RectF(x - step / 2f + 8f, y - 30f, x + step / 2f - 8f, y + 54f), GOLD, 2f)
        }
    }

    /** a wheel of six slices at ([cx], [cy]), [k] 0..1 open, [pick] the slice lit (-1 none) */
    private fun wheel(c: Canvas, cx: Float, cy: Float, rad: Float, k: Float, pick: Int = -1, aim: Float = Float.NaN) {
        if (k <= 0f) return
        val rr = rad * (0.7f + 0.3f * k)
        for (i in 0 until 6) {
            val a = (i * 60f - 90f) * (PI / 180f).toFloat()
            val sx = cx + cos(a) * rr * 0.68f; val sy = cy + sin(a) * rr * 0.68f
            val lit = i == pick
            pFill.color = Color.argb((255 * k).toInt(), if (lit) 120 else 46, if (lit) 90 else 36, if (lit) 30 else 22)
            c.drawCircle(sx, sy, rr * 0.27f, pFill)
            pStroke.color = if (lit) GOLD else BRONZE; pStroke.alpha = (255 * k).toInt(); pStroke.strokeWidth = if (lit) 4f else 2f
            c.drawCircle(sx, sy, rr * 0.27f, pStroke)
            pFill.color = SPELLS[i]; pFill.alpha = (255 * k).toInt()
            val q = rr * 0.13f; c.drawRect(sx - q, sy - q, sx + q, sy + q, pFill)
        }
        pStroke.alpha = 255; pFill.alpha = 255
        if (!aim.isNaN()) {
            pStroke.color = GOLD; pStroke.strokeWidth = 4f
            c.drawLine(cx, cy, cx + cos(aim) * rr * 0.38f, cy + sin(aim) * rr * 0.38f, pStroke)
            pFill.color = GOLD; c.drawCircle(cx, cy, 7f, pFill)
        }
    }

    private fun wheelOpen(c: Canvas, r: RectF, tap: Boolean, t: Float, pause: Boolean) {
        val period = 4f; val k = t % period
        // the button's line: down while pressed
        val down = if (tap) k < 0.2f else k < 2.2f
        val open = if (tap) (if (k < 3.2f) ((k - 0.05f) / 0.2f).coerceIn(0f, 1f) else 1f - ((k - 3.2f) / 0.2f).coerceIn(0f, 1f))
                   else (if (k < 2.2f) ((k - 0.05f) / 0.2f).coerceIn(0f, 1f) else 1f - ((k - 2.2f) / 0.2f).coerceIn(0f, 1f))
        wheel(c, r.centerX(), r.top + r.height() * 0.4f, min(r.width(), r.height()) * 0.36f, open)
        // the timeline under it
        val y = r.bottom - 64f; val x0 = r.left + 30f; val x1 = r.right - 30f
        pStroke.color = Theme.bronzeDim; pStroke.strokeWidth = 2f; c.drawLine(x0, y, x1, y, pStroke)
        val nowX = x0 + (x1 - x0) * (k / period)
        val pressEnd = if (tap) 0.2f else 2.2f
        pFill.color = if (down) GOLD else Theme.bronzeDim
        c.drawRect(x0, y - 8f, x0 + (x1 - x0) * (pressEnd / period), y + 8f, pFill)
        pFill.color = TEXT; c.drawCircle(nowX, y, 6f, pFill)
        text(c, (if (pause) "Start" else "button") + if (tap) ": a press" else ": held", x0, y + 30f, 18f, DIMC, Paint.Align.LEFT)
        if (tap) text(c, "B closes", x1, y + 30f, 18f, if (k >= 3.2f) GOLD else DIMC, Paint.Align.RIGHT)
    }

    private fun wheelPick(c: Canvas, r: RectF, pickA: Boolean, t: Float, glyph: Glyph, set: Int) {
        val period = 3f; val k = t % period
        val target = 1                                            // aims at the top-right slice
        val aimTo = ((target * 60f - 90f) * (PI / 180f)).toFloat()
        val cx = r.centerX(); val cy = r.top + r.height() * 0.42f; val rad = min(r.width(), r.height()) * 0.38f
        val aiming = k in 0.3f..1.6f
        val aim = if (k < 0.3f) Float.NaN else if (k < 1.0f) aimTo * ((k - 0.3f) / 0.7f) + (-PI / 2f).toFloat() * (1f - (k - 0.3f) / 0.7f)
                  else if (k < 1.6f) aimTo else if (pickA) aimTo else Float.NaN
        val picked = k >= 1.6f && k < 2.6f
        wheel(c, cx, cy, rad, if (k < 2.6f) 1f else 1f - ((k - 2.6f) / 0.3f).coerceIn(0f, 1f), if (picked || (aiming && k > 1f)) target else -1, aim)
        if (pickA) {
            val pressed = k in 1.6f..1.8f
            glyph.draw(c, "a", set, r.right - 60f, r.bottom - 70f, if (pressed) 56f else 48f)
            text(c, "A picks", r.right - 60f, r.bottom - 26f, 18f, if (pressed) GOLD else DIMC)
        } else text(c, if (k >= 1.6f) "let go: picked" else "aim with the stick", cx, r.bottom - 30f, 20f, if (k >= 1.6f) GOLD else DIMC)
    }

    // ---- Rooms

    private fun minimap(c: Canvas, r: RectF, yd: Int, indoor: Boolean) {
        val cx = r.centerX(); val cy = r.top + r.height() * 0.44f; val rad = min(r.width(), r.height()) * 0.36f
        pFill.color = if (indoor) Color.rgb(70, 58, 44) else Color.rgb(60, 82, 52); c.drawCircle(cx, cy, rad, pFill)
        // a grid of 20 yd squares: more yards across = smaller squares
        val px = (2f * rad) / yd * 20f
        pStroke.color = Color.argb(70, 255, 255, 255); pStroke.strokeWidth = 1.5f
        c.save(); val clip = Path().apply { addCircle(cx, cy, rad, Path.Direction.CW) }; c.clipPath(clip)
        var x = cx - floor(rad / px) * px
        while (x <= cx + rad) { c.drawLine(x, cy - rad, x, cy + rad, pStroke); x += px }
        var y = cy - floor(rad / px) * px
        while (y <= cy + rad) { c.drawLine(cx - rad, y, cx + rad, y, pStroke); y += px }
        if (indoor) { pStroke.color = Color.argb(160, 230, 200, 150); pStroke.strokeWidth = 4f; c.drawRect(cx - px * 1.4f, cy - px, cx + px * 1.6f, cy + px * 1.2f, pStroke) }
        c.restore()
        pStroke.color = BRONZE; pStroke.strokeWidth = 3f; c.drawCircle(cx, cy, rad, pStroke)
        pFill.color = Color.rgb(255, 230, 120)
        c.drawPath(Path().apply { moveTo(cx, cy - 12f); lineTo(cx - 9f, cy + 10f); lineTo(cx + 9f, cy + 10f); close() }, pFill)
        // the span across
        val y2 = cy + rad + 30f
        pStroke.color = GOLD; pStroke.strokeWidth = 2.5f
        c.drawLine(cx - rad, y2, cx + rad, y2, pStroke); c.drawLine(cx - rad, y2 - 8f, cx - rad, y2 + 8f, pStroke); c.drawLine(cx + rad, y2 - 8f, cx + rad, y2 + 8f, pStroke)
        text(c, "$yd yd across", cx, y2 + 30f, 22f, GOLD)
    }

    private fun chatInput(c: Canvas, r: RectF, quick: Boolean) {
        val box = RectF(r.left + 20f, r.top + 20f, r.right - 20f, r.top + 70f)
        frame(c, box, GOLD, 2f, Color.rgb(28, 22, 15))
        text(c, if (quick) "Say: |" else "Hello th|", box.left + 14f, box.centerY() + 8f, 22f, TEXT, Paint.Align.LEFT)
        val area = RectF(r.left + 20f, box.bottom + 20f, r.right - 20f, r.bottom - 20f)
        if (quick) {
            val chips = listOf("Hello!", "Thanks!", "Ready?", "Need a group?", "On my way", "Good game")
            var x = area.left; var y = area.top
            for (ch in chips) {
                pText.textSize = 20f; val w = pText.measureText(ch) + 30f
                if (x + w > area.right) { x = area.left; y += 50f }
                frame(c, RectF(x, y, x + w, y + 40f), BRONZE, 2f, Color.rgb(46, 36, 17))
                text(c, ch, x + w / 2f, y + 27f, 20f, TEXT)
                x += w + 10f
            }
        } else {
            val rows = listOf("QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM")
            val kw = area.width() / 10f; val kh = min(46f, area.height() / 4f)
            for ((i, row) in rows.withIndex()) {
                val x0 = area.left + (area.width() - row.length * kw) / 2f
                for ((j, ch) in row.withIndex()) {
                    val kr = RectF(x0 + j * kw + 2f, area.top + i * (kh + 6f), x0 + (j + 1) * kw - 2f, area.top + i * (kh + 6f) + kh)
                    frame(c, kr, Theme.bronzeDim, 1.5f, Color.rgb(40, 32, 22))
                    text(c, ch.toString(), kr.centerX(), kr.centerY() + 7f, 18f, TEXT)
                }
            }
        }
    }

    // ---- Performance

    /** one lane of frames: the last quarter second at [fps] scrolling with time, [made] = every other one is generated (a ring) */
    private fun lane(c: Canvas, x0: Float, x1: Float, y: Float, fps: Int, t: Float, col: Int, every: Int = 1) {
        val win = 0.25f; val dt = 1f / fps
        pStroke.color = Theme.bronzeDim; pStroke.strokeWidth = 2f; c.drawLine(x0, y, x1, y, pStroke)
        var k = floor((t - win) / dt).toInt() + 1
        val k1 = floor(t / dt).toInt()
        while (k <= k1) {
            val u = (k * dt - (t - win)) / win
            val x = x0 + u * (x1 - x0)
            if (every <= 1 || Math.floorMod(k, every) == 0) { pFill.color = col; c.drawCircle(x, y, 7f, pFill) }
            else { pStroke.color = col; pStroke.strokeWidth = 2.5f; c.drawCircle(x, y, 6f, pStroke) }
            k++
        }
        // a ball crossing, drawn where each frame put it
        val tq = floor(t * fps) / fps
        val bx = x0 + ((tq % 1.2f) / 1.2f) * (x1 - x0)
        pFill.color = col; c.drawCircle(bx, y - 26f, 11f, pFill)
    }

    private fun frameLimit(c: Canvas, r: RectF, s: State, t: Float) {
        val lanes = listOf(40, 60, 0)
        val x0 = r.left + 100f; val x1 = r.right - 24f
        for ((i, fps) in lanes.withIndex()) {
            val y = r.top + 70f + i * 110f
            val on = fps == s.fpsLimit
            text(c, if (fps > 0) "$fps" else "Off", r.left + 24f, y + 8f, 26f, if (on) GOLD else DIMC, Paint.Align.LEFT)
            lane(c, x0, x1, y, if (fps > 0) fps else 90, t, if (on) GOLD else DIMC)
        }
        // the heat, lower with the limit
        val hy = r.bottom - 40f
        text(c, "heat", r.left + 24f, hy + 6f, 18f, DIMC, Paint.Align.LEFT)
        val heat = when { s.fpsLimit in 1..40 -> 0.5f; s.fpsLimit in 41..60 -> 0.75f; else -> 0.95f }
        pFill.color = Color.rgb(30, 24, 16); c.drawRect(x0, hy - 8f, x1, hy + 8f, pFill)
        pFill.color = when { heat < 0.6f -> Theme.ok; heat < 0.85f -> Theme.warn; else -> Color.rgb(210, 80, 50) }
        c.drawRect(x0, hy - 8f, x0 + (x1 - x0) * heat, hy + 8f, pFill)
    }

    private fun frameGen(c: Canvas, r: RectF, s: State, t: Float) {
        val base = if (s.fpsLimit > 0) s.fpsLimit else 60
        val x0 = r.left + 24f; val x1 = r.right - 24f
        text(c, "the game: $base", x0, r.top + 60f, 22f, if (!s.frameGen) GOLD else DIMC, Paint.Align.LEFT)
        lane(c, x0, x1, r.top + 120f, base, t, if (!s.frameGen) GOLD else DIMC)
        text(c, "with frame generation: ${base * s.multiplier}", x0, r.top + 220f, 22f, if (s.frameGen) FLIGHT else DIMC, Paint.Align.LEFT)
        lane(c, x0, x1, r.top + 280f, base * s.multiplier, t, if (s.frameGen) FLIGHT else DIMC, s.multiplier)
    }

    private fun multiplier(c: Canvas, r: RectF, s: State, t: Float) {
        val base = if (s.fpsLimit > 0) s.fpsLimit else 60
        val x0 = r.left + 70f; val x1 = r.right - 24f
        for ((i, m) in MULTIPLIERS.withIndex()) {
            val y = r.top + 70f + i * 110f
            val on = m == s.multiplier
            text(c, "${m}x", r.left + 24f, y + 8f, 26f, if (on) FLIGHT else DIMC, Paint.Align.LEFT)
            lane(c, x0, x1, y, base * m, t, if (on) FLIGHT else DIMC, m)
        }
    }

    private fun flow(c: Canvas, r: RectF, f: Float, t: Float) {
        // a shape moving across; the made frames keep less detail at a low flow scale: soft ghosts behind it
        val cx0 = r.left + 60f; val cx1 = r.right - 60f
        val u = ((t % 2.4f) / 2.4f)
        val x = cx0 + (cx1 - cx0) * (0.5f - 0.5f * cos(u * 2f * PI.toFloat()))
        val y = r.centerY() - 20f
        val ghosts = ((1f - f) * 10f).toInt() + 1
        for (g in ghosts downTo 1) {
            pFill.color = Color.argb((40 + 80 * f).toInt() / g, 230, 200, 120)
            c.drawCircle(x - g * 6f * (1f - f + 0.1f), y, 34f + (1f - f) * 6f * g / ghosts, pFill)
        }
        pFill.color = Color.argb(255, 230, 200, 120); c.drawCircle(x, y, 34f, pFill)
        pStroke.color = if (f > 0.6f) Color.WHITE else Color.argb((255 * f).toInt() + 40, 255, 255, 255); pStroke.strokeWidth = 3f
        c.drawCircle(x, y, 34f, pStroke)
        // the scale
        val y2 = r.bottom - 60f; val a = r.left + 30f; val b = r.right - 30f
        pStroke.color = Theme.bronzeDim; pStroke.strokeWidth = 4f; c.drawLine(a, y2, b, y2, pStroke)
        val fx = a + (b - a) * ((f - FLOW_MIN) / (FLOW_MAX - FLOW_MIN)).coerceIn(0f, 1f)
        pFill.color = GOLD; c.drawCircle(fx, y2, 10f, pFill)
        text(c, "faster", a, y2 + 32f, 18f, DIMC, Paint.Align.LEFT); text(c, "sharper", b, y2 + 32f, 18f, DIMC, Paint.Align.RIGHT)
    }

    private fun indoor(c: Canvas, r: RectF, on: Boolean, t: Float) {
        val period = 6f; val k = t % period
        val walking = on && k in 3f..4.5f
        // the fill rises while standing, holds while walking
        val stand = when { k < 3f -> k; k < 4.5f -> 3f; else -> 3f + (k - 4.5f) }
        val fill = if (on) (stand / 4.5f).coerceIn(0f, 1f) else 0f
        val hx = r.centerX() + 50f; val hy = r.top + r.height() * 0.46f; val w = 150f; val h = 120f
        val house = Path().apply {
            moveTo(hx - w / 2f, hy); lineTo(hx, hy - h * 0.6f); lineTo(hx + w / 2f, hy)
            lineTo(hx + w / 2f, hy + h); lineTo(hx - w / 2f, hy + h); close()
        }
        c.save(); c.clipPath(house)
        pFill.color = Color.rgb(28, 22, 15); c.drawRect(hx - w, hy - h, hx + w, hy + h + 1f, pFill)
        pFill.color = if (on) Color.rgb(200, 160, 70) else Theme.bronzeDim
        val top = hy + h - (h * 1.6f) * fill
        c.drawRect(hx - w, top, hx + w, hy + h + 1f, pFill)
        c.restore()
        pStroke.color = BRONZE; pStroke.strokeWidth = 3f; c.drawPath(house, pStroke)
        // the player: still, or walking (moving)
        val px = r.left + 60f + if (walking) ((k - 3f) / 1.5f) * 40f else if (k >= 4.5f) 40f else 0f
        val py = hy + h - 30f
        pFill.color = TEXT; c.drawCircle(px, py - 44f, 12f, pFill)
        pStroke.color = TEXT; pStroke.strokeWidth = 5f
        c.drawLine(px, py - 30f, px, py, pStroke)
        val step = if (walking) sin(k * 12f) * 10f else 0f
        c.drawLine(px, py, px - 8f + step, py + 28f, pStroke); c.drawLine(px, py, px + 8f - step, py + 28f, pStroke)
        text(c, when { !on -> "off"; walking -> "paused: walking"; fill >= 1f -> "built"; else -> "building" },
            hx, hy + h + 36f, 22f, when { !on -> DIMC; walking -> Theme.warn; else -> GOLD })
    }
}
