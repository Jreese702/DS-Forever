package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * THE CONTROLLER TAB'S PICTURES (the beautify pass; the user, 2026-11-17: "I REALLY REALLY love what you did with the top screen settings
 * menu with the pictures and want to do something similar to the other menus"): one picture per Controller tile, the same contract as
 * Top screen's [TilePictures] - kit and cast art first (the pad glyphs in the chosen set, the wheels drawn by the app's own wheel code, the
 * kit's crossed swords), then icons from icons.pak, then fixed sample fills, then our own drawing where a piece is missing (so a phone
 * with no kit yet still gets a picture). No screen reading, no hidden values: sample icons and numbers only.
 *
 * The drawings started as COPIES of SettingsPreview's (the "What it does" panel, read-only here: the integrator retires its rows) and were
 * re-framed for the tile's 2:1 well. Each is drawn in a space [H] = 108 tall and [W] (216) or [WIDE_W] (460, the wide Smart Y tile) wide,
 * scaled into the box it is given, so the band's 438 x 220 picture is the same drawing, larger. [Look.t] = the band's clock (seconds;
 * 0 = the tile's still frame: only the open band animates).
 */
object ControllerPictures {
    const val W = 216f
    const val WIDE_W = 460f
    const val H = 108f

    /** what a picture shows: the choices that change its look (now, as chosen), [t] the band's clock in seconds, [glide] 0..1 a change's
     *  move (ABXY), [prevNintendo] where the buttons were before it */
    data class Look(
        val nintendo: Boolean = true, val prevNintendo: Boolean = true, val glide: Float = 1f,
        val on: Boolean = true, val tap: Boolean = true, val pickA: Boolean = true, val gameWheel: Boolean = false,
        val shot: Int = 1, val smartIcons: List<Int> = emptyList(), val smartDim: List<Boolean> = emptyList(),
        val items: List<UiKit.RadialItem?> = emptyList(), val t: Float = 0f,
    )

    /** the ids with a picture of their own (every Controller tile) */
    val IDS = setOf(ABXY, GLYPHS, PET_B, SMART_Y, PAUSE, ITEM, SPELL, CLASS_L, CLASS_R, GameUi.PMODE_ID, GameUi.SRES_ID, GameUi.ATK_ID,
        ATTACK_CAST, GameUi.SHOT_ID)

    const val ABXY = "abxy"; const val GLYPHS = "glyphs"; const val PET_B = "petb"; const val SMART_Y = "smarty"
    const val PAUSE = "pause"; const val ITEM = "item"; const val SPELL = "spell"; const val CLASS_L = "classL"; const val CLASS_R = "classR"
    const val ATTACK_CAST = "attackcast"

    /** sample icons (icons.pak file ids; never the player's own): food, water and three buffs for Smart Y, a few consumables and spells
     *  for the wheels, the res, the pet, Auto Shot, a harmful spell */
    val SAMPLE_SMART = listOf(133964, 132794, 135987, 136078, 135932)
    val SAMPLE_ITEMS = listOf(134830, 134851, 133964, 132794, 133639, 134062, 133633, 132333)
    val SAMPLE_SPELLS = listOf(135468, 136048, 135815, 136085, 135808, 136026, 136095, 132222)
    const val ICON_RES = 135955
    const val ICON_PET = 132161
    const val ICON_SHOT = 132369
    const val ICON_HARM = 135808

    private val pF = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pS = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val pB = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    /** the picture space's width for [r]: the wide one for a wide box (the Smart Y tile's 460 x 108 well) */
    fun spaceW(r: RectF): Float = if (r.width() / r.height() > 3f) WIDE_W else W

    /** [id]'s picture into [r] (its own rounded clip; the canvas left as it was); false = no picture of its own (nothing drawn) */
    fun draw(c: Canvas, ctx: Context?, id: String, r: RectF, look: Look): Boolean {
        if (id !in IDS) return false
        val sw = spaceW(r)
        val save = c.save()
        val rad = 8f * r.height() / H
        path.reset(); path.addRoundRect(r, rad, rad, Path.Direction.CW)
        c.clipPath(path)
        val k = minOf(r.width() / sw, r.height() / H)
        c.translate(r.centerX() - sw * k / 2f, r.centerY() - H * k / 2f)
        c.scale(k, k)
        ground(c, sw)
        runCatching { picture(c, ctx, id, sw, look) }
        c.restoreToCount(save)
        return true
    }

    /** the well's ground: the world's dark stand-in, a soft light behind the middle (fixed fills) */
    private fun ground(c: Canvas, w: Float) {
        pF.shader = LinearGradient(0f, 0f, 0f, H, intArrayOf(Color.rgb(30, 32, 38), Color.rgb(20, 21, 25), Color.rgb(14, 15, 18)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, H, pF)
        pF.shader = RadialGradient(w / 2f, H * 0.5f, w * 0.6f, intArrayOf(Color.argb(40, 255, 220, 150), Color.argb(0, 0, 0, 0)),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, H, pF)
        pF.shader = null
    }

    private fun picture(c: Canvas, ctx: Context?, id: String, w: Float, look: Look) {
        when (id) {
            ABXY -> abxy(c, look)
            GLYPHS -> glyphSet(c)
            PET_B -> petFollow(c, look)
            SMART_Y -> smartY(c, w, look)
            PAUSE -> pauseWheel(c, look)
            ITEM -> wheel(c, look, items = true)
            SPELL -> wheel(c, look, items = false)
            CLASS_L -> classWheel(c, ctx, look, left = true)
            CLASS_R -> classWheel(c, ctx, look, left = false)
            GameUi.PMODE_ID -> partyMode(c, look)
            GameUi.SRES_ID -> smartRes(c, look)
            GameUi.ATK_ID -> startAttack(c, look)
            ATTACK_CAST -> attackCast(c, look)
            GameUi.SHOT_ID -> autoShot(c, look)
        }
    }

    // ---------------------------------------------------------------- Buttons

    /** where each face action sits: Xbox = A bottom, B right, X left, Y top; Nintendo (the Thor's print) = A right, B bottom, X top, Y left */
    private fun spot(action: String, nintendo: Boolean): Pair<Float, Float> = when (if (nintendo) NIN[action] else XBOX[action]) {
        "top" -> 0f to -1f; "left" -> -1f to 0f; "right" -> 1f to 0f; else -> 0f to 1f
    }
    private val XBOX = mapOf("a" to "bottom", "b" to "right", "x" to "left", "y" to "top")
    private val NIN = mapOf("a" to "right", "b" to "bottom", "x" to "top", "y" to "left")

    /** a pad's body: a dark capsule with two grips, a D-pad cross on the left, the face buttons' place on the right */
    private fun padBody(c: Canvas, cx: Float, cy: Float, w: Float, h: Float, dpad: Boolean = true) {
        val body = RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        pF.shader = LinearGradient(0f, body.top, 0f, body.bottom, Color.rgb(58, 58, 64), Color.rgb(30, 30, 34), Shader.TileMode.CLAMP)
        c.drawRoundRect(body, h / 2f, h / 2f, pF)
        pF.shader = null
        pS.color = Color.argb(200, 0, 0, 0); pS.strokeWidth = 1.4f; c.drawRoundRect(body, h / 2f, h / 2f, pS)
        pS.color = Color.argb(60, 255, 255, 255); pS.strokeWidth = 0.8f
        c.drawArc(RectF(body.left + 3f, body.top + 2f, body.right - 3f, body.bottom), 200f, 140f, false, pS)
        if (!dpad) return
        // the D-pad
        val dx = body.left + h * 0.55f; val dy = cy; val a = h * 0.2f; val t = h * 0.075f
        pF.color = Color.rgb(18, 18, 20)
        c.drawRoundRect(dx - t, dy - a, dx + t, dy + a, 2f, 2f, pF)
        c.drawRoundRect(dx - a, dy - t, dx + a, dy + t, 2f, 2f, pF)
    }

    /** ABXY location: the four face glyphs as a diamond where each sits now (a change glides them over in the band) */
    private fun abxy(c: Canvas, look: Look) {
        padBody(c, 108f, 56f, 176f, 84f)
        val cx = 148f; val cy = 56f; val gap = 21f
        val e = 1f - (1f - look.glide).let { it * it * it }
        for (k in listOf("x", "y", "b", "a")) {
            val (ax, ay) = spot(k, look.prevNintendo); val (bx, by) = spot(k, look.nintendo)
            val px = cx + (ax + (bx - ax) * e) * gap; val py = cy + (ay + (by - ay) * e) * gap
            pF.color = Color.rgb(16, 16, 18); c.drawCircle(px, py, 12f, pF)
            glyph(c, k, px, py, 22f)
        }
    }

    /** Button glyphs: A / B / X / Y in the chosen set, large, over the pad's silhouette */
    private fun glyphSet(c: Canvas) {
        padBody(c, 108f, 56f, 196f, 92f, dpad = false)
        pF.color = Color.argb(150, 10, 10, 12); c.drawRoundRect(RectF(22f, 32f, 194f, 80f), 24f, 24f, pF)
        for ((i, k) in listOf("a", "b", "x", "y").withIndex()) glyph(c, k, 46f + i * 41.5f, 56f, 36f)
    }

    /** B: pet follow - the B glyph; your pet (its icon) at an enemy (Off) or walking back after you (On, the trail behind it) */
    private fun petFollow(c: Canvas, look: Look) {
        val k = if (look.t > 0f) ((look.t % 3f) / 1.4f).coerceIn(0f, 1f) else 1f
        val back = if (look.on) k else 0f
        glyph(c, "b", 28f, 56f, 34f)
        figure(c, 82f, 84f, 44f, Color.rgb(212, 186, 120))
        // the enemy on the right
        pF.color = Color.rgb(150, 50, 40); c.drawCircle(188f, 62f, 13f, pF)
        pF.color = Color.rgb(40, 20, 16); c.drawRect(170f, 38f, 206f, 44f, pF)
        pF.color = Color.rgb(200, 40, 30); c.drawRect(171f, 39f, 205f, 43f, pF)
        val atX = 160f; val homeX = 112f
        val px = atX + (homeX - atX) * back
        if (back > 0.05f) {
            pS.color = Color.argb(150, 226, 190, 110); pS.strokeWidth = 1.6f
            pS.pathEffect = android.graphics.DashPathEffect(floatArrayOf(3f, 4f), 0f)
            c.drawLine(px + 16f, 72f, atX + 8f, 72f, pS); pS.pathEffect = null
        }
        icon(c, ICON_PET, px - 14f, 58f, 28f, Color.rgb(110, 170, 90), dim = false)
        if (back == 0f) {                                                       // its strikes
            pS.color = Theme.gold; pS.strokeWidth = 1.8f
            c.drawLine(172f, 58f, 180f, 50f, pS); c.drawLine(174f, 66f, 183f, 57f, pS)
        }
    }

    /** Smart Y: the Y glyph on the left, then the list's first five entries (food, water, buffs), the ones that cannot act dimmed */
    private fun smartY(c: Canvas, w: Float, look: Look) {
        glyph(c, "y", 32f, 54f, 40f)
        pS.color = if (look.on) Theme.gold else Color.rgb(120, 110, 96); pS.strokeWidth = 2.2f
        c.drawLine(58f, 54f, 74f, 54f, pS); c.drawLine(74f, 54f, 68f, 49f, pS); c.drawLine(74f, 54f, 68f, 59f, pS)
        val ids = look.smartIcons.ifEmpty { SAMPLE_SMART }.take(if (w > W) 5 else 3)
        val s = if (w > W) 50f else 34f
        val gap = if (w > W) 26f else 10f
        val x0 = 86f
        for ((i, id) in ids.withIndex()) {
            val x = x0 + i * (s + gap)
            val dim = look.smartDim.getOrElse(i) { false } || !look.on
            if (i > 0) { pF.color = if (look.on) Theme.gold else Color.rgb(90, 84, 74); c.drawRect(x - gap + 3f, 53f, x - 3f, 56f, pF) }
            slot(c, id, x, 54f - s / 2f, s, dim)
        }
    }

    // ---------------------------------------------------------------- Wheels

    private val pauseScreen = RectF(0f, 0f, 1240f, 1080f)

    /** the Pause menu: the app's own pause wheel (UiKit.pauseRadial on a 1240 x 1080 screen, its wheel cut out and scaled into the well);
     *  the Start glyph at the hub; Game's wheel = the same wheel lit gold, a small top screen over it */
    private fun pauseWheel(c: Canvas, look: Look) {
        // the wheel's place on that screen (UiKit.pauseGeo's free layout: centre 620, 522; rim 270 x 1.3)
        val sc = 104f / 620f
        val sv = c.save()
        val drawn = runCatching {
            c.clipRect(56f, 2f, 160f, 106f)
            c.translate(108f - 620f * sc, 54f - 522f * sc); c.scale(sc, sc)
            val keepB = UiKit.pauseBarsBottom; val keepS = UiKit.pauseStripTop
            UiKit.pauseBarsBottom = 0f; UiKit.pauseStripTop = 0f
            try {
                val slices = HomePauseModel.Flow().slices().map { it?.let { sl -> UiKit.PauseSlice(sl.key, sl.label, sl.enabled) } }
                UiKit.pauseRadial(c, pauseScreen, android.graphics.PointF(620f, 1120f), 140f, slices, 0, pointed(look, 8), glyphs = false,
                    up = null, down = null, hubGold = if (look.gameWheel) 1f else 0f)
            } finally { UiKit.pauseBarsBottom = keepB; UiKit.pauseStripTop = keepS }
            true
        }.getOrDefault(false)
        c.restoreToCount(sv)
        if (!drawn) sketchWheel(c, 108f, 54f, 48f, pointed(look, 6), SAMPLE_SPELLS)
        hub(c, 108f, 54f) { glyphOrWord(c, START, "Start", 108f, 54f, 24f) }
        if (look.gameWheel) screens(c, top = true) else screens(c, top = false)
        openPick(c, look)
    }

    /** the item wheel ([items]) or the spell wheel: the app's own wheel at rest (UiKit.radialPreview) with the player's page 1, or
     *  sample icons; its opening button at the hub */
    private fun wheel(c: Canvas, look: Look, items: Boolean) {
        val list = look.items.takeIf { it.any { i -> i != null } } ?: (if (items) SAMPLE_ITEMS else SAMPLE_SPELLS)
            .map { UiKit.RadialItem(Icons.get(it), "", if (items) 3 else -1) }
        radial(c, 108f, 54f, 104f, list, pointed(look, 8))
        hub(c, 108f, 54f) {
            if (items) glyphOrWord(c, "gen_up", "Up", 108f, 54f, 22f)
            else UiKit.tex("ricon_spellbook")?.let { b -> c.drawBitmap(b, null, RectF(98f, 44f, 118f, 64f), pB) }
                ?: glyphOrWord(c, "-", "Spells", 108f, 54f, 20f)
        }
        openPick(c, look)
    }

    /** the left / right class radial: the character's own (PreviewStage) or a sample wheel; LB + LT (RB + RT) at the hub */
    private fun classWheel(c: Canvas, ctx: Context?, look: Look, left: Boolean) {
        val side = 104f
        val sv = c.save()
        val drawn = ctx != null && runCatching {
            c.translate(108f - side / 2f, 54f - side / 2f)
            PreviewStage.classWheel(ctx, c, side, side, left, pointed(look, 8))
        }.getOrDefault(false)
        c.restoreToCount(sv)
        if (!drawn) radial(c, 108f, 54f, 104f, (if (left) SAMPLE_SPELLS else SAMPLE_SPELLS.reversed()).map { UiKit.RadialItem(Icons.get(it), "", -1) },
            pointed(look, 8))
        hub(c, 108f, 54f) {
            glyphOrWord(c, if (left) "rev_lshoulder" else "rev_rshoulder", Glyphs.name(if (left) "lshoulder" else "rshoulder"), 99f, 54f, 19f)
            glyphOrWord(c, if (left) "rev_ltrigger" else "rev_rtrigger", Glyphs.name(if (left) "ltrigger" else "rtrigger"), 117f, 54f, 19f)
        }
        openPick(c, look)
    }

    /** the band's lit place: every 0.8 s the next one (the open band only; a tile's still frame has none) */
    private fun pointed(look: Look, n: Int): Int = if (look.t <= 0f) -1 else ((look.t / 0.8f).toInt() % n)

    /** a wheel at rest: the app's own (radialPreview), its sketch without the kit */
    private fun radial(c: Canvas, cx: Float, cy: Float, d: Float, items: List<UiKit.RadialItem?>, pointed: Int) {
        if (!runCatching { UiKit.radialPreview(c, cx, cy, d, items, pointed); true }.getOrDefault(false))
            sketchWheel(c, cx, cy, d * 0.46f, pointed, emptyList(), items)
    }

    /** our own wheel: eight bronze slices round a dark hub, each a coloured square (or its icon) */
    private fun sketchWheel(c: Canvas, cx: Float, cy: Float, rad: Float, pick: Int, ids: List<Int>, items: List<UiKit.RadialItem?> = emptyList()) {
        pF.color = Color.argb(230, 24, 20, 16); c.drawCircle(cx, cy, rad, pF)
        pS.color = Color.rgb(150, 126, 90); pS.strokeWidth = 2f; c.drawCircle(cx, cy, rad, pS)
        for (i in 0 until 8) {
            val a = (i * 45f - 90f) * (PI / 180f).toFloat()
            val sx = cx + cos(a) * rad * 0.68f; val sy = cy + sin(a) * rad * 0.68f
            val lit = i == pick
            pF.color = if (lit) Color.rgb(120, 90, 30) else Color.rgb(46, 36, 22); c.drawCircle(sx, sy, rad * 0.24f, pF)
            pS.color = if (lit) Theme.gold else Color.rgb(150, 126, 90); pS.strokeWidth = if (lit) 2f else 1f; c.drawCircle(sx, sy, rad * 0.24f, pS)
            val q = rad * 0.13f
            val bmp = items.getOrNull(i)?.icon ?: ids.getOrNull(i)?.let { Icons.get(it) }
            if (bmp != null) c.drawBitmap(bmp, null, RectF(sx - q, sy - q, sx + q, sy + q), pB)
            else { pF.color = SPELLS[i % SPELLS.size]; c.drawRect(sx - q, sy - q, sx + q, sy + q, pF) }
        }
    }

    /** the hub's disc and what opens the wheel on it */
    private inline fun hub(c: Canvas, cx: Float, cy: Float, inside: () -> Unit) {
        pF.color = Color.argb(235, 12, 12, 14); c.drawCircle(cx, cy, 17f, pF)
        pS.color = Theme.gold; pS.strokeWidth = 1.4f; c.drawCircle(cx, cy, 17f, pS)
        inside()
    }

    /** the two settings beside the wheel, as signs: Tap (one press) or Hold (a long one) on the left, A or the stick on the right */
    private fun openPick(c: Canvas, look: Look) {
        // Open: a short or a long press bar, under a finger dot
        val x = 22f
        pF.color = Color.argb(200, 10, 10, 12); c.drawRoundRect(RectF(6f, 42f, 46f, 66f), 6f, 6f, pF)
        pF.color = Theme.gold
        if (look.tap) c.drawCircle(x, 54f, 4.5f, pF)
        else c.drawRoundRect(RectF(x - 10f, 50f, x + 14f, 58f), 4f, 4f, pF)
        // Pick: the A glyph, or the stick letting go
        if (look.pickA) glyph(c, "a", 190f, 54f, 26f)
        else glyphOrWord(c, "gen_rstick", "Stick", 190f, 58f, 46f)          // the stick art is small in its 64 px square
    }

    /** a small two-screen sign: which screen's wheel Start opens over a game window (top = the game's) */
    private fun screens(c: Canvas, top: Boolean) {
        val l = 176f; val w = 30f; val h = 14f
        val a = RectF(l, 8f, l + w, 8f + h); val b = RectF(l + 2f, 26f, l + w - 2f, 26f + h)
        pS.color = Color.rgb(150, 126, 90); pS.strokeWidth = 1.2f
        c.drawRoundRect(a, 2f, 2f, pS); c.drawRoundRect(b, 2f, 2f, pS)
        pF.color = Theme.gold; c.drawRoundRect(if (top) RectF(a.left + 4f, a.top + 3f, a.right - 4f, a.bottom - 3f)
            else RectF(b.left + 4f, b.top + 3f, b.right - 4f, b.bottom - 3f), 1f, 1f, pF)
    }

    // ---------------------------------------------------------------- Combat helpers

    private val CLASS_RGB = intArrayOf(Color.rgb(196, 30, 58), Color.rgb(240, 240, 240), Color.rgb(63, 199, 235), Color.rgb(170, 211, 114))

    /** a party plate: the member's class stripe, the health fill (0 = dead: grey with a cross) */
    private fun plate(c: Canvas, b: RectF, hp: Float, cls: Int, edge: Int? = null) {
        pF.color = Color.argb(230, 16, 14, 12); c.drawRoundRect(b, 3f, 3f, pF)
        pF.color = cls; c.drawRect(b.left + 2f, b.top + 2f, b.left + 6f, b.bottom - 2f, pF)
        val bar = RectF(b.left + 9f, b.top + 3f, b.right - 3f, b.bottom - 3f)
        pF.color = Color.rgb(40, 34, 28); c.drawRect(bar, pF)
        if (hp > 0f) {
            pF.shader = LinearGradient(0f, bar.top, 0f, bar.bottom, Color.rgb(120, 220, 110), Color.rgb(40, 150, 50), Shader.TileMode.CLAMP)
            c.drawRect(bar.left, bar.top, bar.left + bar.width() * hp, bar.bottom, pF); pF.shader = null
        } else {
            pS.color = Color.rgb(170, 170, 170); pS.strokeWidth = 1.6f
            val cx = bar.centerX(); val cy = bar.centerY(); val d = minOf(5f, bar.height() / 2f - 1f)
            c.drawLine(cx - d, cy - d, cx + d, cy + d, pS); c.drawLine(cx - d, cy + d, cx + d, cy - d, pS)
        }
        pS.color = edge ?: Color.argb(220, 0, 0, 0); pS.strokeWidth = if (edge != null) 2f else 1f; c.drawRoundRect(b, 3f, 3f, pS)
    }

    /** Party mode (SettingsPreview's drawing re-framed 2:1): the party strip under the HUD (On), Select beside it; Off = no strip */
    private fun partyMode(c: Canvas, look: Look) {
        // the HUD bar under your character
        figure(c, 136f, 40f, 30f, Color.rgb(22, 24, 30))
        val hp = RectF(96f, 44f, 176f, 52f)
        pF.color = Color.argb(230, 8, 8, 8); c.drawRect(hp, pF)
        pF.color = Color.rgb(40, 200, 60); c.drawRect(hp.left, hp.top, hp.left + hp.width() * 0.8f, hp.bottom, pF)
        pS.color = Color.rgb(170, 160, 140); pS.strokeWidth = 0.9f; c.drawRect(hp, pS)
        // the strip: four members side by side
        if (look.on) for (i in 0 until 4) plate(c, RectF(70f + i * 34f, 62f, 70f + i * 34f + 31f, 74f), 1f - i * 0.18f, CLASS_RGB[i])
        else { pS.color = Color.argb(90, 200, 200, 200); pS.strokeWidth = 1f
            pS.pathEffect = android.graphics.DashPathEffect(floatArrayOf(3f, 3f), 0f); c.drawRoundRect(RectF(70f, 62f, 205f, 74f), 3f, 3f, pS); pS.pathEffect = null }
        glyphOrWord(c, "rev_minus", "Select", 34f, 60f, 34f, select = true)
    }

    /** Smart Res: four plates, one member dead; On = its soft green edge and your res's icon on it */
    private fun smartRes(c: Canvas, look: Look) {
        for (i in 0 until 4) {
            val dead = i == 2
            val b = RectF(24f, 12f + i * 22f, 132f, 12f + i * 22f + 18f)
            plate(c, b, if (dead) 0f else 1f - i * 0.12f, CLASS_RGB[i], if (dead && look.on) Color.rgb(90, 200, 110) else null)
        }
        // the corpse marker and the res
        UiKit.tex("mm_corpse")?.let { c.drawBitmap(it, null, RectF(142f, 52f, 160f, 70f), pB) }
        icon(c, ICON_RES, 166f, 38f, 38f, Color.rgb(240, 220, 120), dim = !look.on)
        if (look.on) { pS.color = Theme.gold; pS.strokeWidth = 1.6f; c.drawLine(140f, 61f, 134f, 58f, pS) }
    }

    /** Start attack (on a hostile target): the kit's crossed swords over the enemy's red plate */
    private fun startAttack(c: Canvas, look: Look) {
        val np = RectF(56f, 64f, 160f, 80f)
        pF.color = Color.rgb(40, 20, 16); c.drawRoundRect(np, 2f, 2f, pF)
        pF.color = Color.rgb(200, 40, 30); c.drawRect(np.left + 2f, np.top + 2f, np.left + 2f + (np.width() - 4f) * 0.7f, np.bottom - 2f, pF)
        pS.color = Color.rgb(176, 150, 100); pS.strokeWidth = 1f; c.drawRoundRect(np, 2f, 2f, pS)
        // the target's ring under the plate
        pS.color = Color.rgb(230, 60, 40); pS.strokeWidth = 1.6f; c.drawOval(RectF(78f, 86f, 138f, 100f), pS)
        swords(c, 108f, 38f, 30f, look.on)
    }

    /** Attack on cast: a harmful spell's icon, the arrow, then the swords lighting (On); the band runs it */
    private fun attackCast(c: Canvas, look: Look) {
        val k = look.t % 2.4f
        val lit = look.on && (look.t <= 0f || k in 0.4f..2.1f)
        val press = look.t > 0f && k < 0.25f
        val s = if (press) 44f else 40f
        icon(c, ICON_HARM, 52f - s / 2f, 54f - s / 2f, s, Color.rgb(90, 140, 220), dim = false)
        pS.color = if (lit) Theme.gold else Color.rgb(120, 110, 96); pS.strokeWidth = 2.4f
        c.drawLine(84f, 54f, 120f, 54f, pS); c.drawLine(120f, 54f, 112f, 47f, pS); c.drawLine(120f, 54f, 112f, 61f, pS)
        swords(c, 160f, 54f, 30f, lit)
    }

    /** Combat Auto Shot: the shot's icon with the swing timer's arc round it (Off: dark), the third choice's leaf for Smart */
    private fun autoShot(c: Canvas, look: Look) {
        val cx = 108f; val cy = 54f; val s = 46f
        icon(c, ICON_SHOT, cx - s / 2f, cy - s / 2f, s, Color.rgb(150, 120, 70), dim = look.shot == 0)
        val f = if (look.t > 0f) (look.t % 2f) / 2f else 0.68f
        val ring = RectF(cx - 33f, cy - 33f, cx + 33f, cy + 33f)
        pS.color = Color.argb(150, 10, 10, 10); pS.strokeWidth = 5f; c.drawOval(ring, pS)
        if (look.shot > 0) { pS.color = Theme.gold; pS.strokeWidth = 4f; c.drawArc(ring, -90f, 360f * f, false, pS) }
        // the enemy far off at range, the arrow on its way
        pF.color = Color.rgb(150, 50, 40); c.drawCircle(186f, 54f, 10f, pF)
        if (look.shot > 0) { pS.color = Theme.gold; pS.strokeWidth = 1.8f; c.drawLine(148f, 54f, 170f, 54f, pS)
            c.drawLine(170f, 54f, 165f, 50f, pS); c.drawLine(170f, 54f, 165f, 58f, pS) }
        if (look.shot == 2) {                                                  // Smart: the leaf of "off for an attack you start"
            pS.color = Theme.gold; pS.strokeWidth = 1.6f
            val lx = 30f; val ly = 54f
            c.drawPath(Path().apply { moveTo(lx - 9f, ly + 7f); cubicTo(lx - 9f, ly - 4f, lx - 1f, ly - 7f, lx + 9f, ly - 7f)
                cubicTo(lx + 9f, ly + 2f, lx + 3f, ly + 7f, lx - 9f, ly + 7f); close() }, pS)
        }
    }

    /** the crossed swords: the kit's (combat_swords), dark while off; our drawing without it */
    private fun swords(c: Canvas, cx: Float, cy: Float, s: Float, lit: Boolean) {
        val sw = UiKit.tex("combat_swords")
        if (sw != null) {
            pB.alpha = if (lit) 255 else 90
            c.drawBitmap(sw, null, RectF(cx - s, cy - s, cx + s, cy + s), pB)
            pB.alpha = 255
            return
        }
        val col = if (lit) Theme.gold else Theme.bronzeDim
        val q = s * 0.62f
        pS.color = col; pS.strokeWidth = 4f
        c.drawLine(cx - q, cy + q, cx + q, cy - q, pS); c.drawLine(cx - q, cy - q, cx + q, cy + q, pS)
        pS.strokeWidth = 3f
        c.drawLine(cx - q * 0.7f, cy + q * 0.3f, cx - q * 0.3f, cy + q * 0.7f, pS)
        c.drawLine(cx + q * 0.7f, cy + q * 0.3f, cx + q * 0.3f, cy + q * 0.7f, pS)
    }

    // ---------------------------------------------------------------- pieces

    /** the Start button's glyph in the chosen set (Nintendo's +; the generic Start for the others) */
    private val START get() = if (CastSettings.buttonLayout == CastSettings.ButtonLayout.NINTENDO) "rev_plus" else "gen_forward"

    /** a face button [action] ("a" "b" "x" "y") in the chosen glyph set (UiKit.pad), else a dark disc with its letter */
    fun glyph(c: Canvas, action: String, cx: Float, cy: Float, size: Float) = glyphOrWord(c, "rev_$action", Glyphs.name(action), cx, cy, size)

    /** the pad glyph [name] (translated to the chosen set), else [word] on a dark disc ([select]: Select's set name) */
    private fun glyphOrWord(c: Canvas, name: String, word: String, cx: Float, cy: Float, size: Float, select: Boolean = false) {
        val n = if (select) when (CastSettings.buttonLayout) {
            CastSettings.ButtonLayout.XBOX -> "ltr_view"; CastSettings.ButtonLayout.PLAYSTATION -> "shp_share"; else -> "rev_minus" } else name
        val key = Glyphs.upDown(n)?.first ?: Glyphs.plainStick(n) ?: Glyphs.translate(n, CastSettings.buttonLayout)
        if (UiKit.tex("pad_$key") != null || UiKit.tex("pad_$n") != null) { UiKit.pad(c, n, cx, cy, size); return }
        val r = size * 0.42f
        pF.color = Color.rgb(28, 28, 30); c.drawCircle(cx, cy, r, pF)
        pS.color = Color.rgb(200, 200, 200); pS.strokeWidth = 1.2f; c.drawCircle(cx, cy, r, pS)
        val w = if (select) Glyphs.name("select") else word
        val p = UiKit.bodyPaint(if (w.length > 2) size * 0.24f else size * 0.46f, Color.WHITE, shadow = false)
        p.textAlign = Paint.Align.CENTER
        c.drawText(w, cx, cy - (p.ascent() + p.descent()) / 2f, p)
    }

    /** an icons.pak icon [id] at ([x], [y]) [s] square, in the game's slot when the kit has it; a [col] square without the pack */
    private fun icon(c: Canvas, id: Int, x: Float, y: Float, s: Float, col: Int, dim: Boolean) {
        val bmp = Icons.get(id)
        if (bmp != null) { slotted(c, bmp, x, y, s, dim); return }
        pF.shader = LinearGradient(0f, y, 0f, y + s, lighter(col), darker(col), Shader.TileMode.CLAMP)
        if (dim) pF.alpha = 110
        c.drawRoundRect(RectF(x, y, x + s, y + s), 3f, 3f, pF); pF.shader = null; pF.alpha = 255
        pS.color = Color.argb(220, 0, 0, 0); pS.strokeWidth = 1f; c.drawRoundRect(RectF(x, y, x + s, y + s), 3f, 3f, pS)
    }

    private fun slot(c: Canvas, id: Int, x: Float, y: Float, s: Float, dim: Boolean) =
        icon(c, id, x, y, s, SPELLS[Math.floorMod(id, SPELLS.size)], dim)

    private fun slotted(c: Canvas, bmp: Bitmap, x: Float, y: Float, s: Float, dim: Boolean) {
        if (UiKit.tex("slot") != null) { UiKit.slot(c, bmp, x, y, s, dim = dim); return }
        if (dim) { val p = Paint(pB).apply { alpha = 110 }; c.drawBitmap(bmp, null, RectF(x, y, x + s, y + s), p) }
        else c.drawBitmap(bmp, null, RectF(x, y, x + s, y + s), pB)
        pS.color = Color.argb(220, 0, 0, 0); pS.strokeWidth = 1f; c.drawRect(x, y, x + s, y + s, pS)
    }

    /** a character from behind: head, shoulders, body, legs; [foot] where it stands, [h] tall (TilePictures' figure) */
    private fun figure(c: Canvas, x: Float, foot: Float, h: Float, col: Int) {
        val head = h * 0.13f
        pF.color = col
        c.drawCircle(x, foot - h + head, head, pF)
        path.reset()
        path.moveTo(x - h * 0.22f, foot - h + head * 2.3f); path.lineTo(x + h * 0.22f, foot - h + head * 2.3f)
        path.lineTo(x + h * 0.16f, foot - h * 0.42f); path.lineTo(x + h * 0.13f, foot); path.lineTo(x + h * 0.03f, foot)
        path.lineTo(x, foot - h * 0.3f); path.lineTo(x - h * 0.03f, foot); path.lineTo(x - h * 0.13f, foot)
        path.lineTo(x - h * 0.16f, foot - h * 0.42f); path.close()
        c.drawPath(path, pF)
    }

    private val SPELLS = intArrayOf(Color.rgb(150, 100, 40), Color.rgb(50, 95, 160), Color.rgb(150, 45, 40), Color.rgb(60, 130, 60),
        Color.rgb(120, 70, 150), Color.rgb(170, 140, 50), Color.rgb(40, 140, 150), Color.rgb(160, 90, 110))
    private fun lighter(c: Int) = Color.rgb((Color.red(c) + 255) / 2, (Color.green(c) + 255) / 2, (Color.blue(c) + 255) / 2)
    private fun darker(c: Int) = Color.rgb(Color.red(c) / 2, Color.green(c) / 2, Color.blue(c) / 2)
}
