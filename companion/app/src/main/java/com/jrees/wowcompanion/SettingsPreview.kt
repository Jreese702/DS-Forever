package com.jrees.wowcompanion

import android.graphics.Bitmap
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
        val spellTap: Boolean = true, val spellPickA: Boolean = true,
        val classTapL: Boolean = false, val classPickAL: Boolean = false, val classTapR: Boolean = false, val classPickAR: Boolean = false,
        val miniOutYd: Int = 80, val miniInYd: Int = 60, val quickChat: Boolean = true,
        val fpsLimit: Int = 40, val frameGen: Boolean = false, val multiplier: Int = 2, val flow: Float = 0.8f, val indoorBuild: Boolean = true,
    )

    /**
     * f-settings-previews (the user: "make our visual aids in the settings less shitty", then "Yes, this direction"): REAL MINIATURES - Home
     * as our own views draw it ([PreviewStage]: an offscreen Home fed the game's latest documents) at a [HomeView.Companion.Look], in a
     * fight or not, the DS toolbar on / off / as it is (null). null = not yet (no game documents): the row's own drawing stands in.
     */
    interface Live {
        fun home(look: HomeView.Companion.Look, fight: Boolean, toolbar: Boolean?): Bitmap?
        /** the player's own item wheel ([spell] = the spell wheel): page 1's slices (null = an empty slice) */
        fun wheelItems(spell: Boolean): List<UiKit.RadialItem?> = emptyList()
        /** the player's class radial on that side, drawn fitted into [w] x [h] with place [pointed] lit; false = none on that side */
        fun classWheel(c: Canvas, w: Float, h: Float, left: Boolean, pointed: Int): Boolean = false
    }

    /** a button picture: [action] "a" "b" "x" "y" "start", in glyph set [set] (0 Nintendo, 1 Xbox, 2 PlayStation), centred, [size] px */
    fun interface Glyph { fun draw(c: Canvas, action: String, set: Int, cx: Float, cy: Float, size: Float) }

    /** the rows with a picture, in the tabs' order; the value says whether it moves */
    val ROWS: Map<String, Boolean> = linkedMapOf(
        "Controller on Home" to false, "Combat layout" to true, "Swing timer" to true, "Focused quest" to false,
        "Start the attack when a spell is cast" to true, "DS WoW toolbar" to false,
        "ABXY location" to true, "Button glyphs" to false,
        "Pause menu: open" to true, "Pause menu: pick" to true, "Item wheel: open" to true, "Item wheel: pick" to true,
        "Spell wheel: open" to true, "Spell wheel: pick" to true,
        "Left class radial: open" to true, "Left class radial: pick" to true, "Right class radial: open" to true, "Right class radial: pick" to true,
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
        "Pause menu: open" -> if (s.pauseTap) "Tap" else "Hold"
        "Item wheel: open", "Spell wheel: open", "Left class radial: open", "Right class radial: open" -> if (tapOf(id, s)) "Tap" else "Hold"
        "Pause menu: pick" -> if (s.pausePickA) "A" else "Release"
        "Item wheel: pick", "Spell wheel: pick", "Left class radial: pick", "Right class radial: pick" -> if (pickAOf(id, s)) "A" else "Release"
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
    private val SHORT = mapOf("Start the attack when a spell is cast" to "Start the attack")

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
        "Pause menu: open", "Item wheel: open", "Spell wheel: open", "Left class radial: open", "Right class radial: open" -> if (tapOf(id, s)) listOf("A press opens the wheel;", "it stays open until you pick or press B.")
            else listOf("Hold the button: the wheel is open", "while you hold it, and closes when you let go.")
        "Pause menu: pick", "Item wheel: pick", "Spell wheel: pick", "Left class radial: pick", "Right class radial: pick" -> if (pickAOf(id, s)) listOf("Aim with the stick, then press A to pick.")
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
    private fun tapOf(id: String, s: State) = when {
        id.startsWith("Pause") -> s.pauseTap; id.startsWith("Spell") -> s.spellTap
        id.startsWith("Left class") -> s.classTapL; id.startsWith("Right class") -> s.classTapR; else -> s.radialTap }
    private fun pickAOf(id: String, s: State) = when {
        id.startsWith("Pause") -> s.pausePickA; id.startsWith("Spell") -> s.spellPickA
        id.startsWith("Left class") -> s.classPickAL; id.startsWith("Right class") -> s.classPickAR; else -> s.radialPickA }

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
    fun draw(c: Canvas, r: RectF, id: String, s: State, tMs: Long, glyph: Glyph, prev: State? = null, sinceMs: Long = Long.MAX_VALUE,
             live: Live? = null) {
        val t = tMs / 1000f
        // the Home tab: the real Home in miniature where the stage has one (the row's own drawing until then)
        if (live != null && liveHome(c, r, id, s, t, live)) return
        // the Controller tab's wheels: the real wheel art over the real Home
        if (live != null && liveWheel(c, r, id, s, t, live, glyph)) return
        when (id) {
            "Controller on Home" -> homeLayout(c, r, s.diamonds)
            "Combat layout" -> combat(c, r, s.combatGrow, t)
            "Swing timer" -> swing(c, r, s.swing, t)
            "Focused quest" -> focusedQuest(c, r, s.questOverMap)
            "Start the attack when a spell is cast" -> startAttack(c, r, s.startAttack == true, t)
            "DS WoW toolbar" -> toolbar(c, r, s.toolbar == true)
            "ABXY location" -> abxy(c, r, s, prev, sinceMs, glyph)
            "Button glyphs" -> glyphSets(c, r, s.glyphSet, glyph)
            "Pause menu: open", "Item wheel: open", "Spell wheel: open", "Left class radial: open", "Right class radial: open" -> wheelOpen(c, r, tapOf(id, s), t, id.startsWith("Pause"))
            "Pause menu: pick", "Item wheel: pick", "Spell wheel: pick", "Left class radial: pick", "Right class radial: pick" -> wheelPick(c, r, pickAOf(id, s), t, glyph, s.glyphSet)
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

    // ---- Home: LIVE MINIATURES (f-settings-previews)

    private fun look(s: State, rest: HomeRest? = null, grow: Boolean? = null, overMap: Boolean? = null, swing: Boolean? = null) =
        HomeView.Companion.Look(rest ?: (if (s.diamonds) HomeRest.DIAMONDS else HomeRest.ROW), grow ?: s.combatGrow,
            overMap ?: s.questOverMap, swing ?: s.swing)

    private val pPic by lazy { Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG) }
    private val pDim by lazy { Paint().apply { color = Color.argb(150, 8, 6, 4) } }

    /**
     * [bmp] fitted to [box]'s width at its top (its own shape), [crop] = the part of it shown (fractions of its height); [lit] true = the
     * chosen one (a gold frame), false = the other (dimmed, a thin frame), null = alone. Returns where it went.
     */
    private fun mini(c: Canvas, box: RectF, bmp: Bitmap, lit: Boolean?, crop: Pair<Float, Float> = 0f to 1f, alpha: Int = 255): RectF {
        val sy0 = (bmp.height * crop.first).toInt(); val sy1 = (bmp.height * crop.second).toInt()
        val w = box.width(); val h = w * (sy1 - sy0) / bmp.width
        val dst = RectF(box.left, box.top, box.right, box.top + h)
        pPic.alpha = alpha
        c.drawBitmap(bmp, android.graphics.Rect(0, sy0, bmp.width, sy1), dst, pPic)
        pPic.alpha = 255
        if (lit == false) c.drawRect(dst, pDim)
        pStroke.strokeWidth = if (lit == true) 3f else 1.5f
        pStroke.color = if (lit == true) GOLD else Color.argb(alpha, 90, 76, 52)
        c.drawRect(dst, pStroke)
        return dst
    }

    /** two choices stacked, each with its words over it; the chosen one lit */
    private fun beforeAfter(c: Canvas, r: RectF, a: Bitmap, aWord: String, b: Bitmap, bWord: String, aChosen: Boolean,
                            crop: Pair<Float, Float> = 0f to 1f): Boolean {
        val gap = 14f; val label = 26f
        val half = (r.height() - gap) / 2f
        val ratio = a.width / (a.height * (crop.second - crop.first))
        val w = minOf(r.width() - 20f, (half - label) * ratio)
        val x0 = r.centerX() - w / 2f
        for ((i, pair) in listOf(a to aWord, b to bWord).withIndex()) {
            val top = r.top + i * (half + gap)
            val chosen = (i == 0) == aChosen
            text(c, pair.second, x0, top + 18f, 19f, if (chosen) GOLD else DIMC, Paint.Align.LEFT)
            mini(c, RectF(x0, top + label, x0 + w, top + half), pair.first, chosen, crop)
        }
        return true
    }

    /** the Home tab's rows from the real Home; false = no picture yet (the row's own drawing then) */
    private fun liveHome(c: Canvas, r: RectF, id: String, s: State, t: Float, live: Live): Boolean {
        when (id) {
            // a look: before / after, the chosen one lit
            "Controller on Home" -> {
                val a = live.home(look(s, rest = HomeRest.DIAMONDS), false, null) ?: return false
                val b = live.home(look(s, rest = HomeRest.ROW), false, null) ?: return false
                return beforeAfter(c, r, a, "Diamonds", b, "Thin row", s.diamonds)
            }
            "Focused quest" -> {
                val a = live.home(look(s, overMap = true), false, null) ?: return false
                val b = live.home(look(s, overMap = false), false, null) ?: return false
                return beforeAfter(c, r, a, "Above Minimap", b, "Above Chat", s.questOverMap)
            }
            // the swing line is the fight's: both in a fight, only the controller's band shown
            "Swing timer" -> {
                val a = live.home(look(s, swing = true), true, null) ?: return false
                val b = live.home(look(s, swing = false), true, null) ?: return false
                return beforeAfter(c, r, a, "On: the swing line under the buttons", b, "Off: the cast bar alone", s.swing, 0f to 0.42f)
            }
            "DS WoW toolbar" -> {
                val on = s.toolbar ?: return false
                val a = live.home(look(s), false, true) ?: return false
                val b = live.home(look(s), false, false) ?: return false
                return beforeAfter(c, r, a, "On: the plain D-pad is the toolbar", b, "Off: your own D-pad slots", on, 0f to 0.32f)
            }
            // a behaviour: the real controller band; a cast's press, then the auto-attack's crossed swords light (On) or stay dark (Off)
            "Start the attack when a spell is cast" -> {
                val on = s.startAttack ?: return false
                val band = live.home(look(s), false, null) ?: return false
                val box = RectF(r.left + 10f, r.top + 10f, r.right - 10f, r.bottom)
                val dst = mini(c, box, band, null, 0f to 0.3f)
                val k = t % 2.4f
                val press = if (k < 0.3f) 1f - k / 0.3f else 0f
                val cy = dst.bottom + 110f
                // the press: a gold ring blooming on the bar, then the arrow to the swords
                pStroke.color = Color.argb((255 * (0.35f + 0.65f * press)).toInt(), 255, 209, 0); pStroke.strokeWidth = 4f
                c.drawCircle(r.centerX() - 110f, cy, 34f + 10f * press, pStroke)
                text(c, "a harmful spell", r.centerX() - 110f, cy + 66f, 18f)
                pStroke.color = DIMC; pStroke.strokeWidth = 3f
                c.drawLine(r.centerX() - 60f, cy, r.centerX() + 40f, cy, pStroke)
                c.drawLine(r.centerX() + 40f, cy, r.centerX() + 28f, cy - 10f, pStroke)
                c.drawLine(r.centerX() + 40f, cy, r.centerX() + 28f, cy + 10f, pStroke)
                val lit = on && k in 0.35f..2.1f
                val sw = UiKit.tex("combat_swords")
                if (sw != null) {
                    pPic.alpha = if (lit) 255 else 90
                    c.drawBitmap(sw, null, RectF(r.centerX() + 70f, cy - 36f, r.centerX() + 150f, cy + 36f), pPic)
                    pPic.alpha = 255
                } else swords(c, r.centerX() + 110f, cy, 34f, if (lit) GOLD else Theme.bronzeDim)
                text(c, if (on) "your auto-attack starts" else "no auto-attack", r.centerX() + 110f, cy + 66f, 18f, if (lit) GOLD else DIMC)
                return true
            }
            // a behaviour: a loop, rest -> the fight -> rest, at the choice made (Grow or Same size)
            "Combat layout" -> {
                val rest = live.home(look(s), false, null) ?: return false
                val fight = live.home(look(s), true, null) ?: return false
                val p = t % 4.4f
                val k = when { p < 1.2f -> 0f; p < 1.6f -> ease((p - 1.2f) / 0.4f); p < 3.4f -> 1f; p < 3.8f -> 1f - ease((p - 3.4f) / 0.4f); else -> 0f }
                val box = RectF(r.left + 10f, r.top + 6f, r.right - 10f, r.bottom - 60f)
                val dst = mini(c, box, rest, null)
                if (k > 0f) {
                    pPic.alpha = (255 * k).toInt(); c.drawBitmap(fight, null, dst, pPic); pPic.alpha = 255
                    pStroke.color = Color.argb((220 * k).toInt(), 200, 40, 30); pStroke.strokeWidth = 4f; c.drawRect(dst, pStroke)
                }
                chip(c, dst.left + 6f, dst.bottom + 14f, "Out of combat", k < 0.5f)
                chip(c, dst.left + 186f, dst.bottom + 14f, "In a fight", k >= 0.5f)
                return true
            }
        }
        return false
    }

    private fun ease(x: Float): Float { val v = x.coerceIn(0f, 1f); return v * v * (3f - 2f * v) }

    // ---- the Controller tab's wheels: LIVE (f-settings-previews)

    /**
     * one moment of a wheel's loop: [open] 0..1 along its open motion, [down] the opening button held, [b] B pressed, [pointed] the place
     * the stick points at (-1 none), [stick] the stick's push 0..1, [picked] the pick just made
     */
    private class WheelFrame(val open: Float, val down: Boolean, val b: Boolean, val pointed: Int, val stick: Float, val picked: Boolean)

    /** Tap / Hold over 3.6 s: the press (a blip, or held), the open, B (Tap) or the release (Hold) closing it */
    private fun openFrame(tap: Boolean, k: Float): WheelFrame {
        val close = 2.4f
        val open = when { k < 0.1f -> 0f; k < close -> ((k - 0.1f) / 0.3f).coerceIn(0f, 1f); else -> 1f - ((k - close) / 0.25f).coerceIn(0f, 1f) }
        val down = if (tap) k < 0.15f else k < close
        return WheelFrame(open, down, tap && k in close..close + 0.15f, -1, 0f, false)
    }

    /** A / Release over 3.4 s on the open wheel: the stick swings to place [target], A (or letting the stick go) picks it, the wheel goes */
    private fun pickFrame(pickA: Boolean, k: Float, target: Int, places: Int): WheelFrame {
        val reopen = 2.9f
        val open = when { k < 1.9f -> 1f; k < 2.2f -> 1f - (k - 1.9f) / 0.3f; k < reopen -> 0f; else -> ((k - reopen) / 0.3f).coerceIn(0f, 1f) }
        val stick = when { k < 0.4f -> 0f; k < 0.8f -> (k - 0.4f) / 0.4f; k < 1.6f -> 1f; pickA && k < 1.9f -> 1f; else -> 0f }
        val pointed = if (k in 0.6f..1.9f) (if (k < 0.9f) (target + places - 1) % places else target) else -1
        val picked = k in 1.6f..1.9f
        return WheelFrame(open, false, false, pointed, stick, picked)
    }

    private val wheelScreen = RectF(0f, 0f, 1240f, 1080f - Strip.HEIGHT)

    /** the Controller tab's wheel rows: the real wheel over the real Home (dimmed), a press line under it; false = no picture yet */
    private fun liveWheel(c: Canvas, r: RectF, id: String, s: State, t: Float, live: Live, glyph: Glyph): Boolean {
        val kind = when {
            id.startsWith("Pause") -> "pause"; id.startsWith("Item") -> "item"; id.startsWith("Spell") -> "spell"
            id.startsWith("Left class") -> "classL"; id.startsWith("Right class") -> "classR"; else -> return false
        }
        val isOpen = id.endsWith(": open")
        val home = live.home(look(s), false, null) ?: return false
        val box = RectF(r.left + 10f, r.top + 6f, r.right - 10f, r.bottom - 120f)
        val dst = mini(c, box, home, null)
        c.drawRect(dst, pDim)
        val f = if (isOpen) openFrame(tapOf(id, s), t % 3.6f) else pickFrame(pickAOf(id, s), t % 3.4f, 2, if (kind == "pause") 8 else 8)
        // the wheel, drawn by the app's own code on Home's frame, fitted to the miniature
        c.save()
        c.clipRect(dst)
        c.translate(dst.left, dst.top)
        val sc = dst.width() / wheelScreen.width()
        c.scale(sc, sc)
        val disc = android.graphics.PointF(wheelScreen.centerX(), wheelScreen.bottom + 40f)
        when (kind) {
            "pause" -> if (f.open > 0f) {
                val keepB = UiKit.pauseBarsBottom; val keepS = UiKit.pauseStripTop
                UiKit.pauseBarsBottom = 0f; UiKit.pauseStripTop = 0f            // the preview's own layout; the live pause view's restored below
                try {
                    val slices = HomePauseModel.Flow().slices().map { it?.let { sl -> UiKit.PauseSlice(sl.key, sl.label, sl.enabled) } }
                    UiKit.pauseRadial(c, wheelScreen, disc, 140f, slices, 0, f.pointed, -1, UiKit.PAUSE_OPEN_MS * f.open,
                        if (f.picked) 1f else 0f, pickGlyph = pickAOf(id, s))
                } finally { UiKit.pauseBarsBottom = keepB; UiKit.pauseStripTop = keepS }
            }
            "item", "spell" -> if (f.open > 0f) UiKit.consumableRadial(c, wheelScreen, disc, 140f, live.wheelItems(kind == "spell"),
                f.pointed, 0, 1, UiKit.CR_OPEN_MS * f.open, glyphs = pickAOf(id, s))
            else -> if (f.open > 0f) {
                // the class wheel takes its side's column (as on Home); shown at its open's scale
                val side = 520f * f.open.coerceAtLeast(0.2f)
                val cx = if (kind == "classL") 330f else 910f
                c.save(); c.translate(cx - side / 2f, wheelScreen.centerY() - side / 2f)
                val drawn = live.classWheel(c, side, side, kind == "classL", f.pointed)
                c.restore()
                if (!drawn) text(c, "Your character has no radial on this side", wheelScreen.centerX(), wheelScreen.centerY(), 40f, DIMC)
            }
        }
        c.restore()
        // the press line: the opening button / the stick, and B or A
        val y = dst.bottom + 40f
        val opener = when (kind) { "pause" -> "Start"; "item" -> "D-pad Up"; "spell" -> "its button"; "classL" -> "LB + LT"; else -> "RB + RT" }
        if (isOpen) {
            text(c, opener, r.left + 20f, y + 6f, 19f, if (f.down) GOLD else DIMC, Paint.Align.LEFT)
            val x0 = r.left + 140f; val x1 = r.right - 30f
            pFill.color = Color.rgb(30, 26, 20); c.drawRoundRect(x0, y - 9f, x1, y + 9f, 9f, 9f, pFill)
            val k = t % 3.6f
            val pressEnd = if (tapOf(id, s)) 0.15f else 2.4f
            pFill.color = GOLD; c.drawRoundRect(x0, y - 7f, x0 + (x1 - x0) * (minOf(k, pressEnd) / 3.6f).coerceAtLeast(0.01f), y + 7f, 7f, 7f, pFill)
            pFill.color = TEXT; c.drawCircle(x0 + (x1 - x0) * k / 3.6f, y, 6f, pFill)
            if (tapOf(id, s)) {
                glyph.draw(c, "b", s.glyphSet, x0 + (x1 - x0) * 2.4f / 3.6f, y + 40f, if (f.b) 44f else 34f)
                text(c, "closes", x0 + (x1 - x0) * 2.4f / 3.6f + 30f, y + 46f, 18f, if (f.b) GOLD else DIMC, Paint.Align.LEFT)
            } else text(c, "letting go closes it", x1, y + 46f, 18f, if (k >= 2.4f) GOLD else DIMC, Paint.Align.RIGHT)
        } else {
            // the stick: a ring and its knob pushed toward the pointed place; then A (or the knob coming home) picks
            val sx = r.left + 70f; val sy = y + 16f
            pStroke.color = Theme.bronze; pStroke.strokeWidth = 3f; c.drawCircle(sx, sy, 30f, pStroke)
            val ang = Math.toRadians(-90.0 + 45.0 * 2)
            pFill.color = if (f.stick > 0f) GOLD else DIMC
            c.drawCircle(sx + (Math.cos(ang) * 18f * f.stick).toFloat(), sy + (Math.sin(ang) * 18f * f.stick).toFloat(), 12f, pFill)
            text(c, "aim with the stick", sx + 50f, sy + 6f, 18f, DIMC, Paint.Align.LEFT)
            if (pickAOf(id, s)) {
                glyph.draw(c, "a", s.glyphSet, r.right - 60f, sy, if (f.picked) 50f else 38f)
                text(c, "picks", r.right - 60f, sy + 44f, 18f, if (f.picked) GOLD else DIMC)
            } else text(c, if (f.picked) "let go: picked" else "", r.right - 30f, sy + 6f, 18f, GOLD, Paint.Align.RIGHT)
        }
        return true
    }

    /** a word in a small plate, gold when it is the state shown */
    private fun chip(c: Canvas, x: Float, y: Float, word: String, on: Boolean) {
        pText.textSize = 18f
        val w = pText.measureText(word) + 26f
        val box = RectF(x, y, x + w, y + 32f)
        frame(c, box, if (on) GOLD else Color.rgb(70, 60, 45), 2f, if (on) Color.rgb(46, 36, 17) else Color.rgb(24, 20, 16))
        text(c, word, box.centerX(), box.centerY() + 6f, 18f, if (on) GOLD else DIMC)
    }

    // ---- Home: the rows' own drawings (until the stage has a picture)

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
