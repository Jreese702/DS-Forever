package com.jrees.wowcompanion

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader

/**
 * THE SCENE behind the start screen and behind a room that waits for the game: the game's OWN
 * loading pictures for WoW: Forever, each in its one colour (user picks 2026-09-29: "a wow
 * forever colorless background setting", "3, as the game tints them", "The one where my
 * character is").
 *
 * The pictures are the game's art. They are read from the device's kit folder
 * (`ui/kit/scene_<fileDataID>.png`, cut by `extract_kit.py` from the user's own client) and are
 * NEVER packed into the app. Without them the ground is the companion's plain dark.
 */
object Scene {

    /**
     * The game's own tie of a map to its loading picture (Map.db2's loading screen, then
     * LoadingScreens.db2's picture), for the eight pictures this client holds. A map is a
     * continent or an instance: 0 Eastern Kingdoms, 1 Kalimdor, 2997 Darkspear Islands ...
     */
    private val byMap = mapOf(
        0 to 7963776, 269 to 7963776, 2720 to 7963776, 2853 to 7963776, 2868 to 7963776, 2980 to 7963776,
        3021 to 7963776, 3109 to 7963776,
        1 to 7963779, 2806 to 7963779, 2817 to 7963779,
        2991 to 7950679, 2959 to 7963775, 2998 to 7963777, 3065 to 7963781, 2999 to 7963782, 2997 to 8423398,
    )
    /** every picture of the kit, for a preview that wants to show them all */
    val all = listOf(7963776, 7963779, 7950679, 7963775, 7963777, 7963781, 7963782, 8423398)

    /** the picture shown while the map is not known, or has none of its own here: the last one that was shown; first the Eastern Kingdoms' */
    private var last = 7963776

    /**
     * Where the character stands: the position document's `inst` (the continent's or the
     * instance's map). A map whose own picture this client lacks (the old dungeons) keeps the
     * picture of where the character came from.
     */
    fun note(map: Int) {
        byMap[map]?.let { last = it }
    }

    /** the picture's fileDataID that [draw] shows now */
    fun picture(): Int = last

    private val pArt = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        // turned down, so that words and gold stand on it
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(0.62f, 0.62f, 0.62f, 1f) })
    }
    private val pShade = Paint()

    /**
     * The scene over [r], cut to it (the picture's middle, never stretched), darker towards its
     * head and much darker towards its foot, where words stand. [picture] = one of [all], or 0
     * for the one of where the character is.
     */
    fun draw(c: Canvas, r: RectF, picture: Int = 0) {
        val b = UiKit.tex("scene_" + (if (picture != 0) picture else last))
        if (b == null) {
            pShade.shader = null; pShade.color = Theme.ink
            c.drawRect(r, pShade)
            return
        }
        // cover: the picture keeps its shape and fills the rectangle
        val k = maxOf(r.width() / b.width, r.height() / b.height)
        val sw = (r.width() / k).toInt().coerceAtMost(b.width)
        val sh = (r.height() / k).toInt().coerceAtMost(b.height)
        val sx = (b.width - sw) / 2
        val sy = (b.height - sh) / 2
        c.drawBitmap(b, Rect(sx, sy, sx + sw, sy + sh), r, pArt)
        val head = minOf(200f, r.height() * 0.2f)
        pShade.shader = LinearGradient(0f, r.top, 0f, r.top + head, Color.argb(110, 6, 5, 4), Color.argb(0, 6, 5, 4), Shader.TileMode.CLAMP)
        c.drawRect(r.left, r.top, r.right, r.top + head, pShade)
        val foot = minOf(400f, r.height() * 0.4f)
        pShade.shader = LinearGradient(0f, r.bottom - foot, 0f, r.bottom, Color.argb(0, 6, 5, 4), Color.argb(205, 6, 5, 4), Shader.TileMode.CLAMP)
        c.drawRect(r.left, r.bottom - foot, r.right, r.bottom, pShade)
        pShade.shader = null
    }
}
