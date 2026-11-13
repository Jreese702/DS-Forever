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
        // 2026-11-13 (the user: "a lot of these new options and settings need visuals"): the extras row, the cast bar, the world map, the
        // Controller tab's helpers. [castBar] = CastStripModel.Side's index (Off, Left, Right, Both); [autoShot] = GameUi.SHOT_WORDS' index
        val clockHearth: Boolean = true, val clockTeleport: Boolean = true, val clockGold: Boolean = true, val clockBags: Boolean = true,
        val clockAmmo: Boolean = true, val castBar: Int = 0, val worldMapAll: Boolean = true, val smartY: Boolean? = null,
        val partyMode: Boolean = true, val smartRes: Boolean = true, val attackHostile: Boolean = false, val autoShot: Int = 1,
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
        /** the map module's box in a Home picture (fractions of it), null = none */
        fun mapBox(): RectF? = null
        /** the companion's own keyboard, drawn at [w] x [h] */
        fun keyboard(c: Canvas, w: Float, h: Float) {}
    }

    /** a button picture: [action] "a" "b" "x" "y" "start" "select", in glyph set [set] (0 Nintendo, 1 Xbox, 2 PlayStation), centred, [size] px */
    fun interface Glyph { fun draw(c: Canvas, action: String, set: Int, cx: Float, cy: Float, size: Float) }

    /** the rows with a picture, in the tabs' order; the value says whether it moves */
    val ROWS: Map<String, Boolean> = linkedMapOf(
        "Controller on Home" to false, "Combat layout" to true, "Swing timer" to true, "Focused quest" to false,
        "Hearthstone" to false, "Class teleport" to false, "Gold" to false, "Free bag slots" to false, "Ammo" to false,
        "Start the attack when a spell is cast" to true, "DS WoW toolbar" to false, "Tap-to-cast bar" to false,
        "ABXY location" to true, "Button glyphs" to false,
        "Pause menu: open" to true, "Pause menu: pick" to true, "Item wheel: open" to true, "Item wheel: pick" to true,
        "Spell wheel: open" to true, "Spell wheel: pick" to true,
        "Left class radial: open" to true, "Left class radial: pick" to true, "Right class radial: open" to true, "Right class radial: pick" to true,
        "Smart Y" to true, "Party mode" to true, "Smart Res" to true, "Start attack on hostile target" to true, "Combat Auto Shot" to true,
        "Minimap zoom outdoors" to false, "Minimap zoom indoors" to false, "World map" to false, "Message input" to false,
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
        "Hearthstone", "Class teleport", "Gold", "Free bag slots", "Ammo" -> onOff(extraOn(id, s))
        "Tap-to-cast bar" -> SIDE_WORDS[s.castBar.coerceIn(0, 3)]
        "World map" -> if (s.worldMapAll) "Show all" else "Explored only"
        "Smart Y" -> s.smartY?.let { onOff(it) } ?: "-"
        "Party mode" -> onOff(s.partyMode)
        "Smart Res" -> onOff(s.smartRes)
        "Start attack on hostile target" -> onOff(s.attackHostile)
        "Combat Auto Shot" -> SHOT_NAMES[s.autoShot.coerceIn(0, 2)]
        else -> ""
    }.let { v -> val n = SHORT[id] ?: id; if (v.isEmpty()) n else "$n: $v" }

    /** a row's name in the picture's title when the row's own words are too long for the panel */
    private val SHORT = mapOf("Start the attack when a spell is cast" to "Start the attack", "Start attack on hostile target" to "Start attack")

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
        "Hearthstone" -> if (s.clockHearth) listOf("Your Hearthstone in the row under the", "clock; it glows when ready.")
            else listOf("No Hearthstone in the row.", "The others space out evenly.")
        "Class teleport" -> if (s.clockTeleport) listOf("Your class teleport in the row,", "once your character knows it.")
            else listOf("No class teleport in the row.", "The others space out evenly.")
        "Gold" -> if (s.clockGold) listOf("Your total gold under the battery:", "gold, silver and copper.")
            else listOf("No gold under the battery.")
        "Free bag slots" -> if (s.clockBags) listOf("How many bag slots are free,", "in the row under the clock.")
            else listOf("No bag count in the row.", "The others space out evenly.")
        "Ammo" -> if (s.clockAmmo) listOf("Your equipped ammo and its count,", "while you have some.")
            else listOf("No ammo in the row.", "The others space out evenly.")
        "Tap-to-cast bar" -> when (s.castBar) {
            1 -> listOf("Six spells under the chat;", "one tap casts each.")
            2 -> listOf("Six spells under the minimap;", "one tap casts each.")
            3 -> listOf("One bar under both columns;", "one tap casts each.")
            else -> listOf("No tap-to-cast bar on Home.") }
        "World map" -> if (s.worldMapAll) listOf("The Map room's continents show", "every zone, revealed.")
            else listOf("The Map room's continents show only", "what you have explored.")
        "Smart Y" -> when (s.smartY) {
            null -> listOf("Needs the game running.")
            true -> listOf("Out of combat, nothing targeted:", "Y casts from your list, top first.")
            false -> listOf("Y keeps its own action.") }
        "Party mode" -> if (s.partyMode) listOf("Select turns Party mode on:", "your party's plates on Home.")
            else listOf("Select does not turn Party mode on.")
        "Smart Res" -> if (s.smartRes) listOf("A tap on a dead member casts your", "res (not in a fight).")
            else listOf("A tap on a dead member does not res.")
        "Start attack on hostile target" -> if (s.attackHostile) listOf("Targeting an enemy starts", "your auto-attack.")
            else listOf("Targeting an enemy does not start", "your auto-attack.")
        "Combat Auto Shot" -> when (s.autoShot) {
            0 -> listOf("No Auto Shot in a fight.")
            2 -> listOf("As On, but off for a plain attack", "you start yourself.")
            else -> listOf("In a fight: Auto Shot at range,", "auto attack up close.") }
        else -> emptyList()
    }

    // ------------------------------------------------------------------ drawing

    private val SET_NAMES = listOf("Nintendo", "Xbox", "PlayStation")
    /** CastStripModel.Side's words and GameUi.SHOT_WORDS (kept here so this file draws on its own, for the off-device renders) */
    private val SIDE_WORDS = listOf("Off", "Left", "Right", "Both")
    private val SHOT_NAMES = listOf("Off", "On", "Smart")
    private fun extraOn(id: String, s: State) = when (id) {
        "Hearthstone" -> s.clockHearth; "Class teleport" -> s.clockTeleport; "Gold" -> s.clockGold; "Free bag slots" -> s.clockBags
        else -> s.clockAmmo }
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
        // the Rooms tab: the player's own minimap, the real keyboard
        if (live != null && liveRooms(c, r, id, s, live)) return
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
            "Frame limit" -> frameLimit(c, r, s, t, live)
            "Frame generation" -> frameGen(c, r, s, t, live)
            "Multiplier" -> multiplier(c, r, s, t, live)
            "Flow scale" -> flow(c, r, s.flow, t, live)
            "Build in the background" -> indoor(c, r, s, t, live)
            "Gold" -> goldLine(c, r, s)
            "Hearthstone", "Class teleport", "Free bag slots", "Ammo" -> extras(c, r, id, s)
            "Tap-to-cast bar" -> castBar(c, r, s.castBar)
            "World map" -> worldMap(c, r, s.worldMapAll)
            "Smart Y" -> smartY(c, r, s, t, glyph)
            "Party mode" -> partyMode(c, r, s.partyMode, t, glyph, s.glyphSet)
            "Smart Res" -> smartRes(c, r, s.smartRes, t)
            "Start attack on hostile target" -> attackHostile(c, r, s.attackHostile, t)
            "Combat Auto Shot" -> autoShot(c, r, s.autoShot, t)
        }
    }

    // ---- small parts

    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float = 20f, col: Int = DIMC, align: Paint.Align = Paint.Align.CENTER) {
        pText.textSize = size; pText.color = col; pText.textAlign = align
        c.drawText(Tr.t(s), x, y, pText)
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

    // ---- the Rooms tab: LIVE (f-settings-previews)

    private fun liveRooms(c: Canvas, r: RectF, id: String, s: State, live: Live): Boolean {
        when (id) {
            "Minimap zoom outdoors", "Minimap zoom indoors" -> {
                // the card off the map (it would cover the minimap's top): the map module alone
                val home = live.home(look(s, overMap = false), false, null) ?: return false
                val mb = live.mapBox() ?: return false
                val indoor = id.endsWith("indoors")
                val yd = if (indoor) s.miniInYd else s.miniOutYd
                // the player's own minimap from the Home picture; indoors the same map seen narrower (out yd / in yd closer)
                val src = android.graphics.Rect((mb.left * home.width).toInt(), (mb.top * home.height).toInt(),
                    (mb.right * home.width).toInt(), (mb.bottom * home.height).toInt())
                if (indoor && s.miniInYd > 0) {
                    val k = (s.miniInYd.toFloat() / s.miniOutYd.coerceAtLeast(1)).coerceIn(0.2f, 1f)
                    val cx = src.exactCenterX(); val cy = src.exactCenterY()
                    val hw = src.width() * k / 2f; val hh = src.height() * k / 2f
                    src.set((cx - hw).toInt(), (cy - hh).toInt(), (cx + hw).toInt(), (cy + hh).toInt())
                }
                val w = r.width() - 40f
                val h = w * src.height() / src.width().coerceAtLeast(1)
                val dst = RectF(r.left + 20f, r.top + 10f, r.right - 20f, r.top + 10f + h)
                c.drawBitmap(home, src, dst, pPic)
                pStroke.color = BRONZE; pStroke.strokeWidth = 3f; c.drawRect(dst, pStroke)
                val y2 = dst.bottom + 28f
                pStroke.color = GOLD; pStroke.strokeWidth = 2.5f
                c.drawLine(dst.left, y2, dst.right, y2, pStroke); c.drawLine(dst.left, y2 - 8f, dst.left, y2 + 8f, pStroke)
                c.drawLine(dst.right, y2 - 8f, dst.right, y2 + 8f, pStroke)
                text(c, "$yd yd across" + if (indoor) " inside a building" else "", r.centerX(), y2 + 30f, 22f, GOLD)
                return true
            }
            "Message input" -> {
                val half = (r.height() - 14f) / 2f
                // Keyboard: the companion's own board; Quick chat: the phrase rows, the game's menu rows
                val kbTop = r.top + (if (s.quickChat) half + 14f else 0f)
                val qcTop = r.top + (if (s.quickChat) 0f else half + 14f)
                text(c, "Keyboard", r.left + 20f, kbTop + 18f, 19f, if (!s.quickChat) GOLD else DIMC, Paint.Align.LEFT)
                val kw = r.width() - 40f; val kh = minOf(half - 30f, kw * ChatKeyboard.fullHeight() / 1240f)
                val kbox = RectF(r.left + 20f, kbTop + 26f, r.left + 20f + kw, kbTop + 26f + kh)
                c.save(); c.translate(kbox.left, kbox.top); live.keyboard(c, kw, kh); c.restore()
                if (s.quickChat) c.drawRect(kbox, pDim)
                pStroke.color = if (!s.quickChat) GOLD else Color.rgb(90, 76, 52); pStroke.strokeWidth = if (!s.quickChat) 3f else 1.5f
                c.drawRect(kbox, pStroke)
                text(c, "Quick chat", r.left + 20f, qcTop + 18f, 19f, if (s.quickChat) GOLD else DIMC, Paint.Align.LEFT)
                val phrases = listOf("Hello!", "Thanks!", "Ready?", "On my way")
                val rowH = minOf(48f, (half - 34f) / phrases.size)
                for ((i, ph) in phrases.withIndex()) {
                    val rr = RectF(r.left + 20f, qcTop + 28f + i * rowH, r.right - 20f, qcTop + 28f + (i + 1) * rowH - 4f)
                    UiKit.menuRow(c, rr, ph, i == 0 && s.quickChat, size = 20f)
                }
                if (!s.quickChat) c.drawRect(RectF(r.left + 20f, qcTop + 28f, r.right - 20f, qcTop + 28f + phrases.size * rowH), pDim)
                return true
            }
        }
        return false
    }

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

    // ---- Performance: in the game's own look (f-settings-previews: the game's bars, its type, gold; the player's own spell icon and map)

    private val pTitle by lazy { Paint(Paint.ANTI_ALIAS_FLAG) }
    private val pBody by lazy { Paint(Paint.ANTI_ALIAS_FLAG) }
    private val pNum by lazy { Paint(Paint.ANTI_ALIAS_FLAG) }

    /** words in the game's type: [kind] 0 Morpheus (titles), 1 Friz Quadrata (labels), 2 Arial Narrow (numbers) */
    private fun gText(c: Canvas, s: String, x: Float, y: Float, size: Float, col: Int, align: Paint.Align = Paint.Align.LEFT, kind: Int = 1) {
        val p = when (kind) { 0 -> pTitle; 2 -> pNum; else -> pBody }
        p.textSize = size; p.color = col; p.textAlign = align
        when (kind) { 0 -> UiKit.asTitle(p); 2 -> UiKit.asNumber(p); else -> UiKit.asBody(p) }
        c.drawText(Tr.t(s), x, y, p)
    }

    /** the thing that moves across a lane: one of the player's own spell icons (or item icons), a gold orb until there is one */
    private fun mover(live: Live?): Bitmap? =
        live?.let { l -> (l.wheelItems(true) + l.wheelItems(false)).firstOrNull { it?.icon != null }?.icon }

    /**
     * One lane of frames on the game's bar ground: the last quarter second at [fps] scrolling with time. A game frame is a filled gem, a
     * made one ([every] > 1: all but every [every]th) a ring. Over it the mover, drawn where each frame put it.
     */
    private fun lane(c: Canvas, x0: Float, x1: Float, y: Float, fps: Int, t: Float, col: Int, every: Int = 1, icon: Bitmap? = null) {
        UiKit.standing(c, RectF(x0, y - 15f, x1, y + 15f), col, 0f, "", 1f)
        val a = x0 + 14f; val b = x1 - 14f
        val win = 0.25f; val dt = 1f / fps
        var k = floor((t - win) / dt).toInt() + 1
        val k1 = floor(t / dt).toInt()
        while (k <= k1) {
            val x = a + (k * dt - (t - win)) / win * (b - a)
            if (every <= 1 || Math.floorMod(k, every) == 0) {
                pFill.color = col; c.drawRect(x - 3.5f, y - 7f, x + 3.5f, y + 7f, pFill)
                pFill.color = Color.argb(150, 255, 250, 225); c.drawRect(x - 3.5f, y - 7f, x + 3.5f, y - 4f, pFill)
            } else { pStroke.color = col; pStroke.strokeWidth = 2f; c.drawCircle(x, y, 4.5f, pStroke) }
            k++
        }
        val tq = floor(t * fps) / fps
        val bx = a + ((tq % 1.2f) / 1.2f) * (b - a)
        val by = y - 36f; val half = 15f
        if (icon != null) {
            c.drawBitmap(icon, null, RectF(bx - half, by - half, bx + half, by + half), pPic)
            pStroke.color = col; pStroke.strokeWidth = 2f; c.drawRect(bx - half, by - half, bx + half, by + half, pStroke)
        } else {
            pFill.color = Color.argb(70, Color.red(col), Color.green(col), Color.blue(col)); c.drawCircle(bx, by, half + 5f, pFill)
            pFill.color = col; c.drawCircle(bx, by, half - 3f, pFill)
        }
    }

    /** a choice's row: the game's selected-row light behind the chosen one */
    private fun choiceRow(c: Canvas, r: RectF, top: Float, h: Float, on: Boolean, col: Int = GOLD) {
        if (on) UiKit.selectedRow(c, RectF(r.left + 8f, top, r.right - 8f, top + h), col)
    }

    private fun frameLimit(c: Canvas, r: RectF, s: State, t: Float, live: Live?) {
        val icon = mover(live)
        val lanes = listOf(40, 60, 0)
        val x0 = r.left + 92f; val x1 = r.right - 20f
        for ((i, fps) in lanes.withIndex()) {
            val top = r.top + 14f + i * ((r.height() - 110f) / 3f)
            val on = fps == s.fpsLimit
            choiceRow(c, r, top, 96f, on)
            val y = top + 66f
            gText(c, if (fps > 0) "$fps" else "Off", r.left + 22f, y + 10f, 30f, if (on) GOLD else DIMC, kind = 2)
            lane(c, x0, x1, y, if (fps > 0) fps else 90, t, if (on) GOLD else DIMC, icon = icon)
        }
        // the heat, lower with the limit: the game's standing bar
        val heat = when { s.fpsLimit in 1..40 -> 0.5f; s.fpsLimit in 41..60 -> 0.75f; else -> 0.95f }
        val col = when { heat < 0.6f -> Theme.ok; heat < 0.85f -> Theme.warn; else -> Color.rgb(210, 80, 50) }
        UiKit.standing(c, RectF(r.left + 20f, r.bottom - 64f, r.right - 20f, r.bottom - 22f), col, heat,
            "Heat: " + when { heat < 0.6f -> "cool"; heat < 0.85f -> "warm"; else -> "hot" }, 19f)
    }

    private fun frameGen(c: Canvas, r: RectF, s: State, t: Float, live: Live?) {
        val icon = mover(live)
        val base = if (s.fpsLimit > 0) s.fpsLimit else 60
        val x0 = r.left + 20f; val x1 = r.right - 20f
        for ((i, made) in listOf(false, true).withIndex()) {
            val top = r.top + 14f + i * ((r.height() - 30f) / 2f)
            val on = made == s.frameGen
            val col = if (!on) DIMC else if (made) FLIGHT else GOLD
            choiceRow(c, r, top, 150f, on, if (made) FLIGHT else GOLD)
            gText(c, if (made) "With frame generation" else "The game alone", x0 + 4f, top + 30f, 21f, col, kind = 0)
            gText(c, "${if (made) base * s.multiplier else base} fps", x1 - 4f, top + 30f, 24f, col, Paint.Align.RIGHT, 2)
            lane(c, x0, x1, top + 112f, if (made) base * s.multiplier else base, t, col, if (made) s.multiplier else 1, icon)
        }
    }

    private fun multiplier(c: Canvas, r: RectF, s: State, t: Float, live: Live?) {
        val icon = mover(live)
        val base = if (s.fpsLimit > 0) s.fpsLimit else 60
        val x0 = r.left + 70f; val x1 = r.right - 20f
        for ((i, m) in MULTIPLIERS.withIndex()) {
            val top = r.top + 14f + i * ((r.height() - 40f) / 3f)
            val on = m == s.multiplier
            val col = if (on) FLIGHT else DIMC
            choiceRow(c, r, top, 96f, on, FLIGHT)
            val y = top + 66f
            gText(c, "${m}x", r.left + 22f, y + 10f, 30f, col, kind = 2)
            gText(c, "${base * m} fps", r.left + 22f, y + 34f, 16f, col, kind = 2)
            lane(c, x0, x1, y, base * m, t, col, m, icon)
        }
    }

    private fun flow(c: Canvas, r: RectF, f: Float, t: Float, live: Live?) {
        // the mover crossing; the made frames keep less detail at a low flow scale: soft ghosts trail it
        val icon = mover(live)
        val cx0 = r.left + 70f; val cx1 = r.right - 70f
        val u = ((t % 2.4f) / 2.4f)
        val x = cx0 + (cx1 - cx0) * (0.5f - 0.5f * cos(u * 2f * PI.toFloat()))
        val y = r.top + r.height() * 0.36f
        val half = 46f
        UiKit.standing(c, RectF(r.left + 20f, y + half + 18f, r.right - 20f, y + half + 46f), GOLD, 0f, "", 1f)
        val ghosts = ((1f - f) * 10f).toInt() + 1
        for (g in ghosts downTo 1) {
            val gx = x - g * 7f * (1f - f + 0.1f)
            val a = ((40 + 80 * f) / g).toInt()
            if (icon != null) { pPic.alpha = a; c.drawBitmap(icon, null, RectF(gx - half, y - half, gx + half, y + half), pPic); pPic.alpha = 255 }
            else { pFill.color = Color.argb(a, 230, 200, 120); c.drawCircle(gx, y, half, pFill) }
        }
        if (icon != null) c.drawBitmap(icon, null, RectF(x - half, y - half, x + half, y + half), pPic)
        else { pFill.color = Color.rgb(230, 200, 120); c.drawCircle(x, y, half, pFill) }
        pStroke.color = if (f > 0.6f) GOLD else Color.argb((255 * f).toInt() + 40, 255, 209, 0); pStroke.strokeWidth = 3f
        if (icon != null) c.drawRect(x - half, y - half, x + half, y + half, pStroke) else c.drawCircle(x, y, half, pStroke)
        // the scale: the game's bar, filled to the setting
        val k = ((f - FLOW_MIN) / (FLOW_MAX - FLOW_MIN)).coerceIn(0f, 1f)
        val bar = RectF(r.left + 20f, r.bottom - 86f, r.right - 20f, r.bottom - 44f)
        UiKit.standing(c, bar, GOLD, k, "${Math.round(f * 100)}%", 20f)
        gText(c, "faster", bar.left + 4f, bar.bottom + 26f, 18f, DIMC)
        gText(c, "sharper", bar.right - 4f, bar.bottom + 26f, 18f, DIMC, Paint.Align.RIGHT)
    }

    private fun indoor(c: Canvas, r: RectF, s: State, t: Float, live: Live?) {
        val on = s.indoorBuild
        val period = 6f; val k = t % period
        val walking = on && k in 3f..4.5f
        // the build rises while standing, holds while walking
        val stand = when { k < 3f -> k; k < 4.5f -> 3f; else -> 3f + (k - 4.5f) }
        val fill = if (on) (stand / 4.5f).coerceIn(0f, 1f) else 0f
        // the map being built: the player's own minimap (from the real Home), lit from the bottom up as it is built; parchment until then
        val box = RectF(r.left + 40f, r.top + 20f, r.right - 40f, r.top + 20f + (r.width() - 80f) * 0.82f)
        // the focused quest card moved off the map (as the minimap rows show it)
        val home = live?.home(look(s, overMap = false), false, null)
        val mb = live?.mapBox()
        val src = if (home != null && mb != null) android.graphics.Rect((mb.left * home.width).toInt(), (mb.top * home.height).toInt(),
            (mb.right * home.width).toInt(), (mb.bottom * home.height).toInt()) else null
        val inner = RectF(box.left + 8f, box.top + 8f, box.right - 8f, box.bottom - 8f)
        fun picture() { if (home != null && src != null) c.drawBitmap(home, src, inner, pPic) else UiKit.parchment(c, inner, 18f) }
        picture(); c.drawRect(inner, pDim); c.drawRect(inner, pDim)
        if (fill > 0f) { c.save(); c.clipRect(inner.left, inner.bottom - inner.height() * fill, inner.right, inner.bottom); picture(); c.restore() }
        UiKit.border(c, box, "border_tooltip", 16f, BRONZE)
        // building: the game's progress bar; what the player is doing, as chips
        UiKit.progress(c, RectF(r.left + 40f, box.bottom + 18f, r.right - 40f, box.bottom + 40f), fill)
        val cy = box.bottom + 58f
        chip(c, r.left + 40f, cy, "Standing still", on && !walking)
        chip(c, r.left + 210f, cy, "Walking", walking)
        gText(c, when { !on -> "Off"; walking -> "Paused: walking"; fill >= 1f -> "Built"; else -> "Building" },
            r.centerX(), cy + 76f, 26f, when { !on -> DIMC; walking -> Theme.warn; else -> GOLD }, Paint.Align.CENTER, 0)
    }

    // ---- 2026-11-13 (the user: "a lot of these new options and settings need visuals to accompany them in the settings menus")

    /** two choices stacked, each drawn by [draw] into its own framed box under its word; the chosen one lit gold, the other dimmed */
    private fun twoWays(c: Canvas, r: RectF, aWord: String, bWord: String, aChosen: Boolean, boxH: Float, draw: (RectF, Boolean) -> Unit) {
        val label = 26f; val gap = 16f
        val h = minOf(boxH, (r.height() - gap) / 2f - label)
        for (i in 0..1) {
            val top = r.top + i * (label + h + gap)
            val chosen = (i == 0) == aChosen
            text(c, if (i == 0) aWord else bWord, r.left + 10f, top + 19f, 19f, if (chosen) GOLD else DIMC, Paint.Align.LEFT)
            val box = RectF(r.left + 10f, top + label, r.right - 10f, top + label + h)
            frame(c, box, Color.rgb(90, 76, 52), 1.5f, Color.rgb(20, 16, 10))
            c.save(); c.clipRect(box); draw(box, i == 0); c.restore()
            if (!chosen) c.drawRect(box, pDim)
            frame(c, box, if (chosen) GOLD else Color.rgb(90, 76, 52), if (chosen) 3f else 1.5f)
        }
    }

    // ---- Bottom screen > Home: THE EXTRAS ROW (ClockBand) under the clock chip, between the controller's two groups

    private val EXTRA_KIND = mapOf("Hearthstone" to ClockBand.Kind.HEARTH, "Class teleport" to ClockBand.Kind.TELEPORT,
        "Free bag slots" to ClockBand.Kind.BAGS, "Ammo" to ClockBand.Kind.AMMO)

    /** a stand-in icon drawn here (no icon is asked of the game's art for a picture): an arrow (ammo) or a swirl (a teleport) */
    private val arrowIcon by lazy { drawnIcon { c, p ->
        p.color = Color.rgb(200, 170, 110); p.strokeWidth = 5f; c.drawLine(14f, 50f, 48f, 16f, p)
        p.style = Paint.Style.FILL; c.drawPath(Path().apply { moveTo(52f, 12f); lineTo(36f, 18f); lineTo(46f, 28f); close() }, p)
        p.color = Color.rgb(230, 230, 230); c.drawPath(Path().apply { moveTo(14f, 50f); lineTo(10f, 40f); lineTo(20f, 46f); close() }, p) } }
    private val swirlIcon by lazy { drawnIcon { c, p ->
        p.color = Color.rgb(140, 170, 255); p.strokeWidth = 4f
        for (i in 0..2) c.drawArc(RectF(12f + i * 6f, 12f + i * 6f, 52f - i * 6f, 52f - i * 6f), i * 120f, 250f, false, p) } }
    private fun drawnIcon(paint: (Canvas, Paint) -> Unit): Bitmap {
        val b = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(36, 30, 24))
        paint(c, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND })
        return b
    }

    /** one extras item as Home draws it, with a made-up value: the Hearthstone ready (its glow), the teleport cooling, a purse... */
    private fun bandItem(k: ClockBand.Kind): UiKit.BandItem = when (k) {
        ClockBand.Kind.HEARTH -> UiKit.BandItem.Icon(Icons.get(ItemCd.hearthIcon), "", glow = true)
        ClockBand.Kind.TELEPORT -> UiKit.BandItem.Icon(ItemCd.teleport?.let { Icons.get(it.icon) } ?: swirlIcon, "12:04", cooling = true)
        ClockBand.Kind.BAGS -> UiKit.BandItem.Icon(Icons.get(133633), "14")
        ClockBand.Kind.AMMO -> UiKit.BandItem.Icon(arrowIcon, "812")
    }

    /**
     * the top of Home in [box]: the controller's two groups, the clock chip centred at the top (as Home has it), the extras row [kinds]
     * under it (UiKit.clockBand, Home's own drawing), [ring] circled in gold. Home's px, the middle of its width shown. [gold] = the
     * chip's gold line under the battery; [ringGold] circles it.
     */
    private fun band(c: Canvas, box: RectF, kinds: List<ClockBand.Kind>, ring: ClockBand.Kind?, gold: Boolean, ringGold: Boolean = false) {
        val x0 = 380f; val x1 = 860f
        val k = box.width() / (x1 - x0)
        c.save(); c.translate(box.left, box.top + (box.height() - 230f * k) / 2f); c.scale(k, k); c.translate(-x0, 0f)
        diamond(c, 300f, 150f, 30f, 64f); diamond(c, 940f, 150f, 30f, 64f)
        UiKit.clockChip(c, 620f, ClockBand.CHIP_TOP, "12:30", 76, gold = if (gold) PREVIEW_GOLD else null)
        if (gold && ringGold) {
            val gw = UiKit.coinsWidth(PREVIEW_GOLD, UiKit.goldPaint())
            val gy = ClockBand.CHIP_TOP + ClockBand.GOLD_Y
            pStroke.color = GOLD; pStroke.strokeWidth = 3f / k
            c.drawRoundRect(RectF(620f - gw / 2f - 9f, gy - 15f, 620f + gw / 2f + 9f, gy + 15f), 8f, 8f, pStroke)
        }
        val spot = floatArrayOf(440f, 98f, 800f, 210f, 620f, 154f)
        val floor = ClockBand.chipBottom(gold) + ClockBand.CHIP_PAD
        val items = kinds.map { bandItem(it) }
        UiKit.clockBand(c, spot, items, floor)
        val i = if (ring == null) -1 else kinds.indexOf(ring)
        if (i >= 0) UiKit.clockBandBoxes(spot, items, floor).getOrNull(i)?.let { b ->
            pStroke.color = GOLD; pStroke.strokeWidth = 3f / k
            c.drawRoundRect(RectF(b.left - 9f, b.top - 7f, b.right + 9f, b.bottom + 5f), 8f, 8f, pStroke)
        }
        c.restore()
    }

    private fun extras(c: Canvas, r: RectF, id: String, s: State) {
        val me = EXTRA_KIND.getValue(id)
        val on = EXTRA_KIND.filter { (row, _) -> extraOn(row, s) }.values.toSet() + me
        val kinds = ClockBand.Kind.values().filter { it in on }
        twoWays(c, r, "On", "Off", extraOn(id, s), 300f) { box, first ->
            if (first) band(c, box, kinds, me, s.clockGold) else band(c, box, kinds - me, null, s.clockGold)
        }
    }

    /** a made-up purse for the pictures: 123g 45s 67c */
    private const val PREVIEW_GOLD = 1_234_567L

    /** Bottom screen > Home > Gold: the clock chip with the total gold under the battery (On, circled) or the chip alone (Off) */
    private fun goldLine(c: Canvas, r: RectF, s: State) {
        val kinds = EXTRA_KIND.filter { (row, _) -> extraOn(row, s) }.values.toSet().let { on -> ClockBand.Kind.values().filter { it in on } }
        twoWays(c, r, "On", "Off", s.clockGold, 300f) { box, first -> band(c, box, kinds, null, first, ringGold = first) }
    }

    // ---- Bottom screen > Tap-to-cast bar: Home in four thumbnails, Off / Left / Right / Both

    private fun castBar(c: Canvas, r: RectF, side: Int) {
        val gap = 14f; val label = 26f
        val w = (r.width() - 20f - gap) / 2f
        val h = minOf(w * 1.3f, (r.height() - gap) / 2f - label)
        for (i in 0..3) {
            val x = r.left + 10f + (i % 2) * (w + gap); val top = r.top + (i / 2) * (label + h + gap)
            val chosen = i == side
            text(c, SIDE_WORDS[i], x, top + 19f, 19f, if (chosen) GOLD else DIMC, Paint.Align.LEFT)
            val box = RectF(x, top + label, x + w, top + label + h)
            homeThumb(c, box, i)
            if (!chosen) c.drawRect(box, pDim)
            frame(c, box, if (chosen) GOLD else Color.rgb(90, 76, 52), if (chosen) 3f else 1.5f)
        }
    }

    /** a small Home: the controller band on top, the chat left, the minimap right, the bar of six under one column or both */
    private fun homeThumb(c: Canvas, b: RectF, side: Int) {
        frame(c, b, BRONZE, 1.5f, Color.rgb(20, 16, 10))
        val g = 5f; val bandB = b.top + b.height() * 0.3f
        diamond(c, b.left + b.width() * 0.22f, (b.top + bandB) / 2f, 6f, 13f); diamond(c, b.right - b.width() * 0.22f, (b.top + bandB) / 2f, 6f, 13f)
        val mid = b.centerX()
        val slot = minOf((b.width() / 2f - 2f * g) / 6.6f, 22f)
        val barH = slot + 8f
        val leftBar = side == 1 || side == 3; val rightBar = side == 2 || side == 3
        val chat = RectF(b.left + g, bandB + g, mid - g / 2f, b.bottom - g - (if (leftBar) barH + g else 0f))
        val map = RectF(mid + g / 2f, bandB + g, b.right - g, b.bottom - g - (if (rightBar) barH + g else 0f))
        frame(c, chat, Theme.bronzeDim, 1.5f, Color.rgb(28, 22, 15))
        pFill.color = DIMC
        for (j in 0 until 3) { val y = chat.bottom - 10f - j * 10f; if (y > chat.top + 6f) c.drawRect(chat.left + 6f, y - 3f, chat.right - 6f - j * 10f, y, pFill) }
        frame(c, map, Theme.bronzeDim, 1.5f, Color.rgb(28, 22, 15))
        val mr = minOf(map.width(), map.height()) / 2f - 5f
        pFill.color = Color.rgb(60, 82, 52); c.drawCircle(map.centerX(), map.centerY(), mr, pFill)
        pStroke.color = BRONZE; pStroke.strokeWidth = 1.5f; c.drawCircle(map.centerX(), map.centerY(), mr, pStroke)
        fun bar(l: Float, rr: Float) {
            val well = RectF(l, b.bottom - g - barH, rr, b.bottom - g)
            frame(c, well, GOLD, 1.5f, Color.rgb(36, 28, 18))
            val s = minOf((well.width() - 8f) / 6f - 3f, well.height() - 8f)
            val x0 = well.centerX() - (6f * s + 5f * 3f) / 2f
            for (j in 0 until 6) { pFill.color = SPELLS[j]; c.drawRect(x0 + j * (s + 3f), well.centerY() - s / 2f, x0 + j * (s + 3f) + s, well.centerY() + s / 2f, pFill) }
        }
        when (side) { 1 -> bar(chat.left, chat.right); 2 -> bar(map.left, map.right); 3 -> bar(chat.left, map.right) }
    }

    // ---- Bottom screen > Map > World map: the stitched continent, every zone or only the explored ones

    private fun worldMap(c: Canvas, r: RectF, all: Boolean) {
        twoWays(c, r, "Show all", "Explored only", all, 320f) { box, first -> continent(c, box, first) }
    }

    /** a made-up continent of zones on parchment; [all] false = the zones not yet explored under the fog */
    private fun continent(c: Canvas, box: RectF, all: Boolean) {
        UiKit.parchment(c, RectF(box.left + 2f, box.top + 2f, box.right - 2f, box.bottom - 2f), 14f)
        val cols = 6; val rows = 4
        val inner = RectF(box.left + 24f, box.top + 18f, box.right - 24f, box.bottom - 18f)
        val cw = inner.width() / cols; val ch = inner.height() / rows
        val land = Path()
        land.addOval(RectF(inner.left, inner.top, inner.centerX() + cw, inner.bottom), Path.Direction.CW)
        land.addOval(RectF(inner.centerX() - cw * 1.4f, inner.top + ch * 0.3f, inner.right, inner.bottom - ch * 0.2f), Path.Direction.CW)
        c.save(); c.clipPath(land)
        val greens = intArrayOf(Color.rgb(96, 120, 70), Color.rgb(130, 120, 74), Color.rgb(84, 104, 66), Color.rgb(146, 110, 70), Color.rgb(110, 130, 86))
        // the explored ones: round the start zone (left middle)
        val explored = setOf(6, 7, 12, 13, 14, 8)
        for (j in 0 until rows) for (i in 0 until cols) {
            val n = j * cols + i
            val jx = ((n * 37) % 11 - 5) * 1.6f; val jy = ((n * 53) % 9 - 4) * 1.6f
            val cell = RectF(inner.left + i * cw + jx, inner.top + j * ch + jy, inner.left + (i + 1) * cw + jx + 2f, inner.top + (j + 1) * ch + jy + 2f)
            pFill.color = greens[n % greens.size]; c.drawRect(cell, pFill)
            pStroke.color = Color.argb(150, 70, 50, 30); pStroke.strokeWidth = 1.5f; c.drawRect(cell, pStroke)
            if (!all && n !in explored) { pFill.color = Color.argb(215, 22, 18, 14); c.drawRect(cell, pFill) }
        }
        c.restore()
        pStroke.color = Color.rgb(90, 70, 40); pStroke.strokeWidth = 2.5f; c.drawPath(land, pStroke)
        // the player: the map's gold arrow in the start zone
        val px = inner.left + cw * 1.5f; val py = inner.top + ch * 1.6f
        pFill.color = Color.rgb(255, 230, 120)
        c.drawPath(Path().apply { moveTo(px, py - 10f); lineTo(px - 8f, py + 9f); lineTo(px + 8f, py + 9f); close() }, pFill)
    }

    // ---- Controller > Smart Y: Y out of combat with nothing targeted casts the list's buffs, top first

    private fun smartY(c: Canvas, r: RectF, s: State, t: Float, glyph: Glyph) {
        val on = s.smartY == true
        val period = 6f; val k = t % period
        val press = (k % 2f) < 0.3f
        val step = (k / 2f).toInt()                                  // 0, 1, 2: the press casts the list's next one
        chip(c, r.left + 20f, r.top + 10f, "Out of combat", on)
        chip(c, r.left + 210f, r.top + 10f, "Nothing targeted", on)
        val cy = r.top + maxOf(150f, r.height() * 0.32f)
        glyph.draw(c, "y", s.glyphSet, r.left + 70f, cy, if (press && on) 74f else 62f)
        pStroke.color = DIMC; pStroke.strokeWidth = 3f
        c.drawLine(r.left + 120f, cy, r.left + 180f, cy, pStroke)
        c.drawLine(r.left + 180f, cy, r.left + 168f, cy - 10f, pStroke); c.drawLine(r.left + 180f, cy, r.left + 168f, cy + 10f, pStroke)
        val names = listOf("1st on your list", "2nd", "3rd")
        for ((i, n) in names.withIndex()) {
            val row = RectF(r.left + 200f, cy - 100f + i * 72f, r.right - 10f, cy - 44f + i * 72f)
            val now = on && i == step && (k % 2f) in 0.2f..1.7f
            val done = on && (i < step || (i == step && (k % 2f) >= 0.2f))
            frame(c, row, if (now) GOLD else Color.rgb(70, 60, 45), if (now) 3f else 1.5f, if (now) Color.rgb(46, 36, 17) else Color.rgb(24, 20, 16))
            pFill.color = SPELLS[(i + 3) % SPELLS.size]; c.drawRect(row.left + 8f, row.top + 8f, row.left + 8f + row.height() - 16f, row.bottom - 8f, pFill)
            text(c, n, row.left + row.height() + 6f, row.centerY() + 7f, 18f, if (done) GOLD else DIMC, Paint.Align.LEFT)
        }
        text(c, if (on) "each press: the next one you lack" else if (s.smartY == false) "Y: its own action" else "needs the game running",
            r.centerX(), cy + 190f, 19f, if (on) GOLD else DIMC)
    }

    // ---- Controller > Combat helpers

    /** a party plate: the member's class stripe, the health fill (0 = dead: grey, a skull mark) */
    private fun plate(c: Canvas, b: RectF, hp: Float, cls: Int, edge: Int? = null) {
        frame(c, b, edge ?: Color.rgb(70, 60, 45), if (edge != null) 3f else 1.5f, Color.rgb(24, 20, 16))
        pFill.color = cls; c.drawRect(b.left + 4f, b.top + 4f, b.left + 10f, b.bottom - 4f, pFill)
        val bar = RectF(b.left + 16f, b.top + 6f, b.right - 6f, b.bottom - 6f)
        pFill.color = Color.rgb(40, 34, 28); c.drawRect(bar, pFill)
        if (hp > 0f) { pFill.color = Color.rgb(60, 170, 60); c.drawRect(bar.left, bar.top, bar.left + bar.width() * hp, bar.bottom, pFill) }
        else {
            pStroke.color = Color.rgb(170, 170, 170); pStroke.strokeWidth = 3f
            val cx = bar.centerX(); val cy = bar.centerY(); val d = minOf(8f, bar.height() / 2f - 1f)
            c.drawLine(cx - d, cy - d, cx + d, cy + d, pStroke); c.drawLine(cx - d, cy + d, cx + d, cy - d, pStroke)
        }
    }
    private val CLASS_RGB by lazy { intArrayOf(Color.rgb(196, 30, 58), Color.rgb(255, 255, 255), Color.rgb(63, 199, 235), Color.rgb(170, 211, 114)) }

    /** Party mode: Select brings the party's plates up on Home (On); nothing (Off) */
    private fun partyMode(c: Canvas, r: RectF, on: Boolean, t: Float, glyph: Glyph, set: Int) {
        val k = t % 4f
        val press = k < 0.3f || k in 3.4f..3.7f
        val shown = on && k in 0.3f..3.4f
        val h = RectF(r.left + 10f, r.top + 10f, r.right - 10f, r.top + 10f + (r.width() - 20f) * 0.82f)
        val (_, chat, ctl) = miniHome(c, h)
        diamond(c, ctl.centerX(), ctl.centerY(), min(ctl.height() / 6.5f, 18f), min(ctl.height() / 3.2f, 36f))
        if (shown) {
            val a = ((k - 0.3f) / 0.25f).coerceIn(0f, 1f)
            val col = RectF(chat.left, chat.top - 6f, chat.right, chat.top - 6f + 4 * 34f)
            c.saveLayerAlpha(col.left - 4f, col.top - 4f, col.right + 4f, col.bottom + 4f, (255 * a).toInt())
            for (i in 0 until 4) plate(c, RectF(col.left, col.top + i * 34f, col.right, col.top + i * 34f + 30f), 1f - i * 0.18f, CLASS_RGB[i])
            c.restore()
        }
        val y = h.bottom + 50f
        glyph.draw(c, "select", set, r.left + 60f, y, if (press) 58f else 48f)
        text(c, "Select", r.left + 100f, y + 7f, 19f, if (press) GOLD else DIMC, Paint.Align.LEFT)
        text(c, if (!on) "nothing" else if (shown) "Party mode on" else "Party mode off", r.right - 20f, y + 7f, 19f,
            if (shown) GOLD else DIMC, Paint.Align.RIGHT)
    }

    /** Smart Res: a tap on a dead member's plate casts the res (On: its soft green edge while armed); a tap only (Off) */
    private fun smartRes(c: Canvas, r: RectF, on: Boolean, t: Float) {
        val k = t % 4.4f
        val tapped = k in 0.5f..0.8f
        val cast = if (on) ((k - 0.8f) / 1.6f).coerceIn(0f, 1f) else 0f
        val alive = on && k >= 2.4f
        val top = r.top + 20f; val ph = minOf(72f, (r.height() - 200f) / 4f - 10f)
        for (i in 0 until 4) {
            val b = RectF(r.left + 30f, top + i * (ph + 10f), r.right - 30f, top + i * (ph + 10f) + ph)
            val dead = i == 2
            val hp = if (!dead) 1f - i * 0.15f else if (alive) ((k - 2.4f) / 0.4f).coerceIn(0.05f, 1f) else 0f
            plate(c, b, hp, CLASS_RGB[i], if (dead && on && !alive) Color.rgb(90, 200, 110) else null)
            if (dead && tapped) {
                pStroke.color = GOLD; pStroke.strokeWidth = 4f
                c.drawCircle(b.centerX(), b.centerY(), 18f + 40f * ((k - 0.5f) / 0.3f), pStroke)
            }
        }
        val y = top + 4 * (ph + 10f) + 30f
        text(c, "a tap on the dead member", r.left + 30f, y, 18f, if (tapped) GOLD else DIMC, Paint.Align.LEFT)
        if (on) {
            UiKit.standing(c, RectF(r.left + 30f, y + 20f, r.right - 30f, y + 54f), Color.rgb(90, 200, 110), cast, if (cast > 0f) "your res" else "", 18f)
            text(c, if (alive) "back on their feet" else "", r.centerX(), y + 86f, 19f, GOLD)
        } else text(c, "no res", r.centerX(), y + 40f, 19f, DIMC)
    }

    /** Start attack on hostile target: an enemy targeted, then the crossed swords light (On) or stay dark (Off) */
    private fun attackHostile(c: Canvas, r: RectF, on: Boolean, t: Float) {
        val k = t % 2.8f
        val targeted = k >= 0.4f && k < 2.5f
        val cx = r.centerX(); val cy = r.top + r.height() * 0.38f
        val mx = cx - 100f
        // the enemy: a red body under a red nameplate; the target's ring under it once targeted
        if (targeted) { pStroke.color = Color.rgb(230, 60, 40); pStroke.strokeWidth = 4f; c.drawOval(RectF(mx - 46f, cy + 22f, mx + 46f, cy + 48f), pStroke) }
        pFill.color = Color.rgb(150, 50, 40); c.drawCircle(mx, cy, 30f, pFill)
        val np = RectF(mx - 50f, cy - 62f, mx + 50f, cy - 48f)
        pFill.color = Color.rgb(40, 20, 16); c.drawRect(np, pFill)
        pFill.color = Color.rgb(200, 40, 30); c.drawRect(np.left + 2f, np.top + 2f, np.right - 2f, np.bottom - 2f, pFill)
        text(c, if (targeted) "an enemy targeted" else "an enemy", mx, cy + 84f, 18f, if (targeted) GOLD else DIMC)
        pStroke.color = DIMC; pStroke.strokeWidth = 3f
        c.drawLine(cx - 40f, cy, cx + 40f, cy, pStroke)
        c.drawLine(cx + 40f, cy, cx + 28f, cy - 10f, pStroke); c.drawLine(cx + 40f, cy, cx + 28f, cy + 10f, pStroke)
        val lit = on && targeted && k >= 0.6f
        val sw = UiKit.tex("combat_swords")
        if (sw != null) {
            pPic.alpha = if (lit) 255 else 90
            c.drawBitmap(sw, null, RectF(cx + 70f, cy - 36f, cx + 150f, cy + 36f), pPic)
            pPic.alpha = 255
        } else swords(c, cx + 110f, cy, 34f, if (lit) GOLD else Theme.bronzeDim)
        text(c, if (on) "your auto-attack starts" else "no auto-attack", cx + 110f, cy + 84f, 18f, if (lit) GOLD else DIMC)
    }

    /** Combat Auto Shot: at range the shot flies (On, Smart), up close the swords; the three choices, the chosen one lit */
    private fun autoShot(c: Canvas, r: RectF, mode: Int, t: Float) {
        val shoots = mode >= 1
        // at range: the player, an arrow flying to a far enemy
        val sp = maxOf(1f, r.height() / 560f)
        val y1 = r.top + 60f * sp; val y2 = r.top + 170f * sp
        val px = r.left + 50f
        fun player(y: Float) { pFill.color = Color.rgb(255, 230, 120); c.drawCircle(px, y, 14f, pFill) }
        fun enemy(x: Float, y: Float) { pFill.color = Color.rgb(150, 50, 40); c.drawCircle(x, y, 18f, pFill) }
        player(y1); enemy(r.right - 50f, y1)
        if (shoots) {
            val u = (t % 1.2f) / 1.2f
            val ax = px + 24f + (r.right - 124f - px) * u
            pStroke.color = GOLD; pStroke.strokeWidth = 3f
            c.drawLine(ax - 26f, y1, ax, y1, pStroke); c.drawLine(ax, y1, ax - 9f, y1 - 7f, pStroke); c.drawLine(ax, y1, ax - 9f, y1 + 7f, pStroke)
        }
        text(c, if (shoots) "at range: Auto Shot" else "at range: no Auto Shot", r.centerX(), y1 + 44f, 18f, if (shoots) GOLD else DIMC)
        // up close: the enemy beside the player, the swords
        player(y2); enemy(px + 50f, y2)
        swords(c, px + 150f, y2, 20f, if (shoots) GOLD else Theme.bronzeDim)
        text(c, "up close: auto attack", px + 190f, y2 + 7f, 18f, if (shoots) GOLD else DIMC, Paint.Align.LEFT)
        // the three choices
        val words = listOf("No Auto Shot", "Auto Shot in a fight", "Off for an attack you start")
        for (i in 0..2) {
            val top = r.top + 230f * sp + i * 72f
            val chosen = i == mode
            choiceRow(c, r, top, 56f, chosen)
            gText(c, SHOT_NAMES[i], r.left + 24f, top + 37f, 24f, if (chosen) GOLD else DIMC, kind = 0)
            gText(c, words[i], r.right - 20f, top + 36f, 18f, if (chosen) TEXT else DIMC, Paint.Align.RIGHT)
        }
    }
}
