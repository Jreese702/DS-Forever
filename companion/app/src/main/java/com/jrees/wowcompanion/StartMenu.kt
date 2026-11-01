package com.jrees.wowcompanion

import android.graphics.Canvas
import android.graphics.RectF

/**
 * THE MENU (the user 2026-11-01), on the waiting screen ([StartView]) and the chooser ([ChooserPanel]): Auto login's tick and Change login,
 * Forget login, Stream settings or Restart game, Report issue, Switch mode (not on the chooser), the controller's two settings, Exit game; the Menu button turned
 * into an X, B or a touch outside shuts it. The host fills in the actions; one app, one menu. Main thread.
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

    /** the panel over [w] x [h]; [menuButton] = where the Menu button is, the X now; [hits] gets what a touch can press, [pressed] the id held down */
    fun draw(c: Canvas, w: Int, h: Int, pressed: Int, hits: ArrayList<Pair<RectF, Int>>, menuButton: RectF, switchMode: Boolean) {
        hits.clear()
        val bh = StartView.BUTTON_H; val gap = StartView.GAP
        val height = PAD * 2 + bh * 5 + gap * 4
        val left = (w - WIDTH) / 2f
        val top = (h - height) / 2f
        val panel = RectF(left, top, left + WIDTH, top + height)
        UiKit.module(c, panel)
        val inL = panel.left + PAD; val inR = panel.right - PAD
        val mid = (inL + inR) / 2f
        fun rowTop(i: Int) = panel.top + PAD + i * (bh + gap)
        fun cell(i: Int, right: Boolean) = RectF(if (right) mid + gap / 2f else inL, rowTop(i), if (right) inR else mid - gap / 2f, rowTop(i) + bh)
        fun put(r: RectF, word: String, id: Int, on: Boolean) {
            if (!on) { UiKit.button(c, r, word, UiKit.Button.DISABLED); return }
            UiKit.button(c, r, word, if (pressed == id) UiKit.Button.DOWN else UiKit.Button.UP)
            hits += r to id
        }
        UiKit.button(c, menuButton, "X", StartView.GLYPH_B, if (pressed == CLOSE) UiKit.Button.DOWN else UiKit.Button.UP)
        hits += RectF(menuButton) to CLOSE
        val auto = cell(0, false)
        UiKit.check(c, auto.left + CHECK / 2f, auto.centerY(), CHECK, autoLogin())
        val words = UiKit.bodyPaint(StartView.LINE_SIZE)
        UiKit.outlined(c, "Auto login", (auto.left + CHECK + auto.right) / 2f, auto.centerY() - (words.ascent() + words.descent()) / 2f, StartView.LINE_SIZE, Theme.text)
        hits += auto to AUTO
        put(cell(0, true), "Change login", CHANGE, true)
        val armed = android.os.SystemClock.uptimeMillis() - forgetArmedAt < FORGET_ARM_MS
        put(cell(1, false), if (armed) "Tap again" else "Forget login", FORGET, canForget())
        if (SetupBridge.openStreamSettings != null) put(cell(1, true), "Stream settings", STREAM, true)
        else put(cell(1, true), "Restart game", RESTART, canRestart())
        if (switchMode) {
            put(cell(2, false), "Report issue", REPORT, true)
            put(cell(2, true), "Switch mode", SWITCH, canSwitch())
        } else put(RectF(inL, rowTop(2), inR, rowTop(2) + bh), "Report issue", REPORT, true)
        // the Settings room's two controller rows, a press steps to the next choice
        put(cell(3, false), "ABXY: ${CastSettings.abxyLocation.label}", ABXY, true)
        put(cell(3, true), "Glyphs: ${CastSettings.buttonLayout.label}", GLYPHS, true)
        put(RectF(inL, rowTop(4), inR, rowTop(4) + bh), "Exit game", EXIT, canExit())
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
}
