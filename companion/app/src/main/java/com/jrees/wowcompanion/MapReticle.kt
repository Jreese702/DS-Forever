package com.jrees.wowcompanion

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF

/**
 * THE GAME'S MAP CURSOR, DRAWN (the Map room's since b107, the user: "I want it to look just like the one that wow forver does, with the
 * golden lines and cross hair"; Home's map controls since 2026-11-17, "it should function just like the map room"). One look for both:
 *  - [lines]: the game's MapCanvasScrollControllerMixin:UpdateGamepadCursorLines - four lines in NORMAL_FONT_COLOR from the reticle's
 *    edge to the visible map's edges, clipped to [clip] (UI Art at our scale, the game's x1.8: 4 px, from half the reticle less 2 px);
 *  - [reticle]: SoftCursor's MetallicSoftCursorPointerTemplate (UI Art's kit map_reticle, the game's x2 art), cropped to its TexCoords
 *    .08-.95, [WorldMapRoomView.RETICLE_PX] wide, a black copy 2 px right under it; while it fades in ([intro] 0..1 over INTRO_MS) it
 *    shrinks from 1.5x (the template's IntroAnim, smoothing IN). No art: the lines alone.
 */
object MapReticle {
    private val pLines = Paint().apply { style = Paint.Style.FILL; color = Color.rgb(255, 209, 0) }
    private val pBmp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val pShadow = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = PorterDuffColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN)
    }
    private val src = Rect()
    private val dst = RectF()

    fun lines(c: Canvas, cx: Float, cy: Float, clip: RectF) {
        if (clip.width() <= 0f || clip.height() <= 0f) return
        val off = WorldMapRoomView.RETICLE_PX / 2f - 2f
        val h = WorldMapRoomView.LINE_PX / 2f
        c.save()
        c.clipRect(clip)
        c.drawRect(clip.left, cy - h, cx - off, cy + h, pLines)
        c.drawRect(cx + off, cy - h, clip.right, cy + h, pLines)
        c.drawRect(cx - h, clip.top, cx + h, cy - off, pLines)
        c.drawRect(cx - h, cy + off, cx + h, clip.bottom, pLines)
        c.restore()
    }

    /** the reticle at ([cx], [cy]); [intro] 0..1 = how far its fade-in is */
    fun reticle(c: Canvas, cx: Float, cy: Float, intro: Float = 1f) {
        val b = UiKit.tex("map_reticle") ?: return
        val t = intro.coerceIn(0f, 1f)
        val e = t * t
        val size = WorldMapRoomView.RETICLE_PX * (1.5f - 0.5f * e)
        val a = (255 * e).toInt()
        src.set((b.width * 0.08f).toInt(), (b.height * 0.08f).toInt(), (b.width * 0.95f).toInt(), (b.height * 0.95f).toInt())
        dst.set(cx - size / 2f + 2f, cy - size / 2f, cx + size / 2f + 2f, cy + size / 2f)
        pShadow.alpha = a; c.drawBitmap(b, src, dst, pShadow)
        dst.offset(-2f, 0f)
        pBmp.alpha = a; c.drawBitmap(b, src, dst, pBmp)
    }
}
