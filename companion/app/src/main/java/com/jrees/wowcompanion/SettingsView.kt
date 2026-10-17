package com.jrees.wowcompanion

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/**
 * The Settings room's own rows: game actions, the map, the controller options, chat, frame generation and the indoor maps.
 *
 * TABS (board f-settings-tabs, 2026-10-15): the room shows one tab at a time ([SettingsTabs]); this view draws the blocks of the
 * current [tab] that are its own ([SettingsTabs.VIEW_BLOCKS]: Game on System, Map and Chat on Rooms, Controller on Controller,
 * Frames and Indoor maps on Performance) and nothing else: every hit box of a block not drawn is EMPTY, so nothing off the tab can
 * be pressed. The title and the tab row are the room's ([SettingsTabBar]). The view MEASURES ITS OWN HEIGHT, so the room's scroll
 * scrolls the page and nothing here scrolls.
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
class SettingsView(context: Context, private val onReload: () -> Unit) : View(context) {

    /** the tab shown (the room sets it) */
    var tab: SettingsTabs.Tab = SettingsTabs.Tab.HOME
        set(v) { if (field != v) { field = v; requestLayout(); invalidate() } }

    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
    private val pTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 44f; color = Theme.gold; typeface = Typeface.DEFAULT_BOLD
    }
    private val pSection = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 32f; color = Theme.gold }   // a heading, in Morpheus
    private val pSubHead = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 26f; color = Theme.gold }   // a block under [SettingsTabs.HOME_MODULES]
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
    /** "Send a problem report" (board f-problem-report): the host opens the report page; [onReportSendNow] while recording; the note */
    var onReport: () -> Unit = {}
    var onReportSendNow: () -> Unit = {}
    var onReportOpenSaved: () -> Unit = {}
    private var btnReport = RectF()
    private var btnReportDismiss = RectF()
    private var btnMiniMinus = RectF(); private var btnMiniPlus = RectF()
    private var btnMiniInMinus = RectF(); private var btnMiniInPlus = RectF()
    private val segLayout = Array(LAYOUTS.size) { RectF() }
    private val segLocation = Array(LOCATIONS.size) { RectF() }
    private val segChat = Array(CHAT_INPUTS.size) { RectF() }
    private val segQt = Array(3) { RectF() }
    /** Map > Indoor maps: build in the background On / Off (Settings.indoorBuild; b-interiors-stutter) */
    private val segIndoor = Array(2) { RectF() }
    /**
     * Map > Indoor maps' PROGRESS (board f-map-tracker): ready / total, the building now, the deferred count (IndoorProgress.line);
     * "Deferred (n)" opens the list of deferred buildings and their reasons in place, under the row. [progress] = the bake's last
     * status, re-read when the bake says it changed (IndoorSchedulerStep.onChange) and when the view is shown.
     */
    private var btnDeferred = RectF()
    private var deferredOpen = false
    private var progress: IndoorScheduler.Status? = null
    private val LIST_LINE = 38f
    private val progressListener: () -> Unit = {
        val s = IndoorSchedulerStep.status(context)
        post {
            val grew = (progress?.list?.size ?: 0) != (s?.list?.size ?: 0)
            progress = s
            if (grew && deferredOpen) requestLayout()
            invalidate()
        }
    }
    /** the open deferred list's height (0 when closed or empty) */
    private fun deferredListH(): Float {
        val n = progress?.list?.size ?: 0
        return if (deferredOpen && n > 0) 12f + n * LIST_LINE else 0f
    }
    /** the wheels' two settings (Home's HomeSettings.radialTap / radialPickA; the user 2026-10-09) */
    private val segOpen = Array(2) { RectF() }
    private val segPick = Array(2) { RectF() }
    /** the spell wheel's own pair (HomeSettings.spellTap / spellPickA; f-radial-settings) */
    private val segSpellOpen = Array(2) { RectF() }
    /** the class radials' pairs (HomeSettings.classTapL / classPickAL, ...R; f-radial-settings) */
    private val segClassLOpen = Array(2) { RectF() }
    private val segClassLPick = Array(2) { RectF() }
    private val segClassROpen = Array(2) { RectF() }
    private val segClassRPick = Array(2) { RectF() }
    private val segSpellPick = Array(2) { RectF() }
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
    /** Frame generation > Frame limit: 40 / 60 / Off (FrameGen.fpsLimit; board n-fps-40) */
    private val segLimit = Array(FrameGen.LIMITS.size) { RectF() }

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

    /** Settings > Add-ons (1.0.9): each module's check box and each option's On / Off, as last drawn ("<addon>.<id>") */
    private val addonModuleBoxes = LinkedHashMap<String, RectF>()
    private val addonOptionSegs = LinkedHashMap<String, Array<RectF>>()

    init {
        ThirdPartyAddons.init(context)
        ThirdPartyAddons.onChange { postInvalidate() }
        Settings.onChange { postInvalidate() }
        ReportFlow.init(context)
        ReportFlow.onChange { postInvalidate() }
        HomeSettings.onChange { postInvalidate() }          // the wheels' two settings (also switched from the Controller DS tab)
        FrameGen.init(context)
        FrameGen.onChange { post { requestLayout(); invalidate() } }   // a pick finishes on a background thread; rows may come or go
        CastSettings.init(context)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        CastSettings.onChange(castListener)
        IndoorSchedulerStep.onChange(progressListener)
        progress = runCatching { IndoorSchedulerStep.status(context) }.getOrNull()
        requestLayout()
        postInvalidate()                     // a value may have changed while detached
    }

    override fun onDetachedFromWindow() {
        CastSettings.removeOnChange(castListener)
        IndoorSchedulerStep.offChange(progressListener)
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
        (listOf(btnReload) + (if (loginRow) listOf(btnLogin) else emptyList()) + listOf(btnReport, btnReportDismiss) +
            listOf(btnMiniMinus, btnMiniPlus, btnMiniInMinus, btnMiniInPlus) + segQt.toList() + segChat.toList() +
            segLocation.toList() + segLayout.toList() + segPauseOpen.toList() + segPausePick.toList() + segOpen.toList() + segPick.toList() +
            // test40 ("I can't press down ... even though it appears like there are options below"): the spell wheel's and the class
            // radials' segments were drawn but never listed, so the cursor stopped at the item wheel
            segSpellOpen.toList() + segSpellPick.toList() + segClassLOpen.toList() + segClassLPick.toList() + segClassROpen.toList() +
            segClassRPick.toList() +
            segLimit.toList() + listOf(btnDll) + segFg.toList() + segMult.toList() + listOf(btnFlowMinus, btnFlowPlus) + segPerf.toList() +
            listOf(btnRestart) + segIndoor.toList() + listOf(btnDeferred) + addonFocus())
            .filter { !it.isEmpty }.map { RectF(it) }.let { controls ->
                // ... and a row with nothing to press (an add-on's greyed module before its install, a status line) is still a stop:
                // the cursor walks every row it can see, A there does nothing
                controls + rowHits.map { it.first }.filter { row -> !row.isEmpty && controls.none { row.contains(it.centerX(), it.centerY()) } }
                    .map { RectF(it) }
            }

    /** [r] is the Reload game UI button (the cursor never starts there: the room's one destructive control) */
    fun isReload(r: RectF): Boolean = !btnReload.isEmpty && r == btnReload

    /** the Add-ons tab's controls, in reading order (each module's box, then each option's two segments) */
    private fun addonFocus(): List<RectF> = listOf(addonRestart) + addonCoreBtn.values.toList() + addonModuleBoxes.values.toList() +
        addonOptionSegs.values.flatMap { it.toList() }

    /** the rows as last drawn, with their words (the "What it does" panel follows the row under the cursor or a touch) */
    private val rowHits = ArrayList<Pair<RectF, String>>()

    /** the row at ([x], [y]) in this view, by its words; null = none */
    fun rowAt(x: Float, y: Float): String? = rowHits.firstOrNull { it.first.contains(x, y) }?.second

    /** every hit box empty: a block not drawn on this tab presses nothing */
    private fun clearBoxes() {
        rowHits.clear()
        addonModuleBoxes.clear(); addonOptionSegs.clear(); addonCoreBtn.clear(); addonRestart.setEmpty()
        for (r in listOf(btnReload, btnLogin, btnReport, btnReportDismiss, btnMiniMinus, btnMiniPlus, btnMiniInMinus, btnMiniInPlus,
                         btnDeferred, btnDll, btnFlowMinus, btnFlowPlus, btnRestart)) r.setEmpty()
        for (a in listOf(segIndoor, segLocation, segLayout, segPauseOpen, segPausePick, segOpen, segPick, segSpellOpen, segSpellPick, segClassLOpen, segClassLPick,
                         segClassROpen, segClassRPick, segChat, segQt,
                         segLimit, segFg, segMult, segPerf))
            for (r in a) r.setEmpty()
    }

    /** the part's height, laid out exactly as onDraw lays it out (the room's scroll asks; nothing here scrolls) */
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), Math.round(layoutHeight()))
    }

    private fun layoutHeight(): Float {
        val blocks = SettingsTabs.viewBlocks(tab)
        if (blocks.isEmpty()) return 0f
        return TOP_PAD + (if (tab == SettingsTabs.Tab.HOME) MODULES_HEAD else 0f) + blocks.sumOf { blockHeight(it).toDouble() }.toFloat() + SECTION_GAP * (blocks.size - 1) + BOTTOM_PAD
    }

    /** a block's height from its heading's y to its last row's bottom, laid out exactly as its draw function lays it out */
    private fun blockHeight(b: SettingsTabs.Block): Float = when (b) {
        SettingsTabs.Block.GAME -> 16f + ROW_H + (if (loginRow) 10f + ROW_H else 0f) + 10f + ROW_H
        SettingsTabs.Block.MAP -> 16f + ROW_H + 10f + ROW_H
        SettingsTabs.Block.INDOOR -> 16f + ROW_H + 10f + ROW_H + deferredListH()
        SettingsTabs.Block.CONTROLLER -> 16f + ROW_H + 10f + ROW_H
        SettingsTabs.Block.PAUSE_WHEEL, SettingsTabs.Block.ITEM_WHEEL, SettingsTabs.Block.SPELL_WHEEL,
        SettingsTabs.Block.CLASS_LEFT, SettingsTabs.Block.CLASS_RIGHT -> 16f + ROW_H + 10f + ROW_H
        SettingsTabs.Block.CHAT -> 16f + ROW_H
        SettingsTabs.Block.FRAMES -> 16f + fgHeight()
        else -> SettingsTabs.addonOf(b)?.let { a -> val n = addonRows(a); 16f + n * ROW_H + (n - 1) * 10f } ?: 0f
    }

    /** the Frame generation section's rows now (FrameGen.rows: the DLL row alone until one is set; Restart when it would change something) */
    private fun fgRows(): List<FrameGen.Row> = listOf(FrameGen.Row.LIMIT) + FrameGen.rows(FrameGen.hasDll(),
        FrameGen.showRestart(gameRunning(), SetupBridge.restarting, FrameGen.launched, FrameGen.current()) ||
            (gameRunning() && !SetupBridge.restarting && FrameGen.limitNeedsRestart(FrameGen.launchedLimit, FrameGen.fpsLimit)))

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
            btnReport.contains(x, y) -> when {
                ReportFlow.pending != null -> onReportOpenSaved()
                DiagLog.recording() -> onReportSendNow()
                else -> onReport()
            }
            btnReportDismiss.contains(x, y) -> ReportFlow.clearPending()
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
            segIndoor[0].contains(x, y) -> { Settings.indoorBuild = true; IndoorSchedulerStep.settingChanged() }
            segIndoor[1].contains(x, y) -> Settings.indoorBuild = false
            btnDeferred.contains(x, y) -> { deferredOpen = !deferredOpen; requestLayout(); invalidate() }
            segPauseOpen[0].contains(x, y) -> HomeSettings.pauseTap = true
            segPauseOpen[1].contains(x, y) -> HomeSettings.pauseTap = false
            segPausePick[0].contains(x, y) -> HomeSettings.pausePickA = true
            segPausePick[1].contains(x, y) -> HomeSettings.pausePickA = false
            segOpen[0].contains(x, y) -> HomeSettings.radialTap = true
            segOpen[1].contains(x, y) -> HomeSettings.radialTap = false
            segPick[0].contains(x, y) -> HomeSettings.radialPickA = true
            segPick[1].contains(x, y) -> HomeSettings.radialPickA = false
            segSpellOpen[0].contains(x, y) -> HomeSettings.spellTap = true
            segSpellOpen[1].contains(x, y) -> HomeSettings.spellTap = false
            segSpellPick[0].contains(x, y) -> HomeSettings.spellPickA = true
            segSpellPick[1].contains(x, y) -> HomeSettings.spellPickA = false
            segClassLOpen[0].contains(x, y) -> HomeSettings.classTapL = true
            segClassLOpen[1].contains(x, y) -> HomeSettings.classTapL = false
            segClassLPick[0].contains(x, y) -> HomeSettings.classPickAL = true
            segClassLPick[1].contains(x, y) -> HomeSettings.classPickAL = false
            segClassROpen[0].contains(x, y) -> HomeSettings.classTapR = true
            segClassROpen[1].contains(x, y) -> HomeSettings.classTapR = false
            segClassRPick[0].contains(x, y) -> HomeSettings.classPickAR = true
            segClassRPick[1].contains(x, y) -> HomeSettings.classPickAR = false
            addonTap(x, y) -> {}
            btnDll.contains(x, y) -> onChooseDll()
            btnRestart.contains(x, y) -> { btnRestart = RectF(); onRestart() }
            segFg[0].contains(x, y) -> FrameGen.enabled = false
            segFg[1].contains(x, y) -> if (FrameGen.hasDll()) FrameGen.enabled = true
            btnFlowMinus.contains(x, y) -> FrameGen.flowScale = FrameGen.stepFlow(FrameGen.flowScale, -1)
            btnFlowPlus.contains(x, y) -> FrameGen.flowScale = FrameGen.stepFlow(FrameGen.flowScale, +1)
            segLimit.any { it.contains(x, y) } -> FrameGen.askLimit(FrameGen.LIMITS[segLimit.indexOfFirst { it.contains(x, y) }])
            segPerf[0].contains(x, y) -> FrameGen.performance = true
            segPerf[1].contains(x, y) -> FrameGen.performance = false
            else -> {
                val mi = segMult.indexOfFirst { it.contains(x, y) }
                if (mi >= 0) { FrameGen.multiplier = FrameGen.MULTIPLIERS[mi]; invalidate(); return }
                val ci = segChat.indexOfFirst { it.contains(x, y) }
                val qi = segQt.indexOfFirst { it.contains(x, y) }
                if (qi >= 0) {
                    if (Settings.questTracking != Settings.QUEST_TRACKING[qi]) Settings.questTracking = Settings.QUEST_TRACKING[qi]
                    // an add-on not installed yet: choosing it installs it (the Add-ons tab's own Install; the app's route meanwhile)
                    qtAddon(qi)?.let { a -> if (!ThirdPartyAddons.coreInstalled(a)) ThirdPartyAddons.install(a) }
                    invalidate()
                }
                else if (ci >= 0) { if (Settings.chatDefaultInput != CHAT_INPUTS[ci]) Settings.chatDefaultInput = CHAT_INPUTS[ci] }
                else {
                    val oi = segLocation.indexOfFirst { it.contains(x, y) }
                    if (oi >= 0) { if (CastSettings.abxyLocation != LOCATIONS[oi]) CastSettings.abxyLocation = LOCATIONS[oi]; invalidate(); return }
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
        UiKit.asTitle(pSection); UiKit.asTitle(pSubHead); UiKit.asBody(pLabel); UiKit.asBody(pSub); UiKit.asBody(pBtn)
        UiKit.asNumber(pValue)
    }

    override fun onDraw(c: Canvas) {
        fonts()
        val w = width.toFloat()
        scroll = clampScroll(scroll)
        drawnScroll = scroll
        c.drawColor(Theme.ink)
        clearBoxes()
        var y = TOP_PAD - scroll       // everything below is laid out in screen coordinates
        // on Home, the modules' blocks stand under one heading of their own, below Home's rows (each block then a sub-heading)
        val modules = tab == SettingsTabs.Tab.HOME && SettingsTabs.viewBlocks(tab).isNotEmpty()
        if (modules) { c.drawText(SettingsTabs.HOME_MODULES, PAD_X, y + 4f, pSection); y += MODULES_HEAD }
        for ((i, b) in SettingsTabs.viewBlocks(tab).withIndex()) {
            if (i > 0) y += SECTION_GAP
            // the block's heading (its words are the tab data's), then its rows; each draw returns its last row's bottom
            c.drawText(b.heading, PAD_X, y + 4f, if (modules) pSubHead else pSection)
            y += 16f
            y = when (b) {
                SettingsTabs.Block.GAME -> drawGame(c, y)
                SettingsTabs.Block.MAP -> drawMap(c, y)
                SettingsTabs.Block.INDOOR -> drawIndoor(c, y)
                SettingsTabs.Block.CONTROLLER -> drawController(c, y)
                SettingsTabs.Block.PAUSE_WHEEL -> drawWheel(c, y, "Pause menu", "Press Start to open; it stays until B.",
                    "Hold Start; letting go closes it.", HomeSettings.pauseTap, HomeSettings.pausePickA, segPauseOpen, segPausePick)
                SettingsTabs.Block.ITEM_WHEEL -> drawWheel(c, y, "Item wheel", "Press D-pad Up to open; it stays until B.",
                    "Hold D-pad Up; letting go closes it.", HomeSettings.radialTap, HomeSettings.radialPickA, segOpen, segPick)
                SettingsTabs.Block.SPELL_WHEEL -> drawWheel(c, y, "Spell wheel", "Press its button to open; it stays until B.",
                    "Hold its button; letting go closes it.", HomeSettings.spellTap, HomeSettings.spellPickA, segSpellOpen, segSpellPick)
                SettingsTabs.Block.CLASS_LEFT -> drawWheel(c, y, "Left class radial", "Press LB + LT to open; it stays until B.",
                    "Hold LB + LT; letting go closes it.", HomeSettings.classTapL, HomeSettings.classPickAL, segClassLOpen, segClassLPick)
                SettingsTabs.Block.CLASS_RIGHT -> drawWheel(c, y, "Right class radial", "Press RB + RT to open; it stays until B.",
                    "Hold RB + RT; letting go closes it.", HomeSettings.classTapR, HomeSettings.classPickAR, segClassROpen, segClassRPick)
                SettingsTabs.Block.CHAT -> drawChat(c, y)
                SettingsTabs.Block.FRAMES -> drawFrames(c, y)
                else -> SettingsTabs.addonOf(b)?.let { drawAddon(c, y, it) } ?: y
            }
        }
        y += BOTTOM_PAD

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

    /** the segmented controls' common width (the longest glyph-set name) */
    private fun segW(): Float {
        pBtn.textSize = SEG_TEXT
        var w = SEG_MIN_W
        for (t in LAYOUT_LABELS) w = maxOf(w, pBtn.measureText(t) + SEG_PAD)
        pBtn.textSize = 27f
        return w
    }

    // ---- GAME (System): Reload, the saved login, the problem report
    private fun drawGame(c: Canvas, top: Float): Float {
        var y = top
        val now = SystemClock.elapsedRealtime()
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
        // "Send a problem report" (board f-problem-report): "Send report" while Record 5 minutes runs (stop + send), the time left on the
        // row; a report saved by itself shows its note with Open issue / Dismiss until one is pressed
        y += ROW_H + 10f
        val rec = DiagLog.recording()
        val left = DiagLog.recordLeftMs() / 1000
        val saved = ReportFlow.pending
        btnReportDismiss = RectF()
        btnReport = row(c, y, saved?.let { ReportFlow.noteTitle(it) } ?: "Problem report",
            when {
                saved != null && saved.sent > 0 -> "Open the issue to see it."
                saved != null -> "Open the GitHub issue to post it (the file is in Documents/DS Forever/reports)."
                rec -> "Recording… %d:%02d left. Send it when the problem happened.".format(left / 60, left % 60)
                ReportFlow.description.isNotBlank() -> "Your unsent report is kept: Send to finish it."
                else -> "Say what happened; DS Forever adds its logs, without names or logins."
            }, enabled = true) { box ->
            val b = RectF(box.right - 260f, box.centerY() - 34f, box.right - 16f, box.centerY() + 34f)
            UiKit.button(c, b, when { saved != null -> "Open issue"; rec -> "Send report"; else -> "Send" }, UiKit.Button.UP)
            if (saved != null) {
                btnReportDismiss = RectF(b.left - 16f - 200f, b.top, b.left - 16f, b.bottom)
                UiKit.button(c, btnReportDismiss, "Dismiss", UiKit.Button.UP)
                btnReportDismiss
            } else b
        }
        if (saved != null) { val d = btnReportDismiss; btnReport = RectF(d.right + 16f, d.top, d.right + 16f + 244f, d.bottom) }
        if (rec) postInvalidateDelayed(1000)
        return y + ROW_H
    }

    // ---- MAP (Rooms): the small map's two widths
    private fun drawMap(c: Canvas, top: Float): Float {
        var y = top
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
        y += ROW_H + 10f
        // QUEST TRACKING (the user, 2026-10-17): what the map's line, arrows and pin follow
        pBtn.textSize = SEG_TEXT
        var qtW = SEG_MIN_W
        for (t in QT_LABELS) qtW = maxOf(qtW, pBtn.measureText(t) + SEG_PAD)
        pBtn.textSize = 27f
        // Polestar / QuestTrail: what the add-on itself is doing, from the Add-ons registry (version, size, download / install)
        fun addonWord(a: ThirdPartyAddons.Addon, what: String): String {
            val st = ThirdPartyAddons.status(a, "core")
            val state = when (st.kind) {
                ThirdPartyAddons.Kind.INSTALLED -> "installed"
                ThirdPartyAddons.Kind.NONE -> "not installed (${ThirdPartyAddons.size(a.core.mb)})"
                else -> st.line.ifEmpty { "not installed" }
            }
            return "${a.title} ${a.version}: $state · $what"        // the state first: a long line is cut at its end
        }
        val sub = when (Settings.questTracking) {
            "polestar" -> addonWord(ThirdPartyAddons.POLESTAR, "walking routes")
            "questtrail" -> addonWord(ThirdPartyAddons.QUESTTRAIL, "the line follows its arrow, no game pin")
            else -> "DS Forever's own route: learned ways, the tram, known flights"
        }
        row(c, y, "Quest tracking", sub, enabled = true) { box ->
            val b = RectF(box.right - 16f - qtW * QT_LABELS.size, box.centerY() - 34f, box.right - 16f, box.centerY() + 34f)
            qtSegmented(c, b, Settings.QUEST_TRACKING.indexOf(Settings.questTracking))
            b
        }
        return y + ROW_H
    }

    // ---- INDOOR MAPS (Performance): the background building's switch and its progress
    private fun drawIndoor(c: Canvas, top: Float): Float {
        var y = top
        // the indoor maps' background building (the user's pick after the walking stutters: only standing still, and a switch)
        val indoorOn = Settings.indoorBuild
        pBtn.textSize = SEG_TEXT
        val indoorW = maxOf(SEG_MIN_W, pBtn.measureText("Off") + SEG_PAD)
        pBtn.textSize = 27f
        row(c, y, "Build in the background",
            if (indoorOn) "Only while you stand still and the device is cool." else "Off: nothing is built; ready maps still show.",
            enabled = true) { box ->
            val b = RectF(box.right - 16f - indoorW * 2, box.centerY() - 34f, box.right - 16f, box.centerY() + 34f)
            segmented(c, b, listOf("On", "Off"), if (indoorOn) 0 else 1, segIndoor)
            b
        }
        y += ROW_H + 10f
        // the progress (f-map-tracker): ready / total, the building now, what was deferred; the list opens in place
        val prog = progress
        val nDef = prog?.list?.size ?: 0
        btnDeferred = RectF()
        row(c, y, "Progress", IndoorProgress.line(prog, indoorOn), enabled = true) { box ->
            if (nDef == 0) RectF(box.right, box.centerY(), box.right, box.centerY())
            else {
                val label = if (deferredOpen) "Hide" else "Deferred ($nDef)"
                val w = maxOf(200f, pBtn.measureText(label) + 60f)
                val b = RectF(box.right - 16f - w, box.centerY() - 34f, box.right - 16f, box.centerY() + 34f)
                UiKit.button(c, b, label, UiKit.Button.UP)
                btnDeferred = b
                b
            }
        }
        y += ROW_H
        if (deferredOpen && nDef > 0 && prog != null) {
            val lb = RectF(16f, y + 4f, width - 16f, y + deferredListH())
            pFill.color = Theme.raised
            c.drawRoundRect(lb, 8f, 8f, pFill)
            pSub.color = Theme.textDim
            for ((i, line) in prog.list.withIndex())
                c.drawText(fit(pSub, line, lb.width() - 44f), lb.left + 22f, lb.top + 8f + (i + 1) * LIST_LINE - 10f, pSub)
            y += deferredListH()
        }
        return y
    }

    // ---- CONTROLLER: ABXY location, the glyphs, the wheels
    private fun drawController(c: Canvas, top: Float): Float {
        var y = top
        val layout = CastSettings.buttonLayout
        val segW = segW()
        // f-abxy-split (the user 2026-10-15): WHERE A / B / X / Y sit (the mapping), then which PICTURES they wear; any combination
        row(c, y, "ABXY location", abxyLine(CastSettings.faceSwap),
            enabled = true) { box ->
            val b = RectF(box.right - 16f - segW * LOCATIONS.size, box.centerY() - 34f, box.right - 16f, box.centerY() + 34f)
            segmented(c, b, LOCATION_LABELS, LOCATIONS.indexOf(CastSettings.abxyLocation), segLocation)
            b
        }
        y += ROW_H + 10f
        row(c, y, "Button glyphs", "The button pictures shown everywhere", enabled = true) { box ->
            val b = RectF(box.right - 16f - segW * LAYOUTS.size, box.centerY() - 34f,
                          box.right - 16f, box.centerY() + 34f)
            segmented(c, b, LAYOUT_LABELS, LAYOUTS.indexOf(layout), segLayout)
            b
        }
        return y + ROW_H
    }

    /**
     * THE WHEELS (the user 2026-10-09: "2 settings per radial. Tap VS hold and A to select vs release select"), a pair per wheel under
     * its own heading (f-radial-settings, 1.0.9: "better organize each radial setting into categories so its clear what each one is"):
     * [name]: open ([tap]: Tap / Hold, its two lines), pick ([pickA]: A / Release). App-wide, the same for every character
     */
    private fun drawWheel(c: Canvas, top: Float, name: String, tapLine: String, holdLine: String, tap: Boolean, pickA: Boolean,
                          segOpenBoxes: Array<RectF>, segPickBoxes: Array<RectF>): Float {
        var y = top
        val segW = segW()
        row(c, y, "$name: open", if (tap) tapLine else holdLine, enabled = true) { box ->
            val b = RectF(box.right - 16f - segW * 2, box.centerY() - 34f, box.right - 16f, box.centerY() + 34f)
            segmented(c, b, listOf("Tap", "Hold"), if (tap) 0 else 1, segOpenBoxes)
            b
        }
        y += ROW_H + 10f
        row(c, y, "$name: pick", if (pickA) "Aim with the stick, ${Glyphs.name("a")} picks." else "Aim, let the stick go to pick.",
            enabled = true) { box ->
            val b = RectF(box.right - 16f - segW * 2, box.centerY() - 34f, box.right - 16f, box.centerY() + 34f)
            segmented(c, b, listOf("A", "Release"), if (pickA) 0 else 1, segPickBoxes)
            b
        }
        return y + ROW_H
    }

    // ---- CHAT (Rooms): what opens in the message first (the user on b121)
    private fun drawChat(c: Canvas, top: Float): Float {
        var y = top
        pBtn.textSize = SEG_TEXT
        var chatW = SEG_MIN_W
        for (t in CHAT_INPUT_LABELS) chatW = maxOf(chatW, pBtn.measureText(t) + SEG_PAD)
        pBtn.textSize = 27f
        row(c, y, "Message input", "What opens when you go into the message", enabled = true) { box ->
            val b = RectF(box.right - 16f - chatW * CHAT_INPUTS.size, box.centerY() - 34f, box.right - 16f, box.centerY() + 34f)
            segmented(c, b, CHAT_INPUT_LABELS, CHAT_INPUTS.indexOf(Settings.chatDefaultInput), segChat)
            b
        }
        return y + ROW_H
    }

    // ---- FRAME LIMIT AND GENERATION (Performance; board f-frame-gen, n-fps-40): the player's own Lossless.dll, never shipped with
    // the app; Off = launches as always. Only the limit and the DLL rows until one is set; "Restart the game" while the running game
    // started with other settings (the user's request)
    private fun drawFrames(c: Canvas, top: Float): Float {
        var y = top
        // ---- FRAME GENERATION (board f-frame-gen): the player's own Lossless.dll, never shipped with the app; Off = launches as always.
        // Only the DLL row until one is set; "Restart the game" while the running game started with other settings (the user's request)
        val segW = segW()
        val hasDll = FrameGen.hasDll()
        val up = gameRunning()
        val fg = fgRows()
        if (fg.size != measuredFg) requestLayout()        // a row came or went: the room's scroll needs the new height
        btnDll = RectF(); btnFlowMinus = RectF(); btnFlowPlus = RectF(); btnRestart = RectF()
        for (r in segFg + segMult + segPerf + segLimit) r.setEmpty()   // hidden rows press nothing
        val on = FrameGen.enabled && hasDll
        val later = if (up) "Applies when the game restarts." else "Applies at the next game start."
        for ((i, r) in fg.withIndex()) {
            if (i > 0) y += ROW_H + 10f
            when (r) {
                FrameGen.Row.LIMIT -> {
                    val lim = FrameGen.fpsLimit
                    // the launcher's own limiter (its quick menu shows the same value); another value from there shows as it is
                    val what = when {
                        FrameGen.limitNote && lim == FrameGen.LIMIT_MIGRATED -> "Set to 40 for battery and heat; change it here. "
                        lim <= 0 -> "No limit. "
                        lim in FrameGen.LIMITS -> "At most $lim frames a second: cooler, longer on battery. "
                        else -> "$lim frames a second (set in the launcher's menu). "
                    }
                    row(c, y, "Frame limit", what + later, enabled = true) { box ->
                        val b = RectF(box.right - 16f - segW * FrameGen.LIMITS.size, box.centerY() - 34f, box.right - 16f, box.centerY() + 34f)
                        segmented(c, b, FrameGen.LIMITS.map { FrameGen.limitLabel(it) }, FrameGen.LIMITS.indexOf(lim), segLimit)
                        b
                    }
                }
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
                    val base = FrameGen.fpsLimit.takeIf { it > 0 } ?: 40
                    row(c, y, "Multiplier", "Frames shown per game frame ($base fps x$mult = ${base * mult})", enabled = on) { box ->
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
                FrameGen.Row.RESTART -> btnRestart = row(c, y, "Restart the game", "To use the new frame limit and frame generation settings", enabled = true) { box ->
                    val b = RectF(box.right - 260f, box.centerY() - 34f, box.right - 16f, box.centerY() + 34f)
                    UiKit.button(c, b, "Restart", UiKit.Button.UP)
                    b
                }
            }
        }
        return y + ROW_H
    }

    /**
     * ONE THIRD-PARTY ADD-ON (Settings > Add-ons, 1.0.9; the user on Spoken: modules opt-in, nothing downloaded by default, the size
     * shown first, the stop rules a setting): its own row (what it is, by whom, its licence, its pinned version), a row per module
     * with the game's check box and the size switching it on would download, then the companion's options for it.
     */
    /** the rows an add-on's block shows now: its own row; its modules and options only once it is installed (the user, test40:
     *  "The settings under spoken shouldn't show until after it's installed") */
    private fun addonRows(a: ThirdPartyAddons.Addon): Int =
        1 + (if (ThirdPartyAddons.coreInstalled(a)) a.modules.size + a.options.size else 0)

    private fun drawAddon(c: Canvas, top: Float, a: ThirdPartyAddons.Addon): Float {
        var y = top
        val K = ThirdPartyAddons.Kind.INSTALLED            // (the kinds read below, by name)
        val core = ThirdPartyAddons.status(a, "core")
        val installed = ThirdPartyAddons.coreInstalled(a)
        val wanted = ThirdPartyAddons.coreWanted(a)
        if (addonRows(a) != addonDrawnRows[a.id]) { addonDrawnRows[a.id] = addonRows(a); requestLayout() }
        if (core.kind == ThirdPartyAddons.Kind.DOWNLOADING || core.kind == ThirdPartyAddons.Kind.WAITING_WIFI) postInvalidateDelayed(1000)
        // ---- the add-on's own row: what it is; Install / Uninstall (the user: "an option here to install it in the first place"; the
        // questing ones too, test40: "Install from Add-ons"); once in, its state (in use or not, for one Map's Quest tracking uses)
        val who = "${a.what}. By ${a.by}, ${a.license}."
        val sub = when {
            core.kind == ThirdPartyAddons.Kind.NONE -> "$who ${ThirdPartyAddons.size(a.core.mb)}."
            core.kind == K && a.inUse != null -> "${core.line}. Version ${a.version}."
            core.kind == K -> "$who Version ${a.version}."
            else -> core.line
        }
        val label = if (installed || wanted) a.title else "Install ${a.title}"
        val btn = row(c, y, label, sub, enabled = true) { box ->
            val bw = 260f
            val r = RectF(box.right - 16f - bw, box.centerY() - 34f, box.right - 16f, box.centerY() + 34f)
            val armedOut = addonArmed == "${a.id}.uninstall" && SystemClock.elapsedRealtime() - addonArmedAt < ARM_MS
            when {
                // something of it is downloaded and waits for the game to start again: the launcher's own Restart, right here
                a.pkgs().any { (k, _) -> ThirdPartyAddons.status(a, k).kind == ThirdPartyAddons.Kind.DOWNLOADED } && gameRunning() -> {
                    UiKit.button(c, r, "Restart game", UiKit.Button.UP); addonRestart = r; RectF(r.left, r.top, r.left, r.top)
                }
                core.kind == ThirdPartyAddons.Kind.DOWNLOADING -> { if (core.pct >= 0) progressBar(c, r, core.pct); RectF(r.left, r.top, r.left, r.top) }
                installed && wanted -> { UiKit.button(c, r, if (armedOut) "Tap again" else "Uninstall", if (armedOut) UiKit.Button.DOWN else UiKit.Button.UP); r }
                installed -> { UiKit.button(c, r, "Keep it", UiKit.Button.UP); r }                 // moving out at the next start
                wanted -> { UiKit.button(c, r, "Cancel", UiKit.Button.UP); r }                     // waiting: Wi-Fi, room, a failure
                else -> { UiKit.button(c, r, "Install", UiKit.Button.UP); r }
            }
        }
        addonCoreBtn[a.id] = btn
        if (!installed) return y + ROW_H
        // ---- its modules and our options for it: only once it is installed
        for (m in a.modules) {
            y += ROW_H + 10f
            val on = ThirdPartyAddons.moduleOn(a, m)
            val st = ThirdPartyAddons.status(a, m.id)
            if (st.kind == ThirdPartyAddons.Kind.DOWNLOADING || st.kind == ThirdPartyAddons.Kind.WAITING_WIFI) postInvalidateDelayed(1000)
            val line = if (on) "${m.what}. ${st.line}." else "${m.what}. ${ThirdPartyAddons.size(m.pkg.mb)} download."
            val box = row(c, y, m.title, line, enabled = true) { box ->
                val r = RectF(box.right - 16f - 96f, box.centerY() - 40f, box.right - 16f, box.centerY() + 40f)
                if (on && st.kind == ThirdPartyAddons.Kind.DOWNLOADING && st.pct >= 0) progressBar(c, RectF(r.left - 220f, r.top + 12f, r.left - 20f, r.bottom - 12f), st.pct)
                UiKit.check(c, r.right - 38f, r.centerY(), 76f, on)
                r
            }
            addonModuleBoxes["${a.id}.${m.id}"] = box
        }
        val segW = segW()
        for (o in a.options) {
            y += ROW_H + 10f
            val on = ThirdPartyAddons.option(a, o)
            val segs = Array(2) { RectF() }
            row(c, y, o.title, if (on) o.on else o.off, enabled = true) { box ->
                val r = RectF(box.right - 16f - segW * 2, box.centerY() - 34f, box.right - 16f, box.centerY() + 34f)
                segmented(c, r, listOf("On", "Off"), if (on) 0 else 1, segs)
                r
            }
            addonOptionSegs["${a.id}.${o.id}"] = segs
        }
        return y + ROW_H
    }
    private val addonDrawnRows = HashMap<String, Int>()

    /** a download's progress, in the game's own bar art when the kit has it: [pct] 0..100 (< 0 = waiting) */
    private fun progressBar(c: Canvas, r: RectF, pct: Int) {
        pFill.color = Theme.ink; c.drawRoundRect(r, 6f, 6f, pFill)
        val k = (pct.coerceIn(0, 100)) / 100f
        pFill.color = Theme.gold
        if (k > 0f) c.drawRoundRect(RectF(r.left + 3f, r.top + 3f, r.left + 3f + (r.width() - 6f) * k, r.bottom - 3f), 4f, 4f, pFill)
        pStroke.color = Theme.bronze; c.drawRoundRect(r, 6f, 6f, pStroke)
        pValue.color = Theme.text
        c.drawText(if (pct >= 0) "$pct%" else "...", r.centerX(), r.centerY() + 10f, pValue)
    }

    private val addonCoreBtn = HashMap<String, RectF>()
    private var addonRestart = RectF()
    private var addonArmed: String? = null
    private var addonArmedAt = 0L

    /** a press on an Add-ons control; true = it was one */
    private fun addonTap(x: Float, y: Float): Boolean {
        if (!addonRestart.isEmpty && addonRestart.contains(x, y)) { onRestart(); return true }
        for (a in ThirdPartyAddons.ALL) {
            addonCoreBtn[a.id]?.takeIf { !it.isEmpty && it.contains(x, y) }?.let {
                val installed = ThirdPartyAddons.coreInstalled(a)
                val wanted = ThirdPartyAddons.coreWanted(a)
                if (installed && wanted) {
                    // Uninstall: twice, as Reload (its folders go to the backup at the next start, never deleted)
                    val now = SystemClock.elapsedRealtime()
                    if (addonArmed == "${a.id}.uninstall" && now - addonArmedAt < ARM_MS) { addonArmed = null; ThirdPartyAddons.setCore(a, false) }
                    else { addonArmed = "${a.id}.uninstall"; addonArmedAt = now; postDelayed({ invalidate() }, ARM_MS + 50) }
                } else ThirdPartyAddons.setCore(a, !wanted)
                return true
            }
            for (m in a.modules) if (addonModuleBoxes["${a.id}.${m.id}"]?.contains(x, y) == true) {
                ThirdPartyAddons.setModule(a, m, !ThirdPartyAddons.moduleOn(a, m)); return true
            }
            for (o in a.options) addonOptionSegs["${a.id}.${o.id}"]?.let { s ->
                if (s[0].contains(x, y)) { ThirdPartyAddons.setOption(a, o, true); return true }
                if (s[1].contains(x, y)) { ThirdPartyAddons.setOption(a, o, false); return true }
            }
        }
        return false
    }

    // 96, not 104: with three sections the page came to 1024 px in a ~970 px room, and the last row
    // sat cut off at the bottom looking like a layout bug. At 96 it all fits (~960); the scrolling
    // stays for whatever section comes next.
    private val ROW_H = 96f

    /** a labelled row; [control] draws the right-hand control and returns its hit box */
    private fun row(c: Canvas, top: Float, label: String, sub: String, enabled: Boolean,
                    control: (RectF) -> RectF): RectF {
        val box = RectF(16f, top, width - 16f, top + ROW_H)
        rowHits.add(RectF(box) to label)
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

    /** the add-on behind Quest tracking's segment [i] (QuestTrail, Polestar), or null (Vanilla) */
    private fun qtAddon(i: Int): ThirdPartyAddons.Addon? = when (Settings.QUEST_TRACKING.getOrNull(i)) {
        "polestar" -> ThirdPartyAddons.POLESTAR; "questtrail" -> ThirdPartyAddons.QUESTTRAIL; else -> null }

    /**
     * Quest tracking's three segments (the user: Polestar / QuestTrail installed from Settings > Add-ons; the picker "offers the
     * installed ones" and marks the others): an add-on not installed is drawn greyed with "not installed" (or "installing" once
     * asked) under its name; it can still be chosen, which installs it ([qtAddon], ThirdPartyAddons.install).
     */
    private fun qtSegmented(c: Canvas, b: RectF, sel: Int) {
        val w = b.width() / QT_LABELS.size
        for (i in QT_LABELS.indices) {
            segQt[i].set(b.left + i * w, b.top, b.left + (i + 1) * w, b.bottom)
            val r = RectF(segQt[i].left + 2f, b.top - 6f, segQt[i].right - 2f, b.bottom + 6f)
            val a = qtAddon(i)
            if (a == null || ThirdPartyAddons.coreInstalled(a)) { UiKit.choice(c, r, QT_LABELS[i], i == sel, textSize = SEG_TEXT); continue }
            val asked = ThirdPartyAddons.status(a, "core").kind != ThirdPartyAddons.Kind.NONE
            UiKit.choice(c, r, "", i == sel, enabled = i == sel, textSize = SEG_TEXT)
            val p1 = UiKit.bodyPaint(SEG_TEXT - 2f, if (i == sel) Theme.text else Theme.dim).apply { textAlign = Paint.Align.CENTER }
            val p2 = UiKit.bodyPaint(15f, Theme.dim).apply { textAlign = Paint.Align.CENTER }
            c.drawText(QT_LABELS[i], r.centerX(), r.centerY() - 2f, p1)
            c.drawText(if (asked) "installing" else "not installed", r.centerX(), r.centerY() + 20f, p2)
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
        private val LOCATIONS = CastSettings.AbxyLocation.values().toList()
        private val LOCATION_LABELS = LOCATIONS.map { it.label }
        /** where each face action sits, in the chosen set's words ("A right, B bottom, ..." / "Cross right, Circle bottom, ...") */
        fun abxyLine(swap: Boolean, layout: CastSettings.ButtonLayout = CastSettings.buttonLayout): String {
            fun n(a: String) = Glyphs.name(a, layout)
            return if (swap) "${n("a")} right, ${n("b")} bottom, ${n("x")} top, ${n("y")} left"
                else "${n("a")} bottom, ${n("b")} right, ${n("x")} left, ${n("y")} top"
        }
        private val CHAT_INPUTS = listOf("quick", "keys")
        private val CHAT_INPUT_LABELS = listOf("Quick chat", "Keyboard")
        private val QT_LABELS = listOf("Vanilla", "QuestTrail", "Polestar")
        private const val SEG_TEXT = 24f
        private const val SEG_PAD = 36f      // text-to-edge room inside a segment, both sides
        private const val SEG_MIN_W = 120f
        // SPACING, measured against the signed-off picture on the emulator (2026-10-06): a row's bottom to the next heading's top
        // is 60 px everywhere; a heading's cap top is ~26 px above its baseline, which is drawn 4 px below the section's y
        private const val MODULES_HEAD = 58f  // "Home module settings" -> its first block's heading
        private const val SECTION_GAP = 82f  // row bottom -> the next section's y (its heading baseline 86 below: cap top +60)
        private const val TOP_PAD = 48f      // the page's top -> its first heading's y (the tab bar stands above the page)
        private const val PAD_X = 28f
        private const val BOTTOM_PAD = 56f   // REST's end -> Game UI's first heading: 60 (it was 28)
    }
}
