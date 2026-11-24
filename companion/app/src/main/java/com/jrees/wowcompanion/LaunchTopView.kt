package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View

/**
 * THE TOP SCREEN WHILE THE GAME LAUNCHES (the user, 2026-11-24: "instead of showing the game native loading screen ... our own loading
 * screen intul the game launches"; picks "Our swirl loading screen", "The game's first real picture", "The swirl across both screens").
 * Over the launcher's game screen from Play ([LaunchLoad.begin]) until the game's first real picture, in place of GameNative's splash:
 * the start-up's reveal ([LoadingScene], the same clock, seed and pictures as the bottom screen: one picture and one swirl across both
 * screens) behind the start-up's framed box, centred - the title, the gold status line (each launch step, terse, with moving dots so it
 * never looks stuck), a gold bar when the step has a progress, and the abort chord in one dim line. At the hand-over it fades out over
 * the game ([LaunchLoad.alpha]); from then on the bottom screen's swirl is its own, smaller ([LoadingScene]'s bottom-alone passes).
 *
 * It takes every touch while it shows (nothing reaches the game under it); the pad's presses are held back from the game by the game
 * screen ([LaunchLoad.holdsPad]); the abort chord is the host's (LaunchLoadingScreen). Redrawn about 30 times a second
 * ([LoadingScene.FRAME_MS]), only while it is attached; [LaunchLoad.tick] runs the timeouts from here.
 */
class LaunchTopView(context: Context) : View(context) {
    private val area = RectF()
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val refresh: () -> Unit = { post { invalidate() } }

    init { contentDescription = "World of Warcraft is starting" }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); LaunchLoad.onChange(refresh) }
    override fun onDetachedFromWindow() { LaunchLoad.offChange(refresh); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        LaunchLoad.tick(now)
        val phase = LaunchLoad.phase
        // the start's own splash (an auto-launch before Play reached the launch) shows the same screen at full strength
        val a = if (phase == LaunchLoad.Phase.IDLE) 1f else LaunchLoad.alpha(now)
        if (alpha != a) alpha = a
        area.set(0f, 0f, width.toFloat(), height.toFloat())
        // fading out = the game is on this screen now: it no longer counts as the scene's top screen (the bottom's swirl goes solo)
        LoadingScene.draw(c, area, top = true, claim = phase != LaunchLoad.Phase.FADING)
        postInvalidateDelayed(LoadingScene.FRAME_MS)
        val step = if (phase == LaunchLoad.Phase.IDLE) LaunchLoad.START else LaunchLoad.line
        val p = if (phase == LaunchLoad.Phase.IDLE) -1f else LaunchLoad.progress
        panel(c, height / 1080f, step, p, LoadingScene.now())
    }

    /** the framed box, centred: title, divider, the gold line (+ dots), the bar, the chord's dim line */
    private fun panel(c: Canvas, k: Float, step: String, progress: Float, t: Float) {
        val w = PANEL_W * k
        var h = TITLE_H * k + LINE_H * k + HINT_H * k + 40f * k
        if (progress >= 0f) h += BAR_H * k
        val box = RectF((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
        pFill.style = Paint.Style.FILL; pFill.color = Color.argb(232, 14, 11, 8)
        c.drawRoundRect(RectF(box.left + 6f * k, box.top + 6f * k, box.right - 6f * k, box.bottom - 6f * k), 10f * k, 10f * k, pFill)
        UiKit.border(c, box, "border_gold", 34f * k)
        val title = UiKit.titlePaint(52f * k).apply { textAlign = Paint.Align.CENTER }
        c.drawText(Tr.t(LoadingTopView.TITLE), box.centerX(), box.top + 92f * k, title)
        UiKit.divider(c, box.left + 70f * k, box.top + 124f * k, box.right - 70f * k, box.top + 124f * k)
        var y = box.top + TITLE_H * k + 34f * k
        // the step translated whole, then the moving dots (never part of the words: one key per step)
        val lp = UiKit.bodyPaint(30f * k, Theme.gold).apply { textAlign = Paint.Align.CENTER }
        c.drawText(LaunchLoad.statusLine(step, t), box.centerX(), y, lp)
        y += LINE_H * k
        if (progress >= 0f) {
            LoadingTopView.bar(c, RectF(box.left + 120f * k, y - 30f * k, box.right - 120f * k, y - 18f * k), progress)
            y += BAR_H * k
        }
        val hp = UiKit.bodyPaint(24f * k, Theme.textDim).apply { textAlign = Paint.Align.CENTER }
        c.drawText(Tr.t(LaunchLoad.ABORT_HINT), box.centerX(), y, hp)
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean = true

    companion object {
        const val PANEL_W = 1000f
        const val TITLE_H = 150f
        const val LINE_H = 56f
        const val BAR_H = 34f
        const val HINT_H = 30f

    }
}
