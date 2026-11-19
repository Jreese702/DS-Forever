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

    /** the well's ground: Top screen's world (TilePictures.world, copied: TilePictures is not this area's file) - a night sky over
     *  dark ground, the edges darker. Fixed fills on purpose: the well stands for the game's world, not the app's theme */
    private fun ground(c: Canvas, w: Float) {
        pF.shader = LinearGradient(0f, 0f, 0f, H, intArrayOf(Color.rgb(36, 50, 62), Color.rgb(30, 40, 34), Color.rgb(20, 26, 18)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, H, pF)
        pF.shader = RadialGradient(w / 2f, H * 0.55f, w * 0.62f, intArrayOf(Color.argb(0, 0, 0, 0), Color.argb(120, 0, 0, 0)),
            floatArrayOf(0.55f, 1f), Shader.TileMode.CLAMP)
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

    /** B: pet recall (was Smart pet / B: pet follow; 2026-11-18 the attack half gone) - the B glyph; your pet (its icon) at an enemy (Off) or walking back after you (On, the trail behind it) */
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

    /** Smart Y: the Y glyph on the left, then the list's first five entries (food, water, buffs), the ones that cannot act dimmed. The
     *  wide tile: one row; the band's 2:1 picture: the same five, large, in two rows (three, then two), read top first */
    private fun smartY(c: Canvas, w: Float, look: Look) {
        val wide = w > W
        val ids = look.smartIcons.ifEmpty { SAMPLE_SMART }.take(5)
        val lit = if (look.on) Theme.gold else Theme.bronzeDim
        if (wide) {
            glyph(c, "y", 32f, 54f, 40f)
            pS.color = lit; pS.strokeWidth = 2.2f
            c.drawLine(56f, 54f, 70f, 54f, pS); c.drawLine(70f, 54f, 64f, 49f, pS); c.drawLine(70f, 54f, 64f, 59f, pS)
            val s = 50f; val gap = 26f; val x0 = 86f
            for ((i, id) in ids.withIndex()) {
                val x = x0 + i * (s + gap)
                if (i > 0) { pF.color = lit; c.drawRect(x - gap + 3f, 53f, x - 3f, 56f, pF) }
                slot(c, id, x, 54f - s / 2f, s, look.smartDim.getOrElse(i) { false } || !look.on)
            }
            return
        }
        glyph(c, "y", 26f, 54f, 38f)
        pS.color = lit; pS.strokeWidth = 2.2f
        c.drawLine(48f, 54f, 60f, 54f, pS); c.drawLine(60f, 54f, 55f, 49f, pS); c.drawLine(60f, 54f, 55f, 59f, pS)
        val s = 42f; val gap = 7f; val x0 = 68f
        for ((i, id) in ids.withIndex()) {
            val x = x0 + (i % 3) * (s + gap); val y = if (i < 3) 8f else 58f
            slot(c, id, x, y, s, look.smartDim.getOrElse(i) { false } || !look.on)
        }
    }

    // ---------------------------------------------------------------- Wheels

    /** the Pause menu, COMPOSED to the well (the whole wheel, never a cropped rim): its ring the size of the other wheels, the game
     *  menu's own slices (HomePauseModel's centre page, kit order) as the kit's slice pictures - large, no labels - or, without the kit,
     *  our drawn menu signs (bag, book, chat...: never spells, so it never reads as the Spell wheel); the Start glyph at the hub;
     *  Game's wheel = its hub lit gold */
    private fun pauseWheel(c: Canvas, look: Look) {
        val cx = 108f; val cy = 54f; val rim = 51f
        pF.shader = RadialGradient(cx, cy, rim, intArrayOf(Color.rgb(44, 36, 26), Color.rgb(16, 13, 10)), floatArrayOf(0.3f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, rim, pF); pF.shader = null
        val slices = runCatching { HomePauseModel.Flow().slices() }.getOrDefault(emptyList())
        val pick = pointed(look, 8)
        // the lit slice (the band's clock): a gold wedge
        if (pick >= 0) {
            pF.color = Color.argb(90, 255, 200, 60)
            c.drawArc(RectF(cx - rim, cy - rim, cx + rim, cy + rim), pick * 45f - 90f - 22.5f, 45f, true, pF)
        }
        // the slices' seams, then the rim
        pS.color = Theme.bronzeDim; pS.strokeWidth = 1f
        for (i in 0 until 8) {
            val a = ((i * 45f - 90f + 22.5f) * PI / 180f).toFloat()
            c.drawLine(cx + cos(a) * 21f, cy + sin(a) * 21f, cx + cos(a) * (rim - 2f), cy + sin(a) * (rim - 2f), pS)
        }
        pS.color = Theme.bronze; pS.strokeWidth = 2.4f; c.drawCircle(cx, cy, rim, pS)
        pS.color = Color.argb(140, 0, 0, 0); pS.strokeWidth = 1f; c.drawCircle(cx, cy, rim - 2.4f, pS)
        for (i in 0 until 8) {
            val sl = slices.getOrNull(i) ?: continue
            val a = ((i * 45f - 90f) * PI / 180f).toFloat()
            val sx = cx + cos(a) * 36f; val sy = cy + sin(a) * 36f
            val name = pauseArt(sl.key)
            val art = (if (!sl.enabled) UiKit.tex(name + "_off") else null) ?: UiKit.tex(name)
            val q = 11.5f
            if (art != null) {
                pB.alpha = if (sl.enabled) 255 else 120
                c.drawBitmap(art, null, RectF(sx - q, sy - q, sx + q, sy + q), pB); pB.alpha = 255
            } else menuSign(c, sl.key, sx, sy, q, sl.enabled)
        }
        hub(c, cx, cy, gold = look.gameWheel) { glyphOrWord(c, START, "Start", cx, cy, 26f) }
    }

    /** the pause slice's kit picture (the names of UiKit.pauseIcon, which is UiKit's own and private) */
    private fun pauseArt(key: String) = "ricon_" + when (key) {
        "lfd" -> "groupfinder"; "store" -> "shop"; "map" -> "quests"; "menu" -> "gamemenu"; "time" -> "clock"
        PolestarSkip.KEY_SKIP, PolestarSkip.KEY_PREV -> "quests"
        else -> key
    }

    /** a menu slice's sign without the kit: a bag, a book, a star, a speech bubble, a figure, a hammer, the quest "!", else a gear */
    private fun menuSign(c: Canvas, key: String, x: Float, y: Float, q: Float, enabled: Boolean) {
        val ink = if (enabled) Color.rgb(226, 200, 140) else Color.rgb(110, 100, 80)
        pF.color = ink; pS.color = ink; pS.strokeWidth = 1.8f
        when (key) {
            "bags" -> {
                c.drawRoundRect(RectF(x - q * 0.8f, y - q * 0.45f, x + q * 0.8f, y + q * 0.85f), 3f, 3f, pF)
                c.drawArc(RectF(x - q * 0.45f, y - q * 0.95f, x + q * 0.45f, y), 180f, 180f, false, pS)
            }
            "spellbook" -> {
                c.drawRect(x - q * 0.75f, y - q * 0.85f, x + q * 0.75f, y + q * 0.85f, pF)
                pS.color = Color.rgb(40, 30, 20); c.drawLine(x - q * 0.45f, y - q * 0.85f, x - q * 0.45f, y + q * 0.85f, pS)
            }
            "talents" -> {
                path.reset()
                for (k in 0 until 10) {
                    val r = if (k % 2 == 0) q * 0.95f else q * 0.42f
                    val a = ((k * 36f - 90f) * PI / 180f).toFloat()
                    if (k == 0) path.moveTo(x + cos(a) * r, y + sin(a) * r) else path.lineTo(x + cos(a) * r, y + sin(a) * r)
                }
                path.close(); c.drawPath(path, pF)
            }
            "chat" -> {
                c.drawRoundRect(RectF(x - q * 0.9f, y - q * 0.7f, x + q * 0.9f, y + q * 0.45f), q * 0.4f, q * 0.4f, pF)
                path.reset(); path.moveTo(x - q * 0.4f, y + q * 0.3f); path.lineTo(x - q * 0.6f, y + q * 0.9f); path.lineTo(x + q * 0.05f, y + q * 0.3f)
                path.close(); c.drawPath(path, pF)
            }
            "character" -> figure(c, x, y + q * 0.95f, q * 1.9f, ink)
            "professions" -> {
                pS.strokeWidth = 2.6f; c.drawLine(x - q * 0.6f, y + q * 0.8f, x + q * 0.3f, y - q * 0.1f, pS)
                c.drawRoundRect(RectF(x - q * 0.1f, y - q * 0.9f, x + q * 0.9f, y - q * 0.2f), 2f, 2f, pF)
            }
            "map" -> { c.drawRect(x - q * 0.16f, y - q * 0.9f, x + q * 0.16f, y + q * 0.35f, pF); c.drawCircle(x, y + q * 0.72f, q * 0.18f, pF) }
            else -> {
                pS.strokeWidth = 2.2f; c.drawCircle(x, y, q * 0.5f, pS)
                for (k in 0 until 6) {
                    val a = (k * 60f * PI / 180f).toFloat()
                    c.drawLine(x + cos(a) * q * 0.55f, y + sin(a) * q * 0.55f, x + cos(a) * q * 0.9f, y + sin(a) * q * 0.9f, pS)
                }
            }
        }
    }

    /** the item wheel ([items]) or the spell wheel: the app's own wheel at rest (UiKit.radialPreview) with the player's page 1, or
     *  sample icons; its opening button at the hub */
    private fun wheel(c: Canvas, look: Look, items: Boolean) {
        val list = look.items.takeIf { it.any { i -> i != null } } ?: (if (items) SAMPLE_ITEMS else SAMPLE_SPELLS)
            .map { UiKit.RadialItem(Icons.get(it), "", if (items) 3 else -1) }
        radial(c, 108f, 54f, 104f, list, pointed(look, 8))
        // the hub says what opens it, as large as the class wheels' ZL / ZR: the D-pad with its Up arm lit (the item wheel), the
        // spellbook (the spell wheel: its own bound button, no fixed glyph)
        hub(c, 108f, 54f, rad = 21f) {
            if (items) dpadUp(c, 108f, 54f, 15f)
            else UiKit.tex("ricon_spellbook")?.let { b -> c.drawBitmap(b, null, RectF(91f, 37f, 125f, 71f), pB) }
                ?: menuSign(c, "spellbook", 108f, 54f, 13f, true)
        }
    }

    /** a D-pad cross, its Up arm lit gold (the button that opens the item wheel) */
    private fun dpadUp(c: Canvas, cx: Float, cy: Float, a: Float) {
        val t = a * 0.36f
        pF.color = Color.rgb(205, 205, 210)
        c.drawRoundRect(cx - t, cy - a, cx + t, cy + a, 2f, 2f, pF)
        c.drawRoundRect(cx - a, cy - t, cx + a, cy + t, 2f, 2f, pF)
        pS.color = Color.argb(200, 0, 0, 0); pS.strokeWidth = 1f
        c.drawRoundRect(cx - t, cy - a, cx + t, cy + a, 2f, 2f, pS); c.drawRoundRect(cx - a, cy - t, cx + a, cy + t, 2f, 2f, pS)
        pF.color = Color.rgb(205, 205, 210); c.drawRect(cx - t + 1f, cy - t + 1f, cx + t - 1f, cy + t - 1f, pF)
        pF.color = Theme.gold; c.drawRoundRect(cx - t + 1f, cy - a + 1f, cx + t - 1f, cy - t, 2f, 2f, pF)
    }

    /** the left / right class radial: the character's own (PreviewStage) or a sample wheel; its trigger (LT / RT) alone at the hub */
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
            glyphOrWord(c, if (left) "rev_ltrigger" else "rev_rtrigger", Glyphs.name(if (left) "ltrigger" else "rtrigger"), 108f, 54f, 28f)
        }
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
        pS.color = Theme.bronze; pS.strokeWidth = 2f; c.drawCircle(cx, cy, rad, pS)
        for (i in 0 until 8) {
            val a = (i * 45f - 90f) * (PI / 180f).toFloat()
            val sx = cx + cos(a) * rad * 0.68f; val sy = cy + sin(a) * rad * 0.68f
            val lit = i == pick
            pF.color = if (lit) Color.rgb(120, 90, 30) else Color.rgb(46, 36, 22); c.drawCircle(sx, sy, rad * 0.24f, pF)
            pS.color = if (lit) Theme.gold else Theme.bronze; pS.strokeWidth = if (lit) 2f else 1f; c.drawCircle(sx, sy, rad * 0.24f, pS)
            val q = rad * 0.13f
            val bmp = items.getOrNull(i)?.icon ?: ids.getOrNull(i)?.let { Icons.get(it) }
            if (bmp != null) c.drawBitmap(bmp, null, RectF(sx - q, sy - q, sx + q, sy + q), pB)
            else { pF.color = SPELLS[i % SPELLS.size]; c.drawRect(sx - q, sy - q, sx + q, sy + q, pF) }
        }
    }

    /** the hub's disc and what opens the wheel on it; [gold] = lit (the game's wheel over its windows) */
    private inline fun hub(c: Canvas, cx: Float, cy: Float, gold: Boolean = false, rad: Float = 18f, inside: () -> Unit) {
        pF.color = if (gold) Theme.gold else Color.argb(235, 12, 12, 14); c.drawCircle(cx, cy, rad, pF)
        pS.color = if (gold) Theme.text else Theme.gold; pS.strokeWidth = 1.4f; c.drawCircle(cx, cy, rad, pS)
        inside()
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
        pS.color = if (lit) Theme.gold else Theme.bronzeDim; pS.strokeWidth = 2.4f
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
    private val START get() = Glyphs.start()

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
        val p = UiKit.bodyPaint(size * 0.46f, Color.WHITE, shadow = false)
        while (p.measureText(w) > r * 1.7f && p.textSize > 6f) p.textSize -= 0.5f        // a word ("Start", "Spells") fills the disc
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
