package com.jrees.wowcompanion

import android.graphics.Canvas
import android.graphics.RectF

/**
 * THE MENU (the user 2026-11-01), on the waiting screen ([StartView]) and the chooser ([ChooserPanel]): Auto login's tick and Change login,
 * Forget login, Report issue, the controller's two settings; on the waiting screen also Stream settings or Restart game, Switch mode and
 * Exit game. The Menu button turned into an X, B or a touch outside shuts it. The host fills in the actions; one app, one menu. Main thread.
 */
object StartMenu {
    var open = false
    var onReport: (() -> Unit)? = null
    var autoLogin: () -> Boolean = { true }
    var onAutoLogin: ((Boolean) -> Unit)? = null
    var onChangeLogin: (() -> Unit)? = null
    /** Exit game answers only while a game or a stream runs */
    var canExit: () -> Boolean = { false }
    var onExit: (() -> Unit)? = null
    var canForget: () -> Boolean = { false }
    var onForgetLogin: (() -> Unit)? = null
    var canRestart: () -> Boolean = { false }
    var onRestart: (() -> Unit)? = null
    var canSwitch: () -> Boolean = { false }
    var onSwitch: (() -> Unit)? = null
    private var forgetArmedAt = -FORGET_ARM_MS

    /**
     * the panel over [w] x [h]; [menuButton] = where the Menu button is, the X now; [hits] gets what a touch can press, [pressed] the id held
     * down. [game] = the waiting screen, with what acts on a running game or mode (Stream settings or Restart, Switch mode, Exit game)
     */
    fun draw(c: Canvas, w: Int, h: Int, pressed: Int, hits: ArrayList<Pair<RectF, Int>>, menuButton: RectF, game: Boolean) {
        hits.clear()
        val bh = StartView.BUTTON_H; val gap = StartView.GAP
        val rows = if (game) 4 else 2
        val height = PAD * 2 + bh * rows + CONTROLLER_H + gap * rows
        val left = (w - WIDTH) / 2f
        val top = (h - height) / 2f
        val panel = RectF(left, top, left + WIDTH, top + height)
        UiKit.module(c, panel)
        val inL = panel.left + PAD; val inR = panel.right - PAD
        val mid = (inL + inR) / 2f
        var y = panel.top + PAD
        fun half(right: Boolean, h2: Float = bh) = RectF(if (right) mid + gap / 2f else inL, y, if (right) inR else mid - gap / 2f, y + h2)
        fun down(id: Int) = if (pressed == id) UiKit.Button.DOWN else UiKit.Button.UP
        fun put(r: RectF, word: String, id: Int, on: Boolean) {
            if (!on) { UiKit.button(c, r, word, UiKit.Button.DISABLED); return }
            UiKit.button(c, r, word, down(id))
            hits += r to id
        }
        UiKit.button(c, menuButton, "X", StartView.GLYPH_B, down(CLOSE))
        hits += RectF(menuButton) to CLOSE
        val auto = half(false)
        UiKit.check(c, auto.left + CHECK / 2f, auto.centerY(), CHECK, autoLogin())
        val words = UiKit.bodyPaint(StartView.LINE_SIZE)
        UiKit.outlined(c, "Auto login", (auto.left + CHECK + auto.right) / 2f, auto.centerY() - (words.ascent() + words.descent()) / 2f, StartView.LINE_SIZE, Theme.text)
        hits += auto to AUTO
        put(half(true), "Change login", CHANGE, true)
        y += bh + gap
        val armed = android.os.SystemClock.uptimeMillis() - forgetArmedAt < FORGET_ARM_MS
        put(half(false), if (armed) "Tap again" else "Forget login", FORGET, canForget())
        if (game) {
            if (SetupBridge.openStreamSettings != null) put(half(true), "Stream settings", STREAM, true)
            else put(half(true), "Restart game", RESTART, canRestart())
            y += bh + gap
            put(half(false), "Report issue", REPORT, true)
            put(half(true), "Switch mode", SWITCH, canSwitch())
        } else put(half(true), "Report issue", REPORT, true)
        y += bh + gap
        controller(c, half(false, CONTROLLER_H), "ABXY", CastSettings.abxyLocation.label, ABXY, pressed, hits) { r -> faceDiamond(c, r) }
        controller(c, half(true, CONTROLLER_H), "Glyphs", CastSettings.buttonLayout.label, GLYPHS, pressed, hits) { r -> glyphRow(c, r) }
        y += CONTROLLER_H + gap
        if (game) put(RectF(inL, y, inR, y + bh), "Exit game", EXIT, canExit())
    }

    /** a taller button: its two words on the left, its picture on the right, the words saying what a press steps through */
    private fun controller(c: Canvas, r: RectF, title: String, value: String, id: Int, pressed: Int, hits: ArrayList<Pair<RectF, Int>>, picture: (RectF) -> Unit) {
        UiKit.button(c, r, "", if (pressed == id) UiKit.Button.DOWN else UiKit.Button.UP)
        hits += r to id
        val text = RectF(r.left + 20f, r.top, r.left + r.width() * 0.48f, r.bottom)
        UiKit.outlined(c, title, text.centerX(), r.centerY() - 12f, StartView.LINE_SIZE, Theme.text)
        UiKit.outlined(c, value, text.centerX(), r.centerY() + 34f, StartView.LINE_MIN + 4f, Theme.text)
        picture(RectF(text.right, r.top, r.right - 16f, r.bottom))
    }

    /** the four face buttons where they sit: Nintendo's X top and A right, Xbox's Y top and B right */
    private fun faceDiamond(c: Canvas, r: RectF) {
        val cx = r.centerX(); val cy = r.centerY()
        val nintendo = CastSettings.abxyLocation == CastSettings.AbxyLocation.NINTENDO
        UiKit.pad(c, if (nintendo) "rev_x" else "rev_y", cx, cy - ARM, FACE)
        UiKit.pad(c, if (nintendo) "rev_a" else "rev_b", cx + ARM, cy, FACE)
        UiKit.pad(c, if (nintendo) "rev_b" else "rev_a", cx, cy + ARM, FACE)
        UiKit.pad(c, if (nintendo) "rev_y" else "rev_x", cx - ARM, cy, FACE)
    }

    /** the chosen set's four pictures */
    private fun glyphRow(c: Canvas, r: RectF) {
        val cx = r.centerX(); val cy = r.centerY()
        UiKit.pad(c, "rev_a", cx - ARM * 0.9f, cy - ARM * 0.7f, FACE)
        UiKit.pad(c, "rev_b", cx + ARM * 0.9f, cy - ARM * 0.7f, FACE)
        UiKit.pad(c, "rev_x", cx - ARM * 0.9f, cy + ARM * 0.7f, FACE)
        UiKit.pad(c, "rev_y", cx + ARM * 0.9f, cy + ARM * 0.7f, FACE)
    }

    private fun <T> next(all: Array<T>, now: T): T = all[(all.indexOf(now) + 1) % all.size]

    fun press(id: Int) {
        when (id) {
            CLOSE -> open = false
            ABXY -> CastSettings.abxyLocation = next(CastSettings.AbxyLocation.values(), CastSettings.abxyLocation)
            GLYPHS -> CastSettings.buttonLayout = next(CastSettings.ButtonLayout.values(), CastSettings.buttonLayout)
            AUTO -> onAutoLogin?.invoke(!autoLogin())
            FORGET -> {
                val at = android.os.SystemClock.uptimeMillis()
                if (at - forgetArmedAt < FORGET_ARM_MS) { forgetArmedAt = -FORGET_ARM_MS; onForgetLogin?.invoke() } else forgetArmedAt = at
            }
            CHANGE -> { open = false; onChangeLogin?.invoke() }
            STREAM -> { open = false; SetupBridge.openStreamSettings?.invoke() }
            RESTART -> { open = false; onRestart?.invoke() }
            SWITCH -> { open = false; onSwitch?.invoke() }
            REPORT -> { open = false; onReport?.invoke() }
            EXIT -> { open = false; onExit?.invoke() }
        }
    }

    private const val AUTO = 4
    private const val CHANGE = 5
    private const val REPORT = 6
    private const val EXIT = 7
    private const val FORGET = 8
    private const val STREAM = 9
    private const val RESTART = 10
    private const val SWITCH = 11
    private const val CLOSE = 12
    private const val ABXY = 13
    private const val GLYPHS = 14
    private const val FORGET_ARM_MS = 4000L
    private const val WIDTH = 1000f
    private const val PAD = 32f
    private const val CHECK = 76f
    private const val CONTROLLER_H = 170f
    private const val FACE = 50f
    private const val ARM = 46f
}
