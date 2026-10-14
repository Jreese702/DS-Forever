package com.jrees.wowcompanion

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/**
 * The Settings room: game actions, map preferences and the Cast room's controller options.
 *
 * TWO PARTS (the user's Settings rework, 2026-10-06; the order Game -> Home -> Map -> Controller -> Game UI -> Companion): Home's
 * section sits between this view's "Game" and its "Map", so the room stacks it twice -- [Part.TOP] = the "Settings" title and Game
 * (Reload), [Part.REST] = Map and Controller -- with Home's section between them. [Part.ALL] is the whole page in one (the order
 * before the rework, for a host that has not split it yet). Each part MEASURES ITS OWN HEIGHT, so the room's scroll scrolls the
 * page and nothing here scrolls. Gone in the rework: "Combat cooldowns" and "Trigger swap" (Cast fixes their values) and the
 * "D-pad room bar" (Home's section's "DS WoW toolbar" now).
 *
 * Reload is TWO taps (tap, then "tap again" within 3 s). A reload mid-fight costs the player
 * several seconds of a frozen UI, and the bottom screen is exactly where a stray thumb lands.
 * It is still one game action per touch: the first tap only arms the button.
 *
 * The page is taller than the room, so it scrolls with a vertical drag. Controls therefore fire
 * on the finger LIFTING, and only when the finger stayed within the system touch slop of where
 * it landed: a drag never presses anything. The page is drawn in screen coordinates (content y
 * minus [scroll]), so every hit box recorded by the last draw matches what the finger touches.
 */
class SettingsView(context: Context, private val part: Part = Part.ALL, private val onReload: () -> Unit) : View(context) {

    enum class Part { ALL, TOP, REST }
    private val hasGame get() = part != Part.REST
    private val hasRest get() = part != Part.TOP

    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
    private val pTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 44f; color = Theme.gold; typeface = Typeface.DEFAULT_BOLD
    }
    private val pSection = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 32f; color = Theme.gold }   // a heading, in Morpheus
    private val pLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 31f; color = Theme.text }
    private val pSub = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; color = Theme.textDim }
    private val pBtn = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 27f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private val pValue = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 30f; textAlign = Paint.Align.CENTER; color = Theme.text
    }

    // hit boxes, in SCREEN coordinates as of the last draw
    private var btnReload = RectF()
    private var btnLogin = RectF()

    /**
     * THE SAVED BATTLE.NET LOGIN, CHANGED HERE (the user 2026-10-08: "Change saved login" moves to Settings; the start screen's row
     * shows only when needed). [loginRow] = the row is there (the host sets it once); [loginSaved] = a login is saved; [onLogin] opens
     * the host's login prompt.
     */
    var loginRow = false
        set(v) { if (field != v) { field = v; requestLayout(); invalidate() } }
    var loginSaved: () -> Boolean = { false }
    var onLogin: () -> Unit = {}
    private var btnMiniMinus = RectF(); private var btnMiniPlus = RectF()
    private var btnMiniInMinus = RectF(); private var btnMiniInPlus = RectF()
    private val segLayout = Array(LAYOUTS.size) { RectF() }
    private val segChat = Array(CHAT_INPUTS.size) { RectF() }
    /** the wheels' two settings (Home's HomeSettings.radialTap / radialPickA; the user 2026-10-09) */
    private val segOpen = Array(2) { RectF() }
    private val segPick = Array(2) { RectF() }
    /** the pause wheel's own pair (HomeSettings.pauseTap / pausePickA; the user 2026-10-09) */
    private val segPauseOpen = Array(2) { RectF() }
    private val segPausePick = Array(2) { RectF() }

    /**
     * FRAME GENERATION (board f-frame-gen): the player's own Lossless.dll and its settings (FrameGen; applied at the next game start).
     * [onChooseDll] opens the host's file picker ON THIS (bottom) SCREEN; [gameRunning] = the game is up: the Settings room is reachable
     * only then (v1.0.4 test: "I only have the setting with the game open"), so Choose works with it up and everything applies at
     * the next game start.
     */
    var onChooseDll: () -> Unit = {}
    var gameRunning: () -> Boolean = { false }
    /** "Restart the game" (the launcher's restart: close, wait, Play again; SetupBridge.restartGame) */
    var onRestart: () -> Unit = {}
    private var btnDll = RectF()
    private var btnRestart = RectF()
    /** the frame-generation rows the last measure counted ([fgRows]) */
    private var measuredFg = -1
    private val segFg = Array(2) { RectF() }
    private val segMult = Array(FrameGen.MULTIPLIERS.size) { RectF() }
    private var btnFlowMinus = RectF(); private var btnFlowPlus = RectF()
    private val segPerf = Array(2) { RectF() }

    /** INERT since the rework: the D-pad row moved to Home's section ("DS WoW toolbar"). Kept so an old host's wiring compiles. */
    var dpadState: () -> PadMapModel.Dpad? = { null }
    var onDpad: (Boolean) -> Unit = {}

    private var reloadArmedAt = 0L          // first tap time; 0 = not armed
    private var reloadSentAt = 0L
    private val ARM_MS = 3000L

    // ---- scrolling
    private var scroll = 0f                 // px of content above the top edge
    private var contentH = 0f               // measured by the last draw (the layout never changes)
    private var drawnScroll = 0f            // the scroll the hit boxes were recorded at
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var activeId = -1               // pointer being tracked; -1 = none
    private var downX = 0f; private var downY = 0f
    private var lastY = 0f
    private var dragging = false            // the finger left the slop circle: this gesture scrolls
    private var tapVoid = false             // a second finger came down: this gesture is no tap

    private val castListener: () -> Unit = { postInvalidate() }

    init {
        Settings.onChange { postInvalidate() }
        HomeSettings.onChange { postInvalidate() }          // the wheels' two settings (also switched from the Controller DS tab)
        FrameGen.init(context)
        FrameGen.onChange { post { requestLayout(); invalidate() } }   // a pick finishes on a background thread; rows may come or go
        CastSettings.init(context)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        CastSettings.onChange(castListener)
        postInvalidate()                     // a value may have changed while detached
    }

    override fun onDetachedFromWindow() {
        CastSettings.removeOnChange(castListener)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        scroll = clampScroll(scroll)
    }

    private fun maxScroll(): Float = (contentH - height).coerceAtLeast(0f)
    private fun clampScroll(s: Float): Float = s.coerceIn(0f, maxScroll())

    /**
     * The controller's targets (Home's Settings cursor, LATER item 5; the map session's OK): every button as drawn, in reading
     * order. Read-only: the cursor presses a button with a touch at its centre, so this view's own logic decides.
     */
    fun focusTargets(): List<RectF> =
        ((if (hasGame) listOf(btnReload) + (if (loginRow) listOf(btnLogin) else emptyList()) else emptyList()) +
            (if (hasRest) listOf(btnMiniMinus, btnMiniPlus, btnMiniInMinus, btnMiniInPlus) + segLayout.toList() + segPauseOpen.toList() + segPausePick.toList() + segOpen.toList() + segPick.toList() + segChat.toList() +
                listOf(btnDll) + segFg.toList() + segMult.toList() + listOf(btnFlowMinus, btnFlowPlus) + segPerf.toList() + listOf(btnRestart) else emptyList()))
            .filter { !it.isEmpty }.map { RectF(it) }

    /** the part's height, laid out exactly as onDraw lays it out (the room's scroll asks; nothing here scrolls) */
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), Math.round(layoutHeight()))
    }

    private fun layoutHeight(): Float {
        var y = if (hasGame) TITLE_END + TITLE_GAP + 16f + ROW_H + (if (loginRow) 10f + ROW_H else 0f) +
            (if (part == Part.TOP) TOP_END else SECTION_GAP) else REST_TOP
        if (hasRest) y += 16f + ROW_H + 10f + ROW_H + SECTION_GAP + 16f + ROW_H + 4f * (10f + ROW_H) + SECTION_GAP + 16f + ROW_H +
            SECTION_GAP + 16f + fgHeight() + BOTTOM_PAD
        return y
    }

    /** the Frame generation section's rows now (FrameGen.rows: the DLL row alone until one is set; Restart when it would change something) */
    private fun fgRows(): List<FrameGen.Row> = FrameGen.rows(FrameGen.hasDll(),
        FrameGen.showRestart(gameRunning(), SetupBridge.restarting, FrameGen.launched, FrameGen.current()))

    private fun fgHeight(): Float {
        val n = fgRows().size.also { measuredFg = it }
        return n * ROW_H + (n - 1) * 10f
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeId = e.getPointerId(0)
                downX = e.x; downY = e.y; lastY = e.y
                dragging = false; tapVoid = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> tapVoid = true
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(activeId)
                if (i < 0) return true
                val x = e.getX(i); val y = e.getY(i)
                if (!dragging && Math.hypot((x - downX).toDouble(), (y - downY).toDouble()) >= touchSlop) {
                    dragging = true
                    lastY = y                // scroll from here: no jump by the slop distance
                }
                if (dragging) {
                    val s = clampScroll(scroll - (y - lastY))
                    lastY = y
                    if (s != scroll) { scroll = s; invalidate() }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // the tracked finger lifted while another stays down: end this gesture here
                if (e.getPointerId(e.actionIndex) == activeId) { activeId = -1; dragging = false }
            }
            MotionEvent.ACTION_UP -> {
                val isTap = activeId == e.getPointerId(0) && !dragging && !tapVoid &&
                    Math.hypot((e.x - downX).toDouble(), (e.y - downY).toDouble()) < touchSlop
                activeId = -1; dragging = false
                // a tap never scrolls, so this shift is 0 unless a frame is still pending after a drag.
                // isShown: firing on LIFT means a finger held here while another finger switched rooms
                // on the strip would otherwise press a control on a page that is no longer on screen.
                if (isTap && isShown) tap(downX, downY + (scroll - drawnScroll))
            }
            MotionEvent.ACTION_CANCEL -> { activeId = -1; dragging = false }
        }
        return true
    }

    /** one control press at screen point ([x], [y]); only ever called for a finger that did not drag */
    private fun tap(x: Float, y: Float) {
        val now = SystemClock.elapsedRealtime()
        when {
            loginRow && btnLogin.contains(x, y) -> onLogin()
            btnReload.contains(x, y) -> {
                if (reloadArmedAt != 0L && now - reloadArmedAt < ARM_MS) {
                    reloadArmedAt = 0L; reloadSentAt = now
                    onReload()
                } else {
                    reloadArmedAt = now
                    postDelayed({ invalidate() }, ARM_MS + 50)   // disarm visibly
                }
            }
            btnMiniMinus.contains(x, y) -> Settings.minimapYd = Settings.stepYd(Settings.minimapYd, -1)
            btnMiniPlus.contains(x, y) -> Settings.minimapYd = Settings.stepYd(Settings.minimapYd, +1)
            btnMiniInMinus.contains(x, y) -> Settings.minimapIndoorYd = Settings.stepYd(Settings.minimapIndoorYd, -1)
            btnMiniInPlus.contains(x, y) -> Settings.minimapIndoorYd = Settings.stepYd(Settings.minimapIndoorYd, +1)
            segPauseOpen[0].contains(x, y) -> HomeSettings.pauseTap = true
            segPauseOpen[1].contains(x, y) -> HomeSettings.pauseTap = false
            segPausePick[0].contains(x, y) -> HomeSettings.pausePickA = true
            segPausePick[1].contains(x, y) -> HomeSettings.pausePickA = false
            segOpen[0].contains(x, y) -> HomeSettings.radialTap = true
            segOpen[1].contains(x, y) -> HomeSettings.radialTap = false
            segPick[0].contains(x, y) -> HomeSettings.radialPickA = true
            segPick[1].contains(x, y) -> HomeSettings.radialPickA = false
            btnDll.contains(x, y) -> onChooseDll()
            btnRestart.contains(x, y) -> { btnRestart = RectF(); onRestart() }
            segFg[0].contains(x, y) -> FrameGen.enabled = false
            segFg[1].contains(x, y) -> if (FrameGen.hasDll()) FrameGen.enabled = true
            btnFlowMinus.contains(x, y) -> FrameGen.flowScale = FrameGen.stepFlow(FrameGen.flowScale, -1)
            btnFlowPlus.contains(x, y) -> FrameGen.flowScale = FrameGen.stepFlow(FrameGen.flowScale, +1)
            segPerf[0].contains(x, y) -> FrameGen.performance = true
            segPerf[1].contains(x, y) -> FrameGen.performance = false
            else -> {
                val mi = segMult.indexOfFirst { it.contains(x, y) }
                if (mi >= 0) { FrameGen.multiplier = FrameGen.MULTIPLIERS[mi]; invalidate(); return }
                val ci = segChat.indexOfFirst { it.contains(x, y) }
                if (ci >= 0) { if (Settings.chatDefaultInput != CHAT_INPUTS[ci]) Settings.chatDefaultInput = CHAT_INPUTS[ci] }
                else {
                    val li = segLayout.indexOfFirst { it.contains(x, y) }
                    if (li < 0) return
                    if (CastSettings.buttonLayout != LAYOUTS[li]) CastSettings.buttonLayout = LAYOUTS[li]
                }
            }
        }
        invalidate()
    }

    /** the game's type (UI art, fonts step). Set where the paints are USED: the kit can reach the
     *  device after this view is built, and a paint keeps the font it was given. */
    private fun fonts() {
        UiKit.asTitle(pTitle)
        UiKit.asTitle(pSection); UiKit.asBody(pLabel); UiKit.asBody(pSub); UiKit.asBody(pBtn)
        UiKit.asNumber(pValue)
    }

    override fun onDraw(c: Canvas) {
        fonts()
        val w = width.toFloat()
        val now = SystemClock.elapsedRealtime()
        scroll = clampScroll(scroll)
        drawnScroll = scroll
        c.drawColor(Theme.ink)
        val padX = 28f
        var y = (if (hasGame) 62f else REST_TOP) - scroll       // everything below is laid out in screen coordinates
        if (hasGame) {
        c.drawText("Settings", padX, y, pTitle)
        y += 22f
        pFill.color = Theme.bronzeDim
        c.drawRect(0f, y, w, y + 1.5f, pFill)

        // ---- GAME
        y += TITLE_GAP
        c.drawText("Game", padX, y + 4f, pSection)
        y += 16f
        val armed = reloadArmedAt != 0L && now - reloadArmedAt < ARM_MS
        val sent = reloadSentAt != 0L && now - reloadSentAt < 2500L
        if (sent) postInvalidateDelayed(300)
        btnReload = row(c, y, "Reload game UI",
            if (sent) "Sent -- the game UI should restart now" else "Reloads the game's UI. Tap twice to confirm.",
            enabled = true) { box ->
            val b = RectF(box.right - 260f, box.centerY() - 34f, box.right - 16f, box.centerY() + 34f)
            // the game's red button; armed = held down, and it says what the second tap does
            UiKit.button(c, b, if (armed) "Tap again" else "Reload", if (armed) UiKit.Button.DOWN else UiKit.Button.UP)
            b
        }
        if (loginRow) {
            y += ROW_H + 10f
            val saved = loginSaved()
            btnLogin = row(c, y, "Battle.net login",
                if (saved) "Saved. The game signs in with it by itself." else "Not saved: save it to sign in by itself.",
                enabled = true) { box ->
                val b = RectF(box.right - 260f, box.centerY() - 34f, box.right - 16f, box.centerY() + 34f)
                UiKit.button(c, b, if (saved) "Change" else "Save", UiKit.Button.UP)
                b
            }
        } else btnLogin = RectF()
        y += ROW_H + (if (part == Part.TOP) TOP_END else SECTION_GAP)
        }
        // (Home's section stands here when the room stacks the two parts)
        if (hasRest) {

        // ---- MAP
        c.drawText("Map", padX, y + 4f, pSection)
        y += 16f
        // the map always frames itself now (the user, 2026-10-02: "remove the dynamic map toggle, as I REALLY love the way that it
        // currently works"; the greyed-out Indoor / Outdoor widths went with it); the small map's width is split in two ("split
        // the minimap default zoom toggle into an indoor and outdoor"), the +/- on the small map step the one in use
        var boxes = stepperRow(c, y, "Minimap zoom outdoors", "Width of the small map on Home outside",
                               Settings.minimapYd, true)
        btnMiniMinus = boxes.first; btnMiniPlus = boxes.second
        y += ROW_H + 10f
        boxes = stepperRow(c, y, "Minimap zoom indoors", "Width of the small map on Home inside buildings",
                           Settings.minimapIndoorYd, true)
        btnMiniInMinus = boxes.first; btnMiniInPlus = boxes.second
        y += ROW_H + SECTION_GAP

        // ---- CONTROLLER (the Cast room's options; values live in CastSettings): Button layout only since the rework
        c.drawText("Controller", padX, y + 4f, pSection)
        y += 16f
        val layout = CastSettings.buttonLayout
        pBtn.textSize = SEG_TEXT
        var segW = SEG_MIN_W
        for (t in LAYOUT_LABELS) segW = maxOf(segW, pBtn.measureText(t) + SEG_PAD)
        pBtn.textSize = 27f
        row(c, y, "Button layout", "How the Cast room names its buttons", enabled = true) { box ->
            val b = RectF(box.right - 16f - segW * LAYOUTS.size, box.centerY() - 34f,
                          box.right - 16f, box.centerY() + 34f)
            segmented(c, b, LAYOUT_LABELS, LAYOUTS.indexOf(layout), segLayout)
            b
        }
        // THE WHEELS (the user 2026-10-09: "2 settings per radial. Tap VS hold and A to select vs release select"), TWO PAIRS since the
        // user's revision ("I dont want that setting to affect the pause menu wheel"): the pause wheel's own, then the item & spell wheels'
        // (consumables, spell radial). App-wide, the same for every character, defaults Tap + A
        y += ROW_H + 10f
        row(c, y, "Pause wheel: open", if (HomeSettings.pauseTap) "Press Start to open; it stays until B." else "Hold Start; letting go closes it.",
            enabled = true) { box ->
            val b = RectF(box.right - 16f - segW * 2, box.centerY() - 34f, box.right - 16f, box.centerY() + 34f)
            segmented(c, b, listOf("Tap", "Hold"), if (HomeSettings.pauseTap) 0 else 1, segPauseOpen)
            b
        }
        y += ROW_H + 10f
        row(c, y, "Pause wheel: pick", if (HomeSettings.pausePickA) "Aim with the stick, A picks." else "Aim, let the stick go to pick.",
            enabled = true) { box ->
            val b = RectF(box.right - 16f - segW * 2, box.centerY() - 34f, box.right - 16f, box.centerY() + 34f)
            segmented(c, b, listOf("A", "Release"), if (HomeSettings.pausePickA) 0 else 1, segPausePick)
            b
        }
        y += ROW_H + 10f
        row(c, y, "Item & spell wheels: open", if (HomeSettings.radialTap) "Press to open; it stays until B." else "Hold to open; letting go closes it.",
            enabled = true) { box ->
            val b = RectF(box.right - 16f - segW * 2, box.centerY() - 34f, box.right - 16f, box.centerY() + 34f)
            segmented(c, b, listOf("Tap", "Hold"), if (HomeSettings.radialTap) 0 else 1, segOpen)
            b
        }
        y += ROW_H + 10f
        row(c, y, "Item & spell wheels: pick", if (HomeSettings.radialPickA) "Aim with the stick, A picks." else "Aim, let the stick go to pick.",
            enabled = true) { box ->
            val b = RectF(box.right - 16f - segW * 2, box.centerY() - 34f, box.right - 16f, box.centerY() + 34f)
            segmented(c, b, listOf("A", "Release"), if (HomeSettings.radialPickA) 0 else 1, segPick)
            b
        }
        y += ROW_H + SECTION_GAP

        // ---- CHAT (the user on b121: "a keyboard vs quick chat default in the settings"): what opens in the message first
        c.drawText("Chat", padX, y + 4f, pSection)
        y += 16f
        pBtn.textSize = SEG_TEXT
        var chatW = SEG_MIN_W
        for (t in CHAT_INPUT_LABELS) chatW = maxOf(chatW, pBtn.measureText(t) + SEG_PAD)
        pBtn.textSize = 27f
        row(c, y, "Message input", "What opens when you go into the message", enabled = true) { box ->
            val b = RectF(box.right - 16f - chatW * CHAT_INPUTS.size, box.centerY() - 34f, box.right - 16f, box.centerY() + 34f)
            segmented(c, b, CHAT_INPUT_LABELS, CHAT_INPUTS.indexOf(Settings.chatDefaultInput), segChat)
            b
        }
        y += ROW_H + SECTION_GAP

        // ---- FRAME GENERATION (board f-frame-gen): the player's own Lossless.dll, never shipped with the app; Off = launches as always.
        // Only the DLL row until one is set; "Restart the game" while the running game started with other settings (the user's request)
        c.drawText("Frame generation", padX, y + 4f, pSection)
        y += 16f
        val hasDll = FrameGen.hasDll()
        val up = gameRunning()
        val fg = fgRows()
        if (fg.size != measuredFg) requestLayout()        // a row came or went: the room's scroll needs the new height
        btnDll = RectF(); btnFlowMinus = RectF(); btnFlowPlus = RectF(); btnRestart = RectF()
        for (r in segFg + segMult + segPerf) r.setEmpty()   // hidden rows press nothing
        val on = FrameGen.enabled && hasDll
        val later = if (up) "Applies when the game restarts." else "Applies at the next game start."
        for ((i, r) in fg.withIndex()) {
            if (i > 0) y += ROW_H + 10f
            when (r) {
                FrameGen.Row.DLL -> btnDll = row(c, y, "Lossless.dll: ${if (hasDll) "found" else "not set"}",
                    when {
                        FrameGen.lastPick.isNotEmpty() -> FrameGen.lastPick + if (up && hasDll) ". $later" else ""
                        hasDll -> "Your own copy, kept in DS Forever's private storage."
                        else -> "Choose your own copy (from Lossless Scaling on your PC)."
                    }, enabled = true) { box ->
                    val b = RectF(box.right - 260f, box.centerY() - 34f, box.right - 16f, box.centerY() + 34f)
                    UiKit.button(c, b, "Choose", UiKit.Button.UP)
                    b
                }
                FrameGen.Row.ON_OFF -> row(c, y, "Frame generation", (if (on) "On. " else "Off. ") + later, enabled = true) { box ->
                    val b = RectF(box.right - 16f - segW * 2, box.centerY() - 34f, box.right - 16f, box.centerY() + 34f)
                    segmented(c, b, listOf("Off", "On"), if (on) 1 else 0, segFg)
                    b
                }
                FrameGen.Row.MULTIPLIER -> {
                    val mult = FrameGen.multiplier
                    row(c, y, "Multiplier", "Frames shown per game frame (40 fps x$mult = ${40 * mult})", enabled = on) { box ->
                        val b = RectF(box.right - 16f - segW * FrameGen.MULTIPLIERS.size, box.centerY() - 34f, box.right - 16f, box.centerY() + 34f)
                        segmented(c, b, FrameGen.MULTIPLIERS.map { "${it}x" }, FrameGen.MULTIPLIERS.indexOf(mult), segMult)
                        b
                    }
                }
                FrameGen.Row.FLOW -> row(c, y, "Flow scale", "Lower is faster, higher is sharper", enabled = on) { box ->
                    val cy = box.centerY()
                    btnFlowPlus = RectF(box.right - 16f - 76f, cy - 34f, box.right - 16f, cy + 34f)
                    btnFlowMinus = RectF(btnFlowPlus.left - 150f - 76f, cy - 34f, btnFlowPlus.left - 150f, cy + 34f)
                    val f = FrameGen.flowScale
                    UiKit.square(c, "minus", btnFlowMinus.centerX(), btnFlowMinus.centerY(), 84f,
                        if (on && f > FrameGen.FLOW_MIN) UiKit.Button.UP else UiKit.Button.DISABLED)
                    UiKit.square(c, "plus", btnFlowPlus.centerX(), btnFlowPlus.centerY(), 84f,
                        if (on && f < FrameGen.FLOW_MAX) UiKit.Button.UP else UiKit.Button.DISABLED)
                    pValue.color = if (on) Theme.text else Theme.dim
                    c.drawText(FrameGen.flowLabel(f), (btnFlowMinus.right + btnFlowPlus.left) / 2f, cy + 11f, pValue)
                    btnFlowMinus
                }
                FrameGen.Row.PERFORMANCE -> row(c, y, "Performance mode", "The lighter model: less heat, a little less smooth", enabled = on) { box ->
                    val b = RectF(box.right - 16f - segW * 2, box.centerY() - 34f, box.right - 16f, box.centerY() + 34f)
                    segmented(c, b, listOf("On", "Off"), if (FrameGen.performance) 0 else 1, segPerf)
                    b
                }
                FrameGen.Row.RESTART -> btnRestart = row(c, y, "Restart the game", "To use the new frame generation settings", enabled = true) { box ->
                    val b = RectF(box.right - 260f, box.centerY() - 34f, box.right - 16f, box.centerY() + 34f)
                    UiKit.button(c, b, "Restart", UiKit.Button.UP)
                    b
                }
            }
        }
        y += ROW_H + BOTTOM_PAD
        }

        val h = Math.round(y + scroll).toFloat()   // back to content coordinates (no float drift)
        if (h != contentH) {
            contentH = h
            if (clampScroll(scroll) != scroll) invalidate()
        }

        // a slim thumb in the right margin, only when the page overflows the room
        val max = maxScroll()
        if (max > 0f) {
            val vh = height.toFloat()
            val th = maxOf(60f, vh * vh / contentH)
            val ty = (vh - th) * (scroll / max)
            pFill.color = Theme.bronze          // bronzeDim on ink measured ~1.8:1: invisible
            c.drawRoundRect(w - 10f, ty + 6f, w - 5f, ty + th - 6f, 3f, 3f, pFill)
        }
    }

    // 96, not 104: with three sections the page came to 1024 px in a ~970 px room, and the last row
    // sat cut off at the bottom looking like a layout bug. At 96 it all fits (~960); the scrolling
    // stays for whatever section comes next.
    private val ROW_H = 96f

    /** a labelled row; [control] draws the right-hand control and returns its hit box */
    private fun row(c: Canvas, top: Float, label: String, sub: String, enabled: Boolean,
                    control: (RectF) -> RectF): RectF {
        val box = RectF(16f, top, width - 16f, top + ROW_H)
        pFill.color = Theme.raised
        c.drawRoundRect(box, 8f, 8f, pFill)
        val hit = control(box)
        pLabel.color = if (enabled) Theme.text else Theme.dim
        pSub.color = if (enabled) Theme.textDim else Theme.dim
        c.drawText(label, box.left + 22f, box.top + 41f, pLabel)
        // the subtitle never runs under the control, whatever the device font measures
        c.drawText(fit(pSub, sub, hit.left - 20f - (box.left + 22f)), box.left + 22f, box.top + 75f, pSub)
        return hit
    }

    private fun stepperRow(c: Canvas, top: Float, label: String, sub: String, yd: Int,
                           enabled: Boolean): Pair<RectF, RectF> {
        var minus = RectF(); var plus = RectF()
        row(c, top, label, sub, enabled) { box ->
            val cy = box.centerY()
            plus = RectF(box.right - 16f - 76f, cy - 34f, box.right - 16f, cy + 34f)
            minus = RectF(plus.left - 150f - 76f, cy - 34f, plus.left - 150f, cy + 34f)
            // the game's red squares; the hit boxes stay the 76 x 68 they were. Each greys at its end of the zoom steps, as the
            // small map's own +/- do (UI Art's look at integ-b41: "+" did nothing at 1600 yd without looking it)
            val steps = Settings.ZOOM_STEPS_YD
            val stMinus = if (enabled && yd > steps.first()) UiKit.Button.UP else UiKit.Button.DISABLED
            val stPlus = if (enabled && yd < steps.last()) UiKit.Button.UP else UiKit.Button.DISABLED
            UiKit.square(c, "minus", minus.centerX(), minus.centerY(), 84f, stMinus)
            UiKit.square(c, "plus", plus.centerX(), plus.centerY(), 84f, stPlus)
            pValue.color = if (enabled) Theme.text else Theme.dim
            c.drawText("$yd yd", (minus.right + plus.left) / 2f, cy + 11f, pValue)
            minus
        }
        return Pair(minus, plus)
    }

    /** on / off is the game's check box (user pick 2026-09-28), at the right end of the box that stays the hit box */
    private fun toggle(c: Canvas, b: RectF, on: Boolean) {
        UiKit.check(c, b.right - 38f, b.centerY(), 76f, on)
    }

    /**
     * One of several, filling [b]: the game's red button for each choice, the [sel]ected one lit.
     * Each segment's hit box is written into [out] (same order as [labels]); the hit boxes are
     * the equal segments they always were, the buttons are drawn 6 px taller than them.
     */
    private fun segmented(c: Canvas, b: RectF, labels: List<String>, sel: Int, out: Array<RectF>) {
        val w = b.width() / labels.size
        for (i in labels.indices) {
            out[i].set(b.left + i * w, b.top, b.left + (i + 1) * w, b.bottom)
            UiKit.choice(c, RectF(out[i].left + 2f, b.top - 6f, out[i].right - 2f, b.bottom + 6f), labels[i], i == sel,
                textSize = SEG_TEXT)
        }
    }

    /** [s] cut to [maxW] px with an ellipsis, or unchanged when it fits */
    private fun fit(p: Paint, s: String, maxW: Float): String {
        if (p.measureText(s) <= maxW) return s
        val n = p.breakText(s, true, (maxW - p.measureText("…")).coerceAtLeast(0f), null)
        return s.substring(0, n).trimEnd() + "…"
    }

    companion object {
        private val LAYOUTS = CastSettings.ButtonLayout.values().toList()
        private val LAYOUT_LABELS = LAYOUTS.map { it.label }
        private val CHAT_INPUTS = listOf("quick", "keys")
        private val CHAT_INPUT_LABELS = listOf("Quick chat", "Keyboard")
        private const val SEG_TEXT = 24f
        private const val SEG_PAD = 36f      // text-to-edge room inside a segment, both sides
        private const val SEG_MIN_W = 120f
        // SPACING, measured against the signed-off picture on the emulator (2026-10-06): a row's bottom to the next heading's top
        // is 60 px everywhere; a heading's cap top is ~26 px above its baseline, which is drawn 4 px below the section's y
        private const val SECTION_GAP = 82f  // row bottom -> the next section's y (its heading baseline 86 below: cap top +60)
        // The two seams with Home's section, measured on the emulator against home-settings2 48f65b09 (a row box's bottom to the next
        // heading's BASELINE is 90 px between this view's own sections; the caps differ in height, so measure baselines, not cap tops)
        private const val TOP_END = 39f      // TOP's end -> Home's heading: Home draws it ~51 under its top -> 90 (36 gave 87)
        private const val REST_TOP = 66f     // REST's first heading under Home's 20 px bottom margin: 20 + 66 + 4 -> 90 (26 gave 50)
        private const val BOTTOM_PAD = 56f   // REST's end -> Game UI's first heading: 60 (it was 28)
        private const val TITLE_END = 84f    // the title's rule: 62 + 22
        private const val TITLE_GAP = 64f    // the rule -> "Game"'s baseline: its cap top 44 under the rule (the picture)
    }
}
