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
     * ONE TERSE LINE UNDER THE LINE, in the same panel (the user, 2026-11-24: advise Blizzard's page after other sign-in errors;
     * BrowserLogin.startHint): "Try Battle.net sign-in: Blizzard's page (Menu)" under the error, or the Menu's Restart game while the
     * running game signed in the other way. Words only: no button. null = the line alone. Never with the add-ons' card.
     */
    var hint: String? = null
        set(v) { if (field != v) { field = v; invalidate() } }

    /**
     * OUR OWN BATTLE.NET SIGN-IN (LATER 29; the user, 2026-10-05: "I want the Bnet login to basically show the first time and then after
     * the details are entered, then I want it to have a notmal login button and then like a gear to the left of it which just reopens
     * the login prompts"). Only while the game's login screen is up: FIRST = no saved login, one "Battle.net login" button; SAVED =
     * [gear][Login]. The row sits under the swirl ("On the companion's waiting screen") and goes with the login screen.
     */
    // BROWSER (the user, 2026-11-18): after a BLZ51914400 refusal, one "Sign in with Blizzard's page" button (BrowserLogin.offer): touch
    // only, as FIRST with the game up; it turns Browser login on and restarts the game ([onBrowserLogin])
    enum class Login { NONE, FIRST, SAVED, BROWSER }

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

    /**
     * THE REGION LINE (the user's pick 2026-11-13, "Show region when it differs"): where the game signs in when that is not the install's
     * own region, e.g. "Region: EU (this install is US)" - one terse line over the status panel (over the sign-in row when it shows),
     * no question asked. null = no line (the same region, Streaming, or past the world in this run: CompanionHost).
     */
    var region: GameRegion.Note? = null
        set(v) { if (field != v) { field = v; invalidate() } }
    /**
     * THE ADD-ONS' WORD ON THE START SCREEN (the add-ons-silent triage, 2026-11-14: the "add-ons aren't running" card never shows over the
     * start screen, which stays up while the add-ons say nothing): the card's title over its text in the line's own panel, in place of a
     * plain waiting line ([AddonSilence.WAIT_LINES] only: a reload's, a restart's or a sign-in's line always shows); the card's How (touch
     * only: the game has the pad's A) in the row's place. Null = the line. Set by the host ([AddonSilence.startMode]).
     */
    var addons: ReportNotice.Card? = null
        set(v) { if (field != v) { field = v; invalidate() } }
    /** How pressed: the host shows the card's steps */
    var onHow: (() -> Unit)? = null
    /** the add-ons' word is on show now (the report's bottom-screen line) */
    val addonsShown: Boolean get() = addons != null && AddonSilence.isWaitLine(line)

    /** the Menu ([StartMenu]), in place of Report at the bottom left */
    var menuOpen: Boolean
        get() = StartMenu.open
        set(v) { StartMenu.open = v; pressed = -1; invalidate() }

    /** a press of the row: true = the gear (touch only) or FIRST's one button (touch or A), false = Login (touch or A) */
    var onLoginButton: ((gear: Boolean) -> Unit)? = null
    /** BROWSER's one button pressed (touch only) */
    var onBrowserLogin: (() -> Unit)? = null

    /**
     * SINGLE SCREEN MODE'S STRIP (SingleLook row 2b; the review 2026-11-25): with the game screen up the page is only its row over the
     * game - no loading scene behind it (the game's login screen and character select show), no redraw loop. AT THE TOP (the user's
     * ruling 2026-11-25, verbatim: "Top of the screen (Recommended): Keeps character select's Enter World and the bottom of the login
     * screen free for touch."): the row from the strip's top down - the line's row (Menu | line | Keyboard) at SingleNpc.START_TOP, the
     * sign-in row (or the add-ons' How) under it, the region line under those. False = the page as always, its row at the foot (two
     * screens: never set)
     */
    var bare = false
        set(v) { if (field != v) { field = v; bareNeedLast = -1; invalidate() } }

    /** the bare row's height for what it shows now (SingleNpc.startNeed): the strip window's height */
    fun bareNeed(): Int = SingleNpc.startNeed(second = login != Login.NONE || howShown(), region = region != null)
    /** the bare row's height changed (a sign-in row, the add-ons' How or the region line came or went): the host places the strip again */
    var onBareNeed: (() -> Unit)? = null
    private var bareNeedLast = -1

    /** the add-ons' card's How in the sign-in row's place (never with the row: the line is a plain waiting one then) */
    private fun howShown(): Boolean {
        val card = addons?.takeIf { addonsShown } ?: return false
        return login == Login.NONE && ReportNotice.HOW in card.buttons
    }

    private val area = RectF()
    private val hits = ArrayList<Pair<RectF, Int>>()
    private var pressed = -1

    override fun onDraw(c: Canvas) {
        area.set(0f, 0f, width.toFloat(), height.toFloat())
        // THE LOADING REVEAL (the user, 2026-10-28): the scene behind both screens replaces the game's still scene and the small swirl.
        // Every 33 ms, NOT every vsync: on a software GPU (the emulator, ~1 s a frame) a per-vsync redraw starved the main thread and,
        // with a document backlog decoding icons, hung the app (Map's ANR on integ-b40 + mapset; UI Art's measure). 30 fps is plenty
        if (!bare) {
            LoadingScene.draw(c, area, top = false)
            postInvalidateDelayed(FRAME_MS)
        } else {
            // the strip fits the row: a change of what it shows asks the host for the new height (posted: no window change mid-draw)
            val need = bareNeed()
            if (need != bareNeedLast) { bareNeedLast = need; post { if (bare) onBareNeed?.invoke() } }
        }
        drawRow(c)
        // the Menu needs the whole page: the host takes the strip back at once (SingleLook: the Menu open is no waiting row), so the bare
        // strip never draws it squeezed into the row's height
        if (menuOpen && !bare) {
            val top = height - ROW_DOWN - BUTTON_H
            val left = (width - StartMenu.WIDTH) / 2f
            StartMenu.draw(c, RectF(left, GAP, left + StartMenu.WIDTH, top - GAP), pressed, hits, RectF(SIDE_M, top, SIDE_M + SIDE_W, top + BUTTON_H), game = true)
        }
    }

    private fun drawRow(c: Canvas) {
        val cx = width / 2f
        val row = login != Login.NONE
        hits.clear()
        // the line in one metal panel, framed by Report and the PC's keyboard (the user, 2026-10-30: "framed between the two buttons in
        // like a fancy looking one part panel"); the keyboard only while a game or stream can take it. Bare (single screen mode, the
        // user's ruling "Top of the screen"): this row at the strip's top, the sign-in row under it, the region line under those
        val sideTop = if (bare) SingleNpc.START_TOP.toFloat() else height - ROW_DOWN - BUTTON_H
        val panel = RectF(SIDE_M + SIDE_W + GAP, sideTop, width - SIDE_M - SIDE_W - GAP, sideTop + BUTTON_H)
        UiKit.module(c, panel)
        val card = addons?.takeIf { addonsShown }
        val under = hint
        if (card != null) drawCard(c, panel, card)
        else if (under != null) drawHint(c, panel, under)
        else {
            val words = UiKit.bodyPaint(if (bare) SingleType.TITLE else LINE_SIZE)     // the tidy pass: single mode's title size
            while (words.measureText(line) > panel.width() - LINE_PAD * 2 && words.textSize > LINE_MIN) words.textSize -= 1f
            UiKit.outlined(c, line, cx, panel.centerY() - (words.ascent() + words.descent()) / 2f, words.textSize, Theme.text)
        }
        val report = RectF(SIDE_M, sideTop, SIDE_M + SIDE_W, sideTop + BUTTON_H)
        UiKit.button(c, report, "Menu", if (pressed == CURSOR_REPORT) UiKit.Button.DOWN else UiKit.Button.UP)
        hits += report to CURSOR_REPORT
        if (SetupBridge.openPcKeyboard != null) {
            val keys = RectF(width - SIDE_M - SIDE_W, sideTop, width - SIDE_M, sideTop + BUTTON_H)
            UiKit.button(c, keys, "Keyboard", if (pressed == CURSOR_KEYS) UiKit.Button.DOWN else UiKit.Button.UP)
            hits += keys to CURSOR_KEYS
        }
        val top = if (bare) sideTop + BUTTON_H + GAP else sideTop - GAP - BUTTON_H
        // the add-ons' card's one action, touch only, where the sign-in row would be (never with the row: the line is a plain waiting one then)
        val how = !row && card != null && ReportNotice.HOW in card.buttons
        // the region line just over the panel, or over the sign-in row / the card's How (its words translated where they are drawn: UiKit.outlined);
        // bare: just under the panel, or under the sign-in row / the How
        region?.let { n ->
            val text = n.line { Tr.t(it) }
            var size = if (bare) SingleType.BODY else REGION_SIZE
            val p = UiKit.bodyPaint(size)
            while (p.measureText(Tr.t(text)) > width - SIDE_M * 2 && size > LINE_MIN) { size -= 1f; p.textSize = size }
            val cy = if (bare) (if (row || how) top else sideTop) + BUTTON_H + REGION_GAP + size / 2f
                else (if (row || how) top else sideTop) - REGION_GAP - size / 2f
            UiKit.outlined(c, text, cx, cy, size, Theme.gold)
        }
        if (!row) {
            if (how) {
                val r = RectF(cx - HOW_W / 2f, top, cx + HOW_W / 2f, top + BUTTON_H)
                UiKit.button(c, r, ReportNotice.HOW, if (pressed == CURSOR_HOW) UiKit.Button.DOWN else UiKit.Button.UP)
                hits += r to CURSOR_HOW
            }
            return
        }
        if (login == Login.BROWSER) {
            // touch only (the game has the pad's A at its login screen); as wide as FIRST's button in the one app
            val r = RectF(cx - BROWSER_W / 2f, top, cx + BROWSER_W / 2f, top + BUTTON_H)
            UiKit.button(c, r, BrowserLogin.OFFER_BUTTON, if (pressed == CURSOR_BROWSER) UiKit.Button.DOWN else UiKit.Button.UP)
            hits += r to CURSOR_BROWSER
            return
        }
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

    /** the line and [hint] under it in the line's panel, each translated and shrunk to fit on one line (the hint in gold) */
    private fun drawHint(c: Canvas, panel: RectF, under: String) {
        val w = panel.width() - LINE_PAD * 2
        val top = Tr.t(line); val low = Tr.t(under)
        val tp = UiKit.bodyPaint(HINT_LINE)
        while (tp.measureText(top) > w && tp.textSize > CARD_MIN) tp.textSize -= 1f
        val hp = UiKit.bodyPaint(HINT_SIZE)
        while (hp.measureText(low) > w && hp.textSize > CARD_MIN) hp.textSize -= 1f
        val th = tp.textSize * 1.2f; val hh = hp.textSize * 1.2f
        val y = panel.centerY() - (th + hh) / 2f
        UiKit.outlined(c, top, panel.centerX(), y + th / 2f, tp.textSize, Theme.text)
        UiKit.outlined(c, low, panel.centerX(), y + th + hh / 2f, hp.textSize, Theme.gold)
    }

    /**
     * The card in the line's panel: its title, then its text translated whole and wrapped (a wrapped half is no key of the word lists),
     * both shrunk until they fit the panel (at most [CARD_LINES] lines of text). Main thread.
     */
    private fun drawCard(c: Canvas, panel: RectF, card: ReportNotice.Card) {
        val w = panel.width() - LINE_PAD * 2
        val title = Tr.t(card.title)
        val tp = UiKit.bodyPaint(CARD_TITLE)
        while (tp.measureText(title) > w && tp.textSize > CARD_MIN) tp.textSize -= 1f
        val bp = UiKit.bodyPaint(CARD_TEXT)
        val text = Tr.t(card.text)
        var lines = SetupPanel.wrap(text, bp, w)
        while (bp.textSize > CARD_MIN && (lines.size > CARD_LINES || tp.textSize * 1.15f + lines.size * bp.textSize * 1.2f > panel.height() - 10f)) {
            bp.textSize -= 1f; lines = SetupPanel.wrap(text, bp, w)
        }
        lines = lines.take(CARD_LINES)
        val th = tp.textSize * 1.15f; val lh = bp.textSize * 1.2f
        var y = panel.centerY() - (th + lines.size * lh) / 2f + th / 2f
        UiKit.outlined(c, title, panel.centerX(), y, tp.textSize, Theme.gold)
        y += th / 2f + lh / 2f
        for (l in lines) { UiKit.outlined(c, l, panel.centerX(), y, bp.textSize, Theme.text); y += lh }
    }

    /**
     * The pad's A while the row shows: Login (or the first-time button); the gear never ("I want A to log in"; no cursor, no box).
     * The game receives the same A: MEASURED on WoW Forever's login screen it does nothing there (19:30, fields and log unchanged), and
     * past it the caller refuses the typing (HomeLogin.State.canType: a realm connection, a sign-in begun...) and Login is greyed with
     * no glyph, so the A stays the game's own (e.g. Enter World). Main thread.
     */
    fun padA() {
        when (login) {
            Login.NONE, Login.BROWSER -> {}
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
                if (menuOpen) {
                    // a touch outside it: one step back out of the region list / card (no change), else the Menu shuts
                    if (was < 0 && hit < 0) { if (!StartMenu.back()) menuOpen = false }
                    else if (was >= 0 && was == hit) StartMenu.press(hit)
                }
                else if (was >= 0 && was == hit) when (hit) {
                    CURSOR_REPORT -> menuOpen = true
                    CURSOR_KEYS -> SetupBridge.openPcKeyboard?.invoke()
                    CURSOR_HOW -> onHow?.invoke()
                    CURSOR_GEAR -> onLoginButton?.invoke(true)
                    CURSOR_BROWSER -> onBrowserLogin?.invoke()
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
        const val GLYPH_B = "rev_b"
        const val CURSOR_GEAR = 0
        const val CURSOR_LOGIN = 1
        const val CURSOR_REPORT = 2
        const val CURSOR_KEYS = 3
        const val CURSOR_HOW = 4
        const val CURSOR_BROWSER = 5
        /** BROWSER's one button (its words are longer than FIRST's) */
        const val BROWSER_W = 620f
        /** the add-ons' card in the line's panel: the title's and the text's sizes, the smallest either shrinks to, the text's lines;
         *  the How button's width (ReportNotice's) */
        const val CARD_TITLE = 30f
        const val CARD_TEXT = 24f
        const val CARD_MIN = 18f
        const val CARD_LINES = 3
        /** the line and the hint under it ([hint]): their sizes (each shrinks to CARD_MIN at the least) */
        const val HINT_LINE = 30f
        const val HINT_SIZE = 26f
        const val HOW_W = 260f
        /** Report and Keyboard, in the row's line at the screen's two ends */
        const val SIDE_M = 40f
        const val SIDE_W = 220f
        /** the status line in its panel: its size, the smallest a long line shrinks to, its room from the panel's ends */
        const val LINE_SIZE = 34f
        const val LINE_MIN = 22f
        const val LINE_PAD = 32f
        /** the region line: its size, and its room over the panel (or the row) */
        const val REGION_SIZE = 30f
        const val REGION_GAP = 18f
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
