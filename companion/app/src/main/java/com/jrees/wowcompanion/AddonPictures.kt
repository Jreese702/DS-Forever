package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader

/**
 * SETTINGS > ADD-ONS' CARD PICTURES (the beautify pass, 2026-11-17; the user: "I REALLY REALLY love what you did with the top screen
 * settings menu with the pictures and want to do something similar to the other menus"): one picture per third-party add-on card, in
 * the Top screen tiles' language ([TilePictures]): drawn in the same 216 x 108 space, scaled into the tile's well or the band's 438 x
 * 220 picture, clipped to its rounded corners.
 *
 * What a picture is made of, in TilePictures' order: kit pieces (UiKit.tex), the game's own icons (icons.pak by fileDataID), a real
 * minimap tile ([MinimapTiles], the harvested art), then our own drawing. Every picture has a no-kit drawing, so a phone with no art
 * yet still gets one. Sample fills only: no screen reading, no game values (the ToS ruling).
 *
 *  - Polestar: the SAME picture as its Top screen tile ([TilePictures], PS_ID; a drawing with no kit piece, so its no-kit picture is
 *    the same), so the two read as one thing (the design's C3).
 *  - Spoken: the game's parchment page, ruled ink lines, a speaker with three sound arcs in the theme's gold.
 *  - Manners: three buffs (Power Word: Fortitude, Arcane Intellect, Mark of the Wild) over a party strip, a small heart.
 *  - QuestTrail: a dotted gold trail over a minimap tile, your arrow at its start, the quest's "!" at its end.
 */
object AddonPictures {
    const val W = TilePictures.W
    const val H = TilePictures.H

    /** the buffs Manners hands back (the game's icon fileDataIDs: Spell_Holy_WordFortitude, Spell_Holy_MagicalSentry,
     *  Spell_Nature_Regeneration) */
    val MANNERS_ICONS = listOf(135987, 135932, 136078)
    /** QuestTrail's map: a sample minimap tile (Eastern Kingdoms, Elwynn Forest round Goldshire); the drawn fields without it */
    const val MAP_CONT = 0
    const val MAP_COL = 32
    const val MAP_ROW = 48

    private val pF = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pS = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val pB = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    /** [a]'s picture into [r] (its own rounded clip; the canvas left as it was). [kit] false = the no-kit drawing (the shots' *_nokit).
     *  [ready] = a picture piece that loads off the UI thread (the map tile) landed: draw again */
    fun draw(c: Canvas, ctx: Context?, a: ThirdPartyAddons.Addon, r: RectF, kit: Boolean = true, ready: () -> Unit = {}) {
        if (a.id == ThirdPartyAddons.POLESTAR.id) { TilePictures.draw(c, ctx, GameUiTiles.PS_ID, r, TilePictures.Look()); return }
        val save = c.save()
        val rad = 8f * r.width() / W
        path.reset(); path.addRoundRect(r, rad, rad, Path.Direction.CW)
        c.clipPath(path)
        val k = minOf(r.width() / W, r.height() / H)
        c.translate(r.centerX() - W * k / 2f, r.centerY() - H * k / 2f)
        c.scale(k, k)
        world(c)
        runCatching {
            when (a.id) {
                ThirdPartyAddons.SPOKEN.id -> spoken(c, kit)
                ThirdPartyAddons.MANNERS.id -> manners(c, kit)
                ThirdPartyAddons.QUESTTRAIL.id -> questTrail(c, kit, ready)
                else -> glyph(c)
            }
        }
        c.restoreToCount(save)
    }

    // ---------------------------------------------------------------- the ground behind (TilePictures' world: the same fills)

    private fun world(c: Canvas) {
        pF.shader = LinearGradient(0f, 0f, 0f, H, intArrayOf(Color.rgb(36, 50, 62), Color.rgb(30, 40, 34), Color.rgb(20, 26, 18)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W, H, pF)
        vignette(c)
    }

    private fun vignette(c: Canvas) {
        pF.shader = RadialGradient(W / 2f, H * 0.55f, W * 0.62f, intArrayOf(Color.argb(0, 0, 0, 0), Color.argb(120, 0, 0, 0)),
            floatArrayOf(0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W, H, pF)
        pF.shader = null
    }

    // ---------------------------------------------------------------- Spoken

    /** the game's parchment page (kit `parchment`; drawn tan without it), ruled ink lines as a quest's text, a speaker with three arcs */
    private fun spoken(c: Canvas, kit: Boolean) {
        val page = RectF(22f, 12f, 128f, 98f)
        // the page's shadow on the world
        pF.color = Color.argb(110, 0, 0, 0); c.drawRoundRect(RectF(page.left + 3f, page.top + 4f, page.right + 3f, page.bottom + 4f), 3f, 3f, pF)
        val parch = if (kit) UiKit.tex("parchment") else null
        if (parch != null) c.drawBitmap(parch, null, page, pB)
        else {
            pF.shader = LinearGradient(0f, page.top, 0f, page.bottom, Color.rgb(222, 196, 146), Color.rgb(196, 164, 112), Shader.TileMode.CLAMP)
            c.drawRoundRect(page, 2f, 2f, pF); pF.shader = null
            pS.color = Color.rgb(120, 90, 50); pS.strokeWidth = 1.2f; c.drawRoundRect(page, 2f, 2f, pS)
        }
        // the title line, then the body's ruled lines (ink, of uneven lengths, as a paragraph)
        pS.color = Color.rgb(74, 46, 18); pS.strokeWidth = 3.2f
        c.drawLine(page.left + 12f, page.top + 15f, page.left + 62f, page.top + 15f, pS)
        pS.strokeWidth = 2f; pS.color = Color.argb(200, 60, 40, 20)
        val ends = listOf(0.92f, 0.84f, 0.95f, 0.70f, 0.88f, 0.52f)
        for ((i, e) in ends.withIndex()) {
            val y = page.top + 29f + i * 9.5f
            c.drawLine(page.left + 12f, y, page.left + 12f + (page.width() - 24f) * e, y, pS)
        }
        // the speaker: a box and its cone, three arcs going out (the theme's gold)
        val cx = 152f; val cy = 55f
        pF.color = Theme.gold
        path.reset()
        path.moveTo(cx - 10f, cy - 7f); path.lineTo(cx - 3f, cy - 7f); path.lineTo(cx + 8f, cy - 17f); path.lineTo(cx + 8f, cy + 17f)
        path.lineTo(cx - 3f, cy + 7f); path.lineTo(cx - 10f, cy + 7f); path.close()
        c.drawPath(path, pF)
        pS.color = Theme.gold
        for (i in 0..2) {
            val rr = 14f + i * 11f
            pS.strokeWidth = 3.4f - i * 0.5f
            pS.alpha = 255 - i * 55
            c.drawArc(RectF(cx + 8f - rr, cy - rr, cx + 8f + rr, cy + rr), -48f, 96f, false, pS)
        }
        pS.alpha = 255
    }

    // ---------------------------------------------------------------- Manners

    /** three buffs (the game's icons, gold-rimmed) over a party strip of four, a small heart between: buffs handed back */
    private fun manners(c: Canvas, kit: Boolean) {
        val s = 34f; val gap = 12f
        val x0 = (W - 3 * s - 2 * gap) / 2f
        val fallback = listOf(Color.rgb(230, 230, 250), Color.rgb(110, 150, 240), Color.rgb(200, 120, 230))
        for (i in 0..2) {
            val r = RectF(x0 + i * (s + gap), 10f, x0 + i * (s + gap) + s, 10f + s)
            pF.color = Color.argb(150, 0, 0, 0); c.drawRect(RectF(r.left + 2f, r.top + 3f, r.right + 2f, r.bottom + 3f), pF)
            val icon = if (kit) Icons.get(MANNERS_ICONS[i]) else null
            if (icon != null) c.drawBitmap(icon, null, r, pB)
            else {
                pF.shader = LinearGradient(0f, r.top, 0f, r.bottom, fallback[i], Color.rgb(30, 30, 40), Shader.TileMode.CLAMP)
                c.drawRect(r, pF); pF.shader = null
                spark(c, r.centerX(), r.centerY(), 8f)
            }
            pS.color = Color.rgb(200, 170, 90); pS.strokeWidth = 1.6f; c.drawRect(r, pS)
        }
        // the heart: the hand-back, between the buffs and the party
        heart(c, W / 2f, 56f, 7f, Color.rgb(226, 72, 82))
        // the party strip: four members, a class icon and a green bar each
        val cls = listOf("PRIEST", "MAGE", "DRUID", "WARRIOR")
        val cols = listOf(Color.WHITE, Color.rgb(64, 199, 235), Color.rgb(255, 124, 10), Color.rgb(199, 156, 110))
        val mw = 44f; val mg = 6f
        val mx0 = (W - 4 * mw - 3 * mg) / 2f
        for (i in 0..3) {
            val m = RectF(mx0 + i * (mw + mg), 70f, mx0 + i * (mw + mg) + mw, 96f)
            pF.color = Color.argb(200, 12, 12, 14); c.drawRoundRect(m, 3f, 3f, pF)
            val ic = RectF(m.left + 3f, m.top + 4f, m.left + 21f, m.top + 22f)
            val b = if (kit) UiKit.tex("classicon_${cls[i].lowercase()}") else null
            if (b != null) c.drawBitmap(b, null, ic, pB) else { pF.color = cols[i]; c.drawRoundRect(ic, 2f, 2f, pF) }
            val bar = RectF(ic.right + 3f, m.top + 8f, m.right - 3f, m.top + 18f)
            pF.color = Color.rgb(20, 40, 20); c.drawRect(bar, pF)
            pF.color = Color.rgb(40, 200, 60); c.drawRect(RectF(bar.left, bar.top, bar.left + bar.width() * listOf(0.9f, 0.7f, 1f, 0.55f)[i], bar.bottom), pF)
            pS.color = Color.argb(200, 120, 104, 80); pS.strokeWidth = 1f; c.drawRoundRect(m, 3f, 3f, pS)
        }
    }

    // ---------------------------------------------------------------- QuestTrail

    /** a minimap tile (the harvest's own, decoded small off the UI thread; drawn fields without it), a dotted gold trail across it,
     *  your arrow at its start and the quest's "!" at its end */
    private fun questTrail(c: Canvas, kit: Boolean, ready: () -> Unit) {
        val tile = if (kit) MinimapTiles.get(MAP_CONT, MAP_COL, MAP_ROW, 4, ready) else null
        if (tile != null) {
            // the tile's middle band (2:1, the well's shape)
            val th = tile.height * 0.5f
            val src = android.graphics.Rect(0, (tile.height * 0.25f).toInt(), tile.width, (tile.height * 0.25f + th).toInt())
            c.drawBitmap(tile, src, RectF(0f, 0f, W, H), pB)
        } else fields(c)
        // darker toward the edges, so the gold reads
        pF.color = Color.argb(60, 0, 0, 0); c.drawRect(0f, 0f, W, H, pF)
        vignette(c)
        // the trail: a curve from your place to the quest, dotted, its shadow under it
        path.reset()
        path.moveTo(30f, 84f)
        path.cubicTo(66f, 92f, 72f, 46f, 104f, 54f)
        path.cubicTo(128f, 61f, 130f, 38f, 150f, 40f)
        pS.pathEffect = DashPathEffect(floatArrayOf(0.1f, 9f), 0f)
        pS.strokeWidth = 6f; pS.color = Color.argb(150, 0, 0, 0)
        c.save(); c.translate(1f, 1.5f); c.drawPath(path, pS); c.restore()
        pS.strokeWidth = 5f; pS.color = Theme.gold
        c.drawPath(path, pS)
        pS.pathEffect = null
        // your arrow (the kit's minimap arrow, or ours)
        val arrow = if (kit) UiKit.tex("mm_player") else null
        if (arrow != null) c.drawBitmap(arrow, null, RectF(20f, 74f, 40f, 94f), pB)
        else {
            pF.color = Color.WHITE
            path.reset(); path.moveTo(36f, 79f); path.lineTo(22f, 84f); path.lineTo(30f, 86f); path.lineTo(31f, 93f); path.close()
            c.drawPath(path, pF)
        }
        // the quest's "!" at the trail's end, on a dark disc, clear of the badge's corner (the game's quest-giver gold; drawn, so it
        // reads at the tile's size)
        val qx = 160f; val qy = 36f
        pF.color = Color.argb(200, 10, 10, 12); c.drawCircle(qx, qy, 12f, pF)
        pS.color = Color.argb(220, 120, 96, 40); pS.strokeWidth = 1.5f; c.drawCircle(qx, qy, 12f, pS)
        pF.color = Color.rgb(255, 210, 0)
        c.drawRoundRect(RectF(qx - 2.6f, qy - 8.5f, qx + 2.6f, qy + 3f), 2f, 2f, pF)
        c.drawCircle(qx, qy + 7f, 2.6f, pF)
    }

    /** the drawn map: fields, a road and a lake (a fixed sample, TilePictures' minimap's colours) */
    private fun fields(c: Canvas) {
        pF.color = Color.rgb(62, 82, 40); c.drawRect(0f, 0f, W, H, pF)
        pF.color = Color.rgb(78, 98, 48); c.drawRect(0f, 0f, 90f, 50f, pF)
        pF.color = Color.rgb(54, 72, 34); c.drawRect(120f, 56f, W, H, pF)
        pF.color = Color.rgb(96, 104, 60); c.drawRect(90f, 0f, 160f, 40f, pF)
        pF.color = Color.rgb(48, 78, 110); c.drawOval(RectF(130f, 66f, 196f, 100f), pF)
        pS.color = Color.rgb(140, 120, 84); pS.strokeWidth = 4f
        path.reset(); path.moveTo(0f, 60f); path.cubicTo(60f, 54f, 100f, 70f, W, 46f)
        c.drawPath(path, pS)
    }

    // ---------------------------------------------------------------- the rest

    /** a card with no picture of its own: a plug sign */
    private fun glyph(c: Canvas) {
        pS.color = Theme.gold; pS.strokeWidth = 3f
        c.drawRoundRect(RectF(88f, 34f, 128f, 70f), 6f, 6f, pS)
        c.drawLine(98f, 34f, 98f, 22f, pS); c.drawLine(118f, 34f, 118f, 22f, pS); c.drawLine(108f, 70f, 108f, 88f, pS)
    }

    private fun spark(c: Canvas, x: Float, y: Float, r: Float) {
        pS.color = Color.argb(220, 255, 255, 255); pS.strokeWidth = 2f
        c.drawLine(x - r, y, x + r, y, pS); c.drawLine(x, y - r, x, y + r, pS)
    }

    private fun heart(c: Canvas, x: Float, y: Float, s: Float, col: Int) {
        pF.color = col
        path.reset()
        path.moveTo(x, y + s)
        path.cubicTo(x - s * 1.6f, y - s * 0.1f, x - s * 0.9f, y - s * 1.3f, x, y - s * 0.45f)
        path.cubicTo(x + s * 0.9f, y - s * 1.3f, x + s * 1.6f, y - s * 0.1f, x, y + s)
        path.close()
        c.drawPath(path, pF)
    }
}
