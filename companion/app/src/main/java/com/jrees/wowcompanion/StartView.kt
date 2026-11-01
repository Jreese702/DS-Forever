package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * THE START SCREEN (user picks 2026-09-29: the game's own scene behind it, "No, one line only"). It covers the rooms and the bar
 * from the app's start until the game has answered, and says in ONE line what it waits for.
 *
 * THE LOADING SWIRL (the user, 2026-10-02: the "DS Forever" title scrapped; then "the swirl alone" as a loading effect, "A, move it to
 * the lower middle above the text"): UI Art's UiKit.swirlLoader, style "travel". REPLACED 2026-10-28 by the loading reveal on both
 * screens ([LoadingScene]: "I love this"), redrawn about 30 times a second. A redraw is asked only from onDraw, so it stops by itself once
 * the screen is gone.
 *
 * NO TOUCH TO CONTINUE (the user, 2026-10-02: "also remove the ability to touch continue"): it leaves only when the game is ready
 * (its first current word) or a reload's new hello. It still takes every touch while it shows, so nothing under it is pressed by
 * accident.
 */
class StartView(context: Context) : View(context) {

    /** the one line: what the app waits for, or what it lacks */
    var line: String = "Waiting for the game."
        set(v) { if (field != v) { field = v; invalidate() } }

    /**
     * OUR OWN BATTLE.NET SIGN-IN (LATER 29; the user, 2026-10-05: "I want the Bnet login to basically show the first time and then after
     * the details are entered, then I want it to have a notmal login button and then like a gear to the left of it which just reopens
     * the login prompts"). Only while the game's login screen is up: FIRST = no saved login, one "Battle.net login" button; SAVED =
     * [gear][Login]. The row sits under the swirl ("On the companion's waiting screen") and goes with the login screen.
     */
    enum class Login { NONE, FIRST, SAVED }

    var login = Login.NONE
        set(v) { if (field != v) { field = v; invalidate() } }

    /** Login greyed: it may not type now (a run, a queue, signed in...). A greyed Login carries no glyph, and then the row has none */
    var loginBusy = false
        set(v) { if (field != v) { field = v; invalidate() } }

    /** FIRST's one button: its words and width (inside the one app: "Save your Battle.net login", the user 2026-10-08) */
    var firstLabel = "Battle.net login"
        set(v) { if (field != v) { field = v; invalidate() } }
    var firstW = FIRST_W
        set(v) { if (field != v) { field = v; invalidate() } }

    /** the Menu (the user 2026-11-01, in place of Report at the bottom left): Auto login's tick and Change login, Report issue, Exit game */
    var menuOpen = false
        set(v) { if (field != v) { field = v; pressed = -1; invalidate() } }
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

    /** a press of the row: true = the gear (touch only) or FIRST's one button (touch or A), false = Login (touch or A) */
    var onLoginButton: ((gear: Boolean) -> Unit)? = null

    private val area = RectF()
    private val hits = ArrayList<Pair<RectF, Int>>()
    private var pressed = -1

    override fun onDraw(c: Canvas) {
        area.set(0f, 0f, width.toFloat(), height.toFloat())
        // THE LOADING REVEAL (the user, 2026-10-28): the scene behind both screens replaces the game's still scene and the small swirl.
        // Every 33 ms, NOT every vsync: on a software GPU (the emulator, ~1 s a frame) a per-vsync redraw starved the main thread and,
        // with a document backlog decoding icons, hung the app (Map's ANR on integ-b40 + mapset; UI Art's measure). 30 fps is plenty
        LoadingScene.draw(c, area, top = false)
        postInvalidateDelayed(FRAME_MS)
        drawRow(c)
        if (menuOpen) drawMenu(c)
    }

    private fun drawMenu(c: Canvas) {
        hits.clear()
        val h = MENU_PAD * 2 + BUTTON_H * 4 + GAP * 3
        val left = (width - MENU_W) / 2f
        val top = (height - h) / 2f
        val panel = RectF(left, top, left + MENU_W, top + h)
        UiKit.module(c, panel)
        val inL = panel.left + MENU_PAD; val inR = panel.right - MENU_PAD
        val mid = (inL + inR) / 2f
        fun rowTop(i: Int) = panel.top + MENU_PAD + i * (BUTTON_H + GAP)
        fun cell(i: Int, right: Boolean) = RectF(if (right) mid + GAP / 2f else inL, rowTop(i), if (right) inR else mid - GAP / 2f, rowTop(i) + BUTTON_H)
        fun put(r: RectF, word: String, id: Int, on: Boolean) {
            if (!on) { UiKit.button(c, r, word, UiKit.Button.DISABLED); return }
            UiKit.button(c, r, word, if (pressed == id) UiKit.Button.DOWN else UiKit.Button.UP)
            hits += r to id
        }
        val auto = cell(0, false)
        UiKit.check(c, auto.left + CHECK / 2f, auto.centerY(), CHECK, autoLogin())
        val words = UiKit.bodyPaint(LINE_SIZE)
        UiKit.outlined(c, "Auto login", (auto.left + CHECK + auto.right) / 2f, auto.centerY() - (words.ascent() + words.descent()) / 2f, LINE_SIZE, Theme.text)
        hits += auto to MENU_AUTO
        put(cell(0, true), "Change login", MENU_CHANGE, true)
        val armed = android.os.SystemClock.uptimeMillis() - forgetArmedAt < FORGET_ARM_MS
        put(cell(1, false), if (armed) "Tap again" else "Forget login", MENU_FORGET, canForget())
        if (SetupBridge.openStreamSettings != null) put(cell(1, true), "Stream settings", MENU_STREAM, true)
        else put(cell(1, true), "Restart game", MENU_RESTART, canRestart())
        put(cell(2, false), "Report issue", MENU_REPORT, true)
        put(cell(2, true), "Switch mode", MENU_SWITCH, canSwitch())
        put(RectF(inL, rowTop(3), inR, rowTop(3) + BUTTON_H), "Exit game", MENU_EXIT, canExit())
    }

    private fun drawRow(c: Canvas) {
        val cx = width / 2f
        val row = login != Login.NONE
        hits.clear()
        // the line in one metal panel, framed by Report and the PC's keyboard (the user, 2026-10-30: "framed between the two buttons in
        // like a fancy looking one part panel"); the keyboard only while a game or stream can take it
        val sideTop = height - ROW_DOWN - BUTTON_H
        val panel = RectF(SIDE_M + SIDE_W + GAP, sideTop, width - SIDE_M - SIDE_W - GAP, sideTop + BUTTON_H)
        UiKit.module(c, panel)
        val words = UiKit.bodyPaint(LINE_SIZE)
        while (words.measureText(line) > panel.width() - LINE_PAD * 2 && words.textSize > LINE_MIN) words.textSize -= 1f
        UiKit.outlined(c, line, cx, panel.centerY() - (words.ascent() + words.descent()) / 2f, words.textSize, Theme.text)
        val report = RectF(SIDE_M, sideTop, SIDE_M + SIDE_W, sideTop + BUTTON_H)
        UiKit.button(c, report, "Menu", if (pressed == CURSOR_REPORT) UiKit.Button.DOWN else UiKit.Button.UP)
        hits += report to CURSOR_REPORT
        if (SetupBridge.openPcKeyboard != null) {
            val keys = RectF(width - SIDE_M - SIDE_W, sideTop, width - SIDE_M, sideTop + BUTTON_H)
            UiKit.button(c, keys, "Keyboard", if (pressed == CURSOR_KEYS) UiKit.Button.DOWN else UiKit.Button.UP)
            hits += keys to CURSOR_KEYS
        }
        if (!row) return
        val top = sideTop - GAP - BUTTON_H
        if (login == Login.FIRST) {
            val r = RectF(cx - firstW / 2f, top, cx + firstW / 2f, top + BUTTON_H)
            // the one button, with the A that presses it (no gold box on this row: the user, 2026-10-05)
            // b-login-a-input: with the game up the row is touch only (no A glyph; A never opens the login form)
            if (LauncherHooks.gameUp()) UiKit.button(c, r, firstLabel, if (pressed == CURSOR_GEAR) UiKit.Button.DOWN else UiKit.Button.UP)
            else UiKit.button(c, r, firstLabel, GLYPH_A, if (pressed == CURSOR_GEAR) UiKit.Button.DOWN else UiKit.Button.UP)
            hits += r to CURSOR_GEAR
            return
        }
        val left = cx - (BUTTON_H + GAP + LOGIN_W) / 2f
        val gear = RectF(left, top, left + BUTTON_H, top + BUTTON_H)
        val go = RectF(gear.right + GAP, top, gear.right + GAP + LOGIN_W, top + BUTTON_H)
        // the gear: touch only, no glyph. Login: the A glyph while A may press it; greyed = no glyph, so the row then shows none
        // (the user, 2026-10-05: "I really hate the highlighted box on the bottom screen" ... "no glyph on the gear. once login goes
        // grey then no glyphs on the bottom")
        UiKit.iconButton(c, gear, "icon_settings", if (pressed == CURSOR_GEAR) UiKit.Button.DOWN else UiKit.Button.UP)
        if (loginBusy) UiKit.button(c, go, "Login", UiKit.Button.DISABLED)
        else if (LauncherHooks.gameUp()) UiKit.button(c, go, "Login", if (pressed == CURSOR_LOGIN) UiKit.Button.DOWN else UiKit.Button.UP)
        else UiKit.button(c, go, "Login", GLYPH_A, if (pressed == CURSOR_LOGIN) UiKit.Button.DOWN else UiKit.Button.UP)
        hits += gear to CURSOR_GEAR
        hits += go to CURSOR_LOGIN
    }

    /**
     * The pad's A while the row shows: Login (or the first-time button); the gear never ("I want A to log in"; no cursor, no box).
     * The game receives the same A: MEASURED on WoW Forever's login screen it does nothing there (19:30, fields and log unchanged), and
     * past it the caller refuses the typing (HomeLogin.State.canType: a realm connection, a sign-in begun...) and Login is greyed with
     * no glyph, so the A stays the game's own (e.g. Enter World). Main thread.
     */
    fun padA() {
        when (login) {
            Login.NONE -> {}
            Login.FIRST -> onLoginButton?.invoke(true)
            Login.SAVED -> if (!loginBusy) onLoginButton?.invoke(false)
        }
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // the row's buttons answer a touch; every other touch is still taken, never passed on, and never sends the screen away
        val hit = hits.firstOrNull { it.first.contains(event.x, event.y) }?.second ?: -1
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressed = hit; invalidate() }
            MotionEvent.ACTION_UP -> {
                val was = pressed; pressed = -1; invalidate()
                if (menuOpen && was < 0 && hit < 0) menuOpen = false      // a touch outside it
                else if (was >= 0 && was == hit) when (hit) {
                    MENU_AUTO -> onAutoLogin?.invoke(!autoLogin())
                    MENU_CHANGE -> { menuOpen = false; onChangeLogin?.invoke() }
                    MENU_FORGET -> {
                        val at = android.os.SystemClock.uptimeMillis()
                        if (at - forgetArmedAt < FORGET_ARM_MS) { forgetArmedAt = -FORGET_ARM_MS; onForgetLogin?.invoke() } else forgetArmedAt = at
                    }
                    MENU_STREAM -> { menuOpen = false; SetupBridge.openStreamSettings?.invoke() }
                    MENU_RESTART -> { menuOpen = false; onRestart?.invoke() }
                    MENU_SWITCH -> { menuOpen = false; onSwitch?.invoke() }
                    MENU_REPORT -> { menuOpen = false; onReport?.invoke() }
                    MENU_EXIT -> { menuOpen = false; onExit?.invoke() }
                    CURSOR_REPORT -> menuOpen = true
                    CURSOR_KEYS -> SetupBridge.openPcKeyboard?.invoke()
                    CURSOR_GEAR -> onLoginButton?.invoke(true)
                    else -> if (!loginBusy) onLoginButton?.invoke(false)
                }
            }
            MotionEvent.ACTION_CANCEL -> { pressed = -1; invalidate() }
        }
        return true
    }

    companion object {
        /** the kit's A glyph (the face button the game calls PAD1), as on the other rooms' buttons */
        const val GLYPH_A = "rev_a"
        const val CURSOR_GEAR = 0
        const val CURSOR_LOGIN = 1
        const val CURSOR_REPORT = 2
        const val CURSOR_KEYS = 3
        const val MENU_AUTO = 4
        const val MENU_CHANGE = 5
        const val MENU_REPORT = 6
        const val MENU_EXIT = 7
        const val MENU_FORGET = 8
        const val MENU_STREAM = 9
        const val MENU_RESTART = 10
        const val MENU_SWITCH = 11
        const val FORGET_ARM_MS = 4000L
        const val MENU_W = 1000f
        const val MENU_PAD = 32f
        const val CHECK = 76f
        /** Report and Keyboard, in the row's line at the screen's two ends */
        const val SIDE_M = 40f
        const val SIDE_W = 220f
        /** the status line in its panel: its size, the smallest a long line shrinks to, its room from the panel's ends */
        const val LINE_SIZE = 34f
        const val LINE_MIN = 22f
        const val LINE_PAD = 32f
        // the sign-in row (LATER 29): buttons 111 px high (48 dp on the Thor's bottom screen, the touch minimum), 40 px off the bottom;
        // the swirl and the line move up 60 / 30 px to make room
        const val BUTTON_H = 111f
        const val ROW_DOWN = 40f
        const val GAP = 24f
        const val LOGIN_W = 360f
        const val FIRST_W = 480f
        const val SWIRL_UP_ROW = 463f
        // UI Art's spec (START_final.png): a 440 px box centred 403 px above the bottom, so it ends just above the line
        const val STYLE = "travel"       // the user's pick "A" (UI Art's SHEET_loader)
        const val SWIRL_D = 440f         // the swirl's box, px
        const val SWIRL_UP = 403f        // the box's centre above the bottom edge
        const val LINE_UP = 175f         // the setup screen's line: its baseline above the bottom edge, 34 px
        const val FRAME_MS = 33L         // the swirl's redraw (see onDraw)
    }
}
