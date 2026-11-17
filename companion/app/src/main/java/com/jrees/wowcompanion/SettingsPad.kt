package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONObject

/**
 * THE SETTINGS ROOM: the title and the TAB ROW fixed at the top ([SettingsTabBar]; board f-settings-tabs, the user's approved plan
 * 2026-10-15), and under it one tab's page in a scroll. THE SIX TABS (the user's approved layout, 2026-11-12): a tab's page is its
 * FOLDING GROUPS ([SettingsTabs.Group]), each a heading ([SettingsFoldHeading]) and the parts that draw its rows - SettingsView slices
 * ([SettingsView.slice]), Home's section, GameUiSection parts. Showing a tab opens its FIRST group and folds the rest, every time; a
 * tap on a heading (or A on it) opens / folds that group. Which row is in which group is [SettingsTabs]' data. The room opens on the
 * last tab used ([SettingsTabs.last]). [SettingsPad] is drawn over the page for the controller.
 */
class SettingsRoom(context: Context, private val view: SettingsView, padSource: () -> JSONObject?) : LinearLayout(context) {
    /** Bottom screen > Home's rows (the host wires its DS WoW toolbar) */
    val homeSection = HomeSettingsSection(context) { it != ATTACK_CAST && it !in CAST_BAR_ROWS }
    /** GitHub #43 cb3: Bottom screen > Tap-to-cast bar (Home's section's row, its own group) */
    private val castBarSection = HomeSettingsSection(context) { it in CAST_BAR_ROWS }
    /** Controller > Combat helpers' "Start the attack when a spell is cast" (Home's section's row, drawn here) */
    private val attackSection = HomeSettingsSection(context) { it == ATTACK_CAST }
    /** every GameUiSection part (B closes an open On / Off bar of any of them first) */
    val gameUis = ArrayList<GameUiSection>()
    /** General's parts (beautify: its tiles and compact rows, GeneralTilesView); B closes an open band of theirs first too */
    val generals = ArrayList<GeneralTilesView>()
    /** beautify/bottom: Bottom screen's tile groups (BottomTilesView: Home, Map & chat) */
    val bottomTiles = ArrayList<BottomTilesView>()
    /** the Controller tab's tile parts (beautify: Buttons, Wheels, Combat helpers) */
    val ctlTiles = ArrayList<ControllerTiles>()
    /** the Smart Y list as a sub-page of the Controller tab (beautify: opened by Smart Y's "List"); GONE while closed */
    val smartYPage = SmartYPage(context, view)
    /** Add-ons' picture cards (the beautify pass, 2026-11-17): one band open in the room, B closes it as a tile's */
    val addonTiles = ArrayList<AddonTiles>()
    /** beautify: Settings > Performance's picture tiles (the tab's one part; its band closes with B like Top's) */
    val perf = PerformanceTiles(context, view)
    val tabBar = SettingsTabBar(context) { t -> show(t) }
    /** "What it does" (phase 2): the row's picture, right of the page, on the tabs that have pictures */
    val preview = SettingsPreviewView(context, { homeSection.dpadState()?.want }, { view.smartDoc()?.on })
    private val scroll = ScrollView(context).apply { ThemeLive.bg(this, Tok.INK) }
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    val pad: SettingsPad

    /** one group as the page shows it: its heading, its parts (folded = the parts GONE) */
    class GroupBox(val group: SettingsTabs.Group, val box: LinearLayout, val heading: SettingsFoldHeading, val parts: List<View>)
    val groupBoxes = ArrayList<GroupBox>()

    /** the tab shown */
    var tab: SettingsTabs.Tab = SettingsTabs.Tab.GENERAL
        private set
    /** the groups open now (on the tab shown) */
    val open = LinkedHashSet<SettingsTabs.Group>()

    init {
        orientation = VERTICAL
        ThemeLive.bg(this, Tok.INK)
        SettingsTabs.init(context)
        val wrap = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
        val sections = ArrayList<SettingsPad.Section>()
        for (g in SettingsTabs.Group.values()) {
            val heading = SettingsFoldHeading(context, g) { toggle(it) }
            val parts = partsOf(g)
            val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            box.addView(heading, wrap())
            // a headless group (Add-ons' Import button): no heading at all, its parts always shown
            if (g.headless) heading.visibility = View.GONE
            for (p in parts) box.addView(p, wrap())
            column.addView(box, wrap())
            groupBoxes.add(GroupBox(g, box, heading, parts))
            // the cursor stops on the heading (A opens / folds it), then on the rows of an open group
            // a tab that does not fold (General): no stop on its plain headings
            // a headless group: no heading, no stop
            sections.add(SettingsPad.Section(heading, { if (SettingsTabs.folds(g)) listOf(heading.rect()) else emptyList() }))
            for (p in parts) sections.add(when (p) {
                // General's "Reload game UI", the room's one destructive control: the cursor never STARTS there
                is SettingsView -> SettingsPad.Section(p, { p.focusTargets() }, noStart = { p.isReload(it) })
                is HomeSettingsSection -> SettingsPad.Section(p, { p.focusTargets() })
                is GameUiSection -> SettingsPad.Section(p, { p.focusTargets() })
                is GeneralTilesView -> SettingsPad.Section(p, { p.focusTargets() }, noStart = { p.isReload(it) })
                is BottomTilesView -> SettingsPad.Section(p, { p.focusTargets() })
                is ControllerTiles -> SettingsPad.Section(p, { p.focusTargets() })
                is AddonTiles -> SettingsPad.Section(p, { p.focusTargets() })
                is PerformanceTiles -> SettingsPad.Section(p, { p.focusTargets() })
                else -> SettingsPad.Section(p, { emptyList() })
            })
        }
        // the Smart Y list's sub-page (beautify): after the groups, shown alone while open
        column.addView(smartYPage, wrap())
        smartYPage.visibility = View.GONE
        sections.add(SettingsPad.Section(smartYPage.list, { smartYPage.focusTargets() }))
        scroll.addView(column)
        addView(tabBar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val page = FrameLayout(context)
        page.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val body = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        pad = SettingsPad(context, scroll, sections, gameUis, padSource, tabBar, { dir -> show(SettingsTabs.step(tab, dir)) },
            { v, r -> rowOf(v, r.centerX(), r.centerY()) })
        // Top screen's tiles (2026-11-16): ONE band open in the room - a tile's band opening closes any other part's - and the cursor
        // and the band follow it (a tap and A alike)
        // the beautify pass: every tiled part (General, Bottom screen, Controller, Add-ons, Performance) with Top screen's: ONE band open in
        // the room - a band opening closes every other part's - and the cursor and the band follow it
        pad.bands.addAll(generals); pad.bands.addAll(bottomTiles); pad.bands.addAll(ctlTiles); pad.bands.addAll(addonTiles); pad.bands.add(perf)
        val closeOthers: (Any) -> Unit = { keep -> gameUis.filter { it !== keep }.forEach { it.closeSelection() }
            pad.bands.filter { it !== keep }.forEach { it.closeSelection() } }
        for (gu in gameUis) gu.onOpen = { s -> closeOthers(s); pad.follow(s) }
        for (gv in generals) gv.onOpen = { s -> closeOthers(s); pad.follow(s, s.selectionAnchor(), s.bandBox()) }
        for (bt in bottomTiles) bt.onOpen = { s -> closeOthers(s); pad.follow(s, s.selectionAnchor(), s.bandBox()) }
        for (at in addonTiles) at.onOpen = { s -> closeOthers(s); pad.follow(s, s.selectionAnchor(), s.bandBox()) }
        perf.onOpen = { s -> closeOthers(s); pad.follow(s, s.selectionAnchor(), s.bandBox()) }
        // the Controller tab's tiles: Smart Y's "List" opens its sub-page
        for (ct in ctlTiles) {
            ct.onOpen = { s -> closeOthers(s); pad.follow(s, s.selectionAnchor(), s.bandBox()) }
            ct.onSubPage = { openSmartY() }
        }
        pad.subPageOpen = { smartYPage.visibility == View.VISIBLE }
        pad.subPageBack = { closeSmartY(anchor = true) }
        smartYPage.onBack = { closeSmartY(anchor = true) }
        page.addView(pad, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        body.addView(page, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        body.addView(preview, LinearLayout.LayoutParams(PREVIEW_W, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(body, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> pad.invalidate() }
        // entering the room shows its tab afresh: the first group open, the rest folded, the page at its top (the user's rule)
        pad.onEnter = { show(tab, force = true) }
        // rows that come and go (the add-on's switches, an add-on installed or removed, the login row, Polestar in use): the groups
        // with rows now are the ones shown, and every part measures again
        // (the main thread's handler, not View.post: a change while the room is not attached must not wait for it to be)
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val again: () -> Unit = { main.post { refreshGroups() } }
        for (gu in gameUis) gu.onContent = again
        for (bt in bottomTiles) bt.onContent = again
        for (at in addonTiles) at.onContent = again
        view.onContent = again
        GameUi.onChange(again); ThirdPartyAddons.onChange(again); ImportedAddons.onChange(again); Settings.onChange(again); HomeAttackSwitch.onChange(again)
        CompassSwitch.onChange(again); RingsSwitch.onChange(again); TipsSwitch.onChange(again); HomeLootSwitch.onChange(again)
        FrameGen.onChange(again)
        // the layout editor's row comes with the add-on set's first "lay" document (only that change: its documents come often)
        var layAvail = LayoutModel.Live.available()
        LayoutModel.Live.onChange { val a = LayoutModel.Live.available(); if (a != layAvail) { layAvail = a; again() } }
        show(SettingsTabs.last, force = true)
    }

    /** [p] has rows to draw now */
    private fun hasContent(p: View): Boolean = when (p) {
        is SettingsView -> p.hasContent(); is GameUiSection -> p.hasContent(); is HomeSettingsSection -> p.hasContent()
        is GeneralTilesView -> p.hasContent(); is BottomTilesView -> p.hasContent(); is ControllerTiles -> p.hasContent(); is AddonTiles -> p.hasContent(); else -> true
    }
    /** [g] has rows to draw now (a group without any is not shown: no heading, no cursor stop) */
    fun hasRows(g: SettingsTabs.Group): Boolean = groupBoxes.firstOrNull { it.group == g }?.parts?.any { hasContent(it) } == true
    /** a group was opened or folded by hand since the tab showed (a group that shows up later then leaves the folds alone) */
    private var touched = false

    /** the groups shown on the tab now: the tab's groups with rows now */
    private fun applyGroups() {
        // a sub-page open (beautify: the Smart Y list) shows alone
        val shown = if (smartYPage.visibility == View.VISIBLE) emptyList() else SettingsTabs.groups(tab).filter { hasRows(it) }
        for (gb in groupBoxes) gb.box.visibility = if (gb.group in shown) View.VISIBLE else View.GONE
    }

    /** rows came or went: the groups shown again, every part measured again; untouched folds follow the tab's first group with rows */
    fun refreshGroups() {
        applyGroups()
        if (!touched) { open.clear(); open.addAll(SettingsTabs.openOnShow(tab) { hasRows(it) }) }
        applyFolds()
        for (gb in groupBoxes) for (p in gb.parts) p.requestLayout()
        pad.invalidate()
    }

    /** the views that draw [g]'s rows, in its blocks' order: a run of SettingsView blocks is one slice; GameUiSection blocks of the same
     *  part share it (Navigation's Polestar tile is in Navigation's grid) */
    private fun partsOf(g: SettingsTabs.Group): List<View> {
        // General as picture tiles and compact rows (the beautify design, section 2): ONE part per group draws all its rows
        if (g.tab == SettingsTabs.Tab.GENERAL) return listOf(GeneralTilesView(context, view, g).also { generals.add(it) })
        // beautify/bottom: Bottom screen's groups are picture tiles (BottomTiles); the toolbar's state and switch stay Home section's
        if (g.tab == SettingsTabs.Tab.BOTTOM)
            return listOf(BottomTilesView(context, g, { homeSection.dpadState() }, { homeSection.onDpad(it) }).also { bottomTiles.add(it) })
        // the Controller tab (beautify): one tile part per group; Smart Y's own group has none (its tile is in Buttons)
        if (g.tab == SettingsTabs.Tab.CONTROLLER) return listOfNotNull(ControllerTiles.forGroup(context, g, view)?.also { ctlTiles.add(it) })
        val out = ArrayList<View>()
        val run = LinkedHashSet<SettingsTabs.Block>()
        fun flush() { if (run.isNotEmpty()) { out.add(view.slice(run.toSet())); run.clear() } }
        for (b in SettingsTabs.Block.values().filter { it.group == g }) when (b.drawer) {
            SettingsTabs.Drawer.VIEW -> run.add(b)
            SettingsTabs.Drawer.HOME -> { flush(); out.add(when (b) {
                SettingsTabs.Block.ATTACK_CAST -> attackSection; SettingsTabs.Block.CAST_BAR -> castBarSection; else -> homeSection }) }
            // Add-ons' cards: ONE view for the group's blocks (its cards are the add-ons the blocks name)
            SettingsTabs.Drawer.ADDONS -> { flush(); if (out.lastOrNull() !is AddonTiles) out.add(AddonTiles(context, view).also { addonTiles.add(it) }) }
            // the Performance tiles: ONE part for the group's every tiled block
            SettingsTabs.Drawer.TILES -> { flush(); if (perf !in out) out.add(perf) }
            SettingsTabs.Drawer.GAME_UI -> {
                flush()
                val part = gameUiPart(b)
                if ((out.lastOrNull() as? GameUiSection)?.part != part) out.add(GameUiSection(context, part).also {
                    it.onEditLayout = { view.onEditLayout() }                  // hub61: the Edit layout tile opens the editor's page
                    gameUis.add(it) })
            }
        }
        flush()
        return out
    }

    /** the GameUiSection part that draws [b] */
    private fun gameUiPart(b: SettingsTabs.Block): GameUiSection.Part = when (b) {
        SettingsTabs.Block.COMPANION -> GameUiSection.Part.COMPANION
        SettingsTabs.Block.HUD -> GameUiSection.Part.HUD
        SettingsTabs.Block.TARGET -> GameUiSection.Part.TARGET
        SettingsTabs.Block.PARTY -> GameUiSection.Part.PARTY
        SettingsTabs.Block.NAVIGATION, SettingsTabs.Block.POLESTAR_TOP -> GameUiSection.Part.NAVIGATION
        SettingsTabs.Block.COMBAT -> GameUiSection.Part.COMBAT
        SettingsTabs.Block.AUTO_SHOT -> GameUiSection.Part.AUTO_SHOT
        SettingsTabs.Block.LAYOUT -> GameUiSection.Part.LAYOUT
        else -> GameUiSection.Part.WINDOWS
    }

    /** show tab [t]: only its groups are in the page (the others GONE, so they take no room and press nothing); its first group open */
    fun show(t: SettingsTabs.Tab, force: Boolean = false) {
        if (t == tab && !force) return
        tab = t
        SettingsTabs.last = t
        gameUis.forEach { it.closeSelection() }
        generals.forEach { it.closeSelection() }
        bottomTiles.forEach { it.closeSelection() }
        ctlTiles.forEach { it.closeSelection() }
        closeSmartY(anchor = false)
        addonTiles.forEach { it.closeSelection() }
        perf.closeSelection()
        touched = false
        open.clear(); open.addAll(SettingsTabs.openOnShow(t) { hasRows(it) })
        applyGroups()
        applyFolds()
        tabBar.tab = t
        scroll.scrollTo(0, 0)
        // the panel: on the tabs with pictures, starting on the first open group's first row that has one
        val rows = SettingsTabs.blocks(t).flatMap { it.rows }
        // the tiled tabs have no "What it does" panel: the tiles are the pictures, as on Top screen (the beautify pass)
        val tiles = SettingsTabs.blocks(t).any { it.drawer == SettingsTabs.Drawer.TILES }
        preview.visibility = if (t !in TILED_TABS && !tiles && rows.any { SettingsPreview.has(it) }) View.VISIBLE else View.GONE
        preview.id = firstPicture(open) ?: rows.firstOrNull { SettingsPreview.has(it) }
        pad.tabChanged()
    }

    /** a tap on [g]'s heading or A on it: open it, or fold it */
    fun toggle(g: SettingsTabs.Group) {
        if (!SettingsTabs.folds(g)) return                               // General, and a headless group: always open
        touched = true
        if (!open.remove(g)) {
            open.add(g)
            firstPicture(setOf(g))?.let { preview.id = it }            // the panel follows the group just opened
        } else {
            // a folded group's open On / Off bar closes with it (B would otherwise close a bar nobody sees)
            groupBoxes.firstOrNull { it.group == g }?.parts?.forEach { (it as? GameUiSection)?.closeSelection(); (it as? BandPart)?.closeSelection() }
        }
        applyFolds()
        pad.invalidate()
    }

    private fun applyFolds() {
        for (gb in groupBoxes) {
            val on = gb.group in open || (gb.group.headless && gb.group.tab == tab)
            gb.heading.open = on
            for (p in gb.parts) p.visibility = if (on) View.VISIBLE else View.GONE
        }
    }

    /** Smart Y's "List" (beautify): the list as a sub-page of the tab body, the groups hidden, the page at its top, the cursor on the
     *  list's first stop */
    fun openSmartY() {
        ctlTiles.forEach { it.closeSelection() }
        smartYPage.visibility = View.VISIBLE
        applyGroups()
        scroll.scrollTo(0, 0)
        smartYPage.list.requestLayout()
        pad.land(smartYPage.list) { smartYPage.firstStop() }
        pad.resyncB()
    }

    /** the sub-page closes (B, the back chip, a tab step): the groups again; [anchor] = the cursor back on the Smart Y tile */
    fun closeSmartY(anchor: Boolean): Boolean {
        if (smartYPage.visibility != View.VISIBLE) return false
        smartYPage.visibility = View.GONE
        applyGroups()
        if (anchor) ctlTiles.firstOrNull { ControllerPictures.SMART_Y in it.tileIds() }?.let { t ->
            pad.land(t) { t.tileBox(ControllerPictures.SMART_Y) }
        }
        pad.resyncB()
        return true
    }

    private fun firstPicture(groups: Set<SettingsTabs.Group>): String? =
        groups.flatMap { SettingsTabs.blocks(it) }.flatMap { it.rows }.firstOrNull { SettingsPreview.has(it) }

    /** the row at ([x], [y]) of section [v] (its own coordinates) shows in the panel, when it has a picture */
    private fun rowOf(v: View, x: Float, y: Float) {
        if (v is BottomTilesView) { v.focusAt(x, y); return }
        val id = when (v) { is SettingsView -> v.rowAt(x, y); is HomeSettingsSection -> v.rowAt(x, y); is GameUiSection -> v.rowAt(x, y); else -> null }
        if (SettingsPreview.has(id)) preview.id = id
    }

    /** a touch on a row: the panel shows it (the touch itself goes on to the row as always) */
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN && preview.visibility == View.VISIBLE) {
            val sx = e.x; val sy = e.y - tabBar.height + scroll.scrollY
            if (sx < scroll.width) for (gb in groupBoxes) {
                val b = gb.box
                if (b.visibility != View.VISIBLE || sy < b.top || sy >= b.bottom) continue
                val y = sy - b.top
                for (i in 0 until b.childCount) {
                    val ch = b.getChildAt(i)
                    if (ch.visibility == View.VISIBLE && y >= ch.top && y < ch.bottom) { rowOf(ch, sx, y - ch.top); break }
                }
                break
            }
        }
        return super.dispatchTouchEvent(e)
    }

    private companion object {
        const val PREVIEW_W = 470
        /** the tabs drawn as picture tiles (the beautify pass): no "What it does" panel, the tiles are the pictures */
        val TILED_TABS = setOf(SettingsTabs.Tab.BOTTOM, SettingsTabs.Tab.CONTROLLER)
        const val ATTACK_CAST = "Start the attack when a spell is cast"
        /** the Tap-to-cast bar group's rows: its place, and the bar in a fight (Hide / Stay) */
        val CAST_BAR_ROWS = SettingsTabs.Block.CAST_BAR.rows.toSet()
    }
}

/**
 * A GROUP'S FOLDING HEADING (the six tabs, 2026-11-12): the page's heading as every Settings heading is drawn (Morpheus, gold), with
 * the game's own fold sign before it (the kit's list plus / minus, as the game's folding lists have it); a folded group's words dim.
 * A tap, or the cursor's A (a touch at its centre), opens / folds the group ([onToggle]). Touches act on RELEASE, only when the
 * finger has not moved (the page scrolls).
 */
class SettingsFoldHeading(context: Context, val group: SettingsTabs.Group, private val onToggle: (SettingsTabs.Group) -> Unit) : View(context) {
    var open = false
        set(v) { if (field != v) { field = v; invalidate() } }
    private val pHead by themed { Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 32f; color = Theme.gold } }
    private val pSign = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var downX = 0f
    private var downY = 0f

    init { Theme.onChange { postInvalidate() } }

    /** the heading's box (the cursor's stop and its touch area), in this view's coordinates */
    fun rect(): RectF = RectF(16f, 4f, width - 16f, H - 4f)

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), H.toInt())
    }

    override fun onDraw(c: Canvas) {
        UiKit.asTitle(pHead)
        c.drawColor(Theme.ink)
        val cy = H / 2f
        val folds = group.tab.folds
        val sign = if (folds) UiKit.tex(if (open) "list_minus" else "list_plus") else null
        if (!folds) {
            // General: a plain heading, no fold sign
        } else if (sign != null) {
            val h = SIGN_W * sign.height / sign.width
            c.drawBitmap(sign, null, RectF(SIGN_X, cy - h / 2f, SIGN_X + SIGN_W, cy + h / 2f), pSign)
        } else {
            // no kit yet: the sign in the heading's own gold
            val p = UiKit.titlePaint(36f, Theme.gold).apply { textAlign = Paint.Align.CENTER }
            c.drawText(if (open) "-" else "+", SIGN_X + SIGN_W / 2f, cy - (p.ascent() + p.descent()) / 2f, p)
        }
        pHead.color = Theme.gold
        pHead.alpha = if (open) 255 else 190
        c.drawText(Tr.t(BottomTiles.heading(group)), if (folds) SIGN_X + SIGN_W + 18f else SIGN_X, cy - (pHead.ascent() + pHead.descent()) / 2f, pHead)
        pHead.alpha = 255
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
            MotionEvent.ACTION_UP -> if (Math.abs(e.x - downX) <= SLOP && Math.abs(e.y - downY) <= SLOP) onToggle(group)
        }
        return true
    }

    companion object {
        const val H = 84f
        const val SIGN_X = 28f
        const val SIGN_W = 30f
        const val SLOP = 24f
    }
}

/**
 * THE SETTINGS ROOM WITH THE CONTROLLER (LATER item 5, the user: "When opening any room I want a unified experience on how the
 * home button behaves, B returning to the home room if there is not another obvious function of the B button"). The room is the
 * generic guest "settings" of the quests addon's facility (Quests.lua q6, GuestPad): entering by touch holds the controller,
 * leaving by touch frees it. The cursor ([UiKit.focus]) is up as soon as the room holds, on the first button in view, and walks every
 * button of the three sections in reading order; the
 * D-pad moves it to the nearest button in that direction; A presses the button EXACTLY as a touch does (a down / up at its centre,
 * so each section keeps all its own logic, the Reload's two-tap confirm included); B closes an open On / Off / Dynamic bar
 * first, otherwise leaves the room with B (Home). The Home button shows B whenever B would go Home (bnext).
 *
 * This view only draws the cursor over the scroll; it never takes a touch.
 */
class SettingsPad(
    context: Context,
    private val scroll: ScrollView,
    private val sections: List<Section>,
    /** the sections with an On / Off bar that B closes first (Top screen's and QOL's) */
    private val gameUis: List<GameUiSection>,
    private val padSource: () -> JSONObject?,
    /** the tab row (board f-settings-tabs): the cursor lands on it */
    private val tabBar: SettingsTabBar? = null,
    /** L / R (and the D-pad's left / right on the tab row): the next tab (-1 / +1) */
    private val onTabStep: (Int) -> Unit = {},
    /** the cursor moved onto a button: its section's view and the button there (the "What it does" panel follows its row) */
    private val onFocus: (View, RectF) -> Unit = { _, _ -> },
) : View(context) {

    /** one section of the room and its buttons, in its own coordinates; a button [noStart] says yes to is never where the cursor
     *  starts (it was "the first N", which on a tab without Reload skipped that tab's first button: Spoken's Install, test40) */
    class Section(val view: View, val targets: () -> List<RectF>, val noStart: (RectF) -> Boolean = { false })

    /** the other parts with a band (the beautify tiles: General, Bottom, Controller, Add-ons, Performance): B closes an open one first, as it closes a GameUiSection's */
    val bands = ArrayList<BandPart>()
    /** a sub-page shows (beautify: the Smart Y list): B goes back to its tile first */
    var subPageOpen: () -> Boolean = { false }
    /** B on a sub-page: it closes, the cursor back on its tile; true = it was open */
    var subPageBack: () -> Boolean = { false }

    /** B with nothing nearer to do, when the leave-with-B cell cannot be tapped: Home directly (MainActivity) */
    var onLeave: (() -> Unit)? = null

    /** the report page over the room (board f-problem-report) takes each press while it is open: true = taken */
    var padOverride: ((String) -> Boolean)? = null
    /** a page over the room that takes B itself (the layout editor): while it shows, B never leads Home in the capture */
    var overlayOpen: () -> Boolean = { false }
    /** tell the capture where B leads again (a page over the room opened or closed) */
    fun resyncB() { bSaid = null; post { syncB() } }

    var held = false; private set
    /** b112 (the rooms audit: one hold tap, never asked again): Map's HoldAsk, asked again every 0.7 s while unanswered, 4 at most */
    private val holdAsk = HoldAsk()
    /** a hold went out this visit (its answer may still come): the leave frees it even before the answer */
    private var holdAsked = false
    private var focus: PointF? = null           // the cursor's button centre in the scroll's content coordinates; null = not shown
    /**
     * THE CURSOR ON THE TAB ROW (f-settings-tabs; the house's "tabs open on landing"): the room opens with it there; L / R or the
     * D-pad's left / right switch the tab, A or down goes into the page, up from the page's first row and B come back here, and B
     * here leaves the room (rows -> tabs -> Home).
     */
    private var onTabs = false
        set(v) { field = v; tabBar?.cursor = v }
    private var lastPadN = -1
    private var bSaid: String? = null

    /** a button in content coordinates, with its section and its own rect */
    private class Target(val box: RectF, val section: Section, val local: RectF, val noStart: Boolean)

    private fun targets(): List<Target> {
        val out = ArrayList<Target>()
        for (s in sections) {
            val top = contentTop(s.view) ?: continue                // another tab's, or a folded group's: its boxes are stale
            if (s.view.height == 0) continue                          // a part with nothing to show: never drawn, its boxes stale
            for (r in s.targets()) out.add(Target(RectF(r.left, r.top + top, r.right, r.bottom + top), s, r, s.noStart(r)))
        }
        // in reading order down the page, whatever order the sections are listed in (on Home, Home's own rows stand above the
        // modules' blocks: the cursor starts on the topmost)
        out.sortWith(compareBy({ it.box.top }, { it.box.left }))
        return out
    }

    /** [v]'s top in the scroll's content (a section may stand inside a group's box); null = it or a box round it is not VISIBLE */
    private fun contentTop(v: View): Float? {
        val content = scroll.getChildAt(0)
        var y = 0f
        var cur: View = v
        while (cur !== content) {
            if (cur.visibility != View.VISIBLE) return null
            y += cur.top
            cur = cur.parent as? View ?: return null
        }
        return y
    }

    /** the buttons as the controller walks them (the room's tests): content-coordinate boxes, in reading order */
    fun targetBoxes(): List<RectF> = targets().map { RectF(it.box) }
    /** the cursor's button now (content coordinates), or null (the tests) */
    fun focusBox(): RectF? = focus?.let { f -> nearest(targets(), f)?.box?.let { RectF(it) } }
    /** the cursor is on the tab row (the tests) */
    val onTabRow get() = onTabs

    /** where the cursor starts: the first button in view that may start it (the user's ruling, via tracking 2026-09-30: not on Reload) */
    private fun startTarget(all: List<Target>): Target? =
        all.firstOrNull { !it.noStart && it.box.bottom > scroll.scrollY } ?: all.firstOrNull { !it.noStart } ?: all.firstOrNull()

    // ---------------------------------------------------------------- the guest (MainActivity calls these on the touch path)

    /** the room is entered: the room shows its tab afresh (the user's rule: its first group open, the rest folded, every time) */
    var onEnter: (() -> Unit)? = null

    /** the room was entered by touch: hold the controller (one tap) */
    fun enter() {
        onEnter?.invoke()
        focus = null; lastPadN = -1; bSaid = null
        // the cursor is there AT ONCE, with its A (the user, 2026-10-15: "make sure that the cursor appears with the A glyph when
        // changing to the settings room"): on the tab row, before the hold's answer (a pending or late answer left none until a press)
        onTabs = tabBar != null
        holdAsk.shown()
        askHold()
    }

    /** one hold tap now if HoldAsk says so, and the next check [HoldAsk.EVERY_MS] later */
    private fun askHold() {
        if (!holdAsk.due()) return
        val sent = tap(GuestPad.cells(padSource(), ID, hold = true))
        holdAsked = holdAsked || sent
        if (holdAsk.asked(sent)) postDelayed({ askHold() }, HoldAsk.EVERY_MS)
    }

    /** the room is left (Home's show(), every route; RoomHandOver.stops): the hold is asked no more, AT ONCE. A fight's leave skips
     *  [leave] (only the capture's secure cell lets go there), and the re-ask went on behind Home (the user 2026-11-16 on Bags: "it
     *  reopens again"; every room the same) */
    fun stopAsking() { holdAsk.hidden() }

    /** the room was left by touch: hand the controller back (one tap), if it was held */
    fun leave() {
        stopAsking()
        // b112: a hold asked this visit is freed even if its answer has not come yet (a late hold must not keep the controller)
        if (held || holdAsked) tap(GuestPad.cells(padSource(), ID, hold = false))
        held = false; holdAsked = false; focus = null; onTabs = false; invalidate()
    }

    /** the room showed another tab: the cursor is on the tab row (a touch's pick or L / R) */
    fun tabChanged() {
        focus = null
        if (tabBar != null && isShown) onTabs = true
        post { syncB() }
        invalidate()
    }

    /** a page over the room let go by B (the layout editor): the cursor on the tab row, so the next B goes Home (the house rule) */
    fun backToTabs() { toTabs(); post { syncB() } }

    /** the cursor onto the tab row (or, with no tab row, the page's first button) */
    private fun toTabs() {
        if (tabBar == null) { startTarget(targets())?.let { moveTo(it) }; return }
        focus = null; onTabs = true
        invalidate()
    }

    /** from the tab row into the page: its first button in view */
    private fun intoPage() {
        val all = targets()
        val t = startTarget(all) ?: return          // a page with no buttons (e.g. only the status): stay on the tabs
        onTabs = false
        moveTo(t)
    }

    /** {"type":"guestack","id":"settings"} */
    fun onGuestAck(msg: JSONObject) {
        when (msg.optString("op")) {
            // the cursor is THERE when the room holds the controller (the user 2026-09-30: "I hate that in the other menus I have to
            // ress buttons a few times to make the cursor appear, it should already be there"): on the first button in view
            "hold" -> { holdAsk.answered(); if (msg.optBoolean("ok")) {
                // b112: "pending" (capture v10 / v11: queued, or a fight's pending ENTER) does not hold yet, as in the Quests room
                held = msg.optString("why") != "pending"; bSaid = null
                // the cursor LANDS ON THE TABS (f-settings-tabs); without a tab row, on the first button in view
                if (focus == null) post { toTabs(); syncB() } else syncB()
            } }
            "free", "leave" -> { held = false; focus = null; onTabs = false; invalidate() }
        }
    }

    /** {"type":"guestpad","id":"settings"}: one press, only while the room shows and from a CURRENT document (MainActivity) */
    fun onPad(msg: JSONObject) {
        if (msg.has("lost")) { held = false; focus = null; onTabs = false; invalidate(); return }
        holdAsk.answered()
        val n = msg.optInt("n", -1)
        if (n == lastPadN) return                           // the same press seen twice
        lastPadN = n
        held = true
        val k = msg.optString("k")
        if (padOverride?.invoke(k) == true) return
        // L / R switch the tab from anywhere; the cursor goes to the tab row (board f-settings-tabs)
        if (k == "PADLSHOULDER" || k == "PADRSHOULDER") {
            if (tabBar != null) { onTabStep(if (k == "PADLSHOULDER") -1 else 1); onTabs = true }
            post { syncB() }; invalidate(); return
        }
        if (onTabs) {
            when (k) {
                "PADDLEFT", "PADDRIGHT" -> onTabStep(if (k == "PADDLEFT") -1 else 1)
                "PADDDOWN", "PAD1" -> intoPage()
                "PAD2" -> if (!tap(GuestPad.leaveB(padSource()))) onLeave?.invoke()
            }
            post { syncB() }; invalidate(); return
        }
        val all = targets()
        if (all.isEmpty()) { toTabs(); post { syncB() }; return }
        val here = nearest(all, focus)
        if (focus == null && k.startsWith("PADD")) {
            // the cursor should already be up (shown on the hold); if a touch scrolled it away, a D-pad press brings it back first
            startTarget(all)?.let { moveTo(it) }
        } else when (k) {
            "PADDUP", "PADDDOWN" -> {
                val next = vertical(all, here, if (k == "PADDUP") -1 else 1)
                if (next != null) moveTo(next) else if (k == "PADDUP" && tabBar != null) toTabs()   // up from the first row: the tabs
            }
            "PADDLEFT", "PADDRIGHT" -> horizontal(all, here, if (k == "PADDLEFT") -1 else 1)?.let { moveTo(it) }
            "PAD1" -> if (focus != null) here?.let { press(it) } else moveTo(all.first())
            "PAD2" -> {
                val open = gameUis.firstOrNull { it.hasSelection }
                val openBand = bands.firstOrNull { it.hasSelection }
                when {
                    // beautify: a sub-page (the Smart Y list) one step back to its tile
                    subPageOpen() -> subPageBack()
                    openBand != null -> {
                        val a = openBand.selectionAnchor()
                        bands.forEach { it.closeSelection() }
                        if (a != null && openBand is View) { anchor = openBand to a; anchorBand = null }
                    }
                    // B one step back: the open bar / band closes and the cursor is back on its own row / tile (the band under a tile
                    // takes the buttons the cursor was on away; the nearest button left would be the next tile row)
                    open != null -> {
                        val a = open.selectionAnchor()
                        gameUis.forEach { it.closeSelection() }
                        if (a != null) { anchor = open to a; anchorBand = null }
                    }
                    openBand != null -> {
                        val a = openBand.selectionAnchor()
                        bands.forEach { it.closeSelection() }
                        if (a != null && openBand is View) { anchor = openBand to a; anchorBand = null }
                    }
                    // B in the page: back to the tab row (rows -> tabs -> Home); without a tab row it leaves
                    tabBar != null -> toTabs()
                    else -> if (!tap(GuestPad.leaveB(padSource()))) onLeave?.invoke()
                }
            }
        }
        post { syncB() }
        invalidate()
    }

    // ---------------------------------------------------------------- moving and pressing

    private fun centre(r: RectF) = PointF(r.centerX(), r.centerY())

    private fun nearest(all: List<Target>, p: PointF?): Target? =
        if (p == null) null else all.minByOrNull { Math.hypot((it.box.centerX() - p.x).toDouble(), (it.box.centerY() - p.y).toDouble()) }

    /** the nearest row above / below: the smallest vertical step, then the closest in x on that row */
    private fun vertical(all: List<Target>, here: Target?, dir: Int): Target? {
        val h = here ?: return all.first()
        val cands = all.filter { (it.box.centerY() - h.box.centerY()) * dir > ROW_SLOP }
        val step = cands.minOfOrNull { Math.abs(it.box.centerY() - h.box.centerY()) } ?: return null
        return cands.filter { Math.abs(it.box.centerY() - h.box.centerY()) <= step + ROW_SLOP }
            .minByOrNull { Math.abs(it.box.centerX() - h.box.centerX()) }
    }

    /** the nearest button left / right on the same row */
    private fun horizontal(all: List<Target>, here: Target?, dir: Int): Target? {
        val h = here ?: return all.first()
        return all.filter { Math.abs(it.box.centerY() - h.box.centerY()) <= h.box.height() / 2f &&
                (it.box.centerX() - h.box.centerX()) * dir > 1f }
            .minByOrNull { Math.abs(it.box.centerX() - h.box.centerX()) }
    }

    private fun moveTo(t: Target) {
        focus = centre(t.box)
        onFocus(t.section.view, t.local)
        // keep it in view, with a margin
        val y0 = scroll.scrollY; val vh = scroll.height
        if (t.box.top - MARGIN < y0) scroll.smoothScrollTo(0, (t.box.top - MARGIN).toInt().coerceAtLeast(0))
        else if (t.box.bottom + MARGIN > y0 + vh) scroll.smoothScrollTo(0, (t.box.bottom + MARGIN - vh).toInt())
        invalidate()
    }

    /**
     * THE CURSOR FOLLOWS A BAND (Top screen's tiles, 2026-11-16): the button the cursor goes to once the page is laid out again ([v]'s
     * box in its own coordinates; resolved in the next draw, when the parts above have their new heights), and the band brought into
     * view with it
     */
    private var anchor: Pair<View, RectF>? = null
    private var anchorBand: RectF? = null

    /** a tile's band opened or moved (a tap, or A): the cursor onto that tile when it is in the page, the tile and its band in view */
    fun follow(s: GameUiSection) = follow(s, s.selectionAnchor(), s.bandBox())

    /** a band opened in part [v]: its tile [a] and its band [band] (its own coordinates) */
    fun follow(v: View, a: RectF?, band: RectF?) {
        a ?: return
        anchor = v to a
        anchorBand = band
        invalidate()
    }

    /** a band of [v] opened (beautify's Controller tiles): the cursor onto [a] (its tile), the tile and [band] in view */
    fun follow(v: View, a: RectF?, band: RectF?) {
        anchor = v to (a ?: return)
        anchorBand = band
        invalidate()
    }

    /** the cursor LANDS on [v]'s box from [at] once the page is laid out again (a sub-page's first stop, or back on its tile): resolved
     *  at the next draw, as a band's anchor is */
    private var landing: Pair<View, () -> RectF?>? = null
    fun land(v: View, at: () -> RectF?) { landing = v to at; invalidate() }

    /** the anchor's place now: the cursor there (when it is in the page) and the tile, with its band when that fits, in view */
    private fun resolveAnchor() {
        landing?.let { (v, at) ->
            val top = contentTop(v)
            val r = if (top != null && v.height > 0) at() else null
            if (r != null) { landing = null; anchor = v to r; anchorBand = null }
            else if (top == null) landing = null
        }
        val (v, a) = anchor ?: return
        val top = contentTop(v) ?: run { anchor = null; return }
        anchor = null
        val box = RectF(a.left, a.top + top, a.right, a.bottom + top)
        if (focus != null) focus = centre(box)
        val band = anchorBand?.let { RectF(it.left, it.top + top, it.right, it.bottom + top) }
        anchorBand = null
        val want = GameUiTiles.reveal(scroll.scrollY.toFloat(), scroll.height.toFloat(), box.top - REVEAL_TOP,
            (band?.bottom ?: box.bottom) + REVEAL_BOTTOM) ?: return
        post { scroll.smoothScrollTo(0, want.toInt().coerceAtLeast(0)) }
    }

    /** A: exactly a touch at the button's centre (down, then up), so the section's own logic decides */
    private fun press(t: Target) {
        val x = t.local.centerX(); val y = t.local.centerY()
        val at = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(at, at, MotionEvent.ACTION_DOWN, x, y, 0)
        t.section.view.dispatchTouchEvent(down); down.recycle()
        val up = MotionEvent.obtain(at, at + 40, MotionEvent.ACTION_UP, x, y, 0)
        t.section.view.dispatchTouchEvent(up); up.recycle()
        // a bar that opened or closed moves the buttons: the cursor stays on the same button
        post { invalidate() }
    }

    /** tell the capture where B leads now (the Home button shows B only when it leads Home) */
    private fun syncB() {
        if (!held) return
        // B leads Home only from the tab row (or with no tab row and no bar open)
        val where = if (overlayOpen() || gameUis.any { it.hasSelection } || bands.any { it.hasSelection } || subPageOpen() ||
            (tabBar != null && !onTabs)) "back" else "home"
        if (where != bSaid && tap(GuestPad.bnext(padSource(), home = where == "home"))) bSaid = where
    }

    private fun tap(cells: List<Pair<String, Int>>?): Boolean {
        val pad = padSource()
        return cells != null && pad != null && TapBridge.taps(context, pad, cells) { }
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(c: Canvas) {
        resolveAnchor()
        if (!held || onTabs) return                 // on the tabs: the tab row draws its own cursor
        val f = focus ?: return
        val t = nearest(targets(), f) ?: return
        val r = RectF(t.box.left, t.box.top - scroll.scrollY, t.box.right, t.box.bottom - scroll.scrollY)
        UiKit.focus(c, r)
        // A presses the option under the cursor: its glyph on the cursor's top-left corner, half over it (the room's house mark)
        val gs = A_GLYPH
        UiKit.pad(c, UiKit.okGlyph(), r.left - 0.35f * gs + gs / 2f, r.top - 0.35f * gs + gs / 2f, gs)
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = false   // touches reach the sections under it

    companion object {
        const val ID = "settings"
        const val ROW_SLOP = 12f
        const val MARGIN = 40f
        const val A_GLYPH = 42f
        /** a tile and its band brought into view: the room kept above the tile (its A glyph) and under the band */
        const val REVEAL_TOP = 24f
        const val REVEAL_BOTTOM = 16f
    }
}
