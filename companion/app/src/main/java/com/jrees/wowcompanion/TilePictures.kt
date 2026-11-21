package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader

/**
 * THE TOP SCREEN TILES' PICTURES (the user, 2026-11-16: "VISUAL overhaul of UI menus (picture beside each game-UI element option)"; the
 * design's pick "Kotlin drawings from pieces already on the phone, with sketches as the fallback. No kit rebuild"): each element of the
 * top screen drawn small, as the game shows it, on a dark stand-in of the game's world.
 *
 * What a picture is made of, in order of preference: the harvested kit and cast pieces (UiKit.tex / UiKit.castArt: the minimap arrow,
 * the tracker's header, the cast bar, the controller rings, the class icons...), our own add-on textures ([MediaArt]: the HUD rim, the
 * compass band, the world markers, the tint), plain live data (your class), fixed sample fills (a bar 78 % full). Where a piece is not on
 * the device our own drawing stands in, so a phone with no kit yet still gets a picture. No screenshot, no secret value.
 *
 * Every picture is drawn in a 216 x 108 space ([W] x [H], the tile's well) scaled into the box it is given, so the band's 438 x 220
 * picture is the same drawing, larger. The [Look] carries the choices that change how an element looks (the pet strip, the health text,
 * the target of target, the aggro tint, the Nintendo buttons): the picture follows the choice at once.
 */
object TilePictures {
    const val W = 216f
    const val H = 108f

    /** the choices a picture shows beyond the element itself; [cls] = your class (the party and player frames' icon), null = a sample */
    data class Look(val mode: UiMode = UiMode.ON, val pet: Boolean = true, val hpText: Int = 0, val tot: Boolean = false,
                    val aggro: Boolean = true, val cls: String? = null,
                    /** hub61 (the beautify design 7's two Look gaps): the HUD's width against its default (the resource bar's width line;
                     *  drawn 0.8..1.2), the compass following the camera (its band line), the Cooldown Manager's parts (bit 0 Essential,
                     *  1 Utility, 2 Buff icons, 3 Buff bars) */
                    val width: Float = 1f, val cam: Boolean = false, val cdm: Int = 0b1111)

    /** [id] has a picture of its own (the registry is GameUiTiles.PICTURED; the rest: the caller's glyph) */
    fun has(id: String): Boolean = id in GameUiTiles.PICTURED

    private val pF = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pS = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val pB = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    /** the picture is drawn at the band's size (sample numbers only there: in a tile they are 6 px noise) */
    private var big = false

    /** [id]'s picture into [r] (its own rounded clip; the canvas left as it was). false = no picture of its own (nothing drawn) */
    fun draw(c: Canvas, ctx: Context?, id: String, r: RectF, look: Look): Boolean {
        if (!has(id)) return false
        val save = c.save()
        val rad = 8f * r.width() / W
        path.reset(); path.addRoundRect(r, rad, rad, Path.Direction.CW)
        c.clipPath(path)
        val k = minOf(r.width() / W, r.height() / H)
        c.translate(r.centerX() - W * k / 2f, r.centerY() - H * k / 2f)
        c.scale(k, k)
        big = k >= 1.5f
        world(c)
        runCatching { element(c, ctx, id, look) }
        c.restoreToCount(save)
        return true
    }

    // ---------------------------------------------------------------- the world behind

    /** a dark stand-in of the game's world: a night sky over dark ground, the edges darker (fixed fills) */
    private fun world(c: Canvas) {
        // (opaque: a shader paints at the paint colour's alpha, and pF keeps the last element's colour - the world came out see-through
        // after a picture that ended on a translucent fill; found by theMetersPicturesKeepTheBadgeCornerClear, 11-17)
        pF.color = Color.BLACK
        pF.shader = LinearGradient(0f, 0f, 0f, H, intArrayOf(Color.rgb(36, 50, 62), Color.rgb(30, 40, 34), Color.rgb(20, 26, 18)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W, H, pF)
        pF.shader = RadialGradient(W / 2f, H * 0.55f, W * 0.62f, intArrayOf(Color.argb(0, 0, 0, 0), Color.argb(120, 0, 0, 0)),
            floatArrayOf(0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W, H, pF)
        pF.shader = null
    }

    private fun element(c: Canvas, ctx: Context?, id: String, look: Look) {
        when (id) {
            GameUi.HUD_ID -> resourceBar(c, ctx, look)
            GameUi.UFT_ID -> targetBar(c, ctx, look)
            GameUi.NP_ID -> nameplates(c, ctx, look)
            GameUi.NPF_ID -> friendlyPlates(c)
            GameUi.UFP_ID -> dsParty(c, ctx, look)
            GameUi.PARTY_ID -> gameParty(c, look)
            GameUiSection.COMPASS_ID -> compass(c, ctx, look.cam)
            GameUi.WM_ID -> worldMarkers(c, ctx)
            GameUiTiles.PS_ID -> polestar(c)
            "minimap" -> minimap(c, look)
            "tracker" -> tracker(c)
            "chat" -> chat(c)
            "dialogs" -> npcWindow(c)
            GameUiSection.LOOT_ID -> loot(c)
            GameUiSection.TIPS_ID -> tooltip(c)
            GameUi.CASTBAR_ID -> castBar(c)
            GameUi.PLAYER_ID -> unitFrame(c, look, target = false)
            GameUi.TARGET_ID -> unitFrame(c, look, target = true)
            "hud" -> controllerBars(c, look)
            HudSplit.BUFFS_ID -> buffs(c)
            HudSplit.XP_ID -> xpBar(c)
            GameUi.DMG_ID -> damageMeter(c, look)
            GameUi.CDM_ID -> cooldownManager(c, look)
            GameUiTiles.LAYOUT_ID -> layoutMini(c)
        }
    }

    // ---------------------------------------------------------------- the elements

    /** HUD mode's own bar under your character: health and power in our rim, a few buffs, the pet strip when it is on */
    private fun resourceBar(c: Canvas, ctx: Context?, look: Look) {
        figure(c, 108f, 40f, 34f, Color.rgb(22, 24, 30))
        for (i in 0..2) aura(c, 124f + i * 13f, 24f, 11f, listOf(Color.rgb(70, 130, 220), Color.rgb(230, 190, 60), Color.rgb(120, 200, 90))[i])
        // hub61: the width line's choice (the bar's own width against its default), drawn 0.8..1.2 round the centre
        val half = 68f * look.width.coerceIn(0.8f, 1.2f)
        val hp = RectF(106f - half, 47f, 106f + half, 60f)
        val pw = RectF(106f - half, 60f, 106f + half, 68f)
        rim(c, ctx, RectF(hp.left, hp.top, hp.right, pw.bottom))
        bar(c, hp, 0.78f, Color.rgb(40, 200, 60))
        bar(c, pw, 0.62f, Color.rgb(40, 100, 230))
        if (look.pet) {
            val pb = RectF(76f, 83f, 160f, 91f)
            rim(c, ctx, pb, small = true)
            bar(c, pb, 0.9f, Color.rgb(40, 200, 60))
            disc(c, 64f, 87f, 8.5f, Color.rgb(150, 110, 70))
        }
    }

    /** our target bar under the compass: its level box (an elite's gold dragon behind it, as Hub.lua's f.cls), the red bar in our rim,
     *  the name over it; the health text and the target of target as chosen; down in the world the targeted mob under the red arrow */
    private fun targetBar(c: Canvas, ctx: Context?, look: Look) {
        compassTicks(c, 6f, alpha = 110)
        val right = if (look.tot) 152f else 186f
        val bar = RectF(52f, 26f, right, 38f)
        val lvl = RectF(34f, 24f, 48f, 40f)
        rim(c, ctx, bar)
        bar(c, bar, 0.7f, Color.rgb(200, 30, 24))
        // the elite's dragon behind the level box (Hub's f.cls: 48 UI px round a box the bar's height, a little left and up)
        dragon(c, Dragon.GOLD, lvl.centerX() - 2f, lvl.centerY() - 1f, 30f)
        pF.color = Color.rgb(12, 10, 8); c.drawRect(lvl, pF)
        pS.color = Color.rgb(120, 104, 80); pS.strokeWidth = 1f; c.drawRect(lvl, pS)
        text(c, "11", lvl.centerX(), lvl.bottom - 3.5f, 9f, Color.rgb(255, 210, 0), center = true, number = true)
        text(c, "Hogger", bar.centerX(), 22f, 7.5f, Color.rgb(255, 210, 0), center = true)
        when (look.hpText) {
            1 -> text(c, "70%", bar.centerX(), bar.bottom - 2.5f, 8.5f, Color.WHITE, center = true, number = true)
            2 -> text(c, "980 / 1400", bar.centerX(), bar.bottom - 2.5f, 8.5f, Color.WHITE, center = true, number = true)
        }
        if (look.tot) {
            val t = RectF(162f, 30f, 200f, 37f)
            rim(c, ctx, t, small = true)
            bar(c, t, 0.85f, Color.rgb(40, 200, 60))
            text(c, "You", t.centerX(), 27f, 6.5f, Color.rgb(200, 200, 200), center = true)
        }
        figure(c, 112f, 100f, 30f, Color.rgb(70, 30, 26))
        MediaArt.get(ctx, "target_arrow")?.let { a -> c.drawBitmap(a, null, RectF(105f, 54f, 119f, 68f), pB) }
            ?: run { pF.color = Color.rgb(210, 30, 24); tri(c, 105f, 56f, 119f, 56f, 112f, 66f) }
    }

    /** hostile mobs in the world with their red plates (a rare's silver dragon and an elite's gold one above theirs, as Trail.lua's plate
     *  marks); the aggro tint round the plates when it is on */
    private fun nameplates(c: Canvas, ctx: Context?, look: Look) {
        val mobs = listOf(Triple(44f, 96f, 30f), Triple(108f, 84f, 22f), Triple(172f, 100f, 34f))
        val tints = listOf(Color.rgb(255, 210, 40), Color.rgb(255, 140, 30), Color.rgb(230, 40, 30))
        for ((i, m) in mobs.withIndex()) {
            figure(c, m.first, m.second, m.third, Color.rgb(64, 30, 26))
            val w = 30f + m.third
            val top = m.second - m.third - 14f
            val plate = RectF(m.first - w / 2f, top, m.first + w / 2f, top + 7f)
            if (look.aggro) {
                val tint = MediaArt.get(ctx, "tint_glow")
                val g = RectF(plate.left - 5f, plate.top - 4f, plate.right + 5f, plate.bottom + 4f)
                if (tint != null) { pB.colorFilter = PorterDuffColorFilter(tints[i], PorterDuff.Mode.MULTIPLY); c.drawBitmap(tint, null, g, pB); pB.colorFilter = null }
                else { pS.color = tints[i]; pS.strokeWidth = 1.6f; c.drawRoundRect(g, 3f, 3f, pS) }
            }
            bar(c, plate, listOf(1f, 0.8f, 0.55f)[i], Color.rgb(200, 30, 24))
            text(c, listOf("Kobold", "Murloc", "Defias Thug")[i], m.first, top - 2.5f, 7.5f, Color.rgb(255, 230, 220), center = true)
            // Trail.lua's mark: its frame's bottom on the plate's top (above the name), the dragon over the plate's middle
            listOf(null, Dragon.SILVER, Dragon.GOLD)[i]?.let { d -> dragon(c, d, m.first, top - 15.5f, 13f) }
        }
    }

    /** a friend in the world, targeted (the game's green reticle at the feet), with the friendly plate over the head */
    private fun friendlyPlates(c: Canvas) {
        figure(c, 56f, 96f, 30f, Color.rgb(26, 34, 52))
        figure(c, 132f, 98f, 50f, Color.rgb(26, 34, 52))
        val reticle = UiKit.castArt("badge_friendly")
        if (reticle != null) c.drawBitmap(reticle, null, RectF(114f, 90f, 150f, 106f), pB)
        else { pS.color = Color.rgb(80, 220, 80); pS.strokeWidth = 1.5f; c.drawOval(RectF(114f, 93f, 150f, 104f), pS) }
        val plate = RectF(100f, 32f, 164f, 40f)
        bar(c, plate, 1f, Color.rgb(40, 200, 60))
        text(c, "Elwynn Guard", 132f, 28f, 8.5f, Color.rgb(120, 230, 120), center = true)
    }

    /** our party frames: a column on the left, a class icon and a bar in our rim for each member; one in the Party mode pick */
    private fun dsParty(c: Canvas, ctx: Context?, look: Look) {
        val classes = listOf(look.cls ?: "PALADIN", "PRIEST", "WARRIOR", "MAGE")
        val fills = listOf(0.92f, 0.64f, 0.38f, 0.8f)
        for (i in 0..3) {
            val y = 14f + i * 22f
            classIcon(c, classes[i], RectF(10f, y, 24f, y + 14f))
            val b = RectF(28f, y + 2f, 96f, y + 10f)
            rim(c, ctx, b, small = true)
            bar(c, b, fills[i], if (fills[i] < 0.4f) Color.rgb(220, 160, 30) else Color.rgb(40, 200, 60))
            pF.color = Color.rgb(40, 100, 230); c.drawRect(b.left, b.bottom + 1f, b.left + (b.width()) * 0.7f, b.bottom + 3f, pF)
            if (i == 1) { pS.color = Theme.gold; pS.strokeWidth = 1.6f; c.drawRoundRect(RectF(7f, y - 3f, 100f, y + 17f), 3f, 3f, pS) }
        }
        figure(c, 150f, 96f, 32f, Color.rgb(22, 24, 30))
    }

    /** the game's own party frames: round portraits in their gold rings, the bars beside them */
    private fun gameParty(c: Canvas, look: Look) {
        val classes = listOf("PRIEST", "WARRIOR", look.cls ?: "MAGE")
        for (i in 0..2) {
            val cy = 22f + i * 30f
            portrait(c, 22f, cy, 11f) { r -> classIcon(c, classes[i], r) }
            pF.color = Color.argb(200, 0, 0, 0); c.drawRect(36f, cy - 9f, 100f, cy + 7f, pF)
            bar(c, RectF(37f, cy - 3f, 99f, cy + 2f), listOf(0.9f, 0.5f, 0.75f)[i], Color.rgb(40, 200, 60), edge = false)
            bar(c, RectF(37f, cy + 2.5f, 99f, cy + 6f), 0.7f, Color.rgb(40, 100, 230), edge = false)
            pF.color = Color.rgb(220, 200, 160); c.drawRect(38f, cy - 8f, 70f, cy - 5f, pF)
        }
        figure(c, 156f, 96f, 32f, Color.rgb(22, 24, 30))
    }

    /** our compass along the top: its dark band, the headings and ticks, the focused quest's disc with its distance; [cam] = it follows
     *  the camera (hub61: the strip turned 25 degrees, N off the centre; else N centred, your character's heading) */
    private fun compass(c: Canvas, ctx: Context?, cam: Boolean = false) {
        MediaArt.get(ctx, "compass_band")?.let { c.drawBitmap(it, null, RectF(-10f, 4f, W + 10f, 44f), pB) }
            ?: run { pF.color = Color.argb(140, 0, 0, 0); c.drawRect(0f, 12f, W, 36f, pF) }
        compassTicks(c, 18f, alpha = 255, big = true, shift = if (cam) -25f * 50f / 45f else 0f)
        val disc = MediaArt.get(ctx, "quest_disc")
        val dr = RectF(136f, 13f, 150f, 27f)
        if (disc != null) c.drawBitmap(disc, null, dr, pB) else disc(c, dr.centerX(), dr.centerY(), 7f, Color.rgb(240, 180, 30))
        text(c, "!", dr.centerX(), dr.bottom - 3f, 10f, Color.rgb(60, 30, 0), center = true)
        text(c, "330 yd", dr.centerX(), 38f, 7f, Color.rgb(230, 230, 230), center = true, number = true)
        figure(c, 108f, 100f, 34f, Color.rgb(22, 24, 30))
    }

    /** the world markers: a beam over the next checkpoint (the diamond on top, the ring on the ground), the quest's pin further off */
    private fun worldMarkers(c: Canvas, ctx: Context?) {
        // the ground's far edge
        pF.color = Color.argb(60, 0, 0, 0); c.drawRect(0f, 64f, W, H, pF)
        marker(c, ctx, 142f, 88f, 1f, Color.rgb(120, 200, 255), "marker_diamond")
        marker(c, ctx, 70f, 70f, 0.6f, Color.rgb(255, 200, 40), "marker_pin")
        figure(c, 40f, 104f, 30f, Color.rgb(22, 24, 30))
    }

    private fun marker(c: Canvas, ctx: Context?, x: Float, groundY: Float, s: Float, col: Int, top: String) {
        val tint = PorterDuffColorFilter(col, PorterDuff.Mode.MULTIPLY)
        val ring = MediaArt.get(ctx, "marker_ring")
        val rr = RectF(x - 22f * s, groundY - 6f * s, x + 22f * s, groundY + 6f * s)
        if (ring != null) { pB.colorFilter = tint; c.drawBitmap(ring, null, rr, pB); pB.colorFilter = null }
        else { pS.color = col; pS.strokeWidth = 1.5f; c.drawOval(rr, pS) }
        val beam = MediaArt.get(ctx, "marker_beam")
        val br = RectF(x - 6f * s, groundY - 60f * s, x + 6f * s, groundY)
        if (beam != null) { pB.colorFilter = tint; c.drawBitmap(beam, null, br, pB); pB.colorFilter = null }
        else { pF.shader = LinearGradient(0f, br.top, 0f, br.bottom, Color.argb(0, 255, 255, 255), col, Shader.TileMode.CLAMP); c.drawRect(x - 1.5f * s, br.top, x + 1.5f * s, br.bottom, pF); pF.shader = null }
        val icon = MediaArt.get(ctx, top)
        val ir = RectF(x - 10f * s, groundY - 82f * s, x + 10f * s, groundY - 62f * s)
        if (icon != null) { pB.colorFilter = tint; c.drawBitmap(icon, null, ir, pB); pB.colorFilter = null }
        else { pF.color = col; tri(c, ir.centerX(), ir.top, ir.right, ir.centerY(), ir.left, ir.centerY()); tri(c, ir.left, ir.centerY(), ir.right, ir.centerY(), ir.centerX(), ir.bottom) }
    }

    /** Polestar's own frame on the top screen (our sketch: its art is the add-on's, not the kit's): the step card and its arrow */
    private fun polestar(c: Canvas) {
        val card = RectF(46f, 8f, 170f, 40f)
        pF.color = Color.argb(215, 22, 24, 44); c.drawRoundRect(card, 4f, 4f, pF)
        pS.color = Color.argb(200, 200, 205, 230); pS.strokeWidth = 1f; c.drawRoundRect(card, 4f, 4f, pS)
        pS.strokeWidth = 1.4f; c.drawCircle(60f, 24f, 8f, pS)
        star(c, 60f, 24f, 5.5f, Color.WHITE)
        text(c, "Kill 8 Kobold Vermin", 74f, 21f, 7f, Color.WHITE)
        pF.color = Color.argb(160, 200, 205, 230); c.drawRect(74f, 27f, 150f, 30f, pF); c.drawRect(74f, 33f, 128f, 36f, pF)
        // the silver arrow ahead of you
        pF.color = Color.rgb(225, 228, 236)
        path.reset(); path.moveTo(108f, 52f); path.lineTo(124f, 86f); path.lineTo(108f, 78f); path.lineTo(92f, 86f); path.close()
        c.drawPath(path, pF)
        pF.color = Color.rgb(150, 154, 168)
        path.reset(); path.moveTo(108f, 52f); path.lineTo(108f, 78f); path.lineTo(92f, 86f); path.close()
        c.drawPath(path, pF)
        figure(c, 108f, 106f, 18f, Color.rgb(22, 24, 30))
    }

    /** the game's minimap: a round map (a fixed sample of fields, a road and a lake) in its ring, your arrow, a few town icons, the
     *  zone band over it; with "While tracking" chosen, the herbs it shows */
    private fun minimap(c: Canvas, look: Look) {
        val cx = 112f; val cy = 58f; val r = 40f
        val save = c.save()
        path.reset(); path.addCircle(cx, cy, r, Path.Direction.CW); c.clipPath(path)
        pF.color = Color.rgb(78, 96, 46); c.drawCircle(cx, cy, r, pF)
        pF.color = Color.rgb(96, 112, 54); c.drawOval(RectF(cx - 40f, cy - 30f, cx + 6f, cy + 8f), pF)
        pF.color = Color.rgb(60, 84, 40); c.drawOval(RectF(cx + 4f, cy + 6f, cx + 46f, cy + 44f), pF)
        pF.color = Color.rgb(46, 92, 130); c.drawOval(RectF(cx + 10f, cy - 34f, cx + 40f, cy - 12f), pF)
        pS.color = Color.rgb(170, 140, 90); pS.strokeWidth = 4f
        path.reset(); path.moveTo(cx - 44f, cy + 20f); path.cubicTo(cx - 14f, cy + 4f, cx - 6f, cy + 14f, cx + 6f, cy - 6f)
        path.cubicTo(cx + 14f, cy - 18f, cx + 26f, cy - 4f, cx + 44f, cy - 10f); c.drawPath(path, pS)
        for (b in listOf(RectF(cx - 18f, cy - 4f, cx - 10f, cy + 4f), RectF(cx - 6f, cy + 10f, cx + 2f, cy + 17f))) { pF.color = Color.rgb(120, 70, 44); c.drawRect(b, pF) }
        if (look.mode == UiMode.TRACKING) for ((x, y) in listOf(cx - 24f to cy - 18f, cx + 22f to cy + 18f, cx - 8f to cy + 28f)) {
            pF.color = Color.rgb(250, 230, 70); c.drawCircle(x, y, 2.6f, pF)
        }
        for ((name, at) in listOf("mm_innkeeper" to (cx - 14f to cy - 12f), "mm_vendor" to (cx + 16f to cy + 4f), "mm_flightmaster" to (cx - 26f to cy + 12f))) {
            UiKit.tex(name)?.let { c.drawBitmap(it, null, RectF(at.first - 5f, at.second - 5f, at.first + 5f, at.second + 5f), pB) }
        }
        c.restoreToCount(save)
        // the ring: a dark outer edge and the metal
        pS.strokeWidth = 5f; pS.color = Color.rgb(20, 18, 14); c.drawCircle(cx, cy, r + 1.5f, pS)
        pS.strokeWidth = 2.4f; pS.color = Color.rgb(176, 150, 100); c.drawCircle(cx, cy, r + 1f, pS)
        val arrow = UiKit.tex("mm_player")
        if (arrow != null) c.drawBitmap(arrow, null, RectF(cx - 6f, cy - 6f, cx + 6f, cy + 6f), pB)
        else { pF.color = Color.WHITE; tri(c, cx, cy - 6f, cx + 4.5f, cy + 5f, cx - 4.5f, cy + 5f) }
        val band = RectF(cx - 34f, 4f, cx + 34f, 15f)
        UiKit.tex("zone_band")?.let { c.drawBitmap(it, null, band, pB) } ?: run { pF.color = Color.argb(200, 0, 0, 0); c.drawRoundRect(band, 3f, 3f, pF) }
        text(c, "Goldshire", cx, band.bottom - 3f, 7f, Color.rgb(255, 210, 0), center = true)
        UiKit.tex("minimap_button")?.let { c.drawBitmap(it, null, RectF(cx + 30f, cy + 26f, cx + 42f, cy + 38f), pB) }
    }

    /** the game's quest tracker: its header, quest names in gold, objectives in white, one done */
    private fun tracker(c: Canvas) {
        val head = RectF(96f, 8f, 208f, 24f)
        UiKit.tex("tracker_header")?.let { c.drawBitmap(it, null, head, pB) } ?: run { pF.color = Color.argb(160, 60, 40, 20); c.drawRect(head, pF) }
        text(c, "Quests", head.left + 8f, head.bottom - 5f, 7.5f, Color.rgb(255, 210, 0))
        val lines = listOf(Triple("Kobold Camp Cleanup", true, false), Triple("- Kobold Vermin: 4/8", false, false),
            Triple("Investigate Echo Ridge", true, false), Triple("- Kobold Worker: 10/10", false, true), Triple("A Threat Within", true, false))
        var y = 34f
        for ((t, quest, done) in lines) {
            if (done) UiKit.tex("tracker_check")?.let { c.drawBitmap(it, null, RectF(98f, y - 7f, 106f, y + 1f), pB) }
            text(c, t, if (quest) 100f else 108f, y, if (quest) 7.5f else 6.8f,
                if (done) Color.rgb(150, 150, 150) else if (quest) Color.rgb(255, 210, 0) else Color.rgb(235, 235, 235))
            y += if (quest) 11f else 12f
        }
        figure(c, 50f, 100f, 32f, Color.rgb(22, 24, 30))
    }

    /** the game's chat window: its dim ground, lines in the channels' colours (sample lengths), the edit box under it */
    private fun chat(c: Canvas) {
        val box = RectF(8f, 26f, 150f, 88f)
        pF.color = Color.argb(120, 0, 0, 0); c.drawRect(box, pF)
        val cols = listOf(Color.rgb(64, 255, 64), Color.rgb(255, 255, 255), Color.rgb(255, 128, 255), Color.rgb(170, 170, 255),
            Color.rgb(255, 255, 160), Color.rgb(64, 255, 64))
        val lens = listOf(0.82f, 0.6f, 0.9f, 0.48f, 0.72f, 0.56f)
        for (i in cols.indices) {
            val y = box.top + 6f + i * 9.5f
            pF.color = cols[i]; pF.alpha = 220
            c.drawRoundRect(RectF(box.left + 4f, y, box.left + 4f + 22f, y + 4f), 1.5f, 1.5f, pF)
            pF.alpha = 150
            c.drawRoundRect(RectF(box.left + 30f, y, box.left + 30f + (box.width() - 36f) * lens[i], y + 4f), 1.5f, 1.5f, pF)
            pF.alpha = 255
        }
        val input = RectF(8f, 91f, 150f, 103f)
        UiKit.inputBox(c, input)
        figure(c, 182f, 98f, 32f, Color.rgb(22, 24, 30))
    }

    /** an NPC's window: the parchment page in its dark frame, the gossip portrait, the words and two options */
    private fun npcWindow(c: Canvas) {
        val win = RectF(52f, 6f, 166f, 102f)
        pF.color = Color.rgb(24, 20, 16); c.drawRoundRect(win, 4f, 4f, pF)
        val page = RectF(win.left + 5f, win.top + 20f, win.right - 5f, win.bottom - 5f)
        UiKit.tex("parchment")?.let { c.drawBitmap(it, null, page, pB) } ?: run { pF.color = Color.rgb(214, 184, 130); c.drawRect(page, pF) }
        pS.color = Color.rgb(150, 126, 90); pS.strokeWidth = 1.6f; c.drawRoundRect(win, 4f, 4f, pS)
        portrait(c, win.left + 10f, win.top + 10f, 10f) { r ->
            UiKit.tex("portrait_gossip")?.let { b -> c.drawBitmap(b, null, RectF(r.left + 2f, r.top + 3f, r.right - 2f, r.bottom - 3f), pB) }
                ?: run { pF.color = Color.rgb(200, 200, 210); c.drawOval(r, pF) }
        }
        text(c, "Innkeeper", win.left + 24f, win.top + 13f, 7.5f, Color.rgb(255, 210, 0))
        pF.color = Color.argb(150, 60, 40, 20)
        for (i in 0..2) c.drawRect(page.left + 6f, page.top + 7f + i * 7f, page.right - 6f - i * 14f, page.top + 9.5f + i * 7f, pF)
        for ((i, k) in listOf("portrait_gossip", "portrait_quest").withIndex()) {
            val y = page.top + 34f + i * 14f
            UiKit.tex(k)?.let { b -> c.drawBitmap(b, null, RectF(page.left + 6f, y - 5f, page.left + 16f, y + 5f), pB) }
                ?: run { pF.color = Color.rgb(90, 70, 40); c.drawCircle(page.left + 11f, y, 4f, pF) }
            pF.color = Color.argb(170, 40, 26, 12); c.drawRect(page.left + 20f, y - 1.5f, page.left + 20f + 54f - i * 12f, y + 1.5f, pF)
        }
    }

    /** the game's loot window: three items in their quality colours */
    private fun loot(c: Canvas) {
        val win = RectF(42f, 8f, 174f, 100f)
        pF.color = Color.argb(220, 16, 14, 12); c.drawRoundRect(win, 4f, 4f, pF)
        pS.color = Color.rgb(150, 126, 90); pS.strokeWidth = 1.6f; c.drawRoundRect(win, 4f, 4f, pS)
        text(c, "Items", win.left + 8f, win.top + 13f, 7.5f, Color.rgb(255, 210, 0))
        val items = listOf(Triple(Color.rgb(30, 255, 0), "green", 0.0f), Triple(Color.rgb(0, 112, 221), "blue", 0.0f), Triple(Color.WHITE, "white", 0f))
        val icons = listOf(Color.rgb(90, 120, 60), Color.rgb(60, 80, 140), Color.rgb(140, 110, 70))
        for ((i, it) in items.withIndex()) {
            val y = win.top + 20f + i * 25f
            val row = RectF(win.left + 6f, y, win.right - 6f, y + 21f)
            UiKit.tex("loot_card_bg")?.let { b -> c.drawBitmap(b, null, row, pB) } ?: run { pF.color = Color.rgb(28, 26, 24); c.drawRect(row, pF) }
            val ic = RectF(row.left + 2f, row.top + 2f, row.left + 19f, row.bottom - 2f)
            pF.shader = LinearGradient(0f, ic.top, 0f, ic.bottom, icons[i], Color.rgb(20, 20, 20), Shader.TileMode.CLAMP); c.drawRect(ic, pF); pF.shader = null
            val border = UiKit.tex("loot_border_${it.second}")
            if (border != null) c.drawBitmap(border, null, ic, pB) else { pS.color = it.first; pS.strokeWidth = 1.2f; c.drawRect(ic, pS) }
            pF.color = it.first; pF.alpha = 200
            c.drawRect(row.left + 24f, row.centerY() - 1.8f, row.left + 24f + 70f - i * 12f, row.centerY() + 1.8f, pF)
            pF.alpha = 255
        }
    }

    /** the game's tooltip: its dark box and light edge, an item's name in green and its lines */
    private fun tooltip(c: Canvas) {
        val box = RectF(70f, 10f, 196f, 92f)
        pF.color = Color.argb(230, 6, 6, 18); c.drawRoundRect(box, 4f, 4f, pF)
        pS.color = Color.rgb(170, 170, 190); pS.strokeWidth = 1.6f; c.drawRoundRect(box, 4f, 4f, pS)
        text(c, "Sturdy Leather Boots", box.left + 6f, box.top + 13f, 7.5f, Color.rgb(30, 255, 0))
        val lines = listOf("Binds when equipped" to Color.WHITE, "Feet          Leather" to Color.WHITE, "62 Armor" to Color.WHITE,
            "+4 Agility" to Color.WHITE, "Requires Level 14" to Color.rgb(255, 210, 0))
        for ((i, l) in lines.withIndex()) text(c, l.first, box.left + 6f, box.top + 25f + i * 11f, 6.8f, l.second)
        // the pointer the tooltip follows
        pF.color = Color.WHITE; tri(c, 44f, 70f, 54f, 86f, 46f, 84f)
    }

    /** the game's cast bar: its groove, the gold fill, the spark at its end, the spell's name */
    private fun castBar(c: Canvas) {
        figure(c, 108f, 44f, 32f, Color.rgb(22, 24, 30))
        val r = RectF(22f, 56f, 194f, 74f)
        val groove = UiKit.castArt("castbar_background")
        val fill = UiKit.castArt("castbar_filling_standard")
        val f = 0.62f
        if (groove != null) c.drawBitmap(groove, null, r, pB) else { pF.color = Color.argb(200, 10, 10, 10); c.drawRoundRect(r, 3f, 3f, pF) }
        val fr = RectF(r.left + 2f, r.top + 2f, r.left + 2f + (r.width() - 4f) * f, r.bottom - 2f)
        if (fill != null) {
            c.save(); c.clipRect(fr)
            c.drawBitmap(fill, null, RectF(r.left + 2f, r.top + 2f, r.right - 2f, r.bottom - 2f), pB)
            c.restore()
        } else { pF.color = Color.rgb(250, 200, 30); c.drawRect(fr, pF) }
        UiKit.castArt("castbar_pip")?.let { c.drawBitmap(it, null, RectF(fr.right - 2f, r.top - 4f, fr.right + 2f, r.bottom + 4f), pB) }
        text(c, "Hearthstone", r.centerX(), r.bottom + 13f, 9f, Color.WHITE, center = true)
    }

    /** the game's player frame (portrait left, your class) or target frame (portrait right, a hostile mob, the red name band) */
    private fun unitFrame(c: Canvas, look: Look, target: Boolean) {
        val px = if (target) 168f else 48f
        val bl = if (target) 54f else 74f
        val br = if (target) 150f else 170f
        val band = RectF(bl, 28f, br, 40f)
        pF.color = if (target) Color.argb(220, 170, 20, 16) else Color.argb(200, 0, 0, 0); c.drawRect(band, pF)
        pF.color = Color.argb(200, 0, 0, 0); c.drawRect(bl, 40f, br, 60f, pF)
        bar(c, RectF(bl + 1f, 41f, br - 1f, 50f), if (target) 0.55f else 0.86f, Color.rgb(40, 200, 60), edge = false)
        bar(c, RectF(bl + 1f, 51f, br - 1f, 59f), 0.7f, if (target) Color.rgb(40, 100, 230) else Color.rgb(40, 100, 230), edge = false)
        text(c, if (target) "Kobold Tunneler" else "", (bl + br) / 2f, 37.5f, 7f, Color.WHITE, center = true)
        if (!target) { pF.color = Color.rgb(220, 200, 160); c.drawRect(bl + 20f, 32f, br - 20f, 36f, pF) }
        portrait(c, px, 46f, 22f) { r ->
            if (target) {
                pF.color = Color.rgb(60, 30, 24); c.drawOval(r, pF)
                figure(c, r.centerX(), r.bottom + 6f, r.height() * 0.9f, Color.rgb(120, 60, 40))
            } else classIcon(c, look.cls ?: "PALADIN", r)
        }
        // the level disc
        val lx = if (target) px + 20f else px - 20f
        disc(c, lx, 66f, 7f, Color.rgb(30, 24, 16))
        pS.color = Color.rgb(176, 150, 100); pS.strokeWidth = 1.2f; c.drawCircle(lx, 66f, 7f, pS)
        text(c, if (target) "11" else "12", lx, 69f, 7.5f, Color.rgb(255, 210, 0), center = true, number = true)
        figure(c, if (target) 60f else 160f, 104f, 26f, Color.rgb(22, 24, 30))
    }

    /** the game's controller bars: four round buttons a side in the diamond; Nintendo = DS Forever's bar in its place (the buttons'
     *  letters, the gold rings). split-hud: the XP bar and the buffs are tiles of their own now ([xpBar], [buffs]) */
    private fun controllerBars(c: Canvas, look: Look) {
        val nin = look.mode == UiMode.NINTENDO
        val icons = listOf("ricon_spellbook", "ricon_bags", "ricon_character", "ricon_talents", "ricon_quests", "ricon_map", "ricon_social", "ricon_professions")
        val letters = listOf("x", "y", "a", "b")
        for (side in 0..1) {
            val cx = if (side == 0) 42f else 174f
            val cy = 64f
            val spots = listOf(cx - 21f to cy, cx to cy - 21f, cx to cy + 21f, cx + 21f to cy)
            for ((i, p) in spots.withIndex()) {
                val r = RectF(p.first - 11.5f, p.second - 11.5f, p.first + 11.5f, p.second + 11.5f)
                pF.color = Color.rgb(16, 16, 18); c.drawOval(r, pF)
                UiKit.tex(icons[side * 4 + i])?.let { b -> c.drawBitmap(b, null, RectF(r.left + 3f, r.top + 3f, r.right - 3f, r.bottom - 3f), pB) }
                val ring = UiKit.castArt(if (nin) "frame_circle_selected" else "frame_circle_normal")
                if (ring != null) c.drawBitmap(ring, null, RectF(r.left - 2f, r.top - 2f, r.right + 2f, r.bottom + 2f), pB)
                else { pS.color = if (nin) Theme.gold else Color.rgb(150, 126, 90); pS.strokeWidth = 1.6f; c.drawOval(r, pS) }
                if (nin) {
                    val g = UiKit.castArt("glyph_nintendo_${letters[i]}")
                    val gr = RectF(r.right - 9f, r.bottom - 9f, r.right + 1f, r.bottom + 1f)
                    if (g != null) c.drawBitmap(g, null, gr, pB) else { disc(c, gr.centerX(), gr.centerY(), 4f, Color.rgb(230, 230, 230)) }
                }
            }
        }
        figure(c, 108f, 90f, 40f, Color.rgb(22, 24, 30))
    }

    /** split-hud: sample buffs and debuffs (icons.pak fileIDs: Fortitude, Mark of the Wild, Arcane Intellect, Kings, Lightning Shield;
     *  Curse of Weakness, Frost Nova) */
    private val BUFF_ICONS = listOf(135987, 136078, 135932, 135995, 136051)
    private val DEBUFF_ICONS = listOf(136138 to "Curse", 135848 to "Magic")

    /** split-hud (the user, 2026-11-17: "we need to split out the Buffs controller UI and XP bar"): the game's buffs at the top right of
     *  the screen (a row of icons, their minutes under them at the band's size), the debuffs under them in their type's border, the
     *  durability figure beside (Home draws it between its buff rows, so it rides this tile); clear of the badge corner (x 172+, y 0..44) */
    private fun buffs(c: Canvas) {
        figure(c, 92f, 104f, 30f, Color.rgb(22, 24, 30))
        val s = 17f
        for ((i, fid) in BUFF_ICONS.withIndex()) {
            val x = 168f - (BUFF_ICONS.size - i) * (s + 3f)
            auraIcon(c, fid, RectF(x, 8f, x + s, 8f + s), i, null)
            if (big) text(c, listOf("58m", "52m", "41m", "9m", "6m")[i], x + s / 2f, 8f + s + 6.5f, 5.5f, Color.rgb(255, 210, 0), center = true, number = true)
        }
        for ((i, d) in DEBUFF_ICONS.withIndex()) {
            val x = 168f - (DEBUFF_ICONS.size - i) * (s + 3f)
            auraIcon(c, d.first, RectF(x, 38f, x + s, 38f + s), 5 + i, UiKit.debuffColour(d.second))
            if (big) text(c, listOf("1m", "6")[i], x + s / 2f, 38f + s + 6.5f, 5.5f, Color.rgb(255, 210, 0), center = true, number = true)
        }
        durability(c, 190f, 50f)
    }

    /** a buff's icon (icons.pak) cut square with the game's thin dark edge; [border] = a debuff's type colour; without the pack its hue */
    private fun auraIcon(c: Canvas, fid: Int, r: RectF, slot: Int, border: Int?) {
        val b = runCatching { Icons.get(fid) }.getOrNull() ?: UiKit.icon(fid)
        if (b != null) {
            val ix = b.width * 0.08f; val iy = b.height * 0.08f
            c.drawBitmap(b, Rect(ix.toInt(), iy.toInt(), (b.width - ix).toInt(), (b.height - iy).toInt()), r, pB)
        } else {
            val hue = ICON_HUES[slot % ICON_HUES.size]
            pF.shader = LinearGradient(0f, r.top, 0f, r.bottom, lighter(hue), darker(hue), Shader.TileMode.CLAMP); c.drawRect(r, pF); pF.shader = null
        }
        if (border != null) { pS.color = border; pS.strokeWidth = 1.6f; c.drawRect(r.left - 0.8f, r.top - 0.8f, r.right + 0.8f, r.bottom + 0.8f, pS) }
        else { pS.color = Color.argb(220, 0, 0, 0); pS.strokeWidth = 0.8f; c.drawRect(r, pS) }
    }

    /** the game's durability figure (its little armoured man): the worn pieces yellow, a broken one red, [top] its head's top */
    private fun durability(c: Canvas, cx: Float, top: Float) {
        val ok = Color.argb(150, 190, 190, 190); val worn = Color.rgb(230, 200, 40); val broken = Color.rgb(220, 40, 30)
        pF.color = ok; c.drawCircle(cx, top + 4f, 4f, pF)                                     // head
        pF.color = worn; c.drawRect(cx - 7f, top + 9f, cx - 3f, top + 13f, pF)               // shoulders
        c.drawRect(cx + 3f, top + 9f, cx + 7f, top + 13f, pF)
        pF.color = broken; c.drawRect(cx - 4f, top + 9f, cx + 4f, top + 22f, pF)             // chest
        pF.color = ok; c.drawRect(cx - 8f, top + 14f, cx - 5f, top + 24f, pF)                // arms
        c.drawRect(cx + 5f, top + 14f, cx + 8f, top + 24f, pF)
        pF.color = worn; c.drawRect(cx - 4f, top + 23f, cx - 0.6f, top + 36f, pF)            // legs
        pF.color = ok; c.drawRect(cx + 0.6f, top + 23f, cx + 4f, top + 36f, pF)
    }

    /** split-hud: the game's XP bar at the bottom of the screen (the gamepad layout's half-width bar: the XP with its rested part, the
     *  kit's track art), the second status bar (a reputation) over it, a character in the world above */
    private fun xpBar(c: Canvas) {
        figure(c, 108f, 68f, 42f, Color.rgb(22, 24, 30))
        UiKit.track(c, RectF(18f, 73f, 198f, 82f), "green", 0.68f, segments = 10)
        UiKit.track(c, RectF(18f, 85f, 198f, 99f), "xp", 0.46f, reach = 0.62f, segments = 20)
    }

    // ---------------------------------------------------------------- hub61: the damage meter, the Cooldown Manager, Edit layout

    /** a sample spell icon set per class (icons.pak fileIDs: 4 Essential, 3 Utility, 2 buffs), so the picture is your class's look */
    private val CLASS_SPELLS = mapOf(
        "SHAMAN" to listOf(136048, 136026, 135813, 136042, 136012, 136052, 136104, 136051, 136081),
        "MAGE" to listOf(135846, 135808, 135848, 135736, 135857, 135827, 135987, 135963, 136096),
        "WARRIOR" to listOf(132355, 132337, 132363, 132333, 132155, 132212, 132090, 132333, 135891),
        "PALADIN" to listOf(135891, 135920, 135928, 135963, 135907, 135953, 135987, 135963, 136104),
        "PRIEST" to listOf(135907, 136207, 135953, 135987, 135920, 136145, 136139, 135987, 135953),
        "ROGUE" to listOf(132292, 132090, 132155, 132115, 132212, 132222, 132363, 132333, 132292),
        "HUNTER" to listOf(132157, 132212, 132222, 132179, 132337, 132355, 136104, 132333, 132179),
        "WARLOCK" to listOf(136197, 136139, 136145, 135827, 136207, 135808, 135987, 135827, 136139),
        "DRUID" to listOf(136096, 136081, 132115, 136104, 136048, 135857, 136081, 136104, 132115))
    /** the icons' stand-in colours (no icons.pak on the phone yet): rounded squares in the icons' own hues */
    private val ICON_HUES = listOf(Color.rgb(80, 120, 210), Color.rgb(200, 120, 50), Color.rgb(200, 70, 50), Color.rgb(90, 170, 90),
        Color.rgb(170, 90, 200), Color.rgb(70, 160, 180), Color.rgb(120, 170, 70), Color.rgb(210, 180, 70), Color.rgb(90, 140, 200))

    /** the game's damage meter on the right of the screen: its dark window and header, 4 class-coloured bars (your class first; fixed
     *  sample shares 100 / 78 / 58 / 40 %), each with its class icon and a short grey name bar; the sample numbers only at the band's
     *  size; Dynamic = the fight's crossed swords in the header (it shows in a fight and 15 s after) */
    private fun damageMeter(c: Canvas, look: Look) {
        // (the window left of the tile's badge corner - x 172 and on, y 0..44 stays the badge's - you under the badge)
        figure(c, 194f, 102f, 34f, Color.rgb(22, 24, 30))
        val win = RectF(40f, 6f, 166f, 102f)
        val bg = UiKit.tex("dmg_bg")
        if (bg != null) { pB.alpha = 215; c.drawBitmap(bg, null, win, pB); pB.alpha = 255 }
        else { pF.color = Color.argb(180, 0, 0, 0); c.drawRoundRect(win, 3f, 3f, pF) }
        val head = RectF(win.left, win.top, win.right, win.top + 13f)
        val hb = UiKit.tex("dmg_header")
        if (hb != null) c.drawBitmap(hb, null, head, pB)
        else {
            pF.color = Color.argb(220, 58, 42, 12); c.drawRect(head, pF)
            pS.color = Color.rgb(200, 160, 60); pS.strokeWidth = 0.8f; c.drawLine(head.left, head.bottom, head.right, head.bottom, pS)
        }
        // the header's title, a short light bar (no words in a tile)
        pF.color = Color.argb(210, 235, 215, 160); c.drawRoundRect(RectF(head.left + 6f, head.centerY() - 1.6f, head.left + 42f, head.centerY() + 1.6f), 1f, 1f, pF)
        // (beside the title, clear of the tile's badge corner)
        if (look.mode == UiMode.DYNAMIC) swords(c, head.left + 54f, head.centerY(), 4.4f, Color.rgb(255, 210, 0))
        val own = (look.cls ?: "SHAMAN").uppercase()
        val classes = (listOf(own) + listOf("WARRIOR", "MAGE", "PRIEST", "ROGUE").filter { it != own }).take(4)
        val fills = listOf(1f, 0.78f, 0.58f, 0.40f)
        val nums = listOf("1.2K", "948", "702", "481")
        for (i in 0..3) {
            val y = head.bottom + 4f + i * 20f
            val row = RectF(win.left + 4f, y, win.right - 4f, y + 16f)
            classIcon(c, classes[i], RectF(row.left, row.top, row.left + 16f, row.bottom))
            val bar = RectF(row.left + 18f, row.top, row.right, row.bottom)
            UiKit.tex("dmg_bar_bg")?.let { c.drawBitmap(it, null, bar, pB) } ?: run { pF.color = Color.argb(160, 30, 28, 40); c.drawRoundRect(bar, 2f, 2f, pF) }
            val col = CLASS_COLOURS[classes[i]] ?: Color.rgb(160, 160, 160)
            val fr = RectF(bar.left + 1f, bar.top + 1f, bar.left + 1f + (bar.width() - 2f) * fills[i], bar.bottom - 1f)
            pF.shader = LinearGradient(0f, fr.top, 0f, fr.bottom, lighter(col), darker(col), Shader.TileMode.CLAMP)
            c.drawRoundRect(fr, 1.5f, 1.5f, pF); pF.shader = null
            UiKit.tex("dmg_bar_edge")?.let { c.drawBitmap(it, null, bar, pB) }
            // the name: a short grey bar (sample data has no words)
            pF.color = Color.argb(190, 245, 245, 245); c.drawRoundRect(RectF(bar.left + 4f, bar.centerY() - 1.6f, bar.left + 4f + 20f + (i % 2) * 7f, bar.centerY() + 1.6f), 1f, 1f, pF)
            if (big) {
                val p = UiKit.numberPaint(7.5f, Color.WHITE, shadow = false)
                p.textAlign = Paint.Align.RIGHT
                c.drawText(nums[i], bar.right - 3f, bar.centerY() + 2.7f, p)
            }
        }
    }

    /** the game's Cooldown Manager under your character: Essential (4 of your class's spells, 30 px, one cooling down: the 60 % sweep),
     *  Utility (3 at 22 px), Buff icons (2 at 20 px, top right), Buff bars (2 bars with their pips, 10 px tall, top left); each part only
     *  while its bit is on */
    private fun cooldownManager(c: Canvas, look: Look) {
        fun on(b: Int) = (look.cdm shr b) and 1 == 1
        val ids = CLASS_SPELLS[(look.cls ?: "SHAMAN").uppercase()] ?: CLASS_SPELLS.getValue("SHAMAN")
        figure(c, 108f, 108f, 16f, Color.rgb(22, 24, 30))
        if (on(3)) for (i in 0..1) {
            val y = 10f + i * 15f
            cdmIcon(c, ids[7 + i], RectF(10f, y, 20f, y + 10f), 7 + i)
            val bar = RectF(23f, y, 96f, y + 10f)
            UiKit.tex("cdm_bar_bg")?.let { c.drawBitmap(it, null, RectF(bar.left - 1f, bar.top - 1f, bar.right + 1f, bar.bottom + 1f), pB) }
                ?: run { pF.color = Color.argb(200, 30, 30, 28); c.drawRoundRect(bar, 2f, 2f, pF) }
            val f = listOf(0.72f, 0.44f)[i]
            val fr = RectF(bar.left + 1f, bar.top + 1.5f, bar.left + 1f + (bar.width() - 2f) * f, bar.bottom - 1.5f)
            val tint = listOf(Color.rgb(255, 176, 40), Color.rgb(120, 190, 255))[i]
            val fill = UiKit.tex("cdm_bar")
            if (fill != null) { pB.colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.MULTIPLY); c.drawBitmap(fill, null, fr, pB); pB.colorFilter = null }
            else { pF.color = tint; c.drawRect(fr, pF) }
            val pip = UiKit.tex("cdm_bar_pip")
            if (pip != null) c.drawBitmap(pip, null, RectF(fr.right - 2f, bar.top - 3f, fr.right + 2f, bar.bottom + 3f), pB)
            else { pF.color = Color.rgb(250, 240, 120); c.drawRect(fr.right - 0.8f, bar.top, fr.right + 0.8f, bar.bottom, pF) }
        }
        // (Buff icons right of the bars, clear of the tile's badge corner: x 172 and on)
        if (on(2)) for (i in 0..1) { val x = 118f + i * 24f; cdmIcon(c, ids[7 + i], RectF(x, 10f, x + 20f, 30f), 7 + i) }
        // (Essential and Utility centred on x 101, not the well's 108: the last Essential icon's overlay frame stays left of the badge corner)
        if (on(0)) for (i in 0..3) { val x = 38f + i * 32f; cdmIcon(c, ids[i], RectF(x, 38f, x + 30f, 68f), i, sweep = if (i == 2) 0.6f else 0f) }
        if (on(1)) for (i in 0..2) { val x = 64f + i * 26f; cdmIcon(c, ids[4 + i], RectF(x, 76f, x + 22f, 98f), 4 + i) }
    }

    /** one Cooldown Manager icon: the spell's icon (icons.pak) in the game's rounded mask, a cooldown's dark sweep, the kit's overlay
     *  frame round it; without the pack a rounded square in the icon's hue */
    private fun cdmIcon(c: Canvas, fid: Int, r: RectF, slot: Int, sweep: Float = 0f) {
        val rad = r.width() * 0.14f
        val save = c.save()
        path.reset(); path.addRoundRect(r, rad, rad, Path.Direction.CW); c.clipPath(path)
        val b = runCatching { Icons.get(fid) }.getOrNull() ?: UiKit.icon(fid)
        if (b != null) {
            val ix = b.width * 0.07f; val iy = b.height * 0.07f
            c.drawBitmap(b, Rect(ix.toInt(), iy.toInt(), (b.width - ix).toInt(), (b.height - iy).toInt()), r, pB)
        } else {
            val hue = ICON_HUES[slot % ICON_HUES.size]
            pF.shader = LinearGradient(0f, r.top, 0f, r.bottom, lighter(hue), darker(hue), Shader.TileMode.CLAMP); c.drawRect(r, pF); pF.shader = null
        }
        if (sweep > 0f) {
            // the cooldown's dark pie from 12 o'clock, clockwise (a path: the same on every canvas)
            pF.color = Color.argb(200, 0, 0, 0)
            val e = r.width() * 0.3f
            val pie = Path()
            pie.moveTo(r.centerX(), r.centerY())
            pie.arcTo(RectF(r.left - e, r.top - e, r.right + e, r.bottom + e), -90f, 360f * sweep, false)
            pie.close()
            c.drawPath(pie, pF)
            // the sweep's bright edge (the game's cooldown edge), from the centre to where the dark part ends
            val a = Math.toRadians((-90.0 + 360.0 * sweep))
            pS.color = Color.argb(230, 255, 236, 170); pS.strokeWidth = (r.width() * 0.05f).coerceAtLeast(0.8f)
            c.drawLine(r.centerX(), r.centerY(), r.centerX() + (r.width() * kotlin.math.cos(a)).toFloat(), r.centerY() + (r.width() * kotlin.math.sin(a)).toFloat(), pS)
        }
        c.restoreToCount(save)
        val ov = UiKit.tex("cdm_icon_overlay")
        if (ov != null) { val e = r.width() * 0.2f; c.drawBitmap(ov, null, RectF(r.left - e, r.top - e, r.right + e, r.bottom + e), pB) }
        else { pS.color = Color.argb(230, 0, 0, 0); pS.strokeWidth = 1.2f; c.drawRoundRect(r, rad, rad, pS) }
    }

    /** two crossed swords (the fight), centred at ([cx], [cy]), [s] half their length */
    private fun swords(c: Canvas, cx: Float, cy: Float, s: Float, col: Int) {
        pS.color = col; pS.strokeWidth = 1.1f
        c.drawLine(cx - s, cy + s, cx + s, cy - s, pS); c.drawLine(cx - s, cy - s, cx + s, cy + s, pS)
        pS.strokeWidth = 0.9f
        c.drawLine(cx - s * 0.75f, cy + s * 0.25f, cx - s * 0.25f, cy + s * 0.75f, pS)
        c.drawLine(cx + s * 0.75f, cy + s * 0.25f, cx + s * 0.25f, cy + s * 0.75f, pS)
    }

    /** sample pieces for Edit layout's miniature before the editor's first document (1080-line px on a 1920 x 1080 top screen) */
    private val SAMPLE_PIECES = listOf("compass" to LayoutModel.Rect(530.0, 4.0, 860.0, 76.0), "target" to LayoutModel.Rect(760.0, 90.0, 400.0, 44.0),
        "party" to LayoutModel.Rect(24.0, 300.0, 230.0, 250.0), "chat" to LayoutModel.Rect(24.0, 790.0, 520.0, 170.0),
        "hud" to LayoutModel.Rect(820.0, 880.0, 280.0, 44.0), "nbar" to LayoutModel.Rect(600.0, 930.0, 720.0, 140.0))

    /** Edit layout: the layout editor's miniature (the top screen 16:9), the pieces that are on in Edit Mode's blue (from the editor's
     *  own document, else a sample), the Resource bar picked in gold with the four move arrows */
    private fun layoutMini(c: Canvas) {
        val scr = RectF(12f, 2f, 204f, 106f)
        pF.color = Color.argb(70, 0, 0, 0); c.drawRect(scr, pF)
        pS.color = Color.argb(46, 220, 220, 220); pS.strokeWidth = 0.5f
        for (i in 1..7) { val x = scr.left + scr.width() * i / 8f; c.drawLine(x, scr.top, x, scr.bottom, pS) }
        for (i in 1..3) { val y = scr.top + scr.height() * i / 4f; c.drawLine(scr.left, y, scr.right, y, pS) }
        pS.color = Color.argb(110, 230, 230, 230); pS.strokeWidth = 0.7f
        c.drawLine(scr.centerX(), scr.top, scr.centerX(), scr.bottom, pS); c.drawLine(scr.left, scr.centerY(), scr.right, scr.centerY(), pS)
        val d = LayoutModel.Live.doc
        val pieces = d?.let { doc -> LayoutModel.listed(doc).map { it.id to it.rect } }?.takeIf { it.isNotEmpty() } ?: SAMPLE_PIECES
        val sw = (d?.w ?: 1920.0).takeIf { it > 0 } ?: 1920.0
        val sh = (d?.h ?: 1080.0).takeIf { it > 0 } ?: 1080.0
        val kx = scr.width() / sw.toFloat(); val ky = scr.height() / sh.toFloat()
        val pick = pieces.firstOrNull { it.first == "hud" }?.first ?: pieces.first().first
        for ((id, rr) in pieces) {
            val b = RectF(scr.left + rr.x.toFloat() * kx, scr.top + rr.y.toFloat() * ky, scr.left + rr.right.toFloat() * kx, scr.top + rr.bottom.toFloat() * ky)
            val sel = id == pick
            val col = if (sel) Color.rgb(255, 200, 40) else Color.rgb(70, 150, 255)
            pF.color = (col and 0x00FFFFFF) or (if (sel) 0x50000000 else 0x34000000); c.drawRect(b, pF)
            layoutPiece(c, id, b)
            pS.color = col; pS.strokeWidth = if (sel) 1.4f else 1f; c.drawRect(b, pS)
            if (sel) {
                // the four move arrows round the picked piece
                pF.color = col
                val ax = b.centerX(); val ay = b.centerY(); val g = 5f
                tri(c, ax, b.top - g - 5f, ax - 3.5f, b.top - g, ax + 3.5f, b.top - g)
                tri(c, ax, b.bottom + g + 5f, ax - 3.5f, b.bottom + g, ax + 3.5f, b.bottom + g)
                tri(c, b.left - g - 5f, ay, b.left - g, ay - 3.5f, b.left - g, ay + 3.5f)
                tri(c, b.right + g + 5f, ay, b.right + g, ay - 3.5f, b.right + g, ay + 3.5f)
            }
        }
        pS.color = Color.argb(200, 176, 150, 100); pS.strokeWidth = 1.2f; c.drawRect(scr, pS)
    }

    /** a piece drawn small in its box (LayoutView's schematic, at the tile's size): the HUD's two bars, the compass's ticks, the target's
     *  red bar, the party's rows, the Nintendo bar's two diamonds, the chat's lines */
    private fun layoutPiece(c: Canvas, id: String, r: RectF) {
        when (id) {
            "hud" -> {
                pF.color = Color.rgb(40, 190, 60); c.drawRect(r.left + 1f, r.top + 1f, r.right - 1f, r.centerY(), pF)
                pF.color = Color.rgb(40, 100, 230); c.drawRect(r.left + 1f, r.centerY() + 0.6f, r.right - 1f, r.bottom - 1f, pF)
            }
            "compass" -> {
                pS.color = Color.argb(200, 210, 190, 130); pS.strokeWidth = 0.6f
                val n = 14
                for (i in 1 until n) { val x = r.left + r.width() * i / n; c.drawLine(x, r.centerY() - 1.4f, x, r.centerY() + 1.4f, pS) }
            }
            "target" -> { pF.color = Color.rgb(200, 34, 28); c.drawRect(r.left + r.height(), r.centerY() - 1.2f, r.right - 1f, r.bottom - 1f, pF) }
            "party" -> for (i in 0 until 4) {
                val y = r.top + r.height() * (i + 0.5f) / 4f
                pF.color = Color.rgb(40, 190, 60); c.drawRect(r.left + r.width() * 0.3f, y - 1f, r.right - r.width() * 0.1f, y + 1f, pF)
            }
            "nbar" -> for (side in listOf(0.25f, 0.75f)) {
                val cx = r.left + r.width() * side; val cy = r.centerY(); val rr = (r.height() * 0.13f).coerceAtLeast(0.8f)
                pF.color = Color.rgb(150, 126, 90)
                for ((ox, oy) in listOf(-1f to 0f, 1f to 0f, 0f to -1f, 0f to 1f)) c.drawCircle(cx + ox * rr * 2.2f, cy + oy * rr * 2.2f, rr, pF)
            }
            "chat" -> {
                pS.color = Color.argb(170, 220, 210, 190); pS.strokeWidth = 0.7f
                for (i in 1..3) { val y = r.bottom - r.height() * 0.2f * i; c.drawLine(r.left + 2f, y, r.left + r.width() * (0.85f - 0.15f * i), y, pS) }
            }
        }
    }

    // ---------------------------------------------------------------- pieces

    /** a character in the world, from behind: head, shoulders, body, legs; [foot] = where it stands, [h] tall */
    private fun figure(c: Canvas, x: Float, foot: Float, h: Float, col: Int) {
        val head = h * 0.13f
        pF.color = col
        c.drawCircle(x, foot - h + head, head, pF)
        path.reset()
        path.moveTo(x - h * 0.22f, foot - h + head * 2.3f)
        path.lineTo(x + h * 0.22f, foot - h + head * 2.3f)
        path.lineTo(x + h * 0.16f, foot - h * 0.42f)
        path.lineTo(x + h * 0.13f, foot)
        path.lineTo(x + h * 0.03f, foot)
        path.lineTo(x, foot - h * 0.3f)
        path.lineTo(x - h * 0.03f, foot)
        path.lineTo(x - h * 0.13f, foot)
        path.lineTo(x - h * 0.16f, foot - h * 0.42f)
        path.close()
        c.drawPath(path, pF)
        // a faint rim light on the shoulders
        pS.color = Color.argb(70, 200, 210, 230); pS.strokeWidth = 0.8f
        c.drawLine(x - h * 0.2f, foot - h + head * 2.3f, x + h * 0.2f, foot - h + head * 2.3f, pS)
    }

    /** a bar: its dark ground, [f] of it filled in [col] (lighter on top), a thin dark edge */
    private fun bar(c: Canvas, r: RectF, f: Float, col: Int, edge: Boolean = true) {
        pF.color = Color.argb(230, 8, 8, 8); c.drawRect(r, pF)
        val fr = RectF(r.left, r.top, r.left + r.width() * f.coerceIn(0f, 1f), r.bottom)
        pF.shader = LinearGradient(0f, r.top, 0f, r.bottom, lighter(col), col, Shader.TileMode.CLAMP)
        c.drawRect(fr, pF); pF.shader = null
        if (edge) { pS.color = Color.argb(200, 0, 0, 0); pS.strokeWidth = 0.6f; c.drawRect(r, pS) }
    }

    /** our HUD rim round [r] (the add-on's own hud_rim art, nine-sliced as Hub.lua lays it); without it a drawn rim */
    private fun rim(c: Canvas, ctx: Context?, r: RectF, small: Boolean = false) {
        val art = MediaArt.get(ctx, "hud_rim_8")
        val k = if (small) 0.8f else 1.1f
        if (art != null) {
            val s = art.width / 16f                      // texture px per art texel
            val sx = intArrayOf(0, (3 * s).toInt(), (9 * s).toInt(), (16 * s).toInt())
            val sy = intArrayOf(0, (3 * s).toInt(), (11 * s).toInt(), (19 * s).toInt())
            val dx = floatArrayOf(r.left - 3f * k, r.left, r.right, r.right + 7f * k)
            val dy = floatArrayOf(r.top - 3f * k, r.top, r.bottom, r.bottom + 8f * k)
            for (i in 0..2) for (j in 0..2) {
                if (i == 1 && j == 1) continue
                c.drawBitmap(art, Rect(sx[i], sy[j], sx[i + 1], sy[j + 1]), RectF(dx[i], dy[j], dx[i + 1], dy[j + 1]), pB)
            }
            return
        }
        pS.color = Color.argb(240, 0, 0, 0); pS.strokeWidth = 2.6f * k
        c.drawRect(r.left - 1.2f * k, r.top - 1.2f * k, r.right + 1.2f * k, r.bottom + 1.2f * k, pS)
        pS.color = Color.rgb(170, 160, 140); pS.strokeWidth = 0.9f * k
        c.drawRect(r.left - 1.6f * k, r.top - 1.6f * k, r.right + 1.6f * k, r.bottom + 1.6f * k, pS)
    }

    /** a buff's small square: a coloured face, a dark edge */
    private fun aura(c: Canvas, x: Float, y: Float, s: Float, col: Int) {
        val r = RectF(x, y, x + s, y + s)
        pF.shader = LinearGradient(0f, r.top, 0f, r.bottom, lighter(col), darker(col), Shader.TileMode.CLAMP); c.drawRect(r, pF); pF.shader = null
        pS.color = Color.argb(220, 0, 0, 0); pS.strokeWidth = 0.8f; c.drawRect(r, pS)
    }

    private fun disc(c: Canvas, x: Float, y: Float, r: Float, col: Int) { pF.color = col; c.drawCircle(x, y, r, pF) }

    /** the game's plate dragons (kit `elite_gold` / `elite_silver` = nameplates-icon-elite-gold / -silver, square: 80 x 80 in this build) */
    enum class Dragon(val kit: String) { GOLD("elite_gold"), SILVER("elite_silver") }

    /**
     * A MOB'S CLASSIFICATION MARK (the user, 2026-11-17: "the Skull above mobs is confusing, I think we need to scrap that, we do however
     * need to add the dragon Icon for an Elite and a silver one for rare spawns"), the same rule as the add-on (Trail.lua ag14, Hub.lua
     * uf25): elite / worldboss the gold dragon, rare / rareelite the silver one, nothing else. [cls] = UnitClassification's word or the
     * aggro document's code ("e" elite, "b" boss, "r" rare, "R" rare elite).
     */
    fun dragonOf(cls: String?): Dragon? = when (cls) {
        "elite", "worldboss", "e", "b" -> Dragon.GOLD
        "rare", "rareelite", "r", "R" -> Dragon.SILVER
        else -> null
    }

    /** [d] centred at ([cx], [cy]), [w] square: the kit's picture, else the drawn stand-in (UiKit.eliteDragon, the maps' one too) */
    private fun dragon(c: Canvas, d: Dragon, cx: Float, cy: Float, w: Float) =
        UiKit.eliteDragon(c, RectF(cx - w / 2f, cy - w / 2f, cx + w / 2f, cy + w / 2f), silver = d == Dragon.SILVER)

    /** a round portrait in the game's gold-metal ring; [inside] draws into the portrait's box (already clipped round) */
    private fun portrait(c: Canvas, cx: Float, cy: Float, r: Float, inside: (RectF) -> Unit) {
        val box = RectF(cx - r, cy - r, cx + r, cy + r)
        pF.color = Color.rgb(12, 10, 8); c.drawOval(box, pF)
        val save = c.save()
        path.reset(); path.addCircle(cx, cy, r, Path.Direction.CW); c.clipPath(path)
        inside(box)
        c.restoreToCount(save)
        pS.color = Color.rgb(20, 16, 10); pS.strokeWidth = r * 0.28f; c.drawCircle(cx, cy, r + r * 0.12f, pS)
        pS.color = Color.rgb(196, 160, 90); pS.strokeWidth = r * 0.13f; c.drawCircle(cx, cy, r + r * 0.12f, pS)
    }

    /** a class's icon (the kit's classicon_), else its colour */
    private fun classIcon(c: Canvas, cls: String, r: RectF) {
        val b = UiKit.tex("classicon_${cls.lowercase()}")
        if (b != null) { c.drawBitmap(b, null, r, pB); return }
        pF.color = CLASS_COLOURS[cls.uppercase()] ?: Color.rgb(160, 160, 160); c.drawRect(r, pF)
    }

    private val CLASS_COLOURS = mapOf("WARRIOR" to Color.rgb(199, 156, 110), "PALADIN" to Color.rgb(245, 140, 186), "HUNTER" to Color.rgb(170, 211, 114),
        "ROGUE" to Color.rgb(255, 244, 104), "PRIEST" to Color.rgb(255, 255, 255), "SHAMAN" to Color.rgb(0, 112, 221), "MAGE" to Color.rgb(63, 199, 235),
        "WARLOCK" to Color.rgb(135, 136, 238), "DRUID" to Color.rgb(255, 124, 10))

    /** the compass's headings and ticks across the picture at [y]; [shift] = the strip moved (px; 50 px = 45 degrees), N then off the
     *  centre (hub61: the camera); 0 = N centred, as before */
    private fun compassTicks(c: Canvas, y: Float, alpha: Int, big: Boolean = false, shift: Float = 0f) {
        val words = listOf("S", "SW", "W", "NW", "N", "NE", "E", "SE", "S")
        pS.color = Color.argb(alpha, 210, 210, 210); pS.strokeWidth = if (big) 0.9f else 0.6f
        for (j in -24..24) { val x = 108f + j * 12.5f + shift; if (x < 7.9f || x > 208.1f) continue; c.drawLine(x, y - (if (big) 3f else 2f), x, y + (if (big) 3f else 2f), pS) }
        for ((i, w) in words.withIndex()) {
            val x = 108f + (i - 4) * 50f + shift
            if (x < 7.9f || x > 208.1f) continue
            text(c, w, x, y + (if (big) 14f else 9f), if (big) 8.5f else 6f, Color.argb(alpha, 230, 230, 230), center = true)
        }
    }

    /** a four-point star (Polestar's mark) */
    private fun star(c: Canvas, x: Float, y: Float, r: Float, col: Int) {
        pF.color = col
        path.reset()
        path.moveTo(x, y - r); path.lineTo(x + r * 0.28f, y - r * 0.28f); path.lineTo(x + r, y); path.lineTo(x + r * 0.28f, y + r * 0.28f)
        path.lineTo(x, y + r); path.lineTo(x - r * 0.28f, y + r * 0.28f); path.lineTo(x - r, y); path.lineTo(x - r * 0.28f, y - r * 0.28f)
        path.close(); c.drawPath(path, pF)
    }

    private fun tri(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float) {
        path.reset(); path.moveTo(x0, y0); path.lineTo(x1, y1); path.lineTo(x2, y2); path.close(); c.drawPath(path, pF)
    }

    /** words in the picture, in the game's type (sample words: a mob, a quest, an item; never the player's own data) */
    private fun text(c: Canvas, s: String, x: Float, base: Float, size: Float, col: Int, center: Boolean = false, number: Boolean = false) {
        if (s.isEmpty()) return
        val p = (if (number) UiKit.numberPaint(size, col, shadow = false) else UiKit.bodyPaint(size, col, shadow = false))
        p.alpha = Color.alpha(col)
        p.textAlign = if (center) Paint.Align.CENTER else Paint.Align.LEFT
        c.drawText(s, x, base, p)
    }

    private fun lighter(c: Int) = Color.rgb((Color.red(c) + 255) / 2, (Color.green(c) + 255) / 2, (Color.blue(c) + 255) / 2)
    private fun darker(c: Int) = Color.rgb(Color.red(c) / 2, Color.green(c) / 2, Color.blue(c) / 2)

    // ---------------------------------------------------------------- the badge and the modes' signs

    private val pBadge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    /** the kit's art greyed to a light grey (the GAME_OFF badge: the game's option is off, nothing of ours is wrong; no colour of its own) */
    private val DIM_ART = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f); postConcat(ColorMatrix().apply { setScale(1.12f, 1.12f, 1.12f, 1f) }) })

    /** the tile's 36 px badge: a dark disc in a thin metal ring, the mode's sign (or the game's state) on it */
    fun badge(c: Canvas, r: RectF, b: GameUiTiles.Badge) {
        val cx = r.centerX(); val cy = r.centerY(); val rad = r.width() / 2f
        pF.color = Color.argb(215, 10, 10, 12); c.drawCircle(cx, cy, rad, pF)
        pBadge.strokeWidth = 2f; pBadge.color = Color.argb(220, 150, 126, 90); c.drawCircle(cx, cy, rad - 1f, pBadge)
        val col = when (b) {
            GameUiTiles.Badge.OFF, GameUiTiles.Badge.WAITING -> Theme.textDim
            GameUiTiles.Badge.GAME_OFF -> Theme.dim
            GameUiTiles.Badge.REFUSED -> Color.rgb(235, 70, 50)
            GameUiTiles.Badge.SENT -> Theme.soft
            else -> Theme.gold
        }
        pBadge.color = col; pBadge.strokeWidth = 2.2f
        val s = rad / 14f
        when (b) {
            GameUiTiles.Badge.ON -> modeSign(c, UiMode.ON, cx, cy, s, pBadge)
            GameUiTiles.Badge.OFF -> modeSign(c, UiMode.OFF, cx, cy, s, pBadge)
            GameUiTiles.Badge.DYNAMIC -> modeSign(c, UiMode.DYNAMIC, cx, cy, s, pBadge)
            GameUiTiles.Badge.TRACKING -> modeSign(c, UiMode.TRACKING, cx, cy, s, pBadge)
            GameUiTiles.Badge.NINTENDO -> modeSign(c, UiMode.NINTENDO, cx, cy, s, pBadge)
            GameUiTiles.Badge.SENT -> {
                // the hourglass: two triangles meeting, sand in the lower one
                val w = 6f * s; val h = 8f * s
                path.reset(); path.moveTo(cx - w, cy - h); path.lineTo(cx + w, cy - h); path.lineTo(cx, cy); path.close()
                path.moveTo(cx, cy); path.lineTo(cx + w, cy + h); path.lineTo(cx - w, cy + h); path.close()
                c.drawPath(path, pBadge)
                pF.color = col; tri(c, cx, cy + h * 0.35f, cx + w * 0.7f, cy + h * 0.85f, cx - w * 0.7f, cy + h * 0.85f)
            }
            GameUiTiles.Badge.REFUSED -> {
                pBadge.strokeWidth = 3f
                c.drawLine(cx, cy - 8f * s, cx, cy + 2f * s, pBadge)
                pF.color = col; c.drawCircle(cx, cy + 7f * s, 1.8f * s, pF)
            }
            GameUiTiles.Badge.WAITING -> { pF.color = col; for (i in -1..1) c.drawCircle(cx + i * 6f * s, cy, 1.9f * s, pF) }
            GameUiTiles.Badge.GAME_OFF -> {
                // hub61: the game's own option is off - the kit's alert_gear, greyed down to the dim colour; else a drawn gear
                val g = UiKit.tex("alert_gear")
                if (g != null) {
                    pB.colorFilter = DIM_ART
                    // (verifier 11-17: at 0.62 brightness and 0.72 size it read as a grey blob, close to the hourglass; now the
                    // triangle fills the disc in a light grey, its gear's dark centre and teeth readable - still no colour of its own)
                    val h = rad * 0.86f
                    c.drawBitmap(g, null, RectF(cx - h, cy - h * 0.98f, cx + h, cy + h * 1.02f), pB)
                    pB.colorFilter = null
                } else {
                    pBadge.strokeWidth = 2f
                    c.drawCircle(cx, cy, 5f * s, pBadge)
                    for (k in 0 until 8) {
                        val a = Math.toRadians(k * 45.0)
                        c.drawLine(cx + (5f * s * kotlin.math.cos(a)).toFloat(), cy + (5f * s * kotlin.math.sin(a)).toFloat(),
                            cx + (8f * s * kotlin.math.cos(a)).toFloat(), cy + (8f * s * kotlin.math.sin(a)).toFloat(), pBadge)
                    }
                    pF.color = col; c.drawCircle(cx, cy, 1.8f * s, pF)
                }
            }
        }
    }

    /** a mode's sign on Strip's 24-unit grid, centred on ([cx], [cy]) at [s] px a unit: On = an eye, Off = the eye struck through,
     *  Dynamic = the two screens (the lower lit), Tracking = the eye with a leaf, Nintendo = the four buttons */
    fun modeSign(c: Canvas, m: UiMode, cx: Float, cy: Float, s: Float, p: Paint) {
        fun X(v: Float) = cx + (v - 12f) * s
        fun Y(v: Float) = cy + (v - 12f) * s
        val eye = Path().apply {
            moveTo(X(2f), Y(12f))
            cubicTo(X(6f), Y(5f), X(18f), Y(5f), X(22f), Y(12f))
            cubicTo(X(18f), Y(19f), X(6f), Y(19f), X(2f), Y(12f)); close()
        }
        when (m) {
            UiMode.ON, UiMode.OFF -> {
                c.drawPath(eye, p)
                c.drawCircle(cx, cy, 3.2f * s, p)
                if (m == UiMode.OFF) c.drawLine(X(4f), Y(20f), X(20f), Y(4f), p)
            }
            UiMode.TRACKING -> {
                c.drawPath(eye, p)
                c.drawPath(Path().apply {
                    moveTo(X(9f), Y(15f)); cubicTo(X(9f), Y(9f), X(13f), Y(8f), X(16f), Y(8f))
                    cubicTo(X(16f), Y(12f), X(14f), Y(15f), X(9f), Y(15f)); close()
                }, p)
            }
            UiMode.NINTENDO -> {
                c.drawCircle(X(12f), Y(5f), 3f * s, p); c.drawCircle(X(12f), Y(19f), 3f * s, p)
                c.drawCircle(X(5f), Y(12f), 3f * s, p); c.drawCircle(X(19f), Y(12f), 3f * s, p)
            }
            UiMode.DYNAMIC -> {
                c.drawRoundRect(RectF(X(3f), Y(3f), X(21f), Y(11f)), 2f, 2f, p)
                val lower = RectF(X(5f), Y(14f), X(19f), Y(21f))
                c.drawRoundRect(lower, 2f, 2f, p)
                val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.color }
                c.drawRoundRect(RectF(lower.left + 5f, lower.top + 4f, lower.right - 5f, lower.bottom - 4f), 1f, 1f, fill)
            }
        }
    }
}

/**
 * OUR OWN ADD-ON TEXTURES FOR THE APP (the tiles' pictures): WowCompanion/Media/<name>.tga ships in the APK's assets (the add-on set the
 * app installs), drawn by our own tools (tools/make_hud_rim.py, make_compass_band.py, make_world_markers.py), so they are ours to show.
 * Android cannot decode TGA: a small reader for what those tools write (uncompressed 32-bit BGRA, type 2, either row order). Decoded
 * once and kept while Settings is the room on show; at Android's running-low trims and MemWatch's relief they go (PreviewStage's rule).
 */
object MediaArt {
    private const val DIR = "companion/addons/WowCompanion/Media/"
    private val cache = HashMap<String, Bitmap>()
    private val missing = HashSet<String>()

    init {
        // Light 8 (LIGHT8_PLAN.md change 1): counted on the 8 GB Thors' memory line ("mediaart"); registered only
        MemWatch.source8("mediaart") { bytes() }
        MemWatch.onTrim("tilepics") { level ->
            if (!PreviewStage.cacheLetsGo(level, MemWatch.room) || cache.isEmpty()) 0L
            else cache.values.sumOf { it.byteCount.toLong() }.also { cache.clear(); missing.clear() }
        }
    }

    /** [name]'s texture, or null (no context, no such file, not a TGA this reader knows) */
    @Synchronized fun get(ctx: Context?, name: String): Bitmap? {
        cache[name]?.let { return it }
        if (ctx == null || name in missing) return null
        val b = runCatching { ctx.assets.open("$DIR$name.tga").use { decode(it.readBytes()) } }.getOrNull()
        if (b == null) missing.add(name) else cache[name] = b
        return b
    }

    /** the bytes held now (tests) */
    @Synchronized fun bytes(): Long = cache.values.sumOf { it.byteCount.toLong() }
    /** tests: let every picture go, as a trim does */
    @Synchronized fun clear() { cache.clear(); missing.clear() }

    /** an uncompressed 32-bit TGA (image type 2) as ARGB; null for anything else */
    fun decode(d: ByteArray): Bitmap? {
        val px = Tga.pixels(d) ?: return null
        return Bitmap.createBitmap(px.third, px.first, px.second, Bitmap.Config.ARGB_8888)
    }
}

/** THE TGA READER, pure (the off-device tests read our own Media files with it): what our tools write, uncompressed 32-bit BGRA */
object Tga {
    /** (width, height, ARGB pixels top row first); null for anything else (a colour map, RLE, another depth, short data) */
    fun pixels(d: ByteArray): Triple<Int, Int, IntArray>? {
        if (d.size < 18) return null
        fun u8(i: Int) = d[i].toInt() and 0xFF
        val idLen = u8(0); val cmap = u8(1); val type = u8(2)
        val w = u8(12) or (u8(13) shl 8); val h = u8(14) or (u8(15) shl 8)
        val bpp = u8(16); val desc = u8(17)
        if (cmap != 0 || type != 2 || bpp != 32 || w <= 0 || h <= 0) return null
        val start = 18 + idLen
        if (d.size < start + w * h * 4) return null
        val topFirst = (desc and 0x20) != 0
        val out = IntArray(w * h)
        for (row in 0 until h) {
            val y = if (topFirst) row else h - 1 - row
            var o = start + row * w * 4
            for (x in 0 until w) {
                val b = u8(o); val g = u8(o + 1); val r = u8(o + 2); val a = u8(o + 3)
                out[y * w + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                o += 4
            }
        }
        return Triple(w, h, out)
    }
}
