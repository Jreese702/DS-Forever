package com.jrees.wowcompanion

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/**
 * THE SETTINGS ROOM'S WIRING AND ITS LAST OWN ROWS. Since the beautify pass (2026-11-17; the user: "I REALLY REALLY love what you did
 * with the top screen settings menu with the pictures and want to do something similar to the other menus") every tab is picture tiles
 * drawn by its own part (GeneralTilesView, BottomTilesView, GameUiSection, ControllerTiles, AddonTiles, PerformanceTiles). This view is:
 *
 * - THE HOST'S WIRING HUB (the one CompanionHost builds and SettingsRoom takes): the login, the report, the DLL picker, Restart, the
 *   layout editor, the add-on settings and import pickers, Smart Y's padmap and taps. The tiles call these through it. The hub itself is
 *   never drawn.
 * - ITS SLICES ([slice]): the rows that are still lists, each a slice drawing ONLY its blocks ([LIVE]): Add-ons' Import button, its
 *   Voice and Imported lists, and the Smart Y list (Controller > Smart Y > List, the sub-page: it moved AS IS, by the user's 11-11
 *   ruling). Every other block is a tile part's; the old row code for them is gone.
 *
 * A slice MEASURES ITS OWN HEIGHT and never scrolls (the room's scroll does). Controls fire on the finger LIFTING, and only when the
 * finger stayed within the system touch slop of where it landed: a drag of the room's scroll never presses anything.
 */
class SettingsView(context: Context, val onReload: () -> Unit) : View(context) {

    /**
     * THE SIX TABS' GROUPS: the room draws each group's list rows with a SLICE of this view ([slice]) that draws ONLY [only]'s blocks. A
     * slice takes its host's wiring ([hub]), and the host's invalidate / requestLayout / partyChanged / onSmartAck reach every slice.
     * The host (null) draws nothing.
     */
    var only: Set<SettingsTabs.Block>? = null
        set(v) { field = v; requestLayout(); invalidate() }
    private fun blocksHere(): List<SettingsTabs.Block> =
        only?.let { o -> SettingsTabs.Block.values().filter { it in o && it in LIVE && SettingsTabs.shown(it) } } ?: emptyList()

    /** the view whose wiring this slice uses (null = its own: the host's view) */
    private var hub: SettingsView? = null
    /** the slices made from this view (the host's): its invalidate / requestLayout and the host's calls reach them */
    private var slices: ArrayList<SettingsView>? = null
    /** a view drawing only [blocks], wired through this one (the room's groups) */
    fun slice(blocks: Set<SettingsTabs.Block>): SettingsView = SettingsView(context, onReload).also { s ->
        s.hub = this
        s.only = blocks
        (slices ?: ArrayList<SettingsView>().also { slices = it }).add(s)
    }

    override fun invalidate() { super.invalidate(); slices?.forEach { it.invalidate() } }
    override fun postInvalidate() { super.postInvalidate(); slices?.forEach { it.postInvalidate() } }
    override fun requestLayout() { super.requestLayout(); slices?.forEach { it.requestLayout() } }

    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
    private val pLabel by themed { Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 31f; color = Theme.text } }
    private val pSub by themed { Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; color = Theme.textDim } }
    private val pValue = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 30f; textAlign = Paint.Align.CENTER; color = Theme.text
    }

    // ---------------------------------------------------------------- the host's wiring (the tiles call it through the hub)

    /**
     * THE SAVED BATTLE.NET LOGIN, CHANGED HERE (the user 2026-10-08: "Change saved login" moves to Settings; the start screen's row
     * shows only when needed). [loginRow] = the row is there (the host sets it once); [loginSaved] = a login is saved; [onLogin] opens
     * the host's login prompt. General's Account tiles draw it.
     */
    var loginRow = false
        get() = hub?.loginRow ?: field
        set(v) { if (field != v) { field = v; requestLayout(); invalidate(); onContent?.invoke() } }
    /** a row that decides whether a group shows came or went (the login row, a list's rows): the room shows / hides the group */
    var onContent: (() -> Unit)? = null
    var loginSaved: () -> Boolean = { false }
        get() = hub?.loginSaved ?: field
    var onLogin: () -> Unit = {}
        get() = hub?.onLogin ?: field
    /** "Send a problem report" (board f-problem-report): the host opens the report page; [onReportSendNow] while recording; the note */
    var onReport: () -> Unit = {}
        get() = hub?.onReport ?: field
    var onReportSendNow: () -> Unit = {}
        get() = hub?.onReportSendNow ?: field
    var onReportOpenSaved: () -> Unit = {}
        get() = hub?.onReportOpenSaved ?: field
    /** Top screen > Layout > Edit layout (the layout editor; the user, 2026-11-16): the host opens its page over this room */
    var onEditLayout: () -> Unit = {}
        get() = hub?.onEditLayout ?: field
    /**
     * FRAME GENERATION (board f-frame-gen): [onChooseDll] opens the host's file picker ON THIS (bottom) SCREEN; [gameRunning] = the
     * game is up; [onRestart] = "Restart the game" (the launcher's restart: close, wait, Play again; SetupBridge.restartGame)
     */
    var onChooseDll: () -> Unit = {}
        get() = hub?.onChooseDll ?: field
    var gameRunning: () -> Boolean = { false }
        get() = hub?.gameRunning ?: field
    var onRestart: () -> Unit = {}
        get() = hub?.onRestart ?: field
    /** an add-on's own settings, on the bottom screen (Manners' / QuestTrail's): the host leaves Settings and asks the game to open them */
    var onAddonSettings: (ThirdPartyAddons.Addon) -> Unit = {}
        get() = hub?.onAddonSettings ?: field
    /** General > Help > Support the Project (SupportLink): the host opens [url] in the browser as its other links; false = no browser */
    var onOpenUrl: (String) -> Boolean = { false }
        get() = hub?.onOpenUrl ?: field
    /** General > Reset (ResetPlan, 2026-11-19): the room opens the button's Yes / No pop-up ([whoKnown]: a character to reset) */
    var onResetAsk: (ResetPlan.Button, Boolean) -> Unit = { _, _ -> }
        get() = hub?.onResetAsk ?: field
    /** the pop-up's Yes: the host runs the reset (ResetFlow), [all] = every character */
    var onReset: (ResetPlan.Button, Boolean) -> Unit = { _, _ -> }
        get() = hub?.onReset ?: field
    /** the character playing now (or last played), "Name-Realm"; null = none known */
    var resetWho: () -> String? = { null }
        get() = hub?.resetWho ?: field
    /** a reset's word on its button now (ResetFlow.word), null = its own name */
    var resetWord: (ResetPlan.Button) -> String? = { null }
        get() = hub?.resetWord ?: field
    /** a reset is under way (its answer awaited) */
    var resetBusy: () -> Boolean = { false }
        get() = hub?.resetBusy ?: field

    private val ARM_MS = 3000L

    // ---- the finger: a press on lift, never after a drag
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var activeId = -1               // pointer being tracked; -1 = none
    private var downX = 0f; private var downY = 0f
    private var dragging = false            // the finger left the slop circle: this gesture is the room's scroll
    private var tapVoid = false             // a second finger came down: this gesture is no tap

    init {
        // the stores the room's parts read (this view is built first, as the host's hub: each init here once, as before the tiles)
        Theme.init(context)
        context.atHostEnd(Theme.onChange { postInvalidate() })
        ThirdPartyAddons.init(context)
        VoiceModels.init(context)
        context.atHostEnd(VoiceModels.onChange { postInvalidate() })
        ImportedAddons.init(context)
        context.atHostEnd(ImportedAddons.onChange { post { requestLayout(); invalidate() } })   // the worker's thread: the card's rows come and go
        context.atHostEnd(Settings.onChange { postInvalidate() })
        ReportFlow.init(context)
        FrameGen.init(context)
        CastSettings.init(context)
    }

    /**
     * The controller's targets: every button as drawn, in reading order. Read-only: the cursor presses a button with a touch at its
     * centre, so this view's own logic decides.
     */
    fun focusTargets(): List<RectF> =
        (importFocus() + VoiceModels.Model.values().mapNotNull { voiceModelBtn[it] } + sbHits.map { it.first })
            .filter { !it.isEmpty }.map { RectF(it) }.let { controls ->
                // ... and a row with nothing to press (an import card's row) is still a stop: the cursor walks every row it can see,
                // A there does nothing
                controls + rowHits.map { it.first }.filter { row -> !row.isEmpty && controls.none { row.contains(it.centerX(), it.centerY()) } }
                    .map { RectF(it) }
            }

    /** the rows as last drawn, with their words */
    private val rowHits = ArrayList<Pair<RectF, String>>()

    /** the row at ([x], [y]) in this view, by its words; null = none */
    fun rowAt(x: Float, y: Float): String? = rowHits.firstOrNull { it.first.contains(x, y) }?.second

    /** every hit box empty: a block not drawn presses nothing */
    private fun clearBoxes() {
        rowHits.clear(); sbHits.clear()
        btnImport.setEmpty(); btnImportInstall.setEmpty(); btnImportCancel.setEmpty(); importRemove.clear(); importUnqueue.clear()
        voiceModelBtn.clear()
    }

    /** the part's height, laid out exactly as onDraw lays it out (the room's scroll asks; nothing here scrolls) */
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val h = Math.round(layoutHeight())
        // a part shrunk to nothing is never drawn again (a 0 px child is skipped): its last boxes must not stay pressable
        if (h == 0) clearBoxes()
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), h)
    }

    /** this view has rows to draw now (the room hides a group whose parts have none) */
    fun hasContent(): Boolean = layoutHeight() > 0f

    private fun layoutHeight(): Float {
        val blocks = drawnBlocks()
        if (blocks.isEmpty()) return 0f
        var h = SLICE_PAD
        for ((i, b) in blocks.withIndex()) {
            if (i > 0) h += BLOCK_GAP
            h += blockHeight(b)
        }
        return h + SLICE_PAD
    }

    /** the blocks drawn now (a block with nothing to show - an empty Imported list - none) */
    private fun drawnBlocks(): List<SettingsTabs.Block> = blocksHere().filter { blockHeight(it) > 0f }

    /** a block's rows' height (from its first row's top to its last row's bottom), laid out exactly as its draw function lays it out */
    private fun blockHeight(b: SettingsTabs.Block): Float = when (b) {
        SettingsTabs.Block.SMART_Y -> sbLines().size.let { n -> n * ROW_H + (n - 1) * 10f } + pbHeight()
        SettingsTabs.Block.ADDON_VOICE -> VoiceModels.Model.values().size.let { n -> n * ROW_H + (n - 1) * 10f }
        SettingsTabs.Block.IMPORT -> importHeight()
        SettingsTabs.Block.IMPORTED -> importedRows().let { n -> if (n == 0) 0f else n * ROW_H + (n - 1) * 10f }
        else -> 0f
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeId = e.getPointerId(0)
                downX = e.x; downY = e.y
                dragging = false; tapVoid = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> tapVoid = true
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(activeId)
                if (i >= 0 && Math.hypot((e.getX(i) - downX).toDouble(), (e.getY(i) - downY).toDouble()) >= touchSlop) dragging = true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // the tracked finger lifted while another stays down: end this gesture here
                if (e.getPointerId(e.actionIndex) == activeId) { activeId = -1; dragging = false }
            }
            MotionEvent.ACTION_UP -> {
                val isTap = activeId == e.getPointerId(0) && !dragging && !tapVoid &&
                    Math.hypot((e.x - downX).toDouble(), (e.y - downY).toDouble()) < touchSlop
                activeId = -1; dragging = false
                // isShown: firing on LIFT means a finger held here while another finger switched rooms on the strip would otherwise
                // press a control on a page that is no longer on screen
                if (isTap && isShown) tap(downX, downY)
            }
            MotionEvent.ACTION_CANCEL -> { activeId = -1; dragging = false }
        }
        return true
    }

    /** one control press at ([x], [y]); only ever called for a finger that did not drag */
    private fun tap(x: Float, y: Float) {
        sbHits.firstOrNull { it.first.contains(x, y) }?.let { it.second(); requestLayout(); invalidate(); return }
        if (voiceModelTap(x, y) || importTap(x, y)) { invalidate(); return }
        invalidate()
    }

    /** the game's type (UI art, fonts step). Set where the paints are USED: the kit can reach the device after this view is built, and a
     *  paint keeps the font it was given. */
    private fun fonts() {
        UiKit.asBody(pLabel); UiKit.asBody(pSub); UiKit.asNumber(pValue)
    }

    override fun onDraw(c: Canvas) {
        fonts()
        // no page ink: the room's RoomGround.settings (the dimmed rock) shows through (beautify 8.4)
        clearBoxes()
        var y = SLICE_PAD
        for ((i, b) in drawnBlocks().withIndex()) {
            if (i > 0) y += BLOCK_GAP
            // each draw returns its last row's bottom
            y = when (b) {
                SettingsTabs.Block.SMART_Y -> drawSmartY(c, y)
                SettingsTabs.Block.ADDON_VOICE -> drawVoiceModels(c, y)
                SettingsTabs.Block.IMPORT -> drawImport(c, y)
                SettingsTabs.Block.IMPORTED -> drawImported(c, y)
                else -> y
            }
        }
    }

    // ---- SMART Y (Spells' PadMap m62; the user: "logic rows, with the bottom row being an Add button ... on a single row I need to be
    // able to add an "or""). m127 FACELIFT (the user, 2026-11-11, picks "Buttons on every row" and "less verbose and more visual ... the
    // Or/takeout/remove buttons shouldnt be hidden after tap, they should be integrated better"): ONE ordered list - Food, Water,
    // Manners and the buff rows, in the player's order ("it shouldnt hardcode food and water as first and second") - each row its
    // pictures (an OR row's buffs as chained slots, each slot's x takes that buff out), a short name and tag, then its own Up / Down /
    // Or (buff rows) / X, always shown (this section's exception to the select -> ONE action rule, by the user's pick); "Add a buff"
    // last. The add-on keeps the list per character; every change is ONE batch of taps (SmartYModel); its smartyack answers (a refusal
    // is said on the Add row), and the next padmap redraws the section.

    /** the padmap's Smart buffs (the host's: the latest padmap); null = no game yet, or an add-on before m62 */
    var smartDoc: () -> SmartYModel.Doc? = { null }
        get() = hub?.smartDoc ?: field
    /** m165: the last current padmap's B pet follow (`pf`: a pet class shows the row); null = no game yet, or an add-on before m165 */
    var petB: () -> PadMapModel.PetB? = { null }
        get() = hub?.petB ?: field
    /** m127: the kind of what Y would do now (the padmap's `smarty` k: food / drink / buff / or / manners); null = nothing */
    var smartNow: () -> String? = { null }
        get() = hub?.smartNow ?: field
    /** sends one batch of taps (the host's TapBridge); false = not sent */
    var smartTaps: (List<Pair<String, Int>>) -> Boolean = { false }
        get() = hub?.smartTaps ?: field
    /** a spell's two taps on the "spells" row by its name (the host's book); null = none known */
    var smartSpellCells: (String) -> List<Pair<String, Int>>? = { null }
        get() = hub?.smartSpellCells ?: field
    /** the row an "Or" is choosing for (while [sbChoose] is "addor") */
    private var sbSel: Int? = null
    private var sbChoose: String? = null
    private var sbNote: String? = null
    private val sbHits = ArrayList<Pair<RectF, () -> Unit>>()

    /** m127: the add-on's answer to an edit (smartyack): a refusal is said on the Add row; a done edit clears the note */
    fun onSmartAck(op: String, ok: Boolean, why: String?) {
        slices?.forEach { it.onSmartAck(op, ok, why) }
        if (op == "on" || op == "off") return
        sbNote = if (ok) null else (why?.takeIf { it.isNotBlank() } ?: "Not taken")
        invalidate()
    }

    /** the section's lines, in order: "none", "toggle", a row's index, "add"; while choosing: a Buff or a kind ("k:food"), "cancel" */
    private fun sbLines(): List<Any> {
        val d = smartDoc() ?: return listOf("none")
        // m76: the On / Off row first; the rest only while Smart Y is on (the user: "hide the other settings until it's turned on")
        if (!SmartYModel.rowsShown(d)) return listOf("toggle")
        val out = ArrayList<Any>(listOf("toggle"))
        if (d.fixedFood) { out += "food"; out += "water" }                  // an add-on before m127: Food / Water fixed on top
        for (r in d.rows.indices) out += r
        val choose = sbChoose
        if (choose == null) { out += "add"; return out }
        val sel = sbSel
        when {
            choose == "add" -> { SmartYModel.kindChoices(d).forEach { out += "k:$it" }; out.addAll(SmartYModel.addChoices(d)) }
            sel != null && choose == "addor" -> out.addAll(SmartYModel.orChoices(d, sel))
        }
        out += "cancel"
        return out
    }

    private fun sbSend(taps: List<Pair<String, Int>>?, done: String) {
        sbNote = when {
            taps == null -> "Open the Spellbook once, then again"
            smartTaps(taps) -> done
            else -> "Game not live"
        }
        sbSel = null; sbChoose = null
    }

    private fun sbButton(c: Canvas, box: RectF, label: String, act: () -> Unit) {
        UiKit.choice(c, RectF(box.left + 2f, box.top - 6f, box.right - 2f, box.bottom + 6f), label, false, textSize = SEG_TEXT)
        sbHits += RectF(box) to act
    }

    /** an up / down button: the game's button plate with its gold arrow; greyed with no hit at the list's end */
    private fun sbArrow(c: Canvas, b: RectF, up: Boolean, enabled: Boolean, act: () -> Unit) {
        UiKit.choice(c, b, "", false, enabled = enabled)
        val size = 26f
        // moveArrowsUpDown draws the up arrow size * 0.62 above its box's middle (the down one below): shifted so it sits in [b]'s middle
        val shift = if (up) size * 0.62f else -size * 0.62f
        UiKit.moveArrowsUpDown(c, RectF(b.left, b.top + shift, b.right, b.bottom + shift), up = up, down = !up, x = b.centerX(),
            size = size, alpha = if (enabled) 255 else 90)
        if (enabled) sbHits += RectF(b) to act
    }

    /** a slot with a kind's picture ([icon]; Manners with no picture: its gold M) */
    private fun sbSlot(c: Canvas, icon: Int, x: Float, y: Float, s: Float, mark: String?) {
        val bmp = Icons.get(icon)
        UiKit.slot(c, bmp, x, y, s)
        if (bmp == null && mark != null) {
            val p = UiKit.titlePaint(s * 0.5f, Theme.gold).apply { textAlign = Paint.Align.CENTER }
            c.drawText(mark, x + s / 2f, y + s / 2f - (p.ascent() + p.descent()) / 2f, p)
        }
    }

    /** one row of the list: its pictures, its name and tag, its four buttons */
    private fun drawSbRow(c: Canvas, d: SmartYModel.Doc, r: Int, y: Float) {
        val box = RectF(16f, y, width - 16f, y + ROW_H)
        val row = d.rows[r]
        val label = SmartYModel.label(d, r)
        rowHits.add(RectF(box) to label)
        pFill.color = Theme.raised
        c.drawRoundRect(box, 8f, 8f, pFill)
        if (sbChoose == "addor" && sbSel == r) UiKit.selectedRow(c, box)
        // the buttons, right to left: X, Or (buff rows), Down, Up
        val bs = 72f; val gap = 8f; val cy = box.centerY()
        var bx = box.right - 14f - bs
        val sq = { RectF(bx, cy - bs / 2f, bx + bs, cy + bs / 2f) }
        val canEdit = r < d.max
        val xr = sq()
        UiKit.square(c, "exit", xr.centerX(), xr.centerY(), bs, if (canEdit) UiKit.Button.UP else UiKit.Button.DISABLED)
        if (canEdit) sbHits += RectF(xr) to { sbSend(SmartYModel.rowTaps(d, r, "remove"), "Removed") }
        bx -= bs + gap
        if (row.isBuff) {
            val orr = sq()
            val canOr = canEdit && row.ids.size < SmartYModel.MAX_OR
            UiKit.choice(c, orr, "Or", false, enabled = canOr, textSize = SEG_TEXT)
            if (canOr) sbHits += RectF(orr) to { sbSel = r; sbChoose = "addor"; sbNote = null }
            bx -= bs + gap
        }
        val down = sq()
        sbArrow(c, down, up = false, enabled = canEdit && r < d.rows.size - 1) { sbSend(SmartYModel.rowTaps(d, r, "down"), "Moved") }
        bx -= bs + gap
        val upB = sq()
        sbArrow(c, upB, up = true, enabled = canEdit && r > 0) { sbSend(SmartYModel.rowTaps(d, r, "up"), "Moved") }
        val right = upB.left - 16f
        // the pictures: one slot, or an OR row's chained slots (each with its x: take that buff out)
        val s = ROW_H - 30f
        var x = box.left + 14f
        val sy = box.top + 15f
        val icons = SmartYModel.icons(d, r)
        val names = SmartYModel.names(d, r)
        val or = SmartYModel.isOr(d, r)
        val maxSlots = ((right - x - 160f) / (s + 18f)).toInt().coerceAtLeast(1)
        for ((k, icon) in icons.withIndex()) {
            if (k >= maxSlots) break
            if (k > 0) {                                                    // the chain between two slots
                pFill.color = Theme.gold
                c.drawRect(x - 18f, sy + s / 2f - 2f, x, sy + s / 2f + 2f, pFill)
            }
            if (row.isBuff) UiKit.slot(c, Icons.get(icon), x, sy, s, unusable = NoMana.has(row.ids[k]))
            else sbSlot(c, icon, x, sy, s, if (row.kind == SmartYModel.MANNERS) "M" else null)
            if (or) {
                val n = names[k]
                UiKit.closeX(c, x + s - 4f, sy + 4f, 30f)
                sbHits += RectF(x, sy, x + s, sy + s) to {
                    sbSend(SmartYModel.spellTaps(d, r, "out", smartSpellCells(n)), "Taken out")
                }
            }
            x += s + 18f
        }
        if (icons.size > maxSlots) {
            val p = UiKit.numberPaint(24f, Theme.textDim)
            c.drawText("+${icons.size - maxSlots}", x, cy + 9f, p); x += p.measureText("+${icons.size - maxSlots}") + 12f
        }
        // the name (gold: what Y does now) and its tag
        val tx = x + 6f
        val now = SmartYModel.nowRow(d, smartNow()) == r
        pLabel.color = if (now) Theme.gold else Theme.text
        // a spell's name is the game's text (never through Tr.t: menus only); a kind's name is ours
        val shown = if (or) names.joinToString(" / ") else if (row.isBuff) label else Tr.t(label)
        c.drawText(fit(pLabel, shown, right - tx), tx, box.top + 41f, pLabel)
        val tag = when {
            !row.isBuff -> SmartYModel.kindTag(row.kind)
            row.ids.any { NoMana.has(it) } -> "no mana"
            or -> "any one"
            d.list.firstOrNull { it.id == row.ids[0] }?.imbue == true -> "weapon"
            else -> null
        }
        if (tag != null) {
            pSub.color = if (tag == "no mana") Theme.questRed else Theme.textDim
            c.drawText(Tr.t(tag), tx, box.top + 75f, pSub)
        }
    }

    private fun drawSmartY(c: Canvas, top: Float): Float {
        var y = top
        val d = smartDoc()
        val lines = sbLines()
        for ((i, line) in lines.withIndex()) {
            if (i > 0) y += ROW_H + 10f
            val box = RectF(16f, y, width - 16f, y + ROW_H)
            when {
                line == "none" -> row(c, y, "Smart Y", "Needs the game running", enabled = false) { RectF(it.right - 16f, it.top, it.right - 16f, it.bottom) }
                line == "toggle" && d != null -> {
                    row(c, y, "Smart Y", if (d.on) "Y: your list, top first" else "Off", enabled = true) { b ->
                        val bw = 300f
                        val bb = RectF(b.right - 16f - bw, b.centerY() - 30f, b.right - 16f, b.centerY() + 30f)
                        val hw = bw / 2f
                        for ((k, pair) in listOf("On" to true, "Off" to false).withIndex()) {
                            val sb = RectF(bb.left + k * hw, bb.top, bb.left + (k + 1) * hw, bb.bottom)
                            UiKit.choice(c, RectF(sb.left + 2f, sb.top - 6f, sb.right - 2f, sb.bottom + 6f), pair.first, d.on == pair.second, textSize = SEG_TEXT)
                            sbHits += RectF(sb) to {
                                if (d.on != pair.second) sbSend(SmartYModel.toggleTaps(d, pair.second), if (pair.second) "On" else "Off")
                            }
                        }
                        bb
                    }
                }
                // an add-on before m127 only: Food / Water fixed on top
                line == "food" -> row(c, y, "Food", "< 80%", enabled = true) { RectF(it.right - 16f, it.top, it.right - 16f, it.bottom) }
                line == "water" -> row(c, y, "Water", "< 80%", enabled = true) { RectF(it.right - 16f, it.top, it.right - 16f, it.bottom) }
                line is Int && d != null -> drawSbRow(c, d, line, y)
                line == "add" && d != null -> {
                    val full = SmartYModel.full(d)
                    row(c, y, "Add a buff", sbNote ?: if (full) "${d.max} at most" else "", enabled = !full) { b ->
                        val bb = RectF(b.right - 14f - 72f, b.centerY() - 36f, b.right - 14f, b.centerY() + 36f)
                        UiKit.square(c, "plus", bb.centerX(), bb.centerY(), 72f, if (full) UiKit.Button.DISABLED else UiKit.Button.UP)
                        if (!full) sbHits += RectF(box) to { sbChoose = "add"; sbSel = null; sbNote = null }
                        bb
                    }
                }
                line is String && line.startsWith("k:") && d != null -> {
                    val kind = line.removePrefix("k:")
                    rowHits.add(RectF(box) to SmartYModel.kindName(kind))
                    pFill.color = Theme.raised; c.drawRoundRect(box, 8f, 8f, pFill)
                    UiKit.auraRow(c, box, Icons.get(d.icons[kind] ?: 0), Tr.t(SmartYModel.kindName(kind)))     // a kind's name is ours
                    sbHits += RectF(box) to { sbSend(SmartYModel.addKindTaps(d, kind), "Added") }
                }
                line is SmartYModel.Buff && d != null -> {
                    val b = line
                    val choose = sbChoose
                    rowHits.add(RectF(box) to b.name)
                    pFill.color = Theme.raised; c.drawRoundRect(box, 8f, 8f, pFill)
                    UiKit.auraRow(c, box, Icons.get(b.icon), b.name)
                    sbHits += RectF(box) to {
                        val cells = smartSpellCells(b.name)
                        val sel = sbSel
                        when {
                            choose == "add" -> sbSend(SmartYModel.addTaps(d, cells), "Added")
                            sel != null && choose == "addor" -> sbSend(SmartYModel.spellTaps(d, sel, "addor", cells), "Added")
                        }
                    }
                }
                line == "cancel" -> row(c, y, "Cancel", "", enabled = true) { b ->
                    val bb = RectF(b.right - 14f - 72f, b.centerY() - 36f, b.right - 14f, b.centerY() + 36f)
                    UiKit.closeX(c, bb.centerX(), bb.centerY(), 72f)
                    sbHits += RectF(box) to { sbChoose = null; sbSel = null }
                    bb
                }
            }
        }
        if (pbShown()) return drawPartyBuffs(c, y + ROW_H)
        return y + ROW_H
    }

    // ---- PARTY MODE'S SUB-SECTION (PARTY_BUILD.md 4.5; P5): only while Smart Y is on and the add-on sends partycfg. The list shown is
    // always partycfg.sy.list (the add-on keeps it per character); a row is SELECTED by a press, its Up / Down / Remove on the line under
    // it; every change is ONE batch on the party row (PartyTaps); the answer is partyack op "pb".
    private var pbSel: Int? = null
    private var pbChoose = false
    private var pbNote: String? = null

    /** Party mode's documents changed: the sub-section follows */
    fun partyChanged() { slices?.forEach { it.partyChanged() }; if (pbShown() || pbSel != null) { requestLayout(); invalidate() } }
    fun onPartyAck(a: PartyModel.Ack) {
        slices?.forEach { it.onPartyAck(a) }
        if (a.op != "pb") return
        pbNote = if (a.ok) null else if (a.why == "combat") "Not in a fight: try again after it." else "The game did not take it."
        invalidate()
    }

    private fun pbShown(): Boolean = smartDoc()?.let { SmartYModel.rowsShown(it) } == true && PartyModel.live.cfg != null

    /** the sub-section's rows: a list row's index, "act" under the selected one, "add"; while choosing: the buffs to add, "cancel" */
    private fun pbLines(): List<Any> {
        if (!pbShown()) return emptyList()
        val cfg = PartyModel.live.cfg ?: return emptyList()
        val out = ArrayList<Any>()
        for (k in cfg.sy.list.indices) { out += k; if (pbSel == k && !pbChoose) out += "act" }
        if (!pbChoose) { out += "add"; return out }
        out.addAll(cfg.sy.known.filter { k -> cfg.sy.list.none { it.id == k.id } })
        out += "cancel"
        return out
    }

    private fun pbHeight(): Float = pbLines().size.let { n -> if (n == 0) 0f else PB_HEAD + n * ROW_H + (n - 1) * 10f }

    private fun pbSend(taps: List<Pair<String, Int>>?, done: String) {
        pbNote = when {
            taps == null -> "Not on the tap pad yet."
            smartTaps(taps) -> done
            else -> "Nothing was sent: the game is not live."
        }
        pbSel = null; pbChoose = false
    }

    private fun drawPartyBuffs(c: Canvas, top: Float): Float {
        val cfg = PartyModel.live.cfg ?: return top
        // the sub-heading (Morpheus 27 gold) and its one line (Friz 22 dim), then the rows
        c.drawText(Tr.t("Party mode (the boxed member)"), 28f, top + 46f, UiKit.titlePaint(27f, Theme.gold))
        c.drawText(Tr.t("Out of combat, on the boxed member."), 28f, top + 76f, UiKit.bodyPaint(22f, Theme.textDim))
        var y = top + PB_HEAD
        for ((i, line) in pbLines().withIndex()) {
            if (i > 0) y += ROW_H + 10f
            val box = RectF(16f, y, width - 16f, y + ROW_H)
            when {
                line is Int -> {
                    val b = cfg.sy.list.getOrNull(line) ?: continue
                    if (pbSel == line) UiKit.selectedRow(c, box)
                    row(c, y, "${line + 1}. ${b.n}", "Cast on the boxed member when it is not up.", enabled = b.k) {
                        RectF(it.right - 16f, it.top, it.right - 16f, it.bottom) }
                    sbHits += RectF(box) to { pbSel = if (pbSel == line) null else line; pbChoose = false; pbNote = null }
                }
                line == "act" -> {
                    val k = pbSel ?: continue
                    val acts = ArrayList<Pair<String, () -> Unit>>()
                    if (k > 0) acts += "Up" to { pbSend(PartyTaps.pbOp(cfg, k + 1, PartyTaps.PbOp.UP), "Moved up.") }
                    if (k < cfg.sy.list.size - 1) acts += "Down" to { pbSend(PartyTaps.pbOp(cfg, k + 1, PartyTaps.PbOp.DOWN), "Moved down.") }
                    acts += "Remove" to { pbSend(PartyTaps.pbOp(cfg, k + 1, PartyTaps.PbOp.REMOVE), "Removed.") }
                    pFill.color = Theme.raised; c.drawRoundRect(box, 8f, 8f, pFill)
                    pLabel.color = Theme.textDim
                    c.drawText(Tr.t("Row ${k + 1}:"), box.left + 22f, box.centerY() + 10f, pLabel)
                    val bw = 150f
                    var x = box.right - 16f - bw * acts.size
                    for ((label, act) in acts) { sbButton(c, RectF(x, box.centerY() - 30f, x + bw - 6f, box.centerY() + 30f), label, act); x += bw }
                }
                line == "add" -> {
                    val full = cfg.sy.list.size >= 8
                    val none = cfg.sy.known.none { k -> cfg.sy.list.none { it.id == k.id } }
                    row(c, y, "Add a party buff", pbNote ?: if (full) "8 at most." else "One of your buffs you can cast on others.",
                        enabled = !full && !none) { b ->
                        val bb = RectF(b.right - 16f - 150f, b.centerY() - 30f, b.right - 16f, b.centerY() + 30f)
                        if (!full && !none) sbButton(c, bb, "Add") { pbChoose = true; pbNote = null }
                        bb
                    }
                }
                line is PartyModel.Spell -> row(c, y, line.n, "", enabled = true) { bx ->
                    val bb = RectF(bx.right - 16f - 150f, bx.centerY() - 30f, bx.right - 16f, bx.centerY() + 30f)
                    sbButton(c, bb, "Pick") { pbSend(PartyTaps.pbAdd(cfg, smartSpellCells(line.n)), "Added.") }
                    bb
                }
                line == "cancel" -> row(c, y, "Cancel", "", enabled = true) { b ->
                    val bb = RectF(b.right - 16f - 150f, b.centerY() - 30f, b.right - 16f, b.centerY() + 30f)
                    sbButton(c, bb, "Cancel") { pbChoose = false }
                    bb
                }
            }
        }
        return y + ROW_H
    }

    // ---- ADD-ONS > VOICE (the user, 2026-11-16: "we could make the per languege downloads part of the addons folder"): offline voice's
    // model files (small and full of each kind: the user's pick "Offer both"), in the add-on rows' look - each its size, its state (Not downloaded / Downloading n% / Ready) and Download / Delete
    // (twice, as Uninstall). App files ([VoiceModels]), never the game's AddOns folder; a download only on this press.
    private val voiceModelBtn = HashMap<VoiceModels.Model, RectF>()

    private fun drawVoiceModels(c: Canvas, top: Float): Float {
        var y = top
        for ((i, m) in VoiceModels.Model.values().withIndex()) {
            if (i > 0) y += ROW_H + 10f
            val st = VoiceModels.state(m)
            if (st is VoiceModels.State.Downloading || st == VoiceModels.State.Checking) postInvalidateDelayed(1000)
            // labels only (the user, terse page text: "labels and icons only"): the state word, no sentence after it
            val sub = Tr.t(VoiceModels.line(m, st))
            voiceModelBtn[m] = row(c, y, m.label, sub, enabled = true) { box ->
                val r = RectF(box.right - 16f - 260f, box.centerY() - 34f, box.right - 16f, box.centerY() + 34f)
                val armed = addonArmed == "voice.${m.id}" && SystemClock.elapsedRealtime() - addonArmedAt < ARM_MS
                when (st) {
                    is VoiceModels.State.Downloading -> {
                        progressBar(c, RectF(r.left - 240f, r.top + 6f, r.left - 20f, r.bottom - 6f), st.pct)
                        UiKit.button(c, r, "Cancel", UiKit.Button.UP); r
                    }
                    VoiceModels.State.Checking -> { UiKit.button(c, r, "Checking", UiKit.Button.DOWN); RectF(r.left, r.top, r.left, r.top) }
                    VoiceModels.State.Ready -> { UiKit.button(c, r, if (armed) "Tap again" else "Delete", if (armed) UiKit.Button.DOWN else UiKit.Button.UP); r }
                    else -> { UiKit.button(c, r, "Download", UiKit.Button.UP); r }
                }
            }
        }
        return y + ROW_H
    }

    // ---------------------------------------------------------------- Add-ons > Imported (ImportedAddons; the user, 2026-11-16)
    /** Import opens Android's picker on this (bottom) screen (the host's openOnBottom, as Choose for the DLL) */
    var onImportAddons: () -> Unit = {}
        get() = hub?.onImportAddons ?: field
    private var btnImport = RectF()
    private var btnImportInstall = RectF()
    private var btnImportCancel = RectF()
    private val importRemove = LinkedHashMap<String, RectF>()
    /** each waiting replacement's Cancel (ImportedAddons.queued: "Replaces at next start") */
    private val importUnqueue = LinkedHashMap<String, RectF>()
    private fun importFocus(): List<RectF> = listOf(btnImport, btnImportInstall, btnImportCancel) + importUnqueue.values.toList() +
        importRemove.values.toList()

    /** the imported list's rows: each replacement waiting, each imported add-on (none = the Imported group is not shown) */
    private fun importedRows(): Int {
        val q = ImportedAddons.queued()
        return q.size + importedList(q).size
    }

    /** the Import button's height (one full-width button, the user 2026-11-16: "Import addons should be a single button at the top of
     *  the list"; not a heading's height, so the room's tests tell the two apart) */
    private val IMPORT_BTN_H = 88f
    /** its status line under it (a step, a result, a refusal; greyed: why) */
    private val IMPORT_LINE_H = 44f

    /** the line under the button now, or null (nothing to say) */
    private fun importLine(): String? = ImportedAddons.status ?: if (ImportedAddons.flavorRoot() == null) "Pick the game folder first" else null

    /** the confirm card's rows: each add-on found, each zip that brought none, its Install / Cancel; 0 = no card */
    private fun importCardRows(): Int = ImportedAddons.pending?.let { p -> p.found.size + p.bad.size + 1 } ?: 0

    private fun importHeight(): Float = IMPORT_BTN_H + (if (importLine() != null) IMPORT_LINE_H else 0f) +
        importCardRows() * (ROW_H + 10f)

    /** the imported list less the ones with a replacement waiting (that row stands for them) */
    private fun importedList(q: List<ImportedAddons.Queued>): List<ImportedAddons.Imported> =
        ImportedAddons.list().filter { a -> q.none { it.folder.equals(a.folder, true) } }

    /** "340 KB" / "3 MB" */
    private fun bytesWord(b: Long): String =
        if (b < 1_000_000) "${((b + 999) / 1000).coerceAtLeast(1)} KB" else ThirdPartyAddons.size(((b + 999_999) / 1_000_000).toInt())

    private fun armed(key: String) = addonArmed == key && SystemClock.elapsedRealtime() - addonArmedAt < ARM_MS

    /** a control's place with nothing to press (a card row: the row is still a cursor stop) */
    private fun none(box: RectF) = RectF(box.right - 16f, box.top, box.right - 16f, box.top)

    private val pImportLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    /** Add-ons' first block: ONE full-width Import button (the app's red button, no label or line beside it), the status line under it
     *  when there is something to say, then the confirm card */
    private fun drawImport(c: Canvas, top: Float): Float {
        val root = ImportedAddons.flavorRoot()
        val busy = ImportedAddons.busy
        val p = ImportedAddons.pending
        val key = importHeight()
        if (key != importDrawnH) { importDrawnH = key; requestLayout() }
        val r = RectF(16f, top, width - 16f, top + IMPORT_BTN_H)
        rowHits.add(RectF(r) to "Import add-ons")
        val on = root != null && !busy
        UiKit.button(c, r, "Import add-ons", if (on) UiKit.Button.UP else UiKit.Button.DISABLED, waiting = busy)
        // greyed: nothing to press, the button still a cursor stop (its row)
        btnImport = if (on) RectF(r) else RectF(r.left, r.top, r.left, r.top)
        var y = r.bottom
        importLine()?.let { line ->
            pImportLine.textSize = pSub.textSize; pImportLine.typeface = pSub.typeface; pImportLine.color = Theme.textDim
            c.drawText(Tr.t(fit(pImportLine, line, r.width() - 40f)), r.centerX(), y + 31f, pImportLine)
            y += IMPORT_LINE_H
        }
        // ---- the confirm card: each add-on found, each zip that brought none, then Install / Cancel
        if (p != null) {
            y -= ROW_H                                    // each row below steps ROW_H + 10 from the last one's top
            val cardTop = y + ROW_H + 10f
            for (f in p.found) {
                y += ROW_H + 10f
                val sub = if (f.refused != null) Tr.t(f.refused) else listOfNotNull(
                    f.version?.let { Tr.t("Version $it") }, bytesWord(f.bytes),
                    if (f.replaces) Tr.t("Replaces yours") else null,
                    if (!f.ifaceOk) Tr.t("Made for another game version") else null).joinToString(" · ")
                // a refused one by its folder name (the one kept), a taken one by its own title
                row(c, y, if (f.refused != null) f.folder else f.title, sub, enabled = f.refused == null, translate = false) { box -> none(box) }
            }
            for (b in p.bad) {
                y += ROW_H + 10f
                row(c, y, b.name, Tr.t(b.why), enabled = false, translate = false) { box -> none(box) }
            }
            y += ROW_H + 10f
            val n = p.taken.size
            val can = n > 0 && !busy && root != null
            val again = armed("import.install")
            row(c, y, "Install these add-ons", if (n > 0) bytesWord(p.bytes) else "Nothing to install", enabled = n > 0) { box ->
                val cancel = RectF(box.right - 16f - 200f, box.centerY() - 34f, box.right - 16f, box.centerY() + 34f)
                val inst = RectF(cancel.left - 16f - 220f, cancel.top, cancel.left - 16f, cancel.bottom)
                UiKit.button(c, inst, if (again) "Tap again" else "Install",
                    if (!can) UiKit.Button.DISABLED else if (again) UiKit.Button.DOWN else UiKit.Button.UP)
                UiKit.button(c, cancel, "Cancel", if (busy) UiKit.Button.DISABLED else UiKit.Button.UP)
                btnImportInstall = if (can) RectF(inst) else RectF()
                btnImportCancel = if (busy) RectF() else RectF(cancel)
                inst
            }
            // the card's edge: these rows are one question
            pStroke.color = Theme.gold
            c.drawRoundRect(RectF(10f, cardTop - 5f, width - 10f, y + ROW_H + 5f), 12f, 12f, pStroke)
            y += ROW_H
        }
        return y
    }

    /** Add-ons' last block: the replacements waiting, then each imported add-on with its Remove */
    private fun drawImported(c: Canvas, top: Float): Float {
        val busy = ImportedAddons.busy
        if (importedRows() != importDrawnRows) { importDrawnRows = importedRows(); requestLayout() }
        if (importedRows() == 0) return top
        var y = top - ROW_H - 10f                         // each row steps ROW_H + 10 from the last one's top
        // ---- the replacements waiting for a start with the game closed (the user, 2026-11-16: "New now, replace next start")
        val q = ImportedAddons.queued()
        for (w in q) {
            y += ROW_H + 10f
            val sub = if (w.failed != null) Tr.t("Not replaced") else Tr.t("Replaces at next start")
            importUnqueue[w.folder] = row(c, y, w.title, sub, enabled = w.failed == null, translate = false) { box ->
                val r = RectF(box.right - 16f - 200f, box.centerY() - 34f, box.right - 16f, box.centerY() + 34f)
                UiKit.button(c, r, "Cancel", if (busy) UiKit.Button.DISABLED else UiKit.Button.UP)
                if (busy) RectF(r.left, r.top, r.left, r.top) else r
            }
        }
        // ---- the imported list: each with its Remove (twice, as Reload)
        for (a in importedList(q)) {
            y += ROW_H + 10f
            val again = armed("import.remove.${a.folder}")
            importRemove[a.folder] = row(c, y, a.title, a.version?.let { Tr.t("Version $it") } ?: a.folder, enabled = true, translate = false) { box ->
                val r = RectF(box.right - 16f - 220f, box.centerY() - 34f, box.right - 16f, box.centerY() + 34f)
                UiKit.button(c, r, if (again) "Tap again" else "Remove",
                    if (busy) UiKit.Button.DISABLED else if (again) UiKit.Button.DOWN else UiKit.Button.UP)
                if (busy) RectF(r.left, r.top, r.left, r.top) else r
            }
        }
        return y + ROW_H
    }

    /** a press on Add-ons > Voice: Download (the player's own), Cancel, Delete (twice); true = it was one */
    private fun voiceModelTap(x: Float, y: Float): Boolean {
        for (m in VoiceModels.Model.values()) {
            val r = voiceModelBtn[m] ?: continue
            if (r.isEmpty || !r.contains(x, y)) continue
            when (VoiceModels.state(m)) {
                is VoiceModels.State.Downloading -> VoiceModels.cancel(m)
                VoiceModels.State.Checking -> {}
                VoiceModels.State.Ready -> {
                    val now = SystemClock.elapsedRealtime()
                    if (addonArmed == "voice.${m.id}" && now - addonArmedAt < ARM_MS) { addonArmed = null; VoiceModels.delete(m) }
                    else { addonArmed = "voice.${m.id}"; addonArmedAt = now; postDelayed({ invalidate() }, ARM_MS + 50) }
                }
                else -> { DiagLog.event("voice", "model ${m.id} download asked in Add-ons"); VoiceModels.download(m) }
            }
            invalidate()
            return true
        }
        return false
    }

    private var importDrawnRows = -1
    private var importDrawnH = -1f

    /** a press on Import, the card's Install / Cancel or a Remove; true = it was one */
    private fun importTap(x: Float, y: Float): Boolean {
        val now = SystemClock.elapsedRealtime()
        fun hit(r: RectF) = !r.isEmpty && r.contains(x, y)
        when {
            hit(btnImport) -> { onImportAddons(); return true }
            hit(btnImportCancel) -> { addonArmed = null; ImportedAddons.cancel(); return true }
            hit(btnImportInstall) -> {
                // replacing a folder that is there: twice (the confirm the card asks for); only new folders: once
                val p = ImportedAddons.pending ?: return true
                if (p.replacesAny && !armed("import.install")) {
                    addonArmed = "import.install"; addonArmedAt = now; postDelayed({ invalidate() }, ARM_MS + 50); return true
                }
                addonArmed = null; ImportedAddons.install(); return true
            }
        }
        for ((folder, r) in importUnqueue) if (hit(r)) { addonArmed = null; ImportedAddons.cancelQueued(folder); return true }
        for ((folder, r) in importRemove) if (hit(r)) {
            if (armed("import.remove.$folder")) { addonArmed = null; ImportedAddons.remove(folder) }
            else { addonArmed = "import.remove.$folder"; addonArmedAt = now; postDelayed({ invalidate() }, ARM_MS + 50) }
            return true
        }
        return false
    }

    /** a download's progress, in the game's own bar art when the kit has it: [pct] 0..100 (< 0 = waiting) */
    private fun progressBar(c: Canvas, r: RectF, pct: Int) {
        pFill.color = Theme.ink; c.drawRoundRect(r, 6f, 6f, pFill)
        val k = (pct.coerceIn(0, 100)) / 100f
        pFill.color = Theme.gold
        if (k > 0f) c.drawRoundRect(RectF(r.left + 3f, r.top + 3f, r.left + 3f + (r.width() - 6f) * k, r.bottom - 3f), 4f, 4f, pFill)
        pStroke.color = Theme.bronze; c.drawRoundRect(r, 6f, 6f, pStroke)
        pValue.color = Theme.text
        c.drawText(Tr.t(if (pct >= 0) "$pct%" else "..."), r.centerX(), r.centerY() + 10f, pValue)
    }

    private var addonArmed: String? = null
    private var addonArmedAt = 0L
    private val ROW_H = 96f
    /** Party mode's sub-section: from the last Smart Y row's foot to its first row (the heading and its line between; P5) */
    private val PB_HEAD = 88f

    /** a labelled row; [control] draws the right-hand control and returns its hit box */
    private fun row(c: Canvas, top: Float, label: String, sub: String, enabled: Boolean, translate: Boolean = true,
                    control: (RectF) -> RectF): RectF {
        val box = RectF(16f, top, width - 16f, top + ROW_H)
        rowHits.add(RectF(box) to label)
        pFill.color = Theme.raised
        c.drawRoundRect(box, 8f, 8f, pFill)
        val hit = control(box)
        pLabel.color = if (enabled) Theme.text else Theme.dim
        pSub.color = if (enabled) Theme.textDim else Theme.dim
        // [translate] false: words that are not ours (an imported add-on's own title; its line put together from translated parts)
        val lw = hit.left - 20f - (box.left + 22f)
        c.drawText(if (translate) Tr.t(label) else fit(pLabel, label, lw), box.left + 22f, box.top + 41f, pLabel)
        // the subtitle never runs under the control, whatever the device font measures
        c.drawText(if (translate) Tr.t(fit(pSub, sub, lw)) else fit(pSub, sub, lw), box.left + 22f, box.top + 75f, pSub)
        return hit
    }

    /** [s] cut to [maxW] px with an ellipsis, or unchanged when it fits */
    private fun fit(p: Paint, s: String, maxW: Float): String {
        if (p.measureText(s) <= maxW) return s
        val n = p.breakText(s, true, (maxW - p.measureText("…")).coerceAtLeast(0f), null)
        return s.substring(0, n).trimEnd() + "…"
    }

    companion object {
        /** the blocks a slice draws (every other block is a tile part's: General, Bottom screen, Top screen, Controller, the add-on
         *  cards, Performance) */
        val LIVE = setOf(SettingsTabs.Block.SMART_Y, SettingsTabs.Block.IMPORT, SettingsTabs.Block.ADDON_VOICE, SettingsTabs.Block.IMPORTED)
        private const val SEG_TEXT = 24f
        private const val SLICE_PAD = 8f     // a slice's top and foot: its group's heading above, the next part below
        private const val BLOCK_GAP = 10f    // between two blocks of one slice
    }
}
